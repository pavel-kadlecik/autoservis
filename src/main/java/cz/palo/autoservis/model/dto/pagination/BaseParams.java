package cz.palo.autoservis.model.dto.pagination;

import lombok.Getter;
import lombok.Setter;

/**
 * Bázová třída stránkovaných query parametrů.
 * Předává se jako {@code @Param("params")} do MyBatis XML mapperů.
 */
@Getter
@Setter
public class BaseParams {

    /** Horní strop velikosti stránky — brání vytažení celé tabulky jedním requestem (E6.2/S-6). */
    public static final int MAX_PAGE_SIZE = 100;

    private int page = 1;
    private int pageSize = 20;

    // Explicitní settery s clampem (Lombok je nepřepíše). Bez toho page=0 → OFFSET −n → 500,
    // pageSize=0 → dělení nulou v PagedResponse, pageSize=100000 → dump celé tabulky (audit S-6).
    public void setPage(int page) {
        this.page = Math.max(1, page);
    }

    public void setPageSize(int pageSize) {
        this.pageSize = Math.min(Math.max(1, pageSize), MAX_PAGE_SIZE);
    }

    /**
     * Směr řazení pro sloupec zvolený přes {@code sortBy}.
     *
     * <p>Default je {@code false} (vzestupně) záměrně: do 2026-07 se {@code sortDesc}
     * v žádném XML mapperu nepoužíval, takže seznamy fakticky řadily vždy vzestupně
     * (TD-46). Ponechat původní default {@code true} by po zprovoznění směru tiše
     * obrátilo pořadí všem volajícím, kteří parametr neposílají.
     *
     * <p>Neplatí pro fallback větev {@code <otherwise>} v mapperech — ta si nese
     * vlastní pevný směr (typicky {@code created_at DESC}, tedy nejnovější první).
     */
    private boolean sortDesc = false;

    /** Vrací SQL OFFSET pro aktuální stránku. */
    public int getOffset() {
        return (page - 1) * pageSize;
    }
}
