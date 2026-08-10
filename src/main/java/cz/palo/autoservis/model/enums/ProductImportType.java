package cz.palo.autoservis.model.enums;

/**
 * Typ identifikátoru dokladu při importu příjemky —
 * podle čeho se doklad dohledává (číslo faktury vs. číslo objednávky).
 */
public enum ProductImportType {

    INVOICE_NUMBER,
    ORDER_NUMBER,

}
