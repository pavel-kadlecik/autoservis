package cz.palo.autoservis.service;

import cz.palo.autoservis.AbstractIntegrationTest;
import cz.palo.autoservis.exception.BusinessRuleException;
import cz.palo.autoservis.mapper.OrderItemMapper;
import cz.palo.autoservis.mapper.OrderMapper;
import cz.palo.autoservis.model.domain.order.Order;
import cz.palo.autoservis.model.domain.order.OrderItem;
import cz.palo.autoservis.model.dto.billing.InvoiceDto;
import cz.palo.autoservis.model.dto.order.OrderItemDto;
import cz.palo.autoservis.model.enums.InvoiceStatus;
import cz.palo.autoservis.model.enums.OrderItemType;
import cz.palo.autoservis.model.enums.PaymentMethod;
import cz.palo.autoservis.model.enums.OrderStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;

import static cz.palo.autoservis.service.InvoiceIssuing.issueWithNextNumber;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pokrývá {@code OrderItemServiceImpl.requireOrderNotInvoiced} zavedený pro V2
 * (analyza-2026-07): jakmile má zakázka fakturu v jakémkoli stavu kromě
 * CANCELLED, její položky jsou jen ke čtení — faktura je snímek pořízený při
 * vytvoření a nesmí se poté od zakázky tiše rozejít.
 *
 * <p>{@code @Transactional} — každý test běží v transakci, která se na konci
 * rollbackne, takže DB zůstává čistá bez ohledu na pořadí testů (viz
 * {@code CustomerServiceTest} / {@code InvoiceStatusTransitionTest}).
 */
@Transactional
class OrderItemInvoiceLockTest extends AbstractIntegrationTest {

    @Autowired
    private OrderItemService orderItemService;

    @Autowired
    private InvoiceService invoiceService;

    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private OrderItemMapper orderItemMapper;

    private Long orderId;
    private Long itemId;

    /**
     * Seed data (V8/V13/V16) mají položky jen u zakázek 1–3 a ty už faktury mají
     * (V16). Každý test si proto založí vlastní zakázku + položku pro zákazníka 1
     * (seed, má BILLING adresu s id=2) — stejný vzor jako
     * {@code InvoiceStatusTransitionTest}.
     */
    @BeforeEach
    void createOrderWithItem() {
        Order order = Order.builder()
                .receivedAt(LocalDate.now())
                .customerId(1L)
                .vehicleId(1L)
                .description("V2 test — zámek zakázky po fakturaci")
                .estimatedPrice(new BigDecimal("1000"))
                .createdBy(1L)
                .build();
        orderMapper.insert(order);
        orderId = order.getId();

        OrderItem item = OrderItem.builder()
                .orderId(orderId)
                .itemType(OrderItemType.LABOR)
                .name("Testovací položka")
                .quantity(BigDecimal.ONE)
                .unit("hod")
                .unitPrice(new BigDecimal("500"))
                .vatRate((short) 21)
                .position((short) 1)
                .createdBy(1L)
                .build();
        orderItemMapper.insert(item);
        itemId = item.getId();
    }

    @Test
    @DisplayName("před fakturací: create/update/delete položky fungují")
    void mutations_beforeInvoice_succeed() {
        OrderItemDto.Response created = orderItemService.create(orderId, buildCreateRequest(), 1L);
        assertThat(created.getId()).isNotNull();

        OrderItemDto.Response updated = orderItemService.update(itemId, buildUpdateRequest("Přejmenovaná položka"), 1L);
        assertThat(updated.getName()).isEqualTo("Přejmenovaná položka");

        orderItemService.delete(created.getId(), 1L);
        assertThat(orderItemMapper.findById(created.getId())).isEmpty();
    }

    @Test
    @DisplayName("po vytvoření faktury (DRAFT): create/update/delete vyhodí ORDER_LOCKED_BY_INVOICE")
    void mutations_afterInvoiceCreated_throwOrderLockedByInvoice() {
        InvoiceDto.DetailResponse invoice = createInvoiceForOrder();
        assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.DRAFT);

        assertThatThrownBy(() -> orderItemService.create(orderId, buildCreateRequest(), 1L))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(ex -> assertThat(((BusinessRuleException) ex).getRuleCode())
                        .isEqualTo("ORDER_LOCKED_BY_INVOICE"));

        assertThatThrownBy(() -> orderItemService.update(itemId, buildUpdateRequest("Pokus o změnu"), 1L))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(ex -> assertThat(((BusinessRuleException) ex).getRuleCode())
                        .isEqualTo("ORDER_LOCKED_BY_INVOICE"));

        assertThatThrownBy(() -> orderItemService.delete(itemId, 1L))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(ex -> assertThat(((BusinessRuleException) ex).getRuleCode())
                        .isEqualTo("ORDER_LOCKED_BY_INVOICE"));
    }

    @Test
    @DisplayName("hláška u konceptu mluví o „konceptu faktury“, ne o „faktuře null“ (02/F-7)")
    void lockMessage_forDraftInvoice_namesTheDraft() {
        // Koncept číslo nemá (dostane ho až při vystavení), takže hlášku musí složit
        // Invoice.describe() — prosté zřetězení s invoiceNumber tu obsluze kdysi tvrdilo
        // „Zakázka už má fakturu null".
        InvoiceDto.DetailResponse invoice = createInvoiceForOrder();
        assertThat(invoice.getInvoiceNumber()).as("koncept číslo nemá").isNull();

        assertThatThrownBy(() -> orderItemService.update(itemId, buildUpdateRequest("Pokus o změnu"), 1L))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(ex -> assertThat(ex.getMessage())
                        .contains("už má koncept faktury")
                        .doesNotContain("null"));
    }

    @Test
    @DisplayName("hláška u vystavené faktury nese její číslo")
    void lockMessage_forIssuedInvoice_containsInvoiceNumber() {
        InvoiceDto.DetailResponse invoice = createInvoiceForOrder();
        String invoiceNumber = issueWithNextNumber(invoiceService, invoice.getId(), 1L).getInvoiceNumber();

        assertThatThrownBy(() -> orderItemService.update(itemId, buildUpdateRequest("Pokus o změnu"), 1L))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(ex -> assertThat(ex.getMessage()).contains("už má fakturu " + invoiceNumber));
    }

    @Test
    @DisplayName("po smazání konceptu faktury: create položky znovu projde")
    void mutations_afterInvoiceDeleted_succeedAgain() {
        InvoiceDto.DetailResponse invoice = createInvoiceForOrder();

        invoiceService.delete(invoice.getId(), 1L); // koncept se maže, ne stornuje

        OrderItemDto.Response created = orderItemService.create(orderId, buildCreateRequest(), 1L);
        assertThat(created.getId()).isNotNull();
    }

    // =========================================================================
    // Privátní pomocníci
    // =========================================================================

    private InvoiceDto.DetailResponse createInvoiceForOrder() {
        InvoiceDto.CreateRequest createRequest = new InvoiceDto.CreateRequest();
        createRequest.setOrderId(orderId);
        createRequest.setBillingAddressId(2L); // seed: customer.addresses id=2, BILLING, customer 1
        createRequest.setIssueDate(LocalDate.now());
        createRequest.setDueDate(LocalDate.now().plusDays(14));
        createRequest.setTaxableSupplyDate(LocalDate.now());
        createRequest.setPaymentMethod(PaymentMethod.CARD);

        markCompleted(orderId);
        return invoiceService.createFromOrder(createRequest, 1L);
    }

    private OrderItemDto.CreateRequest buildCreateRequest() {
        OrderItemDto.CreateRequest request = new OrderItemDto.CreateRequest();
        request.setItemType(OrderItemType.MATERIAL);
        request.setName("Nová položka");
        request.setQuantity(BigDecimal.ONE);
        request.setUnit("ks");
        request.setPurchasePrice(new BigDecimal("100"));
        request.setUnitPrice(new BigDecimal("150"));
        request.setVatRate((short) 21);
        request.setPosition((short) 2);
        return request;
    }

    private OrderItemDto.UpdateRequest buildUpdateRequest(String name) {
        OrderItemDto.UpdateRequest request = new OrderItemDto.UpdateRequest();
        request.setItemType(OrderItemType.LABOR);
        request.setName(name);
        request.setQuantity(BigDecimal.ONE);
        request.setUnit("hod");
        request.setUnitPrice(new BigDecimal("500"));
        request.setVatRate((short) 21);
        request.setPosition((short) 1);
        return request;
    }

    /**
     * Fakturovat lze až dokončenou zakázku (rozhodnutí uživatele 2026-08-05). Setup ji tam
     * přepne <strong>přímo mapperem</strong> — obchází tím branku ve službě schválně, protože
     * tady jde o přípravu dat, ne o testovanou cestu.
     */
    private void markCompleted(Long id) {
        orderMapper.findById(id).ifPresent(o -> {
            o.setStatus(OrderStatus.COMPLETED);
            orderMapper.update(o);
        });
    }
}
