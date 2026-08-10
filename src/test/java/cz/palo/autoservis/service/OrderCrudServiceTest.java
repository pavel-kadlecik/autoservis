package cz.palo.autoservis.service;

import cz.palo.autoservis.AbstractIntegrationTest;
import cz.palo.autoservis.exception.BusinessRuleException;
import cz.palo.autoservis.exception.ResourceNotFoundException;
import cz.palo.autoservis.mapper.OrderMapper;
import cz.palo.autoservis.model.enums.OrderItemType;
import cz.palo.autoservis.model.domain.order.OrderItem;
import cz.palo.autoservis.mapper.OrderItemMapper;
import cz.palo.autoservis.model.dto.order.OrderDto;
import cz.palo.autoservis.model.dto.order.OrderSearchParams;
import cz.palo.autoservis.model.enums.OrderStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * CRUD zakázek ({@code OrderServiceImpl}) — doplňuje {@code OrderSearchTest} (fulltext)
 * a {@code OrderItemInvoiceLockTest} (zámek položek fakturou).
 *
 * <p>Pokrývá: přidělení čísla {@code ZAK-…} databázovým triggerem, audit {@code created_by}
 * ze serveru, výchozí stav z DB defaultu, neměnnost zákazníka a vozidla při update
 * a počítání otevřených zakázek (na něm stojí guardy deaktivace zákazníka i vozidla).
 */
@Transactional
class OrderCrudServiceTest extends AbstractIntegrationTest {

    private static final long CUSTOMER_ID = 1L;
    private static final long VEHICLE_ID = 1L;
    private static final long USER_ID = 1L;

    @Autowired
    private OrderService orderService;

    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private OrderItemMapper orderItemMapper;

    // =========================================================================
    // create
    // =========================================================================

    @Test
    @DisplayName("create uloží zakázku, doplní createdBy ze serveru a vrátí ji z DB")
    void create_persistsOrderWithServerSideAudit() {
        OrderDto.CreateRequest request = createRequest("Výměna rozvodů");
        request.setInternalNote("díly objednány");
        request.setEstimatedCompletionAt(OffsetDateTime.parse("2026-08-01T14:00:00Z"));
        request.setEstimatedPrice(new BigDecimal("12500.00"));

        OrderDto.DetailResponse created = orderService.create(request, USER_ID);

        assertThat(created.getId()).isNotNull();
        assertThat(created.getCustomerId()).isEqualTo(CUSTOMER_ID);
        assertThat(created.getDescription()).isEqualTo("Výměna rozvodů");
        assertThat(created.getInternalNote()).isEqualTo("díly objednány");
        assertThat(created.getEstimatedPrice()).isEqualByComparingTo("12500.00");
        assertThat(created.getCreatedBy()).as("audit doplňuje server, ne DTO").isEqualTo(USER_ID);
    }

    @Test
    @DisplayName("create zakázky s vozidlem cizího zákazníka → VEHICLE_NOT_OWNED_BY_CUSTOMER (E3.2/K-12)")
    void create_vehicleOfAnotherCustomer_isRejected() {
        // Vozidlo 2 patří dle seedu zákazníkovi 3, ne zákazníkovi 1 (CUSTOMER_ID).
        OrderDto.CreateRequest request = createRequest("Zakázka na cizí vozidlo");
        request.setVehicleId(2L);

        assertThatThrownBy(() -> orderService.create(request, USER_ID))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(e -> assertThat(((BusinessRuleException) e).getRuleCode())
                        .isEqualTo("VEHICLE_NOT_OWNED_BY_CUSTOMER"));
    }

    @Test
    @DisplayName("create přidělí číslo ZAK-… databázovým triggerem, ne Javou")
    void create_assignsOrderNumberFromTrigger() {
        OrderDto.DetailResponse created = orderService.create(createRequest("Diagnostika"), USER_ID);

        assertThat(created.getOrderNumber()).matches("ZAK-\\d{4}-\\d{4}");
    }

    @Test
    @DisplayName("dvě zakázky po sobě dostanou různá čísla (sekvence běží)")
    void create_twoOrders_getDistinctNumbers() {
        String first = orderService.create(createRequest("První"), USER_ID).getOrderNumber();
        String second = orderService.create(createRequest("Druhá"), USER_ID).getOrderNumber();

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    @DisplayName("nová zakázka dostane výchozí stav RECEIVED z DB defaultu")
    void create_getsDefaultStatusFromDatabase() {
        OrderDto.DetailResponse created = orderService.create(createRequest("Nová zakázka"), USER_ID);

        assertThat(created.getStatus())
                .as("stav nenastavuje konvertor ani service — přiděluje ho DB default")
                .isEqualTo(OrderStatus.RECEIVED);
    }

    // =========================================================================
    // update
    // =========================================================================

    @Test
    @DisplayName("update změní stav, popis a ceny")
    void update_changesEditableFields() {
        Long orderId = orderService.create(createRequest("Původní popis"), USER_ID).getId();

        OrderDto.UpdateRequest request = new OrderDto.UpdateRequest();
        request.setReceivedAt(LocalDate.now());
        request.setStatus(OrderStatus.IN_PROGRESS);
        request.setDescription("Upravený popis");
        request.setInternalNote("čeká na díly");
        request.setEstimatedPrice(new BigDecimal("13000.00"));
        request.setFinalPrice(new BigDecimal("13450.50"));
        request.setCompletedAt(OffsetDateTime.parse("2026-08-02T16:30:00Z"));

        OrderDto.DetailResponse updated = orderService.update(orderId, request, USER_ID);

        assertThat(updated.getStatus()).isEqualTo(OrderStatus.IN_PROGRESS);
        assertThat(updated.getDescription()).isEqualTo("Upravený popis");
        assertThat(updated.getInternalNote()).isEqualTo("čeká na díly");
        assertThat(updated.getEstimatedPrice()).isEqualByComparingTo("13000.00");
        assertThat(updated.getFinalPrice()).isEqualByComparingTo("13450.50");
        assertThat(updated.getCompletedAt()).isEqualTo(OffsetDateTime.parse("2026-08-02T16:30:00Z"));
    }

    @Test
    @DisplayName("update nepřehodí zakázku na jiného zákazníka ani vozidlo (immutable vazby)")
    void update_keepsCustomerAndVehicle() {
        OrderDto.DetailResponse created = orderService.create(createRequest("Zakázka"), USER_ID);
        String orderNumber = created.getOrderNumber();

        OrderDto.UpdateRequest request = new OrderDto.UpdateRequest();
        request.setReceivedAt(LocalDate.now());
        request.setStatus(OrderStatus.DIAGNOSIS);
        request.setDescription("Zakázka");

        OrderDto.DetailResponse updated = orderService.update(created.getId(), request, USER_ID);

        assertThat(updated.getCustomerId()).isEqualTo(CUSTOMER_ID);
        assertThat(updated.getOrderNumber()).as("číslo se nepřegeneruje").isEqualTo(orderNumber);
        assertThat(orderMapper.findById(created.getId()).orElseThrow().getVehicleId())
                .isEqualTo(VEHICLE_ID);
    }

    @Test
    @DisplayName("update neexistující zakázky → ResourceNotFoundException (404)")
    void update_unknownId_throwsResourceNotFound() {
        OrderDto.UpdateRequest request = new OrderDto.UpdateRequest();
        request.setReceivedAt(LocalDate.now());
        request.setStatus(OrderStatus.IN_PROGRESS);

        assertThatThrownBy(() -> orderService.update(999_999L, request, USER_ID))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // =========================================================================
    // getById
    // =========================================================================

    @Test
    @DisplayName("getById vrátí zakázku i s denormalizovanými údaji zákazníka a vozidla")
    void getById_returnsOrderWithJoinedData() {
        Long orderId = orderService.create(createRequest("Zakázka s JOINy"), USER_ID).getId();

        OrderDto.DetailResponse response = orderService.getById(orderId);

        assertThat(response.getId()).isEqualTo(orderId);
        assertThat(response.getCustomerDisplayName()).isEqualTo("Jan Novák");
        assertThat(response.getVehicleBrand()).isEqualTo("BMW");
        assertThat(response.getVehicleVin()).isEqualTo("WBA3A5C50DF595551");
    }

    @Test
    @DisplayName("getById neexistující zakázky → ResourceNotFoundException (404)")
    void getById_unknownId_throwsResourceNotFound() {
        assertThatThrownBy(() -> orderService.getById(999_999L))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // =========================================================================
    // countOpenBy… — podklad pro guardy deaktivace
    // =========================================================================

    @Test
    @DisplayName("nová zakázka zvýší počet otevřených zakázek zákazníka i vozidla o jednu")
    void countOpen_newOrderIncrementsBothCounters() {
        int customerBefore = orderService.countOpenByCustomerId(CUSTOMER_ID);
        int vehicleBefore = orderService.countOpenByVehicleId(VEHICLE_ID);

        orderService.create(createRequest("Otevřená zakázka"), USER_ID);

        assertThat(orderService.countOpenByCustomerId(CUSTOMER_ID)).isEqualTo(customerBefore + 1);
        assertThat(orderService.countOpenByVehicleId(VEHICLE_ID)).isEqualTo(vehicleBefore + 1);
    }

    @Test
    @DisplayName("uzavření zakázky ji z počtu otevřených odečte (druhá větev)")
    void countOpen_completedOrderIsNotCounted() {
        int before = orderService.countOpenByVehicleId(VEHICLE_ID);
        OrderDto.DetailResponse created = orderService.create(createRequest("Bude hotová"), USER_ID);
        assertThat(orderService.countOpenByVehicleId(VEHICLE_ID)).isEqualTo(before + 1);

        // Zakázku bez položek nelze dokončit (2026-08-05) — prázdná by jako hotová práce lhala.
        orderItemMapper.insert(OrderItem.builder()
                .orderId(created.getId()).itemType(OrderItemType.LABOR)
                .name("Provedená práce").quantity(BigDecimal.ONE).unit("hod")
                .unitPrice(new BigDecimal("500")).vatRate((short) 21).position((short) 1)
                .createdBy(USER_ID).build());

        OrderDto.UpdateRequest request = new OrderDto.UpdateRequest();
        request.setReceivedAt(LocalDate.now());
        request.setStatus(OrderStatus.COMPLETED);
        request.setDescription("Bude hotová");
        orderService.update(created.getId(), request, USER_ID);

        assertThat(orderService.countOpenByVehicleId(VEHICLE_ID)).isEqualTo(before);
    }

    @Test
    @DisplayName("zrušená zakázka se do otevřených taky nepočítá")
    void countOpen_cancelledOrderIsNotCounted() {
        int before = orderService.countOpenByCustomerId(CUSTOMER_ID);
        OrderDto.DetailResponse created = orderService.create(createRequest("Bude zrušená"), USER_ID);

        OrderDto.UpdateRequest request = new OrderDto.UpdateRequest();
        request.setReceivedAt(LocalDate.now());
        request.setStatus(OrderStatus.CANCELLED);
        request.setDescription("Bude zrušená");
        orderService.update(created.getId(), request, USER_ID);

        assertThat(orderService.countOpenByCustomerId(CUSTOMER_ID)).isEqualTo(before);
    }

    // =========================================================================
    // getPage — seznam
    // =========================================================================

    @Test
    @DisplayName("getPage vrátí aktivní zakázky včetně nově založené")
    void getPage_containsNewOrder() {
        OrderDto.DetailResponse created = orderService.create(createRequest("Čerstvá zakázka"), USER_ID);

        var all = orderService.getPage(allActiveParams()).getContent();

        assertThat(all).isNotEmpty();
        assertThat(all).extracting(OrderDto.ListResponse::getId).contains(created.getId());
        assertThat(all).extracting(OrderDto.ListResponse::getOrderNumber).doesNotContainNull();
    }

    /** Parametry pro „vše aktivní na jedné stránce" — testovací DB má zakázek řádově jednotky. */
    private static OrderSearchParams allActiveParams() {
        OrderSearchParams params = new OrderSearchParams();
        params.setPageSize(100);
        return params;
    }

    private static OrderDto.CreateRequest createRequest(String description) {
        OrderDto.CreateRequest request = new OrderDto.CreateRequest();
        request.setReceivedAt(LocalDate.now());
        request.setCustomerId(CUSTOMER_ID);
        request.setVehicleId(VEHICLE_ID);
        request.setDescription(description);
        return request;
    }
}
