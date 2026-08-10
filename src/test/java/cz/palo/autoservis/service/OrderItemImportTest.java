package cz.palo.autoservis.service;

import cz.palo.autoservis.AbstractIntegrationTest;
import cz.palo.autoservis.exception.BusinessRuleException;
import cz.palo.autoservis.mapper.GoodsReceiptMapper;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pokrývá opravu K6 (analyza-2026-07) v {@code OrderItemServiceImpl.importFromReceipt}:
 * požadovaná množství se agregují po šaržích (jeden request smí tutéž šarži odkázat
 * vícekrát) a validují se proti {@code quantity_remaining} jako celek, přičemž šarže
 * zůstávají zamčené ({@code SELECT ... FOR UPDATE}) po zbytek transakce, takže souběžný
 * odběr z téže šarže nemůže proklouznout kolem téže zastaralé kontroly.
 *
 * <p>{@code @Transactional} — každý test běží v transakci, která se na konci rollbackne,
 * takže DB zůstává čistá bez ohledu na pořadí testů (viz {@code InvoiceStatusTransitionTest}).
 */
@Transactional
class OrderItemImportTest extends AbstractIntegrationTest {

    @Autowired
    private OrderItemService orderItemService;

    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private WarehouseImportMapper warehouseImportMapper;

    @Autowired
    private GoodsReceiptMapper goodsReceiptMapper;

    private Long orderId;
    private Long batchId;

    /**
     * Seed data nemají potvrzenou šarži se známým zůstatkem, takže si ji každý test
     * postaví sám: dodavatel, produkt, potvrzená příjemka a jediná šarže (řádek
     * goods_receipt_items) se {@code quantity_remaining = 4}, plus čerstvá zakázka,
     * do které se importuje.
     */
    @BeforeEach
    void createOrderAndBatch() {
        Order order = Order.builder()
                .receivedAt(LocalDate.now())
                .customerId(1L)
                .vehicleId(1L)
                .description("A4 test — agregace požadavků + FOR UPDATE")
                .estimatedPrice(new BigDecimal("1000"))
                .createdBy(1L)
                .build();
        orderMapper.insert(order);
        orderId = order.getId();

        Supplier supplier = Supplier.builder()
                .name("A4 test dodavatel s.r.o.")
                .registrationNumber("12345679")
                .countryCode("CZ")
                .active(true)
                .build();
        warehouseImportMapper.insertSupplier(supplier);

        Product product = Product.builder()
                .sku("A4-TEST-SKU")
                .name("Testovací díl A4")
                .unit("ks")
                .defaultVatRate(21)
                .build();
        warehouseImportMapper.insertProduct(product);

        GoodsReceipt receipt = GoodsReceipt.builder()
                .supplierId(supplier.getId())
                .supplierNameSnapshot(supplier.getName())
                .invoiceNumber("A4-FAK-001")
                .subtotal(new BigDecimal("400.00"))
                .vatAmount(new BigDecimal("84.00"))
                .totalAmount(new BigDecimal("484.00"))
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
                .quantityReceived(new BigDecimal("4"))
                .quantityRemaining(new BigDecimal("4"))
                .unitPriceExclVat(new BigDecimal("100.00"))
                .vatRate(21)
                .totalInclVat(new BigDecimal("484.00"))
                .build();
        warehouseImportMapper.insertReceiptItem(batch);
        batchId = batch.getId();

        // Potvrzená šarže normálně přichází s pohybem RECEIPT, který zboží naskladnil
        // (ReceiptReviewServiceImpl.confirm) — bez něj zůstane quantity_on_hand 0 a každý
        // ISSUE níže by spadl na chk_products_qty bez ohledu na testovanou opravu K6.
        StockMovement receiptMovement = StockMovement.builder()
                .productId(product.getId())
                .batchId(batch.getId())
                .movementType(MovementType.RECEIPT)
                .quantity(new BigDecimal("4"))
                .createdBy(1L)
                .build();
        warehouseImportMapper.insertMovement(receiptMovement);
    }

    private GoodsReceiptItemDto.ImportRequest request(Long goodsReceiptItemId, String quantity) {
        return GoodsReceiptItemDto.ImportRequest.builder()
                .goodsReceiptItemId(goodsReceiptItemId)
                .quantity(new BigDecimal(quantity))
                .build();
    }

    @Test
    @DisplayName("dvě položky téže šarže 3+3 ks při remaining=4 → QUANTITY_EXCEEDS_REMAINING (dřív by prošlo až na DB CHECK)")
    void twoLinesOfSameBatch_sumExceedsRemaining_throwsQuantityExceedsRemaining() {
        List<GoodsReceiptItemDto.ImportRequest> importRequest = List.of(
                request(batchId, "3"),
                request(batchId, "3"));

        assertThatThrownBy(() -> orderItemService.importFromReceipt(orderId, importRequest, 1L))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(ex -> {
                    BusinessRuleException bre = (BusinessRuleException) ex;
                    assertThat(bre.getRuleCode()).isEqualTo("QUANTITY_EXCEEDS_REMAINING");
                });
    }

    @Test
    @DisplayName("dvě položky téže šarže 2+2 ks při remaining=4 → projde; šarže se NEodečte, jen se celá zarezervuje")
    void twoLinesOfSameBatch_sumMatchesRemaining_reservesWithoutDepletingBatch() {
        List<GoodsReceiptItemDto.ImportRequest> importRequest = List.of(
                request(batchId, "2"),
                request(batchId, "2"));

        List<OrderItemDto.Response> created = orderItemService.importFromReceipt(orderId, importRequest, 1L);

        assertThat(created).hasSize(2);

        GoodsReceiptItem batchAfter = goodsReceiptMapper.findById(batchId).orElseThrow();
        assertThat(batchAfter.getQuantityRemaining())
                .as("import je rezervace, ne výdej — díl leží fyzicky dál v regálu (V83)")
                .isEqualByComparingTo("4");

        // Dostupné množství je ale nula, takže další plánování z téže šarže neprojde.
        assertThatThrownBy(() -> orderItemService.importFromReceipt(
                orderId, List.of(request(batchId, "1")), 1L))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(ex -> assertThat(((BusinessRuleException) ex).getRuleCode())
                        .isEqualTo("QUANTITY_EXCEEDS_REMAINING"));
    }

    @Test
    @DisplayName("výdej zakázky odečte šarži; opakovaný výdej už nic nezdvojí")
    void issueStock_depletesBatch_andIsIdempotent() {
        orderItemService.importFromReceipt(orderId, List.of(request(batchId, "3")), 1L);

        assertThat(goodsReceiptMapper.findById(batchId).orElseThrow().getQuantityRemaining())
                .as("po importu se ještě nic neodečetlo")
                .isEqualByComparingTo("4");

        int issued = orderItemService.issueStock(orderId, 1L);

        assertThat(issued).isEqualTo(1);
        assertThat(goodsReceiptMapper.findById(batchId).orElseThrow().getQuantityRemaining())
                .as("teprve výdej sníží fyzický zůstatek šarže")
                .isEqualByComparingTo("1");

        // Druhé volání nemá co vydat — vydané položky do výběru nespadnou.
        assertThat(orderItemService.issueStock(orderId, 1L)).isZero();
        assertThat(goodsReceiptMapper.findById(batchId).orElseThrow().getQuantityRemaining())
                .isEqualByComparingTo("1");
    }

    @Test
    @DisplayName("jedna položka 5 ks při remaining=4 → QUANTITY_EXCEEDS_REMAINING (regrese stávajícího chování)")
    void singleLine_exceedsRemaining_throwsQuantityExceedsRemaining() {
        List<GoodsReceiptItemDto.ImportRequest> importRequest = List.of(
                request(batchId, "5"));

        assertThatThrownBy(() -> orderItemService.importFromReceipt(orderId, importRequest, 1L))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(ex -> {
                    BusinessRuleException bre = (BusinessRuleException) ex;
                    assertThat(bre.getRuleCode()).isEqualTo("QUANTITY_EXCEEDS_REMAINING");
                });
    }
}
