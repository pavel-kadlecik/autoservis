package cz.palo.autoservis.model.dto.warehouse;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Díl pod hlídaným minimem i s doporučeným dodavatelem (E8.3, P-7).
 *
 * <p>Doporučení vychází z převodníku {@code supplier_products} — z posledního
 * dodavatele, který díl dodal, a jeho poslední ceny. U dílu bez záznamu
 * v převodníku zůstávají pole prázdná (není z čeho doporučit).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LowStockDto {

    private Long productId;
    private String sku;
    private String name;
    private String unit;
    /** Fyzický stav — kolik kusů leží v regálu. */
    private BigDecimal quantityOnHand;
    /** Kolik z fyzického stavu je slíbeno otevřeným zakázkám a ještě nevydáno (V83). */
    private BigDecimal quantityReserved;
    /** {@code quantityOnHand − quantityReserved} — proti tomuhle se minimum hlídá. */
    private BigDecimal quantityAvailable;
    private BigDecimal minStockLevel;
    /**
     * Kolik chybí do minima ({@code min − dostupné}).
     *
     * <p>Počítá se z <strong>dostupného</strong>, ne z fyzického stavu: díl slíbený jiné
     * zakázce se objednat musí taky, jinak by na další práci nezbyl.
     */
    private BigDecimal missingQuantity;

    private Long supplierId;
    private String supplierName;
    /** Katalogové číslo dílu u tohoto dodavatele — s ním se objednává. */
    private String supplierSku;
    private BigDecimal lastUnitPriceExclVat;
    private OffsetDateTime lastSeenAt;
}
