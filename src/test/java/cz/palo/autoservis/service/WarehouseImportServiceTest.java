package cz.palo.autoservis.service;

import tools.jackson.databind.ObjectMapper;
import cz.palo.autoservis.AbstractIntegrationTest;
import cz.palo.autoservis.model.domain.user.Role;
import cz.palo.autoservis.model.domain.user.User;
import cz.palo.autoservis.model.draft.FieldState;
import cz.palo.autoservis.model.draft.ReceiptDraft;
import cz.palo.autoservis.model.dto.warehouse.DocumentExtractionResult;
import cz.palo.autoservis.model.dto.warehouse.DocumentExtractionResult.*;
import cz.palo.autoservis.security.model.domain.AppUserDetails;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Full-stack integrační test endpointu importu PDF (draft pipeline):
// HTTP (MockMvc) → controller → service → GlobalExceptionHandler.
// Mockuje se jen AI extrakce; sestavení draftu, verifikace, idempotence
// a zápisy do DB běží doopravdy proti Testcontainers DB.
//
// Klíčový invariant přepracování: import ukládá JEN hlavičku goods_receipts
// + JSONB draft — žádné produkty, šarže ani skladové pohyby až do potvrzení.
@AutoConfigureMockMvc
@Transactional
class WarehouseImportServiceTest extends AbstractIntegrationTest {

    private static final String IMPORT_URL = "/api/v1/warehouse/receipts/import";
    /** Platné české IČO (kontrolní součet mod-11): 1234567 → kontrolní číslice 9. */
    private static final String VALID_ICO = "12345679";

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper objectMapper;

    @MockitoBean
    private PdfDocumentExtractionService extractionService;

    private AppUserDetails admin() {
        return new AppUserDetails(User.builder()
                .id(1L)
                .username("admin")
                .passwordHash("n/a")
                .enabled(true)
                .accountNonExpired(true)
                .accountNonLocked(true)
                .credentialsNonExpired(true)
                .roles(List.of(Role.builder().name("ROLE_ADMIN").build()))
                .build());
    }

    private MockMultipartFile pdfFile() {
        return new MockMultipartFile(
                "file", "doklad.pdf", "application/pdf", "%PDF-1.4 fake".getBytes());
    }

    private static F f(String v) { return v == null ? new F(null, SourceState.ABSENT) : new F(v, SourceState.VERBATIM); }
    private static FDec f(BigDecimal v) { return v == null ? new FDec(null, SourceState.ABSENT) : new FDec(v, SourceState.VERBATIM); }
    private static FDate f(LocalDate v) { return v == null ? new FDate(null, SourceState.ABSENT) : new FDate(v, SourceState.VERBATIM); }

    /**
     * Konzistentní faktura à la LKQ: sazba na řádku jako písmeno "C",
     * řádkový součet jen bez DPH (2 × 500 = 1000), s DPH chybí (dopočte kód),
     * rekapitulace C → 21 %: základ 1000, DPH 210, celkem 1210.
     */
    private DocumentExtractionResult invoiceWithLetterRate() {
        return new DocumentExtractionResult(
                new Header(f("TEST-2026-001"), f((String) null), f((String) null),
                        f(LocalDate.of(2026, 7, 1)), f(LocalDate.of(2026, 7, 15)),
                        f(LocalDate.of(2026, 7, 1)), f("CZK")),
                new Supplier("Testovací dodavatel s.r.o.", VALID_ICO, "CZ" + VALID_ICO,
                        "Testovací 1", "Praha", "11000", null, null, null),
                List.of(new Line(LineKind.ITEM, 1,
                        f("SKU-TEST-1"), f("Testovací díl"), f("ks"),
                        f(new BigDecimal("2")), f(new BigDecimal("500.00")),
                        f("C"),
                        f(new BigDecimal("1000.00")), f((BigDecimal) null),
                        null)),
                List.of(new VatRecapRow("C", 21, new BigDecimal("1000.00"), new BigDecimal("210.00"))),
                new Summary(f(new BigDecimal("1000.00")), f(new BigDecimal("210.00")),
                        f(new BigDecimal("1210.00"))));
    }

    /** Dodací list: bez rekapitulace, bez souhrnu, sazba na řádku chybí. */
    private DocumentExtractionResult deliveryNote() {
        return new DocumentExtractionResult(
                new Header(f("DL-2026-777"), f((String) null), f((String) null),
                        f(LocalDate.of(2026, 7, 8)), f((LocalDate) null), f((LocalDate) null),
                        f((String) null)),
                new Supplier("Testovací dodavatel s.r.o.", VALID_ICO, null,
                        null, null, null, null, null, null),
                List.of(new Line(LineKind.ITEM, 1,
                        f("BS 220-005"), f("Zadní tlumič výfuku"), f("ks"),
                        f(new BigDecimal("1")), f(new BigDecimal("1189.39")),
                        new F(null, SourceState.ABSENT),
                        f(new BigDecimal("1189.39")), f((BigDecimal) null),
                        null)),
                null,
                null);
    }

    private long count(String table) {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
        return n == null ? 0 : n;
    }

    private void insertSupplier(String ico) {
        jdbc.update("INSERT INTO warehouse.suppliers (name, registration_number) VALUES (?, ?)",
                "Testovací dodavatel s.r.o.", ico);
    }

    private ReceiptDraft loadDraft(String documentNumber) throws Exception {
        String json = jdbc.queryForObject(
                "SELECT draft_payload::text FROM warehouse.goods_receipts WHERE invoice_number = ?",
                String.class, documentNumber);
        return objectMapper.readValue(json, ReceiptDraft.class);
    }

    // ------------------------------------------------------------------ testy

    @Test
    @DisplayName("import uloží jen draft — žádné produkty, šarže ani pohyby")
    void importStoresOnlyDraft() throws Exception {
        given(extractionService.extract(any(), any(), any())).willReturn(invoiceWithLetterRate());

        mockMvc.perform(multipart(IMPORT_URL)
                        .file(pdfFile()).param("documentType", "INVOICE")
                        .with(user(admin())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.documentNumber").value("TEST-2026-001"))
                .andExpect(jsonPath("$.status").value("PENDING_REVIEW"))
                .andExpect(jsonPath("$.reconciliationOk").value(true))
                .andExpect(jsonPath("$.supplierMatched").value(false));

        assertThat(count("warehouse.goods_receipts")).isEqualTo(1);
        assertThat(count("warehouse.products")).isZero();
        assertThat(count("warehouse.goods_receipt_items")).isZero();
        assertThat(count("warehouse.stock_movements")).isZero();
        // neznámý dodavatel se při importu nezakládá
        assertThat(count("warehouse.suppliers")).isZero();
    }

    @Test
    @DisplayName("písmenná sazba C se přeloží přes rekapitulaci na 21 % (DERIVED→VERIFIED)")
    void letterVatCodeResolvedViaRecap() throws Exception {
        given(extractionService.extract(any(), any(), any())).willReturn(invoiceWithLetterRate());

        mockMvc.perform(multipart(IMPORT_URL)
                        .file(pdfFile()).param("documentType", "INVOICE")
                        .with(user(admin())))
                .andExpect(status().isCreated());

        ReceiptDraft draft = loadDraft("TEST-2026-001");
        var line = draft.getLines().get(0);
        assertThat(line.getVatRate().getValue()).isEqualTo(21);
        // sazba i dopočtený součet s DPH prošly LINE_MATH → povýšeno na VERIFIED
        assertThat(line.getVatRate().getState()).isEqualTo(FieldState.VERIFIED);
        assertThat(line.getTotalInclVat().getValue()).isEqualByComparingTo("1210.00");
        assertThat(line.getTotalInclVat().getState()).isEqualTo(FieldState.VERIFIED);
        assertThat(draft.getHeader().getTotalAmount().getState()).isEqualTo(FieldState.VERIFIED);
        assertThat(draft.getChecks())
                .anyMatch(c -> c.getCode().equals("LINE_MATH") && c.isOk())
                .anyMatch(c -> c.getCode().equals("ICO_CHECKSUM") && c.isOk());
    }

    @Test
    @DisplayName("dodací list: sazba DEFAULTED 21, měna DEFAULTED CZK, součty DERIVED z řádků")
    void deliveryNoteGetsDefaults() throws Exception {
        given(extractionService.extract(any(), any(), any())).willReturn(deliveryNote());

        mockMvc.perform(multipart(IMPORT_URL)
                        .file(pdfFile()).param("documentType", "DELIVERY_NOTE")
                        .with(user(admin())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.documentType").value("DELIVERY_NOTE"));

        ReceiptDraft draft = loadDraft("DL-2026-777");
        var line = draft.getLines().get(0);
        assertThat(line.getVatRate().getValue()).isEqualTo(21);
        assertThat(line.getVatRate().getState()).isEqualTo(FieldState.DEFAULTED);
        assertThat(draft.getHeader().getCurrency().getState()).isEqualTo(FieldState.DEFAULTED);
        // vat_amount už není hardcoded 21 Kč — je dopočtený z řádků (1189.39 × 0.21)
        assertThat(draft.getHeader().getVatAmount().getValue()).isEqualByComparingTo("249.77");
        BigDecimal vatAmount = jdbc.queryForObject(
                "SELECT vat_amount FROM warehouse.goods_receipts WHERE invoice_number = 'DL-2026-777'",
                BigDecimal.class);
        assertThat(vatAmount).isEqualByComparingTo("249.77");
    }

    @Test
    @DisplayName("duplicitní import (známý dodavatel + stejné číslo) → 409 DUPLICATE_IMPORT")
    void duplicateImport_returns409() throws Exception {
        insertSupplier(VALID_ICO);
        given(extractionService.extract(any(), any(), any())).willReturn(invoiceWithLetterRate());

        mockMvc.perform(multipart(IMPORT_URL)
                        .file(pdfFile()).param("documentType", "INVOICE")
                        .with(user(admin())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.supplierMatched").value(true));

        mockMvc.perform(multipart(IMPORT_URL)
                        .file(pdfFile()).param("documentType", "INVOICE")
                        .with(user(admin())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail",
                        containsString("TEST-2026-001 od tohoto dodavatele už je naimportovaný")))
                .andExpect(jsonPath("$.errors[0].code").value("DUPLICATE_IMPORT"));
    }

    @Test
    @DisplayName("po REJECTED se stejné číslo dokladu smí importovat znovu → 201")
    void reimportAfterRejectedSucceeds() throws Exception {
        insertSupplier(VALID_ICO);
        Long supplierId = jdbc.queryForObject(
                "SELECT id FROM warehouse.suppliers WHERE registration_number = ?",
                Long.class, VALID_ICO);
        jdbc.update("""
                INSERT INTO warehouse.goods_receipts
                    (supplier_id, invoice_number, status, document_type, source_channel)
                VALUES (?, 'TEST-2026-001', 'REJECTED', 'INVOICE', 'AI_PDF')
                """, supplierId);

        given(extractionService.extract(any(), any(), any())).willReturn(invoiceWithLetterRate());

        mockMvc.perform(multipart(IMPORT_URL)
                        .file(pdfFile()).param("documentType", "INVOICE")
                        .with(user(admin())))
                .andExpect(status().isCreated());
    }
}
