package cz.palo.autoservis.service;

import cz.palo.autoservis.model.dto.warehouse.StockTakeDto;
import cz.palo.autoservis.model.dto.warehouse.StockTakeSearchParams;
import cz.palo.autoservis.model.dto.pagination.PagedResponse;

/**
 * Inventura (E6, P-5): soupis k datu, zadání skutečných stavů a uzavření,
 * které vygeneruje korekční pohyby.
 *
 * <p>Rozhodnutí R-H: manko se rozpouští po šaržích od nejstarší (FIFO), přebytek
 * vzniká jako šarže v pseudo-příjemce typu {@code STOCK_TAKE}, a rozdíl se počítá
 * proti <b>aktuálnímu</b> stavu skladu — ne proti snapshotu z otevření, aby
 * inventura nepřepsala výdeje, které během počítání proběhly.
 */
public interface StockTakeService {

    /** Stránkovaný seznam inventur (výchozí řazení: nejnovější první). */
    PagedResponse<StockTakeDto.ListResponse> getPage(StockTakeSearchParams params);

    /** Detail se soupisem a dopočtenými rozdíly. */
    StockTakeDto.DetailResponse getDetail(Long id);

    /**
     * Otevře inventuru a nasnapshotuje soupis všech aktivních produktů.
     * Otevřená smí být jen jedna (409 {@code STOCK_TAKE_ALREADY_OPEN}).
     */
    StockTakeDto.DetailResponse open(StockTakeDto.CreateRequest request, Long userId);

    /** Zápis napočítaných množství a cen přebytku; jen ve stavu OPEN. */
    StockTakeDto.DetailResponse updateItems(Long id, StockTakeDto.ItemsUpdateRequest request);

    /**
     * Uzavře inventuru a vygeneruje korekce: manko záporným ADJUSTMENT po šaržích
     * (FIFO), přebytek novou šarží v pseudo-příjemce a kladným ADJUSTMENT.
     * Řádky bez napočítaného množství se přeskočí.
     */
    StockTakeDto.DetailResponse close(Long id, String note, Long userId);

    /** Zruší otevřenou inventuru — nic se nematerializuje. */
    StockTakeDto.DetailResponse cancel(Long id, Long userId);
}
