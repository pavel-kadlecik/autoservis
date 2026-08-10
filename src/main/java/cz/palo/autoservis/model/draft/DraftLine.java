package cz.palo.autoservis.model.draft;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * Řádek draftu příjemky. ITEM řádky se při potvrzení materializují na šarže
 * a pohyby; DELIVERY_NOTE_GROUP a NOTE řádky se nikdy nematerializují.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DraftLine {

    public enum LineKind {
        /** Skutečná položka dokladu. */
        ITEM,
        /** Skupinový řádek „Dodací list č. X celkem …" (LKQ) — není položka. */
        DELIVERY_NOTE_GROUP,
        /** Jiný nepoložkový řádek (poznámka, mezisoučet…). */
        NOTE
    }

    private LineKind lineKind;
    private Integer position;

    private TrackedField<String> catalogNumber;
    private TrackedField<String> name;
    private TrackedField<String> unit;
    private TrackedField<BigDecimal> quantity;
    private TrackedField<BigDecimal> unitPriceExclVat;
    private TrackedField<Integer> vatRate;
    private TrackedField<BigDecimal> totalExclVat;
    private TrackedField<BigDecimal> totalInclVat;

    /** Číslo dodacího listu — jen u DELIVERY_NOTE_GROUP řádků. */
    private String deliveryNoteNumber;

    /** Výsledek párování na skladovou kartu (plní fáze 5, do té doby null/NONE). */
    private ProductMatch productMatch;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ProductMatch {

        public enum State {
            /** Jednoznačná shoda (převodník supplier_products) — napárováno automaticky. */
            AUTO,
            /** Kandidáti nalezeni — vybírá člověk. */
            SUGGESTED,
            /** Uživatel volbu potvrdil v kontrolní obrazovce. */
            CONFIRMED,
            /** Žádná shoda — potvrzení založí nový produkt. */
            NONE
        }

        private State state;
        private Long productId;
        private List<Candidate> candidates;

        @Data
        @Builder
        @NoArgsConstructor
        @AllArgsConstructor
        public static class Candidate {
            private Long productId;
            private String reason;    // PART_NUMBER / NAME_SIMILARITY
            private Double score;
            private String label;
        }
    }
}
