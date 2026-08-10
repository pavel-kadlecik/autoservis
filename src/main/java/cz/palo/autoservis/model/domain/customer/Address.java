package cz.palo.autoservis.model.domain.customer;

import cz.palo.autoservis.model.enums.AddressType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

/**
 * Doménový objekt adresy zákazníka — mapuje se na {@code customer.addresses}.
 *
 * <p>Čisté POJO bez JPA anotací a závislostí na Springu.
 * Jeden zákazník může mít víc adres různých typů
 * ({@code BILLING}, {@code CONTACT}, {@code HEADQUARTERS}).
 * Nejvýše jedna adresa od každého typu smí být označená jako výchozí —
 * vynucuje částečný unikátní index v databázi.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Address {

    private Long id;
    private Long customerId;
    private AddressType addressType;
    private boolean isDefault;
    private String street;
    private String streetNumber;
    private String city;
    private String postalCode;
    private String countryCode;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
