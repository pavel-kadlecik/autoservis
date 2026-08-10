package cz.palo.autoservis.model.converter;

import cz.palo.autoservis.model.domain.warehouse.Product;
import cz.palo.autoservis.model.dto.warehouse.ProductDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Konvertor skladových karet — čistý unit test bez Spring kontextu.
 *
 * <p>Těžiště je odvozený příznak {@code lowStock}: díl je „pod minimem" jen tehdy, když je
 * <strong>hlídaný</strong> ({@code minStockLevel} vyplněný) a zásoba je <strong>ostře nižší</strong>
 * než minimum. Testují se všechny tři podmínky včetně <em>rovnosti</em> — právě ta odhalí
 * záměnu {@code <} za {@code <=}, kterou by test jen s „pod" a „nad" minimem propustil.
 */
class WarehouseProductConverterTest {

    private final WarehouseProductConverter converter = new WarehouseProductConverter();

    // =========================================================================
    // lowStock — hraniční podmínky
    // =========================================================================

    @Test
    @DisplayName("lowStock: zásoba POD minimem → true")
    void lowStock_quantityBelowMinimum_isTrue() {
        Product product = product("2", "5");

        assertThat(converter.toListResponse(product).getLowStock()).isTrue();
    }

    @Test
    @DisplayName("lowStock: zásoba PŘESNĚ na minimu → false (hranice, chytá záměnu < za <=)")
    void lowStock_quantityExactlyAtMinimum_isFalse() {
        Product product = product("5", "5");

        assertThat(converter.toListResponse(product).getLowStock()).isFalse();
    }

    @Test
    @DisplayName("lowStock: zásoba NAD minimem → false")
    void lowStock_quantityAboveMinimum_isFalse() {
        Product product = product("9", "5");

        assertThat(converter.toListResponse(product).getLowStock()).isFalse();
    }

    @Test
    @DisplayName("lowStock: nehlídaný díl (bez minima) není nikdy pod minimem, ani s nulovou zásobou")
    void lowStock_withoutMinStockLevel_isFalse() {
        Product product = product("0", null);

        assertThat(converter.toListResponse(product).getLowStock()).isFalse();
    }

    @Test
    @DisplayName("lowStock: neznámá zásoba (null) → false, ne pád")
    void lowStock_withNullQuantity_isFalse() {
        Product product = product(null, "5");

        assertThat(converter.toListResponse(product).getLowStock()).isFalse();
    }

    @Test
    @DisplayName("lowStock: srovnává se hodnotou, ne měřítkem — 2.0 pod 5 je stále pod minimem")
    void lowStock_comparesByValueNotScale() {
        Product product = product("2.000", "5.00");

        assertThat(converter.toListResponse(product).getLowStock()).isTrue();
    }

    // =========================================================================
    // toListResponse / toDetailResponse
    // =========================================================================

    @Test
    @DisplayName("toListResponse přenese všechna pole karty")
    void toListResponse_mapsAllFields() {
        Product product = product("12", "5");
        product.setId(8L);
        product.setSku("OF-1234");
        product.setName("Olejový filtr");
        product.setManufacturer("Mann");
        product.setManufacturerPartNumber("W 712/75");
        product.setVariant("krátký");
        product.setUnit("ks");
        product.setSalePrice(new BigDecimal("199.00"));
        product.setDefaultVatRate(21);
        product.setActive(true);

        ProductDto.ListResponse response = converter.toListResponse(product);

        assertThat(response.getId()).isEqualTo(8L);
        assertThat(response.getSku()).isEqualTo("OF-1234");
        assertThat(response.getName()).isEqualTo("Olejový filtr");
        assertThat(response.getManufacturer()).isEqualTo("Mann");
        assertThat(response.getManufacturerPartNumber()).isEqualTo("W 712/75");
        assertThat(response.getVariant()).isEqualTo("krátký");
        assertThat(response.getUnit()).isEqualTo("ks");
        assertThat(response.getQuantityOnHand()).isEqualByComparingTo("12");
        assertThat(response.getSalePrice()).isEqualByComparingTo("199.00");
        assertThat(response.getMinStockLevel()).isEqualByComparingTo("5");
        assertThat(response.getDefaultVatRate()).isEqualTo(21);
        assertThat(response.getActive()).isTrue();
        assertThat(response.getLowStock()).isFalse();
    }

    @Test
    @DisplayName("toListResponse(null) → null")
    void toListResponse_null_returnsNull() {
        assertThat(converter.toListResponse(null)).isNull();
    }

    @Test
    @DisplayName("toListResponses zachová pořadí a spočítá lowStock pro každý řádek zvlášť")
    void toListResponses_mapsRowsInOrder() {
        Product low = product("1", "5");
        low.setId(1L);
        low.setName("Pod minimem");

        Product ok = product("50", "5");
        ok.setId(2L);
        ok.setName("Dost skladem");

        List<ProductDto.ListResponse> result = converter.toListResponses(List.of(low, ok));

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getName()).isEqualTo("Pod minimem");
        assertThat(result.get(0).getLowStock()).isTrue();
        assertThat(result.get(1).getName()).isEqualTo("Dost skladem");
        assertThat(result.get(1).getLowStock()).isFalse();
    }

    @Test
    @DisplayName("toDetailResponse připojí předpřipravené šarže, pohyby a rezervace")
    void toDetailResponse_attachesBatchesAndMovements() {
        Product product = product("12", "5");
        product.setId(8L);
        product.setSku("OF-1234");
        product.setName("Olejový filtr");
        product.setNote("skladem u dveří");
        product.setActive(true);
        product.setCreatedAt(OffsetDateTime.parse("2026-01-02T10:15:30Z"));
        product.setUpdatedAt(OffsetDateTime.parse("2026-07-01T08:00:00Z"));

        ProductDto.BatchResponse batch = ProductDto.BatchResponse.builder().batchId(31L).build();
        ProductDto.MovementResponse movement = ProductDto.MovementResponse.builder().id(41L).build();
        ProductDto.ReservationResponse reservation = ProductDto.ReservationResponse.builder()
                .orderId(51L).orderNumber("ZAK-2026-0001").build();

        ProductDto.DetailResponse response =
                converter.toDetailResponse(product, List.of(batch), List.of(movement), List.of(reservation));

        assertThat(response.getId()).isEqualTo(8L);
        assertThat(response.getSku()).isEqualTo("OF-1234");
        assertThat(response.getNote()).isEqualTo("skladem u dveří");
        assertThat(response.getQuantityOnHand()).isEqualByComparingTo("12");
        assertThat(response.getLowStock()).isFalse();
        assertThat(response.getCreatedAt()).isEqualTo(OffsetDateTime.parse("2026-01-02T10:15:30Z"));
        assertThat(response.getUpdatedAt()).isEqualTo(OffsetDateTime.parse("2026-07-01T08:00:00Z"));
        assertThat(response.getBatches()).hasSize(1);
        assertThat(response.getBatches().getFirst().getBatchId()).isEqualTo(31L);
        assertThat(response.getMovements()).hasSize(1);
        assertThat(response.getMovements().getFirst().getId()).isEqualTo(41L);
        assertThat(response.getReservations()).hasSize(1);
        assertThat(response.getReservations().getFirst().getOrderNumber()).isEqualTo("ZAK-2026-0001");
    }

    @Test
    @DisplayName("toDetailResponse(null produkt) → null")
    void toDetailResponse_nullProduct_returnsNull() {
        assertThat(converter.toDetailResponse(null, List.of(), List.of(), List.of())).isNull();
    }

    // =========================================================================
    // toDomain / applyUpdate
    // =========================================================================

    @Test
    @DisplayName("toDomain přenese pole a založí kartu jako aktivní; zásobu nechá na DB")
    void toDomain_mapsFieldsAndDefaultsToActive() {
        ProductDto.CreateRequest request = new ProductDto.CreateRequest();
        request.setSku("OF-1234");
        request.setName("Olejový filtr");
        request.setManufacturer("Mann");
        request.setManufacturerPartNumber("W 712/75");
        request.setVariant("krátký");
        request.setNote("skladem u dveří");
        request.setUnit("ks");
        request.setDefaultVatRate(21);
        request.setSalePrice(new BigDecimal("199.00"));
        request.setMinStockLevel(new BigDecimal("5"));

        Product result = converter.toDomain(request);

        assertThat(result.getActive()).isTrue();
        assertThat(result.getSku()).isEqualTo("OF-1234");
        assertThat(result.getName()).isEqualTo("Olejový filtr");
        assertThat(result.getManufacturer()).isEqualTo("Mann");
        assertThat(result.getManufacturerPartNumber()).isEqualTo("W 712/75");
        assertThat(result.getVariant()).isEqualTo("krátký");
        assertThat(result.getNote()).isEqualTo("skladem u dveří");
        assertThat(result.getUnit()).isEqualTo("ks");
        assertThat(result.getDefaultVatRate()).isEqualTo(21);
        assertThat(result.getSalePrice()).isEqualByComparingTo("199.00");
        assertThat(result.getMinStockLevel()).isEqualByComparingTo("5");

        assertThat(result.getId()).isNull();
        assertThat(result.getQuantityOnHand()).as("zásobu naplní až pohyby (DB default 0)").isNull();
    }

    @Test
    @DisplayName("toDomain(null) → null")
    void toDomain_null_returnsNull() {
        assertThat(converter.toDomain(null)).isNull();
    }

    @Test
    @DisplayName("applyUpdate přepíše editovatelná pole, ale nesahá na zásobu, aktivitu ani id")
    void applyUpdate_doesNotTouchStockOrActivity() {
        Product existing = product("12", "5");
        existing.setId(8L);
        existing.setSku("OF-1234");
        existing.setName("Olejový filtr");
        existing.setActive(true);

        ProductDto.UpdateRequest request = new ProductDto.UpdateRequest();
        request.setSku("OF-9999");
        request.setName("Olejový filtr (nový)");
        request.setManufacturer("Bosch");
        request.setManufacturerPartNumber("F 026 407 006");
        request.setVariant("dlouhý");
        request.setNote("přesunuto");
        request.setUnit("bal");
        request.setDefaultVatRate(12);
        request.setSalePrice(new BigDecimal("249.00"));
        request.setMinStockLevel(new BigDecimal("8"));

        Product result = converter.applyUpdate(existing, request);

        assertThat(result).as("mutace probíhá na místě, vrací se tentýž objekt").isSameAs(existing);
        assertThat(existing.getSku()).isEqualTo("OF-9999");
        assertThat(existing.getName()).isEqualTo("Olejový filtr (nový)");
        assertThat(existing.getManufacturer()).isEqualTo("Bosch");
        assertThat(existing.getManufacturerPartNumber()).isEqualTo("F 026 407 006");
        assertThat(existing.getVariant()).isEqualTo("dlouhý");
        assertThat(existing.getNote()).isEqualTo("přesunuto");
        assertThat(existing.getUnit()).isEqualTo("bal");
        assertThat(existing.getDefaultVatRate()).isEqualTo(12);
        assertThat(existing.getSalePrice()).isEqualByComparingTo("249.00");
        assertThat(existing.getMinStockLevel()).isEqualByComparingTo("8");

        assertThat(existing.getId()).isEqualTo(8L);
        assertThat(existing.getQuantityOnHand()).as("zásobu mění jen pohyby").isEqualByComparingTo("12");
        assertThat(existing.getActive()).as("aktivitu mění jen deactivate/activate").isTrue();
    }

    @Test
    @DisplayName("applyUpdate vrací null, chybí-li kterýkoli z argumentů")
    void applyUpdate_nullArguments_returnNull() {
        assertThat(converter.applyUpdate(null, new ProductDto.UpdateRequest())).isNull();
        assertThat(converter.applyUpdate(product("1", "5"), null)).isNull();
    }

    private static Product product(String quantityOnHand, String minStockLevel) {
        return Product.builder()
                .sku("OF-1234")
                .name("Olejový filtr")
                .unit("ks")
                .active(true)
                .quantityOnHand(quantityOnHand == null ? null : new BigDecimal(quantityOnHand))
                .minStockLevel(minStockLevel == null ? null : new BigDecimal(minStockLevel))
                .build();
    }
}
