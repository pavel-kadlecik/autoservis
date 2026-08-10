package cz.palo.autoservis.model.converter;

import cz.palo.autoservis.model.domain.order.OrderItem;
import cz.palo.autoservis.model.dto.order.OrderItemDto;
import cz.palo.autoservis.model.enums.OrderItemType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Konvertor položek zakázky — čistý unit test bez Spring kontextu.
 *
 * <p>Nejcennější část je {@code applyUpdate}: položka <strong>naskladněná ze skladu</strong>
 * (má {@code goodsReceiptItemId}) má zamčené množství, typ, jednotku, sazbu DPH i nákupní cenu —
 * jinak by se rozjel sklad proti zakázce. Testují se proto <em>obě</em> větve: skladová položka
 * zamčená pole neměnní, ruční položka je změní.
 */
class OrderItemConverterTest {

    private final OrderItemConverter converter = new OrderItemConverter();

    // =========================================================================
    // toDomain
    // =========================================================================

    @Test
    @DisplayName("toDomain přenese pole CreateRequest")
    void toDomain_mapsCreateRequestFields() {
        OrderItemDto.CreateRequest request = new OrderItemDto.CreateRequest();
        request.setItemType(OrderItemType.MATERIAL);
        request.setName("Olejový filtr");
        request.setQuantity(new BigDecimal("2"));
        request.setUnit("ks");
        request.setPurchasePrice(new BigDecimal("120.00"));
        request.setUnitPrice(new BigDecimal("199.00"));
        request.setVatRate((short) 21);
        request.setPosition((short) 3);
        request.setNote("originál");

        OrderItem result = converter.toDomain(request);

        assertThat(result.getItemType()).isEqualTo(OrderItemType.MATERIAL);
        assertThat(result.getName()).isEqualTo("Olejový filtr");
        assertThat(result.getQuantity()).isEqualByComparingTo("2");
        assertThat(result.getUnit()).isEqualTo("ks");
        assertThat(result.getPurchasePrice()).isEqualByComparingTo("120.00");
        assertThat(result.getUnitPrice()).isEqualByComparingTo("199.00");
        assertThat(result.getVatRate()).isEqualTo((short) 21);
        assertThat(result.getPosition()).isEqualTo((short) 3);
        assertThat(result.getNote()).isEqualTo("originál");
    }

    @Test
    @DisplayName("toDomain nenastaví orderId ani audit — doplní je service")
    void toDomain_leavesOrderIdAndAuditEmpty() {
        OrderItemDto.CreateRequest request = new OrderItemDto.CreateRequest();
        request.setItemType(OrderItemType.LABOR);
        request.setName("Práce mechanika");

        OrderItem result = converter.toDomain(request);

        assertThat(result.getOrderId()).isNull();
        assertThat(result.getId()).isNull();
        assertThat(result.getCreatedBy()).isNull();
        assertThat(result.getGoodsReceiptItemId()).isNull();
    }

    @Test
    @DisplayName("toDomain(null) → null")
    void toDomain_null_returnsNull() {
        assertThat(converter.toDomain(null)).isNull();
    }

    // =========================================================================
    // applyUpdate — obě větve zámku skladové položky
    // =========================================================================

    @Test
    @DisplayName("applyUpdate u RUČNÍ položky změní i množství, typ, jednotku, DPH a nákupní cenu")
    void applyUpdate_manualItem_updatesLockedFieldsToo() {
        OrderItem existing = materialItem();
        existing.setGoodsReceiptItemId(null); // ruční položka

        OrderItem result = converter.applyUpdate(existing, fullUpdateRequest());

        assertThat(result).as("mutace probíhá na místě, vrací se tentýž objekt").isSameAs(existing);

        // volná pole
        assertThat(existing.getName()).isEqualTo("Olejový filtr (jiný)");
        assertThat(existing.getUnitPrice()).isEqualByComparingTo("249.00");
        assertThat(existing.getPosition()).isEqualTo((short) 5);
        assertThat(existing.getNote()).isEqualTo("po úpravě");
        // pole zamčená jen u skladových položek — tady se změnit MUSÍ
        assertThat(existing.getItemType()).isEqualTo(OrderItemType.OTHER_SERVICES);
        assertThat(existing.getQuantity()).isEqualByComparingTo("7");
        assertThat(existing.getUnit()).isEqualTo("hod");
        assertThat(existing.getVatRate()).isEqualTo((short) 12);
        assertThat(existing.getPurchasePrice()).isEqualByComparingTo("150.00");
    }

    @Test
    @DisplayName("applyUpdate u SKLADOVÉ položky změní množství, ostatní zamčená pole ne")
    void applyUpdate_stockItem_keepsLockedFields() {
        OrderItem existing = materialItem();
        existing.setGoodsReceiptItemId(77L); // vazba na šarži = skladová položka

        converter.applyUpdate(existing, fullUpdateRequest());

        // volná pole se změnit smějí
        assertThat(existing.getName()).isEqualTo("Olejový filtr (jiný)");
        assertThat(existing.getUnitPrice()).isEqualByComparingTo("249.00");
        assertThat(existing.getPosition()).isEqualTo((short) 5);
        assertThat(existing.getNote()).isEqualTo("po úpravě");
        // Množství se od V83 měnit SMÍ — dokud je díl jen rezervovaný, mění se pouhý slib;
        // u vydaného dorovná rozdíl protipohyb v OrderItemServiceImpl.syncIssuedQuantity.
        // Do té doby se zadané číslo tiše zahazovalo.
        assertThat(existing.getQuantity()).isEqualByComparingTo("7");
        // zbytek zamčených polí si musí podržet PŮVODNÍ hodnoty — jsou to snímky ze šarže
        assertThat(existing.getItemType()).isEqualTo(OrderItemType.MATERIAL);
        assertThat(existing.getUnit()).isEqualTo("ks");
        assertThat(existing.getVatRate()).isEqualTo((short) 21);
        assertThat(existing.getPurchasePrice()).isEqualByComparingTo("120.00");
    }

    @Test
    @DisplayName("applyUpdate nesahá na orderId, vazbu na šarži ani audit")
    void applyUpdate_doesNotTouchOwnershipFields() {
        OrderItem existing = materialItem();
        existing.setId(3L);
        existing.setOrderId(5L);
        existing.setGoodsReceiptItemId(77L);
        existing.setCreatedBy(9L);

        converter.applyUpdate(existing, fullUpdateRequest());

        assertThat(existing.getId()).isEqualTo(3L);
        assertThat(existing.getOrderId()).isEqualTo(5L);
        assertThat(existing.getGoodsReceiptItemId()).isEqualTo(77L);
        assertThat(existing.getCreatedBy()).isEqualTo(9L);
    }

    @Test
    @DisplayName("applyUpdate vrací null, chybí-li kterýkoli z argumentů")
    void applyUpdate_nullArguments_returnNull() {
        assertThat(converter.applyUpdate(null, new OrderItemDto.UpdateRequest())).isNull();
        assertThat(converter.applyUpdate(materialItem(), null)).isNull();
    }

    // =========================================================================
    // toResponse — příznak fromStock
    // =========================================================================

    @Test
    @DisplayName("toResponse: položka s vazbou na šarži má fromStock = true")
    void toResponse_stockItem_isFlaggedFromStock() {
        OrderItem item = materialItem();
        item.setGoodsReceiptItemId(77L);

        assertThat(converter.toResponse(item).isFromStock()).isTrue();
    }

    @Test
    @DisplayName("toResponse: ruční položka má fromStock = false")
    void toResponse_manualItem_isNotFlaggedFromStock() {
        OrderItem item = materialItem();
        item.setGoodsReceiptItemId(null);

        assertThat(converter.toResponse(item).isFromStock()).isFalse();
    }

    @Test
    @DisplayName("toResponse přenese všechna pole")
    void toResponse_mapsAllFields() {
        OrderItem item = materialItem();
        item.setId(3L);
        item.setOrderId(5L);
        item.setNote("originál");
        item.setCreatedBy(9L);
        item.setCreatedAt(java.time.OffsetDateTime.parse("2026-07-25T07:00:00Z"));
        item.setUpdatedAt(java.time.OffsetDateTime.parse("2026-07-26T07:00:00Z"));

        OrderItemDto.Response response = converter.toResponse(item);

        assertThat(response.getNote()).isEqualTo("originál");
        assertThat(response.getCreatedAt()).isEqualTo(java.time.OffsetDateTime.parse("2026-07-25T07:00:00Z"));
        assertThat(response.getUpdatedAt()).isEqualTo(java.time.OffsetDateTime.parse("2026-07-26T07:00:00Z"));

        assertThat(response.getId()).isEqualTo(3L);
        assertThat(response.getOrderId()).isEqualTo(5L);
        assertThat(response.getItemType()).isEqualTo(OrderItemType.MATERIAL);
        assertThat(response.getName()).isEqualTo("Olejový filtr");
        assertThat(response.getQuantity()).isEqualByComparingTo("2");
        assertThat(response.getUnit()).isEqualTo("ks");
        assertThat(response.getPurchasePrice()).isEqualByComparingTo("120.00");
        assertThat(response.getUnitPrice()).isEqualByComparingTo("199.00");
        assertThat(response.getVatRate()).isEqualTo((short) 21);
        assertThat(response.getPosition()).isEqualTo((short) 3);
        assertThat(response.getCreatedBy()).isEqualTo(9L);
    }

    @Test
    @DisplayName("toResponse(null) → null")
    void toResponse_null_returnsNull() {
        assertThat(converter.toResponse(null)).isNull();
    }

    @Test
    @DisplayName("toListResponses zachová pořadí položek (pozice na dokladu)")
    void toListResponses_mapsRowsInOrder() {
        OrderItem labor = materialItem();
        labor.setId(1L);
        labor.setItemType(OrderItemType.LABOR);
        labor.setName("Práce mechanika");
        labor.setPosition((short) 1);

        OrderItem material = materialItem();
        material.setId(2L);
        material.setPosition((short) 2);

        List<OrderItemDto.Response> result = converter.toListResponses(List.of(labor, material));

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getName()).isEqualTo("Práce mechanika");
        assertThat(result.get(0).getPosition()).isEqualTo((short) 1);
        assertThat(result.get(1).getName()).isEqualTo("Olejový filtr");
        assertThat(result.get(1).getPosition()).isEqualTo((short) 2);
    }

    // =========================================================================
    // Fixtury
    // =========================================================================

    private static OrderItem materialItem() {
        OrderItem item = new OrderItem();
        item.setItemType(OrderItemType.MATERIAL);
        item.setName("Olejový filtr");
        item.setQuantity(new BigDecimal("2"));
        item.setUnit("ks");
        item.setPurchasePrice(new BigDecimal("120.00"));
        item.setUnitPrice(new BigDecimal("199.00"));
        item.setVatRate((short) 21);
        item.setPosition((short) 3);
        return item;
    }

    /** Update, který mění úplně všechna pole — teprve tak se pozná, která zůstala zamčená. */
    private static OrderItemDto.UpdateRequest fullUpdateRequest() {
        OrderItemDto.UpdateRequest request = new OrderItemDto.UpdateRequest();
        request.setName("Olejový filtr (jiný)");
        request.setUnitPrice(new BigDecimal("249.00"));
        request.setPosition((short) 5);
        request.setNote("po úpravě");
        request.setItemType(OrderItemType.OTHER_SERVICES);
        request.setQuantity(new BigDecimal("7"));
        request.setUnit("hod");
        request.setVatRate((short) 12);
        request.setPurchasePrice(new BigDecimal("150.00"));
        return request;
    }
}
