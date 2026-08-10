package cz.palo.autoservis.model.converter;

import cz.palo.autoservis.model.domain.user.Role;
import cz.palo.autoservis.model.domain.user.User;
import cz.palo.autoservis.model.dto.user.UserDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Konvertor uživatelských účtů — čistý unit test bez Spring kontextu.
 *
 * <p>Dvě věci, které musí platit bezpodmínečně:
 * <ul>
 *   <li>do odpovědi se <strong>nikdy</strong> nesmí dostat hash hesla — DTO ho nemá,
 *       test to hlídá tím, že fixtura hash nastavený má;</li>
 *   <li>role se čtou z JOINu, takže mohou přijít jako {@code null} nebo s prázdným názvem —
 *       konvertor je musí ustát a vrátit prázdný seznam, ne spadnout.</li>
 * </ul>
 */
class UserConverterTest {

    private final UserConverter converter = new UserConverter();

    @Test
    @DisplayName("toDetailResponse přenese účet i role jako objekty RoleDto")
    void toDetailResponse_mapsAccountAndRoles() {
        User user = enabledUser();
        user.setId(1L);
        user.setUsername("admin");
        user.setEmail("admin@autoservis.cz");
        user.setAccountNonLocked(true);
        user.setFailedLoginAttempts(2);
        user.setRoles(List.of(role(1, "ROLE_ADMIN", "Správce"), role(2, "ROLE_MANAGER", "Vedoucí")));
        user.setLastLoginAt(OffsetDateTime.parse("2026-07-20T08:00:00Z"));
        user.setPasswordChangedAt(OffsetDateTime.parse("2026-01-01T10:00:00Z"));
        user.setCreatedAt(OffsetDateTime.parse("2025-12-01T09:00:00Z"));
        user.setUpdatedAt(OffsetDateTime.parse("2026-07-20T08:00:00Z"));

        UserDto.DetailResponse response = converter.toDetailResponse(user);

        assertThat(response.getId()).isEqualTo(1L);
        assertThat(response.getUsername()).isEqualTo("admin");
        assertThat(response.getEmail()).isEqualTo("admin@autoservis.cz");
        assertThat(response.isEnabled()).isTrue();
        assertThat(response.isAccountNonLocked()).isTrue();
        assertThat(response.getFailedLoginAttempts()).isEqualTo(2);
        assertThat(response.getLastLoginAt()).isEqualTo(OffsetDateTime.parse("2026-07-20T08:00:00Z"));
        assertThat(response.getPasswordChangedAt()).isEqualTo(OffsetDateTime.parse("2026-01-01T10:00:00Z"));
        assertThat(response.getCreatedAt()).isEqualTo(OffsetDateTime.parse("2025-12-01T09:00:00Z"));
        assertThat(response.getUpdatedAt()).isEqualTo(OffsetDateTime.parse("2026-07-20T08:00:00Z"));

        assertThat(response.getRoles()).hasSize(2);
        assertThat(response.getRoles().get(0).getId()).isEqualTo(1);
        assertThat(response.getRoles().get(0).getName()).isEqualTo("ROLE_ADMIN");
        assertThat(response.getRoles().get(0).getDescription()).isEqualTo("Správce");
        assertThat(response.getRoles().get(1).getName()).isEqualTo("ROLE_MANAGER");
    }

    @Test
    @DisplayName("toDetailResponse: zamčený účet se propíše jako accountNonLocked = false")
    void toDetailResponse_lockedAccount_isReported() {
        User user = enabledUser();
        user.setAccountNonLocked(false);
        user.setFailedLoginAttempts(10);

        UserDto.DetailResponse response = converter.toDetailResponse(user);

        assertThat(response.isAccountNonLocked()).isFalse();
        assertThat(response.getFailedLoginAttempts()).isEqualTo(10);
    }

    @Test
    @DisplayName("toDetailResponse: chybějící role → prázdný seznam, ne null a ne pád")
    void toDetailResponse_nullRoles_yieldsEmptyList() {
        User user = enabledUser();
        user.setRoles(null);

        assertThat(converter.toDetailResponse(user).getRoles()).isNotNull().isEmpty();
    }

    @Test
    @DisplayName("toDetailResponse: role bez názvu se do odpovědi nedostane")
    void toDetailResponse_roleWithNullName_isFilteredOut() {
        User user = enabledUser();
        user.setRoles(Arrays.asList(role(1, "ROLE_ADMIN", "Správce"), role(9, null, "poškozený řádek")));

        UserDto.DetailResponse response = converter.toDetailResponse(user);

        assertThat(response.getRoles()).hasSize(1);
        assertThat(response.getRoles().getFirst().getName()).isEqualTo("ROLE_ADMIN");
    }

    @Test
    @DisplayName("toDetailResponse(null) → null")
    void toDetailResponse_null_returnsNull() {
        assertThat(converter.toDetailResponse(null)).isNull();
    }

    @Test
    @DisplayName("toListResponses vrací role jako názvy a zachová pořadí účtů")
    void toListResponses_mapsRoleNamesInOrder() {
        User admin = enabledUser();
        admin.setId(1L);
        admin.setUsername("admin");
        admin.setEmail("admin@autoservis.cz");
        admin.setLastLoginAt(OffsetDateTime.parse("2026-07-20T08:00:00Z"));
        admin.setCreatedAt(OffsetDateTime.parse("2025-12-01T09:00:00Z"));
        admin.setRoles(List.of(role(1, "ROLE_ADMIN", "Správce")));

        User mechanic = enabledUser();
        mechanic.setId(3L);
        mechanic.setUsername("mechanic");
        mechanic.setEnabled(false);
        mechanic.setRoles(List.of(role(3, "ROLE_MECHANIC", "Mechanik")));

        List<UserDto.ListResponse> result = converter.toListResponses(List.of(admin, mechanic));

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getId()).isEqualTo(1L);
        assertThat(result.get(0).getUsername()).isEqualTo("admin");
        assertThat(result.get(0).getEmail()).isEqualTo("admin@autoservis.cz");
        assertThat(result.get(0).getLastLoginAt()).isEqualTo(OffsetDateTime.parse("2026-07-20T08:00:00Z"));
        assertThat(result.get(0).getCreatedAt()).isEqualTo(OffsetDateTime.parse("2025-12-01T09:00:00Z"));
        assertThat(result.get(0).isEnabled()).isTrue();
        assertThat(result.get(0).getRoles()).containsExactly("ROLE_ADMIN");
        assertThat(result.get(1).getUsername()).isEqualTo("mechanic");
        assertThat(result.get(1).isEnabled()).isFalse();
        assertThat(result.get(1).getRoles()).containsExactly("ROLE_MECHANIC");
    }

    @Test
    @DisplayName("toListResponses: chybějící role → prázdný seznam názvů")
    void toListResponses_nullRoles_yieldEmptyNameList() {
        User user = enabledUser();
        user.setRoles(null);

        List<UserDto.ListResponse> result = converter.toListResponses(List.of(user));

        assertThat(result.getFirst().getRoles()).isNotNull().isEmpty();
    }

    @Test
    @DisplayName("odpovědi neobsahují hash hesla")
    void responses_neverExposePasswordHash() {
        User user = enabledUser();
        user.setPasswordHash("$2a$10$tajnyBcryptHash");
        user.setRoles(List.of(role(1, "ROLE_ADMIN", "Správce")));

        UserDto.DetailResponse detail = converter.toDetailResponse(user);
        UserDto.ListResponse list = converter.toListResponses(List.of(user)).getFirst();

        assertThat(detail.toString()).doesNotContain("$2a$10$tajnyBcryptHash");
        assertThat(list.toString()).doesNotContain("$2a$10$tajnyBcryptHash");
    }

    @Test
    @DisplayName("toDomain přenese username a email a založí účet jako povolený")
    void toDomain_mapsCredentialsAndEnablesAccount() {
        UserDto.CreateRequest request = new UserDto.CreateRequest();
        request.setUsername("novy");
        request.setEmail("novy@autoservis.cz");
        request.setPassword("Password1!");

        User result = converter.toDomain(request);

        assertThat(result.getUsername()).isEqualTo("novy");
        assertThat(result.getEmail()).isEqualTo("novy@autoservis.cz");
        assertThat(result.isEnabled()).isTrue();
    }

    @Test
    @DisplayName("toDomain nepřenáší heslo v otevřené podobě — hash počítá service")
    void toDomain_doesNotCopyRawPassword() {
        UserDto.CreateRequest request = new UserDto.CreateRequest();
        request.setUsername("novy");
        request.setEmail("novy@autoservis.cz");
        request.setPassword("Password1!");

        User result = converter.toDomain(request);

        assertThat(result.getPasswordHash()).isNull();
        assertThat(result.getId()).isNull();
    }

    @Test
    @DisplayName("toDomain(null) → null")
    void toDomain_null_returnsNull() {
        assertThat(converter.toDomain(null)).isNull();
    }

    @Test
    @DisplayName("applyUpdate změní jen e-mail — username je needitovatelné, role řeší service")
    void applyUpdate_changesEmailOnly() {
        User existing = enabledUser();
        existing.setId(1L);
        existing.setUsername("admin");
        existing.setEmail("stary@autoservis.cz");
        existing.setRoles(List.of(role(1, "ROLE_ADMIN", "Správce")));

        UserDto.UpdateRequest request = new UserDto.UpdateRequest();
        request.setEmail("novy@autoservis.cz");

        User result = converter.applyUpdate(existing, request);

        assertThat(result).as("mutace probíhá na místě, vrací se tentýž objekt").isSameAs(existing);
        assertThat(existing.getEmail()).isEqualTo("novy@autoservis.cz");
        assertThat(existing.getUsername()).as("username se needituje").isEqualTo("admin");
        assertThat(existing.getId()).isEqualTo(1L);
        assertThat(existing.getRoles()).as("role přepisuje mapper přes user_roles").hasSize(1);
    }

    @Test
    @DisplayName("applyUpdate vrací null, chybí-li kterýkoli z argumentů")
    void applyUpdate_nullArguments_returnNull() {
        assertThat(converter.applyUpdate(null, new UserDto.UpdateRequest())).isNull();
        assertThat(converter.applyUpdate(enabledUser(), null)).isNull();
    }

    private static User enabledUser() {
        User user = new User();
        user.setUsername("admin");
        user.setEmail("admin@autoservis.cz");
        user.setEnabled(true);
        return user;
    }

    private static Role role(int id, String name, String description) {
        return Role.builder().id(id).name(name).description(description).build();
    }
}
