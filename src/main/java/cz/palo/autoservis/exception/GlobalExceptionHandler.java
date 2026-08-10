package cz.palo.autoservis.exception;

import cz.palo.autoservis.exception.dto.ErrorDetail;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.LockedException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;
import java.util.*;

/**
 * Centrální obsluha výjimek REST API.
 *
 * <p>Všechny chybové odpovědi mají formát {@link ProblemDetail} dle RFC 9457,
 * rozšířený o pole {@code errors} se záznamy {@link ErrorDetail}.
 * {@code Content-Type} odpovědi je {@code application/problem+json}.
 *
 * <h3>Mapování výjimek na HTTP stavy:</h3>
 * <table>
 *   <tr><th>Výjimka</th><th>HTTP</th><th>Kdy</th></tr>
 *   <tr><td>{@link MethodArgumentNotValidException}</td><td>400</td><td>selhala Bean Validation</td></tr>
 *   <tr><td>{@link IllegalArgumentException}</td><td>400</td><td>neplatný argument service metody (např. null ID) (TD-20)</td></tr>
 *   <tr><td>{@code MethodArgumentTypeMismatchException}</td><td>400</td><td>nepřevoditelný path/query parametr, např. nečíselné ID (TD-52)</td></tr>
 *   <tr><td>{@link HttpMessageNotReadableException}</td><td>400</td><td>neparsovatelné tělo requestu / neplatný enum (E6.1/S-5)</td></tr>
 *   <tr><td>{@code HandlerMethodValidationException}</td><td>400</td><td>validace query/path parametrů přes {@code @Validated}</td></tr>
 *   <tr><td>{@link BadCredentialsException}</td><td>401</td><td>nesprávné přihlašovací údaje</td></tr>
 *   <tr><td>{@link LockedException}</td><td>401</td><td>účet zamčen po opakovaných neúspěšných přihlášeních (V3b)</td></tr>
 *   <tr><td>{@link InvalidRefreshTokenException}</td><td>401</td><td>neplatný nebo expirovaný refresh token</td></tr>
 *   <tr><td>{@code UsernameNotFoundException}</td><td>401</td><td>účet deaktivován, token ještě platný (KN-18)</td></tr>
 *   <tr><td>{@link AccessDeniedException}</td><td>403</td><td>nedostatečná oprávnění</td></tr>
 *   <tr><td>{@link ResourceNotFoundException}</td><td>404</td><td>záznam v databázi nenalezen</td></tr>
 *   <tr><td>{@code NoResourceFoundException}</td><td>404</td><td>neznámá cesta bez handleru (TD-61)</td></tr>
 *   <tr><td>{@link HttpRequestMethodNotSupportedException}</td><td>405</td><td>nepodporovaná HTTP metoda na existující cestě</td></tr>
 *   <tr><td>{@link UserAlreadyExistsException}</td><td>409</td><td>duplicitní uživatelský účet</td></tr>
 *   <tr><td>{@link ConflictException}</td><td>409</td><td>požadavek koliduje se stavem zdroje</td></tr>
 *   <tr><td>{@link BusinessRuleException}</td><td>422</td><td>porušení business pravidla</td></tr>
 *   <tr><td>{@link DataIntegrityViolationException}</td><td>422</td><td>porušení DB constraintu</td></tr>
 *   <tr><td>{@link Exception}</td><td>500</td><td>neočekávaná chyba (catch-all)</td></tr>
 *   <tr><td>{@link RegistryUnavailableException}</td><td>503</td><td>nedostupný externí registr vozidel</td></tr>
 *   <tr><td>{@link AresUnavailableException}</td><td>503</td><td>nedostupný ARES</td></tr>
 *   <tr><td>{@link DocumentExtractionException}</td><td>503</td><td>selhání AI extrakce dokladu (E6.3)</td></tr>
 * </table>
 *
 * <h3>Chybové kódy:</h3>
 * <p>Každý {@link ErrorDetail} nese {@code code} — strojově čitelný, jazykově nezávislý
 * identifikátor, který klient používá jako překladový klíč. Standardní Bean Validation
 * anotace se mapují přes {@link #CONSTRAINT_CODE_MAP}. Vlastní validátory uvedené
 * v {@link #CUSTOM_VALIDATOR_ANNOTATIONS} dodávají kód přímo ve své violation šabloně.
 *
 * @see ErrorDetail
 * @see ProblemDetail
 */
@RestControllerAdvice
@RequiredArgsConstructor
@Slf4j
public class GlobalExceptionHandler {

    private final MessageSource messageSource;

    /**
     * Mapuje názvy standardních Bean Validation anotací na strojové chybové kódy.
     * Klíčem je prostý název anotace (poslední prvek {@code FieldError.getCodes()}).
     * Pro nenamapované anotace je fallback {@code "INVALID_VALUE"}.
     */
    private static final Map<String, String> CONSTRAINT_CODE_MAP = Map.ofEntries(
            Map.entry("NotNull",   "REQUIRED"),
            Map.entry("NotBlank",  "REQUIRED"),
            Map.entry("NotEmpty",  "REQUIRED"),
            Map.entry("Size",      "SIZE_EXCEEDED"),
            Map.entry("Email",     "INVALID_EMAIL"),
            Map.entry("Pattern",   "INVALID_PATTERN"),
            Map.entry("Min",       "VALUE_TOO_SMALL"),
            Map.entry("Max",       "VALUE_TOO_LARGE")
    );

    /**
     * Názvy vlastních validačních anotací, jejichž violation šablona obsahuje
     * chybový <em>kód</em> (ne lidský text).
     *
     * <p>U nich se {@code FieldError.getDefaultMessage()} bere jako kód a lidská
     * zpráva se dohledává přes {@link MessageSource}. Každý nový vlastní validátor
     * sem při zavedení přidej.
     *
     * <p><strong>{@code ValidVehicleRequest} (TD-10) záměrně přidán NEBYL</strong>:
     * {@code V5__init_vehicle_schema.sql} nemá na {@code vehicle.vehicles} žádný
     * podmíněný CHECK („pole X povinné, jen když pole Y má hodnotu Z") — všechny
     * tamní CHECKy (formát VIN, rozsah roku, objem, výkon, nájezd) jsou prosté
     * jednopolové rozsahy/vzory, které už pokrývají {@code @Pattern}/{@code @Min}/
     * {@code @Max} na {@code VehicleDto}. Třídní validátor by neměl co vynucovat,
     * proto nevznikl.
     */
    private static final Set<String> CUSTOM_VALIDATOR_ANNOTATIONS = Set.of(
            "ValidCustomerRequest"
    );

    // =========================================================================
    // 400 Bad Request
    // =========================================================================

    /**
     * Obsluhuje selhání Bean Validation — na úrovni polí i třídy.
     *
     * <p>Standardní anotace ({@code @NotNull}, {@code @Size}, …):
     * kód se odvozuje z názvu anotace přes {@link #CONSTRAINT_CODE_MAP};
     * zpráva pochází z atributu {@code message} anotace.
     *
     * <p>Vlastní validátory ({@code @ValidCustomerRequest}):
     * kód pochází z violation šablony;
     * zpráva se dohledává přes {@link MessageSource}.
     *
     * @param ex      validační výjimka se všemi violations
     * @param request HTTP požadavek
     * @return ProblemDetail s HTTP 400 a polem {@code errors}
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException ex,
                                          HttpServletRequest request) {
        List<ErrorDetail> errors = new ArrayList<>();
        Locale locale = LocaleContextHolder.getLocale();

        for (FieldError fieldError : ex.getBindingResult().getFieldErrors()) {
            String constraintName = extractConstraintName(fieldError);
            String code;
            String message;

            if (CUSTOM_VALIDATOR_ANNOTATIONS.contains(constraintName)) {
                code = fieldError.getDefaultMessage();
                message = messageSource.getMessage(code, null, code, locale);
            } else {
                code = CONSTRAINT_CODE_MAP.getOrDefault(constraintName, "INVALID_VALUE");
                message = fieldError.getDefaultMessage();
            }

            errors.add(ErrorDetail.ofField(fieldError.getField(), code, message));
        }

        return buildProblemDetail(HttpStatus.BAD_REQUEST, "Ověření zadaných údajů selhalo", request, errors);
    }

    /**
     * Obsluhuje neplatný argument předaný service metodě — nejčastěji {@code null}
     * identifikátor, který service vrstva odmítá na vlastní hranici (TD-20,
     * plan-oprav.md D2), nezávisle na validaci těla requestu v controlleru.
     * Zprávu píše vyhazující kód, proto se použije přímo jako detail problému
     * i jako zpráva chybového kódu.
     *
     * @param ex      výjimka vyhozená service vrstvou
     * @param request HTTP požadavek
     * @return ProblemDetail s HTTP 400
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleIllegalArgument(IllegalArgumentException ex,
                                               HttpServletRequest request) {
        return buildProblemDetail(
                HttpStatus.BAD_REQUEST,
                ex.getMessage(),
                request,
                List.of(ErrorDetail.ofGlobal("INVALID_ARGUMENT", ex.getMessage())));
    }

    /**
     * Obsluhuje path/query parametr, jehož hodnotu nelze převést na cílový typ —
     * typicky nečíselné ID v URL (např. {@code /vehicles/abc}). Bez tohoto handleru
     * by takový překlep propadl do catch-all a projevil se jako 500 (TD-52). Je to
     * chyba klienta, proto se mapuje na 400 jako ostatní neplatné argumenty.
     *
     * @param ex      výjimka o neshodě typu vzniklá při resolvování argumentů
     * @param request HTTP požadavek
     * @return ProblemDetail s HTTP 400
     */
    @ExceptionHandler(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class)
    public ProblemDetail handleTypeMismatch(
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException ex,
            HttpServletRequest request) {
        String message = "Neplatná hodnota parametru '" + ex.getName() + "'";
        return buildProblemDetail(
                HttpStatus.BAD_REQUEST,
                message,
                request,
                List.of(ErrorDetail.ofGlobal("INVALID_ARGUMENT", message)));
    }

    // =========================================================================
    // 401 Unauthorized
    // =========================================================================

    /**
     * Obsluhuje selhání autentizace — špatné jméno nebo heslo.
     * Záměrně vrací pro oba případy stejnou zprávu, aby nešlo enumerovat uživatele.
     *
     * @param ex      autentizační výjimka Spring Security
     * @param request HTTP požadavek
     * @return ProblemDetail s HTTP 401
     */
    @ExceptionHandler(BadCredentialsException.class)
    public ProblemDetail handleBadCredentials(BadCredentialsException ex,
                                              HttpServletRequest request) {
        return buildProblemDetail(
                HttpStatus.UNAUTHORIZED,
                "Nesprávné uživatelské jméno nebo heslo",
                request,
                List.of(ErrorDetail.ofGlobal("BAD_CREDENTIALS", "Nesprávné uživatelské jméno nebo heslo")));
    }

    /**
     * Obsluhuje přihlášení na zamčený účet (V3b, analyza-2026-07) —
     * {@link cz.palo.autoservis.security.service.LoginAttemptService} zamkne účet
     * po {@code MAX_FAILED_ATTEMPTS} neúspěšných přihlášeních za sebou.
     *
     * <p><strong>Poznámka k enumeraci:</strong> na rozdíl od {@link #handleBadCredentials}
     * tato zpráva existenci účtu potvrzuje — ale až po jeho zamčení, k němuž je potřeba
     * 10 předchozích pokusů s platným jménem. Tento kompromis je přijatý.
     *
     * @param ex      výjimka Spring Security vyhozená při zamčeném účtu
     * @param request HTTP požadavek
     * @return ProblemDetail s HTTP 401
     */
    @ExceptionHandler(LockedException.class)
    public ProblemDetail handleAccountLocked(LockedException ex,
                                             HttpServletRequest request) {
        String message = "Účet je uzamčen po opakovaných neúspěšných přihlášeních. Kontaktujte administrátora.";
        return buildProblemDetail(
                HttpStatus.UNAUTHORIZED,
                message,
                request,
                List.of(ErrorDetail.ofGlobal("ACCOUNT_LOCKED", message)));
    }

    /**
     * Obsluhuje použití neplatného, odvolaného nebo expirovaného refresh tokenu.
     *
     * @param ex      výjimka z auth service
     * @param request HTTP požadavek
     * @return ProblemDetail s HTTP 401
     */
    @ExceptionHandler(InvalidRefreshTokenException.class)
    public ProblemDetail handleInvalidRefreshToken(InvalidRefreshTokenException ex,
                                                   HttpServletRequest request) {
        return buildProblemDetail(
                HttpStatus.UNAUTHORIZED,
                ex.getMessage(),
                request,
                List.of(ErrorDetail.ofGlobal("INVALID_REFRESH_TOKEN", ex.getMessage())));
    }

    /**
     * Obsluhuje požadavek, jehož účet už nelze dohledat — deaktivovaný (či odstraněný)
     * uživatel s kryptograficky stále platným access tokenem (audit KN-18).
     *
     * <p>{@code AppUserDetailsService} hledá uživatele přes {@code findByUsername}, které filtruje
     * {@code enabled = TRUE}, takže deaktivovaný účet vyhodí
     * {@link org.springframework.security.core.userdetails.UsernameNotFoundException}. Bez tohoto
     * handleru propadala do catch-all a vracela <strong>500</strong> — nejviditelněji na
     * {@code /auth/refresh}, který frontend po každé 401 automaticky zkouší. Klient pak viděl
     * chybu serveru místo odhlášení.
     *
     * <p>Stejná situace uvnitř {@code JwtAuthenticationFilter} se řeší zvlášť: filtr běží před
     * {@code DispatcherServlet}, takže {@code @RestControllerAdvice} ji nikdy nevidí.
     *
     * <p><strong>Poznámka k enumeraci:</strong> dostat se k tomuto handleru vyžaduje předložit
     * platně podepsaný token daného uživatele, takže neprozrazuje nic, co by útočník už neměl.
     *
     * @param ex      výjimka z dohledání uživatele
     * @param request HTTP požadavek
     * @return ProblemDetail s HTTP 401
     */
    @ExceptionHandler(org.springframework.security.core.userdetails.UsernameNotFoundException.class)
    public ProblemDetail handleAccountUnavailable(
            org.springframework.security.core.userdetails.UsernameNotFoundException ex,
            HttpServletRequest request) {
        String message = "Účet už není dostupný. Přihlaste se prosím znovu.";
        return buildProblemDetail(
                HttpStatus.UNAUTHORIZED,
                message,
                request,
                List.of(ErrorDetail.ofGlobal("ACCOUNT_UNAVAILABLE", message)));
    }

    // =========================================================================
    // 403 Forbidden
    // =========================================================================

    /**
     * Obsluhuje přístup ke zdroji, na který přihlášený uživatel nemá oprávnění.
     *
     * @param ex      výjimka Spring Security o odepření přístupu
     * @param request HTTP požadavek
     * @return ProblemDetail s HTTP 403
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ProblemDetail handleAccessDenied(AccessDeniedException ex,
                                            HttpServletRequest request) {
        return buildProblemDetail(
                HttpStatus.FORBIDDEN,
                "Nemáte dostatečná oprávnění pro tuto akci",
                request,
                List.of(ErrorDetail.ofGlobal("ACCESS_DENIED", "Nemáte dostatečná oprávnění pro tuto akci")));
    }

    // =========================================================================
    // 404 Not Found
    // =========================================================================

    /**
     * Obsluhuje přístup k neexistujícímu databázovému záznamu.
     * Do {@code params} přidává {@code resourceName} a {@code resourceId},
     * aby si klient mohl sestavit lokalizovanou zprávu.
     *
     * @param ex      výjimka ze service vrstvy
     * @param request HTTP požadavek
     * @return ProblemDetail s HTTP 404
     */
    @ExceptionHandler(ResourceNotFoundException.class)
    public ProblemDetail handleNotFound(ResourceNotFoundException ex,
                                        HttpServletRequest request) {
        return buildProblemDetail(
                HttpStatus.NOT_FOUND,
                ex.getMessage(),
                request,
                List.of(ErrorDetail.ofGlobal(
                        "RESOURCE_NOT_FOUND",
                        ex.getMessage(),
                        Map.of(
                                "resourceName", ex.getResourceName(),
                                "resourceId",   String.valueOf(ex.getResourceId())))));
    }

    // =========================================================================
    // 409 Conflict
    // =========================================================================

    /**
     * Obsluhuje pokus o registraci už obsazeného uživatelského jména.
     *
     * @param ex      výjimka z auth service
     * @param request HTTP požadavek
     * @return ProblemDetail s HTTP 409
     */
    @ExceptionHandler(UserAlreadyExistsException.class)
    public ProblemDetail handleUserExists(UserAlreadyExistsException ex,
                                          HttpServletRequest request) {
        return buildProblemDetail(
                HttpStatus.CONFLICT,
                ex.getMessage(),
                request,
                List.of(ErrorDetail.ofGlobal("USER_ALREADY_EXISTS", ex.getMessage())));
    }

    /**
     * Obsluhuje požadavek kolidující s aktuálním stavem zdroje,
     * např. import faktury, která už importovaná je.
     *
     * @param ex      výjimka ze service vrstvy nesoucí kód konfliktu
     * @param request HTTP požadavek
     * @return ProblemDetail s HTTP 409
     */
    @ExceptionHandler(ConflictException.class)
    public ProblemDetail handleConflict(ConflictException ex,
                                        HttpServletRequest request) {
        return buildProblemDetail(
                HttpStatus.CONFLICT,
                ex.getMessage(),
                request,
                List.of(ErrorDetail.ofGlobal(ex.getCode(), ex.getMessage())));
    }

    // =========================================================================
    // 422 Unprocessable Entity
    // =========================================================================

    /**
     * Obsluhuje porušení business pravidla závislého na stavu databáze.
     * Na rozdíl od validačních chyb (400) tato pravidla vyžadují dotaz do DB.
     *
     * @param ex      výjimka ze service vrstvy
     * @param request HTTP požadavek
     * @return ProblemDetail s HTTP 422
     */
    @ExceptionHandler(BusinessRuleException.class)
    public ProblemDetail handleBusinessRule(BusinessRuleException ex,
                                            HttpServletRequest request) {
        ErrorDetail error = ex.getField() != null
                ? ErrorDetail.ofField(ex.getField(), ex.getRuleCode(), ex.getMessage(), ex.getParams())
                : ErrorDetail.ofGlobal(ex.getRuleCode(), ex.getMessage(), ex.getParams());

        return buildProblemDetail(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage(), request, List.of(error));
    }

    /**
     * Catch-all pro porušení DB constraintů, která proklouzla validací.
     * Vrací 422 místo 500 — data jsou syntakticky platná, ale DB je odmítla.
     *
     * @param ex      Spring DAO výjimka obalující JDBC porušení constraintu
     * @param request HTTP požadavek
     * @return ProblemDetail s HTTP 422
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ProblemDetail handleDataIntegrity(DataIntegrityViolationException ex,
                                             HttpServletRequest request) {
        log.warn("DB constraint violated: {}", ex.getMostSpecificCause().getMessage());
        return buildProblemDetail(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "Zadaná data porušují databázové omezení",
                request,
                List.of(ErrorDetail.ofGlobal("DATA_INTEGRITY_VIOLATION", "Data se nepodařilo uložit")));
    }

    /**
     * Neparsovatelné/nesprávné tělo requestu — vadný JSON nebo neplatná hodnota enumu
     * (např. {@code "paymentMethod":"FOO"}). Dřív spadlo do catch-all → 500; správně 400 (E6.1/S-5).
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail handleUnreadableBody(HttpMessageNotReadableException ex, HttpServletRequest request) {
        return buildProblemDetail(
                HttpStatus.BAD_REQUEST,
                "Tělo požadavku nelze zpracovat",
                request,
                List.of(ErrorDetail.ofGlobal("MALFORMED_REQUEST",
                        "Tělo požadavku nelze zpracovat (neplatný JSON nebo hodnota).")));
    }

    /**
     * Validace parametrů metody ({@code @Validated} na query/path prvcích, Spring 6.1+) → 400
     * místo 500 z catch-all (E6.1/S-5).
     */
    @ExceptionHandler(org.springframework.web.method.annotation.HandlerMethodValidationException.class)
    public ProblemDetail handleHandlerMethodValidation(
            org.springframework.web.method.annotation.HandlerMethodValidationException ex,
            HttpServletRequest request) {
        return buildProblemDetail(
                HttpStatus.BAD_REQUEST,
                "Ověření parametrů požadavku selhalo",
                request,
                List.of(ErrorDetail.ofGlobal("INVALID_ARGUMENT", "Neplatné parametry požadavku.")));
    }

    /** Nepodporovaná HTTP metoda na existující cestě → 405 (dřív 500 z catch-all, E6.1/S-5). */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ProblemDetail handleMethodNotSupported(HttpRequestMethodNotSupportedException ex,
                                                  HttpServletRequest request) {
        return buildProblemDetail(
                HttpStatus.METHOD_NOT_ALLOWED,
                "Tato metoda není pro daný zdroj povolena",
                request,
                List.of(ErrorDetail.ofGlobal("METHOD_NOT_ALLOWED",
                        "Metoda " + ex.getMethod() + " není pro tento zdroj povolena.")));
    }

    /**
     * Neznámá cesta (žádný handler ani statický zdroj) → 404, ne 500 z catch-all (E6.1/S-5, TD-61).
     * Backend je API-only (SPA servíruje nginx/Vite), takže tu není SPA fallback, který by 404 rozbil.
     */
    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    public ProblemDetail handleNoResourceFound(
            org.springframework.web.servlet.resource.NoResourceFoundException ex,
            HttpServletRequest request) {
        return buildProblemDetail(
                HttpStatus.NOT_FOUND,
                "Požadovaný zdroj neexistuje",
                request,
                List.of(ErrorDetail.ofGlobal("NOT_FOUND", "Požadovaná cesta neexistuje.")));
    }

    /**
     * Selhání AI extrakce dokladu → 503 (typicky přechodné, „zkuste znovu"), ne 500 z catch-all
     * (E6.3/sklad S-2). Konkrétní příčina je jen v logu, klientovi jde generická hláška.
     */
    @ExceptionHandler(DocumentExtractionException.class)
    public ProblemDetail handleExtractionFailed(DocumentExtractionException ex, HttpServletRequest request) {
        // `ex` jako poslední argument → SLF4J přiloží celý řetězec příčin. Bez něj se logoval jen
        // text obalu („Extrakci dokladu se nepodařilo dokončit.") a skutečný důvod od API — 401,
        // neexistující model, timeout — se nikam nezapsal, přestože Javadoc tvrdil opak.
        log.warn("Document extraction failed on {} {}: {}",
                request.getMethod(), request.getRequestURI(), ex.getMessage(), ex);
        return buildProblemDetail(
                HttpStatus.SERVICE_UNAVAILABLE,
                "Doklad se nepodařilo automaticky přečíst. Zkuste to prosím znovu.",
                request,
                List.of(ErrorDetail.ofGlobal("EXTRACTION_FAILED",
                        "Doklad se nepodařilo automaticky přečíst. Zkuste to prosím znovu.")));
    }

    // =========================================================================
    // 500 Internal Server Error
    // =========================================================================

    /**
     * Catch-all pro jakoukoli neočekávanou výjimku.
     *
     * <p><strong>Bezpečnostní poznámka:</strong> technické detaily výjimky (stack trace,
     * název třídy, SQL chyba) se klientovi nikdy neposílají — jen generická zpráva.
     * Plné detaily se logují na úrovni ERROR pro serverovou diagnostiku.
     *
     * @param ex      libovolná neobsloužená výjimka
     * @param request HTTP požadavek
     * @return ProblemDetail s HTTP 500 a generickou zprávou
     */
    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Unexpected error on {} {}", request.getMethod(), request.getRequestURI(), ex);
        return buildProblemDetail(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Došlo k neočekávané chybě. Zkuste to prosím znovu.",
                request,
                List.of(ErrorDetail.ofGlobal("INTERNAL_ERROR", "Došlo k neočekávané chybě. Zkuste to prosím znovu.")));
    }

    // =========================================================================
    // 503 Service Unavailable
    // =========================================================================

    /**
     * Obsluhuje výpadek externího registru vozidel (dataovozidlech.cz) —
     * timeout, rate limit, neplatný API klíč nebo chybu serveru. 503 klientovi
     * říká „zkuste později"; konkrétní příčina cestuje v chybovém kódu
     * ({@code REGISTRY_RATE_LIMITED}, {@code REGISTRY_AUTH_FAILED},
     * {@code REGISTRY_TIMEOUT}, {@code REGISTRY_ERROR}).
     *
     * @param ex      výjimka z klienta registru
     * @param request HTTP požadavek
     * @return ProblemDetail s HTTP 503
     */
    @ExceptionHandler(RegistryUnavailableException.class)
    public ProblemDetail handleRegistryUnavailable(RegistryUnavailableException ex,
                                                   HttpServletRequest request) {
        log.warn("Vehicle registry unavailable ({}): {}", ex.getCode(), ex.getMessage());
        return buildProblemDetail(
                HttpStatus.SERVICE_UNAVAILABLE,
                ex.getMessage(),
                request,
                List.of(ErrorDetail.ofGlobal(ex.getCode(), ex.getMessage())));
    }

    /**
     * Obsluhuje nedostupnost ARES (ares.gov.cz) — timeout, rate limit nebo chybu
     * serveru. Stejný tvar jako handler registru vozidel; konkrétní příčina
     * cestuje v chybovém kódu ({@code ARES_RATE_LIMITED}, {@code ARES_TIMEOUT},
     * {@code ARES_ERROR}).
     *
     * @param ex      výjimka z ARES klienta
     * @param request HTTP požadavek
     * @return ProblemDetail s HTTP 503
     */
    @ExceptionHandler(AresUnavailableException.class)
    public ProblemDetail handleAresUnavailable(AresUnavailableException ex,
                                               HttpServletRequest request) {
        log.warn("ARES unavailable ({}): {}", ex.getCode(), ex.getMessage());
        return buildProblemDetail(
                HttpStatus.SERVICE_UNAVAILABLE,
                ex.getMessage(),
                request,
                List.of(ErrorDetail.ofGlobal(ex.getCode(), ex.getMessage())));
    }

    // =========================================================================
    // Privátní pomocné metody
    // =========================================================================

    /**
     * Sestaví {@link ProblemDetail} s rozšiřující vlastností {@code errors}.
     * Všechny handlery delegují sem, aby měla odpověď zaručeně jednotný tvar.
     *
     * @param status  HTTP stavový kód
     * @param detail  lidsky čitelný popis problému
     * @param request HTTP požadavek (nastavuje {@code instance} URI)
     * @param errors  seznam detailů chyb
     * @return sestavený ProblemDetail připravený k serializaci
     */
    private ProblemDetail buildProblemDetail(HttpStatus status, String detail,
                                             HttpServletRequest request,
                                             List<ErrorDetail> errors) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(status.getReasonPhrase());
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("errors", errors);
        return problem;
    }

    /**
     * Vytáhne prostý název anotace z {@link FieldError}.
     *
     * <p>Spring generuje pole {@code codes} od nejkonkrétnějšího k nejobecnějšímu.
     * Poslední prvek je vždy holý název anotace (např. {@code "Size"},
     * {@code "NotNull"}, {@code "ValidCustomerRequest"}) — přesně ten je potřeba pro mapování.
     *
     * @param error chyba pole z BindingResult
     * @return prostý název anotace, nebo {@code "UNKNOWN"}, pokud kódy chybí
     */
    private String extractConstraintName(FieldError error) {
        String[] codes = error.getCodes();
        if (codes != null && codes.length > 0) {
            return codes[codes.length - 1];
        }
        return "UNKNOWN";
    }
}
