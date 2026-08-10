package cz.palo.autoservis.model.domain.customer;

import cz.palo.autoservis.model.domain.vehicle.Vehicle;
import cz.palo.autoservis.model.dto.customer.CustomerDto;
import cz.palo.autoservis.model.enums.ContactChannel;
import cz.palo.autoservis.model.enums.CustomerType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Doménový objekt zákazníka — mapuje se na {@code customer.customers}.
 *
 * <p>Čisté POJO bez JPA anotací a závislostí na Springu.
 * Databázové sloupce na pole mapuje MyBatis přes {@code ResultMap}
 * v {@code CustomerMapper.xml} s {@code map-underscore-to-camel-case}.
 *
 * <p>Podporuje dva typy zákazníka přes diskriminátor {@code customerType}:
 * {@code INDIVIDUAL} (soukromá osoba) a {@code COMPANY} (právnická osoba nebo OSVČ).
 * Pole specifická pro jeden typ jsou u druhého nullable; hlídají je DB constrainty.
 *
 * <p>Vnořené kolekce ({@code addresses}, {@code contactPersons}, {@code vehicles})
 * se načítají podle potřeby přes MyBatis {@code <collection>} mapování — mohou být
 * {@code null} podle toho, který dotaz byl použit.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Customer {

    private Long id;
    private Long userId;
    private CustomerType customerType;
    private String customerNumber;

    // Individual
    private String firstName;
    private String lastName;
    private LocalDate birthDate;

    // Company
    private String companyName;
    private String ico;
    private String dic;
    private String legalForm;

    // Contact
    private String primaryEmail;
    private String primaryPhone;

    // GDPR
    private boolean marketingConsent;
    private OffsetDateTime marketingConsentAt;
    private boolean gdprConsent;
    private OffsetDateTime gdprConsentAt;

    private ContactChannel preferredContactChannel;
    private String internalNote;
    private int loyaltyPoints;
    private boolean active;

    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
    private Long createdBy;

    // Vnořené objekty — načítají se přes MyBatis collection/association mapování.
    // Mohou být null podle použitého dotazu (seznam vs. detail).
    private List<Address> addresses;
    private List<ContactPerson> contactPersons;
    private List<Vehicle> vehicles;

    /**
     * Vrací zobrazované jméno vhodné pro popisky v UI.
     * U soukromých osob: jméno + příjmení.
     * U firem: název firmy.
     *
     * @return lidsky čitelné jméno zákazníka
     */
    public String getDisplayName() {
        if (CustomerType.COMPANY == customerType) {
            return companyName;
        }
        return (firstName != null ? firstName : "") +
               (lastName  != null ? " " + lastName : "");
    }

    /**
     * Převede zákazníka na odlehčené souhrnné DTO.
     * Používá se při vkládání údajů o zákazníkovi do odpovědí vozidel a zakázek.
     *
     * @return souhrnné response DTO
     */
    public CustomerDto.SummaryResponse toSummaryResponse() {
        CustomerDto.SummaryResponse summaryResponse = new CustomerDto.SummaryResponse();
        summaryResponse.setId(this.id);
        summaryResponse.setCustomerNumber(this.customerNumber);
        summaryResponse.setCustomerType(this.customerType);
        summaryResponse.setDisplayName(getDisplayName());
        summaryResponse.setPrimaryEmail(this.primaryEmail);
        summaryResponse.setPrimaryPhone(this.primaryPhone);
        summaryResponse.setLoyaltyPoints(this.loyaltyPoints);
        summaryResponse.setActive(this.active);
        summaryResponse.setCreatedAt(this.createdAt);
        return summaryResponse;
    }
}
