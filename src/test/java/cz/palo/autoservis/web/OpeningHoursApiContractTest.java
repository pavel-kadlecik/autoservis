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

import java.util.List;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP kontrakt otevírací doby — statusy, tvar odpovědi a hlavně to, že rozvrh nemění mechanik.
 *
 * <p>Business pravidla pokrývá {@code OpeningHoursServiceTest}; tady jde o vrstvu nad ním.
 */
@AutoConfigureMockMvc
@Transactional
class OpeningHoursApiContractTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("GET /opening-hours vrátí 200, sedm dnů a stav přepínače")
    void get_returnsWeek() throws Exception {
        mockMvc.perform(get("/api/v1/opening-hours").with(user(mechanic())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openingHoursEnabled").value(false))
                .andExpect(jsonPath("$.days.length()").value(7))
                .andExpect(jsonPath("$.days[0].dayOfWeek").value(1))
                .andExpect(jsonPath("$.days[0].opensAt").value("07:00:00"))
                .andExpect(jsonPath("$.days[5].opensAt").doesNotExist());
    }

    @Test
    @DisplayName("PUT /opening-hours mechanikem → 403 (rozvrh je rozhodnutí vedení)")
    void update_asMechanic_isForbidden() throws Exception {
        mockMvc.perform(put("/api/v1/opening-hours")
                        .with(user(mechanic()))
                        .contentType(APPLICATION_JSON)
                        .content(fullWeekJson()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("PUT /opening-hours manažerem → 200 a uložený stav")
    void update_asManager_succeeds() throws Exception {
        mockMvc.perform(put("/api/v1/opening-hours")
                        .with(user(manager()))
                        .contentType(APPLICATION_JSON)
                        .content(fullWeekJson()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openingHoursEnabled").value(true))
                .andExpect(jsonPath("$.days[0].opensAt").value("08:00:00"));
    }

    @Test
    @DisplayName("PUT s neúplným týdnem → 422 INCOMPLETE_WEEK")
    void update_incompleteWeek_returns422() throws Exception {
        String jenPondeli = """
                {"openingHoursEnabled": true,
                 "days": [{"dayOfWeek": 1, "opensAt": "08:00:00", "closesAt": "16:00:00"}]}""";

        mockMvc.perform(put("/api/v1/opening-hours")
                        .with(user(manager()))
                        .contentType(APPLICATION_JSON)
                        .content(jenPondeli))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].code").value("INCOMPLETE_WEEK"));
    }

    @Test
    @DisplayName("PUT s prázdným rozvrhem → 400 (padne na validaci DTO, ne na service)")
    void update_emptyDays_returns400() throws Exception {
        mockMvc.perform(put("/api/v1/opening-hours")
                        .with(user(manager()))
                        .contentType(APPLICATION_JSON)
                        .content("{\"openingHoursEnabled\": true, \"days\": []}"))
                .andExpect(status().isBadRequest());
    }

    /** Po–pá 8:00–16:00, víkend zavřeno, hlídání zapnuté. */
    private static String fullWeekJson() {
        StringBuilder days = new StringBuilder();
        for (int dayOfWeek = 1; dayOfWeek <= 7; dayOfWeek++) {
            if (dayOfWeek > 1) {
                days.append(",");
            }
            days.append(dayOfWeek <= 5
                    ? "{\"dayOfWeek\": %d, \"opensAt\": \"08:00:00\", \"closesAt\": \"16:00:00\"}".formatted(dayOfWeek)
                    : "{\"dayOfWeek\": %d}".formatted(dayOfWeek));
        }
        return "{\"openingHoursEnabled\": true, \"days\": [%s]}".formatted(days);
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
