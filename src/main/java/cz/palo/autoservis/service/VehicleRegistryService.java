package cz.palo.autoservis.service;

import cz.palo.autoservis.model.dto.registry.RegistryDto;

import java.util.List;

/**
 * Doménové operace nad státním registrem vozidel (dataovozidlech.cz).
 *
 * <p>Dvě sémantiky volání:
 * <ul>
 *   <li><b>přísná</b> — {@link #lookup} a {@link #refreshForVehicle} propagují
 *       selhání registru ({@code RegistryUnavailableException} → 503)
 *       i „v registru není" ({@code BusinessRuleException} → 422) volajícímu;</li>
 *   <li><b>best-effort</b> — {@link #tryRefreshAfterCreate} obojí spolkne a jen
 *       zaloguje, takže založení vozidla kvůli registru nikdy neselže.</li>
 * </ul>
 */
public interface VehicleRegistryService {

    /**
     * Dotáže se registru bez ukládání čehokoli — zdroj dat pro předvyplnění
     * formuláře vozidla. Parametry se kombinují jako AND; alespoň jeden je povinný.
     *
     * @param vin 17znakový VIN, nebo {@code null}
     * @param tp  číslo technického průkazu, nebo {@code null}
     * @param orv číslo osvědčení o registraci, nebo {@code null}
     * @return namapovaná data registru včetně VIN (aby hledání podle ORV/TP
     *         mohlo předvyplnit i pole VIN)
     * @throws cz.palo.autoservis.exception.BusinessRuleException
     *         {@code MISSING_LOOKUP_PARAM}, když jsou všechny parametry prázdné,
     *         {@code INVALID_VIN} při vadném VIN,
     *         {@code VEHICLE_NOT_IN_REGISTRY}, když registr vozidlo nezná
     * @throws cz.palo.autoservis.exception.RegistryUnavailableException
     *         když se registru nelze dotázat
     */
    RegistryDto.LookupResponse lookup(String vin, String tp, String orv);

    /**
     * Stáhne stav registru pro existující vozidlo (podle jeho VIN) a uloží nový
     * snapshot. DB trigger poté obnoví {@code vehicles.stk_valid_until}.
     *
     * @param vehicleId ID vozidla (neaktivní vozidla povolena — administrativní přístup)
     * @param userId    přihlášený uživatel pro auditní stopu
     * @return uložený snapshot
     * @throws cz.palo.autoservis.exception.ResourceNotFoundException neznámé vozidlo
     * @throws cz.palo.autoservis.exception.BusinessRuleException
     *         {@code VEHICLE_NOT_IN_REGISTRY}, když registr vozidlo nezná
     * @throws cz.palo.autoservis.exception.RegistryUnavailableException
     *         když se registru nelze dotázat
     */
    RegistryDto.SnapshotResponse refreshForVehicle(Long vehicleId, Long userId);

    /**
     * Best-effort varianta {@link #refreshForVehicle} pro flow zakládání vozidla:
     * jakékoli selhání registru i výsledek „v registru není" se zaloguje
     * a spolkne. Volat mimo transakci založení.
     *
     * @param vehicleId ID nově založeného vozidla
     * @param userId    přihlášený uživatel pro auditní stopu
     */
    void tryRefreshAfterCreate(Long vehicleId, Long userId);

    /**
     * Vrací všechny uložené snapshoty vozidla, nejnovější první.
     *
     * @param vehicleId ID vozidla
     * @return snapshoty (může být prázdný seznam)
     */
    List<RegistryDto.SnapshotResponse> findSnapshotsByVehicleId(Long vehicleId);
}
