package cz.palo.autoservis.model.converter;

import cz.palo.autoservis.model.domain.warehouse.GoodsReceiptItem;

import java.math.BigDecimal;
import cz.palo.autoservis.model.dto.warehouse.GoodsReceiptItemDto;
import org.springframework.stereotype.Component;

@Component
public class GoodsReceiptItemConverter {

    public GoodsReceiptItemDto.Response toDto(GoodsReceiptItem item) {
        if (item == null) {
            return null;
        }

        // Dostupné se dopočítá jen tam, kde dotaz rezervace opravdu spočítal. Kdyby se
        // null bralo jako nula, tvářilo by se „nevím" jako „nic není rezervováno" — a to
        // je přesně ta lež, kvůli které okno výběru šarží nabízelo cizí kusy.
        BigDecimal available = item.getQuantityReserved() == null || item.getQuantityRemaining() == null
                ? null
                : item.getQuantityRemaining().subtract(item.getQuantityReserved());

        return GoodsReceiptItemDto.Response.builder()
                .id(item.getId())
                .productId(item.getProductId())
                .nameSnapshot(item.getNameSnapshot())
                .quantityReceived(item.getQuantityReceived())
                .quantityRemaining(item.getQuantityRemaining())
                .quantityReserved(item.getQuantityReserved())
                .quantityAvailable(available)
                .unitPriceExclVat(item.getUnitPriceExclVat())
                .vatRate(item.getVatRate())
                .build();

    }

}
