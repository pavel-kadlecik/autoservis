package cz.palo.autoservis.mapper;

import cz.palo.autoservis.model.dto.dashboard.DashboardDto;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * MyBatis mapper pro souhrn dashboardu — samé read-only agregace.
 *
 * <p>SQL žije v {@code mapper/DashboardMapper.xml}. Každá metoda je jeden lehký
 * {@code COUNT}/{@code SUM}/{@code GROUP BY} nebo omezené preview (LIMIT 5) nad
 * existujícími tabulkami a views; nic se tu nezapisuje. Přehled „pod minimem"
 * a řádkové ocenění se záměrně neduplikují — service je bere z
 * {@link WarehouseMapper}.
 */
@Mapper
public interface DashboardMapper {

    // --- Zakázky ------------------------------------------------------

    /** Počty rozpracovaných zakázek (bez COMPLETED/CANCELLED) po stavech. */
    List<DashboardDto.StatusCount> countActiveOrdersByStatus();

    /** Počet zakázek po odhadovaném termínu dokončení. */
    int countOverdueOrders();

    /** Preview nejdéle po termínu (max 5, nejstarší termín první). */
    List<DashboardDto.OrderPreview> findOverdueOrdersPreview();

    /** Preview zakázek k vyzvednutí (max 5, nejdéle čekající první). */
    List<DashboardDto.OrderPreview> findReadyForPickupPreview();

    /** Dokončené zakázky bez nestornované faktury (k vyfakturování). */
    int countOrdersToInvoice();

    // --- Faktury ------------------------------------------------------

    /** Počet + součet částek vystavených faktur po splatnosti (jeden dotaz). */
    DashboardDto.CountSum sumOverdueInvoices();

    /** Preview nejdéle po splatnosti (max 5). */
    List<DashboardDto.InvoicePreview> findOverdueInvoicesPreview();

    /** Počet konceptů faktur (DRAFT). */
    int countDraftInvoices();

    /** Tržby (ISSUED+PAID) tento a minulý měsíc podle issue_date. */
    DashboardDto.Revenue sumRevenue();

    /**
     * Marže (materiál/práce/celkem) tento a minulý měsíc z položek vyfakturovaných
     * zakázek (invoice ISSUED/PAID) s vyplněnou nákupní cenou, podle issue_date.
     */
    DashboardDto.Margin sumMargin();

    // --- Statistika ---------------------------------------------------

    /**
     * Měsíční řada za zvolený rok — tržby a počet faktur podle {@code issue_date}
     * (ISSUED+PAID), marže jako {@link #sumMargin}, počet zakázek podle
     * {@code created_at}. Vrací jen měsíce, ve kterých něco je.
     */
    List<DashboardDto.MonthlyStats> findMonthlyStats(@Param("year") int year);

    /** Roky, ve kterých existují data (faktury/zakázky), nejnovější první. */
    List<Integer> findStatsYears();

    // --- Sklad --------------------------------------------------------

    /** Hodnota skladu z view {@code v_stock_valuation} (0 u prázdného skladu). */
    BigDecimal sumStockValue();

    /** Počet příjemek čekajících na kontrolu (draft PENDING_REVIEW). */
    int countPendingReceipts();

    /** Otevřená (OPEN) inventura, nebo prázdno — jen jedna může běžet. */
    Optional<DashboardDto.OpenStockTake> findOpenStockTake();

    // --- Vozidla — STK ------------------------------------------------

    /**
     * Aktivní vozidla s končící (≤ 30 dní) nebo propadlou STK, propadlé a
     * nejbližší první. Vrací celý (malý) výběr — count i preview si odvodí service.
     */
    List<DashboardDto.VehiclePreview> findStkExpiring();
}
