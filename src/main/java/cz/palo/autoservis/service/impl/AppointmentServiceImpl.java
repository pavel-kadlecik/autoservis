package cz.palo.autoservis.service.impl;

import cz.palo.autoservis.exception.BusinessRuleException;
import cz.palo.autoservis.exception.ResourceNotFoundException;
import cz.palo.autoservis.mapper.AppointmentMapper;
import cz.palo.autoservis.mapper.EmployeeMapper;
import cz.palo.autoservis.mapper.VehicleMapper;
import cz.palo.autoservis.model.converter.AppointmentConverter;
import cz.palo.autoservis.model.domain.schedule.Appointment;
import cz.palo.autoservis.model.domain.vehicle.Vehicle;
import cz.palo.autoservis.model.dto.order.OrderDto;
import cz.palo.autoservis.model.dto.schedule.AppointmentDto;
import cz.palo.autoservis.model.enums.AppointmentStatus;
import cz.palo.autoservis.model.enums.AppointmentType;
import cz.palo.autoservis.service.AppointmentService;
import cz.palo.autoservis.service.OpeningHoursService;
import cz.palo.autoservis.service.OrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Plánovací kalendář — objednávky termínů (BOOKING), blokace dílny (CLOSURE)
 * a obecné události (EVENT, V82 — školení, dovolená zaměstnance…).
 *
 * <h3>Co hlídá service a co databáze</h3>
 * <p>CHECK constrainty v migraci V72 jsou poslední, neobejitelná linie. Service tytéž podmínky
 * kontroluje znovu, aby uživatel dostal českou hlášku (422) místo databázové chyby (500) —
 * a navíc řeší pravidla, na která CHECK nedosáhne, protože závisejí na jiných řádcích:
 * vlastnictví vozidla a překryv s blokací dílny.
 *
 * <h3>Kolize: varování, ne zákaz</h3>
 * <p>Překryv dvou objednávek se <strong>nezakazuje</strong> — servis běžně dělá na dvou autech
 * naráz a kapacita dílny se nikde neeviduje. Klient si ji zjistí přes
 * {@link #checkOverlaps} a rozhodne sám. Tvrdě padá jen objednávka do zavřené dílny.
 */
@Service
@RequiredArgsConstructor
public class AppointmentServiceImpl implements AppointmentService {

    private final AppointmentMapper appointmentMapper;
    private final AppointmentConverter appointmentConverter;
    private final VehicleMapper vehicleMapper;
    private final EmployeeMapper employeeMapper;
    private final OrderService orderService;
    private final OpeningHoursService openingHoursService;

    @Override
    public List<AppointmentDto.ListResponse> getInRange(OffsetDateTime from,
                                                        OffsetDateTime to,
                                                        AppointmentType entryType,
                                                        AppointmentStatus status) {
        if (from == null || to == null) {
            throw new IllegalArgumentException("from i to jsou povinné");
        }
        if (!to.isAfter(from)) {
            throw new BusinessRuleException(
                    "INVALID_RANGE", "to",
                    "Konec období musí být po jeho začátku.",
                    Map.of("from", from, "to", to));
        }
        List<Appointment> found = appointmentMapper.findInRange(from, to, entryType, status);
        return appointmentConverter.toListResponses(found);
    }

    @Override
    public AppointmentDto.DetailResponse getById(Long id) {
        if (id == null) {
            throw new IllegalArgumentException("id nesmí být null");
        }
        return appointmentMapper.findById(id)
                .map(appointmentConverter::toDetailResponse)
                .orElseThrow(() -> new ResourceNotFoundException("Objednávka", id));
    }

    @Override
    public Optional<AppointmentDto.DetailResponse> findByOrderId(Long orderId) {
        if (orderId == null) {
            throw new IllegalArgumentException("orderId nesmí být null");
        }
        return appointmentMapper.findByOrderId(orderId).map(appointmentConverter::toDetailResponse);
    }

    @Override
    @Transactional
    public AppointmentDto.DetailResponse create(AppointmentDto.CreateRequest request, Long userId) {
        requireValidTimeRange(request.getStartsAt(), request.getEndsAt());
        requireEndPresentForType(request.getEntryType(), request.getEndsAt());
        requireConsistentLinks(request.getEntryType(), request.getCustomerId(),
                request.getVehicleId(), request.getContactNote(), request.getEmployeeId());
        Long customerId = resolveCustomerId(request.getCustomerId(), request.getVehicleId());
        requireEmployeeExists(request.getEmployeeId());

        if (request.getEntryType() == AppointmentType.BOOKING) {
            requireWorkshopOpen(request.getStartsAt(), request.getEndsAt(), null);
        }
        if (request.getEntryType() == AppointmentType.CLOSURE) {
            requireNoBookingsInside(request.getStartsAt(), request.getEndsAt(), null);
        }

        Appointment appointment = appointmentConverter.toDomain(request);
        appointment.setCustomerId(customerId);
        appointment.setCreatedBy(userId);
        appointmentMapper.insert(appointment);

        return getById(appointment.getId());
    }

    @Override
    @Transactional
    public AppointmentDto.DetailResponse update(Long id, AppointmentDto.UpdateRequest request) {
        Appointment existing = requireActive(id);
        requireEditable(existing);

        requireValidTimeRange(request.getStartsAt(), request.getEndsAt());
        requireEndPresentForType(existing.getEntryType(), request.getEndsAt());
        requireConsistentLinks(existing.getEntryType(), request.getCustomerId(),
                request.getVehicleId(), request.getContactNote(), request.getEmployeeId());
        Long customerId = resolveCustomerId(request.getCustomerId(), request.getVehicleId());
        requireEmployeeExists(request.getEmployeeId());

        if (existing.getEntryType() == AppointmentType.BOOKING) {
            requireWorkshopOpen(request.getStartsAt(), request.getEndsAt(), id);
        }
        if (existing.getEntryType() == AppointmentType.CLOSURE) {
            requireNoBookingsInside(request.getStartsAt(), request.getEndsAt(), id);
        }

        Appointment updated = appointmentConverter.applyUpdate(existing, request);
        updated.setCustomerId(customerId);
        int affectedRows = appointmentMapper.update(updated);
        return verifyAndFetch(id, affectedRows);
    }

    @Override
    @Transactional
    public AppointmentDto.DetailResponse updateTime(Long id, AppointmentDto.TimeRequest request) {
        Appointment existing = requireActive(id);
        requireEditable(existing);
        requireValidTimeRange(request.getStartsAt(), request.getEndsAt());
        requireEndPresentForType(existing.getEntryType(), request.getEndsAt());

        if (existing.getEntryType() == AppointmentType.BOOKING) {
            requireWorkshopOpen(request.getStartsAt(), request.getEndsAt(), id);
        }
        // Přetažení blokace myší chodí sem, ne do update() — bez téhle kontroly šlo „zavřeno"
        // přetáhnout na den, kam už někdo přijede.
        if (existing.getEntryType() == AppointmentType.CLOSURE) {
            requireNoBookingsInside(request.getStartsAt(), request.getEndsAt(), id);
        }

        int affectedRows = appointmentMapper.updateTime(id, request.getStartsAt(), request.getEndsAt());
        return verifyAndFetch(id, affectedRows);
    }

    @Override
    @Transactional
    public AppointmentDto.DetailResponse changeStatus(Long id, AppointmentDto.StatusRequest request) {
        Appointment existing = requireActive(id);
        AppointmentStatus target = request.getStatus();

        // CONVERTED drží vazbu na zakázku, takže smí vzniknout jen v convert() — jinak by
        // prošel stav bez order_id a spadl by CHECK chk_appointments_converted_order.
        if (target == AppointmentStatus.CONVERTED) {
            throw new BusinessRuleException(
                    "STATUS_NOT_SETTABLE", "status",
                    "Stav „převedeno na zakázku\" vzniká jen převodem objednávky, nelze ho nastavit ručně.",
                    Map.of("status", target.name()));
        }
        if (existing.getEntryType() == AppointmentType.CLOSURE && target != AppointmentStatus.CANCELLED) {
            throw new BusinessRuleException(
                    "STATUS_NOT_ALLOWED_FOR_CLOSURE", "status",
                    "Blokaci dílny lze jen zrušit, jiné stavy pro ni nemají smysl.",
                    Map.of("status", target.name()));
        }
        // „Nedorazil" ani „převedeno" nedávají u události smysl — stejná logika jako u blokace.
        if (existing.getEntryType() == AppointmentType.EVENT && target != AppointmentStatus.CANCELLED) {
            throw new BusinessRuleException(
                    "STATUS_NOT_ALLOWED_FOR_EVENT", "status",
                    "Událost lze jen zrušit, jiné stavy pro ni nemají smysl.",
                    Map.of("status", target.name()));
        }
        if (!existing.getStatus().canTransitionTo(target)) {
            throw new BusinessRuleException(
                    "INVALID_STATUS_TRANSITION", "status",
                    "Z tohoto stavu už objednávka nikam nepřechází.",
                    Map.of("from", existing.getStatus().name(), "to", target.name()));
        }

        int affectedRows = appointmentMapper.updateStatus(id, target);
        return verifyAndFetch(id, affectedRows);
    }

    @Override
    @Transactional
    public void delete(Long id) {
        Appointment existing = requireActive(id);

        // Převedená objednávka není omyl — vzešla z ní skutečná zakázka a odkaz „Vzniklo
        // z objednávky" na jejím detailu je platný záznam. Smazat by šlo (nic na objednávku
        // neodkazuje), ale ztratila by se informace o původu zakázky.
        if (existing.getOrderId() != null) {
            throw new BusinessRuleException(
                    "APPOINTMENT_CONVERTED_CANNOT_DELETE", null,
                    "Objednávku, ze které už vznikla zakázka, nelze smazat.",
                    Map.of("orderId", existing.getOrderId()));
        }

        int affectedRows = appointmentMapper.delete(id);
        if (affectedRows == 0) {
            throw new IllegalStateException(
                    "Objednávka " + id + " zmizela během mazání (byla načtena těsně předtím)");
        }
    }

    @Override
    @Transactional
    public OrderDto.DetailResponse convert(Long id, OrderDto.CreateRequest orderRequest, Long userId) {
        Appointment appointment = requireActive(id);

        if (appointment.getEntryType() != AppointmentType.BOOKING) {
            throw new BusinessRuleException(
                    "NOT_CONVERTIBLE", null,
                    "Na zakázku lze převést jen objednávku zákazníka, ne blokaci dílny ani událost.",
                    Map.of("entryType", appointment.getEntryType().name()));
        }
        if (appointment.getOrderId() != null) {
            throw new BusinessRuleException(
                    "ALREADY_CONVERTED", null,
                    "Tato objednávka už má zakázku.",
                    Map.of("orderId", appointment.getOrderId()));
        }
        if (appointment.getStatus().isTerminal()) {
            throw new BusinessRuleException(
                    "INVALID_STATUS_TRANSITION", null,
                    "Zrušenou objednávku ani objednávku, na kterou zákazník nedorazil, nelze převést na zakázku.",
                    Map.of("status", appointment.getStatus().name()));
        }

        // Zakázka vzniká přes OrderService, aby se její pravidla (vlastnictví vozidla, zápis
        // tachometru do historie) nemusela kopírovat. Celé je to jedna transakce: když založení
        // zakázky selže, objednávka zůstane nedotčená a nevznikne osiřelá zakázka.
        OrderDto.DetailResponse createdOrder = orderService.create(orderRequest, userId);

        int affectedRows = appointmentMapper.linkOrder(id, createdOrder.getId());
        if (affectedRows == 0) {
            throw new IllegalStateException(
                    "Objednávka " + id + " zmizela během převodu na zakázku");
        }
        return createdOrder;
    }

    @Override
    public AppointmentDto.OverlapResponse checkOverlaps(OffsetDateTime startsAt,
                                                        OffsetDateTime endsAt,
                                                        Long excludeId) {
        requireValidTimeRange(startsAt, endsAt);

        OffsetDateTime probeEnd = effectiveEnd(startsAt, endsAt);
        List<Appointment> overlapping =
                appointmentMapper.findOverlappingBookings(startsAt, probeEnd, excludeId);
        int blockingClosures =
                appointmentMapper.countBlockingClosures(startsAt, probeEnd, excludeId);

        AppointmentDto.OverlapResponse response = new AppointmentDto.OverlapResponse();
        response.setOverlappingCount(overlapping.size());
        response.setOverlapping(appointmentConverter.toListResponses(overlapping));
        response.setBlockedByClosure(blockingClosures > 0);
        /*
         * Kontroluje se PŘÍJEZD a VYZVEDNUTÍ zvlášť, ne doba mezi nimi: auto přes noc v zavřené
         * dílně stojí běžně a vícedenní opravy (V74) na tom stojí. Otevírací doba se týká chvil,
         * kdy u toho musí někdo být.
         */
        response.setStartOutsideOpeningHours(openingHoursService.isOutsideOpeningHours(startsAt));
        response.setEndOutsideOpeningHours(openingHoursService.isOutsideOpeningHours(endsAt));
        return response;
    }

    // =========================================================================
    // Privátní pomocné metody
    // =========================================================================

    /**
     * Terminální objednávka (převedená, zrušená, po nedostavení) je uzavřený záznam.
     *
     * <p>Obor to drží stejně — Acuity zrušený termín „nejde od-zrušit" ani upravit. Čas a účastníci
     * jsou fakta o tom, co se stalo: jejich přepsáním by se znehodnotila statistika nedostavení
     * a u převedené objednávky by se údaje rozešly se vzniklou zakázkou. Oprava omylu se řeší
     * smazáním, ne editací historie.
     */
    private void requireEditable(Appointment existing) {
        if (existing.getStatus().isTerminal()) {
            throw new BusinessRuleException(
                    "APPOINTMENT_TERMINAL_READONLY", null,
                    "Uzavřenou objednávku (převedenou, zrušenou nebo po nedostavení) už nelze upravovat.",
                    Map.of("status", existing.getStatus().name()));
        }
    }

    /** Načte položku, nebo skončí 404. */
    private Appointment requireActive(Long id) {
        if (id == null) {
            throw new IllegalArgumentException("id nesmí být null");
        }
        return appointmentMapper.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Objednávka", id));
    }

    /**
     * Zrcadlí {@code chk_appointments_time_range}: konec buď není, nebo je po začátku.
     *
     * <p>{@code endsAt == null} je legitimní stav — „zákazník nechá auto, konec neznámý" (V74).
     * Kontrola je tu proto, aby uživatel dostal srozumitelnou hlášku dřív, než na to narazí databáze.
     */
    private void requireValidTimeRange(OffsetDateTime startsAt, OffsetDateTime endsAt) {
        if (startsAt == null) {
            throw new IllegalArgumentException("startsAt je povinný");
        }
        if (endsAt != null && !endsAt.isAfter(startsAt)) {
            throw new BusinessRuleException(
                    "INVALID_TIME_RANGE", "endsAt",
                    "Konec termínu musí být po jeho začátku.",
                    Map.of("startsAt", startsAt, "endsAt", endsAt));
        }
    }

    /**
     * Blokace dílny musí mít konec ({@code chk_appointments_closure_has_end}) — „zavřeno navždy"
     * nedává smysl a zablokovalo by každou budoucí objednávku. Událost jakbysmet
     * ({@code chk_appointments_event_has_end}, V82): „dovolená navždy" není termín, ale odchod.
     * Otevřený konec (V74) zůstává jen objednávce — délku opravy mechanik předem nezná.
     */
    private void requireEndPresentForType(AppointmentType entryType, OffsetDateTime endsAt) {
        if (endsAt != null) {
            return;
        }
        if (entryType == AppointmentType.CLOSURE) {
            throw new BusinessRuleException(
                    "CLOSURE_END_REQUIRED", "endsAt",
                    "U blokace dílny je konec povinný — jinak by zavřela dílnu natrvalo.",
                    Map.of());
        }
        if (entryType == AppointmentType.EVENT) {
            throw new BusinessRuleException(
                    "EVENT_END_REQUIRED", "endsAt",
                    "U události je konec povinný.",
                    Map.of());
        }
    }

    /**
     * Okno pro časové dotazy. Objednávka bez známého konce nemá interval, takže se posuzuje
     * <strong>jen podle příjezdu</strong>: hledá se, jestli ten okamžik spadá do blokace,
     * resp. do termínu jiné objednávky. Dosadit smyšlenou délku by znamenalo vymýšlet si data.
     */
    private OffsetDateTime effectiveEnd(OffsetDateTime startsAt, OffsetDateTime endsAt) {
        return endsAt != null ? endsAt : startsAt.plusSeconds(1);
    }

    /**
     * Zrcadlí {@code chk_appointments_closure_empty}, {@code chk_appointments_event_empty},
     * {@code chk_appointments_contact_booking_only} a {@code chk_appointments_employee_event_only}:
     * blokace dílny ani událost nesmí mít zákazníka, vozidlo ani kontakt; kontakt patří jen
     * k objednávce a zaměstnanec jen k události.
     *
     * <p>Objednávka sama nemusí mít <em>nic</em> (V85): zákazník ani vozidlo povinné nejsou, protože
     * termín se domlouvá dřív, než servis oboje zná. Čitelná zůstane díky {@code title}, který je
     * povinný vždy.
     */
    private void requireConsistentLinks(AppointmentType entryType, Long customerId,
                                        Long vehicleId, String contactNote, Long employeeId) {
        boolean hasContact = contactNote != null && !contactNote.isBlank();

        if (entryType == AppointmentType.CLOSURE
                && (customerId != null || vehicleId != null || hasContact)) {
            throw new BusinessRuleException(
                    "CLOSURE_MUST_BE_EMPTY", "customerId",
                    "Blokace dílny nesmí mít zákazníka, vozidlo ani kontakt.",
                    Map.of());
        }
        if (entryType == AppointmentType.EVENT
                && (customerId != null || vehicleId != null || hasContact)) {
            throw new BusinessRuleException(
                    "EVENT_MUST_BE_EMPTY", "customerId",
                    "Událost nesmí mít zákazníka, vozidlo ani kontakt.",
                    Map.of());
        }
        if (entryType != AppointmentType.EVENT && employeeId != null) {
            throw new BusinessRuleException(
                    "EMPLOYEE_ONLY_FOR_EVENT", "employeeId",
                    "Zaměstnance lze uvést jen u události.",
                    Map.of("employeeId", employeeId));
        }
        /*
         * Kombinace „vozidlo bez zákazníka" tu chybí schválně: od V85 není chyba, ale běžný vstup.
         * Zákazníka k němu dopočítá resolveCustomerId z majitele auta.
         */
    }

    /**
     * Zaměstnanec u události musí existovat a být aktivní — FK by cizí {@code employeeId} sice
     * chytila, ale jako 422 „porušení databázového omezení" bez určení pole; deaktivovaného
     * zaměstnance by pustila úplně. {@code employeeId == null} je legitimní (událost bez vazby).
     */
    private void requireEmployeeExists(Long employeeId) {
        if (employeeId == null) {
            return;
        }
        employeeMapper.findById(employeeId)
                .orElseThrow(() -> new ResourceNotFoundException("Zaměstnanec", employeeId));
    }

    /**
     * Vrátí zákazníka, se kterým se objednávka uloží — a cestou ověří, že vozidlo sedí.
     *
     * <p>Tři případy:
     * <ul>
     *   <li><strong>Bez vozidla</strong> — vrací zadaného zákazníka (klidně {@code null}, od V85
     *       je objednávka bez obou legitimní).</li>
     *   <li><strong>Vozidlo bez zákazníka</strong> — zákazníka <em>dopočítá z majitele auta</em>.
     *       {@code vehicles.customer_id} je NOT NULL (V5), takže dopočet vždy vyjde. Obsluha, která
     *       vybrala SPZ, tím nemusí vyplňovat podruhé to, co databáze už ví.</li>
     *   <li><strong>Obojí</strong> — musí souhlasit, jinak by šlo objednat cizí auto a převod na
     *       zakázku by selhal až na konci. Táž kontrola jako v {@code OrderServiceImpl.create}
     *       (audit K-12/V-3).</li>
     * </ul>
     */
    private Long resolveCustomerId(Long customerId, Long vehicleId) {
        if (vehicleId == null) {
            return customerId;
        }
        Vehicle vehicle = vehicleMapper.findById(vehicleId)
                .orElseThrow(() -> new ResourceNotFoundException("Vozidlo", vehicleId));
        if (customerId == null) {
            return vehicle.getCustomerId();
        }
        if (!vehicle.getCustomerId().equals(customerId)) {
            throw new BusinessRuleException(
                    "VEHICLE_NOT_OWNED_BY_CUSTOMER", "vehicleId",
                    "Vozidlo nepatří vybranému zákazníkovi.",
                    Map.of("vehicleId", vehicleId, "customerId", customerId));
        }
        return customerId;
    }

    /**
     * Jediné tvrdé časové pravidlo: do zavřené dílny se objednat nedá.
     * Překryv s jinou objednávkou naopak povolený je — viz {@link #checkOverlaps}.
     */
    private void requireWorkshopOpen(OffsetDateTime startsAt, OffsetDateTime endsAt, Long excludeId) {
        int blocking = appointmentMapper.countBlockingClosures(
                startsAt, effectiveEnd(startsAt, endsAt), excludeId);
        if (blocking > 0) {
            // Map.of() zakazuje null hodnoty — u objednávky bez známého konce by sestavení
            // parametrů spadlo na NPE dřív, než by se pravidlo stihlo ohlásit (odhaleno testem).
            Map<String, Object> params = new HashMap<>();
            params.put("startsAt", startsAt);
            if (endsAt != null) {
                params.put("endsAt", endsAt);
            }
            throw new BusinessRuleException(
                    "APPOINTMENT_IN_CLOSURE", "startsAt",
                    "V tomto termínu je dílna zavřená.",
                    params);
        }
    }

    /**
     * Druhá polovina téhož pravidla: <strong>blokace nesmí padnout na už objednaný čas.</strong>
     *
     * <p>Do V85 se hlídal jen jeden směr — objednávka do zavřené dílny neprošla, ale blokaci
     * šlo nakreslit přes existující objednávku. Kalendář pak v jednom okamžiku tvrdil obojí:
     * „zavřeno" i „ve 13:00 přijede Novák". Zavřít dílnu na dobu, kdy někdo přijede, je vždycky
     * chyba — buď se přesune objednávka, nebo se zavírá jindy, a rozhodnout to musí obsluha.
     *
     * <p>Proč tvrdý zákaz, když překryv dvou objednávek jen varuje: dvě auta naráz servis běžně
     * zvládá, ale „zavřeno" a „přijede zákazník" se vylučují. Stejný důvod, proč zakazuje
     * i opačný směr ({@link #requireWorkshopOpen}).
     */
    private void requireNoBookingsInside(OffsetDateTime startsAt, OffsetDateTime endsAt,
                                         Long excludeId) {
        int conflicting = appointmentMapper.countEntriesBlockingClosure(
                startsAt, effectiveEnd(startsAt, endsAt), excludeId);
        if (conflicting > 0) {
            throw new BusinessRuleException(
                    "CLOSURE_OVERLAPS_ENTRIES", "startsAt",
                    conflicting == 1
                            ? "V tomto termínu je naplánovaná objednávka nebo událost."
                            : "V tomto termínu je naplánováno " + conflicting
                              + " objednávek nebo událostí.",
                    Map.of("conflicting", conflicting));
        }
    }

    private AppointmentDto.DetailResponse verifyAndFetch(Long id, int affectedRows) {
        if (affectedRows == 0) {
            throw new IllegalStateException(
                    "Objednávka " + id + " zmizela během aktualizace (byla načtena těsně předtím)");
        }
        return appointmentMapper.findById(id)
                .map(appointmentConverter::toDetailResponse)
                .orElseThrow(() -> new IllegalStateException(
                        "Objednávka " + id + " zmizela mezi UPDATE a SELECT"));
    }
}
