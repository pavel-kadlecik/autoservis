package cz.palo.autoservis.model.converter;

import cz.palo.autoservis.model.domain.user.Role;
import cz.palo.autoservis.model.domain.user.User;
import cz.palo.autoservis.model.dto.RoleDto;
import cz.palo.autoservis.model.dto.user.UserDto;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Konvertor mezi doménovými objekty {@link User} a DTO {@link UserDto}.
 */
@Component
public class UserConverter {

    /**
     * Převede {@link User} na plné {@link UserDto.DetailResponse}.
     *
     * @param user doménový objekt k převodu
     * @return detailové response DTO, nebo {@code null} při {@code null} vstupu
     */
    public UserDto.DetailResponse toDetailResponse(User user) {
        if (user == null) {
            return null;
        }
        UserDto.DetailResponse response = new UserDto.DetailResponse();
        response.setId(user.getId());
        response.setUsername(user.getUsername());
        response.setEmail(user.getEmail());
        response.setEnabled(user.isEnabled());
        response.setAccountNonLocked(user.isAccountNonLocked());
        response.setFailedLoginAttempts(user.getFailedLoginAttempts());
        response.setRoles(toRoleDtos(user.getRoles()));
        response.setLastLoginAt(user.getLastLoginAt());
        response.setPasswordChangedAt(user.getPasswordChangedAt());
        response.setCreatedAt(user.getCreatedAt());
        response.setUpdatedAt(user.getUpdatedAt());
        return response;
    }

    /**
     * Převede seznam doménových objektů {@link User} na seznam {@link UserDto.ListResponse}.
     *
     * @param users seznam doménových objektů
     * @return seznam seznamových response DTO
     */
    public List<UserDto.ListResponse> toListResponses(List<User> users) {
        return users.stream().map(this::toListResponse).collect(Collectors.toList());
    }

    /**
     * Převede {@link UserDto.CreateRequest} na doménový objekt {@link User}.
     * Hash hesla, časová razítka ani auditní pole se tady nenastavují —
     * hash počítá service vrstva a razítka spravuje databáze.
     *
     * @param createRequest zvalidované create request DTO
     * @return doménový objekt připravený k INSERTu, nebo {@code null} při {@code null} vstupu
     */
    public User toDomain(UserDto.CreateRequest createRequest) {
        if (createRequest == null) {
            return null;
        }
        User user = new User();
        user.setUsername(createRequest.getUsername());
        user.setEmail(createRequest.getEmail());
        user.setEnabled(true);
        return user;
    }

    /**
     * Aplikuje pole e-mailu z {@link UserDto.UpdateRequest} na existujícího {@link User}.
     * Přiřazení rolí řeší zvlášť service přes rolové tabulky mapperu.
     *
     * @param existingUser  uživatel načtený z databáze
     * @param updateRequest zvalidované update request DTO
     * @return upravený doménový objekt, nebo {@code null}, je-li kterýkoli argument {@code null}
     */
    public User applyUpdate(User existingUser, UserDto.UpdateRequest updateRequest) {
        if (existingUser == null || updateRequest == null) {
            return null;
        }
        existingUser.setEmail(updateRequest.getEmail());
        return existingUser;
    }

    private UserDto.ListResponse toListResponse(User user) {
        if (user == null) {
            return null;
        }
        UserDto.ListResponse response = new UserDto.ListResponse();
        response.setId(user.getId());
        response.setUsername(user.getUsername());
        response.setEmail(user.getEmail());
        response.setEnabled(user.isEnabled());
        response.setRoles(toRoleNames(user.getRoles()));
        response.setLastLoginAt(user.getLastLoginAt());
        response.setCreatedAt(user.getCreatedAt());
        return response;
    }

    private List<RoleDto> toRoleDtos(List<Role> roles) {
        if (roles == null) {
            return Collections.emptyList();
        }
        return roles.stream()
                .filter(r -> r.getName() != null)
                .map(RoleDto::new)
                .collect(Collectors.toList());
    }

    private List<String> toRoleNames(List<Role> roles) {
        if (roles == null) {
            return Collections.emptyList();
        }
        return roles.stream()
                .map(Role::getName)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toList());
    }
}
