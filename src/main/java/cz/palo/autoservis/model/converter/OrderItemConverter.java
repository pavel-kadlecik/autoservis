package cz.palo.autoservis.model.converter;

import cz.palo.autoservis.model.domain.order.OrderItem;
import cz.palo.autoservis.model.dto.order.OrderItemDto;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Konvertor mezi doménovými objekty {@link OrderItem} a DTO {@link OrderItemDto}.
 */
@Component
public class OrderItemConverter {

    /**
     * Převede {@link OrderItem} na {@link OrderItemDto.Response}.
     *
     * @param orderItem doménový objekt k převodu
     * @return response DTO, nebo {@code null} při {@code null} vstupu
     */
    public OrderItemDto.Response toResponse(OrderItem orderItem) {
        if (orderItem == null) {
            return null;
        }

        OrderItemDto.Response response = new OrderItemDto.Response();
        response.setId(orderItem.getId());
        response.setOrderId(orderItem.getOrderId());
        response.setItemType(orderItem.getItemType());
        response.setName(orderItem.getName());
        response.setQuantity(orderItem.getQuantity());
        response.setUnit(orderItem.getUnit());
        response.setPurchasePrice(orderItem.getPurchasePrice());
        response.setUnitPrice(orderItem.getUnitPrice());
        response.setVatRate(orderItem.getVatRate());
        response.setPosition(orderItem.getPosition());
        response.setNote(orderItem.getNote());
        response.setFromStock(orderItem.getGoodsReceiptItemId() != null);
        response.setIssuedQuantity(orderItem.getIssuedQuantity());
        response.setProductSku(orderItem.getProductSku());
        response.setGoodsReceiptId(orderItem.getGoodsReceiptId());
        response.setSupplierName(orderItem.getSupplierName());
        response.setReceiptInvoiceNumber(orderItem.getReceiptInvoiceNumber());
        response.setEmployeeId(orderItem.getEmployeeId());
        response.setEmployeeName(orderItem.getEmployeeName());
        response.setCreatedAt(orderItem.getCreatedAt());
        response.setUpdatedAt(orderItem.getUpdatedAt());
        response.setCreatedBy(orderItem.getCreatedBy());
        return response;
    }

    /**
     * Převede {@link OrderItemDto.CreateRequest} na doménový objekt {@link OrderItem}.
     * Auditní pole a {@code orderId} nastavuje service vrstva po převodu.
     *
     * @param createRequest zvalidované create request DTO
     * @return doménový objekt připravený k INSERTu, nebo {@code null} při {@code null} vstupu
     */
    public OrderItem toDomain(OrderItemDto.CreateRequest createRequest) {
        if (createRequest == null) {
            return null;
        }

        OrderItem orderItem = new OrderItem();
        orderItem.setItemType(createRequest.getItemType());
        orderItem.setName(createRequest.getName());
        orderItem.setQuantity(createRequest.getQuantity());
        orderItem.setUnit(createRequest.getUnit());
        orderItem.setPurchasePrice(createRequest.getPurchasePrice());
        orderItem.setUnitPrice(createRequest.getUnitPrice());
        orderItem.setVatRate(createRequest.getVatRate());
        orderItem.setPosition(createRequest.getPosition());
        orderItem.setNote(createRequest.getNote());
        orderItem.setEmployeeId(createRequest.getEmployeeId());
        return orderItem;
    }

    /**
     * Aplikuje pole z {@link OrderItemDto.UpdateRequest} na existující {@link OrderItem}.
     * Existující objekt se mění na místě a vrací.
     *
     * @param existingOrderItem položka zakázky načtená z databáze
     * @param updateRequest     zvalidované update request DTO
     * @return upravený doménový objekt, nebo {@code null}, je-li kterýkoli argument {@code null}
     */
    public OrderItem applyUpdate(OrderItem existingOrderItem, OrderItemDto.UpdateRequest updateRequest) {
        if (updateRequest == null || existingOrderItem == null) {
            return null;
        }
        // měnitelná pole — přepisují se vždy
        existingOrderItem.setName(updateRequest.getName());
        existingOrderItem.setUnitPrice(updateRequest.getUnitPrice());
        existingOrderItem.setPosition(updateRequest.getPosition());
        existingOrderItem.setNote(updateRequest.getNote());

        // Množství jde měnit i u skladové položky (V83). Do rezervačního modelu se omyl
        // v počtu opravit musel tak, že se položka smazala a naimportovala znovu — a co
        // hůř, zadané číslo se tiše zahodilo, aniž by aplikace cokoli řekla.
        //   · pouhá rezervace → změní se jen slib, sklad se nehne (rezervace se odvozuje
        //     z tohoto čísla, nikde se neukládá),
        //   · už vydaná položka → rozdíl dorovná protipohyb
        //     (OrderItemServiceImpl.syncIssuedQuantity).
        existingOrderItem.setQuantity(updateRequest.getQuantity());

        // zamčená pole — přepisují se, jen když nejde o skladovou položku
        boolean isStockItem = existingOrderItem.getGoodsReceiptItemId() != null;
        if (!isStockItem) {
            existingOrderItem.setItemType(updateRequest.getItemType());
            existingOrderItem.setVatRate(updateRequest.getVatRate());
            existingOrderItem.setUnit(updateRequest.getUnit());
            existingOrderItem.setPurchasePrice(updateRequest.getPurchasePrice());
            // employee_id patří jen neskladovým položkám LABOR; skladové zůstávají netknuté (null)
            existingOrderItem.setEmployeeId(updateRequest.getEmployeeId());
        }

        return existingOrderItem;
    }

    /**
     * Převede seznam doménových objektů {@link OrderItem} na seznam {@link OrderItemDto.Response}.
     *
     * @param orderItems seznam doménových objektů
     * @return seznam response DTO
     */
    public List<OrderItemDto.Response> toListResponses(List<OrderItem> orderItems) {
        return orderItems.stream().map(this::toResponse).collect(Collectors.toList());
    }
}
