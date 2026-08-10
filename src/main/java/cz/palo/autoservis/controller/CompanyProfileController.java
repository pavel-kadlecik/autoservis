package cz.palo.autoservis.controller;

import cz.palo.autoservis.model.dto.billing.CompanyProfileDto;
import cz.palo.autoservis.service.CompanyProfileService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/{version}/invoices/company-profile")
public class CompanyProfileController {

    private final CompanyProfileService companyProfileService;

    /**
     * Vrací aktuální profil firmy.
     *
     * @return ResponseEntity s aktuálním profilem firmy
     */
    @GetMapping
    public ResponseEntity<CompanyProfileDto.Response> get() {
        return ResponseEntity.ok(companyProfileService.get());
    }


    /**
     * Aktualizuje profil firmy podle zaslaného update requestu.
     *
     * @param updateRequest tělo requestu s aktualizovanými údaji profilu firmy
     * @return ResponseEntity s aktualizovaným profilem firmy
     */
    // E7 (audit R-6): profil firmy (hlavička faktur, IBAN, DIČ) mění jen vedení — je to
    // fakturační identita servisu, ne provozní údaj.
    @PutMapping
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    public ResponseEntity<CompanyProfileDto.Response> update (
            @Valid @RequestBody CompanyProfileDto.UpdateRequest updateRequest
            ) {
        return ResponseEntity.ok(companyProfileService.update(updateRequest));
    }

}
