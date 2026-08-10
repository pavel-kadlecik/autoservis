package cz.palo.autoservis.model.domain.vehicle;

import cz.palo.autoservis.model.enums.MileageSource;
import lombok.*;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * Doménový objekt jednoho odečtu tachometru — mapuje se na {@code vehicle.mileage_history}.
 *
 * <p>Append-only řádek deníku. {@code recordedDate} je business datum odečtu;
 * {@code createdAt} je systémový čas vložení. Nejnovější odečet každého vozidla
 * zrcadlí do {@code vehicle.vehicles.current_mileage_km} trigger
 * {@code trg_mileage_history_sync_current}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MileageHistory {

    private Long id;
    private Long vehicleId;
    private Integer mileageKm;
    private LocalDate recordedDate;
    private MileageSource source;
    private String note;
    /**
     * Zakázka, při jejímž příjmu odečet vznikl (V84). {@code null} u ručně zadaných
     * odečtů z karty vozidla.
     *
     * <p>Nastavuje ho <strong>server</strong> při zakládání zakázky, ne klient —
     * do {@code MileageDto.CreateRequest} proto nepatří (týž princip jako u auditu).
     * Smazání zakázky odečet odstraní ({@code ON DELETE CASCADE}): u zakázky založené
     * omylem, typicky na špatném voze, jde o nesmyslný údaj v historii cizího auta.
     */
    private Long orderId;
    private OffsetDateTime createdAt;
    private Long createdBy;
}