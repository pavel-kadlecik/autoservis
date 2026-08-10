package cz.palo.autoservis.service;

import cz.palo.autoservis.model.dto.employee.EmployeeDto;

import java.util.List;

/**
 * Service rozhraní správy zaměstnanců (modul {@code employee}).
 *
 * <p>Poskytuje CRUD, soft delete/reaktivaci a malý nestránkovaný seznam pro
 * obrazovku správy ({@code /employees}) i select u položky LABOR. Změny sazby
 * ovlivní jen aktuální sazbu — historické položky zakázek si drží snapshotovanou
 * {@code purchase_price} (D-3), takže cesta „editovat minulost" neexistuje.
 */
public interface EmployeeService {

    /**
     * Vrací zaměstnance seřazené podle jména. Malá množina — bez stránkování.
     *
     * @param activeOnly při {@code true} vrací jen aktivní zaměstnance
     * @return seznam zaměstnanců
     */
    List<EmployeeDto.ListResponse> getAll(boolean activeOnly);

    /**
     * Vrací plný detail aktivního zaměstnance podle ID.
     *
     * @param id ID zaměstnance
     * @return detail zaměstnance
     */
    EmployeeDto.DetailResponse getById(Long id);

    /**
     * Založí nového zaměstnance.
     *
     * @param request zvalidovaný create request
     * @param userId  právě přihlášený uživatel (auditní pole {@code created_by})
     * @return detail založeného zaměstnance
     */
    EmployeeDto.DetailResponse create(EmployeeDto.CreateRequest request, Long userId);

    /**
     * Upraví existujícího zaměstnance.
     *
     * @param id      ID zaměstnance
     * @param request zvalidovaný update request
     * @param userId  právě přihlášený uživatel
     * @return detail upraveného zaměstnance
     */
    EmployeeDto.DetailResponse update(Long id, EmployeeDto.UpdateRequest request, Long userId);

    /**
     * Deaktivuje zaměstnance (soft delete). Záznam se uchovává navždy kvůli
     * historii — položky zakázek na něj odkazují přes FK (D-4).
     *
     * @param id ID zaměstnance
     * @return detail upraveného zaměstnance
     */
    EmployeeDto.DetailResponse deactivate(Long id);

    /**
     * Znovu aktivuje dříve deaktivovaného zaměstnance.
     *
     * @param id ID zaměstnance
     * @return detail upraveného zaměstnance
     */
    EmployeeDto.DetailResponse activate(Long id);
}
