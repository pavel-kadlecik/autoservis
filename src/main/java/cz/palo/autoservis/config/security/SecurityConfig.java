package cz.palo.autoservis.config.security;

import cz.palo.autoservis.security.filter.JwtAuthenticationFilter;
import cz.palo.autoservis.security.filter.SecurityProblemWriter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * Konfigurace Spring Security.
 *
 * <p>Aplikace používá bezstavovou JWT autentizaci přes HTTP-only cookies.
 * Sessions jsou vypnuté; každý request autentizuje nezávisle
 * {@link JwtAuthenticationFilter}.
 *
 * <h3>Autorizační pravidla:</h3>
 * <ul>
 *   <li>Auth endpointy ({@code /auth/login}, {@code /auth/refresh}) jsou veřejné</li>
 *   <li>Statické soubory frontendu jsou veřejné</li>
 *   <li>Účtové endpointy ({@code /auth/me}, {@code /auth/logout}, {@code /auth/change-password})
 *       vyžadují jen přihlášení</li>
 *   <li>Zbytek {@code /api/**} je vyhrazen pracovním rolím (ADMIN/MANAGER/MECHANIC);
 *       jemnější omezení (vedení-only operace) řeší {@code @PreAuthorize} na controllerech —
 *       matice role × operace z auditu 2026-07-24 (E7/R-6)</li>
 * </ul>
 *
 * <h3>Bezpečnostní hlavičky:</h3>
 * <p>frame-options DENY, nosniff, referrer SAME_ORIGIN, HSTS a CSP (viz filterChain).
 *
 * <h3>CORS:</h3>
 * <p>Povolené originy se čtou z konfigurace ({@code cors.allowed-origins}); dev = Vite,
 * produkci nastav v {@code application-prod.yaml}. Credentials (cookies) jsou povoleny.
 */
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
@EnableMethodSecurity
public class SecurityConfig {

    /**
     * Pracovní role, kterým baseline {@code /api/**} povoluje přístup — bez prefixu
     * {@code ROLE_}, protože {@code hasAnyRole} si ho doplňuje sám.
     *
     * <p><strong>Jediný zdroj pravdy.</strong> Kromě pravidla níže z něj vychází i
     * {@code RoleService.getAssignable()}, aby se seznam nabízený při zakládání účtu nemohl
     * rozejít s tím, koho sem baseline skutečně pustí. Role mimo tento výčet
     * ({@code ROLE_CUSTOMER}, {@code ROLE_READONLY}) v DB zůstávají, ale účet s nimi by
     * dostal 403 na každé obrazovce (audit KN-22).
     */
    public static final String[] WORKING_ROLES = {"ADMIN", "MANAGER", "MECHANIC"};

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final SecurityProblemWriter securityProblemWriter;
    /** CORS originy z konfigurace ({@code cors.allowed-origins}) — přes {@code @ConfigurationProperties}
     *  (ne {@code @Value}, které YAML seznam navázat neumí — viz {@link CorsProperties}). */
    private final CorsProperties corsProperties;

    /**
     * Sestavuje hlavní security filter chain.
     *
     * @param http konfigurační DSL Spring Security pro HTTP
     * @return nakonfigurovaný {@link SecurityFilterChain}
     */
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, authException) ->
                                securityProblemWriter.writeUnauthorized(
                                        request, response, "UNAUTHORIZED", "Přihlášení je vyžadováno."))
                )
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(org.springframework.http.HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers(
                                "/api/*/auth/login",
                                "/api/*/auth/refresh"
                        ).permitAll()
                        .requestMatchers("/", "/index.html", "/assets/**", "/favicon.ico").permitAll()
                        // Účtové endpointy (me/logout/change-password) smí kdokoli přihlášený.
                        .requestMatchers("/api/*/auth/**").authenticated()
                        // Zbytek API je vyhrazen pracovním rolím. ROLE_CUSTOMER (zákaznický portál,
                        // který zatím neexistuje) sem nesmí — jinak by přes plošné authenticated()
                        // viděl a editoval celou firmu (audit K-10 / R-4).
                        .requestMatchers("/api/**").hasAnyRole(WORKING_ROLES)
                        .anyRequest().permitAll()
                )
                .sessionManagement(sess -> sess
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .headers(headers -> headers
                        // Clickjacking: web nesmí být vložen do cizího rámce.
                        .frameOptions(HeadersConfigurer.FrameOptionsConfig::deny)
                        // Zabrání odhadování MIME typu (X-Content-Type-Options: nosniff).
                        .contentTypeOptions(Customizer.withDefaults())
                        // Referrer neúniká na cizí origin.
                        .referrerPolicy(ref -> ref.policy(
                                ReferrerPolicy.SAME_ORIGIN))
                        // HSTS — vynutí HTTPS (uplatní se jen na zabezpečeném spojení).
                        .httpStrictTransportSecurity(hsts -> hsts
                                .includeSubDomains(true)
                                .maxAgeInSeconds(31536000))
                        // CSP: obsah jen z vlastního originu. 'unsafe-inline' u stylů/skriptů kvůli
                        // Bootstrap/MUI a Vite buildu; data: pro obrázky (logo, QR). V dev běhu FE žije
                        // na Vite (:5173) a tato hlavička se ho netýká — platí pro obsah servírovaný BE.
                        .contentSecurityPolicy(csp -> csp.policyDirectives(
                                "default-src 'self'; "
                                        + "script-src 'self' 'unsafe-inline'; "
                                        + "style-src 'self' 'unsafe-inline'; "
                                        + "img-src 'self' data:; "
                                        + "font-src 'self' data:; "
                                        + "connect-src 'self'; "
                                        + "frame-ancestors 'none'; "
                                        + "base-uri 'self'; "
                                        + "form-action 'self'")))
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    /**
     * Vystavuje {@link AuthenticationManager} jako Spring bean.
     * Vyžaduje ho {@link cz.palo.autoservis.security.service.AuthenticationService}.
     *
     * @param config autentizační konfigurace Springu
     * @return výchozí authentication manager
     */
    @Bean
    public AuthenticationManager authenticationManager(
            AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    /**
     * Bean pro hashování hesel — BCrypt s výchozí silou (10 rund).
     *
     * @return BCrypt password encoder
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * Konfigurace CORS pro celé API.
     *
     * <p>Povolené originy jdou z konfigurace ({@link CorsProperties},
     * {@code cors.allowed-origins}) — dev = Vite server, produkce = doména FE
     * v {@code application-prod.yaml}. Credentials (cookies) jsou povoleny.
     *
     * @return zdroj CORS konfigurace aplikovaný na všechny endpointy
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(corsProperties.getAllowedOrigins());
        configuration.setAllowedMethods(List.of(
                "GET", "POST", "PUT", "DELETE", "OPTIONS"
        ));
        configuration.setAllowedHeaders(List.of(
                "Content-Type",
                "Cache-Control"
        ));
        configuration.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
