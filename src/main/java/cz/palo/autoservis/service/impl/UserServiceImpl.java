package cz.palo.autoservis.service.impl;

import cz.palo.autoservis.exception.BusinessRuleException;
import cz.palo.autoservis.exception.ResourceNotFoundException;
import cz.palo.autoservis.exception.UserAlreadyExistsException;
import cz.palo.autoservis.model.converter.UserConverter;
import cz.palo.autoservis.model.domain.user.Role;
import cz.palo.autoservis.model.domain.user.User;
import cz.palo.autoservis.model.dto.pagination.PagedResponse;
import cz.palo.autoservis.model.dto.user.UserDto;
import cz.palo.autoservis.model.dto.user.UserSearchParams;
import cz.palo.autoservis.security.mapper.RefreshTokenMapper;
import cz.palo.autoservis.security.mapper.RoleMapper;
import cz.palo.autoservis.security.mapper.UserMapper;
import cz.palo.autoservis.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * Implementace {@link UserService} — adminský CRUD nad {@code security.users}.
 */
@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private static final String ROLE_ADMIN = "ROLE_ADMIN";

    private final UserMapper userMapper;
    private final UserConverter userConverter;
    private final PasswordEncoder passwordEncoder;
    private final RoleMapper roleMapper;
    private final RefreshTokenMapper refreshTokenMapper;

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException  když je {@code id} null
     * @throws ResourceNotFoundException když uživatel s daným ID neexistuje
     */
    @Override
    public UserDto.DetailResponse getById(Long id) {
        if (id == null) {
            throw new IllegalArgumentException("id nesmí být null");
        }
        return fetchOrFail(id);
    }

    /** {@inheritDoc} */
    @Override
    public PagedResponse<UserDto.ListResponse> getPage(UserSearchParams params) {
        List<User> users = userMapper.search(params);
        List<UserDto.ListResponse> listResponses = userConverter.toListResponses(users);
        long total = userMapper.countSearch(params);
        return PagedResponse.of(listResponses, params.getPage(), params.getPageSize(), total);
    }

    /**
     * {@inheritDoc}
     *
     * @throws UserAlreadyExistsException když je uživatelské jméno nebo e-mail obsazený
     */
    @Override
    @Transactional
    public UserDto.DetailResponse create(UserDto.CreateRequest createRequest, Long createdBy) {
        if (userMapper.existsByUsername(createRequest.getUsername())) {
            throw new UserAlreadyExistsException(
                    "Uživatel se jménem '" + createRequest.getUsername() + "' již existuje.");
        }
        if (userMapper.existsByEmail(createRequest.getEmail())) {
            throw new UserAlreadyExistsException(
                    "Uživatel s emailem '" + createRequest.getEmail() + "' již existuje.");
        }

        User user = userConverter.toDomain(createRequest);
        user.setPasswordHash(passwordEncoder.encode(createRequest.getPassword()));
        userMapper.insert(user);

        userMapper.insertRoles(user.getId(), createRequest.getRoleIds(), createdBy);

        return fetchOrFail(user.getId());
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException  když je {@code id} null
     * @throws ResourceNotFoundException když uživatel s daným ID neexistuje
     * @throws BusinessRuleException     když nový e-mail už používá jiný uživatel
     */
    @Override
    @Transactional
    public UserDto.DetailResponse update(Long id, UserDto.UpdateRequest updateRequest, Long updatedBy) {
        if (id == null) {
            throw new IllegalArgumentException("id nesmí být null");
        }
        User existingUser = userMapper.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Uživatel", id));

        String newEmail = updateRequest.getEmail();
        if (!newEmail.equals(existingUser.getEmail()) && userMapper.existsByEmail(newEmail)) {
            throw new BusinessRuleException(
                    "DUPLICATE_EMAIL",
                    "email",
                    "Email " + newEmail + " již používá jiný uživatel",
                    Map.of("email", newEmail));
        }

        requireAdminRoleNotRemovedFromLastAdmin(existingUser, updateRequest.getRoleIds());

        User updatedUser = userConverter.applyUpdate(existingUser, updateRequest);
        int affectedRows = userMapper.updateEmail(updatedUser);
        if (affectedRows == 0) {
            throw new IllegalStateException("Uživatel " + id + " zmizel během aktualizace (byl načten těsně předtím)");
        }

        userMapper.deleteRoles(id);
        userMapper.insertRoles(id, updateRequest.getRoleIds(), updatedBy);

        return fetchOrFail(id);
    }

    /**
     * Pojistka proti odebrání role {@code ROLE_ADMIN} poslednímu aktivnímu administrátorovi
     * (audit K-2). Zrcadlová pojistka pro deaktivaci žije v {@link #deactivate}; {@code update}
     * ji neměl, takže admin mohl editací rolí zamknout všem přístup do ADMIN-only
     * {@code UserController} — opravitelné jen přímým zásahem do DB.
     *
     * @param existingUser upravovaný uživatel (s načtenými aktuálními rolemi)
     * @param newRoleIds   ID rolí, které by úprava přiřadila
     * @throws BusinessRuleException 422 {@code CANNOT_REMOVE_LAST_ADMIN}, kdyby změna nechala
     *                               systém bez aktivního administrátora
     */
    private void requireAdminRoleNotRemovedFromLastAdmin(User existingUser, List<Integer> newRoleIds) {
        boolean isCurrentlyAdmin = existingUser.getRoles() != null
                && existingUser.getRoles().stream().anyMatch(r -> ROLE_ADMIN.equals(r.getName()));
        if (!isCurrentlyAdmin) {
            return;
        }

        Integer adminRoleId = roleMapper.getAll().stream()
                .filter(r -> ROLE_ADMIN.equals(r.getName()))
                .map(Role::getId)
                .findFirst()
                .orElse(null);
        boolean willRemainAdmin = adminRoleId != null
                && newRoleIds != null
                && newRoleIds.contains(adminRoleId);

        if (!willRemainAdmin && userMapper.countEnabledByRoleExcluding(ROLE_ADMIN, existingUser.getId()) == 0) {
            throw new BusinessRuleException(
                    "CANNOT_REMOVE_LAST_ADMIN",
                    "Nelze odebrat roli administrátora poslednímu aktivnímu administrátorovi");
        }
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException  když je {@code id} null
     * @throws ResourceNotFoundException když uživatel s daným ID neexistuje
     * @throws BusinessRuleException     při pokusu deaktivovat vlastní účet volajícího
     *                                    nebo posledního aktivního administrátora
     */
    @Override
    @Transactional
    public UserDto.DetailResponse deactivate(Long id, Long currentUserId) {
        if (id == null) {
            throw new IllegalArgumentException("id nesmí být null");
        }
        if (id.equals(currentUserId)) {
            throw new BusinessRuleException(
                    "CANNOT_DEACTIVATE_SELF",
                    "Nelze deaktivovat vlastní uživatelský účet");
        }

        User user = userMapper.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Uživatel", id));

        boolean isAdmin = user.getRoles() != null
                && user.getRoles().stream().anyMatch(r -> ROLE_ADMIN.equals(r.getName()));

        if (isAdmin && userMapper.countEnabledByRoleExcluding(ROLE_ADMIN, id) == 0) {
            throw new BusinessRuleException(
                    "CANNOT_DEACTIVATE_LAST_ADMIN",
                    "Nelze deaktivovat posledního uživatele s rolí administrátora");
        }

        int affectedRows = userMapper.deactivate(id);
        return verifyAndFetchAfterStatusChange(id, affectedRows);
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException když je {@code id} null
     */
    @Override
    public UserDto.DetailResponse activate(Long id) {
        if (id == null) {
            throw new IllegalArgumentException("id nesmí být null");
        }
        int affectedRows = userMapper.activate(id);
        return verifyAndFetchAfterStatusChange(id, affectedRows);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Zároveň odemkne účet a vynuluje čítač neúspěšných přihlášení
     * (V3b, analyza-2026-07) — adminský reset odemyká okamžitě, dřív než časová
     * expirace zámku doplněná ve V64.
     *
     * <p><strong>Zneplatnění sessions (audit KN-6):</strong> odvolají se všechny refresh
     * tokeny uživatele, zrcadlo {@code AuthenticationService.changePassword}. Dokud to
     * chybělo, adminský reset hesla kompromitovaného účtu nic neodřízl — držitel
     * ukradeného refresh tokenu dál razil access tokeny po zbylých 7 dní. {@code api.md}
     * to slibovalo dřív, než to kód uměl.
     *
     * @throws IllegalArgumentException když je {@code id} null
     */
    @Override
    @Transactional
    public UserDto.DetailResponse resetPassword(Long id, UserDto.ResetPasswordRequest request) {
        if (id == null) {
            throw new IllegalArgumentException("id nesmí být null");
        }
        int affectedRows = userMapper.updatePasswordHash(id, passwordEncoder.encode(request.getNewPassword()));
        userMapper.unlockAccount(id);
        refreshTokenMapper.revokeAllByUserId(id);
        return verifyAndFetchAfterStatusChange(id, affectedRows);
    }

    // =========================================================================
    // Privátní pomocné metody
    // =========================================================================

    private UserDto.DetailResponse verifyAndFetchAfterStatusChange(Long id, int affectedRows) {
        if (affectedRows == 0) {
            throw new ResourceNotFoundException("Uživatel", id);
        }
        return fetchOrFail(id);
    }

    private UserDto.DetailResponse fetchOrFail(Long id) {
        return userMapper.findById(id)
                .map(userConverter::toDetailResponse)
                .orElseThrow(() -> new ResourceNotFoundException("Uživatel", id));
    }
}
