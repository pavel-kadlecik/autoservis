package cz.palo.autoservis.exception;

/**
 * Vyhazuje se, když se nelze dotázat státního registru vozidel
 * (dataovozidlech.cz) — timeout, rate limit, selhání autentizace nebo chyba
 * serveru. {@link GlobalExceptionHandler} mapuje na HTTP 503 Service Unavailable.
 *
 * <p>Liší se od výsledku „vozidlo v registru nenalezeno", což je platná business
 * odpověď (klient vrátí prázdný {@code Optional} a service vrstva vyhodí
 * {@link BusinessRuleException} → 422).
 *
 * @see GlobalExceptionHandler
 */
public class RegistryUnavailableException extends RuntimeException {

    private final String code;

    /**
     * @param code    strojový identifikátor selhání: {@code REGISTRY_RATE_LIMITED},
     *                {@code REGISTRY_AUTH_FAILED}, {@code REGISTRY_TIMEOUT} nebo {@code REGISTRY_ERROR}
     * @param message výchozí lidsky čitelná zpráva
     */
    public RegistryUnavailableException(String code, String message) {
        super(message);
        this.code = code;
    }

    /** Vrací strojový identifikátor selhání. Nikdy {@code null}. */
    public String getCode() {
        return code;
    }
}
