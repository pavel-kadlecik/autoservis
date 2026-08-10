package cz.palo.autoservis.model.converter;

import cz.palo.autoservis.model.domain.order.Order;
import cz.palo.autoservis.model.dto.order.OrderDto;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Konvertor mezi doménovými objekty {@link Order} a DTO {@link OrderDto}.
 */
@Component
public class OrderConverter {

    /**
     * Převede seznam doménových objektů {@link Order} na seznam {@link OrderDto.ListResponse}.
     *
     * @param orders seznam doménových objektů
     * @return seznam seznamových response DTO
     */
    public List<OrderDto.ListResponse> toListResponses(List<Order> orders) {
        return orders.stream().map(this::toListResponse).toList();
    }

    /**
     * Převede {@link Order} na plné {@link OrderDto.DetailResponse}.
     *
     * @param order doménový objekt k převodu
     * @return detailové response DTO, nebo {@code null} při {@code null} vstupu
     */
    public OrderDto.DetailResponse toDetailResponse(Order order) {
        if (order == null) {
            return null;
        }

        OrderDto.DetailResponse response = new OrderDto.DetailResponse();

        response.setId(order.getId());
        response.setOrderNumber(order.getOrderNumber());
        response.setCustomerDisplayName(order.getCustomerDisplayName());
        response.setCustomerId(order.getCustomerId());
        // vehicleId se dřív nenaplňoval, ačkoli ho DTO i dotaz mají (audit 01/J-5) — odkaz na
        // vozidlo z detailu zakázky proto nefungoval a zakázkový list by si vůz nedohledal.
        response.setVehicleId(order.getVehicleId());
        response.setVehicleModel(order.getVehicleModel());
        response.setVehicleBrand(order.getVehicleBrand());
        response.setVehicleLicensePlate(order.getVehicleLicensePlate());
        response.setVehicleVin(order.getVehicleVin());
        response.setStatus(order.getStatus());
        response.setInvoiceStatus(order.getInvoiceStatus());
        response.setInvoiceId(order.getInvoiceId());
        response.setDescription(order.getDescription());
        response.setInternalNote(order.getInternalNote());
        response.setEstimatedCompletionAt(order.getEstimatedCompletionAt());
        response.setCompletedAt(order.getCompletedAt());
        response.setEstimatedPrice(order.getEstimatedPrice());
        response.setFinalPrice(order.getFinalPrice());
        response.setMileageKmAtIntake(order.getMileageKmAtIntake());
        response.setReceivedAt(order.getReceivedAt());
        response.setCreatedAt(order.getCreatedAt());
        response.setUpdatedAt(order.getUpdatedAt());
        response.setCreatedBy(order.getCreatedBy());

        return response;
    }

    /**
     * Převede {@link OrderDto.CreateRequest} na doménový objekt {@link Order}.
     * Stav se záměrně nenastavuje — počáteční stav přiděluje databáze defaultem.
     * Auditní pole ({@code createdBy}) ani pole spravovaná DB (časová razítka,
     * orderNumber) se tady nenastavují.
     *
     * @param createRequest zvalidované create request DTO
     * @return doménový objekt připravený k INSERTu
     */
    public Order toDomain(OrderDto.CreateRequest createRequest) {
        Order order = new Order();

        order.setCustomerId(createRequest.getCustomerId());
        order.setVehicleId(createRequest.getVehicleId());
        order.setDescription(createRequest.getDescription());
        order.setInternalNote(createRequest.getInternalNote());
        order.setEstimatedCompletionAt(createRequest.getEstimatedCompletionAt());
        order.setEstimatedPrice(createRequest.getEstimatedPrice());
        order.setMileageKmAtIntake(createRequest.getMileageKmAtIntake());
        order.setReceivedAt(createRequest.getReceivedAt());

        return order;
    }

    /**
     * Aplikuje pole z {@link OrderDto.UpdateRequest} na existující {@link Order}.
     * Existující objekt se mění na místě a vrací.
     *
     * @param existingOrder zakázka načtená z databáze
     * @param updateRequest zvalidované update request DTO
     * @return upravený doménový objekt, nebo {@code null}, je-li kterýkoli argument {@code null}
     */
    public Order applyUpdate(Order existingOrder, OrderDto.UpdateRequest updateRequest) {
        if (updateRequest == null || existingOrder == null) {
            return null;
        }

        existingOrder.setStatus(updateRequest.getStatus());
        existingOrder.setDescription(updateRequest.getDescription());
        existingOrder.setInternalNote(updateRequest.getInternalNote());
        existingOrder.setEstimatedCompletionAt(updateRequest.getEstimatedCompletionAt());
        existingOrder.setEstimatedPrice(updateRequest.getEstimatedPrice());
        existingOrder.setFinalPrice(updateRequest.getFinalPrice());
        existingOrder.setCompletedAt(updateRequest.getCompletedAt());
        existingOrder.setMileageKmAtIntake(updateRequest.getMileageKmAtIntake());
        existingOrder.setReceivedAt(updateRequest.getReceivedAt());

        return existingOrder;
    }

    // =========================================================================
    // Privátní pomocné metody
    // =========================================================================

    private OrderDto.ListResponse toListResponse(Order order) {
        if (order == null) {
            return null;
        }

        OrderDto.ListResponse response = new OrderDto.ListResponse();

        response.setId(order.getId());
        response.setOrderNumber(order.getOrderNumber());
        response.setCustomerDisplayName(order.getCustomerDisplayName());
        response.setCustomerId(order.getCustomerId());
        response.setVehicleId(order.getVehicleId());
        response.setVehicleModel(order.getVehicleModel());
        response.setVehicleBrand(order.getVehicleBrand());
        response.setVehicleLicensePlate(order.getVehicleLicensePlate());
        response.setVehicleVin(order.getVehicleVin());
        response.setStatus(order.getStatus());
        response.setInvoiceId(order.getInvoiceId());
        response.setInvoiceStatus(order.getInvoiceStatus());
        response.setDescription(order.getDescription());
        response.setInternalNote(order.getInternalNote());
        response.setEstimatedCompletionAt(order.getEstimatedCompletionAt());
        response.setCompletedAt(order.getCompletedAt());
        response.setEstimatedPrice(order.getEstimatedPrice());
        response.setFinalPrice(order.getFinalPrice());
        response.setMileageKmAtIntake(order.getMileageKmAtIntake());
        response.setReceivedAt(order.getReceivedAt());
        response.setCreatedAt(order.getCreatedAt());
        response.setUpdatedAt(order.getUpdatedAt());
        response.setCreatedBy(order.getCreatedBy());

        return response;
    }
}
