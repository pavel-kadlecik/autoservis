package cz.palo.autoservis.service.impl;

import cz.palo.autoservis.exception.BusinessRuleException;
import cz.palo.autoservis.exception.ResourceNotFoundException;
import cz.palo.autoservis.mapper.EmployeeMapper;
import cz.palo.autoservis.mapper.GoodsReceiptMapper;
import cz.palo.autoservis.mapper.InvoiceMapper;
import cz.palo.autoservis.mapper.OrderItemMapper;
import cz.palo.autoservis.mapper.OrderMapper;
import cz.palo.autoservis.mapper.WarehouseImportMapper;
import cz.palo.autoservis.mapper.WarehouseMapper;
import cz.palo.autoservis.model.converter.OrderItemConverter;
import cz.palo.autoservis.model.converter.OrderItemSummaryConverter;
import cz.palo.autoservis.model.domain.employee.Employee;
import cz.palo.autoservis.model.domain.order.OrderItem;
import cz.palo.autoservis.model.domain.order.OrderItemSummary;
import cz.palo.autoservis.model.domain.warehouse.GoodsReceiptItem;
import cz.palo.autoservis.model.domain.warehouse.MovementType;
import cz.palo.autoservis.model.domain.warehouse.Product;
import cz.palo.autoservis.model.domain.warehouse.StockMovement;
import cz.palo.autoservis.model.dto.order.OrderItemDto;
import cz.palo.autoservis.model.dto.order.OrderItemSummaryDto;
import cz.palo.autoservis.model.dto.warehouse.GoodsReceiptItemDto;
import cz.palo.autoservis.model.enums.OrderItemType;
import cz.palo.autoservis.service.OrderItemService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Implementace {@link OrderItemService}.
 */
@Service
@RequiredArgsConstructor
public class OrderItemServiceImpl implements OrderItemService {

    /**
     * Jednotka, u které má hodinová sazba mechanika smysl (D-6). Práce se od 2026-08-03
     * účtuje i po kusech (paušál za úkon) — tam se sazba nedosazuje.
     */
    private static final String HOUR_UNIT = "hod";

    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final OrderItemConverter orderItemConverter;
    private final GoodsReceiptMapper goodsReceiptMapper;
    private final WarehouseMapper warehouseMapper;
    private final WarehouseImportMapper warehouseImportMapper;
    private final OrderItemSummaryConverter orderItemSummaryConverter;
    private final InvoiceMapper invoiceMapper;
    private final EmployeeMapper employeeMapper;

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException když je {@code orderId} null
     */
    @Override
    public List<OrderItemDto.Response> getByOrderId(Long orderId) {
        if (orderId == null) {
            throw new IllegalArgumentException("orderId nesmí být null");
        }
        requireOrderExists(orderId);
        return orderItemConverter.toListResponses(orderItemMapper.findByOrderId(orderId));
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException  když je {@code id} null
     * @throws ResourceNotFoundException když položka zakázky s daným ID neexistuje
     */
    @Override
    public OrderItemDto.Response getById(Long id) {
        if (id == null) {
            throw new IllegalArgumentException("id nesmí být null");
        }
        return orderItemMapper.findById(id)
                .map(orderItemConverter::toResponse)
                .orElseThrow(() -> new ResourceNotFoundException("Položka zakázky", id));
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException když je {@code orderId} null
     */
    @Override
    @Transactional(readOnly = true)
    public OrderItemSummaryDto.Response getSummaryByOrderId(Long orderId) {
        if (orderId == null) {
            throw new IllegalArgumentException("orderId nesmí být null");
        }
        requireOrderExists(orderId);

        OrderItemSummary summary = orderItemMapper.findSummaryByOrderId(orderId)
                .orElseGet(() -> OrderItemSummary.zero(orderId));
        return orderItemSummaryConverter.toDto(summary);
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException když je {@code orderId} null
     */
    @Override
    @Transactional
    public OrderItemDto.Response create(Long orderId, OrderItemDto.CreateRequest createRequest, Long userId) {
        if (orderId == null) {
            throw new IllegalArgumentException("orderId nesmí být null");
        }
        requireOrderNotInvoiced(orderId);

        OrderItem orderItem = orderItemConverter.toDomain(createRequest);
        orderItem.setCreatedBy(userId);
        orderItem.setOrderId(orderId);
        applyLaborEmployee(orderItem);
        orderItemMapper.insert(orderItem);
        return getById(orderItem.getId());
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException když je {@code orderId} null
     */
    @Override
    @Transactional
    public List<OrderItemDto.Response> importFromReceipt(Long orderId, List<GoodsReceiptItemDto.ImportRequest> importRequest, Long userId) {
        if (orderId == null) {
            throw new IllegalArgumentException("orderId nesmí být null");
        }

        requireOrderNotInvoiced(orderId);

        // distinct: request může tutéž šarži odkazovat vícekrát (viz agregace níže) —
        // deduplikuje se hned na začátku, aby kontrola chybějících id i zamykací dotaz
        // IN(...) pracovaly s unikátními id, a ne porovnávaly zdvojený seznam
        // s deduplikovanou mapou.
        List<Long> ids = importRequest.stream()
                .map(GoodsReceiptItemDto.ImportRequest::getGoodsReceiptItemId)
                .distinct()
                .toList();
        // FOR UPDATE: šarže se zamknou na zbytek transakce, takže souběžný odběr z téže
        // šarže počká, místo aby oba prošly proti témuž zastaralému quantity_remaining
        // (K6, analyza-2026-07).
        Map<Long, GoodsReceiptItem> batchesById = goodsReceiptMapper.findByIdsForUpdate(ids).stream()
                .collect(Collectors.toMap(GoodsReceiptItem::getId, item -> item));

        if(ids.size() != batchesById.size()) {
            List<Long> missing = ids.stream()
                    .filter(id -> !batchesById.containsKey(id))
                    .toList();
            throw new ResourceNotFoundException("Položka příjemky", missing);
        }

        // Nejdřív součet požadovaného množství za šarži — request může tutéž šarži odkazovat
        // vícekrát a každý řádek se musí validovat proti SOUČTU požadavků z té šarže,
        // ne každý zvlášť (K6, analyza-2026-07).
        Map<Long, BigDecimal> requestedPerBatch = importRequest.stream()
                .collect(Collectors.groupingBy(
                        GoodsReceiptItemDto.ImportRequest::getGoodsReceiptItemId,
                        Collectors.reducing(BigDecimal.ZERO,
                                GoodsReceiptItemDto.ImportRequest::getQuantity, BigDecimal::add)));

        // Validuje se proti DOSTUPNÉMU množství, ne proti zbytku šarže: díl už může být
        // slíbený jiné otevřené zakázce, jen z ní ještě fyzicky neodešel. Kdyby se hlídal
        // jen zbytek, naplánovali by dva lidé tentýž poslední kus a druhý by na to přišel
        // až u výdeje — tedy ve chvíli, kdy auto stojí hotové (rozhodnutí 2026-08-05:
        // „první rezervace vyhraje, druhý dostane hlášku").
        // Rezervace se čte AŽ TEĎ, samostatným dotazem po získání zámku. Kdyby se počítala
        // uvnitř zamykajícího SELECTu, souběh dvou importů o poslední kus propustí oba:
        // druhá transakce sice čeká na zámku, ale poddotaz uvnitř téhož příkazu se
        // vyhodnotí nad snímkem z jeho startu, tedy dřív, než první commitla. Samostatný
        // příkaz dostane v READ COMMITTED čerstvý snímek (StockReservationConcurrencyTest).
        Map<Long, BigDecimal> reservedByBatch = goodsReceiptMapper.findReservedByBatchIds(ids).stream()
                .collect(Collectors.toMap(GoodsReceiptItem::getId, GoodsReceiptItem::getQuantityReserved));

        for (var entry : requestedPerBatch.entrySet()) {
            GoodsReceiptItem batch = batchesById.get(entry.getKey());
            BigDecimal requested = entry.getValue();
            BigDecimal reserved  = reservedByBatch.getOrDefault(entry.getKey(), BigDecimal.ZERO);
            BigDecimal available = batch.getQuantityRemaining().subtract(reserved);

            if (requested.compareTo(available) > 0) {
                // Hláška rozlišuje, jestli díl chybí, nebo jen leží slíbený jinde —
                // obsluha v druhém případě může zakázky přerovnat místo objednávání.
                String detail = reserved.signum() > 0
                        ? "V šarži zbývá " + plain(batch.getQuantityRemaining())
                          + ", z toho je " + plain(reserved) + " rezervováno na jiné zakázky. "
                          + "Volně k dispozici je " + plain(available) + "."
                        : "V šarži zbývá jen " + plain(available) + ".";
                throw new BusinessRuleException(
                        "QUANTITY_EXCEEDS_REMAINING",
                        "quantity",
                        "Požadované množství není k dispozici. " + detail,
                        Map.of(
                                "goodsReceiptItemId", entry.getKey(),
                                "requested",          requested,
                                "remaining",          batch.getQuantityRemaining(),
                                "reserved",           reserved,
                                "available",          available
                        ));
            }
        }

        List<Long> productIds = batchesById.values().stream()
                .map(GoodsReceiptItem::getProductId)
                .distinct()
                .toList();

        Map<Long, Product> productsById = warehouseMapper.findByIds(productIds).stream()
                .collect(Collectors.toMap(Product::getId, item -> item));

        List<OrderItem> newItems = new ArrayList<>();
        int orderItemPosition = orderItemMapper.findMaxPositionByOrderId(orderId) + 1;

        for (var req : importRequest) {
            GoodsReceiptItem batch = batchesById.get(req.getGoodsReceiptItemId());
            Product product = productsById.get(batch.getProductId());

            OrderItem item = new OrderItem();
            item.setOrderId(orderId);
            item.setItemType(OrderItemType.MATERIAL);
            item.setGoodsReceiptItemId(batch.getId());
            item.setName(batch.getNameSnapshot());
            item.setQuantity(req.getQuantity());
            item.setPosition((short)orderItemPosition);
            item.setUnit(product.getUnit());
            item.setPurchasePrice(batch.getUnitPriceExclVat());
            item.setVatRate(batch.getVatRate().shortValue());
            item.setCreatedBy(userId);

            BigDecimal salePrice = product.getSalePrice() != null
                    ? product.getSalePrice()
                    : batch.getUnitPriceExclVat();
            item.setUnitPrice(salePrice);

            orderItemMapper.insert(item);
            newItems.add(item);
            orderItemPosition++;

            // Žádný skladový pohyb: import je REZERVACE, ne výdej (rozhodnutí 2026-08-05).
            // Díl fyzicky leží dál v regálu, jen je slíbený téhle zakázce — sama existence
            // položky s vazbou na šarži tu rezervaci vyjadřuje. Ledger tak obsahuje jen
            // skutečné fyzické události, ne změny plánu, a zrušení či smazání zakázky
            // rezervaci prostě uvolní bez jediného zápisu.
            // Výdej vzniká později: tlačítkem, nebo automaticky při dokončení zakázky.
        }

        return newItems.stream().map(item -> getById(item.getId())).toList();
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException  když je {@code id} null
     * @throws ResourceNotFoundException když položka zakázky s daným ID neexistuje
     */
    @Override
    @Transactional
    public OrderItemDto.Response update(Long id, OrderItemDto.UpdateRequest updateRequest, Long userId) {
        if (id == null) {
            throw new IllegalArgumentException("id nesmí být null");
        }

        OrderItem existingOrderItem = orderItemMapper.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Položka zakázky", id));

        requireOrderNotInvoiced(existingOrderItem.getOrderId());

        OrderItem orderItem = orderItemConverter.applyUpdate(existingOrderItem, updateRequest);
        applyLaborEmployee(orderItem);
        int affectedRows = orderItemMapper.update(orderItem);
        syncIssuedQuantity(orderItem, userId);
        return verifyAndFetchAfterUpdate(id, affectedRows);
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException  když je {@code id} null
     * @throws ResourceNotFoundException když položka zakázky s daným ID neexistuje
     */
    @Override
    @Transactional
    public void delete(Long id, Long userId) {
        if (id == null) {
            throw new IllegalArgumentException("id nesmí být null");
        }
        OrderItem item = orderItemMapper.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Položka zakázky", id));

        requireOrderNotInvoiced(item.getOrderId());

        // Vratka jen za to, co ze skladu skutečně odešlo. Dokud je položka pouze
        // REZERVOVANÁ, díl nikdy regál neopustil — smazání tedy jen uvolní rezervaci
        // a do ledgeru se nezapisuje nic. Kdyby se vratka zakládala vždy jako dřív,
        // přidala by na sklad zboží, které z něj nikdy nebylo vydáno.
        //
        // Vrací se VYDANÉ množství, ne množství položky: ta dvě čísla se mohou lišit,
        // když se množství po výdeji upravilo. Fyzicky lze vrátit jen to, co odešlo.
        if (item.getGoodsReceiptItemId() != null) {
            BigDecimal issued = warehouseImportMapper.findIssuedQuantityByOrderItemId(id);

            if (issued.signum() > 0) {
                GoodsReceiptItem batch = goodsReceiptMapper.findById(item.getGoodsReceiptItemId())
                        .orElseThrow(() -> new ResourceNotFoundException("Položka příjemky", item.getGoodsReceiptItemId()));

                StockMovement movement = new StockMovement();
                movement.setProductId(batch.getProductId());
                movement.setBatchId(batch.getId());
                movement.setMovementType(MovementType.ISSUE_RETURN);
                movement.setQuantity(issued);
                movement.setOrderId(item.getOrderId());
                // Položka za chvíli zmizí, ale vazba v ledgeru zůstane — sloupec nemá cizí
                // klíč právě proto, aby historie přežila smazání zdrojového záznamu (V83).
                movement.setOrderItemId(item.getId());
                movement.setNote("Storno položky zakázky #" + item.getId());
                movement.setCreatedBy(userId);
                warehouseImportMapper.insertMovement(movement);
            }
        }

        orderItemMapper.delete(id);
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException když je {@code orderId} null
     */
    @Override
    @Transactional
    public void reorder(Long orderId, List<OrderItemDto.ReorderRequest> items) {
        if (orderId == null) {
            throw new IllegalArgumentException("orderId nesmí být null");
        }
        requireOrderExists(orderId);
        if (items == null || items.isEmpty()){
            return;
        }
        orderItemMapper.reorder(orderId, items);

    }

    // =========================================================================
    // Privátní pomocné metody
    // =========================================================================

    /**
     * Odmítne práci nad zakázkou, která neexistuje.
     *
     * <p>Bez téhle kontroly se služba tvářila, že prázdná zakázka a neexistující zakázka jsou
     * totéž: {@code GET /items} vrátil {@code 200 []}, {@code /items/summary} nuly a
     * {@code POST /issue-stock} dokonce {@code 200 {"issuedItems": 0}} — tedy „hotovo, nebylo
     * co vydat". Překlep v URL nebo práce nad mezitím smazanou zakázkou tak prošly tiše.
     *
     * <p>U zápisu to bylo ještě horší: {@code POST /items} doběhl až k INSERTu a spadl na cizím
     * klíči, takže obsluze vyšlo 422 „Zadaná data porušují databázové omezení". Pravidlo projektu
     * je opačné — service má odmítnout dřív a česky, ne nechat probublat chybu integrity.
     */
    private void requireOrderExists(Long orderId) {
        if (orderMapper.findById(orderId).isEmpty()) {
            throw new ResourceNotFoundException("Zakázka", orderId);
        }
    }

    /**
     * Blokuje mutace položek zakázky, ke které už existuje aktivní faktura
     * (nestornovaná a nedobropisovaná) — faktura je snapshot položek z okamžiku
     * vytvoření, takže dodatečná změna položek by ji tiše rozjela od zakázky a mohla by
     * (u mazání) vrátit zboží na sklad, aniž by se to promítlo do už vystaveného
     * dokladu (V2, analyza-2026-07).
     *
     * <p>Závislost order→billing je vědomý kompromis: zámek dokladu logicky patří
     * k zakázce, ale nese ho stav faktury, ne stav zakázky. Stavový automat zakázky
     * ({@code OrderStatus}, KN-11) řeší jinou otázku — kudy smí projít zakázka sama.
     *
     * @param orderId ID zakázky, jejíž položka se má měnit
     * @throws BusinessRuleException kód {@code ORDER_LOCKED_BY_INVOICE}, pokud k zakázce
     *                                existuje aktivní faktura
     */
    private void requireOrderNotInvoiced(Long orderId) {
        requireOrderExists(orderId);

        // findByOrderId vrací od V69 jen aktivní fakturu — nestornovanou a nedobropisovanou.
        // Popis dokladu skládá Invoice.describe(): koncept číslo nemá (V49), takže zřetězení
        // s invoiceNumber tu obsluze hlásilo „fakturu null" (audit 02/F-7).
        invoiceMapper.findByOrderId(orderId)
                .ifPresent(inv -> {
                    throw new BusinessRuleException(
                            "ORDER_LOCKED_BY_INVOICE", "orderId",
                            "Zakázka už má " + inv.describe() + " — položky nelze měnit.",
                            Map.of("orderId", orderId, "invoiceId", inv.getId()));
                });
    }

    /**
     * Ověří přiřazení mechanika a zafixuje nákladovou cenu práce (D-2, D-3, D-6).
     *
     * <p>Je-li vyplněno {@code employeeId}, musí jít o položku typu {@code LABOR} (zrcadlí
     * DB CHECK {@code chk_order_items_employee_labor}, jen jako srozumitelná 422 místo syrové
     * chyby integrity). <em>Aktuální</em> {@code hourly_rate} zaměstnance se do
     * {@code purchasePrice} zapíše jen tehdy, když ho volající nechal prázdný (fallback D-6) —
     * explicitně poslaná cena (předvyplnění z frontendu či ruční úprava) se respektuje
     * a jednou uložená se už nikdy nepřepočítává, takže pozdější změna sazby nemůže
     * přepsat historickou položku (D-3).
     *
     * <p><strong>Jen u jednotky {@code hod}</strong> (rozhodnutí uživatele 2026-08-03): práce
     * se nově účtuje i po kusech — paušál za úkon. Hodinová sazba dosazená jako cena za kus
     * by byla tiše špatné číslo v nákladech, a špatný údaj je horší než prázdné pole, které
     * si obsluha všimne.
     *
     * @param item položka zakázky před INSERTem/UPDATEem (mutuje se na místě)
     * @throws BusinessRuleException     {@code EMPLOYEE_ONLY_ON_LABOR}, když je mechanik
     *                                   přiřazen k položce jiného typu než LABOR
     * @throws ResourceNotFoundException když odkazovaný zaměstnanec neexistuje
     */
    private void applyLaborEmployee(OrderItem item) {
        Long employeeId = item.getEmployeeId();
        if (employeeId == null) {
            return;
        }
        if (item.getItemType() != OrderItemType.LABOR) {
            throw new BusinessRuleException(
                    "EMPLOYEE_ONLY_ON_LABOR",
                    "employeeId",
                    "Mechanika lze přiřadit jen k položce typu práce (LABOR).",
                    Map.of("itemType", item.getItemType()));
        }
        Employee employee = employeeMapper.findByIdIncludingInactive(employeeId)
                .orElseThrow(() -> new ResourceNotFoundException("Zaměstnanec", employeeId));
        if (item.getPurchasePrice() == null && HOUR_UNIT.equalsIgnoreCase(item.getUnit())) {
            item.setPurchasePrice(employee.getHourlyRate());
        }
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException když je {@code orderId} null
     */
    @Override
    @Transactional
    public int issueStock(Long orderId, Long userId) {
        if (orderId == null) {
            throw new IllegalArgumentException("orderId nesmí být null");
        }
        requireOrderExists(orderId);

        List<OrderItem> reserved = orderItemMapper.findReservedByOrderId(orderId);
        if (reserved.isEmpty()) {
            return 0;
        }

        // FOR UPDATE: šarže se zamknou na zbytek transakce, takže souběžný výdej nebo
        // import z téže šarže počká místo toho, aby oba prošly proti témuž zůstatku
        // (týž vzor jako u importu, K6).
        List<Long> batchIds = reserved.stream()
                .map(OrderItem::getGoodsReceiptItemId)
                .distinct()
                .toList();
        Map<Long, GoodsReceiptItem> batchesById = goodsReceiptMapper.findByIdsForUpdate(batchIds).stream()
                .collect(Collectors.toMap(GoodsReceiptItem::getId, item -> item));

        if (batchIds.size() != batchesById.size()) {
            List<Long> missing = batchIds.stream().filter(id -> !batchesById.containsKey(id)).toList();
            throw new ResourceNotFoundException("Položka příjemky", missing);
        }

        // Součet za šarži: jedna zakázka může mít z téže šarže víc řádků a každý zvlášť
        // by prošel proti témuž zůstatku.
        Map<Long, BigDecimal> requestedPerBatch = reserved.stream()
                .collect(Collectors.groupingBy(
                        OrderItem::getGoodsReceiptItemId,
                        Collectors.reducing(BigDecimal.ZERO, OrderItem::getQuantity, BigDecimal::add)));

        // Validuje se proti FYZICKÉMU zbytku šarže, ne proti dostupnému: vydávané položky
        // jsou samy součástí rezervace, takže dostupné množství by je odečetlo podruhé
        // a výdej by neprošel nikdy.
        //
        // Nastat to může, i když rezervace při plánování prošla — mezitím mohla šarži
        // sníst inventurní korekce, vratka dodavateli nebo odpis. Odmítáme s výčtem dílů
        // (rozhodnutí uživatele 2026-08-05), ať obsluha ví, co dohledat.
        List<String> missingParts = new ArrayList<>();
        for (var entry : requestedPerBatch.entrySet()) {
            GoodsReceiptItem batch = batchesById.get(entry.getKey());
            if (entry.getValue().compareTo(batch.getQuantityRemaining()) > 0) {
                missingParts.add(batch.getNameSnapshot()
                        + " (potřeba " + plain(entry.getValue())
                        + ", na skladě " + plain(batch.getQuantityRemaining()) + ")");
            }
        }
        if (!missingParts.isEmpty()) {
            throw new BusinessRuleException(
                    "STOCK_MISSING_FOR_ISSUE", "orderId",
                    "Materiál nelze vydat — na skladě chybí: " + String.join(", ", missingParts)
                            + ". Doplňte zásobu, nebo na zakázce upravte množství.",
                    Map.of("orderId", orderId, "missing", missingParts));
        }

        for (OrderItem item : reserved) {
            GoodsReceiptItem batch = batchesById.get(item.getGoodsReceiptItemId());

            StockMovement movement = new StockMovement();
            movement.setProductId(batch.getProductId());
            movement.setBatchId(batch.getId());
            movement.setMovementType(MovementType.ISSUE);
            movement.setQuantity(item.getQuantity().negate());
            movement.setOrderId(orderId);
            // Vazba na položku dělá z pohybu „tenhle díl téhle položky" — bez ní by se
            // nedalo rozlišit vydané od rezervovaných (V83).
            movement.setOrderItemId(item.getId());
            movement.setCreatedBy(userId);
            warehouseImportMapper.insertMovement(movement);
        }

        return reserved.size();
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException když je {@code orderId} null
     */
    @Override
    @Transactional
    public int returnIssuedStock(Long orderId, Long userId) {
        if (orderId == null) {
            throw new IllegalArgumentException("orderId nesmí být null");
        }
        requireOrderExists(orderId);
        List<OrderItem> issued = orderItemMapper.findIssuedByOrderId(orderId);
        if (issued.isEmpty()) {
            return 0;
        }

        // Počítají se jen skutečně vrácené položky — položku mezitím vynulovanou
        // souběžnou vratkou (per-item dotaz vidí čerstvý snímek) continue přeskočí
        // a do výsledku nepatří.
        int returned = 0;
        for (OrderItem item : issued) {
            BigDecimal quantity = warehouseImportMapper.findIssuedQuantityByOrderItemId(item.getId());
            if (quantity.signum() <= 0) {
                continue;
            }
            GoodsReceiptItem batch = goodsReceiptMapper.findById(item.getGoodsReceiptItemId())
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Položka příjemky", item.getGoodsReceiptItemId()));

            StockMovement movement = new StockMovement();
            movement.setProductId(batch.getProductId());
            movement.setBatchId(batch.getId());
            movement.setMovementType(MovementType.ISSUE_RETURN);
            movement.setQuantity(quantity);
            movement.setOrderId(orderId);
            movement.setOrderItemId(item.getId());
            movement.setNote("Znovuotevření zakázky — výdej vrácen do rezervace");
            movement.setCreatedBy(userId);
            warehouseImportMapper.insertMovement(movement);
            returned++;
        }
        return returned;
    }

    /**
     * Dorovná sklad, když se změní množství u položky, která už byla vydaná (V83,
     * rozhodnutí uživatele 2026-08-05: „povolit, rozdíl vrátit protipohybem").
     *
     * <p>Porovnává se s <strong>ledgerem</strong>, ne s předchozí hodnotou položky: deník
     * je jediný zdroj pravdy o tom, co fyzicky odešlo, a rovnou tak vyjde i případ, kdy
     * se množství mění podruhé nebo když se část už vrátila.
     *
     * <ul>
     *   <li><strong>Jen rezervace</strong> (nic nevydáno) — sklad se nemění vůbec. Díl regál
     *       neopustil, mění se pouze slib, a ten se nikam nezapisuje.</li>
     *   <li><strong>Snížení</strong> — rozdíl se vrátí vratkou. Fyzicky lze vrátit jen to,
     *       co odešlo, proto se počítá z vydaného množství.</li>
     *   <li><strong>Zvýšení</strong> — rozdíl se dovydá. Bez toho by položka tvrdila, že drží
     *       víc dílů, než kolik jich ze skladu skutečně odešlo.</li>
     * </ul>
     *
     * <p>Drží tím invariant, na kterém stojí rozlišení rezervace od výdeje: buď je vydáno
     * nic, nebo přesně tolik, kolik položka říká. Částečně vydaná položka by se
     * {@code findReservedByOrderId} jevila jako celá vydaná a zbytek by se nevydal nikdy.
     */
    private void syncIssuedQuantity(OrderItem item, Long userId) {
        if (item.getGoodsReceiptItemId() == null) {
            return;
        }
        BigDecimal issued = warehouseImportMapper.findIssuedQuantityByOrderItemId(item.getId());
        if (issued.signum() == 0) {
            return;
        }
        BigDecimal diff = item.getQuantity().subtract(issued);
        if (diff.signum() == 0) {
            return;
        }

        // FOR UPDATE: u zvýšení se proti zůstatku šarže rozhoduje, takže musí být zamčená
        // proti souběžnému výdeji (týž vzor jako import a issueStock, K6).
        GoodsReceiptItem batch = goodsReceiptMapper.findByIdsForUpdate(List.of(item.getGoodsReceiptItemId()))
                .stream().findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Položka příjemky", item.getGoodsReceiptItemId()));

        StockMovement movement = new StockMovement();
        movement.setProductId(batch.getProductId());
        movement.setBatchId(batch.getId());
        movement.setOrderId(item.getOrderId());
        movement.setOrderItemId(item.getId());
        movement.setCreatedBy(userId);

        if (diff.signum() < 0) {
            movement.setMovementType(MovementType.ISSUE_RETURN);
            movement.setQuantity(diff.negate());
            movement.setNote("Snížení množství položky zakázky #" + item.getId());
        } else {
            if (diff.compareTo(batch.getQuantityRemaining()) > 0) {
                throw new BusinessRuleException(
                        "STOCK_MISSING_FOR_ISSUE", "quantity",
                        "Na zvýšení množství není na skladě dost dílů — " + batch.getNameSnapshot()
                                + " (potřeba navíc " + plain(diff)
                                + ", na skladě " + plain(batch.getQuantityRemaining()) + ").",
                        Map.of("orderItemId", item.getId(),
                                "required", diff,
                                "remaining", batch.getQuantityRemaining()));
            }
            movement.setMovementType(MovementType.ISSUE);
            movement.setQuantity(diff.negate());
            movement.setNote("Zvýšení množství položky zakázky #" + item.getId());
        }
        warehouseImportMapper.insertMovement(movement);
    }

    /** Množství do hlášky bez koncových nul — „2" místo „2.000". */
    private static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private OrderItemDto.Response verifyAndFetchAfterUpdate(Long id, int affectedRows) {
        if (affectedRows == 0) {
            throw new IllegalStateException(
                    "Položka zakázky " + id + " zmizela během aktualizace (byla načtena těsně předtím)");
        }
        return fetchOrFail(id);
    }

    private OrderItemDto.Response fetchOrFail(Long id) {
        return orderItemMapper.findById(id)
                .map(orderItemConverter::toResponse)
                .orElseThrow(() -> new IllegalStateException(
                        "Položka zakázky " + id + " zmizela mezi UPDATE a SELECT"));
    }
}
