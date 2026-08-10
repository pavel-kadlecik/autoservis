package cz.palo.autoservis.controller;

import cz.palo.autoservis.model.dto.employee.EmployeeDto;
import cz.palo.autoservis.security.model.domain.AppUserDetails;
import cz.palo.autoservis.service.EmployeeService;
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
 * REST controller správy zaměstnanců (modul {@code employee}).
 *
 * <p>Base path: {@code /api/{version}/employees}
 *
 * <p>Autorizace (§19, D-7): čtení seznamu a detailu je otevřené všem pracovním
 * rolím — MECHANIC potřebuje seznam aktivních, aby vybral, kdo provedl položku LABOR.
 * Správní mutace (create/update/deactivate/activate) jsou omezeny na
 * {@code ADMIN}/{@code MANAGER} přes {@code @PreAuthorize} na metodách.
 */
@RestController
@RequestMapping("/api/{version}/employees")
@RequiredArgsConstructor
public class EmployeeController {

    private final EmployeeService employeeService;

    /**
     * Vrací seznam zaměstnanců — obrazovka správy a select u položky LABOR.
     *
     * @param activeOnly při {@code true} jen aktivní zaměstnanci (default {@code false})
     * @return 200 OK se seznamem zaměstnanců
     */
    @GetMapping
    public ResponseEntity<List<EmployeeDto.ListResponse>> getAll(
            @RequestParam(defaultValue = "false") boolean activeOnly) {
        return ResponseEntity.ok(employeeService.getAll(activeOnly));
    }

    /**
     * Vrací plný detail aktivního zaměstnance podle ID.
     *
     * @param id ID zaměstnance
     * @return 200 OK s detailem zaměstnance
     */
    @GetMapping("/{id}")
    public ResponseEntity<EmployeeDto.DetailResponse> getById(@PathVariable Long id) {
        return ResponseEntity.ok(employeeService.getById(id));
    }

    /**
     * Založí nového zaměstnance.
     *
     * @param request     zvalidované tělo requestu
     * @param currentUser právě přihlášený uživatel (audit {@code created_by})
     * @return 201 Created s {@code Location} hlavičkou a detailem založeného zaměstnance
     */
    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    public ResponseEntity<EmployeeDto.DetailResponse> create(
            @Valid @RequestBody EmployeeDto.CreateRequest request,
            @AuthenticationPrincipal AppUserDetails currentUser) {
        EmployeeDto.DetailResponse created = employeeService.create(request, currentUser.getUserId());
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(created.getId())
                .toUri();
        return ResponseEntity.created(location).body(created);
    }

    /**
     * Upraví existujícího zaměstnance.
     *
     * @param id          ID zaměstnance
     * @param request     zvalidované tělo requestu
     * @param currentUser právě přihlášený uživatel
     * @return 200 OK s aktualizovaným detailem zaměstnance
     */
    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    public ResponseEntity<EmployeeDto.DetailResponse> update(
            @PathVariable Long id,
            @Valid @RequestBody EmployeeDto.UpdateRequest request,
            @AuthenticationPrincipal AppUserDetails currentUser) {
        return ResponseEntity.ok(employeeService.update(id, request, currentUser.getUserId()));
    }

    /**
     * Deaktivuje zaměstnance (soft delete). Záznam se kvůli historii uchovává navždy (D-4).
     *
     * @param id ID zaměstnance
     * @return 200 OK s aktualizovaným detailem zaměstnance
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    public ResponseEntity<EmployeeDto.DetailResponse> deactivate(@PathVariable Long id) {
        return ResponseEntity.ok(employeeService.deactivate(id));
    }

    /**
     * Znovu aktivuje dříve deaktivovaného zaměstnance.
     *
     * @param id ID zaměstnance
     * @return 200 OK s aktualizovaným detailem zaměstnance
     */
    @PostMapping("/{id}/activate")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    public ResponseEntity<EmployeeDto.DetailResponse> activate(@PathVariable Long id) {
        return ResponseEntity.ok(employeeService.activate(id));
    }
}
