package cz.palo.autoservis.web;

import cz.palo.autoservis.AbstractIntegrationTest;
import cz.palo.autoservis.config.security.SecurityConfig;
import cz.palo.autoservis.model.domain.user.Role;
import cz.palo.autoservis.model.domain.user.User;
import cz.palo.autoservis.security.mapper.RoleMapper;
import cz.palo.autoservis.security.model.domain.AppUserDetails;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Číselník rolí pro zakládání účtu ({@code GET /code-lists/roles}) — audit KN-22.
 *
 * <p>Tabulka {@code security.roles} obsahuje i {@code ROLE_CUSTOMER} (zákaznický portál, který
 * neexistuje) a {@code ROLE_READONLY}. Baseline {@code /api/**} pouští jen pracovní role, takže
 * účet založený s kteroukoli z těch dvou se sice přihlásí, ale dostane 403 na každé obrazovce —
 * pro obsluhu k nerozeznání od rozbité aplikace. Nápověda přiřazení „zákaznického portálu"
 * dokonce doporučovala.
 *
 * <p>Klíčový je {@link #offeredRolesMatchBaseline()}: netvrdí konkrétní tři názvy, ale že nabídka
 * <strong>odpovídá</strong> {@link SecurityConfig#WORKING_ROLES}. Kdyby někdo baseline rozšířil
 * nebo zúžil a zapomněl na číselník, test spadne — přesně ten typ rozejití, který audit našel
 * jinde v projektu.
 */
@AutoConfigureMockMvc
@Transactional
class CodeListRolesTest extends AbstractIntegrationTest {

    private static final String ROLES_ENDPOINT = "/api/v1/code-lists/roles";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RoleMapper roleMapper;

    private static AppUserDetails admin() {
        return new AppUserDetails(User.builder()
                .id(1L).username("admin").passwordHash("n/a")
                .enabled(true).accountNonExpired(true).accountNonLocked(true)
                .credentialsNonExpired(true)
                .roles(List.of(Role.builder().name("ROLE_ADMIN").build()))
                .build());
    }

    @Test
    @DisplayName("číselník nabízí právě role, které baseline /api/** pustí dovnitř")
    void offeredRolesMatchBaseline() throws Exception {
        List<String> expected = Arrays.stream(SecurityConfig.WORKING_ROLES)
                .map(role -> "ROLE_" + role)
                .toList();

        String body = mockMvc.perform(get(ROLES_ENDPOINT).with(user(admin())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        for (String role : expected) {
            assertThat(body).as("chybí pracovní role %s", role).contains(role);
        }
        assertThat(body).doesNotContain("ROLE_CUSTOMER", "ROLE_READONLY");
    }

    @Test
    @DisplayName("počet nabízených rolí odpovídá pracovním rolím, ne všem řádkům tabulky")
    void offeredRoleCount_isNotAllRows() throws Exception {
        long rowsInTable = roleMapper.getAll().size();
        assertThat(rowsInTable)
                .as("předpoklad testu: v DB je víc rolí než pracovních — jinak test nic nedokazuje")
                .isGreaterThan(SecurityConfig.WORKING_ROLES.length);

        mockMvc.perform(get(ROLES_ENDPOINT).with(user(admin())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(SecurityConfig.WORKING_ROLES.length));
    }

    @Test
    @DisplayName("odfiltrované role zůstávají v databázi — filtruje se jen nabídka")
    void filteredRolesStillExistInDatabase() {
        assertThat(roleMapper.getAll())
                .extracting(Role::getName)
                .as("řádky se nemažou (R-06), jen se nenabízejí")
                .contains("ROLE_CUSTOMER", "ROLE_READONLY");
    }
}
