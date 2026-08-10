package cz.palo.autoservis.security;

import cz.palo.autoservis.AbstractIntegrationTest;
import cz.palo.autoservis.model.dto.RoleDto;
import cz.palo.autoservis.security.mapper.BlacklistMapper;
import cz.palo.autoservis.security.mapper.UserMapper;
import cz.palo.autoservis.security.model.domain.AppUserDetails;
import cz.palo.autoservis.security.service.AppUserDetailsService;
import cz.palo.autoservis.security.service.BlacklistCleanupService;
import cz.palo.autoservis.security.service.RoleService;
import cz.palo.autoservis.security.service.TokenHasher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Zbývající služby bezpečnostní vrstvy: načítání uživatele pro Spring Security,
 * číselník rolí a plánovaný úklid blacklistu.
 *
 * <p>Pozornost si zaslouží {@code AppUserDetailsService}: podle {@code backend.md} §3 filtruje
 * jen {@code enabled = TRUE}, ale <strong>ne</strong> {@code account_non_locked} — o zamčeném
 * účtu rozhoduje až {@code AuthenticationManager}. Test tenhle rozdíl ověřuje výslovně, protože
 * kdyby SQL začalo filtrovat i zámek, zamčený uživatel by dostal 401 s jiným kódem
 * ({@code BAD_CREDENTIALS} místo {@code ACCOUNT_LOCKED}) a přestal by poznat, co se děje.
 */
@Transactional
class SecurityServicesTest extends AbstractIntegrationTest {

    private static final long MECHANIC_ID = 3L;

    @Autowired
    private AppUserDetailsService appUserDetailsService;

    @Autowired
    private RoleService roleService;

    @Autowired
    private BlacklistCleanupService blacklistCleanupService;

    @Autowired
    private BlacklistMapper blacklistMapper;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // =========================================================================
    // AppUserDetailsService
    // =========================================================================

    @Test
    @DisplayName("loadUserByUsername vrátí uživatele i s rolemi jako GrantedAuthority")
    void loadUserByUsername_returnsUserWithAuthorities() {
        UserDetails userDetails = appUserDetailsService.loadUserByUsername("admin");

        assertThat(userDetails).isInstanceOf(AppUserDetails.class);
        assertThat(userDetails.getUsername()).isEqualTo("admin");
        assertThat(userDetails.getPassword()).as("hash je potřeba pro ověření hesla").isNotBlank();
        assertThat(userDetails.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_ADMIN");
        assertThat(((AppUserDetails) userDetails).getUserId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("neznámé uživatelské jméno → UsernameNotFoundException")
    void loadUserByUsername_unknownUser_throws() {
        assertThatThrownBy(() -> appUserDetailsService.loadUserByUsername("neexistujici-ucet"))
                .isInstanceOf(UsernameNotFoundException.class)
                .hasMessageContaining("neexistujici-ucet");
    }

    @Test
    @DisplayName("ZAMČENÝ účet se načte a příznak přenese — o odmítnutí rozhoduje AuthenticationManager")
    void loadUserByUsername_lockedAccount_isStillLoadedWithFlag() {
        userMapper.lockAccount(MECHANIC_ID);

        UserDetails userDetails = appUserDetailsService.loadUserByUsername("mechanic");

        assertThat(userDetails.isAccountNonLocked())
                .as("zámek se propíše do UserDetails, ne že by se účet skryl").isFalse();
        assertThat(userDetails.isEnabled()).isTrue();
    }

    @Test
    @DisplayName("DEAKTIVOVANÝ účet se nenačte vůbec (SQL filtruje enabled = TRUE)")
    void loadUserByUsername_disabledAccount_isNotFound() {
        userMapper.deactivate(MECHANIC_ID);

        assertThatThrownBy(() -> appUserDetailsService.loadUserByUsername("mechanic"))
                .isInstanceOf(UsernameNotFoundException.class);
    }

    // =========================================================================
    // RoleService
    // =========================================================================

    @Test
    @DisplayName("getAssignable vrátí pracovní role ze seedu, bez těch odříznutých baseline")
    void getAssignable_returnsWorkingRolesOnly() {
        List<RoleDto> roles = roleService.getAssignable();

        assertThat(roles).isNotEmpty();
        assertThat(roles).extracting(RoleDto::getName)
                .contains("ROLE_ADMIN", "ROLE_MANAGER", "ROLE_MECHANIC")
                // KN-22: ROLE_CUSTOMER a ROLE_READONLY baseline /api/** nepustí, takže účet
                // s nimi dostane 403 všude. V DB zůstávají, jen se nenabízejí.
                .doesNotContain("ROLE_CUSTOMER", "ROLE_READONLY");
        assertThat(roles).allSatisfy(role -> {
            assertThat(role.getId()).isPositive();
            assertThat(role.getName()).startsWith("ROLE_");
        });
    }

    // =========================================================================
    // BlacklistCleanupService
    // =========================================================================

    @Test
    @DisplayName("úklid smaže staré záznamy blacklistu, čerstvé nechá")
    void cleanupOldTokens_removesOnlyExpiredEntries() {
        String oldHash = TokenHasher.sha256Hex("stary-token-" + System.nanoTime());
        String freshHash = TokenHasher.sha256Hex("cerstvy-token-" + System.nanoTime());

        blacklistMapper.save(oldHash);
        blacklistMapper.save(freshHash);
        // starému záznamu posuneme čas zařazení hluboko do minulosti
        jdbcTemplate.update(
                "UPDATE security.token_blacklist SET invalidated_at = ? WHERE token = ?",
                LocalDateTime.now().minusDays(30), oldHash);

        assertThat(blacklistMapper.isBlacklisted(oldHash)).as("předpoklad testu").isTrue();

        blacklistCleanupService.cleanupOldTokens();

        assertThat(blacklistMapper.isBlacklisted(oldHash))
                .as("expirovaný záznam se maže — tabulka nesmí růst donekonečna").isFalse();
        assertThat(blacklistMapper.isBlacklisted(freshHash))
                .as("čerstvý záznam musí zůstat, jinak by odhlášený token zase platil").isTrue();
    }
}
