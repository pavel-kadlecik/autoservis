package cz.palo.autoservis.model.converter;

import cz.palo.autoservis.model.domain.customer.ContactPerson;
import cz.palo.autoservis.model.dto.customer.ContactPersonDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Konvertor kontaktních osob zákazníka — čistý unit test bez Spring kontextu. */
class ContactPersonConverterTest {

    private final ContactPersonConverter converter = new ContactPersonConverter();

    @Test
    @DisplayName("toResponse přenese všechna pole včetně obou příznaků")
    void toResponse_mapsAllFields() {
        ContactPerson person = new ContactPerson();
        person.setId(5L);
        person.setFirstName("Eva");
        person.setLastName("Dvořák");
        person.setPosition("vedoucí nákupu");
        person.setEmail("eva@firma.cz");
        person.setPhone("+420777123456");
        person.setPrimary(true);
        person.setActive(true);

        ContactPersonDto.Response response = converter.toResponse(person);

        assertThat(response.getId()).isEqualTo(5L);
        assertThat(response.getFirstName()).isEqualTo("Eva");
        assertThat(response.getLastName()).isEqualTo("Dvořák");
        assertThat(response.getPosition()).isEqualTo("vedoucí nákupu");
        assertThat(response.getEmail()).isEqualTo("eva@firma.cz");
        assertThat(response.getPhone()).isEqualTo("+420777123456");
        assertThat(response.isPrimary()).isTrue();
        assertThat(response.isActive()).isTrue();
    }

    @Test
    @DisplayName("toResponse: příznaky false se přenesou jako false")
    void toResponse_falseFlags_areMappedAsFalse() {
        ContactPerson person = new ContactPerson();
        person.setFirstName("Petr");
        person.setPrimary(false);
        person.setActive(false);

        ContactPersonDto.Response response = converter.toResponse(person);

        assertThat(response.isPrimary()).isFalse();
        assertThat(response.isActive()).isFalse();
    }

    @Test
    @DisplayName("toResponse nepřenáší interní vazby (customerId, userId nejsou v odpovědi)")
    void toResponse_doesNotLeakInternalIds() {
        ContactPerson person = new ContactPerson();
        person.setId(5L);
        person.setCustomerId(99L);
        person.setUserId(77L);
        person.setFirstName("Eva");

        ContactPersonDto.Response response = converter.toResponse(person);

        assertThat(response.getId()).isEqualTo(5L);
        assertThat(response.getFirstName()).isEqualTo("Eva");
    }

    @Test
    @DisplayName("toResponse(null) → null")
    void toResponse_null_returnsNull() {
        assertThat(converter.toResponse(null)).isNull();
    }

    @Test
    @DisplayName("toResponses zachová pořadí a namapuje každý prvek")
    void toResponses_mapsAllRowsInOrder() {
        ContactPerson first = new ContactPerson();
        first.setId(1L);
        first.setLastName("Dvořák");
        first.setPrimary(true);

        ContactPerson second = new ContactPerson();
        second.setId(2L);
        second.setLastName("Novotný");
        second.setPrimary(false);

        List<ContactPersonDto.Response> result = converter.toResponses(List.of(first, second));

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getId()).isEqualTo(1L);
        assertThat(result.get(0).getLastName()).isEqualTo("Dvořák");
        assertThat(result.get(0).isPrimary()).isTrue();
        assertThat(result.get(1).getId()).isEqualTo(2L);
        assertThat(result.get(1).getLastName()).isEqualTo("Novotný");
        assertThat(result.get(1).isPrimary()).isFalse();
    }
}
