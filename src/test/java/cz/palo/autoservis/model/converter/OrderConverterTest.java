package cz.palo.autoservis.model.converter;

import cz.palo.autoservis.model.domain.order.Order;
import cz.palo.autoservis.model.dto.order.OrderDto;
import cz.palo.autoservis.model.enums.OrderStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Konvertor zakázek — čistý unit test bez Spring kontextu.
 *
 * <p>Hlídá dvě věci, které se snadno tiše rozbijí:
 * <ul>
 *   <li>{@code toDomain} <strong>nesmí</strong> nastavovat {@code status} — výchozí stav
 *       přiděluje DB default, ani {@code orderNumber} (trigger V11);</li>
 *   <li>{@code applyUpdate} nesmí sahat na {@code customerId}/{@code vehicleId} — zakázku
 *       nelze přehodit na jiného zákazníka či vozidlo.</li>
 * </ul>
 */
class OrderConverterTest {

    private final OrderConverter converter = new OrderConverter();

    @Test
    @DisplayName("toDomain přenese pole CreateRequest")
    void toDomain_mapsCreateRequestFields() {
        OrderDto.CreateRequest request = new OrderDto.CreateRequest();
        request.setCustomerId(1L);
        request.setVehicleId(11L);
        request.setDescription("Výměna rozvodů");
        request.setInternalNote("díly objednány");
        request.setEstimatedCompletionAt(OffsetDateTime.parse("2026-08-01T14:00:00Z"));
        request.setEstimatedPrice(new BigDecimal("12500.00"));
        request.setReceivedAt(LocalDate.parse("2026-07-30"));

        Order result = converter.toDomain(request);

        assertThat(result.getCustomerId()).isEqualTo(1L);
        assertThat(result.getVehicleId()).isEqualTo(11L);
        assertThat(result.getDescription()).isEqualTo("Výměna rozvodů");
        assertThat(result.getInternalNote()).isEqualTo("díly objednány");
        assertThat(result.getEstimatedCompletionAt()).isEqualTo(OffsetDateTime.parse("2026-08-01T14:00:00Z"));
        assertThat(result.getEstimatedPrice()).isEqualByComparingTo("12500.00");
        assertThat(result.getReceivedAt()).isEqualTo(LocalDate.parse("2026-07-30"));
    }

    @Test
    @DisplayName("toDomain nenastaví status ani orderNumber — obojí přiděluje databáze")
    void toDomain_leavesDbManagedFieldsEmpty() {
        OrderDto.CreateRequest request = new OrderDto.CreateRequest();
        request.setCustomerId(1L);
        request.setVehicleId(11L);
        request.setDescription("Výměna rozvodů");

        Order result = converter.toDomain(request);

        assertThat(result.getStatus()).as("výchozí stav přiděluje DB default").isNull();
        assertThat(result.getOrderNumber()).as("ZAK-… generuje trigger V11").isNull();
        assertThat(result.getId()).isNull();
        assertThat(result.getCreatedBy()).isNull();
        assertThat(result.getCreatedAt()).isNull();
    }

    @Test
    @DisplayName("applyUpdate přepíše editovatelná pole včetně stavu a konečné ceny")
    void applyUpdate_overwritesEditableFields() {
        Order existing = seededOrder();

        OrderDto.UpdateRequest request = new OrderDto.UpdateRequest();
        request.setStatus(OrderStatus.COMPLETED);
        request.setDescription("Výměna rozvodů + vodní pumpa");
        request.setInternalNote("hotovo");
        request.setEstimatedCompletionAt(OffsetDateTime.parse("2026-08-02T09:00:00Z"));
        request.setEstimatedPrice(new BigDecimal("13000.00"));
        request.setFinalPrice(new BigDecimal("13450.50"));
        request.setCompletedAt(OffsetDateTime.parse("2026-08-02T16:30:00Z"));
        request.setReceivedAt(LocalDate.parse("2026-07-29"));

        Order result = converter.applyUpdate(existing, request);

        assertThat(result).as("mutace probíhá na místě, vrací se tentýž objekt").isSameAs(existing);
        assertThat(existing.getStatus()).isEqualTo(OrderStatus.COMPLETED);
        assertThat(existing.getDescription()).isEqualTo("Výměna rozvodů + vodní pumpa");
        assertThat(existing.getInternalNote()).isEqualTo("hotovo");
        assertThat(existing.getEstimatedCompletionAt()).isEqualTo(OffsetDateTime.parse("2026-08-02T09:00:00Z"));
        assertThat(existing.getEstimatedPrice()).isEqualByComparingTo("13000.00");
        assertThat(existing.getFinalPrice()).isEqualByComparingTo("13450.50");
        assertThat(existing.getCompletedAt()).isEqualTo(OffsetDateTime.parse("2026-08-02T16:30:00Z"));
        assertThat(existing.getReceivedAt()).isEqualTo(LocalDate.parse("2026-07-29"));
    }

    @Test
    @DisplayName("applyUpdate nesmí přehodit zakázku na jiného zákazníka ani vozidlo")
    void applyUpdate_doesNotTouchCustomerOrVehicle() {
        Order existing = seededOrder();
        existing.setCustomerId(1L);
        existing.setVehicleId(11L);
        existing.setOrderNumber("ZAK-2026-0001");
        existing.setCreatedBy(9L);

        OrderDto.UpdateRequest request = new OrderDto.UpdateRequest();
        request.setStatus(OrderStatus.IN_PROGRESS);

        converter.applyUpdate(existing, request);

        assertThat(existing.getCustomerId()).isEqualTo(1L);
        assertThat(existing.getVehicleId()).isEqualTo(11L);
        assertThat(existing.getOrderNumber()).isEqualTo("ZAK-2026-0001");
        assertThat(existing.getCreatedBy()).isEqualTo(9L);
    }

    @Test
    @DisplayName("applyUpdate vrací null, chybí-li kterýkoli z argumentů")
    void applyUpdate_nullArguments_returnNull() {
        assertThat(converter.applyUpdate(null, new OrderDto.UpdateRequest())).isNull();
        assertThat(converter.applyUpdate(seededOrder(), null)).isNull();
    }

    @Test
    @DisplayName("toDetailResponse namapuje zakázku i denormalizované údaje zákazníka a vozidla")
    void toDetailResponse_mapsOrderWithJoinedColumns() {
        Order order = seededOrder();
        order.setId(5L);
        order.setOrderNumber("ZAK-2026-0001");
        order.setStatus(OrderStatus.IN_PROGRESS);
        order.setCustomerDisplayName("Jan Novák");
        order.setVehicleBrand("Škoda");
        order.setVehicleModel("Octavia");
        order.setVehicleLicensePlate("1AB 2345");
        order.setVehicleVin("TMBJJ7NE0E0123456");
        order.setInternalNote("díly objednány");
        order.setEstimatedCompletionAt(OffsetDateTime.parse("2026-08-01T14:00:00Z"));
        order.setCompletedAt(OffsetDateTime.parse("2026-08-02T16:30:00Z"));
        order.setFinalPrice(new BigDecimal("13450.50"));
        order.setCreatedBy(9L);
        order.setCreatedAt(OffsetDateTime.parse("2026-07-25T07:00:00Z"));
        order.setUpdatedAt(OffsetDateTime.parse("2026-08-02T16:30:00Z"));

        OrderDto.DetailResponse response = converter.toDetailResponse(order);

        assertThat(response.getId()).isEqualTo(5L);
        assertThat(response.getOrderNumber()).isEqualTo("ZAK-2026-0001");
        assertThat(response.getStatus()).isEqualTo(OrderStatus.IN_PROGRESS);
        assertThat(response.getCustomerId()).isEqualTo(1L);
        assertThat(response.getCustomerDisplayName()).isEqualTo("Jan Novák");
        assertThat(response.getVehicleBrand()).isEqualTo("Škoda");
        assertThat(response.getVehicleModel()).isEqualTo("Octavia");
        assertThat(response.getVehicleLicensePlate()).isEqualTo("1AB 2345");
        assertThat(response.getVehicleVin()).isEqualTo("TMBJJ7NE0E0123456");
        assertThat(response.getDescription()).isEqualTo("Výměna rozvodů");
        assertThat(response.getInternalNote()).isEqualTo("díly objednány");
        assertThat(response.getEstimatedCompletionAt()).isEqualTo(OffsetDateTime.parse("2026-08-01T14:00:00Z"));
        assertThat(response.getCompletedAt()).isEqualTo(OffsetDateTime.parse("2026-08-02T16:30:00Z"));
        assertThat(response.getEstimatedPrice()).isEqualByComparingTo("12500.00");
        assertThat(response.getFinalPrice()).isEqualByComparingTo("13450.50");
        assertThat(response.getReceivedAt()).isEqualTo(LocalDate.parse("2026-07-25"));
        assertThat(response.getCreatedBy()).isEqualTo(9L);
        assertThat(response.getCreatedAt()).isEqualTo(OffsetDateTime.parse("2026-07-25T07:00:00Z"));
        assertThat(response.getUpdatedAt()).isEqualTo(OffsetDateTime.parse("2026-08-02T16:30:00Z"));
    }

    @Test
    @DisplayName("toDetailResponse(null) → null")
    void toDetailResponse_null_returnsNull() {
        assertThat(converter.toDetailResponse(null)).isNull();
    }

    @Test
    @DisplayName("toListResponses zachová pořadí a rozliší stavy jednotlivých zakázek")
    void toListResponses_mapsRowsInOrder() {
        Order inProgress = seededOrder();
        inProgress.setId(5L);
        inProgress.setOrderNumber("ZAK-2026-0001");
        inProgress.setStatus(OrderStatus.IN_PROGRESS);
        inProgress.setCustomerDisplayName("Jan Novák");
        inProgress.setVehicleBrand("Škoda");
        inProgress.setVehicleModel("Octavia");
        inProgress.setVehicleLicensePlate("1AB 2345");
        inProgress.setVehicleVin("TMBJJ7NE0E0123456");
        inProgress.setInternalNote("díly objednány");
        inProgress.setEstimatedCompletionAt(OffsetDateTime.parse("2026-08-01T14:00:00Z"));
        inProgress.setCompletedAt(OffsetDateTime.parse("2026-08-02T16:30:00Z"));
        inProgress.setFinalPrice(new BigDecimal("13450.50"));
        inProgress.setCreatedBy(9L);
        inProgress.setCreatedAt(OffsetDateTime.parse("2026-07-25T07:00:00Z"));
        inProgress.setUpdatedAt(OffsetDateTime.parse("2026-08-02T16:30:00Z"));

        Order completed = seededOrder();
        completed.setId(6L);
        completed.setOrderNumber("ZAK-2026-0002");
        completed.setStatus(OrderStatus.COMPLETED);

        List<OrderDto.ListResponse> result = converter.toListResponses(List.of(inProgress, completed));

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getId()).isEqualTo(5L);
        assertThat(result.get(0).getOrderNumber()).isEqualTo("ZAK-2026-0001");
        assertThat(result.get(0).getStatus()).isEqualTo(OrderStatus.IN_PROGRESS);
        assertThat(result.get(0).getCustomerDisplayName()).isEqualTo("Jan Novák");
        assertThat(result.get(0).getVehicleBrand()).isEqualTo("Škoda");
        assertThat(result.get(0).getVehicleModel()).isEqualTo("Octavia");
        assertThat(result.get(0).getVehicleLicensePlate()).isEqualTo("1AB 2345");
        assertThat(result.get(0).getVehicleVin()).isEqualTo("TMBJJ7NE0E0123456");
        assertThat(result.get(0).getDescription()).isEqualTo("Výměna rozvodů");
        assertThat(result.get(0).getInternalNote()).isEqualTo("díly objednány");
        assertThat(result.get(0).getEstimatedCompletionAt()).isEqualTo(OffsetDateTime.parse("2026-08-01T14:00:00Z"));
        assertThat(result.get(0).getCompletedAt()).isEqualTo(OffsetDateTime.parse("2026-08-02T16:30:00Z"));
        assertThat(result.get(0).getEstimatedPrice()).isEqualByComparingTo("12500.00");
        assertThat(result.get(0).getFinalPrice()).isEqualByComparingTo("13450.50");
        assertThat(result.get(0).getCreatedBy()).isEqualTo(9L);
        assertThat(result.get(0).getCreatedAt()).isEqualTo(OffsetDateTime.parse("2026-07-25T07:00:00Z"));
        assertThat(result.get(0).getUpdatedAt()).isEqualTo(OffsetDateTime.parse("2026-08-02T16:30:00Z"));

        assertThat(result.get(1).getId()).isEqualTo(6L);
        assertThat(result.get(1).getOrderNumber()).isEqualTo("ZAK-2026-0002");
        assertThat(result.get(1).getStatus()).isEqualTo(OrderStatus.COMPLETED);
    }

    private static Order seededOrder() {
        Order order = new Order();
        order.setCustomerId(1L);
        order.setVehicleId(11L);
        order.setDescription("Výměna rozvodů");
        order.setEstimatedPrice(new BigDecimal("12500.00"));
        order.setReceivedAt(LocalDate.parse("2026-07-25"));
        return order;
    }
}
