package cz.palo.autoservis.controller;

import cz.palo.autoservis.service.InvoiceDocumentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpointy dokumentů faktury (PDF).
 * Výjimky probublávají do GlobalExceptionHandleru (např. 404 pro neexistující fakturu).
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/{version}/invoices")
public class InvoiceDocumentController {

    private final InvoiceDocumentService invoiceDocumentService;

    /**
     * PDF faktury (A4). Posílá se inline, aby ho prohlížeč zobrazil ve vestavěném
     * PDF prohlížeči (odkud si ho uživatel může stáhnout).
     *
     * @param id ID faktury
     * @return 200 OK s fakturou jako PDF dokumentem
     */
    @GetMapping(value = "/{id}/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> pdf(@PathVariable Long id) {
        byte[] pdf = invoiceDocumentService.renderPdf(id);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"faktura-" + id + ".pdf\"")
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdf);
    }
}
