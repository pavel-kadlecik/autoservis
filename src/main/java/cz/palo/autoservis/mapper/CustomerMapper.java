package cz.palo.autoservis.mapper;

import cz.palo.autoservis.model.dto.autocomplete.AutocompleteItem;
import cz.palo.autoservis.model.dto.customer.CustomerAutocompleteParams;
import cz.palo.autoservis.model.dto.customer.CustomerSearchParams;
import cz.palo.autoservis.model.domain.customer.Customer;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Optional;

/**
 * MyBatis mapper rozhraní tabulky {@code customer.customers}.
 *
 * <p>Konvence:
 * <ul>
 *   <li>Veškeré SQL je výhradně v {@code mapper/CustomerMapper.xml}.</li>
 *   <li>Anotace typu {@code @Select} se nepoužívají — XML je u složitějších dotazů
 *       čitelnější a lépe se udržuje.</li>
 *   <li>Dotazy na jeden záznam vracejí {@link Optional}, aby volající nemusel kontrolovat null.</li>
 * </ul>
 *
 * Anotaci {@code @Mapper} sbírá automaticky {@code @MapperScan}.
 */
@Mapper
public interface CustomerMapper {

    /**
     * Načte zákazníka včetně vnořených adres a kontaktních osob (plný detail).
     *
     * @param id ID zákazníka
     * @return zákazník v {@link Optional}, nebo prázdný, když nebyl nalezen
     */
    Optional<Customer> findById(@Param("id") Long id);

    /**
     * Načte zákazníka bez vnořených objektů — pro seznamy, ať se nedělají zbytečné JOINy.
     *
     * @param id ID zákazníka
     * @return zákazník v {@link Optional}, nebo prázdný, když nebyl nalezen
     */
    Optional<Customer> findByIdShallow(@Param("id") Long id);

    /**
     * Hledá zákazníky s dynamickými filtry a stránkováním.
     *
     * @param params parametry hledání — filtry, stránka a velikost stránky
     * @return seznam odpovídajících zákazníků
     */
    List<Customer> search(@Param("params") CustomerSearchParams params);

    /**
     * Vrací návrhy našeptávače pro vyhledávací pole zákazníků.
     *
     * @param params parametry našeptávače (dotaz, limit)
     * @return seznam položek našeptávače
     */
    List<AutocompleteItem> autocomplete(@Param("params") CustomerAutocompleteParams params);

    /**
     * Vrací celkový počet výsledků odpovídajících parametrům hledání — pro stránkování.
     *
     * @param params parametry hledání (stejné filtry jako {@link #search}, bez LIMIT/OFFSET)
     * @return celkový počet odpovídajících zákazníků
     */
    long countSearch(@Param("params") CustomerSearchParams params);

    /**
     * Vloží nového zákazníka — soukromou osobu.
     *
     * @param customer doménový objekt zákazníka (id musí být null)
     * @return počet ovlivněných řádků
     */
    int insertIndividual(Customer customer);

    /**
     * Vloží nového zákazníka — firmu (právnickou osobu nebo OSVČ).
     *
     * @param customer doménový objekt zákazníka (id musí být null)
     * @return počet ovlivněných řádků
     */
    int insertCompany(Customer customer);

    /**
     * Aktualizuje existujícího zákazníka. Pole {@code created_at} a {@code created_by}
     * se nikdy nemění. Dynamický UPDATE — mění se jen non-null pole.
     *
     * @param customer doménový objekt zákazníka s novými hodnotami
     * @return počet ovlivněných řádků (0 = nenalezen, 1 = úspěch)
     */
    int update(Customer customer);

    /**
     * Deaktivuje zákazníka nastavením {@code is_active = FALSE}.
     * Záznam zůstává v databázi kvůli servisní historii.
     *
     * @param id ID zákazníka
     * @return počet ovlivněných řádků (0 = nenalezen)
     */
    int deactivate(@Param("id") Long id);

    /**
     * Znovu aktivuje dříve deaktivovaného zákazníka nastavením {@code is_active = TRUE}.
     *
     * @param id ID zákazníka
     * @return počet ovlivněných řádků (0 = nenalezen)
     */
    int activate(@Param("id") Long id);

    /**
     * Zjistí, zda už existuje zákazník s daným IČO.
     *
     * @param ico IČO
     * @return {@code true}, pokud zákazník s tímto IČO existuje
     */
    boolean existsByIco(@Param("ico") String ico);

    /**
     * Zjistí, zda existuje zákazník s daným ID.
     *
     * @param id ID zákazníka
     * @return {@code true}, pokud zákazník existuje
     */
    boolean existsById(@Param("id") Long id);
}
