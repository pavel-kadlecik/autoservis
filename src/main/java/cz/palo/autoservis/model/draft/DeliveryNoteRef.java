package cz.palo.autoservis.model.draft;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Odkaz na dodací list uvedený na faktuře (LKQ: „Dodací list č. X celkem…").
 * Párování na existující příjemku a resolution (provázat/naskladnit) řeší
 * fáze 7 — tvar je připravený, aby se payload nemusel migrovat.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeliveryNoteRef {

    private String number;
    private BigDecimal totalInclVat;
    private Long matchedReceiptId;
    /** LINKED = jen provázat (nenaskladňovat znovu), RESTOCKED = naskladnit. */
    private String resolution;
}
