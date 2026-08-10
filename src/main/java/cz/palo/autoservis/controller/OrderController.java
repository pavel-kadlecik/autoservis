package cz.palo.autoservis.controller;

import cz.palo.autoservis.model.dto.order.OrderDto;
import cz.palo.autoservis.model.dto.order.OrderSearchParams;
import cz.palo.autoservis.model.dto.pagination.PagedResponse;
import cz.palo.autoservis.security.model.domain.AppUserDetails;
import cz.palo.autoservis.service.OrderDocumentService;
import cz.palo.autoservis.service.OrderService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;

/**
 * REST controller správy servisních zakázek.
 *
 * <p>Base path: {@code /api/{version}/orders}
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/{version}/orders")
public class OrderController {

    private final OrderService orderService;
    private final OrderDocumentService orderDocumentService;

    /**
     * Vrací úplný detail zakázky podle ID.
     *
     * @param id ID zakázky
     * @return 200 OK s detailem zakázky
     */
    @GetMapping("/{id}")
    public ResponseEntity<OrderDto.DetailResponse> getById(@PathVariable Long id) {
        return ResponseEntity.ok(orderService.getById(id));
    }

    /**
     * Vrací stránkovaný seznam aktivních zakázek odpovídajících zadaným parametrům hledání.
     *
     * @param params parametry hledání a stránkování
     * @return 200 OK se stránkovaným seznamem zakázek
     */
    @GetMapping
    public ResponseEntity<PagedResponse<OrderDto.ListResponse>> getPage(OrderSearchParams params) {
        return ResponseEntity.ok(orderService.getPage(params));
    }

    /**
     * Založí novou servisní zakázku.
     *
     * @param createRequest validované tělo requestu
     * @param currentUser   právě přihlášený uživatel
     * @return 201 Created s {@code Location} hlavičkou a detailem založené zakázky
     */
    @PostMapping
    public ResponseEntity<OrderDto.DetailResponse> create(
            @Valid @RequestBody OrderDto.CreateRequest createRequest,
            @AuthenticationPrincipal AppUserDetails currentUser) {
        OrderDto.DetailResponse created = orderService.create(createRequest, currentUser.getUserId());
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(created.getId())
                .toUri();
        return ResponseEntity.created(location).body(created);
    }

    /**
     * Aktualizuje existující servisní zakázku.
     *
     * @param id            ID zakázky
     * @param updateRequest validované tělo requestu
     * @param currentUser   právě přihlášený uživatel
     * @return 200 OK s detailem aktualizované zakázky
     */
    @PutMapping("/{id}")
    public ResponseEntity<OrderDto.DetailResponse> update(
            @PathVariable Long id,
            @Valid @RequestBody OrderDto.UpdateRequest updateRequest,
            @AuthenticationPrincipal AppUserDetails currentUser) {
        return ResponseEntity.ok(orderService.update(id, updateRequest, currentUser.getUserId()));
    }

    /**
     * Změní stav zakázky vyhrazenou cestou — bez otevírání editačního formuláře.
     *
     * @param id ID zakázky
     * @return 200 se zakázkou po změně; 422 u zakázaného přechodu nebo nesplněné podmínky
     */
    @PostMapping("/{id}/status")
    public ResponseEntity<OrderDto.DetailResponse> changeStatus(
            @PathVariable Long id,
            @Valid @RequestBody OrderDto.StatusRequest statusRequest,
            @AuthenticationPrincipal AppUserDetails currentUser) {
        return ResponseEntity.ok(orderService.changeStatus(id, statusRequest, currentUser.getUserId()));
    }

    /**
     * Zruší zakázku a vrátí veškerý vydaný materiál na sklad — jedním krokem.
     *
     * <p>Díly, které zůstaly na voze, zákazník zaplatí; patří na novou zakázku, ne na tuhle.
     *
     * @param id ID zakázky
     * @return 200 se zakázkou po zrušení; 422, má-li zakázka aktivní fakturu
     */
    @PostMapping("/{id}/cancel")
    public ResponseEntity<OrderDto.DetailResponse> cancel(
            @PathVariable Long id,
            @AuthenticationPrincipal AppUserDetails currentUser) {
        return ResponseEntity.ok(orderService.cancel(id, currentUser.getUserId()));
    }

    /**
     * Tvrdě smaže zakázku a <strong>vrátí veškerý vydaný materiál</strong> na sklad.
     *
     * <p>Mazání je pro zakázku, která nikdy neměla vzniknout. Faktura ho blokuje i historická
     * (účetní stopa se nemaže), skladový pohyb od 2026-08-07 nikoli — materiál se vrátí sám.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id,
                                       @AuthenticationPrincipal AppUserDetails currentUser) {
        orderService.delete(id, currentUser.getUserId());
        return ResponseEntity.noContent().build();
    }

    /**
     * Zakázkový list — PDF k podpisu při převzetí vozu (A4, inline).
     *
     * <p>Cesta je {@code /protocol}, ne {@code /pdf} jako u faktury, dobropisu a PPD: zakázka sama
     * doklad není, takže „PDF zakázky" by nepojmenovalo, o který dokument jde.
     *
     * @param id ID zakázky
     * @return 200 OK s PDF zakázkového listu
     */
    @GetMapping(value = "/{id}/protocol", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> protocol(@PathVariable Long id) {
        OrderDto.DetailResponse order = orderService.getById(id);
        byte[] pdf = orderDocumentService.renderPdf(id);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "inline; filename=\"zakazkovy-list-" + order.getOrderNumber() + ".pdf\"")
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdf);
    }
}
