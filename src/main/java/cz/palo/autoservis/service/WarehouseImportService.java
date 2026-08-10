package cz.palo.autoservis.service;

import cz.palo.autoservis.model.domain.warehouse.DocumentType;
import cz.palo.autoservis.model.dto.warehouse.ReceiptDraftDto;

/** Import dokladu dodavatele (faktura / dodací list) z PDF do skladu. */
public interface WarehouseImportService {

    /**
     * Zpracuje PDF doklad: extrahuje data, složí kanonický draft, provede
     * deterministické kontroly a uloží JEN hlavičku příjemky + JSONB draft
     * ve stavu PENDING_REVIEW. Produkty, šarže ani pohyby nevznikají —
     * materializuje je až potvrzení příjemky (review workflow).
     *
     * <p>Kromě PDF přijímá i fotku nebo sken dokladu (rozhodnutí R-D) — rozliší je
     * {@code mimeType}, zbytek zpracování je shodný.
     *
     * @param pdfBytes     obsah souboru (PDF nebo obrázek)
     * @param filename     původní název souboru
     * @param documentType typ dokladu zvolený uživatelem při uploadu
     * @param mimeType     MIME typ nahraného souboru
     * @param userId       přihlášený uživatel (created_by)
     * @return souhrn draftu pro kontrolu mechanikem
     */
    ReceiptDraftDto.ImportResponse importFromPdf(byte[] pdfBytes, String filename,
                                                 DocumentType documentType, String mimeType,
                                                 Long userId);

    /**
     * Zpracuje doklad ve formátu ISDOC (český standard e-faktury): naparsuje XML
     * do téhož kanonického draftu a projde stejnou pipeline jako AI import —
     * kontroly, párování, uložení jen draftu. Bez AI: data jsou strojová, všechna
     * přečtená pole jsou VERBATIM.
     *
     * @param xmlBytes obsah souboru .isdoc / .xml
     * @param filename původní název souboru
     * @param userId   přihlášený uživatel (created_by)
     * @return souhrn draftu pro kontrolu mechanikem
     */
    ReceiptDraftDto.ImportResponse importFromIsdoc(byte[] xmlBytes, String filename, Long userId);
}
