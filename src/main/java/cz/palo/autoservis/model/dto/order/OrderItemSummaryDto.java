package cz.palo.autoservis.model.dto.order;

import cz.palo.autoservis.model.enums.OrderItemType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public class OrderItemSummaryDto {

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class Response {
        private Long orderId;
        private BigDecimal laborNet;
        private BigDecimal laborGross;
        private BigDecimal materialNet;
        private BigDecimal materialGross;
        private BigDecimal serviceNet;
        private BigDecimal serviceGross;
        private BigDecimal totalNet;
        private BigDecimal totalGross;
        private BigDecimal laborCost;
        private BigDecimal materialCost;
        private BigDecimal serviceCost;
        private BigDecimal totalCost;
    }
}
