package cz.palo.autoservis.model.converter;

import cz.palo.autoservis.model.domain.schedule.Appointment;
import cz.palo.autoservis.model.dto.schedule.AppointmentDto;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class AppointmentConverter {

    public Appointment toDomain(AppointmentDto.CreateRequest request) {

        if(request == null) {
            return null;
        }

        Appointment appointment = new Appointment();
        appointment.setEntryType(request.getEntryType());
        appointment.setTitle(request.getTitle());
        appointment.setNote(request.getNote());
        appointment.setStartsAt(request.getStartsAt());
        appointment.setEndsAt(request.getEndsAt());
        appointment.setCustomerId(request.getCustomerId());
        appointment.setContactNote(blankToNull(request.getContactNote()));
        appointment.setVehicleId(request.getVehicleId());
        appointment.setEmployeeId(request.getEmployeeId());
        return appointment;
    }

    public Appointment applyUpdate(Appointment existing, AppointmentDto.UpdateRequest request) {

        if(request == null) {
            return existing;
        }

        existing.setTitle(request.getTitle());
        existing.setNote(request.getNote());
        existing.setStartsAt(request.getStartsAt());
        existing.setEndsAt(request.getEndsAt());
        existing.setCustomerId(request.getCustomerId());
        existing.setContactNote(blankToNull(request.getContactNote()));
        existing.setVehicleId(request.getVehicleId());
        existing.setEmployeeId(request.getEmployeeId());
        return existing;
    }

    /**
     * Prázdný řetězec z formuláře je „nevyplněno", ne hodnota. Bez převodu na {@code null} by
     * {@code chk_appointments_contact_booking_only} spadl u blokace dílny, která pole nezobrazuje,
     * ale prázdné ho pošle.
     */
    private String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value;
    }

    public AppointmentDto.DetailResponse toDetailResponse(Appointment appointment) {
        if(appointment == null) {
            return null;
        }

        AppointmentDto.DetailResponse response = new AppointmentDto.DetailResponse();
        response.setId(appointment.getId());
        response.setEntryType(appointment.getEntryType());
        response.setTitle(appointment.getTitle());
        response.setNote(appointment.getNote());
        response.setStartsAt(appointment.getStartsAt());
        response.setEndsAt(appointment.getEndsAt());
        response.setCustomerId(appointment.getCustomerId());
        response.setCustomerDisplayName(appointment.getCustomerDisplayName());
        response.setContactNote(appointment.getContactNote());
        response.setVehicleId(appointment.getVehicleId());
        response.setVehicleLicensePlate(appointment.getVehicleLicensePlate());
        response.setVehicleBrand(appointment.getVehicleBrand());
        response.setVehicleModel(appointment.getVehicleModel());
        response.setVehicleVin(appointment.getVehicleVin());
        response.setOrderId(appointment.getOrderId());
        response.setOrderNumber(appointment.getOrderNumber());
        response.setEmployeeId(appointment.getEmployeeId());
        response.setEmployeeDisplayName(appointment.getEmployeeDisplayName());
        response.setStatus(appointment.getStatus());
        response.setCreatedAt(appointment.getCreatedAt());
        response.setUpdatedAt(appointment.getUpdatedAt());

        return response;
    }

    public List<AppointmentDto.ListResponse> toListResponses(List<Appointment> appointments) {
        return appointments.stream().map(this::toListResponse).toList();
    }

    private AppointmentDto.ListResponse toListResponse(Appointment appointment) {
        if(appointment == null) {
            return null;
        }

        AppointmentDto.ListResponse response = new AppointmentDto.ListResponse();
        response.setId(appointment.getId());
        response.setEntryType(appointment.getEntryType());
        response.setTitle(appointment.getTitle());
        response.setStartsAt(appointment.getStartsAt());
        response.setEndsAt(appointment.getEndsAt());
        response.setStatus(appointment.getStatus());
        response.setCustomerDisplayName(appointment.getCustomerDisplayName());
        response.setContactNote(appointment.getContactNote());
        response.setVehicleLicensePlate(appointment.getVehicleLicensePlate());
        response.setVehicleBrand(appointment.getVehicleBrand());
        response.setVehicleModel(appointment.getVehicleModel());
        response.setEmployeeDisplayName(appointment.getEmployeeDisplayName());
        response.setOrderId(appointment.getOrderId());

        return response;
    }

}
