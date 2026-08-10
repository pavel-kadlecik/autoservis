package cz.palo.autoservis.model.dto.warehouse;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Cílový tvar structured outputu AI extrakce dokladu (faktura / dodací list).
 * Spring AI z této třídy odvodí JSON schéma a odpověď modelu naparsuje.
 *
 * <p>Každé sledované pole je dvojice {@code {value, state}} — model u hodnoty
 * přiznává původ: VERBATIM (vytištěno doslova), DERIVED (dopočteno z jiných
 * hodnot dokladu), ABSENT (v dokladu není; value pak null). Na VERIFIED
 * povyšuje až deterministický kód, ne model.
 */
public record DocumentExtractionResult(
        Header header,
        Supplier supplier,
        List<Line> lines,
        List<VatRecapRow> vatRecap,
        Summary summary
) {
    /** Původ hodnoty z pohledu modelu. */
    public enum SourceState { VERBATIM, DERIVED, ABSENT }

    public record F(String value, SourceState state) {}
    public record FDec(BigDecimal value, SourceState state) {}
    public record FInt(Integer value, SourceState state) {}
    public record FDate(LocalDate value, SourceState state) {}

    public record Header(
            F documentNumber,          // číslo faktury / dodacího listu
            F orderNumber,
            F originalOrderNumber,
            FDate issueDate,
            FDate dueDate,
            FDate taxableSupplyDate,   // DUZP
            F currency
    ) {}

    /** Dodavatel — plochý; ověřuje ho kód (kontrolní součet IČO, shoda v DB). */
    public record Supplier(
            String name,
            String registrationNumber,   // IČO
            String vatId,                // DIČ
            String street,
            String city,
            String postalCode,
            String bankAccount,
            String iban,
            String swift
    ) {}

    public enum LineKind { ITEM, DELIVERY_NOTE_GROUP, NOTE }

    public record Line(
            LineKind kind,
            Integer position,
            F catalogNumber,
            F name,
            F unit,
            FDec quantity,
            FDec unitPriceExclVat,
            F vatRateOrCode,             // "21", "21%" nebo písmenný kód sazby ("C")
            FDec totalExclVat,
            FDec totalInclVat,
            String deliveryNoteNumber    // jen pro DELIVERY_NOTE_GROUP řádky
    ) {}

    /** Řádek rekapitulace DPH (LKQ: A 0 %, B 12 %, C 21 %). */
    public record VatRecapRow(
            String code,
            Integer ratePercent,
            BigDecimal base,
            BigDecimal vat
    ) {}

    public record Summary(
            FDec subtotal,
            FDec vatAmount,
            FDec totalAmount
    ) {}
}
