package cz.palo.autoservis.model.dto.user;

import cz.palo.autoservis.model.dto.pagination.SearchParams;
import lombok.Data;

/**
 * Parametry hledání pro adminský seznamový endpoint uživatelů.
 * Předává se jako {@code @Param("params")} do MyBatis XML mapperů.
 */
@Data
public class UserSearchParams extends SearchParams {

    /**
     * Výchozí řazení seznamu — nejnovější účty první.
     *
     * Default patří sem, ne do <otherwise> v XML: tam by jako jediný
     * ignoroval sortDesc a nebyl by z Javy vidět (U3R.1).
     */
    public UserSearchParams() {
        setSortBy("createdAt");
        setSortDesc(true);
    }


    /** Filtruje na uživatele s tímto ID role (security.roles.id). */
    private Integer roleId;

    private boolean activeOnly;

}
