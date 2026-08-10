package cz.palo.autoservis.service;

import cz.palo.autoservis.config.WarehouseImportProperties;
import cz.palo.autoservis.mapper.WarehouseImportMapper;
import cz.palo.autoservis.model.domain.warehouse.DocumentType;
import cz.palo.autoservis.model.draft.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/** Čisté unit testy deterministických kontrol — bez Springu a bez DB. */
class DraftVerificationServiceTest {

    private WarehouseImportMapper mapper;
    private DraftVerificationService service;

    @BeforeEach
    void setUp() {
        mapper = mock(WarehouseImportMapper.class);
        given(mapper.findSupplierIdByIco(anyString())).willReturn(Optional.empty());
        service = new DraftVerificationService(
                mapper, new SupplierNormalizer(), new WarehouseImportProperties());
    }

    // reálná IČO: LKQ CZ, AUTO RAVIRA, odběratel ze vzorových dokladů
    @ParameterizedTest
    @CsvSource({"24787426,true", "60715413,true", "61405833,true",
                "12345679,true", "12345678,false", "1234567,false", "abcdefgh,false"})
    @DisplayName("kontrolní součet IČO (mod 11)")
    void icoChecksum(String ico, boolean expected) {
        assertThat(service.icoChecksumValid(ico)).isEqualTo(expected);
    }

    @Test
    @DisplayName("LINE_MATH v toleranci 0.05 projde a povýší pole na VERIFIED")
    void lineMathWithinTolerance() {
        ReceiptDraft draft = draftWithLine(line(
                "2", "500.00", 21, "1000.00", "1210.04"));   // odchylka 4 haléře

        boolean ok = service.verify(draft);

        assertThat(ok).isTrue();
        DraftLine l = draft.getLines().get(0);
        assertThat(l.getQuantity().getState()).isEqualTo(FieldState.VERIFIED);
        assertThat(l.getTotalInclVat().getState()).isEqualTo(FieldState.VERIFIED);
    }

    @Test
    @DisplayName("LINE_MATH mimo toleranci → check false, stavy zůstávají")
    void lineMathOutsideTolerance() {
        ReceiptDraft draft = draftWithLine(line(
                "2", "500.00", 21, "1000.00", "1215.00"));   // o 5 Kč vedle

        boolean ok = service.verify(draft);

        assertThat(ok).isFalse();
        DraftLine l = draft.getLines().get(0);
        assertThat(l.getQuantity().getState()).isEqualTo(FieldState.VERBATIM);
        assertThat(draft.getChecks())
                .anyMatch(c -> c.getCode().equals(DraftCheck.LINE_MATH) && !c.isOk());
    }

    @Test
    @DisplayName("DEFAULTED sazba se nikdy nepovyšuje na VERIFIED")
    void defaultedRateStaysDefaulted() {
        DraftLine l = line("1", "1189.39", 21, "1189.39", "1439.16");
        l.getVatRate().setState(FieldState.DEFAULTED);
        ReceiptDraft draft = draftWithLine(l);

        service.verify(draft);

        assertThat(l.getVatRate().getState()).isEqualTo(FieldState.DEFAULTED);
    }

    // ---------------------------------------------------------------- helpers

    private DraftLine line(String qty, String unitPrice, int rate,
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

    // ------------------------------------------------------------------ KN-17: tautologické kontroly

    @Test
    @DisplayName("dopočtené částky se neověřují samy sebou — žádné VERIFIED ani rekonciliace (KN-17)")
    void derivedAmounts_areNotSelfVerified() {
        ReceiptDraft draft = handwrittenDeliveryNote();

        boolean reconciliationOk = service.verify(draft);

        assertThat(reconciliationOk)
                .as("nic nezávislého se ověřit nedalo → doklad není zrekonciliovaný")
                .isFalse();

        DraftLine l = draft.getLines().get(0);
        assertThat(l.getUnitPriceExclVat().getState()).isEqualTo(FieldState.DERIVED);
        assertThat(l.getTotalExclVat().getState()).isEqualTo(FieldState.DERIVED);
        assertThat(l.getQuantity().getState())
                .as("i přečtené množství zůstává VERBATIM — rovnice, ve které stojí, nic nedokázala")
                .isEqualTo(FieldState.VERBATIM);
        assertThat(draft.getHeader().getSubtotal().getState())
                .as("hlavičkový součet jsme si spočítali ze řádků, nesmí se tvářit jako ověřený")
                .isEqualTo(FieldState.DERIVED);

        assertThat(draft.getChecks())
                .filteredOn(c -> DraftCheck.LINE_MATH.equals(c.getCode()))
                .singleElement()
                .satisfies(c -> {
                    assertThat(c.isOk())
                            .as("aritmeticky to samozřejmě sedí — právě proto to bylo zrádné")
                            .isTrue();
                    assertThat(c.isIndependent())
                            .as("…ale porovnával se náš vlastní dopočet sám se sebou")
                            .isFalse();
                });
    }

    @Test
    @DisplayName("doklad s vytištěnými částkami se ověřuje dál — pravidlo nesmí zabít běžnou fakturu")
    void verbatimAmounts_stillVerifyAndReconcile() {
        ReceiptDraft draft = draftWithLine(line("2", "500.00", 21, "1000.00", "1210.00"));

        assertThat(service.verify(draft)).isTrue();

        DraftLine l = draft.getLines().get(0);
        assertThat(l.getUnitPriceExclVat().getState()).isEqualTo(FieldState.VERIFIED);
        assertThat(l.getTotalInclVat().getState()).isEqualTo(FieldState.VERIFIED);
        assertThat(draft.getHeader().getTotalAmount().getState()).isEqualTo(FieldState.VERIFIED);
        assertThat(draft.getChecks()).allSatisfy(c -> assertThat(c.isIndependent()).isTrue());
    }

    /**
     * Ručně psaný dodací list: v dokladu je jen množství a cena s DPH, zbytek si dopočítal
     * {@code DraftAssembler} (stavy DERIVED) — přesně situace z nálezu KN-17.
     */
    private ReceiptDraft handwrittenDeliveryNote() {
        DraftLine line = DraftLine.builder()
                .lineKind(DraftLine.LineKind.ITEM)
                .position(1)
                .catalogNumber(TrackedField.of("SKU-DL", FieldState.VERBATIM))
                .name(TrackedField.of("Díl z ručního DL", FieldState.VERBATIM))
                .unit(TrackedField.defaulted("ks"))
                .quantity(TrackedField.of(new BigDecimal("2"), FieldState.VERBATIM))
                .vatRate(TrackedField.defaulted(21))
                .totalInclVat(TrackedField.of(new BigDecimal("1210.00"), FieldState.VERBATIM))
                // dopočteno kódem: základ = 1210 / 1,21 ; jednotková cena = základ / množství
                .totalExclVat(TrackedField.of(new BigDecimal("1000.00"), FieldState.DERIVED))
                .unitPriceExclVat(TrackedField.of(new BigDecimal("500.00"), FieldState.DERIVED))
                .build();

        ReceiptDraft draft = draftWithLine(line);
        draft.setDocumentType(DocumentType.DELIVERY_NOTE);
        // hlavička dopočtená ze řádků: subtotal = Σ základů, total = Σ s DPH, DPH = rozdíl
        draft.getHeader().setSubtotal(TrackedField.of(new BigDecimal("1000.00"), FieldState.DERIVED));
        draft.getHeader().setTotalAmount(TrackedField.of(new BigDecimal("1210.00"), FieldState.DERIVED));
        draft.getHeader().setVatAmount(TrackedField.of(new BigDecimal("210.00"), FieldState.DERIVED));
        return draft;
    }

    /** Draft s konzistentní hlavičkou odvozenou z jediného řádku. */
    private ReceiptDraft draftWithLine(DraftLine line) {
        BigDecimal excl = line.getTotalExclVat().getValue();
        BigDecimal incl = line.getTotalInclVat().getValue();
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
                        .subtotal(TrackedField.of(excl, FieldState.VERBATIM))
                        .vatAmount(TrackedField.of(incl.subtract(excl), FieldState.VERBATIM))
                        .totalAmount(TrackedField.of(incl, FieldState.VERBATIM))
                        .build())
                .supplier(DraftSupplier.builder()
                        .extracted(DraftSupplier.Extracted.builder()
                                .name("Dodavatel").registrationNumber("24787426").build())
                        .build())
                .vatRecap(new ArrayList<>())
                .deliveryNoteRefs(new ArrayList<>())
                .lines(new ArrayList<>(List.of(line)))
                .checks(new ArrayList<>())
                .build();
    }
}
