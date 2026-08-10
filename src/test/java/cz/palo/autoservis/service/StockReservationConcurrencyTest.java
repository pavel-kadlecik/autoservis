package cz.palo.autoservis.service;

import cz.palo.autoservis.AbstractIntegrationTest;
import cz.palo.autoservis.exception.BusinessRuleException;
import cz.palo.autoservis.mapper.OrderMapper;
import cz.palo.autoservis.mapper.WarehouseImportMapper;
import cz.palo.autoservis.model.domain.order.Order;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Souběh dvou zakázek o poslední kus na skladě (V83).
 *
 * <p>Rozhodnutí uživatele 2026-08-05: <em>„první rezervace vyhraje, druhý dostane hlášku"</em>.
 * Smysl je, že se konflikt pozná při <strong>plánování</strong>, ne až u pultu při výdeji.
 *
 * <p><strong>Proč tenhle test není {@code @Transactional}:</strong> obě vlákna na sebe musí
 * vidět, což uvnitř jedné nezacommitované transakce nejde. Data se proto zapisují natrvalo
 * a uklízejí se v {@link #cleanUp()} — kontejner je sdílený celým během testů, takže by
 * zbytky ovlivnily ostatní třídy.
 *
 * <p>Souběh je <strong>deterministický</strong>, ne náhodný: první vlákno drží transakci
 * otevřenou na západce, dokud druhé nenarazí na zámek šarže.
 */
class StockReservationConcurrencyTest extends AbstractIntegrationTest {

    private static final long CUSTOMER_ID = 1L;
    private static final long VEHICLE_ID = 1L;
    private static final long USER_ID = 1L;

    @Autowired
    private OrderItemService orderItemService;

    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private WarehouseImportMapper warehouseImportMapper;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbc;

    private Long orderA;
    private Long orderB;
    private Long batchId;
    private Long productId;
    private Long receiptId;
    private Long supplierId;

    @BeforeEach
    void seedSinglePieceOnStock() {
        orderA = createOrder("Souběh A");
        orderB = createOrder("Souběh B");
        createConfirmedBatchWithOnePiece();
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM \"order\".order_items WHERE order_id IN (?, ?)", orderA, orderB);
        // Ledger je append-only (V52) — trigger se vypíná jen pro úklid testovacích dat.
        jdbc.execute("ALTER TABLE warehouse.stock_movements DISABLE TRIGGER trg_movements_append_only");
        jdbc.update("DELETE FROM warehouse.stock_movements WHERE product_id = ?", productId);
        jdbc.execute("ALTER TABLE warehouse.stock_movements ENABLE TRIGGER trg_movements_append_only");
        jdbc.update("DELETE FROM \"order\".orders WHERE id IN (?, ?)", orderA, orderB);
        jdbc.update("DELETE FROM warehouse.goods_receipt_items WHERE id = ?", batchId);
        jdbc.update("DELETE FROM warehouse.goods_receipts WHERE id = ?", receiptId);
        jdbc.update("DELETE FROM warehouse.products WHERE id = ?", productId);
        jdbc.update("DELETE FROM warehouse.suppliers WHERE id = ?", supplierId);
    }

    @Test
    @DisplayName("dvě zakázky o poslední kus: první rezervaci dostane, druhá skončí hláškou")
    void twoOrdersRacingForLastPiece_onlyFirstReserves() throws Exception {
        CountDownLatch firstReserved = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicReference<Throwable> secondFailure = new AtomicReference<>();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            // Vlákno A: zarezervuje poslední kus a DRŽÍ transakci otevřenou (a s ní i zámek
            // šarže), dokud ho nepustíme — tím vznikne skutečný souběh, ne náhoda časování.
            Future<?> first = pool.submit(() -> transactionTemplate.execute(status -> {
                orderItemService.importFromReceipt(orderA, List.of(request("1")), USER_ID);
                firstReserved.countDown();
                awaitQuietly(releaseFirst);
                return null;
            }));

            assertThat(firstReserved.await(10, TimeUnit.SECONDS))
                    .as("první vlákno musí stihnout rezervaci").isTrue();

            // Vlákno B: naráží na zámek šarže a čeká, až A dokončí transakci.
            Future<?> second = pool.submit(() -> {
                try {
                    orderItemService.importFromReceipt(orderB, List.of(request("1")), USER_ID);
                } catch (Throwable t) {
                    secondFailure.set(t);
                }
            });

            // Chvíli, ať B opravdu stihne narazit na zámek, teprve pak pustíme A dál.
            Thread.sleep(500);
            releaseFirst.countDown();

            first.get(15, TimeUnit.SECONDS);
            second.get(15, TimeUnit.SECONDS);
        } finally {
            releaseFirst.countDown();
            pool.shutdownNow();
        }

        assertThat(secondFailure.get())
                .as("druhá zakázka nesmí dostat tentýž poslední kus — konflikt se má poznat "
                        + "při plánování, ne až u výdeje")
                .isInstanceOf(BusinessRuleException.class);
        assertThat(((BusinessRuleException) secondFailure.get()).getRuleCode())
                .isEqualTo("QUANTITY_EXCEEDS_REMAINING");

        Integer reservations = jdbc.queryForObject(
                "SELECT COUNT(*) FROM \"order\".order_items WHERE goods_receipt_item_id = ?",
                Integer.class, batchId);
        assertThat(reservations).as("na šarži smí viset jediná rezervace").isEqualTo(1);
    }

    // =========================================================================
    // Pomocníci
    // =========================================================================

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(15, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private GoodsReceiptItemDto.ImportRequest request(String quantity) {
        GoodsReceiptItemDto.ImportRequest request = new GoodsReceiptItemDto.ImportRequest();
        request.setGoodsReceiptItemId(batchId);
        request.setQuantity(new BigDecimal(quantity));
        return request;
    }

    private Long createOrder(String description) {
        Order order = Order.builder()
                .receivedAt(LocalDate.now())
                .customerId(CUSTOMER_ID).vehicleId(VEHICLE_ID)
                .description(description).createdBy(USER_ID).build();
        orderMapper.insert(order);
        return order.getId();
    }

    /** Potvrzená příjemka s jediným kusem — o ten se obě zakázky perou. */
    private void createConfirmedBatchWithOnePiece() {
        Supplier supplier = Supplier.builder()
                .name("Souběh dodavatel s.r.o.").registrationNumber("99887766")
                .countryCode("CZ").active(true).build();
        warehouseImportMapper.insertSupplier(supplier);
        supplierId = supplier.getId();

        Product product = Product.builder()
                .sku("SOUBEH-TEST-SKU").name("Poslední kus na skladě")
                .unit("ks").defaultVatRate(21).build();
        warehouseImportMapper.insertProduct(product);
        productId = product.getId();

        GoodsReceipt receipt = GoodsReceipt.builder()
                .supplierId(supplierId).supplierNameSnapshot(supplier.getName())
                .invoiceNumber("SOUBEH-FAK-001")
                .subtotal(new BigDecimal("100.00")).vatAmount(new BigDecimal("21.00"))
                .totalAmount(new BigDecimal("121.00")).currency("CZK")
                .documentType(DocumentType.INVOICE).sourceChannel(ReceiptSource.MANUAL)
                .status(ReceiptStatus.CONFIRMED).reconciliationOk(true)
                .createdBy(USER_ID).build();
        warehouseImportMapper.insertReceipt(receipt);
        receiptId = receipt.getId();

        GoodsReceiptItem batch = GoodsReceiptItem.builder()
                .goodsReceiptId(receiptId).productId(productId).position(1)
                .nameSnapshot(product.getName())
                .quantityReceived(BigDecimal.ONE).quantityRemaining(BigDecimal.ONE)
                .unitPriceExclVat(new BigDecimal("100.00")).vatRate(21)
                .totalInclVat(new BigDecimal("121.00")).build();
        warehouseImportMapper.insertReceiptItem(batch);
        batchId = batch.getId();

        warehouseImportMapper.insertMovement(StockMovement.builder()
                .productId(productId).batchId(batchId)
                .movementType(MovementType.RECEIPT).quantity(BigDecimal.ONE)
                .createdBy(USER_ID).build());
    }
}
