package cz.palo.autoservis.service;

import cz.palo.autoservis.AbstractIntegrationTest;
import cz.palo.autoservis.exception.BusinessRuleException;
import cz.palo.autoservis.mapper.GoodsReceiptMapper;
import cz.palo.autoservis.mapper.OrderItemMapper;
import cz.palo.autoservis.mapper.OrderMapper;
import cz.palo.autoservis.mapper.WarehouseImportMapper;
import cz.palo.autoservis.model.domain.order.Order;
import cz.palo.autoservis.model.domain.order.OrderItem;
import cz.palo.autoservis.model.domain.warehouse.DocumentType;
import cz.palo.autoservis.model.domain.warehouse.GoodsReceipt;
import cz.palo.autoservis.model.domain.warehouse.GoodsReceiptItem;
import cz.palo.autoservis.model.domain.warehouse.MovementType;
import cz.palo.autoservis.model.domain.warehouse.Product;
import cz.palo.autoservis.model.domain.warehouse.ReceiptSource;
import cz.palo.autoservis.model.domain.warehouse.ReceiptStatus;
import cz.palo.autoservis.model.domain.warehouse.StockMovement;
import cz.palo.autoservis.model.domain.warehouse.Supplier;
import cz.palo.autoservis.model.dto.billing.CreditNoteDto;
import cz.palo.autoservis.model.dto.billing.InvoiceDto;
import cz.palo.autoservis.model.dto.order.OrderDto;
import cz.palo.autoservis.model.dto.order.OrderItemDto;
import cz.palo.autoservis.model.dto.warehouse.GoodsReceiptItemDto;
import cz.palo.autoservis.model.enums.InvoiceStatus;
import cz.palo.autoservis.model.enums.OrderItemType;
import cz.palo.autoservis.model.enums.OrderStatus;
import cz.palo.autoservis.model.enums.PaymentMethod;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static cz.palo.autoservis.service.InvoiceIssuing.issueWithNextNumber;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Stavový automat zakázky v service vrstvě (audit KN-11) — druhá polovina k unit testu
 * {@code OrderStatusTest}. Enum rozhoduje o tvaru workflow, tady se testují podmínky závislé
 * na stavu databáze, které enum unést nemůže:
 *
 * <ul>
 *   <li>zakázku s <strong>aktivní</strong> fakturou nelze zrušit — a po stornu konceptu i po
 *       vystavení dobropisu (V69) už zrušit lze,</li>
 *   <li>zakázku držící materiál vydaný ze skladu nelze zrušit, dokud se materiál nevrátí,</li>
 *   <li>nezměněný stav není přechod, takže popis uzavřené zakázky zůstává editovatelný.</li>
 * </ul>
 *
 * <p>{@code @Transactional} — každý test běží v transakci, která se na konci rollbackne, takže
 * DB zůstává čistá bez ohledu na pořadí testů (vzor {@code InvoiceStatusTransitionTest}).
 */
@Transactional
class OrderStatusTransitionTest extends AbstractIntegrationTest {

    private static final Long USER_ID = 1L;
    /** Seed: `customer.addresses` id=2 je BILLING adresa zákazníka 1. */
    private static final Long BILLING_ADDRESS_ID = 2L;

    @Autowired
    private OrderService orderService;

    @Autowired
    private OrderItemService orderItemService;

    @Autowired
    private InvoiceService invoiceService;

    @Autowired
    private CreditNoteService creditNoteService;

    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private OrderItemMapper orderItemMapper;

    @Autowired
    private WarehouseImportMapper warehouseImportMapper;

    @Autowired
    private GoodsReceiptMapper goodsReceiptMapper;

    private Long orderId;

    /**
     * Zakázka pro seed zákazníka 1 / vozidlo 1 s jednou položkou práce — položka je potřeba,
     * aby ze zakázky šla vystavit faktura. Stav {@code RECEIVED} přiděluje DB default.
     */
    @BeforeEach
    void createOrderWithLaborItem() {
        Order order = Order.builder()
                .receivedAt(LocalDate.now())
                .customerId(1L)
                .vehicleId(1L)
                .description("KN-11 test — stavový automat zakázky")
                .estimatedPrice(new BigDecimal("1000"))
                .createdBy(USER_ID)
                .build();
        orderMapper.insert(order);
        orderId = order.getId();

        OrderItem labor = OrderItem.builder()
                .orderId(orderId)
                .itemType(OrderItemType.LABOR)
                .name("Testovací práce")
                .quantity(BigDecimal.ONE)
                .unit("hod")
                .unitPrice(new BigDecimal("500"))
                .vatRate((short) 21)
                .position((short) 1)
                .createdBy(USER_ID)
                .build();
        orderItemMapper.insert(labor);

        assertThat(orderMapper.findById(orderId).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.RECEIVED);
    }

    // =========================================================================
    // Přechody mezi stavy
    // =========================================================================

    @Test
    @DisplayName("provozní stavy: pohyb dopředu i zpátky projde (díl přijde poškozený → zpět na čekání)")
    void operationalStatuses_moveBothWays() {
        assertThat(changeStatus(OrderStatus.WAITING_FOR_PARTS).getStatus())
                .isEqualTo(OrderStatus.WAITING_FOR_PARTS);
        assertThat(changeStatus(OrderStatus.IN_PROGRESS).getStatus())
                .isEqualTo(OrderStatus.IN_PROGRESS);
        assertThat(changeStatus(OrderStatus.WAITING_FOR_PARTS).getStatus())
                .as("zpátky na čekání na díly musí jít")
                .isEqualTo(OrderStatus.WAITING_FOR_PARTS);
    }

    @Test
    @DisplayName("dokončenou zakázku bez faktury lze vrátit do provozu")
    void completedOrder_withoutInvoice_canBeReopened() {
        changeStatus(OrderStatus.COMPLETED);

        // Do 2026-08-06 byl COMPLETED slepá ulička: omylem kliknuté „Dokončena" nešlo vzít
        // zpět ani zrušit, a protože zakázka neměla mazání, zůstalo to v evidenci navždy.
        assertThat(changeStatus(OrderStatus.IN_PROGRESS).getStatus())
                .isEqualTo(OrderStatus.IN_PROGRESS);
        assertThat(orderMapper.findById(orderId).orElseThrow().getCompletedAt())
                .as("znovuotevřená zakázka už není hotová, datum dokončení se vynuluje")
                .isNull();
    }

    @Test
    @DisplayName("zakázku bez položek nelze dokončit → ORDER_HAS_NO_ITEMS")
    void complete_withoutItems_isRejected() {
        // Zakázka ze setupu má položku práce, tak si vyrobíme prázdnou.
        Order empty = Order.builder()
                .receivedAt(LocalDate.now())
                .customerId(1L).vehicleId(1L)
                .description("Prázdná zakázka").createdBy(USER_ID).build();
        orderMapper.insert(empty);

        OrderDto.StatusRequest request = new OrderDto.StatusRequest();
        request.setStatus(OrderStatus.COMPLETED);

        assertThatThrownBy(() -> orderService.changeStatus(empty.getId(), request, USER_ID))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(ex -> assertThat(((BusinessRuleException) ex).getRuleCode())
                        .isEqualTo("ORDER_HAS_NO_ITEMS"));

        assertThat(orderMapper.findById(empty.getId()).orElseThrow().getStatus())
                .as("prázdná zakázka by jako hotová práce v přehledech lhala")
                .isNotEqualTo(OrderStatus.COMPLETED);
    }

    @Test
    @DisplayName("dokončení doplní datum dokončení samo")
    void complete_setsCompletedAtAutomatically() {
        assertThat(orderMapper.findById(orderId).orElseThrow().getCompletedAt()).isNull();

        changeStatus(OrderStatus.COMPLETED);

        // Dřív ho obsluha musela vyplnit ručně, takže u většiny zakázek zůstávalo prázdné.
        assertThat(orderMapper.findById(orderId).orElseThrow().getCompletedAt()).isNotNull();
    }

    @Test
    @DisplayName("zakázku s aktivní fakturou nelze znovu otevřít → ORDER_REOPEN_BLOCKED_BY_INVOICE")
    void reopen_withActiveInvoice_isRejected() {
        changeStatus(OrderStatus.COMPLETED);
        createInvoice();

        assertThatThrownBy(() -> changeStatus(OrderStatus.IN_PROGRESS))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(ex -> assertThat(((BusinessRuleException) ex).getRuleCode())
                        .isEqualTo("ORDER_REOPEN_BLOCKED_BY_INVOICE"));

        assertThat(orderMapper.findById(orderId).orElseThrow().getStatus())
                .as("rozpracovaná práce vedle dokladu, který ji vyúčtoval jako hotovou, stát nesmí")
                .isEqualTo(OrderStatus.COMPLETED);
    }

    @Test
    @DisplayName("znovuotevření vrátí výdej do rezervace — díl zůstává na autě, sklad se nehne")
    void reopen_returnsIssuedMaterialToReservation() {
        Long batchId = createConfirmedBatch();
        orderItemService.importFromReceipt(orderId, List.of(importRequest(batchId, "4")), USER_ID);
        changeStatus(OrderStatus.COMPLETED);
        assertThat(goodsReceiptMapper.findById(batchId).orElseThrow().getQuantityRemaining())
                .as("dokončení materiál vydalo").isEqualByComparingTo("0");

        changeStatus(OrderStatus.IN_PROGRESS);

        assertThat(goodsReceiptMapper.findById(batchId).orElseThrow().getQuantityRemaining())
                .as("výdej se vrátil do rezervace — jinak by opakované dokončení odepsalo dvakrát")
                .isEqualByComparingTo("4");

        // A při dalším dokončení se vydá znovu, netto tedy jednou.
        changeStatus(OrderStatus.COMPLETED);
        assertThat(goodsReceiptMapper.findById(batchId).orElseThrow().getQuantityRemaining())
                .isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("vyhrazená změna stavu prochází toutéž brankou jako PUT")
    void changeStatus_usesSameGate() {
        changeStatus(OrderStatus.CANCELLED);

        OrderDto.StatusRequest request = new OrderDto.StatusRequest();
        request.setStatus(OrderStatus.IN_PROGRESS);

        assertThatThrownBy(() -> orderService.changeStatus(orderId, request, USER_ID))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(ex -> assertThat(((BusinessRuleException) ex).getRuleCode())
                        .isEqualTo("INVALID_STATUS_TRANSITION"));
    }

    @Test
    @DisplayName("zrušenou zakázku nelze oživit ani dokončit → INVALID_STATUS_TRANSITION")
    void cancelledOrder_isTerminal() {
        changeStatus(OrderStatus.CANCELLED);

        assertThatThrownBy(() -> changeStatus(OrderStatus.RECEIVED))
                .isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> changeStatus(OrderStatus.COMPLETED))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    @DisplayName("nezměněný stav není přechod — popis a ceny dokončené zakázky jdou dál upravit")
    void unchangedStatus_onCompletedOrder_stillAllowsEditingFields() {
        changeStatus(OrderStatus.COMPLETED);

        OrderDto.UpdateRequest request = updateRequest(OrderStatus.COMPLETED);
        request.setDescription("Doplněný popis po dokončení");
        request.setFinalPrice(new BigDecimal("2500.00"));

        OrderDto.DetailResponse updated = orderService.update(orderId, request, USER_ID);

        assertThat(updated.getStatus()).isEqualTo(OrderStatus.COMPLETED);
        assertThat(updated.getDescription()).isEqualTo("Doplněný popis po dokončení");
        assertThat(updated.getFinalPrice()).isEqualByComparingTo("2500.00");
    }

    // =========================================================================
    // Zrušení vs. faktura
    // =========================================================================

    @Test
    @DisplayName("zakázku s vystavenou fakturou nelze zrušit → ORDER_HAS_ACTIVE_INVOICE, hláška nese číslo dokladu")
    void cancel_withIssuedInvoice_isRejected() {
        InvoiceDto.DetailResponse invoice = createInvoice();
        String invoiceNumber = issueWithNextNumber(invoiceService, invoice.getId(), USER_ID).getInvoiceNumber();

        assertThatThrownBy(() -> changeStatus(OrderStatus.CANCELLED))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(ex -> {
                    BusinessRuleException bre = (BusinessRuleException) ex;
                    assertThat(bre.getRuleCode()).isEqualTo("ORDER_HAS_ACTIVE_INVOICE");
                    // Celá vazba, ne jen číslo dokladu: první verze skládala popis v 1. pádě
                    // a hláška v prohlížeči zněla „má faktura 202607007" (odhalil proklik,
                    // protože test kontroloval jen výskyt čísla).
                    assertThat(bre.getMessage()).contains("má fakturu " + invoiceNumber);
                    assertThat(bre.getParams()).containsEntry("invoiceId", invoice.getId());
                });

        // Zakázka je COMPLETED — fakturovat lze až dokončenou (2026-08-05), takže ji tam
        // vystavení faktury v setupu přepnulo. Podstatné je, že zrušení neprošlo.
        assertThat(orderMapper.findById(orderId).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.COMPLETED);
    }

    @Test
    @DisplayName("u konceptu faktury hláška mluví o „konceptu faktury“ a radí storno (02/F-7)")
    void cancel_withDraftInvoice_namesTheDraftAndAdvisesCancellation() {
        // Koncept číslo nemá (dostane ho až při vystavení), takže hlášku skládá
        // Invoice.describe() — bez něj tu obsluha kdysi četla „faktura null".
        // Rada se řídí stavem: koncept → storno.
        InvoiceDto.DetailResponse invoice = createInvoice();
        assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.DRAFT);
        assertThat(invoice.getInvoiceNumber()).as("koncept číslo nemá").isNull();

        assertThatThrownBy(() -> changeStatus(OrderStatus.CANCELLED))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(ex -> assertThat(ex.getMessage())
                        .contains("má koncept faktury")
                        .contains("stornujte")
                        .doesNotContain("null"));
    }

    @Test
    @DisplayName("po smazání konceptu faktury už zakázku zrušit lze")
    void cancel_afterDraftInvoiceDeleted_isAllowed() {
        InvoiceDto.DetailResponse invoice = createInvoice();
        invoiceService.delete(invoice.getId(), USER_ID);

        assertThat(changeStatus(OrderStatus.CANCELLED).getStatus())
                .isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    @DisplayName("po vystavení dobropisu už zakázku zrušit lze — dobropisovaná faktura není aktivní (V69)")
    void cancel_afterCreditNoteIssued_isAllowed() {
        InvoiceDto.DetailResponse invoice = createInvoice();
        issueWithNextNumber(invoiceService, invoice.getId(), USER_ID);

        // Dobropis opravuje doklad, který zákazník DOSTAL (2026-08-08) — u nepředané faktury
        // není co opravovat, ta se smaže a vystaví znovu.
        invoiceService.handOver(invoice.getId(), USER_ID);

        CreditNoteDto.CreateRequest creditNote = new CreditNoteDto.CreateRequest();
        creditNote.setOriginalInvoiceId(invoice.getId());
        creditNote.setCorrectionReason("Reklamace celé opravy");
        CreditNoteDto.DetailResponse created = creditNoteService.createFromInvoice(creditNote, USER_ID);
        creditNoteService.issue(created.getId(), USER_ID);

        assertThat(changeStatus(OrderStatus.CANCELLED).getStatus())
                .isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    @DisplayName("dokončení zakázky faktura neblokuje — guard je jen na zrušení")
    void complete_withIssuedInvoice_isAllowed() {
        InvoiceDto.DetailResponse invoice = createInvoice();
        issueWithNextNumber(invoiceService, invoice.getId(), USER_ID);

        assertThat(changeStatus(OrderStatus.COMPLETED).getStatus())
                .isEqualTo(OrderStatus.COMPLETED);
    }

    // =========================================================================
    // Zrušení vs. materiál vydaný ze skladu
    // =========================================================================

    @Test
    @DisplayName("zakázku s vydaným materiálem nelze zrušit → ORDER_HAS_ISSUED_MATERIAL s výčtem položek")
    void cancel_withIssuedMaterial_isRejectedAndListsBlockingItems() {
        Long batchId = createConfirmedBatch();
        List<OrderItemDto.Response> imported =
                orderItemService.importFromReceipt(orderId, List.of(importRequest(batchId, "4")), USER_ID);
        // Blokuje až VÝDEJ, ne import — do zavedení rezervací stačilo naimportovat (V83).
        orderItemService.issueStock(orderId, USER_ID);

        assertThatThrownBy(() -> changeStatus(OrderStatus.CANCELLED))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(ex -> {
                    BusinessRuleException bre = (BusinessRuleException) ex;
                    assertThat(bre.getRuleCode()).isEqualTo("ORDER_HAS_ISSUED_MATERIAL");
                    assertThat(bre.getMessage())
                            .as("obsluha musí vidět, která položka blokuje, a co s tím")
                            .contains("Testovací díl KN-11")
                            .contains("4 ks");
                    assertThat(bre.getParams()).containsEntry(
                            "orderItemIds", List.of(imported.getFirst().getId()));
                });

        assertThat(orderMapper.findById(orderId).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.RECEIVED);
    }

    @Test
    @DisplayName("zakázku s pouhou REZERVACÍ lze zrušit — ze skladu nic neodešlo")
    void cancel_withReservedMaterialOnly_isAllowed() {
        Long batchId = createConfirmedBatch();
        orderItemService.importFromReceipt(orderId, List.of(importRequest(batchId, "4")), USER_ID);

        // Materiál se nevydával — díl leží v regálu, zakázka drží jen slib. Do zavedení
        // rezervací tady padalo ORDER_HAS_ISSUED_MATERIAL, přestože se sklad ani nehnul,
        // a hláška o takovém dílu tvrdila „vydaný ze skladu".
        assertThat(changeStatus(OrderStatus.CANCELLED).getStatus())
                .isEqualTo(OrderStatus.CANCELLED);

        assertThat(goodsReceiptMapper.findById(batchId).orElseThrow().getQuantityRemaining())
                .as("zrušením se rezervace jen uvolní, do skladu se nezapisuje nic")
                .isEqualByComparingTo("4");
    }

    @Test
    @DisplayName("po vrácení VYDANÉHO materiálu (smazání položky) se šarže obnoví a zrušení projde")
    void cancel_afterMaterialReturned_isAllowedAndStockIsBack() {
        Long batchId = createConfirmedBatch();
        Long materialItemId = orderItemService
                .importFromReceipt(orderId, List.of(importRequest(batchId, "4")), USER_ID)
                .getFirst().getId();

        // Import je rezervace — šarže se zatím nehne (V83). Teprve výdej ji vyprázdní.
        assertThat(goodsReceiptMapper.findById(batchId).orElseThrow().getQuantityRemaining())
                .as("po importu je materiál jen rezervovaný, ne vydaný")
                .isEqualByComparingTo("4");
        orderItemService.issueStock(orderId, USER_ID);
        assertThat(goodsReceiptMapper.findById(batchId).orElseThrow().getQuantityRemaining())
                .isEqualByComparingTo("0");

        orderItemService.delete(materialItemId, USER_ID); // vytvoří pohyb ISSUE_RETURN

        assertThat(goodsReceiptMapper.findById(batchId).orElseThrow().getQuantityRemaining())
                .as("materiál je zpátky na šarži")
                .isEqualByComparingTo("4");
        assertThat(changeStatus(OrderStatus.CANCELLED).getStatus())
                .isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    @DisplayName("detail zakázky nese aktivní fakturu — dialog zrušení podle ní volí storno konceptu vs. dobropis")
    void detail_carriesActiveInvoice() {
        assertThat(orderService.getById(orderId).getInvoiceStatus())
                .as("nefakturovaná zakázka fakturu nemá")
                .isNull();

        InvoiceDto.DetailResponse draft = createInvoice();
        OrderDto.DetailResponse withDraft = orderService.getById(orderId);
        assertThat(withDraft.getInvoiceStatus()).isEqualTo(InvoiceStatus.DRAFT);
        assertThat(withDraft.getInvoiceId()).isEqualTo(draft.getId());

        String number = issueWithNextNumber(invoiceService, draft.getId(), USER_ID).getInvoiceNumber();
        invoiceService.handOver(draft.getId(), USER_ID);
        assertThat(orderService.getById(orderId).getInvoiceStatus()).isEqualTo(InvoiceStatus.ISSUED);

        // Dobropis fakturu z pohledu zakázky umlčí — týž predikát jako uq_invoices_order_active,
        // takže detail od té chvíle hlásí "nefakturováno" a zrušení je zase v nabídce.
        CreditNoteDto.CreateRequest creditNote = new CreditNoteDto.CreateRequest();
        creditNote.setOriginalInvoiceId(draft.getId());
        creditNote.setCorrectionReason("Zakázka zrušena po dohodě se zákazníkem (" + number + ")");
        creditNoteService.issue(creditNoteService.createFromInvoice(creditNote, USER_ID).getId(), USER_ID);

        assertThat(orderService.getById(orderId).getInvoiceStatus())
                .as("dobropisovaná faktura zakázku už neblokuje, detail ji tedy nemá hlásit")
                .isNull();
    }

    @Test
    @DisplayName("POST /cancel vrátí VŠECHEN vydaný materiál a zruší zakázku jedním voláním")
    void cancelEndpoint_returnsAllIssuedMaterialAndCancels() {
        Long batchId = createConfirmedBatch();
        orderItemService.importFromReceipt(orderId, List.of(importRequest(batchId, "4")), USER_ID);
        orderItemService.issueStock(orderId, USER_ID);
        assertThat(goodsReceiptMapper.findById(batchId).orElseThrow().getQuantityRemaining())
                .isEqualByComparingTo("0");

        OrderDto.DetailResponse cancelled = orderService.cancel(orderId, USER_ID);

        assertThat(cancelled.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(goodsReceiptMapper.findById(batchId).orElseThrow().getQuantityRemaining())
                .as("ze zrušené zakázky se vrací všechno — díly, které zůstaly na voze, "
                        + "zákazník zaplatí na NOVÉ zakázce")
                .isEqualByComparingTo("4");
        assertThat(orderItemMapper.findIssuedByOrderId(orderId))
                .as("po vrácení nedrží zakázka žádný vydaný materiál")
                .isEmpty();
    }

    @Test
    @DisplayName("POST /cancel s aktivní fakturou odmítne a materiál NEVRÁTÍ (faktura se kontroluje první)")
    void cancelEndpoint_withActiveInvoice_isRejectedAndKeepsMaterialIssued() {
        Long batchId = createConfirmedBatch();
        orderItemService.importFromReceipt(orderId, List.of(importRequest(batchId, "4")), USER_ID);
        orderItemService.issueStock(orderId, USER_ID);
        issueWithNextNumber(invoiceService, createInvoice().getId(), USER_ID);

        assertThatThrownBy(() -> orderService.cancel(orderId, USER_ID))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(e -> assertThat(((BusinessRuleException) e).getRuleCode())
                        .isEqualTo("ORDER_HAS_ACTIVE_INVOICE"));

        assertThat(goodsReceiptMapper.findById(batchId).orElseThrow().getQuantityRemaining())
                .as("nemá smysl vracet materiál, když zrušení stejně neprojde")
                .isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("dokončení zakázky s vydaným materiálem projde — díl je namontovaný, ne vrácený")
    void complete_withIssuedMaterial_isAllowed() {
        Long batchId = createConfirmedBatch();
        orderItemService.importFromReceipt(orderId, List.of(importRequest(batchId, "4")), USER_ID);

        assertThat(changeStatus(OrderStatus.COMPLETED).getStatus())
                .isEqualTo(OrderStatus.COMPLETED);
    }

    // =========================================================================
    // Výdej materiálu při dokončení (V83)
    // =========================================================================

    @Test
    @DisplayName("dokončení vydá materiál, který na zakázce ležel jen jako rezervace")
    void complete_issuesReservedMaterial() {
        Long batchId = createConfirmedBatch();
        orderItemService.importFromReceipt(orderId, List.of(importRequest(batchId, "4")), USER_ID);

        assertThat(goodsReceiptMapper.findById(batchId).orElseThrow().getQuantityRemaining())
                .as("import sám o sobě je jen rezervace — šarže se nehnula")
                .isEqualByComparingTo("4");

        changeStatus(OrderStatus.COMPLETED);

        assertThat(goodsReceiptMapper.findById(batchId).orElseThrow().getQuantityRemaining())
                .as("dokončení je okamžik, kdy materiál fyzicky odejde")
                .isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("chybí-li rezervovaný díl na skladě, dokončení neprojde a stav zůstane nezměněný")
    void complete_whenReservedPartIsGone_isRejectedAndStatusUnchanged() {
        Long batchId = createConfirmedBatch();
        orderItemService.importFromReceipt(orderId, List.of(importRequest(batchId, "4")), USER_ID);

        // Rezervace šarži nezamyká — drží jen dostupnost. Inventurní odpis ji tedy může
        // mezitím vyprázdnit a při dokončení nebude co vydat.
        GoodsReceiptItem batch = goodsReceiptMapper.findById(batchId).orElseThrow();
        StockMovement writeOff = new StockMovement();
        writeOff.setProductId(batch.getProductId());
        writeOff.setBatchId(batchId);
        writeOff.setMovementType(MovementType.WRITE_OFF);
        writeOff.setQuantity(new BigDecimal("-4"));
        writeOff.setNote("Inventurní odpis");
        writeOff.setCreatedBy(USER_ID);
        warehouseImportMapper.insertMovement(writeOff);

        assertThatThrownBy(() -> changeStatus(OrderStatus.COMPLETED))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(ex -> assertThat(((BusinessRuleException) ex).getRuleCode())
                        .isEqualTo("STOCK_MISSING_FOR_ISSUE"));

        assertThat(orderMapper.findById(orderId).orElseThrow().getStatus())
                .as("dokončená zakázka s materiálem, který na skladě není, by rozešla papír a regál")
                .isNotEqualTo(OrderStatus.COMPLETED);
    }

    @Test
    @DisplayName("opakované uložení dokončené zakázky materiál nevydá podruhé")
    void completedStatusIdentity_doesNotIssueTwice() {
        Long batchId = createConfirmedBatch();
        Long itemId = orderItemService
                .importFromReceipt(orderId, List.of(importRequest(batchId, "4")), USER_ID)
                .getFirst().getId();

        changeStatus(OrderStatus.COMPLETED);
        assertThat(warehouseImportMapper.findIssuedQuantityByOrderItemId(itemId))
                .isEqualByComparingTo("4");

        // Oprava překlepu v popisu přijde se stejným stavem — výdej se spouští jen na
        // skutečném přechodu, ne na identitě.
        OrderDto.UpdateRequest request = updateRequest(OrderStatus.COMPLETED);
        request.setDescription("Doplněný popis po dokončení");
        orderService.update(orderId, request, USER_ID);

        assertThat(warehouseImportMapper.findIssuedQuantityByOrderItemId(itemId))
                .as("druhé uložení už nevydalo nic")
                .isEqualByComparingTo("4");
    }

    // =========================================================================
    // Privátní pomocníci
    // =========================================================================

    private OrderDto.DetailResponse changeStatus(OrderStatus status) {
        return orderService.update(orderId, updateRequest(status), USER_ID);
    }

    private OrderDto.UpdateRequest updateRequest(OrderStatus status) {
        OrderDto.UpdateRequest request = new OrderDto.UpdateRequest();
        request.setReceivedAt(LocalDate.now());
        request.setStatus(status);
        request.setDescription("KN-11 test — stavový automat zakázky");
        return request;
    }

    private InvoiceDto.DetailResponse createInvoice() {
        InvoiceDto.CreateRequest request = new InvoiceDto.CreateRequest();
        request.setOrderId(orderId);
        request.setBillingAddressId(BILLING_ADDRESS_ID);
        request.setIssueDate(LocalDate.now());
        request.setDueDate(LocalDate.now().plusDays(14));
        request.setTaxableSupplyDate(LocalDate.now());
        request.setPaymentMethod(PaymentMethod.CARD);
        markCompleted(orderId);
        return invoiceService.createFromOrder(request, USER_ID);
    }

    private GoodsReceiptItemDto.ImportRequest importRequest(Long batchId, String quantity) {
        return GoodsReceiptItemDto.ImportRequest.builder()
                .goodsReceiptItemId(batchId)
                .quantity(new BigDecimal(quantity))
                .build();
    }

    /**
     * Potvrzená příjemka s jednou šarží 4 ks včetně příjmového pohybu — bez něj by
     * {@code quantity_on_hand} zůstalo 0 a výdej by spadl na {@code chk_products_qty}
     * (vzor {@code OrderItemImportTest}).
     *
     * @return ID šarže ({@code goods_receipt_items})
     */
    private Long createConfirmedBatch() {
        Supplier supplier = Supplier.builder()
                .name("KN-11 test dodavatel s.r.o.")
                .registrationNumber("87654321")
                .countryCode("CZ")
                .active(true)
                .build();
        warehouseImportMapper.insertSupplier(supplier);

        Product product = Product.builder()
                .sku("KN11-TEST-SKU")
                .name("Testovací díl KN-11")
                .unit("ks")
                .defaultVatRate(21)
                .build();
        warehouseImportMapper.insertProduct(product);

        GoodsReceipt receipt = GoodsReceipt.builder()
                .supplierId(supplier.getId())
                .supplierNameSnapshot(supplier.getName())
                .invoiceNumber("KN11-FAK-001")
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

        StockMovement receiptMovement = StockMovement.builder()
                .productId(product.getId())
                .batchId(batch.getId())
                .movementType(MovementType.RECEIPT)
                .quantity(new BigDecimal("4"))
                .createdBy(USER_ID)
                .build();
        warehouseImportMapper.insertMovement(receiptMovement);

        return batch.getId();
    }

    /**
     * Fakturovat lze až dokončenou zakázku (rozhodnutí uživatele 2026-08-05). Setup ji tam
     * přepne <strong>přímo mapperem</strong> — obchází tím branku ve službě schválně, protože
     * tady jde o přípravu dat, ne o testovanou cestu.
     */
    private void markCompleted(Long id) {
        orderMapper.findById(id).ifPresent(o -> {
            o.setStatus(OrderStatus.COMPLETED);
            orderMapper.update(o);
        });
    }
}
