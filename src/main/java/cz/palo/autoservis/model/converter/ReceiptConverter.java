package cz.palo.autoservis.model.converter;

import cz.palo.autoservis.model.domain.warehouse.GoodsReceipt;
import cz.palo.autoservis.model.draft.ReceiptDraft;
import cz.palo.autoservis.model.dto.warehouse.ReceiptDto;
import org.springframework.stereotype.Component;

/** Ruční konverze GoodsReceipt (hlavička příjemky) ↔ ReceiptDto. */
@Component
public class ReceiptConverter {

    public ReceiptDto.ListResponse toListResponse(GoodsReceipt r) {
        return ReceiptDto.ListResponse.builder()
                .id(r.getId())
                .documentType(name(r.getDocumentType()))
                .documentNumber(r.getInvoiceNumber())
                .supplierName(r.getSupplierNameSnapshot())
                .issueDate(r.getIssueDate())
                .totalAmount(r.getTotalAmount())
                .currency(r.getCurrency())
                .status(name(r.getStatus()))
                .reconciliationOk(Boolean.TRUE.equals(r.getReconciliationOk()))
                .sourceChannel(name(r.getSourceChannel()))
                .createdAt(r.getCreatedAt())
                .build();
    }

    /** @param draft deserializovaný draft_payload (může být null u starých záznamů) */
    public ReceiptDto.DetailResponse toDetailResponse(GoodsReceipt r, ReceiptDraft draft,
                                                      boolean hasPdf) {
        return ReceiptDto.DetailResponse.builder()
                .id(r.getId())
                .documentType(name(r.getDocumentType()))
                .sourceChannel(name(r.getSourceChannel()))
                .status(name(r.getStatus()))
                .documentNumber(r.getInvoiceNumber())
                .supplierId(r.getSupplierId())
                .supplierName(r.getSupplierNameSnapshot())
                .issueDate(r.getIssueDate())
                .subtotal(r.getSubtotal())
                .vatAmount(r.getVatAmount())
                .totalAmount(r.getTotalAmount())
                .currency(r.getCurrency())
                .reconciliationOk(Boolean.TRUE.equals(r.getReconciliationOk()))
                .extractionModel(r.getExtractionModel())
                .sourceFilename(r.getSourceFilename())
                .hasPdf(hasPdf)
                .confirmedAt(r.getConfirmedAt())
                .rejectedAt(r.getRejectedAt())
                .rejectionNote(r.getRejectionNote())
                .cancelledAt(r.getCancelledAt())
                .cancellationNote(r.getCancellationNote())
                .createdAt(r.getCreatedAt())
                .draft(draft)
                .build();
    }

    private String name(Enum<?> e) {
        return e == null ? null : e.name();
    }
}
