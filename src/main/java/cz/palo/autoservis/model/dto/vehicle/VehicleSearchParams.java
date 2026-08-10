package cz.palo.autoservis.model.dto.vehicle;

import cz.palo.autoservis.model.dto.pagination.SearchParams;
import lombok.Getter;
import lombok.Setter;

/**
 * Parametry hledání pro seznamový endpoint vozidel.
 * Předává se jako {@code @Param("params")} do MyBatis XML mapperů.
 */
@Getter
@Setter
public class VehicleSearchParams extends SearchParams {

    /**
     * Vychozi razeni seznamu - seskupeno po zakaznicich, uvnitr podle roku vyroby.
     *
     * Default patri sem, ne do <otherwise> v XML: tam by jako jediny
     * ignoroval sortDesc a nebyl by z Javy videt (U3R.1).
     */
    public VehicleSearchParams() {
        setSortBy("customer");
        setSortDesc(false);
    }



    /** True = vrací se jen aktivní vozidla; false = včetně neaktivních. */
    private boolean activeOnly;

    /**
     * True = vrací se jen vozidla, jimž STK vyprší do 30 dnů (nebo už vypršela).
     * Vozidla bez dat z registru jsou vyloučena.
     */
    private boolean stkExpiring;
}
