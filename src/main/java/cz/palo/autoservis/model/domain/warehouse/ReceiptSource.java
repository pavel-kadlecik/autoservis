package cz.palo.autoservis.model.domain.warehouse;

/** Vstupní kanál, kterým draft příjemky vznikl. Mapuje se na warehouse.receipt_source. */
public enum ReceiptSource {
    AI_PDF,   // AI extrakce z nahraného PDF
    MANUAL,   // ručně založený draft ve formuláři
    ISDOC     // rezervováno: adaptér českého e-fakturačního XML
}
