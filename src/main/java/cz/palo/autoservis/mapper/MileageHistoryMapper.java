package cz.palo.autoservis.mapper;

import cz.palo.autoservis.model.domain.vehicle.MileageHistory;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Optional;

/**
 * MyBatis mapper tabulky {@code vehicle.mileage_history}.
 *
 * <p>Konvence:
 * <ul>
 *   <li>Veškeré SQL žije v {@code mapper/MileageHistoryMapper.xml}.</li>
 *   <li>Tabulka je editovatelný deník (varianta A): odečty lze upravovat
 *       i mazat. Denormalizovanou cache {@code vehicle.vehicles.current_mileage_km}
 *       synchronizuje DB trigger {@code trg_mileage_history_sync_current}
 *       na INSERT/UPDATE/DELETE — aplikační kód ji nikdy nepřepočítává.</li>
 * </ul>
 */
@Mapper
public interface MileageHistoryMapper {

    /**
     * Vloží nový odečet tachometru. DB trigger poté obnoví
     * {@code vehicle.vehicles.current_mileage_km}, je-li odečet nejnovější.
     *
     * @param record odečet k vložení (id se generuje)
     */
    void insert(MileageHistory record);

    /**
     * Najde jeden odečet podle jeho ID.
     *
     * @param id ID odečtu
     * @return odečet v {@link Optional}, nebo prázdný, když nebyl nalezen
     */
    Optional<MileageHistory> findById(@Param("id") Long id);

    /**
     * Vrací všechny odečty vozidla, nejnovější první
     * (podle {@code recorded_date} sestupně, při shodě {@code id} sestupně).
     *
     * @param vehicleId ID vozidla
     * @return seznam odečtů (může být prázdný)
     */
    List<MileageHistory> findByVehicleId(@Param("vehicleId") Long vehicleId);

    /**
     * Aktualizuje editovatelná pole odečtu ({@code mileage_km},
     * {@code recorded_date}, {@code source}, {@code note}). Vlastnící
     * {@code vehicle_id} ani auditní pole se nikdy nemění.
     *
     * @param record odečet s novými hodnotami
     * @return počet ovlivněných řádků (0 = nenalezen, 1 = úspěch)
     */
    int update(MileageHistory record);

    /**
     * Trvale smaže odečet. DB trigger přepočítá cache vozidla
     * ze zbývajících odečtů.
     *
     * @param id ID odečtu
     * @return počet ovlivněných řádků (0 = nenalezen)
     */
    int delete(@Param("id") Long id);

    /**
     * Vrací, zda už vozidlo má alespoň jeden odečet.
     * Používá se k omezení zdroje INITIAL jen na úplně první odečet.
     *
     * @param vehicleId ID vozidla
     * @return {@code true}, pokud pro vozidlo existuje jakýkoli odečet
     */
    boolean existsByVehicleId(@Param("vehicleId") Long vehicleId);
}
