package cz.palo.autoservis.service;

/**
 * Tisková podoba opravného daňového dokladu (dobropisu) — PDF (E5.2).
 */
public interface CreditNoteDocumentService {

    /**
     * Vyrenderuje PDF opravného dokladu.
     *
     * @param creditNoteId ID dobropisu
     * @return PDF jako bajty
     */
    byte[] renderPdf(Long creditNoteId);
}
