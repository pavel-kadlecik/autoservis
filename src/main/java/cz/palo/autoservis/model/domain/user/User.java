package cz.palo.autoservis.model.domain.user;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

/**
 * Doménový objekt uživatelského účtu — mapuje se na {@code security.users}.
 *
 * <p>Čisté POJO bez JPA anotací a závislostí na Springu.
 * Na kontrakt {@link org.springframework.security.core.userdetails.UserDetails}
 * Spring Security ho adaptuje {@link cz.palo.autoservis.security.model.domain.AppUserDetails}.
 *
 * <p>Obsahuje jen autentizační data — žádná business (zákaznická) data.
 * Kolekci {@code roles} načítá {@code UserMapper} předem jedním JOIN dotazem.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class User {

    private Long id;
    private String username;
    private String email;
    private String passwordHash;
    private boolean enabled;
    private boolean accountNonExpired;
    private boolean accountNonLocked;
    private boolean credentialsNonExpired;
    private int failedLoginAttempts;

    /**
     * Kdy byl účet uzamčen po překročení počtu neúspěšných přihlášení ({@code null} = není zamčeno).
     * Od této hodnoty se počítá expirace zámku — viz {@code lockout.duration} a V64 (audit KN-5).
     * Samotné vyhodnocení lhůty dělá DB v {@code UserMapper.unlockIfLockExpired}, aby se nemíchaly
     * hodiny aplikace a databáze; tady je hodnota kvůli úplnosti projekce tabulky.
     */
    private OffsetDateTime lockedAt;

    private OffsetDateTime lastLoginAt;
    private OffsetDateTime passwordChangedAt;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

    /** Role načtené JOINem v {@code UserMapper} — staví se z nich Spring Security authorities. */
    private java.util.List<Role> roles;
}
