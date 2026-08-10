package cz.palo.autoservis.service;

import cz.palo.autoservis.config.WarehouseImportProperties;
import cz.palo.autoservis.mapper.WarehouseImportMapper;
import cz.palo.autoservis.model.draft.*;
import cz.palo.autoservis.model.draft.DeliveryNoteRef;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Deterministické kontroly draftu — druhá půlka principu „AI čte, kód počítá".
 *
 * <p>Projde draft, spočítá aritmetické křížové kontroly (řádková matematika,
 * součty vs. rekapitulace, základ + DPH = celkem), ověří IČO kontrolním
 * součtem a dohledá dodavatele v DB. Výsledky ukládá do draft.checks; vrací
 * reconciliationOk.
 *
 * <h3>Co znamená „ověřeno" (audit KN-17)</h3>
 * <p>Na VERIFIED se povyšuje jen pole, které obstálo proti <strong>nezávislému protějšku</strong> —
 * tedy proti údaji přečtenému z dokladu, kontrolnímu součtu nebo záznamu v DB. To je podstatné,
 * protože {@link DraftAssembler} chybějící částky <em>dopočítává</em> ({@code FieldState#DERIVED}):
 * základ z množství × ceny, cenu s DPH ze základu, hlavičkový součet ze řádků, DPH jako
 * rozdíl celkem − základ. Porovnat dopočtenou hodnotu s tím, z čeho vznikla, je tautologie —
 * projde vždycky, ať model přečetl cokoli.
 *
 * <p>Zakázaná je tedy <strong>tautologie</strong> („dopočet vs. jeho vlastní vstup"), ne kontrola
 * dopočtené hodnoty proti dokladu. Rozdíl je podstatný:
 * <ul>
 *   <li>{@code LINE_MATH} — obě půlky rovnice mají operandy z téhož dopočtu, takže se každá
 *       počítá jen tehdy, když v ní <em>žádný</em> operand není DERIVED;</li>
 *   <li>{@code LINES_SUM_VS_RECAP} — rekapitulace je z dokladu, takže potvrdí i dopočtený základ
 *       (a u písmenných kódů sazby teprve rozklíčuje, která sazba na řádku platí);</li>
 *   <li>{@code LINES_SUM_VS_TOTAL} — protějškem je celková částka z dokladu; nezávislost proto
 *       závisí na ní, ne na řádcích (řádky jsou to ověřované). Je-li dopočtená i ona, je kontrola
 *       tautologická.</li>
 * </ul>
 *
 * <p>Do zavedení tohoto rozlišení skončil ručně psaný dodací list bez rekapitulace se všemi poli
 * zeleně VERIFIED a {@code reconciliation_ok = true}, přestože nebylo ověřeno nic. Každá kontrola
 * proto nese vedle výsledku i příznak {@link DraftCheck#isIndependent()} a
 * {@code reconciliationOk} vyžaduje, aby aspoň jedna aritmetická kontrola nezávislá byla.
 *
 * <p>Nahrazuje dřívější InvoiceReconciliationValidator (jediná kontrola
 * Σ řádků vs. total je teď LINES_SUM_VS_TOTAL).
 */
@Component
@RequiredArgsConstructor
public class DraftVerificationService {

    private final WarehouseImportMapper mapper;
    private final SupplierNormalizer supplierNormalizer;
    private final WarehouseImportProperties props;

    /** Provede kontroly, upraví stavy polí a checks v draftu. @return reconciliationOk */
    public boolean verify(ReceiptDraft draft) {
        List<DraftCheck> checks = new ArrayList<>();

        boolean linesMath = verifyLines(draft, checks);
        boolean linesVsRecap = verifyLinesVsRecap(draft, checks);
        boolean recapSum = verifyRecapSum(draft, checks);
        boolean subtotalPlusVat = verifySubtotalPlusVat(draft, checks);
        boolean linesVsTotal = verifyLinesVsTotal(draft, checks);
        verifySupplier(draft, checks);

        draft.setChecks(checks);

        // Rekonciliace = VŠECHNY aritmetické kontroly — i vnitřně nekonzistentní
        // řádek (sazba vs. ceny) znamená, že model něco přečetl špatně.
        boolean allChecksPassed = linesMath && linesVsRecap && recapSum
                && subtotalPlusVat && linesVsTotal;

        // …ale „prošly" nestačí. Doklad, který neuvádí všechny částky, si je nechá dopočítat
        // assemblerem a kontroly pak porovnávají náš vlastní výpočet sám se sebou — projdou
        // vždycky. U ručně psaného dodacího listu bez rekapitulace tak dřív vyšlo
        // reconciliation_ok = true a všechna pole svítila zeleně VERIFIED, přestože nebylo
        // ověřeno vůbec nic (audit KN-17). Rekonciliaci proto přiznáme jen tehdy, když aspoň
        // jedna z aritmetických kontrol měla nezávislý protějšek.
        boolean anyArithmeticIndependent = checks.stream()
                .filter(c -> !DraftCheck.ICO_CHECKSUM.equals(c.getCode())
                        && !DraftCheck.SUPPLIER_KNOWN.equals(c.getCode()))
                .anyMatch(DraftCheck::isIndependent);
        boolean reconciliationOk = allChecksPassed && anyArithmeticIndependent;

        // Hlavičkové součty povýšíme jen tehdy, když je potvrdil nezávislý údaj z dokladu —
        // ne když jsme si je sami spočítali ze řádků a pak „ověřili" proti těmže řádkům.
        boolean headerIndependent = checks.stream()
                .filter(c -> DraftCheck.RECAP_SUM.equals(c.getCode())
                        || DraftCheck.SUBTOTAL_PLUS_VAT_EQ_TOTAL.equals(c.getCode())
                        || DraftCheck.LINES_SUM_VS_TOTAL.equals(c.getCode()))
                .anyMatch(DraftCheck::isIndependent);
        if (reconciliationOk && headerIndependent) {
            draft.getHeader().getSubtotal().verify();
            draft.getHeader().getVatAmount().verify();
            draft.getHeader().getTotalAmount().verify();
        }
        return reconciliationOk;
    }

    /**
     * Pole nese hodnotu, kterou jsme si dopočítali sami — porovnávat ji s tím, z čeho vznikla,
     * nic nedokazuje. {@code null} pole je taky „nespolehlivé" (nemá co ověřovat).
     */
    private static boolean notDerived(TrackedField<?> field) {
        return field != null && field.getState() != FieldState.DERIVED;
    }

    // ---------------------------------------------------------------- lines

    /** LINE_MATH: qty × cena = součet bez DPH a součet bez DPH × (1+sazba) = součet s DPH. */
    private boolean verifyLines(ReceiptDraft draft, List<DraftCheck> checks) {
        boolean allOk = true;
        for (DraftLine line : draft.getLines()) {
            if (line.getLineKind() != DraftLine.LineKind.ITEM) continue;

            BigDecimal qty = value(line.getQuantity());
            BigDecimal unitPrice = value(line.getUnitPriceExclVat());
            BigDecimal totalExcl = value(line.getTotalExclVat());
            BigDecimal totalIncl = value(line.getTotalInclVat());
            Integer rate = line.getVatRate() == null ? null : line.getVatRate().getValue();

            if (qty == null || unitPrice == null || totalExcl == null
                    || totalIncl == null || rate == null) {
                checks.add(DraftCheck.ofLine(DraftCheck.LINE_MATH, false, true, line.getPosition()));
                allOk = false;
                continue;
            }

            // Která půlka rovnice něco dokazuje: ta, jejíž operandy pocházejí z dokladu.
            // `deriveLineAmounts` umí základ dopočítat z množství × ceny (a naopak cenu ze
            // základu), a cenu s DPH ze základu — porovnat pak výsledek s jeho vlastním
            // vstupem je tautologie, ne kontrola (audit KN-17). Pravidlo je záměrně
            // konzervativní: stačí jeden dopočtený operand a půlka se nepočítá.
            boolean baseIndependent = notDerived(line.getQuantity())
                    && notDerived(line.getUnitPriceExclVat())
                    && notDerived(line.getTotalExclVat());
            boolean grossIndependent = notDerived(line.getTotalExclVat())
                    && notDerived(line.getTotalInclVat());

            boolean exclOk = withinTolerance(qty.multiply(unitPrice), totalExcl);
            BigDecimal factor = BigDecimal.ONE.add(BigDecimal.valueOf(rate).movePointLeft(2));
            boolean inclOk = withinTolerance(totalExcl.multiply(factor), totalIncl);

            boolean ok = exclOk && inclOk;
            checks.add(DraftCheck.ofLine(DraftCheck.LINE_MATH, ok,
                    baseIndependent || grossIndependent, line.getPosition()));
            if (!ok) {
                allOk = false;
                continue;
            }
            // Povyšuje se jen to, co skutečně obstálo proti údaji z dokladu.
            if (baseIndependent) {
                line.getQuantity().verify();
                line.getUnitPriceExclVat().verify();
                line.getTotalExclVat().verify();
            }
            if (grossIndependent) {
                line.getTotalExclVat().verify();
                line.getTotalInclVat().verify();
                line.getVatRate().verify();
            }
        }
        return allOk;
    }

    /** LINES_SUM_VS_RECAP: Σ základů řádků po sazbách vs. řádky rekapitulace. */
    private boolean verifyLinesVsRecap(ReceiptDraft draft, List<DraftCheck> checks) {
        List<ReceiptDraft.VatRecapRow> recap = draft.getVatRecap();
        if (recap == null || recap.isEmpty()) {
            return true;    // bez rekapitulace (dodací list) není co srovnávat
        }
        boolean ok = true;
        for (ReceiptDraft.VatRecapRow row : recap) {
            if (row.getRatePercent() == null || row.getBase() == null) continue;
            BigDecimal linesSum = draft.getLines().stream()
                    .filter(l -> l.getLineKind() == DraftLine.LineKind.ITEM)
                    .filter(l -> l.getVatRate() != null
                            && row.getRatePercent().equals(l.getVatRate().getValue()))
                    .map(l -> value(l.getTotalExclVat()))
                    .filter(java.util.Objects::nonNull)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            if (!withinTolerance(linesSum, row.getBase())) {
                ok = false;
            }
        }
        checks.add(DraftCheck.of(DraftCheck.LINES_SUM_VS_RECAP, ok));

        // Rekapitulace pochází z DOKLADU, takže je to plnohodnotný nezávislý protějšek —
        // i pro hodnoty, které jsme si dopočítali. Sedí-li součet základů po sazbách, je tím
        // potvrzený jak základ řádku, tak jeho sazba (u písmenných kódů „C" ji rekapitulace
        // teprve rozklíčovala). Proto se povyšuje i DERIVED základ (KN-17: zakázaná je jen
        // tautologie „vlastní dopočet vs. jeho vlastní vstup", ne kontrola proti dokladu).
        if (ok) {
            for (DraftLine line : draft.getLines()) {
                if (line.getLineKind() != DraftLine.LineKind.ITEM) continue;
                if (line.getVatRate() == null || line.getVatRate().getValue() == null) continue;
                boolean rateInRecap = recap.stream()
                        .anyMatch(r -> line.getVatRate().getValue().equals(r.getRatePercent()));
                if (rateInRecap) {
                    line.getVatRate().verify();
                    if (line.getTotalExclVat() != null) {
                        line.getTotalExclVat().verify();
                    }
                }
            }
        }
        return ok;
    }

    /** RECAP_SUM: Σ rekapitulace = hlavičkové subtotal / vatAmount. */
    private boolean verifyRecapSum(ReceiptDraft draft, List<DraftCheck> checks) {
        List<ReceiptDraft.VatRecapRow> recap = draft.getVatRecap();
        BigDecimal subtotal = value(draft.getHeader().getSubtotal());
        BigDecimal vatAmount = value(draft.getHeader().getVatAmount());
        if (recap == null || recap.isEmpty() || subtotal == null || vatAmount == null) {
            return true;
        }
        BigDecimal baseSum = recap.stream()
                .map(ReceiptDraft.VatRecapRow::getBase)
                .filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal vatSum = recap.stream()
                .map(ReceiptDraft.VatRecapRow::getVat)
                .filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        boolean ok = withinTolerance(baseSum, subtotal) && withinTolerance(vatSum, vatAmount);
        checks.add(DraftCheck.of(DraftCheck.RECAP_SUM, ok));
        return ok;
    }

    /** SUBTOTAL_PLUS_VAT_EQ_TOTAL. */
    private boolean verifySubtotalPlusVat(ReceiptDraft draft, List<DraftCheck> checks) {
        BigDecimal subtotal = value(draft.getHeader().getSubtotal());
        BigDecimal vatAmount = value(draft.getHeader().getVatAmount());
        BigDecimal totalAmount = value(draft.getHeader().getTotalAmount());
        if (subtotal == null || vatAmount == null || totalAmount == null) {
            checks.add(DraftCheck.of(DraftCheck.SUBTOTAL_PLUS_VAT_EQ_TOTAL, false));
            return false;
        }
        // Pozor na tautologii: když v dokladu chybí DPH, assembler ho dopočítá jako
        // total − subtotal — pak tahle rovnice platí z definice a neprokáže nic (KN-17).
        boolean independent = notDerived(draft.getHeader().getSubtotal())
                && notDerived(draft.getHeader().getVatAmount())
                && notDerived(draft.getHeader().getTotalAmount());

        boolean ok = withinTolerance(subtotal.add(vatAmount), totalAmount);
        checks.add(DraftCheck.of(DraftCheck.SUBTOTAL_PLUS_VAT_EQ_TOTAL, ok, independent));
        return ok;
    }

    /** LINES_SUM_VS_TOTAL: Σ řádků s DPH vs. celková částka (dřívější rekonciliace). */
    private boolean verifyLinesVsTotal(ReceiptDraft draft, List<DraftCheck> checks) {
        BigDecimal totalAmount = value(draft.getHeader().getTotalAmount());
        if (totalAmount == null) {
            checks.add(DraftCheck.of(DraftCheck.LINES_SUM_VS_TOTAL, false));
            return false;
        }
        // Nezávislost tu stojí a padá s CELKOVOU ČÁSTKOU, ne s řádky: řádkové částky jsou to,
        // co se ověřuje, celková částka je protějšek. Chybí-li ale celková částka v dokladu,
        // assembler ji dopočítá jako součet týchž řádků — pak kontrola porovnává součet sám
        // se sebou a neprokáže nic (KN-17).
        boolean independent = notDerived(draft.getHeader().getTotalAmount());

        BigDecimal linesSum = BigDecimal.ZERO;
        for (DraftLine line : draft.getLines()) {
            if (line.getLineKind() != DraftLine.LineKind.ITEM) continue;
            BigDecimal incl = value(line.getTotalInclVat());
            if (incl == null) {
                checks.add(DraftCheck.of(DraftCheck.LINES_SUM_VS_TOTAL, false, independent));
                return false;
            }
            linesSum = linesSum.add(incl);
        }
        boolean ok = withinTolerance(linesSum, totalAmount);
        checks.add(DraftCheck.of(DraftCheck.LINES_SUM_VS_TOTAL, ok, independent));

        // Součet řádků s DPH sedí na částku vytištěnou v dokladu → dopočtené řádkové částky
        // s DPH mají nezávislé potvrzení a smí na VERIFIED.
        if (ok && independent) {
            for (DraftLine line : draft.getLines()) {
                if (line.getLineKind() != DraftLine.LineKind.ITEM) continue;
                if (line.getTotalInclVat() != null) {
                    line.getTotalInclVat().verify();
                }
            }
        }
        return ok;
    }

    // ---------------------------------------------------------------- DL refs

    /**
     * Dedup DL ↔ faktura: k číslům dodacích listů referencovaným na faktuře
     * (LKQ skupinové řádky) dohledá existující DELIVERY_NOTE příjemky.
     * Už napárované reference nepřepisuje (drží rozhodnutí uživatele).
     */
    public void matchDeliveryNoteRefs(ReceiptDraft draft, Long excludeReceiptId) {
        if (draft.getDocumentType() != cz.palo.autoservis.model.domain.warehouse.DocumentType.INVOICE
                || draft.getDeliveryNoteRefs() == null) {
            return;
        }
        Long supplierId = draft.getSupplier() == null
                ? null : draft.getSupplier().getMatchedSupplierId();
        for (DeliveryNoteRef ref : draft.getDeliveryNoteRefs()) {
            if (ref.getMatchedReceiptId() == null && ref.getNumber() != null) {
                mapper.findDeliveryNoteReceiptId(supplierId, ref.getNumber(), excludeReceiptId)
                        .ifPresent(ref::setMatchedReceiptId);
            }
        }
    }

    // ---------------------------------------------------------------- supplier

    private void verifySupplier(ReceiptDraft draft, List<DraftCheck> checks) {
        DraftSupplier supplier = draft.getSupplier();
        if (supplier == null || supplier.getExtracted() == null) {
            checks.add(DraftCheck.of(DraftCheck.SUPPLIER_KNOWN, false));
            return;
        }
        String normalized = supplierNormalizer
                .normalizeRegistrationNumber(supplier.getExtracted().getRegistrationNumber());
        supplier.getExtracted().setRegistrationNumber(normalized);

        boolean checksumOk = normalized != null && icoChecksumValid(normalized);
        supplier.setIcoChecksumOk(checksumOk);
        checks.add(DraftCheck.of(DraftCheck.ICO_CHECKSUM, checksumOk));

        Optional<Long> existing = normalized == null
                ? Optional.empty()
                : mapper.findSupplierIdByIco(normalized);
        if (existing.isPresent()) {
            supplier.setMatchedSupplierId(existing.get());
            supplier.setMatchState(DraftSupplier.MatchState.AUTO);
            checks.add(DraftCheck.of(DraftCheck.SUPPLIER_KNOWN, true));
        } else {
            supplier.setMatchState(DraftSupplier.MatchState.NONE);
            checks.add(DraftCheck.of(DraftCheck.SUPPLIER_KNOWN, false));
        }
    }

    /**
     * Kontrolní součet českého IČO (mod 11, váhy 8..2).
     * 8místné číslo; poslední číslice je kontrolní.
     */
    boolean icoChecksumValid(String ico) {
        if (ico == null || !ico.matches("\\d{8}")) {
            return false;
        }
        int sum = 0;
        for (int i = 0; i < 7; i++) {
            sum += Character.getNumericValue(ico.charAt(i)) * (8 - i);
        }
        int mod = sum % 11;
        int expected = (11 - mod) % 10;   // pokrývá i speciální případy mod 0 → 1, mod 1 → 0
        return Character.getNumericValue(ico.charAt(7)) == expected;
    }

    // ---------------------------------------------------------------- helpers

    private boolean withinTolerance(BigDecimal a, BigDecimal b) {
        return a.setScale(2, RoundingMode.HALF_UP)
                .subtract(b.setScale(2, RoundingMode.HALF_UP))
                .abs()
                .compareTo(props.getDefaults().getTolerance()) <= 0;
    }

    private BigDecimal value(TrackedField<BigDecimal> field) {
        return field == null ? null : field.getValue();
    }
}
