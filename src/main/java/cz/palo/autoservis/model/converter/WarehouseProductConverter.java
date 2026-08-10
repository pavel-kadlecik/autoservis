package cz.palo.autoservis.model.converter;

import cz.palo.autoservis.model.domain.warehouse.Product;
import cz.palo.autoservis.model.dto.warehouse.ProductDto;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Konvertor mezi doménovými objekty {@link Product} a DTO {@link ProductDto}.
 */
@Component
public class WarehouseProductConverter {

    /**
     * Mapuje produkt na jeho řádek přehledu skladu.
     *
     * @param product doménový produkt
     * @return seznamová response, nebo {@code null} při {@code null} vstupu
     */
    public ProductDto.ListResponse toListResponse(Product product) {
        if (product == null) {
            return null;
        }
        return ProductDto.ListResponse.builder()
                .id(product.getId())
                .sku(product.getSku())
                .name(product.getName())
                .manufacturer(product.getManufacturer())
                .manufacturerPartNumber(product.getManufacturerPartNumber())
                .variant(product.getVariant())
                .unit(product.getUnit())
                .quantityOnHand(product.getQuantityOnHand())
                .quantityReserved(product.getQuantityReserved())
                .quantityAvailable(quantityAvailable(product))
                .salePrice(product.getSalePrice())
                .minStockLevel(product.getMinStockLevel())
                .defaultVatRate(product.getDefaultVatRate())
                .active(product.getActive())
                .lowStock(isLowStock(product))
                .build();
    }

    /**
     * Mapuje seznam produktů na řádky přehledu skladu.
     *
     * @param products doménové produkty
     * @return seznam responsí
     */
    public List<ProductDto.ListResponse> toListResponses(List<Product> products) {
        return products.stream().map(this::toListResponse).collect(Collectors.toList());
    }

    /**
     * Sestaví plný detail skladové karty z produktu a předem načtených
     * řádků šarží a pohybů.
     *
     * @param product   doménový produkt
     * @param batches   řádky šarží (už naprojektované mapperem)
     * @param movements řádky pohybů (už naprojektované mapperem)
     * @return detailová response, nebo {@code null} při {@code null} produktu
     */
    public ProductDto.DetailResponse toDetailResponse(Product product,
                                                      List<ProductDto.BatchResponse> batches,
                                                      List<ProductDto.MovementResponse> movements,
                                                      List<ProductDto.ReservationResponse> reservations) {
        if (product == null) {
            return null;
        }
        return ProductDto.DetailResponse.builder()
                .id(product.getId())
                .sku(product.getSku())
                .name(product.getName())
                .manufacturer(product.getManufacturer())
                .manufacturerPartNumber(product.getManufacturerPartNumber())
                .variant(product.getVariant())
                .note(product.getNote())
                .unit(product.getUnit())
                .quantityOnHand(product.getQuantityOnHand())
                .quantityReserved(product.getQuantityReserved())
                .quantityAvailable(quantityAvailable(product))
                .salePrice(product.getSalePrice())
                .minStockLevel(product.getMinStockLevel())
                .defaultVatRate(product.getDefaultVatRate())
                .active(product.getActive())
                .lowStock(isLowStock(product))
                .createdAt(product.getCreatedAt())
                .updatedAt(product.getUpdatedAt())
                .batches(batches)
                .movements(movements)
                .reservations(reservations)
                .build();
    }

    /**
     * Sestaví nový {@link Product} z create requestu. {@code active} má výchozí
     * true; {@code quantityOnHand} zůstává null (DB default 0).
     *
     * @param request create request
     * @return doménový produkt připravený k INSERTu, nebo {@code null} při {@code null} vstupu
     */
    public Product toDomain(ProductDto.CreateRequest request) {
        if (request == null) {
            return null;
        }
        return Product.builder()
                .active(true)
                .sku(request.getSku())
                .name(request.getName())
                .manufacturer(request.getManufacturer())
                .manufacturerPartNumber(request.getManufacturerPartNumber())
                .variant(request.getVariant())
                .note(request.getNote())
                .unit(request.getUnit())
                .defaultVatRate(request.getDefaultVatRate())
                .salePrice(request.getSalePrice())
                .minStockLevel(request.getMinStockLevel())
                .build();
    }

    /**
     * Aplikuje update request na existující produkt (mění se na místě).
     * Nedotýká se {@code id}, {@code active}, {@code quantityOnHand} ani auditních polí.
     *
     * @param existing produkt načtený z DB
     * @param request  update request
     * @return změněný produkt, nebo {@code null}, je-li kterýkoli argument {@code null}
     */
    public Product applyUpdate(Product existing, ProductDto.UpdateRequest request) {
        if (existing == null || request == null) {
            return null;
        }
        existing.setSku(request.getSku());
        existing.setName(request.getName());
        existing.setManufacturer(request.getManufacturer());
        existing.setManufacturerPartNumber(request.getManufacturerPartNumber());
        existing.setVariant(request.getVariant());
        existing.setNote(request.getNote());
        existing.setUnit(request.getUnit());
        existing.setDefaultVatRate(request.getDefaultVatRate());
        existing.setSalePrice(request.getSalePrice());
        existing.setMinStockLevel(request.getMinStockLevel());
        return existing;
    }

    /**
     * Produkt je „pod minimem", jen když je hlídaný (má nastavený minimální stav)
     * a jeho <em>dostupné</em> množství kleslo pod tuto hranici.
     */
    private static boolean isLowStock(Product product) {
        return product.getMinStockLevel() != null
                && product.getQuantityOnHand() != null
                && quantityAvailable(product).compareTo(product.getMinStockLevel()) < 0;
    }

    /**
     * Dostupné množství = fyzický stav − rezervace (rozhodnutí uživatele 2026-08-05).
     *
     * <p>Rezervovaný díl v regálu leží, ale je slíbený jiné zakázce, takže pro další práci
     * použitelný není. Proti tomuhle číslu se hlídá minimum i validuje přidání dílu na
     * zakázku. <strong>Inventura a ocenění skladu naopak dál pracují
     * s {@code quantityOnHand}</strong> — rezervace do nich vstoupit nesmí, jinak by
     * inventura hlásila manko u dílů, které fyzicky na skladě jsou.
     *
     * <p>Chybějící rezervace se bere jako nula, aby dotaz, který ji neselektuje, vrátil
     * dostupné množství rovné fyzickému stavu místo pádu na {@code null}.
     */
    private static BigDecimal quantityAvailable(Product product) {
        BigDecimal onHand = product.getQuantityOnHand();
        if (onHand == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal reserved = product.getQuantityReserved();
        return reserved == null ? onHand : onHand.subtract(reserved);
    }
}
