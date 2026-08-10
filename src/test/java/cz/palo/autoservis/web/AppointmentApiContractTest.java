package cz.palo.autoservis.web;

import cz.palo.autoservis.AbstractIntegrationTest;
import cz.palo.autoservis.model.domain.user.Role;
import cz.palo.autoservis.model.domain.user.User;
import cz.palo.autoservis.security.model.domain.AppUserDetails;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP kontrakt plánovacího kalendáře — statusy, tvar odpovědí a rolová oprávnění.
 *
 * <p><strong>Proč přes MockMvc a ne přes service:</strong> service testy už pokrývají business
 * pravidla ({@code AppointmentServiceTest}). Tady jde o to, co service neuvidí — převod parametrů
 * z query stringu na {@code OffsetDateTime}, mapování výjimek na HTTP statusy, hlavička
 * {@code Location} u 201 a hlavně to, že blokaci dílny nezaloží mechanik.
 */
@AutoConfigureMockMvc
@Transactional
class AppointmentApiContractTest extends AbstractIntegrationTest {

    private static final long CLOSURE_ID = 4L;
    private static final long BOOKING_PLANNED = 2L;

    @Autowired
    private MockMvc mockMvc;

    // =========================================================================
    // čtení
    // =========================================================================

    @Test
    @DisplayName("GET /appointments vrátí 200 a seznam bez stránkovací obálky")
    void getInRange_returnsPlainList() throws Exception {
        mockMvc.perform(get("/api/v1/appointments")
                        .param("from", iso(today().minusDays(10)))
                        .param("to", iso(today().plusDays(10)))
                        .with(user(mechanic())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].id").exists())
                .andExpect(jsonPath("$[0].title").exists())
                // ListResponse je zúžený — note se do kalendáře neposílá
                .andExpect(jsonPath("$[0].note").doesNotExist());
    }

    @Test
    @DisplayName("GET /appointments/{id} neexistující → 404 s problem+json")
    void getById_missing_returns404() throws Exception {
        mockMvc.perform(get("/api/v1/appointments/99999").with(user(mechanic())))
                .andExpect(status().isNotFound())
                .andExpect(header().string("Content-Type", "application/problem+json"))
                .andExpect(jsonPath("$.detail").exists());
    }

    @Test
    @DisplayName("GET /appointments/overlaps vrátí počet i příznak blokace")
    void checkOverlaps_returnsCounts() throws Exception {
        mockMvc.perform(get("/api/v1/appointments/overlaps")
                        .param("startsAt", iso(today().plusDays(40)))
                        .param("endsAt", iso(today().plusDays(40).plusHours(1)))
                        .with(user(mechanic())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overlappingCount").value(0))
                .andExpect(jsonPath("$.blockedByClosure").value(false));
    }

    // =========================================================================
    // zápis
    // =========================================================================

    @Test
    @DisplayName("POST /appointments vrátí 201 a hlavičku Location")
    void create_returns201WithLocation() throws Exception {
        mockMvc.perform(post("/api/v1/appointments")
                        .contentType(APPLICATION_JSON)
                        .content(bookingBody())
                        .with(user(mechanic())))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.status").value("PLANNED"));
    }

    @Test
    @DisplayName("POST /appointments bez názvu → 400 s errors[]")
    void create_withoutTitle_returns400() throws Exception {
        String body = bookingBodyWithout("title");

        mockMvc.perform(post("/api/v1/appointments")
                        .contentType(APPLICATION_JSON)
                        .content(body)
                        .with(user(mechanic())))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("Content-Type", "application/problem+json"))
                .andExpect(jsonPath("$.errors").isArray());
    }

    @Test
    @DisplayName("POST /appointments objednávky bez zákazníka a vozidla → 201 (V85)")
    void create_withoutCustomer_returns201() throws Exception {
        String body = bookingBodyWithout("customerId", "vehicleId");

        mockMvc.perform(post("/api/v1/appointments")
                        .contentType(APPLICATION_JSON)
                        .content(body)
                        .with(user(mechanic())))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.customerId").doesNotExist())
                .andExpect(jsonPath("$.vehicleId").doesNotExist());
    }

    @Test
    @DisplayName("POST /appointments/{id}/time posune termín a vrátí 200")
    void updateTime_returns200() throws Exception {
        String body = """
                {"startsAt": "%s", "endsAt": "%s"}
                """.formatted(iso(today().plusDays(31).plusHours(9)),
                               iso(today().plusDays(31).plusHours(11)));

        mockMvc.perform(post("/api/v1/appointments/" + BOOKING_PLANNED + "/time")
                        .contentType(APPLICATION_JSON)
                        .content(body)
                        .with(user(mechanic())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value((int) BOOKING_PLANNED));
    }

    @Test
    @DisplayName("POST /appointments/{id}/status na CONVERTED → 422 STATUS_NOT_SETTABLE")
    void changeStatus_toConverted_returns422() throws Exception {
        mockMvc.perform(post("/api/v1/appointments/" + BOOKING_PLANNED + "/status")
                        .contentType(APPLICATION_JSON)
                        .content("{\"status\":\"CONVERTED\"}")
                        .with(user(mechanic())))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].code").value("STATUS_NOT_SETTABLE"));
    }

    @Test
    @DisplayName("POST /appointments/{id}/convert vrátí 201 a vzniklou zakázku")
    void convert_returns201WithOrder() throws Exception {
        String orderBody = """
                {"customerId": 1, "vehicleId": 1, "description": "Vzniklo z objednávky.", "receivedAt": "2026-08-09"}
                """;

        mockMvc.perform(post("/api/v1/appointments/" + BOOKING_PLANNED + "/convert")
                        .contentType(APPLICATION_JSON)
                        .content(orderBody)
                        .with(user(mechanic())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.orderNumber").exists());
    }

    // =========================================================================
    // oprávnění — blokace dílny je věc vedení (§19)
    // =========================================================================

    @Test
    @DisplayName("mechanik nesmí založit blokaci dílny → 403")
    void createClosure_asMechanic_returns403() throws Exception {
        mockMvc.perform(post("/api/v1/appointments")
                        .contentType(APPLICATION_JSON)
                        .content(closureBody())
                        .with(user(mechanic())))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("manažer blokaci dílny založit smí → 201")
    void createClosure_asManager_returns201() throws Exception {
        mockMvc.perform(post("/api/v1/appointments")
                        .contentType(APPLICATION_JSON)
                        .content(closureBody())
                        .with(user(manager())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.entryType").value("CLOSURE"));
    }

    @Test
    @DisplayName("mechanik nesmí smazat blokaci dílny → 403")
    void deleteClosure_asMechanic_returns403() throws Exception {
        mockMvc.perform(delete("/api/v1/appointments/" + CLOSURE_ID).with(user(mechanic())))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("mechanik běžnou objednávku smazat smí → 204 bez těla")
    void deleteBooking_asMechanic_returns204() throws Exception {
        mockMvc.perform(delete("/api/v1/appointments/" + BOOKING_PLANNED).with(user(mechanic())))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/appointments/" + BOOKING_PLANNED).with(user(mechanic())))
                .andExpect(status().isNotFound());
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private OffsetDateTime today() {
        return OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(java.time.temporal.ChronoUnit.DAYS);
    }

    private String iso(OffsetDateTime value) {
        return value.toString();
    }

    /** Termíny daleko v budoucnu, aby se netrefily do seedované blokace. */
    private String bookingBody() {
        return bookingBodyWithout();
    }

    /**
     * Tělo objednávky bez vyjmenovaných polí — tak se testuje jak chybějící povinný údaj
     * (title), tak vynechaný nepovinný (customerId, vehicleId od V85), aniž by se JSON
     * psal pokaždé znovu.
     */
    private String bookingBodyWithout(String... omitted) {
        Map<String, String> fields = new java.util.LinkedHashMap<>();
        fields.put("entryType", "\"BOOKING\"");
        fields.put("title", "\"Objednávka z API testu\"");
        fields.put("startsAt", "\"" + iso(today().plusDays(30).plusHours(9)) + "\"");
        fields.put("endsAt", "\"" + iso(today().plusDays(30).plusHours(10)) + "\"");
        fields.put("customerId", "1");
        fields.put("vehicleId", "1");
        for (String key : omitted) {
            fields.remove(key);
        }
        return fields.entrySet().stream()
                .map(entry -> "\"" + entry.getKey() + "\": " + entry.getValue())
                .collect(java.util.stream.Collectors.joining(", ", "{", "}"));
    }

    private String closureBody() {
        return """
                {"entryType": "CLOSURE", "title": "Sanitární den", "startsAt": "%s", "endsAt": "%s"}
                """.formatted(iso(today().plusDays(50)), iso(today().plusDays(51)));
    }

    private static AppUserDetails mechanic() {
        return principalWithRole("ROLE_MECHANIC");
    }

    private static AppUserDetails manager() {
        return principalWithRole("ROLE_MANAGER");
    }

    private static AppUserDetails principalWithRole(String roleName) {
        return new AppUserDetails(User.builder()
                .id(1L).username("admin").passwordHash("n/a")
                .enabled(true).accountNonExpired(true).accountNonLocked(true)
                .credentialsNonExpired(true)
                .roles(List.of(Role.builder().name(roleName).build()))
                .build());
    }
}
