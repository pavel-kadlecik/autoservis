package cz.palo.autoservis.service;

import cz.palo.autoservis.config.WarehouseImportProperties;
import cz.palo.autoservis.model.domain.warehouse.DocumentType;
import cz.palo.autoservis.model.draft.DraftLine;
import cz.palo.autoservis.model.draft.FieldState;
import cz.palo.autoservis.model.draft.ReceiptDraft;
import cz.palo.autoservis.model.draft.TrackedField;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code DraftAssembler.fillDerivedValues} — dopočet chybějících hodnot pro ruční příjemku
 * a editaci v review. Doplňuje {@code DraftAssemblerTest} (ten pokrývá assemble z AI extrakce).
 *
 * <p>Toto je „kód počítá" v čisté podobě: uživatel doplní množství a cenu, kód dopočte
 * řádkové i hlavičkové součty. Testuje se, že se <strong>ABSENT hodnoty dopočítají</strong>
 * se stavem DERIVED/DEFAULTED, ale <strong>už vyplněné se nikdy nepřepíšou</strong> —
 * jinak by kód zahodil to, co obsluha ručně zadala.
 */
class DraftAssemblerDerivationTest {

    private final DraftAssembler assembler = new DraftAssembler(new WarehouseImportProperties());

    // =========================================================================
    // Řádkové dopočty
    // =========================================================================

    @Test
    @DisplayName("chybějící součet bez DPH se dopočte z množství × cena jako DERIVED")
    void fillDerived_totalExclFromQuantityAndPrice() {
        DraftLine line = itemLineWithoutTotals("3", "100.00", 21);
        ReceiptDraft draft = invoiceDraftWithLine(line);

        assembler.fillDerivedValues(draft);

        assertThat(line.getTotalExclVat().getValue()).isEqualByComparingTo("300.00");
        assertThat(line.getTotalExclVat().getState()).isEqualTo(FieldState.DERIVED);
    }

    @Test
    @DisplayName("chybějící součet s DPH se dopočte ze základu × (1 + sazba) jako DERIVED")
    void fillDerived_totalInclFromExclAndRate() {
        DraftLine line = itemLineWithoutTotals("3", "100.00", 21);
        ReceiptDraft draft = invoiceDraftWithLine(line);

        assembler.fillDerivedValues(draft);

        assertThat(line.getTotalInclVat().getValue()).isEqualByComparingTo("363.00"); // 300 × 1.21
        assertThat(line.getTotalInclVat().getState()).isEqualTo(FieldState.DERIVED);
    }

    @Test
    @DisplayName("už vyplněný součet se NEPŘEPÍŠE (drží ruční zadání obsluhy)")
    void fillDerived_doesNotOverwriteExistingTotal() {
        DraftLine line = itemLineWithoutTotals("3", "100.00", 21);
        line.setTotalExclVat(TrackedField.of(new BigDecimal("299.00"), FieldState.EDITED)); // ruční hodnota
        ReceiptDraft draft = invoiceDraftWithLine(line);

        assembler.fillDerivedValues(draft);

        assertThat(line.getTotalExclVat().getValue())
                .as("ruční hodnota 299 se nesmí přepsat na dopočtených 300").isEqualByComparingTo("299.00");
        assertThat(line.getTotalExclVat().getState()).isEqualTo(FieldState.EDITED);
    }

    @Test
    @DisplayName("chybějící jednotka se u položky doplní defaultem 'ks' (DEFAULTED)")
    void fillDerived_unitDefault() {
        DraftLine line = itemLineWithoutTotals("3", "100.00", 21);
        line.setUnit(TrackedField.absent());
        ReceiptDraft draft = invoiceDraftWithLine(line);

        assembler.fillDerivedValues(draft);

        assertThat(line.getUnit().getValue()).isEqualTo("ks");
        assertThat(line.getUnit().getState()).isEqualTo(FieldState.DEFAULTED);
    }

    @Test
    @DisplayName("chybějící sazba se doplní defaultem JEN u dodacího listu, ne u faktury")
    void fillDerived_rateDefault_onlyForDeliveryNote() {
        DraftLine invoiceLine = itemLineWithoutTotals("3", "100.00", 21);
        invoiceLine.setVatRate(TrackedField.absent());
        ReceiptDraft invoice = invoiceDraftWithLine(invoiceLine);

        assembler.fillDerivedValues(invoice);

        assertThat(invoiceLine.getVatRate().getValue())
                .as("u faktury chybějící sazba zůstane ABSENT — flag k revizi").isNull();

        DraftLine dnLine = itemLineWithoutTotals("3", "100.00", 21);
        dnLine.setVatRate(TrackedField.absent());
        ReceiptDraft deliveryNote = draftWithLine(DocumentType.DELIVERY_NOTE, dnLine);

        assembler.fillDerivedValues(deliveryNote);

        assertThat(dnLine.getVatRate().getValue()).as("u dodacího listu se doplní default 21").isEqualTo(21);
        assertThat(dnLine.getVatRate().getState()).isEqualTo(FieldState.DEFAULTED);
    }

    @Test
    @DisplayName("bez množství nebo ceny se součet nedopočítá (zůstane ABSENT)")
    void fillDerived_missingInputs_leavesTotalAbsent() {
        DraftLine line = DraftLine.builder()
                .lineKind(DraftLine.LineKind.ITEM)
                .position(1)
                .name(TrackedField.of("Díl", FieldState.VERBATIM))
                .unit(TrackedField.of("ks", FieldState.VERBATIM))
                .quantity(TrackedField.of(new BigDecimal("3"), FieldState.VERBATIM))
                .unitPriceExclVat(TrackedField.absent())          // chybí cena
                .vatRate(TrackedField.of(21, FieldState.VERBATIM))
                .totalExclVat(TrackedField.absent())
                .totalInclVat(TrackedField.absent())
                .build();
        ReceiptDraft draft = invoiceDraftWithLine(line);

        assembler.fillDerivedValues(draft);

        assertThat(line.getTotalExclVat().getValue()).as("bez ceny se nedopočítá").isNull();
    }

    @Test
    @DisplayName("skupinový (nepoložkový) řádek se přeskočí — nedostane jednotku ani dopočet")
    void fillDerived_skipsNonItemLine() {
        DraftLine group = DraftLine.builder()
                .lineKind(DraftLine.LineKind.DELIVERY_NOTE_GROUP)
                .position(1)
                .name(TrackedField.of("Dodací list č. 5", FieldState.VERBATIM))
                .unit(TrackedField.absent())
                .quantity(TrackedField.absent())
                .unitPriceExclVat(TrackedField.absent())
                .vatRate(TrackedField.absent())
                .totalExclVat(TrackedField.absent())
                .totalInclVat(TrackedField.absent())
                .build();
        ReceiptDraft draft = invoiceDraftWithLine(group);

        assembler.fillDerivedValues(draft);

        assertThat(group.getUnit().getValue()).as("skupinový řádek jednotku nedostane").isNull();
        assertThat(group.getTotalExclVat().getValue()).isNull();
    }

    // =========================================================================
    // Hlavičkové dopočty
    // =========================================================================

    @Test
    @DisplayName("chybějící subtotal/total se dopočtou ze součtu řádků; vatAmount jako jejich rozdíl")
    void fillDerived_headerSumsFromLines() {
        DraftLine a = fullItemLine("2", "500.00", 21, "1000.00", "1210.00");
        DraftLine b = fullItemLine("1", "500.00", 21, "500.00", "605.00");
        ReceiptDraft draft = draftWithLines(DocumentType.INVOICE, List.of(a, b), true);

        assembler.fillDerivedValues(draft);

        assertThat(draft.getHeader().getSubtotal().getValue()).isEqualByComparingTo("1500.00");
        assertThat(draft.getHeader().getSubtotal().getState()).isEqualTo(FieldState.DERIVED);
        assertThat(draft.getHeader().getTotalAmount().getValue()).isEqualByComparingTo("1815.00");
        assertThat(draft.getHeader().getVatAmount().getValue())
                .as("DPH = total − subtotal").isEqualByComparingTo("315.00");
        assertThat(draft.getHeader().getVatAmount().getState()).isEqualTo(FieldState.DERIVED);
    }

    @Test
    @DisplayName("chybějící měna se doplní defaultem CZK (DEFAULTED)")
    void fillDerived_currencyDefault() {
        ReceiptDraft draft = draftWithLines(DocumentType.INVOICE,
                List.of(fullItemLine("1", "100.00", 21, "100.00", "121.00")), false);
        draft.getHeader().setCurrency(TrackedField.absent());

        assembler.fillDerivedValues(draft);

        assertThat(draft.getHeader().getCurrency().getValue()).isEqualTo("CZK");
        assertThat(draft.getHeader().getCurrency().getState()).isEqualTo(FieldState.DEFAULTED);
    }

    @Test
    @DisplayName("vyplněná hlavička se nepřepíše dopočtem")
    void fillDerived_doesNotOverwriteExistingHeader() {
        ReceiptDraft draft = draftWithLines(DocumentType.INVOICE,
                List.of(fullItemLine("2", "500.00", 21, "1000.00", "1210.00")), false);
        draft.getHeader().setSubtotal(TrackedField.of(new BigDecimal("999.00"), FieldState.VERBATIM));

        assembler.fillDerivedValues(draft);

        assertThat(draft.getHeader().getSubtotal().getValue())
                .as("opsaný subtotal se nedopočítává znovu").isEqualByComparingTo("999.00");
    }

    // =========================================================================
    // Fixtury
    // =========================================================================

    private static DraftLine itemLineWithoutTotals(String qty, String unitPrice, int rate) {
        return DraftLine.builder()
                .lineKind(DraftLine.LineKind.ITEM)
                .position(1)
                .catalogNumber(TrackedField.of("SKU-1", FieldState.VERBATIM))
                .name(TrackedField.of("Díl", FieldState.VERBATIM))
                .unit(TrackedField.of("ks", FieldState.VERBATIM))
                .quantity(TrackedField.of(new BigDecimal(qty), FieldState.VERBATIM))
                .unitPriceExclVat(TrackedField.of(new BigDecimal(unitPrice), FieldState.VERBATIM))
                .vatRate(TrackedField.of(rate, FieldState.VERBATIM))
                .totalExclVat(TrackedField.absent())
                .totalInclVat(TrackedField.absent())
                .build();
    }

    private static DraftLine fullItemLine(String qty, String unitPrice, int rate,
                                          String totalExcl, String totalIncl) {
        return DraftLine.builder()
                .lineKind(DraftLine.LineKind.ITEM)
                .position(1)
                .catalogNumber(TrackedField.of("SKU-1", FieldState.VERBATIM))
                .name(TrackedField.of("Díl", FieldState.VERBATIM))
                .unit(TrackedField.of("ks", FieldState.VERBATIM))
                .quantity(TrackedField.of(new BigDecimal(qty), FieldState.VERBATIM))
                .unitPriceExclVat(TrackedField.of(new BigDecimal(unitPrice), FieldState.VERBATIM))
                .vatRate(TrackedField.of(rate, FieldState.VERBATIM))
                .totalExclVat(TrackedField.of(new BigDecimal(totalExcl), FieldState.VERBATIM))
                .totalInclVat(TrackedField.of(new BigDecimal(totalIncl), FieldState.VERBATIM))
                .build();
    }

    private static ReceiptDraft invoiceDraftWithLine(DraftLine line) {
        return draftWithLine(DocumentType.INVOICE, line);
    }

    private static ReceiptDraft draftWithLine(DocumentType type, DraftLine line) {
        return draftWithLines(type, List.of(line), true);
    }

    /** @param emptyHeader true = hlavička plná ABSENT (ať se dopočítává), false = vyplněné součty */
    private static ReceiptDraft draftWithLines(DocumentType type, List<DraftLine> lines, boolean emptyHeader) {
        ReceiptDraft.Header.HeaderBuilder header = ReceiptDraft.Header.builder()
                .documentNumber(TrackedField.of("F-1", FieldState.VERBATIM))
                .orderNumber(TrackedField.absent())
                .originalOrderNumber(TrackedField.absent())
                .issueDate(TrackedField.absent())
                .dueDate(TrackedField.absent())
                .taxableSupplyDate(TrackedField.absent())
                .currency(TrackedField.of("CZK", FieldState.VERBATIM));
        if (emptyHeader) {
            header.subtotal(TrackedField.absent())
                    .vatAmount(TrackedField.absent())
                    .totalAmount(TrackedField.absent());
        } else {
            header.subtotal(TrackedField.of(new BigDecimal("1000.00"), FieldState.VERBATIM))
                    .vatAmount(TrackedField.of(new BigDecimal("210.00"), FieldState.VERBATIM))
                    .totalAmount(TrackedField.of(new BigDecimal("1210.00"), FieldState.VERBATIM));
        }
        return ReceiptDraft.builder()
                .schemaVersion(1)
                .documentType(type)
                .header(header.build())
                .vatRecap(new ArrayList<>())
                .deliveryNoteRefs(new ArrayList<>())
                .lines(new ArrayList<>(lines))
                .checks(new ArrayList<>())
                .build();
    }
}
