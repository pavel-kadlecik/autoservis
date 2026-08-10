package cz.palo.autoservis.model.converter;

import cz.palo.autoservis.model.domain.billing.InvoiceItem;
import cz.palo.autoservis.model.domain.order.OrderItem;
import cz.palo.autoservis.model.dto.billing.InvoiceItemDto;
import cz.palo.autoservis.model.enums.OrderItemType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Konvertor položek faktury — čistý unit test bez Spring kontextu.
 *
 * <p>Klíčová metoda je {@code fromOrderItem}: faktura je <strong>snapshot</strong> položek
 * zakázky z okamžiku vystavení. Když by se sem nepřenesla cena nebo sazba DPH, faktura by
 * seděla se zakázkou jen náhodou — proto se tvrdí každé pole zvlášť, ne jen „něco vzniklo".
 */
class InvoiceItemConverterTest {

    private final InvoiceItemConverter converter = new InvoiceItemConverter();

    @Test
    @DisplayName("fromOrderItem přenese snapshot položky zakázky včetně vazby orderItemId")
    void fromOrderItem_copiesSnapshotFields() {
        OrderItem orderItem = new OrderItem();
        orderItem.setId(3L);
        orderItem.setOrderId(5L);
        orderItem.setItemType(OrderItemType.MATERIAL);
        orderItem.setName("Olejový filtr");
        orderItem.setQuantity(new BigDecimal("2"));
        orderItem.setUnit("ks");
        orderItem.setUnitPrice(new BigDecimal("199.00"));
        orderItem.setVatRate((short) 21);
        orderItem.setPosition((short) 3);

        InvoiceItem result = converter.fromOrderItem(orderItem);

        assertThat(result.getOrderItemId()).as("vazba na zdrojovou položku zakázky").isEqualTo(3L);
        assertThat(result.getName()).isEqualTo("Olejový filtr");
        assertThat(result.getQuantity()).isEqualByComparingTo("2");
        assertThat(result.getUnit()).isEqualTo("ks");
        assertThat(result.getUnitPrice()).isEqualByComparingTo("199.00");
        assertThat(result.getVatRate()).isEqualTo((short) 21);
        assertThat(result.getPosition()).isEqualTo((short) 3);
    }

    @Test
    @DisplayName("fromOrderItem nenastaví invoiceId ani id — doplní je service při vkládání")
    void fromOrderItem_leavesInvoiceIdEmpty() {
        OrderItem orderItem = new OrderItem();
        orderItem.setId(3L);
        orderItem.setName("Práce mechanika");

        InvoiceItem result = converter.fromOrderItem(orderItem);

        assertThat(result.getId()).isNull();
        assertThat(result.getInvoiceId()).isNull();
    }

    @Test
    @DisplayName("fromOrderItem(null) → null")
    void fromOrderItem_null_returnsNull() {
        assertThat(converter.fromOrderItem(null)).isNull();
    }

    @Test
    @DisplayName("toDomain přenese pole CreateRequest, invoiceId nechá na service")
    void toDomain_mapsCreateRequestFields() {
        InvoiceItemDto.CreateRequest request = new InvoiceItemDto.CreateRequest();
        request.setOrderItemId(3L);
        request.setName("Doprava");
        request.setQuantity(new BigDecimal("1"));
        request.setUnit("ks");
        request.setUnitPrice(new BigDecimal("350.00"));
        request.setVatRate((short) 21);
        request.setPosition((short) 9);

        InvoiceItem result = converter.toDomain(request);

        assertThat(result.getOrderItemId()).isEqualTo(3L);
        assertThat(result.getName()).isEqualTo("Doprava");
        assertThat(result.getQuantity()).isEqualByComparingTo("1");
        assertThat(result.getUnit()).isEqualTo("ks");
        assertThat(result.getUnitPrice()).isEqualByComparingTo("350.00");
        assertThat(result.getVatRate()).isEqualTo((short) 21);
        assertThat(result.getPosition()).isEqualTo((short) 9);
        assertThat(result.getInvoiceId()).isNull();
    }

    @Test
    @DisplayName("toDomain(null) → null")
    void toDomain_null_returnsNull() {
        assertThat(converter.toDomain(null)).isNull();
    }

    @Test
    @DisplayName("applyUpdate přepíše editovatelná pole, nesahá na invoiceId ani id")
    void applyUpdate_overwritesEditableFieldsOnly() {
        InvoiceItem existing = invoiceItem();
        existing.setId(4L);
        existing.setInvoiceId(2L);
        existing.setOrderItemId(3L);

        InvoiceItemDto.UpdateRequest request = new InvoiceItemDto.UpdateRequest();
        request.setName("Olejový filtr — oprava názvu");
        request.setQuantity(new BigDecimal("3"));
        request.setUnit("bal");
        request.setUnitPrice(new BigDecimal("210.00"));
        request.setVatRate((short) 12);
        request.setPosition((short) 1);

        InvoiceItem result = converter.applyUpdate(existing, request);

        assertThat(result).as("mutace probíhá na místě, vrací se tentýž objekt").isSameAs(existing);
        assertThat(existing.getName()).isEqualTo("Olejový filtr — oprava názvu");
        assertThat(existing.getQuantity()).isEqualByComparingTo("3");
        assertThat(existing.getUnit()).isEqualTo("bal");
        assertThat(existing.getUnitPrice()).isEqualByComparingTo("210.00");
        assertThat(existing.getVatRate()).isEqualTo((short) 12);
        assertThat(existing.getPosition()).isEqualTo((short) 1);

        assertThat(existing.getId()).isEqualTo(4L);
        assertThat(existing.getInvoiceId()).isEqualTo(2L);
        assertThat(existing.getOrderItemId()).isEqualTo(3L);
    }

    @Test
    @DisplayName("applyUpdate vrací null, chybí-li kterýkoli z argumentů")
    void applyUpdate_nullArguments_returnNull() {
        assertThat(converter.applyUpdate(null, new InvoiceItemDto.UpdateRequest())).isNull();
        assertThat(converter.applyUpdate(invoiceItem(), null)).isNull();
    }

    @Test
    @DisplayName("toResponse přenese i rozpad ceny spočítaný v SQL (net/vat/gross)")
    void toResponse_mapsPrecalculatedTotals() {
        InvoiceItem item = invoiceItem();
        item.setId(4L);
        item.setInvoiceId(2L);
        item.setOrderItemId(3L);
        item.setNet(new BigDecimal("398.00"));
        item.setVat(new BigDecimal("83.58"));
        item.setGross(new BigDecimal("481.58"));

        InvoiceItemDto.Response response = converter.toResponse(item);

        assertThat(response.getId()).isEqualTo(4L);
        assertThat(response.getInvoiceId()).isEqualTo(2L);
        assertThat(response.getOrderItemId()).isEqualTo(3L);
        assertThat(response.getName()).isEqualTo("Olejový filtr");
        assertThat(response.getQuantity()).isEqualByComparingTo("2");
        assertThat(response.getUnit()).isEqualTo("ks");
        assertThat(response.getUnitPrice()).isEqualByComparingTo("199.00");
        assertThat(response.getVatRate()).isEqualTo((short) 21);
        assertThat(response.getPosition()).isEqualTo((short) 3);
        assertThat(response.getNet()).isEqualByComparingTo("398.00");
        assertThat(response.getVat()).isEqualByComparingTo("83.58");
        assertThat(response.getGross()).isEqualByComparingTo("481.58");
    }

    @Test
    @DisplayName("toResponse(null) → null")
    void toResponse_null_returnsNull() {
        assertThat(converter.toResponse(null)).isNull();
    }

    @Test
    @DisplayName("toListResponses zachová pořadí řádků dokladu")
    void toListResponses_mapsRowsInOrder() {
        InvoiceItem first = invoiceItem();
        first.setId(1L);
        first.setName("Práce mechanika");
        first.setPosition((short) 1);

        InvoiceItem second = invoiceItem();
        second.setId(2L);
        second.setPosition((short) 2);

        List<InvoiceItemDto.Response> result = converter.toListResponses(List.of(first, second));

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getName()).isEqualTo("Práce mechanika");
        assertThat(result.get(1).getName()).isEqualTo("Olejový filtr");
    }

    private static InvoiceItem invoiceItem() {
        InvoiceItem item = new InvoiceItem();
        item.setName("Olejový filtr");
        item.setQuantity(new BigDecimal("2"));
        item.setUnit("ks");
        item.setUnitPrice(new BigDecimal("199.00"));
        item.setVatRate((short) 21);
        item.setPosition((short) 3);
        return item;
    }
}
