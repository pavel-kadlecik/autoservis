package cz.palo.autoservis.service;

import cz.palo.autoservis.model.dto.order.OrderDto;
import cz.palo.autoservis.model.dto.schedule.AppointmentDto;
import cz.palo.autoservis.model.enums.AppointmentStatus;
import cz.palo.autoservis.model.enums.AppointmentType;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Plánovací (objednávkový) kalendář — objednávky termínů a blokace dílny.
 *
 * <p>Objednávka vzniká <strong>dřív</strong> než zakázka a je na ní nezávislá; převodem
 * ({@link #convert}) se na zakázku naváže, ale přežije i její smazání.
 */
public interface AppointmentService {

    /**
     * Vrátí položky kalendáře zasahující do zadaného okna, seřazené podle začátku.
     *
     * <p>Bez stránkování — okno je přirozený limit.
     *
     * @param from      začátek okna (včetně)
     * @param to        konec okna (mimo)
     * @param entryType volitelný filtr typu, {@code null} = oba
     * @param status    volitelný filtr stavu, {@code null} = všechny
     * @return položky pro vykreslení kalendáře
     */
    List<AppointmentDto.ListResponse> getInRange(OffsetDateTime from,
                                                 OffsetDateTime to,
                                                 AppointmentType entryType,
                                                 AppointmentStatus status);

    /**
     * @param id ID položky
     * @return detail položky
     * @throws cz.palo.autoservis.exception.ResourceNotFoundException když neexistuje nebo je neaktivní
     */
    AppointmentDto.DetailResponse getById(Long id);

    /**
     * Vrátí objednávku, ze které vznikla daná zakázka.
     *
     * @param orderId ID zakázky
     * @return objednávka, nebo prázdné {@link Optional}, když zakázka vznikla přímo
     */
    Optional<AppointmentDto.DetailResponse> findByOrderId(Long orderId);

    /**
     * Založí novou položku kalendáře.
     *
     * @param request vstup z API
     * @param userId  přihlášený uživatel (audit, R-04)
     * @return vytvořená položka načtená z DB
     */
    AppointmentDto.DetailResponse create(AppointmentDto.CreateRequest request, Long userId);

    /**
     * Upraví měnitelná pole. Typ ani stav se touto cestou nemění.
     *
     * @param id      ID položky
     * @param request vstup z API
     * @return aktuální stav položky
     */
    AppointmentDto.DetailResponse update(Long id, AppointmentDto.UpdateRequest request);

    /**
     * Posune termín (drag-and-drop, změna délky v kalendáři).
     *
     * @param id      ID položky
     * @param request nový začátek a konec
     * @return aktuální stav položky
     */
    AppointmentDto.DetailResponse updateTime(Long id, AppointmentDto.TimeRequest request);

    /**
     * Změní stav objednávky podle automatu {@link AppointmentStatus#canTransitionTo}.
     *
     * @param id      ID položky
     * @param request cílový stav
     * @return aktuální stav položky
     */
    AppointmentDto.DetailResponse changeStatus(Long id, AppointmentDto.StatusRequest request);

    /**
     * Trvale smaže položku založenou omylem (V76).
     *
     * <p>Zrušení skutečné objednávky je změna stavu na {@code CANCELLED} — ta zůstává v historii.
     * Tohle je pro záznam, který nikdy neměl vzniknout, a mizí úplně.
     *
     * @param id ID položky
     * @throws cz.palo.autoservis.exception.BusinessRuleException když už z objednávky vznikla zakázka
     */
    void delete(Long id);

    /**
     * Převede objednávku na zakázku — <strong>atomicky</strong>.
     *
     * <p>V jedné transakci vznikne zakázka (přes {@code OrderService.create}, logika se nekopíruje),
     * objednávce se nastaví {@code order_id} a stav {@code CONVERTED}. Selže-li kterýkoli krok,
     * neprovede se ani jeden — jinak by vznikla zakázka, o které objednávka neví.
     *
     * @param id            ID objednávky
     * @param orderRequest  vstup pro zakázku (uživatelem doplněný formulář)
     * @param userId        přihlášený uživatel
     * @return detail vzniklé zakázky
     */
    OrderDto.DetailResponse convert(Long id, OrderDto.CreateRequest orderRequest, Long userId);

    /**
     * Zjistí, kolik aktivních objednávek se s navrženým termínem překrývá a zda termín nespadá
     * do blokace dílny. Read-only podklad pro varování v UI před uložením.
     *
     * @param startsAt  navržený začátek
     * @param endsAt    navržený konec
     * @param excludeId položka, kterou při kontrole ignorovat (ta právě editovaná), smí být {@code null}
     * @return počet překryvů, jejich seznam a příznak blokace
     */
    AppointmentDto.OverlapResponse checkOverlaps(OffsetDateTime startsAt,
                                                 OffsetDateTime endsAt,
                                                 Long excludeId);
}
