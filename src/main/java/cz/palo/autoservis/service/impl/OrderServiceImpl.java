package cz.palo.autoservis.service.impl;

import cz.palo.autoservis.exception.BusinessRuleException;
import cz.palo.autoservis.exception.ResourceNotFoundException;
import cz.palo.autoservis.mapper.AppointmentMapper;
import cz.palo.autoservis.mapper.InvoiceMapper;
import cz.palo.autoservis.mapper.OrderItemMapper;
import cz.palo.autoservis.mapper.OrderMapper;
import cz.palo.autoservis.mapper.VehicleMapper;
import cz.palo.autoservis.mapper.WarehouseImportMapper;
import cz.palo.autoservis.model.converter.OrderConverter;
import cz.palo.autoservis.model.domain.order.Order;
import cz.palo.autoservis.model.domain.order.OrderItem;
import cz.palo.autoservis.model.domain.vehicle.Vehicle;
import cz.palo.autoservis.model.dto.order.OrderDto;
import cz.palo.autoservis.model.dto.order.OrderSearchParams;
import cz.palo.autoservis.model.dto.pagination.PagedResponse;
import cz.palo.autoservis.model.dto.vehicle.MileageDto;
import cz.palo.autoservis.model.enums.InvoiceStatus;
import cz.palo.autoservis.model.enums.MileageSource;
import cz.palo.autoservis.model.enums.OrderStatus;
import cz.palo.autoservis.service.MileageService;
import cz.palo.autoservis.service.OrderItemService;
import cz.palo.autoservis.service.OrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Implementace {@link OrderService}.
 */
@Service
@RequiredArgsConstructor
public class OrderServiceImpl implements OrderService {

    private final OrderMapper orderMapper;
    private final OrderConverter orderConverter;
    private final VehicleMapper vehicleMapper;
    private final OrderItemMapper orderItemMapper;
    private final InvoiceMapper invoiceMapper;
    private final MileageService mileageService;
    /** Dokončení zakázky vydá rezervovaný materiál — viz {@code issueReservedMaterialOnCompletion}. */
    private final OrderItemService orderItemService;
    private final WarehouseImportMapper warehouseImportMapper;
    private final AppointmentMapper appointmentMapper;

    /** {@inheritDoc} */
    @Override
    public PagedResponse<OrderDto.ListResponse> getPage(OrderSearchParams params) {
        List<Order> orders = orderMapper.search(params);
        List<OrderDto.ListResponse> listResponses = orderConverter.toListResponses(orders);
        long total = orderMapper.countSearch(params);
        return PagedResponse.of(listResponses, params.getPage(), params.getPageSize(), total);
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException  když je {@code id} null
     * @throws ResourceNotFoundException když zakázka s daným ID neexistuje
     */
    @Override
    public OrderDto.DetailResponse getById(Long id) {
        if (id == null) {
            throw new IllegalArgumentException("id nesmí být null");
        }
        return orderMapper.findById(id)
                .map(orderConverter::toDetailResponse)
                .orElseThrow(() -> new ResourceNotFoundException("Zakázka", id));
    }

    /***
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException když je {@code customerId} null
     */
    @Override
    public int countOpenByCustomerId(Long customerId) {
        if (customerId == null) {
            throw new IllegalArgumentException("customerId nesmí být null");
        }
        return orderMapper.countOpenByCustomerId(customerId);
    }

    /***
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException když je {@code vehicleId} null
     */
    @Override
    public int countOpenByVehicleId(Long vehicleId) {
        if (vehicleId == null) {
            throw new IllegalArgumentException("vehicleId nesmí být null");
        }
        return orderMapper.countOpenByVehicleId(vehicleId);
    }

    /**
     * {@inheritDoc}
     *
     * <p>{@code @Transactional}: kromě zakázky může vzniknout i odečet tachometru
     * ({@link #recordIntakeMileage}) — dva zápisy musí platit společně, jinak by zakázka
     * existovala s km, které se do historie vozidla nedostaly.
     */
    @Override
    @Transactional
    public OrderDto.DetailResponse create(OrderDto.CreateRequest createRequest, Long userId) {
        // Vozidlo musí existovat, být aktivní (findById je strict) a patřit zákazníkovi zakázky
        // (audit K-12/V-3) — jinak by šlo založit zakázku i fakturu na cizí vozidlo.
        Vehicle vehicle = vehicleMapper.findById(createRequest.getVehicleId())
                .orElseThrow(() -> new ResourceNotFoundException("Vozidlo", createRequest.getVehicleId()));
        if (!vehicle.getCustomerId().equals(createRequest.getCustomerId())) {
            throw new BusinessRuleException(
                    "VEHICLE_NOT_OWNED_BY_CUSTOMER", "vehicleId",
                    "Vozidlo nepatří vybranému zákazníkovi.",
                    Map.of("vehicleId", vehicle.getId(), "customerId", createRequest.getCustomerId()));
        }

        Order order = orderConverter.toDomain(createRequest);
        order.setCreatedBy(userId);
        orderMapper.insert(order);

        OrderDto.DetailResponse created = getById(order.getId());
        recordIntakeMileage(order, created.getOrderNumber(), userId);
        return created;
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException  když je {@code id} null
     * @throws ResourceNotFoundException když zakázka s daným ID neexistuje
     * @throws BusinessRuleException     {@code INVALID_STATUS_TRANSITION} u zakázaného přechodu,
     *                                   {@code ORDER_HAS_ACTIVE_INVOICE} nebo
     *                                   {@code ORDER_HAS_ISSUED_MATERIAL} u zrušení zakázky,
     *                                   která to ještě neunese, {@code ORDER_HAS_NO_ITEMS}
     *                                   u dokončení zakázky bez položek,
     *                                   {@code ORDER_REOPEN_BLOCKED_BY_INVOICE} u znovuotevření
     *                                   vyfakturované zakázky a {@code STOCK_MISSING_FOR_ISSUE},
     *                                   když při dokončení chybí rezervovaný materiál na skladě
     */
    @Override
    @Transactional
    public OrderDto.DetailResponse update(Long id, OrderDto.UpdateRequest updateRequest, Long userId) {
        if (id == null) {
            throw new IllegalArgumentException("id nesmí být null");
        }
        Order existingOrder = orderMapper.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Zakázka", id));

        requireAllowedStatusChange(existingOrder, updateRequest.getStatus());
        returnIssuedMaterialOnReopen(existingOrder, updateRequest.getStatus(), userId);
        issueReservedMaterialOnCompletion(existingOrder, updateRequest.getStatus(), userId);

        OrderStatus previousStatus = existingOrder.getStatus();
        Order updatedOrder = orderConverter.applyUpdate(existingOrder, updateRequest);
        updatedOrder.setCompletedAt(resolveCompletedAt(
                previousStatus, updateRequest.getStatus(), updatedOrder.getCompletedAt()));

        int affectedRows = orderMapper.update(updatedOrder);
        return verifyAndFetchAfterUpdate(id, affectedRows);
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException  když je {@code id} null
     * @throws ResourceNotFoundException když zakázka s daným ID neexistuje
     * @throws BusinessRuleException     stejné kódy jako u {@link #update} — prochází toutéž brankou
     */
    @Override
    @Transactional
    public OrderDto.DetailResponse changeStatus(Long id, OrderDto.StatusRequest statusRequest, Long userId) {
        if (id == null) {
            throw new IllegalArgumentException("id nesmí být null");
        }
        Order order = orderMapper.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Zakázka", id));
        OrderStatus target = statusRequest.getStatus();

        // Tatáž branka jako u PUT — vyhrazená cesta nesmí obcházet automat ani jeho podmínky.
        requireAllowedStatusChange(order, target);
        returnIssuedMaterialOnReopen(order, target, userId);
        issueReservedMaterialOnCompletion(order, target, userId);

        int affectedRows = orderMapper.updateStatus(
                id, target, resolveCompletedAt(order.getStatus(), target, order.getCompletedAt()));
        return verifyAndFetchAfterUpdate(id, affectedRows);
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException  když je {@code id} null
     * @throws ResourceNotFoundException když zakázka s daným ID neexistuje
     * @throws BusinessRuleException     {@code ORDER_HAS_ACTIVE_INVOICE} nebo zakázaný přechod
     */
    @Override
    @Transactional
    public OrderDto.DetailResponse cancel(Long id, Long userId) {
        if (id == null) {
            throw new IllegalArgumentException("id nesmí být null");
        }
        Order order = orderMapper.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Zakázka", id));

        // Faktura se kontroluje první — nemá smysl vracet materiál, když stejně neprojde.
        requireNoActiveInvoice(order);

        // Vrátí se VŠECHEN vydaný materiál (rozhodnutí uživatele 2026-08-06). Ze zrušené
        // zakázky nemá co zbýt: díly, které zůstaly na voze, zákazník zaplatí — patří tedy
        // na NOVOU zakázku, kterou obsluha založí, ne na tuhle. Tím se zrušená zakázka
        // vyčistí celá a fakturuje se jen to, co se opravdu stalo.
        //
        // Dosud to šlo jen ručně: smazat položky po jedné a teprve pak zrušit — u osmi dílů
        // devět potvrzovacích dialogů. Tady je to jeden krok a jedna transakce.
        orderItemService.returnIssuedStock(id, userId);

        // Až teď branka: po vrácení materiálu už requireMaterialReturned nemá co blokovat.
        requireAllowedStatusChange(order, OrderStatus.CANCELLED);

        int affectedRows = orderMapper.updateStatus(id, OrderStatus.CANCELLED, order.getCompletedAt());
        return verifyAndFetchAfterUpdate(id, affectedRows);
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException  když je {@code id} null
     * @throws ResourceNotFoundException když zakázka s daným ID neexistuje
     * @throws BusinessRuleException     {@code ORDER_HAS_INVOICE_CANNOT_DELETE} nebo
     *                                   {@code ORDER_HAS_STOCK_MOVEMENTS_CANNOT_DELETE}
     */
    @Override
    @Transactional
    public void delete(Long id, Long userId) {
        if (id == null) {
            throw new IllegalArgumentException("id nesmí být null");
        }
        Order order = orderMapper.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Zakázka", id));

        requireNoTraceLeft(order);

        // Materiál se vrací i při mazání, ne jen při rušení: smazaná zakázka nesmí za sebou
        // nechat díly „vydané" na záznam, který už neexistuje — sklad by se rozešel s regálem.
        orderItemService.returnIssuedStock(id, userId);

        // Zakázka mohla vzniknout převodem objednávky z kalendáře. Smazaná zakázka byla omyl,
        // ale termín domluvený se zákazníkem omyl nebyl — objednávka se vrátí na „Naplánováno“
        // a jde ji převést znovu. Bez odpojení by smazání spadlo na cizí klíč.
        appointmentMapper.unlinkOrder(id);

        int affectedRows = orderMapper.delete(id);
        if (affectedRows == 0) {
            throw new IllegalStateException(
                    "Zakázka " + id + " zmizela během mazání (byla načtena těsně předtím)");
        }
    }

    // =========================================================================
    // Privátní pomocné metody
    // =========================================================================

    /**
     * Smazat jde jen zakázka, po které nic nezůstalo (rozhodnutí uživatele 2026-08-06).
     *
     * <p>Mazání je vyhrazené pro záznam, který <strong>nikdy neměl vzniknout</strong> —
     * překlep, špatné auto. Zakázka, u které k práci nedošlo, se <em>ruší</em> stavem
     * {@code CANCELLED}: to je obchodní fakt, který má zůstat v evidenci.
     *
     * <p>Rozhoduje se podle stop, ne podle stavu. Faktura i skladový pohyb mazání blokují
     * i na úrovni cizích klíčů ({@code ON DELETE RESTRICT}); tady se odmítají dřív, aby
     * obsluha dostala srozumitelnou hlášku místo chyby integrity.
     *
     * <p>Pouhá <strong>rezervace</strong> materiálu mazání nebrání — žádný pohyb nemá, díl
     * leží dál v regálu a smazáním zakázky se slib prostě uvolní (V83).
     */
    private void requireNoTraceLeft(Order order) {
        // Záměrně VŠECHNY faktury včetně stornovaných a dobropisovaných: rozhoduje, jestli
        // po zakázce kdy zůstala stopa v účetnictví, ne jestli je doklad zrovna aktivní.
        int invoices = invoiceMapper.countByOrderId(order.getId());
        if (invoices > 0) {
            throw new BusinessRuleException(
                    "ORDER_HAS_INVOICE_CANNOT_DELETE", "id",
                    "Zakázku nelze smazat — byla fakturovaná, takže po ní zůstal doklad. "
                            + "Pokud k opravě nedošlo, zakázku zrušte místo mazání.",
                    Map.of("orderId", order.getId(), "invoices", invoices));
        }

        // Skladový pohyb mazání NEBRÁNÍ (rozhodnutí uživatele 2026-08-07). Dřív blokoval
        // jakýkoli — i vratku — takže omylem založená zakázka, na kterou stihl někdo vydat
        // díl, zůstala v evidenci navždy, i když se materiál dávno vrátil a sklad byl
        // v pořádku. Materiál teď vrací samo `delete`; po smazané zakázce zůstává jen
        // vyrovnaný pár pohybů s nulovým dopadem na zásobu (V87 kvůli tomu zahodila
        // `fk_mov_order` — append-only ledger neumožňuje pohyby smazat ani přepsat).
    }

    /**
     * Zapíše stav tachometru z příjmu do historie vozidla (audit 07/P-14, doprovod KN-28).
     *
     * <p>Zakázka si km drží jako snímek pro zakázkový list; odometr vozu vede
     * {@code vehicle.mileage_history} a bez tohoto zápisu by číslo z příjmu nikam nedošlo —
     * přesně ta „nevratná datová ztráta", kterou audit vytkl. Zdroj je {@code SERVICE}, poznámka
     * nese číslo zakázky, aby bylo v historii vozu vidět, odkud odečet přišel.
     *
     * <p>Jen při <strong>zakládání</strong> zakázky: dodatečné dopsání km přes editaci odečet
     * nezakládá, jinak by každá další editace sypala do historie duplicity. Chybějící hodnota
     * je legitimní (odtažený vůz, nefunkční tachometr), proto se nic nevynucuje.
     */
    private void recordIntakeMileage(Order order, String orderNumber, Long userId) {
        if (order.getMileageKmAtIntake() == null) {
            return;
        }
        MileageDto.CreateRequest reading = new MileageDto.CreateRequest();
        reading.setMileageKm(order.getMileageKmAtIntake());
        reading.setRecordedDate(LocalDate.now());
        reading.setSource(MileageSource.SERVICE);
        reading.setNote("Příjem vozu — zakázka " + orderNumber);

        // Sváže odečet se zakázkou (V84). Do té doby je spojoval jen text poznámky, takže
        // po smazání omylem založené zakázky by odečet zůstal viset v historii vozu —
        // u překlepu na špatném autě jako nesmyslný údaj.
        mileageService.addIntakeReading(order.getVehicleId(), reading, order.getId(), userId);
    }

    /**
     * Dokončit lze jen zakázku, která má aspoň jednu položku (rozhodnutí uživatele 2026-08-05).
     *
     * <p>Dokončení nově znamená „práce hotová a vyúčtovatelná" — je to okamžik, kdy se vydá
     * materiál a od kterého jde vystavit faktura. Prázdná zakázka nic z toho neunese: fakturu
     * z ní vystavit nejde ({@code ORDER_HAS_NO_ITEMS} v {@code InvoiceServiceImpl}) a jako
     * hotová práce by v přehledech lhala.
     *
     * <p>Kontrola je tu předsazená před fakturaci, aby se obsluha dozvěděla dřív a na místě,
     * kde se to dá spravit — ne až u pokladny.
     *
     * <p>Okrajové případy drží: diagnostika bez opravy má položku typu práce, záruční oprava
     * má položky s nulovou cenou. Zakázka, na které se nakonec nic nedělalo, se <em>ruší</em>,
     * ne dokončuje.
     */
    private void requireHasItems(Order order) {
        if (orderItemMapper.findByOrderId(order.getId()).isEmpty()) {
            throw new BusinessRuleException(
                    "ORDER_HAS_NO_ITEMS", "status",
                    "Zakázku bez položek nelze dokončit — doplňte provedenou práci nebo materiál. "
                            + "Pokud se na voze nakonec nic nedělalo, zakázku zrušte.",
                    Map.of("orderId", order.getId()));
        }
    }

    /**
     * Znovuotevřít lze jen zakázku bez aktivní faktury (rozhodnutí uživatele 2026-08-05).
     *
     * <p>Vrátit dokončenou zakázku do provozu je běžná potřeba — auto se vrátí, oprava
     * pokračuje, nebo se na „Dokončena" jen omylem kliklo. Jakmile ale k zakázce existuje
     * platný daňový doklad, musí se nejdřív vyřešit ten: koncept stornovat, vystavenou
     * fakturu opravit dobropisem. Jinak by vedle sebe stála rozpracovaná práce a doklad,
     * který ji vyúčtoval jako hotovou.
     *
     * <p>Predikát „aktivní faktura" se nepočítá tady — {@code findByOrderId} vrací od V69
     * nestornovanou a nedobropisovanou fakturu, tedy přesně to, co pouští částečný unikát
     * {@code uq_invoices_order_active}. Vlastní dotaz by ty predikáty rozešel.
     */
    private void requireReopenAllowed(Order order) {
        invoiceMapper.findByOrderId(order.getId()).ifPresent(invoice -> {
            throw new BusinessRuleException(
                    "ORDER_REOPEN_BLOCKED_BY_INVOICE", "status",
                    "Zakázku nelze znovu otevřít — má " + invoice.describe() + ". "
                            + (invoice.getStatus() == InvoiceStatus.DRAFT
                            ? "Je to zatím koncept — nejdřív ho stornujte."
                            : "Vystavenou fakturu nelze stornovat — vystavte k ní opravný daňový "
                              + "doklad (dobropis), ten zakázku uvolní."),
                    Map.of("orderId", order.getId(),
                            "invoiceId", invoice.getId(),
                            "invoiceStatus", invoice.getStatus()));
        });
    }

    /**
     * Při znovuotevření vrátí vydaný materiál zpět do rezervace (V83).
     *
     * <p>Díl fyzicky <strong>zůstává na autě</strong> — nikam se nevrací. Vrací se jen
     * <em>výdej</em>, aby se při dalším dokončení neodepsal ze skladu podruhé. Netto dopad
     * na sklad je nula: výdej i vratka se vyruší. Stejně to řeší R.O. Writer.
     *
     * <p>Bez toho by opakované dokončení znovuotevřené zakázky sneslo sklad o totéž
     * množství ještě jednou.
     */
    private void returnIssuedMaterialOnReopen(Order order, OrderStatus target, Long userId) {
        if (!order.getStatus().isReopenable() || target == OrderStatus.CANCELLED
                || order.getStatus() == target) {
            return;
        }
        orderItemService.returnIssuedStock(order.getId(), userId);
    }

    /**
     * Při dokončení zakázky vydá ze skladu materiál, který na ní ještě leží jako rezervace
     * (V83, rozhodnutí uživatele 2026-08-05: „tlačítkem, nebo při dokončení — co dřív").
     *
     * <p>Přidání dílu na zakázku je jen rezervace: díl zůstává v regálu a snižuje pouze
     * dostupné množství. Dokončení je okamžik, kdy je jisté, že se oprava opravdu stala —
     * proto tady vzniká skladový pohyb. Obsluha, která materiál vydala dřív tlačítkem,
     * tím není dotčena: už vydané položky {@code issueStock} přeskočí a vrátí 0.
     *
     * <p>Volá se jen na <strong>skutečném přechodu</strong> do {@code COMPLETED}. Identita
     * (uložení dokončené zakázky beze změny stavu) výdej nespouští, jinak by každá oprava
     * překlepu v popisu zkoušela vydávat znovu.
     *
     * <p>Když rezervovaný díl mezitím ze skladu zmizel (inventura, odpis, vratka), vyletí
     * {@code STOCK_MISSING_FOR_ISSUE} a <strong>celá transakce se vrátí zpět</strong> —
     * zakázka tedy zůstane nedokončená a obsluha se z hlášky dozví, co chybí. To je záměr:
     * dokončená zakázka s materiálem, který na skladě není, by tiše rozešla papír a regál.
     */
    private void issueReservedMaterialOnCompletion(Order order, OrderStatus target, Long userId) {
        if (target != OrderStatus.COMPLETED || order.getStatus() == OrderStatus.COMPLETED) {
            return;
        }
        orderItemService.issueStock(order.getId(), userId);
    }

    /**
     * Stavový automat zakázky (audit KN-11) — jediná branka, kterou musí projít každá změna stavu.
     *
     * <p>Automat sám ({@link OrderStatus#canTransitionTo}) rozhoduje o tvaru workflow; podmínky
     * závislé na stavu databáze jsou tady, protože enum k databázi nemá přístup (R-13: business
     * validace patří do service). Nezměněný stav není přechod a projde — {@code PUT} nese celý
     * záznam, takže oprava popisu hotové zakázky nesmí selhat.
     *
     * @param order  zakázka načtená z DB (drží dosavadní stav)
     * @param target požadovaný stav z {@code UpdateRequest} (nikdy null — {@code @NotNull} v DTO)
     * @throws BusinessRuleException zakázaný přechod nebo nesplněná podmínka zrušení
     */
    private void requireAllowedStatusChange(Order order, OrderStatus target) {
        OrderStatus current = order.getStatus();
        if (current == target) {
            return;
        }
        if (!current.canTransitionTo(target)) {
            // Po zavedení znovuotevření sem spadne jediný stav — CANCELLED je nově jediný
            // terminální. Hláška proto může být konkrétní a česká místo obecné s enumem;
            // dřív vypisovala „Zakázka je uzavřená (COMPLETED)".
            throw new BusinessRuleException(
                    "INVALID_STATUS_TRANSITION", "status",
                    "Zrušenou zakázku už nelze přepnout do jiného stavu. Má-li se na voze "
                            + "znovu pracovat, založte novou zakázku.",
                    Map.of("orderId", order.getId(), "from", current, "to", target));
        }
        if (target == OrderStatus.CANCELLED) {
            requireNoActiveInvoice(order);
            requireMaterialReturned(order);
        } else if (target == OrderStatus.COMPLETED) {
            requireHasItems(order);
        } else if (current.isReopenable()) {
            requireReopenAllowed(order);
        }
    }

    /**
     * Zakázku s aktivní fakturou nelze zrušit — jinak by vedle sebe stála „zrušená" práce a platný
     * daňový doklad na ni (audit KN-11, scénář `PUT status=CANCELLED` na vyfakturovanou zakázku).
     *
     * <p>Predikát „aktivní faktura" se <strong>nepočítá tady</strong>: {@code findByOrderId} vrací
     * od V69 nestornovanou <em>a</em> nedobropisovanou fakturu, tedy přesně to, co pouští částečný
     * unikát {@code uq_invoices_order_active}. Vlastní dotaz by ty dva predikáty rozešel.
     *
     * <p>Cesta z blokace je proto stejná jako u přefakturování: koncept stornovat, vystavený doklad
     * opravit dobropisem. Obojí zakázku uvolní i pro zrušení.
     */
    private void requireNoActiveInvoice(Order order) {
        invoiceMapper.findByOrderId(order.getId()).ifPresent(invoice -> {
            throw new BusinessRuleException(
                    "ORDER_HAS_ACTIVE_INVOICE", "status",
                    "Zakázku nelze zrušit — má " + invoice.describe() + ". "
                            + (invoice.getStatus() == InvoiceStatus.DRAFT
                            // Od V71 má číslo i koncept, describe() říká „fakturu X" — dovětek
                            // proto jmenuje koncept, ať hláška sedí jazykově i věcně.
                            ? "Je to zatím koncept — nejdřív ho stornujte."
                            : "Vystavenou fakturu nelze stornovat — vystavte k ní opravný daňový "
                              + "doklad (dobropis), ten zakázku uvolní."),
                    Map.of("orderId", order.getId(),
                            "invoiceId", invoice.getId(),
                            "invoiceStatus", invoice.getStatus()));
        });
    }

    /**
     * Zakázku nelze zrušit změnou stavu, dokud na ní visí materiál vydaný ze skladu
     * (audit KN-11 / 01-J-4, rozhodnutí uživatele 2026-07-30: <em>odmítnout, dokud není
     * vrácen</em>).
     *
     * <p>Blokuje jen skutečně <strong>vydaný</strong> materiál, ne pouhá rezervace (V83) —
     * viz komentář v těle. Hláška vyjmenuje konkrétní položky a pošle obsluhu na jejich
     * smazání, které vydané množství vrátí vratkou {@code ISSUE_RETURN}
     * ({@code OrderItemServiceImpl.delete}).
     *
     * <p>Vyhrazená cesta {@link #cancel} touto brankou projde vždy: veškerý vydaný materiál
     * nejdřív sama vrátí přes {@code returnIssuedStock} (rozhodnutí uživatele 2026-08-06),
     * takže branka už nemá co blokovat.
     */
    private void requireMaterialReturned(Order order) {
        // Jen skutečně VYDANÝ materiál, ne pouhá rezervace (V83, rozhodnutí 2026-08-06).
        // Do rezervačního modelu tu stálo `goodsReceiptItemId != null`, což blokovalo
        // i zakázku, ze které ze skladu nikdy nic neodešlo — přesně ta bolest, kvůli které
        // rezervace vznikly. Hláška navíc o takovém dílu tvrdila „vydaný ze skladu“,
        // přestože ležel v regálu.
        List<OrderItem> issuedMaterial = orderItemMapper.findIssuedByOrderId(order.getId());
        if (issuedMaterial.isEmpty()) {
            return;
        }

        String itemList = issuedMaterial.stream()
                .map(item -> item.getName() + " ("
                        + item.getQuantity().stripTrailingZeros().toPlainString()
                        + " " + item.getUnit() + ")")
                .collect(Collectors.joining(", "));

        throw new BusinessRuleException(
                "ORDER_HAS_ISSUED_MATERIAL", "status",
                "Zakázku nelze zrušit — drží materiál vydaný ze skladu: " + itemList
                        + ". Vraťte ho na sklad smazáním těchto položek, pak zakázku zrušte.",
                Map.of("orderId", order.getId(),
                        "orderItemIds", issuedMaterial.stream().map(OrderItem::getId).toList()));
    }

    /**
     * Datum skutečného dokončení podle toho, co se se stavem děje (rozhodnutí uživatele
     * 2026-08-05).
     *
     * <ul>
     *   <li><strong>Přechod do {@code COMPLETED}</strong> — doplní se dnešek, pokud ho volající
     *       neposlal. Do té doby ho obsluha musela vyplňovat ručně, takže u většiny zakázek
     *       zůstávalo pole prázdné.</li>
     *   <li><strong>Znovuotevření</strong> — vynuluje se; zakázka zase není hotová.</li>
     *   <li><strong>Cokoli jiného včetně identity</strong> — poslaná hodnota beze změny.</li>
     * </ul>
     *
     * <p>Váže se na <strong>přechod</strong>, ne na výsledný stav: {@code PUT} je full-replace
     * a obsluha smí datum u dokončené zakázky legitimně vymazat. Kdyby se dosazovalo podle
     * stavu, nešlo by to (a opakované uložení by přepisovalo, co člověk smazal).
     */
    private OffsetDateTime resolveCompletedAt(OrderStatus current, OrderStatus target,
                                              OffsetDateTime requested) {
        if (current == target) {
            return requested;
        }
        if (target == OrderStatus.COMPLETED) {
            return requested != null ? requested : OffsetDateTime.now();
        }
        if (current.isReopenable()) {
            return null;
        }
        return requested;
    }

    private OrderDto.DetailResponse verifyAndFetchAfterUpdate(Long id, int affectedRows) {
        if (affectedRows == 0) {
            throw new IllegalStateException(
                    "Zakázka " + id + " zmizela během aktualizace (byla načtena těsně předtím)");
        }
        return fetchOrFail(id);
    }

    private OrderDto.DetailResponse fetchOrFail(Long id) {
        return orderMapper.findById(id)
                .map(orderConverter::toDetailResponse)
                .orElseThrow(() -> new IllegalStateException(
                        "Zakázka " + id + " zmizela mezi UPDATE a SELECT"));
    }
}
