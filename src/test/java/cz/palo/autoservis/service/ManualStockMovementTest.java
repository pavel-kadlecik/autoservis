package cz.palo.autoservis.service;

import cz.palo.autoservis.AbstractIntegrationTest;
import cz.palo.autoservis.mapper.WarehouseImportMapper;
import cz.palo.autoservis.mapper.WarehouseMapper;
import cz.palo.autoservis.model.domain.user.Role;
import cz.palo.autoservis.model.domain.user.User;
import cz.palo.autoservis.model.domain.warehouse.DocumentType;
import cz.palo.autoservis.model.domain.warehouse.GoodsReceipt;
import cz.palo.autoservis.model.domain.warehouse.GoodsReceiptItem;
import cz.palo.autoservis.model.domain.warehouse.MovementType;
import cz.palo.autoservis.model.domain.warehouse.Product;
import cz.palo.autoservis.model.domain.warehouse.ReceiptSource;
import cz.palo.autoservis.model.domain.warehouse.ReceiptStatus;
import cz.palo.autoservis.model.domain.warehouse.StockMovement;
import cz.palo.autoservis.model.domain.warehouse.Supplier;
import cz.palo.autoservis.security.model.domain.AppUserDetails;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * E2.1 (P-1): ruční záporné pohyby (ADJUSTMENT−, WRITE_OFF) přes
 * {@code POST /warehouse/products/{id}/movements}. Ověřuje, že stav skladu i zůstatek
 * šarže sníží DB trigger, a že validace/business pravidla vrací správné statusy.
 */
@AutoConfigureMockMvc
@Transactional
class ManualStockMovementTest extends AbstractIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private WarehouseImportMapper warehouseImportMapper;
    @Autowired private WarehouseMapper warehouseMapper;
    @Autowired private JdbcTemplate jdbc;

    private AppUserDetails admin() {
        return new AppUserDetails(User.builder()
                .id(1L).username("admin").passwordHash("n/a")
                .enabled(true).accountNonExpired(true).accountNonLocked(true)
                .credentialsNonExpired(true)
                .roles(List.of(Role.builder().name("ROLE_ADMIN").build()))
                .build());
    }

    /** Dodavatel + produkt + potvrzená příjemka + šarže + RECEIPT pohyb (stav = quantity). */
    private GoodsReceiptItem createBatchWithStock(String suffix, BigDecimal quantity) {
        Supplier supplier = Supplier.builder()
                .name("E2 dodavatel s.r.o.").registrationNumber("125" + suffix)
                .countryCode("CZ").active(true).build();
        warehouseImportMapper.insertSupplier(supplier);

        Product product = Product.builder()
                .sku("E2-SKU-" + suffix).name("Testovací díl E2").unit("ks").defaultVatRate(21).build();
        warehouseImportMapper.insertProduct(product);

        GoodsReceipt receipt = GoodsReceipt.builder()
                .supplierId(supplier.getId()).supplierNameSnapshot(supplier.getName())
                .invoiceNumber("E2-FAK-" + suffix)
                .subtotal(new BigDecimal("100.00")).vatAmount(new BigDecimal("21.00"))
                .totalAmount(new BigDecimal("121.00")).currency("CZK")
                .documentType(DocumentType.INVOICE).sourceChannel(ReceiptSource.AI_PDF)
                .status(ReceiptStatus.CONFIRMED).reconciliationOk(true).createdBy(1L).build();
        warehouseImportMapper.insertReceipt(receipt);

        GoodsReceiptItem batch = GoodsReceiptItem.builder()
                .goodsReceiptId(receipt.getId()).productId(product.getId()).position(1)
                .nameSnapshot(product.getName())
                .quantityReceived(quantity).quantityRemaining(quantity)
                .unitPriceExclVat(new BigDecimal("100.00")).vatRate(21)
                .totalInclVat(new BigDecimal("121.00")).build();
        warehouseImportMapper.insertReceiptItem(batch);

        warehouseImportMapper.insertMovement(StockMovement.builder()
                .productId(product.getId()).batchId(batch.getId())
                .movementType(MovementType.RECEIPT).quantity(quantity).createdBy(1L).build());

        return batch;
    }

    private BigDecimal onHand(Long productId) {
        return jdbc.queryForObject(
                "SELECT quantity_on_hand FROM warehouse.products WHERE id = ?", BigDecimal.class, productId);
    }

    private BigDecimal remaining(Long batchId) {
        return jdbc.queryForObject(
                "SELECT quantity_remaining FROM warehouse.goods_receipt_items WHERE id = ?", BigDecimal.class, batchId);
    }

    private String url(Long productId) {
        return "/api/v1/warehouse/products/" + productId + "/movements";
    }

    @Test
    @DisplayName("ADJUSTMENT− sníží stav skladu i zůstatek šarže (trigger)")
    void adjustmentDecrementsStockAndBatch() throws Exception {
        GoodsReceiptItem batch = createBatchWithStock("001", new BigDecimal("4"));
        Long productId = batch.getProductId();

        mockMvc.perform(post(url(productId))
                        .contentType(APPLICATION_JSON)
                        .content("{\"movementType\":\"ADJUSTMENT\",\"batchId\":" + batch.getId()
                                + ",\"quantity\":1.5,\"note\":\"inventurní manko\"}")
                        .with(user(admin())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quantityOnHand").value(2.5))
                // pohyb je vidět v historii produktu vč. poznámky (findMovementsByProductId);
                // movements[] je řazeno nejnovější první → ADJUSTMENT je [0], RECEIPT [1]
                .andExpect(jsonPath("$.movements[0].movementType").value("ADJUSTMENT"))
                .andExpect(jsonPath("$.movements[0].note").value("inventurní manko"));

        assertThat(onHand(productId)).isEqualByComparingTo("2.5");
        assertThat(remaining(batch.getId())).isEqualByComparingTo("2.5");
    }

    @Test
    @DisplayName("WRITE_OFF přes zbytek šarže → 422 QUANTITY_EXCEEDS_REMAINING, nic se nezmění")
    void writeOffExceedingRemainingFails() throws Exception {
        GoodsReceiptItem batch = createBatchWithStock("002", new BigDecimal("3"));

        mockMvc.perform(post(url(batch.getProductId()))
                        .contentType(APPLICATION_JSON)
                        .content("{\"movementType\":\"WRITE_OFF\",\"batchId\":" + batch.getId()
                                + ",\"quantity\":5,\"note\":\"odpis poškozeného\"}")
                        .with(user(admin())))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].code").value("QUANTITY_EXCEEDS_REMAINING"));

        assertThat(onHand(batch.getProductId())).isEqualByComparingTo("3");
        assertThat(remaining(batch.getId())).isEqualByComparingTo("3");
    }

    @Test
    @DisplayName("šarže jiného produktu → 422 BATCH_PRODUCT_MISMATCH")
    void batchOfAnotherProductFails() throws Exception {
        GoodsReceiptItem batchA = createBatchWithStock("003", new BigDecimal("4"));
        GoodsReceiptItem batchB = createBatchWithStock("004", new BigDecimal("4"));

        // produkt A, ale šarže B
        mockMvc.perform(post(url(batchA.getProductId()))
                        .contentType(APPLICATION_JSON)
                        .content("{\"movementType\":\"ADJUSTMENT\",\"batchId\":" + batchB.getId()
                                + ",\"quantity\":1,\"note\":\"špatná šarže\"}")
                        .with(user(admin())))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].code").value("BATCH_PRODUCT_MISMATCH"));
    }

    @Test
    @DisplayName("nepovolený typ pohybu (RECEIPT) → 400")
    void disallowedTypeRejected() throws Exception {
        GoodsReceiptItem batch = createBatchWithStock("005", new BigDecimal("4"));

        mockMvc.perform(post(url(batch.getProductId()))
                        .contentType(APPLICATION_JSON)
                        .content("{\"movementType\":\"RECEIPT\",\"batchId\":" + batch.getId()
                                + ",\"quantity\":1,\"note\":\"pokus o příjem\"}")
                        .with(user(admin())))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("RETURN: vratka sníží sklad a uloží důvod i číslo dobropisu")
    void returnToSupplierRecordsReasonAndCreditNote() throws Exception {
        GoodsReceiptItem batch = createBatchWithStock("007", new BigDecimal("5"));
        Long productId = batch.getProductId();

        mockMvc.perform(post(url(productId))
                        .contentType(APPLICATION_JSON)
                        .content("{\"movementType\":\"RETURN\",\"batchId\":" + batch.getId()
                                + ",\"quantity\":2,\"returnReason\":\"DEFECTIVE\""
                                + ",\"creditNoteNumber\":\"DOB-2026-1\""
                                + ",\"note\":\"vadný díl vrácen dodavateli\"}")
                        .with(user(admin())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quantityOnHand").value(3))
                .andExpect(jsonPath("$.movements[0].movementType").value("RETURN"))
                .andExpect(jsonPath("$.movements[0].returnReason").value("DEFECTIVE"))
                .andExpect(jsonPath("$.movements[0].creditNoteNumber").value("DOB-2026-1"));

        assertThat(onHand(productId)).isEqualByComparingTo("3");
        assertThat(remaining(batch.getId())).isEqualByComparingTo("3");
    }

    @Test
    @DisplayName("RETURN bez důvodu → 400 (zrcadlí DB CHECK chk_return_reason)")
    void returnWithoutReasonRejected() throws Exception {
        GoodsReceiptItem batch = createBatchWithStock("008", new BigDecimal("4"));

        mockMvc.perform(post(url(batch.getProductId()))
                        .contentType(APPLICATION_JSON)
                        .content("{\"movementType\":\"RETURN\",\"batchId\":" + batch.getId()
                                + ",\"quantity\":1,\"note\":\"vratka bez důvodu\"}")
                        .with(user(admin())))
                .andExpect(status().isBadRequest());

        assertThat(onHand(batch.getProductId())).isEqualByComparingTo("4");
    }

    @Test
    @DisplayName("důvod vratky u odpisu → 400; číslo dobropisu u korekce → 400")
    void reasonAndCreditNoteOnlyForReturn() throws Exception {
        GoodsReceiptItem batch = createBatchWithStock("009", new BigDecimal("4"));

        mockMvc.perform(post(url(batch.getProductId()))
                        .contentType(APPLICATION_JSON)
                        .content("{\"movementType\":\"WRITE_OFF\",\"batchId\":" + batch.getId()
                                + ",\"quantity\":1,\"returnReason\":\"DEFECTIVE\",\"note\":\"odpis\"}")
                        .with(user(admin())))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post(url(batch.getProductId()))
                        .contentType(APPLICATION_JSON)
                        .content("{\"movementType\":\"ADJUSTMENT\",\"batchId\":" + batch.getId()
                                + ",\"quantity\":1,\"creditNoteNumber\":\"DOB-X\",\"note\":\"korekce\"}")
                        .with(user(admin())))
                .andExpect(status().isBadRequest());

        assertThat(onHand(batch.getProductId())).isEqualByComparingTo("4");
    }

    @Test
    @DisplayName("RETURN přes zbytek šarže → 422 QUANTITY_EXCEEDS_REMAINING")
    void returnExceedingRemainingFails() throws Exception {
        GoodsReceiptItem batch = createBatchWithStock("010", new BigDecimal("2"));

        mockMvc.perform(post(url(batch.getProductId()))
                        .contentType(APPLICATION_JSON)
                        .content("{\"movementType\":\"RETURN\",\"batchId\":" + batch.getId()
                                + ",\"quantity\":3,\"returnReason\":\"WRONG_PART\",\"note\":\"vratka\"}")
                        .with(user(admin())))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].code").value("QUANTITY_EXCEEDS_REMAINING"));

        assertThat(onHand(batch.getProductId())).isEqualByComparingTo("2");
    }

    @Test
    @DisplayName("ISSUE bez zakázky: spotřeba sníží stav a v historii nemá zakázku")
    void internalConsumptionWithoutOrder() throws Exception {
        GoodsReceiptItem batch = createBatchWithStock("011", new BigDecimal("6"));
        Long productId = batch.getProductId();

        mockMvc.perform(post(url(productId))
                        .contentType(APPLICATION_JSON)
                        .content("{\"movementType\":\"ISSUE\",\"batchId\":" + batch.getId()
                                + ",\"quantity\":2,\"note\":\"čistivo do dílny\"}")
                        .with(user(admin())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quantityOnHand").value(4))
                .andExpect(jsonPath("$.movements[0].movementType").value("ISSUE"))
                .andExpect(jsonPath("$.movements[0].orderNumber").doesNotExist());

        assertThat(onHand(productId)).isEqualByComparingTo("4");
        assertThat(remaining(batch.getId())).isEqualByComparingTo("4");

        Long orderId = jdbc.queryForObject(
                "SELECT order_id FROM warehouse.stock_movements"
                        + " WHERE batch_id = ? AND movement_type = 'ISSUE'", Long.class, batch.getId());
        assertThat(orderId).isNull();
    }

    @Test
    @DisplayName("chybějící poznámka → 400")
    void missingNoteRejected() throws Exception {
        GoodsReceiptItem batch = createBatchWithStock("006", new BigDecimal("4"));

        mockMvc.perform(post(url(batch.getProductId()))
                        .contentType(APPLICATION_JSON)
                        .content("{\"movementType\":\"WRITE_OFF\",\"batchId\":" + batch.getId()
                                + ",\"quantity\":1}")
                        .with(user(admin())))
                .andExpect(status().isBadRequest());
    }
}
