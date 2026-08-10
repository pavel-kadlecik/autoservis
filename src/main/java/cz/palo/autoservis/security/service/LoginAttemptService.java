package cz.palo.autoservis.security.service;

import cz.palo.autoservis.config.security.LockoutProperties;
import cz.palo.autoservis.security.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Zaznamenává výsledky přihlášení pro mechanismus zamykání účtu (V3b, analyza-2026-07).
 *
 * <p>Všechny metody běží v {@link Propagation#REQUIRES_NEW} — záměrně.
 * {@link AuthenticationService#login} je sám {@code @Transactional} a rollbackuje,
 * když {@code authenticationManager.authenticate(...)} vyhodí {@code BadCredentialsException}.
 * Bez samostatné transakce by se inkrement neúspěšných pokusů odrolloval spolu
 * s výjimkou a zamykání by tiše přestalo fungovat. Běh v nové, nezávisle commitované
 * transakci zaručuje, že čítač přežije bez ohledu na osud transakce volajícího.
 *
 * <p><strong>Vypršení zámku (V64, audit KN-5):</strong> zámek je časově omezený. Do V64 byl
 * trvalý a uměl ho zrušit jen ADMINem spuštěný reset hesla, takže deset požadavků na veřejný
 * login endpoint mohlo natrvalo vyřadit jediný produkční administrátorský účet.
 * {@link #releaseExpiredLock} se volá před autentizací a uvolní zámek, jehož nakonfigurovaná
 * životnost uplynula. Musí být také {@code REQUIRES_NEW} ze stejného důvodu jako čítač:
 * následné špatné heslo odrolluje transakci volajícího a uvolnění musí přežít — jinak by
 * zastaralý čítač účet okamžitě zamkl znovu.
 */
@Service
@RequiredArgsConstructor
public class LoginAttemptService {

    /** Počet neúspěšných pokusů za sebou, po kterém se účet zamkne. */
    private static final int MAX_FAILED_ATTEMPTS = 10;

    private final UserMapper userMapper;
    private final LockoutProperties lockoutProperties;

    /**
     * Zvýší čítač neúspěšných přihlášení daného jména a při dosažení
     * {@link #MAX_FAILED_ATTEMPTS} účet zamkne.
     *
     * <p>Pro neznámé nebo deaktivované jméno tiše nedělá nic — {@code login()}
     * stejně vrací tutéž {@code BadCredentialsException} v obou případech,
     * takže vynechání inkrementu tady nehrozí enumerací uživatelů.
     *
     * @param username přihlašovací jméno z neúspěšného pokusu
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(String username) {
        userMapper.findByUsername(username).ifPresent(u -> {
            userMapper.incrementFailedAttempts(u.getId());
            if (u.getFailedLoginAttempts() + 1 >= MAX_FAILED_ATTEMPTS) {
                userMapper.lockAccount(u.getId());
            }
        });
    }

    /**
     * Zaznamená úspěšné přihlášení — vynuluje čítač neúspěšných pokusů
     * a orazítkuje {@code last_login_at} (viz {@link UserMapper#updateLastLogin}).
     *
     * @param userId ID právě úspěšně přihlášeného uživatele
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordSuccess(Long userId) {
        userMapper.updateLastLogin(userId);
    }

    /**
     * Uvolní zámek účtu, pokud jeho nakonfigurovaná životnost už uplynula (V64, audit KN-5).
     *
     * <p>Musí se volat <strong>před</strong> {@code authenticationManager.authenticate(...)}:
     * Spring Security kontroluje stav účtu dřív než heslo a vyhodil by
     * {@code LockedException}, aniž by se této služby kdy zeptal.
     *
     * <p>Zda zámek vypršel, rozhoduje databáze (hlídaný {@code UPDATE}, jedny hodiny),
     * ne tato metoda — ta jen dodává nakonfigurovanou životnost a výsledek ji nezajímá.
     * No-op znamená, že zámek stále platí a autentizace selže s {@code LockedException},
     * což je správný výsledek.
     *
     * @param username přihlašovací jméno z pokusu; neznámá jména jsou neškodný no-op
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void releaseExpiredLock(String username) {
        if (username == null || username.isBlank()) {
            return;
        }
        userMapper.unlockIfLockExpired(username, lockoutProperties.getDuration().toSeconds());
    }
}
