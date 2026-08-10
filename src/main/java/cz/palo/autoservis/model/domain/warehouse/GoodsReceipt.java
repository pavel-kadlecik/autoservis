package cz.palo.autoservis.model.domain.warehouse;

import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/** Příjemka = hlavička dodavatelské faktury (warehouse.goods_receipts). */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GoodsReceipt {
    private Long id;
    private Long supplierId;
    private String supplierNameSnapshot;
    private String invoiceNumber;
    private String orderNumber;
    private String originalOrderNumber;
    private LocalDate issueDate;
    private LocalDate dueDate;
    private LocalDate taxableSupplyDate;
    private BigDecimal subtotal;
    private BigDecimal vatAmount;
    private BigDecimal totalAmount;
    private String currency;
    private DocumentType documentType;
    private ReceiptSource sourceChannel;
    private ReceiptStatus status;
    private Boolean reconciliationOk;
    private String extractionModel;
    private String sourceFilename;
    private byte[] sourcePdf;
    /** Kanonický draft jako JSON text (v DB JSONB); autoritativní po dobu PENDING_REVIEW. */
    private String draftPayload;
    private OffsetDateTime confirmedAt;
    private Long confirmedBy;
    private OffsetDateTime rejectedAt;
    private Long rejectedBy;
    private String rejectionNote;
    private OffsetDateTime cancelledAt;
    private Long cancelledBy;
    private String cancellationNote;
    private Long createdBy;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
