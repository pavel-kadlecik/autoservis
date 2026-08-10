package cz.palo.autoservis.security;

import cz.palo.autoservis.AbstractIntegrationTest;
import cz.palo.autoservis.exception.InvalidRefreshTokenException;
import cz.palo.autoservis.security.mapper.RefreshTokenMapper;
import cz.palo.autoservis.security.model.domain.RefreshToken;
import cz.palo.autoservis.security.model.dto.ChangePasswordRequest;
import cz.palo.autoservis.security.model.dto.LoginRequest;
import cz.palo.autoservis.security.model.dto.RefreshRequest;
import cz.palo.autoservis.security.model.dto.TokenResponse;
import cz.palo.autoservis.security.service.AuthenticationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Rotace refresh tokenů a <strong>detekce jejich opakovaného použití</strong>
 * ({@code AuthenticationService.refresh}).
 *
 * <p>Tohle je bezpečnostní jádro přihlášení: každý refresh starý token odvolá a vydá nový.
 * Když se objeví <em>už odvolaný</em> token, server to považuje za krádež a preventivně
 * odvolá <strong>všechny</strong> sessions daného uživatele. Test proto neověřuje jen to,
 * že druhé použití selže, ale i to, že se přitom zneplatní i ostatní — jinak by útočník
 * s ukradeným tokenem zůstal přihlášený.
 *
 * <p>Seed (V3): účty admin/manager/mechanic, heslo {@code Password1!}.
 */
@Transactional
class RefreshTokenRotationTest extends AbstractIntegrationTest {

    private static final String USERNAME = "manager";
    private static final String PASSWORD = "Password1!";

    @Autowired
    private AuthenticationService authenticationService;

    @Autowired
    private RefreshTokenMapper refreshTokenMapper;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    /** Kolikrát je hash access tokenu na blacklistu (blacklist ukládá SHA-256 hex, ne raw JWT). */
    private int blacklistCount(String accessToken) {
        String hash = cz.palo.autoservis.security.service.TokenHasher.sha256Hex(accessToken);
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM security.token_blacklist WHERE token = ?", Integer.class, hash);
        return count == null ? 0 : count;
    }

    // =========================================================================
    // Vydání a rotace
    // =========================================================================

    @Test
    @DisplayName("login vydá dvojici tokenů a refresh token uloží do DB jako neodvolaný")
    void login_issuesTokenPairAndPersistsRefreshToken() {
        TokenResponse tokens = login();

        assertThat(tokens.accessToken()).isNotBlank();
        assertThat(tokens.refreshToken()).isNotBlank();
        assertThat(tokens.accessToken())
                .as("access token je JWT — tři části oddělené tečkou")
                .matches("[^.]+\\.[^.]+\\.[^.]+");
        assertThat(tokens.refreshToken())
                .as("refresh token je neprůhledné UUID, ne JWT")
                .matches("[0-9a-f-]{36}");

        RefreshToken stored = stored(tokens.refreshToken()).orElseThrow();
        assertThat(stored.isRevoked()).isFalse();
        assertThat(stored.getExpiresAt()).isAfter(LocalDateTime.now());
    }

    @Test
    @DisplayName("refresh token se v DB ukládá jako SHA-256 hash, ne jako syrové UUID (K-7)")
    void refreshToken_isStoredHashedNotRaw() {
        TokenResponse tokens = login();

        Integer rawRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM security.refresh_tokens WHERE token = ?",
                Integer.class, tokens.refreshToken());
        Integer hashRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM security.refresh_tokens WHERE token = ?",
                Integer.class, cz.palo.autoservis.security.service.TokenHasher.sha256Hex(tokens.refreshToken()));

        assertThat(rawRows).as("syrové UUID nesmí být v DB").isZero();
        assertThat(hashRows).as("v DB je hash tokenu").isEqualTo(1);
    }

    @Test
    @DisplayName("refresh vydá NOVÝ pár a starý refresh token odvolá (rotace)")
    void refresh_rotatesTokenPair() {
        TokenResponse first = login();

        TokenResponse second = authenticationService.refresh(new RefreshRequest(first.refreshToken()));

        assertThat(second.refreshToken())
                .as("musí přijít jiný refresh token, jinak rotace neproběhla")
                .isNotEqualTo(first.refreshToken());

        assertThat(stored(first.refreshToken()).orElseThrow().isRevoked())
                .as("starý token je odvolaný").isTrue();
        assertThat(stored(second.refreshToken()).orElseThrow().isRevoked())
                .as("nový token je platný").isFalse();
    }

    @Test
    @DisplayName("nový refresh token z rotace lze použít k další rotaci (řetězení funguje)")
    void refresh_newTokenCanBeUsedAgain() {
        TokenResponse first = login();
        TokenResponse second = authenticationService.refresh(new RefreshRequest(first.refreshToken()));

        TokenResponse third = authenticationService.refresh(new RefreshRequest(second.refreshToken()));

        assertThat(third.refreshToken())
                .isNotEqualTo(second.refreshToken())
                .isNotEqualTo(first.refreshToken());
        assertThat(stored(third.refreshToken()).orElseThrow().isRevoked()).isFalse();
    }

    // =========================================================================
    // Detekce opakovaného použití
    // =========================================================================

    @Test
    @DisplayName("druhé použití téhož refresh tokenu → InvalidRefreshTokenException")
    void refresh_reusedToken_isRejected() {
        TokenResponse first = login();
        authenticationService.refresh(new RefreshRequest(first.refreshToken()));

        assertThatThrownBy(() -> authenticationService.refresh(new RefreshRequest(first.refreshToken())))
                .isInstanceOf(InvalidRefreshTokenException.class)
                .hasMessageContaining("odvolán");
    }

    @Test
    @DisplayName("opakované použití odvolá VŠECHNY sessions uživatele, ne jen ten jeden token")
    void refresh_reusedToken_revokesEverySessionOfTheUser() {
        // Dvě nezávislá přihlášení = dvě živé sessions (např. notebook a mobil).
        TokenResponse laptop = login();
        TokenResponse phone = login();
        assertThat(stored(phone.refreshToken()).orElseThrow().isRevoked())
                .as("předpoklad: druhá session je živá").isFalse();

        // Legitimní rotace na notebooku…
        TokenResponse rotated = authenticationService.refresh(new RefreshRequest(laptop.refreshToken()));
        assertThat(stored(rotated.refreshToken()).orElseThrow().isRevoked()).isFalse();

        // …a poté útočník zkusí ukradený (už odvolaný) token.
        assertThatThrownBy(() -> authenticationService.refresh(new RefreshRequest(laptop.refreshToken())))
                .isInstanceOf(InvalidRefreshTokenException.class);

        // Preventivní odvolání se musí týkat úplně všech tokenů uživatele.
        assertThat(stored(rotated.refreshToken()).orElseThrow().isRevoked())
                .as("i čerstvě vydaný token z legitimní rotace padá").isTrue();
        assertThat(stored(phone.refreshToken()).orElseThrow().isRevoked())
                .as("i nesouvisející session na jiném zařízení padá").isTrue();
    }

    @Test
    @DisplayName("po odvolání všech sessions už žádný z tokenů nefunguje")
    void refresh_afterMassRevocation_noTokenWorks() {
        TokenResponse laptop = login();
        TokenResponse phone = login();
        TokenResponse rotated = authenticationService.refresh(new RefreshRequest(laptop.refreshToken()));

        assertThatThrownBy(() -> authenticationService.refresh(new RefreshRequest(laptop.refreshToken())))
                .isInstanceOf(InvalidRefreshTokenException.class);

        assertThatThrownBy(() -> authenticationService.refresh(new RefreshRequest(rotated.refreshToken())))
                .isInstanceOf(InvalidRefreshTokenException.class);
        assertThatThrownBy(() -> authenticationService.refresh(new RefreshRequest(phone.refreshToken())))
                .isInstanceOf(InvalidRefreshTokenException.class);
    }

    // =========================================================================
    // Neplatné a expirované tokeny
    // =========================================================================

    @Test
    @DisplayName("neznámý refresh token → InvalidRefreshTokenException (nenalezen)")
    void refresh_unknownToken_isRejected() {
        assertThatThrownBy(() -> authenticationService.refresh(
                new RefreshRequest(UUID.randomUUID().toString())))
                .isInstanceOf(InvalidRefreshTokenException.class)
                .hasMessageContaining("nebyl nalezen");
    }

    @Test
    @DisplayName("expirovaný refresh token → odmítnut a rovnou odvolán")
    void refresh_expiredToken_isRejectedAndRevoked() {
        String expiredValue = UUID.randomUUID().toString();
        refreshTokenMapper.save(RefreshToken.builder()
                .id(UUID.randomUUID().toString())
                .token(cz.palo.autoservis.security.service.TokenHasher.sha256Hex(expiredValue))
                .userId(2L) // manager
                .expiresAt(LocalDateTime.now().minusDays(1))
                .revoked(false)
                .createdAt(LocalDateTime.now().minusDays(8))
                .build());

        assertThatThrownBy(() -> authenticationService.refresh(new RefreshRequest(expiredValue)))
                .isInstanceOf(InvalidRefreshTokenException.class)
                .hasMessageContaining("vypršel");

        assertThat(stored(expiredValue).orElseThrow().isRevoked())
                .as("expirovaný token se rovnou odvolá, ať nejde použít znovu").isTrue();
    }

    @Test
    @DisplayName("expirovaný token NEODVOLÁVÁ ostatní sessions (na rozdíl od reuse)")
    void refresh_expiredToken_doesNotRevokeOtherSessions() {
        TokenResponse live = login();

        String expiredValue = UUID.randomUUID().toString();
        refreshTokenMapper.save(RefreshToken.builder()
                .id(UUID.randomUUID().toString())
                .token(cz.palo.autoservis.security.service.TokenHasher.sha256Hex(expiredValue))
                .userId(2L)
                .expiresAt(LocalDateTime.now().minusDays(1))
                .revoked(false)
                .createdAt(LocalDateTime.now().minusDays(8))
                .build());

        assertThatThrownBy(() -> authenticationService.refresh(new RefreshRequest(expiredValue)))
                .isInstanceOf(InvalidRefreshTokenException.class);

        assertThat(stored(live.refreshToken()).orElseThrow().isRevoked())
                .as("prosté vypršení není útok — živá session zůstává").isFalse();
    }

    // =========================================================================
    // Odhlášení
    // =========================================================================

    @Test
    @DisplayName("logout odvolá refresh token, takže se s ním už nedá obnovit session")
    void logout_revokesRefreshToken() {
        TokenResponse tokens = login();

        authenticationService.logout(tokens.accessToken(), tokens.refreshToken());

        assertThat(stored(tokens.refreshToken()).orElseThrow().isRevoked()).isTrue();
        assertThatThrownBy(() -> authenticationService.refresh(new RefreshRequest(tokens.refreshToken())))
                .isInstanceOf(InvalidRefreshTokenException.class);
    }

    @Test
    @DisplayName("logout bez refresh tokenu nespadne (odhlášení jen s access tokenem)")
    void logout_withoutRefreshToken_doesNotFail() {
        TokenResponse tokens = login();

        authenticationService.logout(tokens.accessToken(), null);

        assertThat(stored(tokens.refreshToken()).orElseThrow().isRevoked())
                .as("refresh token se bez předložení neodvolává").isFalse();
    }

    @Test
    @DisplayName("logout s prázdným refresh tokenem se chová stejně jako s null")
    void logout_withBlankRefreshToken_doesNotFail() {
        TokenResponse tokens = login();

        authenticationService.logout(tokens.accessToken(), "   ");

        assertThat(stored(tokens.refreshToken()).orElseThrow().isRevoked()).isFalse();
    }

    @Test
    @DisplayName("druhý logout téhož tokenu je idempotentní — nespadne (TD-53)")
    void logout_calledTwice_isIdempotent() {
        // BlacklistMapper.save má ON CONFLICT (token) DO NOTHING, takže opakované odhlášení
        // stejným access tokenem (dvojklik, retry po výpadku sítě, dva panely) je no-op,
        // ne DuplicateKeyException → 422.
        TokenResponse tokens = login();
        authenticationService.logout(tokens.accessToken(), tokens.refreshToken());

        // druhé volání nesmí vyhodit výjimku
        authenticationService.logout(tokens.accessToken(), tokens.refreshToken());

        // token je pořád na blacklistu (právě jednou)
        assertThat(blacklistCount(tokens.accessToken()))
                .as("duplicitní logout nevytvoří druhý řádek").isEqualTo(1);
    }

    // =========================================================================
    // Změna hesla — invalidace sessions (K-6)
    // =========================================================================

    @Test
    @DisplayName("změna hesla odvolá všechny refresh tokeny uživatele (K-6)")
    void changePassword_revokesAllSessions() {
        TokenResponse tokens = login(); // manager = id 2
        assertThat(stored(tokens.refreshToken()).orElseThrow().isRevoked())
                .as("předpoklad: session je před změnou hesla živá").isFalse();

        authenticationService.changePassword(2L, new ChangePasswordRequest(PASSWORD, "NoveHeslo123!"));

        assertThat(stored(tokens.refreshToken()).orElseThrow().isRevoked())
                .as("po změně hesla je stará session odvolaná").isTrue();
        assertThatThrownBy(() -> authenticationService.refresh(new RefreshRequest(tokens.refreshToken())))
                .isInstanceOf(InvalidRefreshTokenException.class);
    }

    private TokenResponse login() {
        return authenticationService.login(new LoginRequest(USERNAME, PASSWORD));
    }

    /** Najde uložený refresh token — vstup je syrová hodnota, v DB je jeho SHA-256 hash (K-7). */
    private java.util.Optional<RefreshToken> stored(String rawToken) {
        return refreshTokenMapper.findByToken(
                cz.palo.autoservis.security.service.TokenHasher.sha256Hex(rawToken));
    }
}
