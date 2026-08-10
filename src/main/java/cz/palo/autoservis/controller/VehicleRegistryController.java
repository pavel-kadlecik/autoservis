package cz.palo.autoservis.controller;

import cz.palo.autoservis.model.dto.registry.RegistryDto;
import cz.palo.autoservis.security.model.domain.AppUserDetails;
import cz.palo.autoservis.service.VehicleRegistryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * REST controller integrace s národním registrem vozidel (dataovozidlech.cz).
 *
 * <p>Base path: {@code /api/{version}/vehicles}. Literální segment
 * {@code /registry-lookup} má přednost před šablonou {@code /{id}}
 * ve {@code VehicleController} — mapování nekolidují.
 */
@RestController
@RequestMapping("/api/{version}/vehicles")
@RequiredArgsConstructor
public class VehicleRegistryController {

    private final VehicleRegistryService vehicleRegistryService;

    /**
     * Dotáže se registru kvůli předvyplnění formuláře — nic neukládá. Vozidlo
     * v naší DB ještě neexistuje, proto query parametry místo ID v cestě.
     * Parametry se kombinují jako AND; alespoň jeden je povinný.
     *
     * @param vin 17znakový VIN
     * @param tp  číslo technického průkazu
     * @param orv číslo osvědčení o registraci vozidla
     * @return 200 OK s namapovanými daty z registru (422 při nenalezení / špatných
     *         parametrech, 503 při nedostupnosti registru)
     */
    @GetMapping("/registry-lookup")
    public ResponseEntity<RegistryDto.LookupResponse> registryLookup(
            @RequestParam(required = false) String vin,
            @RequestParam(required = false) String tp,
            @RequestParam(required = false) String orv) {
        return ResponseEntity.ok(vehicleRegistryService.lookup(vin, tp, orv));
    }

    /**
     * Stáhne aktuální stav registru pro existující vozidlo (podle jeho VIN)
     * a uloží nový snapshot. 200, ne 201 — akční endpoint jako
     * {@code POST /{id}/activate}; snapshot je vedlejší produkt.
     *
     * @param vehicleId   ID vozidla
     * @param currentUser právě přihlášený uživatel (audit)
     * @return 200 OK s uloženým snapshotem
     */
    @PostMapping("/{vehicleId}/registry-refresh")
    public ResponseEntity<RegistryDto.SnapshotResponse> registryRefresh(
            @PathVariable Long vehicleId,
            @AuthenticationPrincipal AppUserDetails currentUser) {
        return ResponseEntity.ok(
                vehicleRegistryService.refreshForVehicle(vehicleId, currentUser.getUserId()));
    }

    /**
     * Vrací všechny uložené snapshoty registru k vozidlu, nejnovější první.
     *
     * @param vehicleId ID vozidla
     * @return 200 OK se seznamem snapshotů (klidně prázdným)
     */
    @GetMapping("/{vehicleId}/registry-snapshots")
    public ResponseEntity<List<RegistryDto.SnapshotResponse>> registrySnapshots(
            @PathVariable Long vehicleId) {
        return ResponseEntity.ok(vehicleRegistryService.findSnapshotsByVehicleId(vehicleId));
    }
}
