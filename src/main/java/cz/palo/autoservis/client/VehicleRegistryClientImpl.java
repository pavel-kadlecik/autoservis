package cz.palo.autoservis.client;

import cz.palo.autoservis.exception.RegistryUnavailableException;
import cz.palo.autoservis.model.dto.registry.RegistryFetchResult;
import cz.palo.autoservis.model.dto.registry.RegistryLookupParams;
import cz.palo.autoservis.model.dto.registry.RegistryVehicleData;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriBuilder;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Locale;
import java.util.Optional;

/**
 * {@link VehicleRegistryClient} postavený na beanu {@code vehicleRegistryRestClient}
 * (base URL, hlavička {@code API_KEY} a timeouty jdou z konfigurace).
 *
 * <p>Tělo se čte jako String a parsuje ručně: při rate limitu může API
 * odpovědět 2xx s českou plain-text hláškou místo JSONu, kterou by typované
 * {@code retrieve().body(Class)} proměnilo v neprůhlednou chybu dekódování.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class VehicleRegistryClientImpl implements VehicleRegistryClient {

    private static final String LOOKUP_PATH = "/api/vehicletechnicaldata/v2";

    /**
     * Fragment dokumentované rate-limit hlášky („…dosažen maximální počet
     * požadavků…"). Záměrně bez diakritiky: text/plain odpověď bez explicitního
     * charsetu se může dekódovat jako ISO-8859-1 a diakritiku rozbít — ASCII
     * kmen přežije v obou případech. Kontroluje se jen u ne-JSON 2xx těl,
     * což je dle dokumentace API právě případ rate limitu.
     */
    private static final String RATE_LIMIT_MARKER = "maxim";

    private final RestClient vehicleRegistryRestClient;
    private final ObjectMapper objectMapper;

    @Override
    public Optional<RegistryFetchResult> fetch(RegistryLookupParams params) {
        String body = callRegistry(params);
        return parseBody(body);
    }

    private String callRegistry(RegistryLookupParams params) {
        try {
            return vehicleRegistryRestClient.get()
                    .uri(uri -> buildLookupUri(uri, params))
                    .retrieve()
                    .body(String.class);
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            if (status == 429) {
                throw new RegistryUnavailableException("REGISTRY_RATE_LIMITED",
                        "Registr vozidel omezil počet dotazů (27/min). Zkuste to prosím za chvíli.");
            }
            if (status == 401 || status == 403) {
                log.error("Vehicle registry rejected the API key (HTTP {}) — check registry.dataovozidlech.api-key", status);
                throw new RegistryUnavailableException("REGISTRY_AUTH_FAILED",
                        "Registr vozidel odmítl API klíč. Zkontrolujte konfiguraci.");
            }
            log.warn("Vehicle registry returned HTTP {}: {}", status, abbreviate(e.getResponseBodyAsString()));
            throw new RegistryUnavailableException("REGISTRY_ERROR",
                    "Registr vozidel vrátil neočekávanou odpověď. Zkuste to prosím později.");
        } catch (ResourceAccessException e) {
            log.warn("Vehicle registry unreachable: {}", e.getMessage());
            throw new RegistryUnavailableException("REGISTRY_TIMEOUT",
                    "Registr vozidel neodpovídá. Zkuste to prosím později.");
        }
    }

    private java.net.URI buildLookupUri(UriBuilder uri, RegistryLookupParams params) {
        UriBuilder b = uri.path(LOOKUP_PATH);
        if (notBlank(params.vin())) b = b.queryParam("vin", params.vin());
        if (notBlank(params.tp()))  b = b.queryParam("tp", params.tp());
        if (notBlank(params.orv())) b = b.queryParam("orv", params.orv());
        return b.build();
    }

    /**
     * Parsuje tělo 2xx odpovědi. Obálka: {@code { "Status": 1, "Data": {...} }}.
     * Cokoli jiného než Status 1 + nenulové Data znamená „v registru není".
     * Ne-JSON tělo na 2xx je dokumentovaná rate-limit hláška (nebo
     * neočekávané selhání).
     */
    private Optional<RegistryFetchResult> parseBody(String body) {
        if (body == null || body.isBlank()) {
            throw new RegistryUnavailableException("REGISTRY_ERROR",
                    "Registr vozidel vrátil prázdnou odpověď. Zkuste to prosím později.");
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (JacksonException e) {
            if (body.toLowerCase(Locale.forLanguageTag("cs")).contains(RATE_LIMIT_MARKER)) {
                throw new RegistryUnavailableException("REGISTRY_RATE_LIMITED",
                        "Registr vozidel omezil počet dotazů (27/min). Zkuste to prosím za chvíli.");
            }
            log.warn("Vehicle registry returned a non-JSON body: {}", abbreviate(body));
            throw new RegistryUnavailableException("REGISTRY_ERROR",
                    "Registr vozidel vrátil neočekávanou odpověď. Zkuste to prosím později.");
        }

        int status = root.path("Status").asInt(0);
        JsonNode dataNode = root.get("Data");
        if (status != 1 || dataNode == null || dataNode.isNull()) {
            return Optional.empty();
        }

        try {
            RegistryVehicleData data = objectMapper.treeToValue(dataNode, RegistryVehicleData.class);
            return Optional.of(new RegistryFetchResult(data, dataNode.toString()));
        } catch (JacksonException e) {
            log.warn("Vehicle registry Data object could not be mapped: {}", e.getMessage());
            throw new RegistryUnavailableException("REGISTRY_ERROR",
                    "Odpověď registru vozidel se nepodařilo zpracovat.");
        }
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String abbreviate(String s) {
        if (s == null) return "";
        return s.length() <= 200 ? s : s.substring(0, 200) + "…";
    }
}
