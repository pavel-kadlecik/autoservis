package cz.palo.autoservis.service;

import cz.palo.autoservis.AbstractIntegrationTest;
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
import cz.palo.autoservis.model.dto.warehouse.StockValuationDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E3.1–E3.2 (P-4): ocenění zásob z view {@code v_stock_valuation}.
 *
 * <p>Klíčový scénář: dvě šarže téhož dílu za různé ceny a částečný výdej —
 * hodnota musí odpovídat <b>zbytkům</b> × cenám jejich šarží (skutečné pořizovací
 * ceny, rozhodnutí R-A), ne průměru ani poslední ceně.
 */
@Transactional
class StockValuationTest extends AbstractIntegrationTest {

    @Autowired private ProductService productService;
    @Autowired private WarehouseImportMapper warehouseImportMapper;

    private Supplier supplier;

    private Supplier supplier() {
        if (supplier == null) {
            supplier = Supplier.builder()
                    .name("E3 dodavatel s.r.o.").registrationNumber("12500999")
                    .countryCode("CZ").active(true).build();
            warehouseImportMapper.insertSupplier(supplier);
        }
        return supplier;
    }

    private Product product(String sku) {
        Product product = Product.builder()
                .sku(sku).name("E3 " + sku).unit("ks").defaultVatRate(21).build();
        warehouseImportMapper.insertProduct(product);
        return product;
    }

    /** Přijme šarži daného množství za danou jednotkovou cenu (vč. RECEIPT pohybu). */
    private GoodsReceiptItem receive(Product product, String docNo,
                                     BigDecimal quantity, BigDecimal unitPrice) {
        GoodsReceipt receipt = GoodsReceipt.builder()
                .supplierId(supplier().getId()).supplierNameSnapshot(supplier().getName())
                .invoiceNumber(docNo)
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

    private StockValuationDto.Item itemOf(StockValuationDto.Response response, Long productId) {
        return response.getItems().stream()
                .filter(i -> i.getProductId().equals(productId))
                .findFirst().orElseThrow();
    }

    @Test
    @DisplayName("dvě šarže za různé ceny + částečný výdej → hodnota = zbytky × ceny šarží")
    void valuationUsesBatchRemaindersAndTheirPrices() {
        Product product = product("E3-VAL-1");
        GoodsReceiptItem cheap = receive(product, "E3-FAK-1",
                new BigDecimal("10"), new BigDecimal("100.00"));
        receive(product, "E3-FAK-2", new BigDecimal("5"), new BigDecimal("250.00"));

        // částečný výdej 4 ks z levnější šarže (trigger sníží stav i zůstatek)
        warehouseImportMapper.insertMovement(StockMovement.builder()
                .productId(product.getId()).batchId(cheap.getId())
                .movementType(MovementType.ISSUE).quantity(new BigDecimal("-4"))
                .createdBy(1L).build());

        StockValuationDto.Item item = itemOf(productService.getStockValuation(), product.getId());

        // zbývá 6 × 100 + 5 × 250 = 600 + 1250 = 1850; průměr by dal jinou hodnotu
        assertThat(item.getQuantityOnHand()).isEqualByComparingTo("11");
        assertThat(item.getStockValue()).isEqualByComparingTo("1850.00");
    }

    @Test
    @DisplayName("celkový součet sečte hodnoty všech produktů")
    void totalSumsAllProducts() {
        Product first = product("E3-VAL-2");
        Product second = product("E3-VAL-3");
        receive(first, "E3-FAK-3", new BigDecimal("2"), new BigDecimal("300.00"));   // 600
        receive(second, "E3-FAK-4", new BigDecimal("3"), new BigDecimal("50.50"));   // 151.50

        StockValuationDto.Response response = productService.getStockValuation();

        assertThat(itemOf(response, first.getId()).getStockValue()).isEqualByComparingTo("600.00");
        assertThat(itemOf(response, second.getId()).getStockValue()).isEqualByComparingTo("151.50");
        // ostatní produkty v DB (seed) můžou přispívat — kontroluji, že součet je aspoň tyto dva
        assertThat(response.getTotalValue())
                .isGreaterThanOrEqualTo(new BigDecimal("751.50"));
    }

    @Test
    @DisplayName("produkt bez šarží má hodnotu 0 (a v přehledu nechybí)")
    void productWithoutBatchesIsZero() {
        Product product = product("E3-VAL-4");

        StockValuationDto.Item item = itemOf(productService.getStockValuation(), product.getId());

        assertThat(item.getQuantityOnHand()).isEqualByComparingTo("0");
        assertThat(item.getStockValue()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("vyčerpaná šarže hodnotu nenavyšuje")
    void depletedBatchAddsNothing() {
        Product product = product("E3-VAL-5");
        GoodsReceiptItem batch = receive(product, "E3-FAK-5",
                new BigDecimal("2"), new BigDecimal("400.00"));

        warehouseImportMapper.insertMovement(StockMovement.builder()
                .productId(product.getId()).batchId(batch.getId())
                .movementType(MovementType.ISSUE).quantity(new BigDecimal("-2"))
                .createdBy(1L).build());

        StockValuationDto.Item item = itemOf(productService.getStockValuation(), product.getId());

        assertThat(item.getQuantityOnHand()).isEqualByComparingTo("0");
        assertThat(item.getStockValue()).isEqualByComparingTo("0");
    }
}
