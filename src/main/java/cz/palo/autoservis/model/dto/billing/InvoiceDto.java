package cz.palo.autoservis.model.dto.billing;

import cz.palo.autoservis.model.enums.InvoiceStatus;
import cz.palo.autoservis.model.enums.PaymentMethod;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * DTO pro entitu faktury.
 */
public class InvoiceDto {

    /** Response DTO pro seznamové endpointy — jeden řádek tabulky faktur. */
    @Data
    public static class ListResponse {
        private Long id;
        private String invoiceNumber;
        private LocalDate issueDate;
        private LocalDate dueDate;
        private InvoiceStatus status;
        private PaymentMethod paymentMethod;
        private String variableSymbol;

        private Long orderId;
        private String orderNumber;

        private Long customerId;
        private String customerDisplayName;

        private BigDecimal totalNet;
        private BigDecimal totalVat;
        private BigDecimal totalGross;

        /**
         * Částka k úhradě = {@code totalGross} + zaokrouhlení hotovosti (V67/KN-7).
         * Seznam ji zobrazuje i řadí podle ní, aby se sloupec nerozcházel s dokladem.
         */
        private BigDecimal totalToPay;

        /** Popis zakázky — živě, ne snímek: slouží k orientaci v seznamu, ne jako část dokladu. */
        private String orderDescription;
        private boolean hasDraftCreditNote;
        private OffsetDateTime handedOverAt;
        /**
         * Kdy byl k faktuře vystaven dobropis (V69), nebo {@code null}. Dobropis stav faktury
         * nemění — bez tohoto pole nešlo v seznamu poznat, které doklady jsou opravené.
         */
        private OffsetDateTime creditedAt;
    }

    /**
     * Mezery v číselné řadě aktuálního období (V89).
     *
     * <p>{@code enabled=false} znamená vypnuté hlídání, ne „vše v pořádku" — frontend proto
     * nesmí prázdný seznam vydávat za potvrzení, že řada je souvislá.
     */
    @Data
    public static class NumberGapsResponse {
        private boolean enabled;
        /** Chybějící čísla, vzestupně; prázdné = řada je souvislá. */
        private java.util.List<String> missingNumbers = java.util.List.of();
        /** Období, kterého se kontrola týká — pro hlášku („srpen 2026"). */
        private LocalDate periodDate;
    }

    /** Response DTO pro detailové endpointy — včetně položek a spočítaných součtů. */
    @Data
    public static class DetailResponse {
        /**
         * Kdy doklad dostal zákazník (V88); {@code null} = nepředáno, a tehdy jde vystavenou
         * fakturu ještě smazat. Vystavení tenhle příznak nenastavuje.
         */
        private OffsetDateTime handedOverAt;
        private Long id;
        private String invoiceNumber;
        private Long orderId;
        private Long customerId;
        private LocalDate issueDate;
        private LocalDate dueDate;
        private LocalDate taxableSupplyDate;
        private String variableSymbol;
        private String constantSymbol;
        private String specificSymbol;
        private PaymentMethod paymentMethod;
        private InvoiceStatus status;
        private String note;

        /** Číslo objednávky zákazníka — nákupní objednávka / PO (V91); {@code null} = neuvedeno. */
        private String purchaseOrderNumber;

        private OffsetDateTime createdAt;
        private OffsetDateTime updatedAt;
        private Long createdBy;
        private String customerNameSnapshot;
        private String orderNumberSnapshot;

        // Evidence úhrady (E2.1) — vyplněné jen u zaplacené faktury.
        private OffsetDateTime paidAt;
        private BigDecimal paidAmount;
        private PaymentMethod paidMethod;

        /**
         * Kdy byl k faktuře vystaven dobropis; {@code null} = nedobropisovaná (V69).
         * Faktura zůstává platným dokladem, ale přestává blokovat zakázku — tu lze
         * fakturovat znovu. UI podle toho vysvětlí, proč se u zakázky zase nabízí faktura.
         */
        private OffsetDateTime creditedAt;

        private String vehicleVin;
        private String vehicleBrand;
        private String vehicleModel;
        private String vehicleLicensePlate;

        private PartyResponse supplier;
        private PartyResponse customer;


        private BigDecimal totalNet;
        private BigDecimal totalVat;
        private BigDecimal totalGross;

        /**
         * Zaokrouhlení celkové částky na celou korunu u hotovostní úhrady (V67/KN-7).
         * Nula u nehotovostní. Stojí <strong>mimo základ daně</strong> (§36/5 ZDPH), takže
         * {@code totalNet} ani {@code totalVat} neovlivňuje.
         */
        private BigDecimal rounding;

        /** Částka k úhradě = {@code totalGross + rounding}. Tuhle nese PDF, QR platba i PPD. */
        private BigDecimal totalToPay;

        private List<InvoiceItemDto.Response> items;
        private List<VatSummaryLine> vatSummary;
    }

    /** Zmrazená strana (dodavatel či odběratel), jak je uvedena na faktuře — snapshot z billing.invoice_party. */
    @Data
    public static class PartyResponse {
        private String name;
        private String ico;
        private String dic;
        private String street;
        private String streetNumber;
        private String city;
        private String postalCode;
        private String countryCode;
        private String bankAccount;
        private String iban;
        private String swift;
    }

    /** Jeden řádek rekapitulace DPH (součet hodnot řádků seskupených podle sazby). */
    @Data
    public static class VatSummaryLine {
        private Short rate;
        private BigDecimal base;   // základ daně za sazbu
        private BigDecimal vat;    // výše DPH za sazbu
        private BigDecimal total;  // celkem za sazbu (základ + DPH)
    }

    /**
     * Request DTO pro založení nové faktury.
     * Číslo faktury ani variabilní symbol tu <strong>nejsou</strong> — koncept je nemá,
     * obojí vzniká až při vystavení ({@link IssueRequest}). {@code status} řídí výhradně server.
     */
    @Data
    public static class CreateRequest {

        @NotNull(message = "Zakázka je povinná")
        private Long orderId;

        @NotNull(message = "Fakturační adresa je povinná")
        private Long billingAddressId;

        @NotNull(message = "Datum vystavení je povinné")
        private LocalDate issueDate;

        @NotNull(message = "Datum splatnosti je povinné")
        private LocalDate dueDate;

        @NotNull(message = "Datum zdanitelného plnění je povinné")
        private LocalDate taxableSupplyDate;

        @Size(max = 15, message = "Konstantní symbol může mít maximálně 15 znaků")
        private String constantSymbol;

        @Size(max = 15, message = "Specifický symbol může mít maximálně 15 znaků")
        private String specificSymbol;

        private PaymentMethod paymentMethod;

        @Size(max = 2000, message = "Poznámka může mít maximálně 2000 znaků")
        private String note;

        /**
         * Číslo objednávky zákazníka — nákupní objednávka / PO (V91). Volný text: číslo
         * dodává zákazník ze svého systému, formát se proto nevynucuje.
         */
        @Size(max = 100, message = "Číslo objednávky může mít maximálně 100 znaků")
        private String purchaseOrderNumber;
    }

    /**
     * Response DTO návrhu dalšího čísla faktury ({@code GET /invoices/next-number}).
     * Při vypnutém automatickém číslování je {@code auto = false} a návrh {@code null}
     * — dialog pak nechá pole prázdné pro volný zápis.
     */
    @Data
    public static class NextNumberResponse {
        private boolean auto;
        private String invoiceNumber;
    }

    /**
     * Request DTO vystavení faktury ({@code POST /invoices/{id}/issue}).
     *
     * <p>Číslo i variabilní symbol dostává doklad až tady — v okamžiku, kdy odchází
     * zákazníkovi. Číslo je povinné (DB CHECK {@code chk_invoice_issued_has_number}
     * ho u ISSUED/PAID vyžaduje) a posílá ho vždy dialog vystavení: při zapnutém automatu
     * předvyplněné podle masky, při vypnutém prázdné k ručnímu zápisu. V obou režimech
     * lze zapsat libovolné číslo — maska je předpis pro generování návrhu, ne omezení
     * (rozhodnutí uživatele 2026-08-02).
     *
     * <p>Variabilní symbol je volitelný (u hotovostní faktury nemá co párovat); dialog ho
     * předvyplní číslicemi z čísla, server sám nedosazuje nic.
     *
     * <p><strong>Datum vystavení</strong> posílá dialog také — předvyplněné datem z konceptu
     * a obsluha ho může upravit. Doklad odchází s tímto datem; server ho už nepřerazítkovává
     * dneškem (rozhodnutí uživatele 2026-08-07, dříve audit KN-10). Číslo řady se skládá
     * z téhož data, takže se s dokladem nemůže rozejít o období. Hodnota není ničím
     * omezená — zpětné i budoucí datum je povolené (rozhodnutí uživatele 2026-08-09).
     */
    @Data
    public static class IssueRequest {

        @NotBlank(message = "Číslo faktury je povinné")
        @Size(max = 20, message = "Číslo faktury může mít maximálně 20 znaků")
        private String invoiceNumber;

        @NotNull(message = "Datum vystavení je povinné")
        private LocalDate issueDate;

        @Pattern(regexp = "[0-9]{1,10}", message = "Variabilní symbol smí obsahovat jen číslice (max. 10)")
        private String variableSymbol;
    }

    /**
     * Request DTO pro úpravu existující faktury.
     * Neměnná pole ({@code invoiceNumber}, {@code orderId}, {@code customerId}) jsou vynechána.
     */
    @Data
    public static class UpdateRequest {
        private LocalDate dueDate;

        @Size(max = 15, message = "Konstantní symbol může mít maximálně 15 znaků")
        private String constantSymbol;

        @Size(max = 15, message = "Specifický symbol může mít maximálně 15 znaků")
        private String specificSymbol;

        private PaymentMethod paymentMethod;
        private InvoiceStatus status;

        @Size(max = 2000, message = "Poznámka může mít maximálně 2000 znaků")
        private String note;

        /** Číslo objednávky zákazníka — nákupní objednávka / PO (V91). Volný text. */
        @Size(max = 100, message = "Číslo objednávky může mít maximálně 100 znaků")
        private String purchaseOrderNumber;
    }
}
