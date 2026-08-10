package cz.palo.autoservis.controller;

import cz.palo.autoservis.model.dto.pagination.PagedResponse;
import cz.palo.autoservis.model.dto.user.UserDto;
import cz.palo.autoservis.model.dto.user.UserSearchParams;
import cz.palo.autoservis.security.model.domain.AppUserDetails;
import cz.palo.autoservis.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;

/**
 * REST controller administrátorské správy uživatelských účtů.
 *
 * <p>Base path: {@code /api/{version}/users}
 *
 * <p>Vyhrazeno administrátorům — jediný ADMIN-only controller v API, protože správa účtů
 * a rolí nesmí být dostupná běžnému personálu. Účetní a správní operace jsou vyhrazeny
 * vedení ({@code hasAnyRole('ADMIN','MANAGER')}) na šestnácti místech; tvrzení, že tohle byl
 * „druhý endpoint s rolovou autorizací", platilo před E7 a v dokumentaci zůstalo
 * (audit 10/A-6). Úplný seznam: {@code docs/api.md} §Autorizace rolí.
 */
@RestController
@RequestMapping("/api/{version}/users")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class UserController {

    private final UserService userService;

    /**
     * Vrací úplný detail uživatele podle ID.
     *
     * @param id ID uživatele
     * @return 200 OK s detailem uživatele
     */
    @GetMapping("/{id}")
    public ResponseEntity<UserDto.DetailResponse> getById(@PathVariable Long id) {
        return ResponseEntity.ok(userService.getById(id));
    }

    /**
     * Vrací stránkovaný seznam uživatelů odpovídajících zadaným parametrům hledání.
     *
     * @param params parametry hledání a stránkování
     * @return 200 OK se stránkovaným seznamem uživatelů
     */
    @GetMapping
    public ResponseEntity<PagedResponse<UserDto.ListResponse>> getPage(UserSearchParams params) {
        return ResponseEntity.ok(userService.getPage(params));
    }

    /**
     * Založí nový uživatelský účet.
     *
     * @param createRequest validované tělo requestu
     * @param currentUser   právě přihlášený administrátor
     * @return 201 Created s {@code Location} hlavičkou a detailem založeného uživatele
     */
    @PostMapping
    public ResponseEntity<UserDto.DetailResponse> create(
            @RequestBody @Valid UserDto.CreateRequest createRequest,
            @AuthenticationPrincipal AppUserDetails currentUser) {
        UserDto.DetailResponse created = userService.create(createRequest, currentUser.getUserId());
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(created.getId())
                .toUri();
        return ResponseEntity.created(location).body(created);
    }

    /**
     * Aktualizuje e-mail a přiřazení rolí existujícího uživatele.
     *
     * @param id            ID uživatele
     * @param updateRequest validované tělo requestu
     * @param currentUser   právě přihlášený administrátor
     * @return 200 OK s detailem aktualizovaného uživatele
     */
    @PutMapping("/{id}")
    public ResponseEntity<UserDto.DetailResponse> update(
            @PathVariable Long id,
            @RequestBody @Valid UserDto.UpdateRequest updateRequest,
            @AuthenticationPrincipal AppUserDetails currentUser) {
        return ResponseEntity.ok(userService.update(id, updateRequest, currentUser.getUserId()));
    }

    /**
     * Deaktivuje uživatelský účet (soft delete). Odmítne deaktivaci vlastního účtu
     * volajícího i posledního aktivního administrátora.
     *
     * @param id          ID uživatele
     * @param currentUser právě přihlášený administrátor
     * @return 200 OK s detailem aktualizovaného uživatele
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<UserDto.DetailResponse> deactivate(
            @PathVariable Long id,
            @AuthenticationPrincipal AppUserDetails currentUser) {
        return ResponseEntity.ok(userService.deactivate(id, currentUser.getUserId()));
    }

    /**
     * Znovu aktivuje dříve deaktivovaný uživatelský účet.
     *
     * @param id ID uživatele
     * @return 200 OK s detailem aktualizovaného uživatele
     */
    @PostMapping("/{id}/activate")
    public ResponseEntity<UserDto.DetailResponse> activate(@PathVariable Long id) {
        return ResponseEntity.ok(userService.activate(id));
    }

    /**
     * Nastaví uživateli nové heslo (admin reset — aktuální heslo se nevyžaduje).
     *
     * @param id      ID uživatele
     * @param request nové heslo
     * @return 200 OK s detailem aktualizovaného uživatele
     */
    @PostMapping("/{id}/reset-password")
    public ResponseEntity<UserDto.DetailResponse> resetPassword(
            @PathVariable Long id,
            @RequestBody @Valid UserDto.ResetPasswordRequest request) {
        return ResponseEntity.ok(userService.resetPassword(id, request));
    }
}
