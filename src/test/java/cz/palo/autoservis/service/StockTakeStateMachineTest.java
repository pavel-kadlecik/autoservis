package cz.palo.autoservis.service;

import cz.palo.autoservis.AbstractIntegrationTest;
import cz.palo.autoservis.exception.BusinessRuleException;
import cz.palo.autoservis.exception.ConflictException;
import cz.palo.autoservis.exception.ResourceNotFoundException;
import cz.palo.autoservis.model.domain.warehouse.StockTakeStatus;
import cz.palo.autoservis.model.dto.warehouse.StockTakeDto;
import cz.palo.autoservis.model.dto.warehouse.StockTakeSearchParams;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Stavový automat inventury ({@code StockTakeServiceImpl}) — doplňuje {@code StockTakeTest},
 * který se soustředí na výpočet korekcí (FIFO manko, přebytek, ceny).
 *
 * <p>Tady se testuje jen <strong>přechodová logika</strong>, a to v obou směrech: z každého
 * koncového stavu (CLOSED, CANCELLED) se ověří, že <em>všechny</em> další operace selžou
 * správným kódem — ne jen jedna z nich. Zvlášť se hlídá invariant
 * „<strong>nejvýš jedna otevřená inventura</strong>" včetně toho, že po uzavření či zrušení
 * jde otevřít další.
 */
@Transactional
class StockTakeStateMachineTest extends AbstractIntegrationTest {

    private static final long USER_ID = 1L;

    @Autowired
    private StockTakeService stockTakeService;

    @Autowired
    private ProductService productService;

    private Long createProduct(String sku, String name) {
        var request = new cz.palo.autoservis.model.dto.warehouse.ProductDto.CreateRequest();
        request.setSku(sku);
        request.setName(name);
        request.setUnit("ks");
        request.setDefaultVatRate(21);
        request.setSalePrice(new java.math.BigDecimal("199.00"));
        return productService.create(request).getId();
    }

    // =========================================================================
    // Invariant: nejvýš jedna otevřená inventura
    // =========================================================================

    @Test
    @DisplayName("otevření inventury nasnapshotuje aktivní produkty a vrátí ji jako otevřenou")
    void open_createsSnapshotOfActiveProducts() {
        // Sklad je v seedu prázdný (karty vznikají až potvrzením příjemky), takže si
        // fixturu musí test založit sám — jinak by byl snapshot prázdný a aserce planá.
        Long productId = createProduct("SKU-T3-001", "Díl pro inventuru");

        StockTakeDto.DetailResponse opened = stockTakeService.open(createRequest("první inventura"), USER_ID);

        assertThat(opened.getId()).isNotNull();
        assertThat(opened.getStatus()).isEqualTo(StockTakeStatus.OPEN);
        assertThat(opened.getNote()).isEqualTo("první inventura");
        assertThat(opened.getItems())
                .as("snapshot musí být neprázdný, jinak test nic nedokazuje").isNotEmpty();
        assertThat(opened.getItems()).extracting("productId").contains(productId);
    }

    @Test
    @DisplayName("snapshot obsahuje jen AKTIVNÍ produkty — deaktivovaná karta se do inventury nedostane")
    void open_skipsDeactivatedProducts() {
        Long activeId = createProduct("SKU-T3-002", "Aktivní díl");
        Long inactiveId = createProduct("SKU-T3-003", "Deaktivovaný díl");
        productService.deactivate(inactiveId);

        StockTakeDto.DetailResponse opened = stockTakeService.open(createRequest("inventura"), USER_ID);

        assertThat(opened.getItems()).extracting("productId").contains(activeId);
        assertThat(opened.getItems()).extracting("productId").doesNotContain(inactiveId);
    }

    @Test
    @DisplayName("druhá otevřená inventura → 409 STOCK_TAKE_ALREADY_OPEN")
    void open_secondWhileOneIsOpen_throwsConflict() {
        stockTakeService.open(createRequest("první"), USER_ID);

        assertThatThrownBy(() -> stockTakeService.open(createRequest("druhá"), USER_ID))
                .isInstanceOf(ConflictException.class)
                .satisfies(ex -> assertThat(((ConflictException) ex).getCode())
                        .isEqualTo("STOCK_TAKE_ALREADY_OPEN"));
    }

    @Test
    @DisplayName("po UZAVŘENÍ inventury lze otevřít další (invariant se uvolní)")
    void open_afterClose_isAllowedAgain() {
        StockTakeDto.DetailResponse first = stockTakeService.open(createRequest("první"), USER_ID);
        stockTakeService.close(first.getId(), "hotovo", USER_ID);

        StockTakeDto.DetailResponse second = stockTakeService.open(createRequest("druhá"), USER_ID);

        assertThat(second.getId()).isNotEqualTo(first.getId());
        assertThat(second.getStatus()).isEqualTo(StockTakeStatus.OPEN);
    }

    @Test
    @DisplayName("po ZRUŠENÍ inventury lze otevřít další (druhá cesta uvolnění invariantu)")
    void open_afterCancel_isAllowedAgain() {
        StockTakeDto.DetailResponse first = stockTakeService.open(createRequest("první"), USER_ID);
        stockTakeService.cancel(first.getId(), USER_ID);

        StockTakeDto.DetailResponse second = stockTakeService.open(createRequest("druhá"), USER_ID);

        assertThat(second.getId()).isNotEqualTo(first.getId());
        assertThat(second.getStatus()).isEqualTo(StockTakeStatus.OPEN);
    }

    // =========================================================================
    // Uzavřená inventura je terminální
    // =========================================================================

    @Test
    @DisplayName("uzavřená inventura: zápis položek, uzavření i zrušení selžou")
    void closed_isTerminalForEveryOperation() {
        StockTakeDto.DetailResponse opened = stockTakeService.open(createRequest("k uzavření"), USER_ID);
        Long id = opened.getId();
        stockTakeService.close(id, "hotovo", USER_ID);

        assertThat(stockTakeService.getDetail(id).getStatus()).isEqualTo(StockTakeStatus.CLOSED);

        assertNotEditable(() -> stockTakeService.updateItems(id, emptyItemsUpdate()));
        assertThatThrownBy(() -> stockTakeService.close(id, "znovu", USER_ID))
                .isInstanceOf(RuntimeException.class);
        assertNotEditable(() -> stockTakeService.cancel(id, USER_ID));

        assertThat(stockTakeService.getDetail(id).getStatus())
                .as("stav zůstal CLOSED").isEqualTo(StockTakeStatus.CLOSED);
    }

    // =========================================================================
    // Zrušená inventura je terminální
    // =========================================================================

    @Test
    @DisplayName("zrušená inventura: zápis položek, uzavření i další zrušení selžou")
    void cancelled_isTerminalForEveryOperation() {
        StockTakeDto.DetailResponse opened = stockTakeService.open(createRequest("ke zrušení"), USER_ID);
        Long id = opened.getId();
        stockTakeService.cancel(id, USER_ID);

        assertThat(stockTakeService.getDetail(id).getStatus()).isEqualTo(StockTakeStatus.CANCELLED);

        assertNotEditable(() -> stockTakeService.updateItems(id, emptyItemsUpdate()));
        assertNotEditable(() -> stockTakeService.cancel(id, USER_ID));
        assertThatThrownBy(() -> stockTakeService.close(id, "pozdě", USER_ID))
                .isInstanceOf(RuntimeException.class);

        assertThat(stockTakeService.getDetail(id).getStatus()).isEqualTo(StockTakeStatus.CANCELLED);
    }

    // =========================================================================
    // Otevřená inventura je editovatelná
    // =========================================================================

    @Test
    @DisplayName("otevřenou inventuru lze zrušit i uzavřít (povolené přechody)")
    void open_allowsCancelAndClose() {
        StockTakeDto.DetailResponse toCancel = stockTakeService.open(createRequest("ke zrušení"), USER_ID);
        assertThat(stockTakeService.cancel(toCancel.getId(), USER_ID).getStatus()).isEqualTo(StockTakeStatus.CANCELLED);

        StockTakeDto.DetailResponse toClose = stockTakeService.open(createRequest("k uzavření"), USER_ID);
        assertThat(stockTakeService.close(toClose.getId(), "hotovo", USER_ID).getStatus()).isEqualTo(StockTakeStatus.CLOSED);
    }

    // =========================================================================
    // Počítadla v detailu a zpracování přebytku
    // =========================================================================

    @Test
    @DisplayName("detail počítá spočtené řádky, manka a přebytky odděleně")
    void getDetail_countsCountedShortageAndSurplusLines() {
        Long zeroDiffProduct = createProduct("SKU-T3-010", "Beze změny");
        Long surplusProduct = createProduct("SKU-T3-011", "Přebytek");

        StockTakeDto.DetailResponse opened = stockTakeService.open(createRequest("počítadla"), USER_ID);

        // stejný počet jako na skladě (0) → spočteno, ale bez rozdílu;
        // pět kusů navíc → přebytek. Rozdílné hodnoty schválně, aby prohození polí prasklo.
        stockTakeService.updateItems(opened.getId(), itemsUpdate(
                itemUpdate(itemIdOf(opened, zeroDiffProduct), "0", null),
                itemUpdate(itemIdOf(opened, surplusProduct), "5", "100.00")));

        StockTakeDto.DetailResponse detail = stockTakeService.getDetail(opened.getId());

        assertThat(detail.getCountedLines()).as("spočtené řádky").isEqualTo(2);
        assertThat(detail.getShortageLines()).as("manka — žádné").isZero();
        assertThat(detail.getSurplusLines()).as("přebytky — jeden").isEqualTo(1);
    }

    @Test
    @DisplayName("nespočtené řádky se do počítadel nezapočítají (nepočítáno ≠ nula)")
    void getDetail_uncountedLinesAreNotCounted() {
        createProduct("SKU-T3-012", "Nepočítaný díl");

        StockTakeDto.DetailResponse opened = stockTakeService.open(createRequest("bez počítání"), USER_ID);
        StockTakeDto.DetailResponse detail = stockTakeService.getDetail(opened.getId());

        assertThat(detail.getItems()).isNotEmpty();
        assertThat(detail.getCountedLines()).isZero();
        assertThat(detail.getShortageLines()).isZero();
        assertThat(detail.getSurplusLines()).isZero();
    }

    @Test
    @DisplayName("uzavření s přebytkem naskladní zboží a založí šarži v pseudo-příjemce")
    void close_withSurplus_createsBatchAndRaisesStock() {
        Long productId = createProduct("SKU-T3-013", "Přebytkový díl");

        StockTakeDto.DetailResponse opened = stockTakeService.open(createRequest("přebytek"), USER_ID);
        stockTakeService.updateItems(opened.getId(),
                itemsUpdate(itemUpdate(itemIdOf(opened, productId), "3", "100.00")));

        StockTakeDto.DetailResponse closed = stockTakeService.close(opened.getId(), "hotovo", USER_ID);

        assertThat(closed.getStatus()).isEqualTo(StockTakeStatus.CLOSED);
        assertThat(closed.getSurplusReceiptId())
                .as("přebytek se naskladní přes pseudo-příjemku STOCK_TAKE").isNotNull();
        assertThat(productService.getById(productId).getQuantityOnHand())
                .as("stav skladu zvedl trigger nad kladným ADJUSTMENT").isEqualByComparingTo("3");
    }

    // =========================================================================
    // Čtecí cesty
    // =========================================================================

    @Test
    @DisplayName("getPage vrací inventury včetně nově otevřené; číslo dokladu INV-{rok}-NNNN je vyplněné (V61)")
    void getPage_containsNewlyOpened_withDocumentNumber() {
        StockTakeDto.DetailResponse opened = stockTakeService.open(createRequest("v seznamu"), USER_ID);

        var all = stockTakeService.getPage(new StockTakeSearchParams()).getContent();

        assertThat(all).isNotEmpty();
        assertThat(all).extracting(StockTakeDto.ListResponse::getId).contains(opened.getId());
        // Trigger V61 přidělil číslo dokladu — v detailu i ve výpisu.
        assertThat(opened.getStockTakeNumber()).matches("INV-\\d{4}-\\d{4}");
        assertThat(all)
                .filteredOn(t -> t.getId().equals(opened.getId()))
                .singleElement()
                .satisfies(t -> assertThat(t.getStockTakeNumber()).isEqualTo(opened.getStockTakeNumber()));
    }

    @Test
    @DisplayName("výpis: search hledá přes číslo i poznámku, filtr podle stavu")
    void getPage_searchAndStatusFilter() {
        StockTakeDto.DetailResponse opened = stockTakeService.open(createRequest("Unikatni-poznamka-XYZ"), USER_ID);

        // search podle poznámky
        assertThat(pageWith(p -> p.setSearch("Unikatni-poznamka-XYZ")))
                .extracting(StockTakeDto.ListResponse::getId).contains(opened.getId());
        // search podle čísla dokladu
        assertThat(pageWith(p -> p.setSearch(opened.getStockTakeNumber())))
                .extracting(StockTakeDto.ListResponse::getId).contains(opened.getId());
        // search bez shody → řádek vypadne
        assertThat(pageWith(p -> p.setSearch("neexistujici-vyraz-123")))
                .extracting(StockTakeDto.ListResponse::getId).doesNotContain(opened.getId());
        // filtr stavu: OPEN obsahuje jen otevřené, CLOSED naši otevřenou vynechá
        assertThat(pageWith(p -> p.setStatus(StockTakeStatus.OPEN)))
                .extracting(StockTakeDto.ListResponse::getStatus).containsOnly(StockTakeStatus.OPEN);
        assertThat(pageWith(p -> p.setStatus(StockTakeStatus.CLOSED)))
                .extracting(StockTakeDto.ListResponse::getId).doesNotContain(opened.getId());
    }

    private List<StockTakeDto.ListResponse> pageWith(java.util.function.Consumer<StockTakeSearchParams> cfg) {
        StockTakeSearchParams p = new StockTakeSearchParams();
        p.setPageSize(500);
        cfg.accept(p);
        return stockTakeService.getPage(p).getContent();
    }

    @Test
    @DisplayName("detail neexistující inventury → ResourceNotFoundException (404)")
    void getDetail_unknownId_throwsResourceNotFound() {
        assertThatThrownBy(() -> stockTakeService.getDetail(999_999L))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // =========================================================================
    // Pomocné
    // =========================================================================

    private static void assertNotEditable(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(ex -> assertThat(((BusinessRuleException) ex).getRuleCode())
                        .isEqualTo("STOCK_TAKE_NOT_EDITABLE"));
    }

    private static StockTakeDto.CreateRequest createRequest(String note) {
        StockTakeDto.CreateRequest request = new StockTakeDto.CreateRequest();
        request.setNote(note);
        return request;
    }

    private static StockTakeDto.ItemsUpdateRequest emptyItemsUpdate() {
        StockTakeDto.ItemsUpdateRequest request = new StockTakeDto.ItemsUpdateRequest();
        request.setItems(List.of());
        return request;
    }

    private static StockTakeDto.ItemsUpdateRequest itemsUpdate(StockTakeDto.ItemUpdate... items) {
        StockTakeDto.ItemsUpdateRequest request = new StockTakeDto.ItemsUpdateRequest();
        request.setItems(List.of(items));
        return request;
    }

    private static StockTakeDto.ItemUpdate itemUpdate(Long itemId, String counted, String surplusPrice) {
        StockTakeDto.ItemUpdate item = new StockTakeDto.ItemUpdate();
        item.setId(itemId);
        item.setCountedQuantity(new java.math.BigDecimal(counted));
        if (surplusPrice != null) {
            item.setSurplusUnitPrice(new java.math.BigDecimal(surplusPrice));
        }
        return item;
    }

    /** Řádek inventury patřící danému produktu — id řádku je jiné než id produktu. */
    private static Long itemIdOf(StockTakeDto.DetailResponse stockTake, Long productId) {
        return stockTake.getItems().stream()
                .filter(item -> productId.equals(item.getProductId()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Produkt " + productId + " není v inventuře"))
                .getId();
    }
}
