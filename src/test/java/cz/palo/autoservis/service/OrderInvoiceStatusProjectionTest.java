package cz.palo.autoservis.service;

import cz.palo.autoservis.AbstractIntegrationTest;
import cz.palo.autoservis.mapper.OrderItemMapper;
import cz.palo.autoservis.mapper.OrderMapper;
import cz.palo.autoservis.model.domain.order.Order;
import cz.palo.autoservis.model.domain.order.OrderItem;
import cz.palo.autoservis.model.dto.billing.InvoiceDto;
import cz.palo.autoservis.model.dto.order.OrderDto;
import cz.palo.autoservis.model.dto.order.OrderSearchParams;
import cz.palo.autoservis.model.dto.pagination.PagedResponse;
import cz.palo.autoservis.model.enums.InvoiceStatus;
import cz.palo.autoservis.model.enums.OrderItemType;
import cz.palo.autoservis.model.enums.PaymentMethod;
import cz.palo.autoservis.model.enums.OrderStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;

import static cz.palo.autoservis.service.InvoiceIssuing.issueWithNextNumber;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ověřuje odvozené pole {@code OrderDto.ListResponse.invoiceStatus} — stav aktivní
 * (nestornované) faktury zakázky, načtený LEFT JOINem v {@code OrderMapper.search}.
 *
 * <p>Klíčové chování: sloupec ukazuje reálný stav faktury přes celý životní cyklus
 * (nefakturováno → DRAFT → ISSUED), a po stornu i po <strong>dobropisu</strong> se vrací na
 * {@code null} — díky filtru {@code status <> 'CANCELLED' AND credited_at IS NULL} v joinu,
 * který musí zůstat shodný s partial unique indexem {@code uq_invoices_order_active}
 * (V48 + V69). Obojí dovolí zakázku fakturovat znovu; kdyby se predikáty rozešly, byla by
 * refakturovaná zakázka v seznamu dvakrát.
 *
 * <p>{@code @Transactional} — každý test běží v transakci, která se na konci rollbackne
 * (vzor: {@code InvoiceStatusTransitionTest}).
 */
@Transactional
class OrderInvoiceStatusProjectionTest extends AbstractIntegrationTest {

    @Autowired
    private OrderService orderService;

    @Autowired
    private InvoiceService invoiceService;

    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private OrderItemMapper orderItemMapper;

    /** Vytvoří zakázku s jednou položkou (faktura z prázdné zakázky nedává smysl) a vrátí ji. */
    private Order createOrderWithItem() {
        Order order = Order.builder()
                .receivedAt(LocalDate.now())
                .customerId(1L)
                .vehicleId(1L)
                .description("projekce invoiceStatus — test")
                .estimatedPrice(new BigDecimal("1000"))
                .createdBy(1L)
                .build();
        orderMapper.insert(order);

        OrderItem item = OrderItem.builder()
                .orderId(order.getId())
                .itemType(OrderItemType.LABOR)
                .name("Testovací položka")
                .quantity(BigDecimal.ONE)
                .unit("hod")
                .unitPrice(new BigDecimal("500"))
                .vatRate((short) 21)
                .position((short) 1)
                .createdBy(1L)
                .build();
        orderItemMapper.insert(item);
        return order;
    }

    private InvoiceDto.DetailResponse createInvoiceForOrder(Long orderId) {
        InvoiceDto.CreateRequest req = new InvoiceDto.CreateRequest();
        req.setOrderId(orderId);
        req.setBillingAddressId(2L); // seed: customer.addresses id=2, BILLING, customer 1
        req.setIssueDate(LocalDate.now());
        req.setDueDate(LocalDate.now().plusDays(14));
        req.setTaxableSupplyDate(LocalDate.now());
        req.setPaymentMethod(PaymentMethod.CARD);
        markCompleted(orderId);
        return invoiceService.createFromOrder(req, 1L);
    }

    /** Najde zakázku podle čísla v seznamovém endpointu a vrátí její invoiceStatus. */
    private InvoiceStatus invoiceStatusOf(String orderNumber) {
        OrderSearchParams params = new OrderSearchParams();
        params.setSearch(orderNumber);
        params.setPageSize(50);
        PagedResponse<OrderDto.ListResponse> result = orderService.getPage(params);
        return result.getContent().stream()
                .filter(o -> orderNumber.equals(o.getOrderNumber()))
                .findFirst()
                .orElseThrow()
                .getInvoiceStatus();
    }

    @Test
    @DisplayName("nefakturovaná zakázka → invoiceStatus je null")
    void notInvoiced_isNull() {
        Order order = createOrderWithItem();
        String number = orderMapper.findById(order.getId()).orElseThrow().getOrderNumber();

        assertThat(invoiceStatusOf(number)).isNull();
    }

    @Test
    @DisplayName("životní cyklus: null → DRAFT → ISSUED → PAID")
    void invoiceLifecycle_reflectedInProjection() {
        Order order = createOrderWithItem();
        String number = orderMapper.findById(order.getId()).orElseThrow().getOrderNumber();
        assertThat(invoiceStatusOf(number)).isNull();

        InvoiceDto.DetailResponse invoice = createInvoiceForOrder(order.getId());
        assertThat(invoiceStatusOf(number)).isEqualTo(InvoiceStatus.DRAFT);

        issueWithNextNumber(invoiceService, invoice.getId(), 1L);
        assertThat(invoiceStatusOf(number)).isEqualTo(InvoiceStatus.ISSUED);

        invoiceService.markPaid(invoice.getId(), 1L);
        assertThat(invoiceStatusOf(number)).isEqualTo(InvoiceStatus.PAID);
    }

    @Test
    @DisplayName("stornovaný KONCEPT se do projekce nepočítá — zakázka je zase bez faktury")
    void cancelledDraft_disappearsFromProjection() {
        Order order = createOrderWithItem();
        String number = orderMapper.findById(order.getId()).orElseThrow().getOrderNumber();

        InvoiceDto.DetailResponse invoice = createInvoiceForOrder(order.getId());
        assertThat(invoiceStatusOf(number)).isEqualTo(InvoiceStatus.DRAFT);

        // Koncept se maže (ne stornuje) — po smazání nemá join co spojit, takže zakázka
        // vypadá jako nevyfakturovaná a lze k ní vystavit fakturu novou.
        invoiceService.delete(invoice.getId(), 1L);
        assertThat(invoiceStatusOf(number)).isNull();
    }

    @Test
    @DisplayName("dobropisovaná faktura zmizí z projekce a nová se ukáže — zakázka jen jednou (V69)")
    void creditedInvoice_disappearsAndReinvoicedOrderStaysSingleRow() {
        Order order = createOrderWithItem();
        String number = orderMapper.findById(order.getId()).orElseThrow().getOrderNumber();

        InvoiceDto.DetailResponse first = createInvoiceForOrder(order.getId());
        issueWithNextNumber(invoiceService, first.getId(), 1L);
        assertThat(invoiceStatusOf(number)).isEqualTo(InvoiceStatus.ISSUED);

        var creditNoteRequest = new cz.palo.autoservis.model.dto.billing.CreditNoteDto.CreateRequest();
        creditNoteRequest.setOriginalInvoiceId(first.getId());
        creditNoteRequest.setCorrectionReason("Chybně účtované množství");
        invoiceService.handOver(creditNoteRequest.getOriginalInvoiceId(), 1L);
        Long creditNoteId = creditNoteService.createFromInvoice(creditNoteRequest, 1L).getId();

        assertThat(invoiceStatusOf(number))
                .as("koncept dobropisu ještě nic neuvolňuje").isEqualTo(InvoiceStatus.ISSUED);

        creditNoteService.issue(creditNoteId, 1L);
        assertThat(invoiceStatusOf(number))
                .as("po vystavení dobropisu vypadá zakázka jako nevyfakturovaná").isNull();

        // Zakázka teď má dvě nestornované faktury (dobropisovanou a novou). Kdyby join
        // v projekci nefiltroval credited_at, byla by v seznamu dvakrát.
        InvoiceDto.DetailResponse second = createInvoiceForOrder(order.getId());
        issueWithNextNumber(invoiceService, second.getId(), 1L);

        OrderSearchParams params = new OrderSearchParams();
        params.setSearch(number);
        params.setPageSize(50);
        assertThat(orderService.getPage(params).getContent())
                .filteredOn(o -> number.equals(o.getOrderNumber()))
                .singleElement()
                .satisfies(o -> assertThat(o.getInvoiceStatus()).isEqualTo(InvoiceStatus.ISSUED));
    }

    @Autowired
    private CreditNoteService creditNoteService;

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
