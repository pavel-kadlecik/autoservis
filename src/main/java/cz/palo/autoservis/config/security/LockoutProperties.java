package cz.palo.autoservis.config.security;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Konfigurace uzamykání účtu po neúspěšných přihlášeních (application.yaml, prefix {@code lockout}).
 *
 * <p>Vzniklo z auditu 2026-07-30, nález KN-5: zámek byl do V64 <strong>trvalý</strong> a odemknout
 * ho umělo jen ADMIN-only resetování hesla. Produkční seed má jediný účet {@code admin}, takže
 * kdokoli z internetu ho deseti požadavky na veřejný {@code /auth/login} natrvalo vyřadil.
 *
 * <p>Lhůta je provozní nastavení, ne vlastnost schématu — proto v konfiguraci, ne v DB. Přes
 * {@code @ConfigurationProperties} (ne {@code @Value}) kvůli konzistenci s {@link CorsProperties}
 * a proto, že {@link Duration} se takto naváže i ze zápisu {@code 15m} nebo z env proměnné.
 */
@Component
@ConfigurationProperties(prefix = "lockout")
@Getter
@Setter
public class LockoutProperties {

    /**
     * Jak dlouho zámek platí, než ho první další pokus o přihlášení uvolní.
     * Výchozí 15 minut — kompromis mezi obtěžováním obsluhy a brzděním hádání hesla
     * (rozhodnutí uživatele 2026-07-30).
     */
    private Duration duration = Duration.ofMinutes(15);
}
