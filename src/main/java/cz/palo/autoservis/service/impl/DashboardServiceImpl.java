package cz.palo.autoservis.service.impl;

import cz.palo.autoservis.mapper.DashboardMapper;
import cz.palo.autoservis.mapper.WarehouseMapper;
import cz.palo.autoservis.model.dto.dashboard.DashboardDto;
import cz.palo.autoservis.model.dto.warehouse.LowStockDto;
import cz.palo.autoservis.service.DashboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Skládá souhrn dashboardu z lehkých agregací.
 *
 * <p>Žádný konvertor — jednotlivé dotazy vracejí projekce rovnou jako DTO
 * (vzor {@code StockValuation}); service jen posbírá počty, součty a preview.
 * Přehled „pod minimem" a hodnotu skladu bere z {@link WarehouseMapper}, ať se
 * skladová logika neduplikuje. Celé čtení běží v jedné read-only transakci, aby
 * všechna čísla pocházela z téhož okamžiku.
 */
@Service
@RequiredArgsConstructor
public class DashboardServiceImpl implements DashboardService {

    /** Pořadí stavů rozpracovaných zakázek (workflow, ne abecedně). */
    private static final List<String> IN_PROGRESS_STATUSES = List.of(
            "RECEIVED", "DIAGNOSIS", "WAITING_FOR_PARTS", "IN_PROGRESS", "READY_FOR_PICKUP");

    /** Kolik řádků ukazuje preview každé dlaždice. */
    private static final int PREVIEW_LIMIT = 5;

    private final DashboardMapper dashboardMapper;
    private final WarehouseMapper warehouseMapper;

    @Override
    @Transactional(readOnly = true)
    public DashboardDto.Summary getSummary() {
        return DashboardDto.Summary.builder()
                .orders(buildOrders())
                .invoices(buildInvoices())
                .warehouse(buildWarehouse())
                .vehicles(buildVehicles())
                .revenue(dashboardMapper.sumRevenue())
                .margin(dashboardMapper.sumMargin())
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public DashboardDto.Statistics getStatistics(Integer year) {
        int resolvedYear = year != null ? year : LocalDate.now().getYear();
        return DashboardDto.Statistics.builder()
                .year(resolvedYear)
                .availableYears(dashboardMapper.findStatsYears())
                .months(dashboardMapper.findMonthlyStats(resolvedYear))
                .build();
    }

    private DashboardDto.Orders buildOrders() {
        Map<String, Integer> byStatus = new LinkedHashMap<>();
        IN_PROGRESS_STATUSES.forEach(status -> byStatus.put(status, 0));
        dashboardMapper.countActiveOrdersByStatus()
                .forEach(row -> byStatus.put(row.getStatus(), row.getCount()));

        int inProgressTotal = byStatus.values().stream().mapToInt(Integer::intValue).sum();

        return DashboardDto.Orders.builder()
                .byStatus(byStatus)
                .inProgressTotal(inProgressTotal)
                .overdueCount(dashboardMapper.countOverdueOrders())
                .overdue(dashboardMapper.findOverdueOrdersPreview())
                .readyForPickupCount(byStatus.getOrDefault("READY_FOR_PICKUP", 0))
                .readyForPickup(dashboardMapper.findReadyForPickupPreview())
                .toInvoiceCount(dashboardMapper.countOrdersToInvoice())
                .build();
    }

    private DashboardDto.Invoices buildInvoices() {
        DashboardDto.CountSum overdue = dashboardMapper.sumOverdueInvoices();
        return DashboardDto.Invoices.builder()
                .overdueCount(overdue.getCount())
                .overdueTotal(overdue.getTotal())
                .overdue(dashboardMapper.findOverdueInvoicesPreview())
                .draftCount(dashboardMapper.countDraftInvoices())
                .build();
    }

    private DashboardDto.Warehouse buildWarehouse() {
        // Přehled „pod minimem" je malý (jen hlídané díly pod prahem) — vezmeme
        // celý a preview i počet odvodíme, ať se WHERE neduplikuje v dashboardu.
        List<LowStockDto> belowMinimum = warehouseMapper.findLowStock();

        return DashboardDto.Warehouse.builder()
                .belowMinimumCount(belowMinimum.size())
                .belowMinimum(belowMinimum.stream().limit(PREVIEW_LIMIT).toList())
                .stockValue(nullToZero(dashboardMapper.sumStockValue()))
                .pendingReceiptsCount(dashboardMapper.countPendingReceipts())
                .openStockTake(dashboardMapper.findOpenStockTake().orElse(null))
                .build();
    }

    private DashboardDto.Vehicles buildVehicles() {
        List<DashboardDto.VehiclePreview> expiring = dashboardMapper.findStkExpiring();

        return DashboardDto.Vehicles.builder()
                .stkExpiringCount(expiring.size())
                .stkExpiring(expiring.stream().limit(PREVIEW_LIMIT).toList())
                .build();
    }

    private static BigDecimal nullToZero(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }
}
