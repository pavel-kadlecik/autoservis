package cz.palo.autoservis.service;

import cz.palo.autoservis.AbstractIntegrationTest;
import cz.palo.autoservis.exception.BusinessRuleException;
import cz.palo.autoservis.exception.ResourceNotFoundException;
import cz.palo.autoservis.model.dto.employee.EmployeeDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Správa zaměstnanců ({@code EmployeeServiceImpl}) proti reálné DB.
 *
 * <p>Seed (V58): #1 Petr Mechanik (aktivní, napojený na login {@code mechanic} = user 3),
 * #2 Jan Dvořák a #3 Tomáš Svoboda (aktivní), #4 Martin Novák (odešlý, neaktivní).
 */
@Transactional
class EmployeeServiceTest extends AbstractIntegrationTest {

    private static final long USER_ID = 1L;              // admin (audit created_by)
    private static final long MECHANIC_USER_ID = 3L;     // login 'mechanic' (seed #1 ho už drží)
    private static final long INACTIVE_EMPLOYEE_ID = 4L; // Martin Novák — odešlý

    @Autowired
    private EmployeeService employeeService;

    // =========================================================================
    // create
    // =========================================================================

    @Test
    @DisplayName("create uloží zaměstnance, doplní createdBy ze serveru a vrátí ho z DB")
    void create_persistsWithServerSideAudit() {
        EmployeeDto.DetailResponse created = employeeService.create(newRequest(), USER_ID);

        assertThat(created.getId()).isNotNull();
        assertThat(created.getFirstName()).isEqualTo("Nový");
        assertThat(created.getLastName()).isEqualTo("Zaměstnanec");
        assertThat(created.getFullName()).isEqualTo("Nový Zaměstnanec");
        assertThat(created.getHourlyRate()).isEqualByComparingTo("480.00");
        assertThat(created.isActive()).isTrue();
        assertThat(created.getUserId()).isNull();
    }

    @Test
    @DisplayName("create s user_id bez zaměstnance projde a napojí login")
    void create_withFreeUserId_links() {
        EmployeeDto.CreateRequest request = newRequest();
        request.setUserId(2L); // manager — zatím žádný zaměstnanec na něj neukazuje

        EmployeeDto.DetailResponse created = employeeService.create(request, USER_ID);

        assertThat(created.getUserId()).isEqualTo(2L);
    }

    @Test
    @DisplayName("create s user_id, který už drží jiný zaměstnanec → DUPLICATE_EMPLOYEE_USER")
    void create_withTakenUserId_throws() {
        EmployeeDto.CreateRequest request = newRequest();
        request.setUserId(MECHANIC_USER_ID); // seed #1 ho už drží

        assertThatThrownBy(() -> employeeService.create(request, USER_ID))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(ex -> assertThat(((BusinessRuleException) ex).getRuleCode())
                        .isEqualTo("DUPLICATE_EMPLOYEE_USER"));
    }

    @Test
    @DisplayName("create s neexistujícím user_id → ResourceNotFound (User)")
    void create_withUnknownUserId_throws() {
        EmployeeDto.CreateRequest request = newRequest();
        request.setUserId(999_999L);

        assertThatThrownBy(() -> employeeService.create(request, USER_ID))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("create s datem odchodu před nástupem → INVALID_EMPLOYEE_DATES")
    void create_withLeftBeforeHired_throws() {
        EmployeeDto.CreateRequest request = newRequest();
        request.setHiredAt(LocalDate.of(2024, 1, 1));
        request.setLeftAt(LocalDate.of(2023, 12, 31));

        assertThatThrownBy(() -> employeeService.create(request, USER_ID))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(ex -> assertThat(((BusinessRuleException) ex).getRuleCode())
                        .isEqualTo("INVALID_EMPLOYEE_DATES"));
    }

    // =========================================================================
    // getById / getAll
    // =========================================================================

    @Test
    @DisplayName("getById na neaktivním zaměstnanci vrací 404 (strict, R-10)")
    void getById_inactive_notFound() {
        assertThatThrownBy(() -> employeeService.getById(INACTIVE_EMPLOYEE_ID))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("getAll(activeOnly=false) vrací i odešlého; activeOnly=true ho skryje")
    void getAll_respectsActiveOnly() {
        List<EmployeeDto.ListResponse> all = employeeService.getAll(false);
        List<EmployeeDto.ListResponse> activeOnly = employeeService.getAll(true);

        assertThat(all).anyMatch(e -> e.getId().equals(INACTIVE_EMPLOYEE_ID));
        assertThat(activeOnly).noneMatch(e -> e.getId().equals(INACTIVE_EMPLOYEE_ID));
        assertThat(all).allSatisfy(e -> assertThat(e.getFullName()).isNotBlank());
    }

    // =========================================================================
    // update
    // =========================================================================

    @Test
    @DisplayName("update změní sazbu a vrátí čerstvý stav z DB")
    void update_changesRate() {
        EmployeeDto.DetailResponse created = employeeService.create(newRequest(), USER_ID);

        EmployeeDto.UpdateRequest req = EmployeeDto.UpdateRequest.builder()
                .firstName(created.getFirstName())
                .lastName(created.getLastName())
                .position("Mistr")
                .hourlyRate(new BigDecimal("999.00"))
                .hiredAt(created.getHiredAt())
                .build();

        EmployeeDto.DetailResponse updated = employeeService.update(created.getId(), req, USER_ID);

        assertThat(updated.getHourlyRate()).isEqualByComparingTo("999.00");
        assertThat(updated.getPosition()).isEqualTo("Mistr");
    }

    @Test
    @DisplayName("update neexistujícího zaměstnance → 404")
    void update_unknown_notFound() {
        EmployeeDto.UpdateRequest req = EmployeeDto.UpdateRequest.builder()
                .firstName("X").lastName("Y").hiredAt(LocalDate.now()).build();

        assertThatThrownBy(() -> employeeService.update(999_999L, req, USER_ID))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // =========================================================================
    // deactivate / activate (soft-delete)
    // =========================================================================

    @Test
    @DisplayName("deactivate skryje zaměstnance ze strict getById, activate ho vrátí")
    void deactivateThenActivate_roundTrip() {
        EmployeeDto.DetailResponse created = employeeService.create(newRequest(), USER_ID);
        Long id = created.getId();

        EmployeeDto.DetailResponse deactivated = employeeService.deactivate(id);
        assertThat(deactivated.isActive()).isFalse();
        assertThatThrownBy(() -> employeeService.getById(id))
                .isInstanceOf(ResourceNotFoundException.class);

        EmployeeDto.DetailResponse activated = employeeService.activate(id);
        assertThat(activated.isActive()).isTrue();
        assertThat(employeeService.getById(id).getId()).isEqualTo(id);
    }

    // =========================================================================
    // helpers
    // =========================================================================

    private EmployeeDto.CreateRequest newRequest() {
        return EmployeeDto.CreateRequest.builder()
                .firstName("Nový")
                .lastName("Zaměstnanec")
                .position("Automechanik")
                .hourlyRate(new BigDecimal("480.00"))
                .hiredAt(LocalDate.of(2024, 1, 15))
                .build();
    }
}
