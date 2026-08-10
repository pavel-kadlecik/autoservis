package cz.palo.autoservis.model.domain.employee;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * Doménový objekt zaměstnance — mapuje se na {@code employee.employees}.
 *
 * <p>Čisté POJO bez JPA anotací a závislostí na Springu. Databázové sloupce
 * na pole mapuje MyBatis přes {@code ResultMap} v {@code EmployeeMapper.xml}.
 *
 * <p>Zaměstnanec je osoba, která provádí práci na položce zakázky
 * ({@code "order".order_items.employee_id}, D-1). Jeho {@link #hourlyRate}
 * je náklad práce; při přiřazení k položce LABOR se sazba snapshotuje do
 * {@code order_items.purchase_price} (D-3) — pozdější změna sazby tak nikdy
 * nepřepíše historické položky. Sazba tady je proto jen <em>aktuální</em>
 * hodnota pro předvyplnění nových snapshotů.
 *
 * <p>Zaměstnanci se nikdy nemažou natvrdo (D-4): soft-delete přes
 * {@link #active} udrží historické FK z položek zakázek i po odchodu člověka.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Employee {

    /** Primární klíč — generuje {@code employee.employees_id_seq}. */
    private Long id;

    /**
     * Volitelná mezischémová FK na {@code security.users.id} (D-5).
     * {@code null} u zaměstnanců bez přihlašovacího účtu — zaměstnanec není
     * nutně uživatel systému. Když je vyplněná, je unikátní.
     */
    private Long userId;

    /** Jméno. NOT NULL. */
    private String firstName;

    /** Příjmení. NOT NULL. */
    private String lastName;

    /** Pracovní pozice (např. „Automechanik", „Diagnostik"). Nullable — volný text. */
    private String position;

    /**
     * Hodinová sazba = náklad práce. Nullable (neznámá sazba). Jen <em>aktuální</em>
     * hodnota; historickou přesnost zaručuje snapshot do
     * {@code order_items.purchase_price} v okamžiku přiřazení (D-3).
     */
    private BigDecimal hourlyRate;

    /** Datum nástupu. NOT NULL. */
    private LocalDate hiredAt;

    /** Datum odchodu. {@code null} = stále zaměstnán. Musí být {@code >= hiredAt}. */
    private LocalDate leftAt;

    /** Příznak soft delete. {@code false} = deaktivovaný zaměstnanec (D-4). */
    private boolean active;

    /** Čas vytvoření záznamu. Nastavuje databázový default. */
    private OffsetDateTime createdAt;

    /** Čas poslední změny. Udržuje trigger {@code trg_employees_updated_at}. */
    private OffsetDateTime updatedAt;

    /** Mezischémová FK na {@code security.users.id}. Auditní stopa — kdo záznam založil. */
    private Long createdBy;
}
