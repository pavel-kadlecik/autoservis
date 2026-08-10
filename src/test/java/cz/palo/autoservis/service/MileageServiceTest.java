package cz.palo.autoservis.service;

import cz.palo.autoservis.AbstractIntegrationTest;
import cz.palo.autoservis.exception.BusinessRuleException;
import cz.palo.autoservis.exception.ResourceNotFoundException;
import cz.palo.autoservis.model.dto.vehicle.MileageDto;
import cz.palo.autoservis.model.dto.vehicle.VehicleDto;
import cz.palo.autoservis.model.enums.FuelType;
import cz.palo.autoservis.model.enums.MileageSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Historie tachometru ({@code MileageServiceImpl}) proti reálné DB.
 *
 * <p>Pravidla kolem zdroje {@code INITIAL} tvoří malý stavový automat a testují se v obou
 * větvích: INITIAL smí vzniknout jen jako <em>první</em> záznam vozidla, smí na svém záznamu
 * zůstat při editaci, ale nesmí se přeštítkovat na jiný záznam a nesmí se smazat.
 *
 * <p>Testuje se i <strong>vlastnictví</strong>: záznam cizího vozidla se pod danou cestou
 * chová jako neexistující (404), ne jako cizí zdroj.
 */
@Transactional
class MileageServiceTest extends AbstractIntegrationTest {

    private static final long VEHICLE_ID = 1L;
    private static final long OTHER_VEHICLE_ID = 2L;
    private static final long USER_ID = 1L;

    @Autowired
    private MileageService mileageService;

    @Autowired
    private VehicleService vehicleService;

    // =========================================================================
    // addReading
    // =========================================================================

    @Test
    @DisplayName("addReading uloží záznam, doplní createdBy ze serveru a vrátí ho z DB")
    void addReading_persistsReadingWithServerSideAudit() {
        MileageDto.Response created = mileageService.addReading(
                VEHICLE_ID, createRequest(150_000, LocalDate.of(2026, 7, 1), MileageSource.SERVICE), USER_ID);

        assertThat(created.getId()).isNotNull();
        assertThat(created.getVehicleId()).isEqualTo(VEHICLE_ID);
        assertThat(created.getMileageKm()).isEqualTo(150_000);
        assertThat(created.getRecordedDate()).isEqualTo(LocalDate.of(2026, 7, 1));
        assertThat(created.getSource()).isEqualTo(MileageSource.SERVICE);
        assertThat(created.getCreatedBy()).isEqualTo(USER_ID);
    }

    @Test
    @DisplayName("addReading bez data doplní dnešek (default řeší service, ne konvertor)")
    void addReading_withoutDate_defaultsToToday() {
        MileageDto.Response created = mileageService.addReading(
                VEHICLE_ID, createRequest(150_000, null, MileageSource.SERVICE), USER_ID);

        assertThat(created.getRecordedDate()).isEqualTo(LocalDate.now());
    }

    @Test
    @DisplayName("addReading pro neexistující vozidlo → ResourceNotFoundException (404)")
    void addReading_unknownVehicle_throwsResourceNotFound() {
        assertThatThrownBy(() -> mileageService.addReading(
                999_999L, createRequest(150_000, null, MileageSource.SERVICE), USER_ID))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("INITIAL jako první záznam vozidla projde")
    void addReading_initialAsFirstReading_isAccepted() {
        long emptyVehicleId = vehicleWithoutReadings();

        MileageDto.Response created = mileageService.addReading(
                emptyVehicleId, createRequest(1_000, null, MileageSource.INITIAL), USER_ID);

        assertThat(created.getSource()).isEqualTo(MileageSource.INITIAL);
    }

    @Test
    @DisplayName("INITIAL na vozidle, které už záznam má → INVALID_MILEAGE_SOURCE (422)")
    void addReading_initialWhenReadingsExist_isRejected() {
        long emptyVehicleId = vehicleWithoutReadings();
        mileageService.addReading(emptyVehicleId, createRequest(1_000, null, MileageSource.SERVICE), USER_ID);

        assertThatThrownBy(() -> mileageService.addReading(
                emptyVehicleId, createRequest(2_000, null, MileageSource.INITIAL), USER_ID))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(ex -> {
                    BusinessRuleException e = (BusinessRuleException) ex;
                    assertThat(e.getRuleCode()).isEqualTo("INVALID_MILEAGE_SOURCE");
                    assertThat(e.getField()).isEqualTo("source");
                });
    }

    @Test
    @DisplayName("běžný zdroj na vozidle se záznamy projde (druhá větev pravidla INITIAL)")
    void addReading_nonInitialWhenReadingsExist_isAccepted() {
        long emptyVehicleId = vehicleWithoutReadings();
        mileageService.addReading(emptyVehicleId, createRequest(1_000, null, MileageSource.INITIAL), USER_ID);

        MileageDto.Response second = mileageService.addReading(
                emptyVehicleId, createRequest(2_000, null, MileageSource.CUSTOMER), USER_ID);

        assertThat(second.getSource()).isEqualTo(MileageSource.CUSTOMER);
        assertThat(mileageService.findByVehicleId(emptyVehicleId)).hasSize(2);
    }

    // =========================================================================
    // updateReading
    // =========================================================================

    @Test
    @DisplayName("updateReading přepíše hodnoty a vrátí čerstvý stav z DB")
    void updateReading_overwritesValues() {
        MileageDto.Response created = mileageService.addReading(
                VEHICLE_ID, createRequest(150_000, LocalDate.of(2026, 7, 1), MileageSource.SERVICE), USER_ID);

        MileageDto.UpdateRequest request = updateRequest(
                155_000, LocalDate.of(2026, 7, 5), MileageSource.CUSTOMER, "opraveno podle protokolu");

        MileageDto.Response updated =
                mileageService.updateReading(VEHICLE_ID, created.getId(), request, USER_ID);

        assertThat(updated.getMileageKm()).isEqualTo(155_000);
        assertThat(updated.getRecordedDate()).isEqualTo(LocalDate.of(2026, 7, 5));
        assertThat(updated.getSource()).isEqualTo(MileageSource.CUSTOMER);
        assertThat(updated.getNote()).isEqualTo("opraveno podle protokolu");
        assertThat(updated.getVehicleId()).as("vlastník se nemění").isEqualTo(VEHICLE_ID);
    }

    @Test
    @DisplayName("běžný záznam nelze přeštítkovat na INITIAL → INVALID_MILEAGE_SOURCE (422)")
    void updateReading_relabelToInitial_isRejected() {
        MileageDto.Response created = mileageService.addReading(
                VEHICLE_ID, createRequest(150_000, null, MileageSource.SERVICE), USER_ID);

        MileageDto.UpdateRequest request = updateRequest(150_000, null, MileageSource.INITIAL, null);

        assertThatThrownBy(() -> mileageService.updateReading(VEHICLE_ID, created.getId(), request, USER_ID))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(ex -> assertThat(((BusinessRuleException) ex).getRuleCode())
                        .isEqualTo("INVALID_MILEAGE_SOURCE"));
    }

    @Test
    @DisplayName("INITIAL záznam zůstává editovatelný (druhá větev pravidla)")
    void updateReading_initialStaysEditable() {
        long emptyVehicleId = vehicleWithoutReadings();
        MileageDto.Response initial = mileageService.addReading(
                emptyVehicleId, createRequest(1_000, null, MileageSource.INITIAL), USER_ID);

        MileageDto.Response updated = mileageService.updateReading(
                emptyVehicleId, initial.getId(),
                updateRequest(1_500, null, MileageSource.INITIAL, "oprava překlepu"), USER_ID);

        assertThat(updated.getMileageKm()).isEqualTo(1_500);
        assertThat(updated.getSource()).isEqualTo(MileageSource.INITIAL);
        assertThat(updated.getNote()).isEqualTo("oprava překlepu");
    }

    @Test
    @DisplayName("updateReading záznamu jiného vozidla → 404 (nesmí jít měnit cizí historii)")
    void updateReading_readingOfAnotherVehicle_throwsResourceNotFound() {
        MileageDto.Response created = mileageService.addReading(
                VEHICLE_ID, createRequest(150_000, null, MileageSource.SERVICE), USER_ID);

        assertThatThrownBy(() -> mileageService.updateReading(
                OTHER_VEHICLE_ID, created.getId(),
                updateRequest(999_999, null, MileageSource.SERVICE, null), USER_ID))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("updateReading neexistujícího záznamu → ResourceNotFoundException (404)")
    void updateReading_unknownReading_throwsResourceNotFound() {
        assertThatThrownBy(() -> mileageService.updateReading(
                VEHICLE_ID, 999_999L, updateRequest(1, null, MileageSource.SERVICE, null), USER_ID))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // =========================================================================
    // deleteReading
    // =========================================================================

    @Test
    @DisplayName("deleteReading smaže běžný záznam")
    void deleteReading_removesOrdinaryReading() {
        MileageDto.Response created = mileageService.addReading(
                VEHICLE_ID, createRequest(150_000, null, MileageSource.SERVICE), USER_ID);
        int before = mileageService.findByVehicleId(VEHICLE_ID).size();

        mileageService.deleteReading(VEHICLE_ID, created.getId());

        List<MileageDto.Response> after = mileageService.findByVehicleId(VEHICLE_ID);
        assertThat(after).hasSize(before - 1);
        assertThat(after).extracting(MileageDto.Response::getId).doesNotContain(created.getId());
    }

    @Test
    @DisplayName("INITIAL záznam nelze smazat → CANNOT_DELETE_INITIAL (422)")
    void deleteReading_initialBaseline_isRejected() {
        long emptyVehicleId = vehicleWithoutReadings();
        MileageDto.Response initial = mileageService.addReading(
                emptyVehicleId, createRequest(1_000, null, MileageSource.INITIAL), USER_ID);

        assertThatThrownBy(() -> mileageService.deleteReading(emptyVehicleId, initial.getId()))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(ex -> assertThat(((BusinessRuleException) ex).getRuleCode())
                        .isEqualTo("CANNOT_DELETE_INITIAL"));

        assertThat(mileageService.findByVehicleId(emptyVehicleId))
                .as("záznam musí zůstat").hasSize(1);
    }

    @Test
    @DisplayName("deleteReading záznamu jiného vozidla → 404")
    void deleteReading_readingOfAnotherVehicle_throwsResourceNotFound() {
        MileageDto.Response created = mileageService.addReading(
                VEHICLE_ID, createRequest(150_000, null, MileageSource.SERVICE), USER_ID);

        assertThatThrownBy(() -> mileageService.deleteReading(OTHER_VEHICLE_ID, created.getId()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // =========================================================================
    // findByVehicleId
    // =========================================================================

    @Test
    @DisplayName("findByVehicleId vrátí záznamy daného vozidla, cizí ne")
    void findByVehicleId_returnsOnlyOwnReadings() {
        MileageDto.Response mine = mileageService.addReading(
                VEHICLE_ID, createRequest(150_000, null, MileageSource.SERVICE), USER_ID);
        MileageDto.Response foreign = mileageService.addReading(
                OTHER_VEHICLE_ID, createRequest(60_000, null, MileageSource.SERVICE), USER_ID);

        List<MileageDto.Response> readings = mileageService.findByVehicleId(VEHICLE_ID);

        assertThat(readings).isNotEmpty();
        assertThat(readings).extracting(MileageDto.Response::getId).contains(mine.getId());
        assertThat(readings).extracting(MileageDto.Response::getId).doesNotContain(foreign.getId());
        assertThat(readings).extracting(MileageDto.Response::getVehicleId).containsOnly(VEHICLE_ID);
    }

    @Test
    @DisplayName("findByVehicleId pro neexistující vozidlo → ResourceNotFoundException (404)")
    void findByVehicleId_unknownVehicle_throwsResourceNotFound() {
        assertThatThrownBy(() -> mileageService.findByVehicleId(999_999L))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // =========================================================================
    // Fixtury
    // =========================================================================

    /**
     * Založí nové vozidlo bez počátečního stavu tachometru. Všechna vozidla ze seedu už
     * záznam mají (migrace je doplnila), takže pravidla kolem INITIAL — která platí jen na
     * prázdné historii — jde otestovat pouze na čerstvém vozidle.
     */
    private long vehicleWithoutReadings() {
        VehicleDto.CreateRequest request = new VehicleDto.CreateRequest();
        request.setCustomerId(1L);
        request.setVin("VF1RJB00X66123456");
        request.setBrand("Renault");
        request.setModel("Mégane");
        request.setFuelType(FuelType.PETROL);
        // initialMileageKm schválně nevyplněné → vozidlo vznikne bez záznamu tachometru

        Long vehicleId = vehicleService.create(request, USER_ID).getId();
        assertThat(mileageService.findByVehicleId(vehicleId))
                .as("předpoklad testu: nové vozidlo nemá žádný záznam").isEmpty();
        return vehicleId;
    }

    private static MileageDto.CreateRequest createRequest(int mileageKm, LocalDate recordedDate,
                                                          MileageSource source) {
        MileageDto.CreateRequest request = new MileageDto.CreateRequest();
        request.setMileageKm(mileageKm);
        request.setRecordedDate(recordedDate);
        request.setSource(source);
        return request;
    }

    private static MileageDto.UpdateRequest updateRequest(int mileageKm, LocalDate recordedDate,
                                                          MileageSource source, String note) {
        MileageDto.UpdateRequest request = new MileageDto.UpdateRequest();
        request.setMileageKm(mileageKm);
        request.setRecordedDate(recordedDate);
        request.setSource(source);
        request.setNote(note);
        return request;
    }
}
