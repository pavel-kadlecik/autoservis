package cz.palo.autoservis.service.impl;

import cz.palo.autoservis.client.AresClient;
import cz.palo.autoservis.exception.BusinessRuleException;
import cz.palo.autoservis.model.dto.ares.AresDto;
import cz.palo.autoservis.service.AresLookupService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Výchozí implementace {@link AresLookupService}. Bez {@code @Transactional}
 * a bez přístupu do DB — čistý průchod na HTTP klienta hlídaný validací IČO
 * (stejné zdůvodnění jako {@code VehicleRegistryServiceImpl}: externí volání
 * nesmí nikdy držet DB spojení).
 */
@Service
@RequiredArgsConstructor
public class AresLookupServiceImpl implements AresLookupService {

    private final AresClient aresClient;

    @Override
    public AresDto.LookupResponse lookup(String ico) {
        String normalized = ico == null ? "" : ico.trim();
        validate(normalized);

        return aresClient.fetch(normalized)
                .orElseThrow(() -> new BusinessRuleException(
                        "SUBJECT_NOT_IN_ARES",
                        "ico",
                        "Subjekt s IČO " + normalized + " nebyl v ARES nalezen.",
                        Map.of("ico", normalized)));
    }

    /**
     * Osm číslic plus kontrolní součet mod-11 (váhy 8…2 přes prvních sedm
     * číslic). IČO, které na součtu selže, nemůže být registrované, takže se
     * dotaz odmítne tady a volání ARES se neplýtvá.
     */
    private void validate(String ico) {
        if (!ico.matches("\\d{8}")) {
            throw invalidIco(ico, "IČO má přesně 8 číslic.");
        }
        int sum = 0;
        for (int i = 0; i < 7; i++) {
            sum += (ico.charAt(i) - '0') * (8 - i);
        }
        int checkDigit = (11 - sum % 11) % 10;
        if (checkDigit != ico.charAt(7) - '0') {
            throw invalidIco(ico, "IČO není platné — nesouhlasí kontrolní číslice.");
        }
    }

    private static BusinessRuleException invalidIco(String ico, String message) {
        return new BusinessRuleException("INVALID_ICO", "ico", message, Map.of("ico", ico));
    }
}
