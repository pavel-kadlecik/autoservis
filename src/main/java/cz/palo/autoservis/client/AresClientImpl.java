package cz.palo.autoservis.client;

import cz.palo.autoservis.exception.AresUnavailableException;
import cz.palo.autoservis.model.dto.ares.AresDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * {@link AresClient} postavený na beanu {@code aresRestClient} (base URL
 * a timeouty jdou z konfigurace, bez API klíče).
 *
 * <p>Tělo se čte jako String a parsuje ručně přes {@link JsonNode} — stejný
 * defenzivní styl jako {@link VehicleRegistryClientImpl}: odpověď má desítky
 * polí, která nás nezajímají, a ruční mapování dokumentuje přesně ta,
 * která aplikace používá.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AresClientImpl implements AresClient {

    private static final String LOOKUP_PATH = "/ekonomicke-subjekty/{ico}";

    /** Zrcadlí validaci DIČ v {@code CustomerDto} — zahraniční formáty se zahazují. */
    private static final Pattern CZ_DIC_PATTERN = Pattern.compile("^CZ\\d{8,10}$");

    private final RestClient aresRestClient;
    private final ObjectMapper objectMapper;

    @Override
    public Optional<AresDto.LookupResponse> fetch(String ico) {
        String body = callAres(ico);
        if (body == null) {
            return Optional.empty();
        }
        return Optional.of(parseBody(body));
    }

    /** Vrací tělo 2xx odpovědi, nebo {@code null}, když ARES odpověděl 404 (neznámé IČO). */
    private String callAres(String ico) {
        try {
            return aresRestClient.get()
                    .uri(LOOKUP_PATH, ico)
                    .retrieve()
                    .body(String.class);
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            if (status == 404) {
                return null;
            }
            if (status == 429) {
                throw new AresUnavailableException("ARES_RATE_LIMITED",
                        "ARES omezil počet dotazů. Zkuste to prosím za chvíli.");
            }
            log.warn("ARES returned HTTP {}: {}", status, abbreviate(e.getResponseBodyAsString()));
            throw new AresUnavailableException("ARES_ERROR",
                    "ARES vrátil neočekávanou odpověď. Zkuste to prosím později.");
        } catch (ResourceAccessException e) {
            log.warn("ARES unreachable: {}", e.getMessage());
            throw new AresUnavailableException("ARES_TIMEOUT",
                    "ARES neodpovídá. Zkuste to prosím později.");
        }
    }

    private AresDto.LookupResponse parseBody(String body) {
        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (JacksonException e) {
            log.warn("ARES returned a non-JSON body: {}", abbreviate(body));
            throw new AresUnavailableException("ARES_ERROR",
                    "Odpověď ARES se nepodařilo zpracovat.");
        }

        JsonNode sidlo = root.path("sidlo");
        return new AresDto.LookupResponse(
                root.path("ico").asString(null),
                root.path("obchodniJmeno").asString(null),
                validDic(root.path("dic").asString(null)),
                street(sidlo),
                streetNumber(sidlo),
                sidlo.path("nazevObce").asString(null),
                postalCode(sidlo),
                sidlo.path("kodStatu").asString(null));
    }

    private static String validDic(String dic) {
        return dic != null && CZ_DIC_PATTERN.matcher(dic).matches() ? dic : null;
    }

    /**
     * Obce bez názvů ulic mají sídlo zapsané jako „obec + číslo domu"
     * (případně s částí obce), proto řetěz fallbacků.
     */
    private static String street(JsonNode sidlo) {
        String ulice = sidlo.path("nazevUlice").asString(null);
        if (ulice != null) return ulice;
        String castObce = sidlo.path("nazevCastiObce").asString(null);
        return castObce != null ? castObce : sidlo.path("nazevObce").asString(null);
    }

    /** Český formát adresy: {@code číslo popisné[/číslo orientační[písmeno]]}, např. „1561/4a". */
    private static String streetNumber(JsonNode sidlo) {
        String domovni = sidlo.path("cisloDomovni").asString(null);
        String orientacni = sidlo.path("cisloOrientacni").asString(null);
        if (orientacni != null) {
            orientacni += sidlo.path("cisloOrientacniPismeno").asString("");
        }
        if (domovni == null) return orientacni;
        return orientacni == null ? domovni : domovni + "/" + orientacni;
    }

    /** {@code psc} je u českých sídel číselné; zahraniční mají místo něj {@code pscTxt}. */
    private static String postalCode(JsonNode sidlo) {
        String psc = sidlo.path("psc").asString(null);
        return psc != null ? psc : sidlo.path("pscTxt").asString(null);
    }

    private static String abbreviate(String s) {
        if (s == null) return "";
        return s.length() <= 200 ? s : s.substring(0, 200) + "…";
    }
}
