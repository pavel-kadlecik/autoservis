package cz.palo.autoservis.mapper;

import cz.palo.autoservis.model.domain.warehouse.Product;
import cz.palo.autoservis.model.dto.warehouse.ProductDto;
import cz.palo.autoservis.model.dto.warehouse.ProductSearchParams;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * MyBatis mapper tabulky {@code warehouse.products} (přehled skladu).
 *
 * <p>SQL žije v {@code mapper/warehouse/WarehouseMapper.xml}. {@code quantity_on_hand}
 * je denormalizovaná cache udržovaná triggerem nad pohyby — tady se nikdy nezapisuje.
 */
@Mapper
public interface WarehouseMapper {

    /**
     * Vrací stránku produktů odpovídajících parametrům hledání, seřazenou podle názvu.
     *
     * @param params parametry hledání a stránkování
     * @return výřez produktů pro požadovanou stránku
     */
    List<Product> search(@Param("params") ProductSearchParams params);

    /**
     * Spočítá všechny produkty odpovídající parametrům hledání (bez ohledu na stránkování).
     *
     * @param params parametry hledání
     * @return celkový počet odpovídajících produktů
     */
    long countSearch(@Param("params") ProductSearchParams params);

    /**
     * Najde jeden produkt (skladovou kartu) podle ID, bez ohledu na aktivnost.
     *
     * @param id ID produktu
     * @return produkt v {@link Optional}, nebo prázdný, když nebyl nalezen
     */
    Optional<Product> findById(@Param("id") Long id);

    /**
     * Vrací šarže produktu s původem (faktura, objednávka, dodavatel),
     * nejnovější první.
     *
     * @param productId ID produktu
     * @return seznam řádků šarží
     */
    List<ProductDto.BatchResponse> findBatchesByProductId(@Param("productId") Long productId);

    /**
     * Zakázky, které díl drží <strong>rezervovaný</strong> — naplánovaly si ho, ale ze
     * skladu ještě neodešel (V83). Nejnovější rezervace první.
     *
     * <p>Rozepisuje po zakázkách totéž, co fragment {@code reservedQuantity} sčítá, takže
     * součet množství odpovídá {@code quantityReserved} produktu. Odpovídá na otázku
     * „proč je dostupné míň, než mám v regálu".
     *
     * @param productId ID produktu
     * @return rezervace po zakázkách; prázdný seznam, když díl nikdo nedrží
     */
    List<ProductDto.ReservationResponse> findReservationsByProductId(@Param("productId") Long productId);

    /**
     * Vrací skladové pohyby produktu, nejnovější první.
     *
     * @param productId ID produktu
     * @return seznam řádků pohybů
     */
    List<ProductDto.MovementResponse> findMovementsByProductId(@Param("productId") Long productId);

    /**
     * Vloží nový produkt (skladovou kartu). {@code quantity_on_hand} zůstává na DB
     * defaultu (0); zásoba se mění jedině pohyby.
     *
     * @param product produkt k vložení; vygenerované ID se zapíše zpět do objektu
     */
    void insert(Product product);

    /**
     * Aktualizuje katalogová pole produktu. NEDOTÝKÁ se {@code is_active}
     * (activate/deactivate) ani {@code quantity_on_hand} (cache triggeru).
     *
     * @param product produkt s novými hodnotami a cílovým ID
     * @return počet ovlivněných řádků (0 = nenalezen)
     */
    int update(Product product);

    /**
     * Soft-delete produktu (nastaví {@code is_active = FALSE}).
     *
     * @param id ID produktu
     * @return počet ovlivněných řádků (0 = nenalezen)
     */
    int deactivate(@Param("id") Long id);

    /**
     * Znovu aktivuje produkt (nastaví {@code is_active = TRUE}).
     *
     * @param id ID produktu
     * @return počet ovlivněných řádků (0 = nenalezen)
     */
    int activate(@Param("id") Long id);

    /**
     * Vrací, zda existuje produkt s daným SKU (včetně neaktivních) — SKU je unikátní.
     *
     * @param sku katalogové číslo
     * @return {@code true}, pokud toto SKU používá jakýkoli produkt
     */
    boolean existsBySku(@Param("sku") String sku);

    List<Product> findByGoodsReceiptId(@Param("id") Long id);

    List<Product> findByIds(@Param("ids") List<Long> ids);

    /**
     * Ocenění zásob per aktivní produkt z view {@code warehouse.v_stock_valuation}
     * (Σ zbytek šarže × nákupní cena bez DPH). Celkový součet si sečte service.
     *
     * @return řádky ocenění, řazené podle názvu produktu
     */
    List<cz.palo.autoservis.model.dto.warehouse.StockValuationDto.Item> findStockValuation();

    /**
     * Aktivní díly pod hlídaným minimem i s doporučeným dodavatelem z převodníku
     * {@code supplier_products}. Řazeno podle toho, kolik chybí (nejvíc první).
     *
     * @return řádky přehledu „pod minimem"; prázdný seznam = vše je nad minimem
     */
    List<cz.palo.autoservis.model.dto.warehouse.LowStockDto> findLowStock();
}
