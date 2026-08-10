package cz.palo.autoservis.model.dto.billing;

import cz.palo.autoservis.model.enums.InvoiceStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * DTO namespace pro opravný daňový doklad (dobropis, §45 ZDPH).
 */
public final class CreditNoteDto {

    private CreditNoteDto() {}

    /** Klient vyplní jen původní fakturu, důvod opravy a volitelně DUZP. */
    @Data
    public static class CreateRequest {
        @NotNull(message = "Původní faktura je povinná")
        private Long originalInvoiceId;

        @NotBlank(message = "Důvod opravy je povinný")
        @Size(max = 500, message = "Důvod opravy může mít maximálně 500 znaků")
        private String correctionReason;

        /** Nepovinné — nevyplněno = datum vystavení. */
        private LocalDate taxableSupplyDate;
    }

    /**
     * Plná odpověď. §45 náležitosti: číslo opravného i původního dokladu, důvod, rozdílové částky
     * (záporné — plný dobropis = záporné souhrny původní faktury) vč. rozpadu po sazbách, a identifikace
     * stran ze snapshotů původní faktury.
     */
    @Data
    public static class DetailResponse {
        private Long id;
        private String creditNoteNumber;        // null pro DRAFT (přiděluje se při vystavení)
        private InvoiceStatus status;
        private Long originalInvoiceId;
        private String originalInvoiceNumber;   // §45 evidenční číslo původního dokladu
        private String correctionReason;        // §45 důvod opravy
        private LocalDate issueDate;
        private LocalDate taxableSupplyDate;
        private OffsetDateTime createdAt;
        private OffsetDateTime updatedAt;
        private Long createdBy;

        // §45 rozdíly (záporné hodnoty vůči původní faktuře)
        private BigDecimal totalNetDifference;
        private BigDecimal totalVatDifference;
        private BigDecimal totalGrossDifference;
        private List<InvoiceDto.VatSummaryLine> vatDifferences;

        // Identifikace stran (snapshoty původní faktury)
        private InvoiceDto.PartyResponse supplier;
        private InvoiceDto.PartyResponse customer;
    }
}
