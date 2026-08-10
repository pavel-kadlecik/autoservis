package cz.palo.autoservis.web;

import cz.palo.autoservis.AbstractIntegrationTest;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * E2e průchod <strong>JWT filtrem a cookie flow</strong> (audit K-15 / V2).
 *
 * <p>Ostatní web testy autentizují přes {@code .with(user(...))}, což JWT filtr obchází —
 * reálná cesta „cookie {@code jwt} → SecurityContext → blacklist" tak nebyla nijak pokrytá.
 * Tenhle test jde plným řetězcem přes skutečné cookies: přihlásí se, vytáhne access cookie
 * ze {@code Set-Cookie}, zopakuje ji na chráněný endpoint, odhlásí se a ověří, že blacklistnutá
 * i podvržená cookie skončí 401 ve tvaru RFC 9457 se správným kódem.
 *
 * <p>Seed (V3): účet {@code manager} / {@code Password1!} (role MANAGER → projde přes
 * {@code hasAnyRole} na {@code /api/**}, E0.8).
 */
@AutoConfigureMockMvc
@Transactional
class JwtAuthFlowTest extends AbstractIntegrationTest {

    private static final String LOGIN_BODY = "{\"username\":\"manager\",\"password\":\"Password1!\"}";
    private static final String PROTECTED = "/api/v1/customers";
    private static final long MANAGER_ID = 2L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private cz.palo.autoservis.security.mapper.UserMapper userMapper;

    @Test
    @DisplayName("login → cookie jwt → chráněný GET 200 → logout → táž cookie 401 TOKEN_BLACKLISTED")
    void cookieFlow_loginThenBlacklistedAfterLogout() throws Exception {
        MvcResult login = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(APPLICATION_JSON).content(LOGIN_BODY))
                .andExpect(status().isOk())
                .andReturn();

        String setCookie = accessSetCookieHeader(login);
        assertThat(setCookie).as("access cookie je HttpOnly").contains("HttpOnly");
        Cookie jwt = new Cookie("jwt", cookieValue(setCookie));
        assertThat(jwt.getValue()).as("hodnota je JWT").matches("[^.]+\\.[^.]+\\.[^.]+");

        // Cookie projde filtrem na chráněný endpoint (bez .with(user(...)), tj. reálná cesta).
        mockMvc.perform(get(PROTECTED).cookie(jwt))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/auth/logout").cookie(jwt))
                .andExpect(status().isNoContent());

        // Po odhlášení je access token na blacklistu → 401 s kódem TOKEN_BLACKLISTED.
        mockMvc.perform(get(PROTECTED).cookie(jwt))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errors[0].code").value("TOKEN_BLACKLISTED"));
    }

    @Test
    @DisplayName("podvržená (neparsovatelná) cookie jwt → 401 TOKEN_INVALID")
    void cookieFlow_garbageToken_isUnauthorized() throws Exception {
        mockMvc.perform(get(PROTECTED).cookie(new Cookie("jwt", "tohle-neni-platny-jwt")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errors[0].code").value("TOKEN_INVALID"));
    }

    @Test
    @DisplayName("bez cookie → 401 (nepřihlášený)")
    void protectedEndpoint_withoutCookie_isUnauthorized() throws Exception {
        mockMvc.perform(get(PROTECTED))
                .andExpect(status().isUnauthorized());
    }

    // =========================================================================
    // Deaktivovaný účet s živou cookie (audit KN-18)
    // =========================================================================

    @Test
    @DisplayName("deaktivovaný uživatel s živou cookie → 401 ACCOUNT_UNAVAILABLE, ne 500 (KN-18)")
    void deactivatedUser_withLiveCookie_isUnauthorizedNotServerError() throws Exception {
        MvcResult login = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(APPLICATION_JSON).content(LOGIN_BODY))
                .andExpect(status().isOk())
                .andReturn();
        Cookie jwt = new Cookie("jwt", cookieValue(accessSetCookieHeader(login)));

        // Kontrola, že cookie PŘED deaktivací funguje — jinak by test mohl projít i z úplně
        // jiného důvodu (třeba že cookie byla od začátku neplatná).
        mockMvc.perform(get(PROTECTED).cookie(jwt)).andExpect(status().isOk());

        deactivateManager();

        // Cesta přes FILTR: loadUserByUsername už uživatele nenajde (findByUsername filtruje
        // enabled = TRUE). Před opravou výjimka vyletěla z filtru — tedy před
        // @RestControllerAdvice — a každý požadavek skončil 500 se stack trace v logu.
        mockMvc.perform(get(PROTECTED).cookie(jwt))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errors[0].code").value("ACCOUNT_UNAVAILABLE"));
    }

    @Test
    @DisplayName("deaktivovaný uživatel na /auth/refresh → 401 ACCOUNT_UNAVAILABLE, ne 500 (KN-18)")
    void deactivatedUser_onRefresh_isUnauthorizedNotServerError() throws Exception {
        MvcResult login = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(APPLICATION_JSON).content(LOGIN_BODY))
                .andExpect(status().isOk())
                .andReturn();
        String refreshHeader = login.getResponse().getHeaders("Set-Cookie").stream()
                .filter(h -> h.startsWith("jwt_refresh="))
                .findFirst()
                .orElseThrow(() -> new AssertionError("chybí Set-Cookie pro jwt_refresh"));
        int start = "jwt_refresh=".length();
        int end = refreshHeader.indexOf(';');
        Cookie refresh = new Cookie("jwt_refresh",
                end > 0 ? refreshHeader.substring(start, end) : refreshHeader.substring(start));

        deactivateManager();

        // Cesta přes HANDLER, ne filtr: /auth/refresh je v shouldNotFilter, takže výjimka
        // vzniká až v service a musí ji zachytit GlobalExceptionHandler. Na tomhle endpointu
        // to bolelo nejvíc — frontend ho po každé 401 automaticky zkouší, takže místo
        // odhlášení dostal 500.
        mockMvc.perform(post("/api/v1/auth/refresh").cookie(refresh))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errors[0].code").value("ACCOUNT_UNAVAILABLE"));
    }

    /**
     * Deaktivuje seedového manažera <strong>přes mapper</strong>, tedy stejnou cestou, jakou to
     * dělá {@code UserService.deactivate} v provozu. Zápis se odroluje s testovací transakcí.
     *
     * <p><strong>Nepoužívat tu JdbcTemplate.</strong> MyBatis má zapnutou first-level cache
     * (per {@code SqlSession}, výchozí {@code localCacheScope=SESSION}) a v {@code @Transactional}
     * testu je session sdílená pro celý test. Předchozí {@code findByUsername} si výsledek uloží
     * a UPDATE mimo MyBatis ho neproplachne — následný lookup pak vrátí uživatele
     * s {@code enabled = true} a test tiše ověřuje cache místo databáze. Update přes mapper
     * lokální cache vyčistí ({@code BaseExecutor.update} → {@code clearLocalCache}).
     *
     * <p>Pořadí je důležité: volat až <strong>po</strong> přihlášení. {@code LoginAttemptService}
     * zapisuje do téhož řádku v {@code REQUIRES_NEW} transakci, a kdyby ho testovací transakce
     * držela zamčený, přihlášení by čekalo na zámek až do timeoutu.
     */
    private void deactivateManager() {
        userMapper.deactivate(MANAGER_ID);
    }

    /** Vytáhne řetězec Set-Cookie hlavičky přístupové cookie `jwt` (ne `jwt_refresh`). */
    private static String accessSetCookieHeader(MvcResult result) {
        return result.getResponse().getHeaders("Set-Cookie").stream()
                .filter(h -> h.startsWith("jwt="))
                .findFirst()
                .orElseThrow(() -> new AssertionError("chybí Set-Cookie pro jwt"));
    }

    /** Hodnota cookie z hlavičky `jwt=<value>; Path=…; HttpOnly; …`. */
    private static String cookieValue(String setCookieHeader) {
        int start = "jwt=".length();
        int end = setCookieHeader.indexOf(';');
        return end > 0 ? setCookieHeader.substring(start, end) : setCookieHeader.substring(start);
    }
}
