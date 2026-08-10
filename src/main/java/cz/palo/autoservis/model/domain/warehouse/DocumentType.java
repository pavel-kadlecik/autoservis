package cz.palo.autoservis.model.domain.warehouse;

/** Druh zdrojového dokladu příjemky. Mapuje se na warehouse.document_type. */
public enum DocumentType {
    INVOICE,        // daňový doklad (faktura)
    DELIVERY_NOTE,  // dodací list (není daňový doklad — bez rekapitulace DPH)
    STOCK_TAKE      // V44: pseudo-příjemka inventurních přebytků (bez dodavatele a částek)
}
