package cz.palo.autoservis.service;

import cz.palo.autoservis.mapper.ProductMatchingMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** Unit testy normalizace katalogových čísel (kaskáda je krytá integračně). */
class ProductMatchingServiceTest {

    private final ProductMatchingService service =
            new ProductMatchingService(mock(ProductMatchingMapper.class));

    @Test
    @DisplayName("brand prefix (2–4 písmena + mezera) vytvoří druhou variantu bez prefixu")
    void brandPrefixVariant() {
        assertThat(service.normalizedVariants("EL 871.180"))
                .containsExactly("EL871180", "871180");
        assertThat(service.normalizedVariants("VAG 02M301189G"))
                .containsExactly("VAG02M301189G", "02M301189G");
    }

    @Test
    @DisplayName("číslo bez prefixu má jedinou variantu")
    void noPrefixSingleVariant() {
        assertThat(service.normalizedVariants("SU001A3082"))
                .containsExactly("SU001A3082");
    }

    @Test
    @DisplayName("normalizace: velká písmena, bez mezer, teček a pomlček")
    void normalization() {
        assertThat(service.normalizedVariants("bs 220-005"))
                .containsExactly("BS220005", "220005");
    }

    @Test
    @DisplayName("katalogové číslo jen ze separátorů → prázdné varianty (guard proti IN (), E6.6/№11)")
    void onlySeparators_producesNoVariants() {
        assertThat(service.normalizedVariants("-")).isEmpty();
        assertThat(service.normalizedVariants(" . - ")).isEmpty();
    }
}
