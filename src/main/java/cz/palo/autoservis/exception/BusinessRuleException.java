package cz.palo.autoservis.exception;

import java.util.Collections;
import java.util.Map;

/**
 * Vyhazuje se při porušení business pravidla závislého na stavu databáze.
 * {@link GlobalExceptionHandler} mapuje na HTTP 422 Unprocessable Entity.
 *
 * <p>Používej pro pravidla vyžadující dotaz do DB, např. duplicitní IČO
 * nebo zákazník s otevřenými zakázkami. Pro prostou validaci vstupu použij
 * Bean Validation anotace.
 *
 * @see GlobalExceptionHandler
 */
public class BusinessRuleException extends RuntimeException {

    private final String ruleCode;
    private final String field;
    private final Map<String, Object> params;

    /**
     * Vytvoří výjimku pro globální business pravidlo nevázané na konkrétní pole.
     *
     * @param ruleCode strojový identifikátor pravidla
     * @param message  výchozí lidsky čitelná zpráva
     */
    public BusinessRuleException(String ruleCode, String message) {
        this(ruleCode, null, message, Collections.emptyMap());
    }

    /**
     * Vytvoří výjimku pro business pravidlo vázané na konkrétní pole requestu.
     *
     * @param ruleCode strojový identifikátor pravidla
     * @param field    název DTO pole ({@code null} pro globální pravidla)
     * @param message  výchozí lidsky čitelná zpráva
     * @param params   dynamické parametry pro šablonování zprávy na klientovi
     */
    public BusinessRuleException(String ruleCode, String field,
                                 String message, Map<String, Object> params) {
        super(message);
        this.ruleCode = ruleCode;
        this.field = field;
        this.params = params != null ? Map.copyOf(params) : Collections.emptyMap();
    }

    /** Vrací strojový identifikátor pravidla. Nikdy {@code null}. */
    public String getRuleCode() {
        return ruleCode;
    }

    /** Vrací název DTO pole, nebo {@code null} pro globální pravidla. */
    public String getField() {
        return field;
    }

    /** Vrací dynamické parametry pro šablonování zprávy na klientovi. Nikdy {@code null}. */
    public Map<String, Object> getParams() {
        return params;
    }
}
