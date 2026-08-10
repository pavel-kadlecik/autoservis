package cz.palo.autoservis.model.converter;

import cz.palo.autoservis.model.domain.warehouse.DocumentType;
import cz.palo.autoservis.model.domain.warehouse.GoodsReceipt;
import cz.palo.autoservis.model.domain.warehouse.GoodsReceiptItem;
import cz.palo.autoservis.model.domain.warehouse.ReceiptSource;
import cz.palo.autoservis.model.domain.warehouse.ReceiptStatus;
import cz.palo.autoservis.model.draft.ReceiptDraft;
import cz.palo.autoservis.model.dto.warehouse.GoodsReceiptItemDto;
import cz.palo.autoservis.model.dto.warehouse.ReceiptDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Konvertor příjemek (hlavička + položky) — čistý unit test bez Spring kontextu.
 *
 * <p>Dvě pasti, na které test cílí:
 * <ul>
 *   <li>{@code reconciliationOk} je v doméně {@code Boolean} (třístavové), ale v odpovědi
 *       {@code boolean}. Převod jde přes {@code Boolean.TRUE.equals(...)}, takže
 *       <strong>neznámý stav (null) musí vyjít jako false</strong>, ne spadnout na NPE —
 *       testuje se všemi třemi vstupy;</li>
 *   <li>enum hodnoty se do DTO posílají jako {@code String}; {@code null} enum musí dát
 *       {@code null}, ne řetězec „null".</li>
 * </ul>
 * Sloupec {@code invoice_number} nese i čísla dodacích listů (TD-40) — proto se mapuje na
 * {@code documentNumber}.
 */
class ReceiptConverterTest {

    private final ReceiptConverter converter = new ReceiptConverter();
    private final GoodsReceiptItemConverter itemConverter = new GoodsReceiptItemConverter();

    // =========================================================================
    // reconciliationOk — třístavový Boolean → boolean
    // =========================================================================

    @Test
    @DisplayName("reconciliationOk = TRUE se propíše jako true")
    void listResponse_reconciliationTrue_isTrue() {
        GoodsReceipt receipt = receipt();
        receipt.setReconciliationOk(Boolean.TRUE);

        assertThat(converter.toListResponse(receipt).isReconciliationOk()).isTrue();
    }

    @Test
    @DisplayName("reconciliationOk = FALSE se propíše jako false")
    void listResponse_reconciliationFalse_isFalse() {
        GoodsReceipt receipt = receipt();
        receipt.setReconciliationOk(Boolean.FALSE);

        assertThat(converter.toListResponse(receipt).isReconciliationOk()).isFalse();
    }

    @Test
    @DisplayName("reconciliationOk = null → false (neznámý stav není „v pořádku\", a nesmí spadnout)")
    void listResponse_reconciliationNull_isFalseNotException() {
        GoodsReceipt receipt = receipt();
        receipt.setReconciliationOk(null);

        assertThat(converter.toListResponse(receipt).isReconciliationOk()).isFalse();
    }

    @Test
    @DisplayName("reconciliationOk = null → false i v detailu")
    void detailResponse_reconciliationNull_isFalse() {
        GoodsReceipt receipt = receipt();
        receipt.setReconciliationOk(null);

        assertThat(converter.toDetailResponse(receipt, null, false).isReconciliationOk()).isFalse();
    }

    // =========================================================================
    // Enumy → String
    // =========================================================================

    @Test
    @DisplayName("enumy se posílají jako jejich názvy")
    void listResponse_enumsAreSerializedByName() {
        GoodsReceipt receipt = receipt();
        receipt.setDocumentType(DocumentType.DELIVERY_NOTE);
        receipt.setStatus(ReceiptStatus.CONFIRMED);
        receipt.setSourceChannel(ReceiptSource.ISDOC);

        ReceiptDto.ListResponse response = converter.toListResponse(receipt);

        assertThat(response.getDocumentType()).isEqualTo("DELIVERY_NOTE");
        assertThat(response.getStatus()).isEqualTo("CONFIRMED");
        assertThat(response.getSourceChannel()).isEqualTo("ISDOC");
    }

    @Test
    @DisplayName("chybějící enum → null, ne řetězec „null\"")
    void listResponse_nullEnums_yieldNullNotLiteralString() {
        GoodsReceipt receipt = receipt();
        receipt.setDocumentType(null);
        receipt.setStatus(null);
        receipt.setSourceChannel(null);

        ReceiptDto.ListResponse response = converter.toListResponse(receipt);

        assertThat(response.getDocumentType()).isNull();
        assertThat(response.getStatus()).isNull();
        assertThat(response.getSourceChannel()).isNull();
    }

    // =========================================================================
    // Mapování hlavičky
    // =========================================================================

    @Test
    @DisplayName("toListResponse namapuje hlavičku; číslo dokladu jde z invoice_number (TD-40)")
    void toListResponse_mapsHeaderFields() {
        GoodsReceipt receipt = receipt();
        receipt.setId(60L);
        receipt.setInvoiceNumber("DL-2026-042");
        receipt.setDocumentType(DocumentType.DELIVERY_NOTE);
        receipt.setSupplierNameSnapshot("Autodíly s.r.o.");
        receipt.setIssueDate(LocalDate.of(2026, 7, 1));
        receipt.setTotalAmount(new BigDecimal("1210.00"));
        receipt.setCurrency("CZK");
        receipt.setCreatedAt(OffsetDateTime.parse("2026-07-01T09:30:00Z"));

        ReceiptDto.ListResponse response = converter.toListResponse(receipt);

        assertThat(response.getId()).isEqualTo(60L);
        assertThat(response.getDocumentNumber()).isEqualTo("DL-2026-042");
        assertThat(response.getSupplierName()).isEqualTo("Autodíly s.r.o.");
        assertThat(response.getIssueDate()).isEqualTo(LocalDate.of(2026, 7, 1));
        assertThat(response.getTotalAmount()).isEqualByComparingTo("1210.00");
        assertThat(response.getCurrency()).isEqualTo("CZK");
        assertThat(response.getCreatedAt()).isEqualTo(OffsetDateTime.parse("2026-07-01T09:30:00Z"));
    }

    @Test
    @DisplayName("toDetailResponse namapuje hlavičku, stopy zpracování i draft a příznak PDF")
    void toDetailResponse_mapsHeaderProcessingTrailAndDraft() {
        GoodsReceipt receipt = receipt();
        receipt.setId(60L);
        receipt.setSupplierId(4L);
        receipt.setSubtotal(new BigDecimal("1000.00"));
        receipt.setVatAmount(new BigDecimal("210.00"));
        receipt.setTotalAmount(new BigDecimal("1210.00"));
        receipt.setExtractionModel("claude-sonnet-4-6");
        receipt.setSourceFilename("faktura.pdf");
        receipt.setConfirmedAt(OffsetDateTime.parse("2026-07-02T10:00:00Z"));
        receipt.setRejectedAt(OffsetDateTime.parse("2026-07-03T10:00:00Z"));
        receipt.setRejectionNote("špatný dodavatel");
        receipt.setCancelledAt(OffsetDateTime.parse("2026-07-04T10:00:00Z"));
        receipt.setCancellationNote("storno na přání");

        ReceiptDraft draft = new ReceiptDraft();

        ReceiptDto.DetailResponse response = converter.toDetailResponse(receipt, draft, true);

        assertThat(response.getId()).isEqualTo(60L);
        assertThat(response.getSupplierId()).isEqualTo(4L);
        assertThat(response.getSubtotal()).isEqualByComparingTo("1000.00");
        assertThat(response.getVatAmount()).isEqualByComparingTo("210.00");
        assertThat(response.getTotalAmount()).isEqualByComparingTo("1210.00");
        assertThat(response.getExtractionModel()).isEqualTo("claude-sonnet-4-6");
        assertThat(response.getSourceFilename()).isEqualTo("faktura.pdf");
        assertThat(response.isHasPdf()).isTrue();
        assertThat(response.getConfirmedAt()).isEqualTo(OffsetDateTime.parse("2026-07-02T10:00:00Z"));
        assertThat(response.getRejectedAt()).isEqualTo(OffsetDateTime.parse("2026-07-03T10:00:00Z"));
        assertThat(response.getRejectionNote()).isEqualTo("špatný dodavatel");
        assertThat(response.getCancelledAt()).isEqualTo(OffsetDateTime.parse("2026-07-04T10:00:00Z"));
        assertThat(response.getCancellationNote()).isEqualTo("storno na přání");
        assertThat(response.getDraft()).isSameAs(draft);
    }

    @Test
    @DisplayName("toDetailResponse: doklad bez PDF má hasPdf = false a draft smí být null (staré záznamy)")
    void toDetailResponse_withoutPdfAndDraft() {
        ReceiptDto.DetailResponse response = converter.toDetailResponse(receipt(), null, false);

        assertThat(response.isHasPdf()).isFalse();
        assertThat(response.getDraft()).isNull();
    }

    // =========================================================================
    // Položky příjemky
    // =========================================================================

    @Test
    @DisplayName("položka příjemky přenese přijaté i zbývající množství (podklad pro výdej)")
    void itemConverter_mapsAllFields() {
        GoodsReceiptItem item = GoodsReceiptItem.builder()
                .id(70L)
                .productId(8L)
                .nameSnapshot("Olejový filtr")
                .quantityReceived(new BigDecimal("10"))
                .quantityRemaining(new BigDecimal("4"))
                .unitPriceExclVat(new BigDecimal("120.00"))
                .vatRate(21)
                .build();

        GoodsReceiptItemDto.Response response = itemConverter.toDto(item);

        assertThat(response.getId()).isEqualTo(70L);
        assertThat(response.getProductId()).isEqualTo(8L);
        assertThat(response.getNameSnapshot()).isEqualTo("Olejový filtr");
        assertThat(response.getQuantityReceived()).isEqualByComparingTo("10");
        assertThat(response.getQuantityRemaining()).isEqualByComparingTo("4");
        assertThat(response.getUnitPriceExclVat()).isEqualByComparingTo("120.00");
        assertThat(response.getVatRate()).isEqualTo(21);
    }

    @Test
    @DisplayName("položka příjemky: vyčerpaná šarže má zbytek 0, ne null")
    void itemConverter_depletedBatch_hasZeroRemaining() {
        GoodsReceiptItem item = GoodsReceiptItem.builder()
                .id(70L)
                .quantityReceived(new BigDecimal("10"))
                .quantityRemaining(BigDecimal.ZERO)
                .build();

        assertThat(itemConverter.toDto(item).getQuantityRemaining()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("položka příjemky: null → null")
    void itemConverter_null_returnsNull() {
        assertThat(itemConverter.toDto(null)).isNull();
    }

    private static GoodsReceipt receipt() {
        return GoodsReceipt.builder()
                .id(60L)
                .invoiceNumber("FA-2026-001")
                .documentType(DocumentType.INVOICE)
                .sourceChannel(ReceiptSource.AI_PDF)
                .status(ReceiptStatus.PENDING_REVIEW)
                .supplierNameSnapshot("Autodíly s.r.o.")
                .issueDate(LocalDate.of(2026, 7, 1))
                .totalAmount(new BigDecimal("1210.00"))
                .currency("CZK")
                .reconciliationOk(Boolean.TRUE)
                .build();
    }
}
