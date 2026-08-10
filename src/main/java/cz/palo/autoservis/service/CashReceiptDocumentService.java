package cz.palo.autoservis.service;

/**
 * Tisková podoba příjmového pokladního dokladu (PPD) — PDF (A4).
 */
public interface CashReceiptDocumentService {

    /**
     * Vyrenderuje PDF pokladního dokladu.
     *
     * @param cashReceiptId ID pokladního dokladu
     * @return PDF jako bajty
     */
    byte[] renderPdf(Long cashReceiptId);
}
