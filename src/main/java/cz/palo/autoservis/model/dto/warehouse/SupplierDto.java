package cz.palo.autoservis.model.dto.warehouse;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

public class SupplierDto {

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class DetailResponse {
        private Long id;
        private String name;
        private String registrationNumber;
        private String vatId;
        private String street;
        private String city;
        private String postalCode;
        private String countryCode;
        private String bankAccount;
        private String iban;
        private String swift;
        private String email;
        private String phone;
        private boolean active;
        private OffsetDateTime createdAt;
        private OffsetDateTime updatedAt;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class UpdateRequest {

        @NotBlank(message = "Název dodavatele je povinný")
        @Size(max = 255, message = "Název může mít nejvýše 255 znaků")
        private String name;

        @Size(max = 30, message = "IČO může mít maximalně 30 znaků")
        private String registrationNumber;

        @Size(max = 20, message = "DIČ může mít nejvýše 20 znaků")
        private String vatId;

        @Size(max = 255, message = "Ulice může mít nejvýše 255 znaků")
        private String street;

        @Size(max = 100, message = "Město může mít nejvýše 100 znaků")
        private String city;

        @Size(max = 10, message = "PSČ může mít nejvýše 10 znaků")
        private String postalCode;

        @Size(min = 2, max = 2, message = "Kód země má mít 2 znaky")
        private String countryCode;

        @Size(max = 50, message = "Číslo účtu může mít maximálně 50 znaků")
        private String bankAccount;

        @Size(max = 34, message = "IBAN může mít maximálně 34 znaky")
        private String iban;

        @Size(max = 11, message = "BIC může mít nejvýše 11 znaků")
        private String swift;

        @Email(message = "Neplatný formát emailu")
        @Size(max = 255, message = "E-mail může mít nejvýše 255 znaků")
        private String email;

        @Size(max = 30, message = "Telefonní číslo může mít maximálně 30 znaků")
        private String phone;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class ListResponse {
        private Long id;
        private String name;
        private String registrationNumber;
        private String vatId;
        private String street;
        private String city;
        private String email;
        private String phone;
        private boolean active;
    }


}
