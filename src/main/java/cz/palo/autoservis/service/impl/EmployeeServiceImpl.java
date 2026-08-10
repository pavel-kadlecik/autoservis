package cz.palo.autoservis.service.impl;

import cz.palo.autoservis.exception.BusinessRuleException;
import cz.palo.autoservis.exception.ResourceNotFoundException;
import cz.palo.autoservis.mapper.EmployeeMapper;
import cz.palo.autoservis.model.converter.EmployeeConverter;
import cz.palo.autoservis.model.domain.employee.Employee;
import cz.palo.autoservis.model.dto.employee.EmployeeDto;
import cz.palo.autoservis.security.mapper.UserMapper;
import cz.palo.autoservis.service.EmployeeService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Implementace {@link EmployeeService}.
 */
@Service
@RequiredArgsConstructor
public class EmployeeServiceImpl implements EmployeeService {

    private final EmployeeMapper employeeMapper;
    private final EmployeeConverter employeeConverter;
    private final UserMapper userMapper;

    /** {@inheritDoc} */
    @Override
    public List<EmployeeDto.ListResponse> getAll(boolean activeOnly) {
        return employeeConverter.toListResponses(employeeMapper.findAll(activeOnly));
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException  když je {@code id} null
     * @throws ResourceNotFoundException když aktivní zaměstnanec s daným ID neexistuje
     */
    @Override
    public EmployeeDto.DetailResponse getById(Long id) {
        if (id == null) {
            throw new IllegalArgumentException("id nesmí být null");
        }
        return employeeMapper.findById(id)
                .map(employeeConverter::toDetailResponse)
                .orElseThrow(() -> new ResourceNotFoundException("Zaměstnanec", id));
    }

    /**
     * {@inheritDoc}
     *
     * @throws BusinessRuleException     když je {@code leftAt} před {@code hiredAt}
     * @throws ResourceNotFoundException když navázaný přihlašovací účet neexistuje
     * @throws BusinessRuleException     když účet už používá jiný zaměstnanec
     */
    @Override
    @Transactional
    public EmployeeDto.DetailResponse create(EmployeeDto.CreateRequest request, Long userId) {
        validateDates(request.getHiredAt(), request.getLeftAt());
        validateUserLink(request.getUserId(), null);

        Employee employee = employeeConverter.toDomain(request);
        employee.setCreatedBy(userId);
        employeeMapper.insert(employee);

        return getById(employee.getId());
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException  když je {@code id} null
     * @throws ResourceNotFoundException když zaměstnanec s daným ID neexistuje
     * @throws BusinessRuleException     když je {@code leftAt} před {@code hiredAt}
     * @throws ResourceNotFoundException když navázaný přihlašovací účet neexistuje
     * @throws BusinessRuleException     když účet už používá jiný zaměstnanec
     */
    @Override
    @Transactional
    public EmployeeDto.DetailResponse update(Long id, EmployeeDto.UpdateRequest request, Long userId) {
        if (id == null) {
            throw new IllegalArgumentException("id nesmí být null");
        }
        Employee employee = employeeMapper.findByIdIncludingInactive(id)
                .orElseThrow(() -> new ResourceNotFoundException("Zaměstnanec", id));

        validateDates(request.getHiredAt(), request.getLeftAt());
        validateUserLink(request.getUserId(), id);

        Employee updated = employeeConverter.applyUpdate(employee, request);
        int affectedRows = employeeMapper.update(updated);
        return verifyAndFetchAfterUpdate(id, affectedRows);
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException  když je {@code id} null
     * @throws ResourceNotFoundException když zaměstnanec s daným ID neexistuje
     */
    @Override
    @Transactional
    public EmployeeDto.DetailResponse deactivate(Long id) {
        if (id == null) {
            throw new IllegalArgumentException("id nesmí být null");
        }
        int affectedRows = employeeMapper.deactivate(id);
        return verifyAndFetchAfterStatusChange(id, affectedRows);
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException  když je {@code id} null
     * @throws ResourceNotFoundException když zaměstnanec s daným ID neexistuje
     */
    @Override
    @Transactional
    public EmployeeDto.DetailResponse activate(Long id) {
        if (id == null) {
            throw new IllegalArgumentException("id nesmí být null");
        }
        int affectedRows = employeeMapper.activate(id);
        return verifyAndFetchAfterStatusChange(id, affectedRows);
    }

    // =========================================================================
    // Privátní pomocné metody
    // =========================================================================

    /**
     * Pravidlo napříč poli dat — zrcadlí DB CHECK {@code chk_employees_dates}.
     *
     * @throws BusinessRuleException když je {@code leftAt} před {@code hiredAt}
     */
    private void validateDates(LocalDate hiredAt, LocalDate leftAt) {
        if (hiredAt != null && leftAt != null && leftAt.isBefore(hiredAt)) {
            throw new BusinessRuleException(
                    "INVALID_EMPLOYEE_DATES",
                    "leftAt",
                    "Datum odchodu (" + leftAt + ") nesmí být před datem nástupu (" + hiredAt + ")",
                    Map.of("hiredAt", hiredAt, "leftAt", leftAt));
        }
    }

    /**
     * Validuje volitelnou vazbu na login (D-5): účet musí existovat a nesmí
     * už patřit jinému zaměstnanci ({@code uq_employees_user}, R-13).
     *
     * @param linkedUserId ID přihlašovacího účtu, může být {@code null}
     * @param excludeId    ID zaměstnance k vynechání (sebe sama při úpravě); {@code null} při založení
     * @throws ResourceNotFoundException když účet neexistuje
     * @throws BusinessRuleException     když účet už používá jiný zaměstnanec
     */
    private void validateUserLink(Long linkedUserId, Long excludeId) {
        if (linkedUserId == null) {
            return;
        }
        if (userMapper.findById(linkedUserId).isEmpty()) {
            throw new ResourceNotFoundException("Uživatel", linkedUserId);
        }
        if (employeeMapper.existsByUserId(linkedUserId, excludeId)) {
            throw new BusinessRuleException(
                    "DUPLICATE_EMPLOYEE_USER",
                    "userId",
                    "K přihlašovacímu účtu " + linkedUserId + " už je připojený jiný zaměstnanec",
                    Map.of("userId", linkedUserId));
        }
    }

    private EmployeeDto.DetailResponse verifyAndFetchAfterStatusChange(Long id, int affectedRows) {
        if (affectedRows == 0) {
            throw new ResourceNotFoundException("Zaměstnanec", id);
        }
        return fetchOrFail(id);
    }

    private EmployeeDto.DetailResponse verifyAndFetchAfterUpdate(Long id, int affectedRows) {
        if (affectedRows == 0) {
            throw new IllegalStateException("Zaměstnanec " + id + " zmizel během aktualizace");
        }
        return fetchOrFail(id);
    }

    private EmployeeDto.DetailResponse fetchOrFail(Long id) {
        return employeeMapper.findByIdIncludingInactive(id)
                .map(employeeConverter::toDetailResponse)
                .orElseThrow(() -> new IllegalStateException(
                        "Zaměstnanec " + id + " zmizel mezi UPDATE a SELECT"));
    }
}
