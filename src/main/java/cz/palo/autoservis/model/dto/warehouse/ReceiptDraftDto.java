package cz.palo.autoservis.model.dto.warehouse;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * DTO namespace pro draft příjemky (import + budoucí review workflow).
 */
public final class ReceiptDraftDto {

    private ReceiptDraftDto() {}

    /**
     * Odpověď importu: doklad je uložen jako draft (PENDING_REVIEW) ke kontrole.
     * Nic se zatím nenaskladnilo — produkty, šarže a pohyby vzniknou až potvrzením.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ImportResponse {
        private Long receiptId;
        private String documentType;
        private String status;
        private String documentNumber;
        private String orderNumber;
        private String supplierName;
        private boolean supplierMatched;
        private boolean reconciliationOk;
        private BigDecimal totalAmount;
        private List<CheckResult> checks;
        private List<Line> items;
    }

    /** Výsledek jedné deterministické kontroly (pro zobrazení v UI). */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CheckResult {
        private String code;
        private boolean ok;
        private Integer position;
    }

    /** Řádek draftu pro rychlý náhled v import modalu. */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Line {
        private String sku;
        private String name;
        private BigDecimal quantity;
        private BigDecimal unitPriceExclVat;
        private Integer vatRate;
        private BigDecimal totalInclVat;
    }
}
