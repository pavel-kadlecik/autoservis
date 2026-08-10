package cz.palo.autoservis.mapper;

import cz.palo.autoservis.model.domain.schedule.Appointment;
import cz.palo.autoservis.model.enums.AppointmentStatus;
import cz.palo.autoservis.model.enums.AppointmentType;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * MyBatis mapper rozhraní tabulky {@code schedule.appointments}.
 *
 * <p>Konvence:
 * <ul>
 *   <li>Veškeré SQL je výhradně v {@code mapper/AppointmentMapper.xml} (R-01).</li>
 *   <li>Dotazy na jeden záznam vracejí {@link Optional}, aby volající nemusel kontrolovat null (N-01).</li>
 *   <li>Bez soft-delete (V76): objednávka není doklad, omylem založená se maže natvrdo.</li>
 * </ul>
 */
@Mapper
public interface AppointmentMapper {

    /**
     * Vrací záznamy kalendáře překrývající dané okno, seřazené podle začátku.
     *
     * <p>Záměrně bez stránkování: přirozeným limitem je časové okno. Dvě období se
     * překrývají, když {@code startsAt < to AND from < endsAt} — vrátit se musí
     * i záznam, který začíná před oknem a končí uvnitř, jinak by vícedenní blokace
     * z pohledu zmizela.
     *
     * @param from      začátek okna (včetně)
     * @param to        konec okna (mimo)
     * @param entryType volitelný filtr, {@code null} = oba typy
     * @param status    volitelný filtr, {@code null} = všechny stavy
     * @return odpovídající záznamy s projekcemi zákazníka, vozidla a zakázky
     */
    List<Appointment> findInRange(@Param("from") OffsetDateTime from,
                                  @Param("to") OffsetDateTime to,
                                  @Param("entryType") AppointmentType entryType,
                                  @Param("status") AppointmentStatus status);

    /**
     * Najde záznam podle ID, včetně JOIN projekcí.
     *
     * @param id ID záznamu
     * @return záznam v {@link Optional}, nebo prázdný, když nebyl nalezen
     */
    Optional<Appointment> findById(@Param("id") Long id);

    /**
     * Vrací objednávku, ze které daná zakázka vznikla, pokud nějaká je.
     *
     * @param orderId ID zakázky
     * @return objednávka v {@link Optional}
     */
    Optional<Appointment> findByOrderId(@Param("orderId") Long orderId);

    /**
     * Vrací objednávky překrývající okno — pro měkké varování o překryvu.
     * Blokace jsou vyloučené: ty jsou tvrdé pravidlo, vynucuje ho {@link #countBlockingClosures}.
     *
     * @param startsAt  navrhovaný začátek
     * @param endsAt    navrhovaný konec
     * @param excludeId záznam k ignorování (ten právě editovaný), může být {@code null}
     * @return překrývající se objednávky seřazené podle začátku
     */
    List<Appointment> findOverlappingBookings(@Param("startsAt") OffsetDateTime startsAt,
                                              @Param("endsAt") OffsetDateTime endsAt,
                                              @Param("excludeId") Long excludeId);

    /**
     * Spočítá blokace (zavřená dílna) překrývající dané okno.
     * Nenulový výsledek znamená, že sem objednávka nesmí — jediné tvrdé časové pravidlo.
     *
     * @param startsAt  navrhovaný začátek
     * @param endsAt    navrhovaný konec
     * @param excludeId záznam k ignorování, může být {@code null}
     * @return počet blokujících blokací (0 = volno)
     */
    int countBlockingClosures(@Param("startsAt") OffsetDateTime startsAt,
                              @Param("endsAt") OffsetDateTime endsAt,
                              @Param("excludeId") Long excludeId);

    /**
     * Spočítá objednávky a události překrývající dané okno — zrcadlo
     * {@link #countBlockingClosures}. Nenulový výsledek znamená, že sem nesmí
     * <strong>blokace</strong>: zavřít dílnu na slot, kam už je někdo objednaný,
     * je vždy chyba. Zrušené záznamy a no-show se ignorují — na ty nikdo nepřijede.
     *
     * @param startsAt  navrhovaný začátek blokace
     * @param endsAt    navrhovaný konec blokace
     * @param excludeId záznam k ignorování (editovaná blokace), může být {@code null}
     * @return počet záznamů v cestě (0 = volno)
     */
    int countEntriesBlockingClosure(@Param("startsAt") OffsetDateTime startsAt,
                                    @Param("endsAt") OffsetDateTime endsAt,
                                    @Param("excludeId") Long excludeId);

    /**
     * Vloží nový záznam. Vygenerovaný PK se zapíše zpět přes {@code useGeneratedKeys}.
     *
     * @param appointment nový záznam (id musí být null)
     */
    void insert(Appointment appointment);

    /**
     * Aktualizuje měnitelná pole. {@code entry_type}, {@code status}, {@code order_id}
     * a auditní sloupce se tady nikdy nemění — každý má vlastní vyhrazený příkaz.
     *
     * @param appointment záznam s novými hodnotami
     * @return počet ovlivněných řádků (0 = nenalezen)
     */
    int update(Appointment appointment);

    /**
     * Posune záznam v čase — používá drag-and-drop a resize v kalendáři.
     *
     * @param id       ID záznamu
     * @param startsAt nový začátek
     * @param endsAt   nový konec
     * @return počet ovlivněných řádků
     */
    int updateTime(@Param("id") Long id,
                   @Param("startsAt") OffsetDateTime startsAt,
                   @Param("endsAt") OffsetDateTime endsAt);

    /**
     * Změní stav životního cyklu.
     *
     * @param id     ID záznamu
     * @param status nový stav
     * @return počet ovlivněných řádků
     */
    int updateStatus(@Param("id") Long id, @Param("status") AppointmentStatus status);

    /**
     * Naváže objednávku na zakázku z ní vzniklou a zároveň jedním příkazem přepne
     * stav na {@code CONVERTED} — ty dvě hodnoty se nikdy nesmí rozejít
     * ({@code chk_appointments_converted_order}).
     *
     * @param id      ID objednávky
     * @param orderId ID nově vzniklé zakázky
     * @return počet ovlivněných řádků
     */
    int linkOrder(@Param("id") Long id, @Param("orderId") Long orderId);

    /**
     * Odpojí objednávku od zakázky a vrátí ji na {@code PLANNED} (V84).
     *
     * <p>Volá se před smazáním zakázky, která z objednávky vznikla. Smazaná zakázka byla
     * omyl, ale <strong>termín domluvený se zákazníkem omyl nebyl</strong> — zůstane
     * v kalendáři a jde ho převést znovu.
     *
     * <p>{@code order_id} a stav se mění společně: CHECK {@code chk_appointments_converted_order}
     * je váže k sobě, takže samotné odpojení by ho porušilo.
     *
     * @param orderId ID mazané zakázky
     * @return počet ovlivněných řádků (0 = zakázka z objednávky nevznikla)
     */
    int unlinkOrder(@Param("orderId") Long orderId);

    /**
     * Trvale smaže záznam — pro záznamy založené omylem (V76).
     * Zrušení skutečné objednávky je změna stavu ({@code CANCELLED}), ne tohle.
     *
     * @param id ID záznamu
     * @return počet ovlivněných řádků (0 = nenalezen)
     */
    int delete(@Param("id") Long id);
}
