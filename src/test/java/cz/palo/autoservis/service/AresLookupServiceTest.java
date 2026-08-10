package cz.palo.autoservis.service;

import cz.palo.autoservis.AbstractIntegrationTest;
import cz.palo.autoservis.client.AresClient;
import cz.palo.autoservis.exception.AresUnavailableException;
import cz.palo.autoservis.model.domain.user.Role;
import cz.palo.autoservis.model.domain.user.User;
import cz.palo.autoservis.model.dto.ares.AresDto;
import cz.palo.autoservis.security.model.domain.AppUserDetails;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Full-stack test endpointu ARES lookup: HTTP (MockMvc) → controller →
// service. Mockuje se jen externí HTTP klient (@MockitoBean) — žádné
// volání ares.gov.cz. Do DB se nic nezapisuje, @Transactional přesto drží
// vzor ostatních integračních testů.
@AutoConfigureMockMvc
@Transactional
class AresLookupServiceTest extends AbstractIntegrationTest {

    /** Platné IČO včetně kontrolní číslice mod-11 (Microsoft s.r.o.). */
    private static final String VALID_ICO = "47123737";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AresClient aresClient;

    private AppUserDetails admin() {
        return new AppUserDetails(User.builder()
                .id(1L)
                .username("admin")
                .passwordHash("n/a")
                .enabled(true)
                .accountNonExpired(true)
                .accountNonLocked(true)
                .credentialsNonExpired(true)
                .roles(List.of(Role.builder().name("ROLE_ADMIN").build()))
                .build());
    }

    @Test
    @DisplayName("GET ares-lookup → 200 s daty firmy a adresou sídla")
    void lookup_found_returnsCompanyData() throws Exception {
        given(aresClient.fetch(VALID_ICO)).willReturn(Optional.of(new AresDto.LookupResponse(
                VALID_ICO, "MICROSOFT s.r.o.", "CZ47123737",
                "Vyskočilova", "1561/4a", "Praha", "14000", "CZ")));

        mockMvc.perform(get("/api/v1/customers/ares-lookup").param("ico", VALID_ICO)
                        .with(user(admin())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.companyName").value("MICROSOFT s.r.o."))
                .andExpect(jsonPath("$.dic").value("CZ47123737"))
                .andExpect(jsonPath("$.street").value("Vyskočilova"))
                .andExpect(jsonPath("$.streetNumber").value("1561/4a"))
                .andExpect(jsonPath("$.city").value("Praha"))
                .andExpect(jsonPath("$.postalCode").value("14000"))
                .andExpect(jsonPath("$.countryCode").value("CZ"));
    }

    @Test
    @DisplayName("IČO s vadnou kontrolní číslicí → 422 INVALID_ICO, ARES se nevolá")
    void lookup_badCheckDigit_returns422WithoutAresCall() throws Exception {
        mockMvc.perform(get("/api/v1/customers/ares-lookup").param("ico", "12345678")
                        .with(user(admin())))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].code").value("INVALID_ICO"));

        verify(aresClient, never()).fetch(anyString());
    }

    @Test
    @DisplayName("IČO špatné délky → 422 INVALID_ICO")
    void lookup_wrongLength_returns422() throws Exception {
        mockMvc.perform(get("/api/v1/customers/ares-lookup").param("ico", "1234")
                        .with(user(admin())))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].code").value("INVALID_ICO"));
    }

    @Test
    @DisplayName("subjekt v ARES neexistuje → 422 SUBJECT_NOT_IN_ARES")
    void lookup_notFound_returns422() throws Exception {
        given(aresClient.fetch(VALID_ICO)).willReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/customers/ares-lookup").param("ico", VALID_ICO)
                        .with(user(admin())))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].code").value("SUBJECT_NOT_IN_ARES"));
    }

    @Test
    @DisplayName("ARES nedostupný → 503 s kódem v errors[]")
    void lookup_aresDown_returns503() throws Exception {
        given(aresClient.fetch(VALID_ICO)).willThrow(
                new AresUnavailableException("ARES_TIMEOUT", "ARES neodpovídá."));

        mockMvc.perform(get("/api/v1/customers/ares-lookup").param("ico", VALID_ICO)
                        .with(user(admin())))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.errors[0].code").value("ARES_TIMEOUT"));
    }
}
