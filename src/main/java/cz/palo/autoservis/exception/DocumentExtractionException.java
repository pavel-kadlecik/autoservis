package cz.palo.autoservis.exception;

/**
 * Selhání AI extrakce dat z dokladu (timeout, přetížení, nevalidní odpověď modelu, chybějící klíč).
 * Mapuje se na 503 {@code EXTRACTION_FAILED} — jde o (typicky přechodné) selhání externí služby,
 * ne o chybu klienta (audit E6.3/sklad S-2).
 */
public class DocumentExtractionException extends RuntimeException {

    public DocumentExtractionException(String message, Throwable cause) {
        super(message, cause);
    }
}
