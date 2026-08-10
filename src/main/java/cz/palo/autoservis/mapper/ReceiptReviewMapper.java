package cz.palo.autoservis.mapper;

import cz.palo.autoservis.model.domain.warehouse.GoodsReceipt;
import cz.palo.autoservis.model.dto.warehouse.ReceiptSearchParams;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * MyBatis mapper review workflow příjemek (seznam, detail, úpravy draftu
 * a hlídané přechody stavů). SQL v {@code ReceiptReviewMapper.xml}.
 *
 * <p>Seznamové a detailové selecty nikdy nenačítají {@code source_pdf} (BYTEA) —
 * PDF má vlastní select. Přechody stavů jsou hlídané
 * {@code WHERE status = 'PENDING_REVIEW'} a vracejí počet změněných řádků,
 * který service používá jako optimistickou kontrolu souběhu.
 */
@Mapper
public interface ReceiptReviewMapper {

    List<GoodsReceipt> search(@Param("params") ReceiptSearchParams params);

    long countSearch(@Param("params") ReceiptSearchParams params);

    /** Hlavička + draft payload, bez PDF. */
    Optional<GoodsReceipt> findById(@Param("id") Long id);

    /** Jen bajty PDF + název souboru (těžký sloupec, načítá se na vyžádání). */
    Optional<GoodsReceipt> findPdfById(@Param("id") Long id);

    /** Zda má příjemka uložené PDF (levný EXISTS místo načítání BYTEA). */
    boolean hasPdf(@Param("id") Long id);

    /**
     * Uloží upravený draft + synchronizuje projekci hlavičky.
     * Hlídané: mění se jen řádky ve stavu PENDING_REVIEW.
     *
     * @return počet změněných řádků (0 = příjemka už není editovatelná)
     */
    int updateDraft(@Param("id") Long id,
                    @Param("draftPayload") String draftPayload,
                    @Param("documentNumber") String documentNumber,
                    @Param("orderNumber") String orderNumber,
                    @Param("originalOrderNumber") String originalOrderNumber,
                    @Param("issueDate") LocalDate issueDate,
                    @Param("dueDate") LocalDate dueDate,
                    @Param("taxableSupplyDate") LocalDate taxableSupplyDate,
                    @Param("subtotal") BigDecimal subtotal,
                    @Param("vatAmount") BigDecimal vatAmount,
                    @Param("totalAmount") BigDecimal totalAmount,
                    @Param("currency") String currency,
                    @Param("supplierId") Long supplierId,
                    @Param("supplierNameSnapshot") String supplierNameSnapshot,
                    @Param("reconciliationOk") boolean reconciliationOk);

    /**
     * Označí příjemku CONFIRMED (finální hodnoty hlavičky + audit + zmrazený draft).
     * Hlídané: mění se jen řádky ve stavu PENDING_REVIEW.
     *
     * @return počet změněných řádků (0 = mezitím zpracoval někdo jiný)
     */
    int confirm(@Param("id") Long id,
                @Param("supplierId") Long supplierId,
                @Param("supplierNameSnapshot") String supplierNameSnapshot,
                @Param("draftPayload") String draftPayload,
                @Param("reconciliationOk") boolean reconciliationOk,
                @Param("userId") Long userId);

    /**
     * Označí příjemku REJECTED. Hlídané: mění se jen řádky ve stavu PENDING_REVIEW.
     *
     * @return počet změněných řádků (0 = už zpracováno)
     */
    int reject(@Param("id") Long id,
               @Param("note") String note,
               @Param("userId") Long userId);

    /**
     * Šarže příjemky se zámkem {@code FOR UPDATE} — storno je kontroluje
     * a hned zapisuje kompenzace, mezitím nesmí nikdo vydat (vzor K6).
     *
     * @param receiptId ID příjemky
     * @return šarže příjemky seřazené podle pozice
     */
    java.util.List<cz.palo.autoservis.model.domain.warehouse.GoodsReceiptItem>
    findBatchesForUpdate(@Param("receiptId") Long receiptId);

    /**
     * ID šarží této příjemky, na které se váže položka zakázky (FK z order_items).
     *
     * @param receiptId ID příjemky
     * @return ID čerpaných šarží; prázdné = z příjemky se nevydávalo
     */
    java.util.List<Long> findBatchIdsUsedByOrderItems(@Param("receiptId") Long receiptId);

    /**
     * Označí příjemku CANCELLED (V43, R-C). Hlídané: mění se jen řádky ve stavu CONFIRMED.
     *
     * @return počet změněných řádků (0 = už zpracováno)
     */
    int cancel(@Param("id") Long id,
               @Param("note") String note,
               @Param("userId") Long userId);
}
