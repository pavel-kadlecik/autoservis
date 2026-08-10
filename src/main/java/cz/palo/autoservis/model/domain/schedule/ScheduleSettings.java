package cz.palo.autoservis.model.domain.schedule;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

/**
 * Provozní přepínače plánovacího kalendáře — jediný řádek {@code schedule.schedule_settings}
 * (singleton vynucený {@code CHECK (id = 1)}, týž vzor jako {@code billing.company_profile}).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScheduleSettings {

    private Integer        id;

    /**
     * Zapíná ohled na otevírací dobu. Dnes znamená „upozorňuj na termín mimo dobu" — uložit
     * ho lze i tak (rozhodnutí uživatele 2026-08-04, týž princip jako u překryvu objednávek:
     * kdo tam stojí, ví to líp než systém). Vypnuté = kalendář se otevírací dobou nezabývá.
     */
    private boolean        openingHoursEnabled;

    private OffsetDateTime updatedAt;
}
