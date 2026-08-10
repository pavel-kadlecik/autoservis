import {getAppointmentStatusLabel} from "../api/format.js";

/**
 * Vysvětlivky barev pod kalendářem.
 *
 * <p>Vzorky <strong>nemají vlastní barvy</strong> — nosí tytéž třídy {@code fc-status-*} jako
 * objednávky v denních kartách a barvu si berou z proměnných, které ty třídy nastavují
 * ({@code css/schedule.css}). Legenda proto nemůže začít lhát: změna barvy objednávky ji
 * přebarví taky, protože jiná definice neexistuje.
 *
 */
const STATUSES = ["PLANNED", "CONVERTED", "NO_SHOW", "CANCELLED"];

export default function ScheduleLegend() {
    return (
        <div className="schedule-legend" aria-label="Vysvětlivky barev">
            <span className="schedule-legend-title">Vysvětlivky:</span>

            {STATUSES.map((status) => (
                <span className="schedule-legend-item" key={status}>
                    <span className={`schedule-legend-swatch fc-status-${status.toLowerCase()}`}
                          aria-hidden="true"></span>
                    {getAppointmentStatusLabel(status)}
                </span>
            ))}

            {/*
              Jen „Zavřeno", ne „Zavřeno (blokace dílny)". Od 2026-08-04 se legenda ukazuje
              i u měsíčního přehledu a tam nese šrafování OBA významy — blokaci dílny i den
              mimo otevírací dobu. Konkrétnější popisek by o víkendu lhal; který z důvodů to je,
              se čtenář dozví v týdnu (blokace má kartu s důvodem, zavřený den je jen ztlumený).
            */}
            <span className="schedule-legend-item">
                <span className="schedule-legend-swatch schedule-legend-closure" aria-hidden="true"></span>
                Zavřeno
            </span>

            {/* Událost (V82) se barví podle typu, ne stavu — vzorek nosí tutéž třídu jako řádky. */}
            <span className="schedule-legend-item">
                <span className="schedule-legend-swatch fc-type-event" aria-hidden="true"></span>
                Událost
            </span>
        </div>
    );
}
