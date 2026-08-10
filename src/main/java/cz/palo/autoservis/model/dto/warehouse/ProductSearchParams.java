package cz.palo.autoservis.model.dto.warehouse;

import cz.palo.autoservis.model.dto.pagination.SearchParams;
import lombok.Getter;
import lombok.Setter;

/**
 * Parametry hledání pro seznamový endpoint skladových zásob (produktů).
 * Předává se jako {@code @Param("params")} do MyBatis XML mapperů.
 */
@Getter
@Setter
public class ProductSearchParams extends SearchParams {

    /**
     * Výchozí řazení seznamu — abecedně podle názvu dílu.
     *
     * Default patří sem, ne do <otherwise> v XML: tam by jako jediný
     * ignoroval sortDesc a nebyl by z Javy vidět (U3R.1).
     */
    public ProductSearchParams() {
        setSortBy("name");
        setSortDesc(false);
    }


    /** True = vrací se jen aktivní produkty; false = včetně neaktivních. */
    private boolean activeOnly;

    /** True = vrací se jen hlídané produkty pod svým minimálním stavem. */
    private boolean lowStockOnly;
}
