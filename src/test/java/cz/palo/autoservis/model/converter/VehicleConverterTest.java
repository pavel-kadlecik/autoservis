package cz.palo.autoservis.model.converter;

import cz.palo.autoservis.model.domain.customer.Customer;
import cz.palo.autoservis.model.domain.vehicle.Vehicle;
import cz.palo.autoservis.model.dto.vehicle.VehicleDto;
import cz.palo.autoservis.model.enums.CustomerType;
import cz.palo.autoservis.model.enums.FuelType;
import cz.palo.autoservis.model.enums.TransmissionType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Konvertor vozidel — čistý unit test bez Spring kontextu.
 *
 * <p>Pozn.: {@code toDetailResponse} i {@code toListResponse} čtou {@code vehicle.getCustomer()}
 * s null kontrolou (doplněna v TD-55) — vozidlo bez načteného majitele tedy nespadne, jen má
 * {@code customer = null}. Mappery majitele běžně JOINují, fixtury ho proto mají načteného;
 * větev bez majitele kryje samostatný test.
 */
class VehicleConverterTest {

    private final VehicleConverter converter = new VehicleConverter();


    // =========================================================================
    // toDomain
    // =========================================================================

    @Test
    @DisplayName("toDomain přenese všechna pole CreateRequest")
    void toDomain_mapsAllFields() {
        VehicleDto.CreateRequest request = new VehicleDto.CreateRequest();
        request.setCustomerId(3L);
        request.setVin("TMBJJ7NE0E0123456");
        request.setLicensePlate("1AB 2345");
        request.setBrand("Škoda");
        request.setModel("Octavia");
        request.setYearOfManufacture((short) 2014);
        request.setFirstRegistrationDate(LocalDate.of(2014, 3, 20));
        request.setFuelType(FuelType.DIESEL);
        request.setTransmission(TransmissionType.MANUAL);
        request.setEngineCode("CJCA");
        request.setEngineDisplacementCcm(1968);
        request.setEnginePowerKw((short) 110);
        request.setColor("stříbrná");
        request.setInternalNote("stálý zákazník");

        Vehicle result = converter.toDomain(request);

        assertThat(result.getCustomerId()).isEqualTo(3L);
        assertThat(result.getVin()).isEqualTo("TMBJJ7NE0E0123456");
        assertThat(result.getLicensePlate()).isEqualTo("1AB 2345");
        assertThat(result.getBrand()).isEqualTo("Škoda");
        assertThat(result.getModel()).isEqualTo("Octavia");
        assertThat(result.getYearOfManufacture()).isEqualTo((short) 2014);
        assertThat(result.getFirstRegistrationDate()).isEqualTo(LocalDate.of(2014, 3, 20));
        assertThat(result.getFuelType()).isEqualTo(FuelType.DIESEL);
        assertThat(result.getTransmission()).isEqualTo(TransmissionType.MANUAL);
        assertThat(result.getEngineCode()).isEqualTo("CJCA");
        assertThat(result.getEngineDisplacementCcm()).isEqualTo(1968);
        assertThat(result.getEnginePowerKw()).isEqualTo((short) 110);
        assertThat(result.getColor()).isEqualTo("stříbrná");
        assertThat(result.getInternalNote()).isEqualTo("stálý zákazník");
    }

    @Test
    @DisplayName("toDomain nastaví nové vozidlo jako aktivní")
    void toDomain_setsActiveTrue() {
        VehicleDto.CreateRequest request = new VehicleDto.CreateRequest();
        request.setVin("TMBJJ7NE0E0123456");

        assertThat(converter.toDomain(request).isActive()).isTrue();
    }

    @Test
    @DisplayName("toDomain nenastaví audit ani DB-řízená pole (createdBy, id, timestamps, tachometr)")
    void toDomain_leavesServerManagedFieldsEmpty() {
        VehicleDto.CreateRequest request = new VehicleDto.CreateRequest();
        request.setVin("TMBJJ7NE0E0123456");
        request.setCustomerId(3L);

        Vehicle result = converter.toDomain(request);

        assertThat(result.getId()).isNull();
        assertThat(result.getCreatedBy()).isNull();
        assertThat(result.getCreatedAt()).isNull();
        assertThat(result.getUpdatedAt()).isNull();
        assertThat(result.getCurrentMileageKm()).isNull();
        assertThat(result.getStkValidUntil()).isNull();
    }

    @Test
    @DisplayName("toDomain(null) → null")
    void toDomain_null_returnsNull() {
        assertThat(converter.toDomain(null)).isNull();
    }

    // =========================================================================
    // applyUpdate
    // =========================================================================

    @Test
    @DisplayName("applyUpdate přepíše editovatelná pole")
    void applyUpdate_overwritesEditableFields() {
        Vehicle existing = vehicleWithOwner();
        existing.setLicensePlate("1AB 2345");
        existing.setColor("stříbrná");
        existing.setInternalNote("původní");

        VehicleDto.UpdateRequest request = new VehicleDto.UpdateRequest();
        request.setCustomerId(4L);
        request.setVin("WVWZZZ1JZXW000001");
        request.setLicensePlate("9XY 8765");
        request.setBrand("Volkswagen");
        request.setModel("Passat");
        request.setYearOfManufacture((short) 2018);
        request.setFirstRegistrationDate(LocalDate.of(2018, 9, 5));
        request.setFuelType(FuelType.PETROL);
        request.setTransmission(TransmissionType.AUTOMATIC);
        request.setEngineCode("DKZA");
        request.setEngineDisplacementCcm(1984);
        request.setEnginePowerKw((short) 140);
        request.setColor("černá");
        request.setInternalNote("po servisu");

        Vehicle result = converter.applyUpdate(existing, request);

        assertThat(result).as("mutace probíhá na místě, vrací se tentýž objekt").isSameAs(existing);
        assertThat(existing.getCustomerId()).isEqualTo(4L);
        assertThat(existing.getVin()).isEqualTo("WVWZZZ1JZXW000001");
        assertThat(existing.getLicensePlate()).isEqualTo("9XY 8765");
        assertThat(existing.getBrand()).isEqualTo("Volkswagen");
        assertThat(existing.getModel()).isEqualTo("Passat");
        assertThat(existing.getYearOfManufacture()).isEqualTo((short) 2018);
        assertThat(existing.getFirstRegistrationDate()).isEqualTo(LocalDate.of(2018, 9, 5));
        assertThat(existing.getFuelType()).isEqualTo(FuelType.PETROL);
        assertThat(existing.getTransmission()).isEqualTo(TransmissionType.AUTOMATIC);
        assertThat(existing.getEngineCode()).isEqualTo("DKZA");
        assertThat(existing.getEngineDisplacementCcm()).isEqualTo(1984);
        assertThat(existing.getEnginePowerKw()).isEqualTo((short) 140);
        assertThat(existing.getColor()).isEqualTo("černá");
        assertThat(existing.getInternalNote()).isEqualTo("po servisu");
    }

    @Test
    @DisplayName("applyUpdate nesahá na id, aktivitu, tachometr, STK ani audit")
    void applyUpdate_doesNotTouchServerManagedFields() {
        Vehicle existing = vehicleWithOwner();
        existing.setId(11L);
        existing.setActive(true);
        existing.setCurrentMileageKm(150_000);
        existing.setStkValidUntil(LocalDate.of(2027, 6, 30));
        existing.setCreatedBy(9L);
        existing.setCreatedAt(OffsetDateTime.parse("2026-01-02T10:15:30Z"));

        VehicleDto.UpdateRequest request = new VehicleDto.UpdateRequest();
        request.setLicensePlate("9XY 8765");

        converter.applyUpdate(existing, request);

        assertThat(existing.getId()).isEqualTo(11L);
        assertThat(existing.isActive()).isTrue();
        assertThat(existing.getCurrentMileageKm()).isEqualTo(150_000);
        assertThat(existing.getStkValidUntil()).isEqualTo(LocalDate.of(2027, 6, 30));
        assertThat(existing.getCreatedBy()).isEqualTo(9L);
        assertThat(existing.getCreatedAt()).isEqualTo(OffsetDateTime.parse("2026-01-02T10:15:30Z"));
    }

    @Test
    @DisplayName("applyUpdate vrací null, chybí-li kterýkoli z argumentů")
    void applyUpdate_nullArguments_returnNull() {
        assertThat(converter.applyUpdate(null, new VehicleDto.UpdateRequest())).isNull();
        assertThat(converter.applyUpdate(vehicleWithOwner(), null)).isNull();
    }

    // =========================================================================
    // Response mapování
    // =========================================================================

    @Test
    @DisplayName("toDetailResponse namapuje vozidlo i vnořené shrnutí majitele")
    void toDetailResponse_mapsVehicleAndCustomerSummary() {
        Vehicle vehicle = vehicleWithOwner();
        vehicle.setId(11L);
        vehicle.setFirstRegistrationDate(LocalDate.of(2014, 3, 20));
        vehicle.setTransmission(TransmissionType.MANUAL);
        vehicle.setEngineCode("CJCA");
        vehicle.setEngineDisplacementCcm(1968);
        vehicle.setEnginePowerKw((short) 110);
        vehicle.setInternalNote("stálý zákazník");
        vehicle.setCurrentMileageKm(150_000);
        vehicle.setStkValidUntil(LocalDate.of(2027, 6, 30));
        vehicle.setActive(true);
        vehicle.setCreatedAt(OffsetDateTime.parse("2026-01-02T10:15:30Z"));
        vehicle.setUpdatedAt(OffsetDateTime.parse("2026-02-03T08:00:00Z"));

        VehicleDto.DetailResponse response = converter.toDetailResponse(vehicle);

        assertThat(response.getId()).isEqualTo(11L);
        assertThat(response.getVin()).isEqualTo("TMBJJ7NE0E0123456");
        assertThat(response.getLicensePlate()).isEqualTo("1AB 2345");
        assertThat(response.getBrand()).isEqualTo("Škoda");
        assertThat(response.getModel()).isEqualTo("Octavia");
        assertThat(response.getYearOfManufacture()).isEqualTo((short) 2014);
        assertThat(response.getFirstRegistrationDate()).isEqualTo(LocalDate.of(2014, 3, 20));
        assertThat(response.getFuelType()).isEqualTo(FuelType.DIESEL);
        assertThat(response.getTransmission()).isEqualTo(TransmissionType.MANUAL);
        assertThat(response.getEngineCode()).isEqualTo("CJCA");
        assertThat(response.getEngineDisplacementCcm()).isEqualTo(1968);
        assertThat(response.getEnginePowerKw()).isEqualTo((short) 110);
        assertThat(response.getColor()).isEqualTo("stříbrná");
        assertThat(response.getInternalNote()).isEqualTo("stálý zákazník");
        assertThat(response.getCurrentMileageKm()).isEqualTo(150_000);
        assertThat(response.getStkValidUntil()).isEqualTo(LocalDate.of(2027, 6, 30));
        assertThat(response.isActive()).isTrue();
        assertThat(response.getCreatedAt()).isEqualTo(OffsetDateTime.parse("2026-01-02T10:15:30Z"));
        assertThat(response.getUpdatedAt()).isEqualTo(OffsetDateTime.parse("2026-02-03T08:00:00Z"));

        assertThat(response.getCustomerId()).isEqualTo(3L);
        assertThat(response.getCustomer()).isNotNull();
        assertThat(response.getCustomer().getId()).isEqualTo(3L);
        assertThat(response.getCustomer().getDisplayName()).isEqualTo("Jan Novák");
    }

    @Test
    @DisplayName("toDetailResponse(null) → null")
    void toDetailResponse_null_returnsNull() {
        assertThat(converter.toDetailResponse(null)).isNull();
    }

    @Test
    @DisplayName("vozidlo bez načteného majitele nespadne na NPE (TD-55)")
    void toDetailResponse_vehicleWithoutCustomer_doesNotThrow() {
        Vehicle vehicle = vehicleWithOwner();
        vehicle.setId(11L);
        vehicle.setCustomer(null); // majitel nenačtený (např. projekce bez JOINu)

        VehicleDto.DetailResponse response = converter.toDetailResponse(vehicle);

        assertThat(response.getId()).isEqualTo(11L);
        assertThat(response.getCustomer()).as("bez načteného majitele zůstane null, ne pád").isNull();
    }

    @Test
    @DisplayName("toListResponses zachová pořadí a doplní jméno majitele")
    void toListResponses_mapsRowsInOrderWithOwnerName() {
        Vehicle octavia = vehicleWithOwner();
        octavia.setId(11L);
        octavia.setActive(true);
        octavia.setCurrentMileageKm(150_000);
        octavia.setStkValidUntil(LocalDate.of(2027, 6, 30));
        octavia.setCreatedAt(OffsetDateTime.parse("2026-01-02T10:15:30Z"));

        Vehicle superb = vehicleWithOwner();
        superb.setId(12L);
        superb.setModel("Superb");
        superb.setVin("TMBJJ7NE0E0999999");
        superb.setActive(false);

        List<VehicleDto.ListResponse> result = converter.toListResponses(List.of(octavia, superb));

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getId()).isEqualTo(11L);
        assertThat(result.get(0).getVin()).isEqualTo("TMBJJ7NE0E0123456");
        assertThat(result.get(0).getLicensePlate()).isEqualTo("1AB 2345");
        assertThat(result.get(0).getBrand()).isEqualTo("Škoda");
        assertThat(result.get(0).getModel()).isEqualTo("Octavia");
        assertThat(result.get(0).getYearOfManufacture()).isEqualTo((short) 2014);
        assertThat(result.get(0).getFuelType()).isEqualTo(FuelType.DIESEL);
        assertThat(result.get(0).getColor()).isEqualTo("stříbrná");
        assertThat(result.get(0).getCurrentMileageKm()).isEqualTo(150_000);
        assertThat(result.get(0).getStkValidUntil()).isEqualTo(LocalDate.of(2027, 6, 30));
        assertThat(result.get(0).getCreatedAt()).isEqualTo(OffsetDateTime.parse("2026-01-02T10:15:30Z"));
        assertThat(result.get(0).isActive()).isTrue();
        assertThat(result.get(0).getCustomerId()).isEqualTo(3L);
        assertThat(result.get(0).getCustomer()).isNotNull();
        assertThat(result.get(0).getCustomer().getId()).isEqualTo(3L);
        assertThat(result.get(0).getCustomerDisplayName()).isEqualTo("Jan Novák");

        assertThat(result.get(1).getId()).isEqualTo(12L);
        assertThat(result.get(1).getVin()).isEqualTo("TMBJJ7NE0E0999999");
        assertThat(result.get(1).getModel()).isEqualTo("Superb");
        assertThat(result.get(1).isActive()).isFalse();
    }

    @Test
    @DisplayName("toSummaryResponse naplní všech osm polí záznamu")
    void toSummaryResponse_mapsAllRecordComponents() {
        Vehicle vehicle = vehicleWithOwner();
        vehicle.setId(11L);
        vehicle.setCurrentMileageKm(150_000);
        vehicle.setActive(true);

        VehicleDto.SummaryResponse summary = converter.toSummaryResponse(vehicle);

        assertThat(summary.id()).isEqualTo(11L);
        assertThat(summary.vin()).isEqualTo("TMBJJ7NE0E0123456");
        assertThat(summary.licensePlate()).isEqualTo("1AB 2345");
        assertThat(summary.brand()).isEqualTo("Škoda");
        assertThat(summary.model()).isEqualTo("Octavia");
        assertThat(summary.yearOfManufacture()).isEqualTo((short) 2014);
        assertThat(summary.currentMileageKm()).isEqualTo(150_000);
        assertThat(summary.active()).isTrue();
    }

    @Test
    @DisplayName("toSummaryResponse(null) → null")
    void toSummaryResponse_null_returnsNull() {
        assertThat(converter.toSummaryResponse(null)).isNull();
    }

    // =========================================================================
    // Fixtury
    // =========================================================================

    private static Vehicle vehicleWithOwner() {
        Customer owner = new Customer();
        owner.setId(3L);
        owner.setCustomerType(CustomerType.INDIVIDUAL);
        owner.setFirstName("Jan");
        owner.setLastName("Novák");
        owner.setActive(true);

        Vehicle vehicle = new Vehicle();
        vehicle.setVin("TMBJJ7NE0E0123456");
        vehicle.setLicensePlate("1AB 2345");
        vehicle.setBrand("Škoda");
        vehicle.setModel("Octavia");
        vehicle.setYearOfManufacture((short) 2014);
        vehicle.setFuelType(FuelType.DIESEL);
        vehicle.setColor("stříbrná");
        vehicle.setCustomerId(3L);
        vehicle.setCustomer(owner);
        return vehicle;
    }
}
