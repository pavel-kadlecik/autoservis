package cz.palo.autoservis.model.dto.autocomplete;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * Response DTO pro autocomplete endpointy.
 * Obsahuje seznam odpovídajících položek a příznak, zda existují další výsledky.
 */
@Getter
@Setter
public class AutocompleteResponse {

    private List<AutocompleteItem> data;

    /** {@code true}, pokud byl výsledek oříznut a na serveru existují další záznamy. */
    private boolean hasMore;
}
