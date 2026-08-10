package cz.palo.autoservis.service;

import cz.palo.autoservis.config.WarehouseImportProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Číselník měrných jednotek (Z-4) — čistá logika bez Springu/DB.
 * {@code isAllowedUnit} je case-insensitive a s trimem; {@code canonicalUnit}
 * vrací kanonickou podobu z číselníku, nebo null mimo něj.
 */
class WarehouseImportPropertiesTest {

    private final WarehouseImportProperties props = new WarehouseImportProperties();

    @Test
    @DisplayName("výchozí defaulty zůstávají po přesunu do vnořené třídy Defaults")
    void defaultsPreserved() {
        assertThat(props.getDefaults().getVatRate()).isEqualTo(21);
        assertThat(props.getDefaults().getCurrency()).isEqualTo("CZK");
        assertThat(props.getDefaults().getUnit()).isEqualTo("ks");
        assertThat(props.getDefaults().getTolerance()).isEqualByComparingTo("0.05");
    }

    @Test
    @DisplayName("jednotka z číselníku je povolená bez ohledu na velikost písmen a mezery")
    void allowedUnitCaseAndTrimInsensitive() {
        assertThat(props.isAllowedUnit("ks")).isTrue();
        assertThat(props.isAllowedUnit("KS")).isTrue();
        assertThat(props.isAllowedUnit("  ks  ")).isTrue();
        assertThat(props.isAllowedUnit("Ks")).isTrue();
    }

    @Test
    @DisplayName("jednotka mimo číselník i null nejsou povolené")
    void unitOutsideCatalogueRejected() {
        assertThat(props.isAllowedUnit("krabice")).isFalse();
        assertThat(props.isAllowedUnit("kus")).isFalse();
        assertThat(props.isAllowedUnit("")).isFalse();
        assertThat(props.isAllowedUnit(null)).isFalse();
    }

    @Test
    @DisplayName("canonicalUnit vrací kanonickou podobu z číselníku, jinak null")
    void canonicalUnitNormalizes() {
        assertThat(props.canonicalUnit("KS")).isEqualTo("ks");
        assertThat(props.canonicalUnit(" Bal ")).isEqualTo("bal");
        assertThat(props.canonicalUnit("PÁR")).isEqualTo("pár");
        assertThat(props.canonicalUnit("krabice")).isNull();
        assertThat(props.canonicalUnit(null)).isNull();
    }
}
