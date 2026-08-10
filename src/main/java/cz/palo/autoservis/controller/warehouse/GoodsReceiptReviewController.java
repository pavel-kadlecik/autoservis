package cz.palo.autoservis.controller.warehouse;

import cz.palo.autoservis.model.domain.warehouse.GoodsReceipt;
import cz.palo.autoservis.model.draft.ReceiptDraft;
import cz.palo.autoservis.model.dto.pagination.PagedResponse;
import cz.palo.autoservis.model.dto.warehouse.ReceiptDto;
import cz.palo.autoservis.model.dto.warehouse.ReceiptSearchParams;
import cz.palo.autoservis.security.model.domain.AppUserDetails;
import cz.palo.autoservis.service.ReceiptReviewService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;

/**
 * Review workflow příjemek: seznam, detail s draftem, PDF, editace draftu,
 * potvrzení (materializace skladu) a zamítnutí.
 * Base path: {@code /api/{version}/warehouse/receipts}
 *
 * <p>Samostatný controller vedle {@code GoodsReceiptImportController} (import)
 * a {@code GoodsReceiptController} (/goods-receipts — autocomplete pro zakázky).
 */
@RestController
@RequestMapping("/api/{version}/warehouse/receipts")
@PreAuthorize("hasAnyRole('ADMIN','MANAGER','MECHANIC')")
@RequiredArgsConstructor
public class GoodsReceiptReviewController {

    private final ReceiptReviewService service;

    @GetMapping
    public PagedResponse<ReceiptDto.ListResponse> getAll(ReceiptSearchParams params) {
        return service.list(params);
    }

    /** Založí prázdný draft ruční příjemky (bez PDF) — pokračuje se v review obrazovce. */
    @PostMapping
    public ResponseEntity<ReceiptDto.DetailResponse> create(
            @Valid @RequestBody ReceiptDto.CreateDraftRequest request,
            @AuthenticationPrincipal AppUserDetails currentUser) {
        ReceiptDto.DetailResponse created = service.createManualDraft(request, currentUser.getUserId());
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(created.getId())
                .toUri();
        return ResponseEntity.created(location).body(created);
    }

    @GetMapping("/{id}")
    public ReceiptDto.DetailResponse getById(@PathVariable Long id) {
        return service.getDetail(id);
    }

    /** Originál PDF pro náhled v kontrolní obrazovce (404 = doklad bez PDF). */
    @GetMapping("/{id}/pdf")
    public ResponseEntity<byte[]> getPdf(@PathVariable Long id) {
        GoodsReceipt receipt = service.getPdf(id);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_PDF);
        headers.setContentDisposition(ContentDisposition.inline()
                .filename(receipt.getSourceFilename() != null
                        ? receipt.getSourceFilename() : "doklad-" + id + ".pdf")
                .build());
        return ResponseEntity.ok().headers(headers).body(receipt.getSourcePdf());
    }

    /** Uloží editovaný draft (jen PENDING_REVIEW) a vrátí přepočtený detail. */
    @PutMapping("/{id}/draft")
    public ReceiptDto.DetailResponse updateDraft(@PathVariable Long id,
                                                 @RequestBody ReceiptDraft draft,
                                                 @AuthenticationPrincipal AppUserDetails currentUser) {
        return service.updateDraft(id, draft, currentUser.getUserId());
    }

    /** Potvrdí příjemku — teprve teď vznikají produkty, šarže a pohyby RECEIPT. */
    @PostMapping("/{id}/confirm")
    public ReceiptDto.DetailResponse confirm(@PathVariable Long id,
                                             @AuthenticationPrincipal AppUserDetails currentUser) {
        return service.confirm(id, currentUser.getUserId());
    }

    /** Zamítne příjemku — nic se nematerializuje, číslo dokladu se uvolní. */
    @PostMapping("/{id}/reject")
    public ReceiptDto.DetailResponse reject(@PathVariable Long id,
                                            @Valid @RequestBody(required = false) ReceiptDto.RejectRequest request,
                                            @AuthenticationPrincipal AppUserDetails currentUser) {
        String note = request == null ? null : request.getNote();
        return service.reject(id, note, currentUser.getUserId());
    }

    /**
     * Stornuje potvrzenou příjemku (V43, R-C): kompenzační pohyby vrátí sklad
     * do původního stavu a doklad uvolní své číslo. Jen dokud se z ní nečerpalo.
     */
    @PostMapping("/{id}/cancel")
    public ReceiptDto.DetailResponse cancel(@PathVariable Long id,
                                            @Valid @RequestBody ReceiptDto.CancelRequest request,
                                            @AuthenticationPrincipal AppUserDetails currentUser) {
        return service.cancel(id, request.getNote(), currentUser.getUserId());
    }
}
