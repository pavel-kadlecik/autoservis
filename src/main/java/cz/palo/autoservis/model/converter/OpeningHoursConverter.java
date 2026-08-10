package cz.palo.autoservis.model.converter;

import cz.palo.autoservis.model.domain.schedule.OpeningHours;
import cz.palo.autoservis.model.dto.schedule.OpeningHoursDto;
import org.springframework.stereotype.Component;

import java.util.List;

/** Otevírací doba: domain ↔ DTO. Ruční konvertor podle konvence R-11 (žádný MapStruct). */
@Component
public class OpeningHoursConverter {

    /** Rozvrh i přepínač do jedné odpovědi. */
    public OpeningHoursDto.Response toResponse(List<OpeningHours> days, boolean openingHoursEnabled) {
        OpeningHoursDto.Response response = new OpeningHoursDto.Response();
        response.setOpeningHoursEnabled(openingHoursEnabled);
        response.setDays(days.stream().map(this::toDay).toList());
        return response;
    }

    public OpeningHoursDto.Day toDay(OpeningHours domain) {
        if (domain == null) {
            return null;
        }
        OpeningHoursDto.Day day = new OpeningHoursDto.Day();
        day.setDayOfWeek(domain.getDayOfWeek());
        day.setOpensAt(domain.getOpensAt());
        day.setClosesAt(domain.getClosesAt());
        return day;
    }

    /**
     * DTO → domain. {@code updatedAt} se nepřenáší — ten určuje trigger v databázi,
     * ne klient (konvence R-07).
     */
    public OpeningHours toDomain(OpeningHoursDto.Day day) {
        if (day == null) {
            return null;
        }
        return OpeningHours.builder()
                .dayOfWeek(day.getDayOfWeek())
                .opensAt(day.getOpensAt())
                .closesAt(day.getClosesAt())
                .build();
    }
}
