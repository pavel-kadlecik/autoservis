package cz.palo.autoservis.service;

import cz.palo.autoservis.config.WarehouseImportProperties;
import cz.palo.autoservis.model.domain.warehouse.DocumentType;
import cz.palo.autoservis.model.draft.DraftSupplier;
import cz.palo.autoservis.model.draft.FieldState;
import cz.palo.autoservis.model.draft.ReceiptDraft;
import cz.palo.autoservis.model.dto.warehouse.DocumentExtractionResult;
import cz.palo.autoservis.model.dto.warehouse.DocumentExtractionResult.F;
import cz.palo.autoservis.model.dto.warehouse.DocumentExtractionResult.FDec;
import cz.palo.autoservis.model.dto.warehouse.DocumentExtractionResult.Header;
import cz.palo.autoservis.model.dto.warehouse.DocumentExtractionResult.Line;
import cz.palo.autoservis.model.dto.warehouse.DocumentExtractionResult.LineKind;
import cz.palo.autoservis.model.dto.warehouse.DocumentExtractionResult.SourceState;
import cz.palo.autoservis.model.dto.warehouse.DocumentExtractionResult.Summary;
import cz.palo.autoservis.model.dto.warehouse.DocumentExtractionResult.Supplier;
import cz.palo.autoservis.model.dto.warehouse.DocumentExtractionResult.VatRecapRow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code DraftAssembler.assemble} — mapovací větve, které {@code DraftAssemblerTest}
 * nerozlišuje: hlavička s dodanými součty vs. dopočet z řádků, přenos původu pole
 * (VERBATIM/DERIVED), defaulty a null vstupy z extrakce.
 *
 * <p>Cíl: „AI čte" — model dodá pole s příznakem původu a assembler ho věrně přenese;
 * dopočítává jen to, co je ABSENT. Rozdíl mezi „opsáno z dokladu" a „dopočteno kódem"
 * je pro obsluhu v review zásadní (co může věřit vs. co si má zkontrolovat).
 */
class DraftAssemblerMappingTest {

    private final DraftAssembler assembler = new DraftAssembler(new WarehouseImportProperties());

    // =========================================================================
    // Hlavička: dodané součty se přeberou, nedopočítávají se z řádků
    // =========================================================================

    @Test
    @DisplayName("dodané součty (summary) se přeberou jako VERBATIM, ne dopočtou z řádků")
    void header_withSummary_takesValuesVerbatimNotDerived() {
        // Řádky by daly subtotal 100, ale summary tvrdí 999 → musí vyhrát summary (opsáno z dokladu).
        Summary summary = new Summary(
                dec("999.00"), dec("209.79"), dec("1208.79"));
        DocumentExtractionResult ex = doc(
                List.of(item("21", "1", "100.00", "100.00", "121.00")), null, summary);

        ReceiptDraft draft = assembler.assemble(ex, DocumentType.INVOICE, "test-model");

        assertThat(draft.getHeader().getSubtotal().getValue()).isEqualByComparingTo("999.00");
        assertThat(draft.getHeader().getSubtotal().getState())
                .as("opsaný součet je VERBATIM, ne DERIVED z řádků").isEqualTo(FieldState.VERBATIM);
        assertThat(draft.getHeader().getVatAmount().getValue()).isEqualByComparingTo("209.79");
        assertThat(draft.getHeader().getTotalAmount().getValue()).isEqualByComparingTo("1208.79");
    }

    @Test
    @DisplayName("chybí-li summary, hlavičkové součty se dopočtou z řádků jako DERIVED")
    void header_withoutSummary_derivesFromLines() {
        DocumentExtractionResult ex = doc(
                List.of(item("21", "2", "500.00", "1000.00", "1210.00")), null, null);

        ReceiptDraft draft = assembler.assemble(ex, DocumentType.INVOICE, "test-model");

        assertThat(draft.getHeader().getSubtotal().getValue()).isEqualByComparingTo("1000.00");
        assertThat(draft.getHeader().getSubtotal().getState()).isEqualTo(FieldState.DERIVED);
        assertThat(draft.getHeader().getTotalAmount().getValue()).isEqualByComparingTo("1210.00");
        assertThat(draft.getHeader().getVatAmount().getValue()).isEqualByComparingTo("210.00");
    }

    @Test
    @DisplayName("dodaná měna se přenese, jinak default CZK (DEFAULTED)")
    void header_currency_verbatimOrDefault() {
        DocumentExtractionResult withEur = new DocumentExtractionResult(
                new Header(f("F-1"), absent(), absent(), null, null, null, f("EUR")),
                supplier(), List.of(item("21", "1", "100.00", "100.00", "121.00")), null, null);
        assertThat(assembler.assemble(withEur, DocumentType.INVOICE, "m").getHeader().getCurrency())
                .satisfies(c -> {
                    assertThat(c.getValue()).isEqualTo("EUR");
                    assertThat(c.getState()).isEqualTo(FieldState.VERBATIM);
                });

        DocumentExtractionResult withoutCurrency = doc(
                List.of(item("21", "1", "100.00", "100.00", "121.00")), null, null);
        assertThat(assembler.assemble(withoutCurrency, DocumentType.INVOICE, "m").getHeader().getCurrency())
                .satisfies(c -> {
                    assertThat(c.getValue()).isEqualTo("CZK");
                    assertThat(c.getState()).isEqualTo(FieldState.DEFAULTED);
                });
    }

    @Test
    @DisplayName("všechna hlavičková pole (čísla i data) se přenesou z extrakce")
    void header_allFields_areCarried() {
        Header header = new Header(
                f("F-2026-001"),
                f("ZAK-2026-0001"),
                f("PUV-123"),
                new DocumentExtractionResult.FDate(java.time.LocalDate.of(2026, 7, 1), SourceState.VERBATIM),
                new DocumentExtractionResult.FDate(java.time.LocalDate.of(2026, 7, 15), SourceState.VERBATIM),
                new DocumentExtractionResult.FDate(java.time.LocalDate.of(2026, 7, 1), SourceState.VERBATIM),
                f("CZK"));
        DocumentExtractionResult ex = new DocumentExtractionResult(
                header, supplier(),
                List.of(item("21", "1", "100.00", "100.00", "121.00")), null, null);

        ReceiptDraft.Header result = assembler.assemble(ex, DocumentType.INVOICE, "m").getHeader();

        assertThat(result.getDocumentNumber().getValue()).isEqualTo("F-2026-001");
        assertThat(result.getOrderNumber().getValue()).isEqualTo("ZAK-2026-0001");
        assertThat(result.getOriginalOrderNumber().getValue()).isEqualTo("PUV-123");
        assertThat(result.getIssueDate().getValue()).isEqualTo(java.time.LocalDate.of(2026, 7, 1));
        assertThat(result.getDueDate().getValue()).isEqualTo(java.time.LocalDate.of(2026, 7, 15));
        assertThat(result.getTaxableSupplyDate().getValue()).isEqualTo(java.time.LocalDate.of(2026, 7, 1));
    }

    // =========================================================================
    // Původ pole (VERBATIM vs DERIVED z extrakce)
    // =========================================================================

    @Test
    @DisplayName("pole označené modelem jako DERIVED si příznak DERIVED zachová")
    void field_derivedSourceState_isPreserved() {
        Line line = new Line(LineKind.ITEM, 1,
                new F("SKU-1", SourceState.VERBATIM),
                new F("Díl", SourceState.DERIVED),        // model přiznává, že název odvodil
                f("ks"),
                dec("1"), dec("100.00"),
                f("21"), dec("100.00"), dec("121.00"), null);
        DocumentExtractionResult ex = doc(List.of(line), null, null);

        ReceiptDraft draft = assembler.assemble(ex, DocumentType.INVOICE, "test-model");

        assertThat(draft.getLines().getFirst().getName().getState())
                .as("DERIVED z modelu se nesmí ztratit ani povýšit na VERBATIM")
                .isEqualTo(FieldState.DERIVED);
    }

    // =========================================================================
    // Řádky: jednotka, dopočet, přeskočení skupinových
    // =========================================================================

    @Test
    @DisplayName("chybějící jednotka u položky faktury se doplní defaultem 'ks'")
    void line_missingUnit_getsDefault() {
        Line line = new Line(LineKind.ITEM, 1,
                f("SKU-1"), f("Díl"), absent(),          // jednotka ABSENT
                dec("1"), dec("100.00"),
                f("21"), dec("100.00"), dec("121.00"), null);
        DocumentExtractionResult ex = doc(List.of(line), null, null);

        ReceiptDraft draft = assembler.assemble(ex, DocumentType.INVOICE, "test-model");

        assertThat(draft.getLines().getFirst().getUnit().getValue()).isEqualTo("ks");
        assertThat(draft.getLines().getFirst().getUnit().getState()).isEqualTo(FieldState.DEFAULTED);
    }

    @Test
    @DisplayName("chybějící řádkový součet se při skládání dopočte z množství × cena (DERIVED)")
    void line_missingTotalExcl_isDerivedAtAssembly() {
        // totalExcl ABSENT, qty a cena přítomné → assemble dopočte 2 × 500 = 1000
        Line line = new Line(LineKind.ITEM, 1,
                f("SKU-1"), f("Díl"), f("ks"),
                dec("2"), dec("500.00"),
                f("21"), absentDec(), absentDec(), null);
        DocumentExtractionResult ex = doc(List.of(line), null, null);

        ReceiptDraft draft = assembler.assemble(ex, DocumentType.INVOICE, "test-model");

        assertThat(draft.getLines().getFirst().getTotalExclVat().getValue()).isEqualByComparingTo("1000.00");
        assertThat(draft.getLines().getFirst().getTotalExclVat().getState()).isEqualTo(FieldState.DERIVED);
        assertThat(draft.getLines().getFirst().getTotalInclVat().getValue()).isEqualByComparingTo("1210.00");
    }

    // =========================================================================
    // Null vstupy z extrakce
    // =========================================================================

    @Test
    @DisplayName("null seznam řádků → prázdný seznam, ne pád")
    void nullLines_yieldEmptyList() {
        DocumentExtractionResult ex = doc(null, null, null);

        ReceiptDraft draft = assembler.assemble(ex, DocumentType.INVOICE, "test-model");

        assertThat(draft.getLines()).isNotNull().isEmpty();
    }

    @Test
    @DisplayName("null rekapitulace → prázdný seznam")
    void nullRecap_yieldEmptyList() {
        DocumentExtractionResult ex = doc(
                List.of(item("21", "1", "100.00", "100.00", "121.00")), null, null);

        ReceiptDraft draft = assembler.assemble(ex, DocumentType.INVOICE, "test-model");

        assertThat(draft.getVatRecap()).isNotNull().isEmpty();
    }

    @Test
    @DisplayName("dodaná rekapitulace se přenese včetně kódu, sazby, základu a DPH")
    void recap_isCarried() {
        DocumentExtractionResult ex = doc(
                List.of(item("C", "1", "100.00", "100.00", null)),
                List.of(new VatRecapRow("C", 21, new BigDecimal("100.00"), new BigDecimal("21.00"))),
                null);

        ReceiptDraft draft = assembler.assemble(ex, DocumentType.INVOICE, "test-model");

        assertThat(draft.getVatRecap()).singleElement().satisfies(row -> {
            assertThat(row.getCode()).isEqualTo("C");
            assertThat(row.getRatePercent()).isEqualTo(21);
            assertThat(row.getBase()).isEqualByComparingTo("100.00");
            assertThat(row.getVat()).isEqualByComparingTo("21.00");
        });
    }

    @Test
    @DisplayName("null dodavatel → draft má prázdného dodavatele se stavem NONE")
    void nullSupplier_yieldsNoneMatch() {
        DocumentExtractionResult ex = new DocumentExtractionResult(
                new Header(f("F-1"), absent(), absent(), null, null, null, f("CZK")),
                null,   // dodavatel není
                List.of(item("21", "1", "100.00", "100.00", "121.00")), null, null);

        ReceiptDraft draft = assembler.assemble(ex, DocumentType.INVOICE, "test-model");

        assertThat(draft.getSupplier()).isNotNull();
        assertThat(draft.getSupplier().getExtracted()).isNull();
        assertThat(draft.getSupplier().getMatchState()).isEqualTo(DraftSupplier.MatchState.NONE);
    }

    @Test
    @DisplayName("dodavatel se přenese včetně IČO, DIČ a bankovního spojení")
    void supplier_isCarried() {
        DocumentExtractionResult ex = new DocumentExtractionResult(
                new Header(f("F-1"), absent(), absent(), null, null, null, f("CZK")),
                new Supplier("Autodíly s.r.o.", "24787426", "CZ24787426",
                        "Skladová 7", "Brno", "60200",
                        "123456789/0800", "CZ6508000000192000145399", "GIBACZPX"),
                List.of(item("21", "1", "100.00", "100.00", "121.00")), null, null);

        ReceiptDraft draft = assembler.assemble(ex, DocumentType.INVOICE, "test-model");

        DraftSupplier.Extracted extracted = draft.getSupplier().getExtracted();
        assertThat(extracted.getName()).isEqualTo("Autodíly s.r.o.");
        assertThat(extracted.getRegistrationNumber()).isEqualTo("24787426");
        assertThat(extracted.getVatId()).isEqualTo("CZ24787426");
        assertThat(extracted.getIban()).isEqualTo("CZ6508000000192000145399");
    }

    // =========================================================================
    // collectDeliveryNoteRefs — jen skupinové řádky s vyplněným číslem
    // =========================================================================

    @Test
    @DisplayName("skupinový řádek BEZ čísla dodacího listu se do referencí nesebere")
    void groupLineWithoutNumber_isNotCollected() {
        Line groupNoNumber = new Line(LineKind.DELIVERY_NOTE_GROUP, 1,
                absent(), f("Dodací list bez čísla"), absent(),
                absentDec(), absentDec(), absent(),
                dec("1000.00"), absentDec(),
                null);                                    // deliveryNoteNumber == null
        DocumentExtractionResult ex = doc(
                List.of(groupNoNumber, item("21", "1", "100.00", "100.00", "121.00")), null, null);

        ReceiptDraft draft = assembler.assemble(ex, DocumentType.INVOICE, "test-model");

        assertThat(draft.getDeliveryNoteRefs())
                .as("bez čísla nemá referenci na co navázat").isEmpty();
    }

    @Test
    @DisplayName("skupinový řádek S číslem se sebere i s celkovou částkou")
    void groupLineWithNumber_isCollectedWithTotal() {
        Line group = new Line(LineKind.DELIVERY_NOTE_GROUP, 1,
                absent(), f("Dodací list č. 42 celkem"), absent(),
                absentDec(), absentDec(), absent(),
                absentDec(), dec("1210.00"),
                "42");
        DocumentExtractionResult ex = doc(
                List.of(group, item("21", "1", "100.00", "100.00", "121.00")), null, null);

        ReceiptDraft draft = assembler.assemble(ex, DocumentType.INVOICE, "test-model");

        assertThat(draft.getDeliveryNoteRefs()).singleElement().satisfies(ref -> {
            assertThat(ref.getNumber()).isEqualTo("42");
            assertThat(ref.getTotalInclVat()).isEqualByComparingTo("1210.00");
        });
    }

    // =========================================================================
    // lookupRecapRate — písmenný kód mimo rekapitulaci
    // =========================================================================

    @Test
    @DisplayName("písmenný kód sazby, který v rekapitulaci není → faktura ABSENT (nedohledá se)")
    void letterCode_notInRecap_isAbsentForInvoice() {
        DocumentExtractionResult ex = doc(
                List.of(item("X", "1", "100.00", "100.00", null)),
                List.of(new VatRecapRow("C", 21, new BigDecimal("100.00"), new BigDecimal("21.00"))),
                null);

        ReceiptDraft draft = assembler.assemble(ex, DocumentType.INVOICE, "test-model");

        assertThat(draft.getLines().getFirst().getVatRate().getState())
                .as("kód X není v rekapitulaci (jen C) → nedohledá se").isEqualTo(FieldState.ABSENT);
    }

    // =========================================================================
    // Fixtury
    // =========================================================================

    private static F f(String v) {
        return v == null ? new F(null, SourceState.ABSENT) : new F(v, SourceState.VERBATIM);
    }

    private static F absent() {
        return new F(null, SourceState.ABSENT);
    }

    private static FDec dec(String v) {
        return new FDec(new BigDecimal(v), SourceState.VERBATIM);
    }

    private static FDec absentDec() {
        return new FDec(null, SourceState.ABSENT);
    }

    private static Line item(String rateOrCode, String qty, String unitPrice,
                             String totalExcl, String totalIncl) {
        return new Line(LineKind.ITEM, 1,
                f("SKU-1"), f("Díl"), f("ks"),
                dec(qty), dec(unitPrice),
                rateOrCode == null ? absent() : f(rateOrCode),
                totalExcl == null ? absentDec() : dec(totalExcl),
                totalIncl == null ? absentDec() : dec(totalIncl),
                null);
    }

    private static Supplier supplier() {
        return new Supplier("Dodavatel", "24787426", null, null, null, null, null, null, null);
    }

    private static DocumentExtractionResult doc(List<Line> lines, List<VatRecapRow> recap, Summary summary) {
        return new DocumentExtractionResult(
                new Header(f("DOC-1"), absent(), absent(), null, null, null, absent()),
                supplier(), lines, recap, summary);
    }
}
