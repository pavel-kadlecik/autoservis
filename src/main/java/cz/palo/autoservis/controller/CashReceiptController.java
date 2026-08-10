package cz.palo.autoservis.controller;

import cz.palo.autoservis.model.dto.billing.CashReceiptDto;
import cz.palo.autoservis.security.model.domain.AppUserDetails;
import cz.palo.autoservis.service.CashReceiptDocumentService;
import cz.palo.autoservis.service.CashReceiptService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.time.LocalDate;
import java.util.List;

/**
 * REST controller pro příjmový pokladní doklad (PPD).
 *
 * <p>Base path: {@code /api/{version}/cash-receipts}. Doklad se vystavuje k vystavené/zaplacené
 * faktuře; číslo řady „PPD" přiděluje DB trigger. Pokladní agenda je vyhrazena vedení (ADMIN/MANAGER).
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/{version}/cash-receipts")
@PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
public class CashReceiptController {

    private final CashReceiptService cashReceiptService;
    private final CashReceiptDocumentService cashReceiptDocumentService;

    /**
     * Vystaví pokladní doklad k faktuře.
     *
     * @return 201 Created s {@code Location} hlavičkou a detailem dokladu
     */
    @PostMapping
    public ResponseEntity<CashReceiptDto.DetailResponse> create(
            @Valid @RequestBody CashReceiptDto.CreateRequest request,
            @AuthenticationPrincipal AppUserDetails currentUser) {
        CashReceiptDto.DetailResponse created =
                cashReceiptService.createFromInvoice(request, currentUser.getUserId());
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(created.getId()).toUri();
        return ResponseEntity.created(location).body(created);
    }

    /**
     * Návrh dalšího čísla řady pro dané datum (default dnešek). Nic nerezervuje — souběh
     * dvou stejných návrhů vyřeší zámek řady a unikát při vystavení.
     */
    @GetMapping("/next-number")
    public ResponseEntity<CashReceiptDto.NextNumberResponse> suggestNextNumber(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate issueDate) {
        return ResponseEntity.ok(cashReceiptService.suggestNextNumber(issueDate));
    }

    /** Chybějící čísla aktuálního období řady PPD (V92) — jen informuje, nic nevynucuje. */
    @GetMapping("/number-gaps")
    public ResponseEntity<CashReceiptDto.NumberGapsResponse> findNumberGaps() {
        return ResponseEntity.ok(cashReceiptService.findNumberGaps());
    }

    /**
     * Smaže pokladní doklad (rozhodnutí uživatele 2026-08-09: řadu si obsluha řídí sama).
     * Číslo se uvolní a faktura přestane mít navázaný doklad. Mazat jde i stornovaný doklad;
     * kdo chce záznam zachovat, použije storno.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        cashReceiptService.delete(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Stornuje pokladní doklad vystavený omylem (doklad zůstává v číselné řadě, jen přestane platit).
     * Teprve po stornu (nebo smazání) lze k faktuře vystavit nový doklad.
     */
    @PostMapping("/{id}/cancel")
    public ResponseEntity<CashReceiptDto.DetailResponse> cancel(
            @PathVariable Long id,
            @Valid @RequestBody CashReceiptDto.CancelRequest request,
            @AuthenticationPrincipal AppUserDetails currentUser) {
        return ResponseEntity.ok(cashReceiptService.cancel(id, request, currentUser.getUserId()));
    }

    /** Detail pokladního dokladu (účastníci + rozpis DPH z faktury). */
    @GetMapping("/{id}")
    public ResponseEntity<CashReceiptDto.DetailResponse> getById(@PathVariable Long id) {
        return ResponseEntity.ok(cashReceiptService.getById(id));
    }

    /** Pokladní doklady vystavené k dané faktuře. */
    @GetMapping(params = "invoiceId")
    public ResponseEntity<List<CashReceiptDto.DetailResponse>> getByInvoiceId(@RequestParam Long invoiceId) {
        return ResponseEntity.ok(cashReceiptService.getByInvoiceId(invoiceId));
    }

    /** PDF pokladního dokladu (A4, inline). */
    @GetMapping(value = "/{id}/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> pdf(@PathVariable Long id) {
        byte[] pdf = cashReceiptDocumentService.renderPdf(id);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"pokladni-doklad-" + id + ".pdf\"")
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdf);
    }
}
