import {useDroppable} from "@dnd-kit/core";
import {dayKey, isSameMonth, isToday, monthGridDays, timeOfDay} from "../api/scheduleDates.js";
import {
    appointmentPersonName,
    appointmentTooltip,
    canDragOccurrence,
    closureTimeText,
    DraggableOccurrence,
} from "./ScheduleDayCard.jsx";
import {isClosedDay} from "../api/openingHours.js";
import {getAppointmentToneClass} from "../api/format.js";

const WEEKDAY_HEADS = ["po", "út", "st", "čt", "pá", "so", "ne"];

/**
 * Max řádků objednávek v buňce — víc se do měsíční mřížky nevejde, zbytek shrne „+N další".
 * Přesný seznam je po kliknutí v týdnu, takže se tu nic neztrácí.
 */
const MAX_ROWS = 3;

/**
 * Měsíční přehled — „mini seznam" ve stylu Google (rozhodnutí uživatele 2026-08-03).
 *
 * <p>Buňka ukáže až {@link MAX_ROWS} řádků „čas jméno" obarvených stavem; vícedenní položky
 * chodí z {@code groupOccurrences}, takže pokračování se ukáže ztlumeně s „→". Klik kamkoli
 * do buňky přepne na týden toho dne — řádky jsou proto {@code <span>}, ne tlačítka
 * (v buňce-tlačítku nesmí být vnořená tlačítka) a „+N další" je jen text, klik řeší buňka.
 *
 * @param {Date}     monthStart  první den zobrazeného měsíce
 * @param {Map}      occurrences výskyty podle klíče dne (z {@code groupOccurrences})
 * @param {Function} onPickDay   (date) => void — přepne na týden s tímto dnem
 */
/**
 * Buňka dne jako cíl upuštění.
 *
 * <p>Přijímá <strong>obalový {@code <span>}</strong>, ne samotná buňka: buňka je {@code <button>}
 * a ten musí zůstat nedotčený, aby si podržel průchod tabulátorem, spuštění Enterem i popisek
 * pro čtečku. Obal se v mřížce chová jako původní buňka (viz `.schedule-month-drop` v CSS).
 */
function MonthDayCell({day, blocked, children}) {
    const {setNodeRef, isOver} = useDroppable({id: `month-${dayKey(day)}`, data: {date: day}});

    return (
        <span ref={setNodeRef}
              className={`schedule-month-drop${
                  isOver ? (blocked ? " is-drop-blocked" : " is-drop-target") : ""}`}>
            {children}
        </span>
    );
}

export default function ScheduleMonth({monthStart, occurrences, onPickDay, openingHours = null,
                                          canManageClosures = false, blockedDays = new Set()}) {
    return (
        <div className="schedule-month">
            {WEEKDAY_HEADS.map((name) => (
                <span key={name} className="schedule-month-head">{name}</span>
            ))}

            {monthGridDays(monthStart).map((day) => {
                const dayOccurrences = occurrences.get(dayKey(day)) ?? [];
                /*
                  Zavřeno má dva zdroje: blokaci dílny (jednorázová, má důvod) a otevírací dobu
                  (pravidelná, třeba neděle). Pro měsíční přehled je to totéž — buňka nese jen
                  hustotu, důvod se dočte v týdnu.
                */
                const closed = isClosedDay(openingHours, day)
                    || dayOccurrences.some((o) => o.entry.entryType === "CLOSURE"
                        && o.entry.status !== "CANCELLED");
                /*
                  Objednávky a události (V82) sdílejí řádky buňky v pořadí podle času; události
                  se liší jen barvou. Řádek nese jméno zákazníka, u události jméno zaměstnance,
                  a bez něj název — něco tam stát musí, jinak by řádek byl jen barevný proužek.
                */
                const rows = dayOccurrences.filter((o) => o.entry.entryType !== "CLOSURE");
                const shown = rows.slice(0, MAX_ROWS);
                const extra = rows.length - shown.length;
                const outside = !isSameMonth(day, monthStart);
                /*
                  Blokace nemá v měsíci vlastní řádek (filtruje se výš), veze ji příznak „Zavřeno".
                  Ten proto nese totéž co karta v týdnu — čas a důvod (`Zavřeno · celý den`
                  + „Školení techniků"). Do 2026-08-07 tu stálo jen holé „Zavřeno", takže z měsíce
                  nešlo poznat, jestli je zavřeno celý den nebo jen na dvě hodiny, ani proč.

                  Je-li den zavřený jen podle otevírací doby (neděle), žádný výskyt tu není —
                  příznak zůstane holým „Zavřeno" a nemá co popisovat.
                */
                const closureOccurrence = dayOccurrences.find((o) => o.entry.entryType === "CLOSURE"
                    && o.entry.status !== "CANCELLED");
                /* Přesouvat ji smí jen ten, kdo smí i v týdnu — pravidla drží canDragOccurrence. */
                const closureDraggable = closureOccurrence
                    && canDragOccurrence(closureOccurrence, canManageClosures);

                return (
                    <MonthDayCell key={dayKey(day)} day={day} blocked={blockedDays.has(dayKey(day))}>
                    <button type="button"
                            className={`schedule-month-day${outside ? " is-outside" : ""}`
                                + `${isToday(day) ? " is-today" : ""}${closed ? " is-closed" : ""}`}
                            onClick={() => onPickDay(day)}
                            aria-label={`${day.toLocaleDateString("cs-CZ")} — ${
                                closed ? "zavřeno" : `položek: ${rows.length}`}`}>
                        <span className="schedule-month-number">{day.getDate()}</span>
                        {closed && !closureOccurrence && (
                            <span className="schedule-month-closed">Zavřeno</span>
                        )}
                        {closureOccurrence && (
                            <DraggableOccurrence as="span" occurrence={closureOccurrence}
                                                 enabled={closureDraggable}>
                                <span className="schedule-month-closure"
                                      title={appointmentTooltip(closureOccurrence.entry)}>
                                    <span className="schedule-month-closed">
                                        Zavřeno · {closureTimeText(closureOccurrence.entry,
                                            closureOccurrence.isStart, closureOccurrence.isLast)}
                                    </span>
                                    <span className="schedule-month-closure-reason">
                                        {closureOccurrence.entry.title}
                                    </span>
                                </span>
                            </DraggableOccurrence>
                        )}

                        {shown.map((occurrence) => {
                            const {entry, isStart, isLast} = occurrence;
                            return isStart ? (
                                /*
                                  Obal je <span>, ne <div>: uvnitř <button> smí být jen phrasing
                                  content. Řádek sám zůstává bez onClick, takže klik na něj dál
                                  probublá na buňku a přepne na týden — přesně jako dosud.
                                */
                                <DraggableOccurrence key={entry.id} as="span" occurrence={occurrence}
                                                     enabled={canDragOccurrence(occurrence, canManageClosures)}>
                                    <span className={`schedule-month-row ${getAppointmentToneClass(entry)}`}
                                          title={appointmentTooltip(entry)}>
                                        {timeOfDay(entry.startsAt)} {rowName(entry)}
                                        {crossesMidnight(entry) && " →"}
                                    </span>
                                </DraggableOccurrence>
                            ) : (
                                <span key={entry.id}
                                      className={`schedule-month-row is-continuation ${getAppointmentToneClass(entry)}`}
                                      title={appointmentTooltip(entry)}>
                                    <span className="schedule-month-arrow" aria-hidden="true">→</span>
                                    {isLast ? `do ${timeOfDay(entry.endsAt)} ` : ""}{rowName(entry)}
                                </span>
                            );
                        })}

                        {extra > 0 && <span className="schedule-month-more">+{extra} další</span>}
                    </button>
                    </MonthDayCell>
                );
            })}
        </div>
    );
}

/**
 * Jméno do řádku buňky: zákazník, kontakt nebo zaměstnanec — a když není nikdo, název práce.
 * Buňka měsíce má jeden řádek, takže prázdno tu není přípustné (na rozdíl od karty dne).
 */
function rowName(entry) {
    return appointmentPersonName(entry) || entry.title;
}

/** Vyzvednutí v jiném dni — v den příjezdu se značí šipkou za jménem. */
function crossesMidnight(entry) {
    return Boolean(entry.endsAt)
        && dayKey(entry.startsAt) !== dayKey(new Date(entry.endsAt).getTime() - 1);
}
