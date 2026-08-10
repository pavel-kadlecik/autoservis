package cz.palo.autoservis.client;

import cz.palo.autoservis.exception.RegistryUnavailableException;
import cz.palo.autoservis.model.dto.registry.RegistryFetchResult;
import cz.palo.autoservis.model.dto.registry.RegistryLookupParams;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import java.net.SocketTimeoutException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestToUriTemplate;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withUnauthorizedRequest;

/**
 * Unit test HTTP klienta registru vozidel — bez Spring kontextu. Klient se skládá
 * ručně z {@link RestClient.Builder} navázaného na {@link MockRestServiceServer},
 * což je pro vlastnoručně konfigurovaný bean rychlejší a přímočařejší
 * než {@code @RestClientTest}.
 */
class VehicleRegistryClientTest {

    private static final String BASE = "https://registry.example.test";
    private static final String LOOKUP = BASE + "/api/vehicletechnicaldata/v2?vin={vin}";

    /** Reálný tvar odpovědi z dokumentace API (zkrácený objekt Data). */
    private static final String FOUND_BODY = """
            { "Status": 1,
              "Data": {
                "VIN": "TMBEFF654V7529422",
                "TovarniZnacka": "ŠKODA",
                "ObchodniOznaceni": "FELICIA COMBI",
                "Palivo": "BA 95 B",
                "MotorZdvihObjem": 1289,
                "MotorMaxVykon": "50 / 5000",
                "PravidelnaTechnickaProhlidkaDo": "2013-12-06T00:00:00",
                "StatusNazev": "PROVOZOVANÉ",
                "NeznamePole": "ignorováno"
              } }
            """;

    private MockRestServiceServer server;
    private VehicleRegistryClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder()
                .baseUrl(BASE)
                .defaultHeader("API_KEY", "test-key");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new VehicleRegistryClientImpl(builder.build(), new ObjectMapper());
    }

    @Test
    @DisplayName("found: Status 1 + Data → mapped record + raw Data JSON, API_KEY header sent")
    void fetch_found_mapsDataAndKeepsRawJson() {
        server.expect(requestToUriTemplate(LOOKUP, "TMBEFF654V7529422"))
                .andExpect(header("API_KEY", "test-key"))
                .andExpect(queryParam("vin", "TMBEFF654V7529422"))
                .andRespond(withSuccess(FOUND_BODY, MediaType.APPLICATION_JSON));

        Optional<RegistryFetchResult> result =
                client.fetch(RegistryLookupParams.ofVin("TMBEFF654V7529422"));

        assertThat(result).isPresent();
        assertThat(result.get().data().tovarniZnacka()).isEqualTo("ŠKODA");
        assertThat(result.get().data().motorZdvihObjem()).isEqualTo(1289);
        assertThat(result.get().data().pravidelnaTechnickaProhlidkaDo())
                .isEqualTo("2013-12-06T00:00:00");
        // surový JSON drží i pole, která record nemapuje
        assertThat(result.get().rawJson()).contains("NeznamePole");
        server.verify();
    }

    @Test
    @DisplayName("not found: Status != 1 → empty Optional")
    void fetch_statusNotOne_returnsEmpty() {
        server.expect(requestToUriTemplate(LOOKUP, "WAUZZZ8K9BA000000"))
                .andRespond(withSuccess("{ \"Status\": 2, \"Data\": null }", MediaType.APPLICATION_JSON));

        assertThat(client.fetch(RegistryLookupParams.ofVin("WAUZZZ8K9BA000000"))).isEmpty();
    }

    @Test
    @DisplayName("401 → REGISTRY_AUTH_FAILED")
    void fetch_unauthorized_throwsAuthFailed() {
        server.expect(requestToUriTemplate(LOOKUP, "TMBEFF654V7529422"))
                .andRespond(withUnauthorizedRequest());

        assertThatThrownBy(() -> client.fetch(RegistryLookupParams.ofVin("TMBEFF654V7529422")))
                .isInstanceOf(RegistryUnavailableException.class)
                .extracting("code").isEqualTo("REGISTRY_AUTH_FAILED");
    }

    @Test
    @DisplayName("HTTP 429 → REGISTRY_RATE_LIMITED")
    void fetch_http429_throwsRateLimited() {
        server.expect(requestToUriTemplate(LOOKUP, "TMBEFF654V7529422"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

        assertThatThrownBy(() -> client.fetch(RegistryLookupParams.ofVin("TMBEFF654V7529422")))
                .isInstanceOf(RegistryUnavailableException.class)
                .extracting("code").isEqualTo("REGISTRY_RATE_LIMITED");
    }

    @Test
    @DisplayName("2xx with the documented plain-text rate-limit message → REGISTRY_RATE_LIMITED")
    void fetch_textualRateLimitBody_throwsRateLimited() {
        server.expect(requestToUriTemplate(LOOKUP, "TMBEFF654V7529422"))
                .andRespond(withSuccess(
                        "Pro Váš klíč dosažen maximální počet požadavků. Zkuste to prosím později.",
                        MediaType.TEXT_PLAIN));

        assertThatThrownBy(() -> client.fetch(RegistryLookupParams.ofVin("TMBEFF654V7529422")))
                .isInstanceOf(RegistryUnavailableException.class)
                .extracting("code").isEqualTo("REGISTRY_RATE_LIMITED");
    }

    @Test
    @DisplayName("network timeout → REGISTRY_TIMEOUT")
    void fetch_timeout_throwsTimeout() {
        server.expect(requestToUriTemplate(LOOKUP, "TMBEFF654V7529422"))
                .andRespond(withException(new SocketTimeoutException("read timed out")));

        assertThatThrownBy(() -> client.fetch(RegistryLookupParams.ofVin("TMBEFF654V7529422")))
                .isInstanceOf(RegistryUnavailableException.class)
                .extracting("code").isEqualTo("REGISTRY_TIMEOUT");
    }

    @Test
    @DisplayName("tp + orv params travel as separate query parameters")
    void fetch_tpAndOrv_sentAsQueryParams() {
        server.expect(requestToUriTemplate(BASE + "/api/vehicletechnicaldata/v2?tp={tp}&orv={orv}",
                        "AN628498", "UAB648001"))
                .andExpect(queryParam("tp", "AN628498"))
                .andExpect(queryParam("orv", "UAB648001"))
                .andRespond(withSuccess(FOUND_BODY, MediaType.APPLICATION_JSON));

        assertThat(client.fetch(new RegistryLookupParams(null, "AN628498", "UAB648001"))).isPresent();
        server.verify();
    }
}
