package cz.palo.autoservis.controller;

import cz.palo.autoservis.model.dto.autocomplete.AutocompleteResponse;
import cz.palo.autoservis.model.dto.customer.CustomerAutocompleteParams;
import cz.palo.autoservis.model.dto.customer.CustomerDto;
import cz.palo.autoservis.model.dto.customer.CustomerSearchParams;
import cz.palo.autoservis.model.dto.pagination.PagedResponse;
import cz.palo.autoservis.model.dto.vehicle.VehicleDto;
import cz.palo.autoservis.security.model.domain.AppUserDetails;
import cz.palo.autoservis.service.CustomerService;
import cz.palo.autoservis.service.VehicleService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.List;

/**
 * REST controller správy zákazníků.
 *
 * <p>Base path: {@code /api/{version}/customers}
 */
@RestController
@RequestMapping("/api/{version}/customers")
@RequiredArgsConstructor
public class CustomerController {

    private final CustomerService customerService;
    private final VehicleService vehicleService;

    /**
     * Vrací plný detail zákazníka podle ID.
     *
     * @param id ID zákazníka
     * @return 200 OK s detailem zákazníka
     */
    @GetMapping("/{id}")
    public ResponseEntity<CustomerDto.DetailResponse> getById(@PathVariable Long id) {
        return ResponseEntity.ok(customerService.getById(id));
    }

    /**
     * Vrací všechna aktivní vozidla jednoho zákazníka (prázdný seznam, když žádná nemá).
     *
     * <p>Doc tady dřív byla táž věta jako u {@code VehicleController.getById} — o detailu
     * jednoho vozidla (audit 10/A-3).
     *
     * @param id ID zákazníka
     * @return 200 OK se seznamem vozidel zákazníka
     */
    @GetMapping("/{id}/vehicles")
    public ResponseEntity<List<VehicleDto.SummaryResponse>> getCustomerVehiclesId(@PathVariable Long id) {
        return ResponseEntity.ok(vehicleService.findByCustomerId(id));
    }

    /**
     * Vrací stránkovaný seznam zákazníků odpovídajících zadaným vyhledávacím parametrům.
     *
     * @param params vyhledávací a stránkovací parametry
     * @return 200 OK se stránkovaným seznamem zákazníků
     */
    @GetMapping
    public ResponseEntity<PagedResponse<CustomerDto.ListResponse>> getPage(CustomerSearchParams params) {
        return ResponseEntity.ok(customerService.getPage(params));
    }

    /**
     * Vrací našeptávané návrhy pro vyhledávací pole zákazníků.
     *
     * @param params parametry našeptávače (hledaný řetězec, limit)
     * @return 200 OK s položkami našeptávače
     */
    @GetMapping("/autocomplete")
    public ResponseEntity<AutocompleteResponse> autocomplete(
            @Valid @ModelAttribute CustomerAutocompleteParams params) {
        return ResponseEntity.ok(customerService.autocomplete(params));
    }

    /**
     * Založí nového zákazníka.
     *
     * @param createRequest zvalidované tělo requestu
     * @param currentUser   právě přihlášený uživatel
     * @return 201 Created s {@code Location} hlavičkou a detailem založeného zákazníka
     */
    @PostMapping
    public ResponseEntity<CustomerDto.DetailResponse> create(
            @RequestBody @Valid CustomerDto.CreateRequest createRequest,
            @AuthenticationPrincipal AppUserDetails currentUser) {
        CustomerDto.DetailResponse created = customerService.create(createRequest, currentUser.getUserId());
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(created.getId())
                .toUri();
        return ResponseEntity.created(location).body(created);
    }

    /**
     * Upraví existujícího zákazníka.
     *
     * @param id            ID zákazníka
     * @param updateRequest zvalidované tělo requestu
     * @param currentUser   právě přihlášený uživatel
     * @return 200 OK s aktualizovaným detailem zákazníka
     */
    @PutMapping("/{id}")
    public ResponseEntity<CustomerDto.DetailResponse> update(
            @PathVariable Long id,
            @RequestBody @Valid CustomerDto.UpdateRequest updateRequest,
            @AuthenticationPrincipal AppUserDetails currentUser) {
        return ResponseEntity.ok(customerService.update(id, updateRequest, currentUser.getUserId()));
    }

    /**
     * Deaktivuje zákazníka (soft delete).
     *
     * @param id ID zákazníka
     * @return 200 OK s aktualizovaným detailem zákazníka
     */
    // E7 (audit R-6): (de)aktivace zákazníka je správní úkon vedení; mechanik zakládá a edituje,
    // ale nevyřazuje kartu z evidence.
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    public ResponseEntity<CustomerDto.DetailResponse> deactivate(@PathVariable Long id) {
        return ResponseEntity.ok(customerService.deactivate(id));
    }

    /**
     * Znovu aktivuje dříve deaktivovaného zákazníka.
     *
     * @param id ID zákazníka
     * @return 200 OK s aktualizovaným detailem zákazníka
     */
    @PostMapping("/{id}/activate")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    public ResponseEntity<CustomerDto.DetailResponse> activate(@PathVariable Long id) {
        return ResponseEntity.ok(customerService.activate(id));
    }
}
