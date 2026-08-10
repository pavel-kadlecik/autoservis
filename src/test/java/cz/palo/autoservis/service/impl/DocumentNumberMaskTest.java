package cz.palo.autoservis.service.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Čistý unit test masky číselné řady faktur (V71) — parsování, skládání čísla,
 * regex řady a chybové hlášky. Bez Springu i DB (stejný přístup jako SpaydBuilderTest).
 */
class DocumentNumberMaskTest {

    private static final LocalDate SRPEN_2026 = LocalDate.of(2026, 8, 2);

    // =========================================================================
    // format — skládání čísla
    // =========================================================================

    @Test
    @DisplayName("výchozí maska {RRRR}{MM}{NNN} dává dnešní formát YYYYMM###")
    void format_defaultMaskMatchesLegacyFormat() {
        DocumentNumberMask mask = DocumentNumberMask.parse("{RRRR}{MM}{NNN}");

        assertThat(mask.format(SRPEN_2026, 1)).isEqualTo("202608001");
        assertThat(mask.format(SRPEN_2026, 42)).isEqualTo("202608042");
    }

    @Test
    @DisplayName("maska zákazníka {N}/{RR} dává „pořadí/rok“ bez doplňování nul")
    void format_customerMaskSequenceSlashYear() {
        DocumentNumberMask mask = DocumentNumberMask.parse("{N}/{RR}");

        assertThat(mask.format(SRPEN_2026, 17)).isEqualTo("17/26");
        assertThat(mask.format(LocalDate.of(2027, 1, 5), 1)).isEqualTo("1/27");
    }

    @Test
    @DisplayName("literály (prefix, pomlčky) se přenášejí beze změny")
    void format_keepsLiterals() {
        DocumentNumberMask mask = DocumentNumberMask.parse("FV-{RRRR}-{NNNN}");

        assertThat(mask.format(SRPEN_2026, 7)).isEqualTo("FV-2026-0007");
    }

    @Test
    @DisplayName("pořadí širší než šířka tokenu řadu neshodí — číslo se prodlouží")
    void format_sequenceOverflowWidensNumber() {
        DocumentNumberMask mask = DocumentNumberMask.parse("{RRRR}{MM}{NNN}");

        assertThat(mask.format(SRPEN_2026, 1000)).isEqualTo("2026081000");
    }

    // =========================================================================
    // regex + matches — příslušnost čísla k řadě a období
    // =========================================================================

    @Test
    @DisplayName("regex peče období do vzoru: jiné období ani cizí tvar nematchne")
    void matches_respectsPeriodAndShape() {
        DocumentNumberMask mask = DocumentNumberMask.parse("{N}/{RR}");

        assertThat(mask.matches("17/26", SRPEN_2026)).isTrue();
        assertThat(mask.matches("17/25", SRPEN_2026)).as("loňský rok").isFalse();
        assertThat(mask.matches("ABC-1", SRPEN_2026)).as("cizí tvar").isFalse();
        assertThat(mask.matches(null, SRPEN_2026)).isFalse();
    }

    @Test
    @DisplayName("lomítko v masce je literál, ne regexový metaznak — a tečka se escapuje")
    void regex_escapesLiterals() {
        DocumentNumberMask mask = DocumentNumberMask.parse("A.B{NN}");

        assertThat(mask.matches("A.B01", SRPEN_2026)).isTrue();
        assertThat(mask.matches("AxB01", SRPEN_2026)).as("tečka nesmí matchovat cokoliv").isFalse();
    }

    @Test
    @DisplayName("bez tokenu data je řada nekonečná — regex nezávisí na roce")
    void regex_withoutDateTokensIsPeriodless() {
        DocumentNumberMask mask = DocumentNumberMask.parse("FV{NNNN}");

        assertThat(mask.matches("FV0001", SRPEN_2026)).isTrue();
        assertThat(mask.matches("FV0001", LocalDate.of(2030, 1, 1))).isTrue();
    }

    @Test
    @DisplayName("regex je POSIX-kompatibilní: bez \\Q…\\E, sekvence max 15 číslic kvůli ::BIGINT")
    void regex_isPosixCompatible() {
        DocumentNumberMask mask = DocumentNumberMask.parse("F.V-{NN}/{RR}");
        String regex = mask.regex(SRPEN_2026);

        assertThat(regex).doesNotContain("\\Q").doesNotContain("\\E");
        assertThat(regex).isEqualTo("^F\\.V-([0-9]{2,15})/26$");
    }

    // =========================================================================
    // parse — validace masky
    // =========================================================================

    @Test
    @DisplayName("prázdná maska, chybějící a vícenásobná sekvence se odmítnou")
    void parse_rejectsMissingOrDuplicateSequence() {
        assertThatThrownBy(() -> DocumentNumberMask.parse("  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nesmí být prázdná");
        assertThatThrownBy(() -> DocumentNumberMask.parse("{RRRR}{MM}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("právě jeden token");
        assertThatThrownBy(() -> DocumentNumberMask.parse("{N}{NN}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("právě jeden token");
    }

    @Test
    @DisplayName("neznámý token a rozbité závorky mají srozumitelnou českou hlášku")
    void parse_rejectsUnknownTokenAndBrokenBraces() {
        assertThatThrownBy(() -> DocumentNumberMask.parse("{YYYY}{N}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Neznámý token {YYYY}");
        assertThatThrownBy(() -> DocumentNumberMask.parse("{RRRR{N}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Neznámý token");
        assertThatThrownBy(() -> DocumentNumberMask.parse("{N"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("neuzavřenou závorku");
        assertThatThrownBy(() -> DocumentNumberMask.parse("N}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bez otevírací závorky");
    }

    @Test
    @DisplayName("maska, jejíž číslo by přesáhlo 20 znaků, se odmítne už při uložení")
    void parse_rejectsTooLongResult() {
        assertThatThrownBy(() -> DocumentNumberMask.parse("FAKTURA-CISLO-{RRRR}-{NNNN}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maximum je 20");
    }

    @Test
    @DisplayName("maska delší než 40 znaků se odmítne (limit sloupce v DB)")
    void parse_rejectsTooLongMask() {
        assertThatThrownBy(() -> DocumentNumberMask.parse("X".repeat(41)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maximálně 40 znaků");
    }

    @Test
    @DisplayName("maska se před parsováním trimuje a source() vrací trimovaný zápis")
    void parse_trimsSource() {
        DocumentNumberMask mask = DocumentNumberMask.parse("  {N}/{RR}  ");

        assertThat(mask.source()).isEqualTo("{N}/{RR}");
        assertThat(mask.format(SRPEN_2026, 3)).isEqualTo("3/26");
    }
}
