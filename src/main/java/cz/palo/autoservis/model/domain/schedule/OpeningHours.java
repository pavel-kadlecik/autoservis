package cz.palo.autoservis.model.domain.schedule;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalTime;
import java.time.OffsetDateTime;

/**
 * Otevírací doba dílny pro jeden den v týdnu — jeden řádek {@code schedule.opening_hours}.
 *
 * <p>{@code dayOfWeek} je 1 = pondělí … 7 = neděle (ISO-8601), tedy shodné s
 * {@link java.time.DayOfWeek#getValue()} i s {@code EXTRACT(ISODOW)} v PostgreSQL.
 *
 * <p><strong>Obě časy {@code null} znamenají zavřeno celý den</strong> — hlídá to
 * {@code chk_opening_hours_pair}, takže „otevřeno od sedmi do neznáma" nemůže vzniknout.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OpeningHours {

    private Integer        dayOfWeek;
    private LocalTime      opensAt;
    private LocalTime      closesAt;
    private OffsetDateTime updatedAt;

    /** @return {@code true}, je-li ten den zavřeno celý den */
    public boolean isClosed() {
        return opensAt == null;
    }
}
