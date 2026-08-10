package cz.palo.autoservis.model.converter;

import cz.palo.autoservis.model.domain.order.OrderItemSummary;
import cz.palo.autoservis.model.dto.order.OrderItemSummaryDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Konvertor souhrnu položek zakázky — čistý unit test bez Spring kontextu.
 *
 * <p>Souhrn se zobrazuje zákazníkovi jako rozpad ceny (práce / materiál / služby). Všech
 * osm částek se liší, aby test odhalil i prohození dvou polí — kdyby fixtura použila stejná
 * čísla, záměna „laborNet ↔ materialNet" by prošla bez povšimnutí.
 */
class OrderItemSummaryConverterTest {

    private final OrderItemSummaryConverter converter = new OrderItemSummaryConverter();

    @Test
    @DisplayName("toDto přenese všech osm částek na správná pole")
    void toDto_mapsEveryAmountToItsOwnField() {
        OrderItemSummary summary = OrderItemSummary.builder()
                .orderId(5L)
                .laborNet(new BigDecimal("1000.00"))
                .laborGross(new BigDecimal("1210.00"))
                .materialNet(new BigDecimal("2000.00"))
                .materialGross(new BigDecimal("2420.00"))
                .serviceNet(new BigDecimal("300.00"))
                .serviceGross(new BigDecimal("363.00"))
                .totalNet(new BigDecimal("3300.00"))
                .totalGross(new BigDecimal("3993.00"))
                .laborCost(new BigDecimal("400.00"))
                .materialCost(new BigDecimal("1500.00"))
                .serviceCost(new BigDecimal("50.00"))
                .totalCost(new BigDecimal("1950.00"))
                .build();

        OrderItemSummaryDto.Response response = converter.toDto(summary);

        assertThat(response.getOrderId()).isEqualTo(5L);
        assertThat(response.getLaborNet()).isEqualByComparingTo("1000.00");
        assertThat(response.getLaborGross()).isEqualByComparingTo("1210.00");
        assertThat(response.getMaterialNet()).isEqualByComparingTo("2000.00");
        assertThat(response.getMaterialGross()).isEqualByComparingTo("2420.00");
        assertThat(response.getServiceNet()).isEqualByComparingTo("300.00");
        assertThat(response.getServiceGross()).isEqualByComparingTo("363.00");
        assertThat(response.getTotalNet()).isEqualByComparingTo("3300.00");
        assertThat(response.getTotalGross()).isEqualByComparingTo("3993.00");
        assertThat(response.getLaborCost()).isEqualByComparingTo("400.00");
        assertThat(response.getMaterialCost()).isEqualByComparingTo("1500.00");
        assertThat(response.getServiceCost()).isEqualByComparingTo("50.00");
        assertThat(response.getTotalCost()).isEqualByComparingTo("1950.00");
    }

    @Test
    @DisplayName("toDto(null) → null")
    void toDto_null_returnsNull() {
        assertThat(converter.toDto(null)).isNull();
    }
}
