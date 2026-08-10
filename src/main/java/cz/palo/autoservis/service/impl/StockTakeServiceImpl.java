package cz.palo.autoservis.service.impl;

import cz.palo.autoservis.config.WarehouseImportProperties;
import cz.palo.autoservis.exception.BusinessRuleException;
import cz.palo.autoservis.exception.ConflictException;
import cz.palo.autoservis.exception.ResourceNotFoundException;
import cz.palo.autoservis.mapper.StockTakeMapper;
import cz.palo.autoservis.mapper.WarehouseImportMapper;
import cz.palo.autoservis.model.domain.warehouse.DocumentType;
import cz.palo.autoservis.model.domain.warehouse.GoodsReceipt;
import cz.palo.autoservis.model.domain.warehouse.GoodsReceiptItem;
import cz.palo.autoservis.model.domain.warehouse.MovementType;
import cz.palo.autoservis.model.domain.warehouse.ReceiptSource;
import cz.palo.autoservis.model.domain.warehouse.ReceiptStatus;
import cz.palo.autoservis.model.domain.warehouse.StockMovement;
import cz.palo.autoservis.model.domain.warehouse.StockTake;
import cz.palo.autoservis.model.domain.warehouse.StockTakeStatus;
import cz.palo.autoservis.model.dto.warehouse.StockTakeDto;
import cz.palo.autoservis.model.dto.warehouse.StockTakeSearchParams;
import cz.palo.autoservis.model.dto.pagination.PagedResponse;
import cz.palo.autoservis.service.StockTakeService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Implementace {@link StockTakeService}.
 *
 * <p>Skladový invariant platí i tady: stav ani zůstatky šarží se nikdy nezapisují
 * přímo — inventura vkládá pohyby a zbytek dopočítá DB trigger.
 */
@Service
@RequiredArgsConstructor
public class StockTakeServiceImpl implements StockTakeService {

    private static final String RESOURCE = "Inventura";

    private final StockTakeMapper mapper;
    private final WarehouseImportMapper importMapper;
    private final WarehouseImportProperties properties;

    // ------------------------------------------------------------------ čtení

    @Override
    public PagedResponse<StockTakeDto.ListResponse> getPage(StockTakeSearchParams params) {
        List<StockTakeDto.ListResponse> content = mapper.search(params);
        long total = mapper.countSearch(params);
        return PagedResponse.of(content, params.getPage(), params.getPageSize(), total);
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException  pokud je {@code id} null
     * @throws ResourceNotFoundException pokud inventura s daným ID neexistuje
     */
    @Override
    public StockTakeDto.DetailResponse getDetail(Long id) {
        if (id == null) {
            throw new IllegalArgumentException("id nesmí být null");
        }
        StockTake stockTake = mapper.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(RESOURCE, id));
        List<StockTakeDto.ItemResponse> items = mapper.findItems(id);

        int counted = 0;
        int shortages = 0;
        int surpluses = 0;
        for (StockTakeDto.ItemResponse item : items) {
            if (item.getCountedQuantity() == null) continue;
            counted++;
            int sign = item.getDifference().compareTo(BigDecimal.ZERO);
            if (sign < 0) shortages++;
            if (sign > 0) surpluses++;
        }

        return StockTakeDto.DetailResponse.builder()
                .id(stockTake.getId())
                .stockTakeNumber(stockTake.getStockTakeNumber())
                .status(stockTake.getStatus())
                .note(stockTake.getNote())
                .openedAt(stockTake.getOpenedAt())
                .closedAt(stockTake.getClosedAt())
                .surplusReceiptId(stockTake.getSurplusReceiptId())
                .countedLines(counted)
                .shortageLines(shortages)
                .surplusLines(surpluses)
                .items(items)
                .build();
    }

    // ------------------------------------------------------------------ otevření

    /**
     * {@inheritDoc}
     *
     * @throws ConflictException pokud už je otevřená jiná inventura
     */
    @Override
    @Transactional
    public StockTakeDto.DetailResponse open(StockTakeDto.CreateRequest request, Long userId) {
        mapper.findOpenId().ifPresent(openId -> {
            throw new ConflictException("STOCK_TAKE_ALREADY_OPEN",
                    "Inventura " + openId + " je otevřená — uzavřete ji nebo zrušte.");
        });

        StockTake stockTake = StockTake.builder()
                .note(request == null ? null : request.getNote())
                .openedBy(userId)
                .build();
        mapper.insert(stockTake);
        mapper.snapshotActiveProducts(stockTake.getId());

        return getDetail(stockTake.getId());
    }

    // ------------------------------------------------------------------ soupis

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException  pokud je {@code id} null
     * @throws ResourceNotFoundException pokud inventura s daným ID (nebo její položka)
     *                                   neexistuje
     * @throws BusinessRuleException     pokud inventura už není OPEN
     */
    @Override
    @Transactional
    public StockTakeDto.DetailResponse updateItems(Long id, StockTakeDto.ItemsUpdateRequest request) {
        if (id == null) {
            throw new IllegalArgumentException("id nesmí být null");
        }
        requireOpen(mapper.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(RESOURCE, id)));

        for (StockTakeDto.ItemUpdate item : request.getItems()) {
            int updated = mapper.updateItem(id, item.getId(),
                    item.getCountedQuantity(), item.getSurplusUnitPrice());
            if (updated == 0) {
                throw new ResourceNotFoundException("Položka inventury", item.getId());
            }
        }
        return getDetail(id);
    }

    // ------------------------------------------------------------------ uzavření

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException  pokud je {@code id} null
     * @throws ResourceNotFoundException pokud inventura s daným ID neexistuje
     * @throws BusinessRuleException     pokud inventura není OPEN, manko přesahuje
     *                                   zůstatky šarží, nebo přebytek nemá cenu
     * @throws ConflictException         pokud ji mezitím uzavřel někdo jiný
     */
    @Override
    @Transactional
    public StockTakeDto.DetailResponse close(Long id, String note, Long userId) {
        if (id == null) {
            throw new IllegalArgumentException("id nesmí být null");
        }
        StockTake stockTake = mapper.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(RESOURCE, id));
        requireOpen(stockTake);

        // Rozdíly se počítají proti aktuálnímu stavu, ne proti snapshotu — během počítání
        // mohl proběhnout výdej a ten se nesmí přepsat (R-H).
        //
        // Zmrazíme je do closed_* JEŠTĚ PŘED korekcemi (V65, audit KN-2): korekce srovnají
        // quantity_on_hand na napočítané množství, takže potom už není z čeho rozdíl spočítat
        // a uzavřená inventura vykazovala samé nuly — nedoložila ani jedno manko, přestože je
        // právě zaúčtovala. Následné findItems tak čte tytéž zmrazené hodnoty, které jdou do
        // ledgeru i do dokladu; jeden výpočet, jeden zdroj pravdy. Celé je to v jedné
        // @Transactional metodě, takže když uzavření spadne (chybějící cena, manko nad rámec
        // šarží, souběh), odrolují se i zmrazené hodnoty.
        mapper.materializeDifferences(id);
        List<StockTakeDto.ItemResponse> items = mapper.findItems(id);
        List<StockTakeDto.ItemResponse> shortages = new ArrayList<>();
        List<StockTakeDto.ItemResponse> surpluses = new ArrayList<>();
        for (StockTakeDto.ItemResponse item : items) {
            if (item.getCountedQuantity() == null) continue;   // nepočítáno ≠ nula
            int sign = item.getDifference().compareTo(BigDecimal.ZERO);
            if (sign < 0) shortages.add(item);
            if (sign > 0) surpluses.add(item);
        }

        applyShortages(stockTake.getStockTakeNumber(), shortages, userId);
        Long surplusReceiptId = applySurpluses(stockTake.getStockTakeNumber(), surpluses, userId);

        int updated = mapper.close(id, note, surplusReceiptId, userId);
        if (updated == 0) {
            throw new ConflictException("STOCK_TAKE_ALREADY_PROCESSED",
                    "Inventuru " + id + " mezitím uzavřel někdo jiný.");
        }
        return getDetail(id);
    }

    /** Manko: záporné ADJUSTMENT po šaržích od nejstarší, dokud není pokryto. */
    private void applyShortages(String stockTakeNumber, List<StockTakeDto.ItemResponse> shortages, Long userId) {
        for (StockTakeDto.ItemResponse item : shortages) {
            BigDecimal missing = item.getDifference().abs();
            List<GoodsReceiptItem> batches = mapper.findBatchesForShortage(item.getProductId());

            for (GoodsReceiptItem batch : batches) {
                if (missing.compareTo(BigDecimal.ZERO) <= 0) break;
                BigDecimal take = missing.min(batch.getQuantityRemaining());
                importMapper.insertMovement(StockMovement.builder()
                        .productId(item.getProductId())
                        .batchId(batch.getId())
                        .movementType(MovementType.ADJUSTMENT)
                        .quantity(take.negate())
                        .note("Inventura " + stockTakeNumber + " — manko")
                        .createdBy(userId)
                        .build());
                missing = missing.subtract(take);
            }

            // Zbylo manko, na které nejsou šarže → záporný zůstatek by shodil CHECK.
            if (missing.compareTo(BigDecimal.ZERO) > 0) {
                throw new BusinessRuleException("STOCK_TAKE_SHORTAGE_EXCEEDS_BATCHES", null,
                        "U dílu " + item.getSku() + " chybí víc kusů, než kolik zbývá v šaržích "
                                + "— zkontrolujte napočítané množství.",
                        Map.of("sku", item.getSku(), "missing", missing));
            }
        }
    }

    /**
     * Přebytek: jedna pseudo-příjemka typu STOCK_TAKE pro celou inventuru, v ní
     * per díl šarže s cenou a kladné ADJUSTMENT (trigger dorovná stav i zůstatek).
     *
     * @return ID příjemky, nebo {@code null} když žádný přebytek není
     */
    private Long applySurpluses(String stockTakeNumber, List<StockTakeDto.ItemResponse> surpluses, Long userId) {
        if (surpluses.isEmpty()) {
            return null;
        }
        for (StockTakeDto.ItemResponse item : surpluses) {
            if (item.getSurplusUnitPrice() == null) {
                throw new BusinessRuleException("STOCK_TAKE_PRICE_MISSING", null,
                        "U dílu " + item.getSku() + " chybí nákupní cena pro přebytek — doplňte ji.",
                        Map.of("sku", item.getSku(), "productId", item.getProductId()));
            }
        }

        // Hodnota přebytku = Σ (množství × pořizovací/reprodukční cena), BEZ DPH.
        // Inventurní přebytek je nalezené zboží, ne nákup ani dodávka — účetně je to
        // výnos (ČÚS 007, účet 648), ne přijatá faktura. Skladová příjemka je jen
        // evidenční doklad bez daňové vazby (nemá fakturu), proto vat_amount = 0
        // a hlavička = základ = celkem = hodnota zásoby.
        BigDecimal surplusValue = surpluses.stream()
                .map(i -> i.getDifference().multiply(i.getSurplusUnitPrice()))
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);

        GoodsReceipt receipt = GoodsReceipt.builder()
                .supplierNameSnapshot("Inventura")
                .invoiceNumber(stockTakeNumber)
                .subtotal(surplusValue)
                .vatAmount(BigDecimal.ZERO)
                .totalAmount(surplusValue)
                .currency(properties.getDefaults().getCurrency())
                .documentType(DocumentType.STOCK_TAKE)
                .sourceChannel(ReceiptSource.MANUAL)
                .status(ReceiptStatus.CONFIRMED)
                .reconciliationOk(true)
                .createdBy(userId)
                .build();
        importMapper.insertReceipt(receipt);

        int position = 1;
        for (StockTakeDto.ItemResponse item : surpluses) {
            BigDecimal surplus = item.getDifference();
            BigDecimal price = item.getSurplusUnitPrice();

            // quantity_remaining = 0: doplní ho až kladné ADJUSTMENT přes trigger,
            // aby stav skladu a zůstatek šarže vznikly jedinou cestou — pohybem.
            // vat_rate = 0: přebytek nemá DPH (nalezené zboží, ne nákup — viz hlavička).
            GoodsReceiptItem batch = GoodsReceiptItem.builder()
                    .goodsReceiptId(receipt.getId())
                    .productId(item.getProductId())
                    .position(position++)
                    .nameSnapshot(item.getName())
                    .quantityReceived(surplus)
                    .quantityRemaining(BigDecimal.ZERO)
                    .unitPriceExclVat(price)
                    .vatRate(0)
                    .totalInclVat(surplus.multiply(price).setScale(2, RoundingMode.HALF_UP))
                    .build();
            importMapper.insertReceiptItem(batch);

            importMapper.insertMovement(StockMovement.builder()
                    .productId(item.getProductId())
                    .batchId(batch.getId())
                    .movementType(MovementType.ADJUSTMENT)
                    .quantity(surplus)
                    .note("Inventura " + stockTakeNumber + " — přebytek")
                    .createdBy(userId)
                    .build());
        }
        return receipt.getId();
    }

    // ------------------------------------------------------------------ zrušení

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException  pokud je {@code id} null
     * @throws ResourceNotFoundException pokud inventura s daným ID neexistuje
     * @throws BusinessRuleException     pokud inventura už není OPEN
     * @throws ConflictException         pokud ji mezitím zpracoval někdo jiný
     */
    @Override
    @Transactional
    public StockTakeDto.DetailResponse cancel(Long id, Long userId) {
        if (id == null) {
            throw new IllegalArgumentException("id nesmí být null");
        }
        requireOpen(mapper.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(RESOURCE, id)));

        if (mapper.cancel(id, userId) == 0) {
            throw new ConflictException("STOCK_TAKE_ALREADY_PROCESSED",
                    "Inventuru " + id + " mezitím zpracoval někdo jiný.");
        }
        return getDetail(id);
    }

    // ------------------------------------------------------------------ pomocné

    private void requireOpen(StockTake stockTake) {
        if (stockTake.getStatus() != StockTakeStatus.OPEN) {
            throw new BusinessRuleException("STOCK_TAKE_NOT_EDITABLE",
                    "Inventura už je " + (stockTake.getStatus() == StockTakeStatus.CLOSED
                            ? "uzavřená" : "zrušená") + " — nelze ji měnit.");
        }
    }
}
