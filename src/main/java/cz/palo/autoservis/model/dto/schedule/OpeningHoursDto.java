package cz.palo.autoservis.model.dto.schedule;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalTime;
import java.util.List;

/**
 * Otevírací doba dílny — namespace DTO (konvence R-12).
 *
 * <p>Rozvrh se čte i ukládá <strong>vždy celý týden naráz</strong>. Kdyby šly dny ukládat
 * jednotlivě, dala by se evidence nechat v půlce — třeba s pondělím podle nového rozvrhu
 * a úterým podle starého — a nikdo by nepoznal, který stav je ten platný.
 */
public final class OpeningHoursDto {

    private OpeningHoursDto() {
    }

    /** Jeden den rozvrhu. Oba časy {@code null} = zavřeno celý den. */
    @Data
    public static class Day {

        /** 1 = pondělí … 7 = neděle (ISO-8601). */
        @NotNull(message = "Den v týdnu je povinný.")
        @Min(value = 1, message = "Den v týdnu musí být 1 až 7.")
        @Max(value = 7, message = "Den v týdnu musí být 1 až 7.")
        private Integer dayOfWeek;

        private LocalTime opensAt;
        private LocalTime closesAt;
    }

    /** Odpověď — rozvrh i přepínač pohromadě, aby si je klient nemusel skládat ze dvou dotazů. */
    @Data
    public static class Response {
        private boolean   openingHoursEnabled;
        private List<Day> days;
    }

    /** Požadavek na uložení — týž tvar jako odpověď. */
    @Data
    public static class UpdateRequest {

        @NotNull(message = "Zapnutí hlídání otevírací doby je povinné.")
        private Boolean openingHoursEnabled;

        @NotEmpty(message = "Rozvrh musí obsahovat všech sedm dnů.")
        @Valid
        private List<Day> days;
    }
}
