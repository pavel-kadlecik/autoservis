package cz.palo.autoservis.service;

import cz.palo.autoservis.model.dto.order.OrderItemDto;
import cz.palo.autoservis.model.dto.order.OrderItemSummaryDto;
import cz.palo.autoservis.model.dto.warehouse.GoodsReceiptItemDto;
import jakarta.validation.Valid;

import java.util.List;

/**
 * Service rozhraní pro správu položek servisních zakázek.
 *
 * <p>Poskytuje CRUD operace nad tabulkou {@code order.order_items}.
 * Položky lze přidávat, upravovat i mazat po celou dobu života zakázky — dokud k ní
 * neexistuje aktivní faktura; ta položky zamkne ({@code ORDER_LOCKED_BY_INVOICE}).
 */
public interface OrderItemService {

    /**
     * Vrátí všechny položky dané zakázky, seřazené podle pozice.
     *
     * @param orderId ID zakázky
     * @return seznam položek zakázky
     */
    List<OrderItemDto.Response> getByOrderId(Long orderId);

    /**
     * Vrátí jednu položku zakázky podle ID.
     *
     * @param id ID položky zakázky
     * @return položka zakázky
     */
    OrderItemDto.Response getById(Long id);

    /**
     * Vytvoří novou položku v dané zakázce.
     *
     * @param orderId       ID zakázky, ke které položka patří
     * @param createRequest zvalidovaný request s daty položky
     * @param userId        ID přihlášeného uživatele (auditní pole {@code created_by})
     * @return vytvořená položka zakázky
     */
    OrderItemDto.Response create(Long orderId, OrderItemDto.CreateRequest createRequest, Long userId);

    /**
     * Aktualizuje existující položku zakázky.
     *
     * <p>Změní-li se množství u položky, která už byla <strong>vydaná</strong> ze skladu,
     * dorovná se rozdíl protipohybem (V83): snížení vrátí rozdíl vratkou, zvýšení ho
     * doobjedná dalším výdejem. U pouhé rezervace se sklad nemění vůbec — díl regál
     * neopustil, mění se jen slib.
     *
     * @param id            ID položky zakázky
     * @param updateRequest zvalidovaný request s novými hodnotami
     * @param userId        ID přihlášeného uživatele pro audit případného pohybu
     * @return položka zakázky po aktualizaci
     * @throws cz.palo.autoservis.exception.BusinessRuleException {@code STOCK_MISSING_FOR_ISSUE},
     *         když na zvýšení množství není na skladě dost dílů
     */
    OrderItemDto.Response update(Long id, OrderItemDto.UpdateRequest updateRequest, Long userId);

    /**
     * Trvale smaže položku zakázky podle ID. Byl-li na položku už vydán materiál ze skladu,
     * založí k němu vratku {@code ISSUE_RETURN}; pouhá rezervace se smazáním jen uvolní.
     *
     * @param id     ID položky zakázky
     * @param userId ID přihlášeného uživatele pro audit případné vratky
     */
    void delete(Long id, Long userId);

    /**
     * Vydá ze skladu materiál rezervovaný na zakázce — tedy všechny položky s vazbou
     * na šarži, které ze skladu ještě neodešly (V83).
     *
     * <p>Přidání dílu na zakázku je jen <strong>rezervace</strong>: díl leží dál v regálu
     * a snižuje pouze dostupné množství. Teprve tady vznikne skladový pohyb {@code ISSUE}
     * a klesne fyzický stav. Volá se buď z tlačítka „Vydat ze skladu", nebo automaticky
     * při dokončení zakázky — podle toho, co nastane dřív.
     *
     * <p>Opakované volání nic nezdvojí: už vydané položky do výběru nespadnou. Naopak
     * položka, jejíž výdej se vrátil (znovuotevření zakázky), se vydá znovu.
     *
     * @param orderId ID zakázky
     * @param userId  ID přihlášeného uživatele pro audit pohybu
     * @return kolik položek bylo vydáno (0 = nebylo co vydat)
     * @throws cz.palo.autoservis.exception.BusinessRuleException {@code STOCK_MISSING_FOR_ISSUE},
     *         když rezervovaný díl mezitím ze skladu zmizel (inventura, odpis, vratka);
     *         hláška vyjmenuje, co chybí
     */
    int issueStock(Long orderId, Long userId);

    /**
     * Vrátí výdej materiálu zakázky zpět do <strong>rezervace</strong> (V83).
     *
     * <p>Volá se při znovuotevření dokončené zakázky. Díl fyzicky <strong>zůstává na
     * autě</strong> — nikam se nevrací; ruší se jen výdej, aby se při dalším dokončení
     * neodepsal ze skladu podruhé. Netto dopad na sklad je nula.
     *
     * @param orderId ID zakázky
     * @param userId  ID přihlášeného uživatele pro audit pohybu
     * @return kolik položek se vrátilo do rezervace (0 = nebylo co vracet)
     */
    int returnIssuedStock(Long orderId, Long userId);

    /**
     * Aktualizuje pořadí zobrazení více položek v rámci dané zakázky.
     * Položek, které v seznamu nejsou, se změna netýká.
     *
     * @param orderId ID zakázky
     * @param items   seznam položek s jejich novými pozicemi
     */
    void reorder(Long orderId, @Valid List<OrderItemDto.ReorderRequest> items);

    /**
     * Naimportuje položky zakázky z vybraných položek příjemky (šarží) v zadaných množstvích.
     *
     * <p>Import je pouhá <strong>rezervace</strong> (V83): vzniknou položky typu
     * {@code MATERIAL} s vazbou na šarži, ale žádný skladový pohyb — ten vznikne až
     * výdejem ({@link #issueStock}), případně automaticky při dokončení zakázky.
     *
     * @param orderId       ID zakázky, do které se položky importují
     * @param importRequest seznam položek příjemky k importu s požadovanými množstvími
     * @param userId        ID přihlášeného uživatele
     * @return seznam vytvořených položek zakázky
     */
    List<OrderItemDto.Response> importFromReceipt(Long orderId, @Valid List<GoodsReceiptItemDto.ImportRequest> importRequest, Long userId);

    /**
     * Vrátí souhrn všech položek dané zakázky.
     * Souhrn obsahuje agregovaná finanční data — částky bez DPH a s DPH za práci,
     * materiál, ostatní služby a celkové součty.
     *
     * @param orderId ID zakázky, pro kterou se souhrn počítá
     * @return {@code OrderItemSummaryDto.Response} se souhrnnými daty zakázky
     */
    OrderItemSummaryDto.Response getSummaryByOrderId(Long orderId);
}
