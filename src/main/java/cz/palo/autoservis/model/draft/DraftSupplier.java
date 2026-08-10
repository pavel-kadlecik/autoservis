package cz.palo.autoservis.model.draft;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Dodavatel v draftu: co bylo přečteno z dokladu + výsledek napárování na DB.
 * Dodavatel se při importu nikdy nezakládá — vznik řeší až potvrzení příjemky.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DraftSupplier {

    public enum MatchState {
        /** Nalezen v DB podle normalizovaného IČO. */
        AUTO,
        /** Nenalezen — potvrzení příjemky ho založí z extrahovaných dat. */
        NONE
    }

    private Extracted extracted;
    private Boolean icoChecksumOk;
    private Long matchedSupplierId;
    private MatchState matchState;

    /** Hodnoty dodavatele opsané z dokladu (ověřuje je kód, ne stavy polí). */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Extracted {
        private String name;
        private String registrationNumber;   // IČO (normalizované)
        private String vatId;                // DIČ
        private String street;
        private String city;
        private String postalCode;
        private String bankAccount;
        private String iban;
        private String swift;
    }
}
