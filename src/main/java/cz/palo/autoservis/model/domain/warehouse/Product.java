package cz.palo.autoservis.model.domain.warehouse;

import lombok.*;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** Skladová karta — typ dílu (warehouse.products). */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Product {
    private Long id;
    private String sku;
    private String name;
    private String manufacturer;
    /** Číslo dílu dle výrobce — párovací identita spolu s manufacturer (V40). */
    private String manufacturerPartNumber;
    /** Generovaný sloupec (upper, bez mezer/teček/pomlček) — jen ke čtení. */
    private String partNumberNormalized;
    private String variant;
    private String note;
    private String unit;
    private Integer defaultVatRate;
    private BigDecimal salePrice;
    private BigDecimal minStockLevel;
    private BigDecimal quantityOnHand;
    /**
     * Rezervované množství — díly naplánované na zakázky, které ze skladu ještě fyzicky
     * neodešly. <strong>Není sloupec tabulky</strong>: odvozuje se v dotazu
     * ({@code WarehouseMapper.xml}, fragment {@code reservedQuantity}), aby vedle
     * pohybového ledgeru nevznikl druhý záznam téhož faktu (V83).
     *
     * <p>Fyzického stavu se nedotýká — snižuje jen <em>dostupné</em> množství
     * ({@code quantityOnHand - quantityReserved}). Inventura i ocenění skladu pracují
     * dál s {@code quantityOnHand}.
     */
    private BigDecimal quantityReserved;
    private Boolean active;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
