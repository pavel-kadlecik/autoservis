package cz.palo.autoservis.model.converter;

import cz.palo.autoservis.model.domain.customer.Customer;
import cz.palo.autoservis.model.dto.customer.CustomerDto;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Konvertor mezi doménovými objekty {@link Customer} a DTO {@link CustomerDto}.
 */
@Component
@RequiredArgsConstructor
public class CustomerConverter {

    private final AddressConverter addressConverter;
    private final ContactPersonConverter contactPersonConverter;
    private final VehicleConverter vehicleConverter;

    /**
     * Převede {@link Customer} na plné {@link CustomerDto.DetailResponse}.
     * Vnořené kolekce (adresy, kontaktní osoby, vozidla) se mapují,
     * jen když jsou na doménovém objektu přítomné.
     *
     * @param customer doménový objekt k převodu
     * @return detailové response DTO, nebo {@code null} při {@code null} vstupu
     */
    public CustomerDto.DetailResponse toDetailResponse(Customer customer) {
        if (customer == null) {
            return null;
        }

        CustomerDto.DetailResponse response = new CustomerDto.DetailResponse();

        response.setId(customer.getId());
        response.setCustomerNumber(customer.getCustomerNumber());
        response.setCustomerType(customer.getCustomerType());
        response.setDisplayName(customer.getDisplayName());

        response.setFirstName(customer.getFirstName());
        response.setLastName(customer.getLastName());
        response.setBirthDate(customer.getBirthDate());

        response.setCompanyName(customer.getCompanyName());
        response.setIco(customer.getIco());
        response.setDic(customer.getDic());
        response.setLegalForm(customer.getLegalForm());

        response.setPrimaryEmail(customer.getPrimaryEmail());
        response.setPrimaryPhone(customer.getPrimaryPhone());
        response.setMarketingConsent(customer.isMarketingConsent());
        response.setGdprConsent(customer.isGdprConsent());
        response.setPreferredContactChannel(customer.getPreferredContactChannel());
        response.setInternalNote(customer.getInternalNote());
        response.setLoyaltyPoints(customer.getLoyaltyPoints());
        response.setActive(customer.isActive());

        response.setCreatedAt(customer.getCreatedAt());
        response.setUpdatedAt(customer.getUpdatedAt());

        if (customer.getAddresses() != null) {
            response.setAddresses(addressConverter.toResponses(customer.getAddresses()));
        }
        if (customer.getContactPersons() != null) {
            response.setContactPersons(contactPersonConverter.toResponses(customer.getContactPersons()));
        }
        if (customer.getVehicles() != null) {
            response.setVehicles(vehicleConverter.toListResponses(customer.getVehicles()));
        }

        return response;
    }

    /**
     * Převede {@link CustomerDto.CreateRequest} na doménový objekt {@link Customer}.
     * Auditní pole ({@code createdBy}) ani pole spravovaná DB ({@code customerNumber},
     * časová razítka) se tady nenastavují.
     *
     * @param createRequest zvalidované create request DTO
     * @return doménový objekt připravený k INSERTu, nebo {@code null} při {@code null} vstupu
     */
    public Customer toDomain(CustomerDto.CreateRequest createRequest) {
        if (createRequest == null) {
            return null;
        }
        Customer customer = new Customer();
        customer.setCustomerType(createRequest.getCustomerType());
        customer.setFirstName(blankToNull(createRequest.getFirstName()));
        customer.setLastName(blankToNull(createRequest.getLastName()));
        customer.setBirthDate(createRequest.getBirthDate());
        customer.setCompanyName(blankToNull(createRequest.getCompanyName()));
        customer.setIco(blankToNull(createRequest.getIco()));
        customer.setDic(blankToNull(createRequest.getDic()));
        customer.setLegalForm(blankToNull(createRequest.getLegalForm()));
        customer.setPrimaryEmail(blankToNull(createRequest.getPrimaryEmail()));
        customer.setPrimaryPhone(blankToNull(createRequest.getPrimaryPhone()));
        customer.setGdprConsent(createRequest.isGdprConsent());
        customer.setMarketingConsent(createRequest.isMarketingConsent());
        customer.setPreferredContactChannel(createRequest.getPreferredContactChannel());
        customer.setInternalNote(blankToNull(createRequest.getInternalNote()));
        customer.setAddresses(addressConverter.toDomains(createRequest.getAddresses()));

        return customer;
    }

    /**
     * Aplikuje pole z {@link CustomerDto.UpdateRequest} na existujícího {@link Customer}.
     * Existující objekt se mění na místě a vrací.
     *
     * @param existingCustomer zákazník načtený z databáze
     * @param updateRequest    zvalidované update request DTO
     * @return upravený doménový objekt, nebo {@code null}, je-li kterýkoli argument {@code null}
     */
    public Customer applyUpdate(Customer existingCustomer, CustomerDto.UpdateRequest updateRequest) {
        if (updateRequest == null || existingCustomer == null) {
            return null;
        }
        existingCustomer.setFirstName(blankToNull(updateRequest.getFirstName()));
        existingCustomer.setLastName(blankToNull(updateRequest.getLastName()));
        existingCustomer.setBirthDate(updateRequest.getBirthDate());
        existingCustomer.setCompanyName(blankToNull(updateRequest.getCompanyName()));
        existingCustomer.setIco(blankToNull(updateRequest.getIco()));
        existingCustomer.setDic(blankToNull(updateRequest.getDic()));
        existingCustomer.setLegalForm(blankToNull(updateRequest.getLegalForm()));
        existingCustomer.setPrimaryEmail(blankToNull(updateRequest.getPrimaryEmail()));
        existingCustomer.setPrimaryPhone(blankToNull(updateRequest.getPrimaryPhone()));
        // TD-23: gdprConsent/marketingConsent jsou v UpdateRequest typu Boolean (ne boolean) —
        // chybějící pole v JSON (null) se nepřepisuje, na rozdíl od ostatních polí výše
        // (ta mají full-replace sémantiku i pro chybějící/blank hodnoty).
        if (updateRequest.getGdprConsent() != null) {
            existingCustomer.setGdprConsent(updateRequest.getGdprConsent());
        }
        if (updateRequest.getMarketingConsent() != null) {
            existingCustomer.setMarketingConsent(updateRequest.getMarketingConsent());
        }
        existingCustomer.setPreferredContactChannel(updateRequest.getPreferredContactChannel());
        existingCustomer.setInternalNote(blankToNull(updateRequest.getInternalNote()));
        return existingCustomer;
    }

    /**
     * Nevyplněná textová pole normalizuje na {@code null} — frontend je posílá
     * jako prázdné řetězce, ale DB stojí na NULL sémantice: {@code uq_customers_ico}
     * povoluje více NULL, ale {@code ''} jen jednou, a {@code chk_customers_email}
     * prázdný řetězec rovnou odmítá (V80).
     */
    private String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value;
    }

    /**
     * Převede seznam doménových objektů {@link Customer} na seznam {@link CustomerDto.ListResponse}.
     *
     * @param customers seznam doménových objektů
     * @return seznam seznamových response DTO
     */
    public List<CustomerDto.ListResponse> toListResponses(List<Customer> customers) {
        return customers.stream().map(this::toListResponse).collect(Collectors.toList());
    }

    private CustomerDto.ListResponse toListResponse(Customer customer) {
        if (customer == null) {
            return null;
        }
        CustomerDto.ListResponse response = new CustomerDto.ListResponse();
        response.setId(customer.getId());
        response.setActive(customer.isActive());
        response.setCustomerNumber(customer.getCustomerNumber());
        response.setCustomerType(customer.getCustomerType());
        response.setLoyaltyPoints(customer.getLoyaltyPoints());
        response.setCreatedAt(customer.getCreatedAt());
        response.setDisplayName(customer.getDisplayName());
        response.setPrimaryEmail(customer.getPrimaryEmail());
        response.setPrimaryPhone(customer.getPrimaryPhone());
        return response;
    }
}
