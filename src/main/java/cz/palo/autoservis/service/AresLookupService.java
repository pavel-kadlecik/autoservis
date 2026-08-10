package cz.palo.autoservis.service;

import cz.palo.autoservis.model.dto.ares.AresDto;

/**
 * Vyhledání ekonomických subjektů v ARES (ares.gov.cz) — zdroj dat pro
 * předvyplnění formuláře zákazníka. Výhradně striktní sémantika: selhání se
 * propagují volajícímu ({@code AresUnavailableException} → 503, „nenalezeno" → 422);
 * nic se neperzistuje, takže best-effort varianta neexistuje.
 */
public interface AresLookupService {

    /**
     * Dotáže se ARES podle IČO, nic neperzistuje.
     *
     * @param ico osmimístné IČO
     * @return namapovaná data firmy (název, DIČ, adresa sídla)
     * @throws cz.palo.autoservis.exception.BusinessRuleException
     *         {@code INVALID_ICO} při vadném IČO (špatná délka nebo nesedící
     *         kontrolní číslice — takové IČO nemůže existovat, ARES se ani nevolá),
     *         {@code SUBJECT_NOT_IN_ARES} když ARES žádný takový subjekt nezná
     * @throws cz.palo.autoservis.exception.AresUnavailableException
     *         když se ARES nepodaří dotázat
     */
    AresDto.LookupResponse lookup(String ico);
}
