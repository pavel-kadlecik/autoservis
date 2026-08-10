package cz.palo.autoservis.model.domain.vehicle;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * Doménový objekt jednoho výsledku dotazu do státního registru — mapuje se na
 * {@code vehicle.registry_snapshots}.
 *
 * <p>Append-only: každé úspěšné volání API dataovozidlech.cz vloží nový řádek.
 * {@code stkValidUntil} nejnovějšího snapshotu zrcadlí do
 * {@code vehicle.vehicles.stk_valid_until} trigger
 * {@code trg_registry_snapshots_sync_stk}.
 *
 * <p>{@code rawResponse} drží kompletní objekt {@code Data} vrácený registrem
 * jako JSON řetězec (v databázi JSONB — cast dělá mapper XML). API má rate
 * limit 27 požadavků/min, takže uchování celého payloadu ušetří opakovaná
 * volání, až budou později zajímavá další pole.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RegistrySnapshot {

    private Long id;
    private Long vehicleId;

    /** Platnost pravidelné technické prohlídky (STK) do — {@code PravidelnaTechnickaProhlidkaDo}. */
    private LocalDate stkValidUntil;

    /** Datum evidenční prohlídky — {@code EvidencniProhlidkaDne}. */
    private LocalDate lastInspectionDate;

    /** Stav vozidla v registru, např. „PROVOZOVANÉ" — {@code StatusNazev}. Volný text, číselník vlastní ministerstvo. */
    private String registryStatus;

    /** Kompletní surový objekt {@code Data} z API jako JSON text. */
    private String rawResponse;

    private OffsetDateTime fetchedAt;
    private Long createdBy;
}
