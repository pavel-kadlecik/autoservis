package cz.palo.autoservis.model.domain.billing;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

/**
 * Doménový objekt identity vystavující firmy (dodavatele) —
 * mapuje se na jediný řádek {@code billing.company_profile}.
 *
 * <p>Živá, editovatelná identita vlastní firmy. V okamžiku vystavení faktury
 * se snapshotuje do {@code billing.invoice_party} (role {@code SUPPLIER}),
 * takže pozdější změny tady nikdy nemění historické faktury.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CompanyProfile {

    private Integer        id;
    private String         name;
    private String         ico;
    private String         dic;
    private String         street;
    private String         streetNumber;
    private String         city;
    private String         postalCode;
    private String         countryCode;
    private String         bankAccount;
    private String         iban;
    private String         swift;
    /** Zda se číslo faktury skládá podle masky a předvyplňuje v dialogu (V71). */
    private Boolean        invoiceNumberAuto;
    /** Maska číselné řady faktur — tokeny {RRRR} {RR} {MM} {N…}, viz DocumentNumberMask (V71). */
    private String         invoiceNumberMask;

    /** Hlídat mezery v číselné řadě a varovat nad seznamem faktur (V89). */
    private Boolean        invoiceGapCheckEnabled;

    /** Číslo, od kterého se hlídá; starší se ignorují. NULL = od pořadí 1. */
    private String         invoiceGapCheckFrom;

    /** Zdroj čísla PPD v dialogu vystavení — MASK / INVOICE / MANUAL (V93, dřív boolean auto z V92). */
    private cz.palo.autoservis.model.enums.CashReceiptNumberSource cashReceiptNumberSource;

    /** Maska číselné řady pokladních dokladů — tokeny {RRRR} {RR} {MM} {N…}, viz DocumentNumberMask (V92). */
    private String         cashReceiptNumberMask;

    /** Hlídat mezery v číselné řadě pokladních dokladů (V92). */
    private Boolean        cashReceiptGapCheckEnabled;

    /** Číslo PPD, od kterého se hlídá; starší se ignorují. NULL = od pořadí 1. */
    private String         cashReceiptGapCheckFrom;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
