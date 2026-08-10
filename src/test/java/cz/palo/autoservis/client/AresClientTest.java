package cz.palo.autoservis.client;

import cz.palo.autoservis.exception.AresUnavailableException;
import cz.palo.autoservis.model.dto.ares.AresDto;
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
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Unit test HTTP klienta ARES — bez Spring kontextu; stejné ručně sestavené
 * {@link MockRestServiceServer} zapojení jako {@link VehicleRegistryClientTest}.
 */
class AresClientTest {

    private static final String BASE = "https://ares.example.test";
    private static final String LOOKUP = BASE + "/ekonomicke-subjekty/";

    /** Reálný tvar odpovědi ARES v3 API (zkrácený, sídlo ve městě). */
    private static final String FOUND_BODY = """
            { "ico": "47123737",
              "obchodniJmeno": "MICROSOFT s.r.o.",
              "sidlo": {
                "kodStatu": "CZ",
                "nazevObce": "Praha",
                "nazevUlice": "Vyskočilova",
                "cisloDomovni": 1561,
                "cisloOrientacni": 4,
                "cisloOrientacniPismeno": "a",
                "nazevCastiObce": "Michle",
                "psc": 14000,
                "textovaAdresa": "Vyskočilova 1561/4a, Michle, 14000 Praha 4"
              },
              "pravniForma": "112",
              "dic": "CZ47123737",
              "neznamePole": "ignorováno" }
            """;

    /** Sídlo na vesnici — bez názvu ulice, jen číslo popisné. */
    private static final String VILLAGE_BODY = """
            { "ico": "00006947",
              "obchodniJmeno": "Obecní firma",
              "sidlo": {
                "kodStatu": "CZ",
                "nazevObce": "Lhota",
                "cisloDomovni": 42,
                "psc": 25001
              } }
            """;

    private MockRestServiceServer server;
    private AresClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
        server = MockRestServiceServer.bindTo(builder).build();
        client = new AresClientImpl(builder.build(), new ObjectMapper());
    }

    @Test
    @DisplayName("found: maps name, DIČ and composes the seat address (1561/4a)")
    void fetch_found_mapsCompanyAndAddress() {
        server.expect(requestTo(LOOKUP + "47123737"))
                .andRespond(withSuccess(FOUND_BODY, MediaType.APPLICATION_JSON));

        Optional<AresDto.LookupResponse> result = client.fetch("47123737");

        assertThat(result).isPresent();
        AresDto.LookupResponse data = result.get();
        assertThat(data.ico()).isEqualTo("47123737");
        assertThat(data.companyName()).isEqualTo("MICROSOFT s.r.o.");
        assertThat(data.dic()).isEqualTo("CZ47123737");
        assertThat(data.street()).isEqualTo("Vyskočilova");
        assertThat(data.streetNumber()).isEqualTo("1561/4a");
        assertThat(data.city()).isEqualTo("Praha");
        assertThat(data.postalCode()).isEqualTo("14000");
        assertThat(data.countryCode()).isEqualTo("CZ");
        server.verify();
    }

    @Test
    @DisplayName("village seat without a street name → obec as street, plain house number")
    void fetch_villageSeat_fallsBackToMunicipality() {
        server.expect(requestTo(LOOKUP + "00006947"))
                .andRespond(withSuccess(VILLAGE_BODY, MediaType.APPLICATION_JSON));

        AresDto.LookupResponse data = client.fetch("00006947").orElseThrow();

        assertThat(data.street()).isEqualTo("Lhota");
        assertThat(data.streetNumber()).isEqualTo("42");
        assertThat(data.city()).isEqualTo("Lhota");
        assertThat(data.dic()).isNull();
    }

    @Test
    @DisplayName("404 → empty Optional (unknown IČO is a business answer, not a failure)")
    void fetch_notFound_returnsEmpty() {
        server.expect(requestTo(LOOKUP + "12345670"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThat(client.fetch("12345670")).isEmpty();
    }

    @Test
    @DisplayName("HTTP 429 → ARES_RATE_LIMITED")
    void fetch_http429_throwsRateLimited() {
        server.expect(requestTo(LOOKUP + "47123737"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

        assertThatThrownBy(() -> client.fetch("47123737"))
                .isInstanceOf(AresUnavailableException.class)
                .extracting("code").isEqualTo("ARES_RATE_LIMITED");
    }

    @Test
    @DisplayName("network timeout → ARES_TIMEOUT")
    void fetch_timeout_throwsTimeout() {
        server.expect(requestTo(LOOKUP + "47123737"))
                .andRespond(withException(new SocketTimeoutException("read timed out")));

        assertThatThrownBy(() -> client.fetch("47123737"))
                .isInstanceOf(AresUnavailableException.class)
                .extracting("code").isEqualTo("ARES_TIMEOUT");
    }

    @Test
    @DisplayName("HTTP 500 → ARES_ERROR")
    void fetch_serverError_throwsAresError() {
        server.expect(requestTo(LOOKUP + "47123737"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        assertThatThrownBy(() -> client.fetch("47123737"))
                .isInstanceOf(AresUnavailableException.class)
                .extracting("code").isEqualTo("ARES_ERROR");
    }

    @Test
    @DisplayName("2xx with a non-JSON body → ARES_ERROR")
    void fetch_nonJsonBody_throwsAresError() {
        server.expect(requestTo(LOOKUP + "47123737"))
                .andRespond(withSuccess("<html>maintenance</html>", MediaType.TEXT_HTML));

        assertThatThrownBy(() -> client.fetch("47123737"))
                .isInstanceOf(AresUnavailableException.class)
                .extracting("code").isEqualTo("ARES_ERROR");
    }

    @Test
    @DisplayName("foreign seat: pscTxt instead of psc, foreign country code, no street/number")
    void fetch_foreignSeat_mapsPscTxtAndCountry() {
        server.expect(requestTo(LOOKUP + "26185610"))
                .andRespond(withSuccess("""
                        { "ico": "26185610",
                          "obchodniJmeno": "Ausländische GmbH",
                          "sidlo": {
                            "kodStatu": "DE",
                            "nazevStatu": "Německo",
                            "nazevObce": "München",
                            "pscTxt": "80331",
                            "textovaAdresa": "München, 80331, Německo"
                          } }
                        """, MediaType.APPLICATION_JSON));

        AresDto.LookupResponse data = client.fetch("26185610").orElseThrow();

        assertThat(data.countryCode()).isEqualTo("DE");
        assertThat(data.postalCode()).isEqualTo("80331");
        assertThat(data.street()).isEqualTo("München");
        assertThat(data.streetNumber()).isNull();
        assertThat(data.city()).isEqualTo("München");
    }

    @Test
    @DisplayName("foreign-format DIČ is dropped — it would fail the form validation")
    void fetch_foreignDic_mapsToNull() {
        server.expect(requestTo(LOOKUP + "47123737"))
                .andRespond(withSuccess(
                        "{ \"ico\": \"47123737\", \"obchodniJmeno\": \"X\", \"dic\": \"SK2020317068\" }",
                        MediaType.APPLICATION_JSON));

        assertThat(client.fetch("47123737").orElseThrow().dic()).isNull();
    }
}
