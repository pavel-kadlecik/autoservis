package cz.palo.autoservis.model.converter;

import cz.palo.autoservis.model.domain.vehicle.RegistrySnapshot;
import cz.palo.autoservis.model.dto.registry.RegistryDto;
import cz.palo.autoservis.model.dto.registry.RegistryFetchResult;
import cz.palo.autoservis.model.dto.registry.RegistryVehicleData;
import cz.palo.autoservis.model.enums.FuelType;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Ruční konvertor mezi daty API registru a aplikačními tvary
 * (bez MapStructu — konvence projektu).
 *
 * <p>Veškeré parsování je defenzivní: volnotextové hodnoty registru (řetězec
 * paliva, výkon „50 / 5000", ISO datetimy) se při nerozpoznání mapují na
 * {@code null} — nikdy na odhad. {@code null} prostě nechá pole formuláře být.
 */
@Component
public class RegistryConverter {

    private static final Locale CZECH = Locale.forLanguageTag("cs");
    private static final Pattern FIRST_INT = Pattern.compile("\\d+");

    // ---------------------------------------------------------------
    // Parsování jednotlivých polí
    // ---------------------------------------------------------------

    /**
     * Mapuje popis paliva z registru na aplikační {@link FuelType}.
     *
     * <p>Registr používá kódy/popisky jako {@code "BA 95 B"} (benzín),
     * {@code "NM"}/{@code "NAFTA"} (nafta), {@code "LPG"}, {@code "CNG"},
     * {@code "EL"}; elektrický a hybridní pohon jsou zvláštní ANO/NE příznaky.
     *
     * @return namapované palivo, nebo {@code null} při nerozpoznání
     */
    public FuelType mapFuel(String palivo, String vozidloElektricke, String vozidloHybridni) {
        if (parseAnoNe(vozidloElektricke)) {
            return FuelType.ELECTRIC;
        }
        String p = palivo == null ? "" : palivo.trim().toUpperCase(CZECH);
        boolean hybrid = parseAnoNe(vozidloHybridni);
        boolean petrol = p.startsWith("BA") || p.contains("BENZ");
        boolean diesel = p.startsWith("NM") || p.contains("NAFT");

        if (hybrid && petrol) return FuelType.HYBRID_PETROL;
        if (hybrid && diesel) return FuelType.HYBRID_DIESEL;
        if (petrol) return FuelType.PETROL;
        if (diesel) return FuelType.DIESEL;
        if (p.contains("LPG")) return FuelType.LPG;
        if (p.contains("CNG")) return FuelType.CNG;
        if (p.equals("EL") || p.contains("ELEKTRO")) return FuelType.ELECTRIC;
        if (p.contains("VODÍK") || p.contains("VODIK") || p.contains("H2")) return FuelType.HYDROGEN;
        return null;
    }

    /**
     * Vytáhne výkon motoru v kW z formátu registru {@code "50 / 5000"}
     * (kW / ot./min) — první celé číslo v řetězci.
     *
     * @return výkon v kW, nebo {@code null}, když řetězec žádné číslo nenese
     */
    public Short parsePowerKw(String motorMaxVykon) {
        if (motorMaxVykon == null) return null;
        Matcher m = FIRST_INT.matcher(motorMaxVykon);
        if (!m.find()) return null;
        try {
            int value = Integer.parseInt(m.group());
            return value > 0 && value <= Short.MAX_VALUE ? (short) value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Parsuje příznaky registru {@code "ANO"}/{@code "NE"}; cokoli jiného je {@code false}. */
    public boolean parseAnoNe(String value) {
        return value != null && value.trim().equalsIgnoreCase("ANO");
    }

    /**
     * Kód motoru z pole registru {@code MotorTyp} (např. {@code "CRL"}).
     * Ořezaný; prázdný → {@code null}, aby prázdná hodnota nikdy nespadla na DB
     * not-blank CHECK ani nepředvyplnila formulář prázdným řetězcem.
     */
    public String parseEngineCode(String motorTyp) {
        if (motorTyp == null || motorTyp.isBlank()) return null;
        return motorTyp.trim();
    }

    /**
     * Parsuje ISO datetime registru ({@code "1997-03-21T00:00:00"}) na datum.
     *
     * @return datumová část, nebo {@code null}, když chybí/nejde parsovat
     */
    public LocalDate parseDate(String isoDateTime) {
        if (isoDateTime == null || isoDateTime.length() < 10) return null;
        try {
            return LocalDate.parse(isoDateTime.substring(0, 10));
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    // ---------------------------------------------------------------
    // Mapování objektů
    // ---------------------------------------------------------------

    /** Sestaví odpověď pro předvyplnění formuláře z namapovaných dat registru. */
    public RegistryDto.LookupResponse toLookupResponse(RegistryVehicleData d) {
        return new RegistryDto.LookupResponse(
                d.vin(),
                d.tovarniZnacka(),
                d.obchodniOznaceni(),
                d.vozidloKaroserieBarva(),
                d.motorZdvihObjem(),
                parsePowerKw(d.motorMaxVykon()),
                parseEngineCode(d.motorTyp()),
                parseDate(d.datumPrvniRegistrace()),
                mapFuel(d.palivo(), d.vozidloElektricke(), d.vozidloHybridni()),
                parseDate(d.pravidelnaTechnickaProhlidkaDo()),
                parseDate(d.evidencniProhlidkaDne()),
                d.statusNazev());
    }

    /** Sestaví perzistovatelný snapshot z výsledku dotazu. */
    public RegistrySnapshot toSnapshot(Long vehicleId, RegistryFetchResult result, Long userId) {
        RegistryVehicleData d = result.data();
        return RegistrySnapshot.builder()
                .vehicleId(vehicleId)
                .stkValidUntil(parseDate(d.pravidelnaTechnickaProhlidkaDo()))
                .lastInspectionDate(parseDate(d.evidencniProhlidkaDne()))
                .registryStatus(d.statusNazev())
                .rawResponse(result.rawJson())
                .createdBy(userId)
                .build();
    }

    /** Mapuje uložený snapshot na jeho API response tvar. */
    public RegistryDto.SnapshotResponse toSnapshotResponse(RegistrySnapshot s) {
        return new RegistryDto.SnapshotResponse(
                s.getId(),
                s.getStkValidUntil(),
                s.getLastInspectionDate(),
                s.getRegistryStatus(),
                s.getFetchedAt());
    }
}
