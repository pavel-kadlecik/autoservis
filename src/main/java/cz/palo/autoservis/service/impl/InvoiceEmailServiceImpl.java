package cz.palo.autoservis.service.impl;

import cz.palo.autoservis.exception.BusinessRuleException;
import cz.palo.autoservis.exception.ResourceNotFoundException;
import cz.palo.autoservis.model.dto.billing.InvoiceDto;
import cz.palo.autoservis.model.dto.billing.InvoiceEmailDto;
import cz.palo.autoservis.model.enums.InvoiceStatus;
import cz.palo.autoservis.service.CompanyProfileService;
import cz.palo.autoservis.service.CustomerService;
import cz.palo.autoservis.service.InvoiceDocumentService;
import cz.palo.autoservis.service.InvoiceEmailService;
import cz.palo.autoservis.service.InvoiceService;
import jakarta.mail.Flags;
import jakarta.mail.Folder;
import jakarta.mail.MessagingException;
import jakarta.mail.Store;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class InvoiceEmailServiceImpl implements InvoiceEmailService {

    private static final Locale CZECH = Locale.of("cs", "CZ");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d. M. yyyy");

    private final InvoiceService invoiceService;
    private final CustomerService customerService;
    private final CompanyProfileService companyProfileService;
    private final InvoiceDocumentService invoiceDocumentService;
    /**
     * Konkrétní typ, ne interface {@code JavaMailSender}: pro IMAP kopii do Odeslaných
     * je potřeba {@code getSession()} s {@code mail.imap.*} properties z konfigurace.
     */
    private final JavaMailSenderImpl mailSender;

    /**
     * SMTP login = adresa odesílatele. Prázdný default nechá aplikaci nastartovat bez
     * konfigurace mailu; odeslání pak vrátí EMAIL_NOT_CONFIGURED místo pádu při startu.
     */
    @Value("${spring.mail.username:}")
    private String senderAddress;

    /** Stejné přihlášení pro IMAP — kopie odeslaného e-mailu se ukládá do téže schránky. */
    @Value("${spring.mail.password:}")
    private String senderPassword;

    /** {@inheritDoc} */
    @Override
    public InvoiceEmailDto.DraftResponse getDraft(Long invoiceId) {
        InvoiceDto.DetailResponse invoice = loadIssued(invoiceId);
        String companyName = companyName();

        InvoiceEmailDto.DraftResponse draft = new InvoiceEmailDto.DraftResponse();
        draft.setRecipient(findCustomerEmail(invoice.getCustomerId()));
        // Předmět nese ZÁKAZNÍKA (rozhodnutí uživatele 2026-08-08): příjemce si fakturu
        // pozná podle sebe; kdo ji poslal, říká odesílatel a podpis.
        draft.setSubject("Faktura " + invoice.getInvoiceNumber() + " — " + customerName(invoice));
        draft.setBody("""
                Dobrý den,

                v příloze zasíláme fakturu č. %s na částku %s se splatností %s.

                S pozdravem
                %s"""
                .formatted(invoice.getInvoiceNumber(),
                        formatAmount(invoice),
                        DATE.format(invoice.getDueDate() != null ? invoice.getDueDate() : LocalDate.now()),
                        companyName));
        return draft;
    }

    /** {@inheritDoc} */
    @Override
    public InvoiceDto.DetailResponse send(Long invoiceId, InvoiceEmailDto.SendRequest request, Long userId) {
        InvoiceDto.DetailResponse invoice = loadIssued(invoiceId);

        if (senderAddress == null || senderAddress.isBlank()) {
            throw new BusinessRuleException(
                    "EMAIL_NOT_CONFIGURED",
                    "Odesílání e-mailů není nastaveno — chybí přihlášení k SMTP "
                            + "(spring.mail.username/password v konfiguraci serveru).");
        }

        byte[] pdf = invoiceDocumentService.renderPdf(invoiceId);

        try {
            MimeMessage message = mailSender.createMimeMessage();
            // multipart = true kvůli příloze; UTF-8 kvůli diakritice v předmětu i textu
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(senderAddress, companyName());
            helper.setTo(request.getRecipient());
            helper.setSubject(request.getSubject());
            helper.setText(request.getBody(), false);
            helper.addAttachment(attachmentFileName(invoice.getInvoiceNumber()),
                    new ByteArrayResource(pdf), "application/pdf");
            mailSender.send(message);
            appendCopyToSent(message);
        } catch (Exception e) {
            throw new BusinessRuleException(
                    "EMAIL_SEND_FAILED", "recipient",
                    "E-mail se nepodařilo odeslat: " + e.getMessage(),
                    Map.of("invoiceId", invoiceId));
        }

        // E-mail ve schránce = doklad u zákazníka; předání razítkuje týž kód jako ruční
        // akce (idempotence, zámky). U už předané faktury (opakované odeslání) se nemění nic.
        if (invoice.getHandedOverAt() == null) {
            return invoiceService.handOver(invoiceId, userId);
        }
        return invoice;
    }

    /**
     * Uloží kopii odeslaného e-mailu do složky Odeslané přes IMAP (2026-08-08).
     *
     * <p>Seznam poštu odeslanou přes SMTP do Odeslaných sám neukládá (ověřeno prvním
     * testovacím odesláním) — kopii si tam ukládá poštovní klient, a tuhle roli tu přebírá
     * aplikace. Bez kopie by neexistovala žádná evidence odeslaných faktur: v DB se
     * schválně nevede (rozhodnutí uživatele, „evidence = schránka").
     *
     * <p><strong>Best-effort:</strong> e-mail už odešel a podruhé ho poslat nejde, takže
     * selhání kopie (výpadek IMAP) akci neshazuje — jen WARN do logu.
     */
    private void appendCopyToSent(MimeMessage message) {
        try {
            var session = mailSender.getSession();
            try (Store store = session.getStore("imap")) {
                store.connect(session.getProperty("mail.imap.host"), senderAddress, senderPassword);
                Folder sent = findSentFolder(store);
                if (sent == null) {
                    log.warn("Kopie e-mailu se neuložila: schránka nemá složku Odeslané (\\Sent).");
                    return;
                }
                // Kopie vlastní pošty nemá ve schránce svítit jako nepřečtená.
                message.setFlag(Flags.Flag.SEEN, true);
                sent.appendMessages(new MimeMessage[]{message});
            }
        } catch (Exception e) {
            log.warn("Faktura odešla, ale kopii se nepodařilo uložit do Odeslaných: {}", e.toString());
        }
    }

    /**
     * Složka Odeslaných podle obvyklých jmen — Seznam ji přes IMAP jmenuje {@code sent}
     * (ověřeno výpisem LIST 2026-08-08). Spolehlivější detekce IMAP atributem {@code \Sent}
     * (RFC 6154) by chtěla angus-specifické API ({@code IMAPFolder.getAttributes()}), a to je
     * runtime závislost starteru — vázat se na ni kvůli jménu jedné složky nestojí za to.
     */
    private Folder findSentFolder(Store store) throws MessagingException {
        for (String name : List.of("sent", "Sent", "Odeslané", "Sent Items")) {
            Folder folder = store.getFolder(name);
            if (folder.exists()) {
                return folder;
            }
        }
        return null;
    }

    /** Načte fakturu a odmítne koncept — bez čísla není co poslat a doklad to ještě není. */
    private InvoiceDto.DetailResponse loadIssued(Long invoiceId) {
        if (invoiceId == null) {
            throw new IllegalArgumentException("invoiceId nesmí být null");
        }
        InvoiceDto.DetailResponse invoice = invoiceService.getById(invoiceId);
        if (invoice.getStatus() == InvoiceStatus.DRAFT) {
            throw new BusinessRuleException(
                    "INVOICE_NOT_ISSUED", "invoice",
                    "Koncept se neposílá — nejdřív fakturu vystavte.",
                    Map.of("invoiceId", invoiceId, "status", invoice.getStatus()));
        }
        return invoice;
    }

    /**
     * E-mail z karty zákazníka; {@code null} = nevyplněný, obsluha ho zadá v dialogu.
     * Smazaný zákazník draft neshodí — faktura nese snapshot a poslat ji jde i tak.
     */
    private String findCustomerEmail(Long customerId) {
        if (customerId == null) {
            return null;
        }
        try {
            String email = customerService.getById(customerId).getPrimaryEmail();
            return (email == null || email.isBlank()) ? null : email;
        } catch (ResourceNotFoundException e) {
            return null;
        }
    }

    /**
     * Název firmy z Fakturačních údajů ({@code company_profile}), ne ze snapshotu stran
     * dokladu: e-mail se píše teď, tak nese aktuální název — snapshot může držet stav
     * z doby vytvoření faktury, třeba ještě nevyplněný profil (rozhodnutí uživatele
     * 2026-08-08). PDF v příloze snapshot pochopitelně drží dál.
     */
    private String companyName() {
        return companyProfileService.get().getName();
    }

    /** Jméno zákazníka tak, jak stojí na dokladu (snapshot z vytvoření faktury). */
    private String customerName(InvoiceDto.DetailResponse invoice) {
        if (invoice.getCustomerNameSnapshot() != null) {
            return invoice.getCustomerNameSnapshot();
        }
        return invoice.getCustomer() != null && invoice.getCustomer().getName() != null
                ? invoice.getCustomer().getName() : "";
    }

    /** Částka k úhradě česky („12 345,60 Kč") — táž hodnota, kterou nese PDF i QR platba. */
    private String formatAmount(InvoiceDto.DetailResponse invoice) {
        BigDecimal amount = invoice.getTotalToPay() != null
                ? invoice.getTotalToPay() : invoice.getTotalGross();
        NumberFormat format = NumberFormat.getNumberInstance(CZECH);
        format.setMinimumFractionDigits(2);
        format.setMaximumFractionDigits(2);
        return format.format(amount) + " Kč";
    }

    /**
     * Název přílohy z čísla faktury. Číslo smí obsahovat znaky, které v názvu souboru
     * neprojdou — typicky lomítko z masky typu {@code {NNN}/{RR}}: „faktura-006/26.pdf"
     * si klient vyloží jako cestu a uživateli ukáže nesmyslné „26.pdf". Problémové znaky
     * se proto nahrazují pomlčkou: {@code faktura-006-26.pdf}.
     */
    static String attachmentFileName(String invoiceNumber) {
        return "faktura-" + invoiceNumber.replaceAll("[\\\\/:*?\"<>|\\s]+", "-") + ".pdf";
    }
}
