package cz.palo.autoservis.security.filter;

import cz.palo.autoservis.security.mapper.BlacklistMapper;
import cz.palo.autoservis.security.service.AppUserDetailsService;
import cz.palo.autoservis.security.service.JwtService;
import cz.palo.autoservis.security.service.TokenHasher;
import io.jsonwebtoken.ExpiredJwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.regex.Pattern;

/**
 * JWT autentizační filtr — běží jednou na request.
 *
 * <p>Čte access token z HTTP-only cookie {@code jwt}, validuje ho a naplní
 * Spring Security kontext. Requesty na veřejné auth endpointy přeskakuje úplně.
 *
 * <p>Pořadí validace tokenu:
 * <ol>
 *   <li>Vytáhnout token z cookie — chybějící cookie znamená neautentizovaný request, pustit dál</li>
 *   <li>Zkontrolovat blacklist — tokeny zneplatněné odhlášením se odmítají s 401. Hledá se podle
 *       SHA-256 hashe tokenu ({@link TokenHasher}), stejně jak ho ukládá
 *       {@code AuthenticationService.logout} (V4, analyza-2026-07) — surový JWT se nikdy neperzistuje.</li>
 *   <li>Parsovat JWT — expirované či poškozené tokeny se odmítají s 401</li>
 *   <li>Ověřit token proti načtenému uživateli — nastavit autentizaci do security kontextu</li>
 * </ol>
 */
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final AppUserDetailsService userDetailsService;
    private final BlacklistMapper blacklistMapper;
    private final SecurityProblemWriter securityProblemWriter;

    /**
     * Přesná shoda s veřejnými auth endpointy — {@code /api/{version}/auth/login}
     * a {@code /api/{version}/auth/refresh}. Registrace byla odstraněna
     * (A2, analyza-2026-07); žádná jiná cesta {@code /auth/**} veřejná není.
     */
    private static final Pattern PUBLIC_AUTH = Pattern.compile("^/api/[^/]+/auth/(login|refresh)$");

    /**
     * Přeskakuje filtrování pro veřejné autentizační endpointy.
     * Tyto cesty platný access token nevyžadují.
     *
     * @param request příchozí HTTP požadavek
     * @return {@code true}, pokud má request tento filtr obejít
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return PUBLIC_AUTH.matcher(request.getServletPath()).matches();
    }

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain
    ) throws ServletException, IOException {

        final String jwt = extractTokenFromCookie(request);

        if (jwt == null) {
            filterChain.doFilter(request, response);
            return;
        }

        if (blacklistMapper.isBlacklisted(TokenHasher.sha256Hex(jwt))) {
            securityProblemWriter.writeUnauthorized(request, response, "TOKEN_BLACKLISTED", "Token byl zneplatněn.");
            return;
        }

        final String username;
        try {
            username = jwtService.extractUsername(jwt);
        } catch (ExpiredJwtException e) {
            securityProblemWriter.writeUnauthorized(request, response, "TOKEN_EXPIRED", "Platnost tokenu vypršela.");
            return;
        } catch (Exception e) {
            securityProblemWriter.writeUnauthorized(request, response, "TOKEN_INVALID", "Neplatný token.");
            return;
        }

        if (username != null && SecurityContextHolder.getContext().getAuthentication() == null) {
            final UserDetails userDetails;
            try {
                userDetails = userDetailsService.loadUserByUsername(username);
            } catch (UsernameNotFoundException e) {
                // Deaktivovaný (nebo smazaný) uživatel s ještě platnou cookie. `findByUsername`
                // filtruje `enabled = TRUE`, takže tady skončí každý požadavek takového účtu.
                // Bez tohohle catch vyletí výjimka z filtru — tedy PŘED ExceptionTranslationFilter
                // i @RestControllerAdvice, které ji nemají kdo zachytit → 500 na každý požadavek
                // až do vypršení tokenu, s ERROR stack trace v logu (audit KN-18).
                securityProblemWriter.writeUnauthorized(request, response,
                        "ACCOUNT_UNAVAILABLE", "Účet už není dostupný. Přihlaste se prosím znovu.");
                return;
            }
            if (jwtService.isTokenValid(jwt, userDetails)) {
                UsernamePasswordAuthenticationToken authToken = new UsernamePasswordAuthenticationToken(
                        userDetails,
                        null,
                        userDetails.getAuthorities()
                );
                authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(authToken);
            }
        }

        filterChain.doFilter(request, response);
    }

    /**
     * Vytáhne JWT access token z cookie {@code jwt}.
     *
     * @param request příchozí HTTP požadavek
     * @return hodnota tokenu, nebo {@code null}, když cookie chybí
     */
    private String extractTokenFromCookie(HttpServletRequest request) {
        if (request.getCookies() == null) return null;
        for (Cookie cookie : request.getCookies()) {
            if ("jwt".equals(cookie.getName())) return cookie.getValue();
        }
        return null;
    }
}
