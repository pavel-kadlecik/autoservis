package cz.palo.autoservis.service;

import cz.palo.autoservis.AbstractIntegrationTest;
import cz.palo.autoservis.model.domain.user.User;
import cz.palo.autoservis.model.dto.user.UserDto;
import cz.palo.autoservis.security.mapper.UserMapper;
import cz.palo.autoservis.security.model.dto.LoginRequest;
import cz.palo.autoservis.security.service.AuthenticationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pokrývá uzamčení účtu po opakovaných neúspěšných přihlášeních (V3b, analyza-2026-07):
 * {@code security.service.LoginAttemptService} zvyšuje {@code failed_login_attempts}
 * v {@code REQUIRES_NEW} transakci (takže přežije rollback selhávající transakce
 * {@code AuthenticationService.login}), při 10 pokusech účet zamkne
 * a admin reset hesla ho zase odemkne.
 *
 * <p><strong>Záměrně BEZ {@code @Transactional}</strong> — na rozdíl od většiny service
 * testů v tomto balíčku. {@link cz.palo.autoservis.security.service.LoginAttemptService}
 * commituje ve vlastní {@code REQUIRES_NEW} transakci a celý smysl těchto testů je
 * pozorovat právě ten commitnutý stav čerstvým čtením {@code UserMapper.findByUsername}.
 * Obalení testu transakcí by zápisy z REQUIRES_NEW stejně neodrolovalo
 * a jen by zamlžilo, co se doopravdy testuje. Testovací data (seed uživatel
 * {@code mechanic}) se obnovují ručně v {@link #restoreMechanicAccount()}.
 */
class LoginLockoutTest extends AbstractIntegrationTest {

    private static final Long MECHANIC_ID = 3L;
    private static final String MECHANIC_USERNAME = "mechanic";
    private static final String MECHANIC_PASSWORD = "Password1!";
    private static final String WRONG_PASSWORD = "totalne-spatne-heslo";

    @Autowired
    private AuthenticationService authenticationService;

    @Autowired
    private UserService userService;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** Vrátí seed účet do původního stavu bez ohledu na to, co s ním test provedl. */
    @AfterEach
    void restoreMechanicAccount() {
        userMapper.unlockAccount(MECHANIC_ID);
        userMapper.updatePasswordHash(MECHANIC_ID, passwordEncoder.encode(MECHANIC_PASSWORD));
    }

    @Test
    @DisplayName("3x špatné heslo zvýší failed_login_attempts na 3 v DB (přežije rollback login transakce)")
    void threeFailedAttempts_incrementCounterInDatabase() {
        for (int i = 0; i < 3; i++) {
            failWithWrongPassword();
        }

        User mechanic = userMapper.findByUsername(MECHANIC_USERNAME).orElseThrow();
        assertThat(mechanic.getFailedLoginAttempts()).isEqualTo(3);
        assertThat(mechanic.isAccountNonLocked()).isTrue();
    }

    @Test
    @DisplayName("úspěšný login vynuluje počítadlo neúspěšných pokusů")
    void successfulLogin_resetsFailedAttemptCounter() {
        failWithWrongPassword();
        failWithWrongPassword();

        authenticationService.login(new LoginRequest(MECHANIC_USERNAME, MECHANIC_PASSWORD));

        User mechanic = userMapper.findByUsername(MECHANIC_USERNAME).orElseThrow();
        assertThat(mechanic.getFailedLoginAttempts()).isZero();
    }

    @Test
    @DisplayName("10x špatné heslo uzamkne účet — i se SPRÁVNÝM heslem pak login vyhodí LockedException")
    void tenFailedAttempts_locksAccount_correctPasswordThenThrowsLocked() {
        for (int i = 0; i < 10; i++) {
            failWithWrongPassword();
        }

        User mechanic = userMapper.findByUsername(MECHANIC_USERNAME).orElseThrow();
        assertThat(mechanic.isAccountNonLocked()).isFalse();

        assertThatThrownBy(() -> authenticationService.login(new LoginRequest(MECHANIC_USERNAME, MECHANIC_PASSWORD)))
                .isInstanceOf(LockedException.class);
    }

    @Test
    @DisplayName("admin reset hesla odemkne účet a login s novým heslem projde")
    void adminPasswordReset_unlocksAccount_newPasswordWorks() {
        for (int i = 0; i < 10; i++) {
            failWithWrongPassword();
        }
        assertThat(userMapper.findByUsername(MECHANIC_USERNAME).orElseThrow().isAccountNonLocked()).isFalse();

        UserDto.ResetPasswordRequest resetRequest = new UserDto.ResetPasswordRequest();
        resetRequest.setNewPassword("NoveHeslo1!");
        userService.resetPassword(MECHANIC_ID, resetRequest);

        User unlocked = userMapper.findByUsername(MECHANIC_USERNAME).orElseThrow();
        assertThat(unlocked.isAccountNonLocked()).isTrue();
        assertThat(unlocked.getFailedLoginAttempts()).isZero();

        // login se starým heslem teď musí selhat (BadCredentialsException, ne zamčeno)…
        assertThatThrownBy(() -> authenticationService.login(new LoginRequest(MECHANIC_USERNAME, MECHANIC_PASSWORD)))
                .isInstanceOf(BadCredentialsException.class);
        // …ale nové heslo funguje.
        authenticationService.login(new LoginRequest(MECHANIC_USERNAME, "NoveHeslo1!"));
    }

    // =========================================================================
    // Expirace zámku (V64, audit KN-5)
    // =========================================================================

    @Test
    @DisplayName("uzamčení účtu orazítkuje locked_at — bez toho by zámku nešlo spočítat expiraci")
    void lockingAccount_stampsLockedAt() {
        for (int i = 0; i < 10; i++) {
            failWithWrongPassword();
        }

        User mechanic = userMapper.findByUsername(MECHANIC_USERNAME).orElseThrow();
        assertThat(mechanic.isAccountNonLocked()).isFalse();
        assertThat(mechanic.getLockedAt()).isNotNull();
    }

    @Test
    @DisplayName("po uplynutí lhůty se zámek uvolní a login se SPRÁVNÝM heslem projde (KN-5)")
    void lockOlderThanConfiguredWindow_isReleased_andLoginSucceeds() {
        for (int i = 0; i < 10; i++) {
            failWithWrongPassword();
        }
        assertThat(userMapper.findByUsername(MECHANIC_USERNAME).orElseThrow().isAccountNonLocked()).isFalse();

        backdateLock("16 minutes");   // konfigurovaná lhůta je 15 min

        authenticationService.login(new LoginRequest(MECHANIC_USERNAME, MECHANIC_PASSWORD));

        User afterLogin = userMapper.findByUsername(MECHANIC_USERNAME).orElseThrow();
        assertThat(afterLogin.isAccountNonLocked()).isTrue();
        assertThat(afterLogin.getFailedLoginAttempts()).isZero();
        assertThat(afterLogin.getLockedAt()).isNull();
    }

    @Test
    @DisplayName("zámek uvnitř lhůty drží — login se správným heslem dál vyhodí LockedException")
    void lockWithinConfiguredWindow_staysLocked() {
        for (int i = 0; i < 10; i++) {
            failWithWrongPassword();
        }

        backdateLock("5 minutes");    // méně než konfigurovaných 15 min

        assertThatThrownBy(() -> authenticationService.login(new LoginRequest(MECHANIC_USERNAME, MECHANIC_PASSWORD)))
                .isInstanceOf(LockedException.class);

        assertThat(userMapper.findByUsername(MECHANIC_USERNAME).orElseThrow().isAccountNonLocked()).isFalse();
    }

    @Test
    @DisplayName("unlockIfLockExpired je guardovaný zápis — uvnitř lhůty 0 řádků, po lhůtě 1")
    void unlockIfLockExpired_isGuardedByElapsedTime() {
        for (int i = 0; i < 10; i++) {
            failWithWrongPassword();
        }

        // 900 s = 15 min; zámek je čerstvý, takže nemá co uvolnit
        assertThat(userMapper.unlockIfLockExpired(MECHANIC_USERNAME, 900L)).isZero();
        assertThat(userMapper.findByUsername(MECHANIC_USERNAME).orElseThrow().isAccountNonLocked()).isFalse();

        backdateLock("16 minutes");

        assertThat(userMapper.unlockIfLockExpired(MECHANIC_USERNAME, 900L)).isEqualTo(1);
        // druhé volání už nemá co uvolnit — zápis není idempotentně opakovatelný na odemčeném účtu
        assertThat(userMapper.unlockIfLockExpired(MECHANIC_USERNAME, 900L)).isZero();
    }

    @Test
    @DisplayName("admin reset hesla vyčistí i locked_at, ne jen příznak zámku")
    void adminPasswordReset_clearsLockStamp() {
        for (int i = 0; i < 10; i++) {
            failWithWrongPassword();
        }
        assertThat(userMapper.findByUsername(MECHANIC_USERNAME).orElseThrow().getLockedAt()).isNotNull();

        UserDto.ResetPasswordRequest resetRequest = new UserDto.ResetPasswordRequest();
        resetRequest.setNewPassword("NoveHeslo1!");
        userService.resetPassword(MECHANIC_ID, resetRequest);

        assertThat(userMapper.findByUsername(MECHANIC_USERNAME).orElseThrow().getLockedAt()).isNull();
    }

    private void failWithWrongPassword() {
        assertThatThrownBy(() -> authenticationService.login(new LoginRequest(MECHANIC_USERNAME, WRONG_PASSWORD)))
                .isInstanceOf(BadCredentialsException.class);
    }

    /**
     * Posune {@code locked_at} do minulosti, aby test nemusel čekat reálnou lhůtu.
     * Zápis jde přímo do DB — expiraci vyhodnocuje databáze porovnáním s {@code NOW()},
     * takže posunutím razítka se simuluje uplynulý čas bez sahání na konfiguraci.
     */
    private void backdateLock(String interval) {
        jdbcTemplate.update(
                "UPDATE security.users SET locked_at = NOW() - INTERVAL '" + interval + "' WHERE id = ?",
                MECHANIC_ID);
    }
}
