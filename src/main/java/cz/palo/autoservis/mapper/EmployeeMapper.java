package cz.palo.autoservis.mapper;

import cz.palo.autoservis.model.domain.employee.Employee;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Optional;

/**
 * MyBatis mapper rozhraní tabulky {@code employee.employees}.
 *
 * <p>Konvence:
 * <ul>
 *   <li>Veškeré SQL je výhradně v {@code mapper/EmployeeMapper.xml} (R-01).</li>
 *   <li>{@link #findById(Long)} je <strong>přísný</strong> — jen aktivní řádky (R-10);
 *       {@link #findByIdIncludingInactive(Long)} slouží administraci / reaktivaci
 *       a čtení po mutaci.</li>
 * </ul>
 */
@Mapper
public interface EmployeeMapper {

    // =========================================================================
    // CREATE
    // =========================================================================

    /**
     * Vloží nového zaměstnance. Vygenerovaný PK se zapíše zpět do
     * {@code employee.id} přes {@code useGeneratedKeys}.
     *
     * @param employee nový zaměstnanec (id musí být null)
     */
    void insert(Employee employee);

    // =========================================================================
    // READ
    // =========================================================================

    /**
     * Najde aktivního zaměstnance podle ID (přísné, R-10).
     *
     * @param id ID zaměstnance
     * @return zaměstnanec, nebo prázdný Optional, když neexistuje nebo je neaktivní
     */
    Optional<Employee> findById(@Param("id") Long id);

    /**
     * Najde zaměstnance podle ID bez ohledu na {@code is_active}.
     * Slouží administraci, reaktivaci a čtení po mutaci.
     *
     * @param id ID zaměstnance
     * @return zaměstnanec, nebo prázdný Optional, když neexistuje
     */
    Optional<Employee> findByIdIncludingInactive(@Param("id") Long id);

    /**
     * Vrací zaměstnance seřazené podle příjmení a jména — seznam ve správě
     * a select u položky LABOR. Malá množina, bez stránkování.
     *
     * @param activeOnly při {@code true} vrací jen aktivní zaměstnance
     * @return seznam zaměstnanců
     */
    List<Employee> findAll(@Param("activeOnly") boolean activeOnly);

    // =========================================================================
    // UPDATE
    // =========================================================================

    /**
     * Aktualizuje editovatelná pole zaměstnance. {@code created_at} a {@code created_by}
     * jsou neměnné; {@code updated_at} řeší DB trigger.
     *
     * @param employee zaměstnanec s novými hodnotami
     * @return počet ovlivněných řádků (0 = nenalezen)
     */
    int update(Employee employee);

    /**
     * Deaktivuje zaměstnance (soft delete, {@code is_active = FALSE}).
     *
     * @param id ID zaměstnance
     * @return počet ovlivněných řádků (0 = nenalezen)
     */
    int deactivate(@Param("id") Long id);

    /**
     * Znovu aktivuje dříve deaktivovaného zaměstnance ({@code is_active = TRUE}).
     *
     * @param id ID zaměstnance
     * @return počet ovlivněných řádků (0 = nenalezen)
     */
    int activate(@Param("id") Long id);

    // =========================================================================
    // EXISTS
    // =========================================================================

    /**
     * Zjistí, zda je na daný přihlašovací účet už navázaný jiný zaměstnanec.
     * Vynucuje unikát {@code uq_employees_user} s vlídnou hláškou (R-13).
     *
     * @param userId    ID přihlašovacího účtu
     * @param excludeId ID zaměstnance k vynechání (sebe sama při úpravě); {@code null} při založení
     * @return {@code true}, pokud tento {@code userId} už používá jiný zaměstnanec
     */
    boolean existsByUserId(@Param("userId") Long userId, @Param("excludeId") Long excludeId);
}
