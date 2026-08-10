package cz.palo.autoservis.controller;

import cz.palo.autoservis.model.dto.billing.CreditNoteDto;
import cz.palo.autoservis.security.model.domain.AppUserDetails;
import cz.palo.autoservis.service.CreditNoteDocumentService;
import cz.palo.autoservis.service.CreditNoteService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;

/**
 * REST controller pro opravný daňový doklad (dobropis, §45 ZDPH).
 *
 * <p>Base path: {@code /api/{version}/credit-notes}. Dobropis se zakládá k vystavené/zaplacené
 * faktuře; číslo řady „OD" se přidělí až při vystavení.
 *
 * <p>E7 (audit R-6): dobropis je čistě účetní doklad — celý vyhrazen vedení (ADMIN/MANAGER).
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/{version}/credit-notes")
@PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
public class CreditNoteController {

    private final CreditNoteService creditNoteService;
    private final CreditNoteDocumentService creditNoteDocumentService;

    /**
     * Založí koncept dobropisu k faktuře.
     *
     * @return 201 Created s {@code Location} hlavičkou a detailem dobropisu
     */
    @PostMapping
    public ResponseEntity<CreditNoteDto.DetailResponse> create(
            @Valid @RequestBody CreditNoteDto.CreateRequest request,
            @AuthenticationPrincipal AppUserDetails currentUser) {
        CreditNoteDto.DetailResponse created =
                creditNoteService.createFromInvoice(request, currentUser.getUserId());
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(created.getId()).toUri();
        return ResponseEntity.created(location).body(created);
    }

    /** Vystaví dobropis (DRAFT→ISSUED); přidělí číslo řady „OD". */
    @PostMapping("/{id}/issue")
    public ResponseEntity<CreditNoteDto.DetailResponse> issue(
            @PathVariable Long id,
            @AuthenticationPrincipal AppUserDetails currentUser) {
        return ResponseEntity.ok(creditNoteService.issue(id, currentUser.getUserId()));
    }

    /**
     * Smaže koncept opravného dokladu — omylem založená oprava tak přestává být slepou
     * uličkou (bez toho blokovala založení dalšího dobropisu k téže faktuře natrvalo).
     * Vystavený doklad smazat nelze.
     *
     * @return 204 No Content
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @PathVariable Long id,
            @AuthenticationPrincipal AppUserDetails currentUser) {
        creditNoteService.delete(id, currentUser.getUserId());
        return ResponseEntity.noContent().build();
    }

    /**
     * Dobropisy k dané faktuře — filtr kolekce (`konvence.md §10`).
     *
     * <p>Bez něj by šel dobropis jen založit, ne dohledat: detail faktury nemá jak zjistit,
     * že k ní opravný doklad už existuje, a rozdělaný koncept by se po odchodu ze stránky
     * ztratil. Vrací i stornované — aktivní může být nejvýš jeden (`uq_credit_notes_original_active`, V66).
     */
    @GetMapping
    public ResponseEntity<java.util.List<CreditNoteDto.DetailResponse>> getByInvoice(
            @RequestParam Long invoiceId) {
        return ResponseEntity.ok(creditNoteService.getByInvoiceId(invoiceId));
    }

    /** Detail dobropisu (§45 rozdíly + strany z původní faktury). */
    @GetMapping("/{id}")
    public ResponseEntity<CreditNoteDto.DetailResponse> getById(@PathVariable Long id) {
        return ResponseEntity.ok(creditNoteService.getById(id));
    }

    /** PDF opravného dokladu (A4, inline). */
    @GetMapping(value = "/{id}/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> pdf(@PathVariable Long id) {
        byte[] pdf = creditNoteDocumentService.renderPdf(id);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"dobropis-" + id + ".pdf\"")
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdf);
    }
}
