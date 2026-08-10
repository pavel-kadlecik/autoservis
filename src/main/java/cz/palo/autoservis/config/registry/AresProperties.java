package cz.palo.autoservis.config.registry;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Konfigurace klienta API ARES (Administrativní registr ekonomických subjektů,
 * MF ČR). Váže se ze sekce {@code registry.ares} v application.yaml.
 * Na rozdíl od registru vozidel tu není API klíč — REST API ARES je veřejné
 * a neautentizované.
 *
 * @param baseUrl        base URL API, např. {@code https://ares.gov.cz/ekonomicke-subjekty-v-be/rest}
 * @param connectTimeout timeout TCP připojení
 * @param readTimeout    timeout čtení odpovědi — ARES se volá synchronně
 *                       z uživatelských requestů, proto ho drž krátký
 */
@ConfigurationProperties(prefix = "registry.ares")
public record AresProperties(
        String baseUrl,
        Duration connectTimeout,
        Duration readTimeout
) {
}
