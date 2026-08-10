package cz.palo.autoservis.service;

import cz.palo.autoservis.AbstractIntegrationTest;
import cz.palo.autoservis.mapper.ProductMatchingMapper;
import cz.palo.autoservis.mapper.WarehouseImportMapper;
import cz.palo.autoservis.model.domain.warehouse.DocumentType;
import cz.palo.autoservis.model.domain.warehouse.GoodsReceipt;
import cz.palo.autoservis.model.domain.warehouse.GoodsReceiptItem;
import cz.palo.autoservis.model.domain.warehouse.MovementType;
import cz.palo.autoservis.model.domain.warehouse.Product;
import cz.palo.autoservis.model.domain.warehouse.ReceiptSource;
import cz.palo.autoservis.model.domain.warehouse.ReceiptStatus;
import cz.palo.autoservis.model.domain.warehouse.StockMovement;
import cz.palo.autoservis.model.domain.warehouse.Supplier;
import cz.palo.autoservis.model.dto.warehouse.LowStockDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E8.3 (P-7): přehled dílů pod hlídaným minimem i s doporučeným dodavatelem
 * z převodníku {@code supplier_products}.
 */
@Transactional
class LowStockTest extends AbstractIntegrationTest {

    @Autowired private ProductService productService;
    @Autowired private WarehouseImportMapper warehouseImportMapper;
    @Autowired private cz.palo.autoservis.mapper.WarehouseMapper warehouseMapper;
    @Autowired private ProductMatchingMapper matchingMapper;
    @Autowired private JdbcTemplate jdbc;

    /** Seed produkty stranou, ať přehled obsahuje jen díly tohoto testu. */
    private void isolateFromSeedProducts() {
        jdbc.update("UPDATE warehouse.products SET is_active = FALSE");
    }

    /**
     * Karta se zakládá přes {@code WarehouseMapper.insert} (cesta ruční správy karet) —
     * import mapper {@code min_stock_level} vědomě neukládá: karta vzniklá z dokladu
     * žádné hlídané minimum nemá, dokud ho někdo nenastaví.
     */
    private Product product(String sku, String minStock) {
        Product product = Product.builder()
                .sku(sku).name("E8 " + sku).unit("ks").defaultVatRate(21)
                .minStockLevel(minStock == null ? null : new BigDecimal(minStock))
                .active(true)
                .build();
        warehouseMapper.insert(product);
        return product;
    }

    /** Naskladní množství, ať quantity_on_hand odpovídá (přes pohyb a trigger). */
    private void stock(Product product, String quantity, String unitPrice, Supplier supplier) {
        GoodsReceipt receipt = GoodsReceipt.builder()
                .supplierId(supplier.getId()).supplierNameSnapshot(supplier.getName())
                .invoiceNumber("E8-" + product.getSku())
                .subtotal(new BigDecimal("10.00")).vatAmount(new BigDecimal("2.10"))
                .totalAmount(new BigDecimal("12.10")).currency("CZK")
                .documentType(DocumentType.INVOICE).sourceChannel(ReceiptSource.AI_PDF)
                .status(ReceiptStatus.CONFIRMED).reconciliationOk(true).createdBy(1L).build();
        warehouseImportMapper.insertReceipt(receipt);

        GoodsReceiptItem batch = GoodsReceiptItem.builder()
                .goodsReceiptId(receipt.getId()).productId(product.getId()).position(1)
                .nameSnapshot(product.getName())
                .quantityReceived(new BigDecimal(quantity)).quantityRemaining(new BigDecimal(quantity))
                .unitPriceExclVat(new BigDecimal(unitPrice)).vatRate(21)
                .totalInclVat(new BigDecimal(quantity).multiply(new BigDecimal(unitPrice))).build();
        warehouseImportMapper.insertReceiptItem(batch);

        warehouseImportMapper.insertMovement(StockMovement.builder()
                .productId(product.getId()).batchId(batch.getId())
                .movementType(MovementType.RECEIPT).quantity(new BigDecimal(quantity))
                .createdBy(1L).build());
    }

    private Supplier supplier(String name, String ico) {
        Supplier supplier = Supplier.builder().name(name).registrationNumber(ico)
                .countryCode("CZ").active(true).build();
        warehouseImportMapper.insertSupplier(supplier);
        return supplier;
    }

    private Optional<LowStockDto> rowOf(List<LowStockDto> rows, String sku) {
        return rows.stream().filter(r -> sku.equals(r.getSku())).findFirst();
    }

    /**
     * Naplánuje díl na novou otevřenou zakázku — tedy ho <strong>rezervuje</strong>, aniž by
     * cokoli odešlo ze skladu (V83). Zakládá se přímo SQL: testuje se předpis dotazu,
     * ne cesta přes službu.
     */
    private void reserve(Product product, String quantity) {
        Long batchId = jdbc.queryForObject(
                "SELECT id FROM warehouse.goods_receipt_items WHERE product_id = ? ORDER BY id DESC LIMIT 1",
                Long.class, product.getId());
        jdbc.update("INSERT INTO \"order\".orders (customer_id, vehicle_id, description, received_at, created_by) "
                + "VALUES (1, 1, 'E8 rezervace', CURRENT_DATE, 1)");
        Long orderId = jdbc.queryForObject(
                "SELECT id FROM \"order\".orders ORDER BY id DESC LIMIT 1", Long.class);
        jdbc.update("INSERT INTO \"order\".order_items "
                        + "(order_id, item_type, name, quantity, unit, unit_price, goods_receipt_item_id) "
                        + "VALUES (?, 'MATERIAL', ?, ?::numeric, 'ks', 100, ?)",
                orderId, product.getName(), quantity, batchId);
    }

    @Test
    @DisplayName("rezervace sníží dostupné a díl spadne pod minimum, fyzický stav zůstane")
    void reservationPushesProductBelowMinimum() {
        isolateFromSeedProducts();
        Supplier supplier = supplier("E8 dodavatel rezervace", "66554433");
        Product product = product("E8-REZ", "5");
        stock(product, "10", "100.00", supplier);

        // Rezervuje se dřív, než se přehled poprvé zavolá: dvě volání téhož dotazu v jedné
        // relaci by druhé obsloužila cache MyBatisu a test by měřil jen ji, ne dotaz.
        // Že díl nad minimem v přehledu není, hlídá productsAboveMinimumOrUnwatchedAreExcluded.
        reserve(product, "6");

        Optional<LowStockDto> row = rowOf(productService.getLowStock(), "E8-REZ");
        assertThat(row)
                .as("dostupné kleslo na 4, tedy pod hlídané minimum 5")
                .isPresent();
        assertThat(row.get().getQuantityOnHand())
                .as("fyzicky se nic nestalo — díl leží dál v regálu a inventura ho napočítá")
                .isEqualByComparingTo("10");
        assertThat(row.get().getQuantityReserved()).isEqualByComparingTo("6");
        assertThat(row.get().getQuantityAvailable()).isEqualByComparingTo("4");
        assertThat(row.get().getMissingQuantity())
                .as("chybějící množství se počítá z dostupného, ne z fyzického stavu")
                .isEqualByComparingTo("1");
    }

    @Test
    @DisplayName("díl pod minimem vrátí chybějící množství i doporučeného dodavatele")
    void lowStockIncludesSupplierRecommendation() {
        isolateFromSeedProducts();
        Supplier supplier = supplier("E8 dodavatel", "12800111");
        Product product = product("E8-LOW-1", "10");
        stock(product, "3", "120.00", supplier);
        // převodník zná kód dodavatele i poslední cenu (upsertuje ho potvrzení příjemky)
        matchingMapper.upsertSupplierProduct(supplier.getId(), "DOD-KOD-1", product.getId(),
                product.getName(), new BigDecimal("125.50"));

        LowStockDto row = rowOf(productService.getLowStock(), "E8-LOW-1").orElseThrow();

        assertThat(row.getQuantityOnHand()).isEqualByComparingTo("3");
        assertThat(row.getMinStockLevel()).isEqualByComparingTo("10");
        assertThat(row.getMissingQuantity()).isEqualByComparingTo("7");
        assertThat(row.getSupplierName()).isEqualTo("E8 dodavatel");
        assertThat(row.getSupplierSku()).isEqualTo("DOD-KOD-1");
        assertThat(row.getLastUnitPriceExclVat()).isEqualByComparingTo("125.50");
    }

    @Test
    @DisplayName("díl bez převodníku se vypíše bez doporučení, ne že vypadne")
    void productWithoutCrossReferenceStillListed() {
        isolateFromSeedProducts();
        Product product = product("E8-LOW-2", "5");   // bez šarže i bez převodníku

        LowStockDto row = rowOf(productService.getLowStock(), "E8-LOW-2").orElseThrow();

        assertThat(row.getMissingQuantity()).isEqualByComparingTo("5");
        assertThat(row.getSupplierName()).isNull();
        assertThat(row.getSupplierSku()).isNull();
    }

    @Test
    @DisplayName("díl nad minimem ani díl bez hlídání se v přehledu neobjeví")
    void productsAboveMinimumOrUnwatchedAreExcluded() {
        isolateFromSeedProducts();
        Supplier supplier = supplier("E8 dodavatel 2", "12800222");
        Product watchedOk = product("E8-OK", "2");
        stock(watchedOk, "9", "50.00", supplier);
        Product unwatched = product("E8-UNWATCHED", null);   // min_stock_level NULL

        List<LowStockDto> rows = productService.getLowStock();

        assertThat(rowOf(rows, "E8-OK")).isEmpty();
        assertThat(rowOf(rows, "E8-UNWATCHED")).isEmpty();
    }
}
