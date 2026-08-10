package cz.palo.autoservis.model.converter;

import cz.palo.autoservis.model.domain.billing.InvoiceItem;
import cz.palo.autoservis.model.domain.order.OrderItem;
import cz.palo.autoservis.model.dto.billing.InvoiceItemDto;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Konvertor mezi doménovými objekty {@link InvoiceItem} a DTO {@link InvoiceItemDto}.
 */
@Component
public class InvoiceItemConverter {

    /**
     * Převede {@link InvoiceItem} na {@link InvoiceItemDto.Response}.
     *
     * @param invoiceItem doménový objekt k převodu
     * @return response DTO, nebo {@code null} při {@code null} vstupu
     */
    public InvoiceItemDto.Response toResponse(InvoiceItem invoiceItem) {
        if (invoiceItem == null) {
            return null;
        }

        InvoiceItemDto.Response response = new InvoiceItemDto.Response();
        response.setId(invoiceItem.getId());
        response.setInvoiceId(invoiceItem.getInvoiceId());
        response.setOrderItemId(invoiceItem.getOrderItemId());
        response.setName(invoiceItem.getName());
        response.setQuantity(invoiceItem.getQuantity());
        response.setUnit(invoiceItem.getUnit());
        response.setUnitPrice(invoiceItem.getUnitPrice());
        response.setVatRate(invoiceItem.getVatRate());
        response.setPosition(invoiceItem.getPosition());

        // Rozpad ceny řádku počítá SQL (findByInvoiceId), tady už jen kopírujeme.
        response.setNet(invoiceItem.getNet());
        response.setVat(invoiceItem.getVat());
        response.setGross(invoiceItem.getGross());
        return response;
    }

    /**
     * Převede seznam doménových objektů {@link InvoiceItem} na seznam {@link InvoiceItemDto.Response}.
     *
     * @param invoiceItems seznam doménových objektů
     * @return seznam response DTO
     */
    public List<InvoiceItemDto.Response> toListResponses(List<InvoiceItem> invoiceItems) {
        return invoiceItems.stream().map(this::toResponse).toList();
    }

    /**
     * Převede {@link InvoiceItemDto.CreateRequest} na doménový objekt {@link InvoiceItem}.
     * {@code invoiceId} nastavuje service vrstva před INSERTem.
     *
     * @param createRequest zvalidované create request DTO
     * @return doménový objekt připravený k INSERTu, nebo {@code null} při {@code null} vstupu
     */
    public InvoiceItem toDomain(InvoiceItemDto.CreateRequest createRequest) {
        if (createRequest == null) {
            return null;
        }

        InvoiceItem invoiceItem = new InvoiceItem();
        invoiceItem.setOrderItemId(createRequest.getOrderItemId());
        invoiceItem.setName(createRequest.getName());
        invoiceItem.setQuantity(createRequest.getQuantity());
        invoiceItem.setUnit(createRequest.getUnit());
        invoiceItem.setUnitPrice(createRequest.getUnitPrice());
        invoiceItem.setVatRate(createRequest.getVatRate());
        invoiceItem.setPosition(createRequest.getPosition());
        return invoiceItem;
    }

    /**
     * Aplikuje pole z {@link InvoiceItemDto.UpdateRequest} na existující {@link InvoiceItem}.
     * Existující objekt se mění na místě a vrací.
     *
     * @param existingItem  položka faktury načtená z databáze
     * @param updateRequest zvalidované update request DTO
     * @return upravený doménový objekt, nebo {@code null}, je-li kterýkoli argument {@code null}
     */
    public InvoiceItem applyUpdate(InvoiceItem existingItem, InvoiceItemDto.UpdateRequest updateRequest) {
        if (updateRequest == null || existingItem == null) {
            return null;
        }

        existingItem.setName(updateRequest.getName());
        existingItem.setQuantity(updateRequest.getQuantity());
        existingItem.setUnit(updateRequest.getUnit());
        existingItem.setUnitPrice(updateRequest.getUnitPrice());
        existingItem.setVatRate(updateRequest.getVatRate());
        existingItem.setPosition(updateRequest.getPosition());
        return existingItem;
    }

    public InvoiceItem fromOrderItem(OrderItem orderItem) {

        if (orderItem == null) {
            return null;
        }

        InvoiceItem invoiceItem = new InvoiceItem();
        invoiceItem.setOrderItemId(orderItem.getId());
        invoiceItem.setName(orderItem.getName());
        invoiceItem.setQuantity(orderItem.getQuantity());
        invoiceItem.setUnit(orderItem.getUnit());
        invoiceItem.setUnitPrice(orderItem.getUnitPrice());
        invoiceItem.setVatRate(orderItem.getVatRate());
        invoiceItem.setPosition(orderItem.getPosition());

        return invoiceItem;
    }
}
