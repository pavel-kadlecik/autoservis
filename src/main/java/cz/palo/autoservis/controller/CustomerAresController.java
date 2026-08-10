package cz.palo.autoservis.controller;

import cz.palo.autoservis.model.dto.ares.AresDto;
import cz.palo.autoservis.service.AresLookupService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller integrace na ARES (ares.gov.cz).
 *
 * <p>Base path: {@code /api/{version}/customers}. Literální segment
 * {@code /ares-lookup} má přednost před šablonou {@code /{id}}
 * v {@code CustomerController} — mapování nekoliduje. Stejný vzor
 * jako {@code VehicleRegistryController} na {@code /vehicles}.
 */
@RestController
@RequestMapping("/api/{version}/customers")
@RequiredArgsConstructor
public class CustomerAresController {

    private final AresLookupService aresLookupService;

    /**
     * Dotáže se ARES kvůli předvyplnění formuláře — nic neukládá. Zákazník
     * v naší DB ještě neexistuje, proto query parametr místo ID v cestě.
     *
     * @param ico osmimístné IČO
     * @return 200 OK s namapovanými údaji firmy (422 při nenalezení / chybném IČO,
     *         503 při nedostupném ARES)
     */
    @GetMapping("/ares-lookup")
    public ResponseEntity<AresDto.LookupResponse> aresLookup(@RequestParam String ico) {
        return ResponseEntity.ok(aresLookupService.lookup(ico));
    }
}
