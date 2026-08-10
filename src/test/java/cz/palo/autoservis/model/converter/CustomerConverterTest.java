package cz.palo.autoservis.model.converter;

import cz.palo.autoservis.model.domain.customer.Address;
import cz.palo.autoservis.model.domain.customer.ContactPerson;
import cz.palo.autoservis.model.domain.customer.Customer;
import cz.palo.autoservis.model.domain.vehicle.Vehicle;
import cz.palo.autoservis.model.dto.customer.AddressDto;
import cz.palo.autoservis.model.dto.customer.CustomerDto;
import cz.palo.autoservis.model.enums.AddressType;
import cz.palo.autoservis.model.enums.ContactChannel;
import cz.palo.autoservis.model.enums.CustomerType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Konvertor zákazníka — čistý unit test bez Spring kontextu (konvertory jsou bezstavové
 * {@code @Component}, stačí je poskládat ručně).
 *
 * <p>Testuje se především to, co je snadné tiše rozbít:
 * <ul>
 *   <li><strong>TD-23</strong> — {@code gdprConsent}/{@code marketingConsent} jsou v
 *       {@code UpdateRequest} typu {@code Boolean}: {@code null} znamená „nech stávající",
 *       ne „nastav false". Testují se <em>obě</em> větve.</li>
 *   <li>ostatní pole {@code UpdateRequest} mají naopak full-replace sémantiku ({@code null} přepíše),</li>
 *   <li>konvertor <strong>nesmí</strong> plnit {@code createdBy} ani DB-řízená pole
 *       ({@code customerNumber}, timestamps) — audit doplňuje server (R-04, N-06).</li>
 * </ul>
 */
class CustomerConverterTest {

    private final AddressConverter addressConverter = new AddressConverter();
    private final ContactPersonConverter contactPersonConverter = new ContactPersonConverter();
    private final VehicleConverter vehicleConverter = new VehicleConverter();
    private final CustomerConverter converter =
            new CustomerConverter(addressConverter, contactPersonConverter, vehicleConverter);

    // =========================================================================
    // toDomain
    // =========================================================================

    @Test
    @DisplayName("toDomain přenese všechna vyplněná pole CreateRequest")
    void toDomain_mapsAllFields() {
        CustomerDto.CreateRequest request = new CustomerDto.CreateRequest();
        request.setCustomerType(CustomerType.COMPANY);
        request.setFirstName("Jan");
        request.setLastName("Novák");
        request.setBirthDate(LocalDate.of(1980, 5, 17));
        request.setCompanyName("Autodíly s.r.o.");
        request.setIco("12345678");
        request.setDic("CZ12345678");
        request.setLegalForm("s.r.o.");
        request.setPrimaryEmail("info@autodily.cz");
        request.setPrimaryPhone("+420777123456");
        request.setGdprConsent(true);
        request.setMarketingConsent(true);
        request.setPreferredContactChannel(ContactChannel.EMAIL);
        request.setInternalNote("VIP zákazník");
        request.setAddresses(List.of(billingAddressRequest()));

        Customer result = converter.toDomain(request);

        assertThat(result.getCustomerType()).isEqualTo(CustomerType.COMPANY);
        assertThat(result.getFirstName()).isEqualTo("Jan");
        assertThat(result.getLastName()).isEqualTo("Novák");
        assertThat(result.getBirthDate()).isEqualTo(LocalDate.of(1980, 5, 17));
        assertThat(result.getCompanyName()).isEqualTo("Autodíly s.r.o.");
        assertThat(result.getIco()).isEqualTo("12345678");
        assertThat(result.getDic()).isEqualTo("CZ12345678");
        assertThat(result.getLegalForm()).isEqualTo("s.r.o.");
        assertThat(result.getPrimaryEmail()).isEqualTo("info@autodily.cz");
        assertThat(result.getPrimaryPhone()).isEqualTo("+420777123456");
        assertThat(result.isGdprConsent()).isTrue();
        assertThat(result.isMarketingConsent()).isTrue();
        assertThat(result.getPreferredContactChannel()).isEqualTo(ContactChannel.EMAIL);
        assertThat(result.getInternalNote()).isEqualTo("VIP zákazník");
        assertThat(result.getAddresses()).hasSize(1);
        assertThat(result.getAddresses().getFirst().getCity()).isEqualTo("Praha");
    }

    @Test
    @DisplayName("toDomain nenastaví audit ani DB-řízená pole (createdBy, customerNumber, id, timestamps)")
    void toDomain_leavesServerManagedFieldsEmpty() {
        CustomerDto.CreateRequest request = new CustomerDto.CreateRequest();
        request.setCustomerType(CustomerType.INDIVIDUAL);
        request.setFirstName("Jan");
        request.setLastName("Novák");
        request.setAddresses(List.of(billingAddressRequest()));

        Customer result = converter.toDomain(request);

        assertThat(result.getId()).isNull();
        assertThat(result.getCreatedBy()).isNull();
        assertThat(result.getCustomerNumber()).isNull();
        assertThat(result.getCreatedAt()).isNull();
        assertThat(result.getUpdatedAt()).isNull();
    }

    @Test
    @DisplayName("toDomain(null) → null")
    void toDomain_null_returnsNull() {
        assertThat(converter.toDomain(null)).isNull();
    }

    // =========================================================================
    // applyUpdate — TD-23 (Boolean = tri-state) vs. full-replace
    // =========================================================================

    @Test
    @DisplayName("applyUpdate: gdprConsent/marketingConsent null NEPŘEPÍŠE uloženou hodnotu (TD-23)")
    void applyUpdate_nullConsents_keepExistingValues() {
        Customer existing = individualCustomer();
        existing.setGdprConsent(true);
        existing.setMarketingConsent(true);

        CustomerDto.UpdateRequest request = new CustomerDto.UpdateRequest();
        request.setFirstName("Jan");
        request.setLastName("Novák");
        request.setGdprConsent(null);
        request.setMarketingConsent(null);

        converter.applyUpdate(existing, request);

        assertThat(existing.isGdprConsent()).as("null = pole nebylo v requestu").isTrue();
        assertThat(existing.isMarketingConsent()).as("null = pole nebylo v requestu").isTrue();
    }

    @Test
    @DisplayName("applyUpdate: explicitní false souhlasy PŘEPÍŠE (druhá větev TD-23)")
    void applyUpdate_explicitFalseConsents_overwriteExistingValues() {
        Customer existing = individualCustomer();
        existing.setGdprConsent(true);
        existing.setMarketingConsent(true);

        CustomerDto.UpdateRequest request = new CustomerDto.UpdateRequest();
        request.setFirstName("Jan");
        request.setLastName("Novák");
        request.setGdprConsent(false);
        request.setMarketingConsent(false);

        converter.applyUpdate(existing, request);

        assertThat(existing.isGdprConsent()).isFalse();
        assertThat(existing.isMarketingConsent()).isFalse();
    }

    @Test
    @DisplayName("applyUpdate: explicitní true souhlasy nastaví z false na true")
    void applyUpdate_explicitTrueConsents_setValues() {
        Customer existing = individualCustomer();
        existing.setGdprConsent(false);
        existing.setMarketingConsent(false);

        CustomerDto.UpdateRequest request = new CustomerDto.UpdateRequest();
        request.setGdprConsent(true);
        request.setMarketingConsent(true);

        converter.applyUpdate(existing, request);

        assertThat(existing.isGdprConsent()).isTrue();
        assertThat(existing.isMarketingConsent()).isTrue();
    }

    @Test
    @DisplayName("applyUpdate: ostatní pole mají full-replace sémantiku — null je přepíše na null")
    void applyUpdate_otherFields_haveFullReplaceSemantics() {
        Customer existing = individualCustomer();
        existing.setInternalNote("původní poznámka");
        existing.setPrimaryEmail("stary@email.cz");
        existing.setDic("CZ87654321");

        CustomerDto.UpdateRequest request = new CustomerDto.UpdateRequest();
        request.setFirstName("Petr");
        request.setLastName("Svoboda");
        request.setInternalNote(null);
        request.setPrimaryEmail(null);
        request.setDic(null);

        converter.applyUpdate(existing, request);

        assertThat(existing.getFirstName()).isEqualTo("Petr");
        assertThat(existing.getLastName()).isEqualTo("Svoboda");
        assertThat(existing.getInternalNote()).isNull();
        assertThat(existing.getPrimaryEmail()).isNull();
        assertThat(existing.getDic()).isNull();
    }

    @Test
    @DisplayName("applyUpdate přenese každé editovatelné pole a vrátí tentýž objekt")
    void applyUpdate_appliesEveryEditableFieldAndReturnsSameInstance() {
        Customer existing = individualCustomer();

        CustomerDto.UpdateRequest request = new CustomerDto.UpdateRequest();
        request.setFirstName("Petr");
        request.setLastName("Svoboda");
        request.setBirthDate(LocalDate.of(1975, 11, 3));
        request.setCompanyName("Svoboda a syn");
        request.setIco("87654321");
        request.setDic("CZ87654321");
        request.setLegalForm("s.r.o.");
        request.setPrimaryEmail("petr@svoboda.cz");
        request.setPrimaryPhone("+420602111222");
        request.setPreferredContactChannel(ContactChannel.PHONE);
        request.setInternalNote("po telefonu");

        Customer result = converter.applyUpdate(existing, request);

        assertThat(result).as("mutace probíhá na místě, vrací se tentýž objekt").isSameAs(existing);
        assertThat(existing.getFirstName()).isEqualTo("Petr");
        assertThat(existing.getLastName()).isEqualTo("Svoboda");
        assertThat(existing.getBirthDate()).isEqualTo(LocalDate.of(1975, 11, 3));
        assertThat(existing.getCompanyName()).isEqualTo("Svoboda a syn");
        assertThat(existing.getIco()).isEqualTo("87654321");
        assertThat(existing.getDic()).isEqualTo("CZ87654321");
        assertThat(existing.getLegalForm()).isEqualTo("s.r.o.");
        assertThat(existing.getPrimaryEmail()).isEqualTo("petr@svoboda.cz");
        assertThat(existing.getPrimaryPhone()).isEqualTo("+420602111222");
        assertThat(existing.getPreferredContactChannel()).isEqualTo(ContactChannel.PHONE);
        assertThat(existing.getInternalNote()).isEqualTo("po telefonu");
    }

    @Test
    @DisplayName("applyUpdate nesahá na immutable a auditní pole (id, customerNumber, customerType, createdBy)")
    void applyUpdate_doesNotTouchImmutableFields() {
        Customer existing = individualCustomer();
        existing.setId(42L);
        existing.setCustomerNumber("ZNK-2026-0007");
        existing.setCreatedBy(9L);

        CustomerDto.UpdateRequest request = new CustomerDto.UpdateRequest();
        request.setFirstName("Petr");

        converter.applyUpdate(existing, request);

        assertThat(existing.getId()).isEqualTo(42L);
        assertThat(existing.getCustomerNumber()).isEqualTo("ZNK-2026-0007");
        assertThat(existing.getCustomerType()).isEqualTo(CustomerType.INDIVIDUAL);
        assertThat(existing.getCreatedBy()).isEqualTo(9L);
    }

    @Test
    @DisplayName("applyUpdate vrací null, chybí-li kterýkoli z argumentů")
    void applyUpdate_nullArguments_returnNull() {
        assertThat(converter.applyUpdate(null, new CustomerDto.UpdateRequest())).isNull();
        assertThat(converter.applyUpdate(individualCustomer(), null)).isNull();
    }

    // =========================================================================
    // toDetailResponse
    // =========================================================================

    @Test
    @DisplayName("toDetailResponse namapuje skalární pole i vnořené kolekce")
    void toDetailResponse_mapsScalarsAndNestedCollections() {
        Customer customer = individualCustomer();
        customer.setId(7L);
        customer.setCustomerNumber("ZNK-2026-0001");
        customer.setBirthDate(LocalDate.of(1980, 5, 17));
        customer.setCompanyName("Novák servis");
        customer.setIco("12345678");
        customer.setDic("CZ12345678");
        customer.setLegalForm("OSVČ");
        customer.setPrimaryEmail("jan@novak.cz");
        customer.setPrimaryPhone("+420777123456");
        customer.setGdprConsent(true);
        customer.setMarketingConsent(true);
        customer.setPreferredContactChannel(ContactChannel.EMAIL);
        customer.setInternalNote("VIP zákazník");
        customer.setLoyaltyPoints(120);
        customer.setActive(true);
        customer.setCreatedAt(OffsetDateTime.parse("2026-01-02T10:15:30Z"));
        customer.setUpdatedAt(OffsetDateTime.parse("2026-07-01T08:00:00Z"));
        customer.setAddresses(List.of(billingAddress()));
        customer.setContactPersons(List.of(contactPerson()));
        customer.setVehicles(List.of(vehicleOwnedBy(customer)));

        CustomerDto.DetailResponse response = converter.toDetailResponse(customer);

        assertThat(response.getId()).isEqualTo(7L);
        assertThat(response.getCustomerNumber()).isEqualTo("ZNK-2026-0001");
        assertThat(response.getCustomerType()).isEqualTo(CustomerType.INDIVIDUAL);
        assertThat(response.getDisplayName()).isEqualTo("Jan Novák");
        assertThat(response.getFirstName()).isEqualTo("Jan");
        assertThat(response.getLastName()).isEqualTo("Novák");
        assertThat(response.getBirthDate()).isEqualTo(LocalDate.of(1980, 5, 17));
        assertThat(response.getCompanyName()).isEqualTo("Novák servis");
        assertThat(response.getIco()).isEqualTo("12345678");
        assertThat(response.getDic()).isEqualTo("CZ12345678");
        assertThat(response.getLegalForm()).isEqualTo("OSVČ");
        assertThat(response.getPrimaryEmail()).isEqualTo("jan@novak.cz");
        assertThat(response.getPrimaryPhone()).isEqualTo("+420777123456");
        assertThat(response.isGdprConsent()).isTrue();
        assertThat(response.isMarketingConsent()).isTrue();
        assertThat(response.getPreferredContactChannel()).isEqualTo(ContactChannel.EMAIL);
        assertThat(response.getInternalNote()).isEqualTo("VIP zákazník");
        assertThat(response.getLoyaltyPoints()).isEqualTo(120);
        assertThat(response.isActive()).isTrue();
        assertThat(response.getCreatedAt()).isEqualTo(OffsetDateTime.parse("2026-01-02T10:15:30Z"));
        assertThat(response.getUpdatedAt()).isEqualTo(OffsetDateTime.parse("2026-07-01T08:00:00Z"));

        assertThat(response.getAddresses()).hasSize(1);
        assertThat(response.getAddresses().getFirst().getCity()).isEqualTo("Praha");
        assertThat(response.getContactPersons()).hasSize(1);
        assertThat(response.getContactPersons().getFirst().getLastName()).isEqualTo("Dvořák");
        assertThat(response.getVehicles()).hasSize(1);
        assertThat(response.getVehicles().getFirst().getVin()).isEqualTo("TMBJJ7NE0E0123456");
    }

    @Test
    @DisplayName("toDetailResponse nezamění souhlasy GDPR a marketing (každý má vlastní pole)")
    void toDetailResponse_doesNotSwapConsentFlags() {
        Customer customer = individualCustomer();
        customer.setGdprConsent(true);
        customer.setMarketingConsent(false);

        CustomerDto.DetailResponse response = converter.toDetailResponse(customer);

        assertThat(response.isGdprConsent()).isTrue();
        assertThat(response.isMarketingConsent()).isFalse();
    }

    @Test
    @DisplayName("toDetailResponse: chybějící kolekce zůstanou null, nespadne to")
    void toDetailResponse_nullCollections_areLeftNull() {
        Customer customer = individualCustomer();
        customer.setAddresses(null);
        customer.setContactPersons(null);
        customer.setVehicles(null);

        CustomerDto.DetailResponse response = converter.toDetailResponse(customer);

        assertThat(response.getAddresses()).isNull();
        assertThat(response.getContactPersons()).isNull();
        assertThat(response.getVehicles()).isNull();
        assertThat(response.getDisplayName()).isEqualTo("Jan Novák");
    }

    @Test
    @DisplayName("toDetailResponse(null) → null")
    void toDetailResponse_null_returnsNull() {
        assertThat(converter.toDetailResponse(null)).isNull();
    }

    // =========================================================================
    // toListResponses
    // =========================================================================

    @Test
    @DisplayName("toListResponses zachová pořadí a namapuje displayName podle typu zákazníka")
    void toListResponses_mapsRowsInOrder() {
        Customer individual = individualCustomer();
        individual.setId(1L);
        individual.setCustomerNumber("ZNK-2026-0001");
        individual.setPrimaryEmail("jan@novak.cz");
        individual.setPrimaryPhone("+420777123456");
        individual.setLoyaltyPoints(120);
        individual.setCreatedAt(OffsetDateTime.parse("2026-01-02T10:15:30Z"));

        Customer company = new Customer();
        company.setId(2L);
        company.setCustomerType(CustomerType.COMPANY);
        company.setCompanyName("Autodíly s.r.o.");
        company.setCustomerNumber("ZNK-2026-0002");
        company.setPrimaryEmail("info@autodily.cz");
        company.setPrimaryPhone("+420541123456");
        company.setLoyaltyPoints(0);
        company.setActive(false);

        List<CustomerDto.ListResponse> result = converter.toListResponses(List.of(individual, company));

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getId()).isEqualTo(1L);
        assertThat(result.get(0).getCustomerNumber()).isEqualTo("ZNK-2026-0001");
        assertThat(result.get(0).getCustomerType()).isEqualTo(CustomerType.INDIVIDUAL);
        assertThat(result.get(0).getDisplayName()).isEqualTo("Jan Novák");
        assertThat(result.get(0).getPrimaryEmail()).isEqualTo("jan@novak.cz");
        assertThat(result.get(0).getPrimaryPhone()).isEqualTo("+420777123456");
        assertThat(result.get(0).getLoyaltyPoints()).isEqualTo(120);
        assertThat(result.get(0).getCreatedAt()).isEqualTo(OffsetDateTime.parse("2026-01-02T10:15:30Z"));
        assertThat(result.get(0).isActive()).isTrue();

        assertThat(result.get(1).getId()).isEqualTo(2L);
        assertThat(result.get(1).getCustomerNumber()).isEqualTo("ZNK-2026-0002");
        assertThat(result.get(1).getCustomerType()).isEqualTo(CustomerType.COMPANY);
        assertThat(result.get(1).getDisplayName()).isEqualTo("Autodíly s.r.o.");
        assertThat(result.get(1).getPrimaryEmail()).isEqualTo("info@autodily.cz");
        assertThat(result.get(1).isActive()).isFalse();
    }

    // =========================================================================
    // Customer.getDisplayName — doménová logika s větvením
    // =========================================================================

    @Test
    @DisplayName("displayName: COMPANY vrací název firmy, ne jméno osoby")
    void displayName_company_usesCompanyName() {
        Customer company = new Customer();
        company.setCustomerType(CustomerType.COMPANY);
        company.setCompanyName("Autodíly s.r.o.");
        company.setFirstName("Jan");
        company.setLastName("Novák");

        assertThat(company.getDisplayName()).isEqualTo("Autodíly s.r.o.");
    }

    @Test
    @DisplayName("displayName: INDIVIDUAL vrací jméno a příjmení")
    void displayName_individual_usesPersonName() {
        assertThat(individualCustomer().getDisplayName()).isEqualTo("Jan Novák");
    }

    @Test
    @DisplayName("displayName: chybějící část jména nezpůsobí NullPointerException")
    void displayName_missingNameParts_doesNotThrow() {
        Customer onlyFirstName = new Customer();
        onlyFirstName.setCustomerType(CustomerType.INDIVIDUAL);
        onlyFirstName.setFirstName("Jan");

        Customer onlyLastName = new Customer();
        onlyLastName.setCustomerType(CustomerType.INDIVIDUAL);
        onlyLastName.setLastName("Novák");

        assertThat(onlyFirstName.getDisplayName()).isEqualTo("Jan");
        assertThat(onlyLastName.getDisplayName()).isEqualTo(" Novák");
    }

    // =========================================================================
    // Fixtury
    // =========================================================================

    private static Customer individualCustomer() {
        Customer customer = new Customer();
        customer.setCustomerType(CustomerType.INDIVIDUAL);
        customer.setFirstName("Jan");
        customer.setLastName("Novák");
        customer.setActive(true);
        return customer;
    }

    private static AddressDto.CreateRequest billingAddressRequest() {
        AddressDto.CreateRequest address = new AddressDto.CreateRequest();
        address.setAddressType(AddressType.BILLING);
        address.setDefault(true);
        address.setStreet("Testovací");
        address.setStreetNumber("1");
        address.setCity("Praha");
        address.setPostalCode("11000");
        address.setCountryCode("CZ");
        return address;
    }

    private static Address billingAddress() {
        Address address = new Address();
        address.setId(3L);
        address.setAddressType(AddressType.BILLING);
        address.setDefault(true);
        address.setStreet("Testovací");
        address.setStreetNumber("1");
        address.setCity("Praha");
        address.setPostalCode("11000");
        address.setCountryCode("CZ");
        return address;
    }

    private static ContactPerson contactPerson() {
        ContactPerson person = new ContactPerson();
        person.setId(5L);
        person.setFirstName("Eva");
        person.setLastName("Dvořák");
        person.setPosition("nákup");
        person.setEmail("eva@firma.cz");
        person.setPrimary(true);
        person.setActive(true);
        return person;
    }

    private static Vehicle vehicleOwnedBy(Customer owner) {
        Vehicle vehicle = new Vehicle();
        vehicle.setId(11L);
        vehicle.setVin("TMBJJ7NE0E0123456");
        vehicle.setLicensePlate("1AB 2345");
        vehicle.setBrand("Škoda");
        vehicle.setModel("Octavia");
        vehicle.setActive(true);
        vehicle.setCustomerId(owner.getId());
        vehicle.setCustomer(owner);
        return vehicle;
    }
}
