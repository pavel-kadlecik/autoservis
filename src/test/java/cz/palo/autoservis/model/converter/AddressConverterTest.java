package cz.palo.autoservis.model.converter;

import cz.palo.autoservis.model.domain.customer.Address;
import cz.palo.autoservis.model.dto.customer.AddressDto;
import cz.palo.autoservis.model.enums.AddressType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Konvertor adres — čistý unit test bez Spring kontextu.
 *
 * <p>Adresa putuje na fakturu jako snapshot, takže špatně přenesené pole se propíše do
 * daňového dokladu. Zvláštní pozornost si zaslouží příznak {@code isDefault} (jediná výchozí
 * adresa per typ) a {@code addressType} — na obojím stojí výběr fakturační adresy.
 */
class AddressConverterTest {

    private final AddressConverter converter = new AddressConverter();

    @Test
    @DisplayName("toDomains přenese všechna pole a zachová pořadí")
    void toDomains_mapsAllFieldsInOrder() {
        AddressDto.CreateRequest billing = request(AddressType.BILLING, true, "Testovací", "1", "Praha", "110 00", "CZ");
        AddressDto.CreateRequest shipping = request(AddressType.CONTACT, false, "Doručovací", "22b", "Brno", "60200", "SK");

        List<Address> result = converter.toDomains(List.of(billing, shipping));

        assertThat(result).hasSize(2);

        Address first = result.get(0);
        assertThat(first.getAddressType()).isEqualTo(AddressType.BILLING);
        assertThat(first.isDefault()).isTrue();
        assertThat(first.getStreet()).isEqualTo("Testovací");
        assertThat(first.getStreetNumber()).isEqualTo("1");
        assertThat(first.getCity()).isEqualTo("Praha");
        assertThat(first.getPostalCode()).isEqualTo("110 00");
        assertThat(first.getCountryCode()).isEqualTo("CZ");

        Address second = result.get(1);
        assertThat(second.getAddressType()).isEqualTo(AddressType.CONTACT);
        assertThat(second.isDefault()).isFalse();
        assertThat(second.getCity()).isEqualTo("Brno");
        assertThat(second.getCountryCode()).isEqualTo("SK");
    }

    @Test
    @DisplayName("toDomains nenastaví id — to přiděluje databáze")
    void toDomains_doesNotSetId() {
        List<Address> result = converter.toDomains(
                List.of(request(AddressType.BILLING, true, "Testovací", "1", "Praha", "11000", "CZ")));

        assertThat(result.getFirst().getId()).isNull();
    }

    @Test
    @DisplayName("toResponse přenese všechna pole včetně id a příznaku isDefault")
    void toResponse_mapsAllFields() {
        Address address = new Address();
        address.setId(42L);
        address.setAddressType(AddressType.BILLING);
        address.setDefault(true);
        address.setStreet("Testovací");
        address.setStreetNumber("1");
        address.setCity("Praha");
        address.setPostalCode("110 00");
        address.setCountryCode("CZ");

        AddressDto.Response response = converter.toResponse(address);

        assertThat(response.getId()).isEqualTo(42L);
        assertThat(response.getAddressType()).isEqualTo(AddressType.BILLING);
        assertThat(response.isDefault()).isTrue();
        assertThat(response.getStreet()).isEqualTo("Testovací");
        assertThat(response.getStreetNumber()).isEqualTo("1");
        assertThat(response.getCity()).isEqualTo("Praha");
        assertThat(response.getPostalCode()).isEqualTo("110 00");
        assertThat(response.getCountryCode()).isEqualTo("CZ");
    }

    @Test
    @DisplayName("toResponse: isDefault=false se přenese jako false, ne jako true")
    void toResponse_nonDefaultAddress_keepsFlagFalse() {
        Address address = new Address();
        address.setAddressType(AddressType.CONTACT);
        address.setDefault(false);

        assertThat(converter.toResponse(address).isDefault()).isFalse();
    }

    @Test
    @DisplayName("toResponse(null) → null")
    void toResponse_null_returnsNull() {
        assertThat(converter.toResponse(null)).isNull();
    }

    @Test
    @DisplayName("toResponses zachová pořadí a namapuje každý prvek")
    void toResponses_mapsAllRowsInOrder() {
        Address billing = new Address();
        billing.setId(1L);
        billing.setAddressType(AddressType.BILLING);
        billing.setCity("Praha");

        Address shipping = new Address();
        shipping.setId(2L);
        shipping.setAddressType(AddressType.CONTACT);
        shipping.setCity("Brno");

        List<AddressDto.Response> result = converter.toResponses(List.of(billing, shipping));

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getId()).isEqualTo(1L);
        assertThat(result.get(0).getCity()).isEqualTo("Praha");
        assertThat(result.get(1).getId()).isEqualTo(2L);
        assertThat(result.get(1).getCity()).isEqualTo("Brno");
    }

    private static AddressDto.CreateRequest request(AddressType type, boolean isDefault, String street,
                                                    String streetNumber, String city, String postalCode,
                                                    String countryCode) {
        AddressDto.CreateRequest request = new AddressDto.CreateRequest();
        request.setAddressType(type);
        request.setDefault(isDefault);
        request.setStreet(street);
        request.setStreetNumber(streetNumber);
        request.setCity(city);
        request.setPostalCode(postalCode);
        request.setCountryCode(countryCode);
        return request;
    }
}
