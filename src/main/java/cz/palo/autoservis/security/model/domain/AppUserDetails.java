package cz.palo.autoservis.security.model.domain;

import cz.palo.autoservis.model.domain.user.User;
import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Adaptér doménového {@link User} na kontrakt {@link UserDetails} Spring Security.
 *
 * <p>Nese navíc pole {@code userId} (primární klíč z databáze), aby se controllery
 * dostaly k ID přihlášeného uživatele přímo:
 * <pre>{@code
 * @GetMapping("/me")
 * public ResponseEntity<?> me(@AuthenticationPrincipal AppUserDetails user) {
 *     Long id = user.getUserId();
 * }
 * }</pre>
 */
@Getter
public class AppUserDetails implements UserDetails {

    private final Long userId;
    private final String username;
    private final String email;
    private final String passwordHash;
    private final boolean enabled;
    private final boolean accountNonExpired;
    private final boolean accountNonLocked;
    private final boolean credentialsNonExpired;
    private final List<GrantedAuthority> authorities;

    /**
     * Sestaví {@link AppUserDetails} z plně načteného doménového {@link User}.
     *
     * @param user doménový uživatel s předem načtenými rolemi
     */
    public AppUserDetails(User user) {
        this.userId                = user.getId();
        this.username              = user.getUsername();
        this.email                 = user.getEmail();
        this.passwordHash          = user.getPasswordHash();
        this.enabled               = user.isEnabled();
        this.accountNonExpired     = user.isAccountNonExpired();
        this.accountNonLocked      = user.isAccountNonLocked();
        this.credentialsNonExpired = user.isCredentialsNonExpired();

        this.authorities = user.getRoles() == null
                ? Collections.emptyList()
                : user.getRoles().stream()
                        .map(r -> new SimpleGrantedAuthority(r.getName()))
                        .collect(Collectors.toList());
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return authorities;
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }
}
