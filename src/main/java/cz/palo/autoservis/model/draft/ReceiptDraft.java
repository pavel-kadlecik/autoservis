package cz.palo.autoservis.model.draft;

import cz.palo.autoservis.model.domain.warehouse.DocumentType;
import cz.palo.autoservis.model.domain.warehouse.ReceiptSource;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Kanonický draft příjemky — jednotný mezistupeň všech vstupních kanálů
 * (AI extrakce z PDF, ruční formulář, budoucí ISDOC). Serializuje se do
 * goods_receipts.draft_payload (JSONB); závazný během PENDING_REVIEW,
 * po potvrzení/zamítnutí zůstává jako zmrazený snapshot.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReceiptDraft {

    public static final int CURRENT_SCHEMA_VERSION = 1;

    private int schemaVersion;
    private DocumentType documentType;
    private ReceiptSource sourceChannel;
    private Extraction extraction;
    private Header header;
    private DraftSupplier supplier;
    private List<VatRecapRow> vatRecap;
    private List<DeliveryNoteRef> deliveryNoteRefs;
    private List<DraftLine> lines;
    private List<DraftCheck> checks;

    /** Metadata AI extrakce (u MANUAL/ISDOC null). */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Extraction {
        private String model;
        private OffsetDateTime extractedAt;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Header {
        private TrackedField<String> documentNumber;
        private TrackedField<String> orderNumber;
        private TrackedField<String> originalOrderNumber;
        private TrackedField<LocalDate> issueDate;
        private TrackedField<LocalDate> dueDate;
        private TrackedField<LocalDate> taxableSupplyDate;
        private TrackedField<String> currency;
        private TrackedField<BigDecimal> subtotal;
        private TrackedField<BigDecimal> vatAmount;
        private TrackedField<BigDecimal> totalAmount;
    }

    /** Řádek rekapitulace DPH z dokladu (LKQ: kódy sazeb A/B/C). */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class VatRecapRow {
        private String code;
        private Integer ratePercent;
        private BigDecimal base;
        private BigDecimal vat;
    }
}
