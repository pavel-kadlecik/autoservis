package cz.palo.autoservis.model.domain.user;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Doménový objekt uživatelské role — mapuje se na {@code security.roles}.
 *
 * <p>Názvy rolí drží konvenci Spring Security: {@code ROLE_ADMIN},
 * {@code ROLE_MANAGER}, {@code ROLE_MECHANIC}, {@code ROLE_CUSTOMER}.
 * Mapují se přímo na {@link org.springframework.security.core.GrantedAuthority}
 * v {@link cz.palo.autoservis.security.model.domain.AppUserDetails}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Role {

    private int id;
    private String name;
    private String description;
}
