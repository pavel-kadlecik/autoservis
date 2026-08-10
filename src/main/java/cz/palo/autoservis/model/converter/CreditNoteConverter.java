package cz.palo.autoservis.model.converter;

import cz.palo.autoservis.model.domain.billing.CreditNote;
import cz.palo.autoservis.model.domain.billing.Invoice;
import cz.palo.autoservis.model.domain.billing.InvoiceParty;
import cz.palo.autoservis.model.domain.billing.InvoiceSummary;
import cz.palo.autoservis.model.domain.billing.InvoiceVatSummary;
import cz.palo.autoservis.model.dto.billing.CreditNoteDto;
import cz.palo.autoservis.model.dto.billing.InvoiceDto;
import cz.palo.autoservis.model.enums.InvoicePartyRole;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/**
 * Ruční konvertor pro opravný daňový doklad (dobropis). Rozdílové částky a strany se neukládají —
 * odvozují se z původní faktury (§45): rozdíly = záporné souhrny původní faktury (MVP = plný dobropis),
 * strany = snapshoty původní faktury.
 */
@Component
@RequiredArgsConstructor
public class CreditNoteConverter {

    private final InvoiceConverter invoiceConverter;

    public CreditNote toDomain(CreditNoteDto.CreateRequest request) {
        if (request == null) {
            return null;
        }
        return CreditNote.builder()
                .originalInvoiceId(request.getOriginalInvoiceId())
                .correctionReason(request.getCorrectionReason())
                .taxableSupplyDate(request.getTaxableSupplyDate())
                .build();
    }

    /**
     * Sestaví detail: skalární pole dobropisu + §45 náležitosti odvozené z původní faktury.
     *
     * @param creditNote      dobropis
     * @param originalInvoice původní faktura (její číslo = §45 evidenční číslo původního dokladu)
     * @param originalSummary souhrny původní faktury (základ/DPH/celkem)
     * @param originalVat     rozpad DPH původní faktury po sazbách
     * @param originalParties snapshoty stran původní faktury
     */
    public CreditNoteDto.DetailResponse toDetailResponse(
            CreditNote creditNote, Invoice originalInvoice, InvoiceSummary originalSummary,
            List<InvoiceVatSummary> originalVat, List<InvoiceParty> originalParties) {

        CreditNoteDto.DetailResponse response = new CreditNoteDto.DetailResponse();
        response.setId(creditNote.getId());
        response.setCreditNoteNumber(creditNote.getCreditNoteNumber());
        response.setStatus(creditNote.getStatus());
        response.setOriginalInvoiceId(creditNote.getOriginalInvoiceId());
        response.setCorrectionReason(creditNote.getCorrectionReason());
        response.setIssueDate(creditNote.getIssueDate());
        response.setTaxableSupplyDate(creditNote.getTaxableSupplyDate());
        response.setCreatedAt(creditNote.getCreatedAt());
        response.setUpdatedAt(creditNote.getUpdatedAt());
        response.setCreatedBy(creditNote.getCreatedBy());

        if (originalInvoice != null) {
            response.setOriginalInvoiceNumber(originalInvoice.getInvoiceNumber());
        }

        // §45 rozdíly — plný dobropis = záporné souhrny původní faktury.
        if (originalSummary != null) {
            response.setTotalNetDifference(negate(originalSummary.getTotalNet()));
            response.setTotalVatDifference(negate(originalSummary.getTotalVat()));
            response.setTotalGrossDifference(negate(originalSummary.getTotalGross()));
        }

        if (originalVat != null) {
            response.setVatDifferences(originalVat.stream().map(v -> {
                InvoiceDto.VatSummaryLine line = new InvoiceDto.VatSummaryLine();
                line.setRate(v.getVatRate());
                line.setBase(negate(v.getBase()));
                line.setVat(negate(v.getVat()));
                line.setTotal(negate(v.getTotal()));
                return line;
            }).toList());
        }

        if (originalParties != null) {
            for (InvoiceParty party : originalParties) {
                if (party.getRole() == InvoicePartyRole.SUPPLIER) {
                    response.setSupplier(invoiceConverter.toPartyResponse(party));
                } else if (party.getRole() == InvoicePartyRole.CUSTOMER) {
                    response.setCustomer(invoiceConverter.toPartyResponse(party));
                }
            }
        }

        return response;
    }

    private static BigDecimal negate(BigDecimal value) {
        return value == null ? null : value.negate();
    }
}
