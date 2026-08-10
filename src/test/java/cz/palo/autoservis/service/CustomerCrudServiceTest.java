package cz.palo.autoservis.service;

import cz.palo.autoservis.AbstractIntegrationTest;
import cz.palo.autoservis.exception.BusinessRuleException;
import cz.palo.autoservis.exception.ResourceNotFoundException;
import cz.palo.autoservis.mapper.CustomerMapper;
import cz.palo.autoservis.model.dto.customer.AddressDto;
import cz.palo.autoservis.model.dto.customer.CustomerDto;
import cz.palo.autoservis.model.enums.AddressType;
import cz.palo.autoservis.model.enums.ContactChannel;
import cz.palo.autoservis.model.enums.CustomerType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * CRUD zákazníků ({@code CustomerServiceImpl}) proti reálné DB — doplňuje existující
 * {@code CustomerServiceTest} (jen čtení/hledání) a {@code CustomerValidationTest} (validace).
 *
 * <p>Pokrývá: obě větve zakládání podle typu zákazníka (INDIVIDUAL vs. COMPANY jdou do
 * odlišných INSERT metod), automatické označení fakturační adresy jako výchozí, jedinečnost
 * IČO, <strong>kaskádovou deaktivaci vozidel</strong> při deaktivaci zákazníka a zákaz
 * deaktivace zákazníka s otevřenou zakázkou.
 *
 * <p>Seed: zákazník 3 (firma) má otevřenou zakázku ZAK-2026-0001; zákazník 1 má vozidla
 * a jen uzavřené zakázky.
 */
@Transactional
class CustomerCrudServiceTest extends AbstractIntegrationTest {

    private static final long CUSTOMER_WITH_VEHICLES = 1L;
    private static final long CUSTOMER_WITH_OPEN_ORDER = 3L;
    private static final long USER_ID = 1L;

    @Autowired
    private CustomerService customerService;

    @Autowired
    private VehicleService vehicleService;

    @Autowired
    private CustomerMapper customerMapper;

    // =========================================================================
    // create
    // =========================================================================

    @Test
    @DisplayName("create fyzické osoby uloží jméno a doplní createdBy ze serveru")
    void create_individual_persistsPersonalFields() {
        CustomerDto.CreateRequest request = individualRequest("Petr", "Svoboda");
        request.setPrimaryEmail("petr.svoboda@email.cz");
        request.setPreferredContactChannel(ContactChannel.EMAIL);

        CustomerDto.DetailResponse created = customerService.create(request, USER_ID);

        assertThat(created.getId()).isNotNull();
        assertThat(created.getCustomerType()).isEqualTo(CustomerType.INDIVIDUAL);
        assertThat(created.getFirstName()).isEqualTo("Petr");
        assertThat(created.getLastName()).isEqualTo("Svoboda");
        assertThat(created.getDisplayName()).isEqualTo("Petr Svoboda");
        assertThat(created.getPrimaryEmail()).isEqualTo("petr.svoboda@email.cz");
        assertThat(created.isActive()).isTrue();

        assertThat(customerMapper.findById(created.getId()).orElseThrow().getCreatedBy())
                .as("audit doplňuje server, ne DTO").isEqualTo(USER_ID);
    }

    @Test
    @DisplayName("create firmy uloží název a IČO (jiná INSERT větev než u fyzické osoby)")
    void create_company_persistsCompanyFields() {
        CustomerDto.CreateRequest request = companyRequest("Nová Firma s.r.o.", "45678912");

        CustomerDto.DetailResponse created = customerService.create(request, USER_ID);

        assertThat(created.getCustomerType()).isEqualTo(CustomerType.COMPANY);
        assertThat(created.getCompanyName()).isEqualTo("Nová Firma s.r.o.");
        assertThat(created.getIco()).isEqualTo("45678912");
        assertThat(created.getDisplayName()).isEqualTo("Nová Firma s.r.o.");
    }

    @Test
    @DisplayName("create přiřadí adresy zákazníkovi a fakturační označí jako výchozí")
    void create_marksBillingAddressAsDefault() {
        CustomerDto.CreateRequest request = individualRequest("Petr", "Svoboda");
        request.setAddresses(List.of(
                address(AddressType.CONTACT, "Kontaktní", "1", "Brno", "602 00"),
                address(AddressType.BILLING, "Fakturační", "2", "Praha", "110 00")));

        CustomerDto.DetailResponse created = customerService.create(request, USER_ID);

        assertThat(created.getAddresses()).hasSize(2);

        AddressDto.Response billing = created.getAddresses().stream()
                .filter(a -> a.getAddressType() == AddressType.BILLING)
                .findFirst().orElseThrow();
        AddressDto.Response contact = created.getAddresses().stream()
                .filter(a -> a.getAddressType() == AddressType.CONTACT)
                .findFirst().orElseThrow();

        assertThat(billing.isDefault()).as("fakturační adresa se označí jako výchozí").isTrue();
        assertThat(billing.getCity()).isEqualTo("Praha");
        assertThat(contact.isDefault()).as("kontaktní adresa výchozí není").isFalse();
        assertThat(contact.getCity()).isEqualTo("Brno");
    }

    @Test
    @DisplayName("create přidělí číslo zákazníka ZNK-… (generuje DB trigger, ne Java)")
    void create_assignsCustomerNumberFromTrigger() {
        CustomerDto.DetailResponse created =
                customerService.create(individualRequest("Petr", "Svoboda"), USER_ID);

        assertThat(created.getCustomerNumber())
                .as("číslo přiděluje trigger V9, aplikace ho neposílá")
                .matches("ZNK-\\d{4}-\\d{4}");
    }

    // =========================================================================
    // update
    // =========================================================================

    @Test
    @DisplayName("update přepíše údaje a vrátí čerstvě načtený stav")
    void update_overwritesFields() {
        CustomerDto.UpdateRequest request = individualUpdateRequest("Jan", "Novák");
        request.setPrimaryEmail("jan.novak.novy@email.cz");
        request.setInternalNote("aktualizováno testem");

        CustomerDto.DetailResponse updated =
                customerService.update(CUSTOMER_WITH_VEHICLES, request, USER_ID);

        assertThat(updated.getPrimaryEmail()).isEqualTo("jan.novak.novy@email.cz");
        assertThat(updated.getInternalNote()).isEqualTo("aktualizováno testem");
        assertThat(customerService.getById(CUSTOMER_WITH_VEHICLES).getPrimaryEmail())
                .isEqualTo("jan.novak.novy@email.cz");
    }

    @Test
    @DisplayName("update s adresami přepíše celou adresní sadu — TD-42 (dřív UpdateRequest addresses neměl)")
    void update_withAddresses_replacesSet() {
        // Zákazník 1 má v seedu CONTACT + BILLING (Hlavní 42, Brno). Full-replace jedinou
        // fakturační adresou → kontaktní zmizí, nová billing se uloží jako default.
        CustomerDto.UpdateRequest request = individualUpdateRequest("Jan", "Novák");
        request.setAddresses(List.of(billingAddress("Nová ulice", "Olomouc")));

        customerService.update(CUSTOMER_WITH_VEHICLES, request, USER_ID);

        assertThat(customerService.getById(CUSTOMER_WITH_VEHICLES).getAddresses())
                .singleElement()
                .satisfies(a -> {
                    assertThat(a.getAddressType()).isEqualTo(AddressType.BILLING);
                    assertThat(a.getStreet()).isEqualTo("Nová ulice");
                    assertThat(a.getCity()).isEqualTo("Olomouc");
                    assertThat(a.isDefault()).as("BILLING je vždy default").isTrue();
                });
    }

    @Test
    @DisplayName("update bez adres (null) nechá adresní sadu beze změny — TD-42 (vzor TD-23)")
    void update_withoutAddresses_keepsExisting() {
        var before = customerService.getById(CUSTOMER_WITH_VEHICLES).getAddresses()
                .stream().map(AddressDto.Response::getAddressType).toList();

        // individualUpdateRequest adresy nenastavuje → null → adresy se nemají dotknout
        customerService.update(CUSTOMER_WITH_VEHICLES, individualUpdateRequest("Jan", "Novák"), USER_ID);

        assertThat(customerService.getById(CUSTOMER_WITH_VEHICLES).getAddresses())
                .extracting(AddressDto.Response::getAddressType)
                .containsExactlyInAnyOrderElementsOf(before);
    }

    @Test
    @DisplayName("update s PRÁZDNÝM seznamem adres je odmítnut a sada zůstane — KN-15")
    void update_withEmptyAddressList_isRejectedAndKeepsSet() {
        // Prázdný seznam se dřív choval jako full-replace prázdnou sadou: validátor se u něj
        // hned vracel („Handled by @NotEmpty" — jenže UpdateRequest tu anotaci nemá),
        // deleteByCustomerId adresy smazal a nic se nevložilo zpátky. Zákazníka pak nešlo
        // vyfakturovat. Rozdíl proti null: null = neměnit, prázdný seznam = chyba.
        var before = customerService.getById(CUSTOMER_WITH_VEHICLES).getAddresses()
                .stream().map(AddressDto.Response::getAddressType).toList();
        assertThat(before).as("předpoklad testu: zákazník nějaké adresy má").isNotEmpty();

        CustomerDto.UpdateRequest request = individualUpdateRequest("Jan", "Novák");
        request.setAddresses(List.of());

        assertThatThrownBy(() -> customerService.update(CUSTOMER_WITH_VEHICLES, request, USER_ID))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(ex -> {
                    BusinessRuleException e = (BusinessRuleException) ex;
                    assertThat(e.getRuleCode()).isEqualTo("EMPTY_ADDRESS_SET");
                    assertThat(e.getField()).isEqualTo("addresses");
                });

        assertThat(customerService.getById(CUSTOMER_WITH_VEHICLES).getAddresses())
                .as("odmítnutý update nesmí na adresách nic změnit")
                .extracting(AddressDto.Response::getAddressType)
                .containsExactlyInAnyOrderElementsOf(before);
    }

    @Test
    @DisplayName("gdprConsent lze přes PUT zapnout i vypnout — E1.1/K-4 (dřív se tiše zahazoval)")
    void update_gdprConsentIsPersisted() {
        CustomerDto.UpdateRequest grant = individualUpdateRequest("Jan", "Novák");
        grant.setGdprConsent(true);
        customerService.update(CUSTOMER_WITH_VEHICLES, grant, USER_ID);
        assertThat(customerService.getById(CUSTOMER_WITH_VEHICLES).isGdprConsent())
                .as("udělení souhlasu se propíše do DB").isTrue();

        CustomerDto.UpdateRequest revoke = individualUpdateRequest("Jan", "Novák");
        revoke.setGdprConsent(false);
        customerService.update(CUSTOMER_WITH_VEHICLES, revoke, USER_ID);
        assertThat(customerService.getById(CUSTOMER_WITH_VEHICLES).isGdprConsent())
                .as("odvolání souhlasu se propíše do DB (dřív UPDATE gdpr_consent vynechával)").isFalse();
    }

    @Test
    @DisplayName("update na IČO jiného zákazníka → DUPLICATE_ICO (422)")
    void update_icoTakenByAnotherCustomer_throwsBusinessRule() {
        CustomerDto.UpdateRequest request = individualUpdateRequest("Jan", "Novák");
        request.setIco("12345678"); // IČO zákazníka 3 ze seedu

        assertThatThrownBy(() -> customerService.update(CUSTOMER_WITH_VEHICLES, request, USER_ID))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(ex -> {
                    BusinessRuleException e = (BusinessRuleException) ex;
                    assertThat(e.getRuleCode()).isEqualTo("DUPLICATE_ICO");
                    assertThat(e.getField()).isEqualTo("ico");
                });
    }

    @Test
    @DisplayName("update ponechávající vlastní IČO projde (nekoliduje sám se sebou)")
    void update_keepingOwnIco_succeeds() {
        CustomerDto.UpdateRequest request = companyUpdateRequest("Logistika ABC s.r.o.");
        request.setIco("12345678");

        CustomerDto.DetailResponse updated =
                customerService.update(CUSTOMER_WITH_OPEN_ORDER, request, USER_ID);

        assertThat(updated.getIco()).isEqualTo("12345678");
    }

    @Test
    @DisplayName("update firmy s prázdným názvem → CUSTOMER_COMPANY_REQUIRED (422, TD-10)")
    void update_companyWithBlankName_isRejected() {
        CustomerDto.UpdateRequest request = companyUpdateRequest("   ");

        assertThatThrownBy(() -> customerService.update(CUSTOMER_WITH_OPEN_ORDER, request, USER_ID))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(ex -> {
                    BusinessRuleException e = (BusinessRuleException) ex;
                    assertThat(e.getRuleCode()).isEqualTo("CUSTOMER_COMPANY_REQUIRED");
                    assertThat(e.getField()).isEqualTo("companyName");
                });
    }

    @Test
    @DisplayName("update firmy s vyplněným názvem projde (druhá větev pravidla)")
    void update_companyWithName_succeeds() {
        CustomerDto.UpdateRequest request = companyUpdateRequest("Logistika ABC s.r.o.");

        CustomerDto.DetailResponse updated =
                customerService.update(CUSTOMER_WITH_OPEN_ORDER, request, USER_ID);

        assertThat(updated.getCompanyName()).isEqualTo("Logistika ABC s.r.o.");
    }

    @Test
    @DisplayName("update neexistujícího zákazníka → ResourceNotFoundException (404)")
    void update_unknownId_throwsResourceNotFound() {
        assertThatThrownBy(() -> customerService.update(
                999_999L, individualUpdateRequest("Kdo", "Koli"), USER_ID))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // =========================================================================
    // deactivate / activate
    // =========================================================================

    @Test
    @DisplayName("deactivate zákazníka kaskádově deaktivuje i jeho vozidla")
    void deactivate_cascadesToVehicles() {
        long customerId = customerWithVehicleAndNoOpenOrders();
        int vehiclesBefore = vehicleService.findByCustomerId(customerId).size();
        assertThat(vehiclesBefore).as("fixtura musí být neprázdná").isPositive();

        CustomerDto.DetailResponse deactivated = customerService.deactivate(customerId);

        assertThat(deactivated.isActive()).isFalse();
        assertThat(vehicleService.findByCustomerId(customerId))
                .as("vozidla deaktivovaného zákazníka se skryjí taky").isEmpty();
    }

    @Test
    @DisplayName("deactivate zákazníka s otevřenou zakázkou → CUSTOMER_HAS_OPEN_ORDERS (422)")
    void deactivate_withOpenOrder_isRejected() {
        assertThatThrownBy(() -> customerService.deactivate(CUSTOMER_WITH_OPEN_ORDER))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(ex -> {
                    BusinessRuleException e = (BusinessRuleException) ex;
                    assertThat(e.getRuleCode()).isEqualTo("CUSTOMER_HAS_OPEN_ORDERS");
                    assertThat(e.getParams()).containsKey("openOrders");
                });

        assertThat(customerService.getById(CUSTOMER_WITH_OPEN_ORDER).isActive())
                .as("zákazník musí zůstat aktivní").isTrue();
    }

    @Test
    @DisplayName("zamítnutá deaktivace nesmí deaktivovat ani vozidla (guard běží před kaskádou)")
    void deactivate_rejected_leavesVehiclesUntouched() {
        int vehiclesBefore = vehicleService.findByCustomerId(CUSTOMER_WITH_OPEN_ORDER).size();
        assertThat(vehiclesBefore).as("fixtura musí být neprázdná").isPositive();

        assertThatThrownBy(() -> customerService.deactivate(CUSTOMER_WITH_OPEN_ORDER))
                .isInstanceOf(BusinessRuleException.class);

        assertThat(vehicleService.findByCustomerId(CUSTOMER_WITH_OPEN_ORDER))
                .hasSize(vehiclesBefore);
    }

    @Test
    @DisplayName("activate zákazníka ho vrátí mezi aktivní (vozidla se ale samy neobnoví)")
    void activate_restoresCustomerOnly() {
        long customerId = customerWithVehicleAndNoOpenOrders();
        customerService.deactivate(customerId);

        CustomerDto.DetailResponse reactivated = customerService.activate(customerId);

        assertThat(reactivated.isActive()).isTrue();
        assertThat(vehicleService.findByCustomerId(customerId))
                .as("kaskáda je jednosměrná — vozidla se aktivují ručně").isEmpty();
    }

    @Test
    @DisplayName("deaktivovaného zákazníka lze pořád otevřít detailem (TD-08 — záměrně permissive)")
    void getById_deactivatedCustomer_isStillReadable() {
        long customerId = customerWithVehicleAndNoOpenOrders();
        customerService.deactivate(customerId);

        CustomerDto.DetailResponse response = customerService.getById(customerId);

        assertThat(response.getId()).isEqualTo(customerId);
        assertThat(response.isActive()).isFalse();
    }

    @Test
    @DisplayName("activate neexistujícího zákazníka → ResourceNotFoundException (404)")
    void activate_unknownId_throwsResourceNotFound() {
        assertThatThrownBy(() -> customerService.activate(999_999L))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // =========================================================================
    // Validace sady adres (AddressSetValidator běží při zakládání)
    // =========================================================================

    @Test
    @DisplayName("dvě fakturační adresy → INVALID_BILLING_ADDRESS_COUNT (422)")
    void create_twoBillingAddresses_isRejected() {
        CustomerDto.CreateRequest request = individualRequest("Petr", "Svoboda");
        request.setAddresses(List.of(
                address(AddressType.BILLING, "První", "1", "Praha", "110 00"),
                address(AddressType.BILLING, "Druhá", "2", "Brno", "602 00")));

        assertThatThrownBy(() -> customerService.create(request, USER_ID))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(ex -> {
                    BusinessRuleException e = (BusinessRuleException) ex;
                    assertThat(e.getRuleCode()).isEqualTo("INVALID_BILLING_ADDRESS_COUNT");
                    assertThat(e.getField()).isEqualTo("addresses");
                });
    }

    @Test
    @DisplayName("žádná fakturační adresa → INVALID_BILLING_ADDRESS_COUNT (422)")
    void create_withoutBillingAddress_isRejected() {
        CustomerDto.CreateRequest request = individualRequest("Petr", "Svoboda");
        request.setAddresses(List.of(address(AddressType.CONTACT, "Kontaktní", "1", "Praha", "110 00")));

        assertThatThrownBy(() -> customerService.create(request, USER_ID))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(ex -> assertThat(((BusinessRuleException) ex).getRuleCode())
                        .isEqualTo("INVALID_BILLING_ADDRESS_COUNT"));
    }

    @Test
    @DisplayName("dvě kontaktní adresy → INVALID_CONTACT_ADDRESS_COUNT (422)")
    void create_twoContactAddresses_isRejected() {
        CustomerDto.CreateRequest request = individualRequest("Petr", "Svoboda");
        request.setAddresses(List.of(
                address(AddressType.BILLING, "Fakturační", "1", "Praha", "110 00"),
                address(AddressType.CONTACT, "První kontaktní", "2", "Brno", "602 00"),
                address(AddressType.CONTACT, "Druhá kontaktní", "3", "Ostrava", "702 00")));

        assertThatThrownBy(() -> customerService.create(request, USER_ID))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(ex -> assertThat(((BusinessRuleException) ex).getRuleCode())
                        .isEqualTo("INVALID_CONTACT_ADDRESS_COUNT"));
    }

    @Test
    @DisplayName("jedna fakturační a jedna kontaktní adresa projde (hranice pravidla)")
    void create_oneBillingOneContact_isAccepted() {
        CustomerDto.CreateRequest request = individualRequest("Petr", "Svoboda");
        request.setAddresses(List.of(
                address(AddressType.BILLING, "Fakturační", "1", "Praha", "110 00"),
                address(AddressType.CONTACT, "Kontaktní", "2", "Brno", "602 00")));

        CustomerDto.DetailResponse created = customerService.create(request, USER_ID);

        assertThat(created.getAddresses()).hasSize(2);
    }

    // =========================================================================
    // autocomplete
    // =========================================================================

    @Test
    @DisplayName("autocomplete najde zákazníka podle jména")
    void autocomplete_findsCustomerByName() {
        var params = new cz.palo.autoservis.model.dto.customer.CustomerAutocompleteParams("Novák", 10);

        var response = customerService.autocomplete(params);

        assertThat(response.getData()).isNotEmpty();
        assertThat(response.getData()).extracting("value")
                .anySatisfy(value -> assertThat(String.valueOf(value)).contains("Novák"));
    }

    @Test
    @DisplayName("autocomplete respektuje limit a při přebytku nastaví hasMore")
    void autocomplete_respectsLimitAndFlagsMore() {
        var params = new cz.palo.autoservis.model.dto.customer.CustomerAutocompleteParams("", 2);

        var response = customerService.autocomplete(params);

        assertThat(response.getData()).hasSize(2);
        assertThat(response.isHasMore())
                .as("seed má 10 zákazníků, takže při limitu 2 musí být hasMore true").isTrue();
    }

    @Test
    @DisplayName("autocomplete bez shody vrátí prázdný seznam a hasMore = false")
    void autocomplete_withoutMatches_returnsEmpty() {
        var params = new cz.palo.autoservis.model.dto.customer.CustomerAutocompleteParams(
                "NeexistujiciZakaznikXYZ", 10);

        var response = customerService.autocomplete(params);

        assertThat(response.getData()).isEmpty();
        assertThat(response.isHasMore()).isFalse();
    }

    // =========================================================================
    // Fixtury
    // =========================================================================

    /**
     * Založí čerstvého zákazníka i s vozidlem. Všichni zákazníci ze seedu (1, 2, 3) mají
     * otevřenou zakázku, takže na nich deaktivaci otestovat nejde — guard by ji zamítl dřív,
     * než by se dostalo ke kaskádě.
     */
    private long customerWithVehicleAndNoOpenOrders() {
        Long customerId = customerService.create(individualRequest("Bez", "Zakázek"), USER_ID).getId();

        cz.palo.autoservis.model.dto.vehicle.VehicleDto.CreateRequest vehicle =
                new cz.palo.autoservis.model.dto.vehicle.VehicleDto.CreateRequest();
        vehicle.setCustomerId(customerId);
        vehicle.setVin("VF1RJB00X66123456");
        vehicle.setBrand("Renault");
        vehicle.setModel("Mégane");
        vehicle.setFuelType(cz.palo.autoservis.model.enums.FuelType.PETROL);
        vehicleService.create(vehicle, USER_ID);

        return customerId;
    }

    private static CustomerDto.CreateRequest individualRequest(String firstName, String lastName) {
        CustomerDto.CreateRequest request = new CustomerDto.CreateRequest();
        request.setCustomerType(CustomerType.INDIVIDUAL);
        request.setFirstName(firstName);
        request.setLastName(lastName);
        request.setGdprConsent(true);
        request.setAddresses(List.of(address(AddressType.BILLING, "Testovací", "1", "Praha", "110 00")));
        return request;
    }

    private static CustomerDto.CreateRequest companyRequest(String companyName, String ico) {
        CustomerDto.CreateRequest request = new CustomerDto.CreateRequest();
        request.setCustomerType(CustomerType.COMPANY);
        request.setCompanyName(companyName);
        request.setIco(ico);
        request.setDic("CZ" + ico);
        request.setLegalForm("s.r.o.");
        request.setGdprConsent(true);
        request.setAddresses(List.of(address(AddressType.BILLING, "Firemní", "10", "Brno", "602 00")));
        return request;
    }

    private static CustomerDto.UpdateRequest individualUpdateRequest(String firstName, String lastName) {
        CustomerDto.UpdateRequest request = new CustomerDto.UpdateRequest();
        request.setFirstName(firstName);
        request.setLastName(lastName);
        return request;
    }

    private static AddressDto.CreateRequest billingAddress(String street, String city) {
        AddressDto.CreateRequest a = new AddressDto.CreateRequest();
        a.setAddressType(AddressType.BILLING);
        a.setStreet(street);
        a.setStreetNumber("1");
        a.setCity(city);
        a.setPostalCode("602 00");
        a.setCountryCode("CZ");
        return a;
    }

    private static CustomerDto.UpdateRequest companyUpdateRequest(String companyName) {
        CustomerDto.UpdateRequest request = new CustomerDto.UpdateRequest();
        request.setCompanyName(companyName);
        return request;
    }

    private static AddressDto.CreateRequest address(AddressType type, String street, String number,
                                                    String city, String postalCode) {
        AddressDto.CreateRequest address = new AddressDto.CreateRequest();
        address.setAddressType(type);
        address.setStreet(street);
        address.setStreetNumber(number);
        address.setCity(city);
        address.setPostalCode(postalCode);
        address.setCountryCode("CZ");
        return address;
    }
}
