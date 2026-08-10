package cz.palo.autoservis.service;

/**
 * Vykresluje faktury do podoby dokumentu (PDF; HTML je jen interní mezikrok renderu).
 *
 * <p>Data se vždy berou z {@link InvoiceService#getById(Long)}, takže dokument
 * zůstává konzistentní s tím, co aplikace zobrazuje (single source of truth).
 */
public interface InvoiceDocumentService {

    /**
     * Vykreslí fakturu do PDF dokumentu (A4).
     *
     * @param invoiceId ID faktury
     * @return bajty PDF
     */
    byte[] renderPdf(Long invoiceId);
}
