package cz.palo.autoservis.mapper;

import cz.palo.autoservis.model.domain.vehicle.RegistrySnapshot;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Optional;

/**
 * MyBatis mapper tabulky {@code vehicle.registry_snapshots}.
 *
 * <p>Konvence:
 * <ul>
 *   <li>Veškeré SQL žije v {@code mapper/RegistrySnapshotMapper.xml}.</li>
 *   <li>Append-only deník: žádný update/delete — nový dotaz do registru znamená
 *       nový řádek. Denormalizovanou cache {@code vehicle.vehicles.stk_valid_until}
 *       synchronizuje DB trigger {@code trg_registry_snapshots_sync_stk}.</li>
 * </ul>
 */
@Mapper
public interface RegistrySnapshotMapper {

    /**
     * Vloží nový snapshot registru. DB trigger poté obnoví
     * {@code vehicle.vehicles.stk_valid_until} z nejnovějšího snapshotu.
     *
     * @param snapshot snapshot k vložení (id se generuje)
     */
    void insert(RegistrySnapshot snapshot);

    /**
     * Najde jeden snapshot podle jeho ID.
     *
     * @param id ID snapshotu
     * @return snapshot v {@link Optional}, nebo prázdný, když nebyl nalezen
     */
    Optional<RegistrySnapshot> findById(@Param("id") Long id);

    /**
     * Vrací všechny snapshoty vozidla, nejnovější první
     * (podle {@code fetched_at} sestupně, při shodě {@code id} sestupně).
     *
     * @param vehicleId ID vozidla
     * @return seznam snapshotů (může být prázdný)
     */
    List<RegistrySnapshot> findByVehicleId(@Param("vehicleId") Long vehicleId);
}
