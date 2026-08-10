package cz.palo.autoservis.service;

import cz.palo.autoservis.model.dto.pagination.PagedResponse;
import cz.palo.autoservis.model.dto.user.UserDto;
import cz.palo.autoservis.model.dto.user.UserSearchParams;

/**
 * Service rozhraní adminské správy uživatelských účtů ({@code security.users}).
 *
 * <p>Odděleno od self-service autentizačních věcí (přihlášení, změna vlastního
 * hesla), které žijí v {@code AuthenticationService}.
 */
public interface UserService {

    /**
     * Vrací plný detail uživatele podle ID, včetně rolí.
     *
     * @param id ID uživatele
     * @return detailová response uživatele
     */
    UserDto.DetailResponse getById(Long id);

    /**
     * Vrací stránkovaný seznam uživatelů odpovídajících parametrům hledání.
     *
     * @param params parametry hledání (filtry, stránka, velikost stránky)
     * @return stránkovaná response
     */
    PagedResponse<UserDto.ListResponse> getPage(UserSearchParams params);

    /**
     * Založí nový uživatelský účet s danými rolemi.
     *
     * @param createRequest zvalidovaný request s daty účtu a ID rolí
     * @param createdBy     ID administrátora provádějícího akci (auditní pole {@code assigned_by})
     * @return detailová response založeného uživatele
     */
    UserDto.DetailResponse create(UserDto.CreateRequest createRequest, Long createdBy);

    /**
     * Upraví e-mail a přiřazení rolí existujícího uživatele.
     *
     * @param id            ID uživatele
     * @param updateRequest zvalidovaný request s novým e-mailem a ID rolí
     * @param updatedBy     ID administrátora provádějícího akci
     * @return detailová response upraveného uživatele
     */
    UserDto.DetailResponse update(Long id, UserDto.UpdateRequest updateRequest, Long updatedBy);

    /**
     * Deaktivuje uživatelský účet (soft delete — nastaví {@code enabled = FALSE}).
     * Odmítne deaktivovat vlastní účet volajícího i posledního aktivního administrátora.
     *
     * @param id            ID uživatele k deaktivaci
     * @param currentUserId ID administrátora provádějícího akci
     * @return detailová response upraveného uživatele
     */
    UserDto.DetailResponse deactivate(Long id, Long currentUserId);

    /**
     * Znovu aktivuje dříve deaktivovaný účet (nastaví {@code enabled = TRUE}).
     *
     * @param id ID uživatele
     * @return detailová response upraveného uživatele
     */
    UserDto.DetailResponse activate(Long id);

    /**
     * Nastaví uživateli nové heslo bez znalosti aktuálního (adminský reset).
     *
     * <p>Zároveň odemkne účet a <strong>odvolá všechny refresh tokeny uživatele</strong>
     * (audit KN-6) — adminský reset je standardní reakce na kompromitovaný účet
     * a ponechat běžící session by popřelo jeho smysl.
     *
     * @param id      ID uživatele
     * @param request nové heslo
     * @return detailová response upraveného uživatele
     */
    UserDto.DetailResponse resetPassword(Long id, UserDto.ResetPasswordRequest request);
}
