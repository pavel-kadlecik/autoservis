package cz.palo.autoservis.security.controller;

import cz.palo.autoservis.security.model.domain.AppUserDetails;
import cz.palo.autoservis.security.model.dto.*;
import cz.palo.autoservis.security.service.AuthenticationService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;

/**
 * REST controller autentizace — přihlášení, obnova tokenů a odhlášení.
 *
 * <p>Base path: {@code /api/{version}/auth} — v praxi záměrně bez pevné verze.
 * Auth endpointy jsou infrastrukturní kontrakt sdílený webovým frontendem
 * i případnými budoucími mobilními klienty. Verze auth API se mění nezávisle
 * na business API.
 *
 * <p>Tokeny se přenášejí výhradně v HTTP-only cookies, nikdy v tělech odpovědí
 * ani v {@code Authorization} hlavičkách. To brání krádeži tokenu přes XSS.
 *
 * <h3>Strategie cookies:</h3>
 * <ul>
 *   <li>{@code jwt} — access token, omezený na {@code /api}, krátkodobý</li>
 *   <li>{@code jwt_refresh} — refresh token, omezený jen na {@code /api/.../auth/refresh}</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/{version}/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthenticationService service;

    @Value("${jwt.expiration}")
    private long jwtExpirationMs;

    @Value("${jwt.refresh-expiration}")
    private long refreshExpirationMs;

    @Value("${jwt.cookie-secure}")
    private boolean cookieSecure;

    /**
     * Autentizuje uživatele a nastaví HTTP-only cookies s tokeny.
     *
     * @param request  přihlašovací údaje (jméno, heslo)
     * @param response HTTP odpověď pro nastavení cookies
     * @return 200 OK s prázdným tělem; tokeny jsou v cookies
     */
    @PostMapping("/login")
    public ResponseEntity<Void> login(
            @RequestBody @Valid LoginRequest request,
            HttpServletResponse response,
            @PathVariable String version) {
        TokenResponse tokens = service.login(request);
        setTokenCookies(response, tokens, version);
        return ResponseEntity.ok().build();
    }

    /**
     * Vydá nový pár tokenů na základě platné refresh token cookie.
     *
     * <p>Refresh token se čte z cookie {@code jwt_refresh}. Při úspěchu se obě
     * cookies nahradí čerstvými tokeny (rotace refresh tokenu).
     *
     * @param request  HTTP požadavek pro čtení refresh token cookie
     * @param response HTTP odpověď pro nastavení nových cookies
     * @return 200 OK s prázdným tělem; nové tokeny jsou v cookies,
     *         nebo 400 Bad Request, když refresh token cookie chybí
     */
    @PostMapping("/refresh")
    public ResponseEntity<Void> refresh(
            HttpServletRequest request,
            HttpServletResponse response,
            @PathVariable String version) {
        String refreshToken = extractCookie(request, "jwt_refresh");
        if (refreshToken == null) {
            return ResponseEntity.badRequest().build();
        }
        TokenResponse tokens = service.refresh(new RefreshRequest(refreshToken));
        setTokenCookies(response, tokens, version);
        return ResponseEntity.ok().build();
    }

    /**
     * Odhlásí aktuálního uživatele — odvolá tokeny a smaže cookies.
     *
     * <p>Access token se přidá na blacklist (zůstává neplatný až do své přirozené
     * expirace). Refresh token se odvolá v databázi.
     *
     * @param request  HTTP požadavek pro čtení token cookies
     * @param response HTTP odpověď pro smazání cookies
     * @return 204 No Content při úspěchu, nebo 400 Bad Request, když access token cookie chybí
     */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            HttpServletRequest request,
            HttpServletResponse response,
            @PathVariable String version) {
        String accessToken = extractCookie(request, "jwt");
        String refreshToken = extractCookie(request, "jwt_refresh");
        if (accessToken == null) {
            return ResponseEntity.badRequest().build();
        }
        service.logout(accessToken, refreshToken);
        deleteTokenCookies(response, version);
        return ResponseEntity.noContent().build();
    }

    /**
     * Vrací základní informace o právě přihlášeném uživateli.
     *
     * <p>Frontend jím ověřuje, že session cookie stále platí. Když cookie chybí
     * nebo expirovala, Spring Security automaticky vrací 401.
     *
     * @param userDetails přihlášený uživatel injektovaný Spring Security
     * @return 200 OK s ID uživatele, jménem, e-mailem a rolemi
     */
    @GetMapping("/me")
    public ResponseEntity<MeResponse> me(
            @AuthenticationPrincipal AppUserDetails userDetails) {
        return ResponseEntity.ok(new MeResponse(
                userDetails.getUserId(),
                userDetails.getUsername(),
                userDetails.getEmail(),
                userDetails.getAuthorities().stream()
                        .map(GrantedAuthority::getAuthority)
                        .toList()
        ));
    }

    /**
     * Změní heslo právě přihlášeného uživatele (self-service).
     *
     * <p>Vyžaduje ověření aktuálním heslem, na rozdíl od adminem spouštěného
     * resetu v {@code UserController}. Viz {@link AuthenticationService#changePassword}.
     *
     * @param request     aktuální a nové heslo
     * @param userDetails přihlášený uživatel
     * @return 204 No Content při úspěchu
     */
    @PostMapping("/change-password")
    public ResponseEntity<Void> changePassword(
            @RequestBody @Valid ChangePasswordRequest request,
            @AuthenticationPrincipal AppUserDetails userDetails) {
        service.changePassword(userDetails.getUserId(), request);
        return ResponseEntity.noContent().build();
    }

    // =========================================================================
    // Privátní pomocné metody
    // =========================================================================

    /**
     * Nastaví obě token cookies na HTTP odpovědi.
     * Access token cookie je omezená na {@code /api};
     * refresh token cookie jen na refresh endpoint.
     *
     * <p>{@code maxAge} a {@code secure} se odvozují z konfigurace
     * ({@code jwt.expiration}/{@code jwt.refresh-expiration}/{@code jwt.cookie-secure})
     * místo hardcodování, takže životnost cookie vždy odpovídá životnosti tokenu.
     *
     * @param response HTTP odpověď
     * @param tokens   pár tokenů k nastavení do cookies
     * @param version  segment verze API z cesty requestu; omezuje cestu refresh cookie
     */
    private void setTokenCookies(HttpServletResponse response, TokenResponse tokens, String version) {
        ResponseCookie accessCookie = ResponseCookie.from("jwt", tokens.accessToken())
                .httpOnly(true)
                .secure(cookieSecure)
                .sameSite("Strict")
                .path("/api")
                .maxAge(Duration.ofMillis(jwtExpirationMs))
                .build();

        ResponseCookie refreshCookie = ResponseCookie.from("jwt_refresh", tokens.refreshToken())
                .httpOnly(true)
                .secure(cookieSecure)
                .sameSite("Strict")
                .path("/api/" + version + "/auth/refresh")
                .maxAge(Duration.ofMillis(refreshExpirationMs))
                .build();

        response.addHeader(HttpHeaders.SET_COOKIE, accessCookie.toString());
        response.addHeader(HttpHeaders.SET_COOKIE, refreshCookie.toString());
    }

    /**
     * Smaže obě token cookies nastavením jejich {@code maxAge} na nulu.
     *
     * @param response HTTP odpověď
     * @param version  segment verze API z cesty requestu; omezuje cestu refresh cookie
     */
    private void deleteTokenCookies(HttpServletResponse response, String version) {
        ResponseCookie deleteAccess = ResponseCookie.from("jwt", "")
                .httpOnly(true)
                .secure(cookieSecure)
                .sameSite("Strict")
                .path("/api")
                .maxAge(0)
                .build();

        ResponseCookie deleteRefresh = ResponseCookie.from("jwt_refresh", "")
                .httpOnly(true)
                .secure(cookieSecure)
                .sameSite("Strict")
                .path("/api/" + version + "/auth/refresh")
                .maxAge(0)
                .build();

        response.addHeader(HttpHeaders.SET_COOKIE, deleteAccess.toString());
        response.addHeader(HttpHeaders.SET_COOKIE, deleteRefresh.toString());
    }

    /**
     * Najde cookie podle názvu v příchozím requestu.
     *
     * @param request HTTP požadavek
     * @param name    hledaný název cookie
     * @return hodnota cookie, nebo {@code null}, když chybí
     */
    private String extractCookie(HttpServletRequest request, String name) {
        if (request.getCookies() == null) return null;
        for (Cookie cookie : request.getCookies()) {
            if (name.equals(cookie.getName())) return cookie.getValue();
        }
        return null;
    }
}
