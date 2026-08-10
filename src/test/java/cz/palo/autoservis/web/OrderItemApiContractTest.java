package cz.palo.autoservis.web;

import cz.palo.autoservis.AbstractIntegrationTest;
import cz.palo.autoservis.mapper.GoodsReceiptMapper;
import cz.palo.autoservis.mapper.OrderMapper;
import cz.palo.autoservis.mapper.WarehouseImportMapper;
import cz.palo.autoservis.model.domain.order.Order;
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
import cz.palo.autoservis.model.dto.warehouse.GoodsReceiptItemDto;
import cz.palo.autoservis.security.model.domain.AppUserDetails;
import cz.palo.autoservis.service.OrderItemService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import org.springframework.http.MediaType;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP kontrakt výdeje materiálu zakázky — {@code POST /orders/{orderId}/issue-stock} (V83).
 *
 * <p><strong>Proč přes MockMvc a ne přes službu:</strong> pravidla samotného výdeje pokrývá
 * {@code OrderItemImportTest} a {@code OrderStatusTransitionTest}. Tady jde o to, co služba
 * neuvidí — tvar odpovědi ({@code issuedItems}), převod {@code BusinessRuleException} na HTTP
 * 422 a to, že endpoint zvládne i opakované zavolání.
 */
@AutoConfigureMockMvc
@Transactional
class OrderItemApiContractTest extends AbstractIntegrationTest {

    private static final long CUSTOMER_ID = 1L;
    private static final long VEHICLE_ID = 1L;
    private static final long USER_ID = 1L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private OrderItemService orderItemService;

    @Autowired
    private WarehouseImportMapper warehouseImportMapper;

    @Autowired
    private GoodsReceiptMapper goodsReceiptMapper;

    private Long orderId;
    private Long batchId;
    private Long productId;

    @BeforeEach
    void createOrderWithReservedMaterial() {
        Order order = Order.builder()
                .receivedAt(LocalDate.now())
                .customerId(CUSTOMER_ID).vehicleId(VEHICLE_ID)
                .description("Kontrakt výdeje materiálu")
                .createdBy(USER_ID)
                .build();
        orderMapper.insert(order);
        orderId = order.getId();

        createConfirmedBatch();
        // Import materiál jen REZERVUJE — díl leží dál v regálu (V83).
        orderItemService.importFromReceipt(
                orderId, List.of(importRequest(batchId, "4")), USER_ID);
    }

    @Test
    @DisplayName("POST /issue-stock vydá rezervovaný materiál a vrátí počet položek")
    void issueStock_returnsIssuedItemCount() throws Exception {
        assertThat(remaining()).as("před výdejem se šarže nehnula").isEqualByComparingTo("4");

        mockMvc.perform(post("/api/v1/orders/{orderId}/issue-stock", orderId)
                        .with(user(principal())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.issuedItems").value(1));

        assertThat(remaining()).as("teprve výdej sníží zůstatek šarže").isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("opakovaný POST /issue-stock nic nezdvojí a vrátí nulu")
    void issueStock_secondCall_returnsZero() throws Exception {
        mockMvc.perform(post("/api/v1/orders/{orderId}/issue-stock", orderId)
                        .with(user(principal())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.issuedItems").value(1));

        mockMvc.perform(post("/api/v1/orders/{orderId}/issue-stock", orderId)
                        .with(user(principal())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.issuedItems").value(0));

        assertThat(remaining()).as("druhé volání šarži znovu nesnížilo").isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("neexistující zakázka → 404 na všech cestách, které berou orderId")
    void unknownOrder_isNotFound() throws Exception {
        // Služba dřív nerozlišovala prázdnou zakázku od neexistující: čtení vracelo 200 s
        // prázdným výsledkem a výdej dokonce 200 {"issuedItems": 0}, tedy „hotovo, nebylo co
        // vydat". Překlep v URL nebo práce nad mezitím smazanou zakázkou prošly tiše.
        long unknown = 999_999L;

        mockMvc.perform(get("/api/v1/orders/{orderId}/items", unknown).with(user(principal())))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/v1/orders/{orderId}/items/summary", unknown).with(user(principal())))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/api/v1/orders/{orderId}/issue-stock", unknown).with(user(principal())))
                .andExpect(status().isNotFound());

        // Zápis padal až na cizím klíči, takže obsluze vyšlo 422 „Zadaná data porušují
        // databázové omezení" — chyba integrity místo české hlášky.
        mockMvc.perform(post("/api/v1/orders/{orderId}/items", unknown)
                        .with(user(principal()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"itemType":"LABOR","name":"Práce","quantity":1,"unit":"hod",
                                 "unitPrice":500,"vatRate":21,"position":1}
                                """))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("chybí-li rezervovaný díl na skladě → 422 STOCK_MISSING_FOR_ISSUE s výčtem")
    void issueStock_whenPartIsGone_returns422() throws Exception {
        // Rezervace šarži nezamyká, takže ji mezitím může vyprázdnit inventurní odpis.
        warehouseImportMapper.insertMovement(StockMovement.builder()
                .productId(productId).batchId(batchId)
                .movementType(MovementType.WRITE_OFF).quantity(new BigDecimal("-4"))
                .note("Inventurní odpis").createdBy(USER_ID).build());

        mockMvc.perform(post("/api/v1/orders/{orderId}/issue-stock", orderId)
                        .with(user(principal())))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].code").value("STOCK_MISSING_FOR_ISSUE"))
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.containsString("Testovací díl kontrakt")));
    }

    // =========================================================================
    // Pomocníci
    // =========================================================================

    private BigDecimal remaining() {
        return goodsReceiptMapper.findById(batchId).orElseThrow().getQuantityRemaining();
    }

    private GoodsReceiptItemDto.ImportRequest importRequest(Long batch, String quantity) {
        GoodsReceiptItemDto.ImportRequest request = new GoodsReceiptItemDto.ImportRequest();
        request.setGoodsReceiptItemId(batch);
        request.setQuantity(new BigDecimal(quantity));
        return request;
    }

    /** Potvrzená příjemka se čtyřmi kusy na skladě — zdroj rezervace i výdeje. */
    private void createConfirmedBatch() {
        Supplier supplier = Supplier.builder()
                .name("Kontrakt test dodavatel s.r.o.").registrationNumber("55667788")
                .countryCode("CZ").active(true).build();
        warehouseImportMapper.insertSupplier(supplier);

        Product product = Product.builder()
                .sku("KONTRAKT-TEST-SKU").name("Testovací díl kontrakt")
                .unit("ks").defaultVatRate(21).build();
        warehouseImportMapper.insertProduct(product);
        productId = product.getId();

        GoodsReceipt receipt = GoodsReceipt.builder()
                .supplierId(supplier.getId()).supplierNameSnapshot(supplier.getName())
                .invoiceNumber("KONTRAKT-FAK-001")
                .subtotal(new BigDecimal("400.00")).vatAmount(new BigDecimal("84.00"))
                .totalAmount(new BigDecimal("484.00")).currency("CZK")
                .documentType(DocumentType.INVOICE).sourceChannel(ReceiptSource.MANUAL)
                .status(ReceiptStatus.CONFIRMED).reconciliationOk(true)
                .createdBy(USER_ID).build();
        warehouseImportMapper.insertReceipt(receipt);

        GoodsReceiptItem batch = GoodsReceiptItem.builder()
                .goodsReceiptId(receipt.getId()).productId(product.getId()).position(1)
                .nameSnapshot(product.getName())
                .quantityReceived(new BigDecimal("4")).quantityRemaining(new BigDecimal("4"))
                .unitPriceExclVat(new BigDecimal("100.00")).vatRate(21)
                .totalInclVat(new BigDecimal("484.00")).build();
        warehouseImportMapper.insertReceiptItem(batch);
        batchId = batch.getId();

        // Naskladnění, ať quantity_on_hand odpovídá a výdej nespadne na chk_products_qty.
        warehouseImportMapper.insertMovement(StockMovement.builder()
                .productId(product.getId()).batchId(batch.getId())
                .movementType(MovementType.RECEIPT).quantity(new BigDecimal("4"))
                .createdBy(USER_ID).build());
    }

    private static AppUserDetails principal() {
        return new AppUserDetails(User.builder()
                .id(USER_ID).username("admin").passwordHash("n/a")
                .enabled(true).accountNonExpired(true).accountNonLocked(true)
                .credentialsNonExpired(true)
                .roles(List.of(Role.builder().name("ROLE_MECHANIC").build()))
                .build());
    }
}
