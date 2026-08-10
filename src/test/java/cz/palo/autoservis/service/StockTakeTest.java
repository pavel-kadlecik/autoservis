package cz.palo.autoservis.service;

import cz.palo.autoservis.AbstractIntegrationTest;
import cz.palo.autoservis.mapper.WarehouseImportMapper;
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
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * E6 (P-5, R-H): inventura — soupis, zápis a uzavření generující korekce.
 *
 * <p>Klíčové vlastnosti: manko se rozpouští po šaržích FIFO, přebytek vzniká
 * šarží v pseudo-příjemce STOCK_TAKE, rozdíl se počítá proti aktuálnímu stavu
 * a nevyplněný řádek neznamená nulu.
 */
@AutoConfigureMockMvc
@Transactional
class StockTakeTest extends AbstractIntegrationTest {

    private static final String URL = "/api/v1/warehouse/stock-takes";

    @Autowired private MockMvc mockMvc;
    @Autowired private WarehouseImportMapper warehouseImportMapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private tools.jackson.databind.ObjectMapper objectMapper;

    private Supplier supplier;

    private AppUserDetails admin() {
        return new AppUserDetails(User.builder()
                .id(1L).username("admin").passwordHash("n/a")
                .enabled(true).accountNonExpired(true).accountNonLocked(true)
                .credentialsNonExpired(true)
                .roles(List.of(Role.builder().name("ROLE_ADMIN").build()))
                .build());
    }

    private Supplier supplier() {
        if (supplier == null) {
            supplier = Supplier.builder().name("E6 dodavatel").registrationNumber("12600111")
                    .countryCode("CZ").active(true).build();
            warehouseImportMapper.insertSupplier(supplier);
        }
        return supplier;
    }

    /** Deaktivuje seed produkty, ať soupis obsahuje jen díly tohoto testu. */
    private void isolateFromSeedProducts() {
        jdbc.update("UPDATE warehouse.products SET is_active = FALSE");
    }

    private Product product(String sku) {
        Product product = Product.builder()
                .sku(sku).name("E6 " + sku).unit("ks").defaultVatRate(21).build();
        warehouseImportMapper.insertProduct(product);
        return product;
    }

    /** Přijme šarži s daným datem dokladu (kvůli FIFO pořadí). */
    private GoodsReceiptItem receive(Product product, String docNo, LocalDate issueDate,
                                     BigDecimal quantity, BigDecimal unitPrice) {
        GoodsReceipt receipt = GoodsReceipt.builder()
                .supplierId(supplier().getId()).supplierNameSnapshot(supplier().getName())
                .invoiceNumber(docNo).issueDate(issueDate)
                .subtotal(new BigDecimal("100.00")).vatAmount(new BigDecimal("21.00"))
                .totalAmount(new BigDecimal("121.00")).currency("CZK")
                .documentType(DocumentType.INVOICE).sourceChannel(ReceiptSource.AI_PDF)
                .status(ReceiptStatus.CONFIRMED).reconciliationOk(true).createdBy(1L).build();
        warehouseImportMapper.insertReceipt(receipt);

        GoodsReceiptItem batch = GoodsReceiptItem.builder()
                .goodsReceiptId(receipt.getId()).productId(product.getId()).position(1)
                .nameSnapshot(product.getName())
                .quantityReceived(quantity).quantityRemaining(quantity)
                .unitPriceExclVat(unitPrice).vatRate(21)
                .totalInclVat(quantity.multiply(unitPrice)).build();
        warehouseImportMapper.insertReceiptItem(batch);

        warehouseImportMapper.insertMovement(StockMovement.builder()
                .productId(product.getId()).batchId(batch.getId())
                .movementType(MovementType.RECEIPT).quantity(quantity).createdBy(1L).build());
        return batch;
    }

    private long openStockTake() throws Exception {
        String json = mockMvc.perform(post(URL)
                        .contentType(APPLICATION_JSON).content("{\"note\":\"test\"}")
                        .with(user(admin())))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) com.jayway.jsonpath.JsonPath.read(json, "$.id")).longValue();
    }

    /** Najde ID řádku soupisu podle SKU. */
    private long itemIdOf(long stockTakeId, String sku) throws Exception {
        String json = mockMvc.perform(get(URL + "/" + stockTakeId).with(user(admin())))
                .andReturn().getResponse().getContentAsString();
        var node = objectMapper.readTree(json).get("items");
        for (var item : node) {
            if (sku.equals(item.get("sku").asText())) {
                return item.get("id").asLong();
            }
        }
        throw new AssertionError("Řádek pro SKU " + sku + " v soupisu není");
    }

    private void writeCount(long stockTakeId, long itemId, String counted, String price) throws Exception {
        String priceJson = price == null ? "null" : price;
        mockMvc.perform(put(URL + "/" + stockTakeId + "/items")
                        .contentType(APPLICATION_JSON)
                        .content("{\"items\":[{\"id\":" + itemId + ",\"countedQuantity\":" + counted
                                + ",\"surplusUnitPrice\":" + priceJson + "}]}")
                        .with(user(admin())))
                .andExpect(status().isOk());
    }

    private BigDecimal onHand(Long productId) {
        return jdbc.queryForObject(
                "SELECT quantity_on_hand FROM warehouse.products WHERE id = ?", BigDecimal.class, productId);
    }

    private BigDecimal remaining(Long batchId) {
        return jdbc.queryForObject(
                "SELECT quantity_remaining FROM warehouse.goods_receipt_items WHERE id = ?",
                BigDecimal.class, batchId);
    }

    /** Zmrazená hodnota ze sloupce `closed_*` pro daný díl (V65, KN-2). */
    private BigDecimal closedColumn(long stockTakeId, String sku, String column) {
        return jdbc.queryForObject(
                "SELECT sti." + column + " FROM warehouse.stock_take_items sti "
                        + "JOIN warehouse.products p ON p.id = sti.product_id "
                        + "WHERE sti.stock_take_id = ? AND p.sku = ?",
                BigDecimal.class, stockTakeId, sku);
    }

    // ------------------------------------------------------------------ testy

    @Test
    @DisplayName("uzavřená inventura doloží zjištěné rozdíly, ne nuly (KN-2)")
    void closedStockTake_documentsDiscoveredDifferences() throws Exception {
        isolateFromSeedProducts();
        Product shortage = product("E6-DOC-MANKO");
        receive(shortage, "E6-D1", LocalDate.of(2026, 2, 1),
                new BigDecimal("10"), new BigDecimal("100.00"));
        Product surplus = product("E6-DOC-PREBYTEK");
        receive(surplus, "E6-D2", LocalDate.of(2026, 2, 2),
                new BigDecimal("2"), new BigDecimal("50.00"));

        long id = openStockTake();
        writeCount(id, itemIdOf(id, "E6-DOC-MANKO"), "7", null);        // 10 → 7, manko −3
        writeCount(id, itemIdOf(id, "E6-DOC-PREBYTEK"), "5", "60.00");  // 2 → 5, přebytek +3

        mockMvc.perform(post(URL + "/" + id + "/close").with(user(admin())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.shortageLines").value(1))
                .andExpect(jsonPath("$.surplusLines").value(1));

        // Jádro nálezu: korekce srovnaly stav na napočítané množství, takže živý výpočet
        // rozdílu teď dává 0. Doklad ale musí zjištěné rozdíly doložit i po znovunačtení —
        // dřív tady banner hlásil „0 mank a 0 přebytků" u inventury, která je právě zaúčtovala.
        mockMvc.perform(get(URL + "/" + id).with(user(admin())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shortageLines").value(1))
                .andExpect(jsonPath("$.surplusLines").value(1));

        assertThat(closedColumn(id, "E6-DOC-MANKO", "closed_difference")).isEqualByComparingTo("-3");
        assertThat(closedColumn(id, "E6-DOC-PREBYTEK", "closed_difference")).isEqualByComparingTo("3");
        assertThat(closedColumn(id, "E6-DOC-MANKO", "closed_expected_quantity"))
                .as("stav, proti kterému se rozdíl počítal — ne stav po korekci")
                .isEqualByComparingTo("10");

        // korekce samotné proběhly beze změny chování
        assertThat(onHand(shortage.getId())).isEqualByComparingTo("7");
        assertThat(onHand(surplus.getId())).isEqualByComparingTo("5");
    }

    @Test
    @DisplayName("nepočítaný řádek nedostane zmrazený rozdíl — „nepočítáno\" není doložená nula (KN-2)")
    void uncountedLine_isNotFrozenAsZeroDifference() throws Exception {
        isolateFromSeedProducts();
        Product counted = product("E6-FROZEN-YES");
        receive(counted, "E6-F1", LocalDate.of(2026, 2, 3),
                new BigDecimal("4"), new BigDecimal("10.00"));
        Product untouched = product("E6-FROZEN-NO");
        receive(untouched, "E6-F2", LocalDate.of(2026, 2, 4),
                new BigDecimal("4"), new BigDecimal("10.00"));

        long id = openStockTake();
        writeCount(id, itemIdOf(id, "E6-FROZEN-YES"), "3", null);   // jen tenhle se počítá

        mockMvc.perform(post(URL + "/" + id + "/close").with(user(admin())))
                .andExpect(status().isOk());

        assertThat(closedColumn(id, "E6-FROZEN-YES", "closed_difference")).isEqualByComparingTo("-1");
        assertThat(closedColumn(id, "E6-FROZEN-NO", "closed_difference"))
                .as("nepočítaný řádek zůstává NULL, jinak by z něj vznikl doložený nulový rozdíl")
                .isNull();
    }

    @Test
    @DisplayName("manko se rozpustí po šaržích od nejstarší (FIFO)")
    void shortageConsumesOldestBatchesFirst() throws Exception {
        isolateFromSeedProducts();
        Product product = product("E6-FIFO");
        GoodsReceiptItem older = receive(product, "E6-1", LocalDate.of(2026, 1, 1),
                new BigDecimal("4"), new BigDecimal("100.00"));
        GoodsReceiptItem newer = receive(product, "E6-2", LocalDate.of(2026, 6, 1),
                new BigDecimal("6"), new BigDecimal("150.00"));

        long id = openStockTake();
        // skladem 10, napočítáno 4 → manko 6: celá starší šarže (4) + 2 z novější
        writeCount(id, itemIdOf(id, "E6-FIFO"), "4", null);

        mockMvc.perform(post(URL + "/" + id + "/close").with(user(admin())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"));

        assertThat(onHand(product.getId())).isEqualByComparingTo("4");
        assertThat(remaining(older.getId())).isEqualByComparingTo("0");
        assertThat(remaining(newer.getId())).isEqualByComparingTo("4");
    }

    @Test
    @DisplayName("přebytek založí šarži v pseudo-příjemce STOCK_TAKE a zvedne stav")
    void surplusCreatesStockTakeReceipt() throws Exception {
        isolateFromSeedProducts();
        Product product = product("E6-SURPLUS");
        receive(product, "E6-3", LocalDate.of(2026, 3, 1),
                new BigDecimal("2"), new BigDecimal("200.00"));

        long id = openStockTake();
        // skladem 2, napočítáno 5 → přebytek 3 za 250 Kč
        writeCount(id, itemIdOf(id, "E6-SURPLUS"), "5", "250.00");

        mockMvc.perform(post(URL + "/" + id + "/close").with(user(admin())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.surplusReceiptId").isNumber());

        assertThat(onHand(product.getId())).isEqualByComparingTo("5");

        Long receiptId = jdbc.queryForObject(
                "SELECT surplus_receipt_id FROM warehouse.stock_takes WHERE id = ?", Long.class, id);
        String docType = jdbc.queryForObject(
                "SELECT document_type::text FROM warehouse.goods_receipts WHERE id = ?", String.class, receiptId);
        assertThat(docType).isEqualTo("STOCK_TAKE");

        // Číslo přebytkové příjemky = číslo inventury (sjednoceno, žádné oddělené „INV-{id}").
        String receiptNumber = jdbc.queryForObject(
                "SELECT invoice_number FROM warehouse.goods_receipts WHERE id = ?", String.class, receiptId);
        String stockTakeNumber = jdbc.queryForObject(
                "SELECT stock_take_number FROM warehouse.stock_takes WHERE id = ?", String.class, id);
        assertThat(receiptNumber).isEqualTo(stockTakeNumber).matches("INV-\\d{4}-\\d{4}");

        // šarže přebytku má cenu a zůstatek dorovnaný triggerem přes ADJUSTMENT
        BigDecimal surplusRemaining = jdbc.queryForObject(
                "SELECT quantity_remaining FROM warehouse.goods_receipt_items WHERE goods_receipt_id = ?",
                BigDecimal.class, receiptId);
        assertThat(surplusRemaining).isEqualByComparingTo("3");
        BigDecimal price = jdbc.queryForObject(
                "SELECT unit_price_excl_vat FROM warehouse.goods_receipt_items WHERE goods_receipt_id = ?",
                BigDecimal.class, receiptId);
        assertThat(price).isEqualByComparingTo("250.00");

        // Hodnota přebytku = 3 × 250 = 750, BEZ DPH (nalezené zboží není nákup — ČÚS 007).
        BigDecimal total = jdbc.queryForObject(
                "SELECT total_amount FROM warehouse.goods_receipts WHERE id = ?", BigDecimal.class, receiptId);
        assertThat(total).as("hodnota přebytku bez DPH").isEqualByComparingTo("750.00");
        BigDecimal vatAmount = jdbc.queryForObject(
                "SELECT vat_amount FROM warehouse.goods_receipts WHERE id = ?", BigDecimal.class, receiptId);
        assertThat(vatAmount).as("přebytek nemá DPH v hlavičce").isEqualByComparingTo("0.00");
        Integer lineVat = jdbc.queryForObject(
                "SELECT vat_rate FROM warehouse.goods_receipt_items WHERE goods_receipt_id = ?",
                Integer.class, receiptId);
        assertThat(lineVat).as("řádek přebytku má vat_rate = 0").isZero();
    }

    @Test
    @DisplayName("nevyplněný řádek negeneruje korekci (nepočítáno ≠ nula)")
    void uncountedLineGeneratesNothing() throws Exception {
        isolateFromSeedProducts();
        Product product = product("E6-SKIP");
        receive(product, "E6-4", LocalDate.of(2026, 2, 1),
                new BigDecimal("7"), new BigDecimal("50.00"));

        long id = openStockTake();
        long movementsBefore = jdbc.queryForObject(
                "SELECT COUNT(*) FROM warehouse.stock_movements", Long.class);

        mockMvc.perform(post(URL + "/" + id + "/close").with(user(admin())))
                .andExpect(status().isOk());

        assertThat(onHand(product.getId())).isEqualByComparingTo("7");
        Long movementsAfter = jdbc.queryForObject(
                "SELECT COUNT(*) FROM warehouse.stock_movements", Long.class);
        assertThat(movementsAfter).isEqualTo(movementsBefore);
    }

    @Test
    @DisplayName("manko větší než zůstatky šarží → 422 STOCK_TAKE_SHORTAGE_EXCEEDS_BATCHES")
    void shortageBeyondBatchesFails() throws Exception {
        isolateFromSeedProducts();
        Product product = product("E6-NOBATCH");
        GoodsReceiptItem batch = receive(product, "E6-5", LocalDate.of(2026, 4, 1),
                new BigDecimal("3"), new BigDecimal("80.00"));
        // celý zůstatek pryč výdejem, stav ale zůstane 3 → uměle rozejitý stav
        jdbc.update("UPDATE warehouse.goods_receipt_items SET quantity_remaining = 0 WHERE id = ?",
                batch.getId());

        long id = openStockTake();
        writeCount(id, itemIdOf(id, "E6-NOBATCH"), "0", null);

        mockMvc.perform(post(URL + "/" + id + "/close").with(user(admin())))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].code").value("STOCK_TAKE_SHORTAGE_EXCEEDS_BATCHES"));
    }

    @Test
    @DisplayName("přebytek bez ceny → 422 STOCK_TAKE_PRICE_MISSING a nic se nezmění")
    void surplusWithoutPriceFails() throws Exception {
        isolateFromSeedProducts();
        Product product = product("E6-NOPRICE");   // bez šarže → cena se nepředvyplní

        long id = openStockTake();
        writeCount(id, itemIdOf(id, "E6-NOPRICE"), "2", null);

        mockMvc.perform(post(URL + "/" + id + "/close").with(user(admin())))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].code").value("STOCK_TAKE_PRICE_MISSING"));

        assertThat(onHand(product.getId())).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("druhá otevřená inventura → 409; dvojí uzavření → 422")
    void onlyOneOpenAndSingleClose() throws Exception {
        isolateFromSeedProducts();
        long id = openStockTake();

        mockMvc.perform(post(URL).contentType(APPLICATION_JSON).content("{}").with(user(admin())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors[0].code").value("STOCK_TAKE_ALREADY_OPEN"));

        mockMvc.perform(post(URL + "/" + id + "/close").with(user(admin())))
                .andExpect(status().isOk());
        mockMvc.perform(post(URL + "/" + id + "/close").with(user(admin())))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].code").value("STOCK_TAKE_NOT_EDITABLE"));
    }

    @Test
    @DisplayName("rozdíl se počítá proti aktuálnímu stavu, ne proti snapshotu z otevření")
    void differenceUsesCurrentStockNotSnapshot() throws Exception {
        isolateFromSeedProducts();
        Product product = product("E6-MOVED");
        GoodsReceiptItem batch = receive(product, "E6-6", LocalDate.of(2026, 5, 1),
                new BigDecimal("10"), new BigDecimal("60.00"));

        long id = openStockTake();   // snapshot = 10

        // mezi otevřením a uzavřením se vydaly 3 ks (stav 7)
        warehouseImportMapper.insertMovement(StockMovement.builder()
                .productId(product.getId()).batchId(batch.getId())
                .movementType(MovementType.ISSUE).quantity(new BigDecimal("-3")).createdBy(1L).build());

        // fyzicky napočítáno 7 → proti aktuálnímu stavu je rozdíl 0, žádná korekce
        writeCount(id, itemIdOf(id, "E6-MOVED"), "7", null);

        mockMvc.perform(post(URL + "/" + id + "/close").with(user(admin())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shortageLines").value(0))
                .andExpect(jsonPath("$.surplusLines").value(0));

        assertThat(onHand(product.getId())).isEqualByComparingTo("7");
    }

    @Test
    @DisplayName("zrušená inventura nic nemění a zápis do ní → 422")
    void cancelledStockTakeIsInert() throws Exception {
        isolateFromSeedProducts();
        Product product = product("E6-CANCEL");
        receive(product, "E6-7", LocalDate.of(2026, 6, 15),
                new BigDecimal("5"), new BigDecimal("90.00"));

        long id = openStockTake();
        long itemId = itemIdOf(id, "E6-CANCEL");
        writeCount(id, itemId, "1", null);

        mockMvc.perform(post(URL + "/" + id + "/cancel").with(user(admin())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        assertThat(onHand(product.getId())).isEqualByComparingTo("5");   // beze změny

        mockMvc.perform(put(URL + "/" + id + "/items")
                        .contentType(APPLICATION_JSON)
                        .content("{\"items\":[{\"id\":" + itemId + ",\"countedQuantity\":2}]}")
                        .with(user(admin())))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].code").value("STOCK_TAKE_NOT_EDITABLE"));
    }
}
