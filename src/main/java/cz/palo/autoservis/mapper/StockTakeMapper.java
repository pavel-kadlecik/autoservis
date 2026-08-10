package cz.palo.autoservis.mapper;

import cz.palo.autoservis.model.domain.warehouse.StockTake;
import cz.palo.autoservis.model.dto.warehouse.StockTakeDto;
import cz.palo.autoservis.model.dto.warehouse.StockTakeSearchParams;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/** SQL pro inventuru (warehouse.stock_takes, stock_take_items — V44). */
@Mapper
public interface StockTakeMapper {

    /** Vloží hlavičku; naplní {@code id}. Druhá otevřená selže na partial unique indexu. */
    void insert(StockTake stockTake);

    Optional<StockTake> findById(@Param("id") Long id);

    /** Stránka inventur dle {@code params} (řazení z whitelistu, LIMIT/OFFSET). */
    List<StockTakeDto.ListResponse> search(@Param("params") StockTakeSearchParams params);

    /** Celkový počet inventur pro stránkování. */
    long countSearch(@Param("params") StockTakeSearchParams params);

    /** ID otevřené inventury, pokud nějaká je. */
    Optional<Long> findOpenId();

    /**
     * Nasnapshotuje všechny aktivní produkty do soupisu: očekávané množství
     * z aktuálního stavu, cenu přebytku z nejnovější šarže dílu (může být NULL).
     *
     * @return počet vložených řádků
     */
    int snapshotActiveProducts(@Param("stockTakeId") Long stockTakeId);

    /**
     * Zmrazí zjištěné rozdíly do sloupců {@code closed_*} (V65, audit KN-2).
     *
     * <p><strong>Volat POVINNĚ před zápisem korekčních pohybů.</strong> Korekce srovnají
     * {@code products.quantity_on_hand} na napočítané množství, takže po nich už rozdíl nelze
     * spočítat — uzavřená inventura pak vykazovala samé nuly a nedoložila ani jedno manko,
     * přestože je právě zaúčtovala. Řádky bez napočítaného množství se přeskakují
     * („nepočítáno" není nula, R-H).
     *
     * @param stockTakeId ID inventury
     * @return počet zmrazených (tj. napočítaných) řádků
     */
    int materializeDifferences(@Param("stockTakeId") Long stockTakeId);

    /**
     * Řádky soupisu s rozdílem. U otevřené inventury se rozdíl počítá proti aktuálnímu stavu
     * skladu, u uzavřené se čtou hodnoty zmrazené při uzavření (V65).
     */
    List<StockTakeDto.ItemResponse> findItems(@Param("stockTakeId") Long stockTakeId);

    /**
     * Šarže dílu se zbytkem, seřazené FIFO (nejstarší doklad první) a zamčené
     * {@code FOR UPDATE} — v tomto pořadí se rozpouští inventurní manko.
     */
    List<cz.palo.autoservis.model.domain.warehouse.GoodsReceiptItem>
    findBatchesForShortage(@Param("productId") Long productId);

    /** Zápis jednoho řádku soupisu; vrací počet aktualizovaných řádků (0 = cizí položka). */
    int updateItem(@Param("stockTakeId") Long stockTakeId,
                   @Param("itemId") Long itemId,
                   @Param("countedQuantity") BigDecimal countedQuantity,
                   @Param("surplusUnitPrice") BigDecimal surplusUnitPrice);

    /** Guarded přechod do CLOSED; 0 řádků = inventura už není otevřená. */
    int close(@Param("id") Long id,
              @Param("note") String note,
              @Param("surplusReceiptId") Long surplusReceiptId,
              @Param("userId") Long userId);

    /** Guarded přechod do CANCELLED; 0 řádků = inventura už není otevřená. */
    int cancel(@Param("id") Long id, @Param("userId") Long userId);
}
