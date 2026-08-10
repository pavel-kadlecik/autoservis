package cz.palo.autoservis.model.converter;

import cz.palo.autoservis.model.domain.employee.Employee;
import cz.palo.autoservis.model.dto.employee.EmployeeDto;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Konvertor mezi doménovými objekty {@link Employee} a DTO {@link EmployeeDto}.
 * Ruční {@code @Component} konvertor — MapStruct se nepoužívá (R-11).
 *
 * <p>Nenastavuje {@code createdBy} (service, ze SecurityContextu) ani časová
 * razítka (DB default/trigger).
 */
@Component
public class EmployeeConverter {

    /**
     * Převede {@link EmployeeDto.CreateRequest} na nového {@link Employee}.
     * {@code active} má u nových zaměstnanců výchozí hodnotu {@code true}.
     *
     * @param request zvalidovaný create request, může být {@code null}
     * @return doménový objekt připravený k INSERTu, nebo {@code null} při {@code null} vstupu
     */
    public Employee toDomain(EmployeeDto.CreateRequest request) {
        if (request == null) {
            return null;
        }
        Employee employee = new Employee();
        employee.setActive(true);
        employee.setUserId(request.getUserId());
        employee.setFirstName(request.getFirstName());
        employee.setLastName(request.getLastName());
        employee.setPosition(request.getPosition());
        employee.setHourlyRate(request.getHourlyRate());
        employee.setHiredAt(request.getHiredAt());
        employee.setLeftAt(request.getLeftAt());
        return employee;
    }

    /**
     * Aplikuje pole z {@link EmployeeDto.UpdateRequest} na existujícího
     * {@link Employee}, mění ho na místě. Polí {@code id}, {@code active}
     * a auditních polí se nedotýká.
     *
     * @param existing zaměstnanec načtený z databáze
     * @param request  zvalidovaný update request
     * @return upravený doménový objekt, nebo {@code null}, je-li kterýkoli argument {@code null}
     */
    public Employee applyUpdate(Employee existing, EmployeeDto.UpdateRequest request) {
        if (existing == null || request == null) {
            return null;
        }
        existing.setUserId(request.getUserId());
        existing.setFirstName(request.getFirstName());
        existing.setLastName(request.getLastName());
        existing.setPosition(request.getPosition());
        existing.setHourlyRate(request.getHourlyRate());
        existing.setHiredAt(request.getHiredAt());
        existing.setLeftAt(request.getLeftAt());
        return existing;
    }

    /**
     * Převede {@link Employee} na plné {@link EmployeeDto.DetailResponse}.
     *
     * @param employee doménový objekt, může být {@code null}
     * @return detailové response DTO, nebo {@code null} při {@code null} vstupu
     */
    public EmployeeDto.DetailResponse toDetailResponse(Employee employee) {
        if (employee == null) {
            return null;
        }
        return EmployeeDto.DetailResponse.builder()
                .id(employee.getId())
                .userId(employee.getUserId())
                .firstName(employee.getFirstName())
                .lastName(employee.getLastName())
                .fullName(fullName(employee))
                .position(employee.getPosition())
                .hourlyRate(employee.getHourlyRate())
                .hiredAt(employee.getHiredAt())
                .leftAt(employee.getLeftAt())
                .active(employee.isActive())
                .createdAt(employee.getCreatedAt())
                .updatedAt(employee.getUpdatedAt())
                .build();
    }

    /**
     * Převede {@link Employee} na zúžené {@link EmployeeDto.ListResponse}.
     *
     * @param employee doménový objekt, může být {@code null}
     * @return seznamové response DTO, nebo {@code null} při {@code null} vstupu
     */
    public EmployeeDto.ListResponse toListResponse(Employee employee) {
        if (employee == null) {
            return null;
        }
        return EmployeeDto.ListResponse.builder()
                .id(employee.getId())
                .fullName(fullName(employee))
                .position(employee.getPosition())
                .hourlyRate(employee.getHourlyRate())
                .hiredAt(employee.getHiredAt())
                .leftAt(employee.getLeftAt())
                .active(employee.isActive())
                .build();
    }

    /**
     * Převede seznam doménových objektů {@link Employee} na {@link EmployeeDto.ListResponse}.
     *
     * @param employees seznam doménových objektů
     * @return seznam seznamových response DTO
     */
    public List<EmployeeDto.ListResponse> toListResponses(List<Employee> employees) {
        return employees.stream().map(this::toListResponse).toList();
    }

    private String fullName(Employee employee) {
        return (employee.getFirstName() + " " + employee.getLastName()).trim();
    }
}
