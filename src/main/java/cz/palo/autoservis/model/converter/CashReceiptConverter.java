package cz.palo.autoservis.model.converter;

import cz.palo.autoservis.model.domain.billing.CashReceipt;
import cz.palo.autoservis.model.domain.billing.Invoice;
import cz.palo.autoservis.model.domain.billing.InvoiceParty;
import cz.palo.autoservis.model.domain.billing.InvoiceSummary;
import cz.palo.autoservis.model.domain.billing.InvoiceVatSummary;
import cz.palo.autoservis.model.dto.billing.CashReceiptDto;
import cz.palo.autoservis.model.dto.billing.InvoiceDto;
import cz.palo.autoservis.model.enums.InvoicePartyRole;
import cz.palo.autoservis.util.AmountInWords;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Ruční konvertor pro příjmový pokladní doklad. Účastníci a rozpis DPH se na dokladu neukládají —
 * odvozují se z faktury: příjemce = dodavatel, plátce = odběratel (snapshoty invoice_party), rozpis DPH
 * z {@code v_invoice_vat_summary}. Částka slovy se skládá z uložené {@code amount} přes {@link AmountInWords}.
 */
@Component
@RequiredArgsConstructor
public class CashReceiptConverter {

    private final InvoiceConverter invoiceConverter;

    /**
     * Sestaví detail: skalární pole dokladu + účastníci, rozpis DPH a součty odvozené z faktury.
     *
     * @param receipt    pokladní doklad
     * @param invoice    hrazená faktura (její číslo a VS jde do dokladu)
     * @param summary    souhrny faktury (základ/DPH/celkem)
     * @param vatSummary rozpad DPH faktury po sazbách
     * @param parties    snapshoty stran faktury
     */
    public CashReceiptDto.DetailResponse toDetailResponse(
            CashReceipt receipt, Invoice invoice, InvoiceSummary summary,
            List<InvoiceVatSummary> vatSummary, List<InvoiceParty> parties) {

        CashReceiptDto.DetailResponse response = new CashReceiptDto.DetailResponse();
        response.setId(receipt.getId());
        response.setReceiptNumber(receipt.getReceiptNumber());
        response.setIssueDate(receipt.getIssueDate());
        response.setInvoiceId(receipt.getInvoiceId());
        response.setPurpose(receipt.getPurpose());
        response.setAmount(receipt.getAmount());
        response.setAmountInWords(receipt.getAmount() == null ? null : AmountInWords.toWords(receipt.getAmount()));
        response.setStatus(receipt.getStatus());
        response.setCancelledAt(receipt.getCancelledAt());
        response.setCancellationReason(receipt.getCancellationReason());
        response.setCreatedAt(receipt.getCreatedAt());
        response.setCreatedBy(receipt.getCreatedBy());

        if (invoice != null) {
            response.setInvoiceNumber(invoice.getInvoiceNumber());
            response.setVariableSymbol(invoice.getVariableSymbol());
        }

        if (summary != null) {
            response.setTotalNet(summary.getTotalNet());
            response.setTotalVat(summary.getTotalVat());
            response.setTotalGross(summary.getTotalGross());
            if (receipt.getAmount() != null) {
                response.setRounding(receipt.getAmount().subtract(summary.getTotalGross()));
            }
        }

        if (vatSummary != null) {
            response.setVatSummary(vatSummary.stream().map(v -> {
                InvoiceDto.VatSummaryLine line = new InvoiceDto.VatSummaryLine();
                line.setRate(v.getVatRate());
                line.setBase(v.getBase());
                line.setVat(v.getVat());
                line.setTotal(v.getTotal());
                return line;
            }).toList());
        }

        if (parties != null) {
            for (InvoiceParty party : parties) {
                if (party.getRole() == InvoicePartyRole.SUPPLIER) {
                    response.setSupplier(invoiceConverter.toPartyResponse(party));
                } else if (party.getRole() == InvoicePartyRole.CUSTOMER) {
                    response.setCustomer(invoiceConverter.toPartyResponse(party));
                }
            }
        }

        return response;
    }
}
