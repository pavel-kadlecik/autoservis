package cz.palo.autoservis.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/**
 * Konfigurace importu příjemek (application.yaml, prefix warehouse.import):
 * defaulty dosazované do draftu se stavem DEFAULTED a uzavřený číselník
 * povolených měrných jednotek (Z-4).
 */
@Component
@ConfigurationProperties(prefix = "warehouse.import")
@Getter
@Setter
public class WarehouseImportProperties {

    /** Defaulty dosazované do draftu, když hodnota v dokladu chybí. */
    private Defaults defaults = new Defaults();

    /**
     * Uzavřený číselník povolených měrných jednotek. Vědomě BEZ DB CHECKu —
     * existující data mimo číselník dožijí; validuje/normalizuje se při vstupu
     * (confirm příjemky, create/update produktu), kde jde chyba srozumitelně vrátit.
     */
    private List<String> allowedUnits = List.of("ks", "l", "kg", "bal", "m", "sada", "pár");

    /** True, pokud jednotka (case-insensitive, s trimem) patří do číselníku. */
    public boolean isAllowedUnit(String unit) {
        return canonicalUnit(unit) != null;
    }

    /** Kanonická podoba jednotky z číselníku („KS" → „ks"), nebo null mimo číselník. */
    public String canonicalUnit(String unit) {
        if (unit == null) {
            return null;
        }
        String trimmed = unit.trim();
        return allowedUnits.stream()
                .filter(allowed -> allowed.equalsIgnoreCase(trimmed))
                .findFirst()
                .orElse(null);
    }

    /**
     * Hodnoty se stavem DEFAULTED — typicky dodací list bez rozpisu DPH.
     * Zdroj: application.yaml, prefix warehouse.import.defaults.
     */
    @Getter
    @Setter
    public static class Defaults {

        /** Výchozí sazba DPH v procentech (dodací list ji neuvádí). */
        private Integer vatRate = 21;

        /** Výchozí měna dokladu. */
        private String currency = "CZK";

        /** Výchozí měrná jednotka položky. */
        private String unit = "ks";

        /** Tolerance aritmetických kontrol (haléřové zaokrouhlení na dokladech). */
        private BigDecimal tolerance = new BigDecimal("0.05");
    }
}
