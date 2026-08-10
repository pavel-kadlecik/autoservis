package cz.palo.autoservis.controller.warehouse;

import cz.palo.autoservis.model.dto.pagination.PagedResponse;
import cz.palo.autoservis.model.dto.warehouse.ProductDto;
import cz.palo.autoservis.model.dto.warehouse.ProductSearchParams;
import cz.palo.autoservis.model.dto.warehouse.StockMovementDto;
import cz.palo.autoservis.security.model.domain.AppUserDetails;
import cz.palo.autoservis.service.ProductService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.List;

/**
 * REST controller přehledu skladových zásob (produktů).
 *
 * <p>Base path: {@code /api/{version}/warehouse/products}
 */
@RestController
@RequestMapping("/api/{version}/warehouse/products")
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;

    /**
     * Vrací stránkovaný seznam produktů odpovídajících zadaným parametrům hledání.
     *
     * @param params parametry hledání a stránkování
     * @return 200 OK se stránkovaným seznamem produktů
     */
    @GetMapping
    public ResponseEntity<PagedResponse<ProductDto.ListResponse>> getPage(ProductSearchParams params) {
        return ResponseEntity.ok(productService.getPage(params));
    }

    /**
     * Vrací úplnou skladovou kartu produktu (hlavička, šarže, historie pohybů).
     *
     * @param id ID produktu
     * @return 200 OK s detailem produktu
     */
    @GetMapping("/{id}")
    public ResponseEntity<ProductDto.DetailResponse> getById(@PathVariable Long id) {
        return ResponseEntity.ok(productService.getById(id));
    }

    /**
     * Vrací produkty pod hlídaným minimem, každý s dodavatelem, který ho naposledy
     * dodal, a jeho poslední cenou (E8.3) — podklad pro objednávku, ne objednávka.
     *
     * @return 200 OK s řádky podlimitních zásob, největší manko první
     */
    @GetMapping("/low-stock")
    public ResponseEntity<List<cz.palo.autoservis.model.dto.warehouse.LowStockDto>> getLowStock() {
        return ResponseEntity.ok(productService.getLowStock());
    }

    /**
     * Vrací produkty jedné příjemky (řádky seznamu — bez šarží, bez historie pohybů).
     *
     * <p>Doc tady dřív popisovala {@code getById} o sedmnáct řádků výš (audit 10/A-3).
     *
     * @param id ID příjemky
     * @return 200 OK se seznamem produktů
     */
    @GetMapping("/import/{id}")
    public ResponseEntity<List<ProductDto.ListResponse>> getByGoodsReceipt(@PathVariable Long id) {
        return ResponseEntity.ok(productService.getByGoodsReceiptId(id));
    }

    /**
     * Založí nový produkt (skladovou kartu).
     *
     * @param request validované tělo requestu
     * @return 201 Created s {@code Location} hlavičkou a detailem založeného produktu
     */
    @PostMapping
    public ResponseEntity<ProductDto.DetailResponse> create(@Valid @RequestBody ProductDto.CreateRequest request) {
        ProductDto.DetailResponse created = productService.create(request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(created.getId())
                .toUri();
        return ResponseEntity.created(location).body(created);
    }

    /**
     * Aktualizuje katalogová pole produktu.
     *
     * @param id      ID produktu
     * @param request validované tělo requestu
     * @return 200 OK s detailem aktualizovaného produktu
     */
    @PutMapping("/{id}")
    public ResponseEntity<ProductDto.DetailResponse> update(@PathVariable Long id,
                                                            @Valid @RequestBody ProductDto.UpdateRequest request) {
        return ResponseEntity.ok(productService.update(id, request));
    }

    /**
     * Deaktivuje produkt (soft delete).
     *
     * @param id ID produktu
     * @return 200 OK s detailem aktualizovaného produktu
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<ProductDto.DetailResponse> deactivate(@PathVariable Long id) {
        return ResponseEntity.ok(productService.deactivate(id));
    }

    /**
     * Znovu aktivuje dříve deaktivovaný produkt.
     *
     * @param id ID produktu
     * @return 200 OK s detailem aktualizovaného produktu
     */
    @PostMapping("/{id}/activate")
    public ResponseEntity<ProductDto.DetailResponse> activate(@PathVariable Long id) {
        return ResponseEntity.ok(productService.activate(id));
    }

    /**
     * Zaznamená ruční záporný skladový pohyb proti jedné ze šarží produktu.
     *
     * <p>Přijímají se čtyři typy — korekce dolů ({@code ADJUSTMENT}), odpis
     * ({@code WRITE_OFF}), vrácení dodavateli ({@code RETURN}) a spotřeba mimo zakázku
     * ({@code ISSUE}) — viz {@code StockMovementDto.CreateRequest.isManualMovementType}.
     * Stav skladu a zůstatek šarže odečítá DB trigger. Přebytek řeší ruční příjemka (R-E),
     * ne tento endpoint.
     *
     * @param id          ID produktu
     * @param request     validovaný request pohybu (typ, šarže, kladné množství, poznámka)
     * @param currentUser přihlášený uživatel (audit)
     * @return 200 OK s detailem aktualizovaného produktu
     */
    @PostMapping("/{id}/movements")
    public ResponseEntity<ProductDto.DetailResponse> registerMovement(
            @PathVariable Long id,
            @Valid @RequestBody StockMovementDto.CreateRequest request,
            @AuthenticationPrincipal AppUserDetails currentUser) {
        return ResponseEntity.ok(
                productService.registerManualMovement(id, request, currentUser.getUserId()));
    }
}
