package cz.palo.autoservis.model.dto.billing;

import cz.palo.autoservis.model.enums.CashReceiptStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * DTO namespace pro příjmový pokladní doklad (PPD).
 */
public final class CashReceiptDto {

    private CashReceiptDto() {}

    /**
     * Klient vyplní fakturu, číslo dokladu a datum vystavení — částku a účel doplní server.
     * Číslo od V92 skládá aplikace podle masky (návrh z {@code GET /next-number}), uživatel
     * ho může přepsat — týž kontrakt jako {@link InvoiceDto.IssueRequest}.
     */
    @Data
    public static class CreateRequest {
        @NotNull(message = "Faktura je povinná")
        private Long invoiceId;

        @NotBlank(message = "Číslo dokladu je povinné")
        @Size(max = 20, message = "Číslo dokladu může mít nejvýš 20 znaků")
        private String receiptNumber;

        /** Datum vystavení; nevyplněné = dnešek (PPD je okamžité potvrzení příjmu). */
        private LocalDate issueDate;
    }

    /** Návrh dalšího čísla v řadě — nic nerezervuje (viz suggestNextNumber u faktur). */
    @Data
    public static class NextNumberResponse {
        /**
         * Zdroj čísla (V93): {@code MASK} = návrh v {@code receiptNumber}, {@code INVOICE} =
         * FE předvyplní číslem hrazené faktury (návrh se tu neskládá), {@code MANUAL} = prázdné pole.
         */
        private cz.palo.autoservis.model.enums.CashReceiptNumberSource source;
        private String receiptNumber;
    }

    /** Hlídání mezer v řadě PPD (V92, zrcadlo V89 u faktur). */
    @Data
    public static class NumberGapsResponse {
        /** Je kontrola zapnutá? Vypnutá kontrola vrací prázdný seznam, což NEznamená „bez děr". */
        private boolean enabled;
        private List<String> missingNumbers = List.of();
        private LocalDate periodDate;
    }

    /**
     * Storno dokladu vystaveného omylem. Důvod je povinný — stornovaný pokladní doklad bez
     * vysvětlení je díra v auditní stopě (§35 ZoÚ: záznam musí zůstat doložitelný).
     */
    @Data
    public static class CancelRequest {
        @NotBlank(message = "Důvod storna je povinný")
        @Size(max = 255, message = "Důvod storna může mít nejvýš 255 znaků")
        private String reason;
    }

    /**
     * Plná odpověď. §11 náležitosti: číslo dokladu, datum, přijatá částka číslem i slovy, účel platby;
     * účastníci (příjemce = dodavatel faktury, plátce = odběratel) a rozpis DPH se odvozují z faktury.
     */
    @Data
    public static class DetailResponse {
        private Long id;
        private String receiptNumber;
        private LocalDate issueDate;

        private Long invoiceId;
        private String invoiceNumber;    // evidenční číslo hrazené faktury
        private String variableSymbol;
        private String purpose;          // účel platby („Úhrada faktury č. …, VS …")

        private BigDecimal amount;       // přijatá částka (celé Kč — hotovost)
        private String amountInWords;    // částka slovy (čeština)
        private BigDecimal rounding;     // zaokrouhlení na celé Kč (amount − totalGross), § 36/5 ZDPH

        // Storno (V68) — doklad zůstává v číselné řadě, jen přestane platit.
        private CashReceiptStatus status;
        private OffsetDateTime cancelledAt;
        private String cancellationReason;

        private OffsetDateTime createdAt;
        private Long createdBy;

        // Účastníci (snapshoty faktury): příjemce hotovosti = dodavatel, plátce = odběratel.
        private InvoiceDto.PartyResponse supplier;
        private InvoiceDto.PartyResponse customer;

        // Rozpis DPH a součty hrazené faktury (jeden zdroj počítá).
        private BigDecimal totalNet;
        private BigDecimal totalVat;
        private BigDecimal totalGross;
        private List<InvoiceDto.VatSummaryLine> vatSummary;
    }
}
