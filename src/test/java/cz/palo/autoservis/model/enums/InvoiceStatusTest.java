package cz.palo.autoservis.model.enums;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.EnumSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Stavový automat faktury ({@code InvoiceStatus}) — čistý unit test bez Spring kontextu.
 *
 * <p>Automat je jediné místo, kde je definováno, co je povolený přechod. Proto se testuje
 * <strong>celá matice 4×4</strong>, ne jen šťastná cesta: u každé dvojice se tvrdí konkrétní
 * očekávaná hodnota. Test, který by ověřoval jen povolené přechody, by prošel i implementaci,
 * která povoluje všechno — proto jsou zakázané přechody vyjmenované stejně pečlivě.
 */
class InvoiceStatusTest {

    /** Jediný zdroj pravdy testu — úmyslně vypsaný ručně, ne odvozený z produkčního kódu. */
    // Koncept se od 2026-08-02 nestornuje, ale MAŽE — do CANCELLED tedy nevede žádný přechod
    // a hodnota zůstává v enumu jen kvůli historickým řádkům.
    private static final Set<InvoiceStatus> FROM_DRAFT = EnumSet.of(InvoiceStatus.ISSUED);
    // ISSUED → CANCELLED zamčeno (KN-1): vystavený doklad se opravuje dobropisem, ne stornem.
    private static final Set<InvoiceStatus> FROM_ISSUED = EnumSet.of(InvoiceStatus.PAID);

    @ParameterizedTest(name = "{0} → {1} je povolený přechod")
    @CsvSource({
            "DRAFT,  ISSUED",
            "ISSUED, PAID"
    })
    @DisplayName("povolené přechody projdou")
    void allowedTransitions_areAllowed(InvoiceStatus from, InvoiceStatus to) {
        assertThat(from.canTransitionTo(to))
                .as("%s → %s musí být povolený přechod", from, to)
                .isTrue();
    }

    @ParameterizedTest(name = "{0} → {1} je zakázaný přechod")
    @CsvSource({
            // DRAFT nesmí skočit rovnou na PAID ani zůstat sám na sobě
            "DRAFT,     PAID",
            "DRAFT,     DRAFT",
            // …a stornovat se nedá vůbec — koncept se maže (DELETE /invoices/{id})
            "DRAFT,     CANCELLED",
            // ISSUED se nesmí vrátit do DRAFT (vystavená faktura je neměnný doklad)
            "ISSUED,    DRAFT",
            "ISSUED,    ISSUED",
            // …ani se stornovat — oprava vystaveného dokladu patří dobropisu (§42/§45, KN-1)
            "ISSUED,    CANCELLED",
            // PAID je terminální
            "PAID,      DRAFT",
            "PAID,      ISSUED",
            "PAID,      PAID",
            "PAID,      CANCELLED",
            // CANCELLED je terminální
            "CANCELLED, DRAFT",
            "CANCELLED, ISSUED",
            "CANCELLED, PAID",
            "CANCELLED, CANCELLED"
    })
    @DisplayName("zakázané přechody selžou")
    void forbiddenTransitions_areRejected(InvoiceStatus from, InvoiceStatus to) {
        assertThat(from.canTransitionTo(to))
                .as("%s → %s musí být zakázaný přechod", from, to)
                .isFalse();
    }

    @ParameterizedTest
    @EnumSource(InvoiceStatus.class)
    @DisplayName("celá matice: každý stav povoluje právě očekávanou množinu cílů")
    void transitionMatrix_matchesExpectedTargetSetExactly(InvoiceStatus from) {
        Set<InvoiceStatus> actualTargets = EnumSet.noneOf(InvoiceStatus.class);
        for (InvoiceStatus to : InvoiceStatus.values()) {
            if (from.canTransitionTo(to)) {
                actualTargets.add(to);
            }
        }

        Set<InvoiceStatus> expected = switch (from) {
            case DRAFT -> FROM_DRAFT;
            case ISSUED -> FROM_ISSUED;
            case PAID, CANCELLED -> EnumSet.noneOf(InvoiceStatus.class);
        };

        assertThat(actualTargets)
                .as("množina povolených cílů ze stavu %s", from)
                .containsExactlyInAnyOrderElementsOf(expected);
    }

    @Test
    @DisplayName("editovatelný je právě a jen DRAFT")
    void isEditable_onlyDraft() {
        assertThat(InvoiceStatus.DRAFT.isEditable()).isTrue();
        assertThat(InvoiceStatus.ISSUED.isEditable()).isFalse();
        assertThat(InvoiceStatus.PAID.isEditable()).isFalse();
        assertThat(InvoiceStatus.CANCELLED.isEditable()).isFalse();
    }
}
