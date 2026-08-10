package cz.palo.autoservis.model.converter;

import cz.palo.autoservis.model.domain.customer.Address;
import cz.palo.autoservis.model.dto.customer.AddressDto;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Konvertor mezi doménovými objekty {@link Address} a DTO {@link AddressDto}.
 */
@Component
public class AddressConverter {

    public List<Address> toDomains(List<AddressDto.CreateRequest> createRequest) {
        return createRequest.stream().map(this::toDomain).collect(Collectors.toList());
    }

    private Address toDomain(AddressDto.CreateRequest createRequest){
        Address address = new Address();
        address.setAddressType(createRequest.getAddressType());
        address.setDefault(createRequest.isDefault());
        address.setStreet(createRequest.getStreet());
        address.setStreetNumber(createRequest.getStreetNumber());
        address.setCity(createRequest.getCity());
        address.setPostalCode(createRequest.getPostalCode());
        address.setCountryCode(createRequest.getCountryCode());
        return address;
    }

    /**
     * Převede {@link Address} na {@link AddressDto.Response}.
     *
     * @param address doménový objekt k převodu
     * @return response DTO, nebo {@code null} při {@code null} vstupu
     */
    public AddressDto.Response toResponse(Address address) {
        if (address == null) {
            return null;
        }

        AddressDto.Response response = new AddressDto.Response();
        response.setId(address.getId());
        response.setAddressType(address.getAddressType());
        response.setDefault(address.isDefault());
        response.setStreet(address.getStreet());
        response.setStreetNumber(address.getStreetNumber());
        response.setCity(address.getCity());
        response.setPostalCode(address.getPostalCode());
        response.setCountryCode(address.getCountryCode());
        return response;
    }

    /**
     * Převede seznam doménových objektů {@link Address} na seznam {@link AddressDto.Response}.
     *
     * @param addresses seznam doménových objektů
     * @return seznam response DTO
     */
    public List<AddressDto.Response> toResponses(List<Address> addresses) {
        return addresses.stream().map(this::toResponse).collect(Collectors.toList());
    }
}
