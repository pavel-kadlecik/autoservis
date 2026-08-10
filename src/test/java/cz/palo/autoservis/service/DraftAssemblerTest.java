package cz.palo.autoservis.service;

import cz.palo.autoservis.config.WarehouseImportProperties;
import cz.palo.autoservis.model.domain.warehouse.DocumentType;
import cz.palo.autoservis.model.draft.FieldState;
import cz.palo.autoservis.model.draft.ReceiptDraft;
import cz.palo.autoservis.model.dto.warehouse.DocumentExtractionResult;
import cz.palo.autoservis.model.dto.warehouse.DocumentExtractionResult.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Čisté unit testy skládání draftu — mapování sazeb, dopočty, defaulty. */
class DraftAssemblerTest {

    private final DraftAssembler assembler = new DraftAssembler(new WarehouseImportProperties());

    private static F f(String v) { return v == null ? new F(null, SourceState.ABSENT) : new F(v, SourceState.VERBATIM); }
    private static FDec f(BigDecimal v) { return v == null ? new FDec(null, SourceState.ABSENT) : new FDec(v, SourceState.VERBATIM); }

    private static Line item(String rateOrCode, String qty, String unitPrice,
                             String totalExcl, String totalIncl) {
        return new Line(LineKind.ITEM, 1,
                f("SKU-1"), f("Díl"), f("ks"),
                f(new BigDecimal(qty)), f(new BigDecimal(unitPrice)),
                rateOrCode == null ? new F(null, SourceState.ABSENT) : f(rateOrCode),
                totalExcl == null ? f((BigDecimal) null) : f(new BigDecimal(totalExcl)),
                totalIncl == null ? f((BigDecimal) null) : f(new BigDecimal(totalIncl)),
                null);
    }

    /** Řádek, který uvádí jen množství a cenu s DPH (jednotková cena i základ chybí). */
    private static Line grossOnlyItem(String qty, String totalIncl) {
        return new Line(LineKind.ITEM, 1,
                f("SKU-1"), f("Díl"), f("ks"),
                f(new BigDecimal(qty)),
                f((BigDecimal) null),                 // unitPriceExclVat ABSENT
                new F(null, SourceState.ABSENT),      // vatRateOrCode ABSENT → u DL default 21
                f((BigDecimal) null),                 // totalExclVat ABSENT
                f(new BigDecimal(totalIncl)),         // totalInclVat VERBATIM
                null);
    }

    private static DocumentExtractionResult doc(List<Line> lines, List<VatRecapRow> recap) {
        return new DocumentExtractionResult(
                new Header(f("DOC-1"), f((String) null), f((String) null),
                        null, null, null, f((String) null)),
                new Supplier("Dodavatel", "24787426", null, null, null, null, null, null, null),
                lines, recap, null);
    }

    @Test
    @DisplayName("písmenný kód sazby se mapuje přes rekapitulaci → DERIVED")
    void letterCodeMappedViaRecap() {
        var result = assembler.assemble(
                doc(List.of(item("C", "1", "100.00", "100.00", null)),
                        List.of(new VatRecapRow("C", 21, new BigDecimal("100.00"),
                                new BigDecimal("21.00")))),
                DocumentType.INVOICE, "test-model");

        var rate = result.getLines().get(0).getVatRate();
        assertThat(rate.getValue()).isEqualTo(21);
        assertThat(rate.getState()).isEqualTo(FieldState.DERIVED);
    }

    @Test
    @DisplayName("procentní sazba '21%' se parsuje → VERBATIM")
    void percentRateParsed() {
        var result = assembler.assemble(
                doc(List.of(item("21%", "1", "100.00", "100.00", "121.00")), null),
                DocumentType.INVOICE, "test-model");

        var rate = result.getLines().get(0).getVatRate();
        assertThat(rate.getValue()).isEqualTo(21);
        assertThat(rate.getState()).isEqualTo(FieldState.VERBATIM);
    }

    @Test
    @DisplayName("faktura bez sazby a bez rekapitulace → ABSENT (flag k revizi)")
    void invoiceMissingRateIsAbsent() {
        var result = assembler.assemble(
                doc(List.of(item(null, "1", "100.00", "100.00", null)), null),
                DocumentType.INVOICE, "test-model");

        assertThat(result.getLines().get(0).getVatRate().getState())
                .isEqualTo(FieldState.ABSENT);
    }

    @Test
    @DisplayName("dodací list: sazba DEFAULTED, hlavičkové součty DERIVED z řádků")
    void deliveryNoteDefaultsAndDerivedTotals() {
        ReceiptDraft result = assembler.assemble(
                doc(List.of(item(null, "1", "1189.39", "1189.39", null)), null),
                DocumentType.DELIVERY_NOTE, "test-model");

        var line = result.getLines().get(0);
        assertThat(line.getVatRate().getValue()).isEqualTo(21);
        assertThat(line.getVatRate().getState()).isEqualTo(FieldState.DEFAULTED);
        // dopočet: 1189.39 × 1.21 = 1439.16
        assertThat(line.getTotalInclVat().getValue()).isEqualByComparingTo("1439.16");
        assertThat(line.getTotalInclVat().getState()).isEqualTo(FieldState.DERIVED);

        var header = result.getHeader();
        assertThat(header.getSubtotal().getValue()).isEqualByComparingTo("1189.39");
        assertThat(header.getSubtotal().getState()).isEqualTo(FieldState.DERIVED);
        assertThat(header.getVatAmount().getValue()).isEqualByComparingTo("249.77");
        assertThat(header.getCurrency().getState()).isEqualTo(FieldState.DEFAULTED);
        assertThat(header.getCurrency().getValue()).isEqualTo("CZK");
    }

    @Test
    @DisplayName("skupinový řádek 'Dodací list č. X' se sebere do deliveryNoteRefs")
    void deliveryNoteGroupCollected() {
        Line group = new Line(LineKind.DELIVERY_NOTE_GROUP, 1,
                f((String) null), f("Dodací list č. 3726025144 celkem"), f((String) null),
                f((BigDecimal) null), f((BigDecimal) null),
                new F(null, SourceState.ABSENT),
                f(new BigDecimal("1996.12")), f((BigDecimal) null),
                "3726025144");

        var result = assembler.assemble(
                doc(List.of(group, item("21", "1", "100.00", "100.00", "121.00")), null),
                DocumentType.INVOICE, "test-model");

        assertThat(result.getDeliveryNoteRefs())
                .singleElement()
                .satisfies(ref -> assertThat(ref.getNumber()).isEqualTo("3726025144"));
        // skupinový řádek nevstupuje do hlavičkových dopočtů ani mezi ITEM řádky
        assertThat(result.getLines()).hasSize(2);
    }

    @Test
    @DisplayName("dodací list jen s cenou s DPH → zpětný přepočet základu a jednotkové ceny (DERIVED)")
    void grossOnlyPriceBackCalculated() {
        // ručně psaný DL: 2 ks, jen 'celkem s DPH' 968 Kč, žádná jednotková cena ani sazba
        ReceiptDraft result = assembler.assemble(
                doc(List.of(grossOnlyItem("2", "968.00")), null),
                DocumentType.DELIVERY_NOTE, "test-model");

        var line = result.getLines().get(0);
        assertThat(line.getVatRate().getValue()).isEqualTo(21);
        assertThat(line.getVatRate().getState()).isEqualTo(FieldState.DEFAULTED);
        // 968.00 / 1.21 = 800.00
        assertThat(line.getTotalExclVat().getValue()).isEqualByComparingTo("800.00");
        assertThat(line.getTotalExclVat().getState()).isEqualTo(FieldState.DERIVED);
        // 800.00 / 2 = 400.00
        assertThat(line.getUnitPriceExclVat().getValue()).isEqualByComparingTo("400.00");
        assertThat(line.getUnitPriceExclVat().getState()).isEqualTo(FieldState.DERIVED);
        // cena s DPH zůstala vytištěná
        assertThat(line.getTotalInclVat().getState()).isEqualTo(FieldState.VERBATIM);

        var header = result.getHeader();
        assertThat(header.getSubtotal().getValue()).isEqualByComparingTo("800.00");
        assertThat(header.getTotalAmount().getValue()).isEqualByComparingTo("968.00");
        assertThat(header.getVatAmount().getValue()).isEqualByComparingTo("168.00");
    }

    @Test
    @DisplayName("dopředná větev má přednost: se známou jednotkovou cenou se základ NEpočítá zpětně z ceny s DPH")
    void forwardDerivationTakesPrecedence() {
        // záměrně nekonzistentní: 1 × 100 bez DPH, ale s DPH 'jen' 150 (ne 121)
        ReceiptDraft result = assembler.assemble(
                doc(List.of(item("21", "1", "100.00", null, "150.00")), null),
                DocumentType.INVOICE, "test-model");

        var line = result.getLines().get(0);
        // základ = množství × jednotková cena (100), NE 150/1.21
        assertThat(line.getTotalExclVat().getValue()).isEqualByComparingTo("100.00");
        assertThat(line.getTotalExclVat().getState()).isEqualTo(FieldState.DERIVED);
        // vytištěné hodnoty se nepřepisují
        assertThat(line.getUnitPriceExclVat().getValue()).isEqualByComparingTo("100.00");
        assertThat(line.getUnitPriceExclVat().getState()).isEqualTo(FieldState.VERBATIM);
        assertThat(line.getTotalInclVat().getValue()).isEqualByComparingTo("150.00");
        assertThat(line.getTotalInclVat().getState()).isEqualTo(FieldState.VERBATIM);
    }
}
