package cz.palo.autoservis.service;

import cz.palo.autoservis.AbstractIntegrationTest;
import cz.palo.autoservis.exception.BusinessRuleException;
import cz.palo.autoservis.model.dto.schedule.OpeningHoursDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Otevírací doba dílny ({@code OpeningHoursServiceImpl}) proti reálné DB.
 *
 * <p>Výchozí stav z migrace V79: po–pá 7:00–17:00, so a ne zavřeno,
 * hlídání <strong>vypnuté</strong>.
 *
 * <p>Testy počítají okamžiky přes {@link TemporalAdjusters}, ne pevnými datumy — jinak by
 * sada začala padat podle toho, jaký je zrovna den v týdnu.
 */
@Transactional
class OpeningHoursServiceTest extends AbstractIntegrationTest {

    @Autowired
    private OpeningHoursService openingHoursService;

    // =========================================================================
    // čtení
    // =========================================================================

    @Test
    @DisplayName("get vrátí všech sedm dnů od pondělí a hlídání je zprvu vypnuté")
    void get_returnsWholeWeekDisabled() {
        OpeningHoursDto.Response result = openingHoursService.get();

        assertThat(result.isOpeningHoursEnabled())
                .as("migrace V79 nechává hlídání vypnuté, ať nezačne varovat u dat, "
                        + "která nikdo nezkontroloval")
                .isFalse();
        assertThat(result.getDays()).hasSize(7);
        assertThat(result.getDays()).extracting(OpeningHoursDto.Day::getDayOfWeek)
                .containsExactly(1, 2, 3, 4, 5, 6, 7);
        assertThat(result.getDays().get(0).getOpensAt()).isEqualTo(LocalTime.of(7, 0));
        assertThat(result.getDays().get(0).getClosesAt()).isEqualTo(LocalTime.of(17, 0));
    }

    @Test
    @DisplayName("víkend je zavřený — oba časy prázdné")
    void get_weekendIsClosed() {
        List<OpeningHoursDto.Day> days = openingHoursService.get().getDays();

        assertThat(days.get(5).getOpensAt()).isNull();
        assertThat(days.get(5).getClosesAt()).isNull();
        assertThat(days.get(6).getOpensAt()).isNull();
    }

    // =========================================================================
    // ukládání
    // =========================================================================

    @Test
    @DisplayName("update uloží celý týden i přepínač")
    void update_savesWeekAndSwitch() {
        OpeningHoursDto.UpdateRequest request = week(true);
        request.getDays().get(5).setOpensAt(LocalTime.of(8, 0));    // sobota nově otevřená
        request.getDays().get(5).setClosesAt(LocalTime.of(12, 0));

        OpeningHoursDto.Response result = openingHoursService.update(request);

        assertThat(result.isOpeningHoursEnabled()).isTrue();
        assertThat(result.getDays().get(5).getOpensAt()).isEqualTo(LocalTime.of(8, 0));
        assertThat(result.getDays().get(5).getClosesAt()).isEqualTo(LocalTime.of(12, 0));
    }

    @Test
    @DisplayName("den lze zavřít vyprázdněním obou časů")
    void update_canCloseDay() {
        OpeningHoursDto.UpdateRequest request = week(true);
        request.getDays().get(0).setOpensAt(null);
        request.getDays().get(0).setClosesAt(null);

        OpeningHoursDto.Response result = openingHoursService.update(request);

        assertThat(result.getDays().get(0).getOpensAt()).isNull();
    }

    @Test
    @DisplayName("neúplný týden → INCOMPLETE_WEEK")
    void update_incompleteWeek_throws() {
        OpeningHoursDto.UpdateRequest request = week(true);
        request.getDays().remove(6);

        assertBusinessRule(() -> openingHoursService.update(request), "INCOMPLETE_WEEK");
    }

    @Test
    @DisplayName("zdvojený den → INCOMPLETE_WEEK (rozvrh by byl nejednoznačný)")
    void update_duplicateDay_throws() {
        OpeningHoursDto.UpdateRequest request = week(true);
        request.getDays().get(6).setDayOfWeek(1);

        assertBusinessRule(() -> openingHoursService.update(request), "INCOMPLETE_WEEK");
    }

    @Test
    @DisplayName("jen jeden z časů → INCOMPLETE_OPENING_HOURS")
    void update_halfFilledDay_throws() {
        OpeningHoursDto.UpdateRequest request = week(true);
        request.getDays().get(0).setClosesAt(null);

        assertBusinessRule(() -> openingHoursService.update(request), "INCOMPLETE_OPENING_HOURS");
    }

    @Test
    @DisplayName("zavírá se dřív, než otevírá → INVALID_OPENING_HOURS")
    void update_closesBeforeOpens_throws() {
        OpeningHoursDto.UpdateRequest request = week(true);
        request.getDays().get(0).setOpensAt(LocalTime.of(17, 0));
        request.getDays().get(0).setClosesAt(LocalTime.of(7, 0));

        assertBusinessRule(() -> openingHoursService.update(request), "INVALID_OPENING_HOURS");
    }

    @Test
    @DisplayName("stejný čas otevření i zavření → INVALID_OPENING_HOURS (nulová doba není doba)")
    void update_sameTimes_throws() {
        OpeningHoursDto.UpdateRequest request = week(true);
        request.getDays().get(0).setOpensAt(LocalTime.of(7, 0));
        request.getDays().get(0).setClosesAt(LocalTime.of(7, 0));

        assertBusinessRule(() -> openingHoursService.update(request), "INVALID_OPENING_HOURS");
    }

    // =========================================================================
    // isOutsideOpeningHours
    // =========================================================================

    @Test
    @DisplayName("při vypnutém hlídání není mimo dobu nic — ani neděle o půlnoci")
    void isOutside_disabled_alwaysFalse() {
        assertThat(openingHoursService.isOutsideOpeningHours(nextDayAt(DayOfWeek.SUNDAY, 0, 0)))
                .isFalse();
    }

    @Test
    @DisplayName("uvnitř doby → false, před otevřením i po zavření → true")
    void isOutside_respectsSchedule() {
        openingHoursService.update(week(true));

        assertThat(openingHoursService.isOutsideOpeningHours(nextDayAt(DayOfWeek.MONDAY, 9, 0)))
                .as("pondělí 9:00 je uvnitř 7:00–17:00").isFalse();
        assertThat(openingHoursService.isOutsideOpeningHours(nextDayAt(DayOfWeek.MONDAY, 6, 59)))
                .as("minutu před otevřením").isTrue();
        assertThat(openingHoursService.isOutsideOpeningHours(nextDayAt(DayOfWeek.MONDAY, 17, 1)))
                .as("minutu po zavření").isTrue();
    }

    @Test
    @DisplayName("hranice patří dovnitř — v 7:00 i v 17:00 je otevřeno")
    void isOutside_boundariesAreInside() {
        openingHoursService.update(week(true));

        assertThat(openingHoursService.isOutsideOpeningHours(nextDayAt(DayOfWeek.MONDAY, 7, 0)))
                .isFalse();
        assertThat(openingHoursService.isOutsideOpeningHours(nextDayAt(DayOfWeek.MONDAY, 17, 0)))
                .isFalse();
    }

    @Test
    @DisplayName("zavřený den je mimo dobu v každou hodinu")
    void isOutside_closedDay() {
        openingHoursService.update(week(true));

        assertThat(openingHoursService.isOutsideOpeningHours(nextDayAt(DayOfWeek.SUNDAY, 10, 0)))
                .isTrue();
    }

    @Test
    @DisplayName("null okamžik (konec neznámý) není mimo dobu")
    void isOutside_nullIsFalse() {
        openingHoursService.update(week(true));

        assertThat(openingHoursService.isOutsideOpeningHours(null)).isFalse();
    }

    // =========================================================================
    // pomocné
    // =========================================================================

    /** Výchozí týden po–pá 7:00–17:00, víkend zavřeno, s daným stavem přepínače. */
    private OpeningHoursDto.UpdateRequest week(boolean enabled) {
        List<OpeningHoursDto.Day> days = new ArrayList<>();
        for (int dayOfWeek = 1; dayOfWeek <= 7; dayOfWeek++) {
            OpeningHoursDto.Day day = new OpeningHoursDto.Day();
            day.setDayOfWeek(dayOfWeek);
            if (dayOfWeek <= 5) {
                day.setOpensAt(LocalTime.of(7, 0));
                day.setClosesAt(LocalTime.of(17, 0));
            }
            days.add(day);
        }
        OpeningHoursDto.UpdateRequest request = new OpeningHoursDto.UpdateRequest();
        request.setOpeningHoursEnabled(enabled);
        request.setDays(days);
        return request;
    }

    /** Nejbližší příští daný den v týdnu v danou hodinu, v časové zóně serveru. */
    private OffsetDateTime nextDayAt(DayOfWeek dayOfWeek, int hour, int minute) {
        return java.time.LocalDate.now(ZoneId.systemDefault())
                .with(TemporalAdjusters.next(dayOfWeek))
                .atTime(hour, minute)
                .atZone(ZoneId.systemDefault())
                .toOffsetDateTime();
    }

    private void assertBusinessRule(Runnable action, String expectedRuleCode) {
        assertThatThrownBy(action::run)
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(ex -> assertThat(((BusinessRuleException) ex).getRuleCode())
                        .isEqualTo(expectedRuleCode));
    }
}
