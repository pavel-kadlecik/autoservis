package cz.palo.autoservis.security.service;

import cz.palo.autoservis.security.model.domain.AppUserDetails;
import cz.palo.autoservis.security.mapper.UserMapper;
import cz.palo.autoservis.model.domain.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implementace {@link UserDetailsService} pro Spring Security.
 *
 * <p>Načítá data uživatele z {@code security.users}, {@code security.user_roles}
 * a {@code security.roles} jediným JOIN dotazem přes MyBatis. Výsledek se balí
 * do {@link AppUserDetails}, který adaptuje doménového {@link User} na kontrakt
 * Spring Security.
 */
@Service
@RequiredArgsConstructor
public class AppUserDetailsService implements UserDetailsService {

    private final UserMapper userMapper;

    /**
     * Načte uživatele podle jména a zabalí ho do {@link AppUserDetails}.
     *
     * @param username hledané přihlašovací jméno
     * @return naplněná instance {@link UserDetails}
     * @throws UsernameNotFoundException když uživatel s daným jménem neexistuje
     */
    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        User user = userMapper.findByUsername(username)
                .orElseThrow(() ->
                        new UsernameNotFoundException("Uživatel nenalezen: " + username));

        return new AppUserDetails(user);
    }
}
