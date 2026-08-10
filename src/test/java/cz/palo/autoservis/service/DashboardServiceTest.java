package cz.palo.autoservis.service;

import cz.palo.autoservis.AbstractIntegrationTest;
import cz.palo.autoservis.mapper.InvoiceMapper;
import cz.palo.autoservis.mapper.OrderMapper;
import cz.palo.autoservis.model.domain.billing.Invoice;
import cz.palo.autoservis.model.domain.order.Order;
import cz.palo.autoservis.model.dto.dashboard.DashboardDto;
import cz.palo.autoservis.model.enums.PaymentMethod;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Souhrn dashboardu ({@link DashboardService}) — agregace napříč moduly.
 *
 * <p>Seed (V8 aj.) by počty zkresloval, proto se zdroje před každým scénářem
 * <b>izolují</b> (deaktivace / neutralizace stavů; celý test běží v transakci,
 * která se odroluje). Pak se vloží řízená data a ověří odvozená čísla i preview.
 */
@Transactional
class DashboardServiceTest extends AbstractIntegrationTest {

    @Autowired private DashboardService dashboardService;
    @Autowired private OrderMapper orderMapper;
    @Autowired private InvoiceMapper invoiceMapper;
    @Autowired private InvoiceService invoiceService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private CreditNoteService creditNoteService;

    private Long customerId;
    private Long vehicleId;

    @BeforeEach
    void isolateSeed() {
        // Zakázky, faktury, sklad i STK stranou — ať přehled vidí jen data testu.
        jdbc.update("UPDATE \"order\".orders SET is_active = FALSE");
        jdbc.update("UPDATE billing.invoices SET status = 'CANCELLED'");
        jdbc.update("UPDATE warehouse.products SET is_active = FALSE");
        jdbc.update("UPDATE warehouse.goods_receipts SET status = 'CONFIRMED' WHERE status = 'PENDING_REVIEW'");
        jdbc.update("UPDATE vehicle.vehicles SET stk_valid_until = NULL");

        customerId = jdbc.queryForObject(
                "SELECT id FROM customer.customers WHERE is_active = TRUE ORDER BY id LIMIT 1", Long.class);
        vehicleId = jdbc.queryForObject(
                "SELECT id FROM vehicle.vehicles ORDER BY id LIMIT 1", Long.class);
    }

    /** Vloží zakázku s odhadem dokončení; vrátí její id (stav zůstává RECEIVED). */
    private Long order(String description, OffsetDateTime estimatedCompletion) {
        Order order = Order.builder()
                .receivedAt(LocalDate.now())
                .customerId(customerId).vehicleId(vehicleId)
                .description(description).estimatedCompletionAt(estimatedCompletion)
                .createdBy(1L).build();
        orderMapper.insert(order);
        return order.getId();
    }

    private void setStatus(Long orderId, String status) {
        jdbc.update("UPDATE \"order\".orders SET status = CAST(? AS \"order\".order_status) WHERE id = ?",
                status, orderId);
    }

    /** Vloží položku zakázky (náklad = purchasePrice; {@code null} = neznámý náklad). */
    private void orderItem(Long orderId, String type, String unitPrice, String purchasePrice, String qty) {
        jdbc.update("""
                INSERT INTO "order".order_items
                    (order_id, item_type, name, quantity, unit, purchase_price, unit_price, vat_rate, position, created_by)
                VALUES (?, CAST(? AS "order".order_item_type), ?, CAST(? AS NUMERIC), ?, CAST(? AS NUMERIC), CAST(? AS NUMERIC), 21, 1, 1)
                """,
                orderId, type, type + " položka", qty, "LABOR".equals(type) ? "hod" : "ks", purchasePrice, unitPrice);
    }

    /** Vloží fakturu k zakázce a přepne ji na ISSUED (číslo má od založení — V71). */
    private void issuedInvoice(Long orderId, LocalDate issueDate) {
        Long id = invoice(orderId, issueDate, issueDate.plusDays(14));
        jdbc.update("UPDATE billing.invoices SET status = 'ISSUED' WHERE id = ?", id);
    }

    /** Vloží fakturu k zakázce (stav zůstává DRAFT). */
    private Long invoice(Long orderId, LocalDate issueDate, LocalDate dueDate) {
        Invoice inv = Invoice.builder()
                .orderId(orderId).customerId(customerId)
                // Od V71 číslo vkládá aplikace při založení a CHECK chk_invoice_issued_has_number
                // nepustí vystavení bez něj — helper proto číslo nastavuje jako produkční kód.
                .invoiceNumber(invoiceService.suggestNextNumber(issueDate).getInvoiceNumber())
                .issueDate(issueDate).dueDate(dueDate).taxableSupplyDate(issueDate)
                .paymentMethod(PaymentMethod.CASH)
                .customerNameSnapshot("Testovací zákazník").orderNumberSnapshot("ZAK-TEST")
                .createdBy(1L).build();
        invoiceMapper.insert(inv);
        return inv.getId();
    }

    /**
     * Dá faktuře jednu položku o známé částce a vrátí její hodnotu s DPH.
     *
     * <p>Bez položek dává view {@code v_invoice_price_totals} nulu, takže by se na tržbách
     * nedalo ověřit vůbec nic — přesně ta past, kterou audit označil za planý test (T-4).
     * {@code invoice_items.order_item_id} je NOT NULL s FK, proto se zakládá i položka zakázky.
     */
    private BigDecimal invoiceItem(Long invoiceId, Long orderId, String unitPrice) {
        Long orderItemId = jdbc.queryForObject("""
                INSERT INTO "order".order_items
                    (order_id, item_type, name, quantity, unit, unit_price, vat_rate, position, created_by)
                VALUES (?, CAST('LABOR' AS "order".order_item_type), 'Práce', 1, 'hod', CAST(? AS NUMERIC), 21, 1, 1)
                RETURNING id
                """, Long.class, orderId, unitPrice);
        jdbc.update("""
                INSERT INTO billing.invoice_items
                    (invoice_id, order_item_id, name, quantity, unit, unit_price, vat_rate, position)
                VALUES (?, ?, 'Práce', 1, 'hod', CAST(? AS NUMERIC), 21, 1)
                """, invoiceId, orderItemId, unitPrice);
        return new BigDecimal(unitPrice).multiply(new BigDecimal("1.21"));
    }

    /** Založí koncept dobropisu k faktuře a vrátí jeho id. */
    private Long draftCreditNote(Long invoiceId) {
        // Dobropis opravuje doklad, který zákazník DOSTAL (2026-08-08); nepředaná faktura
        // se místo toho maže a vystavuje znovu.
        invoiceService.handOver(invoiceId, 1L);
        var request = new cz.palo.autoservis.model.dto.billing.CreditNoteDto.CreateRequest();
        request.setOriginalInvoiceId(invoiceId);
        request.setCorrectionReason("Reklamace — vrácení dílu");
        return creditNoteService.createFromInvoice(request, 1L).getId();
    }

    @Test
    @DisplayName("dobropis: vystavený ruší pohledávku po splatnosti, koncept ne (KN-20)")
    void issuedCreditNote_removesInvoiceFromOverdue() {
        // Splatnost nesmí předcházet vystavení (chk_due_date), proto vystaveno dřív.
        Long orderId = order("faktura po splatnosti", null);
        Long invoiceId = invoice(orderId, LocalDate.now().minusDays(20), LocalDate.now().minusDays(6));
        invoiceItem(invoiceId, orderId, "1000.00");
        jdbc.update("UPDATE billing.invoices SET status = 'ISSUED' WHERE id = ?", invoiceId);

        assertThat(dashboardService.getSummary().getInvoices().getOverdueCount()).isEqualTo(1);

        Long creditNoteId = draftCreditNote(invoiceId);

        // KONCEPT nic neruší — nemá evidenční číslo, není daňovým dokladem, pohledávka trvá.
        assertThat(dashboardService.getSummary().getInvoices().getOverdueCount())
                .as("koncept dobropisu pohledávku neruší").isEqualTo(1);

        creditNoteService.issue(creditNoteId, 1L);

        // Dřív tu faktura zůstala napořád a obsluha urgovala zákazníka, kterému vrátila peníze.
        assertThat(dashboardService.getSummary().getInvoices().getOverdueCount())
                .as("dobropisovanou fakturu už nelze urgovat").isZero();
    }

    @Test
    @DisplayName("dobropis: vystavený snižuje tržbu měsíce o částku faktury, koncept ne (KN-20)")
    void issuedCreditNote_reducesMonthlyRevenue() {
        Long orderId = order("faktura k dobropisu", null);
        Long invoiceId = invoice(orderId, LocalDate.now(), LocalDate.now().plusDays(14));
        BigDecimal gross = invoiceItem(invoiceId, orderId, "1000.00");
        jdbc.update("UPDATE billing.invoices SET status = 'ISSUED' WHERE id = ?", invoiceId);

        BigDecimal revenueBefore = dashboardService.getSummary().getRevenue().getCurrentMonth();
        assertThat(revenueBefore)
                .as("předpoklad testu: faktura s položkou se v tržbách projeví")
                .isEqualByComparingTo(gross);

        Long creditNoteId = draftCreditNote(invoiceId);

        assertThat(dashboardService.getSummary().getRevenue().getCurrentMonth())
                .as("koncept dobropisu tržbu nesnižuje").isEqualByComparingTo(revenueBefore);

        creditNoteService.issue(creditNoteId, 1L);

        assertThat(dashboardService.getSummary().getRevenue().getCurrentMonth())
                .as("tržba se sníží o celou částku dobropisované faktury")
                .isEqualByComparingTo(revenueBefore.subtract(gross));
    }

    @Test
    @DisplayName("rozpracované zakázky: rozpad po stavech, po termínu, k vyzvednutí, k vyfakturování")
    void ordersSection() {
        OffsetDateTime past = OffsetDateTime.now().minusDays(3);
        OffsetDateTime future = OffsetDateTime.now().plusDays(5);

        Long overdue = order("po termínu", past);      // RECEIVED, po termínu
        order("v termínu", future);                     // RECEIVED, není po termínu
        Long ready = order("k vyzvednutí", future);
        setStatus(ready, "READY_FOR_PICKUP");
        Long completedNoInvoice = order("hotová bez faktury", past);
        setStatus(completedNoInvoice, "COMPLETED");     // → k vyfakturování
        Long completedInvoiced = order("hotová s fakturou", past);
        setStatus(completedInvoiced, "COMPLETED");
        invoice(completedInvoiced, LocalDate.now(), LocalDate.now().plusDays(14)); // má fakturu

        DashboardDto.Orders orders = dashboardService.getSummary().getOrders();

        assertThat(orders.getByStatus().get("RECEIVED")).isEqualTo(2);
        assertThat(orders.getByStatus().get("READY_FOR_PICKUP")).isEqualTo(1);
        assertThat(orders.getInProgressTotal()).isEqualTo(3);   // 2× RECEIVED + 1× READY
        assertThat(orders.getReadyForPickupCount()).isEqualTo(1);

        assertThat(orders.getOverdueCount()).isEqualTo(1);
        assertThat(orders.getOverdue()).singleElement()
                .satisfies(row -> {
                    assertThat(row.getId()).isEqualTo(overdue);
                    assertThat(row.getDaysOverdue()).isGreaterThanOrEqualTo(2);
                });

        // COMPLETED bez faktury se počítá, COMPLETED s fakturou ne.
        assertThat(orders.getToInvoiceCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("faktury: po splatnosti (count) i koncepty; PAID/CANCELLED se nepočítají")
    void invoicesSection() {
        Long o1 = order("k faktuře po splatnosti", null);
        Long overdueInv = invoice(o1, LocalDate.now().minusDays(20), LocalDate.now().minusDays(6));
        jdbc.update("UPDATE billing.invoices SET status = 'ISSUED' WHERE id = ?", overdueInv);

        Long o2 = order("k faktuře v splatnosti", null);
        Long notOverdue = invoice(o2, LocalDate.now(), LocalDate.now().plusDays(14));
        jdbc.update("UPDATE billing.invoices SET status = 'ISSUED' WHERE id = ?", notOverdue);

        Long o3 = order("koncept faktury", null);
        invoice(o3, LocalDate.now(), LocalDate.now().plusDays(14));   // zůstává DRAFT

        DashboardDto.Invoices invoices = dashboardService.getSummary().getInvoices();

        assertThat(invoices.getOverdueCount()).isEqualTo(1);
        assertThat(invoices.getOverdueTotal()).isNotNull();
        assertThat(invoices.getOverdue()).singleElement()
                .satisfies(row -> assertThat(row.getDaysOverdue()).isGreaterThanOrEqualTo(5));
        assertThat(invoices.getDraftCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("STK: končící i propadlá se počítají, propadlá je v preview první")
    void stkSection() {
        Long[] ids = jdbc.queryForList(
                "SELECT id FROM vehicle.vehicles ORDER BY id LIMIT 2", Long.class).toArray(new Long[0]);
        jdbc.update("UPDATE vehicle.vehicles SET stk_valid_until = ? WHERE id = ?",
                LocalDate.now().minusDays(2), ids[0]);   // propadlá
        jdbc.update("UPDATE vehicle.vehicles SET stk_valid_until = ? WHERE id = ?",
                LocalDate.now().plusDays(10), ids[1]);   // končící

        DashboardDto.Vehicles vehicles = dashboardService.getSummary().getVehicles();

        assertThat(vehicles.getStkExpiringCount()).isEqualTo(2);
        assertThat(vehicles.getStkExpiring().get(0).getId()).isEqualTo(ids[0]);
        assertThat(vehicles.getStkExpiring().get(0).isExpired()).isTrue();
        assertThat(vehicles.getStkExpiring().get(1).isExpired()).isFalse();
    }

    @Test
    @DisplayName("preview je omezené na 5, počet zůstává úplný")
    void previewIsCappedButCountIsFull() {
        OffsetDateTime past = OffsetDateTime.now().minusDays(1);
        for (int i = 0; i < 7; i++) {
            order("po termínu #" + i, past);
        }

        DashboardDto.Orders orders = dashboardService.getSummary().getOrders();

        assertThat(orders.getOverdueCount()).isEqualTo(7);
        assertThat(orders.getOverdue()).hasSize(5);
    }

    @Test
    @DisplayName("prázdné sekce skladu: po izolaci nulové počty, žádná otevřená inventura")
    void warehouseSectionIsolated() {
        DashboardDto.Warehouse warehouse = dashboardService.getSummary().getWarehouse();

        assertThat(warehouse.getBelowMinimumCount()).isZero();
        assertThat(warehouse.getBelowMinimum()).isEmpty();
        assertThat(warehouse.getStockValue()).isNotNull();
        assertThat(warehouse.getPendingReceiptsCount()).isZero();
        assertThat(warehouse.getOpenStockTake()).isNull();
    }

    @Test
    @DisplayName("souhrn nikdy nevrací null sekce ani null tržby/marži")
    void summaryIsFullyPopulated() {
        DashboardDto.Summary summary = dashboardService.getSummary();

        assertThat(summary.getOrders()).isNotNull();
        assertThat(summary.getInvoices()).isNotNull();
        assertThat(summary.getWarehouse()).isNotNull();
        assertThat(summary.getVehicles()).isNotNull();
        assertThat(summary.getRevenue()).isNotNull();
        assertThat(summary.getRevenue().getCurrentMonth()).isNotNull();
        assertThat(summary.getRevenue().getPreviousMonth()).isNotNull();
        assertThat(summary.getMargin()).isNotNull();
        assertThat(summary.getMargin().getTotalCurrentMonth()).isNotNull();
        assertThat(summary.getMargin().getTotalPreviousMonth()).isNotNull();
    }

    @Test
    @DisplayName("statistika: měsíční řada — počty zakázek a faktur, marže; bez parametru aktuální rok; rok bez dat je prázdný")
    void statisticsSection() {
        int year = LocalDate.now().getYear();
        int month = LocalDate.now().getMonthValue();

        // Vyfakturovaná zakázka: marže (800−550)×2 = 500; faktura tento měsíc.
        Long invoiced = order("statistika — vyfakturovaná", null);
        orderItem(invoiced, "LABOR", "800.00", "550.00", "2");
        issuedInvoice(invoiced, LocalDate.now());

        // Jen založená zakázka — do počtu zakázek ano (created_at), do faktur/marže ne.
        order("statistika — jen založená", null);

        DashboardDto.Statistics stats = dashboardService.getStatistics(year);

        assertThat(stats.getYear()).isEqualTo(year);
        assertThat(stats.getAvailableYears()).contains(year);
        assertThat(stats.getMonths()).anySatisfy(row -> {
            assertThat(row.getMonth()).isEqualTo(month);
            assertThat(row.getOrderCount()).isEqualTo(2);
            assertThat(row.getInvoiceCount()).isEqualTo(1);
            assertThat(row.getMargin()).isEqualByComparingTo("500.00");
            assertThat(row.getRevenue()).isNotNull();
        });

        // Bez parametru se bere aktuální rok.
        assertThat(dashboardService.getStatistics(null).getYear()).isEqualTo(year);

        // Rok bez jakýchkoli dat vrací prázdnou řadu, ne chybu.
        assertThat(dashboardService.getStatistics(1999).getMonths()).isEmpty();
    }

    @Test
    @DisplayName("marže: materiál i práce z vyfakturovaných zakázek, tento vs. minulý měsíc; položka bez nákladu se vynechá")
    void marginSection() {
        // Tento měsíc: LABOR marže (800−550)×2=500, MATERIAL marže (300−200)×1=100,
        // + LABOR bez nákupní ceny (neznámý náklad) → do marže se NEzapočítá.
        Long current = order("marže tento měsíc", null);
        orderItem(current, "LABOR",    "800.00", "550.00", "2");
        orderItem(current, "MATERIAL", "300.00", "200.00", "1");
        orderItem(current, "LABOR",    "400.00", null,     "1");   // neznámý náklad → vynechat
        issuedInvoice(current, LocalDate.now());

        // Minulý měsíc: LABOR marže (1000−600)×1=400.
        Long previous = order("marže minulý měsíc", null);
        orderItem(previous, "LABOR", "1000.00", "600.00", "1");
        issuedInvoice(previous, LocalDate.now().minusMonths(1));

        // Nevyfakturovaná zakázka (bez faktury) do marže nevstupuje.
        Long notInvoiced = order("bez faktury", null);
        orderItem(notInvoiced, "LABOR", "999.00", "100.00", "5");

        DashboardDto.Margin margin = dashboardService.getSummary().getMargin();

        assertThat(margin.getLaborCurrentMonth()).isEqualByComparingTo("500.00");
        assertThat(margin.getMaterialCurrentMonth()).isEqualByComparingTo("100.00");
        assertThat(margin.getTotalCurrentMonth()).isEqualByComparingTo("600.00");   // 500 + 100 (bez neznámého nákladu)
        assertThat(margin.getLaborPreviousMonth()).isEqualByComparingTo("400.00");
        assertThat(margin.getMaterialPreviousMonth()).isEqualByComparingTo("0");
        assertThat(margin.getTotalPreviousMonth()).isEqualByComparingTo("400.00");
    }
}
