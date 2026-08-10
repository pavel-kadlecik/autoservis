package cz.palo.autoservis.model.converter;

import cz.palo.autoservis.model.domain.vehicle.MileageHistory;
import cz.palo.autoservis.model.dto.vehicle.MileageDto;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Konvertor mezi doménovými objekty {@link MileageHistory} a DTO {@link MileageDto}.
 */
@Component
public class MileageConverter {

    /**
     * Sestaví doménový odečet z create requestu. Default {@code recordedDate}
     * (při null) a auditní pole {@code createdBy} doplňuje service vrstva.
     *
     * @param vehicleId ID vozidla, kterému odečet patří
     * @param request   zvalidovaný create request
     * @return doménový objekt připravený k INSERTu, nebo {@code null} při {@code null} requestu
     */
    public MileageHistory toDomain(Long vehicleId, MileageDto.CreateRequest request) {
        if (request == null) {
            return null;
        }
        MileageHistory record = new MileageHistory();
        record.setVehicleId(vehicleId);
        record.setMileageKm(request.getMileageKm());
        record.setRecordedDate(request.getRecordedDate());
        record.setSource(request.getSource());
        record.setNote(request.getNote());
        return record;
    }

    /**
     * Aplikuje editovatelná pole update requestu na existující odečet.
     * Vlastnícího {@code vehicleId} ani auditních polí se nedotýká.
     *
     * @param existing odečet načtený z databáze
     * @param request  zvalidovaný update request
     * @return změněný odečet, nebo {@code null}, je-li kterýkoli argument {@code null}
     */
    public MileageHistory applyUpdate(MileageHistory existing, MileageDto.UpdateRequest request) {
        if (existing == null || request == null) {
            return null;
        }
        existing.setMileageKm(request.getMileageKm());
        existing.setRecordedDate(request.getRecordedDate());
        existing.setSource(request.getSource());
        existing.setNote(request.getNote());
        return existing;
    }

    /**
     * Převede odečet na jeho response DTO.
     *
     * @param record doménový odečet
     * @return response DTO, nebo {@code null} při {@code null} vstupu
     */
    public MileageDto.Response toResponse(MileageHistory record) {
        if (record == null) {
            return null;
        }
        MileageDto.Response response = new MileageDto.Response();
        response.setId(record.getId());
        response.setVehicleId(record.getVehicleId());
        response.setMileageKm(record.getMileageKm());
        response.setRecordedDate(record.getRecordedDate());
        response.setSource(record.getSource());
        response.setNote(record.getNote());
        response.setCreatedAt(record.getCreatedAt());
        response.setCreatedBy(record.getCreatedBy());
        return response;
    }

    /**
     * Převede seznam odečtů na response DTO.
     *
     * @param records seznam doménových odečtů
     * @return seznam response DTO
     */
    public List<MileageDto.Response> toResponses(List<MileageHistory> records) {
        return records.stream().map(this::toResponse).collect(Collectors.toList());
    }
}
