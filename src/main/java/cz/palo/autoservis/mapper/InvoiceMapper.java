package cz.palo.autoservis.mapper;

import cz.palo.autoservis.model.domain.billing.Invoice;
import cz.palo.autoservis.model.domain.billing.InvoiceListRow;
import cz.palo.autoservis.model.domain.billing.InvoiceSummary;
import cz.palo.autoservis.model.domain.billing.InvoiceVatSummary;
import cz.palo.autoservis.model.dto.billing.InvoiceSearchParams;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Optional;

/**
 * MyBatis mapper rozhraní tabulky {@code billing.invoices}.
 *
 * <p>Konvence:
 * <ul>
 *   <li>Veškeré SQL je výhradně v {@code mapper/InvoiceMapper.xml}.</li>
 *   <li>Anotace typu {@code @Select} se nepoužívají — pro složitější dotazy má přednost XML.</li>
 *   <li>Dotazy na jeden záznam vracejí {@link Optional}, aby volající nemusel kontrolovat null.</li>
 * </ul>
 */
@Mapper
public interface InvoiceMapper {

    /**
     * Vloží novou fakturu. Po úspěšném INSERTu se vygenerovaný PK zapíše
     * zpět do doménového objektu přes {@code useGeneratedKeys}.
     * Koncept se zakládá <strong>bez čísla</strong> a bez variabilního symbolu —
     * obojí doplňuje až {@link #issueWithNumber} při vystavení.
     *
     * @param invoice nová faktura (id musí být null)
     */
    void insert(Invoice invoice);

    /**
     * Najde nejvyšší pořadové číslo v řadě dané regexem z masky (V71).
     * Regex staví {@code DocumentNumberMask#regex(LocalDate)} — rok/měsíc období
     * má zapečené jako konkrétní číslice a pořadí je jeho jediná zachytávací
     * skupina (max. 15 číslic, aby {@code ::BIGINT} nepřetekl). Čísla mimo masku
     * (ruční zápis při vypnutém automatu) se řady netýkají a nematchnou.
     *
     * <p>Koncepty do řady nevstupují — číslo mají až vystavené doklady, takže
     * MAX+1 nepočítá nic, co by mohlo zaniknout.
     *
     * @param regex POSIX regex řady pro dané období
     * @return nejvyšší pořadí v řadě, nebo {@code null} když řada zatím nemá žádné číslo
     */
    Long findMaxSequence(@Param("regex") String regex);

    /** Čísla faktur téže řady a období — podklad pro hlídání mezer (V89). */
    List<String> findNumbersByRegex(@Param("regex") String regex);

    /**
     * Vezme transakční poradní zámek nad jednou číselnou řadou a jedním obdobím.
     *
     * <p>Bez něj by dva souběžně vystavované doklady mohly dostat od
     * {@link #findMaxSequence} totéž pořadí; jeden by pak spadl na
     * {@code uq_invoice_number} a musel se opakovat. Zámek se uvolní koncem
     * transakce, takže nemá co odemykat. Vzor: {@code fn_generate_credit_note_number}
     * (V55) a {@code fn_generate_cash_receipt_number} (V57).
     *
     * @param key klíč řady + období (regex masky pro dané datum vystavení)
     * @return vždy {@code true} — návratová hodnota je jen formalita, aby měl
     *         {@code <select>} co namapovat ({@code pg_advisory_xact_lock} vrací {@code void})
     */
    Boolean lockNumberSeries(@Param("key") String key);

    /**
     * Aktualizuje existující fakturu. Dynamický UPDATE — mění se jen non-null pole.
     * Změnit lze jen tato pole: {@code due_date}, {@code constant_symbol},
     * {@code specific_symbol}, {@code payment_method}, {@code status}, {@code note}.
     *
     * @param invoice faktura s novými hodnotami
     * @return počet ovlivněných řádků (0 = nenalezena, 1 = úspěch)
     */
    int update(Invoice invoice);

    /**
     * Mění jen sloupec {@code status} faktury, hlídaný očekávaným aktuálním stavem
     * ({@code WHERE id = ... AND status = expectedStatus}).
     * Používají ho hlídané přechodové operace (issue / markPaid / cancel);
     * kontrolu povolených přechodů dělá service vrstva, tenhle guard chrání jen
     * proti souběžnému zápisu mezi kontrolou a zápisem (TOCTOU).
     *
     * @param id             ID faktury
     * @param status         nový stav
     * @param expectedStatus stav, ve kterém se faktura má aktuálně nacházet
     * @return počet ovlivněných řádků (0 = nenalezena, nebo stav už neodpovídá
     *         {@code expectedStatus}; 1 = úspěch)
     */
    int updateStatus(@Param("id") Long id,
                      @Param("status") cz.palo.autoservis.model.enums.InvoiceStatus status,
                      @Param("expectedStatus") cz.palo.autoservis.model.enums.InvoiceStatus expectedStatus);

    /**
     * Vystaví koncept faktury: zapíše číslo, variabilní symbol a datum
     * vystavení/splatnosti a přepne DRAFT → ISSUED <strong>jedním</strong> UPDATE.
     *
     * <p>Všechno v jednom příkazu kvůli guardu {@code WHERE status = 'DRAFT'} (TOCTOU):
     * číslo se tak nemůže zapsat do dokladu, který mezitím vystavil někdo jiný.
     * DB trigger {@code trg_invoices_number_immutable} tomuhle zápisu nebrání — spouští
     * se až {@code WHEN old.status <> 'DRAFT'}.
     *
     * @param id             ID faktury
     * @param issueDate      datum vystavení z dialogu (volba obsluhy — server ho nepřepisuje)
     * @param dueDate        splatnost (service ji posune, kdyby ji zvolené datum vystavení předběhlo)
     * @param invoiceNumber  číslo dokladu (trimované, neprázdné — ověřuje service)
     * @param variableSymbol variabilní symbol, nebo {@code null} pro doklad bez VS
     * @return počet ovlivněných řádků (0 = nenalezena nebo už není DRAFT, 1 = úspěch)
     */
    int issueWithNumber(@Param("id") Long id,
                        @Param("issueDate") java.time.LocalDate issueDate,
                        @Param("dueDate") java.time.LocalDate dueDate,
                        @Param("invoiceNumber") String invoiceNumber,
                        @Param("variableSymbol") String variableSymbol);

    /**
     * Smaže <strong>koncept</strong> faktury i s položkami a stranami (FK {@code ON DELETE CASCADE}).
     *
     * <p>Guard {@code WHERE status = 'DRAFT'} je bezpečnostní pojistka, ne optimalizace: vystavený
     * doklad je právní dokument a smazat se nesmí ani při souběhu (obsluha maže koncept ve chvíli,
     * kdy ho kolega vystavuje) — 0 řádků pak service přeloží na 409.
     *
     * <p>Tvrdé mazání je vědomá <strong>výjimka z R-06</strong> (soft-delete): koncept není doklad,
     * nemá číslo a nikdy neopustil firmu, takže není co archivovat. Viz {@code konvence.md} §18.
     *
     * @param id ID faktury
     * @return počet smazaných řádků (0 = nenalezena nebo už není DRAFT, 1 = úspěch)
     */
    int deleteDeletable(@Param("id") Long id);

    /** Označí doklad za předaný zákazníkovi; 0 = už předaný nebo není vystavený (V88). */
    int markHandedOver(@Param("id") Long id, @Param("userId") Long userId);

    /** Vezme platbu zpět: PAID → ISSUED a smaže záznam o úhradě; 0 = stav se mezitím změnil. */
    int clearPayment(@Param("id") Long id);

    /** Vrácení do konceptu, krok 1 — zruší vystavení; 0 = stav se mezitím změnil. */
    int revokeIssueStep1Status(@Param("id") Long id);

    /** Vrácení do konceptu, krok 2 — uvolní číslo a VS (viz komentář v XML k pořadí). */
    int revokeIssueStep2ClearNumber(@Param("id") Long id);

    /** Vezme předání zpět; 0 = nebylo předané nebo je už zaplacené (V88). */
    int clearHandedOver(@Param("id") Long id);

    /**
     * Zapíše údaje o úhradě při označení faktury PAID (E2.1 / audit K-9).
     * {@code paid_at} se nastaví na hodiny serveru (NOW()).
     *
     * @param id          ID faktury
     * @param paidAmount  zaplacená částka (MVP plné úhrady = celková částka dokladu)
     * @param paidMethod  skutečný způsob úhrady
     */
    void recordPayment(@Param("id") Long id,
                       @Param("paidAmount") java.math.BigDecimal paidAmount,
                       @Param("paidMethod") cz.palo.autoservis.model.enums.PaymentMethod paidMethod);

    /**
     * Najde fakturu podle interního ID.
     *
     * @param id ID faktury
     * @return faktura v {@link Optional}, nebo prázdný, když nebyla nalezena
     */
    Optional<Invoice> findById(@Param("id") Long id);

    /**
     * Najde fakturu podle jejího čísla (např. {@code 202608001} či {@code 17/26}
     * — formát určuje maska v nastavení, případně volný ruční zápis).
     *
     * @param invoiceNumber číslo faktury
     * @return faktura v {@link Optional}, nebo prázdný, když nebyla nalezena
     */
    Optional<Invoice> findByInvoiceNumber(@Param("invoiceNumber") String invoiceNumber);

    /**
     * Najde <strong>aktivní</strong> fakturu servisní zakázky — tu, která blokuje
     * opětovnou fakturaci. Stornované a dobropisované faktury jsou vyloučené, přesně
     * jako v částečném unikátním indexu {@code uq_invoices_order_active} (V48 + V69),
     * který zaručuje nejvýš jeden takový řádek.
     *
     * @param orderId ID servisní zakázky
     * @return faktura v {@link Optional}, nebo prázdný, když zakázka aktivní fakturu nemá
     *         (nikdy nefakturovaná, nebo její faktura byla stornována / dobropisována)
     */
    Optional<Invoice> findByOrderId(@Param("orderId") Long orderId);

    /**
     * Kolik faktur k zakázce existuje <strong>včetně</strong> stornovaných a dobropisovaných.
     *
     * <p>Záměrně širší predikát než {@link #findByOrderId}, které vrací jen aktivní doklad:
     * pro <em>mazání</em> zakázky je rozhodující, jestli po ní kdy zůstala jakákoli stopa
     * v účetnictví. Vyfakturovaná zakázka se ruší, ne maže (V84).
     *
     * @param orderId ID zakázky
     * @return počet faktur, 0 = zakázka nikdy fakturovaná nebyla
     */
    int countByOrderId(@Param("orderId") Long orderId);

    /**
     * Orazítkuje {@code credited_at} poté, co byl k faktuře <strong>vystaven</strong> dobropis
     * (koncept dobropisu nic nemění). Faktura si drží stav ISSUED/PAID — zůstává platným
     * dokladem — ale přestává být aktivní fakturou zakázky, takže zakázku lze fakturovat
     * znovu (V69).
     *
     * @param id ID faktury
     */
    void markCredited(@Param("id") Long id);

    /**
     * Vrací všechny faktury daného zákazníka jako seznamové řádky,
     * seřazené podle data vystavení sestupně.
     *
     * @param customerId ID zákazníka
     * @return seznam řádků read modelu (může být prázdný)
     */
    List<InvoiceListRow> findByCustomerId(@Param("customerId") Long customerId);

    /**
     * Vrací jednu stránku seznamových řádků faktur odpovídajících parametrům hledání.
     * Hlavička faktury, číslo zakázky, jméno zákazníka i spočítané součty se načítají
     * jedním JOIN dotazem (žádné N+1).
     *
     * @param params parametry hledání a stránkování
     * @return seznam řádků read modelu (může být prázdný)
     */
    List<InvoiceListRow> search(@Param("params") InvoiceSearchParams params);

    /**
     * Spočítá všechny faktury odpovídající parametrům hledání, bez ohledu na stránkování.
     * Musí používat přesně stejné filtry jako {@link #search(InvoiceSearchParams)}.
     *
     * @param params parametry hledání
     * @return celkový počet odpovídajících faktur
     */
    long countSearch(@Param("params") InvoiceSearchParams params);

    /**
     * Načte spočítané součty jedné faktury z view
     * {@code billing.v_invoice_price_totals}.
     *
     * <p>Faktura bez položek ve view žádný řádek nemá, výsledek je tedy prázdný —
     * volající má sáhnout po {@link InvoiceSummary#zero(Long)}.
     *
     * @param invoiceId ID faktury
     * @return součty v {@link Optional}, nebo prázdný, když faktura nemá položky
     */
    Optional<InvoiceSummary> findSummaryByInvoiceId(@Param("invoiceId") Long invoiceId);

    /**
     * Načte rekapitulaci DPH (základ / DPH / celkem po sazbách) jedné faktury
     * z view {@code billing.v_invoice_vat_summary}.
     *
     * @param invoiceId ID faktury
     * @return seznam řádků po sazbách, seřazený podle sazby (může být prázdný)
     */
    List<InvoiceVatSummary> findVatSummaryByInvoiceId(@Param("invoiceId") Long invoiceId);

}
