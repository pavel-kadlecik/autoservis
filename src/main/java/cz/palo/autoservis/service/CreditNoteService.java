package cz.palo.autoservis.service;

import cz.palo.autoservis.model.dto.billing.CreditNoteDto;

/**
 * Opravný daňový doklad (dobropis, §45 ZDPH) — vytvoření z faktury, vystavení, detail.
 * Plný dobropis (MVP): rozdíly = záporné souhrny původní faktury. Částečný dobropis je budoucí rozšíření.
 */
public interface CreditNoteService {

    /**
     * Založí koncept (DRAFT) dobropisu k dané faktuře.
     *
     * <p>K jedné faktuře smí být nejvýš <strong>jeden aktivní</strong> opravný doklad (audit KN-8):
     * každý nese celou zápornou fakturu (MVP = plný dobropis), takže druhý by znamenal dvojnásobné
     * snížení daně na výstupu. Vynucuje i částečný unikát {@code uq_credit_notes_original_active} (V66).
     *
     * @throws cz.palo.autoservis.exception.ResourceNotFoundException faktura neexistuje
     * @throws cz.palo.autoservis.exception.BusinessRuleException     faktura není opravitelná (jen ISSUED/PAID),
     *                                                                nebo už k ní aktivní dobropis existuje
     *                                                                ({@code INVOICE_ALREADY_CREDITED})
     */
    CreditNoteDto.DetailResponse createFromInvoice(CreditNoteDto.CreateRequest request, Long userId);

    /**
     * Dobropisy k dané faktuře (i stornované), seřazené podle ID.
     *
     * <p>Umožňuje detailu faktury poznat, že opravný doklad už existuje, a otevřít ho —
     * jinak by šel dobropis jen založit a rozdělaný koncept by se ztratil.
     *
     * @return prázdný seznam, pokud k faktuře žádný dobropis není (nikdy {@code null}, N-01)
     */
    java.util.List<CreditNoteDto.DetailResponse> getByInvoiceId(Long invoiceId);

    /** Vystaví dobropis (DRAFT→ISSUED); DB trigger přidělí číslo řady „OD". */
    CreditNoteDto.DetailResponse issue(Long id, Long userId);

    /** Detail dobropisu vč. §45 rozdílů a stran odvozených z původní faktury. */
    CreditNoteDto.DetailResponse getById(Long id);

    /**
     * Smaže <strong>koncept</strong> opravného dokladu.
     *
     * <p>Koncept nemá číslo řady „OD" a není dokladem, takže není co archivovat (výjimka z R-06,
     * stejně jako u konceptu faktury — {@code konvence.md} §18). Bez toho byla omylem založená
     * oprava slepou uličkou: vystavit ji obsluha nechce, zahodit nemohla, a nový dobropis k téže
     * faktuře už založit nešlo ({@code INVOICE_ALREADY_CREDITED} počítá i s koncepty).
     * Vystavený dobropis smazat nelze — má číslo a je platným daňovým dokladem.
     *
     * @param id     ID opravného dokladu
     * @param userId přihlášený uživatel
     */
    void delete(Long id, Long userId);
}
