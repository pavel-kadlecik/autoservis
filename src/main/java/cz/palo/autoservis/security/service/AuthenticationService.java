package cz.palo.autoservis.security.service;

import cz.palo.autoservis.exception.BusinessRuleException;
import cz.palo.autoservis.exception.InvalidRefreshTokenException;
import cz.palo.autoservis.exception.ResourceNotFoundException;
import cz.palo.autoservis.model.domain.user.User;
import cz.palo.autoservis.security.model.domain.RefreshToken;
import cz.palo.autoservis.security.mapper.BlacklistMapper;
import cz.palo.autoservis.security.mapper.RefreshTokenMapper;
import cz.palo.autoservis.security.mapper.UserMapper;
import cz.palo.autoservis.security.model.dto.ChangePasswordRequest;
import cz.palo.autoservis.security.model.dto.LoginRequest;
import cz.palo.autoservis.security.model.dto.RefreshRequest;
import cz.palo.autoservis.security.model.dto.TokenResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Služba odpovědná za autentizaci uživatele — přihlášení, obnovu tokenů a odhlášení.
 *
 * <h3>Tok tokenů:</h3>
 * <ol>
 *   <li>Přihlášení → vygenerovat access token + refresh token,
 *       refresh token uložit do databáze, oba vrátit klientovi</li>
 *   <li>API request → klient posílá access token v HTTP-only cookie</li>
 *   <li>Access token expiruje → klient volá {@code POST /auth/refresh}
 *       s refresh token cookie → dostane nový pár tokenů (rotace refresh tokenu)</li>
 *   <li>Odhlášení → refresh token se odvolá v databázi, access token se přidá
 *       na blacklist, kde zůstává do své přirozené expirace JWT</li>
 * </ol>
 *
 * <h3>Rotace refresh tokenu:</h3>
 * <p>Každá obnova zneplatní starý refresh token a vydá nový. Když útočník
 * ukradne refresh token a zkusí ho použít poté, co už proběhla legitimní obnova,
 * server rozpozná pokus o opakované použití odvolaného tokenu a preventivně
 * odvolá všechny session daného uživatele.
 */
@Service
@RequiredArgsConstructor
public class AuthenticationService {

    private final UserMapper userMapper;
    private final BlacklistMapper blacklistMapper;
    private final RefreshTokenMapper refreshTokenMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuthenticationManager authenticationManager;
    private final UserDetailsService userDetailsService;
    private final LoginAttemptService loginAttemptService;

    /**
     * Autentizuje uživatele podle přihlašovacích údajů a vydá pár tokenů.
     *
     * <p>Spring {@link AuthenticationManager} ověří jméno a heslo proti databázi.
     * Neplatné údaje vyhodí {@link org.springframework.security.core.AuthenticationException},
     * kterou {@link cz.palo.autoservis.exception.GlobalExceptionHandler} mapuje na HTTP 401.
     *
     * <p><strong>Zamykání účtu (V3b, analyza-2026-07):</strong> špatné heslo zaznamená
     * neúspěšný pokus přes {@link LoginAttemptService#recordFailure} — při dosažení
     * {@code MAX_FAILED_ATTEMPTS} účet zamkne — a pak výjimku vyhodí znovu, takže volající
     * pořád vidí obvyklou {@code BadCredentialsException}. Když je účet už zamčený,
     * {@code authenticationManager.authenticate} vyhodí {@code LockedException} přímo
     * (Spring Security kontroluje stav účtu před heslem); ta se tady nechytá a propaguje
     * se do {@link cz.palo.autoservis.exception.GlobalExceptionHandler} jako 401
     * {@code ACCOUNT_LOCKED}. Úspěšné přihlášení vynuluje čítač přes
     * {@link LoginAttemptService#recordSuccess}.
     *
     * <p><strong>Vypršení zámku (V64, audit KN-5):</strong> před autentizací dáme vypršelému
     * zámku šanci na uvolnění ({@link LoginAttemptService#releaseExpiredLock}). Musí to být
     * první krok — Spring Security vyhodnocuje stav účtu před heslem, takže stále zamčený
     * účet se ke kontrole hesla vůbec nedostane. Do V64 byl zámek trvalý a rušil ho jen
     * adminský reset hesla, což umožňovalo komukoli deseti požadavky na tento endpoint
     * vyřadit jediný produkční administrátorský účet.
     *
     * @param request přihlašovací údaje (jméno, heslo)
     * @return pár tokenů (access token + refresh token)
     */
    @Transactional
    public TokenResponse login(LoginRequest request) {
        loginAttemptService.releaseExpiredLock(request.username());
        try {
            authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(
                            request.username(),
                            request.password()
                    )
            );
        } catch (BadCredentialsException e) {
            loginAttemptService.recordFailure(request.username());
            throw e;
        }

        UserDetails userDetails = userDetailsService.loadUserByUsername(request.username());
        User user = userMapper.findByUsername(request.username())
                .orElseThrow(() -> new IllegalStateException("Uživatel nenalezen po úspěšné autentizaci."));

        loginAttemptService.recordSuccess(user.getId());

        return issueTokenPair(userDetails, user.getId());
    }

    /**
     * Vydá nový pár tokenů na základě platného refresh tokenu (rotace refresh tokenu).
     *
     * <p>Kroky ověření:
     * <ol>
     *   <li>Dohledat refresh token v databázi</li>
     *   <li>Zkontrolovat odvolání — odvolaný token značí možný útok opakovaným použitím;
     *       preventivně se odvolají všechny session uživatele</li>
     *   <li>Zkontrolovat expiraci</li>
     *   <li>Odvolat starý refresh token a vydat nový pár</li>
     * </ol>
     *
     * @param request obsahuje refresh token zaslaný klientem
     * @return nový pár tokenů (access token + refresh token)
     * @throws InvalidRefreshTokenException když token chybí, je odvolaný nebo expirovaný
     */
    @Transactional
    public TokenResponse refresh(RefreshRequest request) {
        RefreshToken stored = refreshTokenMapper.findByToken(TokenHasher.sha256Hex(request.refreshToken()))
                .orElseThrow(() -> new InvalidRefreshTokenException("Refresh token nebyl nalezen."));

        if (stored.isRevoked()) {
            refreshTokenMapper.revokeAllByUserId(stored.getUserId());
            throw new InvalidRefreshTokenException(
                    "Refresh token byl již použit nebo odvolán. Z bezpečnostních důvodů byly ukončeny všechny sessions."
            );
        }

        if (stored.getExpiresAt().isBefore(LocalDateTime.now())) {
            refreshTokenMapper.revokeByToken(stored.getToken());
            throw new InvalidRefreshTokenException("Refresh token vypršel. Přihlaste se prosím znovu.");
        }

        refreshTokenMapper.revokeByToken(stored.getToken());

        UserDetails userDetails = userDetailsService.loadUserByUsername(
                getUsernameByUserId(stored.getUserId())
        );

        return issueTokenPair(userDetails, stored.getUserId());
    }

    /**
     * Odhlásí uživatele z aktuální session.
     *
     * <p>Access token se přidá na blacklist a zůstává neplatný do své přirozené
     * expirace JWT. Refresh token se odvolá v databázi.
     *
     * <p><strong>V4 (analyza-2026-07):</strong> perzistuje se jen SHA-256 hash access
     * tokenu (viz {@link TokenHasher}) — nikdy surový JWT — takže uniklá záloha DB
     * nevydá použitelné bearer tokeny.
     *
     * @param accessToken  JWT access token z cookie
     * @param refreshToken refresh token z cookie (může být {@code null})
     */
    @Transactional
    public void logout(String accessToken, String refreshToken) {
        if (accessToken != null && !accessToken.isBlank()) {
            blacklistMapper.save(TokenHasher.sha256Hex(accessToken));
        }
        if (refreshToken != null && !refreshToken.isBlank()) {
            refreshTokenMapper.revokeByToken(TokenHasher.sha256Hex(refreshToken));
        }
    }

    /**
     * Změní heslo daného uživatele (self-service).
     *
     * <p>Na rozdíl od adminem spuštěného resetu ({@code UserService.resetPassword})
     * vyžaduje, aby volající prokázal znalost aktuálního hesla.
     *
     * <p><strong>Zneplatnění sessions (audit K-6):</strong> po změně hesla se odvolají
     * všechny refresh tokeny uživatele. Změna hesla je standardní způsob, jak odříznout
     * útočníka s živou session — bez toho by ukradený refresh token dál razil access
     * tokeny až do své přirozené sedmidenní expirace.
     *
     * @param userId  ID uživatele měnícího si vlastní heslo
     * @param request aktuální a nové heslo
     * @throws ResourceNotFoundException když uživatel neexistuje
     * @throws BusinessRuleException     když aktuální heslo nesedí
     */
    @Transactional
    public void changePassword(Long userId, ChangePasswordRequest request) {
        User user = userMapper.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Uživatel", userId));

        if (!passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
            throw new BusinessRuleException(
                    "INVALID_CURRENT_PASSWORD",
                    "currentPassword",
                    "Současné heslo není správné",
                    Map.of());
        }

        userMapper.updatePasswordHash(userId, passwordEncoder.encode(request.newPassword()));
        refreshTokenMapper.revokeAllByUserId(userId);
    }

    // =========================================================================
    // Privátní pomocné metody
    // =========================================================================

    /**
     * Vygeneruje pár access token + refresh token a refresh token uloží.
     *
     * <p>Centrální metoda volaná z {@link #login} a {@link #refresh},
     * aby bylo vydávání tokenů konzistentní.
     *
     * @param userDetails autentizovaný uživatel
     * @param userId      databázové ID uživatele
     * @return pár tokenů připravený k odeslání klientovi
     */
    private TokenResponse issueTokenPair(UserDetails userDetails, Long userId) {
        String accessToken = jwtService.generateToken(userDetails);
        String refreshTokenValue = jwtService.generateRefreshToken();

        RefreshToken refreshToken = RefreshToken.builder()
                .id(UUID.randomUUID().toString())
                // Do DB jen SHA-256 hash (audit K-7) — stejně jako blacklist (V4). Únik zálohy DB
                // tak nevydá použitelný refresh token; klientovi se vrací syrová hodnota níže.
                .token(TokenHasher.sha256Hex(refreshTokenValue))
                .userId(userId)
                .expiresAt(jwtService.generateRefreshTokenExpiry().toInstant()
                        .atZone(java.time.ZoneId.systemDefault())
                        .toLocalDateTime())
                .revoked(false)
                .createdAt(LocalDateTime.now())
                .build();

        refreshTokenMapper.save(refreshToken);

        return new TokenResponse(accessToken, refreshTokenValue);
    }

    private String getUsernameByUserId(Long userId) {
        return userMapper.findById(userId)
                .map(User::getUsername)
                .orElseThrow(() -> new IllegalStateException("Uživatel s ID " + userId + " neexistuje."));
    }
}
