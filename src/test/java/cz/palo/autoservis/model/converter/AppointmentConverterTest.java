package cz.palo.autoservis.model.converter;

import cz.palo.autoservis.model.domain.schedule.Appointment;
import cz.palo.autoservis.model.dto.schedule.AppointmentDto;
import cz.palo.autoservis.model.enums.AppointmentStatus;
import cz.palo.autoservis.model.enums.AppointmentType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Konvertor položek kalendáře — čistý unit test bez Spring kontextu.
 *
 * <p>Hlídá tři věci, na kterých modul stojí:
 * <ul>
 *   <li>{@code toDomain} <b>nesmí</b> přenést {@code id}, {@code status}, {@code orderId} ani audit —
 *       ty určuje databáze a service, ne klient (N-06).</li>
 *   <li>{@code toDetailResponse} musí naplnit <b>všechna</b> pole DTO. Zapomenuté pole se navenek
 *       projeví jako tiché {@code null} v odpovědi API, ne jako chyba.</li>
 *   <li>{@code toListResponse} nese jen to, co kalendář kreslí — a <b>ne</b> {@code note}
 *       (dlouhý text × desítky událostí v jedné odpovědi).</li>
 * </ul>
 */
class AppointmentConverterTest {

    private static final OffsetDateTime START = OffsetDateTime.of(2026, 8, 5, 9, 0, 0, 0, ZoneOffset.ofHours(2));
    private static final OffsetDateTime END = OffsetDateTime.of(2026, 8, 5, 10, 0, 0, 0, ZoneOffset.ofHours(2));

    private final AppointmentConverter converter = new AppointmentConverter();

    @Test
    @DisplayName("toDomain přenese vstupní pole a nepřenese to, co určuje server")
    void toDomain_mapsInputFieldsOnly() {
        AppointmentDto.CreateRequest request = new AppointmentDto.CreateRequest();
        request.setEntryType(AppointmentType.BOOKING);
        request.setTitle("Výměna oleje");
        request.setNote("Zákazník počká.");
        request.setStartsAt(START);
        request.setEndsAt(END);
        request.setCustomerId(1L);
        request.setVehicleId(3L);

        Appointment result = converter.toDomain(request);

        assertThat(result.getEntryType()).isEqualTo(AppointmentType.BOOKING);
        assertThat(result.getTitle()).isEqualTo("Výměna oleje");
        assertThat(result.getNote()).isEqualTo("Zákazník počká.");
        assertThat(result.getStartsAt()).isEqualTo(START);
        assertThat(result.getEndsAt()).isEqualTo(END);
        assertThat(result.getCustomerId()).isEqualTo(1L);
        assertThat(result.getVehicleId()).isEqualTo(3L);

        // Server-side pole: klient je nesmí ovlivnit.
        assertThat(result.getId()).isNull();
        assertThat(result.getStatus()).isNull();
        assertThat(result.getOrderId()).isNull();
        assertThat(result.getCreatedBy()).isNull();
        assertThat(result.getCreatedAt()).isNull();
    }

    @Test
    @DisplayName("toDomain vrátí null pro null vstup")
    void toDomain_nullSafe() {
        assertThat(converter.toDomain(null)).isNull();
    }

    /*
     * Formulář posílá nevyplněný kontakt jako "", ne jako null. Bez převodu na null by prázdný
     * řetězec u blokace dílny porušil chk_appointments_contact_booking_only a skončil chybou 500
     * místo uložení — tatáž třída chyby, kterou musely opravovat migrace V80 a V81.
     */
    @Test
    @DisplayName("toDomain převede prázdný kontakt na null, ne na prázdný řetězec")
    void toDomain_blankContactBecomesNull() {
        AppointmentDto.CreateRequest request = new AppointmentDto.CreateRequest();
        request.setEntryType(AppointmentType.CLOSURE);
        request.setTitle("Státní svátek");
        request.setStartsAt(START);
        request.setEndsAt(END);
        request.setContactNote("   ");

        assertThat(converter.toDomain(request).getContactNote()).isNull();
    }

    @Test
    @DisplayName("toDomain zachová vyplněný kontakt na zákazníka mimo evidenci")
    void toDomain_keepsContactNote() {
        AppointmentDto.CreateRequest request = new AppointmentDto.CreateRequest();
        request.setEntryType(AppointmentType.BOOKING);
        request.setTitle("Něco to klepe");
        request.setStartsAt(START);
        request.setContactNote("Nováková, 777 123 456");

        assertThat(converter.toDomain(request).getContactNote()).isEqualTo("Nováková, 777 123 456");
    }

    @Test
    @DisplayName("applyUpdate převede vyprázdněný kontakt na null")
    void applyUpdate_blankContactBecomesNull() {
        Appointment existing = fullAppointment();
        existing.setContactNote("Nováková, 777 123 456");

        AppointmentDto.UpdateRequest request = new AppointmentDto.UpdateRequest();
        request.setTitle("Výměna oleje");
        request.setStartsAt(START);
        request.setContactNote("");

        assertThat(converter.applyUpdate(existing, request).getContactNote()).isNull();
    }

    @Test
    @DisplayName("applyUpdate změní jen měnitelná pole a nesáhne na typ, stav ani audit")
    void applyUpdate_doesNotTouchImmutableFields() {
        Appointment existing = fullAppointment();

        AppointmentDto.UpdateRequest request = new AppointmentDto.UpdateRequest();
        request.setTitle("Nový název");
        request.setNote("Nová poznámka");
        request.setStartsAt(START.plusHours(3));
        request.setEndsAt(END.plusHours(3));
        request.setCustomerId(2L);
        request.setVehicleId(4L);

        Appointment result = converter.applyUpdate(existing, request);

        assertThat(result.getTitle()).isEqualTo("Nový název");
        assertThat(result.getStartsAt()).isEqualTo(START.plusHours(3));
        assertThat(result.getCustomerId()).isEqualTo(2L);

        // Typ se po založení nemění (překlopení BOOKING → CLOSURE by porušilo CHECK v DB).
        assertThat(result.getEntryType()).isEqualTo(AppointmentType.BOOKING);
        assertThat(result.getStatus()).isEqualTo(AppointmentStatus.PLANNED);
        assertThat(result.getId()).isEqualTo(42L);
        assertThat(result.getCreatedBy()).isEqualTo(7L);
    }

    @Test
    @DisplayName("toDetailResponse naplní všechna pole detailu")
    void toDetailResponse_mapsEveryField() {
        AppointmentDto.DetailResponse response = converter.toDetailResponse(fullAppointment());

        assertThat(response.getId()).isEqualTo(42L);
        assertThat(response.getEntryType()).isEqualTo(AppointmentType.BOOKING);
        assertThat(response.getTitle()).isEqualTo("Výměna oleje");
        assertThat(response.getNote()).isEqualTo("Zákazník počká.");
        assertThat(response.getStartsAt()).isEqualTo(START);
        assertThat(response.getEndsAt()).isEqualTo(END);

        assertThat(response.getCustomerId()).isEqualTo(1L);
        assertThat(response.getCustomerDisplayName()).isEqualTo("Jan Novák");
        assertThat(response.getVehicleId()).isEqualTo(3L);
        assertThat(response.getVehicleLicensePlate()).isEqualTo("1AB 2345");
        assertThat(response.getVehicleBrand()).isEqualTo("BMW");
        assertThat(response.getVehicleModel()).isEqualTo("3 Series");
        assertThat(response.getVehicleVin()).isEqualTo("WBA3A5C51DF123456");

        assertThat(response.getOrderId()).isEqualTo(99L);
        assertThat(response.getOrderNumber()).isEqualTo("ZAK-2026-0042");

        assertThat(response.getStatus()).isEqualTo(AppointmentStatus.PLANNED);
        assertThat(response.getCreatedAt()).isEqualTo(START.minusDays(1));
        assertThat(response.getUpdatedAt()).isEqualTo(START.minusHours(1));
    }

    @Test
    @DisplayName("toListResponse nese jen to, co kalendář kreslí")
    void toListResponse_carriesOnlyRenderingFields() {
        List<AppointmentDto.ListResponse> result = converter.toListResponses(List.of(fullAppointment()));

        assertThat(result).hasSize(1);
        AppointmentDto.ListResponse item = result.get(0);

        assertThat(item.getId()).isEqualTo(42L);
        assertThat(item.getEntryType()).isEqualTo(AppointmentType.BOOKING);
        assertThat(item.getTitle()).isEqualTo("Výměna oleje");
        assertThat(item.getStartsAt()).isEqualTo(START);
        assertThat(item.getEndsAt()).isEqualTo(END);
        assertThat(item.getStatus()).isEqualTo(AppointmentStatus.PLANNED);
        assertThat(item.getCustomerDisplayName()).isEqualTo("Jan Novák");
        assertThat(item.getVehicleLicensePlate()).isEqualTo("1AB 2345");
        assertThat(item.getVehicleBrand()).isEqualTo("BMW");
        assertThat(item.getVehicleModel()).isEqualTo("3 Series");
        assertThat(item.getOrderId()).isEqualTo(99L);
    }

    @Test
    @DisplayName("blokace dílny projde konverzí bez zákazníka a vozidla")
    void closure_hasNoCustomerOrVehicle() {
        AppointmentDto.CreateRequest request = new AppointmentDto.CreateRequest();
        request.setEntryType(AppointmentType.CLOSURE);
        request.setTitle("Školení techniků");
        request.setStartsAt(START);
        request.setEndsAt(END);

        Appointment result = converter.toDomain(request);

        assertThat(result.getEntryType()).isEqualTo(AppointmentType.CLOSURE);
        assertThat(result.getCustomerId()).isNull();
        assertThat(result.getVehicleId()).isNull();
    }

    /** Plně vyplněná objednávka včetně projekcí z JOINu. */
    private Appointment fullAppointment() {
        Appointment appointment = new Appointment();
        appointment.setId(42L);
        appointment.setEntryType(AppointmentType.BOOKING);
        appointment.setTitle("Výměna oleje");
        appointment.setNote("Zákazník počká.");
        appointment.setStartsAt(START);
        appointment.setEndsAt(END);
        appointment.setCustomerId(1L);
        appointment.setVehicleId(3L);
        appointment.setOrderId(99L);
        appointment.setCustomerDisplayName("Jan Novák");
        appointment.setVehicleLicensePlate("1AB 2345");
        appointment.setVehicleBrand("BMW");
        appointment.setVehicleModel("3 Series");
        appointment.setVehicleVin("WBA3A5C51DF123456");
        appointment.setOrderNumber("ZAK-2026-0042");
        appointment.setStatus(AppointmentStatus.PLANNED);
        appointment.setCreatedAt(START.minusDays(1));
        appointment.setUpdatedAt(START.minusHours(1));
        appointment.setCreatedBy(7L);
        return appointment;
    }
}
