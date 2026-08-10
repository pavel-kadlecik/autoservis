package cz.palo.autoservis.model.dto.registry;

import cz.palo.autoservis.model.enums.FuelType;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * Namespace pro API DTO integrace registru vozidel (dataovozidlech.cz).
 * Jen response tvary — requesty nesou obyčejné query/path parametry.
 */
public final class RegistryDto {

    private RegistryDto() {
    }

    /**
     * Odpověď {@code GET /vehicles/registry-lookup} — data pro předvyplnění
     * formuláře vozidla. {@code vin} je součástí, aby hledání podle čísla ORV/TP
     * mohlo předvyplnit i pole VIN. {@code fuelType} je namapovaný aplikační
     * enum; {@code null}, když palivo z registru nerozpoznáme
     * (pole formuláře se prostě nedotkne).
     */
    public record LookupResponse(
            String vin,
            String brand,
            String model,
            String color,
            Integer engineDisplacementCcm,
            Short enginePowerKw,
            String engineCode,
            LocalDate firstRegistrationDate,
            FuelType fuelType,
            LocalDate stkValidUntil,
            LocalDate lastInspectionDate,
            String registryStatus
    ) {
    }

    /**
     * Jeden uložený snapshot registru — odpověď akce obnovení a seznamu
     * snapshotů. Surový JSONB payload se záměrně nevystavuje.
     */
    public record SnapshotResponse(
            Long id,
            LocalDate stkValidUntil,
            LocalDate lastInspectionDate,
            String registryStatus,
            OffsetDateTime fetchedAt
    ) {
    }
}
