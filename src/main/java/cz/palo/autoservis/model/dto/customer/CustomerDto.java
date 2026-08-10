package cz.palo.autoservis.model.dto.customer;

import cz.palo.autoservis.model.dto.vehicle.VehicleDto;
import cz.palo.autoservis.model.enums.ContactChannel;
import cz.palo.autoservis.model.enums.CustomerType;
import cz.palo.autoservis.validation.ValidCustomerRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.Data;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * DTO pro entitu zákazníka.
 */
public class CustomerDto {

    /** Request DTO pro založení nového zákazníka. */
    @Data
    @ValidCustomerRequest
    public static class CreateRequest {

        @NotNull(message = "Typ zákazníka je povinný")
        private CustomerType customerType;

        // INDIVIDUAL
        @Size(max = 100)
        private String firstName;

        @Size(max = 100)
        private String lastName;

        private LocalDate birthDate;

        // COMPANY
        @Size(max = 255)
        private String companyName;

        @Pattern(regexp = "^$|^\\d{8}$", message = "IČO musí mít přesně 8 číslic")
        private String ico;

        @Pattern(regexp = "^$|^CZ\\d{8,10}$", message = "DIČ musí být ve formátu CZ + 8-10 číslic")
        private String dic;

        @Size(max = 100)
        private String legalForm;

        @Email(message = "Neplatný formát emailu")
        @Size(max = 255)
        private String primaryEmail;

        @Pattern(regexp = "^$|^\\+?[\\d\\s\\-()]{7,20}$", message = "Neplatný formát telefonu")
        private String primaryPhone;

        @NotNull
        private boolean gdprConsent;

        private boolean marketingConsent;

        private ContactChannel preferredContactChannel;

        private String internalNote;

        @NotEmpty(message = "Adresa zákazníka je povinná")
        @Valid
        @Size(max = 2, message = "Adresa zákazníka může mít maximálně 2 záznamy")
        private List<AddressDto.CreateRequest> addresses;
    }

    /** Request DTO pro úpravu existujícího zákazníka. */
    @Data
    public static class UpdateRequest {

        @Size(max = 100)
        private String firstName;

        @Size(max = 100)
        private String lastName;

        private LocalDate birthDate;

        @Size(max = 255)
        private String companyName;

        @Pattern(regexp = "^$|^\\d{8}$", message = "IČO musí mít přesně 8 číslic")
        private String ico;

        @Pattern(regexp = "^$|^CZ\\d{8,10}$", message = "DIČ musí být ve formátu CZ + 8-10 číslic")
        private String dic;

        @Size(max = 100)
        private String legalForm;

        @Email(message = "Neplatný formát emailu")
        @Size(max = 255)
        private String primaryEmail;

        @Pattern(regexp = "^$|^\\+?[\\d\\s\\-()]{7,20}$", message = "Neplatný formát telefonu")
        private String primaryPhone;

        // Boolean (ne boolean) — TD-23: PATCH-tolerantní kontrakt. Chybějící pole v JSON
        // (null) se v CustomerConverter.applyUpdate nepřepisuje, na rozdíl od ostatních
        // polí zde (full-replace sémantika). Frontend odesílá obě pole vždy (kontrolované
        // checkboxy), takže chování se pro stávající klienty nemění.
        private Boolean gdprConsent;

        private Boolean marketingConsent;

        private ContactChannel preferredContactChannel;

        private String internalNote;

        // TD-42: adresy jdou editovat i u existujícího zákazníka. Volitelné pole se
        // sémantikou stejnou jako gdprConsent/marketingConsent (TD-23): null = neměnit,
        // neprázdný seznam = full-replace celé sady (server starou smaže a vloží tuto).
        // Faktura drží immutable snapshot (invoice_party), přepis adres ji neovlivní.
        //
        // POZOR na volbu anotace (audit KN-15): `min = 1` je tu proto, že prázdný seznam
        // se dřív choval jako „smaž všechny adresy" — full-replace prázdnou sadou. Nelze
        // použít @NotEmpty jako u CreateRequest: ten odmítá i null, čímž by z adres udělal
        // povinné pole při KAŽDÉ editaci a zabil sémantiku „null = neměnit". @Size null
        // naopak ignoruje, takže odmítne jen skutečně prázdný seznam.
        @Valid
        @Size(min = 1, max = 2,
                message = "Adresa zákazníka musí mít 1 až 2 záznamy; vynechte pole úplně, pokud adresy měnit nechcete")
        private List<AddressDto.CreateRequest> addresses;
    }

    /** Response DTO pro stránkované seznamové endpointy. */
    @Data
    public static class ListResponse {
        private Long id;
        private String customerNumber;
        private CustomerType customerType;
        /** Celé jméno (INDIVIDUAL) nebo název firmy (COMPANY). */
        private String displayName;
        private String primaryEmail;
        private String primaryPhone;
        private int loyaltyPoints;
        private boolean active;
        private OffsetDateTime createdAt;
    }

    /** Response DTO pro detailové endpointy — včetně adres, kontaktních osob a vozidel. */
    @Data
    public static class DetailResponse {
        private Long id;
        private String customerNumber;
        private CustomerType customerType;
        private String displayName;

        // INDIVIDUAL
        private String firstName;
        private String lastName;
        private LocalDate birthDate;

        // COMPANY
        private String companyName;
        private String ico;
        private String dic;
        private String legalForm;

        private String primaryEmail;
        private String primaryPhone;
        private boolean marketingConsent;
        private boolean gdprConsent;
        private ContactChannel preferredContactChannel;
        private String internalNote;
        private int loyaltyPoints;
        private boolean active;

        private OffsetDateTime createdAt;
        private OffsetDateTime updatedAt;

        private List<AddressDto.Response> addresses;
        private List<ContactPersonDto.Response> contactPersons;
        private List<VehicleDto.ListResponse> vehicles;
    }

    /**
     * Odlehčený souhrn zákazníka vkládaný do odpovědí vozidel.
     * Obsahuje jen pole potřebná k zobrazení identity zákazníka.
     */
    @Data
    public static class SummaryResponse {
        private Long id;
        private String customerNumber;
        private CustomerType customerType;
        /** Celé jméno (INDIVIDUAL) nebo název firmy (COMPANY). */
        private String displayName;
        private String primaryEmail;
        private String primaryPhone;
        private int loyaltyPoints;
        private boolean active;
        private OffsetDateTime createdAt;
    }
}
