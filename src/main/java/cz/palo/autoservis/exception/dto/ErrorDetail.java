package cz.palo.autoservis.exception.dto;

import java.util.Map;

/**
 * Jedna položka pole {@code errors} v chybové odpovědi API.
 *
 * <p>Každá chybová odpověď (400, 404, 422, …) obsahuje jeden či více záznamů
 * {@code ErrorDetail}, což zaručuje jednotný formát napříč všemi typy chyb.
 *
 * <ul>
 *   <li>{@code field} — název pole request DTO, ke kterému se chyba vztahuje,
 *       nebo {@code null} pro globální (nepolové) chyby.</li>
 *   <li>{@code code} — strojový, jazykově nezávislý identifikátor chyby.
 *       Klienti ho používají jako klíč do vlastního překladového slovníku.</li>
 *   <li>{@code message} — výchozí lidsky čitelná zpráva (locale serveru).
 *       Fallback pro klienty bez překladového slovníku.</li>
 *   <li>{@code params} — dynamická data pro šablonování zprávy na klientovi
 *       (např. {@code {"ico": "12345678"}}). {@code null}, když nedávají smysl.</li>
 * </ul>
 *
 * @param field   název DTO pole, nebo {@code null} pro globální chyby
 * @param code    strojový chybový kód sloužící jako překladový klíč
 * @param message výchozí lidsky čitelná zpráva
 * @param params  dynamické parametry pro šablonování zprávy na klientovi
 */
public record ErrorDetail(
        String field,
        String code,
        String message,
        Map<String, Object> params
) {

    /** Vytvoří chybu na úrovni pole bez dynamických parametrů. */
    public static ErrorDetail ofField(String field, String code, String message) {
        return new ErrorDetail(field, code, message, null);
    }

    /** Vytvoří chybu na úrovni pole s dynamickými parametry. */
    public static ErrorDetail ofField(String field, String code,
                                      String message, Map<String, Object> params) {
        return new ErrorDetail(field, code, message, params);
    }

    /** Vytvoří globální chybu bez dynamických parametrů. */
    public static ErrorDetail ofGlobal(String code, String message) {
        return new ErrorDetail(null, code, message, null);
    }

    /** Vytvoří globální chybu s dynamickými parametry. */
    public static ErrorDetail ofGlobal(String code, String message,
                                       Map<String, Object> params) {
        return new ErrorDetail(null, code, message, params);
    }
}
