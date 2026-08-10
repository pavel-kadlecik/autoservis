package cz.palo.autoservis.model.converter;

import cz.palo.autoservis.model.domain.customer.ContactPerson;
import cz.palo.autoservis.model.dto.customer.ContactPersonDto;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Konvertor mezi doménovými objekty {@link ContactPerson} a DTO {@link ContactPersonDto}.
 */
@Component
public class ContactPersonConverter {

    /**
     * Převede doménový objekt {@link ContactPerson} na {@link ContactPersonDto.Response}.
     *
     * @param contactPerson doménový objekt k převodu
     * @return odpovídající DTO, nebo {@code null} při {@code null} vstupu
     */
    public ContactPersonDto.Response toResponse(ContactPerson contactPerson) {
        if (contactPerson == null) {
            return null;
        }

        ContactPersonDto.Response response = new ContactPersonDto.Response();
        response.setId(contactPerson.getId());
        response.setFirstName(contactPerson.getFirstName());
        response.setLastName(contactPerson.getLastName());
        response.setPosition(contactPerson.getPosition());
        response.setEmail(contactPerson.getEmail());
        response.setPhone(contactPerson.getPhone());
        response.setPrimary(contactPerson.isPrimary());
        response.setActive(contactPerson.isActive());

        return response;
    }

    /**
     * Převede seznam doménových objektů {@link ContactPerson} na seznam {@link ContactPersonDto.Response}.
     *
     * @param contactPersons seznam doménových objektů k převodu
     * @return odpovídající seznam DTO
     */
    public List<ContactPersonDto.Response> toResponses(List<ContactPerson> contactPersons) {
        return contactPersons.stream().map(this::toResponse).collect(Collectors.toList());
    }
}