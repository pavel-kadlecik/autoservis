package cz.palo.autoservis.model.dto.customer;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * DTO pro entitu kontaktní osoby.
 */
public class ContactPersonDto {

    /** Response DTO pro všechny čtecí operace. */
    @Data
    public static class Response {
        private Long id;
        private String firstName;
        private String lastName;
        private String position;
        private String email;
        private String phone;
        private boolean primary;

        @JsonProperty("isActive")
        private boolean active;
    }
}
