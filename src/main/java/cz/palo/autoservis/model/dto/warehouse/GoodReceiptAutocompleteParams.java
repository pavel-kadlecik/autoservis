package cz.palo.autoservis.model.dto.warehouse;

import cz.palo.autoservis.model.enums.ProductImportType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Query parametry pro {@code GET /api/{version}/warehouse/goods-receipts}.
 * Spring naváže query string na tento record automaticky.
 */
public record GoodReceiptAutocompleteParams(

        /** Hledaný výraz. Může být {@code null}, když frontend pošle prázdný řetězec. */
        String q,

        /** Požadovaný limit výsledků. Server vynucuje {@link #MAX_LIMIT}. */
        @Min(1) @Max(100)
        Integer limit,

        /** Podle čeho se hledá — číslo faktury, nebo číslo objednávky. */
        @NotNull
        ProductImportType importType

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
