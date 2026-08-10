package cz.palo.autoservis.exception;

/**
 * Vyhazuje se, když požadavek koliduje s aktuálním stavem zdroje,
 * např. opakovaný import už existující faktury.
 * {@link GlobalExceptionHandler} mapuje na HTTP 409 Conflict.
 *
 * @see GlobalExceptionHandler
 */
public class ConflictException extends RuntimeException {

    private final String code;

    /**
     * @param code    strojový identifikátor konfliktu (např. {@code "DUPLICATE_IMPORT"})
     * @param message výchozí lidsky čitelná zpráva
     */
    public ConflictException(String code, String message) {
        super(message);
        this.code = code;
    }

    /** Vrací strojový identifikátor konfliktu. Nikdy {@code null}. */
    public String getCode() {
        return code;
    }
}
