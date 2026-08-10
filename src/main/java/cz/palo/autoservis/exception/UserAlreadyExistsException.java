package cz.palo.autoservis.exception;

/**
 * Vyhazuje se při pokusu o registraci už existujícího uživatelského jména.
 * {@link GlobalExceptionHandler} mapuje na HTTP 409 Conflict.
 */
public class UserAlreadyExistsException extends RuntimeException {

    public UserAlreadyExistsException(String message) {
        super(message);
    }
}
