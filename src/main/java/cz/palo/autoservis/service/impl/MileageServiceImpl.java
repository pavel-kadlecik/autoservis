package cz.palo.autoservis.service.impl;

import cz.palo.autoservis.exception.BusinessRuleException;
import cz.palo.autoservis.exception.ResourceNotFoundException;
import cz.palo.autoservis.mapper.MileageHistoryMapper;
import cz.palo.autoservis.mapper.VehicleMapper;
import cz.palo.autoservis.model.converter.MileageConverter;
import cz.palo.autoservis.model.domain.vehicle.MileageHistory;
import cz.palo.autoservis.model.dto.vehicle.MileageDto;
import cz.palo.autoservis.model.enums.MileageSource;
import cz.palo.autoservis.service.MileageService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Implementace {@link MileageService}.
 *
 * <p>Pravidla zdroje pro počáteční odečet INITIAL:
 * <ul>
 *   <li>Smí vzniknout jen jako úplně první odečet vozidla (viz {@link #addReading}).</li>
 *   <li>Je editovatelný, ale identitu INITIAL nelze přehodit na jiný odečet.</li>
 *   <li>Nelze ho smazat — kotví historii tachometru vozidla.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class MileageServiceImpl implements MileageService {

    private final MileageHistoryMapper mileageHistoryMapper;
    private final MileageConverter mileageConverter;
    private final VehicleMapper vehicleMapper;

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException  když je {@code vehicleId} null
     * @throws ResourceNotFoundException když vozidlo s daným ID neexistuje
     */
    @Override
    public List<MileageDto.Response> findByVehicleId(Long vehicleId) {
        if (vehicleId == null) {
            throw new IllegalArgumentException("vehicleId nesmí být null");
        }
        requireVehicleExists(vehicleId);
        return mileageConverter.toResponses(mileageHistoryMapper.findByVehicleId(vehicleId));
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException  když je {@code vehicleId} null
     * @throws ResourceNotFoundException když vozidlo s daným ID neexistuje
     * @throws BusinessRuleException     když je {@code source} INITIAL a vozidlo už odečty má
     */
    @Override
    @Transactional
    public MileageDto.Response addReading(Long vehicleId, MileageDto.CreateRequest request, Long userId) {
        return addIntakeReading(vehicleId, request, null, userId);
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException  když je {@code vehicleId} null
     * @throws ResourceNotFoundException když vozidlo s daným ID neexistuje
     * @throws BusinessRuleException     když je {@code source} INITIAL a vozidlo už odečty má
     */
    @Override
    @Transactional
    public MileageDto.Response addIntakeReading(Long vehicleId, MileageDto.CreateRequest request,
                                                Long orderId, Long userId) {
        if (vehicleId == null) {
            throw new IllegalArgumentException("vehicleId nesmí být null");
        }
        requireVehicleExists(vehicleId);

        // INITIAL označuje počáteční odečet a je povolený jen jako úplně první
        // odečet vozidla (ať vznikl při evidenci, nebo dodatečně). Jakmile jakýkoli
        // odečet existuje, INITIAL se zamkne — historie ho smí mít nejvýš jeden.
        if (request.getSource() == MileageSource.INITIAL
                && mileageHistoryMapper.existsByVehicleId(vehicleId)) {
            throw new BusinessRuleException(
                    "INVALID_MILEAGE_SOURCE",
                    "source",
                    "Počáteční stav tachometru lze zadat jen jako úplně první čtení vozidla.",
                    Map.of("source", MileageSource.INITIAL));
        }

        MileageHistory record = mileageConverter.toDomain(vehicleId, request);
        if (record.getRecordedDate() == null) {
            record.setRecordedDate(LocalDate.now());
        }
        record.setCreatedBy(userId);
        // Vazbu na zakázku dosazuje server, ne klient — do CreateRequest nepatří (týž
        // princip jako u auditních polí). Ručně zadaný odečet z karty vozu ji nemá (V84).
        record.setOrderId(orderId);

        mileageHistoryMapper.insert(record);
        return fetchOrFail(record.getId());
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException  když je {@code vehicleId} nebo {@code readingId} null
     * @throws ResourceNotFoundException když odečet neexistuje nebo nepatří vozidlu
     * @throws BusinessRuleException     když je {@code source} INITIAL u odečtu, který INITIAL už není
     */
    @Override
    @Transactional
    public MileageDto.Response updateReading(Long vehicleId, Long readingId,
                                             MileageDto.UpdateRequest request, Long userId) {
        if (vehicleId == null) {
            throw new IllegalArgumentException("vehicleId nesmí být null");
        }
        if (readingId == null) {
            throw new IllegalArgumentException("readingId nesmí být null");
        }
        MileageHistory existing = requireReadingOfVehicle(vehicleId, readingId);

        // INITIAL smí zůstat na odečtu, který už INITIAL je (počáteční zůstává
        // editovatelný), ale běžný odečet se na INITIAL přeznačit nedá.
        if (request.getSource() == MileageSource.INITIAL
                && existing.getSource() != MileageSource.INITIAL) {
            throw new BusinessRuleException(
                    "INVALID_MILEAGE_SOURCE",
                    "source",
                    "Počáteční stav tachometru může zůstat jen na stávajícím prvním čtení.",
                    Map.of("source", MileageSource.INITIAL));
        }

        MileageHistory updated = mileageConverter.applyUpdate(existing, request);
        if (updated.getRecordedDate() == null) {
            updated.setRecordedDate(LocalDate.now());
        }

        int affectedRows = mileageHistoryMapper.update(updated);
        if (affectedRows == 0) {
            throw new IllegalStateException("Čtení tachometru " + readingId + " zmizelo během aktualizace");
        }
        return fetchOrFail(readingId);
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException  když je {@code vehicleId} nebo {@code readingId} null
     * @throws ResourceNotFoundException když odečet neexistuje nebo nepatří vozidlu
     * @throws BusinessRuleException     když jde o počáteční odečet INITIAL (nelze smazat)
     */
    @Override
    @Transactional
    public void deleteReading(Long vehicleId, Long readingId) {
        if (vehicleId == null) {
            throw new IllegalArgumentException("vehicleId nesmí být null");
        }
        if (readingId == null) {
            throw new IllegalArgumentException("readingId nesmí být null");
        }
        MileageHistory existing = requireReadingOfVehicle(vehicleId, readingId);

        // Počáteční odečet INITIAL je editovatelný, ale smazat se nesmí — kotví
        // historii tachometru vozidla. Ostatní odečty odstranit lze.
        if (existing.getSource() == MileageSource.INITIAL) {
            throw new BusinessRuleException(
                    "CANNOT_DELETE_INITIAL",
                    null,
                    "Počáteční (výchozí) čtení tachometru nelze smazat.",
                    Map.of("readingId", readingId));
        }

        mileageHistoryMapper.delete(readingId);
    }

    // =========================================================================
    // Privátní pomocné metody
    // =========================================================================

    /**
     * Ověří, že vozidlo existuje (v libovolném stavu aktivnosti — historie je administrativní).
     *
     * @throws ResourceNotFoundException když vozidlo s daným ID neexistuje
     */
    private void requireVehicleExists(Long vehicleId) {
        if (vehicleMapper.findByIdIncludingInactive(vehicleId).isEmpty()) {
            throw new ResourceNotFoundException("Vozidlo", vehicleId);
        }
    }

    /**
     * Načte odečet a ověří, že patří danému vozidlu.
     * Odečet jiného vozidla se na této cestě bere jako nenalezený.
     *
     * @throws ResourceNotFoundException když neexistuje nebo patří jinému vozidlu
     */
    private MileageHistory requireReadingOfVehicle(Long vehicleId, Long readingId) {
        MileageHistory reading = mileageHistoryMapper.findById(readingId)
                .orElseThrow(() -> new ResourceNotFoundException("Čtení tachometru", readingId));
        if (!reading.getVehicleId().equals(vehicleId)) {
            throw new ResourceNotFoundException("Čtení tachometru", readingId);
        }
        return reading;
    }

    private MileageDto.Response fetchOrFail(Long readingId) {
        return mileageHistoryMapper.findById(readingId)
                .map(mileageConverter::toResponse)
                .orElseThrow(() -> new IllegalStateException(
                        "Čtení tachometru " + readingId + " zmizelo po zápisu"));
    }
}
