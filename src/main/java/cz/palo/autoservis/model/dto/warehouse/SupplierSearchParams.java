package cz.palo.autoservis.model.dto.warehouse;

import cz.palo.autoservis.model.dto.pagination.SearchParams;
import lombok.Getter;
import lombok.Setter;

/**
 * Parametry hledání pro seznamový endpoint dodavatelů skladu.
 * Předává se jako {@code @Param("params")} do MyBatis XML mapperů.
 */
@Getter
@Setter
public class SupplierSearchParams extends SearchParams {

    /**
     * Výchozí řazení seznamu — abecedně podle názvu.
     *
     * Default patří sem, ne do <otherwise> v XML: tam by jako jediný
     * ignoroval sortDesc a nebyl by z Javy vidět (U3R.1).
     */
    public SupplierSearchParams() {
        setSortBy("name");
        setSortDesc(false);
    }


    /** True = vrací se jen aktivní dodavatelé; false = včetně neaktivních. */
    private boolean activeOnly;
}
