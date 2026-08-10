package cz.palo.autoservis.model.domain.warehouse;

import lombok.*;
import java.time.OffsetDateTime;

/** Dodavatel skladu (warehouse.suppliers). */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Supplier {
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
