package cz.palo.autoservis.service;

import cz.palo.autoservis.AbstractIntegrationTest;
import cz.palo.autoservis.security.mapper.BlacklistMapper;
import cz.palo.autoservis.security.model.dto.LoginRequest;
import cz.palo.autoservis.security.model.dto.TokenResponse;
import cz.palo.autoservis.security.service.AuthenticationService;
import cz.palo.autoservis.security.service.TokenHasher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pokrývá B5 (V4, analyza-2026-07): {@code security.token_blacklist} musí ukládat SHA-256
 * hash access tokenu, nikdy raw JWT — uniklá záloha DB nesmí vydat použitelný bearer
 * token. Viz {@link TokenHasher}, {@code AuthenticationService.logout} (zápisová cesta) a
 * {@code JwtAuthenticationFilter} (čtecí cesta).
 *
 * <p>{@code @Transactional} — každý test běží ve vlastní transakci, která se automaticky
 * rollbackne (zdůvodnění viz {@code CustomerServiceTest}); řádek blacklistu vložený
 * {@code logout} tak nikdy neprosákne do jiných testů.
 */
@Transactional
class TokenBlacklistTest extends AbstractIntegrationTest {

    private static final String ADMIN_USERNAME = "admin";
    private static final String ADMIN_PASSWORD = "Password1!";

    @Autowired
    private AuthenticationService authenticationService;

    @Autowired
    private BlacklistMapper blacklistMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @Nested
    @DisplayName("TokenHasher.sha256Hex")
    class TokenHasherUnitTest {

        @Test
        @DisplayName("stejný vstup dá vždy stejný 64znakový hex otisk")
        void sha256Hex_isDeterministicAnd64CharsHex() {
            String token = "hlava.telo.podpis";

            String hash1 = TokenHasher.sha256Hex(token);
            String hash2 = TokenHasher.sha256Hex(token);

            assertThat(hash1).isEqualTo(hash2);
            assertThat(hash1).hasSize(64);
            assertThat(hash1).matches("[0-9a-f]{64}");
        }

        @Test
        @DisplayName("různé tokeny dají různý otisk")
        void sha256Hex_differentTokensProduceDifferentHashes() {
            assertThat(TokenHasher.sha256Hex("token-a"))
                    .isNotEqualTo(TokenHasher.sha256Hex("token-b"));
        }
    }

    @Nested
    @DisplayName("AuthenticationService.logout + JwtAuthenticationFilter lookup (integrace)")
    class LogoutBlacklistIntegrationTest {

        @Test
        @DisplayName("logout uloží do token_blacklist 64znakový hex otisk, ne raw JWT")
        void logout_storesHashNotRawToken() {
            TokenResponse tokens = authenticationService.login(new LoginRequest(ADMIN_USERNAME, ADMIN_PASSWORD));
            String accessToken = tokens.accessToken();

            authenticationService.logout(accessToken, tokens.refreshToken());

            String storedToken = jdbc.queryForObject(
                    "SELECT token FROM security.token_blacklist ORDER BY invalidated_at DESC LIMIT 1",
                    String.class);

            assertThat(storedToken).hasSize(64);
            assertThat(storedToken).matches("[0-9a-f]{64}");
            assertThat(storedToken).isNotEqualTo(accessToken);
            assertThat(storedToken).isEqualTo(TokenHasher.sha256Hex(accessToken));
        }

        @Test
        @DisplayName("isBlacklisted(hash(token)) je true, isBlacklisted(raw token) je false")
        void isBlacklisted_matchesOnlyHash_notRawToken() {
            TokenResponse tokens = authenticationService.login(new LoginRequest(ADMIN_USERNAME, ADMIN_PASSWORD));
            String accessToken = tokens.accessToken();

            authenticationService.logout(accessToken, tokens.refreshToken());

            assertThat(blacklistMapper.isBlacklisted(TokenHasher.sha256Hex(accessToken))).isTrue();
            assertThat(blacklistMapper.isBlacklisted(accessToken)).isFalse();
        }
    }
}
