package cz.palo.autoservis.model.converter;

import cz.palo.autoservis.model.domain.billing.Invoice;
import cz.palo.autoservis.model.domain.billing.InvoiceListRow;
import cz.palo.autoservis.model.domain.billing.InvoiceParty;
import cz.palo.autoservis.model.domain.billing.InvoiceSummary;
import cz.palo.autoservis.model.domain.billing.InvoiceVatSummary;
import cz.palo.autoservis.model.domain.order.Order;
import cz.palo.autoservis.model.dto.billing.InvoiceDto;
import cz.palo.autoservis.model.dto.billing.InvoiceItemDto;
import cz.palo.autoservis.model.enums.InvoicePartyRole;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Konvertor mezi doménovými objekty {@link Invoice} a DTO {@link InvoiceDto}.
 */
@Component
public class InvoiceConverter {

    /**
     * Převede seznam read modelů {@link InvoiceListRow} na seznamová response DTO.
     *
     * @param rows řádky read modelu vrácené {@code InvoiceMapper.search}
     * @return seznam seznamových response DTO
     */
    public List<InvoiceDto.ListResponse> toListResponses(List<InvoiceListRow> rows) {
        return rows.stream().map(this::toListResponse).toList();
    }

    /**
     * Převede {@link Invoice} na {@link InvoiceDto.DetailResponse} s položkami a součty.
     *
     * @param invoice doménový objekt k převodu
     * @param items   předem načtený seznam response položek faktury
     * @param summary spočítané součty; nikdy {@code null} (použij {@link InvoiceSummary#zero(Long)})
     * @return detailové response DTO, nebo {@code null} při {@code null} faktuře
     */
    public InvoiceDto.DetailResponse toDetailResponse(Invoice invoice,
                                                      List<InvoiceItemDto.Response> items,
                                                      InvoiceSummary summary) {
        return toDetailResponse(invoice, items, summary, null, null, null);
    }

    /**
     * Převede {@link Invoice} na {@link InvoiceDto.DetailResponse} s položkami,
     * součty a zmrazenými stranami (dodavatel / odběratel).
     *
     * @param invoice doménový objekt k převodu
     * @param items   předem načtený seznam response položek faktury
     * @param summary spočítané součty; nikdy {@code null} (použij {@link InvoiceSummary#zero(Long)})
     * @param parties zmrazené strany faktury; může být {@code null} či obsahovat jen jednu roli
     * @return detailové response DTO, nebo {@code null} při {@code null} faktuře
     */
    public InvoiceDto.DetailResponse toDetailResponse(Invoice invoice,
                                                      List<InvoiceItemDto.Response> items,
                                                      InvoiceSummary summary,
                                                      List<InvoiceParty> parties,
                                                      Order order,
                                                      List<InvoiceVatSummary> vatSummary) {
        if (invoice == null) {
            return null;
        }

        InvoiceDto.DetailResponse response = buildDetailResponse(invoice);
        response.setItems(items);
        response.setVatSummary(toVatSummaryLines(vatSummary));
        response.setTotalNet(summary.getTotalNet());
        response.setTotalVat(summary.getTotalVat());
        response.setTotalGross(summary.getTotalGross());
        // V67/KN-7: zaokrouhlení hotovosti je mimo základ daně, proto stojí vedle součtů
        response.setRounding(summary.getRounding());
        response.setTotalToPay(summary.getTotalToPay());

        // Vozidlo: celé ze zmraženého snapshotu (K-5) — SPZ, VIN, značka i model se v čase mění,
        // ale právní doklad musí nést hodnoty k datu vystavení, ne živý stav vozidla.
        response.setVehicleLicensePlate(invoice.getVehicleLicensePlateSnapshot());
        response.setVehicleVin(invoice.getVehicleVinSnapshot());
        response.setVehicleBrand(invoice.getVehicleBrandSnapshot());
        response.setVehicleModel(invoice.getVehicleModelSnapshot());

        // Evidence úhrady (E2.1) — vyplněné jen u zaplacené faktury.
        response.setPaidAt(invoice.getPaidAt());
        response.setPaidAmount(invoice.getPaidAmount());
        response.setPaidMethod(invoice.getPaidMethod());
        response.setCreditedAt(invoice.getCreditedAt());
        response.setHandedOverAt(invoice.getHandedOverAt());

        if (parties != null) {
            for (InvoiceParty party : parties) {
                if (party.getRole() == InvoicePartyRole.SUPPLIER) {
                    response.setSupplier(toPartyResponse(party));
                } else if (party.getRole() == InvoicePartyRole.CUSTOMER) {
                    response.setCustomer(toPartyResponse(party));
                }
            }
        }

        return response;
    }

    /**
     * Převede {@link Invoice} na {@link InvoiceDto.DetailResponse} bez položek a součtů.
     * Když jsou potřeba, použij {@link #toDetailResponse(Invoice, List, InvoiceSummary)}.
     *
     * @param invoice doménový objekt k převodu
     * @return detailové response DTO bez položek, nebo {@code null} při {@code null} vstupu
     */
    public InvoiceDto.DetailResponse toDetailResponse(Invoice invoice) {
        if (invoice == null) {
            return null;
        }
        return buildDetailResponse(invoice);
    }

    /**
     * Převede {@link InvoiceDto.CreateRequest} na doménový objekt {@link Invoice}.
     * Číslo faktury ani variabilní symbol se tu nedosazují — koncept je nemá, obojí
     * vzniká až při vystavení; {@code status} řídí výhradně server.
     *
     * @param createRequest zvalidované create request DTO
     * @return doménový objekt připravený k INSERTu, nebo {@code null} při {@code null} vstupu
     */
    public Invoice toDomain(InvoiceDto.CreateRequest createRequest) {
        if (createRequest == null) {
            return null;
        }

        Invoice invoice = new Invoice();

        invoice.setOrderId(createRequest.getOrderId());
        invoice.setIssueDate(createRequest.getIssueDate());
        invoice.setDueDate(createRequest.getDueDate());
        invoice.setTaxableSupplyDate(createRequest.getTaxableSupplyDate());
        invoice.setConstantSymbol(createRequest.getConstantSymbol());
        invoice.setSpecificSymbol(createRequest.getSpecificSymbol());
        invoice.setPaymentMethod(createRequest.getPaymentMethod());
        invoice.setNote(createRequest.getNote());
        invoice.setPurchaseOrderNumber(blankToNull(createRequest.getPurchaseOrderNumber()));

        return invoice;
    }

    /**
     * Aplikuje pole z {@link InvoiceDto.UpdateRequest} na existující {@link Invoice}.
     * Existující objekt se mění na místě a vrací.
     * Neměnných polí ({@code invoiceNumber}, {@code orderId}, {@code customerId}) se nedotýká.
     *
     * @param existingInvoice faktura načtená z databáze
     * @param updateRequest   zvalidované update request DTO
     * @return upravený doménový objekt, nebo {@code null}, je-li kterýkoli argument {@code null}
     */
    public Invoice applyUpdate(Invoice existingInvoice, InvoiceDto.UpdateRequest updateRequest) {
        if (updateRequest == null || existingInvoice == null) {
            return null;
        }

        existingInvoice.setDueDate(updateRequest.getDueDate());
        existingInvoice.setConstantSymbol(updateRequest.getConstantSymbol());
        existingInvoice.setSpecificSymbol(updateRequest.getSpecificSymbol());
        existingInvoice.setPaymentMethod(updateRequest.getPaymentMethod());
        existingInvoice.setStatus(updateRequest.getStatus());
        existingInvoice.setNote(updateRequest.getNote());
        existingInvoice.setPurchaseOrderNumber(blankToNull(updateRequest.getPurchaseOrderNumber()));

        return existingInvoice;
    }

    /**
     * Nevyplněná volitelná textová pole normalizuje na {@code null} — frontend je
     * posílá jako prázdné řetězce, ale DB stojí na NULL sémantice (V80/V81).
     */
    private String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value;
    }

    private InvoiceDto.ListResponse toListResponse(InvoiceListRow row) {
        if (row == null) {
            return null;
        }

        InvoiceDto.ListResponse response = new InvoiceDto.ListResponse();

        response.setId(row.getId());
        response.setInvoiceNumber(row.getInvoiceNumber());
        response.setIssueDate(row.getIssueDate());
        response.setDueDate(row.getDueDate());
        response.setStatus(row.getStatus());
        response.setPaymentMethod(row.getPaymentMethod());
        response.setVariableSymbol(row.getVariableSymbol());
        response.setOrderId(row.getOrderId());
        response.setOrderNumber(row.getOrderNumber());
        response.setCustomerId(row.getCustomerId());
        response.setCustomerDisplayName(row.getCustomerDisplayName());
        response.setTotalNet(row.getTotalNet());
        response.setTotalVat(row.getTotalVat());
        response.setTotalGross(row.getTotalGross());
        response.setTotalToPay(row.getTotalToPay());
        response.setCreditedAt(row.getCreditedAt());
        response.setHandedOverAt(row.getHandedOverAt());
        response.setHasDraftCreditNote(row.isHasDraftCreditNote());
        response.setOrderDescription(row.getOrderDescription());

        return response;
    }

    private InvoiceDto.DetailResponse buildDetailResponse(Invoice invoice) {
        InvoiceDto.DetailResponse response = new InvoiceDto.DetailResponse();

        response.setId(invoice.getId());
        response.setInvoiceNumber(invoice.getInvoiceNumber());
        response.setOrderId(invoice.getOrderId());
        response.setCustomerId(invoice.getCustomerId());
        response.setIssueDate(invoice.getIssueDate());
        response.setDueDate(invoice.getDueDate());
        response.setTaxableSupplyDate(invoice.getTaxableSupplyDate());
        response.setVariableSymbol(invoice.getVariableSymbol());
        response.setConstantSymbol(invoice.getConstantSymbol());
        response.setSpecificSymbol(invoice.getSpecificSymbol());
        response.setPaymentMethod(invoice.getPaymentMethod());
        response.setStatus(invoice.getStatus());
        response.setNote(invoice.getNote());
        response.setPurchaseOrderNumber(invoice.getPurchaseOrderNumber());
        response.setCreatedAt(invoice.getCreatedAt());
        response.setUpdatedAt(invoice.getUpdatedAt());
        response.setCreatedBy(invoice.getCreatedBy());
        response.setCustomerNameSnapshot(invoice.getCustomerNameSnapshot());
        response.setOrderNumberSnapshot(invoice.getOrderNumberSnapshot());

        return response;
    }

    /** Mapuje řádky view rekapitulace DPH (po sazbách) na response řádky. */
    private List<InvoiceDto.VatSummaryLine> toVatSummaryLines(List<InvoiceVatSummary> vatSummary) {
        if (vatSummary == null) {
            return List.of();
        }
        return vatSummary.stream().map(s -> {
            InvoiceDto.VatSummaryLine line = new InvoiceDto.VatSummaryLine();
            line.setRate(s.getVatRate());
            line.setBase(s.getBase());
            line.setVat(s.getVat());
            line.setTotal(s.getTotal());
            return line;
        }).toList();
    }

    /** Public: znovupoužívá {@code CreditNoteConverter} (dobropis ukazuje strany původní faktury). */
    public InvoiceDto.PartyResponse toPartyResponse(InvoiceParty party) {
        if (party == null) {
            return null;
        }

        InvoiceDto.PartyResponse response = new InvoiceDto.PartyResponse();
        response.setName(party.getName());
        response.setIco(party.getIco());
        response.setDic(party.getDic());
        response.setStreet(party.getStreet());
        response.setStreetNumber(party.getStreetNumber());
        response.setCity(party.getCity());
        response.setPostalCode(party.getPostalCode());
        response.setCountryCode(party.getCountryCode());
        response.setBankAccount(party.getBankAccount());
        response.setIban(party.getIban());
        response.setSwift(party.getSwift());
        return response;
    }
}
