package cz.palo.autoservis.security;

import cz.palo.autoservis.AbstractIntegrationTest;
import cz.palo.autoservis.model.domain.user.Role;
import cz.palo.autoservis.model.domain.user.User;
import cz.palo.autoservis.security.model.domain.AppUserDetails;
import cz.palo.autoservis.security.service.JwtService;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.userdetails.UserDetails;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Vydávání a ověřování JWT ({@code JwtService}).
 *
 * <p>Klíčové je, že se ověřuje <strong>podpis</strong>, ne jen obsah: token podepsaný cizím
 * klíčem se musí odmítnout, jinak by si kdokoli mohl vyrobit token na libovolné jméno.
 * Stejně tak musí padnout expirovaný token — obojí se testuje ručně sestaveným tokenem,
 * protože přes {@code generateToken} takový token nevznikne.
 *
 * <p>Běží nad Spring kontextem, protože klíč i doby platnosti se injektují z konfigurace
 * ({@code jwt.secret}, {@code jwt.expiration}).
 */
class JwtServiceTest extends AbstractIntegrationTest {

    @Autowired
    private JwtService jwtService;

    private static UserDetails userDetails(String username) {
        return new AppUserDetails(User.builder()
                .id(1L).username(username).passwordHash("n/a")
                .enabled(true).accountNonExpired(true).accountNonLocked(true)
                .credentialsNonExpired(true)
                .roles(List.of(Role.builder().name("ROLE_ADMIN").build()))
                .build());
    }

    // =========================================================================
    // Vydání tokenu
    // =========================================================================

    @Test
    @DisplayName("generateToken vydá JWT, ze kterého jde přečíst uživatelské jméno")
    void generateToken_roundTripsUsername() {
        String token = jwtService.generateToken(userDetails("admin"));

        assertThat(token).matches("[^.]+\\.[^.]+\\.[^.]+");
        assertThat(jwtService.extractUsername(token)).isEqualTo("admin");
    }

    @Test
    @DisplayName("dva tokeny pro různé uživatele nesou různá jména")
    void generateToken_carriesTheRightSubject() {
        String adminToken = jwtService.generateToken(userDetails("admin"));
        String managerToken = jwtService.generateToken(userDetails("manager"));

        assertThat(jwtService.extractUsername(adminToken)).isEqualTo("admin");
        assertThat(jwtService.extractUsername(managerToken)).isEqualTo("manager");
    }

    @Test
    @DisplayName("token s extra claims si zachová jméno v subjectu")
    void generateToken_withExtraClaims_keepsSubject() {
        String token = jwtService.generateToken(Map.of("role", "ROLE_ADMIN"), userDetails("admin"));

        assertThat(jwtService.extractUsername(token)).isEqualTo("admin");
    }

    // =========================================================================
    // Ověření platnosti
    // =========================================================================

    @Test
    @DisplayName("isTokenValid: token svého uživatele je platný")
    void isTokenValid_ownToken_isTrue() {
        String token = jwtService.generateToken(userDetails("admin"));

        assertThat(jwtService.isTokenValid(token, userDetails("admin"))).isTrue();
    }

    @Test
    @DisplayName("isTokenValid: token JINÉHO uživatele je neplatný (token nejde přenést na cizí účet)")
    void isTokenValid_tokenOfAnotherUser_isFalse() {
        String token = jwtService.generateToken(userDetails("admin"));

        assertThat(jwtService.isTokenValid(token, userDetails("manager"))).isFalse();
    }

    @Test
    @DisplayName("token podepsaný CIZÍM klíčem se odmítne — ověřuje se podpis, ne jen obsah")
    void extractUsername_foreignSignature_isRejected() {
        // Token má korektní strukturu i subject, liší se jen podpisem. Kdyby se podpis
        // neověřoval, kdokoli by si vyrobil token na libovolné jméno.
        SecretKey foreignKey = Keys.hmacShaKeyFor(
                "tohle-je-uplne-jiny-klic-ktery-ma-aspon-256-bitu!!".getBytes(StandardCharsets.UTF_8));
        String forged = Jwts.builder()
                .subject("admin")
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(foreignKey, Jwts.SIG.HS256)
                .compact();

        assertThatThrownBy(() -> jwtService.extractUsername(forged))
                .isInstanceOf(io.jsonwebtoken.security.SignatureException.class);
    }

    @Test
    @DisplayName("nezparsovatelný řetězec se odmítne, nevrací null")
    void extractUsername_garbage_isRejected() {
        assertThatThrownBy(() -> jwtService.extractUsername("tohle-neni-jwt"))
                .isInstanceOf(io.jsonwebtoken.JwtException.class);
    }

    @Test
    @DisplayName("expirovaný token se odmítne už při parsování")
    void extractUsername_expiredToken_isRejected() {
        String expired = expiredTokenSignedWithApplicationKey();

        assertThatThrownBy(() -> jwtService.extractUsername(expired))
                .isInstanceOf(io.jsonwebtoken.ExpiredJwtException.class);
    }

    @Test
    @DisplayName("isTokenValid na expirovaném tokenu taky neprojde")
    void isTokenValid_expiredToken_doesNotPass() {
        String expired = expiredTokenSignedWithApplicationKey();

        assertThatThrownBy(() -> jwtService.isTokenValid(expired, userDetails("admin")))
                .isInstanceOf(io.jsonwebtoken.ExpiredJwtException.class);
    }

    // =========================================================================
    // Refresh token
    // =========================================================================

    @Test
    @DisplayName("refresh token je neprůhledné UUID, ne JWT — nejde z něj nic vyčíst")
    void generateRefreshToken_isOpaqueUuid() {
        String refreshToken = jwtService.generateRefreshToken();

        assertThat(refreshToken).matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
        assertThat(refreshToken).doesNotContain(".");
    }

    @Test
    @DisplayName("každý refresh token je jiný (nesmí se opakovat)")
    void generateRefreshToken_isUniquePerCall() {
        java.util.Set<String> tokens = new java.util.HashSet<>();
        for (int i = 0; i < 50; i++) {
            tokens.add(jwtService.generateRefreshToken());
        }

        assertThat(tokens).hasSize(50);
    }

    @Test
    @DisplayName("expirace refresh tokenu leží v budoucnosti")
    void generateRefreshTokenExpiry_isInTheFuture() {
        Date expiry = jwtService.generateRefreshTokenExpiry();

        assertThat(expiry).isAfter(new Date());
    }

    /**
     * Token podepsaný <strong>aplikačním</strong> klíčem, ale s expirací v minulosti —
     * jinak by se netestovala expirace, ale zase jen podpis. Klíč se čte ze stejné
     * konfigurace, jakou používá {@code JwtService}.
     */
    private String expiredTokenSignedWithApplicationKey() {
        SecretKey key = Keys.hmacShaKeyFor(io.jsonwebtoken.io.Decoders.BASE64.decode(applicationSecret));
        return Jwts.builder()
                .subject("admin")
                .issuedAt(new Date(System.currentTimeMillis() - 120_000))
                .expiration(new Date(System.currentTimeMillis() - 60_000))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    @org.springframework.beans.factory.annotation.Value("${jwt.secret}")
    private String applicationSecret;
}
