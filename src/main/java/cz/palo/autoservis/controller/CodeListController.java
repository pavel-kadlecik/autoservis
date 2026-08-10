package cz.palo.autoservis.controller;

import cz.palo.autoservis.model.dto.RoleDto;
import cz.palo.autoservis.security.service.RoleService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * REST controller statických číselníků pro frontend.
 *
 * <p>Base path: {@code /api/{version}/code-lists}
 *
 * <p>Číselníky jsou referenční data jen ke čtení (role, stavy, enumy),
 * kterými frontend plní rozbalovací seznamy a popisky.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/{version}/code-lists")
public class CodeListController {

    private final RoleService roleService;

    /**
     * Vrací uživatelské role, které lze přiřadit účtu.
     *
     * <p>Ne každý řádek {@code security.roles} — role, které baseline {@code /api/**} odřízne,
     * jsou odfiltrované, viz {@link RoleService#getAssignable()} (audit KN-22).
     *
     * @return 200 OK se seznamem přiřaditelných rolí
     */
    @GetMapping("/roles")
    public ResponseEntity<List<RoleDto>> getRoles() {
        return ResponseEntity.ok(roleService.getAssignable());
    }
}
