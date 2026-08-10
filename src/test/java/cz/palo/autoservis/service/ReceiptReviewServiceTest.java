package cz.palo.autoservis.service;

import cz.palo.autoservis.AbstractIntegrationTest;
import cz.palo.autoservis.model.domain.user.Role;
import cz.palo.autoservis.model.domain.user.User;
import cz.palo.autoservis.model.domain.warehouse.DocumentType;
import cz.palo.autoservis.model.draft.FieldState;
import cz.palo.autoservis.model.draft.ReceiptDraft;
import cz.palo.autoservis.model.dto.warehouse.DocumentExtractionResult;
import cz.palo.autoservis.model.dto.warehouse.DocumentExtractionResult.*;
import cz.palo.autoservis.service.ReceiptReviewService;
import cz.palo.autoservis.security.model.domain.AppUserDetails;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Full-stack test review workflow: import (jen draft) → confirm/reject.
// Potvrzení je JEDINÉ místo, kde se materializují dodavatelé, produkty, šarže
// a pohyby RECEIPT; quantity_on_hand musí zvednout DB trigger.
@AutoConfigureMockMvc
@Transactional
class ReceiptReviewServiceTest extends AbstractIntegrationTest {

    private static final String IMPORT_URL = "/api/v1/warehouse/receipts/import";
    private static final String RECEIPTS_URL = "/api/v1/warehouse/receipts";
    private static final String VALID_ICO = "12345679";

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private tools.jackson.databind.ObjectMapper objectMapper;
    @Autowired private ReceiptReviewService service;

    @MockitoBean
    private PdfDocumentExtractionService extractionService;

    private AppUserDetails admin() {
        return new AppUserDetails(User.builder()
                .id(1L).username("admin").passwordHash("n/a")
                .enabled(true).accountNonExpired(true).accountNonLocked(true)
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

    /** Konzistentní faktura: 2 ks × 500 Kč, 21 % → 1000 / 210 / 1210. */
    private DocumentExtractionResult consistentInvoice(String documentNumber) {
        return new DocumentExtractionResult(
                new Header(f(documentNumber), f((String) null), f((String) null),
                        f(LocalDate.of(2026, 7, 1)), f(LocalDate.of(2026, 7, 15)),
                        f(LocalDate.of(2026, 7, 1)), f("CZK")),
                new Supplier("Testovací dodavatel s.r.o.", VALID_ICO, "CZ" + VALID_ICO,
                        "Testovací 1", "Praha", "11000", null, null, null),
                List.of(new Line(LineKind.ITEM, 1,
                        f("SKU-TEST-1"), f("Testovací díl"), f("ks"),
                        f(new BigDecimal("2")), f(new BigDecimal("500.00")),
                        f("21"),
                        f(new BigDecimal("1000.00")), f(new BigDecimal("1210.00")),
                        null)),
                null,
                new Summary(f(new BigDecimal("1000.00")), f(new BigDecimal("210.00")),
                        f(new BigDecimal("1210.00"))));
    }

    /** Faktura bez data vystavení → completeness gate ji nepustí. */
    private DocumentExtractionResult invoiceWithoutIssueDate(String documentNumber) {
        var full = consistentInvoice(documentNumber);
        return new DocumentExtractionResult(
                new Header(full.header().documentNumber(), full.header().orderNumber(),
                        full.header().originalOrderNumber(),
                        new FDate(null, SourceState.ABSENT),
                        full.header().dueDate(), full.header().taxableSupplyDate(),
                        full.header().currency()),
                full.supplier(), full.lines(), full.vatRecap(), full.summary());
    }

    private long importReceipt(DocumentExtractionResult extraction) throws Exception {
        given(extractionService.extract(any(), any(), any())).willReturn(extraction);
        MvcResult result = mockMvc.perform(multipart(IMPORT_URL)
                        .file(pdfFile()).param("documentType", "INVOICE")
                        .with(user(admin())))
                .andExpect(status().isCreated())
                .andReturn();
        return ((Number) com.jayway.jsonpath.JsonPath.read(
                result.getResponse().getContentAsString(), "$.receiptId")).longValue();
    }

    private long count(String table) {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
        return n == null ? 0 : n;
    }

    /** Načte perzistovaný draft z JSONB sloupce (kontrola toho, co je opravdu uloženo). */
    private ReceiptDraft loadDraft(long id) throws Exception {
        String payload = jdbc.queryForObject(
                "SELECT draft_payload FROM warehouse.goods_receipts WHERE id = ?", String.class, id);
        return objectMapper.readValue(payload, ReceiptDraft.class);
    }

    // ------------------------------------------------------------------ TD-59: validace/normalizace draftu

    @Test
    @DisplayName("PUT draftu s neúplným tělem ({}) → 400, ne NPE→500 (TD-59)")
    void updateDraft_malformedBody_returns400() throws Exception {
        long id = importReceipt(consistentInvoice("FAK-MALFORMED"));

        mockMvc.perform(put(RECEIPTS_URL + "/" + id + "/draft")
                        .contentType(APPLICATION_JSON).content("{}")
                        .with(user(admin())))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("padělaný state VERIFIED z klienta se neuloží — sražen na baseline (TD-59)")
    void updateDraft_forgedVerifiedState_isStripped() throws Exception {
        long id = importReceipt(consistentInvoice("FAK-FORGE"));
        ReceiptDraft draft = loadDraft(id);
        // documentNumber deterministická verify() NIKDY nepovyšuje → čistý kanárek na padělek stavu.
        draft.getHeader().getDocumentNumber().setState(FieldState.VERIFIED);

        service.updateDraft(id, draft, 1L);

        assertThat(loadDraft(id).getHeader().getDocumentNumber().getState())
                .as("kódem-vlastněný VERIFIED z klienta se zahodí (kód ho nezaslouží)")
                .isNotEqualTo(FieldState.VERIFIED);
    }

    @Test
    @DisplayName("documentType se bere ze sloupce příjemky, ne z těla draftu (TD-59)")
    void updateDraft_documentTypeFromColumn_notPayload() throws Exception {
        long id = importReceipt(consistentInvoice("FAK-DOCTYPE"));   // sloupec document_type = INVOICE
        ReceiptDraft draft = loadDraft(id);
        draft.setDocumentType(DocumentType.DELIVERY_NOTE);           // pokus rozejít payload se sloupcem

        service.updateDraft(id, draft, 1L);

        assertThat(loadDraft(id).getDocumentType())
                .as("payload nesmí přebít autoritativní sloupec document_type")
                .isEqualTo(DocumentType.INVOICE);
    }

    // ------------------------------------------------------------------ testy

    @Test
    @DisplayName("confirm materializuje: dodavatel, produkt, šarže, pohyb, sklad — a doklad je v autocomplete")
    void confirmMaterializesStock() throws Exception {
        long id = importReceipt(consistentInvoice("FAK-100"));
        assertThat(count("warehouse.stock_movements")).isZero();

        mockMvc.perform(post(RECEIPTS_URL + "/" + id + "/confirm").with(user(admin())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));

        assertThat(count("warehouse.suppliers")).isEqualTo(1);
        assertThat(count("warehouse.products")).isEqualTo(1);
        assertThat(count("warehouse.goods_receipt_items")).isEqualTo(1);
        assertThat(count("warehouse.stock_movements")).isEqualTo(1);

        BigDecimal onHand = jdbc.queryForObject(
                "SELECT quantity_on_hand FROM warehouse.products WHERE sku = 'SKU-TEST-1'",
                BigDecimal.class);
        assertThat(onHand).isEqualByComparingTo("2");   // trigger fn_apply_stock_movement

        // potvrzený doklad se objeví v autocomplete pro import položek do zakázky
        mockMvc.perform(get("/api/v1/warehouse/goods-receipts/autocomplete")
                        .param("q", "FAK-100").param("importType", "INVOICE_NUMBER")
                        .with(user(admin())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].value").value("FAK-100"));
    }

    // ------------------------------------------------------------------ KN-4: dvojí naskladnění

    /** Faktura od dodavatele BEZ IČO — dedup podle supplier_id nemá s čím porovnávat. */
    private DocumentExtractionResult invoiceWithoutIco(String documentNumber) {
        var full = consistentInvoice(documentNumber);
        return new DocumentExtractionResult(
                full.header(),
                new Supplier("Ručně psaný dodavatel", null, null,
                        null, null, null, null, null, null),
                full.lines(), full.vatRecap(), full.summary());
    }

    /** Faktura o dvou řádcích — kvůli testu, kdy se má přeskočit jen jeden z nich. */
    private DocumentExtractionResult twoLineInvoice(String documentNumber) {
        var base = consistentInvoice(documentNumber);
        var second = new Line(LineKind.ITEM, 2,
                f("SKU-TEST-2"), f("Druhý díl"), f("ks"),
                f(new BigDecimal("1")), f(new BigDecimal("200.00")),
                f("21"),
                f(new BigDecimal("200.00")), f(new BigDecimal("242.00")),
                null);
        return new DocumentExtractionResult(
                base.header(), base.supplier(),
                List.of(base.lines().getFirst(), second),
                base.vatRecap(),
                new Summary(f(new BigDecimal("1200.00")), f(new BigDecimal("252.00")),
                        f(new BigDecimal("1452.00"))));
    }

    /** Jednořádkový dodací list s vlastním SKU — „zboží, které už fyzicky přišlo". */
    private DocumentExtractionResult deliveryNoteFor(String documentNumber, String sku) {
        var base = consistentInvoice(documentNumber);
        var line = new Line(LineKind.ITEM, 1,
                f(sku), f("Díl z dodacího listu"), f("ks"),
                f(new BigDecimal("2")), f(new BigDecimal("500.00")),
                f("21"),
                f(new BigDecimal("1000.00")), f(new BigDecimal("1210.00")),
                null);
        return new DocumentExtractionResult(
                base.header(), base.supplier(), List.of(line), base.vatRecap(), base.summary());
    }

    private long importReceipt(DocumentExtractionResult extraction, String documentType) throws Exception {
        given(extractionService.extract(any(), any(), any())).willReturn(extraction);
        MvcResult result = mockMvc.perform(multipart(IMPORT_URL)
                        .file(pdfFile()).param("documentType", documentType)
                        .with(user(admin())))
                .andExpect(status().isCreated())
                .andReturn();
        return ((Number) com.jayway.jsonpath.JsonPath.read(
                result.getResponse().getContentAsString(), "$.receiptId")).longValue();
    }

    /** Přepíše uložený draft (obchází sanitizaci v updateDraft — nastavujeme stav po review). */
    private void saveDraft(long id, ReceiptDraft draft) throws Exception {
        jdbc.update("UPDATE warehouse.goods_receipts SET draft_payload = CAST(? AS jsonb) WHERE id = ?",
                objectMapper.writeValueAsString(draft), id);
    }

    @Test
    @DisplayName("„pouze provázat\" u DL, který nejde přiřadit k řádkům → 422, nic se nenaskladní (KN-4a)")
    void confirm_linkedDeliveryNoteWithoutLineAttribution_isRejected() throws Exception {
        long id = importReceipt(consistentInvoice("FAK-DL-LINK"));

        // Stav po review: uživatel označil dodací list jako „jen provázat". Číslo DL ale žádný
        // POLOŽKOVÝ řádek nenese — extrakce ho podle kontraktu plní jen u skupinového řádku,
        // takže přeskočení v materializaci nemá podle čeho poznat, které řádky vynechat.
        ReceiptDraft draft = loadDraft(id);
        draft.setDeliveryNoteRefs(new java.util.ArrayList<>(List.of(
                cz.palo.autoservis.model.draft.DeliveryNoteRef.builder()
                        .number("DL-777")
                        .matchedReceiptId(4242L)
                        .resolution("LINKED")
                        .build())));
        saveDraft(id, draft);

        mockMvc.perform(post(RECEIPTS_URL + "/" + id + "/confirm").with(user(admin())))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].code").value("DELIVERY_NOTE_LINK_NOT_APPLICABLE"));

        // Dřív se tenhle doklad naskladnil CELÝ — volba „pouze provázat" byla mrtvá.
        assertThat(count("warehouse.stock_movements"))
                .as("odmítnuté potvrzení nesmí naskladnit nic").isZero();
    }

    @Test
    @DisplayName("provázaný DL přeskočí JEN své řádky, zbytek faktury se naskladní (KN-4a)")
    void confirm_linkedDeliveryNoteWithLineAttribution_skipsOnlyCoveredLines() throws Exception {
        // Reálný postup: nejdřív dorazí zboží s dodacím listem a naskladní se…
        long deliveryNote = importReceipt(deliveryNoteFor("DL-888", "SKU-DL-1"), "DELIVERY_NOTE");
        mockMvc.perform(post(RECEIPTS_URL + "/" + deliveryNote + "/confirm").with(user(admin())))
                .andExpect(status().isOk());
        assertThat(count("warehouse.stock_movements")).isEqualTo(1);

        // …a teprve potom přijde faktura, která ten dodací list přefakturovává a navíc přidává
        // jednu novou položku. Řádek 1 je krytý dodacím listem, řádek 2 ne.
        long id = importReceipt(twoLineInvoice("FAK-DL-OK"));
        ReceiptDraft draft = loadDraft(id);
        draft.getLines().getFirst().setDeliveryNoteNumber("DL-888");
        draft.setDeliveryNoteRefs(new java.util.ArrayList<>(List.of(
                cz.palo.autoservis.model.draft.DeliveryNoteRef.builder()
                        .number("DL-888")
                        .matchedReceiptId(deliveryNote)
                        .resolution("LINKED")
                        .build())));
        saveDraft(id, draft);

        mockMvc.perform(post(RECEIPTS_URL + "/" + id + "/confirm").with(user(admin())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));

        assertThat(count("warehouse.stock_movements"))
                .as("k pohybu z dodacího listu přibude jen nekrytý řádek faktury").isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM warehouse.products WHERE sku = 'SKU-TEST-1'", Long.class))
                .as("krytý řádek se nenaskladnil podruhé a nezaložil ani kartu").isZero();
        assertThat(jdbc.queryForObject(
                "SELECT quantity_on_hand FROM warehouse.products WHERE sku = 'SKU-DL-1'",
                BigDecimal.class))
                .as("zboží z dodacího listu zůstalo na původním množství").isEqualByComparingTo("2");
    }

    @Test
    @DisplayName("týž doklad od dodavatele BEZ IČO nejde naskladnit dvakrát → 409 (KN-4b)")
    void confirm_sameDocumentFromSupplierWithoutIco_isRejectedSecondTime() throws Exception {
        long first = importReceipt(invoiceWithoutIco("DL-RUCNI-1"));
        mockMvc.perform(post(RECEIPTS_URL + "/" + first + "/confirm").with(user(admin())))
                .andExpect(status().isOk());
        assertThat(count("warehouse.stock_movements")).isEqualTo(1);

        // Týž doklad nahraný podruhé. Dodavatel nemá IČO, takže se při potvrzení zakládá znovu
        // a dedup podle supplier_id by neměl s čím porovnávat — dřív se zboží naskladnilo
        // podruhé a vznikl duplicitní dodavatel.
        long second = importReceipt(invoiceWithoutIco("DL-RUCNI-1"));

        mockMvc.perform(post(RECEIPTS_URL + "/" + second + "/confirm").with(user(admin())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors[0].code").value("DUPLICATE_IMPORT"));

        assertThat(count("warehouse.stock_movements"))
                .as("žádné druhé naskladnění").isEqualTo(1);
        assertThat(count("warehouse.suppliers"))
                .as("a žádný duplicitní dodavatel").isEqualTo(1);
    }

    // ------------------------------------------------------------------ KN-16: deaktivovaná karta / dodavatel

    @Test
    @DisplayName("potvrzení na DEAKTIVOVANOU kartu dílu ji reaktivuje, nezaloží duplicitu (KN-16)")
    void confirm_inactiveProductCard_isReactivated() throws Exception {
        // Karta se stejným SKU existuje, ale je vyřazená. Párování hledá jen aktivní, takže
        // řádek propadne na založení nové karty — a `uq_products_sku` platí bez ohledu na
        // is_active, takže insert dřív spadl na porušení UNIQUE a příjemku nešlo dokončit.
        jdbc.update("INSERT INTO warehouse.products (sku, name, unit, default_vat_rate, is_active) "
                + "VALUES ('SKU-TEST-1', 'Vyřazená karta', 'ks', 21, FALSE)");

        long id = importReceipt(consistentInvoice("FAK-INACTIVE-PROD"));

        mockMvc.perform(post(RECEIPTS_URL + "/" + id + "/confirm").with(user(admin())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));

        assertThat(count("warehouse.products"))
                .as("žádná duplicitní karta — použije se ta existující").isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT is_active FROM warehouse.products WHERE sku = 'SKU-TEST-1'", Boolean.class))
                .as("zboží fyzicky přišlo → karta se reaktivuje, jinak by zásoba zmizela "
                        + "z ocenění skladu i z inventury")
                .isTrue();
        assertThat(jdbc.queryForObject(
                "SELECT quantity_on_hand FROM warehouse.products WHERE sku = 'SKU-TEST-1'",
                BigDecimal.class))
                .isEqualByComparingTo("2");
    }

    @Test
    @DisplayName("potvrzení s DEAKTIVOVANÝM dodavatelem → 422 SUPPLIER_INACTIVE, ne pád na UNIQUE (KN-16)")
    void confirm_inactiveSupplier_isRejectedWithActionableMessage() throws Exception {
        jdbc.update("INSERT INTO warehouse.suppliers (name, registration_number, is_active) "
                + "VALUES (?, ?, FALSE)", "Vyřazený dodavatel s.r.o.", VALID_ICO);

        long id = importReceipt(consistentInvoice("FAK-INACTIVE-SUPP"));

        mockMvc.perform(post(RECEIPTS_URL + "/" + id + "/confirm").with(user(admin())))
                .andExpect(status().isUnprocessableEntity())
                // dřív sem přišlo DATA_INTEGRITY_VIOLATION („Data se nepodařilo uložit"),
                // ze kterého obsluha nemohla poznat, co má udělat
                .andExpect(jsonPath("$.errors[0].code").value("SUPPLIER_INACTIVE"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("deaktivovaný")));

        assertThat(count("warehouse.stock_movements"))
                .as("odmítnuté potvrzení nesmí nic naskladnit").isZero();
        assertThat(count("warehouse.suppliers"))
                .as("a už vůbec ne založit druhého dodavatele se stejným IČO").isEqualTo(1);
    }

    @Test
    @DisplayName("skladový pohyb je append-only — přímé DELETE selže na DB triggeru (E3.1/K-13)")
    void stockMovementIsAppendOnly() throws Exception {
        long id = importReceipt(consistentInvoice("FAK-APPEND"));
        mockMvc.perform(post(RECEIPTS_URL + "/" + id + "/confirm").with(user(admin())))
                .andExpect(status().isOk());
        Long movementId = jdbc.queryForObject(
                "SELECT id FROM warehouse.stock_movements ORDER BY id DESC LIMIT 1", Long.class);

        assertThatThrownBy(() -> jdbc.update("DELETE FROM warehouse.stock_movements WHERE id = ?", movementId))
                .as("append-only trigger nedovolí smazat pohyb")
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    @Test
    @DisplayName("seznam příjemek je 1-based: page=1 → first=true, page=1 (E0.7)")
    void listIsOneBased() throws Exception {
        importReceipt(consistentInvoice("FAK-PAGE"));

        mockMvc.perform(get(RECEIPTS_URL).param("page", "1").param("pageSize", "10").with(user(admin())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.first").value(true))
                .andExpect(jsonPath("$.content").isNotEmpty());
    }

    @Test
    @DisplayName("confirm s chybějícím povinným polem → 422 RECEIPT_INCOMPLETE")
    void confirmIncompleteFails() throws Exception {
        long id = importReceipt(invoiceWithoutIssueDate("FAK-101"));

        mockMvc.perform(post(RECEIPTS_URL + "/" + id + "/confirm").with(user(admin())))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].code").value("RECEIPT_INCOMPLETE"));

        assertThat(count("warehouse.stock_movements")).isZero();
    }

    @Test
    @DisplayName("confirm s ne-CZK měnou → 422 RECEIPT_INCOMPLETE s currency; po přepsání na CZK projde")
    void confirmNonCzkCurrencyBlocked() throws Exception {
        // faktura v EUR — ceny šarží by se dál tvářily jako CZK (R-F)
        var full = consistentInvoice("FAK-EUR-1");
        var eurInvoice = new DocumentExtractionResult(
                new Header(full.header().documentNumber(), full.header().orderNumber(),
                        full.header().originalOrderNumber(), full.header().issueDate(),
                        full.header().dueDate(), full.header().taxableSupplyDate(),
                        f("EUR")),
                full.supplier(), full.lines(), full.vatRecap(), full.summary());
        long id = importReceipt(eurInvoice);

        mockMvc.perform(post(RECEIPTS_URL + "/" + id + "/confirm").with(user(admin())))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].code").value("RECEIPT_INCOMPLETE"))
                .andExpect(jsonPath("$.errors[0].params.currency").value(
                        org.hamcrest.Matchers.containsString("EUR")));
        assertThat(count("warehouse.stock_movements")).isZero();

        // měna jde v review přepsat jako každé jiné pole → confirm projde
        String detailJson = mockMvc.perform(get(RECEIPTS_URL + "/" + id).with(user(admin())))
                .andReturn().getResponse().getContentAsString();
        var json = objectMapper.readTree(detailJson);
        var draftNode = (tools.jackson.databind.node.ObjectNode) json.get("draft");
        ((tools.jackson.databind.node.ObjectNode) draftNode.get("header"))
                .set("currency", tracked("CZK"));
        mockMvc.perform(put(RECEIPTS_URL + "/" + id + "/draft")
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(draftNode))
                        .with(user(admin())))
                .andExpect(status().isOk());

        mockMvc.perform(post(RECEIPTS_URL + "/" + id + "/confirm").with(user(admin())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));
        assertThat(count("warehouse.stock_movements")).isEqualTo(1);
    }

    @Test
    @DisplayName("dvojí confirm → druhý selže (příjemka už není PENDING_REVIEW)")
    void doubleConfirmFails() throws Exception {
        long id = importReceipt(consistentInvoice("FAK-102"));

        mockMvc.perform(post(RECEIPTS_URL + "/" + id + "/confirm").with(user(admin())))
                .andExpect(status().isOk());
        mockMvc.perform(post(RECEIPTS_URL + "/" + id + "/confirm").with(user(admin())))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].code").value("RECEIPT_NOT_EDITABLE"));

        assertThat(count("warehouse.stock_movements")).isEqualTo(1);   // ne 2
    }

    @Test
    @DisplayName("reject nematerializuje nic a stejný doklad jde importovat znovu")
    void rejectFreesDocumentNumber() throws Exception {
        long id = importReceipt(consistentInvoice("FAK-103"));

        mockMvc.perform(post(RECEIPTS_URL + "/" + id + "/reject")
                        .contentType(APPLICATION_JSON)
                        .content("{\"note\":\"špatná extrakce\"}")
                        .with(user(admin())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.rejectionNote").value("špatná extrakce"));

        assertThat(count("warehouse.stock_movements")).isZero();
        assertThat(count("warehouse.goods_receipt_items")).isZero();

        importReceipt(consistentInvoice("FAK-103"));   // 201, číslo je volné
    }

    @Test
    @DisplayName("stejný díl od druhého dodavatele: návrh přes číslo dílu, po potvrzení JEDNA karta")
    void samePartFromSecondSupplierMatchesViaPartNumber() throws Exception {
        // 1) dodavatel A doručí díl "EL 871.180" → potvrzením vznikne karta
        long first = importReceipt(consistentInvoice("FAK-200"));
        mockMvc.perform(post(RECEIPTS_URL + "/" + first + "/confirm").with(user(admin())))
                .andExpect(status().isOk());
        // FAK-200 má SKU-TEST-1; kartě nastavíme číslo výrobce, ať je co párovat
        jdbc.update("UPDATE warehouse.products SET manufacturer_part_number = '871.180' WHERE sku = 'SKU-TEST-1'");

        // 2) dodavatel B (jiné IČO) fakturuje týž díl pod svým kódem "ELR 871-180"
        var supplierB = new Supplier("Druhý dodavatel s.r.o.", "24787426", "CZ24787426",
                null, null, null, null, null, null);
        var invoiceB = new DocumentExtractionResult(
                new Header(f("FAK-201"), f((String) null), f((String) null),
                        f(LocalDate.of(2026, 7, 2)), f((LocalDate) null), f((LocalDate) null), f("CZK")),
                supplierB,
                List.of(new Line(LineKind.ITEM, 1,
                        f("ELR 871-180"), f("Testovací díl"), f("ks"),
                        f(new BigDecimal("2")), f(new BigDecimal("500.00")),
                        f("21"),
                        f(new BigDecimal("1000.00")), f(new BigDecimal("1210.00")), null)),
                null,
                new Summary(f(new BigDecimal("1000.00")), f(new BigDecimal("210.00")),
                        f(new BigDecimal("1210.00"))));
        long second = importReceipt(invoiceB);

        // kaskáda našla kandidáta přes normalizované číslo → SUGGESTED
        String detailJson = mockMvc.perform(get(RECEIPTS_URL + "/" + second).with(user(admin())))
                .andExpect(jsonPath("$.draft.lines[0].productMatch.state").value("SUGGESTED"))
                .andReturn().getResponse().getContentAsString();

        // bez vyřešení návrhu confirm neprojde
        mockMvc.perform(post(RECEIPTS_URL + "/" + second + "/confirm").with(user(admin())))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].code").value("RECEIPT_INCOMPLETE"));

        // 3) uživatel volbu potvrdí (PUT draftu s productMatch CONFIRMED)
        var json = objectMapper.readTree(detailJson);
        var draftNode = (tools.jackson.databind.node.ObjectNode) json.get("draft");
        var lineNode = (tools.jackson.databind.node.ObjectNode) draftNode.get("lines").get(0);
        Long candidateId = lineNode.get("productMatch").get("candidates").get(0)
                .get("productId").asLong();
        var matchNode = (tools.jackson.databind.node.ObjectNode) lineNode.get("productMatch");
        matchNode.put("state", "CONFIRMED");
        matchNode.put("productId", candidateId);

        mockMvc.perform(put(RECEIPTS_URL + "/" + second + "/draft")
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(draftNode))
                        .with(user(admin())))
                .andExpect(status().isOk());

        mockMvc.perform(post(RECEIPTS_URL + "/" + second + "/confirm").with(user(admin())))
                .andExpect(status().isOk());

        // JEDNA karta, sečtené kusy (2 + 2), dva záznamy převodníku
        assertThat(count("warehouse.products")).isEqualTo(1);
        assertThat(count("warehouse.supplier_products")).isEqualTo(2);
        BigDecimal onHand = jdbc.queryForObject(
                "SELECT quantity_on_hand FROM warehouse.products WHERE sku = 'SKU-TEST-1'",
                BigDecimal.class);
        assertThat(onHand).isEqualByComparingTo("4");
    }

    @Test
    @DisplayName("ruční příjemka: prázdný draft → doplnění řádku → confirm naskladní; prázdnou nelze potvrdit")
    void manualDraftFlow() throws Exception {
        // 1) založení prázdného draftu (bez PDF)
        String createdJson = mockMvc.perform(post(RECEIPTS_URL)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"documentType":"INVOICE","supplierName":"Ruční dodavatel s.r.o.",
                                 "supplierRegistrationNumber":"12345679"}
                                """)
                        .with(user(admin())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sourceChannel").value("MANUAL"))
                .andExpect(jsonPath("$.status").value("PENDING_REVIEW"))
                .andExpect(jsonPath("$.hasPdf").value(false))
                .andReturn().getResponse().getContentAsString();
        long id = ((Number) com.jayway.jsonpath.JsonPath.read(createdJson, "$.id")).longValue();

        // PDF ruční draft nemá
        mockMvc.perform(get(RECEIPTS_URL + "/" + id + "/pdf").with(user(admin())))
                .andExpect(status().isNotFound());

        // 2) prázdný draft nejde potvrdit
        mockMvc.perform(post(RECEIPTS_URL + "/" + id + "/confirm").with(user(admin())))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].code").value("RECEIPT_INCOMPLETE"));

        // 3) doplnění hlavičky + řádku (fillDerivedValues dopočte součty)
        var json = objectMapper.readTree(createdJson);
        var draftNode = (tools.jackson.databind.node.ObjectNode) json.get("draft");
        var header = (tools.jackson.databind.node.ObjectNode) draftNode.get("header");
        header.set("documentNumber", tracked("RUCNI-001"));
        header.set("issueDate", tracked("2026-07-20"));
        var line = objectMapper.createObjectNode();
        line.put("lineKind", "ITEM").put("position", 1);
        line.set("catalogNumber", tracked("MAN-1"));
        line.set("name", tracked("Ruční díl"));
        line.set("unit", tracked("ks"));
        line.set("quantity", trackedNumber("3"));
        line.set("unitPriceExclVat", trackedNumber("100.00"));
        line.set("vatRate", trackedNumber("21"));
        ((tools.jackson.databind.node.ArrayNode) draftNode.withArray("lines")).add(line);

        mockMvc.perform(put(RECEIPTS_URL + "/" + id + "/draft")
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(draftNode))
                        .with(user(admin())))
                .andExpect(status().isOk())
                // dopočty kódu: 3 × 100 = 300 bez DPH, 363 s DPH
                .andExpect(jsonPath("$.draft.lines[0].totalInclVat.value").value(363.00))
                .andExpect(jsonPath("$.totalAmount").value(363.00));

        // 4) potvrzení naskladní
        mockMvc.perform(post(RECEIPTS_URL + "/" + id + "/confirm").with(user(admin())))
                .andExpect(status().isOk());
        BigDecimal onHand = jdbc.queryForObject(
                "SELECT quantity_on_hand FROM warehouse.products WHERE sku = 'MAN-1'",
                BigDecimal.class);
        assertThat(onHand).isEqualByComparingTo("3");
    }

    @Test
    @DisplayName("dedup DL↔faktura: LINKED nenaskladní znovu a uloží vazbu")
    void deliveryNoteInvoiceDedup() throws Exception {
        // 1) dodací list přijde první a naskladní se
        var dl = new DocumentExtractionResult(
                new Header(f("3726026714"), f((String) null), f((String) null),
                        f(LocalDate.of(2026, 7, 8)), f((LocalDate) null), f((LocalDate) null), f("CZK")),
                new Supplier("Testovací dodavatel s.r.o.", VALID_ICO, null,
                        null, null, null, null, null, null),
                List.of(new Line(LineKind.ITEM, 1,
                        f("BS 220-005"), f("Zadní tlumič"), f("ks"),
                        f(new BigDecimal("1")), f(new BigDecimal("1189.39")),
                        f("21"),
                        f(new BigDecimal("1189.39")), f(new BigDecimal("1439.16")), null)),
                null,
                new Summary(f(new BigDecimal("1189.39")), f(new BigDecimal("249.77")),
                        f(new BigDecimal("1439.16"))));
        given(extractionService.extract(any(), any(), any())).willReturn(dl);
        MvcResult dlResult = mockMvc.perform(multipart(IMPORT_URL)
                        .file(pdfFile()).param("documentType", "DELIVERY_NOTE")
                        .with(user(admin())))
                .andExpect(status().isCreated()).andReturn();
        long dlId = ((Number) com.jayway.jsonpath.JsonPath.read(
                dlResult.getResponse().getContentAsString(), "$.receiptId")).longValue();
        mockMvc.perform(post(RECEIPTS_URL + "/" + dlId + "/confirm").with(user(admin())))
                .andExpect(status().isOk());
        assertThat(count("warehouse.stock_movements")).isEqualTo(1);

        // 2) souhrnná faktura kryjící tentýž DL (skupinový řádek + položka s DL číslem)
        var invoice = new DocumentExtractionResult(
                new Header(f("1726019023"), f((String) null), f((String) null),
                        f(LocalDate.of(2026, 7, 30)), f((LocalDate) null), f((LocalDate) null), f("CZK")),
                new Supplier("Testovací dodavatel s.r.o.", VALID_ICO, null,
                        null, null, null, null, null, null),
                List.of(
                        new Line(LineKind.DELIVERY_NOTE_GROUP, 1,
                                f((String) null), f("Dodací list č. 3726026714 celkem"), f((String) null),
                                f((BigDecimal) null), f((BigDecimal) null),
                                new F(null, SourceState.ABSENT),
                                f(new BigDecimal("1189.39")), f((BigDecimal) null), "3726026714"),
                        new Line(LineKind.ITEM, 2,
                                f("BS 220-005"), f("Zadní tlumič"), f("ks"),
                                f(new BigDecimal("1")), f(new BigDecimal("1189.39")),
                                f("21"),
                                f(new BigDecimal("1189.39")), f(new BigDecimal("1439.16")), "3726026714")),
                null,
                new Summary(f(new BigDecimal("1189.39")), f(new BigDecimal("249.77")),
                        f(new BigDecimal("1439.16"))));
        long invId = importReceipt(invoice);

        // reference je napárovaná na DL příjemku
        String detailJson = mockMvc.perform(get(RECEIPTS_URL + "/" + invId).with(user(admin())))
                .andExpect(jsonPath("$.draft.deliveryNoteRefs[0].matchedReceiptId").value((int) dlId))
                .andReturn().getResponse().getContentAsString();

        // bez rozhodnutí confirm neprojde
        mockMvc.perform(post(RECEIPTS_URL + "/" + invId + "/confirm").with(user(admin())))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].code").value("RECEIPT_INCOMPLETE"));

        // 3) volba „jen provázat" → confirm projde a NEnaskladní znovu
        var json = objectMapper.readTree(detailJson);
        var draftNode = (tools.jackson.databind.node.ObjectNode) json.get("draft");
        ((tools.jackson.databind.node.ObjectNode) draftNode.get("deliveryNoteRefs").get(0))
                .put("resolution", "LINKED");
        mockMvc.perform(put(RECEIPTS_URL + "/" + invId + "/draft")
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(draftNode))
                        .with(user(admin())))
                .andExpect(status().isOk());
        mockMvc.perform(post(RECEIPTS_URL + "/" + invId + "/confirm").with(user(admin())))
                .andExpect(status().isOk());

        assertThat(count("warehouse.stock_movements")).isEqualTo(1);      // žádný druhý příjem
        assertThat(count("warehouse.goods_receipt_items")).isEqualTo(1);
        String resolution = jdbc.queryForObject("""
                SELECT resolution FROM warehouse.receipt_delivery_note_refs
                WHERE goods_receipt_id = ? AND delivery_note_number = '3726026714'
                """, String.class, invId);
        assertThat(resolution).isEqualTo("LINKED");
    }

    @Test
    @DisplayName("řádek se zápornou cenou → 422 RECEIPT_INCOMPLETE / invalidLines (E6.5/S-3)")
    void confirmNegativePriceLineBlocked() throws Exception {
        var full = consistentInvoice("FAK-NEG-1");
        var line = full.lines().get(0);
        var negativeInvoice = new DocumentExtractionResult(
                full.header(), full.supplier(),
                List.of(new Line(line.kind(), line.position(), line.catalogNumber(),
                        line.name(), line.unit(), line.quantity(),
                        f(new BigDecimal("-500.00")), line.vatRateOrCode(),
                        line.totalExclVat(), f(new BigDecimal("-1210.00")),
                        line.deliveryNoteNumber())),
                full.vatRecap(), full.summary());
        long id = importReceipt(negativeInvoice);

        mockMvc.perform(post(RECEIPTS_URL + "/" + id + "/confirm").with(user(admin())))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].code").value("RECEIPT_INCOMPLETE"))
                .andExpect(jsonPath("$.errors[0].params.invalidLines").isArray());
        assertThat(count("warehouse.stock_movements")).isZero();
    }

    @Test
    @DisplayName("confirm s jednotkou mimo číselník → 422 s invalidUnits; po opravě na ks projde")
    void confirmInvalidUnitBlocked() throws Exception {
        // faktura s jednotkou „krabice" — mimo uzavřený číselník (Z-4)
        var full = consistentInvoice("FAK-UNIT-1");
        var line = full.lines().get(0);
        var badUnitInvoice = new DocumentExtractionResult(
                full.header(), full.supplier(),
                List.of(new Line(line.kind(), line.position(), line.catalogNumber(),
                        line.name(), f("krabice"), line.quantity(), line.unitPriceExclVat(),
                        line.vatRateOrCode(), line.totalExclVat(), line.totalInclVat(),
                        line.deliveryNoteNumber())),
                full.vatRecap(), full.summary());
        long id = importReceipt(badUnitInvoice);

        mockMvc.perform(post(RECEIPTS_URL + "/" + id + "/confirm").with(user(admin())))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].code").value("RECEIPT_INCOMPLETE"))
                .andExpect(jsonPath("$.errors[0].params.invalidUnits").isArray());
        assertThat(count("warehouse.stock_movements")).isZero();

        // oprava jednotky na platnou → confirm projde a karta se uloží kanonicky
        String detailJson = mockMvc.perform(get(RECEIPTS_URL + "/" + id).with(user(admin())))
                .andReturn().getResponse().getContentAsString();
        var draftNode = (tools.jackson.databind.node.ObjectNode)
                objectMapper.readTree(detailJson).get("draft");
        ((tools.jackson.databind.node.ObjectNode) draftNode.get("lines").get(0))
                .set("unit", tracked("ks"));
        mockMvc.perform(put(RECEIPTS_URL + "/" + id + "/draft")
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(draftNode))
                        .with(user(admin())))
                .andExpect(status().isOk());

        mockMvc.perform(post(RECEIPTS_URL + "/" + id + "/confirm").with(user(admin())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));
        String unit = jdbc.queryForObject(
                "SELECT unit FROM warehouse.products WHERE sku = 'SKU-TEST-1'", String.class);
        assertThat(unit).isEqualTo("ks");
    }

    @Test
    @DisplayName("řádek napárovaný na existující kartu bez katalogového čísla → confirm projde (Fix 3)")
    void confirmMatchedLineWithoutCatalogNumber() throws Exception {
        // 1) první doklad založí kartu SKU-TEST-1
        long first = importReceipt(consistentInvoice("FAK-CAT-1"));
        mockMvc.perform(post(RECEIPTS_URL + "/" + first + "/confirm").with(user(admin())))
                .andExpect(status().isOk());
        Long productId = jdbc.queryForObject(
                "SELECT id FROM warehouse.products WHERE sku = 'SKU-TEST-1'", Long.class);
        assertThat(count("warehouse.products")).isEqualTo(1);

        // 2) druhý doklad — ručně psaný díl bez katalogového čísla, kontrolor ho napáruje
        //    na existující kartu (CONFIRMED s productId). SKU tedy není potřeba.
        long second = importReceipt(consistentInvoice("FAK-CAT-2"));
        String detailJson = mockMvc.perform(get(RECEIPTS_URL + "/" + second).with(user(admin())))
                .andReturn().getResponse().getContentAsString();
        var draftNode = (tools.jackson.databind.node.ObjectNode)
                objectMapper.readTree(detailJson).get("draft");
        var lineNode = (tools.jackson.databind.node.ObjectNode) draftNode.get("lines").get(0);
        lineNode.set("catalogNumber", trackedAbsent());            // SKU chybí
        var matchNode = objectMapper.createObjectNode();
        matchNode.put("state", "CONFIRMED").put("productId", productId);  // napárováno na kartu
        lineNode.set("productMatch", matchNode);

        mockMvc.perform(put(RECEIPTS_URL + "/" + second + "/draft")
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(draftNode))
                        .with(user(admin())))
                .andExpect(status().isOk());

        // confirm projde — SKU se u napárovaného řádku nevyžaduje
        mockMvc.perform(post(RECEIPTS_URL + "/" + second + "/confirm").with(user(admin())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));

        // žádná nová karta, kusy se sečetly na existující (2 + 2)
        assertThat(count("warehouse.products")).isEqualTo(1);
        BigDecimal onHand = jdbc.queryForObject(
                "SELECT quantity_on_hand FROM warehouse.products WHERE sku = 'SKU-TEST-1'", BigDecimal.class);
        assertThat(onHand).isEqualByComparingTo("4");
    }

    @Test
    @DisplayName("nový produkt bez katalogového čísla → 422 RECEIPT_INCOMPLETE / invalidLines (Fix 3)")
    void confirmNewProductWithoutCatalogNumberBlocked() throws Exception {
        long id = importReceipt(consistentInvoice("FAK-CAT-3"));
        String detailJson = mockMvc.perform(get(RECEIPTS_URL + "/" + id).with(user(admin())))
                .andReturn().getResponse().getContentAsString();
        var draftNode = (tools.jackson.databind.node.ObjectNode)
                objectMapper.readTree(detailJson).get("draft");
        var lineNode = (tools.jackson.databind.node.ObjectNode) draftNode.get("lines").get(0);
        lineNode.set("catalogNumber", trackedAbsent());            // SKU chybí
        var matchNode = objectMapper.createObjectNode();
        matchNode.put("state", "CONFIRMED").putNull("productId");  // volba „nový produkt"
        lineNode.set("productMatch", matchNode);

        mockMvc.perform(put(RECEIPTS_URL + "/" + id + "/draft")
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(draftNode))
                        .with(user(admin())))
                .andExpect(status().isOk());

        // nový produkt musí mít identitu → SKU je povinné → blokace
        mockMvc.perform(post(RECEIPTS_URL + "/" + id + "/confirm").with(user(admin())))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].code").value("RECEIPT_INCOMPLETE"))
                .andExpect(jsonPath("$.errors[0].params.invalidLines").isArray());
        assertThat(count("warehouse.stock_movements")).isZero();
    }

    // ------------------------------------------------------------------ storno (E4.2, R-C)

    /** Vydá zadané množství z první šarže příjemky na zakázku (import položek do zakázky). */
    private void issueFromReceiptToOrder(long receiptId, String quantity) throws Exception {
        Long batchId = jdbc.queryForObject(
                "SELECT id FROM warehouse.goods_receipt_items WHERE goods_receipt_id = ? ORDER BY id LIMIT 1",
                Long.class, receiptId);
        // zakázka bez faktury — vyfakturovaná by mutace položek odmítla (oprava V2)
        Long orderId = jdbc.queryForObject(
                "SELECT o.id FROM \"order\".orders o"
                        + " WHERE NOT EXISTS (SELECT 1 FROM billing.invoices i WHERE i.order_id = o.id)"
                        + " ORDER BY o.id LIMIT 1", Long.class);
        mockMvc.perform(post("/api/v1/orders/" + orderId + "/items/import-from-receipt")
                        .contentType(APPLICATION_JSON)
                        .content("[{\"goodsReceiptItemId\":" + batchId + ",\"quantity\":" + quantity + "}]")
                        .with(user(admin())))
                .andExpect(status().is2xxSuccessful());
    }

    @Test
    @DisplayName("storno nečerpané příjemky: kompenzační pohyby vynulují sklad a číslo se uvolní")
    void cancelUnusedReceipt() throws Exception {
        long id = importReceipt(consistentInvoice("FAK-CANCEL-1"));
        mockMvc.perform(post(RECEIPTS_URL + "/" + id + "/confirm").with(user(admin())))
                .andExpect(status().isOk());
        assertThat(count("warehouse.stock_movements")).isEqualTo(1);

        mockMvc.perform(post(RECEIPTS_URL + "/" + id + "/cancel")
                        .contentType(APPLICATION_JSON)
                        .content("{\"note\":\"omylem potvrzeno\"}")
                        .with(user(admin())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.cancellationNote").value("omylem potvrzeno"));

        // ledger je append-only: původní RECEIPT zůstal, přibyl kompenzační pohyb
        assertThat(count("warehouse.stock_movements")).isEqualTo(2);
        BigDecimal onHand = jdbc.queryForObject(
                "SELECT quantity_on_hand FROM warehouse.products WHERE sku = 'SKU-TEST-1'", BigDecimal.class);
        assertThat(onHand).isEqualByComparingTo("0");
        BigDecimal remaining = jdbc.queryForObject(
                "SELECT quantity_remaining FROM warehouse.goods_receipt_items WHERE goods_receipt_id = ?",
                BigDecimal.class, id);
        assertThat(remaining).isEqualByComparingTo("0");

        // číslo dokladu je zase volné (partial unique index V43)
        importReceipt(consistentInvoice("FAK-CANCEL-1"));
    }

    @Test
    @DisplayName("storno čerpané příjemky → 422 RECEIPT_ALREADY_USED a nic se nezmění")
    void cancelUsedReceiptFails() throws Exception {
        long id = importReceipt(consistentInvoice("FAK-CANCEL-2"));
        mockMvc.perform(post(RECEIPTS_URL + "/" + id + "/confirm").with(user(admin())))
                .andExpect(status().isOk());
        issueFromReceiptToOrder(id, "1");

        long movementsBefore = count("warehouse.stock_movements");
        mockMvc.perform(post(RECEIPTS_URL + "/" + id + "/cancel")
                        .contentType(APPLICATION_JSON)
                        .content("{\"note\":\"pokus o storno\"}")
                        .with(user(admin())))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].code").value("RECEIPT_ALREADY_USED"));

        assertThat(count("warehouse.stock_movements")).isEqualTo(movementsBefore);
        String status = jdbc.queryForObject(
                "SELECT status FROM warehouse.goods_receipts WHERE id = ?", String.class, id);
        assertThat(status).isEqualTo("CONFIRMED");
    }

    @Test
    @DisplayName("storno příjemky ke kontrole → 422 RECEIPT_NOT_CANCELLABLE; dvojí storno → 422")
    void cancelWrongStateFails() throws Exception {
        long draft = importReceipt(consistentInvoice("FAK-CANCEL-3"));

        // PENDING_REVIEW se zamítá, nestornuje
        mockMvc.perform(post(RECEIPTS_URL + "/" + draft + "/cancel")
                        .contentType(APPLICATION_JSON)
                        .content("{\"note\":\"nelze\"}")
                        .with(user(admin())))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].code").value("RECEIPT_NOT_CANCELLABLE"));

        mockMvc.perform(post(RECEIPTS_URL + "/" + draft + "/confirm").with(user(admin())))
                .andExpect(status().isOk());
        mockMvc.perform(post(RECEIPTS_URL + "/" + draft + "/cancel")
                        .contentType(APPLICATION_JSON).content("{\"note\":\"první\"}")
                        .with(user(admin())))
                .andExpect(status().isOk());

        // podruhé už není co stornovat
        mockMvc.perform(post(RECEIPTS_URL + "/" + draft + "/cancel")
                        .contentType(APPLICATION_JSON).content("{\"note\":\"druhé\"}")
                        .with(user(admin())))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].code").value("RECEIPT_NOT_CANCELLABLE"));
    }

    @Test
    @DisplayName("storno bez důvodu → 400 (poznámka je povinná)")
    void cancelWithoutNoteFails() throws Exception {
        long id = importReceipt(consistentInvoice("FAK-CANCEL-4"));
        mockMvc.perform(post(RECEIPTS_URL + "/" + id + "/confirm").with(user(admin())))
                .andExpect(status().isOk());

        mockMvc.perform(post(RECEIPTS_URL + "/" + id + "/cancel")
                        .contentType(APPLICATION_JSON).content("{\"note\":\"  \"}")
                        .with(user(admin())))
                .andExpect(status().isBadRequest());
    }

    private tools.jackson.databind.node.ObjectNode tracked(String value) {
        var node = objectMapper.createObjectNode();
        node.put("value", value).put("state", "EDITED");
        return node;
    }

    private tools.jackson.databind.node.ObjectNode trackedNumber(String value) {
        var node = objectMapper.createObjectNode();
        node.put("value", new BigDecimal(value)).put("state", "EDITED");
        return node;
    }

    private tools.jackson.databind.node.ObjectNode trackedAbsent() {
        var node = objectMapper.createObjectNode();
        node.putNull("value").put("state", "EDITED");
        return node;
    }

    @Test
    @DisplayName("seznam a detail: draft je v detailu, seznam filtruje podle stavu")
    void listAndDetail() throws Exception {
        long id = importReceipt(consistentInvoice("FAK-104"));

        mockMvc.perform(get(RECEIPTS_URL).param("status", "PENDING_REVIEW")
                        .with(user(admin())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].documentNumber").value("FAK-104"));

        mockMvc.perform(get(RECEIPTS_URL + "/" + id).with(user(admin())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.draft.header.documentNumber.value").value("FAK-104"))
                .andExpect(jsonPath("$.draft.lines[0].vatRate.value").value(21))
                .andExpect(jsonPath("$.hasPdf").value(true));

        mockMvc.perform(get(RECEIPTS_URL + "/" + id + "/pdf").with(user(admin())))
                .andExpect(status().isOk())
                .andExpect(result -> assertThat(result.getResponse().getContentType())
                        .isEqualTo("application/pdf"));
    }
}
