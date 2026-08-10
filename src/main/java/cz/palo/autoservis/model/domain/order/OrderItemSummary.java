package cz.palo.autoservis.model.domain.order;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderItemSummary {

    private Long       orderId;
    private BigDecimal laborNet;
    private BigDecimal laborGross;
    private BigDecimal materialNet;
    private BigDecimal materialGross;
    private BigDecimal serviceNet;
    private BigDecimal serviceGross;
    private BigDecimal totalNet;
    private BigDecimal totalGross;

    /** Náklad bez DPH (množství × nákupní cena) po kategoriích — pro výpočet marže. */
    private BigDecimal laborCost;
    private BigDecimal materialCost;
    private BigDecimal serviceCost;
    private BigDecimal totalCost;

    public static OrderItemSummary zero(Long orderId) {
        BigDecimal z = BigDecimal.ZERO;
        return OrderItemSummary.builder()
                .orderId(orderId)
                .laborNet(z).laborGross(z)
                .materialNet(z).materialGross(z)
                .serviceNet(z).serviceGross(z)
                .totalNet(z).totalGross(z)
                .laborCost(z).materialCost(z)
                .serviceCost(z).totalCost(z)
                .build();
    }



}
