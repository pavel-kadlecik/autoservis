package cz.palo.autoservis.exception;

/**
 * Vyhazuje se, když se nelze dotázat registru ARES (ares.gov.cz) — timeout,
 * rate limit nebo chyba serveru. {@link GlobalExceptionHandler} mapuje
 * na HTTP 503 Service Unavailable.
 *
 * <p>Liší se od výsledku „subjekt v ARES nenalezen", což je platná business
 * odpověď (klient vrátí prázdný {@code Optional} a service vrstva vyhodí
 * {@link BusinessRuleException} → 422). Stejné rozdělení jako
 * {@link RegistryUnavailableException} u registru vozidel.
 *
 * @see GlobalExceptionHandler
 */
public class AresUnavailableException extends RuntimeException {

    private final String code;

    /**
     * @param code    strojový identifikátor selhání: {@code ARES_RATE_LIMITED},
     *                {@code ARES_TIMEOUT} nebo {@code ARES_ERROR}
     * @param message výchozí lidsky čitelná zpráva
     */
    public AresUnavailableException(String code, String message) {
        super(message);
        this.code = code;
    }

    /** Vrací strojový identifikátor selhání. Nikdy {@code null}. */
    public String getCode() {
        return code;
    }
}
