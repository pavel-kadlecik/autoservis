package cz.palo.autoservis.service;

import cz.palo.autoservis.model.dto.billing.InvoiceDto;
import cz.palo.autoservis.model.dto.billing.InvoiceItemDto;
import cz.palo.autoservis.model.dto.billing.InvoiceSearchParams;
import cz.palo.autoservis.model.dto.pagination.PagedResponse;

import java.time.LocalDate;
import java.util.List;

/**
 * Service interface pro správu faktur.
 *
 * <p>Poskytuje CRUD operace pro faktury a jejich položky.
 * Položky faktury lze měnit, jen dokud je faktura ve stavu {@code DRAFT}.
 * Jakmile je faktura {@code ISSUED}, její obsah je neměnný — oprava se řeší
 * opravným daňovým dokladem (dobropisem), u nepředané faktury návratem
 * do konceptu ({@link #revokeIssue}).
 */
public interface InvoiceService {

    // =========================================================================
    // Faktura
    // =========================================================================

    /**
     * Vytvoří koncept faktury ze zadané zakázky, včetně stran dokladu a položek.
     * Vytvořená faktura se audituje na zadaného uživatele ({@code created_by}).
     *
     * @param createRequest request s údaji pro vytvoření faktury
     * @param userId ID uživatele, který vytvoření provádí
     * @return detail vytvořené faktury
     */
    InvoiceDto.DetailResponse createFromOrder(InvoiceDto.CreateRequest createRequest, Long userId);

    /**
     * Návrh dalšího čísla faktury podle masky číselné řady (V71) — pro předvyplnění
     * dialogu vytvoření faktury. Při vypnutém automatickém číslování vrací
     * {@code auto = false} bez návrhu. Nic nerezervuje.
     *
     * @param issueDate datum vystavení, ze kterého se odvodí období řady (null = dnešek)
     * @return návrh čísla a příznak, zda je automatické číslování zapnuté
     */
    InvoiceDto.NextNumberResponse suggestNextNumber(LocalDate issueDate);

    /**
     * Vrátí stránkovaný seznam faktur odpovídajících zadaným parametrům hledání.
     * Každý řádek nese číslo zakázky, zobrazované jméno zákazníka a spočtené součty.
     *
     * @param params parametry hledání a stránkování
     * @return stránkovaný seznam řádků faktur
     */
    PagedResponse<InvoiceDto.ListResponse> getPage(InvoiceSearchParams params);

    /**
     * Upraví existující fakturu. Změnit lze jen tato pole:
     * {@code due_date}, {@code constant_symbol}, {@code specific_symbol},
     * {@code payment_method}, {@code note}, {@code purchase_order_number}.
     * Stav se přes update nemění — jen přes issue/markPaid a spol.
     *
     * @param id            ID faktury
     * @param updateRequest validovaný request se změněnými poli
     * @param userId        ID přihlášeného uživatele
     * @return detail upravené faktury včetně položek
     */
    InvoiceDto.DetailResponse update(Long id, InvoiceDto.UpdateRequest updateRequest, Long userId);

    /**
     * Vystaví koncept faktury (DRAFT → ISSUED). Od tohoto okamžiku je faktura
     * neměnný právní doklad a její pole ani položky už nelze upravovat.
     *
     * <p>Doklad tu dostává <strong>číslo</strong> a variabilní symbol — koncept je nemá,
     * takže zrušený koncept nedělá do řady mezeru. Číslo posílá dialog vystavení — při zapnutém
     * automatu předvyplněné podle masky ({@link #suggestNextNumber}), jinak prázdné k ručnímu
     * zápisu; přepsat ho lze v obou režimech.
     *
     * @param id           ID faktury
     * @param issueRequest číslo dokladu, datum vystavení a volitelný variabilní symbol
     * @param userId       přihlášený uživatel
     * @return detail faktury po vystavení
     */
    InvoiceDto.DetailResponse issue(Long id, InvoiceDto.IssueRequest issueRequest, Long userId);

    /**
     * Označí vystavenou fakturu jako zaplacenou (ISSUED → PAID).
     *
     * @param id     ID faktury
     * @param userId přihlášený uživatel
     * @return detail faktury po označení
     */
    InvoiceDto.DetailResponse markPaid(Long id, Long userId);

    /**
     * Smaže fakturu i s položkami a stranami — koncept vždy, vystavenou jen dokud
     * není <strong>předaná</strong> ani <strong>zaplacená</strong> (V88).
     *
     * <p>Takový doklad nikdy neopustil firmu a není vykázaný v DPH, takže není co
     * archivovat; tvrdé mazání je proto vědomá výjimka z pravidla o soft-delete
     * (R-06, viz {@code konvence.md} §18). Zrušená faktura zároveň uvolní zakázku k úpravám
     * i k nové fakturaci. Předaný nebo zaplacený doklad smazat nelze — opravuje se
     * dobropisem (KN-1).
     *
     * @param id     ID faktury
     * @param userId přihlášený uživatel
     * @throws ResourceNotFoundException pokud faktura neexistuje
     * @throws BusinessRuleException     {@code INVOICE_NOT_DELETABLE} (předaná/zaplacená), příp.
     *                                   {@code INVOICE_HAS_LINKED_DOCUMENTS} (navázané doklady)
     */
    void delete(Long id, Long userId);

    /**
     * Mezery v číselné řadě faktur za aktuální období (V89).
     *
     * <p>Od V88 lze smazat nepředanou fakturu; u poslední v řadě se číslo uvolní, u starší
     * po ní zůstane díra a `MAX+1` ji sám nezavře. Zavírá se ručně — číslo je při vystavení
     * editovatelné. Tohle je jen upozornění, aby se na díru nepřišlo až u kontroly.
     *
     * <p>Hlídá se jen aktuální období a jen když je hlídání zapnuté ve Fakturačních údajích.
     */
    InvoiceDto.NumberGapsResponse findNumberGaps();

    /**
     * Potvrdí, že doklad dostal zákazník (V88).
     *
     * <p>Vystavení předání <strong>neznamená</strong> — aplikace fakturu neposílá a o odeslání
     * nic neví, takže by šlo o domněnku. Tímhle krokem ji nahrazuje tvrzení člověka, který to
     * ví. Do té chvíle jde omylem vystavenou fakturu ještě smazat; potom už jen dobropisem.
     *
     * @return faktura po označení
     */
    InvoiceDto.DetailResponse handOver(Long id, Long userId);

    /**
     * Vezme předání zpět (V88) — i „předáno" jde kliknout omylem.
     *
     * <p>U zaplacené faktury neprojde: kdo platí, doklad má.
     *
     * @return faktura po zrušení příznaku
     */
    InvoiceDto.DetailResponse revokeHandOver(Long id, Long userId);

    /**
     * Vezme zpět evidenci úhrady (2026-08-08): {@code PAID → ISSUED}.
     *
     * <p>Úhrada není daňový doklad, ale interní záznam — opravit v něm překlep je legitimní.
     * Do téhle změny bylo omylem kliknuté „Označit zaplaceno" nevratné a jediná cesta ven
     * vedla přes dobropis, který by ale zapsal „držím peníze a dlužím vratku".
     *
     * <p>Neprojde, existuje-li k faktuře <strong>platný pokladní doklad</strong> — ten má
     * vlastní číselnou řadu a stornuje se zvlášť.
     *
     * @return faktura po vzetí platby zpět
     */
    InvoiceDto.DetailResponse revokePayment(Long id, Long userId);

    /**
     * Vrátí vystavenou fakturu do konceptu (2026-08-08) — typicky kvůli špatně zadanému číslu.
     *
     * <p>Doklad, který nikam neodešel, se opravuje editací, ne dobropisem. Mazání a nové
     * vystavení by fungovalo taky, ale zahodí i vše ostatní (adresu, data, symboly) a nutí
     * fakturu skládat znovu ze zakázky. Tohle je ta šetrná cesta: <strong>uvolní se číslo
     * i variabilní symbol</strong>, zbytek zůstane.
     *
     * <p>Neprojde u faktury <strong>předané</strong> (zákazník ji má → dobropis),
     * <strong>zaplacené</strong> (nejdřív vzít platbu zpět) ani s navázaným
     * <strong>pokladním dokladem</strong> — číslovaný doklad nemůže viset na konceptu.
     *
     * @return faktura vrácená do konceptu
     */
    InvoiceDto.DetailResponse revokeIssue(Long id, Long userId);

    /**
     * Vrátí úplný detail faktury podle ID, včetně všech položek.
     *
     * @param id ID faktury
     * @return detail faktury
     */
    InvoiceDto.DetailResponse getById(Long id);

    /**
     * Vrátí úplný detail faktury podle jejího čísla.
     *
     * @param invoiceNumber číslo faktury (např. {@code 202607001} — formát YYYYMM + pořadové číslo, konvence.md §18)
     * @return detail faktury
     */
    InvoiceDto.DetailResponse getByInvoiceNumber(String invoiceNumber);

    /**
     * Vrátí fakturu navázanou na danou zakázku.
     *
     * @param orderId ID zakázky
     * @return detail faktury
     */
    InvoiceDto.DetailResponse getByOrderId(Long orderId);

    /**
     * Vrátí všechny faktury daného zákazníka.
     *
     * @param customerId ID zákazníka
     * @return seznam řádků faktur (může být prázdný)
     */
    List<InvoiceDto.ListResponse> getByCustomerId(Long customerId);

    // =========================================================================
    // Položky faktury
    // =========================================================================

    /**
     * Přidá novou položku k existující faktuře.
     * Povoleno jen dokud je faktura ve stavu {@code DRAFT}.
     *
     * @param invoiceId     ID faktury
     * @param createRequest validovaný request s daty položky
     * @return vytvořená položka faktury
     */
    InvoiceItemDto.Response addItem(Long invoiceId, InvoiceItemDto.CreateRequest createRequest);

    /**
     * Upraví existující položku faktury.
     * Povoleno jen dokud je nadřazená faktura ve stavu {@code DRAFT}.
     *
     * @param itemId        ID položky faktury
     * @param updateRequest validovaný request se změněnými poli
     * @return upravená položka faktury
     */
    InvoiceItemDto.Response updateItem(Long itemId, InvoiceItemDto.UpdateRequest updateRequest);

    /**
     * Trvale smaže položku faktury.
     * Povoleno jen dokud je nadřazená faktura ve stavu {@code DRAFT}.
     *
     * @param itemId ID položky faktury
     */
    void deleteItem(Long itemId);
}
