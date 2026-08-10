package cz.palo.autoservis.service.impl;

import cz.palo.autoservis.client.VehicleRegistryClient;
import cz.palo.autoservis.exception.BusinessRuleException;
import cz.palo.autoservis.exception.RegistryUnavailableException;
import cz.palo.autoservis.exception.ResourceNotFoundException;
import cz.palo.autoservis.mapper.RegistrySnapshotMapper;
import cz.palo.autoservis.mapper.VehicleMapper;
import cz.palo.autoservis.model.converter.RegistryConverter;
import cz.palo.autoservis.model.domain.vehicle.RegistrySnapshot;
import cz.palo.autoservis.model.domain.vehicle.Vehicle;
import cz.palo.autoservis.model.dto.registry.RegistryDto;
import cz.palo.autoservis.model.dto.registry.RegistryFetchResult;
import cz.palo.autoservis.model.dto.registry.RegistryLookupParams;
import cz.palo.autoservis.service.VehicleRegistryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Výchozí implementace {@link VehicleRegistryService}.
 *
 * <p><b>Transakce:</b> metody tady záměrně NEJSOU {@code @Transactional} —
 * HTTP volání registru nesmí po dobu svého běhu držet DB spojení/transakci
 * jako rukojmí. Perzistence je jediný {@code INSERT} (sync trigger
 * stk_valid_until běží v transakci téhož příkazu), takže atomicita platí
 * i bez explicitní transakce. Kdyby do flow někdy přibyl druhý zápis,
 * vytáhni perzistenci do {@code @Transactional} metody samostatné
 * komponenty (ne self-invocation).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class VehicleRegistryServiceImpl implements VehicleRegistryService {

    private static final Pattern VIN_PATTERN = Pattern.compile("^[A-HJ-NPR-Z0-9]{17}$");

    /** Defenzivní strop pro vstupy TP/ORV — registr žádný formát nedokumentuje. */
    private static final int MAX_DOC_NUMBER_LENGTH = 30;

    private final VehicleRegistryClient registryClient;
    private final RegistryConverter registryConverter;
    private final RegistrySnapshotMapper registrySnapshotMapper;
    private final VehicleMapper vehicleMapper;

    @Override
    public RegistryDto.LookupResponse lookup(String vin, String tp, String orv) {
        RegistryLookupParams params = new RegistryLookupParams(trim(vin), trim(tp), trim(orv));
        validate(params);

        RegistryFetchResult result = registryClient.fetch(params)
                .orElseThrow(() -> new BusinessRuleException(
                        "VEHICLE_NOT_IN_REGISTRY",
                        "Vozidlo se zadanými údaji nebylo v registru nalezeno."));

        return registryConverter.toLookupResponse(result.data());
    }

    @Override
    public RegistryDto.SnapshotResponse refreshForVehicle(Long vehicleId, Long userId) {
        Vehicle vehicle = vehicleMapper.findByIdIncludingInactive(vehicleId)
                .orElseThrow(() -> new ResourceNotFoundException("Vozidlo", vehicleId));

        // Stroj bez VIN (V90) v registru není — bez guardu by dotaz odešel s null.
        if (vehicle.getVin() == null) {
            throw new BusinessRuleException(
                    "VEHICLE_HAS_NO_VIN",
                    "Vozidlo nemá VIN — z registru vozidel ho nelze načíst.");
        }

        RegistryFetchResult result = registryClient.fetch(RegistryLookupParams.ofVin(vehicle.getVin()))
                .orElseThrow(() -> new BusinessRuleException(
                        "VEHICLE_NOT_IN_REGISTRY",
                        "vin",
                        "Vozidlo s VIN " + vehicle.getVin() + " nebylo v registru nalezeno.",
                        Map.of("vin", vehicle.getVin())));

        RegistrySnapshot snapshot = registryConverter.toSnapshot(vehicleId, result, userId);
        registrySnapshotMapper.insert(snapshot);   // fills snapshot.id; DB trigger syncs stk_valid_until

        // Verify-and-fetch: vrátit řádek tak, jak ho DB uložila (default fetched_at).
        RegistrySnapshot stored = registrySnapshotMapper.findById(snapshot.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Záznam registru vozidel", snapshot.getId()));
        return registryConverter.toSnapshotResponse(stored);
    }

    @Override
    public void tryRefreshAfterCreate(Long vehicleId, Long userId) {
        try {
            refreshForVehicle(vehicleId, userId);
        } catch (RegistryUnavailableException | BusinessRuleException e) {
            log.warn("Registry refresh after vehicle {} creation skipped: {}", vehicleId, e.getMessage());
        }
    }

    @Override
    public List<RegistryDto.SnapshotResponse> findSnapshotsByVehicleId(Long vehicleId) {
        return registrySnapshotMapper.findByVehicleId(vehicleId).stream()
                .map(registryConverter::toSnapshotResponse)
                .toList();
    }

    private void validate(RegistryLookupParams params) {
        if (params.isEmpty()) {
            throw new BusinessRuleException(
                    "MISSING_LOOKUP_PARAM",
                    "Zadejte VIN, číslo technického průkazu, nebo číslo osvědčení o registraci.");
        }
        if (params.vin() != null && !VIN_PATTERN.matcher(params.vin()).matches()) {
            throw new BusinessRuleException(
                    "INVALID_VIN",
                    "vin",
                    "VIN musí mít přesně 17 znaků (A-Z bez I,O,Q a 0-9).",
                    Map.of("vin", params.vin()));
        }
        if (tooLong(params.tp()) || tooLong(params.orv())) {
            throw new BusinessRuleException(
                    "INVALID_LOOKUP_PARAM",
                    "Číslo dokladu je příliš dlouhé.");
        }
    }

    private static boolean tooLong(String s) {
        return s != null && s.length() > MAX_DOC_NUMBER_LENGTH;
    }

    private static String trim(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
