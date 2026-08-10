package cz.palo.autoservis.controller;

import cz.palo.autoservis.model.dto.schedule.OpeningHoursDto;
import cz.palo.autoservis.service.OpeningHoursService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Otevírací doba dílny — nastavení, na které se ohlíží plánovací kalendář. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/{version}/opening-hours")
public class OpeningHoursController {

    private final OpeningHoursService openingHoursService;

    /**
     * Čtení má baseline oprávnění {@code /api/**} — rozvrh potřebuje každý, kdo otevře kalendář,
     * aby se zavřené dny daly ztlumit a formulář uměl napovědět.
     */
    @GetMapping
    public ResponseEntity<OpeningHoursDto.Response> get() {
        return ResponseEntity.ok(openingHoursService.get());
    }

    /**
     * Ukládá se celý týden naráz.
     *
     * <p>Zápis jen pro vedení — stejně jako blokace dílny (§19 konvencí): kdy má servis otevřeno,
     * není rozhodnutí mechanika.
     */
    @PutMapping
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    public ResponseEntity<OpeningHoursDto.Response> update(
            @Valid @RequestBody OpeningHoursDto.UpdateRequest request) {
        return ResponseEntity.ok(openingHoursService.update(request));
    }
}
