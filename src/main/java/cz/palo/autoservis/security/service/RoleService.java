package cz.palo.autoservis.security.service;

import cz.palo.autoservis.config.security.SecurityConfig;
import cz.palo.autoservis.security.mapper.RoleMapper;
import cz.palo.autoservis.model.dto.RoleDto;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Služba pro čtení systémových rolí ze {@code security.roles}.
 *
 * <p>Role slouží jako granted authorities ve Spring Security a vystavují se
 * číselníkovým API pro rozbalovací nabídky na frontendu.
 */
@Service
@RequiredArgsConstructor
public class RoleService {

    /**
     * Role, které má smysl komukoli přiřadit — odvozené z {@link SecurityConfig#WORKING_ROLES},
     * aby se nabídka nemohla rozejít s tím, koho baseline {@code /api/**} skutečně pustí dovnitř.
     * V DB je název i s prefixem {@code ROLE_}, v konfiguraci bez něj.
     */
    private static final Set<String> ASSIGNABLE_ROLE_NAMES = Arrays.stream(SecurityConfig.WORKING_ROLES)
            .map(role -> "ROLE_" + role)
            .collect(Collectors.toUnmodifiableSet());

    private final RoleMapper roleMapper;

    /**
     * Vrací role, které má smysl přiřadit uživatelskému účtu.
     *
     * <p>Záměrně <strong>ne</strong> všechny řádky {@code security.roles}: tabulka drží
     * i {@code ROLE_CUSTOMER} (zákaznický portál, který zatím neexistuje)
     * a {@code ROLE_READONLY}, a baseline pravidlo v {@link SecurityConfig} obě odřízne
     * od {@code /api/**}. Jejich nabízení ve formuláři účtu vytvářelo uživatele, který se
     * přihlásil a pak dostal 403 na každé obrazovce — k nerozeznání od rozbité aplikace
     * (audit KN-22). Řádky v databázi zůstávají; jen se skrývají z číselníku.
     *
     * @return role povolené baselinem {@code /api/**}, jako DTO
     */
    public List<RoleDto> getAssignable() {
        return roleMapper.getAll().stream()
                .filter(role -> ASSIGNABLE_ROLE_NAMES.contains(role.getName()))
                .map(RoleDto::new)
                .toList();
    }
}
