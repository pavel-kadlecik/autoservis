package cz.palo.autoservis.mapper;

import cz.palo.autoservis.model.domain.vehicle.Vehicle;
import cz.palo.autoservis.model.dto.autocomplete.AutocompleteItem;
import cz.palo.autoservis.model.dto.vehicle.VehicleAutocompleteParams;
import cz.palo.autoservis.model.dto.vehicle.VehicleSearchParams;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Optional;

/**
 * MyBatis mapper rozhraní tabulky {@code vehicle.vehicles}.
 *
 * <p>Konvence:
 * <ul>
 *   <li>Veškeré SQL je výhradně v {@code mapper/VehicleMapper.xml}.</li>
 *   <li>Anotace typu {@code @Select} se nepoužívají — pro složitější dotazy má přednost XML.</li>
 *   <li>Všechny kolekcové dotazy a SELECTy filtrují {@code is_active = TRUE},
 *       kromě {@link #findByIdIncludingInactive(Long)}, který deaktivované záznamy
 *       záměrně zahrnuje pro administrativní účely.</li>
 * </ul>
 */
@Mapper
public interface VehicleMapper {

    // =========================================================================
    // CREATE
    // =========================================================================

    /**
     * Vloží nové vozidlo. Po úspěšném INSERTu se vygenerovaný PK
     * zapíše zpět do {@code vehicle.id} přes {@code useGeneratedKeys}.
     *
     * @param vehicle nové vozidlo (id musí být null)
     */
    void insert(Vehicle vehicle);

    // =========================================================================
    // READ
    // =========================================================================

    /**
     * Najde aktivní vozidlo podle ID.
     * Pro administrativní přístup použij {@link #findByIdIncludingInactive(Long)}.
     *
     * @param id ID vozidla
     * @return vozidlo v {@link Optional}, nebo prázdný, když neexistuje nebo je neaktivní
     */
    Optional<Vehicle> findById(@Param("id") Long id);

    /**
     * Najde vozidlo podle ID bez ohledu na stav {@code is_active}.
     * Pro administraci a reaktivaci deaktivovaných záznamů.
     *
     * @param id ID vozidla
     * @return vozidlo v {@link Optional}, nebo prázdný, když neexistuje
     */
    Optional<Vehicle> findByIdIncludingInactive(@Param("id") Long id);

    /**
     * Vrací všechna aktivní vozidla daného zákazníka,
     * seřazená podle značky a modelu pro stabilní výstup.
     *
     * @param customerId ID zákazníka
     * @return seznam aktivních vozidel
     */
    List<Vehicle> findByCustomerId(@Param("customerId") Long customerId);

    /**
     * Hledá vozidla s dynamickými filtry a stránkováním.
     *
     * @param params parametry hledání — filtry, stránka a velikost stránky
     * @return seznam odpovídajících vozidel
     */
    List<Vehicle> search(@Param("params") VehicleSearchParams params);

    /**
     * Vrací celkový počet výsledků odpovídajících parametrům hledání — pro stránkování.
     *
     * @param params parametry hledání (stejné filtry jako {@link #search}, bez LIMIT/OFFSET)
     * @return celkový počet odpovídajících vozidel
     */
    long countSearch(@Param("params") VehicleSearchParams params);

    /**
     * Vrací návrhy našeptávače pro vyhledávací pole vozidel.
     *
     * @param params parametry našeptávače (dotaz, limit)
     * @return seznam položek našeptávače
     */
    List<AutocompleteItem> autocomplete(@Param("params") VehicleAutocompleteParams params);

    // =========================================================================
    // UPDATE
    // =========================================================================

    /**
     * Aktualizuje editovatelná pole vozidla — full-replace včetně {@code vin}
     * (od V90 je VIN editovatelný, např. dodatečné doplnění u stroje).
     * {@code created_at} a {@code created_by} se nikdy nemění;
     * {@code updated_at} řeší DB trigger.
     *
     * @param vehicle vozidlo s novými hodnotami
     * @return počet ovlivněných řádků (0 = nenalezeno, 1 = úspěch)
     */
    int update(Vehicle vehicle);

    /**
     * Deaktivuje vozidlo nastavením {@code is_active = FALSE}.
     * Záznam zůstává v databázi kvůli servisní historii.
     *
     * @param id ID vozidla
     * @return počet ovlivněných řádků (0 = nenalezeno)
     */
    int deactivate(@Param("id") Long id);

    /**
     * Deaktivuje VŠECHNA aktuálně aktivní vozidla daného zákazníka
     * (soft delete, is_active = FALSE). Používá se při deaktivaci vlastníka,
     * aby platil invariant „aktivní vozidlo ⇒ aktivní vlastník".
     *
     * @param customerId ID zákazníka
     * @return počet skutečně deaktivovaných vozidel
     */
    int deactivateByCustomerId (@Param("customerId") Long customerId);

    /**
     * Znovu aktivuje dříve deaktivované vozidlo nastavením {@code is_active = TRUE}.
     *
     * @param id ID vozidla
     * @return počet ovlivněných řádků (0 = nenalezeno)
     */
    int activate(@Param("id") Long id);

    // =========================================================================
    // EXISTS / COUNT
    // =========================================================================

    /**
     * Zjistí, zda už existuje vozidlo s daným VIN.
     * Volat před INSERTem — srozumitelná hláška místo chytání porušení unikátu.
     *
     * @param vin Vehicle Identification Number
     * @return {@code true}, pokud vozidlo s tímto VIN existuje
     */
    boolean existsByVin(@Param("vin") String vin);
}
