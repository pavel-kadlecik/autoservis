package cz.palo.autoservis.service;

import cz.palo.autoservis.AbstractIntegrationTest;
import cz.palo.autoservis.mapper.OrderMapper;
import cz.palo.autoservis.model.domain.order.Order;
import cz.palo.autoservis.model.dto.order.OrderDto;
import cz.palo.autoservis.model.dto.order.OrderSearchParams;
import cz.palo.autoservis.model.dto.pagination.PagedResponse;
import cz.palo.autoservis.model.enums.OrderStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testy víceslovného hledání v {@link OrderService#getPage(OrderSearchParams)} (TD-25).
 *
 * <p>Seed data (V8__seed_vehicles_and_orders.sql): zakázka {@code ZAK-2025-0002} patří
 * zákazníkovi 1 — „Jan Novák" (seed V3) — a vozidlu 3 — VW Passat, SPZ „3EF 4567".
 * Prohledávané sloupce (OrderMapper.xml {@code WhereClause}): order_number, u zákazníka
 * first_name/last_name/company_name/primary_phone, u vozidla vin/license_plate/brand/model,
 * description.
 */
@Transactional
class OrderSearchTest extends AbstractIntegrationTest {

    @Autowired
    private OrderService orderService;

    @Autowired
    private OrderMapper orderMapper;

    private PagedResponse<OrderDto.ListResponse> search(String query) {
        OrderSearchParams params = new OrderSearchParams();
        params.setSearch(query);
        params.setPageSize(50);
        return orderService.getPage(params);
    }

    /** Vytvoří zakázku pro seed zákazníka 1 / vozidlo 1 v daném stavu a vrátí její číslo. */
    private String createOrderWithStatus(OrderStatus status) {
        Order order = Order.builder()
                .receivedAt(LocalDate.now())
                .customerId(1L)
                .vehicleId(1L)
                .description("filtr stavu — test")
                .estimatedPrice(new BigDecimal("100"))
                .createdBy(1L)
                .build();
        orderMapper.insert(order);                 // trigger nastaví status RECEIVED + číslo
        if (status != OrderStatus.RECEIVED) {
            order.setStatus(status);
            orderMapper.update(order);             // full-replace na cílový stav
        }
        return orderMapper.findById(order.getId()).orElseThrow().getOrderNumber();
    }

    private PagedResponse<OrderDto.ListResponse> searchByStatus(OrderStatus status) {
        OrderSearchParams params = new OrderSearchParams();
        params.setStatuses(java.util.List.of(status));
        params.setPageSize(500);
        return orderService.getPage(params);
    }

    @Test
    @DisplayName("filtr více stavů najednou → vrátí zakázky ze všech zaškrtnutých, ostatní vynechá")
    void statusFilter_multipleStatuses_returnsUnion() {
        String received = createOrderWithStatus(OrderStatus.RECEIVED);
        String inProgress = createOrderWithStatus(OrderStatus.IN_PROGRESS);
        String completed = createOrderWithStatus(OrderStatus.COMPLETED);

        OrderSearchParams params = new OrderSearchParams();
        params.setStatuses(java.util.List.of(OrderStatus.RECEIVED, OrderStatus.IN_PROGRESS));
        params.setPageSize(500);

        assertThat(orderService.getPage(params).getContent())
                .extracting(OrderDto.ListResponse::getOrderNumber)
                .as("běžný dotaz obsluhy zní „ukaž mi rozpracované\", což je víc stavů zároveň")
                .contains(received, inProgress)
                .doesNotContain(completed);
    }

    @Test
    @DisplayName("prázdný seznam stavů = bez filtru, vrátí i zrušené")
    void statusFilter_emptyList_doesNotFilter() {
        String cancelled = createOrderWithStatus(OrderStatus.CANCELLED);

        OrderSearchParams params = new OrderSearchParams();
        params.setStatuses(java.util.List.of());
        params.setPageSize(500);

        assertThat(orderService.getPage(params).getContent())
                .extracting(OrderDto.ListResponse::getOrderNumber)
                .contains(cancelled);
    }

    @Test
    @DisplayName("filtr status=IN_PROGRESS → vrátí jen zakázky v tomto stavu, ostatní vynechá")
    void statusFilter_returnsOnlyThatStatus() {
        String inProgress = createOrderWithStatus(OrderStatus.IN_PROGRESS);
        String received   = createOrderWithStatus(OrderStatus.RECEIVED);

        PagedResponse<OrderDto.ListResponse> result = searchByStatus(OrderStatus.IN_PROGRESS);

        // Každý vrácený řádek je IN_PROGRESS (nezávisle na seedu)
        assertThat(result.getContent())
                .extracting(OrderDto.ListResponse::getStatus)
                .containsOnly(OrderStatus.IN_PROGRESS);
        // Vlastní zakázky: IN_PROGRESS je vidět, RECEIVED ne
        assertThat(result.getContent())
                .extracting(OrderDto.ListResponse::getOrderNumber)
                .contains(inProgress)
                .doesNotContain(received);
    }

    @Test
    @DisplayName("filtr se promítá do počtu (countSearch) — totalElements s filtrem ≤ bez filtru")
    void statusFilter_narrowsPaginationCount() {
        createOrderWithStatus(OrderStatus.IN_PROGRESS);

        OrderSearchParams all = new OrderSearchParams();
        all.setPageSize(500);
        long totalAll = orderService.getPage(all).getTotalElements();

        long totalInProgress = searchByStatus(OrderStatus.IN_PROGRESS).getTotalElements();

        assertThat(totalInProgress).isGreaterThan(0).isLessThanOrEqualTo(totalAll);
    }

    /** Vytvoří otevřenou zakázku s termínem v minulosti/budoucnosti a vrátí její číslo. */
    private String createOrderWithDeadline(OrderStatus status, java.time.OffsetDateTime deadline) {
        Order order = Order.builder()
                .receivedAt(LocalDate.now())
                .customerId(1L).vehicleId(1L)
                .description("termín — test")
                .estimatedCompletionAt(deadline)
                .estimatedPrice(new BigDecimal("100"))
                .createdBy(1L)
                .build();
        orderMapper.insert(order);
        if (status != OrderStatus.RECEIVED) {
            order.setStatus(status);
            order.setEstimatedCompletionAt(deadline); // full-replace nesmí termín vynulovat
            orderMapper.update(order);
        }
        return orderMapper.findById(order.getId()).orElseThrow().getOrderNumber();
    }

    @Test
    @DisplayName("filtr overdue → jen otevřené zakázky s prošlým termínem; hotové, budoucí a bez termínu vynechá")
    void overdueFilter_returnsOnlyOpenPastDeadline() {
        var past = java.time.OffsetDateTime.now().minusDays(3);
        var future = java.time.OffsetDateTime.now().plusDays(3);

        String overdueOpen  = createOrderWithDeadline(OrderStatus.IN_PROGRESS, past);   // ✓ po termínu
        String doneButPast  = createOrderWithDeadline(OrderStatus.COMPLETED, past);     // ✗ uzavřená
        String openFuture   = createOrderWithDeadline(OrderStatus.IN_PROGRESS, future); // ✗ termín v budoucnu
        String openNoDate   = createOrderWithStatus(OrderStatus.IN_PROGRESS);           // ✗ bez termínu

        OrderSearchParams params = new OrderSearchParams();
        params.setOverdue(true);
        params.setPageSize(500);
        PagedResponse<OrderDto.ListResponse> result = orderService.getPage(params);

        assertThat(result.getContent())
                .extracting(OrderDto.ListResponse::getOrderNumber)
                .contains(overdueOpen)
                .doesNotContain(doneButPast, openFuture, openNoDate);
        // Žádný vrácený řádek není v terminálním stavu
        assertThat(result.getContent())
                .extracting(OrderDto.ListResponse::getStatus)
                .doesNotContain(OrderStatus.COMPLETED, OrderStatus.CANCELLED);
    }

    @Test
    @DisplayName("\"Novák Passat\" → najde zakázku ZAK-2025-0002 (příjmení zákazníka + model vozidla napříč sloupci)")
    void twoWordQuery_findsOrderAcrossColumns() {
        PagedResponse<OrderDto.ListResponse> result = search("Novák Passat");

        assertThat(result.getContent())
                .extracting(OrderDto.ListResponse::getOrderNumber)
                .containsExactly("ZAK-2025-0002");
    }

    @Test
    @DisplayName("\"passat novak\" (přehozené pořadí, bez diakritiky) → také najde ZAK-2025-0002")
    void reversedWordOrderWithoutDiacritics_findsOrder() {
        PagedResponse<OrderDto.ListResponse> result = search("passat novak");

        assertThat(result.getContent())
                .extracting(OrderDto.ListResponse::getOrderNumber)
                .containsExactly("ZAK-2025-0002");
    }

    @Test
    @DisplayName("\"Novák ZAK-2025-0002\" → jméno zákazníka + číslo zakázky napříč sloupci")
    void customerNamePlusOrderNumber_findsOrder() {
        PagedResponse<OrderDto.ListResponse> result = search("Novák ZAK-2025-0002");

        assertThat(result.getContent())
                .extracting(OrderDto.ListResponse::getOrderNumber)
                .containsExactly("ZAK-2025-0002");
    }

    @Test
    @DisplayName("\"Novák Neexistujici\" → nenajde nic (druhé slovo nemá shodu v žádném sloupci)")
    void secondWordWithoutMatch_findsNothing() {
        PagedResponse<OrderDto.ListResponse> result = search("Novák Neexistujici");

        assertThat(result.getContent()).isEmpty();
    }

    @Test
    @DisplayName("jednoslovné \"Novák\" → funguje jako dřív (najde všechny zakázky zákazníka Novák)")
    void singleWordQuery_worksAsBefore() {
        PagedResponse<OrderDto.ListResponse> result = search("Novák");

        assertThat(result.getContent())
                .extracting(OrderDto.ListResponse::getOrderNumber)
                .contains("ZAK-2025-0002");
    }

    // =========================================================================
    // Servisní historie — filtr podle vozidla a zákazníka (audit KN-27)
    // =========================================================================

    /**
     * Seed (V8): vozidlo 1 (BMW) i vozidlo 3 (VW Passat) patří zákazníkovi 1, vozidlo 4
     * zákazníkovi 2. Testy proto rozliší filtr na vozidlo od filtru na zákazníka — dvě vozidla
     * téhož zákazníka odhalí záměnu, kterou by jedno vozidlo na zákazníka neukázalo.
     */
    private String createOrderFor(Long customerId, Long vehicleId) {
        Order order = Order.builder()
                .receivedAt(LocalDate.now())
                .customerId(customerId)
                .vehicleId(vehicleId)
                .description("servisní historie — test")
                .estimatedPrice(new BigDecimal("100"))
                .createdBy(1L)
                .build();
        orderMapper.insert(order);
        return orderMapper.findById(order.getId()).orElseThrow().getOrderNumber();
    }

    private PagedResponse<OrderDto.ListResponse> searchByVehicleAndCustomer(Long vehicleId, Long customerId) {
        OrderSearchParams params = new OrderSearchParams();
        params.setVehicleId(vehicleId);
        params.setCustomerId(customerId);
        params.setPageSize(500);
        return orderService.getPage(params);
    }

    @Test
    @DisplayName("filtr vehicleId → jen zakázky toho vozu, ne dalšího vozu téhož zákazníka")
    void vehicleFilter_returnsOnlyOrdersOfThatVehicle() {
        String bmwOrder = createOrderFor(1L, 1L);
        String passatOrder = createOrderFor(1L, 3L);

        PagedResponse<OrderDto.ListResponse> result = searchByVehicleAndCustomer(1L, null);

        assertThat(result.getContent())
                .extracting(OrderDto.ListResponse::getOrderNumber)
                .contains(bmwOrder)
                .doesNotContain(passatOrder);
    }

    @Test
    @DisplayName("filtr customerId → jen zakázky toho zákazníka, napříč jeho vozidly")
    void customerFilter_returnsOnlyOrdersOfThatCustomer() {
        String novakBmw = createOrderFor(1L, 1L);
        String novakPassat = createOrderFor(1L, 3L);
        String svobodovaBmw = createOrderFor(2L, 4L);

        PagedResponse<OrderDto.ListResponse> result = searchByVehicleAndCustomer(null, 1L);

        assertThat(result.getContent())
                .extracting(OrderDto.ListResponse::getOrderNumber)
                .contains(novakBmw, novakPassat)
                .doesNotContain(svobodovaBmw);
    }

    @Test
    @DisplayName("filtry se promítají do countSearch (sdílený WhereClause) a kombinují se")
    void vehicleAndCustomerFilters_narrowCountAndCombine() {
        createOrderFor(1L, 1L);
        String passatOrder = createOrderFor(1L, 3L);

        OrderSearchParams all = new OrderSearchParams();
        all.setPageSize(500);
        long totalAll = orderService.getPage(all).getTotalElements();

        PagedResponse<OrderDto.ListResponse> filtered = searchByVehicleAndCustomer(3L, 1L);

        assertThat(filtered.getTotalElements())
                .as("countSearch musí filtrovat stejně jako search")
                .isEqualTo(filtered.getContent().size())
                .isLessThan(totalAll);
        assertThat(filtered.getContent())
                .extracting(OrderDto.ListResponse::getOrderNumber)
                .contains(passatOrder);
    }

    @Test
    @DisplayName("kombinace vozidlo + jiný zákazník nevrátí nic (obě podmínky platí zároveň)")
    void vehicleOfOneCustomerWithAnotherCustomerId_findsNothing() {
        createOrderFor(1L, 1L);

        PagedResponse<OrderDto.ListResponse> result = searchByVehicleAndCustomer(1L, 2L);

        assertThat(result.getContent()).isEmpty();
        assertThat(result.getTotalElements()).isZero();
    }
}
