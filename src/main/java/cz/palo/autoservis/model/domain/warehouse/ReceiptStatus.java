package cz.palo.autoservis.model.domain.warehouse;

/** Stav příjemky v procesu AI importu z PDF. Mapuje se na warehouse.receipt_status. */
public enum ReceiptStatus {
    PENDING_REVIEW,   // AI extrahovala, čeká na potvrzení člověkem
    CONFIRMED,        // mechanik zkontroloval a potvrdil
    REJECTED,         // chybná extrakce, zamítnuto (nic se nematerializovalo)
    CANCELLED         // potvrzená příjemka stornována kompenzačními pohyby (V43, R-C)
}
