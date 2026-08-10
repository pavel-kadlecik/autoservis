package cz.palo.autoservis.model.dto.pagination;

import lombok.Getter;
import lombok.Setter;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Společné parametry hledání s fulltextovým polem.
 * Předává se jako {@code @Param("params")} do MyBatis XML mapperů.
 */
@Getter
@Setter
public class SearchParams extends BaseParams {

    /** Fulltextový dotaz — porovnává se se jménem, e-mailem, telefonem atd. */
    private String search;

    /**
     * Sloupec řazení. Hodnota je **klíč z whitelistu** v příslušném XML mapperu
     * (např. {@code lastName}, {@code customerNumber}), ne název sloupce v DB —
     * identifikátor nejde předat přes {@code #{}}, takže whitelist v mapperu je
     * zároveň ochranou proti SQL injection.
     *
     * <p>Neznámá nebo chybějící hodnota spadne do větve {@code <otherwise>},
     * která má pevné výchozí řazení. Směr určuje {@code sortDesc} v {@link BaseParams}.
     */
    private String sortBy;

    /**
     * Rozdělí {@link #search} na jednotlivé tokeny oddělené bílými znaky (ořezané,
     * prázdné položky vypuštěné). Používají ho víceslovné vyhledávací XML dotazy —
     * každý token musí sedět aspoň na jeden prohledávaný sloupec (AND mezi tokeny,
     * OR mezi sloupci).
     *
     * @return seznam tokenů; prázdný (nikdy null), když je {@link #search} null/prázdný
     */
    public List<String> getSearchTokens() {
        if (search == null || search.isBlank()) {
            return Collections.emptyList();
        }
        return Arrays.asList(search.trim().split("\\s+"));
    }
}
