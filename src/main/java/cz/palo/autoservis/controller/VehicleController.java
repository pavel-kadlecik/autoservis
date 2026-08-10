package cz.palo.autoservis.controller;

import cz.palo.autoservis.model.dto.autocomplete.AutocompleteResponse;
import cz.palo.autoservis.model.dto.pagination.PagedResponse;
import cz.palo.autoservis.model.dto.vehicle.VehicleAutocompleteParams;
import cz.palo.autoservis.model.dto.vehicle.VehicleDto;
import cz.palo.autoservis.model.dto.vehicle.VehicleSearchParams;
import cz.palo.autoservis.security.model.domain.AppUserDetails;
import cz.palo.autoservis.service.VehicleRegistryService;
import cz.palo.autoservis.service.VehicleService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;

/**
 * REST controller správy vozidel.
 *
 * <p>Base path: {@code /api/{version}/vehicles}
 */
@RestController
@RequestMapping("/api/{version}/vehicles")
@RequiredArgsConstructor
public class VehicleController {

    private final VehicleService vehicleService;
    private final VehicleRegistryService vehicleRegistryService;

    /**
     * Vrací úplný detail aktivního vozidla podle ID.
     *
     * @param id ID vozidla
     * @return 200 OK s detailem vozidla
     */
    @GetMapping("/{id}")
    public ResponseEntity<VehicleDto.DetailResponse> getById(@PathVariable Long id) {
        return ResponseEntity.ok(vehicleService.getById(id));
    }

    /**
     * Vrací stránkovaný seznam aktivních vozidel odpovídajících zadaným parametrům hledání.
     *
     * @param params parametry hledání a stránkování
     * @return 200 OK se stránkovaným seznamem vozidel
     */
    @GetMapping
    public ResponseEntity<PagedResponse<VehicleDto.ListResponse>> getPage(VehicleSearchParams params) {
        return ResponseEntity.ok(vehicleService.getPage(params));
    }

    /**
     * Vrací návrhy našeptávače pro vyhledávací pole vozidel.
     *
     * @param params parametry našeptávače (hledaný řetězec, limit)
     * @return 200 OK s položkami našeptávače
     */
    @GetMapping("/autocomplete")
    public ResponseEntity<AutocompleteResponse> autocomplete(
            @Valid @ModelAttribute VehicleAutocompleteParams params) {
        return ResponseEntity.ok(vehicleService.autocomplete(params));
    }

    /**
     * Založí nové vozidlo navázané na existujícího zákazníka a poté se best-effort
     * pokusí stáhnout jeho STK data z národního registru.
     *
     * <p>Volání registru se orchestruje tady — po návratu z {@code create} je jeho
     * transakce commitnutá, takže HTTP volání nikdy nedrží DB transakci a výpadek
     * registru nemůže shodit založení. Response proto {@code stkValidUntil} ještě
     * nenese; detailová obrazovka si ho načte čerstvé.
     *
     * @param createRequest validované tělo requestu
     * @param currentUser   právě přihlášený uživatel
     * @return 201 Created s {@code Location} hlavičkou a detailem založeného vozidla
     */
    @PostMapping
    public ResponseEntity<VehicleDto.DetailResponse> create(
            @Valid @RequestBody VehicleDto.CreateRequest createRequest,
            @AuthenticationPrincipal AppUserDetails currentUser) {
        VehicleDto.DetailResponse created =
                vehicleService.create(createRequest, currentUser.getUserId());
        vehicleRegistryService.tryRefreshAfterCreate(created.getId(), currentUser.getUserId());
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(created.getId())
                .toUri();
        return ResponseEntity.created(location).body(created);
    }

    /**
     * Aktualizuje existující vozidlo.
     *
     * @param id            ID vozidla
     * @param updateRequest validované tělo requestu
     * @param currentUser   právě přihlášený uživatel
     * @return 200 OK s detailem aktualizovaného vozidla
     */
    @PutMapping("/{id}")
    public ResponseEntity<VehicleDto.DetailResponse> update(
            @PathVariable Long id,
            @Valid @RequestBody VehicleDto.UpdateRequest updateRequest,
            @AuthenticationPrincipal AppUserDetails currentUser) {
        return ResponseEntity.ok(vehicleService.update(id, updateRequest, currentUser.getUserId()));
    }

    /**
     * Deaktivuje vozidlo (soft delete).
     *
     * @param id ID vozidla
     * @return 200 OK s detailem aktualizovaného vozidla
     */
    // E7 (audit R-6): (de)aktivace vozidla je správní úkon vedení; mechanik zakládá a edituje,
    // ale nevyřazuje vozidlo z evidence.
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    public ResponseEntity<VehicleDto.DetailResponse> deactivate(@PathVariable Long id) {
        return ResponseEntity.ok(vehicleService.deactivate(id));
    }

    /**
     * Znovu aktivuje dříve deaktivované vozidlo.
     *
     * @param id ID vozidla
     * @return 200 OK s detailem aktualizovaného vozidla
     */
    @PostMapping("/{id}/activate")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    public ResponseEntity<VehicleDto.DetailResponse> activate(@PathVariable Long id) {
        return ResponseEntity.ok(vehicleService.activate(id));
    }
}
