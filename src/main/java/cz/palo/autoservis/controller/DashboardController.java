package cz.palo.autoservis.controller;

import cz.palo.autoservis.model.dto.dashboard.DashboardDto;
import cz.palo.autoservis.service.DashboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller pro úvodní přehled (dashboard).
 *
 * <p>Base path: {@code /api/{version}/dashboard}
 */
@RestController
@RequestMapping("/api/{version}/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardService dashboardService;

    /**
     * Vrátí souhrn pro dashboard: rozpracované zakázky, zakázky po termínu,
     * faktury po splatnosti i koncepty, sklad pod minimem a jeho hodnotu,
     * příjemky ke kontrole, otevřenou inventuru, končící STK a měsíční tržby.
     *
     * @return 200 OK s agregovaným souhrnem
     */
    @GetMapping("/summary")
    public ResponseEntity<DashboardDto.Summary> getSummary() {
        return ResponseEntity.ok(dashboardService.getSummary());
    }

    /**
     * Měsíční statistika za zvolený rok: tržby (s DPH), marže (bez DPH),
     * počty zakázek a faktur. Počítá se živě z existujících dat.
     *
     * @param year rok filtru; bez parametru aktuální rok
     * @return 200 OK s měsíční řadou a seznamem roků s daty
     */
    @GetMapping("/statistics")
    public ResponseEntity<DashboardDto.Statistics> getStatistics(
            @RequestParam(required = false) Integer year) {
        return ResponseEntity.ok(dashboardService.getStatistics(year));
    }
}
