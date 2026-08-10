package cz.palo.autoservis.model.dto.warehouse;

import cz.palo.autoservis.model.domain.warehouse.StockTakeStatus;
import cz.palo.autoservis.model.dto.pagination.SearchParams;
import lombok.Getter;
import lombok.Setter;

/**
 * Parametry výpisu inventur (stránkování + řazení).
 * Předává se jako {@code @Param("params")} do StockTakeMapper.
 */
@Getter
@Setter
public class StockTakeSearchParams extends SearchParams {

    /**
     * Výchozí řazení: nejnovější inventura první (podle zahájení).
     * Default patří sem, ne do &lt;otherwise&gt; v XML — tam by ignoroval sortDesc
     * a nebyl by z Javy vidět (U3R.1).
     */
    public StockTakeSearchParams() {
        setSortBy("openedAt");
        setSortDesc(true);
    }

    /** Když je vyplněno, vrací jen inventury v tomto stavu (null = všechny). */
    private StockTakeStatus status;

    /** Fulltext ({@code search} z rodiče) hledá přes číslo inventury a poznámku. */
}
