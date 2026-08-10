package cz.palo.autoservis.service.impl;

import cz.palo.autoservis.config.WarehouseImportProperties;
import cz.palo.autoservis.exception.BusinessRuleException;
import cz.palo.autoservis.exception.ResourceNotFoundException;
import cz.palo.autoservis.mapper.GoodsReceiptMapper;
import cz.palo.autoservis.mapper.WarehouseImportMapper;
import cz.palo.autoservis.mapper.WarehouseMapper;
import cz.palo.autoservis.model.converter.WarehouseProductConverter;
import cz.palo.autoservis.model.domain.warehouse.GoodsReceiptItem;
import cz.palo.autoservis.model.domain.warehouse.Product;
import cz.palo.autoservis.model.domain.warehouse.StockMovement;
import cz.palo.autoservis.model.dto.pagination.PagedResponse;
import cz.palo.autoservis.model.dto.warehouse.ProductDto;
import cz.palo.autoservis.model.dto.warehouse.ProductSearchParams;
import cz.palo.autoservis.model.dto.warehouse.StockMovementDto;
import cz.palo.autoservis.model.dto.warehouse.StockValuationDto;
import cz.palo.autoservis.service.ProductService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Implementace {@link ProductService}.
 */
@Service
@RequiredArgsConstructor
public class ProductServiceImpl implements ProductService {

    private final WarehouseMapper warehouseMapper;
    private final WarehouseProductConverter warehouseProductConverter;
    private final WarehouseImportProperties importProperties;
    private final WarehouseImportMapper warehouseImportMapper;
    private final GoodsReceiptMapper goodsReceiptMapper;

    /** {@inheritDoc} */
    @Override
    public PagedResponse<ProductDto.ListResponse> getPage(ProductSearchParams params) {
        List<Product> products = warehouseMapper.search(params);
        List<ProductDto.ListResponse> content = warehouseProductConverter.toListResponses(products);
        long total = warehouseMapper.countSearch(params);
        return PagedResponse.of(content, params.getPage(), params.getPageSize(), total);
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException pokud je {@code id} null
     */
    @Override
    public List<ProductDto.ListResponse> getByGoodsReceiptId(Long id) {
        if (id == null) {
            throw new IllegalArgumentException("id nesmí být null");
        }
        List<Product> products = warehouseMapper.findByGoodsReceiptId(id);
        return warehouseProductConverter.toListResponses(products);
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException  pokud je {@code id} null
     * @throws ResourceNotFoundException pokud produkt s daným ID neexistuje
     */
    @Override
    public ProductDto.DetailResponse getById(Long id) {
        if (id == null) {
            throw new IllegalArgumentException("id nesmí být null");
        }
        Product product = warehouseMapper.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Skladová položka", id));
        List<ProductDto.BatchResponse> batches = warehouseMapper.findBatchesByProductId(id);
        List<ProductDto.MovementResponse> movements = warehouseMapper.findMovementsByProductId(id);
        // Rozpad rezervací vysvětluje, proč je dostupné množství nižší než fyzický stav
        // (rozhodnutí uživatele 2026-08-05 — na kartě produktu seznam zakázek).
        List<ProductDto.ReservationResponse> reservations = warehouseMapper.findReservationsByProductId(id);
        return warehouseProductConverter.toDetailResponse(product, batches, movements, reservations);
    }

    /**
     * {@inheritDoc}
     *
     * @throws BusinessRuleException pokud produkt se stejným SKU už existuje
     */
    @Override
    @Transactional
    public ProductDto.DetailResponse create(ProductDto.CreateRequest request) {
        if (warehouseMapper.existsBySku(request.getSku())) {
            throw duplicateSku(request.getSku());
        }
        Product product = warehouseProductConverter.toDomain(request);
        product.setUnit(requireValidUnit(product.getUnit()));
        warehouseMapper.insert(product);
        return getById(product.getId());
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException  pokud je {@code id} null
     * @throws ResourceNotFoundException pokud produkt s daným ID neexistuje
     * @throws BusinessRuleException     pokud nové SKU už používá jiný produkt
     */
    @Override
    @Transactional
    public ProductDto.DetailResponse update(Long id, ProductDto.UpdateRequest request) {
        if (id == null) {
            throw new IllegalArgumentException("id nesmí být null");
        }
        Product existing = warehouseMapper.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Skladová položka", id));

        if (!request.getSku().equals(existing.getSku()) && warehouseMapper.existsBySku(request.getSku())) {
            throw duplicateSku(request.getSku());
        }

        Product updated = warehouseProductConverter.applyUpdate(existing, request);
        updated.setUnit(requireValidUnit(updated.getUnit()));
        int affectedRows = warehouseMapper.update(updated);
        if (affectedRows == 0) {
            throw new IllegalStateException("Skladová položka " + id + " zmizela během aktualizace");
        }
        return getById(id);
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException  pokud je {@code id} null
     * @throws ResourceNotFoundException pokud produkt s daným ID neexistuje
     * @throws BusinessRuleException     pokud má produkt ještě zásobu na skladě (TD-28)
     */
    @Override
    @Transactional
    public ProductDto.DetailResponse deactivate(Long id) {
        if (id == null) {
            throw new IllegalArgumentException("id nesmí být null");
        }
        Product product = warehouseMapper.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Skladová položka", id));
        BigDecimal quantityOnHand = product.getQuantityOnHand();
        if (quantityOnHand != null && quantityOnHand.compareTo(BigDecimal.ZERO) > 0) {
            throw new BusinessRuleException(
                    "PRODUCT_HAS_STOCK",
                    "quantityOnHand",
                    "Produkt má zásobu na skladě (" + quantityOnHand + ") — nelze deaktivovat.",
                    Map.of("quantityOnHand", quantityOnHand));
        }
        if (warehouseMapper.deactivate(id) == 0) {
            throw new ResourceNotFoundException("Skladová položka", id);
        }
        return getById(id);
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException  pokud je {@code id} null
     * @throws ResourceNotFoundException pokud produkt s daným ID neexistuje
     */
    @Override
    @Transactional
    public ProductDto.DetailResponse activate(Long id) {
        if (id == null) {
            throw new IllegalArgumentException("id nesmí být null");
        }
        if (warehouseMapper.activate(id) == 0) {
            throw new ResourceNotFoundException("Skladová položka", id);
        }
        return getById(id);
    }

    /** {@inheritDoc} */
    @Override
    public List<cz.palo.autoservis.model.dto.warehouse.LowStockDto> getLowStock() {
        return warehouseMapper.findLowStock();
    }

    /** Prázdné číslo dobropisu z formuláře ukládáme jako NULL, ne jako "". */
    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** {@inheritDoc} */
    @Override
    public StockValuationDto.Response getStockValuation() {
        List<StockValuationDto.Item> items = warehouseMapper.findStockValuation();
        BigDecimal total = items.stream()
                .map(StockValuationDto.Item::getStockValue)
                .filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return StockValuationDto.Response.builder()
                .totalValue(total)
                .items(items)
                .build();
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException  pokud je {@code productId} null
     * @throws ResourceNotFoundException pokud produkt nebo šarže neexistuje
     * @throws BusinessRuleException     pokud šarže patří jinému produktu
     *                                   ({@code BATCH_PRODUCT_MISMATCH}) nebo požadované
     *                                   množství přesahuje zůstatek šarže
     *                                   ({@code QUANTITY_EXCEEDS_REMAINING})
     */
    @Override
    @Transactional
    public ProductDto.DetailResponse registerManualMovement(Long productId,
                                                            StockMovementDto.CreateRequest request,
                                                            Long userId) {
        if (productId == null) {
            throw new IllegalArgumentException("productId nesmí být null");
        }
        // produkt musí existovat (jinak 404, ne matoucí chyba o šarži)
        warehouseMapper.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Skladová položka", productId));

        // FOR UPDATE zamkne šarži do konce transakce — souběžné korekce se serializují
        // proti stejnému quantity_remaining (vzor K6, analyza-2026-07)
        GoodsReceiptItem batch = goodsReceiptMapper.findByIdsForUpdate(List.of(request.getBatchId()))
                .stream().findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Položka příjemky", request.getBatchId()));

        if (!batch.getProductId().equals(productId)) {
            throw new BusinessRuleException(
                    "BATCH_PRODUCT_MISMATCH",
                    "batchId",
                    "Šarže nepatří k tomuto produktu.",
                    Map.of("batchId", request.getBatchId(), "productId", productId));
        }

        BigDecimal requested = request.getQuantity();
        if (requested.compareTo(batch.getQuantityRemaining()) > 0) {
            throw new BusinessRuleException(
                    "QUANTITY_EXCEEDS_REMAINING",
                    "quantity",
                    "Požadované množství je větší, než zbývá v šarži.",
                    Map.of("batchId", request.getBatchId(),
                            "requested", requested,
                            "remaining", batch.getQuantityRemaining()));
        }

        // ruční pohyb je vždy záporný; stav skladu i zůstatek šarže sníží DB trigger.
        // returnReason/creditNoteNumber plní jen vratka — konzistenci hlídá už validace
        // DTO (zrcadlí DB CHECK chk_return_reason), takže se propisují tak, jak přišly.
        StockMovement movement = StockMovement.builder()
                .productId(productId)
                .batchId(batch.getId())
                .movementType(request.getMovementType())
                .quantity(requested.negate())
                .returnReason(request.getReturnReason())
                .creditNoteNumber(blankToNull(request.getCreditNoteNumber()))
                .note(request.getNote())
                .createdBy(userId)
                .build();
        warehouseImportMapper.insertMovement(movement);

        return getById(productId);
    }

    /**
     * Z-4: jednotka musí patřit do uzavřeného číselníku (application.yaml,
     * {@code warehouse.import.allowed-units}). Vrací kanonickou podobu („KS" → „ks"),
     * takže se karta uloží konzistentně; mimo číselník vyhodí 422 {@code INVALID_UNIT}.
     */
    private String requireValidUnit(String unit) {
        String canonical = importProperties.canonicalUnit(unit);
        if (canonical == null) {
            throw new BusinessRuleException(
                    "INVALID_UNIT",
                    "unit",
                    "Neplatná měrná jednotka: " + unit + " — povolené: " + importProperties.getAllowedUnits(),
                    Map.of("unit", unit == null ? "" : unit,
                            "allowed", importProperties.getAllowedUnits()));
        }
        return canonical;
    }

    private BusinessRuleException duplicateSku(String sku) {
        return new BusinessRuleException(
                "DUPLICATE_SKU",
                "sku",
                "Skladová položka se SKU " + sku + " už existuje.",
                Map.of("sku", sku));
    }
}
