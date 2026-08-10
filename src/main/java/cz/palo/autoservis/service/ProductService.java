package cz.palo.autoservis.service;

import cz.palo.autoservis.model.dto.pagination.PagedResponse;
import cz.palo.autoservis.model.dto.warehouse.ProductDto;
import cz.palo.autoservis.model.dto.warehouse.ProductSearchParams;
import cz.palo.autoservis.model.dto.warehouse.StockMovementDto;
import cz.palo.autoservis.model.dto.warehouse.StockValuationDto;

import java.util.List;

/**
 * Service pro skladový přehled (produkty).
 */
public interface ProductService {

    /**
     * Vrátí stránkovaný seznam produktů odpovídajících zadaným parametrům vyhledávání.
     *
     * @param params parametry vyhledávání (filtry, stránka, velikost stránky)
     * @return stránkovaná odpověď
     */
    PagedResponse<ProductDto.ListResponse> getPage(ProductSearchParams params);

    /**
     * Vrátí kompletní skladovou kartu produktu (hlavička, šarže, historie pohybů,
     * rozpad rezervací).
     *
     * @param id ID produktu
     * @return detail produktu
     */
    ProductDto.DetailResponse getById(Long id);

    /**
     * Založí nový produkt (skladovou kartu).
     *
     * @param request validovaný požadavek na založení
     * @return detail založeného produktu
     */
    ProductDto.DetailResponse create(ProductDto.CreateRequest request);

    /**
     * Aktualizuje katalogová pole produktu.
     *
     * @param id      ID produktu
     * @param request validovaný požadavek na aktualizaci
     * @return aktualizovaný detail produktu
     */
    ProductDto.DetailResponse update(Long id, ProductDto.UpdateRequest request);

    /**
     * Deaktivuje produkt (soft delete).
     *
     * @param id ID produktu
     * @return aktualizovaný detail produktu
     */
    ProductDto.DetailResponse deactivate(Long id);

    /**
     * Znovu aktivuje dříve deaktivovaný produkt.
     *
     * @param id ID produktu
     * @return aktualizovaný detail produktu
     */
    ProductDto.DetailResponse activate(Long id);

    /**
     * Vrátí seznam produktů navázaných na konkrétní příjemku.
     *
     * @param id ID příjemky
     * @return seznam produktů patřících k zadané příjemce
     */
    List<ProductDto.ListResponse> getByGoodsReceiptId(Long id);

    /**
     * Zaznamená ruční záporný skladový pohyb proti konkrétní šarži produktu.
     *
     * <p>Povolené typy jsou <strong>čtyři</strong> — korekce dolů ({@code ADJUSTMENT}),
     * odpis ({@code WRITE_OFF}), vratka dodavateli ({@code RETURN}) a spotřeba bez zakázky
     * ({@code ISSUE}); vynucuje je {@code StockMovementDto.CreateRequest.isManualMovementType}.
     * Liší se jen důvodem, který zůstává dohledatelný v ledgeru — u vratky navíc přibývá
     * {@code returnReason}. Dřívější znění tu zmiňovalo jen korekci a odpis, což svádělo
     * odepsat vadný díl místo vratky; v append-only ledgeru se to už neopraví (audit KN-23).
     *
     * <p>Stav skladu i zůstatek šarže sníží DB trigger. Kladný přebytek se řeší ruční
     * příjemkou (R-E), ne tudy (E2.1, P-1).
     *
     * @param productId ID produktu
     * @param request   validovaný požadavek (typ, šarže, kladné množství, poznámka)
     * @param userId    ID přihlášeného uživatele (audit)
     * @return aktualizovaný detail produktu
     */
    ProductDto.DetailResponse registerManualMovement(Long productId,
                                                     StockMovementDto.CreateRequest request,
                                                     Long userId);

    /**
     * Ocenění zásob: hodnota skladu v nákupních cenách bez DPH (Σ zbytek šarže ×
     * cena šarže) — celkem i s rozpadem po produktech (E3.2, P-4).
     *
     * @return celková hodnota a řádky po produktech; prázdný sklad = 0
     */
    StockValuationDto.Response getStockValuation();

    /**
     * Díly pod hlídaným minimem i s doporučeným dodavatelem (E8.3, P-7) —
     * podklad pro objednání, ne objednávka.
     *
     * @return řádky přehledu, nejvíc chybějící první
     */
    List<cz.palo.autoservis.model.dto.warehouse.LowStockDto> getLowStock();
}
