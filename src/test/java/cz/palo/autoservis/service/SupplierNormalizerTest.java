package cz.palo.autoservis.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Normalizace registračního čísla dodavatele (IČO) — čistý unit test bez Spring kontextu.
 *
 * <p>Na normalizaci závisí párování dodavatele při importu (lookup dle IČO) a idempotence
 * importu. Když normalizace pustí mezeru, „123 456 78" a „12345678" jsou dva různí dodavatelé
 * a duplicitní doklad projde. Proto se testuje i <strong>nezlomitelná mezera</strong> (U+00A0),
 * kterou PDF extrakce běžně vrací.
 */
class SupplierNormalizerTest {

    /**
     * NBSP se skládá z kódu znaku záměrně, ne doslovným znakem ve zdrojáku: v editoru je
     * k nerozeznání od obyčejné mezery a test by se tiše zvrhl v kopii testu s běžnou
     * mezerou, aniž by cokoli navíc dokazoval.
     */
    private static final String NBSP = String.valueOf((char) 0x00A0);

    private final SupplierNormalizer normalizer = new SupplierNormalizer();

    @Test
    @DisplayName("běžné mezery se odstraní")
    void normalize_removesRegularSpaces() {
        assertThat(normalizer.normalizeRegistrationNumber("123 456 78")).isEqualTo("12345678");
    }

    @Test
    @DisplayName("nezlomitelná mezera (U+00A0) se odstraní — PDF ji běžně obsahuje")
    void normalize_removesNonBreakingSpace() {
        String withNbsp = "123" + NBSP + "456" + NBSP + "78";
        // pojistka, že fixtura je opravdu jiná než varianta s běžnou mezerou
        assertThat(withNbsp).isNotEqualTo("123 456 78");

        assertThat(normalizer.normalizeRegistrationNumber(withNbsp)).isEqualTo("12345678");
    }

    @Test
    @DisplayName("řetězec ze samých nezlomitelných mezer → null")
    void normalize_nonBreakingSpacesOnly_returnsNull() {
        assertThat(normalizer.normalizeRegistrationNumber(NBSP + NBSP)).isNull();
    }

    @Test
    @DisplayName("tabulátory a konce řádků se odstraní")
    void normalize_removesTabsAndNewlines() {
        assertThat(normalizer.normalizeRegistrationNumber("12\t345\n678")).isEqualTo("12345678");
    }

    @Test
    @DisplayName("okrajové mezery se odstraní")
    void normalize_trimsSurroundingWhitespace() {
        assertThat(normalizer.normalizeRegistrationNumber("  12345678  ")).isEqualTo("12345678");
    }

    @Test
    @DisplayName("prefix CZ/SK se NEstrhává — je součástí identifikátoru")
    void normalize_keepsCountryPrefix() {
        assertThat(normalizer.normalizeRegistrationNumber("CZ 12345678")).isEqualTo("CZ12345678");
        assertThat(normalizer.normalizeRegistrationNumber("SK2020123456")).isEqualTo("SK2020123456");
    }

    @Test
    @DisplayName("hodnota bez mezer projde beze změny")
    void normalize_leavesCleanValueUntouched() {
        assertThat(normalizer.normalizeRegistrationNumber("12345678")).isEqualTo("12345678");
    }

    @Test
    @DisplayName("řetězec ze samých mezer → null (ne prázdný řetězec)")
    void normalize_whitespaceOnly_returnsNull() {
        assertThat(normalizer.normalizeRegistrationNumber("   ")).isNull();
        assertThat(normalizer.normalizeRegistrationNumber(" ")).isNull();
    }

    @Test
    @DisplayName("prázdný řetězec → null")
    void normalize_emptyString_returnsNull() {
        assertThat(normalizer.normalizeRegistrationNumber("")).isNull();
    }

    @Test
    @DisplayName("null → null (nesmí spadnout)")
    void normalize_null_returnsNull() {
        assertThat(normalizer.normalizeRegistrationNumber(null)).isNull();
    }
}
