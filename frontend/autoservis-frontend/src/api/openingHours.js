/**
 * Otevírací doba dílny — sdílené pomůcky pro nastavení i kalendář.
 *
 * <p>Rozvrh je pole sedmi dnů z `GET /opening-hours`; `dayOfWeek` je 1 = pondělí … 7 = neděle
 * (ISO-8601, shodné se serverem i s PostgreSQL). JavaScript má ovšem `Date.getDay()` 0 = neděle,
 * takže převod je tady na jediném místě — {@link isoDayOfWeek}.
 */

/** Názvy dnů podle ISO čísla (index 0 se nepoužívá). */
export const WEEKDAY_NAMES = [
    null, "Pondělí", "Úterý", "Středa", "Čtvrtek", "Pátek", "Sobota", "Neděle",
];

/** Zkratky pro souhrn rozvrhu; používá je jen summarizeSchedule níž. */
const WEEKDAY_SHORT = [
    null, "po", "út", "st", "čt", "pá", "so", "ne",
];

/**
 * Den s předložkou pro věty typu „Otevřeno **v úterý** 7:00–17:00".
 *
 * <p>Skloňování je uložené celé, ne skládané za běhu: čeština tu mění pád (sobota → v sobotu)
 * i předložku (ve středu, ve čtvrtek). Poskládat to z prvního pádu nejde, a „V sobota má dílna
 * zavřeno" je přesně to, co z toho vyleze.
 */
export const WEEKDAY_IN = [
    null, "v pondělí", "v úterý", "ve středu", "ve čtvrtek", "v pátek", "v sobotu", "v neděli",
];

/**
 * ISO číslo dne (1 = pondělí … 7 = neděle) z JS data.
 *
 * <p>`Date.getDay()` vrací 0 pro neděli — bez tohoto převodu by neděle vyšla jako den 0,
 * který v rozvrhu neexistuje, a kalendář by ji tiše považoval za neznámou.
 */
function isoDayOfWeek(date) {
    return date.getDay() === 0 ? 7 : date.getDay();
}

/** Rozvrh daného dne, nebo `undefined`, není-li rozvrh načtený. */
export function scheduleFor(schedule, date) {
    return schedule?.days?.find((day) => day.dayOfWeek === isoDayOfWeek(date));
}

/** Je ten den zavřeno celý den? Bez načteného rozvrhu vždy `false` — nic se nepředstírá. */
export function isClosedDay(schedule, date) {
    if (!schedule?.openingHoursEnabled) {
        return false;
    }
    const day = scheduleFor(schedule, date);
    return Boolean(day) && !day.opensAt;
}

/** „7:00" z „07:00:00"; prázdný vstup vrací prázdný řetězec. */
export function shortTime(time) {
    if (!time) {
        return "";
    }
    const [hours, minutes] = time.split(":");
    return `${Number(hours)}:${minutes}`;
}

/**
 * Jednořádkový přehled do lišty kalendáře: „po–pá 7:00–17:00 · so, ne zavřeno".
 *
 * <p>Dny se stejnou dobou se slučují do rozsahu, aby řádek nebyl sedm samostatných údajů —
 * u běžného servisu z toho zbydou dvě skupiny.
 */
export function summarizeSchedule(schedule) {
    if (!schedule?.days?.length) {
        return "";
    }
    const groups = [];
    for (const day of schedule.days) {
        const key = day.opensAt ? `${day.opensAt}-${day.closesAt}` : "closed";
        const last = groups[groups.length - 1];
        if (last && last.key === key) {
            last.to = day.dayOfWeek;
        } else {
            groups.push({key, from: day.dayOfWeek, to: day.dayOfWeek, day});
        }
    }
    return groups.map((group) => {
        const label = group.from === group.to
            ? WEEKDAY_SHORT[group.from]
            : `${WEEKDAY_SHORT[group.from]}–${WEEKDAY_SHORT[group.to]}`;
        return group.key === "closed"
            ? `${label} zavřeno`
            : `${label} ${shortTime(group.day.opensAt)}–${shortTime(group.day.closesAt)}`;
    }).join(" · ");
}
