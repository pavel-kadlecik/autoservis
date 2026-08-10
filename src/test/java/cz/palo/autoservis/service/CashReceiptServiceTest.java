package cz.palo.autoservis.service;

import cz.palo.autoservis.AbstractIntegrationTest;
import cz.palo.autoservis.exception.BusinessRuleException;
import cz.palo.autoservis.exception.ConflictException;
import cz.palo.autoservis.mapper.OrderItemMapper;
import cz.palo.autoservis.model.domain.order.OrderItem;
import cz.palo.autoservis.model.dto.billing.CashReceiptDto;
import cz.palo.autoservis.model.dto.billing.InvoiceDto;
import cz.palo.autoservis.model.enums.CashReceiptStatus;
import cz.palo.autoservis.model.enums.OrderItemType;
import cz.palo.autoservis.model.enums.PaymentMethod;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

import static cz.palo.autoservis.service.InvoiceIssuing.issueWithNextNumber;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Příjmový pokladní doklad: <strong>zaokrouhlení hotovostní úhrady</strong> (V67) a
 * <strong>jeden platný doklad na fakturu + jeho storno</strong> (V68). Audit KN-7/L-9.
 *
 * <p>Modul PPD do tohoto auditu neměl <em>jediný</em> test (nález T-1) — jediný peněžní modul
 * projektu bez sítě. Těžiště je tu na tom, co bylo rozbité: faktura, pokladní doklad a evidence
 * úhrady říkaly <strong>tři různé částky</strong>, protože si každý zaokrouhloval po svém, a
 * dvojklik na tlačítko vystavil <strong>dva platné doklady</strong> na tutéž hotovost.
 *
 * <p>Zároveň se hlídá hranice, na které stojí legislativní správnost: zaokrouhlení je dle
 * §36 odst. 5 ZDPH <strong>mimo základ daně</strong>, takže nesmí prosáknout do {@code totalNet}
 * ani do rozpisu DPH.
 *
 * <p>Zakázka 4 nemá v seedu (V16) fakturu, takže k ní lze fakturu založit; položky nemá žádnou,
 * proto si ji test doplní sám (patří zákazníkovi 1, fakturační adresa id 2).
 */
@Transactional
class CashReceiptServiceTest extends AbstractIntegrationTest {

    private static final long USER_ID = 1L;
    private static final long ORDER_WITHOUT_INVOICE = 4L;
    private static final long BILLING_ADDRESS_ID = 2L;

    /** 1 × 1000,10 při 21 % → základ 1000,10 + DPH 210,02 = 1210,12 → k úhradě 1210 Kč. */
    private static final String UNIT_PRICE = "1000.10";

    @Autowired private CashReceiptService cashReceiptService;
    @Autowired private CashReceiptDocumentService cashReceiptDocumentService;
    @Autowired private InvoiceService invoiceService;
    @Autowired private OrderItemMapper orderItemMapper;
    @Autowired private JdbcTemplate jdbc;

    private Long issuedInvoice(PaymentMethod paymentMethod) {
        orderItemMapper.insert(OrderItem.builder()
                .orderId(ORDER_WITHOUT_INVOICE)
                .itemType(OrderItemType.LABOR)
                .name("Práce mechanika")
                .quantity(BigDecimal.ONE)
                .unit("hod")
                .unitPrice(new BigDecimal(UNIT_PRICE))
                .vatRate((short) 21)
                .position((short) 1)
                .createdBy(USER_ID)
                .build());

        var request = new InvoiceDto.CreateRequest();
        request.setOrderId(ORDER_WITHOUT_INVOICE);
        request.setBillingAddressId(BILLING_ADDRESS_ID);
        request.setIssueDate(LocalDate.now());
        request.setDueDate(LocalDate.now().plusDays(14));
        request.setTaxableSupplyDate(LocalDate.now());
        request.setPaymentMethod(paymentMethod);

        Long invoiceId = invoiceService.createFromOrder(request, USER_ID).getId();
        issueWithNextNumber(invoiceService, invoiceId, USER_ID);
        return invoiceId;
    }

    /** Request s dalším číslem řady — od V92 číslo dodává klient (návrh z next-number). */
    private CashReceiptDto.CreateRequest receiptRequest(Long invoiceId) {
        var request = new CashReceiptDto.CreateRequest();
        request.setInvoiceId(invoiceId);
        request.setReceiptNumber(cashReceiptService.suggestNextNumber(null).getReceiptNumber());
        return request;
    }

    @Test
    @DisplayName("hotovostní faktura: zaokrouhlení stojí MIMO základ daně (§36/5, KN-7)")
    void cashInvoice_roundingIsOutsideTaxBase() {
        Long invoiceId = issuedInvoice(PaymentMethod.CASH);

        InvoiceDto.DetailResponse invoice = invoiceService.getById(invoiceId);

        assertThat(invoice.getTotalNet()).as("základ daně").isEqualByComparingTo("1000.10");
        assertThat(invoice.getTotalVat()).as("daň").isEqualByComparingTo("210.02");
        assertThat(invoice.getTotalGross()).as("základ + daň, na haléř").isEqualByComparingTo("1210.12");
        assertThat(invoice.getRounding()).as("rozdíl ze zaokrouhlení").isEqualByComparingTo("-0.12");
        assertThat(invoice.getTotalToPay()).as("k úhradě v celých Kč").isEqualByComparingTo("1210.00");

        // Kdyby zaokrouhlení prosáklo do rozpisu DPH, doklad by vykazoval jinou daň, než odvádí.
        assertThat(invoice.getVatSummary()).allSatisfy(line ->
                assertThat(line.getVat()).isEqualByComparingTo("210.02"));
    }

    @Test
    @DisplayName("faktura, pokladní doklad i evidence úhrady říkají TUTÉŽ částku (KN-7/L-9)")
    void cashInvoice_documentsAgreeOnSingleAmount() {
        Long invoiceId = issuedInvoice(PaymentMethod.CASH);
        BigDecimal toPay = invoiceService.getById(invoiceId).getTotalToPay();

        CashReceiptDto.DetailResponse receipt =
                cashReceiptService.createFromInvoice(receiptRequest(invoiceId), USER_ID);

        assertThat(receipt.getAmount())
                .as("pokladní doklad zní na částku z faktury, nezaokrouhluje si po svém")
                .isEqualByComparingTo(toPay);

        invoiceService.markPaid(invoiceId, USER_ID);

        assertThat(jdbc.queryForObject(
                "SELECT paid_amount FROM billing.invoices WHERE id = ?", BigDecimal.class, invoiceId))
                .as("evidence úhrady zapíše, co skutečně přišlo do pokladny")
                .isEqualByComparingTo(toPay);

        // Jádro nálezu L-9: dřív tu byly tři různé hodnoty — 1210 v pokladně,
        // 1210,12 na faktuře a 1210,12 v pohledávkách.
        assertThat(toPay).isEqualByComparingTo("1210.00");
    }

    @Test
    @DisplayName("budoucí datum vystavení PPD projde — doklad se připravuje dopředu (2026-08-09)")
    void createFromInvoice_acceptsFutureIssueDate() {
        Long invoiceId = issuedInvoice(PaymentMethod.CASH);
        CashReceiptDto.CreateRequest request = receiptRequest(invoiceId);
        request.setIssueDate(LocalDate.now().plusDays(1));

        assertThat(cashReceiptService.createFromInvoice(request, USER_ID).getIssueDate())
                .isEqualTo(LocalDate.now().plusDays(1));
    }

    @Test
    @DisplayName("seznam faktur nese TUTÉŽ částku k úhradě jako detail (KN-7)")
    void invoiceList_carriesAmountToPay() {
        Long invoiceId = issuedInvoice(PaymentMethod.CASH);
        String number = invoiceService.getById(invoiceId).getInvoiceNumber();

        var params = new cz.palo.autoservis.model.dto.billing.InvoiceSearchParams();
        params.setSearch(number);

        // Sloupec „Celkem k úhradě" v seznamu čte totalToPay z resultMapu — snadné místo,
        // kde se projekce rozejde s detailem (sloupec v SELECTu bez mapování zůstane null).
        assertThat(invoiceService.getPage(params).getContent())
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.getTotalToPay()).isEqualByComparingTo("1210.00");
                    assertThat(row.getTotalGross()).isEqualByComparingTo("1210.12");
                });
    }

    @Test
    @DisplayName("nehotovostní faktura se nezaokrouhluje — platí se na haléře")
    void transferInvoice_isNotRounded() {
        Long invoiceId = issuedInvoice(PaymentMethod.TRANSFER);

        InvoiceDto.DetailResponse invoice = invoiceService.getById(invoiceId);

        assertThat(invoice.getRounding()).isEqualByComparingTo("0");
        assertThat(invoice.getTotalToPay()).isEqualByComparingTo(invoice.getTotalGross());
        assertThat(invoice.getTotalToPay()).isEqualByComparingTo("1210.12");
    }

    @Test
    @DisplayName("druhý pokladní doklad k téže faktuře neprojde (409, V68)")
    void secondCashReceipt_isRejected() {
        Long invoiceId = issuedInvoice(PaymentMethod.CASH);
        var request = receiptRequest(invoiceId);
        cashReceiptService.createFromInvoice(request, USER_ID);

        // Dvojklik na tlačítko dřív vystavil dva platné doklady na tutéž částku
        // a pokladna vykázala dvojnásobek přijaté hotovosti (audit KN-7).
        assertThatThrownBy(() -> cashReceiptService.createFromInvoice(request, USER_ID))
                .isInstanceOf(ConflictException.class)
                .satisfies(ex -> assertThat(((ConflictException) ex).getCode())
                        .isEqualTo("CASH_RECEIPT_ALREADY_EXISTS"));
    }

    @Test
    @DisplayName("po stornu lze vystavit nový doklad; stornovaný zůstává v řadě s důvodem")
    void cancelledCashReceipt_freesTheInvoiceButStaysVisible() {
        Long invoiceId = issuedInvoice(PaymentMethod.CASH);
        CashReceiptDto.DetailResponse first =
                cashReceiptService.createFromInvoice(receiptRequest(invoiceId), USER_ID);

        var cancelRequest = new CashReceiptDto.CancelRequest();
        cancelRequest.setReason("Vystaveno omylem");
        CashReceiptDto.DetailResponse cancelled =
                cashReceiptService.cancel(first.getId(), cancelRequest, USER_ID);

        assertThat(cancelled.getStatus()).isEqualTo(CashReceiptStatus.CANCELLED);
        assertThat(cancelled.getCancellationReason()).isEqualTo("Vystaveno omylem");
        assertThat(cancelled.getCancelledAt()).isNotNull();

        // Částečný unikát (V68) pustí nový doklad, jakmile ten předchozí přestal platit.
        // Stornovaný doklad drží svoje číslo, takže MAX+1 navrhne další v řadě.
        CashReceiptDto.DetailResponse second =
                cashReceiptService.createFromInvoice(receiptRequest(invoiceId), USER_ID);
        assertThat(second.getReceiptNumber()).isNotEqualTo(first.getReceiptNumber());

        // Účetní doklad se nemaže (§35 ZoÚ) — stornovaný musí zůstat dohledatelný.
        assertThat(cashReceiptService.getByInvoiceId(invoiceId))
                .extracting(CashReceiptDto.DetailResponse::getId)
                .containsExactly(first.getId(), second.getId());
    }

    @Test
    @DisplayName("stornovaný doklad se dá pořád vytisknout (pruh STORNOVÁNO v šabloně)")
    void cancelledCashReceipt_stillRendersPdf() {
        Long invoiceId = issuedInvoice(PaymentMethod.CASH);
        Long receiptId = cashReceiptService.createFromInvoice(receiptRequest(invoiceId), USER_ID).getId();

        var cancelRequest = new CashReceiptDto.CancelRequest();
        cancelRequest.setReason("Vystaveno omylem");
        cashReceiptService.cancel(receiptId, cancelRequest, USER_ID);

        // Šablona sahá na pole, která u nestornovaného dokladu nikdy nejsou vyplněná
        // (cancelledAt, cancellationReason) — chyba by se jinak projevila až u tiskárny.
        assertThat(cashReceiptDocumentService.renderPdf(receiptId))
                .isNotEmpty()
                .startsWith("%PDF".getBytes(StandardCharsets.US_ASCII));
    }

    @Test
    @DisplayName("stornovat týž doklad podruhé nejde — nepřepíše se původní důvod ani čas")
    void doubleCancel_isRejected() {
        Long invoiceId = issuedInvoice(PaymentMethod.CASH);
        Long receiptId = cashReceiptService.createFromInvoice(receiptRequest(invoiceId), USER_ID).getId();

        var cancelRequest = new CashReceiptDto.CancelRequest();
        cancelRequest.setReason("Vystaveno omylem");
        cashReceiptService.cancel(receiptId, cancelRequest, USER_ID);

        var secondReason = new CashReceiptDto.CancelRequest();
        secondReason.setReason("Něco jiného");
        assertThatThrownBy(() -> cashReceiptService.cancel(receiptId, secondReason, USER_ID))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(ex -> assertThat(((BusinessRuleException) ex).getRuleCode())
                        .isEqualTo("CASH_RECEIPT_ALREADY_CANCELLED"));

        assertThat(cashReceiptService.getById(receiptId).getCancellationReason())
                .isEqualTo("Vystaveno omylem");
    }

    @Test
    @DisplayName("PPD lze vystavit jen k vystavené nebo zaplacené faktuře, ne ke konceptu")
    void cashReceipt_requiresIssuedInvoice() {
        orderItemMapper.insert(OrderItem.builder()
                .orderId(ORDER_WITHOUT_INVOICE).itemType(OrderItemType.LABOR)
                .name("Práce").quantity(BigDecimal.ONE).unit("hod")
                .unitPrice(new BigDecimal(UNIT_PRICE)).vatRate((short) 21)
                .position((short) 1).createdBy(USER_ID).build());

        var request = new InvoiceDto.CreateRequest();
        request.setOrderId(ORDER_WITHOUT_INVOICE);
        request.setBillingAddressId(BILLING_ADDRESS_ID);
        request.setIssueDate(LocalDate.now());
        request.setDueDate(LocalDate.now().plusDays(14));
        request.setTaxableSupplyDate(LocalDate.now());
        request.setPaymentMethod(PaymentMethod.CASH);
        Long draftId = invoiceService.createFromOrder(request, USER_ID).getId();

        assertThatThrownBy(() -> cashReceiptService.createFromInvoice(receiptRequest(draftId), USER_ID))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(ex -> assertThat(((BusinessRuleException) ex).getRuleCode())
                        .isEqualTo("INVOICE_NOT_ISSUED"));
    }

    @Test
    @DisplayName("smazaný doklad uvolní číslo (MAX+1 ho navrhne znovu) i fakturu pro návrat do konceptu (V92)")
    void deletedCashReceipt_freesNumberAndInvoice() {
        Long invoiceId = issuedInvoice(PaymentMethod.CASH);
        CashReceiptDto.DetailResponse receipt =
                cashReceiptService.createFromInvoice(receiptRequest(invoiceId), USER_ID);

        // S dokladem na krku fakturu do konceptu vrátit nejde (guard requireNoLinkedDocuments).
        assertThatThrownBy(() -> invoiceService.revokeIssue(invoiceId, USER_ID))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(ex -> assertThat(((BusinessRuleException) ex).getRuleCode())
                        .isEqualTo("INVOICE_HAS_LINKED_DOCUMENTS"));

        cashReceiptService.delete(receipt.getId());

        // Číslo se uvolnilo — MAX+1 nabídne totéž číslo znovu, řada nemá díru.
        assertThat(cashReceiptService.suggestNextNumber(null).getReceiptNumber())
                .isEqualTo(receipt.getReceiptNumber());

        // A faktura přestala mít navázaný doklad — návrat do konceptu projde.
        assertThat(invoiceService.revokeIssue(invoiceId, USER_ID).getStatus())
                .isEqualTo(cz.palo.autoservis.model.enums.InvoiceStatus.DRAFT);
    }

    @Test
    @DisplayName("smazat jde i stornovaný doklad — rozhodnutí o zachování záznamu je na uživateli (V92)")
    void cancelledCashReceipt_canBeDeleted() {
        Long invoiceId = issuedInvoice(PaymentMethod.CASH);
        Long receiptId = cashReceiptService.createFromInvoice(receiptRequest(invoiceId), USER_ID).getId();

        var cancelRequest = new CashReceiptDto.CancelRequest();
        cancelRequest.setReason("Vystaveno omylem");
        cashReceiptService.cancel(receiptId, cancelRequest, USER_ID);

        cashReceiptService.delete(receiptId);

        assertThat(cashReceiptService.getByInvoiceId(invoiceId)).isEmpty();
    }

    @Test
    @DisplayName("duplicitní číslo dokladu neprojde — DUPLICATE_CASH_RECEIPT_NUMBER (V92)")
    void duplicateReceiptNumber_isRejected() {
        Long invoiceId = issuedInvoice(PaymentMethod.CASH);
        var first = receiptRequest(invoiceId);
        cashReceiptService.createFromInvoice(first, USER_ID);

        // Storno fakturu uvolní, ale číslo zůstává obsazené stornovaným dokladem.
        var cancelRequest = new CashReceiptDto.CancelRequest();
        cancelRequest.setReason("Vystaveno omylem");
        cashReceiptService.cancel(
                cashReceiptService.getByInvoiceId(invoiceId).get(0).getId(), cancelRequest, USER_ID);

        var duplicate = new CashReceiptDto.CreateRequest();
        duplicate.setInvoiceId(invoiceId);
        duplicate.setReceiptNumber(first.getReceiptNumber());

        assertThatThrownBy(() -> cashReceiptService.createFromInvoice(duplicate, USER_ID))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(ex -> assertThat(((BusinessRuleException) ex).getRuleCode())
                        .isEqualTo("DUPLICATE_CASH_RECEIPT_NUMBER"));
    }

    @Test
    @DisplayName("režim INVOICE (V93): návrh nenese číslo řady a hlídání mezer je vypnuté i při zapnutém přepínači")
    void invoiceNumberSource_disablesSeriesSuggestionAndGapCheck() {
        jdbc.update("UPDATE billing.company_profile SET "
                + "cash_receipt_number_source = 'INVOICE'::billing.cash_receipt_number_source, "
                + "cash_receipt_gap_check_enabled = TRUE WHERE id = 1");

        var suggestion = cashReceiptService.suggestNextNumber(null);
        assertThat(suggestion.getSource())
                .isEqualTo(cz.palo.autoservis.model.enums.CashReceiptNumberSource.INVOICE);
        assertThat(suggestion.getReceiptNumber())
                .as("číslo hrazené faktury dosazuje FE, server řadu neskládá").isNull();

        // Díry v řadě PPD jsou v tomto režimu faktury zaplacené převodem, ne chyba —
        // kontrola je vypnutá explicitně, i když je přepínač v DB zapnutý.
        assertThat(cashReceiptService.findNumberGaps().isEnabled()).isFalse();

        // Vystavení s číslem faktury projde — zdroj čísla server nevynucuje, jen unikátnost.
        Long invoiceId = issuedInvoice(PaymentMethod.CASH);
        String invoiceNumber = invoiceService.getById(invoiceId).getInvoiceNumber();
        var request = new CashReceiptDto.CreateRequest();
        request.setInvoiceId(invoiceId);
        request.setReceiptNumber(invoiceNumber);
        assertThat(cashReceiptService.createFromInvoice(request, USER_ID).getReceiptNumber())
                .isEqualTo(invoiceNumber);
    }

    @Test
    @DisplayName("hlídání mezer: po smazání dokladu uprostřed řady kontrola díru nahlásí (V92)")
    void numberGaps_reportHoleAfterMidSeriesDelete() {
        jdbc.update("UPDATE billing.company_profile SET cash_receipt_gap_check_enabled = TRUE WHERE id = 1");

        Long invoiceId = issuedInvoice(PaymentMethod.CASH);
        CashReceiptDto.DetailResponse first =
                cashReceiptService.createFromInvoice(receiptRequest(invoiceId), USER_ID);

        // Druhý doklad v řadě: první se stornuje (uvolní fakturu), druhý dostane další číslo.
        var cancelRequest = new CashReceiptDto.CancelRequest();
        cancelRequest.setReason("Vystaveno omylem");
        cashReceiptService.cancel(first.getId(), cancelRequest, USER_ID);
        cashReceiptService.createFromInvoice(receiptRequest(invoiceId), USER_ID);

        assertThat(cashReceiptService.findNumberGaps().getMissingNumbers())
                .as("souvislá řada — bez děr").isEmpty();

        // Smazání PRVNÍHO dokladu nechá v řadě díru — MAX+1 ji sám nezavře.
        cashReceiptService.delete(first.getId());

        var gaps = cashReceiptService.findNumberGaps();
        assertThat(gaps.isEnabled()).isTrue();
        assertThat(gaps.getMissingNumbers()).containsExactly(first.getReceiptNumber());

        // Zavře se ručním zápisem chybějícího čísla — přesně to dialog nabízí.
        var fill = new CashReceiptDto.CreateRequest();
        fill.setInvoiceId(invoiceId);
        fill.setReceiptNumber(first.getReceiptNumber());
        assertThatThrownBy(() -> cashReceiptService.createFromInvoice(fill, USER_ID))
                .as("faktura má platný doklad — díru by zavřel doklad k jiné faktuře")
                .isInstanceOf(ConflictException.class);
    }
}
