package cz.palo.autoservis.service;

import cz.palo.autoservis.AbstractIntegrationTest;
import cz.palo.autoservis.exception.ResourceNotFoundException;
import cz.palo.autoservis.mapper.OrderMapper;
import cz.palo.autoservis.mapper.WarehouseImportMapper;
import cz.palo.autoservis.mapper.WarehouseMapper;
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
import cz.palo.autoservis.model.dto.order.OrderItemSummaryDto;
import cz.palo.autoservis.model.dto.warehouse.GoodsReceiptItemDto;
import cz.palo.autoservis.model.enums.OrderItemType;
import org.junit.jupiter.api.BeforeEach;
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
 * Položky zakázky ({@code OrderItemServiceImpl}) — CRUD, souhrn a řazení. Doplňuje
 * {@code OrderItemImportTest} (výdej ze skladu) a {@code OrderItemInvoiceLockTest} (zámek fakturou).
 *
 * <p>Zvláštní pozornost: <strong>souhrn</strong> ({@code getSummaryByOrderId}) rozpočítává cenu
 * po typech (práce/materiál/služby) a je to podklad faktury; a <strong>mazání skladové položky</strong>
 * musí vygenerovat kompenzační pohyb {@code ISSUE_RETURN}, jinak by vydané zboží zmizelo ze skladu
 * i ze zakázky, aniž by se vrátilo.
 */
@Transactional
class OrderItemServiceTest extends AbstractIntegrationTest {

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
    private WarehouseMapper warehouseMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long orderId;

    @BeforeEach
    void createOrder() {
        Order order = Order.builder()
                .receivedAt(LocalDate.now())
                .customerId(CUSTOMER_ID).vehicleId(VEHICLE_ID)
                .description("Zakázka pro test položek")
                .estimatedPrice(new BigDecimal("1000")).createdBy(USER_ID)
                .build();
        orderMapper.insert(order);
        orderId = order.getId();
    }

    // =========================================================================
    // create / getById / getByOrderId
    // =========================================================================

    @Test
    @DisplayName("create uloží položku, doplní orderId a createdBy ze serveru")
    void create_persistsItemWithServerSideFields() {
        OrderItemDto.Response created = orderItemService.create(orderId, laborRequest("Práce", "2", "500.00"), USER_ID);

        assertThat(created.getId()).isNotNull();
        assertThat(created.getOrderId()).isEqualTo(orderId);
        assertThat(created.getName()).isEqualTo("Práce");
        assertThat(created.getQuantity()).isEqualByComparingTo("2");
        assertThat(created.getUnitPrice()).isEqualByComparingTo("500.00");
        assertThat(created.getCreatedBy()).isEqualTo(USER_ID);
        assertThat(created.isFromStock()).as("ruční položka není ze skladu").isFalse();
    }

    @Test
    @DisplayName("getByOrderId vrátí položky zakázky v pořadí pozic")
    void getByOrderId_returnsItemsInPositionOrder() {
        orderItemService.create(orderId, requestAt("Druhá", (short) 2), USER_ID);
        orderItemService.create(orderId, requestAt("První", (short) 1), USER_ID);

        List<OrderItemDto.Response> items = orderItemService.getByOrderId(orderId);

        assertThat(items).hasSize(2);
        assertThat(items).extracting(OrderItemDto.Response::getName)
                .containsExactly("První", "Druhá");
    }

    @Test
    @DisplayName("getById neexistující položky → ResourceNotFoundException (404)")
    void getById_unknownId_throwsResourceNotFound() {
        assertThatThrownBy(() -> orderItemService.getById(999_999L))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("getByOrderId(null) → IllegalArgumentException (fail-fast, TD-20)")
    void getByOrderId_nullId_throwsIllegalArgument() {
        assertThatThrownBy(() -> orderItemService.getByOrderId(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // =========================================================================
    // update
    // =========================================================================

    @Test
    @DisplayName("update ruční položky přepíše množství, cenu i typ")
    void update_manualItem_overwritesFields() {
        Long itemId = orderItemService.create(orderId, laborRequest("Práce", "2", "500.00"), USER_ID).getId();

        OrderItemDto.UpdateRequest request = new OrderItemDto.UpdateRequest();
        request.setName("Práce (rozšířená)");
        request.setItemType(OrderItemType.OTHER_SERVICES);
        request.setQuantity(new BigDecimal("3"));
        request.setUnit("hod");
        request.setUnitPrice(new BigDecimal("600.00"));
        request.setVatRate((short) 21);
        request.setPosition((short) 1);

        OrderItemDto.Response updated = orderItemService.update(itemId, request, USER_ID);

        assertThat(updated.getName()).isEqualTo("Práce (rozšířená)");
        assertThat(updated.getItemType()).isEqualTo(OrderItemType.OTHER_SERVICES);
        assertThat(updated.getQuantity()).isEqualByComparingTo("3");
        assertThat(updated.getUnitPrice()).isEqualByComparingTo("600.00");
    }

    @Test
    @DisplayName("update neexistující položky → ResourceNotFoundException (404)")
    void update_unknownId_throwsResourceNotFound() {
        OrderItemDto.UpdateRequest request = new OrderItemDto.UpdateRequest();
        request.setName("Cokoli");
        request.setUnitPrice(new BigDecimal("1.00"));
        request.setPosition((short) 1);

        assertThatThrownBy(() -> orderItemService.update(999_999L, request, USER_ID))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // =========================================================================
    // Změna množství vs. sklad (V83)
    // =========================================================================

    @Test
    @DisplayName("snížení množství u VYDANÉ položky vrátí rozdíl na sklad")
    void update_decreaseIssuedQuantity_returnsDifference() {
        GoodsReceiptItem batch = createBatchWithStock(new BigDecimal("10"));
        Long productId = batch.getProductId();
        Long itemId = orderItemService.importFromReceipt(
                orderId, List.of(importRequest(batch.getId(), "6")), USER_ID).getFirst().getId();
        orderItemService.issueStock(orderId, USER_ID);
        assertThat(onHand(productId)).isEqualByComparingTo("4");

        orderItemService.update(itemId, materialUpdate("4"), USER_ID);

        assertThat(onHand(productId)).as("dva kusy se vrátily do regálu").isEqualByComparingTo("6");
        assertThat(remaining(batch.getId())).isEqualByComparingTo("6");
    }

    @Test
    @DisplayName("zvýšení množství u VYDANÉ položky dovydá rozdíl ze skladu")
    void update_increaseIssuedQuantity_issuesDifference() {
        GoodsReceiptItem batch = createBatchWithStock(new BigDecimal("10"));
        Long productId = batch.getProductId();
        Long itemId = orderItemService.importFromReceipt(
                orderId, List.of(importRequest(batch.getId(), "3")), USER_ID).getFirst().getId();
        orderItemService.issueStock(orderId, USER_ID);
        assertThat(onHand(productId)).isEqualByComparingTo("7");

        orderItemService.update(itemId, materialUpdate("5"), USER_ID);

        assertThat(onHand(productId)).as("další dva kusy odešly").isEqualByComparingTo("5");
        assertThat(remaining(batch.getId())).isEqualByComparingTo("5");
    }

    @Test
    @DisplayName("změna množství u pouze REZERVOVANÉ položky sklad nehne — díl regál neopustil")
    void update_reservedQuantity_leavesStockUntouched() {
        GoodsReceiptItem batch = createBatchWithStock(new BigDecimal("10"));
        Long productId = batch.getProductId();
        Long itemId = orderItemService.importFromReceipt(
                orderId, List.of(importRequest(batch.getId(), "6")), USER_ID).getFirst().getId();

        OrderItemDto.Response updated = orderItemService.update(itemId, materialUpdate("2"), USER_ID);

        assertThat(updated.getQuantity())
                .as("u pouhé rezervace se množství musí dát opravit — díl regál neopustil")
                .isEqualByComparingTo("2");
        assertThat(onHand(productId)).isEqualByComparingTo("10");
        assertThat(remaining(batch.getId())).isEqualByComparingTo("10");
        Integer movements = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM warehouse.stock_movements WHERE batch_id = ? AND movement_type IN ('ISSUE','ISSUE_RETURN')",
                Integer.class, batch.getId());
        assertThat(movements).as("mění se jen slib, ne sklad").isZero();
    }

    /** Úprava skladové položky na dané množství; ostatní pole zůstávají použitelná. */
    private OrderItemDto.UpdateRequest materialUpdate(String quantity) {
        OrderItemDto.UpdateRequest request = new OrderItemDto.UpdateRequest();
        request.setName("Díl ze skladu");
        request.setItemType(OrderItemType.MATERIAL);
        request.setQuantity(new BigDecimal(quantity));
        request.setUnit("ks");
        request.setUnitPrice(new BigDecimal("100.00"));
        request.setVatRate((short) 21);
        request.setPosition((short) 1);
        return request;
    }

    // =========================================================================
    // Souhrn — rozpočet po typech
    // =========================================================================

    @Test
    @DisplayName("souhrn rozpočítá cenu po typech (práce / materiál / služby) a spočítá celek")
    void summary_splitsByItemType() {
        orderItemService.create(orderId, laborRequest("Práce", "2", "500.00"), USER_ID);      // 1000 práce
        orderItemService.create(orderId, materialRequest("Filtr", "1", "300.00"), USER_ID);   // 300 materiál
        orderItemService.create(orderId, serviceRequest("Doprava", "1", "200.00"), USER_ID);  // 200 služby

        OrderItemSummaryDto.Response summary = orderItemService.getSummaryByOrderId(orderId);

        assertThat(summary.getLaborNet()).isEqualByComparingTo("1000.00");
        assertThat(summary.getMaterialNet()).isEqualByComparingTo("300.00");
        assertThat(summary.getServiceNet()).isEqualByComparingTo("200.00");
        assertThat(summary.getTotalNet()).isEqualByComparingTo("1500.00");
        assertThat(summary.getTotalGross())
                .as("21 % DPH: 1500 × 1.21 = 1815").isEqualByComparingTo("1815.00");
        assertThat(summary.getTotalCost())
                .as("bez zadané nákupní ceny je náklad 0 (COALESCE)").isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("souhrn sečte náklad (množství × nákupní cena) — podklad pro marži")
    void summary_aggregatesCost() {
        OrderItemDto.CreateRequest material = materialRequest("Filtr", "3", "300.00"); // tržba 3×300 = 900
        material.setPurchasePrice(new BigDecimal("100.00"));                            // náklad 3×100 = 300
        orderItemService.create(orderId, material, USER_ID);

        OrderItemSummaryDto.Response summary = orderItemService.getSummaryByOrderId(orderId);

        assertThat(summary.getMaterialNet()).isEqualByComparingTo("900.00");
        assertThat(summary.getMaterialCost()).isEqualByComparingTo("300.00");
        assertThat(summary.getTotalCost()).isEqualByComparingTo("300.00");
        assertThat(summary.getMaterialNet().subtract(summary.getMaterialCost()))
                .as("marže bez DPH = tržba − náklad = 900 − 300").isEqualByComparingTo("600.00");
    }

    @Test
    @DisplayName("souhrn prázdné zakázky je nulový, ne null")
    void summary_emptyOrder_isZero() {
        OrderItemSummaryDto.Response summary = orderItemService.getSummaryByOrderId(orderId);

        assertThat(summary).isNotNull();
        assertThat(summary.getTotalNet()).isEqualByComparingTo("0");
        assertThat(summary.getTotalGross()).isEqualByComparingTo("0");
        assertThat(summary.getTotalCost()).isEqualByComparingTo("0");
    }

    // =========================================================================
    // delete
    // =========================================================================

    @Test
    @DisplayName("delete ruční položky ji odstraní")
    void delete_manualItem_removesIt() {
        Long itemId = orderItemService.create(orderId, laborRequest("Práce", "2", "500.00"), USER_ID).getId();

        orderItemService.delete(itemId, USER_ID);

        assertThat(orderItemService.getByOrderId(orderId)).isEmpty();
    }

    @Test
    @DisplayName("delete VYDANÉ položky vrátí zboží na sklad kompenzačním pohybem ISSUE_RETURN")
    void delete_issuedStockItem_returnsGoodsToStock() {
        GoodsReceiptItem batch = createBatchWithStock(new BigDecimal("10"));
        Long productId = batch.getProductId();
        assertThat(onHand(productId)).isEqualByComparingTo("10");

        // Import je jen rezervace — sklad se zatím nehne (V83).
        List<OrderItemDto.Response> imported = orderItemService.importFromReceipt(
                orderId, List.of(importRequest(batch.getId(), "4")), USER_ID);
        assertThat(onHand(productId)).as("import nic neodečte").isEqualByComparingTo("10");
        Long stockItemId = imported.getFirst().getId();

        // Teprve výdej sníží sklad → 6, zbytek šarže 6.
        orderItemService.issueStock(orderId, USER_ID);
        assertThat(onHand(productId)).isEqualByComparingTo("6");
        assertThat(remaining(batch.getId())).isEqualByComparingTo("6");

        // Smazání vydané položky → ISSUE_RETURN vrátí 4 ks zpět.
        orderItemService.delete(stockItemId, USER_ID);

        assertThat(onHand(productId)).as("sklad je zpět na 10").isEqualByComparingTo("10");
        assertThat(remaining(batch.getId())).as("zbytek šarže je zpět na 10").isEqualByComparingTo("10");

        Integer returnMovements = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM warehouse.stock_movements WHERE batch_id = ? AND movement_type = 'ISSUE_RETURN'",
                Integer.class, batch.getId());
        assertThat(returnMovements).as("vznikl právě jeden kompenzační pohyb").isEqualTo(1);
    }

    @Test
    @DisplayName("delete pouze REZERVOVANÉ položky nezaloží žádný pohyb — díl regál neopustil")
    void delete_reservedStockItem_createsNoMovement() {
        GoodsReceiptItem batch = createBatchWithStock(new BigDecimal("10"));
        Long productId = batch.getProductId();

        List<OrderItemDto.Response> imported = orderItemService.importFromReceipt(
                orderId, List.of(importRequest(batch.getId(), "4")), USER_ID);

        orderItemService.delete(imported.getFirst().getId(), USER_ID);

        assertThat(onHand(productId)).as("sklad se nehnul ani po smazání").isEqualByComparingTo("10");
        assertThat(remaining(batch.getId())).isEqualByComparingTo("10");

        // Dřív se vratka zakládala vždycky — u pouhé rezervace by tím na sklad přidala
        // zboží, které z něj nikdy nebylo vydáno.
        Integer movements = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM warehouse.stock_movements WHERE batch_id = ? AND movement_type IN ('ISSUE','ISSUE_RETURN')",
                Integer.class, batch.getId());
        assertThat(movements).as("žádný výdej ani vratka").isZero();
    }

    @Test
    @DisplayName("delete neexistující položky → ResourceNotFoundException (404)")
    void delete_unknownId_throwsResourceNotFound() {
        assertThatThrownBy(() -> orderItemService.delete(999_999L, USER_ID))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // =========================================================================
    // reorder
    // =========================================================================

    @Test
    @DisplayName("reorder přeskládá pozice položek zakázky")
    void reorder_changesPositions() {
        Long first = orderItemService.create(orderId, requestAt("A", (short) 1), USER_ID).getId();
        Long second = orderItemService.create(orderId, requestAt("B", (short) 2), USER_ID).getId();

        OrderItemDto.ReorderRequest r1 = new OrderItemDto.ReorderRequest();
        r1.setId(second);
        r1.setPosition((short) 1);
        OrderItemDto.ReorderRequest r2 = new OrderItemDto.ReorderRequest();
        r2.setId(first);
        r2.setPosition((short) 2);

        orderItemService.reorder(orderId, List.of(r1, r2));

        assertThat(orderItemService.getByOrderId(orderId)).extracting(OrderItemDto.Response::getName)
                .containsExactly("B", "A");
    }

    @Test
    @DisplayName("reorder prázdného seznamu nic nedělá a nespadne")
    void reorder_emptyList_isNoOp() {
        orderItemService.create(orderId, requestAt("A", (short) 1), USER_ID);

        orderItemService.reorder(orderId, List.of());

        assertThat(orderItemService.getByOrderId(orderId)).extracting(OrderItemDto.Response::getName)
                .containsExactly("A");
    }

    @Test
    @DisplayName("reorder(null orderId) → IllegalArgumentException")
    void reorder_nullOrderId_throwsIllegalArgument() {
        assertThatThrownBy(() -> orderItemService.reorder(null, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // =========================================================================
    // Fixtury
    // =========================================================================

    private BigDecimal onHand(Long productId) {
        return jdbcTemplate.queryForObject(
                "SELECT quantity_on_hand FROM warehouse.products WHERE id = ?", BigDecimal.class, productId);
    }

    private BigDecimal remaining(Long batchId) {
        return jdbcTemplate.queryForObject(
                "SELECT quantity_remaining FROM warehouse.goods_receipt_items WHERE id = ?", BigDecimal.class, batchId);
    }

    /** Dodavatel + produkt + potvrzená příjemka + šarže + RECEIPT pohyb (stav = quantity). */
    private GoodsReceiptItem createBatchWithStock(BigDecimal quantity) {
        Supplier supplier = Supplier.builder()
                .name("Dodavatel OI").registrationNumber("24787426").countryCode("CZ").active(true).build();
        warehouseImportMapper.insertSupplier(supplier);

        Product product = Product.builder()
                .sku("OI-SKU-1").name("Olejový filtr").unit("ks").defaultVatRate(21)
                .salePrice(new BigDecimal("199.00")).build();
        warehouseImportMapper.insertProduct(product);

        GoodsReceipt receipt = GoodsReceipt.builder()
                .supplierId(supplier.getId()).supplierNameSnapshot(supplier.getName())
                .invoiceNumber("OI-FAK-1")
                .subtotal(new BigDecimal("100.00")).vatAmount(new BigDecimal("21.00"))
                .totalAmount(new BigDecimal("121.00")).currency("CZK")
                .documentType(DocumentType.INVOICE).sourceChannel(ReceiptSource.AI_PDF)
                .status(ReceiptStatus.CONFIRMED).reconciliationOk(true).createdBy(USER_ID).build();
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
                .movementType(MovementType.RECEIPT).quantity(quantity).createdBy(USER_ID).build());

        return batch;
    }

    private static GoodsReceiptItemDto.ImportRequest importRequest(Long batchId, String qty) {
        GoodsReceiptItemDto.ImportRequest request = new GoodsReceiptItemDto.ImportRequest();
        request.setGoodsReceiptItemId(batchId);
        request.setQuantity(new BigDecimal(qty));
        return request;
    }

    private static OrderItemDto.CreateRequest laborRequest(String name, String qty, String price) {
        return itemRequest(name, qty, price, OrderItemType.LABOR, "hod", (short) 1);
    }

    private static OrderItemDto.CreateRequest materialRequest(String name, String qty, String price) {
        return itemRequest(name, qty, price, OrderItemType.MATERIAL, "ks", (short) 1);
    }

    private static OrderItemDto.CreateRequest serviceRequest(String name, String qty, String price) {
        return itemRequest(name, qty, price, OrderItemType.OTHER_SERVICES, "ks", (short) 1);
    }

    private static OrderItemDto.CreateRequest requestAt(String name, short position) {
        return itemRequest(name, "1", "100.00", OrderItemType.LABOR, "hod", position);
    }

    private static OrderItemDto.CreateRequest itemRequest(String name, String qty, String price,
                                                          OrderItemType type, String unit, short position) {
        OrderItemDto.CreateRequest request = new OrderItemDto.CreateRequest();
        request.setItemType(type);
        request.setName(name);
        request.setQuantity(new BigDecimal(qty));
        request.setUnit(unit);
        request.setUnitPrice(new BigDecimal(price));
        request.setVatRate((short) 21);
        request.setPosition(position);
        return request;
    }
}
