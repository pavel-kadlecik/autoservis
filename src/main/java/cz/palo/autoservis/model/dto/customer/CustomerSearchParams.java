package cz.palo.autoservis.model.dto.customer;

import cz.palo.autoservis.model.dto.pagination.SearchParams;
import lombok.Data;

/**
 * Parametry hledání pro seznamový endpoint zákazníků.
 * Předává se jako {@code @Param("params")} do MyBatis XML mapperů.
 *
 * <p>Dědí ze {@link SearchParams} (ne jen z {@code BaseParams}), aby sdílela pole
 * {@code search} a víceslovný tokenizér {@link SearchParams#getSearchTokens()}
 * s {@link cz.palo.autoservis.model.dto.order.OrderSearchParams} a ostatními
 * search-param třídami — jedna implementace místo duplikátu v každé třídě.
 */
@Data
public class CustomerSearchParams extends SearchParams {

    /**
     * Výchozí řazení seznamu — nejnovější zákazníci první.
     *
     * Default patří sem, ne do <otherwise> v XML: tam by jako jediný
     * ignoroval sortDesc a nebyl by z Javy vidět (U3R.1).
     */
    public CustomerSearchParams() {
        setSortBy("createdAt");
        setSortDesc(true);
    }


    /**
     * Pole {@code customerType} a {@code city} tady BÝVALA a {@code api.md} je popisovalo jako
     * funkční filtry — jenže {@code CustomerMapper.xml} je v žádné {@code WHERE} klauzuli nečetl,
     * takže se nikdy neprojevily (audit 2026-07-30, bod 6.3). Smazána podle R-12 místo
     * doimplementování: filtrování podle typu a města nikdo nežádal a fulltext město už pokrývá
     * přes adresy. Rozhodnutí uživatele 2026-07-31.
     */
    private boolean activeOnly;



}
