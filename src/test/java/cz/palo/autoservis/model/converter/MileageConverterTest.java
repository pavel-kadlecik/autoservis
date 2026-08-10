package cz.palo.autoservis.model.converter;

import cz.palo.autoservis.model.domain.vehicle.MileageHistory;
import cz.palo.autoservis.model.dto.vehicle.MileageDto;
import cz.palo.autoservis.model.enums.MileageSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Konvertor záznamů tachometru — čistý unit test bez Spring kontextu.
 *
 * <p>{@code vehicleId} nepřichází z těla requestu, ale z URL (R-14 / N-07) — proto ho
 * {@code toDomain} bere jako samostatný parametr. Test to ověřuje explicitně.
 */
class MileageConverterTest {

    private final MileageConverter converter = new MileageConverter();

    @Test
    @DisplayName("toDomain přenese pole requestu a vehicleId vezme z parametru, ne z těla")
    void toDomain_mapsFieldsAndTakesVehicleIdFromParameter() {
        MileageDto.CreateRequest request = new MileageDto.CreateRequest();
        request.setMileageKm(150_000);
        request.setRecordedDate(LocalDate.of(2026, 7, 1));
        request.setSource(MileageSource.SERVICE);
        request.setNote("při výměně oleje");

        MileageHistory result = converter.toDomain(42L, request);

        assertThat(result.getVehicleId()).isEqualTo(42L);
        assertThat(result.getMileageKm()).isEqualTo(150_000);
        assertThat(result.getRecordedDate()).isEqualTo(LocalDate.of(2026, 7, 1));
        assertThat(result.getSource()).isEqualTo(MileageSource.SERVICE);
        assertThat(result.getNote()).isEqualTo("při výměně oleje");
    }

    @Test
    @DisplayName("toDomain nenastaví audit ani DB-řízená pole (createdBy, id, createdAt)")
    void toDomain_leavesServerManagedFieldsEmpty() {
        MileageDto.CreateRequest request = new MileageDto.CreateRequest();
        request.setMileageKm(150_000);
        request.setSource(MileageSource.SERVICE);

        MileageHistory result = converter.toDomain(42L, request);

        assertThat(result.getId()).isNull();
        assertThat(result.getCreatedBy()).isNull();
        assertThat(result.getCreatedAt()).isNull();
    }

    @Test
    @DisplayName("toDomain: null recordedDate se nedoplňuje v konvertoru (default řeší service)")
    void toDomain_nullRecordedDate_isLeftNull() {
        MileageDto.CreateRequest request = new MileageDto.CreateRequest();
        request.setMileageKm(150_000);
        request.setRecordedDate(null);

        assertThat(converter.toDomain(42L, request).getRecordedDate()).isNull();
    }

    @Test
    @DisplayName("toDomain(null request) → null")
    void toDomain_nullRequest_returnsNull() {
        assertThat(converter.toDomain(42L, null)).isNull();
    }

    @Test
    @DisplayName("applyUpdate přepíše editovatelná pole, ale nesahá na vehicleId ani audit")
    void applyUpdate_overwritesEditableFieldsOnly() {
        MileageHistory existing = new MileageHistory();
        existing.setId(7L);
        existing.setVehicleId(42L);
        existing.setMileageKm(140_000);
        existing.setRecordedDate(LocalDate.of(2026, 1, 1));
        existing.setSource(MileageSource.CUSTOMER);
        existing.setNote("původní");
        existing.setCreatedBy(9L);
        existing.setCreatedAt(OffsetDateTime.parse("2026-01-01T10:00:00Z"));

        MileageDto.UpdateRequest request = new MileageDto.UpdateRequest();
        request.setMileageKm(155_000);
        request.setRecordedDate(LocalDate.of(2026, 7, 1));
        request.setSource(MileageSource.SERVICE);
        request.setNote("opraveno podle protokolu");

        MileageHistory result = converter.applyUpdate(existing, request);

        assertThat(result).as("mutace probíhá na místě, vrací se tentýž objekt").isSameAs(existing);
        assertThat(existing.getMileageKm()).isEqualTo(155_000);
        assertThat(existing.getRecordedDate()).isEqualTo(LocalDate.of(2026, 7, 1));
        assertThat(existing.getSource()).isEqualTo(MileageSource.SERVICE);
        assertThat(existing.getNote()).isEqualTo("opraveno podle protokolu");

        assertThat(existing.getId()).isEqualTo(7L);
        assertThat(existing.getVehicleId()).isEqualTo(42L);
        assertThat(existing.getCreatedBy()).isEqualTo(9L);
        assertThat(existing.getCreatedAt()).isEqualTo(OffsetDateTime.parse("2026-01-01T10:00:00Z"));
    }

    @Test
    @DisplayName("applyUpdate vrací null, chybí-li kterýkoli z argumentů")
    void applyUpdate_nullArguments_returnNull() {
        assertThat(converter.applyUpdate(null, new MileageDto.UpdateRequest())).isNull();
        assertThat(converter.applyUpdate(new MileageHistory(), null)).isNull();
    }

    @Test
    @DisplayName("toResponse přenese všechna pole včetně auditu (createdBy je v odpovědi žádoucí)")
    void toResponse_mapsAllFields() {
        MileageHistory record = new MileageHistory();
        record.setId(7L);
        record.setVehicleId(42L);
        record.setMileageKm(150_000);
        record.setRecordedDate(LocalDate.of(2026, 7, 1));
        record.setSource(MileageSource.SERVICE);
        record.setNote("při výměně oleje");
        record.setCreatedAt(OffsetDateTime.parse("2026-07-01T09:30:00Z"));
        record.setCreatedBy(9L);

        MileageDto.Response response = converter.toResponse(record);

        assertThat(response.getId()).isEqualTo(7L);
        assertThat(response.getVehicleId()).isEqualTo(42L);
        assertThat(response.getMileageKm()).isEqualTo(150_000);
        assertThat(response.getRecordedDate()).isEqualTo(LocalDate.of(2026, 7, 1));
        assertThat(response.getSource()).isEqualTo(MileageSource.SERVICE);
        assertThat(response.getNote()).isEqualTo("při výměně oleje");
        assertThat(response.getCreatedAt()).isEqualTo(OffsetDateTime.parse("2026-07-01T09:30:00Z"));
        assertThat(response.getCreatedBy()).isEqualTo(9L);
    }

    @Test
    @DisplayName("toResponse(null) → null")
    void toResponse_null_returnsNull() {
        assertThat(converter.toResponse(null)).isNull();
    }

    @Test
    @DisplayName("toResponses zachová pořadí (historie se zobrazuje seřazená)")
    void toResponses_mapsRowsInOrder() {
        MileageHistory older = new MileageHistory();
        older.setId(1L);
        older.setMileageKm(140_000);

        MileageHistory newer = new MileageHistory();
        newer.setId(2L);
        newer.setMileageKm(150_000);

        List<MileageDto.Response> result = converter.toResponses(List.of(older, newer));

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getMileageKm()).isEqualTo(140_000);
        assertThat(result.get(1).getMileageKm()).isEqualTo(150_000);
    }
}
