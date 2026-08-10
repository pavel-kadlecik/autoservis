package cz.palo.autoservis.controller.warehouse;

import cz.palo.autoservis.model.dto.pagination.PagedResponse;
import cz.palo.autoservis.model.dto.warehouse.SupplierDto;
import cz.palo.autoservis.model.dto.warehouse.SupplierSearchParams;
import cz.palo.autoservis.service.SupplierService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/{version}/warehouse/suppliers")
@RequiredArgsConstructor
public class SupplierController {

    private final SupplierService supplierService;

    /**
     * Vrací stránkovaný seznam dodavatelů odpovídajících zadaným parametrům hledání.
     *
     * @param params parametry hledání a stránkování
     * @return 200 OK se stránkovaným seznamem dodavatelů
     */
    @GetMapping
    public ResponseEntity<PagedResponse<SupplierDto.ListResponse>> getPage(SupplierSearchParams params) {
        return ResponseEntity.ok(supplierService.getPage(params));
    }

    /**
     * Vrací úplný detail dodavatele podle ID.
     *
     * @param id ID dodavatele
     * @return 200 OK s detailem dodavatele
     */
    @GetMapping("/{id}")
    public ResponseEntity<SupplierDto.DetailResponse> getById(@PathVariable Long id) {
        return ResponseEntity.ok(supplierService.getById(id));
    }

    /**
     * Aktualizuje katalogová pole dodavatele.
     *
     * @param id      ID dodavatele
     * @param request validované tělo requestu
     * @return 200 OK s detailem aktualizovaného dodavatele
     */
    @PutMapping("/{id}")
    public ResponseEntity<SupplierDto.DetailResponse> update(@PathVariable Long id,
                                                            @Valid @RequestBody SupplierDto.UpdateRequest request) {
        return ResponseEntity.ok(supplierService.update(id, request));
    }

    /**
     * Deaktivuje dodavatele (soft delete).
     *
     * @param id ID dodavatele
     * @return 200 OK s detailem aktualizovaného dodavatele
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<SupplierDto.DetailResponse> deactivate(@PathVariable Long id) {
        return ResponseEntity.ok(supplierService.deactivate(id));
    }

    /**
     * Znovu aktivuje dříve deaktivovaného dodavatele.
     *
     * @param id ID dodavatele
     * @return 200 OK s detailem aktualizovaného dodavatele
     */
    @PostMapping("/{id}/activate")
    public ResponseEntity<SupplierDto.DetailResponse> activate(@PathVariable Long id) {
        return ResponseEntity.ok(supplierService.activate(id));
    }

}
