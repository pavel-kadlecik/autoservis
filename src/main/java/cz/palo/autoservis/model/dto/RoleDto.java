package cz.palo.autoservis.model.dto;

import cz.palo.autoservis.model.domain.user.Role;
import lombok.Data;

/**
 * Response DTO číselníku {@code security.roles}.
 */
@Data
public class RoleDto {

    private final int id;
    private final String name;
    private final String description;

    public RoleDto(Role role) {
        this.id = role.getId();
        this.name = role.getName();
        this.description = role.getDescription();
    }
}
