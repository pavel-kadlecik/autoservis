package cz.palo.autoservis.security.mapper;

import cz.palo.autoservis.model.domain.user.User;
import cz.palo.autoservis.model.dto.user.UserSearchParams;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Optional;

/**
 * MyBatis mapper tabulky {@code security.users}.
 *
 * <p>Složitější dotazy (findByUsername, findById) používají XML mapování
 * v {@code UserMapper.xml}, aby načetly uživatele i s rolemi jedním JOIN
 * dotazem. Jednoduché operace nad jednou tabulkou používají inline anotace.
 */
@Mapper
public interface UserMapper {

    /**
     * Načte uživatele včetně rolí — používá Spring Security při každém přihlášení.
     * Provádí se jako jediný JOIN přes {@code users}, {@code user_roles} a {@code roles}.
     *
     * @param username hledané přihlašovací jméno
     * @return uživatel s rolemi, nebo prázdný Optional, když neexistuje nebo je deaktivovaný
     */
    Optional<User> findByUsername(@Param("username") String username);

    /**
     * Načte uživatele podle primárního klíče, včetně rolí.
     *
     * @param id ID uživatele
     * @return uživatel s rolemi, nebo prázdný Optional, když neexistuje
     */
    Optional<User> findById(@Param("id") Long id);

    /**
     * Zvýší čítač neúspěšných přihlášení o jedna.
     *
     * @param id ID uživatele
     * @return počet ovlivněných řádků
     */
    int incrementFailedAttempts(@Param("id") Long id);

    /**
     * Vynuluje čítač neúspěšných přihlášení.
     *
     * @param id ID uživatele
     * @return počet ovlivněných řádků
     */
    int resetFailedAttempts(@Param("id") Long id);

    /**
     * Zaznamená aktuální čas jako poslední úspěšné přihlášení
     * a vynuluje čítač neúspěšných pokusů.
     *
     * @param id ID uživatele
     * @return počet ovlivněných řádků
     */
    int updateLastLogin(@Param("id") Long id);

    /**
     * Zamkne účet nastavením {@code account_non_locked = FALSE} a razítkem
     * {@code locked_at = NOW()}, od kterého se měří vypršení zámku (V64, audit KN-5).
     *
     * @param id ID uživatele
     * @return počet ovlivněných řádků
     */
    int lockAccount(@Param("id") Long id);

    /**
     * Odemkne účet, vynuluje čítač neúspěšných přihlášení a smaže razítko zámku
     * ({@code account_non_locked = TRUE, failed_login_attempts = 0, locked_at = NULL}).
     * Volá se po adminem spuštěném resetu hesla (V3b, analyza-2026-07).
     *
     * @param id ID uživatele
     * @return počet ovlivněných řádků
     */
    int unlockAccount(@Param("id") Long id);

    /**
     * Uvolní zámek, jehož nakonfigurovaná životnost už uplynula (V64, audit KN-5).
     *
     * <p>Hlídaný zápis — všechny podmínky žijí ve {@code WHERE} klauzuli, takže souběžné
     * pokusy o přihlášení nemohou tentýž zámek uvolnit dvakrát ani předčasně. Uplynulý čas
     * se vyhodnocuje výhradně v databázi ({@code locked_at} i {@code NOW()} jdou z jedněch
     * hodin), nikdy proti hodinám aplikačního serveru.
     *
     * @param username       přihlašovací jméno, jehož zámek se má přehodnotit
     * @param lockoutSeconds jak dlouho zámek platí, v sekundách
     * @return 1, pokud byl zámek uvolněn; 0, pokud nebylo co uvolnit — účet není
     *         zamčený, nebo zámek stále platí
     */
    int unlockIfLockExpired(@Param("username") String username,
                            @Param("lockoutSeconds") long lockoutSeconds);

    /**
     * Zjistí, zda uživatel s daným jménem už existuje.
     *
     * @param username kontrolované uživatelské jméno
     * @return {@code true}, pokud je jméno obsazené
     */
    @Select("SELECT COUNT(*) > 0 FROM security.users WHERE username = #{username}")
    boolean existsByUsername(String username);

    /**
     * Zjistí, zda uživatel s daným e-mailem už existuje.
     *
     * @param email kontrolovaný e-mail
     * @return {@code true}, pokud je e-mail obsazený
     */
    boolean existsByEmail(@Param("email") String email);

    /**
     * Hledá uživatele s dynamickými filtry a stránkováním, včetně rolí.
     *
     * @param params parametry hledání — filtry, stránka a velikost stránky
     * @return seznam odpovídajících uživatelů
     */
    List<User> search(@Param("params") UserSearchParams params);

    /**
     * Vrací celkový počet výsledků odpovídajících parametrům hledání — pro stránkování.
     *
     * @param params parametry hledání (stejné filtry jako {@link #search}, bez LIMIT/OFFSET)
     * @return celkový počet odpovídajících uživatelů
     */
    long countSearch(@Param("params") UserSearchParams params);

    /**
     * Založí nový uživatelský účet (admin CRUD; veřejná registrace byla zrušena, audit K1).
     * Vygenerovaný primární klíč se zapíše zpět do {@code user.id}.
     *
     * @param user uživatel k vložení (id musí být null); email a enabled musí být nastavené
     */
    void insert(User user);

    /**
     * Aktualizuje e-mail existujícího uživatele. Uživatelské jméno je po založení neměnné.
     *
     * @param user doménový objekt nesoucí nový e-mail a existující id
     * @return počet ovlivněných řádků (0 = nenalezen)
     */
    int updateEmail(User user);

    /**
     * Aktualizuje hash hesla uživatele a orazítkuje {@code password_changed_at}.
     * Používá se pro adminem spuštěné resety i self-service změny hesla.
     *
     * @param id           ID uživatele
     * @param passwordHash nový BCrypt hash hesla
     * @return počet ovlivněných řádků (0 = nenalezen)
     */
    int updatePasswordHash(@Param("id") Long id, @Param("passwordHash") String passwordHash);

    /**
     * Deaktivuje uživatelský účet nastavením {@code enabled = FALSE}.
     * Záznam v databázi zůstává — obdoba soft delete pro tuto tabulku.
     *
     * @param id ID uživatele
     * @return počet ovlivněných řádků (0 = nenalezen)
     */
    int deactivate(@Param("id") Long id);

    /**
     * Znovu aktivuje dříve deaktivovaný účet nastavením {@code enabled = TRUE}.
     *
     * @param id ID uživatele
     * @return počet ovlivněných řádků (0 = nenalezen)
     */
    int activate(@Param("id") Long id);

    /**
     * Odstraní všechna přiřazení rolí uživatele — první krok výměny sady rolí.
     *
     * @param userId ID uživatele
     */
    void deleteRoles(@Param("userId") Long userId);

    /**
     * Přiřadí uživateli sadu rolí. Nejdřív zavolej {@link #deleteRoles}, aby se
     * existující sada nahradila a duplicity se nehromadily.
     *
     * @param userId     ID uživatele
     * @param roleIds    ID rolí k přiřazení
     * @param assignedBy ID admina provádějícího přiřazení (auditní stopa)
     */
    void insertRoles(@Param("userId") Long userId, @Param("roleIds") List<Integer> roleIds,
                      @Param("assignedBy") Long assignedBy);

    /**
     * Spočítá aktivní uživatele s danou rolí, kromě jednoho — pojistka proti
     * deaktivaci posledního zbývajícího administrátora.
     *
     * @param roleName      název role, např. {@code ROLE_ADMIN}
     * @param excludeUserId ID uživatele vyjmutého z počítání (ten deaktivovaný)
     * @return počet ostatních aktivních uživatelů s touto rolí
     */
    long countEnabledByRoleExcluding(@Param("roleName") String roleName, @Param("excludeUserId") Long excludeUserId);
}
