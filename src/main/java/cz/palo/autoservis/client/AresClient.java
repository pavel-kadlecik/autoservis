package cz.palo.autoservis.client;

import cz.palo.autoservis.model.dto.ares.AresDto;

import java.util.Optional;

/**
 * Klient ARES — Administrativního registru ekonomických subjektů (ares.gov.cz).
 *
 * <p>Veřejné REST API Ministerstva financí, bez API klíče. Subjekt se adresuje
 * přímo přes IČO: {@code GET /ekonomicke-subjekty/{ico}}; HTTP 404 znamená
 * „subjekt neexistuje" (platná business odpověď, ne selhání).
 */
public interface AresClient {

    /**
     * Vyhledá ekonomický subjekt podle IČO.
     *
     * @param ico osmimístné IČO (validuje service vrstva)
     * @return namapovaná data firmy, nebo prázdný Optional, když ARES subjekt nezná
     * @throws cz.palo.autoservis.exception.AresUnavailableException
     *         když se ARES nelze dotázat — rate limit ({@code ARES_RATE_LIMITED}),
     *         timeout ({@code ARES_TIMEOUT}) nebo jiné transportní/serverové
     *         selhání ({@code ARES_ERROR})
     */
    Optional<AresDto.LookupResponse> fetch(String ico);
}
