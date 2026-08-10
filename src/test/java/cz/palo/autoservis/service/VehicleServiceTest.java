package cz.palo.autoservis.service;

import cz.palo.autoservis.AbstractIntegrationTest;
import cz.palo.autoservis.exception.BusinessRuleException;
import cz.palo.autoservis.exception.ResourceNotFoundException;
import cz.palo.autoservis.mapper.CustomerMapper;
import cz.palo.autoservis.mapper.VehicleMapper;
import cz.palo.autoservis.model.dto.vehicle.MileageDto;
import cz.palo.autoservis.model.dto.vehicle.VehicleDto;
import cz.palo.autoservis.model.enums.FuelType;
import cz.palo.autoservis.model.enums.MileageSource;
import cz.palo.autoservis.model.enums.TransmissionType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * CRUD vozidel ({@code VehicleServiceImpl}) proti reálné DB.
 *
 * <p>Pokrývá business pravidla, která nejsou v anotacích DTO a dají se porušit jen za běhu:
 * jedinečnost VIN, konzistenci roku výroby vůči první registraci, zákaz deaktivace vozidla
 * s otevřenou zakázkou a doplnění auditu {@code created_by} <strong>serverem</strong>, ne z DTO.
 *
 * <p>Seed (V8): vozidlo 1 (BMW, zákazník 1) má jen uzavřené zakázky; vozidlo 7 má otevřenou
 * zakázku ZAK-2026-0001 (stav RECEIVED).
 */
@Transactional
class VehicleServiceTest extends AbstractIntegrationTest {

    private static final long CUSTOMER_ID = 1L;
    private static final long VEHICLE_WITHOUT_OPEN_ORDERS = 1L;
    private static final long VEHICLE_WITH_OPEN_ORDER = 7L;
    private static final long USER_ID = 1L;
    private static final String SEEDED_VIN = "WBA3A5C50DF595551";
    private static final String FREE_VIN = "VF1RJB00X66123456";

    @Autowired
    private VehicleService vehicleService;

    @Autowired
    private MileageService mileageService;

    @Autowired
    private VehicleMapper vehicleMapper;

    @Autowired
    private CustomerService customerService;

    @Autowired
    private CustomerMapper customerMapper;

    // =========================================================================
    // create
    // =========================================================================

    @Test
    @DisplayName("create založí vozidlo, doplní createdBy ze serveru a nastaví ho jako aktivní")
    void create_persistsVehicleWithServerSideAudit() {
        VehicleDto.CreateRequest request = createRequest(CUSTOMER_ID, FREE_VIN);
        request.setLicensePlate("9XY 8765");
        request.setColor("modrá");
        request.setInternalNote("nové vozidlo");

        VehicleDto.DetailResponse created = vehicleService.create(request, USER_ID);

        assertThat(created.getId()).isNotNull();
        assertThat(created.getVin()).isEqualTo(FREE_VIN);
        assertThat(created.getLicensePlate()).isEqualTo("9XY 8765");
        assertThat(created.getBrand()).isEqualTo("Renault");
        assertThat(created.getColor()).isEqualTo("modrá");
        assertThat(created.isActive()).isTrue();
        assertThat(created.getCustomerId()).isEqualTo(CUSTOMER_ID);

        // audit doplňuje server z principalu, ne klient (R-04 / N-06)
        assertThat(vehicleMapper.findByIdIncludingInactive(created.getId()).orElseThrow().getCreatedBy())
                .isEqualTo(USER_ID);
    }

    @Test
    @DisplayName("create s initialMileageKm založí i výchozí záznam tachometru se zdrojem INITIAL")
    void create_withInitialMileage_createsBaselineReading() {
        VehicleDto.CreateRequest request = createRequest(CUSTOMER_ID, FREE_VIN);
        request.setInitialMileageKm(123_456);

        VehicleDto.DetailResponse created = vehicleService.create(request, USER_ID);

        List<MileageDto.Response> readings = mileageService.findByVehicleId(created.getId());
        assertThat(readings).hasSize(1);
        assertThat(readings.getFirst().getMileageKm()).isEqualTo(123_456);
        assertThat(readings.getFirst().getSource()).isEqualTo(MileageSource.INITIAL);
        assertThat(readings.getFirst().getCreatedBy()).isEqualTo(USER_ID);
    }

    @Test
    @DisplayName("create bez initialMileageKm žádný záznam tachometru nezaloží")
    void create_withoutInitialMileage_createsNoReading() {
        VehicleDto.DetailResponse created =
                vehicleService.create(createRequest(CUSTOMER_ID, FREE_VIN), USER_ID);

        assertThat(mileageService.findByVehicleId(created.getId())).isEmpty();
    }

    @Test
    @DisplayName("create bez VIN (V90) projde — stroj s výrobním číslem místo VIN")
    void create_withoutVin_persistsMachine() {
        VehicleDto.CreateRequest request = createRequest(CUSTOMER_ID, null);
        request.setBrand("Husqvarna");
        request.setModel("TC 242T");
        request.setFuelType(null);
        request.setTransmission(null);
        request.setMachineSerialNumber("HQV-2024-001234");

        VehicleDto.DetailResponse created = vehicleService.create(request, USER_ID);

        assertThat(created.getVin()).isNull();
        assertThat(created.getMachineSerialNumber()).isEqualTo("HQV-2024-001234");
        assertThat(created.isActive()).isTrue();
    }

    @Test
    @DisplayName("dva stroje bez VIN vedle sebe — UNIQUE má NULLy navzájem různé (V90)")
    void create_twoVehiclesWithoutVin_bothPersist() {
        VehicleDto.CreateRequest first = createRequest(CUSTOMER_ID, null);
        VehicleDto.CreateRequest second = createRequest(CUSTOMER_ID, null);

        VehicleDto.DetailResponse a = vehicleService.create(first, USER_ID);
        VehicleDto.DetailResponse b = vehicleService.create(second, USER_ID);

        assertThat(a.getId()).isNotEqualTo(b.getId());
        assertThat(a.getVin()).isNull();
        assertThat(b.getVin()).isNull();
    }

    @Test
    @DisplayName("create s prázdným VIN z formuláře uloží NULL, ne '' (V90, vzor V81)")
    void create_blankVin_normalizedToNull() {
        VehicleDto.CreateRequest request = createRequest(CUSTOMER_ID, "");

        VehicleDto.DetailResponse created = vehicleService.create(request, USER_ID);

        assertThat(created.getVin()).isNull();
    }

    @Test
    @DisplayName("create s již existujícím VIN → DUPLICATE_VIN (422)")
    void create_duplicateVin_throwsBusinessRule() {
        assertThatThrownBy(() -> vehicleService.create(createRequest(CUSTOMER_ID, SEEDED_VIN), USER_ID))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(ex -> {
                    BusinessRuleException e = (BusinessRuleException) ex;
                    assertThat(e.getRuleCode()).isEqualTo("DUPLICATE_VIN");
                    assertThat(e.getField()).isEqualTo("vin");
                });
    }

    @Test
    @DisplayName("create pro neexistujícího zákazníka → ResourceNotFoundException (404)")
    void create_unknownCustomer_throwsResourceNotFound() {
        assertThatThrownBy(() -> vehicleService.create(createRequest(999_999L, FREE_VIN), USER_ID))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("create: rok výroby po roce první registrace → INVALID_YEAR_OF_MANUFACTURE (422)")
    void create_yearOfManufactureAfterRegistration_isRejected() {
        VehicleDto.CreateRequest request = createRequest(CUSTOMER_ID, FREE_VIN);
        request.setYearOfManufacture((short) 2020);
        request.setFirstRegistrationDate(LocalDate.of(2019, 5, 1));

        assertThatThrownBy(() -> vehicleService.create(request, USER_ID))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(ex -> assertThat(((BusinessRuleException) ex).getRuleCode())
                        .isEqualTo("INVALID_YEAR_OF_MANUFACTURE"));
    }

    @Test
    @DisplayName("create: rok výroby shodný s rokem registrace projde (hranice pravidla)")
    void create_yearOfManufactureSameAsRegistrationYear_isAccepted() {
        VehicleDto.CreateRequest request = createRequest(CUSTOMER_ID, FREE_VIN);
        request.setYearOfManufacture((short) 2019);
        request.setFirstRegistrationDate(LocalDate.of(2019, 5, 1));

        VehicleDto.DetailResponse created = vehicleService.create(request, USER_ID);

        assertThat(created.getYearOfManufacture()).isEqualTo((short) 2019);
    }

    // =========================================================================
    // update
    // =========================================================================

    @Test
    @DisplayName("update změní editovatelná pole a vrátí čerstvě načtený stav")
    void update_changesEditableFields() {
        VehicleDto.UpdateRequest request = updateRequestFrom(VEHICLE_WITHOUT_OPEN_ORDERS);
        request.setLicensePlate("5ZZ 9999");
        request.setColor("bílá");
        request.setInternalNote("přelakováno");

        VehicleDto.DetailResponse updated = vehicleService.update(VEHICLE_WITHOUT_OPEN_ORDERS, request, USER_ID);

        assertThat(updated.getLicensePlate()).isEqualTo("5ZZ 9999");
        assertThat(updated.getColor()).isEqualTo("bílá");
        assertThat(updated.getInternalNote()).isEqualTo("přelakováno");
        assertThat(vehicleMapper.findById(VEHICLE_WITHOUT_OPEN_ORDERS).orElseThrow().getLicensePlate())
                .isEqualTo("5ZZ 9999");
    }

    @Test
    @DisplayName("update na VIN jiného vozidla → DUPLICATE_VIN (422)")
    void update_vinTakenByAnotherVehicle_throwsBusinessRule() {
        VehicleDto.UpdateRequest request = updateRequestFrom(VEHICLE_WITHOUT_OPEN_ORDERS);
        request.setVin("TMBKG6NW2L7234565"); // VIN vozidla 2 ze seedu

        assertThatThrownBy(() -> vehicleService.update(VEHICLE_WITHOUT_OPEN_ORDERS, request, USER_ID))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(ex -> assertThat(((BusinessRuleException) ex).getRuleCode())
                        .isEqualTo("DUPLICATE_VIN"));
    }

    @Test
    @DisplayName("update ponechávající vlastní VIN projde (nekoliduje sám se sebou)")
    void update_keepingOwnVin_succeeds() {
        VehicleDto.UpdateRequest request = updateRequestFrom(VEHICLE_WITHOUT_OPEN_ORDERS);
        request.setColor("zelená");

        VehicleDto.DetailResponse updated = vehicleService.update(VEHICLE_WITHOUT_OPEN_ORDERS, request, USER_ID);

        assertThat(updated.getVin()).isEqualTo(SEEDED_VIN);
        assertThat(updated.getColor()).isEqualTo("zelená");
    }

    @Test
    @DisplayName("update na neexistujícího zákazníka → ResourceNotFoundException (404)")
    void update_unknownCustomer_throwsResourceNotFound() {
        VehicleDto.UpdateRequest request = updateRequestFrom(VEHICLE_WITHOUT_OPEN_ORDERS);
        request.setCustomerId(999_999L);

        assertThatThrownBy(() -> vehicleService.update(VEHICLE_WITHOUT_OPEN_ORDERS, request, USER_ID))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("update neexistujícího vozidla → ResourceNotFoundException (404)")
    void update_unknownVehicle_throwsResourceNotFound() {
        VehicleDto.UpdateRequest request = updateRequestFrom(VEHICLE_WITHOUT_OPEN_ORDERS);

        assertThatThrownBy(() -> vehicleService.update(999_999L, request, USER_ID))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // =========================================================================
    // deactivate / activate
    // =========================================================================

    @Test
    @DisplayName("deactivate vozidla bez otevřených zakázek projde a je vratná (soft-delete)")
    void deactivate_thenActivate_restoresVehicle() {
        VehicleDto.DetailResponse deactivated = vehicleService.deactivate(VEHICLE_WITHOUT_OPEN_ORDERS);
        assertThat(deactivated.isActive()).isFalse();
        assertThat(vehicleMapper.findById(VEHICLE_WITHOUT_OPEN_ORDERS))
                .as("strict findById deaktivované vozidlo nevrací").isEmpty();

        VehicleDto.DetailResponse reactivated = vehicleService.activate(VEHICLE_WITHOUT_OPEN_ORDERS);
        assertThat(reactivated.isActive()).isTrue();
        assertThat(vehicleMapper.findById(VEHICLE_WITHOUT_OPEN_ORDERS)).isPresent();
    }

    @Test
    @DisplayName("deactivate vozidla s otevřenou zakázkou → VEHICLE_HAS_OPEN_ORDERS (422)")
    void deactivate_withOpenOrder_isRejected() {
        assertThatThrownBy(() -> vehicleService.deactivate(VEHICLE_WITH_OPEN_ORDER))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(ex -> {
                    BusinessRuleException e = (BusinessRuleException) ex;
                    assertThat(e.getRuleCode()).isEqualTo("VEHICLE_HAS_OPEN_ORDERS");
                    assertThat(e.getParams()).containsKey("openOrders");
                });

        assertThat(vehicleMapper.findById(VEHICLE_WITH_OPEN_ORDER))
                .as("vozidlo musí zůstat aktivní").isPresent();
    }

    @Test
    @DisplayName("deactivate neexistujícího vozidla → ResourceNotFoundException (404)")
    void deactivate_unknownId_throwsResourceNotFound() {
        assertThatThrownBy(() -> vehicleService.deactivate(999_999L))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("deactivateByCustomerId deaktivuje všechna vozidla zákazníka a vrátí jejich počet")
    void deactivateByCustomerId_deactivatesAllOwnedVehicles() {
        int before = vehicleService.findByCustomerId(CUSTOMER_ID).size();
        assertThat(before).as("fixtura musí být neprázdná, jinak test nic nedokazuje").isPositive();

        int affected = vehicleService.deactivateByCustomerId(CUSTOMER_ID);

        assertThat(affected).isEqualTo(before);
        assertThat(vehicleService.findByCustomerId(CUSTOMER_ID))
                .as("findByCustomerId vrací jen aktivní").isEmpty();
    }

    // =========================================================================
    // getById
    // =========================================================================

    @Test
    @DisplayName("getById vrátí vozidlo i se shrnutím majitele")
    void getById_returnsVehicleWithOwnerSummary() {
        VehicleDto.DetailResponse response = vehicleService.getById(VEHICLE_WITHOUT_OPEN_ORDERS);

        assertThat(response.getId()).isEqualTo(VEHICLE_WITHOUT_OPEN_ORDERS);
        assertThat(response.getVin()).isEqualTo(SEEDED_VIN);
        assertThat(response.getBrand()).isEqualTo("BMW");
        assertThat(response.getCustomer()).isNotNull();
        assertThat(response.getCustomer().getDisplayName()).isEqualTo("Jan Novák");
    }

    @Test
    @DisplayName("customer.active odráží majitele, ne vozidlo — TD-56 (duchový zákazník)")
    void getById_customerActiveReflectsOwnerNotVehicle() {
        // Majitele deaktivujeme PŘÍMO mapperem (bez kaskády služby na vozidla) → divergence:
        // vozidlo zůstává aktivní, majitel ne. Před opravou TD-56 kolidoval sloupec is_active
        // (v i c) a customer.active nesl stav VOZIDLA (true); po aliasu cust_* nese stav majitele.
        customerMapper.deactivate(CUSTOMER_ID);

        VehicleDto.DetailResponse response = vehicleService.getById(VEHICLE_WITHOUT_OPEN_ORDERS);

        assertThat(response.isActive()).as("vozidlo je pořád aktivní").isTrue();
        assertThat(response.getCustomer()).isNotNull();
        assertThat(response.getCustomer().isActive())
                .as("customer.active = stav deaktivovaného majitele, ne aktivního vozidla")
                .isFalse();
    }

    @Test
    @DisplayName("getById deaktivovaného vozidla → 404 (strict findById, R-10)")
    void getById_deactivatedVehicle_throwsResourceNotFound() {
        vehicleService.deactivate(VEHICLE_WITHOUT_OPEN_ORDERS);

        assertThatThrownBy(() -> vehicleService.getById(VEHICLE_WITHOUT_OPEN_ORDERS))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("getById neexistujícího vozidla → ResourceNotFoundException (404)")
    void getById_unknownId_throwsResourceNotFound() {
        assertThatThrownBy(() -> vehicleService.getById(999_999L))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("update: rok výroby po roce první registrace → INVALID_YEAR_OF_MANUFACTURE (422)")
    void update_yearOfManufactureAfterRegistration_isRejected() {
        VehicleDto.UpdateRequest request = updateRequestFrom(VEHICLE_WITHOUT_OPEN_ORDERS);
        request.setYearOfManufacture((short) 2020);
        request.setFirstRegistrationDate(LocalDate.of(2019, 5, 1));

        assertThatThrownBy(() -> vehicleService.update(VEHICLE_WITHOUT_OPEN_ORDERS, request, USER_ID))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(ex -> assertThat(((BusinessRuleException) ex).getRuleCode())
                        .isEqualTo("INVALID_YEAR_OF_MANUFACTURE"));
    }

    // =========================================================================
    // getPage / autocomplete
    // =========================================================================

    @Test
    @DisplayName("getPage vrátí stránku vozidel i s celkovým počtem")
    void getPage_returnsPagedVehicles() {
        cz.palo.autoservis.model.dto.vehicle.VehicleSearchParams params =
                new cz.palo.autoservis.model.dto.vehicle.VehicleSearchParams();
        params.setPage(1);
        params.setPageSize(3);

        var page = vehicleService.getPage(params);

        assertThat(page.getContent()).hasSize(3);
        assertThat(page.getPageSize()).isEqualTo(3);
        assertThat(page.getTotalElements()).as("seed má 20 vozidel").isGreaterThanOrEqualTo(20);
        assertThat(page.getContent()).allSatisfy(vehicle ->
                assertThat(vehicle.getVin()).isNotBlank());
    }

    @Test
    @DisplayName("autocomplete vrátí položky odpovídající dotazu")
    void autocomplete_returnsMatchingItems() {
        var params = new cz.palo.autoservis.model.dto.vehicle.VehicleAutocompleteParams(
                SEEDED_VIN, 10, null);

        var response = vehicleService.autocomplete(params);

        // Nabídka nese tři řádky: „Značka Model - SPZ", majitele a VIN. Bez SPZ a majitele
        // nešlo od sebe rozeznat tři stejné vozy, když se hledá napříč všemi vozidly (V85).
        assertThat(response.getData()).hasSize(1);
        assertThat(response.getData().getFirst().getId()).isEqualTo(VEHICLE_WITHOUT_OPEN_ORDERS);
        assertThat(response.getData().getFirst().getValue()).isEqualTo("BMW 3 Series - 1AB 2345");
        assertThat(response.getData().getFirst().getDescription()).isEqualTo("Jan Novák");
        assertThat(response.getData().getFirst().getDetail()).isEqualTo(SEEDED_VIN);
        assertThat(response.isHasMore()).isFalse();
    }

    @Test
    @DisplayName("autocomplete najde vozidlo podle SPZ — hlavní identifikátor dílny")
    void autocomplete_findsByLicensePlate() {
        var params = new cz.palo.autoservis.model.dto.vehicle.VehicleAutocompleteParams(
                "1AB 2345", 10, null);

        var response = vehicleService.autocomplete(params);

        assertThat(response.getData()).hasSize(1);
        assertThat(response.getData().getFirst().getId()).isEqualTo(VEHICLE_WITHOUT_OPEN_ORDERS);
    }

    @Test
    @DisplayName("autocomplete respektuje limit a při přebytku nastaví hasMore")
    void autocomplete_respectsLimitAndFlagsMore() {
        var params = new cz.palo.autoservis.model.dto.vehicle.VehicleAutocompleteParams("", 2, null);

        var response = vehicleService.autocomplete(params);

        assertThat(response.getData())
                .as("vrátí se nejvýš tolik položek, kolik je limit").hasSize(2);
        assertThat(response.isHasMore())
                .as("v seedu je vozidel víc než 2, takže hasMore musí být true").isTrue();
    }

    @Test
    @DisplayName("autocomplete bez shody vrátí prázdný seznam a hasMore = false")
    void autocomplete_withoutMatches_returnsEmpty() {
        var params = new cz.palo.autoservis.model.dto.vehicle.VehicleAutocompleteParams(
                "NEEXISTUJICI-VIN-XYZ", 10, null);

        var response = vehicleService.autocomplete(params);

        assertThat(response.getData()).isEmpty();
        assertThat(response.isHasMore()).isFalse();
    }

    @Test
    @DisplayName("autocomplete: přesně tolik výsledků, kolik je limit → hasMore = false (hranice)")
    void autocomplete_resultCountEqualToLimit_hasMoreIsFalse() {
        // Vlastní zákazník s přesně dvěma vozidly — na seedu by počet nebyl deterministický.
        // Hranice odhalí záměnu `size > limit` za `size >= limit`, která by hlásila „další
        // výsledky" i tehdy, když už žádné nejsou.
        Long customerId = customerService.create(freshCustomerRequest(), USER_ID).getId();
        createVehicleFor(customerId, "VF1RJB00X66100001");
        createVehicleFor(customerId, "VF1RJB00X66100002");

        var params = new cz.palo.autoservis.model.dto.vehicle.VehicleAutocompleteParams("", 2, customerId);

        var response = vehicleService.autocomplete(params);

        assertThat(response.getData()).hasSize(2);
        assertThat(response.isHasMore())
                .as("víc vozidel zákazník nemá, takže hasMore musí být false").isFalse();
    }

    @Test
    @DisplayName("autocomplete s filtrem na zákazníka vrátí jen jeho vozidla")
    void autocomplete_filteredByCustomer_returnsOnlyOwnedVehicles() {
        var params = new cz.palo.autoservis.model.dto.vehicle.VehicleAutocompleteParams(
                "", 100, CUSTOMER_ID);

        var response = vehicleService.autocomplete(params);

        assertThat(response.getData()).isNotEmpty();
        List<Long> ownedIds = vehicleService.findByCustomerId(CUSTOMER_ID).stream()
                .map(VehicleDto.SummaryResponse::id).toList();
        assertThat(response.getData()).extracting("id").allSatisfy(id ->
                assertThat(ownedIds).contains((Long) id));
    }

    // =========================================================================
    // Fixtury
    // =========================================================================

    private void createVehicleFor(Long customerId, String vin) {
        vehicleService.create(createRequest(customerId, vin), USER_ID);
    }

    private static cz.palo.autoservis.model.dto.customer.CustomerDto.CreateRequest freshCustomerRequest() {
        var request = new cz.palo.autoservis.model.dto.customer.CustomerDto.CreateRequest();
        request.setCustomerType(cz.palo.autoservis.model.enums.CustomerType.INDIVIDUAL);
        request.setFirstName("Auto");
        request.setLastName("Complete");
        request.setGdprConsent(true);

        var address = new cz.palo.autoservis.model.dto.customer.AddressDto.CreateRequest();
        address.setAddressType(cz.palo.autoservis.model.enums.AddressType.BILLING);
        address.setStreet("Testovací");
        address.setStreetNumber("1");
        address.setCity("Praha");
        address.setPostalCode("110 00");
        address.setCountryCode("CZ");
        request.setAddresses(List.of(address));
        return request;
    }

    private static VehicleDto.CreateRequest createRequest(Long customerId, String vin) {
        VehicleDto.CreateRequest request = new VehicleDto.CreateRequest();
        request.setCustomerId(customerId);
        request.setVin(vin);
        request.setBrand("Renault");
        request.setModel("Mégane");
        request.setFuelType(FuelType.PETROL);
        request.setTransmission(TransmissionType.MANUAL);
        return request;
    }

    /** Update je full-replace — vychází se z aktuálního stavu, ať test mění jen to, co chce. */
    private VehicleDto.UpdateRequest updateRequestFrom(Long vehicleId) {
        VehicleDto.DetailResponse current = vehicleService.getById(vehicleId);

        VehicleDto.UpdateRequest request = new VehicleDto.UpdateRequest();
        request.setCustomerId(current.getCustomerId());
        request.setVin(current.getVin());
        request.setLicensePlate(current.getLicensePlate());
        request.setBrand(current.getBrand());
        request.setModel(current.getModel());
        request.setYearOfManufacture(current.getYearOfManufacture());
        request.setFirstRegistrationDate(current.getFirstRegistrationDate());
        request.setFuelType(current.getFuelType());
        request.setTransmission(current.getTransmission());
        request.setEngineCode(current.getEngineCode());
        request.setEngineDisplacementCcm(current.getEngineDisplacementCcm());
        request.setEnginePowerKw(current.getEnginePowerKw());
        request.setColor(current.getColor());
        request.setInternalNote(current.getInternalNote());
        return request;
    }
}
