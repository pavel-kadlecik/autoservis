package cz.palo.autoservis.model.dto.customer;

import com.fasterxml.jackson.annotation.JsonProperty;
import cz.palo.autoservis.model.enums.AddressType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;
import lombok.Getter;

/**
 * DTO pro entitu adresy zákazníka.
 */
public class AddressDto {

    /** Request DTO pro založení nové adresy. */
    @Data
    public static class CreateRequest {

        @NotNull
        private AddressType addressType;

        @JsonProperty("isDefault")
        @Getter(onMethod_ = @JsonProperty("isDefault"))
        private boolean isDefault;

        @NotBlank
        private String street;

        @NotBlank
        private String streetNumber;

        @NotBlank
        private String city;

        @NotBlank
        @Pattern(regexp = "^\\d{3}\\s?\\d{2}$", message = "Neplatné PSČ")
        private String postalCode;

        private String countryCode = "CZ";
    }

    /** Response DTO pro všechny čtecí operace. */
    @Data
    public static class Response {
        private Long id;
        private AddressType addressType;

        @JsonProperty("isDefault")
        @Getter(onMethod_ = @JsonProperty("isDefault"))
        private boolean isDefault;

        private String street;
        private String streetNumber;
        private String city;
        private String postalCode;
        private String countryCode;
    }

}
