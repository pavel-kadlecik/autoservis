package cz.palo.autoservis.model.converter;

import cz.palo.autoservis.model.domain.vehicle.Vehicle;
import cz.palo.autoservis.model.dto.vehicle.VehicleDto;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Konvertor mezi doménovými objekty {@link Vehicle} a DTO {@link VehicleDto}.
 */
@Component
public class VehicleConverter {

    /**
     * Převede {@link Vehicle} na plné {@link VehicleDto.DetailResponse}.
     *
     * <p>Pozn.: {@code customerId} je v odpovědi vedle plného souhrnu {@code customer},
     * aby si formulář na frontendu nemusel hodnotu dohledávat zvlášť.
     *
     * @param vehicle doménový objekt k převodu
     * @return detailové response DTO, nebo {@code null} při {@code null} vstupu
     */
    public VehicleDto.DetailResponse toDetailResponse(Vehicle vehicle) {
        if (vehicle == null) {
            return null;
        }

        VehicleDto.DetailResponse response = new VehicleDto.DetailResponse();

        response.setId(vehicle.getId());
        response.setVin(vehicle.getVin());
        response.setMachineSerialNumber(vehicle.getMachineSerialNumber());
        response.setLicensePlate(vehicle.getLicensePlate());

        response.setCustomerId(vehicle.getCustomerId());
        if (vehicle.getCustomer() != null) {
            response.setCustomer(vehicle.getCustomer().toSummaryResponse());
        }

        response.setBrand(vehicle.getBrand());
        response.setModel(vehicle.getModel());

        response.setYearOfManufacture(vehicle.getYearOfManufacture());
        response.setFirstRegistrationDate(vehicle.getFirstRegistrationDate());

        response.setFuelType(vehicle.getFuelType());
        response.setTransmission(vehicle.getTransmission());
        response.setEngineCode(vehicle.getEngineCode());
        response.setEngineDisplacementCcm(vehicle.getEngineDisplacementCcm());
        response.setEnginePowerKw(vehicle.getEnginePowerKw());
        response.setColor(vehicle.getColor());

        response.setCurrentMileageKm(vehicle.getCurrentMileageKm());
        response.setStkValidUntil(vehicle.getStkValidUntil());
        response.setWheels(vehicle.getWheels());
        response.setInternalNote(vehicle.getInternalNote());
        response.setActive(vehicle.isActive());

        response.setCreatedAt(vehicle.getCreatedAt());
        response.setUpdatedAt(vehicle.getUpdatedAt());

        return response;
    }

    /**
     * Převede {@link VehicleDto.CreateRequest} na doménový objekt {@link Vehicle}.
     * Auditní pole ({@code createdBy}) ani pole spravovaná DB (časová razítka)
     * se tady nenastavují. {@code isActive} má u nových vozidel výchozí {@code true}.
     *
     * @param createRequest zvalidované create request DTO
     * @return doménový objekt připravený k INSERTu, nebo {@code null} při {@code null} vstupu
     */
    public Vehicle toDomain(VehicleDto.CreateRequest createRequest) {
        if (createRequest == null) {
            return null;
        }
        Vehicle vehicle = new Vehicle();

        vehicle.setActive(true);
        vehicle.setCustomerId(createRequest.getCustomerId());
        vehicle.setVin(blankToNull(createRequest.getVin()));
        vehicle.setMachineSerialNumber(blankToNull(createRequest.getMachineSerialNumber()));
        vehicle.setLicensePlate(blankToNull(createRequest.getLicensePlate()));
        vehicle.setBrand(createRequest.getBrand());
        vehicle.setModel(createRequest.getModel());
        vehicle.setYearOfManufacture(createRequest.getYearOfManufacture());
        vehicle.setFirstRegistrationDate(createRequest.getFirstRegistrationDate());
        vehicle.setFuelType(createRequest.getFuelType());
        vehicle.setTransmission(createRequest.getTransmission());
        vehicle.setEngineCode(blankToNull(createRequest.getEngineCode()));
        vehicle.setEngineDisplacementCcm(createRequest.getEngineDisplacementCcm());
        vehicle.setEnginePowerKw(createRequest.getEnginePowerKw());
        vehicle.setColor(blankToNull(createRequest.getColor()));
        vehicle.setInternalNote(blankToNull(createRequest.getInternalNote()));

        return vehicle;
    }

    /**
     * Aplikuje pole z {@link VehicleDto.UpdateRequest} na existující {@link Vehicle}.
     * Existující objekt se mění na místě a vrací.
     * Polí {@code id}, {@code isActive} a auditních polí se nedotýká.
     *
     * @param existingVehicle vozidlo načtené z databáze
     * @param updateRequest   zvalidované update request DTO
     * @return upravený doménový objekt, nebo {@code null}, je-li kterýkoli argument {@code null}
     */
    public Vehicle applyUpdate(Vehicle existingVehicle, VehicleDto.UpdateRequest updateRequest) {
        if (updateRequest == null || existingVehicle == null) {
            return null;
        }

        existingVehicle.setCustomerId(updateRequest.getCustomerId());
        existingVehicle.setVin(blankToNull(updateRequest.getVin()));
        existingVehicle.setMachineSerialNumber(blankToNull(updateRequest.getMachineSerialNumber()));
        existingVehicle.setLicensePlate(blankToNull(updateRequest.getLicensePlate()));
        existingVehicle.setBrand(updateRequest.getBrand());
        existingVehicle.setModel(updateRequest.getModel());
        existingVehicle.setYearOfManufacture(updateRequest.getYearOfManufacture());
        existingVehicle.setFirstRegistrationDate(updateRequest.getFirstRegistrationDate());
        existingVehicle.setFuelType(updateRequest.getFuelType());
        existingVehicle.setTransmission(updateRequest.getTransmission());
        existingVehicle.setEngineCode(blankToNull(updateRequest.getEngineCode()));
        existingVehicle.setEngineDisplacementCcm(updateRequest.getEngineDisplacementCcm());
        existingVehicle.setEnginePowerKw(updateRequest.getEnginePowerKw());
        existingVehicle.setColor(blankToNull(updateRequest.getColor()));
        existingVehicle.setInternalNote(blankToNull(updateRequest.getInternalNote()));

        return existingVehicle;
    }

    /**
     * Nevyplněná volitelná textová pole normalizuje na {@code null} — frontend je
     * posílá jako prázdné řetězce, ale DB stojí na NULL sémantice:
     * {@code chk_vehicles_engine_code_not_blank} (V19) prázdný řetězec odmítá (V81).
     */
    private String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value;
    }

    /**
     * Převede seznam doménových objektů {@link Vehicle} na seznam {@link VehicleDto.ListResponse}.
     *
     * @param vehicles seznam doménových objektů
     * @return seznam seznamových response DTO
     */
    public List<VehicleDto.ListResponse> toListResponses(List<Vehicle> vehicles) {
        return vehicles.stream().map(this::toListResponse).collect(Collectors.toList());
    }

    private VehicleDto.ListResponse toListResponse(Vehicle vehicle) {
        if (vehicle == null) {
            return null;
        }

        VehicleDto.ListResponse response = new VehicleDto.ListResponse();

        response.setId(vehicle.getId());
        response.setVin(vehicle.getVin());
        response.setMachineSerialNumber(vehicle.getMachineSerialNumber());
        response.setLicensePlate(vehicle.getLicensePlate());
        response.setBrand(vehicle.getBrand());
        response.setModel(vehicle.getModel());
        response.setYearOfManufacture(vehicle.getYearOfManufacture());
        response.setFuelType(vehicle.getFuelType());
        response.setColor(vehicle.getColor());
        response.setCurrentMileageKm(vehicle.getCurrentMileageKm());
        response.setStkValidUntil(vehicle.getStkValidUntil());
        response.setActive(vehicle.isActive());
        response.setCreatedAt(vehicle.getCreatedAt());
        response.setCustomerId(vehicle.getCustomerId());
        if (vehicle.getCustomer() != null) {
            response.setCustomer(vehicle.getCustomer().toSummaryResponse());
            response.setCustomerDisplayName(vehicle.getCustomer().getDisplayName());
        }

        return response;
    }

    /**
     * Převede doménový objekt {@link Vehicle} na {@link VehicleDto.SummaryResponse}.
     *
     * @param vehicle doménový objekt vozidla k převodu; může být {@code null}
     * @return souhrnné response DTO vozidla, nebo {@code null} při {@code null} vstupu
     */
    public VehicleDto.SummaryResponse toSummaryResponse(Vehicle vehicle) {

        if (vehicle == null) {
            return null;
        }

        return new VehicleDto.SummaryResponse(
                vehicle.getId(),
                vehicle.getVin(),
                vehicle.getMachineSerialNumber(),
                vehicle.getLicensePlate(),
                vehicle.getBrand(),
                vehicle.getModel(),
                vehicle.getYearOfManufacture(),
                vehicle.getCurrentMileageKm(),
                vehicle.isActive()

        );
    }
}
