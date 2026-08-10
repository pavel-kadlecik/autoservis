package cz.palo.autoservis.model.domain.schedule;

import cz.palo.autoservis.model.enums.AppointmentStatus;
import cz.palo.autoservis.model.enums.AppointmentType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

/**
 * Doménový objekt záznamu kalendáře — mapuje se na {@code schedule.appointments}.
 *
 * <p>Čisté POJO bez JPA anotací a závislostí na Springu.
 * Databázové sloupce na pole mapuje MyBatis přes {@code ResultMap}
 * v {@code AppointmentMapper.xml}.
 *
 * <p>Jedna tabulka nese tři druhy záznamů (viz {@link AppointmentType}): objednávku zákazníka,
 * blokaci dílny a událost. Objednávka smí mít {@code customerId}, {@code vehicleId} i
 * {@code contactNote}, ale žádné z nich není povinné (V85) — termín se domlouvá dřív, než servis
 * zákazníka i auto zná. Blokace a událost mají všechna tři {@code null}.
 * Rozdíl vynucují CHECK constrainty v migracích V72, V82 a V85, ne tato třída.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Appointment {

    private Long id;
    private AppointmentType entryType;
    private String title;
    private String note;

    private OffsetDateTime startsAt;
    private OffsetDateTime endsAt;

    private Long customerId;
    /** Zákazník mimo evidenci — jméno a telefon volným textem. Jen pro BOOKING (V85). */
    private String contactNote;
    private Long vehicleId;
    private Long orderId;
    /** Jen pro {@link AppointmentType#EVENT} — dovolená apod. (V82). */
    private Long employeeId;
    private String customerDisplayName;
    private String vehicleLicensePlate;
    private String vehicleBrand;
    private String vehicleModel;
    private String vehicleVin;
    private String orderNumber;
    private String employeeDisplayName;


    private AppointmentStatus status;

    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
    private Long createdBy;

}
