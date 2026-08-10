package cz.palo.autoservis.config.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Vazba CORS originů z reálného {@code application.yaml} — bez Springového kontextu a bez Dockeru
 * (čistý unit test, běží v milisekundách). Dokládá <strong>proč</strong> byla oprava CORS nutná
 * (regrese E7): originy jsou v YAML block-sequence seznamu.
 *
 * <ul>
 *   <li>{@code @ConfigurationProperties} (Binder) seznam <strong>naváže</strong> → {@link CorsProperties}
 *       nese oba originy. To je zvolené řešení (varianta B).</li>
 *   <li>Plochá property {@code cors.allowed-origins} — kterou čte {@code @Value} — je
 *       <strong>{@code null}</strong>, protože block-sequence se ukládá jako indexované klíče
 *       {@code cors.allowed-origins[0..n]}. Proto E7 s {@code @Value} dostal prázdno a login spadl.</li>
 * </ul>
 */
class CorsPropertiesBindingTest {

    private StandardEnvironment environmentFromApplicationYaml() throws Exception {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load("application.yaml", new ClassPathResource("application.yaml"));
        StandardEnvironment env = new StandardEnvironment();
        sources.forEach(s -> env.getPropertySources().addLast(s));
        return env;
    }

    @Test
    @DisplayName("@ConfigurationProperties naváže YAML seznam originů (varianta B funguje)")
    void configurationProperties_bindsYamlList() throws Exception {
        CorsProperties props = Binder.get(environmentFromApplicationYaml())
                .bind("cors", CorsProperties.class)
                .orElseGet(CorsProperties::new);

        assertThat(props.getAllowedOrigins())
                .containsExactly("http://localhost:5173", "http://127.0.0.1:5173");
    }

    @Test
    @DisplayName("plochá property cors.allowed-origins je null — proto @Value dostal prázdno (regrese E7)")
    void flatProperty_asSeenByAtValue_isNull() throws Exception {
        // Přesně to, co dělá @Value("${cors.allowed-origins}") — čte plochou property z Environmentu.
        String flat = environmentFromApplicationYaml().getProperty("cors.allowed-origins");
        assertThat(flat)
                .as("block-sequence seznam nemá plochou property; @Value ji nenajde")
                .isNull();
    }
}
