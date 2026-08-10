package cz.palo.autoservis.controller;

import cz.palo.autoservis.model.dto.billing.InvoiceDto;
import cz.palo.autoservis.model.dto.billing.InvoiceEmailDto;
import cz.palo.autoservis.model.dto.billing.InvoiceItemDto;
import cz.palo.autoservis.model.dto.billing.InvoiceSearchParams;
import cz.palo.autoservis.model.dto.pagination.PagedResponse;
import cz.palo.autoservis.security.model.domain.AppUserDetails;
import cz.palo.autoservis.service.InvoiceEmailService;
import cz.palo.autoservis.service.InvoiceService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.time.LocalDate;
import java.util.List;

/**
 * REST controller správy faktur.
 *
 * <p>Base path: {@code /api/{version}/invoices}
 *
 * <p>Faktura je vždy navázaná 1:1 na zakázku.
 * Položky faktury lze měnit jen ve stavu {@code DRAFT}.
 * Po vystavení je faktura neměnná.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/{version}/invoices")
public class InvoiceController {

    private final InvoiceService invoiceService;
    private final InvoiceEmailService invoiceEmailService;

    // =========================================================================
    // Faktura
    // =========================================================================

    /**
     * Založí novou fakturu ve stavu {@code DRAFT} ze zakázky,
     * všechny položky zakázky zkopíruje do faktury.
     *
     * @param createRequest zvalidované tělo requestu (musí obsahovat orderId)
     * @param currentUser   právě přihlášený uživatel
     * @return 201 Created s {@code Location} hlavičkou a detailem založené faktury včetně položek
     */
    @PostMapping("/from-order")
    public ResponseEntity<InvoiceDto.DetailResponse> createFromOrder(
            @Valid @RequestBody InvoiceDto.CreateRequest createRequest,
            @AuthenticationPrincipal AppUserDetails currentUser) {
        InvoiceDto.DetailResponse created =
                invoiceService.createFromOrder(createRequest, currentUser.getUserId());
        // POST cesta je .../invoices/from-order, ale zdroj se čte z .../invoices/{id} —
        // Location proto nejde sestavit prostým přidáním "/{id}" k aktuální cestě.
        UriComponentsBuilder current = ServletUriComponentsBuilder.fromCurrentRequest();
        String collectionPath = current.build().getPath().replaceFirst("/from-order$", "");
        URI location = current.replacePath(collectionPath + "/{id}")
                .buildAndExpand(created.getId())
                .toUri();
        return ResponseEntity.created(location).body(created);
    }

    /**
     * Vrací stránkovaný seznam faktur odpovídajících zadaným vyhledávacím parametrům.
     *
     * @param params vyhledávací a stránkovací parametry
     * @return 200 OK se stránkovaným seznamem faktur
     */
    @GetMapping
    public ResponseEntity<PagedResponse<InvoiceDto.ListResponse>> getPage(InvoiceSearchParams params) {
        return ResponseEntity.ok(invoiceService.getPage(params));
    }

    /**
     * Návrh dalšího čísla faktury podle masky číselné řady (V71) — předvyplnění
     * dialogu <strong>vystavení</strong>. Nic nerezervuje; při vypnutém automatickém
     * číslování vrací {@code auto = false} bez návrhu.
     *
     * @param issueDate datum vystavení, ze kterého se odvodí období řady (nepovinné, default dnešek)
     * @return 200 OK s návrhem čísla
     */
    @GetMapping("/next-number")
    public ResponseEntity<InvoiceDto.NextNumberResponse> nextNumber(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate issueDate) {
        return ResponseEntity.ok(invoiceService.suggestNextNumber(issueDate));
    }

    /**
     * Upraví existující fakturu (jen ve stavu {@code DRAFT} — guarded write v mapperu).
     * Měnit lze jen {@code due_date}, {@code constant_symbol}, {@code specific_symbol},
     * {@code payment_method}, {@code status}, {@code note}
     * a {@code purchase_order_number} (V91).
     *
     * @param id            ID faktury
     * @param updateRequest zvalidované tělo requestu
     * @param currentUser   právě přihlášený uživatel
     * @return 200 OK s aktualizovaným detailem faktury
     */
    @PutMapping("/{id}")
    public ResponseEntity<InvoiceDto.DetailResponse> update(
            @PathVariable Long id,
            @Valid @RequestBody InvoiceDto.UpdateRequest updateRequest,
            @AuthenticationPrincipal AppUserDetails currentUser) {
        return ResponseEntity.ok(invoiceService.update(id, updateRequest, currentUser.getUserId()));
    }

    /**
     * Vystaví koncept faktury (DRAFT → ISSUED) — tady doklad dostává číslo a variabilní symbol.
     *
     * @param id           ID faktury
     * @param issueRequest číslo dokladu (povinné, dialog ho předvyplní z {@code GET /next-number})
     *                     a volitelný variabilní symbol
     * @param currentUser  právě přihlášený uživatel
     * @return 200 OK s aktualizovaným detailem faktury
     */
    // E7 (audit R-6): účetní úkony faktury (vystavení, úhrada, storno) smí jen vedení; mechanik
    // připraví koncept, ale nevystavuje ani neruší doklad s daňovým dopadem.
    @PostMapping("/{id}/issue")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    public ResponseEntity<InvoiceDto.DetailResponse> issue(
            @PathVariable Long id,
            @Valid @RequestBody InvoiceDto.IssueRequest issueRequest,
            @AuthenticationPrincipal AppUserDetails currentUser) {
        return ResponseEntity.ok(invoiceService.issue(id, issueRequest, currentUser.getUserId()));
    }

    /**
     * Označí vystavenou fakturu jako zaplacenou (ISSUED → PAID).
     *
     * @param id          ID faktury
     * @param currentUser právě přihlášený uživatel
     * @return 200 OK s aktualizovaným detailem faktury
     */
    @PostMapping("/{id}/pay")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    public ResponseEntity<InvoiceDto.DetailResponse> markPaid(
            @PathVariable Long id,
            @AuthenticationPrincipal AppUserDetails currentUser) {
        return ResponseEntity.ok(invoiceService.markPaid(id, currentUser.getUserId()));
    }

    /**
     * Vrátí vystavenou fakturu do konceptu (2026-08-08) — typicky kvůli špatně zadanému číslu.
     *
     * <p>Uvolní číslo i variabilní symbol, zbytek faktury zůstane. Neprojde u předané,
     * zaplacené ani u faktury s navázaným pokladním dokladem.
     */
    @DeleteMapping("/{id}/issue")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    public ResponseEntity<InvoiceDto.DetailResponse> revokeIssue(
            @PathVariable Long id,
            @AuthenticationPrincipal AppUserDetails currentUser) {
        return ResponseEntity.ok(invoiceService.revokeIssue(id, currentUser.getUserId()));
    }

    /**
     * Vezme zpět evidenci úhrady (PAID → ISSUED, 2026-08-08).
     *
     * <p>Úhrada není daňový doklad; omylem kliknuté „Označit zaplaceno" bylo do téhle změny
     * nevratné. Neprojde, visí-li na faktuře platný pokladní doklad.
     */
    @DeleteMapping("/{id}/pay")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    public ResponseEntity<InvoiceDto.DetailResponse> revokePayment(
            @PathVariable Long id,
            @AuthenticationPrincipal AppUserDetails currentUser) {
        return ResponseEntity.ok(invoiceService.revokePayment(id, currentUser.getUserId()));
    }

    /**
     * Mezery v číselné řadě za aktuální období (V89) — podklad pro varování nad seznamem.
     *
     * <p>Vlastní cesta, ne přílepek k výpisu: seznam faktur se tím nezpomalí.
     */
    @GetMapping("/number-gaps")
    public ResponseEntity<InvoiceDto.NumberGapsResponse> numberGaps() {
        return ResponseEntity.ok(invoiceService.findNumberGaps());
    }

    /**
     * Potvrdí, že doklad dostal zákazník (V88).
     *
     * <p>Do té chvíle jde omylem vystavenou fakturu ještě smazat — vystavení samo předání
     * neznamená, aplikace fakturu neposílá a o odeslání neví nic. Idempotentní.
     */
    @PostMapping("/{id}/hand-over")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    public ResponseEntity<InvoiceDto.DetailResponse> handOver(
            @PathVariable Long id,
            @AuthenticationPrincipal AppUserDetails currentUser) {
        return ResponseEntity.ok(invoiceService.handOver(id, currentUser.getUserId()));
    }

    /**
     * Návrh e-mailu s fakturou (2026-08-08) — předvyplnění dialogu odeslání: adresát z karty
     * zákazníka, předmět a kostra textu z dat dokladu. Nic neodesílá ani nerezervuje.
     */
    @GetMapping("/{id}/email-draft")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    public ResponseEntity<InvoiceEmailDto.DraftResponse> emailDraft(@PathVariable Long id) {
        return ResponseEntity.ok(invoiceEmailService.getDraft(id));
    }

    /**
     * Odešle fakturu e-mailem zákazníkovi — PDF dokladu jako příloha, text v podobě, v jaké
     * ho obsluha potvrdila v dialogu. Nebyla-li faktura předaná, úspěšné odeslání předání
     * orazítkuje (e-mail ve schránce = doklad u zákazníka). Oprávnění stejné jako předání.
     */
    @PostMapping("/{id}/send-email")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    public ResponseEntity<InvoiceDto.DetailResponse> sendEmail(
            @PathVariable Long id,
            @Valid @RequestBody InvoiceEmailDto.SendRequest sendRequest,
            @AuthenticationPrincipal AppUserDetails currentUser) {
        return ResponseEntity.ok(invoiceEmailService.send(id, sendRequest, currentUser.getUserId()));
    }

    /** Vezme předání zpět — i „předáno" jde kliknout omylem. U zaplacené faktury neprojde. */
    @DeleteMapping("/{id}/hand-over")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    public ResponseEntity<InvoiceDto.DetailResponse> revokeHandOver(
            @PathVariable Long id,
            @AuthenticationPrincipal AppUserDetails currentUser) {
        return ResponseEntity.ok(invoiceService.revokeHandOver(id, currentUser.getUserId()));
    }

    /**
     * Smaže koncept faktury i s položkami a stranami. Vystavený doklad smazat nelze —
     * opravuje se dobropisem (§42/§45 ZDPH, audit KN-1).
     *
     * @param id          ID faktury
     * @param currentUser právě přihlášený uživatel
     * @return 204 No Content
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    public ResponseEntity<Void> delete(
            @PathVariable Long id,
            @AuthenticationPrincipal AppUserDetails currentUser) {
        invoiceService.delete(id, currentUser.getUserId());
        return ResponseEntity.noContent().build();
    }

    /**
     * Vrací plný detail faktury podle ID včetně všech položek.
     *
     * @param id ID faktury
     * @return 200 OK s detailem faktury
     */
    @GetMapping("/{id}")
    public ResponseEntity<InvoiceDto.DetailResponse> getById(@PathVariable Long id) {
        return ResponseEntity.ok(invoiceService.getById(id));
    }

    /**
     * Vrací plný detail faktury podle jejího čísla.
     *
     * @param invoiceNumber číslo faktury (např. {@code 202607001} (formát YYYYMM + pořadové číslo, konvence.md §18))
     * @return 200 OK s detailem faktury
     */
    @GetMapping("/number/{invoiceNumber}")
    public ResponseEntity<InvoiceDto.DetailResponse> getByInvoiceNumber(
            @PathVariable String invoiceNumber) {
        return ResponseEntity.ok(invoiceService.getByInvoiceNumber(invoiceNumber));
    }

    /**
     * Vrací fakturu navázanou na danou zakázku.
     *
     * @param orderId ID zakázky
     * @return 200 OK s detailem faktury
     */
    @GetMapping("/order/{orderId}")
    public ResponseEntity<InvoiceDto.DetailResponse> getByOrderId(
            @PathVariable Long orderId) {
        return ResponseEntity.ok(invoiceService.getByOrderId(orderId));
    }

    /**
     * Vrací všechny faktury daného zákazníka.
     *
     * @param customerId ID zákazníka
     * @return 200 OK se seznamem faktur (může být prázdný)
     */
    @GetMapping("/customer/{customerId}")
    public ResponseEntity<List<InvoiceDto.ListResponse>> getByCustomerId(
            @PathVariable Long customerId) {
        return ResponseEntity.ok(invoiceService.getByCustomerId(customerId));
    }

    // =========================================================================
    // Položky faktury
    // =========================================================================

    /**
     * Přidá novou položku k existující faktuře.
     * Povoleno jen ve stavu faktury {@code DRAFT}.
     *
     * @param invoiceId     ID faktury
     * @param createRequest zvalidované tělo requestu
     * @return 201 Created s {@code Location} hlavičkou a založenou položkou faktury
     */
    @PostMapping("/{invoiceId}/items")
    public ResponseEntity<InvoiceItemDto.Response> addItem(
            @PathVariable Long invoiceId,
            @Valid @RequestBody InvoiceItemDto.CreateRequest createRequest) {
        InvoiceItemDto.Response created = invoiceService.addItem(invoiceId, createRequest);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(created.getId())
                .toUri();
        return ResponseEntity.created(location).body(created);
    }

    /**
     * Upraví existující položku faktury.
     * Povoleno jen ve stavu nadřazené faktury {@code DRAFT}.
     *
     * @param invoiceId     ID faktury (jen kvůli struktuře cesty)
     * @param itemId        ID položky faktury
     * @param updateRequest zvalidované tělo requestu
     * @return 200 OK s aktualizovanou položkou faktury
     */
    @PutMapping("/{invoiceId}/items/{itemId}")
    public ResponseEntity<InvoiceItemDto.Response> updateItem(
            @PathVariable Long invoiceId,
            @PathVariable Long itemId,
            @Valid @RequestBody InvoiceItemDto.UpdateRequest updateRequest) {
        return ResponseEntity.ok(invoiceService.updateItem(itemId, updateRequest));
    }

    /**
     * Trvale smaže položku faktury.
     * Povoleno jen ve stavu nadřazené faktury {@code DRAFT}.
     *
     * @param invoiceId ID faktury (jen kvůli struktuře cesty)
     * @param itemId    ID položky faktury
     * @return 204 No Content
     */
    @DeleteMapping("/{invoiceId}/items/{itemId}")
    public ResponseEntity<Void> deleteItem(
            @PathVariable Long invoiceId,
            @PathVariable Long itemId) {
        invoiceService.deleteItem(itemId);
        return ResponseEntity.noContent().build();
    }
}
