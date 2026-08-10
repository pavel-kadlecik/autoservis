package cz.palo.autoservis.config.registry;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Konfigurace klienta API dataovozidlech.cz (Datová kostka RSV).
 * Váže se ze sekce {@code registry.dataovozidlech} v application.yaml.
 *
 * @param baseUrl        base URL API, např. {@code https://api.dataovozidlech.cz}
 * @param apiKey         uživatelský API klíč posílaný v hlavičce {@code API_KEY} každého
 *                       requestu; získává se registrací na dataovozidlech.cz/registraceapi
 * @param connectTimeout timeout TCP připojení
 * @param readTimeout    timeout čtení odpovědi — registr se volá synchronně
 *                       z uživatelských requestů, proto ho drž krátký
 */
@ConfigurationProperties(prefix = "registry.dataovozidlech")
public record VehicleRegistryProperties(
        String baseUrl,
        String apiKey,
        Duration connectTimeout,
        Duration readTimeout
) {
}
