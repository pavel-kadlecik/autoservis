package cz.palo.autoservis.model.converter;

import cz.palo.autoservis.model.domain.order.OrderItemSummary;
import cz.palo.autoservis.model.dto.order.OrderItemSummaryDto;
import org.springframework.stereotype.Component;

@Component
public class OrderItemSummaryConverter {

    public OrderItemSummaryDto.Response toDto (OrderItemSummary orderItemSummary) {

        if(orderItemSummary == null){
            return null;
        }

        return OrderItemSummaryDto.Response.builder()
                .orderId(orderItemSummary.getOrderId())
                .laborNet(orderItemSummary.getLaborNet())
                .laborGross(orderItemSummary.getLaborGross())
                .materialNet(orderItemSummary.getMaterialNet())
                .materialGross(orderItemSummary.getMaterialGross())
                .serviceNet(orderItemSummary.getServiceNet())
                .serviceGross(orderItemSummary.getServiceGross())
                .totalNet(orderItemSummary.getTotalNet())
                .totalGross(orderItemSummary.getTotalGross())
                .laborCost(orderItemSummary.getLaborCost())
                .materialCost(orderItemSummary.getMaterialCost())
                .serviceCost(orderItemSummary.getServiceCost())
                .totalCost(orderItemSummary.getTotalCost())
                .build();
    }

}
