package cz.palo.autoservis.service;

import cz.palo.autoservis.AbstractIntegrationTest;
import cz.palo.autoservis.exception.BusinessRuleException;
import cz.palo.autoservis.mapper.WarehouseMapper;
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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pokrývá opravu TD-28: {@code ProductServiceImpl.deactivate} odmítne deaktivovat
 * produkt, který má ještě zásobu na skladě ({@code quantity_on_hand > 0}) — pravidlo
 * „zakázat" zvolené v analýze, místo tichého stavu „doprodej".
 *
 * <p>{@code @Transactional} — každý test běží v transakci, která se na konci rollbackne,
 * takže DB zůstává čistá bez ohledu na pořadí testů (viz {@code OrderItemImportTest}).
 */
@Transactional
class ProductDeactivationTest extends AbstractIntegrationTest {

    @Autowired
    private ProductService productService;

    @Autowired
    private WarehouseMapper warehouseMapper;

    @Autowired
    private WarehouseImportMapper warehouseImportMapper;

    /**
     * Postaví dodavatele + produkt + potvrzenou příjemku + šarži s daným přijatým
     * množstvím, plus odpovídající skladový pohyb RECEIPT, aby ho {@code quantity_on_hand}
     * skutečně odráželo (sloupec udržuje DB trigger na {@code stock_movements} — viz
     * {@code OrderItemImportTest}).
     */
    private Product createProductWithStock(BigDecimal quantity) {
        Supplier supplier = Supplier.builder()
                .name("D4 test dodavatel s.r.o.")
                .registrationNumber("12345680")
                .countryCode("CZ")
                .active(true)
                .build();
        warehouseImportMapper.insertSupplier(supplier);

        Product product = Product.builder()
                .sku("D4-TEST-SKU-" + quantity)
                .name("Testovací díl D4")
                .unit("ks")
                .defaultVatRate(21)
                .build();
        warehouseImportMapper.insertProduct(product);

        if (quantity.compareTo(BigDecimal.ZERO) > 0) {
            GoodsReceipt receipt = GoodsReceipt.builder()
                    .supplierId(supplier.getId())
                    .supplierNameSnapshot(supplier.getName())
                    .invoiceNumber("D4-FAK-" + quantity)
                    .subtotal(new BigDecimal("100.00"))
                    .vatAmount(new BigDecimal("21.00"))
                    .totalAmount(new BigDecimal("121.00"))
                    .currency("CZK")
                    .documentType(DocumentType.INVOICE)
                    .sourceChannel(ReceiptSource.AI_PDF)
                    .status(ReceiptStatus.CONFIRMED)
                    .reconciliationOk(true)
                    .createdBy(1L)
                    .build();
            warehouseImportMapper.insertReceipt(receipt);

            GoodsReceiptItem batch = GoodsReceiptItem.builder()
                    .goodsReceiptId(receipt.getId())
                    .productId(product.getId())
                    .position(1)
                    .nameSnapshot(product.getName())
                    .quantityReceived(quantity)
                    .quantityRemaining(quantity)
                    .unitPriceExclVat(new BigDecimal("100.00"))
                    .vatRate(21)
                    .totalInclVat(new BigDecimal("121.00"))
                    .build();
            warehouseImportMapper.insertReceiptItem(batch);

            StockMovement receiptMovement = StockMovement.builder()
                    .productId(product.getId())
                    .batchId(batch.getId())
                    .movementType(MovementType.RECEIPT)
                    .quantity(quantity)
                    .createdBy(1L)
                    .build();
            warehouseImportMapper.insertMovement(receiptMovement);
        }

        return product;
    }

    @Test
    void deactivate_productWithStock_throwsProductHasStock() {
        Product product = createProductWithStock(new BigDecimal("4"));

        assertThatThrownBy(() -> productService.deactivate(product.getId()))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(ex -> {
                    BusinessRuleException bre = (BusinessRuleException) ex;
                    assertThat(bre.getRuleCode()).isEqualTo("PRODUCT_HAS_STOCK");
                });

        Product afterAttempt = warehouseMapper.findById(product.getId()).orElseThrow();
        assertThat(afterAttempt.getActive()).isTrue();
    }

    @Test
    void deactivate_productWithZeroStock_succeeds() {
        Product product = createProductWithStock(BigDecimal.ZERO);

        productService.deactivate(product.getId());

        Product afterDeactivation = warehouseMapper.findById(product.getId()).orElseThrow();
        assertThat(afterDeactivation.getActive()).isFalse();
    }
}
