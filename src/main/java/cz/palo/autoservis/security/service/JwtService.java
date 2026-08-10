package cz.palo.autoservis.security.service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * Služba pro generování a validaci JWT access tokenů a refresh tokenů.
 *
 * <h3>Access token:</h3>
 * <p>Krátkodobý JWT podepsaný HMAC-SHA256. Nese {@code sub} (uživatelské jméno)
 * a {@code exp} (čas expirace). Platnost se konfiguruje přes {@code jwt.expiration}
 * (dev 8 hodin, produkce 15 minut).
 *
 * <h3>Refresh token:</h3>
 * <p>Kryptograficky náhodný UUID řetězec — záměrně NE JWT. Neobsahuje žádnou
 * dekódovatelnou informaci; platnost se ověřuje výhradně v databázi
 * (tabulka {@code security.refresh_tokens}). Konfiguruje se přes
 * {@code jwt.refresh-expiration} (7 dní).
 */
@Service
public class JwtService {

    @Value("${jwt.secret}")
    private String secretKey;

    @Value("${jwt.expiration}")
    private long jwtExpiration;

    @Value("${jwt.refresh-expiration}")
    private long refreshExpiration;

    /**
     * Vytáhne uživatelské jméno (subject claim) z JWT access tokenu.
     *
     * @param token JWT access token
     * @return uživatelské jméno uložené v {@code sub} claimu
     */
    public String extractUsername(String token) {
        return extractClaim(token, Claims::getSubject);
    }

    /**
     * Vygeneruje JWT access token pro daného uživatele bez dalších claimů.
     *
     * @param userDetails autentizovaný uživatel
     * @return podepsaný JWT řetězec
     */
    public String generateToken(UserDetails userDetails) {
        return generateToken(new HashMap<>(), userDetails);
    }

    /**
     * Vygeneruje JWT access token s dodatečnými claimy.
     *
     * <p>Payload tokenu:
     * <ul>
     *   <li>{@code sub} — uživatelské jméno</li>
     *   <li>{@code iat} — čas vydání</li>
     *   <li>{@code exp} — expirace ({@code iat + jwt.expiration})</li>
     * </ul>
     *
     * @param extraClaims dodatečné claimy k vložení (např. role, userId)
     * @param userDetails autentizovaný uživatel
     * @return podepsaný JWT řetězec
     */
    public String generateToken(Map<String, Object> extraClaims, UserDetails userDetails) {
        return Jwts.builder()
                .claims(extraClaims)
                .subject(userDetails.getUsername())
                .issuedAt(new Date(System.currentTimeMillis()))
                .expiration(new Date(System.currentTimeMillis() + jwtExpiration))
                .signWith(getSignInKey(), Jwts.SIG.HS256)
                .compact();
    }

    /**
     * Vygeneruje refresh token — kryptograficky náhodný UUID řetězec.
     *
     * <p>Záměrně NE JWT. Token je neprůhledný a neobsahuje žádnou dekódovatelnou
     * informaci. Platnost se ověřuje výhradně v databázi
     * ({@code security.refresh_tokens}), kde se sleduje expirace i odvolání.
     *
     * <p>UUID v4 poskytuje 122 bitů entropie, dost pro kryptografické použití.
     *
     * @return náhodný UUID řetězec, např. {@code "550e8400-e29b-41d4-a716-446655440000"}
     */
    public String generateRefreshToken() {
        return UUID.randomUUID().toString();
    }

    /**
     * Vrací čas expirace nově vydaného refresh tokenu.
     *
     * <p>Volá se při ukládání refresh tokenu do databáze — ta potřebuje
     * přesný čas expirace pro ověřování.
     *
     * @return datum expirace nového refresh tokenu
     */
    public Date generateRefreshTokenExpiry() {
        return new Date(System.currentTimeMillis() + refreshExpiration);
    }

    /**
     * Ověří JWT access token proti danému uživateli.
     *
     * <p>Kontroluje:
     * <ol>
     *   <li>Jméno v tokenu odpovídá {@code userDetails.getUsername()}</li>
     *   <li>Token není expirovaný</li>
     * </ol>
     *
     * <p>Podpis tokenu se ověřuje automaticky při parsování
     * v {@link #extractAllClaims} — neplatný podpis vyhodí výjimku.
     *
     * @param token       JWT access token
     * @param userDetails uživatel, proti kterému se ověřuje
     * @return {@code true}, pokud je token pro daného uživatele platný
     */
    public boolean isTokenValid(String token, UserDetails userDetails) {
        final String username = extractUsername(token);
        return (username.equals(userDetails.getUsername())) && !isTokenExpired(token);
    }

    // =========================================================================
    // Privátní pomocné metody
    // =========================================================================

    private <T> T extractClaim(String token, Function<Claims, T> claimsResolver) {
        final Claims claims = extractAllClaims(token);
        return claimsResolver.apply(claims);
    }

    private Claims extractAllClaims(String token) {
        return Jwts.parser()
                .verifyWith(getSignInKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    private SecretKey getSignInKey() {
        byte[] keyBytes = Decoders.BASE64.decode(secretKey);
        return Keys.hmacShaKeyFor(keyBytes);
    }

    private boolean isTokenExpired(String token) {
        return extractExpiration(token).before(new Date());
    }

    private Date extractExpiration(String token) {
        return extractClaim(token, Claims::getExpiration);
    }
}
