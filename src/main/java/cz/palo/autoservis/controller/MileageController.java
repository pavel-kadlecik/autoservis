package cz.palo.autoservis.controller;

import cz.palo.autoservis.model.dto.vehicle.MileageDto;
import cz.palo.autoservis.security.model.domain.AppUserDetails;
import cz.palo.autoservis.service.MileageService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.List;

/**
 * REST controller historie záznamů tachometru vozidla.
 *
 * <p>Base path: {@code /api/{version}/vehicles/{vehicleId}/mileage}
 */
@RestController
@RequestMapping("/api/{version}/vehicles/{vehicleId}/mileage")
@RequiredArgsConstructor
public class MileageController {

    private final MileageService mileageService;

    /**
     * Vrací všechny záznamy vozidla, nejnovější první.
     *
     * @param vehicleId ID vozidla
     * @return 200 OK se seznamem záznamů
     */
    @GetMapping
    public ResponseEntity<List<MileageDto.Response>> findByVehicleId(@PathVariable Long vehicleId) {
        return ResponseEntity.ok(mileageService.findByVehicleId(vehicleId));
    }

    /**
     * Zaznamená nový stav tachometru vozidla.
     *
     * @param vehicleId   ID vozidla
     * @param request     validované tělo requestu
     * @param currentUser právě přihlášený uživatel
     * @return 201 Created s {@code Location} hlavičkou a novým záznamem
     */
    @PostMapping
    public ResponseEntity<MileageDto.Response> addReading(
            @PathVariable Long vehicleId,
            @Valid @RequestBody MileageDto.CreateRequest request,
            @AuthenticationPrincipal AppUserDetails currentUser) {
        MileageDto.Response created = mileageService.addReading(vehicleId, request, currentUser.getUserId());
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(created.getId())
                .toUri();
        return ResponseEntity.created(location).body(created);
    }

    /**
     * Opraví existující záznam.
     *
     * @param vehicleId   ID vozidla (záznam mu musí patřit)
     * @param readingId   ID záznamu
     * @param request     validované tělo requestu
     * @param currentUser právě přihlášený uživatel
     * @return 200 OK s aktualizovaným záznamem
     */
    @PutMapping("/{readingId}")
    public ResponseEntity<MileageDto.Response> updateReading(
            @PathVariable Long vehicleId,
            @PathVariable Long readingId,
            @Valid @RequestBody MileageDto.UpdateRequest request,
            @AuthenticationPrincipal AppUserDetails currentUser) {
        return ResponseEntity.ok(
                mileageService.updateReading(vehicleId, readingId, request, currentUser.getUserId()));
    }

    /**
     * Smaže záznam.
     *
     * @param vehicleId ID vozidla (záznam mu musí patřit)
     * @param readingId ID záznamu
     * @return 204 No Content
     */
    @DeleteMapping("/{readingId}")
    public ResponseEntity<Void> deleteReading(
            @PathVariable Long vehicleId,
            @PathVariable Long readingId) {
        mileageService.deleteReading(vehicleId, readingId);
        return ResponseEntity.noContent().build();
    }
}
