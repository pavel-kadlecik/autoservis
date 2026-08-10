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
 * Stavový automat zakázky ({@code OrderStatus}) — čistý unit test bez Spring kontextu.
 *
 * <p>Testuje se <strong>celá matice 7×7</strong>, u každé dvojice s konkrétní očekávanou hodnotou.
 * Test, který by ověřoval jen povolené přechody, by prošel i implementaci povolující všechno —
 * a přesně tou aplikace do auditu KN-11 byla. Zakázané přechody jsou proto vyjmenované stejně
 * pečlivě jako povolené.
 *
 * <p>Očekávané množiny jsou napsané ručně, ne odvozené z produkčního kódu — jinak by test
 * kopíroval chybu, kterou má hlídat.
 */
class OrderStatusTest {

    /** Provozní stavy — vypsané ručně, záměrně nezávisle na produkčním {@code OPERATIONAL}. */
    private static final Set<OrderStatus> OPERATIONAL = EnumSet.of(
            OrderStatus.RECEIVED,
            OrderStatus.DIAGNOSIS,
            OrderStatus.WAITING_FOR_PARTS,
            OrderStatus.IN_PROGRESS,
            OrderStatus.READY_FOR_PICKUP);

    /** Uzavřené, ale vratné — zakázku z nich lze znovu otevřít (2026-08-06). */
    private static final Set<OrderStatus> REOPENABLE = EnumSet.of(OrderStatus.COMPLETED);

    @ParameterizedTest(name = "{0} → {1} je povolený přechod")
    @CsvSource({
            // dopředu po workflow
            "RECEIVED,          DIAGNOSIS",
            "DIAGNOSIS,         WAITING_FOR_PARTS",
            "WAITING_FOR_PARTS, IN_PROGRESS",
            "IN_PROGRESS,       READY_FOR_PICKUP",
            // …a zpátky: díl přijde poškozený → z rozpracované práce zpět na čekání
            "IN_PROGRESS,       WAITING_FOR_PARTS",
            "READY_FOR_PICKUP,  IN_PROGRESS",
            "WAITING_FOR_PARTS, DIAGNOSIS",
            "DIAGNOSIS,         RECEIVED",
            // přeskočení mezikroku (obsluha stav nevyplnila průběžně)
            "RECEIVED,          READY_FOR_PICKUP",
            // uzavření z kteréhokoli provozního stavu
            "RECEIVED,          COMPLETED",
            "RECEIVED,          CANCELLED",
            "WAITING_FOR_PARTS, CANCELLED",
            "READY_FOR_PICKUP,  COMPLETED",
            // znovuotevření dokončené zakázky (2026-08-06): auto se vrátilo, nebo se na
            // „Dokončena" jen omylem kliklo. Dřív byl COMPLETED slepá ulička bez východu.
            "COMPLETED,          IN_PROGRESS",
            "COMPLETED,          READY_FOR_PICKUP",
            "COMPLETED,          RECEIVED",
            // …a omylem dokončenou zakázku jde nově i zrušit
            "COMPLETED,          CANCELLED",
            // nezměněný stav není přechod — PUT nese celý záznam, i u uzavřené zakázky
            "IN_PROGRESS,       IN_PROGRESS",
            "COMPLETED,         COMPLETED",
            "CANCELLED,         CANCELLED"
    })
    @DisplayName("povolené přechody projdou")
    void allowedTransitions_areAllowed(OrderStatus from, OrderStatus to) {
        assertThat(from.canTransitionTo(to))
                .as("%s → %s musí být povolený přechod", from, to)
                .isTrue();
    }

    @ParameterizedTest(name = "{0} → {1} je zakázaný přechod")
    @CsvSource({
            // CANCELLED je od 2026-08-06 JEDINÝ terminální stav — návrat by oživil zakázku, jejíž materiál se vrátil na sklad
            "CANCELLED, RECEIVED",
            "CANCELLED, DIAGNOSIS",
            "CANCELLED, WAITING_FOR_PARTS",
            "CANCELLED, IN_PROGRESS",
            "CANCELLED, READY_FOR_PICKUP",
            "CANCELLED, COMPLETED"
    })
    @DisplayName("z terminálního stavu nevede žádný přechod")
    void transitionsFromTerminalStatus_areRejected(OrderStatus from, OrderStatus to) {
        assertThat(from.canTransitionTo(to))
                .as("%s → %s musí být zakázaný přechod", from, to)
                .isFalse();
    }

    @ParameterizedTest
    @EnumSource(OrderStatus.class)
    @DisplayName("celá matice: každý stav povoluje právě očekávanou množinu cílů")
    void transitionMatrix_matchesExpectedTargetSetExactly(OrderStatus from) {
        Set<OrderStatus> actualTargets = EnumSet.noneOf(OrderStatus.class);
        for (OrderStatus to : OrderStatus.values()) {
            if (from.canTransitionTo(to)) {
                actualTargets.add(to);
            }
        }

        // Z provozního i z vratného stavu vede přechod kamkoli (včetně sebe),
        // z terminálního jen „zůstat".
        Set<OrderStatus> expected = OPERATIONAL.contains(from) || REOPENABLE.contains(from)
                ? EnumSet.allOf(OrderStatus.class)
                : EnumSet.of(from);

        assertThat(actualTargets)
                .as("množina povolených cílů ze stavu %s", from)
                .containsExactlyInAnyOrderElementsOf(expected);
    }

    @ParameterizedTest
    @EnumSource(OrderStatus.class)
    @DisplayName("terminální je nově jen CANCELLED — COMPLETED je vratný")
    void isTerminal_onlyCompletedAndCancelled(OrderStatus status) {
        boolean expectedTerminal = status == OrderStatus.CANCELLED;

        assertThat(status.isTerminal())
                .as("terminalita stavu %s", status)
                .isEqualTo(expectedTerminal);
    }

    @Test
    @DisplayName("null cíl není povolený přechod (NOT NULL sloupec se nesmí přepsat na null)")
    void canTransitionTo_nullTarget_isRejected() {
        for (OrderStatus from : OrderStatus.values()) {
            assertThat(from.canTransitionTo(null))
                    .as("%s → null", from)
                    .isFalse();
        }
    }
}
