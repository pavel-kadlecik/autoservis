package cz.palo.autoservis.model.dto.dashboard;

import cz.palo.autoservis.model.dto.warehouse.LowStockDto;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * DTO namespace pro souhrn na úvodní stránce (dashboard).
 *
 * <p>Čistě <b>read-only</b> agregace nad existujícími daty — žádná doména,
 * žádný konvertor: jednotlivé projekce se mapují přímo z SQL (vzor
 * {@link cz.palo.autoservis.model.dto.warehouse.StockValuationDto},
 * {@link LowStockDto}). Dashboard nic needituje, jen naviguje: každá dlaždice
 * odkazuje na už existující seznam nebo detail.
 *
 * <p>Preview seznamy jsou omezené (max 5 řádků, řazené podle naléhavosti) —
 * počty jsou vždy úplné, aby dlaždice nelhala číslem.
 */
public class DashboardDto {

    /** Kompletní odpověď endpointu {@code GET /dashboard/summary}. */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class Summary {
        private Orders orders;
        private Invoices invoices;
        private Warehouse warehouse;
        private Vehicles vehicles;
        private Revenue revenue;
        private Margin margin;
    }

    // ------------------------------------------------------------------
    // Zakázky
    // ------------------------------------------------------------------

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class Orders {
        /** Počty rozpracovaných zakázek podle stavu (bez COMPLETED/CANCELLED). */
        private Map<String, Integer> byStatus;
        /** Součet všech rozpracovaných (Σ {@link #byStatus}). */
        private int inProgressTotal;

        /** Zakázky po odhadovaném termínu dokončení (celkový počet). */
        private int overdueCount;
        /** Preview nejdéle po termínu (max 5). */
        private List<OrderPreview> overdue;

        /** Zakázky ve stavu READY_FOR_PICKUP (celkový počet). */
        private int readyForPickupCount;
        /** Preview k vyzvednutí (max 5). */
        private List<OrderPreview> readyForPickup;

        /** Dokončené zakázky bez (nestornované) faktury — k vyfakturování. */
        private int toInvoiceCount;
    }

    /** Řádek preview zakázky. {@code daysOverdue} počítá DB, jako STK filtr. */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class OrderPreview {
        private Long id;
        private String orderNumber;
        private String vehicleLabel;
        private String vehicleLicensePlate;
        private String status;
        private OffsetDateTime estimatedCompletionAt;
        /** Počet dnů po termínu (jen u dlaždice „po termínu"; jinak null). */
        private Integer daysOverdue;
    }

    // ------------------------------------------------------------------
    // Faktury
    // ------------------------------------------------------------------

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class Invoices {
        /** Vystavené faktury po splatnosti (celkový počet). */
        private int overdueCount;
        /** Součet částek (s DPH) faktur po splatnosti. */
        private BigDecimal overdueTotal;
        /** Preview nejdéle po splatnosti (max 5). */
        private List<InvoicePreview> overdue;

        /** Koncepty faktur (DRAFT) k vystavení. */
        private int draftCount;
    }

    /** Řádek preview faktury po splatnosti. */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class InvoicePreview {
        private Long id;
        private String invoiceNumber;
        private String customerName;
        private LocalDate dueDate;
        private BigDecimal amount;
        /** Počet dnů po splatnosti (počítá DB). */
        private Integer daysOverdue;
    }

    // ------------------------------------------------------------------
    // Sklad
    // ------------------------------------------------------------------

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class Warehouse {
        /** Aktivní díly pod hlídaným minimem (celkový počet). */
        private int belowMinimumCount;
        /** Preview pod minimem (max 5) — reuse {@link LowStockDto}. */
        private List<LowStockDto> belowMinimum;

        /** Hodnota skladu (Σ zbytek šarže × nákupní cena bez DPH), z view V42. */
        private BigDecimal stockValue;

        /** Příjemky čekající na kontrolu (draft PENDING_REVIEW). */
        private int pendingReceiptsCount;

        /** Otevřená inventura, nebo {@code null} když žádná neběží. */
        private OpenStockTake openStockTake;
    }

    /** Rozpracovaná (OPEN) inventura — jen tolik, kolik dlaždice potřebuje. */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class OpenStockTake {
        private Long id;
        private String note;
        private OffsetDateTime openedAt;
    }

    // ------------------------------------------------------------------
    // Vozidla — STK
    // ------------------------------------------------------------------

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class Vehicles {
        /** Vozidla s končící (≤ 30 dní) nebo propadlou STK (celkový počet). */
        private int stkExpiringCount;
        /** Preview (max 5), nejdřív propadlé a nejbližší. */
        private List<VehiclePreview> stkExpiring;
    }

    /**
     * Řádek preview STK. {@code expired} počítá DB.
     *
     * <p>Pozn.: čerstvost je jen tak dobrá jako poslední snapshot z registru
     * (data se obnovují on-demand; noční refresh je roadmapa §2.4).
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class VehiclePreview {
        private Long id;
        private String licensePlate;
        private String label;
        private LocalDate stkValidUntil;
        private boolean expired;
    }

    // ------------------------------------------------------------------
    // Tržby
    // ------------------------------------------------------------------

    /**
     * Tržby z faktur (ISSUED + PAID) podle {@code issue_date}.
     *
     * <p>Ne podle data úhrady — datum zaplacení faktury se neeviduje.
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class Revenue {
        private BigDecimal currentMonth;
        private BigDecimal previousMonth;
    }

    // ------------------------------------------------------------------
    // Marže (materiál + práce)
    // ------------------------------------------------------------------

    /**
     * Marže z vyfakturovaných zakázek (`Σ (unit_price − purchase_price) × quantity`),
     * tento a minulý měsíc podle {@code issue_date} faktury — zrcadlí a doplňuje
     * {@link Revenue}. Náklad práce ({@code purchase_price} u LABOR položek, snímek
     * hodinové sazby, D-3) tuto marži teprve odemyká — dřív šel spočítat jen materiál.
     *
     * <p>Základem jsou položky zakázek s vyfakturovanou zakázkou (invoice ISSUED/PAID)
     * a <b>vyplněnou</b> nákupní cenou; položky bez známého nákladu se vynechávají, aby
     * marži uměle nenafoukly. {@code total} zahrnuje i ostatní služby, proto může být
     * vyšší než {@code material + labor}.
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class Margin {
        private BigDecimal totalCurrentMonth;
        private BigDecimal totalPreviousMonth;
        private BigDecimal materialCurrentMonth;
        private BigDecimal materialPreviousMonth;
        private BigDecimal laborCurrentMonth;
        private BigDecimal laborPreviousMonth;
    }

    // ------------------------------------------------------------------
    // Statistika — měsíční řada (modal „Statistika")
    // ------------------------------------------------------------------

    /**
     * Odpověď {@code GET /dashboard/statistics}: měsíční řada zvoleného roku.
     *
     * <p>Nic se neukládá — měsíce jsou kdykoli zpětně spočitatelné z faktur
     * ({@code issue_date}) a položek zakázek (snapshot {@code purchase_price});
     * uložený agregát by byl druhý zdroj pravdy a rozešel by se při stornu.
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class Statistics {
        /** Rok, ke kterému patří {@link #months}. */
        private int year;
        /** Roky s daty (pro select filtru), nejnovější první. */
        private List<Integer> availableYears;
        /** Jen měsíce, ve kterých něco je (1–12, vzestupně). */
        private List<MonthlyStats> months;
    }

    /**
     * Řádek měsíční statistiky. Tržby (s DPH) a počet faktur podle
     * {@code issue_date} ISSUED+PAID (zrcadlí {@link Revenue}), marže (bez DPH)
     * jako {@link Margin}; počet zakázek podle {@code created_at} — počet
     * vyfakturovaných by kvůli 1:1 faktura↔zakázka kopíroval počet faktur.
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class MonthlyStats {
        private int month;
        private BigDecimal revenue;
        private BigDecimal margin;
        private int orderCount;
        private int invoiceCount;
    }

    // ------------------------------------------------------------------
    // Pomocné projekce pro mapper (nezobrazují se přímo v odpovědi)
    // ------------------------------------------------------------------

    /** Řádek {@code GROUP BY status} — mapper vrací, service složí do mapy. */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class StatusCount {
        private String status;
        private int count;
    }

    /** Počet + součet v jednom dotazu (faktury po splatnosti). */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class CountSum {
        private int count;
        private BigDecimal total;
    }
}
