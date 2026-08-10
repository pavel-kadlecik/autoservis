package cz.palo.autoservis.service;

import cz.palo.autoservis.AbstractIntegrationTest;
import cz.palo.autoservis.exception.BusinessRuleException;
import cz.palo.autoservis.exception.ResourceNotFoundException;
import cz.palo.autoservis.mapper.OrderMapper;
import cz.palo.autoservis.model.domain.order.Order;
import cz.palo.autoservis.model.dto.employee.EmployeeDto;
import cz.palo.autoservis.model.dto.order.OrderItemDto;
import cz.palo.autoservis.model.enums.OrderItemType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Snapshot nákladu práce (D-3/D-6): přiřazení mechanika k LABOR položce zapíše jeho
 * <em>aktuální</em> hodinovou sazbu do {@code purchase_price} a tento snímek je od té chvíle
 * zmražený — pozdější změna sazby zaměstnance historickou položku nezmění.
 *
 * <p>Seed (V58): zaměstnanec #1 Petr Mechanik, {@code hourly_rate = 550.00}, napojený na login
 * {@code mechanic} (user 3).
 */
@Transactional
class LaborCostSnapshotTest extends AbstractIntegrationTest {

    private static final long USER_ID = 1L;
    private static final long EMPLOYEE_ID = 1L;           // Petr Mechanik, sazba 550.00
    private static final long MECHANIC_USER_ID = 3L;

    @Autowired
    private OrderItemService orderItemService;

    @Autowired
    private EmployeeService employeeService;

    @Autowired
    private OrderMapper orderMapper;

    private Long orderId;

    @BeforeEach
    void createOrder() {
        Order order = Order.builder()
                .receivedAt(LocalDate.now())
                .customerId(1L).vehicleId(1L)
                .description("Zakázka pro test snapshotu sazby")
                .estimatedPrice(new BigDecimal("1000")).createdBy(USER_ID)
                .build();
        orderMapper.insert(order);
        orderId = order.getId();
    }

    // =========================================================================
    // Snapshot sazby (D-3, D-6)
    // =========================================================================

    @Test
    @DisplayName("přiřazení mechanika k LABOR položce bez ceny zapíše jeho sazbu do purchase_price (D-6 fallback)")
    void assigningEmployee_snapshotsHourlyRate() {
        OrderItemDto.Response created = orderItemService.create(orderId, laborWithEmployee(null, EMPLOYEE_ID), USER_ID);

        assertThat(created.getEmployeeId()).isEqualTo(EMPLOYEE_ID);
        assertThat(created.getEmployeeName()).isEqualTo("Petr Mechanik");
        assertThat(created.getPurchasePrice()).isEqualByComparingTo("550.00");
    }

    @Test
    @DisplayName("výslovně poslaná purchase_price se snapshotem nepřepíše (D-6 — jen když je prázdná)")
    void explicitPurchasePrice_isKept() {
        OrderItemDto.Response created = orderItemService.create(
                orderId, laborWithEmployee(new BigDecimal("700.00"), EMPLOYEE_ID), USER_ID);

        assertThat(created.getPurchasePrice()).isEqualByComparingTo("700.00");
    }

    @Test
    @DisplayName("pozdější změna sazby zaměstnance NEZMĚNÍ purchase_price historické položky (D-3)")
    void laterRateChange_doesNotRewriteHistory() {
        OrderItemDto.Response item = orderItemService.create(orderId, laborWithEmployee(null, EMPLOYEE_ID), USER_ID);
        assertThat(item.getPurchasePrice()).isEqualByComparingTo("550.00");

        // sazba mechanika se změní z 550 na 999
        EmployeeDto.DetailResponse before = employeeService.getById(EMPLOYEE_ID);
        EmployeeDto.UpdateRequest req = EmployeeDto.UpdateRequest.builder()
                .userId(MECHANIC_USER_ID)
                .firstName(before.getFirstName())
                .lastName(before.getLastName())
                .position(before.getPosition())
                .hourlyRate(new BigDecimal("999.00"))
                .hiredAt(before.getHiredAt())
                .leftAt(before.getLeftAt())
                .build();
        EmployeeDto.DetailResponse after = employeeService.update(EMPLOYEE_ID, req, USER_ID);
        assertThat(after.getHourlyRate()).isEqualByComparingTo("999.00");

        // historická položka drží původní snímek 550, ne novou sazbu
        OrderItemDto.Response reloaded = orderItemService.getById(item.getId());
        assertThat(reloaded.getPurchasePrice()).isEqualByComparingTo("550.00");
    }

    @Test
    @DisplayName("přiřazení mechanika při update (dosnímkování) doplní sazbu do prázdné purchase_price")
    void assigningEmployeeOnUpdate_snapshotsRate() {
        OrderItemDto.Response created = orderItemService.create(orderId, laborWithEmployee(null, null), USER_ID);
        assertThat(created.getEmployeeId()).isNull();

        OrderItemDto.UpdateRequest update = new OrderItemDto.UpdateRequest();
        update.setItemType(OrderItemType.LABOR);
        update.setName(created.getName());
        update.setQuantity(created.getQuantity());
        update.setUnit(created.getUnit());
        update.setUnitPrice(created.getUnitPrice());
        update.setVatRate(created.getVatRate());
        update.setPosition(created.getPosition());
        update.setEmployeeId(EMPLOYEE_ID);   // purchasePrice nechána null → dosnímkovat

        OrderItemDto.Response updated = orderItemService.update(created.getId(), update, USER_ID);

        assertThat(updated.getEmployeeId()).isEqualTo(EMPLOYEE_ID);
        assertThat(updated.getPurchasePrice()).isEqualByComparingTo("550.00");
    }

    // =========================================================================
    // CHECK: employee_id jen u LABOR (D-2) → 422
    // =========================================================================

    @Test
    @DisplayName("mechanik na položce MATERIAL → 422 EMPLOYEE_ONLY_ON_LABOR (garance CHECK)")
    void employeeOnNonLabor_throws() {
        OrderItemDto.CreateRequest req = laborWithEmployee(null, EMPLOYEE_ID);
        req.setItemType(OrderItemType.MATERIAL);
        req.setUnit("ks");

        assertThatThrownBy(() -> orderItemService.create(orderId, req, USER_ID))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(ex -> assertThat(((BusinessRuleException) ex).getRuleCode())
                        .isEqualTo("EMPLOYEE_ONLY_ON_LABOR"));
    }

    @Test
    @DisplayName("neexistující mechanik na LABOR položce → 404")
    void unknownEmployee_throws() {
        assertThatThrownBy(() -> orderItemService.create(orderId, laborWithEmployee(null, 999_999L), USER_ID))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("u práce účtované po KUSECH se sazba nepředvyplní — hodinová sazba není cena za kus")
    void pieceRatedLabor_doesNotSnapshotHourlyRate() {
        // Práci lze od 2026-08-03 účtovat paušálem za úkon (rozhodnutí uživatele). Dosadit
        // do nákladu 550 Kč/hod jako cenu za kus by bylo tiše špatné číslo v marži —
        // prázdné pole si obsluha všimne, špatně dosazeného ne.
        OrderItemDto.CreateRequest request = laborWithEmployee(null, EMPLOYEE_ID);
        request.setUnit("ks");

        OrderItemDto.Response created = orderItemService.create(orderId, request, USER_ID);

        assertThat(created.getEmployeeId())
                .as("mechanik se přiřadí i u paušálu — kdo práci odvedl, se eviduje dál")
                .isEqualTo(EMPLOYEE_ID);
        assertThat(created.getPurchasePrice()).isNull();
    }

    @Test
    @DisplayName("výslovná cena u práce po kusech se uloží beze změny")
    void pieceRatedLabor_keepsExplicitPurchasePrice() {
        OrderItemDto.CreateRequest request = laborWithEmployee(new BigDecimal("300.00"), EMPLOYEE_ID);
        request.setUnit("ks");

        assertThat(orderItemService.create(orderId, request, USER_ID).getPurchasePrice())
                .isEqualByComparingTo("300.00");
    }

    // =========================================================================
    // helpers
    // =========================================================================

    private static OrderItemDto.CreateRequest laborWithEmployee(BigDecimal purchasePrice, Long employeeId) {
        OrderItemDto.CreateRequest request = new OrderItemDto.CreateRequest();
        request.setItemType(OrderItemType.LABOR);
        request.setName("Výměna oleje");
        request.setQuantity(new BigDecimal("2"));
        request.setUnit("hod");
        request.setPurchasePrice(purchasePrice);
        request.setUnitPrice(new BigDecimal("800.00"));
        request.setVatRate((short) 21);
        request.setPosition((short) 1);
        request.setEmployeeId(employeeId);
        return request;
    }
}
