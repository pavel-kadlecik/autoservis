package cz.palo.autoservis.model.dto.vehicle;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * Query parametry pro {@code GET /api/{version}/vehicles/autocomplete}.
 * Spring naváže query string na tento record automaticky.
 */
public record VehicleAutocompleteParams(

        /** Hledaný výraz. Může být {@code null}, když frontend pošle prázdný řetězec. */
        String q,

        /** Požadovaný limit výsledků. Server vynucuje {@link #MAX_LIMIT}. */
        @Min(1) @Max(100)
        Integer limit,

        /**
         * Volitelný filtr zákazníka — vrací jen vozidla patřící tomuto zákazníkovi.
         * {@code null} znamená bez filtru (hledá se ve všech vozidlech).
         */
        Long customerId

) {
    /** Serverový strop — víc si klient vyžádat nemůže. */
    public static final int MAX_LIMIT = 100;

    /** Vrací efektivní limit: minimum z požadované hodnoty a {@link #MAX_LIMIT}. */
    public int effectiveLimit() {
        if (limit == null) return 10;
        return Math.min(limit, MAX_LIMIT);
    }

    /** Vrací hledaný výraz zbavený okrajových mezer, nebo prázdný řetězec při {@code null}. */
    public String normalizedQuery() {
        return q == null ? "" : q.strip();
    }
}
