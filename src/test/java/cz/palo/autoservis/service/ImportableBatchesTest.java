package cz.palo.autoservis.service;

import cz.palo.autoservis.AbstractIntegrationTest;
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
import cz.palo.autoservis.model.dto.order.OrderItemDto;
import cz.palo.autoservis.model.dto.warehouse.GoodsReceiptItemDto;
import cz.palo.autoservis.model.enums.OrderItemType;
import cz.palo.autoservis.model.enums.OrderStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Nabídka šarží pro import na zakázku — {@code GET /warehouse/goods-receipts/{id}/items}.
 *
 * <p>Okno výběru se dlouho řídilo jen zbytkem šarže, a proto nabízelo kusy, které drží jiná
 * otevřená zakázka: vstup označilo za platný a server ho odmítl až po odeslání, s rollbackem
 * celé dávky. Rezervace fyzickým stavem nehýbou, takže {@code quantity_remaining} o nich neví —
 * musí se dopočítat. Na tuhle cestu do 2026-08-06 neexistoval jediný test, což je důvod,
 * proč mezera přežila zavedení celého rezervačního modelu.
 */
@Transactional
class ImportableBatchesTest extends AbstractIntegrationTest {

    private static final Long USER_ID = 1L;

    @Autowired private GoodsReceiptService goodsReceiptService;
    @Autowired private OrderItemService orderItemService;
    @Autowired private OrderService orderService;
    @Autowired private OrderMapper orderMapper;
    @Autowired private WarehouseImportMapper warehouseImportMapper;

    private Long orderId;
    private Long receiptId;
    private Long batchId;

    @BeforeEach
    void setUp() {
        Order order = Order.builder()
                .receivedAt(LocalDate.now())
                .customerId(1L)
                .vehicleId(1L)
                .description("Test nabídky šarží")
                .createdBy(USER_ID)
                .build();
        orderMapper.insert(order);
        orderId = order.getId();

        createConfirmedBatchOfFour();
    }

    @Test
    @DisplayName("nabídka vrací zbytek šarže i rezervaci — 4 v regále, 3 slíbené, 1 volný")
    void offer_carriesReservationAlongsideRemaining() {
        orderItemService.importFromReceipt(orderId, List.of(importRequest("3")), USER_ID);

        GoodsReceiptItemDto.Response offered = onlyOfferedBatch();

        assertThat(offered.getQuantityRemaining())
                .as("rezervace do skladu nezapisuje — v regále leží pořád 4 kusy")
                .isEqualByComparingTo("4");
        assertThat(offered.getQuantityReserved()).isEqualByComparingTo("3");
        assertThat(offered.getQuantityAvailable())
                .as("slíbit lze jen zbytek; tohle číslo okno stropuje a dřív ho neznalo")
                .isEqualByComparingTo("1");
    }

    @Test
    @DisplayName("beze zakázek je dostupné = zbytek, ne null")
    void offer_withoutReservations_reportsZeroReserved() {
        GoodsReceiptItemDto.Response offered = onlyOfferedBatch();

        assertThat(offered.getQuantityReserved()).isEqualByComparingTo("0");
        assertThat(offered.getQuantityAvailable()).isEqualByComparingTo("4");
    }

    @Test
    @DisplayName("po výdeji rezervace mizí a zbytek šarže klesne — dostupné se nezmění")
    void offer_afterIssue_movesQuantityFromReservedToGone() {
        orderItemService.importFromReceipt(orderId, List.of(importRequest("3")), USER_ID);
        orderItemService.issueStock(orderId, USER_ID);

        GoodsReceiptItemDto.Response offered = onlyOfferedBatch();

        assertThat(offered.getQuantityRemaining())
                .as("teprve výdej sáhne do skladu")
                .isEqualByComparingTo("1");
        assertThat(offered.getQuantityReserved())
                .as("vydaný díl už není slíbený, ale pryč — jinak by se odečetl dvakrát")
                .isEqualByComparingTo("0");
        assertThat(offered.getQuantityAvailable()).isEqualByComparingTo("1");
    }

    @Test
    @DisplayName("celá rezervovaná šarže se dál nabízí, jen s nulou dostupných")
    void offer_fullyReservedBatch_staysListed() {
        orderItemService.importFromReceipt(orderId, List.of(importRequest("4")), USER_ID);

        GoodsReceiptItemDto.Response offered = onlyOfferedBatch();

        assertThat(offered.getQuantityAvailable()).isEqualByComparingTo("0");
        assertThat(offered.getQuantityRemaining())
                .as("řádek nesmí zmizet — obsluha musí poznat „díl tu není\" od „je slíbený "
                        + "jinam\" a může zakázky přerovnat místo objednávání")
                .isEqualByComparingTo("4");
    }

    @Test
    @DisplayName("zrušení zakázky rezervaci uvolní a šarže je zase celá k dispozici")
    void offer_afterOrderCancelled_releasesReservation() {
        orderItemService.importFromReceipt(orderId, List.of(importRequest("4")), USER_ID);
        assertThat(onlyOfferedBatch().getQuantityAvailable()).isEqualByComparingTo("0");

        orderService.cancel(orderId, USER_ID);

        assertThat(orderMapper.findById(orderId).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.CANCELLED);
        assertThat(onlyOfferedBatch().getQuantityAvailable())
                .as("zrušená zakázka už nic nedrží")
                .isEqualByComparingTo("4");
    }

    @Test
    @DisplayName("výpis položek nese SKU dílu a vydané množství — rozliší ruční, rezervaci a výdej")
    void orderItems_carrySkuAndIssuedQuantity() {
        // Ruční položka: se skladem nemá nic společného.
        OrderItemDto.CreateRequest manual = new OrderItemDto.CreateRequest();
        manual.setItemType(OrderItemType.MATERIAL);
        manual.setName("Ručně psaný díl");
        manual.setQuantity(new BigDecimal("1"));
        manual.setUnit("ks");
        manual.setUnitPrice(new BigDecimal("100"));
        manual.setVatRate((short) 21);
        manual.setPosition((short) 1);
        orderItemService.create(orderId, manual, USER_ID);

        orderItemService.importFromReceipt(orderId, List.of(importRequest("2")), USER_ID);

        OrderItemDto.Response handWritten = itemNamed("Ručně psaný díl");
        assertThat(handWritten.isFromStock()).isFalse();
        assertThat(handWritten.getProductSku())
                .as("ruční položka nemá vazbu na šarži, takže ani katalogové číslo")
                .isNull();
        assertThat(handWritten.getIssuedQuantity()).isEqualByComparingTo("0");

        OrderItemDto.Response reserved = itemNamed("Brzdové destičky přední SET");
        assertThat(reserved.isFromStock()).isTrue();
        assertThat(reserved.getProductSku())
                .as("SKU rozliší dvě položky se shodným názvem z různých šarží")
                .isEqualTo("BATCH-OFFER-SKU");
        assertThat(reserved.getIssuedQuantity())
                .as("rezervace ze skladu nic nevydala")
                .isEqualByComparingTo("0");

        // Původ dílu — odpověď na „u koho tohle reklamovat", až díl za půl roku odejde.
        assertThat(reserved.getSupplierName()).isEqualTo("Dodavatel nabídky šarží s.r.o.");
        assertThat(reserved.getReceiptInvoiceNumber()).isEqualTo("BATCH-OFFER-001");
        assertThat(reserved.getGoodsReceiptId()).isEqualTo(receiptId);

        assertThat(handWritten.getSupplierName())
                .as("ruční položka nemá odkud pocházet")
                .isNull();
        assertThat(handWritten.getGoodsReceiptId()).isNull();

        orderItemService.issueStock(orderId, USER_ID);

        assertThat(itemNamed("Brzdové destičky přední SET").getIssuedQuantity())
                .as("po výdeji drží položka materiál fyzicky")
                .isEqualByComparingTo("2");
        assertThat(itemNamed("Ručně psaný díl").getIssuedQuantity())
                .as("výdej se ruční položky netýká")
                .isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("poznámka u položky se vrací ve výpisu — pole existovalo, ale nikam neteklo")
    void orderItems_carryNote() {
        OrderItemDto.CreateRequest request = new OrderItemDto.CreateRequest();
        request.setItemType(OrderItemType.LABOR);
        request.setName("Výměna destiček");
        request.setQuantity(new BigDecimal("1"));
        request.setUnit("hod");
        request.setUnitPrice(new BigDecimal("500"));
        request.setVatRate((short) 21);
        request.setPosition((short) 1);
        request.setNote("Kotouče na hranici opotřebení — nabídnout výměnu při další prohlídce.");
        orderItemService.create(orderId, request, USER_ID);

        assertThat(itemNamed("Výměna destiček").getNote())
                .isEqualTo("Kotouče na hranici opotřebení — nabídnout výměnu při další prohlídce.");
    }

    private OrderItemDto.Response itemNamed(String name) {
        return orderItemService.getByOrderId(orderId).stream()
                .filter(i -> name.equals(i.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Položka nenalezena: " + name));
    }

    // =========================================================================
    // Setup
    // =========================================================================

    private GoodsReceiptItemDto.Response onlyOfferedBatch() {
        List<GoodsReceiptItemDto.Response> offered = goodsReceiptService.getImportableItems(receiptId);
        assertThat(offered).hasSize(1);
        return offered.getFirst();
    }

    private GoodsReceiptItemDto.ImportRequest importRequest(String quantity) {
        return GoodsReceiptItemDto.ImportRequest.builder()
                .goodsReceiptItemId(batchId)
                .quantity(new BigDecimal(quantity))
                .build();
    }

    private void createConfirmedBatchOfFour() {
        Supplier supplier = Supplier.builder()
                .name("Dodavatel nabídky šarží s.r.o.")
                .registrationNumber("11223344")
                .countryCode("CZ")
                .active(true)
                .build();
        warehouseImportMapper.insertSupplier(supplier);

        Product product = Product.builder()
                .sku("BATCH-OFFER-SKU")
                .name("Brzdové destičky přední SET")
                .unit("ks")
                .defaultVatRate(21)
                .build();
        warehouseImportMapper.insertProduct(product);

        GoodsReceipt receipt = GoodsReceipt.builder()
                .supplierId(supplier.getId())
                .supplierNameSnapshot(supplier.getName())
                .invoiceNumber("BATCH-OFFER-001")
                .subtotal(new BigDecimal("400.00"))
                .vatAmount(new BigDecimal("84.00"))
                .totalAmount(new BigDecimal("484.00"))
                .currency("CZK")
                .documentType(DocumentType.INVOICE)
                .sourceChannel(ReceiptSource.AI_PDF)
                .status(ReceiptStatus.CONFIRMED)
                .reconciliationOk(true)
                .createdBy(USER_ID)
                .build();
        warehouseImportMapper.insertReceipt(receipt);
        receiptId = receipt.getId();

        GoodsReceiptItem batch = GoodsReceiptItem.builder()
                .goodsReceiptId(receiptId)
                .productId(product.getId())
                .position(1)
                .nameSnapshot(product.getName())
                .quantityReceived(new BigDecimal("4"))
                .quantityRemaining(new BigDecimal("4"))
                .unitPriceExclVat(new BigDecimal("100.00"))
                .vatRate(21)
                .totalInclVat(new BigDecimal("484.00"))
                .build();
        warehouseImportMapper.insertReceiptItem(batch);
        batchId = batch.getId();

        warehouseImportMapper.insertMovement(StockMovement.builder()
                .productId(product.getId())
                .batchId(batchId)
                .movementType(MovementType.RECEIPT)
                .quantity(new BigDecimal("4"))
                .createdBy(USER_ID)
                .build());
    }
}
