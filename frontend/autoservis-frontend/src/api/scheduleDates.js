/**
 * Datová aritmetika plánovacího kalendáře.
 *
 * <p>Vlastní funkce místo knihovny: potřebujeme jen začátek týdne, posun o týden/měsíc a klíč dne.
 * To je pár řádků, které se nezmění — přidávat kvůli tomu `date-fns` by znamenalo další závislost
 * a další verzi k udržování.
 *
 * <p>Všechno počítá v <strong>místním čase</strong>. Objednávky chodí ze serveru jako ISO okamžiky
 * a do dnů se zařazují podle toho, jak je vidí obsluha ve své zóně — ne podle UTC. Klíč dne proto
 * nesmí vzniknout z `toISOString()`: ta převádí do UTC a objednávka po 22:00 letního času by
 * spadla do dalšího dne.
 */

/** Půlnoc daného dne v místním čase. */
export function startOfDay(date) {
    const copy = new Date(date);
    copy.setHours(0, 0, 0, 0);
    return copy;
}

/** Pondělí týdne, do kterého datum spadá (týden začíná pondělkem, ne nedělí). */
export function startOfWeek(date) {
    const day = startOfDay(date);
    const shift = (day.getDay() + 6) % 7;   // ne=0 → 6, po=1 → 0
    day.setDate(day.getDate() - shift);
    return day;
}

/** První den měsíce, do kterého datum spadá. */
export function startOfMonth(date) {
    const day = startOfDay(date);
    day.setDate(1);
    return day;
}

export function addDays(date, count) {
    const copy = new Date(date);
    copy.setDate(copy.getDate() + count);
    return copy;
}

export function addMonths(date, count) {
    const copy = new Date(date);
    copy.setMonth(copy.getMonth() + count);
    return copy;
}

/** Sedm dnů od pondělí. */
export function weekDays(weekStart) {
    return Array.from({length: 7}, (_, index) => addDays(weekStart, index));
}

/**
 * Dny měsíční mřížky — vždy celé týdny, takže mřížka má 35 nebo 42 buněk a nezubatí se.
 * Dny z okolních měsíců se vracejí taky; volající je pozná přes {@link isSameMonth}.
 */
export function monthGridDays(monthStart) {
    const first = startOfWeek(monthStart);
    const lastOfMonth = new Date(monthStart.getFullYear(), monthStart.getMonth() + 1, 0);
    const last = addDays(startOfWeek(lastOfMonth), 6);
    const days = [];
    for (let day = first; day <= last; day = addDays(day, 1)) {
        days.push(day);
    }
    return days;
}

/** Klíč dne `RRRR-MM-DD` v místním čase — pro seskupení objednávek podle dne. */
export function dayKey(date) {
    const value = new Date(date);
    return [
        value.getFullYear(),
        String(value.getMonth() + 1).padStart(2, "0"),
        String(value.getDate()).padStart(2, "0"),
    ].join("-");
}

export function isToday(date) {
    return dayKey(date) === dayKey(new Date());
}

export function isSameMonth(date, monthStart) {
    return date.getMonth() === monthStart.getMonth()
        && date.getFullYear() === monthStart.getFullYear();
}

/** Zkratka dne v týdnu — „po", „út"… */
export function weekdayShort(date) {
    return date.toLocaleDateString("cs-CZ", {weekday: "short"});
}

/** Hodina a minuta — „9:00". */
export function timeOfDay(isoString) {
    return new Date(isoString).toLocaleTimeString("cs-CZ", {hour: "numeric", minute: "2-digit"});
}

/** Popis rozsahu týdne do hlavičky — „3. – 9. 8. 2026". */
export function weekRangeLabel(weekStart) {
    const end = addDays(weekStart, 6);
    const sameMonth = weekStart.getMonth() === end.getMonth();
    const from = sameMonth
        ? `${weekStart.getDate()}.`
        : `${weekStart.getDate()}. ${weekStart.getMonth() + 1}.`;
    return `${from} – ${end.getDate()}. ${end.getMonth() + 1}. ${end.getFullYear()}`;
}

/** Popis měsíce do hlavičky — „srpen 2026". */
export function monthLabel(monthStart) {
    return monthStart.toLocaleDateString("cs-CZ", {month: "long", year: "numeric"});
}

/**
 * Rozdělí položky do dnů, kterých se týkají — včetně přesahů do dalších dnů.
 *
 * <p>Dřívější seskupení podle dne začátku mělo dvě díry: vícedenní blokace se ukázala jen
 * v první den a blokace začínající před zobrazeným oknem zmizela úplně (SQL ji správně
 * vrátilo, frontend ji zahodil, protože její první den v mřížce nebyl).
 *
 * <p>Pravidla:
 * <ul>
 *   <li>Položka se objeví v každém dni od začátku po konec. Konec přesně o půlnoci do toho
 *       dne NEspadá (blokace st 00:00 – čt 00:00 je jen středa) — proto `endsAt − 1 ms`.</li>
 *   <li>Objednávka <strong>bez konce</strong> patří jen do dne příjezdu: délku neznáme,
 *       takže ji do dalších dnů nekreslíme.</li>
 *   <li>Uvnitř dne: blokace první, pak pokračování (začala dřív), pak příjezdy podle času.</li>
 * </ul>
 *
 * @param {object[]} entries položky z API (ISO časy)
 * @param {Date}     from    začátek zobrazeného okna (včetně, půlnoc)
 * @param {Date}     to      konec okna (mimo)
 * @returns {Map<string, {entry: object, isStart: boolean, isLast: boolean}[]>} klíč dne → výskyty
 */
export function groupOccurrences(entries, from, to) {
    const map = new Map();
    for (const entry of entries) {
        const firstDay = startOfDay(new Date(entry.startsAt));
        const lastDay = entry.endsAt
            ? startOfDay(new Date(new Date(entry.endsAt).getTime() - 1))
            : firstDay;

        for (let day = firstDay < from ? startOfDay(from) : firstDay;
             day <= lastDay && day < to;
             day = addDays(day, 1)) {
            const key = dayKey(day);
            if (!map.has(key)) {
                map.set(key, []);
            }
            map.get(key).push({
                entry,
                isStart: key === dayKey(firstDay),
                isLast: key === dayKey(lastDay),
            });
        }
    }
    for (const list of map.values()) {
        list.sort((a, b) => occurrenceOrder(a) - occurrenceOrder(b)
            || a.entry.startsAt.localeCompare(b.entry.startsAt));
    }
    return map;
}

/** Pořadí v rámci dne: blokace (0) → pokračování (1) → příjezdy dne (2). */
function occurrenceOrder(occurrence) {
    if (occurrence.entry.entryType === "CLOSURE") {
        return 0;
    }
    return occurrence.isStart ? 2 : 1;
}

/**
 * Nový termín po přetažení položky na jiný den — posun o celé dny.
 *
 * <p><strong>Čas i délka zůstávají.</strong> Karta dne je seznam, ne časová osa, takže svislá
 * poloha při upuštění nenese hodinu — vymyslet z ní čas by zapsalo údaj, který nikdo netvrdil.
 * Přesouvá se tedy jen datum: objednávka na osmou ráno je na osmou ráno i po přesunu.
 *
 * <p>Posouvá se přes {@link addDays} (tedy `setDate`), ne přičtením milisekund: při přechodu
 * na letní čas má den 23 nebo 25 hodin a objednávka by se posunula o hodinu.
 *
 * <p>Konec se posune o týž počet dní, takže vícedenní objednávka si délku podrží. Objednávka
 * bez konce ho nedostane — {@code null} zůstává {@code null} (V74).
 *
 * @param {object} entry     položka z API (ISO časy)
 * @param {Date}   targetDay den, na který se upouští
 * @returns {{startsAt: string, endsAt: string|null}|null} nový termín, nebo `null` když se den nemění
 */
export function shiftToDay(entry, targetDay) {
    const start = new Date(entry.startsAt);
    const dayDiff = Math.round(
        (startOfDay(targetDay).getTime() - startOfDay(start).getTime()) / 86400000);

    if (dayDiff === 0) {
        return null;
    }
    return {
        startsAt: addDays(start, dayDiff).toISOString(),
        endsAt: entry.endsAt ? addDays(new Date(entry.endsAt), dayDiff).toISOString() : null,
    };
}

/** Konec pro účely překryvu. Položka bez konce se posuzuje jako okamžik (V74) — stejně jako v SQL. */
function overlapEnd(entry) {
    return entry.endsAt
        ? new Date(entry.endsAt).getTime()
        : new Date(entry.startsAt).getTime() + 1000;
}

/** Zrušené a nedostavené nepřekážejí — na ty už nikdo nepřijede (totéž pravidlo jako v mapperu). */
function isLive(entry) {
    return entry.status !== "CANCELLED" && entry.status !== "NO_SHOW";
}

/**
 * Skončilo by přetažení položky na tento den chybou?
 *
 * <p>Slouží <strong>jen k obarvení cíle během tažení</strong>, aby obsluha viděla zákaz dřív, než
 * pustí myš. Rozhoduje dál server — tohle je předběžné vodítko počítané z dat, která má klient
 * zrovna načtená (zobrazené okno). Kdyby se obojí rozešlo, projeví se to tak, že se položka po
 * upuštění vrátí a přijde hláška; nikdy ne tak, že by se uložilo něco zakázaného.
 *
 * <p>Zrcadlí dvojici pravidel ze `AppointmentServiceImpl`:
 * <ul>
 *   <li>objednávku nelze pustit do blokace dílny (`APPOINTMENT_IN_CLOSURE`),</li>
 *   <li>blokaci nelze pustit na objednávku ani událost (`CLOSURE_OVERLAPS_ENTRIES`),</li>
 *   <li>událost nebrání ničemu a nic nebrání jí — dovolená jednoho mechanika dílnu nezavírá.</li>
 * </ul>
 *
 * @param {object}   entry     tažená položka
 * @param {Date}     targetDay den, nad kterým kurzor visí
 * @param {object[]} entries   položky zobrazeného okna
 * @returns {boolean} true = upuštění by skončilo chybou
 */
export function isDropBlocked(entry, targetDay, entries) {
    const shifted = shiftToDay(entry, targetDay);
    if (!shifted) {
        return false;   // týž den — nic se neděje
    }

    const conflictsWith = entry.entryType === "CLOSURE"
        ? (other) => other.entryType !== "CLOSURE" && isLive(other)
        : entry.entryType === "BOOKING"
            ? (other) => other.entryType === "CLOSURE" && isLive(other)
            : () => false;

    const start = new Date(shifted.startsAt).getTime();
    const end = shifted.endsAt ? new Date(shifted.endsAt).getTime() : start + 1000;

    return entries.some((other) => other.id !== entry.id
        && conflictsWith(other)
        && new Date(other.startsAt).getTime() < end
        && start < overlapEnd(other));
}
