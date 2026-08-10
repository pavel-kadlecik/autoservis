package cz.palo.autoservis.service;

import cz.palo.autoservis.AbstractIntegrationTest;
import cz.palo.autoservis.exception.BusinessRuleException;
import cz.palo.autoservis.mapper.InvoiceItemMapper;
import cz.palo.autoservis.mapper.InvoiceMapper;
import cz.palo.autoservis.mapper.OrderItemMapper;
import cz.palo.autoservis.mapper.OrderMapper;
import cz.palo.autoservis.model.domain.billing.Invoice;
import cz.palo.autoservis.model.domain.billing.InvoiceItem;
import cz.palo.autoservis.model.domain.order.Order;
import cz.palo.autoservis.model.domain.order.OrderItem;
import cz.palo.autoservis.model.dto.billing.InvoiceDto;
import cz.palo.autoservis.model.enums.InvoiceStatus;
import cz.palo.autoservis.model.enums.OrderItemType;
import cz.palo.autoservis.model.enums.PaymentMethod;
import cz.palo.autoservis.model.enums.OrderStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;

import static cz.palo.autoservis.service.InvoiceIssuing.issueWithNextNumber;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pokrývá guardovaný {@code updateStatus} UPDATE zavedený pro K5 (analyza-2026-07):
 * souběžný přechod nesmí tiše přepsat předchozí — zápis do DB je guardovaný přes
 * {@code WHERE status = expectedStatus}, takže zastaralý volající dostane 0
 * dotčených řádků, místo aby fakturu přepsal.
 *
 * <p>{@code @Transactional} — každý test běží v transakci, která se na konci odroluje,
 * takže DB zůstává čistá bez ohledu na pořadí testů (viz {@code CustomerServiceTest}).
 */
@Transactional
class InvoiceStatusTransitionTest extends AbstractIntegrationTest {

    @Autowired
    private InvoiceService invoiceService;

    @Autowired
    private InvoiceMapper invoiceMapper;

    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private OrderItemMapper orderItemMapper;

    @Autowired
    private InvoiceItemMapper invoiceItemMapper;

    private Long draftInvoiceId;

    /**
     * Seed data (V8/V13/V16) mají položky jen u zakázek 1–3 a ty už fakturu mají (V16) —
     * žádná není DRAFT. Každý test si proto založí vlastní zakázku + položku pro
     * zákazníka 1 (seed, má BILLING adresu s id=2) a vyfakturuje ji přes service,
     * čímž dostane čerstvou DRAFT fakturu.
     */
    @BeforeEach
    void createDraftInvoice() {
        Order order = Order.builder()
                .receivedAt(LocalDate.now())
                .customerId(1L)
                .vehicleId(1L)
                .description("K5 test — guarded updateStatus")
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

        InvoiceDto.CreateRequest createRequest = new InvoiceDto.CreateRequest();
        createRequest.setOrderId(order.getId());
        createRequest.setBillingAddressId(2L); // seed: customer.addresses id=2, BILLING, zákazník 1
        createRequest.setIssueDate(LocalDate.now());
        createRequest.setDueDate(LocalDate.now().plusDays(14));
        createRequest.setTaxableSupplyDate(LocalDate.now());
        createRequest.setPaymentMethod(PaymentMethod.CARD);

        markCompleted(order.getId());
        InvoiceDto.DetailResponse created = invoiceService.createFromOrder(createRequest, 1L);
        draftInvoiceId = created.getId();
        assertThat(created.getStatus()).isEqualTo(InvoiceStatus.DRAFT);
    }

    @Test
    @DisplayName("issue() DRAFT → ISSUED projde")
    void issue_fromDraft_transitionsToIssued() {
        InvoiceDto.DetailResponse result = issueWithNextNumber(invoiceService, draftInvoiceId, 1L);

        assertThat(result.getStatus()).isEqualTo(InvoiceStatus.ISSUED);
    }

    @Test
    @DisplayName("druhý issue() na už vystavenou fakturu vyhodí BusinessRuleException INVALID_STATUS_TRANSITION")
    void issue_calledTwice_secondCallThrowsInvalidStatusTransition() {
        issueWithNextNumber(invoiceService, draftInvoiceId, 1L);

        assertThatThrownBy(() -> issueWithNextNumber(invoiceService, draftInvoiceId, 1L))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(ex -> assertThat(((BusinessRuleException) ex).getRuleCode())
                        .isEqualTo("INVALID_STATUS_TRANSITION"));
    }

    @Test
    @DisplayName("guarded updateStatus: expectedStatus DRAFT na už ISSUED faktuře vrátí 0 řádků")
    void updateStatus_withStaleExpectedStatus_returnsZeroAffectedRows() {
        issueWithNextNumber(invoiceService, draftInvoiceId, 1L); // DRAFT -> ISSUED

        int affectedRows = invoiceMapper.updateStatus(draftInvoiceId, InvoiceStatus.PAID, InvoiceStatus.DRAFT);

        assertThat(affectedRows).isZero();
        // faktura musí zůstat ISSUED — guard přepsání zabránil
        assertThat(invoiceMapper.findById(draftInvoiceId).orElseThrow().getStatus())
                .isEqualTo(InvoiceStatus.ISSUED);
    }

    @Test
    @DisplayName("guarded updateStatus: expectedStatus ISSUED na skutečně ISSUED faktuře vrátí 1 řádek")
    void updateStatus_withMatchingExpectedStatus_returnsOneAffectedRow() {
        issueWithNextNumber(invoiceService, draftInvoiceId, 1L); // DRAFT -> ISSUED

        int affectedRows = invoiceMapper.updateStatus(draftInvoiceId, InvoiceStatus.PAID, InvoiceStatus.ISSUED);

        assertThat(affectedRows).isEqualTo(1);
        assertThat(invoiceMapper.findById(draftInvoiceId).orElseThrow().getStatus())
                .isEqualTo(InvoiceStatus.PAID);
    }

    // =========================================================================
    // TD-58 / S-4 — guarded editace DRAFT faktury (hlavička + položky).
    // Zápis je guardovaný na status='DRAFT'; testujeme na mapper úrovni (stejně jako K5 výše),
    // protože přidanou hodnotu guardu — souběh, který proklouzne přes requireEditable — nejde
    // deterministicky nasimulovat vlákny. „Zastaralý volající" na už vystavené faktuře musí
    // dostat 0 řádků; na DRAFT faktuře 1 řádek (guard nepřeblokuje).
    // =========================================================================

    private InvoiceItem soleItemOfDraft() {
        return invoiceItemMapper.findByInvoiceId(draftInvoiceId).getFirst();
    }

    @Test
    @DisplayName("guarded update hlavičky: na už ISSUED faktuře vrátí 0 řádků")
    void headerUpdate_onIssuedInvoice_returnsZeroRows() {
        Invoice stale = invoiceMapper.findById(draftInvoiceId).orElseThrow(); // načteno jako DRAFT
        issueWithNextNumber(invoiceService, draftInvoiceId, 1L);                             // souběžný přechod → ISSUED
        stale.setNote("pokus o editaci vystaveného dokladu");

        assertThat(invoiceMapper.update(stale)).isZero();
    }

    @Test
    @DisplayName("guarded update hlavičky: na DRAFT faktuře vrátí 1 řádek (guard nepřeblokuje)")
    void headerUpdate_onDraftInvoice_returnsOneRow() {
        Invoice draft = invoiceMapper.findById(draftInvoiceId).orElseThrow();
        draft.setNote("editace v draftu");

        assertThat(invoiceMapper.update(draft)).isEqualTo(1);
    }

    @Test
    @DisplayName("guarded update položky: parent ISSUED → 0 řádků")
    void itemUpdate_onIssuedParent_returnsZeroRows() {
        InvoiceItem item = soleItemOfDraft();
        issueWithNextNumber(invoiceService, draftInvoiceId, 1L);
        item.setName("pokus o změnu položky vystaveného dokladu");

        assertThat(invoiceItemMapper.update(item)).isZero();
    }

    @Test
    @DisplayName("guarded delete položky: parent ISSUED → 0 řádků, položka zůstává")
    void itemDelete_onIssuedParent_returnsZeroRowsAndKeepsItem() {
        Long itemId = soleItemOfDraft().getId();
        issueWithNextNumber(invoiceService, draftInvoiceId, 1L);

        assertThat(invoiceItemMapper.deleteById(itemId)).isZero();
        assertThat(invoiceItemMapper.findById(itemId)).isPresent();
    }

    @Test
    @DisplayName("guarded insert položky: parent ISSUED → 0 řádků, položka nepřibude")
    void itemInsert_onIssuedParent_returnsZeroRows() {
        InvoiceItem template = soleItemOfDraft();
        int before = invoiceItemMapper.findByInvoiceId(draftInvoiceId).size();
        issueWithNextNumber(invoiceService, draftInvoiceId, 1L);

        InvoiceItem fresh = InvoiceItem.builder()
                .invoiceId(draftInvoiceId)
                .orderItemId(template.getOrderItemId())
                .name("pokus o přidání položky vystavenému dokladu")
                .quantity(BigDecimal.ONE)
                .unit("ks")
                .unitPrice(new BigDecimal("100"))
                .vatRate((short) 21)
                .position((short) 2)
                .build();

        assertThat(invoiceItemMapper.insert(fresh)).isZero();
        assertThat(invoiceItemMapper.findByInvoiceId(draftInvoiceId)).hasSize(before);
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
