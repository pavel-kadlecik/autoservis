package cz.palo.autoservis.controller.warehouse;

import cz.palo.autoservis.model.dto.warehouse.StockTakeDto;
import cz.palo.autoservis.model.dto.warehouse.StockTakeSearchParams;
import cz.palo.autoservis.model.dto.pagination.PagedResponse;
import cz.palo.autoservis.security.model.domain.AppUserDetails;
import cz.palo.autoservis.service.StockTakeService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;

/**
 * REST controller pro inventuru (E6, P-5).
 *
 * <p>Base path: {@code /api/{version}/warehouse/stock-takes}
 */
@RestController
@RequestMapping("/api/{version}/warehouse/stock-takes")
@RequiredArgsConstructor
public class StockTakeController {

    private final StockTakeService stockTakeService;

    /** Stránkovaný seznam inventur (výchozí řazení: nejnovější první). */
    @GetMapping
    public ResponseEntity<PagedResponse<StockTakeDto.ListResponse>> getPage(StockTakeSearchParams params) {
        return ResponseEntity.ok(stockTakeService.getPage(params));
    }

    /** Detail se soupisem a dopočtenými rozdíly. */
    @GetMapping("/{id}")
    public ResponseEntity<StockTakeDto.DetailResponse> getById(@PathVariable Long id) {
        return ResponseEntity.ok(stockTakeService.getDetail(id));
    }

    /** Otevře inventuru a nasnapshotuje soupis aktivních produktů. */
    @PostMapping
    public ResponseEntity<StockTakeDto.DetailResponse> open(
            @Valid @RequestBody(required = false) StockTakeDto.CreateRequest request,
            @AuthenticationPrincipal AppUserDetails currentUser) {
        StockTakeDto.DetailResponse created = stockTakeService.open(request, currentUser.getUserId());
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(created.getId())
                .toUri();
        return ResponseEntity.created(location).body(created);
    }

    /** Dávkový zápis napočítaných množství a cen přebytku. */
    @PutMapping("/{id}/items")
    public ResponseEntity<StockTakeDto.DetailResponse> updateItems(
            @PathVariable Long id,
            @Valid @RequestBody StockTakeDto.ItemsUpdateRequest request) {
        return ResponseEntity.ok(stockTakeService.updateItems(id, request));
    }

    /** Uzavře inventuru a vygeneruje korekční pohyby. */
    // E7 (audit R-6): uzavření zmaterializuje korekční skladové pohyby s cenovým dopadem —
    // vyhrazeno vedení. Otevření a sčítání smí i mechanik (fyzicky počítá on).
    @PostMapping("/{id}/close")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    public ResponseEntity<StockTakeDto.DetailResponse> close(
            @PathVariable Long id,
            @Valid @RequestBody(required = false) StockTakeDto.CloseRequest request,
            @AuthenticationPrincipal AppUserDetails currentUser) {
        String note = request == null ? null : request.getNote();
        return ResponseEntity.ok(stockTakeService.close(id, note, currentUser.getUserId()));
    }

    /** Zruší otevřenou inventuru — nic se nematerializuje. */
    @PostMapping("/{id}/cancel")
    public ResponseEntity<StockTakeDto.DetailResponse> cancel(
            @PathVariable Long id,
            @AuthenticationPrincipal AppUserDetails currentUser) {
        return ResponseEntity.ok(stockTakeService.cancel(id, currentUser.getUserId()));
    }
}
