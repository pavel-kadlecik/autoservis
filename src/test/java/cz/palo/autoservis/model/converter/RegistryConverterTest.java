package cz.palo.autoservis.model.converter;

import cz.palo.autoservis.model.domain.vehicle.RegistrySnapshot;
import cz.palo.autoservis.model.dto.registry.RegistryDto;
import cz.palo.autoservis.model.dto.registry.RegistryFetchResult;
import cz.palo.autoservis.model.dto.registry.RegistryVehicleData;
import cz.palo.autoservis.model.enums.FuelType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.LocalDate;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Čistý unit test (bez Spring kontextu) defenzivního parsování volnotextových
 * hodnot z registru vozidel. Nerozpoznaný vstup se musí mapovat na {@code null} —
 * nikdy na odhadnutou hodnotu — aby předvyplnění formuláře pole prostě nechalo být.
 */
class RegistryConverterTest {

    private final RegistryConverter converter = new RegistryConverter();

    // ---------------------------------------------------------------
    // mapFuel — řetězec paliva z registru + příznaky pohonu ANO/NE
    // ---------------------------------------------------------------

    @ParameterizedTest(name = "palivo=\"{0}\", el={1}, hyb={2} → {3}")
    @CsvSource(nullValues = "NULL", value = {
            "'BA 95 B',  NE,   NE,   PETROL",
            "'BENZIN',   NE,   NE,   PETROL",
            "'NM',       NE,   NE,   DIESEL",
            "'NAFTA',    NE,   NE,   DIESEL",
            "'LPG',      NE,   NE,   LPG",
            "'CNG',      NE,   NE,   CNG",
            "'EL',       NE,   NE,   ELECTRIC",
            "'BA 95 B',  ANO,  NE,   ELECTRIC",       // příznak elektro vítězí nad řetězcem paliva
            "'BA 95 B',  NE,   ANO,  HYBRID_PETROL",
            "'NM',       NE,   ANO,  HYBRID_DIESEL",
            "'VODÍK',    NE,   NE,   HYDROGEN",
            "'???',      NE,   NE,   NULL",           // neznámé → null, ne OTHER
            "NULL,       NULL, NULL, NULL",
    })
    void mapFuel_coversRegistryVocabulary(String palivo, String elektricke, String hybridni, FuelType expected) {
        assertThat(converter.mapFuel(palivo, elektricke, hybridni)).isEqualTo(expected);
    }

    // ---------------------------------------------------------------
    // parsePowerKw — "50 / 5000" (kW / ot./min)
    // ---------------------------------------------------------------

    @Test
    @DisplayName("parsePowerKw extracts the first integer (kW), garbage → null")
    void parsePowerKw_extractsFirstInteger() {
        assertThat(converter.parsePowerKw("50 / 5000")).isEqualTo((short) 50);
        assertThat(converter.parsePowerKw("110/6000")).isEqualTo((short) 110);
        assertThat(converter.parsePowerKw(" / ")).isNull();
        assertThat(converter.parsePowerKw(null)).isNull();
        assertThat(converter.parsePowerKw("0 / 0")).isNull();   // výkon musí být kladný
    }

    // ---------------------------------------------------------------
    // parseAnoNe / parseDate
    // ---------------------------------------------------------------

    @Test
    @DisplayName("parseAnoNe: only ANO (case-insensitive) is true")
    void parseAnoNe_onlyAnoIsTrue() {
        assertThat(converter.parseAnoNe("ANO")).isTrue();
        assertThat(converter.parseAnoNe("ano ")).isTrue();
        assertThat(converter.parseAnoNe("NE")).isFalse();
        assertThat(converter.parseAnoNe(null)).isFalse();
        assertThat(converter.parseAnoNe("cokoliv")).isFalse();
    }

    @Test
    @DisplayName("parseEngineCode: MotorTyp se ořízne; prázdné/null → null")
    void parseEngineCode_trimsAndBlankToNull() {
        assertThat(converter.parseEngineCode("CRL")).isEqualTo("CRL");
        assertThat(converter.parseEngineCode("  N47D20 ")).isEqualTo("N47D20");
        assertThat(converter.parseEngineCode("   ")).isNull();
        assertThat(converter.parseEngineCode(null)).isNull();
    }

    @Test
    @DisplayName("parseDate: ISO datetime → date part, garbage → null")
    void parseDate_isoDatetimeToLocalDate() {
        assertThat(converter.parseDate("1997-03-21T00:00:00")).isEqualTo(LocalDate.of(1997, 3, 21));
        assertThat(converter.parseDate("2013-12-06T00:00:00")).isEqualTo(LocalDate.of(2013, 12, 6));
        assertThat(converter.parseDate(null)).isNull();
        assertThat(converter.parseDate("")).isNull();
        assertThat(converter.parseDate("not-a-date")).isNull();
    }

    // ---------------------------------------------------------------
    // Hraniční hodnoty parserů
    // ---------------------------------------------------------------

    @Test
    @DisplayName("parsePowerKw: hranice rozsahu — 0 je neplatné, 1 a Short.MAX_VALUE platné")
    void parsePowerKw_rangeBoundaries() {
        assertThat(converter.parsePowerKw("0")).as("nulový výkon není údaj").isNull();
        assertThat(converter.parsePowerKw("1")).isEqualTo((short) 1);
        assertThat(converter.parsePowerKw(String.valueOf(Short.MAX_VALUE))).isEqualTo(Short.MAX_VALUE);
        assertThat(converter.parsePowerKw(String.valueOf(Short.MAX_VALUE + 1)))
                .as("nad rozsah Short → null, ne přetečená hodnota").isNull();
    }

    @Test
    @DisplayName("parsePowerKw: číslo mimo rozsah int → null, ne pád ani nula")
    void parsePowerKw_numberTooLargeForInt_returnsNull() {
        assertThat(converter.parsePowerKw("99999999999 / 5000")).isNull();
    }

    @Test
    @DisplayName("parseDate: hranice délky — přesně 10 znaků se ještě parsuje, 9 už ne")
    void parseDate_lengthBoundary() {
        assertThat(converter.parseDate("1997-03-21")).isEqualTo(LocalDate.of(1997, 3, 21));
        assertThat(converter.parseDate("1997-03-2")).isNull();
    }

    // ---------------------------------------------------------------
    // Mapování objektů
    // ---------------------------------------------------------------

    @Test
    @DisplayName("toLookupResponse poskládá prefill formuláře z registrových dat")
    void toLookupResponse_mapsAllComponents() {
        RegistryDto.LookupResponse response = converter.toLookupResponse(vehicleData());

        assertThat(response).isNotNull();
        assertThat(response.vin()).isEqualTo("TMBJJ7NE0E0123456");
        assertThat(response.brand()).isEqualTo("ŠKODA");
        assertThat(response.model()).isEqualTo("OCTAVIA");
        assertThat(response.color()).isEqualTo("STŘÍBRNÁ");
        assertThat(response.engineDisplacementCcm()).isEqualTo(1968);
        assertThat(response.enginePowerKw()).isEqualTo((short) 110);
        assertThat(response.engineCode()).as("kód motoru z pole MotorTyp").isEqualTo("CRL");
        assertThat(response.firstRegistrationDate()).isEqualTo(LocalDate.of(2014, 3, 20));
        assertThat(response.fuelType()).isEqualTo(FuelType.DIESEL);
        assertThat(response.stkValidUntil()).isEqualTo(LocalDate.of(2027, 6, 30));
        assertThat(response.lastInspectionDate()).isEqualTo(LocalDate.of(2025, 6, 30));
        assertThat(response.registryStatus()).isEqualTo("PROVOZOVANÉ");
    }

    @Test
    @DisplayName("toSnapshot poskládá záznam k uložení včetně syrového JSON a auditu")
    void toSnapshot_mapsPersistableFields() {
        RegistryFetchResult result = new RegistryFetchResult(vehicleData(), "{\"VIN\":\"TMBJJ7NE0E0123456\"}");

        RegistrySnapshot snapshot = converter.toSnapshot(11L, result, 9L);

        assertThat(snapshot).isNotNull();
        assertThat(snapshot.getVehicleId()).isEqualTo(11L);
        assertThat(snapshot.getStkValidUntil()).isEqualTo(LocalDate.of(2027, 6, 30));
        assertThat(snapshot.getLastInspectionDate()).isEqualTo(LocalDate.of(2025, 6, 30));
        assertThat(snapshot.getRegistryStatus()).isEqualTo("PROVOZOVANÉ");
        assertThat(snapshot.getRawResponse()).isEqualTo("{\"VIN\":\"TMBJJ7NE0E0123456\"}");
        assertThat(snapshot.getCreatedBy()).isEqualTo(9L);
        assertThat(snapshot.getId()).isNull();
    }

    @Test
    @DisplayName("toSnapshotResponse nevystavuje syrový JSON registru")
    void toSnapshotResponse_mapsFieldsWithoutRawJson() {
        RegistrySnapshot snapshot = RegistrySnapshot.builder()
                .id(21L)
                .vehicleId(11L)
                .stkValidUntil(LocalDate.of(2027, 6, 30))
                .lastInspectionDate(LocalDate.of(2025, 6, 30))
                .registryStatus("PROVOZOVANÉ")
                .rawResponse("{\"tajne\":\"data\"}")
                .fetchedAt(OffsetDateTime.parse("2026-07-01T09:30:00Z"))
                .build();

        RegistryDto.SnapshotResponse response = converter.toSnapshotResponse(snapshot);

        assertThat(response).isNotNull();
        assertThat(response.id()).isEqualTo(21L);
        assertThat(response.stkValidUntil()).isEqualTo(LocalDate.of(2027, 6, 30));
        assertThat(response.lastInspectionDate()).isEqualTo(LocalDate.of(2025, 6, 30));
        assertThat(response.registryStatus()).isEqualTo("PROVOZOVANÉ");
        assertThat(response.fetchedAt()).isEqualTo(OffsetDateTime.parse("2026-07-01T09:30:00Z"));
        assertThat(response.toString()).doesNotContain("tajne");
    }

    private static RegistryVehicleData vehicleData() {
        return new RegistryVehicleData(
                "TMBJJ7NE0E0123456",
                "ŠKODA",
                "OCTAVIA",
                "STŘÍBRNÁ",
                "NM",
                1968,
                "110 / 4000",
                "CRL",
                "NE",
                "NE",
                "2014-03-20T00:00:00",
                "2027-06-30T00:00:00",
                "2025-06-30T00:00:00",
                "PROVOZOVANÉ",
                "UA123456",
                "ORV123456",
                Boolean.FALSE,
                Boolean.FALSE,
                2,
                1);
    }
}
