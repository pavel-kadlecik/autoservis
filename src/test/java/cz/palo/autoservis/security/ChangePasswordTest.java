package cz.palo.autoservis.security;

import cz.palo.autoservis.AbstractIntegrationTest;
import cz.palo.autoservis.exception.BusinessRuleException;
import cz.palo.autoservis.exception.ResourceNotFoundException;
import cz.palo.autoservis.security.mapper.UserMapper;
import cz.palo.autoservis.security.model.dto.ChangePasswordRequest;
import cz.palo.autoservis.security.model.dto.LoginRequest;
import cz.palo.autoservis.security.service.AuthenticationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Sebeobslužná změna hesla ({@code AuthenticationService.changePassword}).
 *
 * <p>Na rozdíl od admin resetu ({@code UserService.resetPassword}) vyžaduje prokázání
 * znalosti současného hesla. Test ověřuje obě větve a hlavně to, že se změna skutečně
 * propíše do přihlášení — tedy že nové heslo funguje a staré přestane platit.
 *
 * <p><strong>Záměrně BEZ {@code @Transactional}</strong> — stejně jako {@code LoginLockoutTest}.
 * {@code LoginAttemptService.recordSuccess} běží v {@code REQUIRES_NEW} transakci a sahá na
 * tentýž řádek {@code security.users}; kdyby ho testovací transakce držela zamčený, přihlášení
 * by čekalo na zámek až do timeoutu. Změněné heslo se proto vrací zpátky ručně
 * v {@link #restoreManagerPassword()}.
 *
 * <p>Seed (V3): manager id=2, heslo {@code Password1!}.
 */
class ChangePasswordTest extends AbstractIntegrationTest {

    private static final long MANAGER_ID = 2L;
    private static final String USERNAME = "manager";
    private static final String CURRENT_PASSWORD = "Password1!";
    private static final String NEW_PASSWORD = "NoveHeslo1!";

    @Autowired
    private AuthenticationService authenticationService;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    /**
     * Vrátí seedové heslo i počítadla, aby test neovlivnil ostatní třídy v běhu
     * (bez {@code @Transactional} se nic samo neodroluje).
     */
    @org.junit.jupiter.api.AfterEach
    void restoreManagerPassword() {
        userMapper.updatePasswordHash(MANAGER_ID, passwordEncoder.encode(CURRENT_PASSWORD));
        userMapper.unlockAccount(MANAGER_ID);
    }

    @Test
    @DisplayName("se správným současným heslem se heslo změní a uloží jako BCrypt hash")
    void changePassword_withCorrectCurrentPassword_updatesHash() {
        authenticationService.changePassword(MANAGER_ID,
                new ChangePasswordRequest(CURRENT_PASSWORD, NEW_PASSWORD));

        String storedHash = userMapper.findById(MANAGER_ID).orElseThrow().getPasswordHash();
        assertThat(storedHash).as("nikdy plaintext (N-09)").isNotEqualTo(NEW_PASSWORD);
        assertThat(storedHash).startsWith("$2");
        assertThat(passwordEncoder.matches(NEW_PASSWORD, storedHash)).isTrue();
        assertThat(passwordEncoder.matches(CURRENT_PASSWORD, storedHash))
                .as("staré heslo už neplatí").isFalse();
    }

    @Test
    @DisplayName("po změně hesla projde přihlášení novým heslem a staré selže")
    void changePassword_newPasswordWorksForLogin() {
        authenticationService.changePassword(MANAGER_ID,
                new ChangePasswordRequest(CURRENT_PASSWORD, NEW_PASSWORD));

        assertThat(authenticationService.login(new LoginRequest(USERNAME, NEW_PASSWORD)).accessToken())
                .isNotBlank();

        assertThatThrownBy(() -> authenticationService.login(new LoginRequest(USERNAME, CURRENT_PASSWORD)))
                .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    @DisplayName("se špatným současným heslem → INVALID_CURRENT_PASSWORD (422) a hash se nezmění")
    void changePassword_withWrongCurrentPassword_isRejected() {
        String hashBefore = userMapper.findById(MANAGER_ID).orElseThrow().getPasswordHash();

        assertThatThrownBy(() -> authenticationService.changePassword(MANAGER_ID,
                new ChangePasswordRequest("UplneSpatneHeslo1!", NEW_PASSWORD)))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(ex -> {
                    BusinessRuleException e = (BusinessRuleException) ex;
                    assertThat(e.getRuleCode()).isEqualTo("INVALID_CURRENT_PASSWORD");
                    assertThat(e.getField()).isEqualTo("currentPassword");
                });

        assertThat(userMapper.findById(MANAGER_ID).orElseThrow().getPasswordHash())
                .as("neúspěšný pokus nesmí hash změnit").isEqualTo(hashBefore);
    }

    @Test
    @DisplayName("po odmítnutém pokusu funguje původní heslo dál")
    void changePassword_afterRejectedAttempt_oldPasswordStillWorks() {
        assertThatThrownBy(() -> authenticationService.changePassword(MANAGER_ID,
                new ChangePasswordRequest("UplneSpatneHeslo1!", NEW_PASSWORD)))
                .isInstanceOf(BusinessRuleException.class);

        assertThat(authenticationService.login(new LoginRequest(USERNAME, CURRENT_PASSWORD)).accessToken())
                .isNotBlank();
    }

    @Test
    @DisplayName("změna hesla neexistujícího uživatele → ResourceNotFoundException (404)")
    void changePassword_unknownUser_throwsResourceNotFound() {
        assertThatThrownBy(() -> authenticationService.changePassword(999_999L,
                new ChangePasswordRequest(CURRENT_PASSWORD, NEW_PASSWORD)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("nastavení stejného hesla znovu projde, ale hash je jiný (BCrypt sůl)")
    void changePassword_toSameValue_producesDifferentHash() {
        String hashBefore = userMapper.findById(MANAGER_ID).orElseThrow().getPasswordHash();

        authenticationService.changePassword(MANAGER_ID,
                new ChangePasswordRequest(CURRENT_PASSWORD, CURRENT_PASSWORD));

        String hashAfter = userMapper.findById(MANAGER_ID).orElseThrow().getPasswordHash();
        assertThat(hashAfter).isNotEqualTo(hashBefore);
        assertThat(passwordEncoder.matches(CURRENT_PASSWORD, hashAfter)).isTrue();
    }
}
