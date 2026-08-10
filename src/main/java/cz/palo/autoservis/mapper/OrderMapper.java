package cz.palo.autoservis.mapper;

import cz.palo.autoservis.model.domain.order.Order;
import cz.palo.autoservis.model.dto.order.OrderSearchParams;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Optional;

/**
 * MyBatis mapper rozhraní tabulky {@code order.orders}.
 *
 * <p>Konvence:
 * <ul>
 *   <li>Veškeré SQL je výhradně v {@code mapper/OrderMapper.xml}.</li>
 *   <li>Anotace typu {@code @Select} se nepoužívají — pro složitější dotazy má přednost XML.</li>
 *   <li>Dotazy na jeden záznam vracejí {@link Optional}, aby volající nemusel kontrolovat null.</li>
 * </ul>
 */
@Mapper
public interface OrderMapper {

    /**
     * Hledá zakázky s dynamickými filtry a stránkováním.
     *
     * @param params parametry hledání — filtry, stránka a velikost stránky
     * @return seznam odpovídajících zakázek
     */
    List<Order> search(@Param("params") OrderSearchParams params);

    /**
     * Vrací celkový počet výsledků odpovídajících parametrům hledání — pro stránkování.
     *
     * @param params parametry hledání (stejné filtry jako {@link #search}, bez LIMIT/OFFSET)
     * @return celkový počet odpovídajících zakázek
     */
    long countSearch(@Param("params") OrderSearchParams params);

    /**
     * Vloží novou servisní zakázku. Po úspěšném INSERTu se vygenerovaný PK
     * zapíše zpět do {@code order.id} přes {@code useGeneratedKeys}.
     *
     * @param order nová zakázka (id musí být null)
     */
    void insert(Order order);

    /**
     * Aktualizuje existující zakázku. Dynamický UPDATE — mění se jen non-null pole.
     * Pole {@code order_number}, {@code created_at} a {@code created_by} se nikdy nemění.
     *
     * @param order zakázka s novými hodnotami
     * @return počet ovlivněných řádků (0 = nenalezena, 1 = úspěch)
     */
    int update(Order order);

    /**
     * Změní jen stav a datum dokončení — <strong>úzký</strong> protějšek full-replace
     * {@link #update}.
     *
     * <p>Zapisují se dva sloupce, které ta operace opravdu mění. Full-replace by při rychlé
     * změně stavu ze seznamu přepsal i popis a ceny hodnotami, které si klient nenačetl —
     * přesně ta tichá ztráta dat, kvůli které vznikl TD-47.
     *
     * @param id          ID zakázky
     * @param status      cílový stav
     * @param completedAt datum skutečného dokončení ({@code null} u znovuotevření)
     * @return počet ovlivněných řádků
     */
    int updateStatus(@Param("id") Long id,
                     @Param("status") cz.palo.autoservis.model.enums.OrderStatus status,
                     @Param("completedAt") java.time.OffsetDateTime completedAt);

    /**
     * Tvrdě smaže zakázku (V84, Etapa 2 — omyl obsluhy).
     *
     * <p><strong>Výjimka z R-06 (soft-delete)</strong>, vědomá: mazání je vyhrazené pro
     * záznam, který nikdy neměl vzniknout — překlep, špatné auto. Zakázka, u které k práci
     * nedošlo, se <em>ruší</em> stavem {@code CANCELLED}, ne maže.
     *
     * <p>Kaskádou odejdou položky zakázky ({@code order_items}) i odečet tachometru
     * z příjmu ({@code vehicle.mileage_history}, V84). Skladový pohyb a faktura naopak
     * mazání blokují na úrovni cizích klíčů ({@code ON DELETE RESTRICT}) — service je
     * odmítá dřív, aby obsluha dostala hlášku místo chyby integrity.
     *
     * @param id ID zakázky
     * @return počet smazaných řádků (0 = neexistuje, 1 = smazáno)
     */
    int delete(@Param("id") Long id);

    /**
     * Najde zakázku podle ID.
     *
     * @param id ID zakázky
     * @return zakázka v {@link Optional}, nebo prázdný, když nebyla nalezena
     */
    Optional<Order> findById(@Param("id") Long id);

    /**
     * Spočítá otevřené (neterminální) zakázky zákazníka.
     * Otevřená = is_active = TRUE AND status NOT IN (COMPLETED, CANCELLED).
     * Blokuje deaktivaci zákazníka, dokud běží práce.
     *
     * @param  customerId ID zákazníka
     * @return počet otevřených zakázek (0 = žádná, deaktivace je bezpečná)
     */
    int countOpenByCustomerId(@Param("customerId") Long customerId);

    /**
     * Spočítá otevřené (neterminální) zakázky vozidla.
     * Otevřená = is_active = TRUE AND status NOT IN (COMPLETED, CANCELLED).
     * Blokuje deaktivaci vozidla, dokud běží práce.
     *
     * @param  vehicleId ID vozidla
     * @return počet otevřených zakázek (0 = žádná, deaktivace je bezpečná)
     */
    int countOpenByVehicleId(@Param("vehicleId")  Long vehicleId);
}
