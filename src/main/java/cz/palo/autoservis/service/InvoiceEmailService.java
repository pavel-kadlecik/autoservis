package cz.palo.autoservis.service;

import cz.palo.autoservis.model.dto.billing.InvoiceDto;
import cz.palo.autoservis.model.dto.billing.InvoiceEmailDto;

/**
 * Odeslání faktury e-mailem zákazníkovi (2026-08-08) — PDF dokladu jako příloha,
 * text z editovatelné kostry.
 *
 * <p>Odesílá se přes SMTP účet servisu ({@code spring.mail.*}, typicky Seznam).
 * Evidence odeslaných e-mailů se <strong>nevede v aplikaci</strong> — evidencí je složka
 * Odeslané e-mailového účtu, kam aplikace po odeslání kopii sama uloží přes IMAP
 * (rozhodnutí uživatele 2026-08-08; Seznam SMTP poštu do Odeslaných neukládá).
 * V aplikaci zůstává jen příznak předání {@code handed_over_at}.
 */
public interface InvoiceEmailService {

    /**
     * Sestaví návrh e-mailu pro dialog odeslání: adresát z karty zákazníka,
     * předmět a kostra textu z dat dokladu, podpis názvem firmy z Fakturačních
     * údajů ({@code company_profile}). Nic neodesílá ani neukládá.
     *
     * @throws cz.palo.autoservis.exception.ResourceNotFoundException faktura neexistuje
     * @throws cz.palo.autoservis.exception.BusinessRuleException     {@code INVOICE_NOT_ISSUED} — koncept se neposílá
     */
    InvoiceEmailDto.DraftResponse getDraft(Long invoiceId);

    /**
     * Odešle fakturu e-mailem (PDF jako příloha) a — nebyla-li ještě předaná —
     * <strong>orazítkuje předání</strong>: doklad, který odešel zákazníkovi do schránky,
     * zákazník má (V88 chápe e-mail jako formu předání, rozhodnutí uživatele 2026-08-08).
     * Opakované odeslání už předané faktury předání nemění.
     *
     * @return faktura po odeslání (s aktuálním {@code handedOverAt})
     * @throws cz.palo.autoservis.exception.BusinessRuleException {@code INVOICE_NOT_ISSUED} /
     *         {@code EMAIL_NOT_CONFIGURED} (chybí SMTP přihlášení) / {@code EMAIL_SEND_FAILED}
     */
    InvoiceDto.DetailResponse send(Long invoiceId, InvoiceEmailDto.SendRequest request, Long userId);
}
