package cz.palo.autoservis.service.impl;

import cz.palo.autoservis.exception.BusinessRuleException;
import cz.palo.autoservis.exception.ResourceNotFoundException;
import cz.palo.autoservis.mapper.CustomerMapper;
import cz.palo.autoservis.mapper.MileageHistoryMapper;
import cz.palo.autoservis.mapper.VehicleMapper;
import cz.palo.autoservis.model.converter.VehicleConverter;
import cz.palo.autoservis.model.domain.vehicle.MileageHistory;
import cz.palo.autoservis.model.domain.vehicle.Vehicle;
import cz.palo.autoservis.model.dto.autocomplete.AutocompleteItem;
import cz.palo.autoservis.model.dto.autocomplete.AutocompleteResponse;
import cz.palo.autoservis.model.dto.pagination.PagedResponse;
import cz.palo.autoservis.model.dto.vehicle.VehicleAutocompleteParams;
import cz.palo.autoservis.model.dto.vehicle.VehicleDto;
import cz.palo.autoservis.model.dto.vehicle.VehicleSearchParams;
import cz.palo.autoservis.model.enums.MileageSource;
import cz.palo.autoservis.service.OrderService;
import cz.palo.autoservis.service.VehicleService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Implementace {@link VehicleService}.
 */
@Service
@RequiredArgsConstructor
public class VehicleServiceImpl implements VehicleService {

    private final VehicleMapper vehicleMapper;
    private final VehicleConverter vehicleConverter;
    private final CustomerMapper customerMapper;
    private final OrderService orderService;
    private final MileageHistoryMapper mileageHistoryMapper;

    /** {@inheritDoc} */
    @Override
    public PagedResponse<VehicleDto.ListResponse> getPage(VehicleSearchParams params) {
        List<Vehicle> vehicles = vehicleMapper.search(params);
        List<VehicleDto.ListResponse> listResponses = vehicleConverter.toListResponses(vehicles);
        long total = vehicleMapper.countSearch(params);
        return PagedResponse.of(listResponses, params.getPage(), params.getPageSize(), total);
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException  když je {@code id} null
     * @throws ResourceNotFoundException když aktivní vozidlo s daným ID neexistuje
     */
    @Override
    public VehicleDto.DetailResponse getById(Long id) {
        if (id == null) {
            throw new IllegalArgumentException("id nesmí být null");
        }
        return vehicleMapper.findById(id)
                .map(vehicleConverter::toDetailResponse)
                .orElseThrow(() -> new ResourceNotFoundException("Vozidlo", id));
    }

    /**
     * {@inheritDoc}
     *
     * <p>Zákazník bez vozidel dostane prázdný seznam — žádná výjimka. Doc tady dřív slibovala
     * {@code ResourceNotFoundException} (zkopírováno z {@code getById}), takže klient psaný podle
     * ní čekal 404, který nikdy nepřijde (audit 10/A-3).
     *
     * @throws IllegalArgumentException když je {@code id} null
     */
    @Override
    public List<VehicleDto.SummaryResponse> findByCustomerId(Long id) {
        if (id == null) {
            throw new IllegalArgumentException("id nesmí být null");
        }
        List<Vehicle> byCustomerId = vehicleMapper.findByCustomerId(id);
         return byCustomerId.stream().map(vehicleConverter::toSummaryResponse).toList();
    }

    /**
     * {@inheritDoc}
     *
     * @throws ResourceNotFoundException když zákazník neexistuje
     * @throws BusinessRuleException     když vozidlo s daným VIN už existuje
     * @throws BusinessRuleException     když je rok výroby po datu první registrace
     */
    @Override
    @Transactional
    public VehicleDto.DetailResponse create(VehicleDto.CreateRequest createRequest, Long userId) {
        Long customerId = createRequest.getCustomerId();
        String vin = createRequest.getVin();

        if (!customerMapper.existsById(customerId)) {
            throw new ResourceNotFoundException("Zákazník", customerId);
        }

        // Stroj bez VIN (V90) — kontrola duplicity nemá co porovnávat.
        if (vin != null && !vin.isBlank() && vehicleMapper.existsByVin(vin)) {
            throw new BusinessRuleException(
                    "DUPLICATE_VIN",
                    "vin",
                    "Vozidlo s VIN " + vin + " už existuje.",
                    Map.of("vin", vin));
        }

        validateYearAndRegistration(createRequest.getYearOfManufacture(), createRequest.getFirstRegistrationDate());

        Vehicle vehicle = vehicleConverter.toDomain(createRequest);
        vehicle.setCreatedBy(userId);
        vehicleMapper.insert(vehicle);

        Integer initialMileageKm = createRequest.getInitialMileageKm();
        if (initialMileageKm != null) {
            MileageHistory initialReading = MileageHistory.builder()
                    .vehicleId(vehicle.getId())
                    .mileageKm(initialMileageKm)
                    .recordedDate(LocalDate.now())
                    .source(MileageSource.INITIAL)
                    .note("Počáteční stav zaznamenaný při registraci vozidla")
                    .createdBy(userId)
                    .build();
            mileageHistoryMapper.insert(initialReading);
        }

        return getById(vehicle.getId());
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException  když je {@code id} null
     * @throws ResourceNotFoundException když vozidlo s daným ID neexistuje
     * @throws BusinessRuleException     když nový VIN už používá jiné vozidlo
     * @throws ResourceNotFoundException když nové ID zákazníka neexistuje
     */
    @Override
    @Transactional
    public VehicleDto.DetailResponse update(Long id, VehicleDto.UpdateRequest updateRequest, Long userId) {
        if (id == null) {
            throw new IllegalArgumentException("id nesmí být null");
        }
        Vehicle vehicle = vehicleMapper.findByIdIncludingInactive(id)
                .orElseThrow(() -> new ResourceNotFoundException("Vozidlo", id));

        String newVin = updateRequest.getVin();
        // Stroj bez VIN (V90) — kontrola duplicity nemá co porovnávat.
        if (newVin != null && !newVin.isBlank()
                && !newVin.equals(vehicle.getVin()) && vehicleMapper.existsByVin(newVin)) {
            throw new BusinessRuleException(
                    "DUPLICATE_VIN",
                    "vin",
                    "Vozidlo s VIN " + newVin + " už existuje.",
                    Map.of("vin", newVin));
        }

        validateYearAndRegistration(updateRequest.getYearOfManufacture(), updateRequest.getFirstRegistrationDate());

        Long newCustomerId = updateRequest.getCustomerId();
        // Současný majitel z přímého FK pole (vehicle.customerId), NE z vnořeného customer —
        // findByIdIncludingInactive zákazníka nejoinuje, takže getCustomer() je null (TD-56).
        if (!newCustomerId.equals(vehicle.getCustomerId()) && !customerMapper.existsById(newCustomerId)) {
            throw new ResourceNotFoundException("Zákazník", newCustomerId);
        }

        Vehicle updatedVehicle = vehicleConverter.applyUpdate(vehicle, updateRequest);
        int affectedRows = vehicleMapper.update(updatedVehicle);
        return verifyAndFetchAfterUpdate(id, affectedRows);
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException  když je {@code id} null
     * @throws ResourceNotFoundException když vozidlo s daným ID neexistuje
     */
    @Override
    @Transactional
    public VehicleDto.DetailResponse deactivate(Long id) {
        if (id == null) {
            throw new IllegalArgumentException("id nesmí být null");
        }

        int openOrders = orderService.countOpenByVehicleId(id);

        if(openOrders > 0) {
            throw new BusinessRuleException(
                    "VEHICLE_HAS_OPEN_ORDERS",
                    null,
                    "Vozidlo má " + openOrders + " otevřených zakázek, proto ho nelze deaktivovat.",
                    Map.of("openOrders", openOrders));
        }

        int affectedRows = vehicleMapper.deactivate(id);
        return verifyAndFetchAfterStatusChange(id, affectedRows);
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException když je {@code customerId} null
     */
    @Override
    public int deactivateByCustomerId(Long customerId) {
        if (customerId == null) {
            throw new IllegalArgumentException("customerId nesmí být null");
        }
        return vehicleMapper.deactivateByCustomerId(customerId);
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException  když je {@code id} null
     * @throws ResourceNotFoundException když vozidlo s daným ID neexistuje
     */
    @Override
    public VehicleDto.DetailResponse activate(Long id) {
        if (id == null) {
            throw new IllegalArgumentException("id nesmí být null");
        }
        int affectedRows = vehicleMapper.activate(id);
        return verifyAndFetchAfterStatusChange(id, affectedRows);
    }

    /** {@inheritDoc} */
    @Override
    public AutocompleteResponse autocomplete(VehicleAutocompleteParams params) {
        List<AutocompleteItem> items = vehicleMapper.autocomplete(params);
        int effectiveLimit = params.effectiveLimit();
        boolean hasMore = items.size() > effectiveLimit;

        AutocompleteResponse response = new AutocompleteResponse();
        response.setData(items.subList(0, Math.min(items.isEmpty() ? 0 : hasMore ? items.size() - 1 : items.size(), effectiveLimit)));
        response.setHasMore(hasMore);
        return response;
    }

    // =========================================================================
    // Privátní pomocné metody
    // =========================================================================

    /**
     * Validuje, že rok výroby nepřesahuje rok první registrace.
     *
     * @throws BusinessRuleException když je rok výroby po roce první registrace
     */
    private void validateYearAndRegistration(Short yearOfManufacture, LocalDate firstRegistrationDate) {
        if (firstRegistrationDate != null && yearOfManufacture != null) {
            int registrationYear = firstRegistrationDate.getYear();
            if ((int) yearOfManufacture > registrationYear) {
                throw new BusinessRuleException(
                        "INVALID_YEAR_OF_MANUFACTURE",
                        "yearOfManufacture",
                        "Rok výroby " + yearOfManufacture + " nemůže být pozdější než rok první registrace " + registrationYear + ".",
                        Map.of("yearOfManufacture", yearOfManufacture));
            }
        }
    }

    private VehicleDto.DetailResponse verifyAndFetchAfterStatusChange(Long id, int affectedRows) {
        if (affectedRows == 0) {
            throw new ResourceNotFoundException("Vozidlo", id);
        }
        return fetchOrFail(id);
    }

    private VehicleDto.DetailResponse verifyAndFetchAfterUpdate(Long id, int affectedRows) {
        if (affectedRows == 0) {
            throw new IllegalStateException("Vozidlo " + id + " zmizelo během aktualizace");
        }
        return fetchOrFail(id);
    }

    private VehicleDto.DetailResponse fetchOrFail(Long id) {
        return vehicleMapper.findByIdIncludingInactive(id)
                .map(vehicleConverter::toDetailResponse)
                .orElseThrow(() -> new IllegalStateException(
                        "Vozidlo " + id + " zmizelo mezi UPDATE a SELECT"));
    }
}
