package cz.palo.autoservis.model.converter;

import cz.palo.autoservis.model.domain.warehouse.Supplier;
import cz.palo.autoservis.model.dto.warehouse.SupplierDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Konvertor dodavatelů — čistý unit test bez Spring kontextu. */
class SupplierConverterTest {

    private final SupplierConverter converter = new SupplierConverter();

    @Test
    @DisplayName("toDetailResponse přenese všechna pole včetně bankovního spojení")
    void toDetailResponse_mapsAllFields() {
        Supplier supplier = supplier();
        supplier.setId(4L);
        supplier.setCreatedAt(OffsetDateTime.parse("2026-01-02T10:15:30Z"));
        supplier.setUpdatedAt(OffsetDateTime.parse("2026-07-01T08:00:00Z"));

        SupplierDto.DetailResponse response = converter.toDetailResponse(supplier);

        assertThat(response.getId()).isEqualTo(4L);
        assertThat(response.getName()).isEqualTo("Autodíly s.r.o.");
        assertThat(response.getRegistrationNumber()).isEqualTo("12345678");
        assertThat(response.getVatId()).isEqualTo("CZ12345678");
        assertThat(response.getStreet()).isEqualTo("Skladová 7");
        assertThat(response.getCity()).isEqualTo("Brno");
        assertThat(response.getPostalCode()).isEqualTo("602 00");
        assertThat(response.getCountryCode()).isEqualTo("CZ");
        assertThat(response.getBankAccount()).isEqualTo("123456789/0800");
        assertThat(response.getIban()).isEqualTo("CZ6508000000192000145399");
        assertThat(response.getSwift()).isEqualTo("GIBACZPX");
        assertThat(response.getEmail()).isEqualTo("obchod@autodily.cz");
        assertThat(response.getPhone()).isEqualTo("+420541123456");
        assertThat(response.isActive()).isTrue();
        assertThat(response.getCreatedAt()).isEqualTo(OffsetDateTime.parse("2026-01-02T10:15:30Z"));
        assertThat(response.getUpdatedAt()).isEqualTo(OffsetDateTime.parse("2026-07-01T08:00:00Z"));
    }

    @Test
    @DisplayName("toDetailResponse: deaktivovaný dodavatel má active = false")
    void toDetailResponse_inactiveSupplier_reportsFalse() {
        Supplier supplier = supplier();
        supplier.setActive(false);

        assertThat(converter.toDetailResponse(supplier).isActive()).isFalse();
    }

    @Test
    @DisplayName("toDetailResponse(null) → null")
    void toDetailResponse_null_returnsNull() {
        assertThat(converter.toDetailResponse(null)).isNull();
    }

    @Test
    @DisplayName("applyUpdate přepíše editovatelná pole, ale nesahá na id ani aktivitu")
    void applyUpdate_overwritesEditableFieldsOnly() {
        Supplier existing = supplier();
        existing.setId(4L);
        existing.setActive(true);

        SupplierDto.UpdateRequest request = new SupplierDto.UpdateRequest();
        request.setName("Autodíly a.s.");
        request.setRegistrationNumber("87654321");
        request.setVatId("CZ87654321");
        request.setStreet("Nová 1");
        request.setCity("Praha");
        request.setPostalCode("110 00");
        request.setCountryCode("SK");
        request.setBankAccount("987654321/0300");
        request.setIban("CZ0203000000000123456789");
        request.setSwift("CEKOCZPP");
        request.setEmail("novy@autodily.cz");
        request.setPhone("+420777999888");

        Supplier result = converter.applyUpdate(existing, request);

        assertThat(result).as("mutace probíhá na místě, vrací se tentýž objekt").isSameAs(existing);
        assertThat(existing.getName()).isEqualTo("Autodíly a.s.");
        assertThat(existing.getRegistrationNumber()).isEqualTo("87654321");
        assertThat(existing.getVatId()).isEqualTo("CZ87654321");
        assertThat(existing.getStreet()).isEqualTo("Nová 1");
        assertThat(existing.getCity()).isEqualTo("Praha");
        assertThat(existing.getPostalCode()).isEqualTo("110 00");
        assertThat(existing.getCountryCode()).isEqualTo("SK");
        assertThat(existing.getBankAccount()).isEqualTo("987654321/0300");
        assertThat(existing.getIban()).isEqualTo("CZ0203000000000123456789");
        assertThat(existing.getSwift()).isEqualTo("CEKOCZPP");
        assertThat(existing.getEmail()).isEqualTo("novy@autodily.cz");
        assertThat(existing.getPhone()).isEqualTo("+420777999888");

        assertThat(existing.getId()).isEqualTo(4L);
        assertThat(existing.isActive()).as("aktivitu mění jen deactivate/activate").isTrue();
    }

    @Test
    @DisplayName("applyUpdate vrací null, chybí-li kterýkoli z argumentů")
    void applyUpdate_nullArguments_returnNull() {
        assertThat(converter.applyUpdate(null, new SupplierDto.UpdateRequest())).isNull();
        assertThat(converter.applyUpdate(supplier(), null)).isNull();
    }

    @Test
    @DisplayName("toListResponse zachová pořadí a namapuje zúženou sadu polí")
    void toListResponse_mapsRowsInOrder() {
        Supplier active = supplier();
        active.setId(4L);

        Supplier inactive = supplier();
        inactive.setId(5L);
        inactive.setName("Zrušený dodavatel");
        inactive.setActive(false);

        List<SupplierDto.ListResponse> result = converter.toListResponse(List.of(active, inactive));

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getId()).isEqualTo(4L);
        assertThat(result.get(0).getName()).isEqualTo("Autodíly s.r.o.");
        assertThat(result.get(0).getRegistrationNumber()).isEqualTo("12345678");
        assertThat(result.get(0).getCity()).isEqualTo("Brno");
        assertThat(result.get(0).isActive()).isTrue();
        assertThat(result.get(1).getId()).isEqualTo(5L);
        assertThat(result.get(1).getName()).isEqualTo("Zrušený dodavatel");
        assertThat(result.get(1).isActive()).isFalse();
    }

    private static Supplier supplier() {
        return Supplier.builder()
                .name("Autodíly s.r.o.")
                .registrationNumber("12345678")
                .vatId("CZ12345678")
                .street("Skladová 7")
                .city("Brno")
                .postalCode("602 00")
                .countryCode("CZ")
                .bankAccount("123456789/0800")
                .iban("CZ6508000000192000145399")
                .swift("GIBACZPX")
                .email("obchod@autodily.cz")
                .phone("+420541123456")
                .active(true)
                .build();
    }
}
