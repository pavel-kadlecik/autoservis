package cz.palo.autoservis.model.domain.warehouse;

/** Typ skladového pohybu. Mapuje se na warehouse.movement_type. */
public enum MovementType {
    RECEIPT,      // příjem z faktury (+)
    ISSUE,        // výdej do zakázky (-)
    ISSUE_RETURN, // vratka na sklad ze zakázky (+)
    ADJUSTMENT,   // inventurní korekce (+/-)
    RETURN,       // vratka dodavateli (-)
    WRITE_OFF     // odpis (-)
}
