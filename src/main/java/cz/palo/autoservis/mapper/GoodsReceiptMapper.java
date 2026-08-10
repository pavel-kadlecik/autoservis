package cz.palo.autoservis.mapper;

import cz.palo.autoservis.model.domain.warehouse.GoodsReceiptItem;
import cz.palo.autoservis.model.dto.autocomplete.AutocompleteItem;
import cz.palo.autoservis.model.dto.warehouse.GoodReceiptAutocompleteParams;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Optional;

@Mapper
public interface GoodsReceiptMapper {


    /**
     * Načte řádek příjemky (šarži) podle jeho ID.
     *
     * @param id jednoznačný identifikátor hledaného řádku příjemky
     * @return {@code Optional} s řádkem příjemky, nebo prázdný {@code Optional}, když nebyl nalezen
     */
    Optional<GoodsReceiptItem> findById(@Param("id") Long id);

    /**
     * Načte šarže podle ID se zámky řádků ({@code SELECT ... FOR UPDATE}).
     * Používá výdej ze skladu (import položky zakázky) k serializaci souběžných
     * výdejů z téže šarže v rámci transakce volajícího — viz
     * {@code OrderItemServiceImpl.importFromReceipt} (K6, analyza-2026-07).
     *
     * @param ids ID šarží k zamčení a načtení
     * @return odpovídající šarže, zamčené do konce aktuální transakce
     */
    List<GoodsReceiptItem> findByIdsForUpdate(@Param("ids") List<Long> ids);

    /**
     * Kolik je ze šarží slíbeno otevřeným zakázkám a ještě nevydáno (V83).
     *
     * <p><strong>Volat až po {@link #findByIdsForUpdate}</strong>, jako samostatný dotaz.
     * Dokud byl tenhle součet poddotazem uvnitř zamykajícího SELECTu, souběh dvou importů
     * o poslední kus propustil oba: druhá transakce sice čekala na zámku, ale její poddotaz
     * se vyhodnotil nad snímkem z okamžiku startu příkazu — tedy dřív, než první transakce
     * rezervaci commitla. Zámek sám o sobě nestačí, protože rezervace zůstatek šarže vůbec
     * nemění. V režimu READ COMMITTED dostane samostatný příkaz čerstvý snímek a rezervaci
     * už uvidí. Pokryto testem {@code StockReservationConcurrencyTest}.
     *
     * @param ids ID šarží
     * @return šarže s vyplněným {@code id} a {@code quantityReserved}; ostatní pole prázdná
     */
    List<GoodsReceiptItem> findReservedByBatchIds(@Param("ids") List<Long> ids);

    /**
     * Našeptávač příjemek podle zadaných parametrů.
     *
     * @param params parametry hledání — dotaz, limit výsledků a typ identifikátoru
     * @return seznam {@code AutocompleteItem} s návrhy odpovídajícími dotazu
     */
    List<AutocompleteItem> autocomplete(@Param("params") GoodReceiptAutocompleteParams params);

    /**
     * Načte seznam importovatelných řádků příjemky podle jejího ID.
     *
     * @param receiptId ID příjemky, jejíž importovatelné řádky se mají načíst
     * @return seznam {@code GoodsReceiptItem}, které lze z dané příjemky importovat
     */
    List<GoodsReceiptItem> findImportableItems(@Param("receiptId") Long receiptId);


    /**
     * Zjistí, zda je příjemka s daným ID potvrzená.
     *
     * @param receiptId ID kontrolované příjemky
     * @return true, pokud je příjemka potvrzená; jinak false
     */
    boolean existsConfirmed(@Param("receiptId") Long receiptId);


}
