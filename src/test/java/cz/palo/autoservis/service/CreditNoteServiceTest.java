package cz.palo.autoservis.service;

import cz.palo.autoservis.AbstractIntegrationTest;
import cz.palo.autoservis.exception.BusinessRuleException;
import cz.palo.autoservis.exception.ResourceNotFoundException;
import cz.palo.autoservis.model.dto.billing.CreditNoteDto;
import cz.palo.autoservis.model.dto.billing.InvoiceDto;
import cz.palo.autoservis.model.enums.InvoiceStatus;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Opravný daňový doklad (dobropis, §45 ZDPH) — vytvoření z faktury, vystavení, §45 rozdíly.
 *
 * <p>Seed (V16): faktura zakázky 1 = PAID, zakázky 2 = ISSUED, zakázky 3 = PAID.
 */
@Transactional
class CreditNoteServiceTest extends AbstractIntegrationTest {

    private static final long USER_ID = 1L;

    @Autowired
    private CreditNoteService creditNoteService;

    @Autowired
    private cz.palo.autoservis.mapper.InvoiceMapper invoiceMapper;

    @Autowired
    private CreditNoteDocumentService creditNoteDocumentService;

    @Autowired
    private InvoiceService invoiceService;

    @Autowired
    private cz.palo.autoservis.mapper.CreditNoteMapper creditNoteMapper;

    @Autowired
    private cz.palo.autoservis.mapper.OrderItemMapper orderItemMapper;

    private Long invoiceIdOfOrder(long orderId) {
        return invoiceService.getByOrderId(orderId).getId();
    }

    private CreditNoteDto.CreateRequest request(Long invoiceId) {
        CreditNoteDto.CreateRequest r = new CreditNoteDto.CreateRequest();
        r.setOriginalInvoiceId(invoiceId);
        r.setCorrectionReason("Reklamace — vrácení dílu");
        return r;
    }

    @Test
    @DisplayName("createFromInvoice k vystavené faktuře → DRAFT bez čísla, s §45 náležitostmi a zápornými rozdíly")
    void createFromInvoice_buildsDraftWithNegativeDifferences() {
        Long invoiceId = invoiceIdOfOrder(2L); // ISSUED
        String originalNumber = invoiceService.getById(invoiceId).getInvoiceNumber();

        CreditNoteDto.DetailResponse cn = creditNoteService.createFromInvoice(request(invoiceId), USER_ID);

        assertThat(cn.getStatus()).isEqualTo(InvoiceStatus.DRAFT);
        assertThat(cn.getCreditNoteNumber()).as("koncept ještě nemá číslo").isNull();
        assertThat(cn.getOriginalInvoiceId()).isEqualTo(invoiceId);
        assertThat(cn.getOriginalInvoiceNumber()).as("§45 evidenční číslo původního dokladu").isEqualTo(originalNumber);
        assertThat(cn.getCorrectionReason()).isEqualTo("Reklamace — vrácení dílu");
        // Konkrétní hodnoty, ne jen isNegative()/isNotNull(): audit (KN-21 / 09-T-2) ukázal, že
        // s původními asserty by suitou prošlo prohození totalNet ↔ totalGross i dodavatel ↔
        // odběratel — tedy doklad se špatnou daní a obrácenými stranami.
        InvoiceDto.DetailResponse original = invoiceService.getById(invoiceId);

        assertThat(cn.getTotalNetDifference())
                .as("plný dobropis = záporný základ původní faktury")
                .isEqualByComparingTo(original.getTotalNet().negate());
        assertThat(cn.getTotalVatDifference())
                .as("záporná daň původní faktury")
                .isEqualByComparingTo(original.getTotalVat().negate());
        assertThat(cn.getTotalGrossDifference())
                .as("záporná celková částka původní faktury")
                .isEqualByComparingTo(original.getTotalGross().negate());
        assertThat(cn.getTotalNetDifference().abs())
                .as("základ je menší než částka s DPH — kontrola proti prohození net/gross")
                .isLessThan(cn.getTotalGrossDifference().abs());

        assertThat(cn.getSupplier().getName())
                .as("dodavatelem je servis (§45), ne zákazník")
                .isEqualTo(original.getSupplier().getName())
                .isNotEqualTo(original.getCustomer().getName());
        assertThat(cn.getCustomer().getName())
                .as("odběratelem je zákazník z původní faktury")
                .isEqualTo(original.getCustomer().getName());

        assertThat(cn.getVatDifferences())
                .as("rozpis po sazbách zrcadlí původní fakturu")
                .hasSameSizeAs(original.getVatSummary());
        assertThat(cn.getVatDifferences()).allSatisfy(line -> {
            assertThat(line.getVat()).isNotPositive();
            assertThat(line.getBase()).isNotPositive();
        });
        assertThat(cn.getVatDifferences().stream()
                .map(InvoiceDto.VatSummaryLine::getVat)
                .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add))
                .as("součet rozpisu po sazbách se rovná celkové dani dokladu")
                .isEqualByComparingTo(cn.getTotalVatDifference());
    }

    @Test
    @DisplayName("issue přidělí číslo řady OD a přepne na ISSUED")
    void issue_assignsCreditNoteNumber() {
        Long invoiceId = invoiceIdOfOrder(2L);
        Long id = creditNoteService.createFromInvoice(request(invoiceId), USER_ID).getId();

        CreditNoteDto.DetailResponse issued = creditNoteService.issue(id, USER_ID);

        assertThat(issued.getStatus()).isEqualTo(InvoiceStatus.ISSUED);
        assertThat(issued.getCreditNoteNumber())
                .as("vlastní řada OD{YYYYMM}###").matches("OD\\d{6}\\d{3}");
    }

    @Test
    @DisplayName("dobropis lze vystavit i k zaplacené faktuře (reklamace po zaplacení)")
    void createFromInvoice_paidInvoice_isAllowed() {
        Long paidInvoiceId = invoiceIdOfOrder(1L); // PAID

        CreditNoteDto.DetailResponse cn = creditNoteService.createFromInvoice(request(paidInvoiceId), USER_ID);

        assertThat(cn.getStatus()).isEqualTo(InvoiceStatus.DRAFT);
        assertThat(cn.getOriginalInvoiceId()).isEqualTo(paidInvoiceId);
    }

    @Test
    @DisplayName("druhý dobropis k téže faktuře → INVOICE_ALREADY_CREDITED (422), i když je první jen koncept (KN-8)")
    void createFromInvoice_secondCreditNote_isRejected() {
        Long invoiceId = invoiceIdOfOrder(2L);
        creditNoteService.createFromInvoice(request(invoiceId), USER_ID);

        // Každý dobropis nese CELOU zápornou fakturu (MVP = plný dobropis), takže druhý by
        // znamenal dvojnásobné snížení daně na výstupu a zápornou pohledávku.
        assertThatThrownBy(() -> creditNoteService.createFromInvoice(request(invoiceId), USER_ID))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(ex -> assertThat(((BusinessRuleException) ex).getRuleCode())
                        .isEqualTo("INVOICE_ALREADY_CREDITED"));
    }

    @Test
    @DisplayName("druhý dobropis je odmítnut i po vystavení prvního (KN-8)")
    void createFromInvoice_secondCreditNoteAfterIssue_isRejected() {
        Long invoiceId = invoiceIdOfOrder(2L);
        CreditNoteDto.DetailResponse first =
                creditNoteService.createFromInvoice(request(invoiceId), USER_ID);
        creditNoteService.issue(first.getId(), USER_ID);

        assertThatThrownBy(() -> creditNoteService.createFromInvoice(request(invoiceId), USER_ID))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(ex -> assertThat(((BusinessRuleException) ex).getRuleCode())
                        .isEqualTo("INVOICE_ALREADY_CREDITED"));
    }

    @Test
    @DisplayName("smazání konceptu uvolní faktuře cestu k novému opravnému dokladu")
    void delete_draft_unblocksCreatingAnother() {
        // Bez mazání byla omylem založená oprava slepou uličkou: vystavit ji obsluha nechce
        // (byl by to platný doklad se špatným důvodem), zahodit nemohla, a nový dobropis
        // k téže faktuře už založit nešlo — INVOICE_ALREADY_CREDITED počítá i s koncepty.
        Long invoiceId = invoiceIdOfOrder(2L);
        CreditNoteDto.DetailResponse mistake = creditNoteService.createFromInvoice(request(invoiceId), USER_ID);

        creditNoteService.delete(mistake.getId(), USER_ID);

        assertThat(creditNoteMapper.findById(mistake.getId())).isEmpty();
        assertThat(creditNoteService.createFromInvoice(request(invoiceId), USER_ID).getId())
                .as("k faktuře jde založit nový opravný doklad").isNotNull();
    }

    @Test
    @DisplayName("vystavený dobropis smazat NELZE — má číslo řady OD a je platným dokladem")
    void delete_issuedCreditNote_isRejected() {
        CreditNoteDto.DetailResponse creditNote =
                creditNoteService.createFromInvoice(request(invoiceIdOfOrder(2L)), USER_ID);
        creditNoteService.issue(creditNote.getId(), USER_ID);

        assertThatThrownBy(() -> creditNoteService.delete(creditNote.getId(), USER_ID))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(ex -> assertThat(((BusinessRuleException) ex).getRuleCode())
                        .isEqualTo("CREDIT_NOTE_NOT_DELETABLE"));

        assertThat(creditNoteMapper.findById(creditNote.getId())).isPresent();
    }

    @Test
    @DisplayName("smazání konceptu nesahá na razítko `credited_at` — to dává až vystavení")
    void delete_draft_leavesInvoiceUncredited() {
        Long invoiceId = invoiceIdOfOrder(2L);
        CreditNoteDto.DetailResponse draft = creditNoteService.createFromInvoice(request(invoiceId), USER_ID);

        creditNoteService.delete(draft.getId(), USER_ID);

        assertThat(invoiceService.getById(invoiceId).getCreditedAt())
                .as("koncept po sobě na faktuře nic nenechá").isNull();
    }

    @Test
    @DisplayName("dobropisy k RŮZNÝM fakturám se navzájem neblokují (KN-8 — guard nesmí být příliš široký)")
    void createFromInvoice_differentInvoices_areIndependent() {
        creditNoteService.createFromInvoice(request(invoiceIdOfOrder(2L)), USER_ID);

        CreditNoteDto.DetailResponse other =
                creditNoteService.createFromInvoice(request(invoiceIdOfOrder(1L)), USER_ID);

        assertThat(other.getId()).isNotNull();
        assertThat(other.getStatus()).isEqualTo(InvoiceStatus.DRAFT);
    }

    @Test
    @DisplayName("dobropis ke KONCEPTU faktury → INVOICE_NOT_CORRECTABLE (422)")
    void createFromInvoice_draftInvoice_isRejected() {
        Long draftInvoiceId = draftInvoiceForOrderWithoutInvoice();

        assertThatThrownBy(() -> creditNoteService.createFromInvoice(request(draftInvoiceId), USER_ID))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(e -> assertThat(((BusinessRuleException) e).getRuleCode())
                        .isEqualTo("INVOICE_NOT_CORRECTABLE"));
    }

    @Test
    @DisplayName("dobropis ke stornované faktuře → INVOICE_NOT_CORRECTABLE (422)")
    void createFromInvoice_cancelledInvoice_isRejected() {
        // Stav CANCELLED aplikace od 2026-08-02 nenastaví (koncept se maže), ale faktury
        // stornované dřív v DB zůstávají — a opravovat je dobropisem nemá smysl.
        Long draftInvoiceId = draftInvoiceForOrderWithoutInvoice();
        // Přes mapper, ne jdbcTemplate — MyBatis by si jinak v session cache nechal DRAFT.
        invoiceMapper.updateStatus(draftInvoiceId, InvoiceStatus.CANCELLED, InvoiceStatus.DRAFT);

        assertThatThrownBy(() -> creditNoteService.createFromInvoice(request(draftInvoiceId), USER_ID))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(e -> assertThat(((BusinessRuleException) e).getRuleCode())
                        .isEqualTo("INVOICE_NOT_CORRECTABLE"));
    }

    /**
     * Koncept faktury k zakázce 4 — ta v seedu (V16) fakturu nemá, takže ji lze založit.
     * Zakázka patří zákazníkovi 1, jehož fakturační adresa má v seedu id 2.
     *
     * <p>Položky v seedu (V13) mají jen zakázky 1–3, a ty už fakturu mají; zakázku bez položek
     * ale fakturovat nelze, takže si jednu doplníme.
     */
    private Long draftInvoiceForOrderWithoutInvoice() {
        orderItemMapper.insert(cz.palo.autoservis.model.domain.order.OrderItem.builder()
                .orderId(4L)
                .itemType(cz.palo.autoservis.model.enums.OrderItemType.LABOR)
                .name("Práce mechanika")
                .quantity(java.math.BigDecimal.ONE)
                .unit("hod")
                .unitPrice(new java.math.BigDecimal("500"))
                .vatRate((short) 21)
                .position((short) 1)
                .createdBy(USER_ID)
                .build());

        var request = new cz.palo.autoservis.model.dto.billing.InvoiceDto.CreateRequest();
        request.setOrderId(4L);
        request.setBillingAddressId(2L);
        request.setIssueDate(java.time.LocalDate.now());
        request.setDueDate(java.time.LocalDate.now().plusDays(14));
        request.setTaxableSupplyDate(java.time.LocalDate.now());
        request.setPaymentMethod(cz.palo.autoservis.model.enums.PaymentMethod.TRANSFER);
        return invoiceService.createFromOrder(request, USER_ID).getId();
    }

    @Test
    @DisplayName("dobropis k neexistující faktuře → ResourceNotFoundException (404)")
    void createFromInvoice_unknownInvoice_throwsNotFound() {
        assertThatThrownBy(() -> creditNoteService.createFromInvoice(request(999_999L), USER_ID))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("renderPdf vytvoří validní PDF opravného dokladu (E5.2, %PDF hlavička)")
    void renderPdf_producesValidPdf() {
        Long invoiceId = invoiceIdOfOrder(2L);
        Long id = creditNoteService.createFromInvoice(request(invoiceId), USER_ID).getId();
        creditNoteService.issue(id, USER_ID);

        byte[] pdf = creditNoteDocumentService.renderPdf(id);

        assertThat(pdf).isNotEmpty();
        assertThat(new String(pdf, 0, 5, StandardCharsets.ISO_8859_1)).startsWith("%PDF-");
    }
}
