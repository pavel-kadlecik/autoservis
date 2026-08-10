package cz.palo.autoservis.service;

import cz.palo.autoservis.AbstractIntegrationTest;
import cz.palo.autoservis.exception.BusinessRuleException;
import cz.palo.autoservis.exception.ResourceNotFoundException;
import cz.palo.autoservis.mapper.OrderMapper;
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
import cz.palo.autoservis.model.dto.order.OrderDto;
import cz.palo.autoservis.model.dto.warehouse.GoodsReceiptItemDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tvrdé smazání zakázky (V84) — vyhrazené pro záznam, který nikdy neměl vzniknout.
 *
 * <p><strong>Smazat ≠ zrušit.</strong> Zakázka, u které k práci nedošlo, se ruší stavem
 * {@code CANCELLED} a v evidenci zůstává; maže se jen omyl obsluhy (překlep, špatné auto).
 * Rozhoduje se podle <em>stop</em>, ne podle stavu: projde jen zakázka, po které nezůstala
 * faktura ani skladový pohyb.
 */
@Transactional
class OrderDeleteTest extends AbstractIntegrationTest {

    private static final long CUSTOMER_ID = 1L;
    private static final long VEHICLE_ID = 1L;
    private static final long USER_ID = 1L;

    @Autowired
    private OrderService orderService;

    @Autowired
    private OrderItemService orderItemService;

    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private WarehouseImportMapper warehouseImportMapper;

    @Autowired
    private cz.palo.autoservis.mapper.GoodsReceiptMapper goodsReceiptMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("zakázku bez jakékoli stopy lze smazat")
    void delete_orderWithoutTrace_removesIt() {
        Long orderId = createOrder(null);

        orderService.delete(orderId, USER_ID);

        assertThat(orderMapper.findById(orderId)).isEmpty();
    }

    @Test
    @DisplayName("zakázku s pouhou REZERVACÍ lze smazat — díl regál neopustil")
    void delete_orderWithReservationOnly_removesItAndFreesReservation() {
        Long orderId = createOrder(null);
        Long batchId = createConfirmedBatch();
        orderItemService.importFromReceipt(orderId, List.of(importRequest(batchId, "4")), USER_ID);

        orderService.delete(orderId, USER_ID);

        assertThat(orderMapper.findById(orderId)).isEmpty();
        assertThat(countItems(orderId)).as("položky odejdou kaskádou").isZero();
        assertThat(remaining(batchId)).as("sklad se nehnul, rezervace se jen uvolnila")
                .isEqualByComparingTo("4");
    }

    @Test
    @DisplayName("zakázku s VYDANÝM materiálem smazat lze — materiál se vrátí na sklad")
    void delete_orderWithIssuedMaterial_returnsMaterialAndRemovesOrder() {
        // Do 2026-08-07 to nešlo: mazání blokoval jakýkoli pohyb, i vratka. Omylem založená
        // zakázka, na kterou stihl někdo vydat díl, tak zůstala v evidenci navždy — i když se
        // materiál dávno vrátil a sklad byl v pořádku.
        Long orderId = createOrder(null);
        Long batchId = createConfirmedBatch();
        orderItemService.importFromReceipt(orderId, List.of(importRequest(batchId, "4")), USER_ID);
        orderItemService.issueStock(orderId, USER_ID);
        assertThat(goodsReceiptMapper.findById(batchId).orElseThrow().getQuantityRemaining())
                .as("výdej šarži vyprázdnil")
                .isEqualByComparingTo("0");

        orderService.delete(orderId, USER_ID);

        assertThat(orderMapper.findById(orderId)).isEmpty();
        assertThat(goodsReceiptMapper.findById(batchId).orElseThrow().getQuantityRemaining())
                .as("materiál se vrátil — po smazané zakázce nesmí zbýt díly vydané na záznam, "
                        + "který už neexistuje")
                .isEqualByComparingTo("4");
    }

    @Test
    @DisplayName("pohyby smazané zakázky zůstávají v ledgeru — append-only, jen bez FK (V87)")
    void delete_keepsMovementsInLedger() {
        Long orderId = createOrder(null);
        Long batchId = createConfirmedBatch();
        orderItemService.importFromReceipt(orderId, List.of(importRequest(batchId, "4")), USER_ID);
        orderItemService.issueStock(orderId, USER_ID);

        orderService.delete(orderId, USER_ID);

        // Výdej i vratka zůstávají a nesou ID zakázky, které už na nic neukazuje. Historie
        // skladu je tím pravdivá: materiál opravdu odešel a vrátil se. Pár je vyrovnaný,
        // takže dopad na zásobu je nulový.
        assertThat(warehouseImportMapper.countMovementsByOrderId(orderId))
                .as("ledger je append-only — pohyby se smazáním zakázky nemažou")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("smazání odstraní i odečet tachometru z příjmu — u špatného auta je to nesmysl")
    void delete_removesIntakeMileageReading() {
        Long orderId = createOrder(123_456);

        assertThat(countMileageOfOrder(orderId)).as("založení zakázky odečet zapsalo").isEqualTo(1);

        orderService.delete(orderId, USER_ID);

        assertThat(countMileageOfOrder(orderId))
                .as("odečet odejde kaskádou spolu se zakázkou (V84)")
                .isZero();
    }

    @Test
    @DisplayName("smazání neexistující zakázky → ResourceNotFoundException (404)")
    void delete_unknownId_throwsResourceNotFound() {
        assertThatThrownBy(() -> orderService.delete(999_999L, USER_ID))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // =========================================================================
    // Pomocníci
    // =========================================================================

    private Long createOrder(Integer mileageKm) {
        OrderDto.CreateRequest request = new OrderDto.CreateRequest();
        request.setReceivedAt(LocalDate.now());
        request.setCustomerId(CUSTOMER_ID);
        request.setVehicleId(VEHICLE_ID);
        request.setDescription("Zakázka pro test mazání");
        request.setMileageKmAtIntake(mileageKm);
        return orderService.create(request, USER_ID).getId();
    }

    private Integer countItems(Long orderId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM \"order\".order_items WHERE order_id = ?", Integer.class, orderId);
    }

    private Integer countMileageOfOrder(Long orderId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM vehicle.mileage_history WHERE order_id = ?", Integer.class, orderId);
    }

    private BigDecimal remaining(Long batchId) {
        return jdbc.queryForObject(
                "SELECT quantity_remaining FROM warehouse.goods_receipt_items WHERE id = ?",
                BigDecimal.class, batchId);
    }

    private GoodsReceiptItemDto.ImportRequest importRequest(Long batchId, String quantity) {
        GoodsReceiptItemDto.ImportRequest request = new GoodsReceiptItemDto.ImportRequest();
        request.setGoodsReceiptItemId(batchId);
        request.setQuantity(new BigDecimal(quantity));
        return request;
    }

    private Long createConfirmedBatch() {
        Supplier supplier = Supplier.builder()
                .name("Mazání test dodavatel s.r.o.").registrationNumber("12123434")
                .countryCode("CZ").active(true).build();
        warehouseImportMapper.insertSupplier(supplier);

        Product product = Product.builder()
                .sku("MAZANI-TEST-SKU").name("Testovací díl mazání")
                .unit("ks").defaultVatRate(21).build();
        warehouseImportMapper.insertProduct(product);

        GoodsReceipt receipt = GoodsReceipt.builder()
                .supplierId(supplier.getId()).supplierNameSnapshot(supplier.getName())
                .invoiceNumber("MAZANI-FAK-001")
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

        warehouseImportMapper.insertMovement(StockMovement.builder()
                .productId(product.getId()).batchId(batch.getId())
                .movementType(MovementType.RECEIPT).quantity(new BigDecimal("4"))
                .createdBy(USER_ID).build());

        return batch.getId();
    }
}
