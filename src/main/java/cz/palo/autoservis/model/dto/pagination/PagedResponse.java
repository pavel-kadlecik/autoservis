package cz.palo.autoservis.model.dto.pagination;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Generický obal stránkovaných API odpovědí.
 *
 * @param <T> typ položek stránky
 */
@Data
@Builder
public class PagedResponse<T> {

    private List<T> content;
    private int page;
    private int pageSize;
    private long totalElements;
    private int totalPages;
    private boolean first;
    private boolean last;

    /**
     * Sestaví stránkovanou odpověď z už načteného výřezu obsahu a celkového počtu.
     *
     * <p>{@code page} je <strong>1-based</strong> (první stránka = 1), v souladu
     * s API kontraktem (viz {@code api.md}) — příznaky {@code first}/{@code last}
     * se počítají proti této konvenci. Dřívější 0-based výpočet (TD-50) hlásil
     * {@code first} vždy false a {@code last} o stránku dřív, takže poslední
     * stránka byla pro klienta nedosažitelná.
     *
     * @param content       položky aktuální stránky
     * @param page          1-based index stránky (první stránka = 1)
     * @param pageSize      počet položek na stránku
     * @param totalElements celkový počet odpovídajících záznamů
     * @param <T>           typ položky
     * @return naplněná {@link PagedResponse}
     */
    public static <T> PagedResponse<T> of(List<T> content, int page, int pageSize, long totalElements) {
        int totalPages = (int) Math.ceil((double) totalElements / pageSize);
        return PagedResponse.<T>builder()
                .content(content)
                .page(page)
                .pageSize(pageSize)
                .totalElements(totalElements)
                .totalPages(totalPages)
                .first(page <= 1)
                .last(page >= totalPages)
                .build();
    }
}
