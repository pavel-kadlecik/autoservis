package cz.palo.autoservis.service.impl;

import cz.palo.autoservis.exception.BusinessRuleException;
import cz.palo.autoservis.exception.ResourceNotFoundException;
import cz.palo.autoservis.model.dto.billing.CompanyProfileDto;
import cz.palo.autoservis.model.dto.billing.InvoiceDto;
import cz.palo.autoservis.model.dto.billing.InvoiceEmailDto;
import cz.palo.autoservis.model.dto.customer.CustomerDto;
import cz.palo.autoservis.model.enums.InvoiceStatus;
import cz.palo.autoservis.service.CompanyProfileService;
import cz.palo.autoservis.service.CustomerService;
import cz.palo.autoservis.service.InvoiceDocumentService;
import cz.palo.autoservis.service.InvoiceService;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Odeslání faktury e-mailem ({@code InvoiceEmailServiceImpl}) — unit test s mockovaným
 * SMTP: integrační běh by potřeboval poštovní server a nic dalšího by neověřil.
 *
 * <p>Hlídá hlavně <strong>vazbu e-mail → předání</strong> (V88): úspěšné odeslání razítkuje
 * {@code handed_over_at}, selhané ho nechává být — jinak by v evidenci leželo „předáno"
 * k dokladu, který nikam nedošel.
 */
@ExtendWith(MockitoExtension.class)
class InvoiceEmailServiceTest {

    private static final Long INVOICE_ID = 5L;
    private static final Long CUSTOMER_ID = 9L;
    private static final Long USER_ID = 1L;

    @Mock private InvoiceService invoiceService;
    @Mock private CustomerService customerService;
    @Mock private CompanyProfileService companyProfileService;
    @Mock private InvoiceDocumentService invoiceDocumentService;
    // Konkrétní třída, ne interface — služba potřebuje getSession() pro IMAP kopii.
    // Mock vrací u getSession() null → append kopie selže a spolkne se (best-effort),
    // takže testy odeslání zároveň kryjí výpadek IMAP.
    @Mock private JavaMailSenderImpl mailSender;

    @InjectMocks
    private InvoiceEmailServiceImpl service;

    @BeforeEach
    void configureSenderAndProfile() {
        ReflectionTestUtils.setField(service, "senderAddress", "servis@seznam.cz");
        // Název firmy jde z Fakturačních údajů, NE ze snapshotu stran dokladu (rozhodnutí
        // uživatele 2026-08-08) — snapshot v issuedInvoice() proto schválně nese jiný text.
        CompanyProfileDto.Response profile = new CompanyProfileDto.Response();
        profile.setName("Autoservis Testovací s.r.o.");
        lenient().when(companyProfileService.get()).thenReturn(profile);
    }

    private InvoiceDto.DetailResponse issuedInvoice() {
        InvoiceDto.PartyResponse supplier = new InvoiceDto.PartyResponse();
        supplier.setName("Snapshot dodavatele z doby vystavení");

        InvoiceDto.DetailResponse invoice = new InvoiceDto.DetailResponse();
        invoice.setId(INVOICE_ID);
        invoice.setInvoiceNumber("202608001");
        invoice.setStatus(InvoiceStatus.ISSUED);
        invoice.setCustomerId(CUSTOMER_ID);
        invoice.setCustomerNameSnapshot("Jan Novák");
        invoice.setDueDate(LocalDate.of(2026, 8, 22));
        invoice.setTotalGross(new BigDecimal("6105.00"));
        invoice.setTotalToPay(new BigDecimal("6105.00"));
        invoice.setSupplier(supplier);
        return invoice;
    }

    private InvoiceEmailDto.SendRequest sendRequest() {
        InvoiceEmailDto.SendRequest request = new InvoiceEmailDto.SendRequest();
        request.setRecipient("zakaznik@seznam.cz");
        request.setSubject("Faktura 202608001");
        request.setBody("Dobrý den,\n\nfaktura v příloze.");
        return request;
    }

    private void mockCustomerEmail(String email) {
        CustomerDto.DetailResponse customer = new CustomerDto.DetailResponse();
        customer.setPrimaryEmail(email);
        when(customerService.getById(CUSTOMER_ID)).thenReturn(customer);
    }

    // =========================================================================
    // getDraft
    // =========================================================================

    @Test
    @DisplayName("getDraft složí adresáta z karty zákazníka a kostru z dat dokladu")
    void getDraft_composesRecipientSubjectAndBody() {
        when(invoiceService.getById(INVOICE_ID)).thenReturn(issuedInvoice());
        mockCustomerEmail("zakaznik@seznam.cz");

        InvoiceEmailDto.DraftResponse draft = service.getDraft(INVOICE_ID);

        assertThat(draft.getRecipient()).isEqualTo("zakaznik@seznam.cz");
        // Předmět nese zákazníka (příjemce si fakturu pozná podle sebe), podpis firmu.
        assertThat(draft.getSubject())
                .isEqualTo("Faktura 202608001 — Jan Novák");
        assertThat(draft.getBody())
                .contains("fakturu č. 202608001")
                .contains("se splatností 22. 8. 2026")
                .contains("Autoservis Testovací s.r.o.")
                .doesNotContain("Snapshot dodavatele")
                // cs formát skládá NumberFormat (oddělovač tisíců je nezlomitelná mezera) —
                // tvrdí se desetinná část a měna, ne přesný bajtový tvar mezery
                .contains("105,00 Kč");
    }

    @Test
    @DisplayName("getDraft u zákazníka bez e-mailu vrátí prázdného adresáta (obsluha ho zadá)")
    void getDraft_withoutCustomerEmail_returnsNullRecipient() {
        when(invoiceService.getById(INVOICE_ID)).thenReturn(issuedInvoice());
        mockCustomerEmail("   ");

        assertThat(service.getDraft(INVOICE_ID).getRecipient()).isNull();
    }

    @Test
    @DisplayName("getDraft přežije smazaného zákazníka — faktura nese snapshot a poslat ji jde")
    void getDraft_withDeletedCustomer_returnsNullRecipient() {
        when(invoiceService.getById(INVOICE_ID)).thenReturn(issuedInvoice());
        when(customerService.getById(CUSTOMER_ID))
                .thenThrow(new ResourceNotFoundException("Zákazník", CUSTOMER_ID));

        assertThat(service.getDraft(INVOICE_ID).getRecipient()).isNull();
    }

    @Test
    @DisplayName("getDraft odmítne koncept — bez čísla není co poslat")
    void getDraft_draftInvoice_throwsNotIssued() {
        InvoiceDto.DetailResponse draft = issuedInvoice();
        draft.setStatus(InvoiceStatus.DRAFT);
        when(invoiceService.getById(INVOICE_ID)).thenReturn(draft);

        assertThatThrownBy(() -> service.getDraft(INVOICE_ID))
                .isInstanceOf(BusinessRuleException.class)
                .extracting(e -> ((BusinessRuleException) e).getRuleCode())
                .isEqualTo("INVOICE_NOT_ISSUED");
    }

    // =========================================================================
    // send
    // =========================================================================

    @Test
    @DisplayName("send bez SMTP přihlášení vrátí EMAIL_NOT_CONFIGURED, nic neodesílá")
    void send_withoutConfiguration_throwsAndSendsNothing() {
        ReflectionTestUtils.setField(service, "senderAddress", "");
        when(invoiceService.getById(INVOICE_ID)).thenReturn(issuedInvoice());

        assertThatThrownBy(() -> service.send(INVOICE_ID, sendRequest(), USER_ID))
                .isInstanceOf(BusinessRuleException.class)
                .extracting(e -> ((BusinessRuleException) e).getRuleCode())
                .isEqualTo("EMAIL_NOT_CONFIGURED");

        verify(mailSender, never()).send(any(MimeMessage.class));
        verify(invoiceService, never()).handOver(any(), any());
    }

    @Test
    @DisplayName("send odešle PDF přílohu a nepředanou fakturu orazítkuje jako předanou")
    void send_unhandedInvoice_sendsAndMarksHandedOver() {
        InvoiceDto.DetailResponse invoice = issuedInvoice();
        InvoiceDto.DetailResponse handed = issuedInvoice();
        handed.setHandedOverAt(OffsetDateTime.now());

        when(invoiceService.getById(INVOICE_ID)).thenReturn(invoice);
        when(invoiceDocumentService.renderPdf(INVOICE_ID)).thenReturn("%PDF".getBytes());
        when(mailSender.createMimeMessage()).thenReturn(new MimeMessage((jakarta.mail.Session) null));
        when(invoiceService.handOver(INVOICE_ID, USER_ID)).thenReturn(handed);

        InvoiceDto.DetailResponse result = service.send(INVOICE_ID, sendRequest(), USER_ID);

        verify(mailSender).send(any(MimeMessage.class));
        verify(invoiceService).handOver(INVOICE_ID, USER_ID);
        assertThat(result.getHandedOverAt()).isNotNull();
    }

    @Test
    @DisplayName("send u už předané faktury předání znovu nerazítkuje (opakované odeslání)")
    void send_handedInvoice_doesNotTouchHandOver() {
        InvoiceDto.DetailResponse invoice = issuedInvoice();
        invoice.setHandedOverAt(OffsetDateTime.now());

        when(invoiceService.getById(INVOICE_ID)).thenReturn(invoice);
        when(invoiceDocumentService.renderPdf(INVOICE_ID)).thenReturn("%PDF".getBytes());
        when(mailSender.createMimeMessage()).thenReturn(new MimeMessage((jakarta.mail.Session) null));

        InvoiceDto.DetailResponse result = service.send(INVOICE_ID, sendRequest(), USER_ID);

        verify(mailSender).send(any(MimeMessage.class));
        verify(invoiceService, never()).handOver(any(), any());
        assertThat(result).isSameAs(invoice);
    }

    @Test
    @DisplayName("selhané odeslání předání NErazítkuje — „předáno“ bez doručeného dokladu je lež")
    void send_smtpFailure_throwsAndDoesNotHandOver() {
        when(invoiceService.getById(INVOICE_ID)).thenReturn(issuedInvoice());
        when(invoiceDocumentService.renderPdf(INVOICE_ID)).thenReturn("%PDF".getBytes());
        when(mailSender.createMimeMessage()).thenReturn(new MimeMessage((jakarta.mail.Session) null));
        doThrow(new MailSendException("connection refused"))
                .when(mailSender).send(any(MimeMessage.class));

        assertThatThrownBy(() -> service.send(INVOICE_ID, sendRequest(), USER_ID))
                .isInstanceOf(BusinessRuleException.class)
                .extracting(e -> ((BusinessRuleException) e).getRuleCode())
                .isEqualTo("EMAIL_SEND_FAILED");

        verify(invoiceService, never()).handOver(any(), any());
    }

    @Test
    @DisplayName("send odmítne koncept stejně jako getDraft")
    void send_draftInvoice_throwsNotIssued() {
        InvoiceDto.DetailResponse draft = issuedInvoice();
        draft.setStatus(InvoiceStatus.DRAFT);
        when(invoiceService.getById(INVOICE_ID)).thenReturn(draft);
        // Konfigurace se kontroluje až za stavem dokladu; mock mailu tu nesmí být potřeba.
        lenient().when(mailSender.createMimeMessage())
                .thenReturn(new MimeMessage((jakarta.mail.Session) null));

        assertThatThrownBy(() -> service.send(INVOICE_ID, sendRequest(), USER_ID))
                .isInstanceOf(BusinessRuleException.class)
                .extracting(e -> ((BusinessRuleException) e).getRuleCode())
                .isEqualTo("INVOICE_NOT_ISSUED");
    }

    @Test
    @DisplayName("název přílohy přežije lomítko v čísle faktury (006/26 → faktura-006-26.pdf)")
    void attachmentFileName_replacesPathHostileCharacters() {
        // Lomítko z masky {NNN}/{RR} dělalo z přílohy „faktura-006/26.pdf" — poštovní
        // klient ho vzal jako oddělovač cesty a příjemce viděl nesmyslné „26.pdf".
        org.assertj.core.api.Assertions.assertThat(
                        InvoiceEmailServiceImpl.attachmentFileName("006/26"))
                .isEqualTo("faktura-006-26.pdf");
        org.assertj.core.api.Assertions.assertThat(
                        InvoiceEmailServiceImpl.attachmentFileName("202608001"))
                .isEqualTo("faktura-202608001.pdf");
    }
}
