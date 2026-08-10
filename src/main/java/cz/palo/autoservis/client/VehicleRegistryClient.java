package cz.palo.autoservis.client;

import cz.palo.autoservis.model.dto.registry.RegistryFetchResult;
import cz.palo.autoservis.model.dto.registry.RegistryLookupParams;

import java.util.Optional;

/**
 * Klient státního registru vozidel — dataovozidlech.cz (Datová kostka RSV).
 *
 * <p>Obálka odpovědi je {@code { "Status": 1, "Data": { ... } }};
 * {@code Status == 1} s nenulovým {@code Data} znamená, že vozidlo bylo nalezeno.
 * API má rate limit 27 požadavků za minutu na klíč.
 */
public interface VehicleRegistryClient {

    /**
     * Vyhledá vozidlo podle libovolné kombinace čísel VIN / TP / ORV
     * (API parametry kombinuje jako AND).
     *
     * @param params parametry hledání; alespoň jeden musí být neprázdný
     *               (garantuje service vrstva)
     * @return namapovaná data plus surový {@code Data} JSON, nebo prázdný
     *         Optional, když registr vozidlo nezná
     * @throws cz.palo.autoservis.exception.RegistryUnavailableException
     *         když se registru nelze dotázat — rate limit
     *         ({@code REGISTRY_RATE_LIMITED}), neplatný klíč
     *         ({@code REGISTRY_AUTH_FAILED}), timeout ({@code REGISTRY_TIMEOUT})
     *         nebo jiné transportní/serverové selhání ({@code REGISTRY_ERROR})
     */
    Optional<RegistryFetchResult> fetch(RegistryLookupParams params);
}
