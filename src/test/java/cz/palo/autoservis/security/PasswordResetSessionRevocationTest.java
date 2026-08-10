package cz.palo.autoservis.security;

import cz.palo.autoservis.AbstractIntegrationTest;
import cz.palo.autoservis.exception.InvalidRefreshTokenException;
import cz.palo.autoservis.model.dto.user.UserDto;
import cz.palo.autoservis.security.mapper.UserMapper;
import cz.palo.autoservis.security.model.dto.ChangePasswordRequest;
import cz.palo.autoservis.security.model.dto.LoginRequest;
import cz.palo.autoservis.security.model.dto.RefreshRequest;
import cz.palo.autoservis.security.model.dto.TokenResponse;
import cz.palo.autoservis.security.service.AuthenticationService;
import cz.palo.autoservis.service.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Odvolání živých sessions při změně hesla — obě cesty (audit 2026-07-30, nález KN-6).
 *
 * <p>Změna hesla je standardní reakce na kompromitovaný účet: ukradený notebook, odchod
 * zaměstnance, phishing. Pokud se přitom neodvolají refresh tokeny, držitel ukradeného tokenu
 * si obnovuje přístup dál — u sedmidenní expirace a rotace donekonečna. Heslo se změní a
 * <em>nic se nestane</em>.
 *
 * <p><strong>Proč tato třída vznikla:</strong> {@code revokeAllByUserId} neměla do 2026-07-30
 * <em>žádný</em> test. Samoobslužná změna hesla ji volala (oprava K-6 z auditu 2026-07-24),
 * ale odstranění toho řádku by celou suitou prošlo — a admin reset hesla ji nevolal vůbec,
 * přestože {@code api.md} tvrdil opak. Testy níže proto pokrývají obě cesty: novou (admin reset)
 * i tu starší, aby už nemohla tiše regredovat.
 *
 * <p><strong>Záměrně BEZ {@code @Transactional}</strong> — stejně jako {@code ChangePasswordTest}
 * a {@code LoginLockoutTest}. Testované metody sahají na řádek {@code security.users} a
 * {@code LoginAttemptService} běží v {@code REQUIRES_NEW}; testovací transakce držící ten řádek
 * zamčený by přihlášení zablokovala až do timeoutu. Stav se proto vrací ručně
 * v {@link #restoreManagerAccount()}.
 *
 * <p>Seed (V3): manager id=2, heslo {@code Password1!}.
 */
class PasswordResetSessionRevocationTest extends AbstractIntegrationTest {

    private static final long MANAGER_ID = 2L;
    private static final String USERNAME = "manager";
    private static final String PASSWORD = "Password1!";
    private static final String NEW_PASSWORD = "NoveHeslo1!";

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

    @AfterEach
    void restoreManagerAccount() {
        userMapper.updatePasswordHash(MANAGER_ID, passwordEncoder.encode(PASSWORD));
        userMapper.unlockAccount(MANAGER_ID);
    }

    @Test
    @DisplayName("admin reset hesla odvolá všechny refresh tokeny uživatele (KN-6)")
    void adminPasswordReset_revokesAllRefreshTokens() {
        TokenResponse beforeReset = login();
        assertThat(activeRefreshTokenCount()).isPositive();

        UserDto.ResetPasswordRequest resetRequest = new UserDto.ResetPasswordRequest();
        resetRequest.setNewPassword(NEW_PASSWORD);
        userService.resetPassword(MANAGER_ID, resetRequest);

        assertThat(activeRefreshTokenCount())
                .as("po admin resetu nesmí uživateli zůstat živý refresh token")
                .isZero();

        assertThatThrownBy(() -> authenticationService.refresh(new RefreshRequest(beforeReset.refreshToken())))
                .as("ukradený token už nesmí obnovit přístup")
                .isInstanceOf(InvalidRefreshTokenException.class);
    }

    @Test
    @DisplayName("samoobslužná změna hesla odvolá všechny refresh tokeny (regresní pojistka K-6)")
    void selfServicePasswordChange_revokesAllRefreshTokens() {
        TokenResponse beforeChange = login();
        assertThat(activeRefreshTokenCount()).isPositive();

        authenticationService.changePassword(MANAGER_ID, new ChangePasswordRequest(PASSWORD, NEW_PASSWORD));

        assertThat(activeRefreshTokenCount())
                .as("po změně hesla nesmí uživateli zůstat živý refresh token")
                .isZero();

        assertThatThrownBy(() -> authenticationService.refresh(new RefreshRequest(beforeChange.refreshToken())))
                .isInstanceOf(InvalidRefreshTokenException.class);
    }

    @Test
    @DisplayName("reset hesla neodvolá sessions jiných uživatelů")
    void passwordReset_doesNotTouchOtherUsersSessions() {
        TokenResponse mechanicTokens = authenticationService.login(new LoginRequest("mechanic", PASSWORD));
        login();   // manager

        UserDto.ResetPasswordRequest resetRequest = new UserDto.ResetPasswordRequest();
        resetRequest.setNewPassword(NEW_PASSWORD);
        userService.resetPassword(MANAGER_ID, resetRequest);

        assertThat(activeRefreshTokenCount()).isZero();
        // mechanikův token musí dál fungovat — odvolání je cílené na jednoho uživatele
        assertThat(authenticationService.refresh(new RefreshRequest(mechanicTokens.refreshToken())).accessToken())
                .isNotBlank();
    }

    private TokenResponse login() {
        return authenticationService.login(new LoginRequest(USERNAME, PASSWORD));
    }

    /** Počet neodvolaných refresh tokenů manažera — čteno přímo z DB, ne přes službu. */
    private int activeRefreshTokenCount() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM security.refresh_tokens WHERE user_id = ? AND revoked = FALSE",
                Integer.class, MANAGER_ID);
        return count == null ? 0 : count;
    }
}
