package cz.palo.autoservis.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("AmountInWords — částka slovy (čeština)")
class AmountInWordsTest {

    // Výstup je dohromady a s velkým počátečním písmenem — účetní konvence
    // pokladních dokladů proti vpisování mezi slova (viz javadoc utilu).
    @ParameterizedTest
    @CsvSource({
            "0,                Nula",
            "1,                Jedna",
            "2,                Dva",
            "9,                Devět",
            "10,               Deset",
            "11,               Jedenáct",
            "15,               Patnáct",
            "19,               Devatenáct",
            "20,               Dvacet",
            "21,               Dvacetjedna",
            "83,               Osmdesáttři",
            "100,              Sto",
            "101,              Stojedna",
            "200,              Dvěstě",
            "283,              Dvěstěosmdesáttři",
            "500,              Pětset",
            "999,              Devětsetdevadesátdevět",
            "1000,             Tisíc",
            "2000,             Dvatisíce",
            "5000,             Pěttisíc",
            "21000,            Dvacetjedentisíc",
            "22000,            Dvacetdvatisíce",
            "22083,            Dvacetdvatisíceosmdesáttři",
            "100000,           Stotisíc",
            "123456,           Stodvacettřitisícečtyřistapadesátšest",
            "1000000,          Milion",
            "2000000,          Dvamiliony",
            "5000000,          Pětmilionů",
            "1234567,          Miliondvěstětřicetčtyřitisícepětsetšedesátsedm"
    })
    @DisplayName("celé koruny")
    void celeKoruny(long value, String expected) {
        assertThat(AmountInWords.toWords(BigDecimal.valueOf(value))).isEqualTo(expected);
    }

    @Test
    @DisplayName("desetinná část s .00 se ignoruje")
    void bezHaleru() {
        assertThat(AmountInWords.toWords(new BigDecimal("22083.00"))).isEqualTo("Dvacetdvatisíceosmdesáttři");
    }

    @Test
    @DisplayName("nenulové haléře se doplní jako a XX/100")
    void sHaleri() {
        assertThat(AmountInWords.toWords(new BigDecimal("100.50"))).isEqualTo("Sto a 50/100");
    }

    @Test
    @DisplayName("haléře se zaokrouhlují HALF_UP")
    void zaokrouhleniHaleru() {
        assertThat(AmountInWords.toWords(new BigDecimal("100.005"))).isEqualTo("Sto a 01/100");
    }

    @Test
    @DisplayName("null, záporná a příliš velká částka jsou odmítnuty")
    void nevalidni() {
        assertThatThrownBy(() -> AmountInWords.toWords(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AmountInWords.toWords(new BigDecimal("-1")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AmountInWords.toWords(new BigDecimal("1000000000")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
