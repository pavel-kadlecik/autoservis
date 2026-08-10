package cz.palo.autoservis.exception;

/**
 * Vyhazuje se, když refresh token chybí, byl odvolán nebo expiroval.
 * {@link GlobalExceptionHandler} mapuje na HTTP 401 Unauthorized.
 */
public class InvalidRefreshTokenException extends RuntimeException {

    public InvalidRefreshTokenException(String message) {
        super(message);
    }
}
