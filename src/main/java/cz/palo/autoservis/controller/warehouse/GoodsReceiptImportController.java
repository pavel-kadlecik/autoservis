package cz.palo.autoservis.controller.warehouse;

import cz.palo.autoservis.model.domain.warehouse.DocumentType;
import cz.palo.autoservis.model.dto.warehouse.ReceiptDraftDto;
import cz.palo.autoservis.service.WarehouseImportService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.net.URI;

/**
 * Controller importu dodavatelských faktur z PDF souborů do skladu.
 * Base path: {@code /api/{version}/warehouse/receipts}
 */
@RestController
@RequestMapping("/api/{version}/warehouse/receipts")
@RequiredArgsConstructor
public class GoodsReceiptImportController {

    private final WarehouseImportService importService;

    /**
     * Nahraje dodavatelský PDF doklad (fakturu nebo dodací list), vytěží z něj data
     * a uloží draft příjemky ve stavu PENDING_REVIEW — nic se zatím nenaskladňuje;
     * produkty, šarže a pohyby vznikají až potvrzením.
     * Vrací 201 se souhrnem draftu.
     *
     * Tělo: multipart/form-data — "file" (PDF) + "documentType" (INVOICE / DELIVERY_NOTE,
     * volí uživatel, ne AI).
     */
    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','MECHANIC')")
    public ResponseEntity<ReceiptDraftDto.ImportResponse> importDocument(
            @RequestParam("file") MultipartFile file,
            @RequestParam("documentType") DocumentType documentType,
            @AuthenticationPrincipal cz.palo.autoservis.security.model.domain.AppUserDetails currentUser) {

        validatePdf(file);

        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Soubor se nepodařilo načíst.");
        }

        ReceiptDraftDto.ImportResponse result = importService.importFromPdf(
                bytes, file.getOriginalFilename(), documentType,
                file.getContentType(), currentUser.getUserId());

        // POST cesta je .../warehouse/receipts/import, ale draft se čte z
        // .../warehouse/receipts/{id} (GoodsReceiptReviewController) — Location proto
        // nejde sestavit prostým přidáním "/{id}" k aktuální cestě.
        UriComponentsBuilder current = ServletUriComponentsBuilder.fromCurrentRequest();
        String collectionPath = current.build().getPath().replaceFirst("/import$", "");
        URI location = current.replacePath(collectionPath + "/{id}")
                .buildAndExpand(result.getReceiptId())
                .toUri();
        return ResponseEntity.created(location).body(result);
    }

    /**
     * Importuje českou e-fakturu ISDOC (XML). Bez parametru typu dokladu — ISDOC
     * ho nese uvnitř a přijímají se jen faktury (dobropis by sklad přičetl,
     * místo aby ho odečetl).
     */
    @PostMapping(value = "/import-isdoc", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','MECHANIC')")
    public ResponseEntity<ReceiptDraftDto.ImportResponse> importIsdoc(
            @RequestParam("file") MultipartFile file,
            @AuthenticationPrincipal cz.palo.autoservis.security.model.domain.AppUserDetails currentUser) {

        validateIsdoc(file);

        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Soubor se nepodařilo načíst.");
        }

        ReceiptDraftDto.ImportResponse result = importService.importFromIsdoc(
                bytes, file.getOriginalFilename(), currentUser.getUserId());

        UriComponentsBuilder current = ServletUriComponentsBuilder.fromCurrentRequest();
        String collectionPath = current.build().getPath().replaceFirst("/import-isdoc$", "");
        URI location = current.replacePath(collectionPath + "/{id}")
                .buildAndExpand(result.getReceiptId())
                .toUri();
        return ResponseEntity.created(location).body(result);
    }

    private void validateIsdoc(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Chybí soubor dokladu.");
        }
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase();
        if (!name.endsWith(".isdoc") && !name.endsWith(".xml") && !name.endsWith(".isdocx")) {
            throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "Očekávám soubor .isdoc nebo .xml.");
        }
    }

    /**
     * Doklad smí být PDF nebo <b>fotka/sken</b> (rozhodnutí R-D) — model čte obrázek
     * stejně jako stránku PDF. Ostatní formáty odmítáme, ať se do extrakce nedostane
     * něco, co model přečíst neumí.
     */
    private void validatePdf(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Chybí soubor dokladu.");
        }
        String contentType = file.getContentType() == null ? "" : file.getContentType().toLowerCase();
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase();

        boolean acceptedType = contentType.equals("application/pdf")
                || contentType.startsWith("image/");
        boolean acceptedName = name.endsWith(".pdf") || name.endsWith(".jpg") || name.endsWith(".jpeg")
                || name.endsWith(".png") || name.endsWith(".heic") || name.endsWith(".webp");
        if (!acceptedType && !acceptedName) {
            throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "Očekávám PDF nebo fotku dokladu (JPG, PNG, HEIC).");
        }
    }
}
