package cz.palo.autoservis.model.domain.customer;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

/**
 * Doménový objekt kontaktní osoby — mapuje se na {@code customer.contact_persons}.
 *
 * <p>Čisté POJO bez JPA anotací a závislostí na Springu.
 * Používá se hlavně u firemních zákazníků ({@code COMPANY}), kde může existovat
 * víc kontaktů s různými rolemi (např. správce vozového parku, účtárna, ředitel).
 *
 * <p>Kontaktní osoba může volitelně mít vlastní přístup do portálu
 * přes navázaný účet {@code security.users} ({@code userId}).
 * Nejvýše jedna kontaktní osoba na zákazníka smí být označená jako hlavní —
 * vynucuje částečný unikátní index v databázi.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContactPerson {

    private Long id;
    private Long customerId;
    private Long userId;
    private String firstName;
    private String lastName;
    private String position;
    private String email;
    private String phone;
    private boolean primary;
    private boolean active;
    private String note;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
