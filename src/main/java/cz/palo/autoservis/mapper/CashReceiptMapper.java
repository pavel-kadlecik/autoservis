package cz.palo.autoservis.mapper;

import cz.palo.autoservis.model.domain.billing.CashReceipt;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Optional;

/**
 * MyBatis mapper tabulky {@code billing.cash_receipts} (příjmový pokladní doklad). SQL v {@code CashReceiptMapper.xml}.
 */
@Mapper
public interface CashReceiptMapper {

    /**
     * Vloží nový pokladní doklad. Číslo řady ({@code receipt_number}) od V92 skládá aplikace
     * (maska z profilu firmy, editovatelné v dialogu) — DB generátor byl zrušen.
     *
     * @param cashReceipt nový doklad (id musí být null, receiptNumber vyplněné)
     */
    void insert(CashReceipt cashReceipt);

    Optional<CashReceipt> findById(@Param("id") Long id);

    /** Doklad podle čísla — pre-check duplicity před {@code uq_cash_receipt_number} (hezčí 422). */
    Optional<CashReceipt> findByReceiptNumber(@Param("receiptNumber") String receiptNumber);

    /** Nejvyšší pořadové číslo řady daného období (regex z DocumentNumberMask), null = řada prázdná. */
    Long findMaxSequence(@Param("regex") String regex);

    /** Čísla dokladů odpovídající řadě a období — pro hlídání mezer (V92). */
    List<String> findNumbersByRegex(@Param("regex") String regex);

    /** Poradní zámek nad řadou daného období; drží se do konce transakce. */
    Boolean lockNumberSeries(@Param("key") String key);

    /**
     * Smaže doklad — rozhodnutí uživatele 2026-08-09: řadu PPD si řídí obsluha sama,
     * číslo smazaného dokladu se uvolní a díra jde zavřít ručním zápisem čísla.
     *
     * @return počet smazaných řádků (0 = doklad už neexistuje)
     */
    int deleteById(@Param("id") Long id);

    List<CashReceipt> findByInvoiceId(@Param("invoiceId") Long invoiceId);

    /**
     * Platný (nestornovaný) doklad k faktuře — nejvýš jeden, hlídá částečný unikát
     * {@code uq_cash_receipts_invoice_active} (V68).
     *
     * @param invoiceId faktura
     * @return doklad, nebo prázdno, pokud k faktuře žádný platný neexistuje
     */
    Optional<CashReceipt> findActiveByInvoiceId(@Param("invoiceId") Long invoiceId);

    /**
     * Stornuje doklad. Přechod je hlídaný stavem — projde jen u dokladu ve stavu {@code ISSUED}.
     *
     * @param id     doklad
     * @param reason důvod storna (povinný, jde do auditní stopy a na PDF)
     * @param userId kdo stornoval
     * @return počet změněných řádků (0 = doklad neexistuje nebo už byl stornovaný)
     */
    int cancel(@Param("id") Long id, @Param("reason") String reason, @Param("userId") Long userId);
}
