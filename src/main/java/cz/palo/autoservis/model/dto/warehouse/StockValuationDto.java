package cz.palo.autoservis.model.dto.warehouse;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * DTO namespace pro ocenění zásob (E3.2, P-4).
 *
 * <p>Hodnota se počítá ze zbytků šarží a jejich skutečných nákupních cen
 * (skutečné pořizovací ceny — rozhodnutí R-A), tedy vždy bez DPH.
 */
public class StockValuationDto {

    /** Jeden produkt v přehledu ocenění (řádek view {@code v_stock_valuation}). */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class Item {
        private Long productId;
        private String sku;
        private String name;
        private String unit;
        private BigDecimal quantityOnHand;
        /** Σ (zbytek šarže × nákupní cena bez DPH), zaokrouhleno po šarži. */
        private BigDecimal stockValue;
    }

    /** Celková hodnota skladu + rozpad po produktech. */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class Response {
        /** Součet {@code stockValue} všech položek; prázdný sklad = 0. */
        private BigDecimal totalValue;
        private List<Item> items;
    }
}
