package cz.palo.autoservis.service;

import cz.palo.autoservis.AbstractIntegrationTest;
import cz.palo.autoservis.exception.BusinessRuleException;
import cz.palo.autoservis.exception.ResourceNotFoundException;
import cz.palo.autoservis.model.dto.order.OrderDto;
import cz.palo.autoservis.model.dto.schedule.AppointmentDto;
import cz.palo.autoservis.model.enums.AppointmentStatus;
import cz.palo.autoservis.model.enums.AppointmentType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Plánovací kalendář ({@code AppointmentServiceImpl}) proti reálné DB.
 *
 * <p>Seed V73 (datumy relativní k {@code NOW()}): #1 objednávka dnes 9–10 PLANNED,
 * #2 dnes 13–15 PLANNED, #3 zítra 8–12 PLANNED, #4 <strong>blokace</strong> pozítří celý den,
 * #5 před 5 dny NO_SHOW.
 *
 * <p>Zákazník #1 Jan Novák vlastní vozidla 1, 3, 8, 9…; zákazník #2 Marie Svobodová vozidlo 4 —
 * dvojice se používá na test cizího vozidla.
 */
@Transactional
class AppointmentServiceTest extends AbstractIntegrationTest {

    private static final long USER_ID = 1L;
    private static final long CUSTOMER_NOVAK = 1L;
    private static final long VEHICLE_OF_NOVAK = 1L;
    private static final long CUSTOMER_SVOBODOVA = 2L;
    private static final long VEHICLE_OF_SVOBODOVA = 4L;

    private static final long BOOKING_FIRST = 1L;
    private static final long BOOKING_PLANNED = 2L;
    private static final long CLOSURE_ID = 4L;
    private static final long BOOKING_NO_SHOW = 5L;

    /** Seed V58 (db/demo): #1 Petr Mechanik (aktivní), #4 Martin Novák (neaktivní, odešel). */
    private static final long EMPLOYEE_MECHANIK = 1L;
    private static final long EMPLOYEE_INACTIVE = 4L;

    @Autowired
    private AppointmentService appointmentService;

    // =========================================================================
    // čtení
    // =========================================================================

    @Test
    @DisplayName("getInRange vrátí položky zasahující do okna, seřazené podle začátku")
    void getInRange_returnsOverlappingEntriesSorted() {
        List<AppointmentDto.ListResponse> result = appointmentService.getInRange(
                today().minusDays(10), today().plusDays(10), null, null);

        assertThat(result).hasSize(5);
        assertThat(result).extracting(AppointmentDto.ListResponse::getStartsAt).isSorted();
        assertThat(result).extracting(AppointmentDto.ListResponse::getTitle)
                .contains("Výměna oleje a filtrů", "Školení techniků");
    }

    @Test
    @DisplayName("getInRange zachytí i událost, která začala PŘED oknem a končí v něm")
    void getInRange_includesEntryStartingBeforeWindow() {
        // Blokace trvá celý den; okno leží až uprostřed ní, takže její začátek je mimo.
        AppointmentDto.DetailResponse closure = appointmentService.getById(CLOSURE_ID);
        List<AppointmentDto.ListResponse> result = appointmentService.getInRange(
                closure.getStartsAt().plusHours(10), closure.getStartsAt().plusHours(11),
                AppointmentType.CLOSURE, null);

        assertThat(result).extracting(AppointmentDto.ListResponse::getId).containsExactly(CLOSURE_ID);
    }

    @Test
    @DisplayName("getInRange filtruje podle typu i stavu")
    void getInRange_filters() {
        OffsetDateTime from = today().minusDays(10);
        OffsetDateTime to = today().plusDays(10);

        assertThat(appointmentService.getInRange(from, to, AppointmentType.CLOSURE, null))
                .hasSize(1);
        assertThat(appointmentService.getInRange(from, to, AppointmentType.BOOKING, AppointmentStatus.NO_SHOW))
                .extracting(AppointmentDto.ListResponse::getId).containsExactly(BOOKING_NO_SHOW);
    }

    @Test
    @DisplayName("getInRange s obráceným oknem → INVALID_RANGE")
    void getInRange_invalidWindow_throws() {
        assertThatThrownBy(() -> appointmentService.getInRange(today().plusDays(1), today(), null, null))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(ex -> assertThat(((BusinessRuleException) ex).getRuleCode())
                        .isEqualTo("INVALID_RANGE"));
    }

    @Test
    @DisplayName("getById vrátí detail včetně projekcí zákazníka a vozidla")
    void getById_includesJoinProjections() {
        AppointmentDto.DetailResponse detail = appointmentService.getById(BOOKING_FIRST);

        assertThat(detail.getEntryType()).isEqualTo(AppointmentType.BOOKING);
        assertThat(detail.getStatus()).isEqualTo(AppointmentStatus.PLANNED);
        assertThat(detail.getCustomerDisplayName()).isEqualTo("Jan Novák");
        assertThat(detail.getVehicleLicensePlate()).isEqualTo("1AB 2345");
        assertThat(detail.getVehicleBrand()).isNotBlank();
        assertThat(detail.getOrderId()).isNull();
    }

    @Test
    @DisplayName("getById neexistující → 404")
    void getById_missing_throws() {
        assertThatThrownBy(() -> appointmentService.getById(99999L))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // =========================================================================
    // create
    // =========================================================================

    @Test
    @DisplayName("create uloží objednávku a doplní createdBy ze serveru")
    void create_persistsWithServerSideAudit() {
        AppointmentDto.DetailResponse created = appointmentService.create(booking(), USER_ID);

        assertThat(created.getId()).isNotNull();
        assertThat(created.getTitle()).isEqualTo("Nová objednávka");
        assertThat(created.getStatus()).isEqualTo(AppointmentStatus.PLANNED);
        assertThat(created.getCustomerDisplayName()).isEqualTo("Jan Novák");
        assertThat(created.getOrderId()).isNull();
    }

    @Test
    @DisplayName("create blokace dílny projde bez zákazníka a vozidla")
    void create_closure_withoutCustomer() {
        AppointmentDto.CreateRequest request = new AppointmentDto.CreateRequest();
        request.setEntryType(AppointmentType.CLOSURE);
        request.setTitle("Revize zvedáku");
        request.setStartsAt(today().plusDays(20));
        request.setEndsAt(today().plusDays(21));

        AppointmentDto.DetailResponse created = appointmentService.create(request, USER_ID);

        assertThat(created.getEntryType()).isEqualTo(AppointmentType.CLOSURE);
        assertThat(created.getCustomerId()).isNull();
        assertThat(created.getVehicleId()).isNull();
    }

    @Test
    @DisplayName("create objednávky bez vozidla projde — po telefonu se neví, s čím zákazník dojede (V85)")
    void create_bookingWithoutVehicle_succeeds() {
        AppointmentDto.CreateRequest request = new AppointmentDto.CreateRequest();
        request.setEntryType(AppointmentType.BOOKING);
        request.setTitle("Objednávka bez auta");
        request.setStartsAt(today().plusDays(9).plusHours(9));
        request.setCustomerId(CUSTOMER_NOVAK);

        AppointmentDto.DetailResponse created = appointmentService.create(request, USER_ID);

        assertThat(created.getVehicleId()).isNull();
        assertThat(created.getCustomerId()).isEqualTo(CUSTOMER_NOVAK);
    }

    @Test
    @DisplayName("create objednávky bez zákazníka i vozidla projde — stačí název práce (V85)")
    void create_bookingWithoutCustomer_succeeds() {
        AppointmentDto.CreateRequest request = booking();
        request.setCustomerId(null);
        request.setVehicleId(null);

        AppointmentDto.DetailResponse created = appointmentService.create(request, USER_ID);

        assertThat(created.getCustomerId()).isNull();
        assertThat(created.getVehicleId()).isNull();
        assertThat(created.getTitle()).isEqualTo("Nová objednávka");
    }

    @Test
    @DisplayName("create objednávky s kontaktem místo zákazníka uloží kontakt (V85)")
    void create_bookingWithContactNote_succeeds() {
        AppointmentDto.CreateRequest request = booking();
        request.setCustomerId(null);
        request.setVehicleId(null);
        request.setContactNote("Nováková, 777 123 456");

        AppointmentDto.DetailResponse created = appointmentService.create(request, USER_ID);

        assertThat(created.getContactNote()).isEqualTo("Nováková, 777 123 456");
        assertThat(created.getCustomerId()).isNull();
    }

    @Test
    @DisplayName("create objednávky jen s vozidlem dopočítá zákazníka z majitele auta (V85)")
    void create_bookingWithVehicleOnly_derivesCustomer() {
        AppointmentDto.CreateRequest request = booking();
        request.setCustomerId(null);
        request.setVehicleId(VEHICLE_OF_NOVAK);

        AppointmentDto.DetailResponse created = appointmentService.create(request, USER_ID);

        assertThat(created.getCustomerId()).isEqualTo(CUSTOMER_NOVAK);
        assertThat(created.getCustomerDisplayName()).isEqualTo("Jan Novák");
    }

    @Test
    @DisplayName("create blokace s kontaktem → CLOSURE_MUST_BE_EMPTY")
    void create_closureWithContactNote_throws() {
        AppointmentDto.CreateRequest request = new AppointmentDto.CreateRequest();
        request.setEntryType(AppointmentType.CLOSURE);
        request.setTitle("Revize zvedáku");
        request.setStartsAt(today().plusDays(22));
        request.setEndsAt(today().plusDays(23));
        request.setContactNote("Nováková, 777 123 456");

        assertBusinessRule(() -> appointmentService.create(request, USER_ID), "CLOSURE_MUST_BE_EMPTY");
    }

    @Test
    @DisplayName("create blokace se zákazníkem → CLOSURE_MUST_BE_EMPTY")
    void create_closureWithCustomer_throws() {
        AppointmentDto.CreateRequest request = booking();
        request.setEntryType(AppointmentType.CLOSURE);

        assertBusinessRule(() -> appointmentService.create(request, USER_ID), "CLOSURE_MUST_BE_EMPTY");
    }

    @Test
    @DisplayName("create s koncem před začátkem → INVALID_TIME_RANGE")
    void create_reversedTimes_throws() {
        AppointmentDto.CreateRequest request = booking();
        request.setStartsAt(today().plusDays(30).plusHours(10));
        request.setEndsAt(today().plusDays(30).plusHours(9));

        assertBusinessRule(() -> appointmentService.create(request, USER_ID), "INVALID_TIME_RANGE");
    }

    @Test
    @DisplayName("create s vozidlem cizího zákazníka → VEHICLE_NOT_OWNED_BY_CUSTOMER")
    void create_foreignVehicle_throws() {
        AppointmentDto.CreateRequest request = booking();
        request.setCustomerId(CUSTOMER_SVOBODOVA);
        request.setVehicleId(VEHICLE_OF_NOVAK);

        assertBusinessRule(() -> appointmentService.create(request, USER_ID), "VEHICLE_NOT_OWNED_BY_CUSTOMER");
    }

    @Test
    @DisplayName("create objednávky do blokace dílny → APPOINTMENT_IN_CLOSURE (jediné tvrdé časové pravidlo)")
    void create_insideClosure_throws() {
        AppointmentDto.DetailResponse closure = appointmentService.getById(CLOSURE_ID);
        AppointmentDto.CreateRequest request = booking();
        request.setStartsAt(closure.getStartsAt().plusHours(9));
        request.setEndsAt(closure.getStartsAt().plusHours(10));

        assertBusinessRule(() -> appointmentService.create(request, USER_ID), "APPOINTMENT_IN_CLOSURE");
    }

    @Test
    @DisplayName("create blokace přes existující objednávku → CLOSURE_OVERLAPS_ENTRIES (opačný směr)")
    void create_closureOverBooking_throws() {
        AppointmentDto.DetailResponse booking = appointmentService.getById(BOOKING_FIRST);

        AppointmentDto.CreateRequest request = new AppointmentDto.CreateRequest();
        request.setEntryType(AppointmentType.CLOSURE);
        request.setTitle("Sanitární den");
        request.setStartsAt(booking.getStartsAt().minusHours(1));
        request.setEndsAt(booking.getStartsAt().plusHours(1));

        assertBusinessRule(() -> appointmentService.create(request, USER_ID), "CLOSURE_OVERLAPS_ENTRIES");
    }

    @Test
    @DisplayName("create blokace na volný termín projde")
    void create_closureOnFreeSlot_succeeds() {
        AppointmentDto.CreateRequest request = new AppointmentDto.CreateRequest();
        request.setEntryType(AppointmentType.CLOSURE);
        request.setTitle("Revize elektroinstalace");
        request.setStartsAt(today().plusDays(120));
        request.setEndsAt(today().plusDays(121));

        AppointmentDto.DetailResponse created = appointmentService.create(request, USER_ID);

        assertThat(created.getEntryType()).isEqualTo(AppointmentType.CLOSURE);
    }

    @Test
    @DisplayName("create blokace přes ZRUŠENOU objednávku projde — na tu už nikdo nepřijede")
    void create_closureOverCancelledBooking_succeeds() {
        AppointmentDto.CreateRequest booking = booking();
        booking.setStartsAt(today().plusDays(130).plusHours(9));
        booking.setEndsAt(today().plusDays(130).plusHours(10));
        AppointmentDto.DetailResponse created = appointmentService.create(booking, USER_ID);

        AppointmentDto.StatusRequest cancel = new AppointmentDto.StatusRequest();
        cancel.setStatus(AppointmentStatus.CANCELLED);
        appointmentService.changeStatus(created.getId(), cancel);

        AppointmentDto.CreateRequest closure = new AppointmentDto.CreateRequest();
        closure.setEntryType(AppointmentType.CLOSURE);
        closure.setTitle("Státní svátek");
        closure.setStartsAt(today().plusDays(130));
        closure.setEndsAt(today().plusDays(131));

        assertThat(appointmentService.create(closure, USER_ID).getId()).isNotNull();
    }

    @Test
    @DisplayName("přetažení blokace na den s objednávkou → CLOSURE_OVERLAPS_ENTRIES")
    void updateTime_closureOntoBooking_throws() {
        AppointmentDto.CreateRequest closure = new AppointmentDto.CreateRequest();
        closure.setEntryType(AppointmentType.CLOSURE);
        closure.setTitle("Školení");
        closure.setStartsAt(today().plusDays(140));
        closure.setEndsAt(today().plusDays(141));
        AppointmentDto.DetailResponse created = appointmentService.create(closure, USER_ID);

        AppointmentDto.DetailResponse booking = appointmentService.getById(BOOKING_FIRST);
        AppointmentDto.TimeRequest move = new AppointmentDto.TimeRequest();
        move.setStartsAt(booking.getStartsAt().minusHours(1));
        move.setEndsAt(booking.getStartsAt().plusHours(2));

        assertBusinessRule(() -> appointmentService.updateTime(created.getId(), move),
                "CLOSURE_OVERLAPS_ENTRIES");
    }

    @Test
    @DisplayName("create objednávky překrývající jinou objednávku PROJDE — kolize jen varují")
    void create_overlappingBooking_isAllowed() {
        AppointmentDto.DetailResponse existing = appointmentService.getById(BOOKING_FIRST);
        AppointmentDto.CreateRequest request = booking();
        request.setStartsAt(existing.getStartsAt());
        request.setEndsAt(existing.getEndsAt());

        AppointmentDto.DetailResponse created = appointmentService.create(request, USER_ID);

        assertThat(created.getId()).isNotNull();
    }

    // =========================================================================
    // objednávka bez známého konce (V74)
    // =========================================================================

    @Test
    @DisplayName("create bez konce projde — zákazník nechá auto, délku opravy nikdo nezná")
    void create_withoutEnd_isAllowed() {
        AppointmentDto.CreateRequest request = booking();
        request.setEndsAt(null);

        AppointmentDto.DetailResponse created = appointmentService.create(request, USER_ID);

        assertThat(created.getStartsAt()).isNotNull();
        assertThat(created.getEndsAt()).isNull();
    }

    @Test
    @DisplayName("objednávka bez konce se v kalendáři NEZTRATÍ")
    void openEndedBooking_appearsInRange() {
        AppointmentDto.CreateRequest request = booking();
        request.setEndsAt(null);
        AppointmentDto.DetailResponse created = appointmentService.create(request, USER_ID);

        List<AppointmentDto.ListResponse> window = appointmentService.getInRange(
                created.getStartsAt().minusHours(1), created.getStartsAt().plusHours(1), null, null);

        assertThat(window).extracting(AppointmentDto.ListResponse::getId).contains(created.getId());
    }

    @Test
    @DisplayName("blokace dílny bez konce → CLOSURE_END_REQUIRED (zavřela by dílnu natrvalo)")
    void create_closureWithoutEnd_throws() {
        AppointmentDto.CreateRequest request = new AppointmentDto.CreateRequest();
        request.setEntryType(AppointmentType.CLOSURE);
        request.setTitle("Revize");
        request.setStartsAt(today().plusDays(25));
        request.setEndsAt(null);

        assertBusinessRule(() -> appointmentService.create(request, USER_ID), "CLOSURE_END_REQUIRED");
    }

    @Test
    @DisplayName("objednávka bez konce do blokace se posuzuje podle příjezdu → APPOINTMENT_IN_CLOSURE")
    void create_openEndedInsideClosure_throws() {
        AppointmentDto.DetailResponse closure = appointmentService.getById(CLOSURE_ID);
        AppointmentDto.CreateRequest request = booking();
        request.setStartsAt(closure.getStartsAt().plusHours(9));
        request.setEndsAt(null);

        assertBusinessRule(() -> appointmentService.create(request, USER_ID), "APPOINTMENT_IN_CLOSURE");
    }

    @Test
    @DisplayName("objednávka bez konce těsně PO blokaci projde")
    void create_openEndedAfterClosure_isAllowed() {
        AppointmentDto.DetailResponse closure = appointmentService.getById(CLOSURE_ID);
        AppointmentDto.CreateRequest request = booking();
        request.setStartsAt(closure.getEndsAt());
        request.setEndsAt(null);

        assertThat(appointmentService.create(request, USER_ID).getId()).isNotNull();
    }

    @Test
    @DisplayName("protažení za spodní okraj konec doplní")
    void updateTime_fillsInMissingEnd() {
        AppointmentDto.CreateRequest request = booking();
        request.setEndsAt(null);
        AppointmentDto.DetailResponse created = appointmentService.create(request, USER_ID);

        AppointmentDto.TimeRequest time = new AppointmentDto.TimeRequest();
        time.setStartsAt(created.getStartsAt());
        time.setEndsAt(created.getStartsAt().plusHours(3));

        AppointmentDto.DetailResponse resized = appointmentService.updateTime(created.getId(), time);

        assertThat(resized.getEndsAt()).isEqualTo(created.getStartsAt().plusHours(3));
    }

    @Test
    @DisplayName("přetažení otevřené objednávky posune příjezd a konec nechá prázdný")
    void updateTime_keepsEndOpen() {
        AppointmentDto.CreateRequest request = booking();
        request.setEndsAt(null);
        AppointmentDto.DetailResponse created = appointmentService.create(request, USER_ID);

        AppointmentDto.TimeRequest time = new AppointmentDto.TimeRequest();
        time.setStartsAt(created.getStartsAt().plusDays(1));
        time.setEndsAt(null);

        AppointmentDto.DetailResponse moved = appointmentService.updateTime(created.getId(), time);

        assertThat(moved.getStartsAt()).isEqualTo(created.getStartsAt().plusDays(1));
        assertThat(moved.getEndsAt()).isNull();
    }

    @Test
    @DisplayName("varování o překryvu započítá i objednávku bez konce podle jejího příjezdu")
    void checkOverlaps_countsOpenEndedByArrival() {
        AppointmentDto.CreateRequest request = booking();
        request.setEndsAt(null);
        AppointmentDto.DetailResponse open = appointmentService.create(request, USER_ID);

        AppointmentDto.OverlapResponse result = appointmentService.checkOverlaps(
                open.getStartsAt().minusMinutes(30), open.getStartsAt().plusMinutes(30), null);

        assertThat(result.getOverlapping()).extracting(AppointmentDto.ListResponse::getId)
                .contains(open.getId());
    }

    // =========================================================================
    // update / updateTime
    // =========================================================================

    @Test
    @DisplayName("update změní měnitelná pole, typ a stav nechá být")
    void update_changesMutableFieldsOnly() {
        AppointmentDto.UpdateRequest request = new AppointmentDto.UpdateRequest();
        request.setTitle("Přejmenováno");
        request.setNote("Jiná poznámka");
        request.setStartsAt(today().plusDays(6).plusHours(9));
        request.setEndsAt(today().plusDays(6).plusHours(11));
        request.setCustomerId(CUSTOMER_NOVAK);
        request.setVehicleId(VEHICLE_OF_NOVAK);

        AppointmentDto.DetailResponse updated = appointmentService.update(BOOKING_FIRST, request);

        assertThat(updated.getTitle()).isEqualTo("Přejmenováno");
        assertThat(updated.getEntryType()).isEqualTo(AppointmentType.BOOKING);
        assertThat(updated.getStatus()).isEqualTo(AppointmentStatus.PLANNED);
    }

    @Test
    @DisplayName("updateTime posune termín (drag-and-drop)")
    void updateTime_movesEntry() {
        AppointmentDto.TimeRequest request = new AppointmentDto.TimeRequest();
        request.setStartsAt(today().plusDays(7).plusHours(14));
        request.setEndsAt(today().plusDays(7).plusHours(16));

        AppointmentDto.DetailResponse moved = appointmentService.updateTime(BOOKING_PLANNED, request);

        assertThat(moved.getStartsAt()).isEqualTo(request.getStartsAt());
        assertThat(moved.getEndsAt()).isEqualTo(request.getEndsAt());
        assertThat(moved.getTitle()).isEqualTo("Výměna brzdových destiček");
    }

    @Test
    @DisplayName("updateTime do blokace dílny → APPOINTMENT_IN_CLOSURE")
    void updateTime_intoClosure_throws() {
        AppointmentDto.DetailResponse closure = appointmentService.getById(CLOSURE_ID);
        AppointmentDto.TimeRequest request = new AppointmentDto.TimeRequest();
        request.setStartsAt(closure.getStartsAt().plusHours(8));
        request.setEndsAt(closure.getStartsAt().plusHours(9));

        assertBusinessRule(() -> appointmentService.updateTime(BOOKING_PLANNED, request),
                "APPOINTMENT_IN_CLOSURE");
    }

    @Test
    @DisplayName("blokace se smí posunout sama na sebe — vlastní překryv se ignoruje")
    void updateTime_closureOntoItself_isAllowed() {
        AppointmentDto.DetailResponse closure = appointmentService.getById(CLOSURE_ID);
        AppointmentDto.TimeRequest request = new AppointmentDto.TimeRequest();
        request.setStartsAt(closure.getStartsAt().minusHours(3));
        request.setEndsAt(closure.getEndsAt().minusHours(3));

        AppointmentDto.DetailResponse moved = appointmentService.updateTime(CLOSURE_ID, request);

        assertThat(moved.getStartsAt()).isEqualTo(request.getStartsAt());
    }

    @Test
    @DisplayName("update terminální objednávky → APPOINTMENT_TERMINAL_READONLY")
    void update_terminal_throws() {
        AppointmentDto.UpdateRequest request = new AppointmentDto.UpdateRequest();
        request.setTitle("Pokus o přepsání historie");
        request.setStartsAt(today().plusDays(8).plusHours(9));
        request.setCustomerId(CUSTOMER_NOVAK);

        assertBusinessRule(() -> appointmentService.update(BOOKING_NO_SHOW, request),
                "APPOINTMENT_TERMINAL_READONLY");
    }

    @Test
    @DisplayName("updateTime terminální objednávky → APPOINTMENT_TERMINAL_READONLY")
    void updateTime_terminal_throws() {
        AppointmentDto.TimeRequest request = new AppointmentDto.TimeRequest();
        request.setStartsAt(today().plusDays(8).plusHours(9));

        assertBusinessRule(() -> appointmentService.updateTime(BOOKING_NO_SHOW, request),
                "APPOINTMENT_TERMINAL_READONLY");
    }

    // =========================================================================
    // changeStatus
    // =========================================================================

    @Test
    @DisplayName("changeStatus PLANNED → NO_SHOW projde")
    void changeStatus_plannedToNoShow() {
        AppointmentDto.DetailResponse result =
                appointmentService.changeStatus(BOOKING_PLANNED, status(AppointmentStatus.NO_SHOW));

        assertThat(result.getStatus()).isEqualTo(AppointmentStatus.NO_SHOW);
    }

    @Test
    @DisplayName("changeStatus z terminálního stavu → INVALID_STATUS_TRANSITION")
    void changeStatus_fromTerminal_throws() {
        assertBusinessRule(
                () -> appointmentService.changeStatus(BOOKING_NO_SHOW, status(AppointmentStatus.PLANNED)),
                "INVALID_STATUS_TRANSITION");
    }

    @Test
    @DisplayName("changeStatus na CONVERTED ručně → STATUS_NOT_SETTABLE")
    void changeStatus_toConverted_throws() {
        assertBusinessRule(
                () -> appointmentService.changeStatus(BOOKING_PLANNED, status(AppointmentStatus.CONVERTED)),
                "STATUS_NOT_SETTABLE");
    }

    @Test
    @DisplayName("changeStatus blokace na NO_SHOW → STATUS_NOT_ALLOWED_FOR_CLOSURE")
    void changeStatus_closureToNoShow_throws() {
        assertBusinessRule(
                () -> appointmentService.changeStatus(CLOSURE_ID, status(AppointmentStatus.NO_SHOW)),
                "STATUS_NOT_ALLOWED_FOR_CLOSURE");
    }

    @Test
    @DisplayName("blokaci lze zrušit — pak už neblokuje objednávky")
    void cancelledClosure_stopsBlocking() {
        AppointmentDto.DetailResponse closure = appointmentService.getById(CLOSURE_ID);
        appointmentService.changeStatus(CLOSURE_ID, status(AppointmentStatus.CANCELLED));

        AppointmentDto.CreateRequest request = booking();
        request.setStartsAt(closure.getStartsAt().plusHours(9));
        request.setEndsAt(closure.getStartsAt().plusHours(10));

        assertThat(appointmentService.create(request, USER_ID).getId()).isNotNull();
    }

    // =========================================================================
    // delete — tvrdé mazání (V76)
    // =========================================================================

    @Test
    @DisplayName("delete odstraní položku z DB i z kalendáře")
    void delete_removesEntry() {
        appointmentService.delete(BOOKING_PLANNED);

        assertThatThrownBy(() -> appointmentService.getById(BOOKING_PLANNED))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThat(appointmentService.getInRange(today().minusDays(10), today().plusDays(10), null, null))
                .extracting(AppointmentDto.ListResponse::getId)
                .doesNotContain(BOOKING_PLANNED);
    }

    @Test
    @DisplayName("delete neexistující → 404")
    void delete_missing_throws() {
        assertThatThrownBy(() -> appointmentService.delete(99999L))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("smazaná objednávka se neuvolní pro překryv ani pro blokaci — je pryč doopravdy")
    void delete_isPhysical() {
        AppointmentDto.DetailResponse existing = appointmentService.getById(BOOKING_PLANNED);
        appointmentService.delete(BOOKING_PLANNED);

        AppointmentDto.OverlapResponse result = appointmentService.checkOverlaps(
                existing.getStartsAt(), existing.getEndsAt(), null);

        assertThat(result.getOverlapping()).extracting(AppointmentDto.ListResponse::getId)
                .doesNotContain(BOOKING_PLANNED);
    }

    @Test
    @DisplayName("převedenou objednávku smazat nelze → APPOINTMENT_CONVERTED_CANNOT_DELETE")
    void delete_converted_throws() {
        appointmentService.convert(BOOKING_FIRST, orderRequest(), USER_ID);

        assertBusinessRule(() -> appointmentService.delete(BOOKING_FIRST),
                "APPOINTMENT_CONVERTED_CANNOT_DELETE");
    }

    @Test
    @DisplayName("zrušenou objednávku smazat lze — zrušení je stav, mazání je úklid omylu")
    void delete_cancelled_isAllowed() {
        appointmentService.changeStatus(BOOKING_PLANNED, status(AppointmentStatus.CANCELLED));

        appointmentService.delete(BOOKING_PLANNED);

        assertThatThrownBy(() -> appointmentService.getById(BOOKING_PLANNED))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // =========================================================================
    // convert
    // =========================================================================

    @Test
    @DisplayName("convert vytvoří zakázku, naváže ji a přepne stav na CONVERTED")
    void convert_createsOrderAndLinks() {
        OrderDto.DetailResponse order =
                appointmentService.convert(BOOKING_FIRST, orderRequest(), USER_ID);

        assertThat(order.getId()).isNotNull();
        assertThat(order.getOrderNumber()).startsWith("ZAK-");

        AppointmentDto.DetailResponse appointment = appointmentService.getById(BOOKING_FIRST);
        assertThat(appointment.getStatus()).isEqualTo(AppointmentStatus.CONVERTED);
        assertThat(appointment.getOrderId()).isEqualTo(order.getId());
        assertThat(appointment.getOrderNumber()).isEqualTo(order.getOrderNumber());
    }

    @Test
    @DisplayName("findByOrderId najde objednávku, ze které zakázka vznikla")
    void findByOrderId_returnsSourceAppointment() {
        OrderDto.DetailResponse order =
                appointmentService.convert(BOOKING_FIRST, orderRequest(), USER_ID);

        assertThat(appointmentService.findByOrderId(order.getId()))
                .isPresent()
                .get()
                .extracting(AppointmentDto.DetailResponse::getId)
                .isEqualTo(BOOKING_FIRST);
    }

    @Test
    @DisplayName("druhý convert téže objednávky → ALREADY_CONVERTED")
    void convert_twice_throws() {
        appointmentService.convert(BOOKING_FIRST, orderRequest(), USER_ID);

        assertBusinessRule(() -> appointmentService.convert(BOOKING_FIRST, orderRequest(), USER_ID),
                "ALREADY_CONVERTED");
    }

    @Test
    @DisplayName("convert blokace dílny → NOT_CONVERTIBLE")
    void convert_closure_throws() {
        assertBusinessRule(() -> appointmentService.convert(CLOSURE_ID, orderRequest(), USER_ID),
                "NOT_CONVERTIBLE");
    }

    @Test
    @DisplayName("convert objednávky, na kterou zákazník nedorazil → INVALID_STATUS_TRANSITION")
    void convert_terminalStatus_throws() {
        assertBusinessRule(() -> appointmentService.convert(BOOKING_NO_SHOW, orderRequest(), USER_ID),
                "INVALID_STATUS_TRANSITION");
    }

    @Test
    @DisplayName("selže-li založení zakázky, objednávka zůstane nedotčená (jedna transakce)")
    void convert_orderCreationFails_leavesAppointmentUntouched() {
        OrderDto.CreateRequest invalid = orderRequest();
        invalid.setVehicleId(VEHICLE_OF_SVOBODOVA); // cizí vozidlo → OrderService selže

        assertBusinessRule(() -> appointmentService.convert(BOOKING_FIRST, invalid, USER_ID),
                "VEHICLE_NOT_OWNED_BY_CUSTOMER");

        AppointmentDto.DetailResponse untouched = appointmentService.getById(BOOKING_FIRST);
        assertThat(untouched.getStatus()).isEqualTo(AppointmentStatus.PLANNED);
        assertThat(untouched.getOrderId()).isNull();
    }

    // =========================================================================
    // checkOverlaps
    // =========================================================================

    @Test
    @DisplayName("checkOverlaps spočítá překryv, ale nezakáže ho")
    void checkOverlaps_countsWithoutBlocking() {
        AppointmentDto.DetailResponse existing = appointmentService.getById(BOOKING_FIRST);

        AppointmentDto.OverlapResponse result = appointmentService.checkOverlaps(
                existing.getStartsAt(), existing.getEndsAt(), null);

        assertThat(result.getOverlappingCount()).isEqualTo(1);
        assertThat(result.getOverlapping()).extracting(AppointmentDto.ListResponse::getId)
                .containsExactly(BOOKING_FIRST);
        assertThat(result.isBlockedByClosure()).isFalse();
    }

    @Test
    @DisplayName("checkOverlaps ohlásí blokaci dílny")
    void checkOverlaps_detectsClosure() {
        AppointmentDto.DetailResponse closure = appointmentService.getById(CLOSURE_ID);

        AppointmentDto.OverlapResponse result = appointmentService.checkOverlaps(
                closure.getStartsAt().plusHours(9), closure.getStartsAt().plusHours(10), null);

        assertThat(result.isBlockedByClosure()).isTrue();
    }

    @Test
    @DisplayName("checkOverlaps s excludeId nepočítá editovanou položku samu do sebe")
    void checkOverlaps_excludesSelf() {
        AppointmentDto.DetailResponse existing = appointmentService.getById(BOOKING_FIRST);

        AppointmentDto.OverlapResponse result = appointmentService.checkOverlaps(
                existing.getStartsAt(), existing.getEndsAt(), BOOKING_FIRST);

        assertThat(result.getOverlappingCount()).isZero();
    }

    @Test
    @DisplayName("checkOverlaps nepočítá zrušené ani nedostavené objednávky")
    void checkOverlaps_ignoresTerminalBookings() {
        AppointmentDto.DetailResponse noShow = appointmentService.getById(BOOKING_NO_SHOW);

        AppointmentDto.OverlapResponse result = appointmentService.checkOverlaps(
                noShow.getStartsAt(), noShow.getEndsAt(), null);

        assertThat(result.getOverlappingCount()).isZero();
    }

    // =========================================================================
    // události (EVENT, V82)
    // =========================================================================

    @Test
    @DisplayName("create založí událost se zaměstnancem a detail nese jeho jméno")
    void create_event_persistsWithEmployee() {
        AppointmentDto.DetailResponse created = appointmentService.create(event(), USER_ID);

        assertThat(created.getEntryType()).isEqualTo(AppointmentType.EVENT);
        assertThat(created.getStatus()).isEqualTo(AppointmentStatus.PLANNED);
        assertThat(created.getEmployeeId()).isEqualTo(EMPLOYEE_MECHANIK);
        assertThat(created.getEmployeeDisplayName()).isEqualTo("Petr Mechanik");
        assertThat(created.getCustomerId()).isNull();
    }

    @Test
    @DisplayName("create události bez zaměstnance projde — vazba je volitelná")
    void create_event_withoutEmployee_ok() {
        AppointmentDto.CreateRequest request = event();
        request.setEmployeeId(null);

        AppointmentDto.DetailResponse created = appointmentService.create(request, USER_ID);

        assertThat(created.getEmployeeId()).isNull();
        assertThat(created.getEmployeeDisplayName()).isNull();
    }

    @Test
    @DisplayName("create události bez konce → EVENT_END_REQUIRED")
    void create_event_withoutEnd_throws() {
        AppointmentDto.CreateRequest request = event();
        request.setEndsAt(null);

        assertBusinessRule(() -> appointmentService.create(request, USER_ID), "EVENT_END_REQUIRED");
    }

    @Test
    @DisplayName("create události se zákazníkem → EVENT_MUST_BE_EMPTY")
    void create_event_withCustomer_throws() {
        AppointmentDto.CreateRequest request = event();
        request.setCustomerId(CUSTOMER_NOVAK);

        assertBusinessRule(() -> appointmentService.create(request, USER_ID), "EVENT_MUST_BE_EMPTY");
    }

    @Test
    @DisplayName("create objednávky se zaměstnancem → EMPLOYEE_ONLY_FOR_EVENT")
    void create_booking_withEmployee_throws() {
        AppointmentDto.CreateRequest request = booking();
        request.setEmployeeId(EMPLOYEE_MECHANIK);

        assertBusinessRule(() -> appointmentService.create(request, USER_ID), "EMPLOYEE_ONLY_FOR_EVENT");
    }

    @Test
    @DisplayName("create události s neexistujícím či neaktivním zaměstnancem → 404")
    void create_event_unknownOrInactiveEmployee_throws() {
        AppointmentDto.CreateRequest unknown = event();
        unknown.setEmployeeId(999_999L);
        assertThatThrownBy(() -> appointmentService.create(unknown, USER_ID))
                .isInstanceOf(ResourceNotFoundException.class);

        AppointmentDto.CreateRequest inactive = event();
        inactive.setEmployeeId(EMPLOYEE_INACTIVE);
        assertThatThrownBy(() -> appointmentService.create(inactive, USER_ID))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("událost se vejde i do zavřené dílny — blokace ji na rozdíl od objednávky nezastaví")
    void create_event_insideClosure_ok() {
        AppointmentDto.DetailResponse closure = appointmentService.getById(CLOSURE_ID);
        AppointmentDto.CreateRequest request = event();
        request.setStartsAt(closure.getStartsAt());
        request.setEndsAt(closure.getEndsAt());

        AppointmentDto.DetailResponse created = appointmentService.create(request, USER_ID);

        assertThat(created.getId()).isNotNull();
    }

    @Test
    @DisplayName("událost nefiguruje ve varování o překryvu objednávek")
    void checkOverlaps_ignoresEvents() {
        AppointmentDto.DetailResponse created = appointmentService.create(event(), USER_ID);

        AppointmentDto.OverlapResponse result = appointmentService.checkOverlaps(
                created.getStartsAt(), created.getEndsAt(), null);

        assertThat(result.getOverlapping())
                .extracting(AppointmentDto.ListResponse::getId)
                .doesNotContain(created.getId());
        assertThat(result.isBlockedByClosure()).isFalse();
    }

    @Test
    @DisplayName("událost lze jen zrušit — jiný stav → STATUS_NOT_ALLOWED_FOR_EVENT")
    void changeStatus_event_onlyCancellation() {
        Long id = appointmentService.create(event(), USER_ID).getId();

        assertBusinessRule(() -> appointmentService.changeStatus(id, status(AppointmentStatus.NO_SHOW)),
                "STATUS_NOT_ALLOWED_FOR_EVENT");

        AppointmentDto.DetailResponse cancelled =
                appointmentService.changeStatus(id, status(AppointmentStatus.CANCELLED));
        assertThat(cancelled.getStatus()).isEqualTo(AppointmentStatus.CANCELLED);
    }

    @Test
    @DisplayName("událost nelze převést na zakázku → NOT_CONVERTIBLE")
    void convert_event_throws() {
        Long id = appointmentService.create(event(), USER_ID).getId();

        assertBusinessRule(() -> appointmentService.convert(id, orderRequest(), USER_ID),
                "NOT_CONVERTIBLE");
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /** Půlnoc dnešního dne v UTC — základ pro relativní časy jako v seedu V73. */
    private OffsetDateTime today() {
        return OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(java.time.temporal.ChronoUnit.DAYS);
    }

    private AppointmentDto.CreateRequest booking() {
        AppointmentDto.CreateRequest request = new AppointmentDto.CreateRequest();
        request.setEntryType(AppointmentType.BOOKING);
        request.setTitle("Nová objednávka");
        request.setNote("Telefonicky.");
        request.setStartsAt(today().plusDays(5).plusHours(9));
        request.setEndsAt(today().plusDays(5).plusHours(11));
        request.setCustomerId(CUSTOMER_NOVAK);
        request.setVehicleId(VEHICLE_OF_NOVAK);
        return request;
    }

    private AppointmentDto.CreateRequest event() {
        AppointmentDto.CreateRequest request = new AppointmentDto.CreateRequest();
        request.setEntryType(AppointmentType.EVENT);
        request.setTitle("Dovolená");
        request.setStartsAt(today().plusDays(6));
        request.setEndsAt(today().plusDays(8));
        request.setEmployeeId(EMPLOYEE_MECHANIK);
        return request;
    }

    private AppointmentDto.StatusRequest status(AppointmentStatus status) {
        AppointmentDto.StatusRequest request = new AppointmentDto.StatusRequest();
        request.setStatus(status);
        return request;
    }

    private OrderDto.CreateRequest orderRequest() {
        OrderDto.CreateRequest request = new OrderDto.CreateRequest();
        request.setReceivedAt(LocalDate.now());
        request.setCustomerId(CUSTOMER_NOVAK);
        request.setVehicleId(VEHICLE_OF_NOVAK);
        request.setDescription("Vzniklo z objednávky v kalendáři.");
        return request;
    }

    private void assertBusinessRule(ThrowingCallable action, String expectedRuleCode) {
        assertThatThrownBy(action)
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(ex -> assertThat(((BusinessRuleException) ex).getRuleCode())
                        .isEqualTo(expectedRuleCode));
    }

    /** Zkratka, ať se v testech neopakuje plný název typu z AssertJ. */
    private interface ThrowingCallable extends org.assertj.core.api.ThrowableAssert.ThrowingCallable {
    }
}
