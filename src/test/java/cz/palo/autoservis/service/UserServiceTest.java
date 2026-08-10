package cz.palo.autoservis.service;

import cz.palo.autoservis.AbstractIntegrationTest;
import cz.palo.autoservis.exception.BusinessRuleException;
import cz.palo.autoservis.exception.ResourceNotFoundException;
import cz.palo.autoservis.exception.UserAlreadyExistsException;
import cz.palo.autoservis.model.dto.user.UserDto;
import cz.palo.autoservis.security.mapper.UserMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Admin CRUD nad uživatelskými účty ({@code UserServiceImpl}).
 *
 * <p>Těžiště jsou <strong>guardy proti zamčení aplikace</strong>: nelze deaktivovat vlastní
 * účet ani posledního admina. U obou se testuje <em>obě</em> větve — že guard zabere, i že
 * pustí, jakmile podmínka pominout může (druhý admin existuje). Guard, u kterého by se
 * ověřilo jen „vyhodí výjimku", by prošel i implementaci, která zakazuje všechno.
 *
 * <p>Seed (V3): admin id=1 (ROLE_ADMIN, jediný), manager id=2, mechanic id=3.
 *
 * <p>{@code @Transactional} — každý test běží ve vlastní transakci, která se na konci
 * odroluje, takže se seed nepoškodí bez ohledu na pořadí testů.
 */
@Transactional
class UserServiceTest extends AbstractIntegrationTest {

    private static final long ADMIN_ID = 1L;
    private static final long MANAGER_ID = 2L;
    private static final long MECHANIC_ID = 3L;

    @Autowired
    private UserService userService;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private cz.palo.autoservis.security.mapper.RoleMapper roleMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    // =========================================================================
    // create
    // =========================================================================

    @Test
    @DisplayName("create založí účet, zahashuje heslo a přiřadí role")
    void create_persistsUserWithHashedPasswordAndRoles() {
        UserDto.DetailResponse created = userService.create(
                createRequest("novy.technik", "novy.technik@autoservis.cz", "Password1!", roleIdsOf("ROLE_MECHANIC")),
                ADMIN_ID);

        assertThat(created.getId()).isNotNull();
        assertThat(created.getUsername()).isEqualTo("novy.technik");
        assertThat(created.getEmail()).isEqualTo("novy.technik@autoservis.cz");
        assertThat(created.isEnabled()).isTrue();
        assertThat(created.getRoles()).extracting("name").containsExactly("ROLE_MECHANIC");

        // heslo se ukládá jako BCrypt hash, nikdy v otevřené podobě (N-09)
        String storedHash = userMapper.findById(created.getId()).orElseThrow().getPasswordHash();
        assertThat(storedHash).isNotEqualTo("Password1!");
        assertThat(storedHash).startsWith("$2");
        assertThat(passwordEncoder.matches("Password1!", storedHash)).isTrue();
    }

    @Test
    @DisplayName("create s obsazeným uživatelským jménem → USER_ALREADY_EXISTS (409)")
    void create_duplicateUsername_throwsUserAlreadyExists() {
        assertThatThrownBy(() -> userService.create(
                createRequest("admin", "jiny@autoservis.cz", "Password1!", roleIdsOf("ROLE_MECHANIC")),
                ADMIN_ID))
                .isInstanceOf(UserAlreadyExistsException.class)
                .hasMessageContaining("admin");
    }

    @Test
    @DisplayName("create s obsazeným e-mailem → USER_ALREADY_EXISTS (409)")
    void create_duplicateEmail_throwsUserAlreadyExists() {
        assertThatThrownBy(() -> userService.create(
                createRequest("uplne.novy", "admin@autoservis.cz", "Password1!", roleIdsOf("ROLE_MECHANIC")),
                ADMIN_ID))
                .isInstanceOf(UserAlreadyExistsException.class)
                .hasMessageContaining("admin@autoservis.cz");
    }

    @Test
    @DisplayName("create umí přiřadit více rolí najednou")
    void create_withMultipleRoles_assignsAll() {
        UserDto.DetailResponse created = userService.create(
                createRequest("vedouci.technik", "vedouci@autoservis.cz", "Password1!",
                        roleIdsOf("ROLE_MANAGER", "ROLE_MECHANIC")),
                ADMIN_ID);

        assertThat(created.getRoles()).extracting("name")
                .containsExactlyInAnyOrder("ROLE_MANAGER", "ROLE_MECHANIC");
    }

    // =========================================================================
    // update
    // =========================================================================

    @Test
    @DisplayName("update změní e-mail a kompletně nahradí role")
    void update_changesEmailAndReplacesRoles() {
        UserDto.DetailResponse updated = userService.update(
                MECHANIC_ID, updateRequest("mechanik.novy@autoservis.cz", roleIdsOf("ROLE_MANAGER")), ADMIN_ID);

        assertThat(updated.getEmail()).isEqualTo("mechanik.novy@autoservis.cz");
        assertThat(updated.getUsername()).as("username je needitovatelné").isEqualTo("mechanic");
        assertThat(updated.getRoles()).extracting("name")
                .as("staré role se mažou, nepřičítají").containsExactly("ROLE_MANAGER");
    }

    @Test
    @DisplayName("update na e-mail jiného uživatele → DUPLICATE_EMAIL (422)")
    void update_emailTakenByAnotherUser_throwsDuplicateEmail() {
        assertThatThrownBy(() -> userService.update(
                MECHANIC_ID, updateRequest("admin@autoservis.cz", roleIdsOf("ROLE_MECHANIC")), ADMIN_ID))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(ex -> {
                    BusinessRuleException e = (BusinessRuleException) ex;
                    assertThat(e.getRuleCode()).isEqualTo("DUPLICATE_EMAIL");
                    assertThat(e.getField()).isEqualTo("email");
                });
    }

    @Test
    @DisplayName("update ponechávající vlastní e-mail projde (nekoliduje sám se sebou)")
    void update_keepingOwnEmail_succeeds() {
        UserDto.DetailResponse updated = userService.update(
                MECHANIC_ID, updateRequest("mechanic@autoservis.cz", roleIdsOf("ROLE_MECHANIC")), ADMIN_ID);

        assertThat(updated.getEmail()).isEqualTo("mechanic@autoservis.cz");
    }

    @Test
    @DisplayName("update neexistujícího uživatele → ResourceNotFoundException (404)")
    void update_unknownId_throwsResourceNotFound() {
        assertThatThrownBy(() -> userService.update(
                999_999L, updateRequest("kdokoli@autoservis.cz", roleIdsOf("ROLE_MECHANIC")), ADMIN_ID))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // =========================================================================
    // deactivate — guardy proti zamčení aplikace (obě větve)
    // =========================================================================

    @Test
    @DisplayName("deactivate vlastního účtu → CANNOT_DEACTIVATE_SELF (422)")
    void deactivate_ownAccount_isRejected() {
        assertThatThrownBy(() -> userService.deactivate(MANAGER_ID, MANAGER_ID))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(ex -> assertThat(((BusinessRuleException) ex).getRuleCode())
                        .isEqualTo("CANNOT_DEACTIVATE_SELF"));

        assertThat(userMapper.findById(MANAGER_ID).orElseThrow().isEnabled())
                .as("účet musí zůstat aktivní").isTrue();
    }

    @Test
    @DisplayName("deactivate posledního admina → CANNOT_DEACTIVATE_LAST_ADMIN (422)")
    void deactivate_lastAdmin_isRejected() {
        // seed má jediný účet s ROLE_ADMIN (id=1)
        assertThatThrownBy(() -> userService.deactivate(ADMIN_ID, MANAGER_ID))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(ex -> assertThat(((BusinessRuleException) ex).getRuleCode())
                        .isEqualTo("CANNOT_DEACTIVATE_LAST_ADMIN"));

        assertThat(userMapper.findById(ADMIN_ID).orElseThrow().isEnabled())
                .as("poslední admin musí zůstat aktivní").isTrue();
    }

    @Test
    @DisplayName("deactivate admina PROJDE, existuje-li druhý admin (druhá větev guardu)")
    void deactivate_adminWithAnotherAdminPresent_succeeds() {
        userService.create(
                createRequest("druhy.admin", "druhy.admin@autoservis.cz", "Password1!", roleIdsOf("ROLE_ADMIN")),
                ADMIN_ID);

        UserDto.DetailResponse deactivated = userService.deactivate(ADMIN_ID, MANAGER_ID);

        assertThat(deactivated.isEnabled()).isFalse();
        assertThat(userMapper.findById(ADMIN_ID).orElseThrow().isEnabled()).isFalse();
    }

    @Test
    @DisplayName("deactivate běžného účtu projde a je vratná přes activate (soft-delete)")
    void deactivate_thenActivate_restoresAccount() {
        UserDto.DetailResponse deactivated = userService.deactivate(MECHANIC_ID, ADMIN_ID);
        assertThat(deactivated.isEnabled()).isFalse();

        UserDto.DetailResponse reactivated = userService.activate(MECHANIC_ID);
        assertThat(reactivated.isEnabled()).isTrue();
        assertThat(userMapper.findById(MECHANIC_ID).orElseThrow().isEnabled()).isTrue();
    }

    @Test
    @DisplayName("deactivate neexistujícího uživatele → ResourceNotFoundException (404)")
    void deactivate_unknownId_throwsResourceNotFound() {
        assertThatThrownBy(() -> userService.deactivate(999_999L, ADMIN_ID))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("activate neexistujícího uživatele → ResourceNotFoundException (404)")
    void activate_unknownId_throwsResourceNotFound() {
        assertThatThrownBy(() -> userService.activate(999_999L))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // =========================================================================
    // update — guard proti odebrání role poslednímu adminovi (K-2)
    // =========================================================================

    @Test
    @DisplayName("update odebírající ADMIN roli poslednímu adminovi → CANNOT_REMOVE_LAST_ADMIN (422)")
    void update_removingAdminRoleFromLastAdmin_isRejected() {
        assertThatThrownBy(() -> userService.update(
                ADMIN_ID, updateRequest("admin@autoservis.cz", roleIdsOf("ROLE_MECHANIC")), ADMIN_ID))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(ex -> assertThat(((BusinessRuleException) ex).getRuleCode())
                        .isEqualTo("CANNOT_REMOVE_LAST_ADMIN"));

        assertThat(userMapper.findById(ADMIN_ID).orElseThrow().getRoles())
                .as("poslední admin si roli ADMIN podrží")
                .anySatisfy(r -> assertThat(r.getName()).isEqualTo("ROLE_ADMIN"));
    }

    @Test
    @DisplayName("update posledního admina, který si ADMIN roli ponechá, projde")
    void update_lastAdminKeepingAdminRole_succeeds() {
        UserDto.DetailResponse updated = userService.update(
                ADMIN_ID, updateRequest("admin@autoservis.cz", roleIdsOf("ROLE_ADMIN", "ROLE_MANAGER")), ADMIN_ID);

        assertThat(updated.getRoles()).extracting(r -> r.getName())
                .contains("ROLE_ADMIN", "ROLE_MANAGER");
    }

    @Test
    @DisplayName("update odebírající ADMIN roli PROJDE, existuje-li druhý admin (druhá větev guardu)")
    void update_removingAdminRoleWithAnotherAdminPresent_succeeds() {
        userService.create(
                createRequest("druhy.admin", "druhy.admin@autoservis.cz", "Password1!", roleIdsOf("ROLE_ADMIN")),
                ADMIN_ID);

        UserDto.DetailResponse updated = userService.update(
                ADMIN_ID, updateRequest("admin@autoservis.cz", roleIdsOf("ROLE_MECHANIC")), ADMIN_ID);

        assertThat(updated.getRoles()).extracting(r -> r.getName())
                .containsExactly("ROLE_MECHANIC");
    }

    // =========================================================================
    // resetPassword — zároveň odemyká účet (V3b)
    // =========================================================================

    @Test
    @DisplayName("resetPassword nastaví nový hash a zároveň odemkne zamčený účet")
    void resetPassword_setsNewHashAndUnlocksAccount() {
        // simulace zamčení po 10 neúspěšných pokusech
        userMapper.incrementFailedAttempts(MECHANIC_ID);
        userMapper.lockAccount(MECHANIC_ID);
        assertThat(userMapper.findById(MECHANIC_ID).orElseThrow().isAccountNonLocked())
                .as("předpoklad testu: účet je zamčený").isFalse();

        UserDto.ResetPasswordRequest request = new UserDto.ResetPasswordRequest();
        request.setNewPassword("NoveHeslo1!");

        UserDto.DetailResponse result = userService.resetPassword(MECHANIC_ID, request);

        assertThat(result.isAccountNonLocked()).isTrue();
        assertThat(result.getFailedLoginAttempts()).isZero();

        String storedHash = userMapper.findById(MECHANIC_ID).orElseThrow().getPasswordHash();
        assertThat(passwordEncoder.matches("NoveHeslo1!", storedHash)).isTrue();
        assertThat(passwordEncoder.matches("Password1!", storedHash))
                .as("staré heslo už nesmí projít").isFalse();
    }

    @Test
    @DisplayName("resetPassword neexistujícího uživatele → ResourceNotFoundException (404)")
    void resetPassword_unknownId_throwsResourceNotFound() {
        UserDto.ResetPasswordRequest request = new UserDto.ResetPasswordRequest();
        request.setNewPassword("NoveHeslo1!");

        assertThatThrownBy(() -> userService.resetPassword(999_999L, request))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // =========================================================================
    // getById
    // =========================================================================

    @Test
    @DisplayName("getById vrátí účet i s rolemi")
    void getById_returnsAccountWithRoles() {
        UserDto.DetailResponse response = userService.getById(ADMIN_ID);

        assertThat(response.getId()).isEqualTo(ADMIN_ID);
        assertThat(response.getUsername()).isEqualTo("admin");
        assertThat(response.getEmail()).isEqualTo("admin@autoservis.cz");
        assertThat(response.isEnabled()).isTrue();
        assertThat(response.getRoles()).extracting("name").containsExactly("ROLE_ADMIN");
    }

    @Test
    @DisplayName("getById neexistujícího uživatele → ResourceNotFoundException (404)")
    void getById_unknownId_throwsResourceNotFound() {
        assertThatThrownBy(() -> userService.getById(999_999L))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // =========================================================================
    // getPage
    // =========================================================================

    @Test
    @DisplayName("getPage vrátí stránku účtů i s celkovým počtem a rolemi")
    void getPage_returnsPagedAccounts() {
        cz.palo.autoservis.model.dto.user.UserSearchParams params =
                new cz.palo.autoservis.model.dto.user.UserSearchParams();
        params.setPage(1);
        params.setPageSize(2);

        var page = userService.getPage(params);

        assertThat(page).isNotNull();
        assertThat(page.getContent()).hasSize(2);
        assertThat(page.getPageSize()).isEqualTo(2);
        assertThat(page.getPage()).isEqualTo(1);
        assertThat(page.getTotalElements())
                .as("seed má 5 účtů (3 zaměstnanci + 2 portálové)").isGreaterThanOrEqualTo(5);
        assertThat(page.getContent()).allSatisfy(user -> {
            assertThat(user.getId()).isNotNull();
            assertThat(user.getUsername()).isNotBlank();
        });
    }

    @Test
    @DisplayName("getPage s filtrem na jméno vrátí jen odpovídající účet")
    void getPage_withSearchFilter_narrowsResult() {
        cz.palo.autoservis.model.dto.user.UserSearchParams params =
                new cz.palo.autoservis.model.dto.user.UserSearchParams();
        params.setPage(1);
        params.setPageSize(10);
        params.setSearch("mechanic");

        var page = userService.getPage(params);

        assertThat(page.getContent()).isNotEmpty();
        assertThat(page.getContent()).extracting("username").contains("mechanic");
        assertThat(page.getContent()).allSatisfy(user ->
                assertThat(user.getUsername() + " " + user.getEmail()).contains("mechanic"));
    }

    // =========================================================================
    // Guard posledního admina — rozlišení role
    // =========================================================================

    @Test
    @DisplayName("guard posledního admina se týká JEN admina — běžný účet jde deaktivovat i bez aktivního admina")
    void deactivate_nonAdmin_isNotBlockedByAdminGuard() {
        // Vynutíme stav, kdy v systému není žádný aktivní admin (přes mapper, mimo service
        // guard). Kdyby kontrola role vypadla a považovala za admina každého, deaktivace
        // mechanika by teď spadla na CANNOT_DEACTIVATE_LAST_ADMIN.
        userMapper.deactivate(ADMIN_ID);
        assertThat(userMapper.countEnabledByRoleExcluding("ROLE_ADMIN", MECHANIC_ID))
                .as("předpoklad testu: žádný aktivní admin").isZero();

        UserDto.DetailResponse deactivated = userService.deactivate(MECHANIC_ID, MANAGER_ID);

        assertThat(deactivated.isEnabled()).isFalse();
    }

    // =========================================================================
    // Fixtury
    // =========================================================================

    private List<Integer> roleIdsOf(String... roleNames) {
        return java.util.Arrays.stream(roleNames)
                .map(name -> roleMapper.getAll().stream()
                        .filter(r -> name.equals(r.getName()))
                        .findFirst()
                        .orElseThrow(() -> new IllegalStateException("Role " + name + " není v seedu"))
                        .getId())
                .toList();
    }

    private static UserDto.CreateRequest createRequest(String username, String email,
                                                       String password, List<Integer> roleIds) {
        UserDto.CreateRequest request = new UserDto.CreateRequest();
        request.setUsername(username);
        request.setEmail(email);
        request.setPassword(password);
        request.setRoleIds(roleIds);
        return request;
    }

    private static UserDto.UpdateRequest updateRequest(String email, List<Integer> roleIds) {
        UserDto.UpdateRequest request = new UserDto.UpdateRequest();
        request.setEmail(email);
        request.setRoleIds(roleIds);
        return request;
    }
}
