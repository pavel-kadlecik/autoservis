package cz.palo.autoservis.config.security;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Konfigurace CORS (application.yaml, prefix {@code cors}).
 *
 * <p>Vědomě přes {@code @ConfigurationProperties}, ne {@code @Value}: originy jsou v YAML
 * <strong>seznam</strong> (block sequence) a {@code @Value("${cors.allowed-origins}")} YAML seznam
 * navázat neumí (viděl by prázdno → prohlížeč dostane „Invalid CORS request", regrese E7).
 * {@code @ConfigurationProperties} seznam naváže korektně — a stejně tak čárkou oddělený řetězec
 * z jedné env proměnné v produkci ({@code CORS_ALLOWED_ORIGINS=https://a,https://b}).
 */
@Component
@ConfigurationProperties(prefix = "cors")
@Getter
@Setter
public class CorsProperties {

    /** Originy, ze kterých smí prohlížeč volat API s cookies. Dev = Vite, prod = doména FE. */
    private List<String> allowedOrigins = new ArrayList<>();
}
