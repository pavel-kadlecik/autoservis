package cz.palo.autoservis.service;

import cz.palo.autoservis.config.WarehouseImportProperties;
import cz.palo.autoservis.mapper.WarehouseImportMapper;
import cz.palo.autoservis.model.domain.warehouse.DocumentType;
import cz.palo.autoservis.model.draft.DraftCheck;
import cz.palo.autoservis.model.draft.DraftLine;
import cz.palo.autoservis.model.draft.DraftSupplier;
import cz.palo.autoservis.model.draft.FieldState;
import cz.palo.autoservis.model.draft.ReceiptDraft;
import cz.palo.autoservis.model.draft.TrackedField;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * Souhrnné (hlavičkové) kontroly {@code DraftVerificationService} — druhá půlka principu
 * „AI čte, kód počítá". Doplňuje {@code DraftVerificationServiceTest}, který pokrýval
 * LINE_MATH a IČO kontrolní součet.
 *
 * <p>Čistý unit test <strong>bez Springu a bez DB</strong>: lookup dodavatele je mockovaný,
 * takže se testuje výhradně to, že <strong>aritmetiku dělá kód</strong> — model vrátí čísla,
 * verifikace je nezávisle přepočítá a rozhodne. U každé kontroly se testuje shoda i rozpor,
 * jinak by aserce „check je OK" prošla i kontrole, která nikdy neselže.
 */
class DraftVerificationSumsTest {

    private WarehouseImportMapper mapper;
    private DraftVerificationService service;

    @BeforeEach
    void setUp() {
        mapper = mock(WarehouseImportMapper.class);
        given(mapper.findSupplierIdByIco(anyString())).willReturn(Optional.empty());
        service = new DraftVerificationService(
                mapper, new SupplierNormalizer(), new WarehouseImportProperties());
    }

    // =========================================================================
    // SUBTOTAL_PLUS_VAT_EQ_TOTAL
    // =========================================================================

    @Test
    @DisplayName("subtotal + DPH = total → kontrola projde a hlavičkové součty se povýší na VERIFIED")
    void subtotalPlusVat_consistent_passesAndVerifiesHeader() {
        ReceiptDraft draft = consistentSingleLineDraft();

        boolean reconciliationOk = service.verify(draft);

        assertThat(reconciliationOk).isTrue();
        assertThat(checkOk(draft, DraftCheck.SUBTOTAL_PLUS_VAT_EQ_TOTAL)).isTrue();
        assertThat(draft.getHeader().getSubtotal().getState()).isEqualTo(FieldState.VERIFIED);
        assertThat(draft.getHeader().getVatAmount().getState()).isEqualTo(FieldState.VERIFIED);
        assertThat(draft.getHeader().getTotalAmount().getState()).isEqualTo(FieldState.VERIFIED);
    }

    @Test
    @DisplayName("total nesedí se subtotal + DPH → kontrola selže a hlavička NEpovýší na VERIFIED")
    void subtotalPlusVat_wrongTotal_failsAndKeepsHeaderUnverified() {
        ReceiptDraft draft = consistentSingleLineDraft();
        // model přečetl celkovou částku o 100 Kč špatně
        draft.getHeader().getTotalAmount().setValue(new BigDecimal("1310.00"));

        boolean reconciliationOk = service.verify(draft);

        assertThat(reconciliationOk).isFalse();
        assertThat(checkOk(draft, DraftCheck.SUBTOTAL_PLUS_VAT_EQ_TOTAL)).isFalse();
        assertThat(draft.getHeader().getTotalAmount().getState())
                .as("neprošlá kontrola pole nepovyšuje").isNotEqualTo(FieldState.VERIFIED);
    }

    @Test
    @DisplayName("chybějící hlavičková částka → SUBTOTAL_PLUS_VAT_EQ_TOTAL false a rekonciliace padá")
    void subtotalPlusVat_missingValue_isFalse() {
        // vatAmount null vyřadí jen SUBTOTAL_PLUS_VAT (RECAP_SUM se bez recap neuplatní,
        // LINES_SUM_VS_TOTAL má total i řádky) — takže null-větev musí sama shodit rekonciliaci.
        ReceiptDraft draft = consistentSingleLineDraft();
        draft.getHeader().getVatAmount().setValue(null);

        boolean reconciliationOk = service.verify(draft);

        assertThat(reconciliationOk).isFalse();
        assertThat(checkOk(draft, DraftCheck.SUBTOTAL_PLUS_VAT_EQ_TOTAL)).isFalse();
        assertThat(checkOk(draft, DraftCheck.LINES_SUM_VS_TOTAL)).as("ostatní projdou").isTrue();
    }

    // =========================================================================
    // LINES_SUM_VS_TOTAL
    // =========================================================================

    @Test
    @DisplayName("Σ řádků s DPH = total → LINES_SUM_VS_TOTAL projde")
    void linesSumVsTotal_consistent_passes() {
        ReceiptDraft draft = twoLineDraft();

        service.verify(draft);

        assertThat(checkOk(draft, DraftCheck.LINES_SUM_VS_TOTAL)).isTrue();
    }

    @Test
    @DisplayName("Σ řádků neodpovídá total (chybí řádek na dokladu) → LINES_SUM_VS_TOTAL selže")
    void linesSumVsTotal_missingLineInTotal_fails() {
        ReceiptDraft draft = twoLineDraft();
        // total tvrdí jen jeden řádek, ačkoli řádky jsou dva
        draft.getHeader().getTotalAmount().setValue(new BigDecimal("1210.00"));
        draft.getHeader().getSubtotal().setValue(new BigDecimal("1000.00"));
        draft.getHeader().getVatAmount().setValue(new BigDecimal("210.00"));

        boolean reconciliationOk = service.verify(draft);

        assertThat(reconciliationOk).isFalse();
        assertThat(checkOk(draft, DraftCheck.LINES_SUM_VS_TOTAL)).isFalse();
    }

    // =========================================================================
    // LINES_SUM_VS_RECAP + RECAP_SUM
    // =========================================================================

    @Test
    @DisplayName("rekapitulace sedí s řádky i hlavičkou → LINES_SUM_VS_RECAP i RECAP_SUM projdou")
    void recap_consistent_passesBothChecks() {
        ReceiptDraft draft = consistentSingleLineDraft();
        draft.setVatRecap(new ArrayList<>(List.of(
                recapRow(21, "1000.00", "210.00"))));

        boolean reconciliationOk = service.verify(draft);

        assertThat(reconciliationOk).isTrue();
        assertThat(checkOk(draft, DraftCheck.LINES_SUM_VS_RECAP)).isTrue();
        assertThat(checkOk(draft, DraftCheck.RECAP_SUM)).isTrue();
    }

    @Test
    @DisplayName("základ v rekapitulaci neodpovídá součtu řádků té sazby → LINES_SUM_VS_RECAP selže")
    void recap_baseMismatchAgainstLines_failsLinesVsRecap() {
        ReceiptDraft draft = consistentSingleLineDraft();
        // řádky mají základ 1000, rekapitulace tvrdí 900 při téže sazbě
        draft.setVatRecap(new ArrayList<>(List.of(
                recapRow(21, "900.00", "189.00"))));

        boolean reconciliationOk = service.verify(draft);

        assertThat(reconciliationOk).isFalse();
        assertThat(checkOk(draft, DraftCheck.LINES_SUM_VS_RECAP)).isFalse();
    }

    @Test
    @DisplayName("Σ rekapitulace neodpovídá hlavičkovému subtotal → RECAP_SUM selže")
    void recap_sumMismatchAgainstHeader_failsRecapSum() {
        ReceiptDraft draft = consistentSingleLineDraft();
        // rekapitulace sedí s řádky (1000), ale hlavička tvrdí subtotal 1100
        draft.setVatRecap(new ArrayList<>(List.of(recapRow(21, "1000.00", "210.00"))));
        draft.getHeader().getSubtotal().setValue(new BigDecimal("1100.00"));
        draft.getHeader().getTotalAmount().setValue(new BigDecimal("1310.00")); // ať SUBTOTAL_PLUS_VAT sedí

        service.verify(draft);

        assertThat(checkOk(draft, DraftCheck.RECAP_SUM)).isFalse();
    }

    @Test
    @DisplayName("rekapitulace o dvou sazbách: základ se srovnává PO SAZBÁCH, ne dohromady")
    void recap_twoRates_matchedPerRate() {
        // 1000 při 21 % a 500 při 12 %. Rekapitulace to musí sedět rozdělené po sazbách —
        // kdyby se řádky sčítaly bez ohledu na sazbu, 21% řádek by dostal 1500 vs 1000.
        DraftLine at21 = itemLine(1, "2", "500.00", 21, "1000.00", "1210.00");
        DraftLine at12 = itemLine(2, "1", "500.00", 12, "500.00", "560.00");
        ReceiptDraft draft = baseDraft(new BigDecimal("1500.00"), new BigDecimal("270.00"),
                new BigDecimal("1770.00"), List.of(at21, at12));
        draft.setVatRecap(new ArrayList<>(List.of(
                recapRow(21, "1000.00", "210.00"),
                recapRow(12, "500.00", "60.00"))));

        boolean reconciliationOk = service.verify(draft);

        assertThat(reconciliationOk).isTrue();
        assertThat(checkOk(draft, DraftCheck.LINES_SUM_VS_RECAP)).isTrue();
    }

    @Test
    @DisplayName("dvě sazby: prohození základů mezi sazbami LINES_SUM_VS_RECAP odhalí")
    void recap_twoRates_swappedBases_fails() {
        DraftLine at21 = itemLine(1, "2", "500.00", 21, "1000.00", "1210.00");
        DraftLine at12 = itemLine(2, "1", "500.00", 12, "500.00", "560.00");
        ReceiptDraft draft = baseDraft(new BigDecimal("1500.00"), new BigDecimal("270.00"),
                new BigDecimal("1770.00"), List.of(at21, at12));
        // základy prohozené mezi sazbami — celek 1500 sedí, ale po sazbách ne
        draft.setVatRecap(new ArrayList<>(List.of(
                recapRow(21, "500.00", "105.00"),
                recapRow(12, "1000.00", "120.00"))));

        service.verify(draft);

        assertThat(checkOk(draft, DraftCheck.LINES_SUM_VS_RECAP))
                .as("21 %: řádky 1000 vs rekapitulace 500 → nesedí").isFalse();
    }

    @Test
    @DisplayName("skupinový řádek (Dodací list č. X) se do součtů rekapitulace NEZAPOČÍTÁVÁ")
    void recap_ignoresNonItemGroupLine() {
        // Řádky: skutečná položka 1000 při 21 % + skupinový LKQ řádek se stejnou sazbou.
        // Kdyby se do součtu po sazbách započítal i skupinový řádek, 21% základ by nabobtnal
        // a LINES_SUM_VS_RECAP by falešně selhala.
        DraftLine item = itemLine(1, "2", "500.00", 21, "1000.00", "1210.00");
        DraftLine group = DraftLine.builder()
                .lineKind(DraftLine.LineKind.DELIVERY_NOTE_GROUP)
                .position(2)
                .name(TrackedField.of("Dodací list č. 42 celkem", FieldState.VERBATIM))
                .vatRate(TrackedField.of(21, FieldState.VERBATIM))
                .totalExclVat(TrackedField.of(new BigDecimal("1000.00"), FieldState.VERBATIM))
                .totalInclVat(TrackedField.of(new BigDecimal("1210.00"), FieldState.VERBATIM))
                .build();
        ReceiptDraft draft = baseDraft(new BigDecimal("1000.00"), new BigDecimal("210.00"),
                new BigDecimal("1210.00"), List.of(item, group));
        draft.setVatRecap(new ArrayList<>(List.of(recapRow(21, "1000.00", "210.00"))));

        boolean reconciliationOk = service.verify(draft);

        assertThat(checkOk(draft, DraftCheck.LINES_SUM_VS_RECAP))
                .as("skupinový řádek se do 21% základu nezapočítá").isTrue();
        assertThat(checkOk(draft, DraftCheck.LINES_SUM_VS_TOTAL))
                .as("a nezapočítá se ani do Σ řádků vs. total").isTrue();
        assertThat(reconciliationOk).isTrue();
    }

    @Test
    @DisplayName("tolerance je INKLUZIVNÍ: odchylka přesně 0.05 ještě projde")
    void sums_toleranceIsInclusiveAtExactBoundary() {
        ReceiptDraft draft = consistentSingleLineDraft(); // total 1210.00
        draft.getHeader().getTotalAmount().setValue(new BigDecimal("1210.05")); // přesně na hranici

        service.verify(draft);

        assertThat(checkOk(draft, DraftCheck.SUBTOTAL_PLUS_VAT_EQ_TOTAL))
                .as("hranice 0.05 se počítá jako v toleranci (<=)").isTrue();
    }

    @Test
    @DisplayName("bez rekapitulace (dodací list) se LINES_SUM_VS_RECAP i RECAP_SUM neuplatní jako selhání")
    void noRecap_doesNotFailRecapChecks() {
        ReceiptDraft draft = consistentSingleLineDraft(); // vatRecap prázdná

        boolean reconciliationOk = service.verify(draft);

        assertThat(reconciliationOk)
                .as("bez rekapitulace se rekonciliace neopírá o recap kontroly").isTrue();
    }

    @Test
    @DisplayName("tolerance 0.05: odchylka součtů o 4 haléře projde, o 6 haléřů ne")
    void sums_respectTolerance() {
        ReceiptDraft within = consistentSingleLineDraft();
        within.getHeader().getTotalAmount().setValue(new BigDecimal("1210.04"));
        assertThat(checkOk(verified(within), DraftCheck.SUBTOTAL_PLUS_VAT_EQ_TOTAL)).isTrue();

        ReceiptDraft outside = consistentSingleLineDraft();
        outside.getHeader().getTotalAmount().setValue(new BigDecimal("1210.06"));
        assertThat(checkOk(verified(outside), DraftCheck.SUBTOTAL_PLUS_VAT_EQ_TOTAL)).isFalse();
    }

    // =========================================================================
    // Izolovaná selhání — každá kontrola sama o sobě shodí rekonciliaci
    //
    // Drafty jsou schválně poskládané tak, aby selhala PRÁVĚ JEDNA kontrola;
    // jinak by překlopení návratové hodnoty jedné metody rekonciliaci nezměnilo
    // (víc selhání = redundance) a mutant „return true" by přežil.
    // =========================================================================

    @Test
    @DisplayName("izolovaně jen SUBTOTAL_PLUS_VAT: špatné vatAmount shodí rekonciliaci samo")
    void onlySubtotalPlusVatFails_breaksReconciliation() {
        ReceiptDraft draft = consistentSingleLineDraft(); // lines 1210 incl, total 1210
        draft.getHeader().getVatAmount().setValue(new BigDecimal("999.00")); // 1000+999 ≠ 1210

        boolean reconciliationOk = service.verify(draft);

        assertThat(reconciliationOk).isFalse();
        assertThat(checkOk(draft, DraftCheck.SUBTOTAL_PLUS_VAT_EQ_TOTAL)).isFalse();
        assertThat(checkOk(draft, DraftCheck.LINES_SUM_VS_TOTAL)).as("ostatní sedí").isTrue();
    }

    @Test
    @DisplayName("izolovaně jen LINES_SUM_VS_TOTAL: chybějící řádek v součtu shodí rekonciliaci sám")
    void onlyLinesSumVsTotalFails_breaksReconciliation() {
        ReceiptDraft draft = twoLineDraft();                                  // lines incl 1815
        draft.getHeader().getSubtotal().setValue(new BigDecimal("1000.00"));
        draft.getHeader().getVatAmount().setValue(new BigDecimal("210.00"));
        draft.getHeader().getTotalAmount().setValue(new BigDecimal("1210.00")); // 1000+210=1210 ✓

        boolean reconciliationOk = service.verify(draft);

        assertThat(reconciliationOk).isFalse();
        assertThat(checkOk(draft, DraftCheck.LINES_SUM_VS_TOTAL)).isFalse();
        assertThat(checkOk(draft, DraftCheck.SUBTOTAL_PLUS_VAT_EQ_TOTAL)).as("hlavička je sama v sobě konzistentní").isTrue();
    }

    @Test
    @DisplayName("chybějící total → LINES_SUM_VS_TOTAL false a rekonciliace padá")
    void missingTotal_breaksLinesSumVsTotal() {
        ReceiptDraft draft = consistentSingleLineDraft();
        draft.getHeader().getTotalAmount().setValue(null);

        boolean reconciliationOk = service.verify(draft);

        assertThat(reconciliationOk).isFalse();
        assertThat(checkOk(draft, DraftCheck.LINES_SUM_VS_TOTAL)).isFalse();
    }

    @Test
    @DisplayName("izolovaně jen LINES_SUM_VS_RECAP: nesouhlas po sazbách při souhlasném celku")
    void onlyLinesSumVsRecapFails_breaksReconciliation() {
        // Řádky: 1000 při 21 %, incl 1210 (LINE_MATH nechávám nedotčenou).
        // Rekapitulace: 900 při 21 % + 100 při 12 % — po sazbách nesedí (21 %: 1000 vs 900),
        // ale Σ base = 1000 = subtotal a Σ vat = 210 = vatAmount → RECAP_SUM projde.
        // Hlavička i total zůstávají konzistentní (subtotal 1000, vat 210, total 1210).
        ReceiptDraft draft = consistentSingleLineDraft();
        draft.setVatRecap(new ArrayList<>(List.of(
                recapRow(21, "900.00", "189.00"),
                recapRow(12, "100.00", "21.00"))));

        boolean reconciliationOk = service.verify(draft);

        assertThat(reconciliationOk).isFalse();
        assertThat(checkOk(draft, DraftCheck.LINES_SUM_VS_RECAP)).isFalse();
        assertThat(checkOk(draft, DraftCheck.RECAP_SUM)).as("Σ rekapitulace = subtotal a vatAmount").isTrue();
        assertThat(checkOk(draft, DraftCheck.LINE_MATH)).as("řádek sám o sobě sedí").isTrue();
        assertThat(checkOk(draft, DraftCheck.LINES_SUM_VS_TOTAL)).isTrue();
    }

    @Test
    @DisplayName("izolovaně jen RECAP_SUM: rekapitulace sedí s řádky, ale ne s hlavičkou")
    void onlyRecapSumFails_breaksReconciliation() {
        // Rekapitulace 1000 = Σ řádků té sazby (LINES_SUM_VS_RECAP projde),
        // ale hlavičkový subtotal je 1100 (RECAP_SUM selže). Hlavička přitom sama
        // konzistentní: 1100 + 110 = 1210 = total, a řádky incl 1210 = total.
        ReceiptDraft draft = consistentSingleLineDraft();
        draft.setVatRecap(new ArrayList<>(List.of(recapRow(21, "1000.00", "210.00"))));
        draft.getHeader().getSubtotal().setValue(new BigDecimal("1100.00"));
        draft.getHeader().getVatAmount().setValue(new BigDecimal("110.00"));
        // total zůstává 1210: 1100 + 110 = 1210 ✓, řádky incl 1210 = total ✓

        boolean reconciliationOk = service.verify(draft);

        assertThat(reconciliationOk).isFalse();
        assertThat(checkOk(draft, DraftCheck.RECAP_SUM)).isFalse();
        assertThat(checkOk(draft, DraftCheck.LINES_SUM_VS_RECAP)).as("po sazbách řádky sedí").isTrue();
        assertThat(checkOk(draft, DraftCheck.SUBTOTAL_PLUS_VAT_EQ_TOTAL)).as("hlavička konzistentní").isTrue();
    }

    // =========================================================================
    // SUPPLIER_KNOWN
    // =========================================================================

    @Test
    @DisplayName("dodavatel dohledaný v DB podle IČO → SUPPLIER_KNOWN true a match AUTO")
    void supplierKnown_foundInDb_isAutoMatched() {
        given(mapper.findSupplierIdByIco("24787426")).willReturn(Optional.of(42L));
        ReceiptDraft draft = consistentSingleLineDraft();

        service.verify(draft);

        assertThat(draft.getSupplier().getMatchedSupplierId()).isEqualTo(42L);
        assertThat(draft.getSupplier().getMatchState()).isEqualTo(DraftSupplier.MatchState.AUTO);
        assertThat(checkOk(draft, DraftCheck.SUPPLIER_KNOWN)).isTrue();
    }

    @Test
    @DisplayName("dodavatel v DB není → SUPPLIER_KNOWN false a match NONE (založí se až při potvrzení)")
    void supplierKnown_notInDb_isNoneMatch() {
        // mapper vrací empty (default z setUp)
        ReceiptDraft draft = consistentSingleLineDraft();

        service.verify(draft);

        assertThat(draft.getSupplier().getMatchedSupplierId()).isNull();
        assertThat(draft.getSupplier().getMatchState()).isEqualTo(DraftSupplier.MatchState.NONE);
        assertThat(checkOk(draft, DraftCheck.SUPPLIER_KNOWN)).isFalse();
    }

    @Test
    @DisplayName("SUPPLIER_KNOWN neselže rekonciliaci — je informativní, ne aritmetická")
    void supplierKnown_doesNotAffectReconciliation() {
        ReceiptDraft draft = consistentSingleLineDraft(); // dodavatel v DB není

        boolean reconciliationOk = service.verify(draft);

        assertThat(reconciliationOk)
                .as("neznámý dodavatel nebrání rekonciliaci — aritmetika sedí").isTrue();
        assertThat(checkOk(draft, DraftCheck.SUPPLIER_KNOWN)).isFalse();
    }

    @Test
    @DisplayName("verify normalizuje extrahované IČO (odstraní mezery) ještě před lookupem")
    void verifySupplier_normalizesExtractedRegistrationNumber() {
        // IČO přijde z extrakce s mezerami; verify ho musí normalizovat na místě,
        // jinak by lookup i uložený dodavatel nesli „24 787 426" místo „24787426".
        given(mapper.findSupplierIdByIco("24787426")).willReturn(Optional.of(42L));
        ReceiptDraft draft = consistentSingleLineDraft();
        draft.getSupplier().getExtracted().setRegistrationNumber("24 787 426");

        service.verify(draft);

        assertThat(draft.getSupplier().getExtracted().getRegistrationNumber())
                .as("mezery se odstraní přímo v extrahovaných datech").isEqualTo("24787426");
        assertThat(draft.getSupplier().getIcoChecksumOk()).isTrue();
        assertThat(draft.getSupplier().getMatchedSupplierId()).isEqualTo(42L);
    }

    @Test
    @DisplayName("IČO s vadným kontrolním součtem → ICO_CHECKSUM false, icoChecksumOk false")
    void icoChecksum_invalid_setsFlagFalse() {
        ReceiptDraft draft = consistentSingleLineDraft();
        draft.getSupplier().getExtracted().setRegistrationNumber("12345678"); // neplatný součet

        service.verify(draft);

        assertThat(draft.getSupplier().getIcoChecksumOk()).isFalse();
        assertThat(checkOk(draft, DraftCheck.ICO_CHECKSUM)).isFalse();
    }

    // =========================================================================
    // Povýšení řádkových polí na VERIFIED
    // =========================================================================

    @Test
    @DisplayName("prošlá LINE_MATH povýší VŠECHNA počítaná pole řádku, ne jen některá")
    void lineMath_promotesEveryComputedField() {
        ReceiptDraft draft = consistentSingleLineDraft();

        service.verify(draft);

        DraftLine line = draft.getLines().getFirst();
        assertThat(line.getQuantity().getState()).isEqualTo(FieldState.VERIFIED);
        assertThat(line.getUnitPriceExclVat().getState()).isEqualTo(FieldState.VERIFIED);
        assertThat(line.getTotalExclVat().getState()).isEqualTo(FieldState.VERIFIED);
        assertThat(line.getTotalInclVat().getState()).isEqualTo(FieldState.VERIFIED);
        assertThat(line.getVatRate().getState()).isEqualTo(FieldState.VERIFIED);
    }

    @Test
    @DisplayName("neprošlá LINE_MATH nepovýší ŽÁDNÉ pole řádku")
    void lineMath_failed_promotesNoField() {
        ReceiptDraft draft = consistentSingleLineDraft();
        draft.getLines().getFirst().getTotalInclVat().setValue(new BigDecimal("1500.00")); // vedle

        service.verify(draft);

        DraftLine line = draft.getLines().getFirst();
        assertThat(line.getQuantity().getState()).isEqualTo(FieldState.VERBATIM);
        assertThat(line.getUnitPriceExclVat().getState()).isEqualTo(FieldState.VERBATIM);
        assertThat(line.getTotalExclVat().getState()).isEqualTo(FieldState.VERBATIM);
        assertThat(line.getVatRate().getState()).isEqualTo(FieldState.VERBATIM);
    }

    // =========================================================================
    // Dedup dodací list ↔ faktura (matchDeliveryNoteRefs)
    // =========================================================================

    @Test
    @DisplayName("referenci na DL bez párování dohledá podle čísla a napáruje na existující příjemku")
    void matchDeliveryNoteRefs_matchesUnresolvedRefByNumber() {
        given(mapper.findDeliveryNoteReceiptId(null, "DL-2026-042", 99L))
                .willReturn(Optional.of(555L));

        ReceiptDraft draft = consistentSingleLineDraft();
        draft.setDeliveryNoteRefs(new ArrayList<>(List.of(
                DeliveryNoteRefFixture.unresolved("DL-2026-042"))));

        service.matchDeliveryNoteRefs(draft, 99L);

        assertThat(draft.getDeliveryNoteRefs().getFirst().getMatchedReceiptId()).isEqualTo(555L);
    }

    @Test
    @DisplayName("už napárovanou referenci dedup nepřepíše (drží rozhodnutí uživatele)")
    void matchDeliveryNoteRefs_keepsAlreadyMatchedRef() {
        ReceiptDraft draft = consistentSingleLineDraft();
        var ref = DeliveryNoteRefFixture.unresolved("DL-2026-042");
        ref.setMatchedReceiptId(777L);
        draft.setDeliveryNoteRefs(new ArrayList<>(List.of(ref)));

        service.matchDeliveryNoteRefs(draft, 99L);

        assertThat(draft.getDeliveryNoteRefs().getFirst().getMatchedReceiptId())
                .as("mapper se pro už napárovanou referenci ani nevolá").isEqualTo(777L);
        org.mockito.Mockito.verify(mapper, org.mockito.Mockito.never())
                .findDeliveryNoteReceiptId(org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.eq("DL-2026-042"),
                        org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("dedup se týká jen faktur — u dodacího listu se nedělá nic")
    void matchDeliveryNoteRefs_onlyForInvoices() {
        ReceiptDraft draft = consistentSingleLineDraft();
        draft.setDocumentType(DocumentType.DELIVERY_NOTE);
        draft.setDeliveryNoteRefs(new ArrayList<>(List.of(
                DeliveryNoteRefFixture.unresolved("DL-2026-042"))));

        service.matchDeliveryNoteRefs(draft, 99L);

        assertThat(draft.getDeliveryNoteRefs().getFirst().getMatchedReceiptId()).isNull();
        org.mockito.Mockito.verifyNoInteractions(mapper);
    }

    @Test
    @DisplayName("dedup použije napárovaného dodavatele k zúžení hledání DL podle IČO")
    void matchDeliveryNoteRefs_scopesByMatchedSupplier() {
        given(mapper.findDeliveryNoteReceiptId(42L, "DL-2026-042", 99L))
                .willReturn(Optional.of(555L));

        ReceiptDraft draft = consistentSingleLineDraft();
        draft.getSupplier().setMatchedSupplierId(42L);
        draft.setDeliveryNoteRefs(new ArrayList<>(List.of(
                DeliveryNoteRefFixture.unresolved("DL-2026-042"))));

        service.matchDeliveryNoteRefs(draft, 99L);

        assertThat(draft.getDeliveryNoteRefs().getFirst().getMatchedReceiptId()).isEqualTo(555L);
    }

    private static final class DeliveryNoteRefFixture {
        static cz.palo.autoservis.model.draft.DeliveryNoteRef unresolved(String number) {
            return cz.palo.autoservis.model.draft.DeliveryNoteRef.builder()
                    .number(number)
                    .totalInclVat(new BigDecimal("1210.00"))
                    .build();
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private ReceiptDraft verified(ReceiptDraft draft) {
        service.verify(draft);
        return draft;
    }

    private boolean checkOk(ReceiptDraft draft, String code) {
        return draft.getChecks().stream()
                .filter(c -> c.getCode().equals(code))
                .anyMatch(DraftCheck::isOk)
                && draft.getChecks().stream()
                    .filter(c -> c.getCode().equals(code))
                    .allMatch(DraftCheck::isOk);
    }

    private static DraftLine itemLine(int position, String qty, String unitPrice, int rate,
                                      String totalExcl, String totalIncl) {
        return DraftLine.builder()
                .lineKind(DraftLine.LineKind.ITEM)
                .position(position)
                .catalogNumber(TrackedField.of("SKU-" + position, FieldState.VERBATIM))
                .name(TrackedField.of("Díl " + position, FieldState.VERBATIM))
                .unit(TrackedField.of("ks", FieldState.VERBATIM))
                .quantity(TrackedField.of(new BigDecimal(qty), FieldState.VERBATIM))
                .unitPriceExclVat(TrackedField.of(new BigDecimal(unitPrice), FieldState.VERBATIM))
                .vatRate(TrackedField.of(rate, FieldState.VERBATIM))
                .totalExclVat(TrackedField.of(new BigDecimal(totalExcl), FieldState.VERBATIM))
                .totalInclVat(TrackedField.of(new BigDecimal(totalIncl), FieldState.VERBATIM))
                .build();
    }

    private static ReceiptDraft.VatRecapRow recapRow(int ratePercent, String base, String vat) {
        return ReceiptDraft.VatRecapRow.builder()
                .code(String.valueOf(ratePercent))
                .ratePercent(ratePercent)
                .base(new BigDecimal(base))
                .vat(new BigDecimal(vat))
                .build();
    }

    /** Jeden řádek: 2 × 500 = 1000 bez DPH, 1210 s DPH 21 %; hlavička sedí. */
    private static ReceiptDraft consistentSingleLineDraft() {
        DraftLine line = itemLine(1, "2", "500.00", 21, "1000.00", "1210.00");
        return baseDraft(new BigDecimal("1000.00"), new BigDecimal("210.00"),
                new BigDecimal("1210.00"), List.of(line));
    }

    /** Dva řádky: 1000 + 500 = 1500 bez DPH, 1815 s DPH; hlavička sedí. */
    private static ReceiptDraft twoLineDraft() {
        DraftLine a = itemLine(1, "2", "500.00", 21, "1000.00", "1210.00");
        DraftLine b = itemLine(2, "1", "500.00", 21, "500.00", "605.00");
        return baseDraft(new BigDecimal("1500.00"), new BigDecimal("315.00"),
                new BigDecimal("1815.00"), List.of(a, b));
    }

    private static ReceiptDraft baseDraft(BigDecimal subtotal, BigDecimal vat, BigDecimal total,
                                          List<DraftLine> lines) {
        return ReceiptDraft.builder()
                .schemaVersion(1)
                .documentType(DocumentType.INVOICE)
                .header(ReceiptDraft.Header.builder()
                        .documentNumber(TrackedField.of("F-1", FieldState.VERBATIM))
                        .orderNumber(TrackedField.absent())
                        .originalOrderNumber(TrackedField.absent())
                        .issueDate(TrackedField.absent())
                        .dueDate(TrackedField.absent())
                        .taxableSupplyDate(TrackedField.absent())
                        .currency(TrackedField.of("CZK", FieldState.VERBATIM))
                        .subtotal(TrackedField.of(subtotal, FieldState.VERBATIM))
                        .vatAmount(TrackedField.of(vat, FieldState.VERBATIM))
                        .totalAmount(TrackedField.of(total, FieldState.VERBATIM))
                        .build())
                .supplier(DraftSupplier.builder()
                        .extracted(DraftSupplier.Extracted.builder()
                                .name("Dodavatel").registrationNumber("24787426").build())
                        .build())
                .vatRecap(new ArrayList<>())
                .deliveryNoteRefs(new ArrayList<>())
                .lines(new ArrayList<>(lines))
                .checks(new ArrayList<>())
                .build();
    }
}
