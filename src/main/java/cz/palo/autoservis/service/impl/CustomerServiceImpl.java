package cz.palo.autoservis.service.impl;

import cz.palo.autoservis.exception.BusinessRuleException;
import cz.palo.autoservis.exception.ResourceNotFoundException;
import cz.palo.autoservis.mapper.AddressMapper;
import cz.palo.autoservis.mapper.CustomerMapper;
import cz.palo.autoservis.model.converter.AddressConverter;
import cz.palo.autoservis.model.converter.CustomerConverter;
import cz.palo.autoservis.model.domain.customer.Address;
import cz.palo.autoservis.model.domain.customer.Customer;
import cz.palo.autoservis.model.dto.autocomplete.AutocompleteItem;
import cz.palo.autoservis.model.dto.autocomplete.AutocompleteResponse;
import cz.palo.autoservis.model.dto.customer.CustomerAutocompleteParams;
import cz.palo.autoservis.model.dto.customer.AddressDto;
import cz.palo.autoservis.model.dto.customer.CustomerDto;
import cz.palo.autoservis.model.dto.customer.CustomerSearchParams;
import cz.palo.autoservis.model.dto.pagination.PagedResponse;
import cz.palo.autoservis.model.enums.AddressType;
import cz.palo.autoservis.model.enums.CustomerType;
import cz.palo.autoservis.service.AddressSetValidator;
import cz.palo.autoservis.service.CustomerService;
import cz.palo.autoservis.service.OrderService;
import cz.palo.autoservis.service.VehicleService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * Implementace {@link CustomerService}.
 */
@Service
@RequiredArgsConstructor
public class CustomerServiceImpl implements CustomerService {

    private final CustomerMapper customerMapper;
    private final CustomerConverter customerConverter;
    private final OrderService orderService;
    private final VehicleService vehicleService;
    private final AddressSetValidator addressSetValidator;
    private final AddressMapper addressMapper;
    private final AddressConverter addressConverter;

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException  když je {@code id} null
     * @throws ResourceNotFoundException když zákazník s daným ID neexistuje
     */
    @Override
    public CustomerDto.DetailResponse getById(Long id) {
        if (id == null) {
            throw new IllegalArgumentException("ID zákazníka nesmí být null");
        }
        return customerMapper.findById(id)
                .map(customerConverter::toDetailResponse)
                .orElseThrow(() -> new ResourceNotFoundException("Zákazník", id));
    }

    /** {@inheritDoc} */
    @Override
    public PagedResponse<CustomerDto.ListResponse> getPage(CustomerSearchParams params) {
        List<Customer> customers = customerMapper.search(params);
        List<CustomerDto.ListResponse> listResponses = customerConverter.toListResponses(customers);
        long total = customerMapper.countSearch(params);
        return PagedResponse.of(listResponses, params.getPage(), params.getPageSize(), total);
    }

    /**
     * {@inheritDoc}
     *
     * @throws ResourceNotFoundException když se záznam po INSERT nepodaří načíst
     * @throws BusinessRuleException     když adresa není platná
     */
    @Override
    @Transactional
    public CustomerDto.DetailResponse create(CustomerDto.CreateRequest createRequest, Long userId) {

        // validace adresy zákazníka
        addressSetValidator.validate(createRequest.getAddresses());

        Customer customer = customerConverter.toDomain(createRequest);
        customer.setCreatedBy(userId);

        if (createRequest.getCustomerType() == CustomerType.INDIVIDUAL) {
            customerMapper.insertIndividual(customer);
        } else if (createRequest.getCustomerType() == CustomerType.COMPANY) {
            customerMapper.insertCompany(customer);
        } else {
            throw new IllegalArgumentException(
                    "Nepodporovaný typ zákazníka: " + createRequest.getCustomerType());
        }

        List<Address> addresses = addressConverter.toDomains(createRequest.getAddresses());
        for (Address a : addresses) {
            a.setCustomerId(customer.getId());
            a.setDefault(a.getAddressType() == AddressType.BILLING);
            addressMapper.insert(a);
        }

        return getById(customer.getId());
    }

    /**
     * {@inheritDoc}
     *
     * @throws ResourceNotFoundException když zákazník s daným ID neexistuje
     * @throws BusinessRuleException     když nové IČO už používá jiný zákazník
     */
    @Override
    @Transactional
    public CustomerDto.DetailResponse update(Long id, CustomerDto.UpdateRequest updateRequest, Long userId) {
        Customer existingCustomer = customerMapper.findByIdShallow(id)
                .orElseThrow(() -> new ResourceNotFoundException("Zákazník", id));

        // Typ zákazníka je neměnný — UpdateRequest pole customerType nenese,
        // takže podmíněné pravidlo povinných polí (TD-10) čte typ z už načteného
        // záznamu a validuje proti němu příchozí request. applyUpdate() níže je
        // úplná náhrada (žádná PATCH sémantika „null = ponechat stávající"),
        // takže validace polí samotného requestu je zde ekvivalentní validaci
        // stavu po aktualizaci.
        requireNameOrCompanyPresent(existingCustomer.getCustomerType(), updateRequest);

        // Blank IČO se nekontroluje — FE posílá nevyplněné pole jako "" a dotaz
        // existsByIco("") by na dirty datech (řádek s ico = '', před V80) shodil
        // editaci každého zákazníka bez IČO na DUPLICATE_ICO.
        String newIco = updateRequest.getIco();
        if (!isBlank(newIco)
                && !newIco.equals(existingCustomer.getIco())
                && customerMapper.existsByIco(newIco)) {
            throw new BusinessRuleException(
                    "DUPLICATE_ICO",
                    "ico",
                    "Zákazník s IČO " + newIco + " už existuje.",
                    Map.of("ico", newIco));
        }

        Customer updatedCustomer = customerConverter.applyUpdate(existingCustomer, updateRequest);
        int affectedRows = customerMapper.update(updatedCustomer);

        // TD-42: adresy jsou volitelné (null = neměnit, vzor TD-23). Když je klient poslal,
        // full-replace sady: validovat → smazat staré → vložit nové (stejná logika jako create).
        // Nic nedrží FK na address.id, faktura má vlastní snapshot → přepis je bezpečný. @Transactional
        // drží vše pohromadě (validace selže → rollback i update zákazníka).
        List<AddressDto.CreateRequest> addressRequests = updateRequest.getAddresses();
        if (addressRequests != null) {
            addressSetValidator.validate(addressRequests);
            addressMapper.deleteByCustomerId(id);
            for (Address a : addressConverter.toDomains(addressRequests)) {
                a.setCustomerId(id);
                a.setDefault(a.getAddressType() == AddressType.BILLING);
                addressMapper.insert(a);
            }
        }

        return verifyAndFetchAfterUpdate(id, affectedRows);
    }

    /**
     * {@inheritDoc}
     *
     * @throws ResourceNotFoundException když zákazník s daným ID neexistuje
     * @throws BusinessRuleException když má zákazník otevřenou zakázku (jednu či více)
     */
    @Override
    @Transactional
    public CustomerDto.DetailResponse deactivate(Long id) {
        int openOrders = orderService.countOpenByCustomerId(id);

        if(openOrders > 0) {
            throw new BusinessRuleException(
                    "CUSTOMER_HAS_OPEN_ORDERS",
                    null,
                    "Zákazník má " + openOrders + " otevřených zakázek, proto ho nelze deaktivovat.",
                    Map.of("openOrders", openOrders));
        }

        vehicleService.deactivateByCustomerId(id);

        int affectedRows = customerMapper.deactivate(id);
        return verifyAndFetchAfterStatusChange(id, affectedRows);
    }

    /**
     * {@inheritDoc}
     *
     * @throws ResourceNotFoundException když zákazník s daným ID neexistuje
     */
    @Override
    public CustomerDto.DetailResponse activate(Long id) {
        int affectedRows = customerMapper.activate(id);
        return verifyAndFetchAfterStatusChange(id, affectedRows);
    }

    /** {@inheritDoc} */
    @Override
    public AutocompleteResponse autocomplete(CustomerAutocompleteParams params) {
        List<AutocompleteItem> items = customerMapper.autocomplete(params);
        int effectiveLimit = params.effectiveLimit();
        boolean hasMore = items.size() > effectiveLimit;

        AutocompleteResponse response = new AutocompleteResponse();
        response.setData(items.subList(0, Math.min(items.isEmpty() ? 0 : hasMore ? items.size() - 1 : items.size(), effectiveLimit)));
        response.setHasMore(hasMore);
        return response;
    }

    // =========================================================================
    // Privátní pomocné metody
    // =========================================================================

    /**
     * TD-10 — podmíněné pravidlo povinných polí pro {@code UpdateRequest}, zrcadlící
     * {@link cz.palo.autoservis.validation.CustomerCreateRequestValidator} na straně vytváření.
     * {@code CreateRequest} to vynucuje přes {@code @ValidCustomerRequest} (Bean Validation, 400),
     * ale {@code UpdateRequest} žádné pole {@code customerType} k validaci nemá (typ je neměnný),
     * takže stejné pravidlo musí běžet tady proti typu už uloženému v DB.
     *
     * @throws BusinessRuleException {@code CUSTOMER_NAME_REQUIRED}/{@code CUSTOMER_COMPANY_REQUIRED}
     *                                když jsou pole povinná pro existující typ zákazníka prázdná
     */
    private void requireNameOrCompanyPresent(CustomerType customerType, CustomerDto.UpdateRequest updateRequest) {
        if (customerType == CustomerType.INDIVIDUAL) {
            if (isBlank(updateRequest.getFirstName())) {
                throw new BusinessRuleException(
                        "CUSTOMER_NAME_REQUIRED", "firstName",
                        "Jméno je u fyzické osoby povinné", Map.of());
            }
            if (isBlank(updateRequest.getLastName())) {
                throw new BusinessRuleException(
                        "CUSTOMER_NAME_REQUIRED", "lastName",
                        "Příjmení je u fyzické osoby povinné", Map.of());
            }
        } else if (customerType == CustomerType.COMPANY && isBlank(updateRequest.getCompanyName())) {
            throw new BusinessRuleException(
                    "CUSTOMER_COMPANY_REQUIRED", "companyName",
                    "Název firmy je u firmy povinný", Map.of());
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private CustomerDto.DetailResponse verifyAndFetchAfterStatusChange(Long id, int affectedRows) {
        if (affectedRows == 0) {
            throw new ResourceNotFoundException("Zákazník", id);
        }
        return fetchOrFail(id);
    }

    private CustomerDto.DetailResponse verifyAndFetchAfterUpdate(Long id, int affectedRows) {
        if (affectedRows == 0) {
            throw new IllegalStateException(
                    "Zákazník " + id + " zmizel během aktualizace (byl načten těsně předtím)");
        }
        return fetchOrFail(id);
    }

    private CustomerDto.DetailResponse fetchOrFail(Long id) {
        return customerMapper.findById(id)
                .map(customerConverter::toDetailResponse)
                .orElseThrow(() -> new IllegalStateException(
                        "Zákazník " + id + " zmizel mezi UPDATE a SELECT"));
    }
}
