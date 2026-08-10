package cz.palo.autoservis.model.dto.warehouse;

import cz.palo.autoservis.model.draft.ReceiptDraft;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * DTO namespace pro review workflow příjemek.
 *
 * <p>Pozn.: draft se v detailu i v PUT přenáší přímo jako {@link ReceiptDraft} —
 * je to už serializační model (JSONB payload), paralelní DTO strom by jen
 * duploval strukturu. Hlavičkové sloupce příjemky jdou přes klasické DTO.
 */
public final class ReceiptDto {

    private ReceiptDto() {}

    /** Řádek seznamu příjemek (bez PDF a bez draftu — obojí je těžké). */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ListResponse {
        private Long id;
        private String documentType;
        private String documentNumber;
        private String supplierName;
        private LocalDate issueDate;
        private BigDecimal totalAmount;
        private String currency;
        private String status;
        private boolean reconciliationOk;
        private String sourceChannel;
        private OffsetDateTime createdAt;
    }

    /** Detail příjemky: hlavička + kanonický draft (u CONFIRMED/REJECTED zmrazený snapshot). */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DetailResponse {
        private Long id;
        private String documentType;
        private String sourceChannel;
        private String status;
        private String documentNumber;
        private Long supplierId;
        private String supplierName;
        private LocalDate issueDate;
        private BigDecimal subtotal;
        private BigDecimal vatAmount;
        private BigDecimal totalAmount;
        private String currency;
        private boolean reconciliationOk;
        private String extractionModel;
        private String sourceFilename;
        private boolean hasPdf;
        private OffsetDateTime confirmedAt;
        private OffsetDateTime rejectedAt;
        private String rejectionNote;
        private OffsetDateTime cancelledAt;
        private String cancellationNote;
        private OffsetDateTime createdAt;
        private ReceiptDraft draft;
    }

    /** Tělo zamítnutí. */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RejectRequest {
        @Size(max = 500, message = "Poznámka může mít nejvýše 500 znaků.")
        private String note;
    }

    /**
     * Storno potvrzené příjemky (V43, R-C). Na rozdíl od zamítnutí je poznámka
     * <b>povinná</b> — storno maže zásobu kompenzačními pohyby a musí být zdůvodněné.
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CancelRequest {
        @jakarta.validation.constraints.NotBlank(message = "Důvod storna je povinný.")
        @Size(max = 500, message = "Poznámka může mít nejvýše 500 znaků.")
        private String note;
    }

    /** Založení prázdného draftu ruční příjemky (bez PDF). */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CreateDraftRequest {
        @jakarta.validation.constraints.NotNull(message = "Typ dokladu je povinný.")
        private cz.palo.autoservis.model.domain.warehouse.DocumentType documentType;

        /** Existující dodavatel (volitelné — jinak name/IČO níže). */
        private Long supplierId;

        @Size(max = 255, message = "Název dodavatele může mít nejvýše 255 znaků.")
        private String supplierName;

        @Size(max = 15, message = "IČO může mít nejvýše 15 znaků.")
        private String supplierRegistrationNumber;
    }
}
