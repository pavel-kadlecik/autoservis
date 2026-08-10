package cz.palo.autoservis.model.domain.warehouse;

/** Důvod vratky dodavateli. Mapuje se na warehouse.return_reason. */
public enum ReturnReason {
    DEFECTIVE,          // vadný
    WRONG_PART,         // špatně dodaný / objednaný
    DAMAGED_TRANSPORT,  // poškozený přepravou
    SURPLUS,            // přebytek
    OTHER
}
