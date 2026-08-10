package cz.palo.autoservis.web;

import cz.palo.autoservis.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CORS z konfigurace (regrese E7). Přihlášení se rozbilo, protože originy se v E7 přesunuly do
 * {@code cors.allowed-origins} vázané přes {@code @Value List<String>}, jenže dev/test profil má
 * v {@code application.yaml} YAML <em>seznam</em> — a {@code @Value} YAML seznam navázat neumí
 * (jen čárkou oddělený řetězec). Výsledek: prázdné originy → prohlížeč z Vite (:5173) dostal
 * „Invalid CORS request".
 *
 * <p>Stávající web testy to minuly, protože MockMvc bez hlavičky {@code Origin} CORS vůbec
 * nevyhodnocuje (server to bere jako same-origin). Tenhle test posílá <strong>skutečný
 * cross-origin preflight</strong> s {@code Origin} — přesně to, co dělá prohlížeč před loginem.
 */
@AutoConfigureMockMvc
class CorsConfigTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("preflight z povoleného originu (Vite) → 200 + Access-Control-Allow-Origin + Allow-Credentials")
    void preflight_fromViteOrigin_isAllowed() throws Exception {
        mockMvc.perform(options("/api/v1/auth/login")
                        .header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"))
                .andExpect(header().string("Access-Control-Allow-Credentials", "true"));
    }

    @Test
    @DisplayName("preflight z neznámého originu → 403 (Invalid CORS request)")
    void preflight_fromUnknownOrigin_isRejected() throws Exception {
        mockMvc.perform(options("/api/v1/auth/login")
                        .header("Origin", "http://evil.example")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isForbidden());
    }
}
