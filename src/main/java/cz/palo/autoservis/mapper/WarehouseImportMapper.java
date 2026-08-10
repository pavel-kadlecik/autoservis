package cz.palo.autoservis.mapper;

import cz.palo.autoservis.model.domain.warehouse.*;
import org.apache.ibatis.annotations.Param;
import java.math.BigDecimal;
import java.util.Optional;

/**
 * MyBatis mapper s perzistentními operacemi procesu importu dodavatelských faktur.
 * <p>
 * SQL dotazy jsou definované v {@code WarehouseImportMapper.xml}.
 * Insert metody mají {@code useGeneratedKeys="true"} — vygenerované ID se
 * zapisuje zpět do předaného doménového objektu.
 */
public interface WarehouseImportMapper {

    /**
     * Najde ID dodavatele podle jeho IČO.
     *
     * @param ico IČO dodavatele
     * @return {@link Optional} s ID dodavatele, nebo prázdný, když nebyl nalezen
     */
    Optional<Long> findSupplierIdByIco(@Param("ico") String ico);

    /**
     * Najde <strong>deaktivovaného</strong> dodavatele podle IČO (audit KN-16).
     *
     * <p>Protějšek {@link #findSupplierIdByIco}, které záměrně hledá jen aktivní
     * dodavatele — automaticky napárovat draft na vyřazenou firmu by bylo horší než
     * přiznat, že shoda není. Unikát {@code uq_suppliers_registration_number} ale platí
     * bez ohledu na {@code is_active}, takže potvrzení příjemky s takovým dodavatelem
     * by spadlo na porušení constraintu a projevilo se jako nicneříkající 422. Volající
     * touto metodou situaci rozpozná a nahlásí ji tak, aby s ní obsluha uměla naložit.
     *
     * @param ico hledané IČO
     * @return ID deaktivovaného dodavatele, nebo prázdný Optional, když žádný není
     */
    Optional<Long> findInactiveSupplierIdByIco(@Param("ico") String ico);

    /**
     * Duplicita dokladu u dodavatele <strong>bez čitelného IČO</strong> (audit KN-4b).
     *
     * <p>{@code existsActiveDocument} se ptá podle {@code supplier_id}, jenže dodavatel bez IČO
     * se při každém potvrzení zakládá znovu — nová karta žádný dřívější doklad nemá, takže dedup
     * neměl s čím porovnávat a týž ručně psaný dodací list šel naskladnit opakovaně. Tahle
     * varianta porovnává podle jména uloženého v {@code supplier_name_snapshot} příjemky
     * (case-insensitive, bez okrajových mezer), které na počtu dodavatelských karet nezávisí.
     *
     * @param supplierName      jméno dodavatele z dokladu
     * @param documentNumber    číslo dokladu
     * @param excludeReceiptId  právě potvrzovaná příjemka (vyloučí se ze srovnání)
     * @return {@code true}, pokud takový aktivní doklad už existuje
     */
    boolean existsActiveDocumentBySupplierName(@Param("supplierName") String supplierName,
                                               @Param("documentNumber") String documentNumber,
                                               @Param("excludeReceiptId") Long excludeReceiptId);

    /**
     * Vloží nového dodavatele do databáze.
     *
     * @param supplier dodavatel k vložení; jeho ID se po dokončení naplní
     */
    void insertSupplier(Supplier supplier);

    /**
     * Zjistí, zda už u dodavatele existuje ne-REJECTED příjemka s daným číslem
     * dokladu (idempotence importu; zamítnuté drafty číslo uvolňují).
     *
     * @param supplierId       ID dodavatele
     * @param documentNumber   kontrolované číslo dokladu (faktura / dodací list)
     * @param excludeReceiptId příjemka k ignorování (ta právě potvrzovaná), může být {@code null}
     * @return {@code true}, pokud aktivní doklad existuje; jinak {@code false}
     */
    boolean existsActiveDocument(@Param("supplierId") Long supplierId,
                                 @Param("documentNumber") String documentNumber,
                                 @Param("excludeReceiptId") Long excludeReceiptId);

    /**
     * Vloží novou příjemku.
     *
     * @param receipt příjemka k vložení; její ID se po dokončení naplní
     */
    void insertReceipt(GoodsReceipt receipt);

    /**
     * Najde ID produktu podle jeho SKU (katalogového čísla).
     *
     * @param sku SKU produktu
     * @return {@link Optional} s ID produktu, nebo prázdný, když nebyl nalezen
     */
    Optional<Long> findProductIdBySku(@Param("sku") String sku);

    /**
     * Najde <strong>deaktivovanou</strong> skladovou kartu podle SKU (audit KN-16).
     *
     * <p>{@code uq_products_sku} platí bez ohledu na {@code is_active}, takže vložení
     * nové karty pro SKU, které už existuje jako deaktivované, spadne na porušení
     * constraintu. Zboží fyzicky přišlo, proto volající existující kartu reaktivuje,
     * místo aby zakládal duplicitní.
     *
     * @param sku hledané katalogové číslo
     * @return ID deaktivovaného produktu, nebo prázdný Optional, když žádný není
     */
    Optional<Long> findInactiveProductIdBySku(@Param("sku") String sku);

    /**
     * Vloží nový produkt do databáze.
     *
     * @param product produkt k vložení; jeho ID se po dokončení naplní
     */
    void insertProduct(Product product);

    /**
     * Vloží nový řádek (šarži) k příjemce.
     *
     * @param item řádek příjemky k vložení
     */
    void insertReceiptItem(GoodsReceiptItem item);

    /**
     * Vloží nový skladový pohyb — záznam změny množství zásob.
     *
     * @param movement skladový pohyb k vložení
     */
    void insertMovement(StockMovement movement);

    /**
     * Kolik z položky zakázky je právě teď fyzicky mimo sklad.
     *
     * <p>Součet znaménkových množství jejích pohybů obrácený do kladné hodnoty: výdej je
     * záporný, vratka kladná, takže se už vrácené množství samo odečte. Nula znamená
     * „jen rezervováno" (nebo „už vráceno"), kladné číslo „tolik ještě drží u sebe".
     *
     * @param orderItemId ID položky zakázky
     * @return nezáporné množství, nikdy {@code null} — bez pohybů vrací 0
     */
    BigDecimal findIssuedQuantityByOrderItemId(@Param("orderItemId") Long orderItemId);

    /**
     * Kolik skladových pohybů kdy vzniklo ze zakázky.
     *
     * <p>Rozhoduje o tom, jestli jde zakázku <em>smazat</em> (V84): pouhá rezervace žádný
     * pohyb nemá, takže mazání nebrání, kdežto vydaný materiál ano — po něm zůstala stopa
     * v append-only ledgeru a zakázka se musí zrušit, ne smazat.
     *
     * @param orderId ID zakázky
     * @return počet pohybů, 0 = ze zakázky nikdy nic ze skladu neodešlo
     */
    int countMovementsByOrderId(@Param("orderId") Long orderId);

    /**
     * Najde existující ne-REJECTED příjemku typu DELIVERY_NOTE pro dané číslo
     * dodacího listu (volitelně omezené na dodavatele). Používá deduplikace
     * DL↔faktura.
     */
    Optional<Long> findDeliveryNoteReceiptId(@Param("supplierId") Long supplierId,
                                             @Param("number") String number,
                                             @Param("excludeReceiptId") Long excludeReceiptId);

    /** Upsert řádku reference na dodací list u fakturové příjemky. */
    void upsertDeliveryNoteRef(@Param("goodsReceiptId") Long goodsReceiptId,
                               @Param("number") String number,
                               @Param("matchedReceiptId") Long matchedReceiptId,
                               @Param("resolution") String resolution);
}
