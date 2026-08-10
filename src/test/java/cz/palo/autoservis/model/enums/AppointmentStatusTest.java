package cz.palo.autoservis.model.enums;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Stavový automat objednávky termínu — čistý unit test bez Springu.
 *
 * <p>Matice přechodů je jediné místo, kde se rozhoduje, co s objednávkou ještě jde udělat.
 * Chyba tady se v provozu projeví jako oživená zrušená objednávka nebo jako rozpojená vazba
 * na zakázku, což CHECK v DB odmítne až jako 500.
 */
class AppointmentStatusTest {

    @ParameterizedTest
    @EnumSource(value = AppointmentStatus.class, names = "PLANNED")
    @DisplayName("otevřený stav není terminální")
    void openStatus_isNotTerminal(AppointmentStatus status) {
        assertThat(status.isTerminal()).isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = AppointmentStatus.class, names = {"CONVERTED", "NO_SHOW", "CANCELLED"})
    @DisplayName("uzavřené stavy jsou terminální")
    void closedStatuses_areTerminal(AppointmentStatus status) {
        assertThat(status.isTerminal()).isTrue();
    }

    @ParameterizedTest
    @EnumSource(AppointmentStatus.class)
    @DisplayName("z otevřeného stavu lze na libovolný jiný, z terminálního nikam")
    void transitionMatrix(AppointmentStatus from) {
        for (AppointmentStatus to : AppointmentStatus.values()) {
            boolean allowed = from.canTransitionTo(to);

            if (from == to) {
                assertThat(allowed)
                        .as("identita %s → %s musí projít (PUT nese i nezměněný stav)", from, to)
                        .isTrue();
            } else if (from.isTerminal()) {
                assertThat(allowed)
                        .as("z terminálního %s nesmí vést nikam (%s)", from, to)
                        .isFalse();
            } else {
                assertThat(allowed)
                        .as("z otevřeného %s má jít na %s", from, to)
                        .isTrue();
            }
        }
    }

    @Test
    @DisplayName("null cíl se odmítne, ne aby spadl na NPE")
    void nullTarget_isRejected() {
        for (AppointmentStatus status : AppointmentStatus.values()) {
            assertThat(status.canTransitionTo(null)).isFalse();
        }
    }

    @Test
    @DisplayName("hodnoty enumu odpovídají PostgreSQL typu schedule.appointment_status")
    void values_matchDatabaseEnum() {
        assertThat(AppointmentStatus.values())
                .extracting(Enum::name)
                .containsExactly("PLANNED", "CONVERTED", "NO_SHOW", "CANCELLED");
    }
}
