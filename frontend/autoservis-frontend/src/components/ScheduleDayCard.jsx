import {useDraggable, useDroppable} from "@dnd-kit/core";
import StatusBadge from "./StatusBadge.jsx";
import {
    formatDateTime,
    getAppointmentStatusLabel,
    getAppointmentStatusTone,
    getAppointmentToneClass,
} from "../api/format.js";
import {dayKey, isToday, timeOfDay, weekdayShort} from "../api/scheduleDates.js";

/**
 * Jeden den týdne jako karta se seznamem objednávek.
 *
 * <p><strong>Proč seznam a ne časová osa</strong> (rozhodnutí 2026-08-03): osa kreslí výšku podle
 * délky, jenže délku opravy mechanik před diagnostikou nezná, a když na jednu hodinu přijede pět
 * aut, osa je rozseká na pět nečitelných proužků — kapacitu dílny nikde neevidujeme. Seznam
 * seřazený podle příjezdu nic z toho nepředstírá: pět aut na devátou jsou prostě pět řádků.
 *
 * <p><strong>Vícedenní položky</strong> chodí jako výskyty z {@code groupOccurrences} — táž
 * objednávka se ukáže v každém dotčeném dni. V den příjezdu plně; dál jako „→ pokračuje"
 * ve <strong>stejné barvě stavu</strong> (rozhodnutí uživatele 2026-08-03 — barva je informace
 * a nesmí se ztlumením ztratit); pokračování odlišuje čárkovaný obvod a zvýrazněná šipka.
 * Objednávka bez konce má jen den příjezdu.
 *
 * @param {Date}     date        den, který karta zobrazuje
 * @param {object[]} occurrences výskyty toho dne ({entry, isStart, isLast}), seřazené
 * @param {Function} onSelect    (entry) => void — otevře detail
 * @param {Function} onAdd       (date) => void — nová objednávka na tento den
 * @param {boolean}  [closed]    dílna má ten den podle otevírací doby zavřeno
 */
/**
 * Smí se položka přesouvat tažením?
 *
 * <p>Ne u <strong>pokračování</strong> vícedenní položky: chytit „→ pokračuje" ve středu
 * neříká, kam se má přesunout začátek z pondělí. Táhne se za den příjezdu, kde je celá.
 *
 * <p>Ne u <strong>terminálních stavů</strong> (zrušená, nedostavil se, převedená): server je
 * odmítne jako needitovatelné (422) a nabízet akci, která vždy selže, je horší než ji nemít.
 *
 * <p>Blokaci dílny smí posunout jen vedení — stejné pravidlo jako u jejího zakládání.
 */
export function canDragOccurrence({entry, isStart}, canManageClosures) {
    if (!isStart) {
        return false;
    }
    if (["CONVERTED", "NO_SHOW", "CANCELLED"].includes(entry.status)) {
        return false;
    }
    return entry.entryType !== "CLOSURE" || canManageClosures;
}

/**
 * Obal položky, který ji dělá uchopitelnou. Když se táhnout nesmí, vykreslí děti beze změny —
 * hook se volá vždy (pravidla hooků), rozhoduje se až o navěšení posluchačů.
 *
 * <p>{@code as} určuje značku obalu, protože měsíční buňka je {@code <button>} a ten smí
 * obsahovat jen „phrasing content" — {@code <div>} uvnitř něj je nevalidní, {@code <span>} ne.
 *
 * <p><strong>Záměrně se nepoužívá {@code attributes} z dnd-kit.</strong> Ta sada obsahuje
 * {@code role="button"} a {@code tabIndex={0}}, takže by z obalu udělala další místo, na které
 * se dá dostat tabulátorem — uvnitř buňky-tlačítka (měsíc) i kolem tlačítka objednávky (týden)
 * by vzniklo vnořené tlačítko a průchod klávesnicí by se zdvojil. Tažení je zrychlovač pro myš;
 * klávesnicová cesta ke změně termínu vede přes „Upravit", která nikam nezmizela.
 */
export function DraggableOccurrence({occurrence, enabled, as, children}) {
    /*
     * Hook se volá jen pro položku, která se opravdu smí táhnout — proto to rozdělení na dvě
     * komponenty místo `disabled: !enabled` v jedné.
     *
     * Vícedenní objednávka se totiž kreslí v každém dotčeném dni, ale `id` má odvozené od
     * `entry.id`, takže tři dny znamenaly TŘI registrace téhož `entry-42`. dnd-kit si drží
     * jednu mapu id → uzel: poslední registrace (pokračování v posledním dni) přepsala tu první
     * a knihovna pak měřila polohu úplně jiného místa na obrazovce, než za které uživatel táhl.
     * Kolize s cílovým dnem tím vycházela mimo — objednávka skončila jinde, než kam se pustila.
     */
    if (!enabled) {
        return children;
    }
    return <DraggableOccurrenceInner occurrence={occurrence} as={as}>{children}</DraggableOccurrenceInner>;
}

function DraggableOccurrenceInner({occurrence, as: Tag = "div", children}) {
    const {listeners, setNodeRef, isDragging} = useDraggable({
        id: `entry-${occurrence.entry.id}`,
        data: {entry: occurrence.entry},
    });

    return (
        <Tag ref={setNodeRef} className={`schedule-draggable${isDragging ? " is-dragging" : ""}`}
             {...listeners}>
            {children}
        </Tag>
    );
}

export default function ScheduleDayCard({date, occurrences, onSelect, onAdd, closed = false,
                                            canManageClosures = false, dropBlocked = false}) {
    /*
     * Cílem upuštění je celá karta dne, ne jednotlivé řádky: karta je seznam, takže svislá
     * poloha uvnitř ní nenese čas a „mezi devátou a desátou" se tu upustit nedá.
     */
    const {setNodeRef: setDropRef, isOver} = useDroppable({
        id: `day-${dayKey(date)}`,
        data: {date},
    });
    const closures = occurrences.filter((o) => o.entry.entryType === "CLOSURE");
    /*
     * Události (EVENT, V82) jdou do jednoho seznamu s objednávkami, seřazené podle času —
     * dovolená v 0:00 stojí nahoře, školení ve 14:00 mezi objednávkami. Vlastní sekce by
     * lhala o pořadí dne. Liší se jen barvou (getAppointmentToneClass) a obsahem řádků.
     */
    const bookings = occurrences.filter((o) => o.entry.entryType !== "CLOSURE");
    const today = isToday(date);

    /*
     * Zavřený den se jen ztlumí a popíše — tlačítko „+" zůstává. Otevírací doba totiž
     * objednávku nezakazuje, jen na ni upozorní (rozhodnutí uživatele 2026-08-04); skrývat
     * tlačítko by slibovalo zákaz, který neplatí.
     */
    return (
        /* Nad zakázaným dnem svítí obrys červeně — zákaz je vidět dřív, než se pustí myš. */
        <div ref={setDropRef}
             className={`schedule-day${today ? " is-today" : ""}${closed ? " is-closed-day" : ""}`
                 + `${isOver ? (dropBlocked ? " is-drop-blocked" : " is-drop-target") : ""}`}>
            <div className="schedule-day-head">
                <span className="schedule-day-date">
                    <span className={`schedule-day-number${today ? " is-today" : ""}`}>
                        {date.getDate()}
                    </span>
                    <span className="schedule-day-name">{weekdayShort(date)}</span>
                </span>
                <button type="button" className="schedule-day-add" onClick={() => onAdd(date)}
                        title="Nová objednávka" aria-label={`Nová objednávka na ${date.toLocaleDateString("cs-CZ")}`}>
                    <i className="bi bi-plus-lg" aria-hidden="true"></i>
                </button>
            </div>

            <div className="schedule-day-body">
                {/*
                  Blokace nese tytéž údaje jako objednávka — čas i šipku u přesahu do dalších dnů.
                  Do 2026-08-04 obojí chybělo: dovolená od pátečních 8:00 do sobotních 10:00 se
                  kreslila oba dny stejně, takže z karet nešlo poznat, že dílna je v pátek ráno
                  a v sobotu dopoledne otevřená, a sobota vypadala jako samostatná blokace.

                  Pokračování odlišuje jen šipka, ne čárkovaný obvod jako u objednávek — ten už
                  tady znamená „blokace" a druhý význam by neunesl.
                */}
                {closures.map((occurrence) => {
                    const {entry, isStart, isLast} = occurrence;
                    return (
                        <DraggableOccurrence key={entry.id} occurrence={occurrence}
                                             enabled={canDragOccurrence(occurrence, canManageClosures)}>
                            <button type="button" className="schedule-closure"
                                    title={appointmentTooltip(entry)}
                                    onClick={() => onSelect(entry)}>
                                <span className="schedule-closure-label">
                                    {!isStart && (
                                        <i className="bi bi-arrow-right schedule-continuation-arrow"
                                           aria-hidden="true"></i>
                                    )}
                                    Zavřeno · {closureTimeText(entry, isStart, isLast)}
                                </span>
                                <span className="schedule-closure-reason">{entry.title}</span>
                            </button>
                        </DraggableOccurrence>
                    );
                })}

                {bookings.map((occurrence) => {
                    const {entry, isStart, isLast} = occurrence;
                    const draggable = canDragOccurrence(occurrence, canManageClosures);
                    return isStart ? (
                    <DraggableOccurrence key={entry.id} occurrence={occurrence} enabled={draggable}>
                    <button type="button"
                            className={`schedule-booking ${getAppointmentToneClass(entry)}`}
                            title={appointmentTooltip(entry)}
                            onClick={() => onSelect(entry)}>
                        {/*
                          Bez konce stačí „od 9:30". Dovětek „· konec neznámý" v kartě jen zabíral
                          řádek a opakoval, co už říká chybějící druhý čas; zůstává v tooltipu
                          a v detailu, kde je na vysvětlení místo.
                        */}
                        <span className="schedule-booking-time">
                            {entry.endsAt ? arrivalRangeText(entry) : `od ${timeOfDay(entry.startsAt)}`}
                        </span>
                        <span className="schedule-booking-title">{entry.title}</span>
                        {/* U objednávky zákazník nebo kontakt, u události zaměstnanec. Může chybět. */}
                        {appointmentPersonName(entry) && (
                            <span className="schedule-booking-meta">
                                {appointmentPersonName(entry)}
                            </span>
                        )}
                        {entry.vehicleLicensePlate && (
                            <span className="schedule-booking-plate">{entry.vehicleLicensePlate}</span>
                        )}
                        {entry.status !== "PLANNED" && (
                            <StatusBadge tone={getAppointmentStatusTone(entry.status)}
                                         className="schedule-booking-status">
                                {getAppointmentStatusLabel(entry.status)}
                            </StatusBadge>
                        )}
                    </button>
                    </DraggableOccurrence>
                ) : (
                    /* Pokračování se netáhne (canDragOccurrence), proto bez obalu. */
                    <button type="button" key={entry.id}
                            className={`schedule-continuation ${getAppointmentToneClass(entry)}`}
                            title={appointmentTooltip(entry)}
                            onClick={() => onSelect(entry)}>
                        <span className="schedule-continuation-main">
                            <i className="bi bi-arrow-right schedule-continuation-arrow" aria-hidden="true"></i>
                            {isLast ? `do ${timeOfDay(entry.endsAt)} · ` : "pokračuje · "}
                            {appointmentPersonName(entry)}
                        </span>
                        {/*
                          Podřádek nese název práce, ne čas vyzvednutí. „vyzvednutí st 23:16" bylo
                          bez data matoucí (v jiném týdnu neřekne nic) a duplikovalo informaci,
                          kterou nese hlavní řádek posledního dne. Přesný termín je v tooltipu a v detailu.
                        */}
                        <span className="schedule-continuation-sub">{entry.title}</span>
                    </button>
                );
                })}

                {occurrences.length === 0 && closed && (
                    <p className="schedule-day-empty">Zavřeno</p>
                )}
                {occurrences.length === 0 && !closed && (
                    <p className="schedule-day-empty">Žádné objednávky</p>
                )}
            </div>
        </div>
    );
}

/**
 * Čas blokace na kartě dne — táž pravidla jako u objednávky, jen bez nesmyslných půlnocí.
 *
 * <p>Celodenní zavřeno se v DB ukládá jako 0:00 → 0:00 dalšího dne. Vypsat „0:00 – 0:00" by
 * nic neřeklo a působilo by jako chyba, proto se z toho stane „celý den". Zbylé případy
 * kopírují objednávku: první den rozsah, prostřední „pokračuje", poslední „do HH:MM".
 */
export function closureTimeText(entry, isStart, isLast) {
    if (!isStart) {
        return isLast ? `do ${timeOfDay(entry.endsAt)}` : "pokračuje";
    }
    const start = new Date(entry.startsAt);
    const wholeDay = start.getHours() === 0 && start.getMinutes() === 0
        && new Date(entry.endsAt).getTime() >= start.getTime() + 24 * 60 * 60 * 1000;
    return wholeDay ? "celý den" : arrivalRangeText(entry);
}

/**
 * Časový rozsah v den příjezdu. Když vyzvednutí padne do jiného dne, doplní se jeho zkratka —
 * „8:00 – pá 12:00" — jinak by „8:00 – 12:00" vypadalo jako čtyřhodinová návštěva.
 */
function arrivalRangeText(entry) {
    const end = new Date(entry.endsAt);
    const crossDay = dayKey(entry.startsAt) !== dayKey(end.getTime() - 1);
    const endText = crossDay
        ? `${weekdayShort(end)} ${timeOfDay(entry.endsAt)}`
        : timeOfDay(entry.endsAt);
    return `${timeOfDay(entry.startsAt)} – ${endText}`;
}

/**
 * Kdo k položce patří — jedním řetězcem pro kartu, měsíc i tooltip.
 *
 * <p>Pořadí je od nejspolehlivějšího údaje k nejslabšímu: navázaný zákazník, pak kontakt na
 * zákazníka mimo evidenci (V85), pak zaměstnanec u události. Nikdy nevyjdou dva naráz —
 * objednávka nemá zaměstnance a událost nemá zákazníka ani kontakt (CHECK constrainty).
 * Prázdný výsledek je legitimní: objednávka bez kohokoli se pozná podle názvu práce.
 */
export function appointmentPersonName(entry) {
    return entry.customerDisplayName || entry.contactNote || entry.employeeDisplayName || "";
}

/**
 * Úplné údaje do nativního tooltipu — to, co se do úzké karty nevešlo, se tím neztratí.
 * Sdílí ho i měsíční přehled, aby oba pohledy říkaly na hover totéž.
 *
 * <p>Termín se vypisuje s <strong>plným datem</strong> (ne jen časem jako v kartě): v kartě dává
 * kontext den, ve kterém stojí, ale tooltip se čte samostatně a u vícedenní objednávky nebo
 * v jiném týdnu by holý čas neřekl nic. Tvar je záměrně shodný s detailem po kliknutí.
 */
export function appointmentTooltip(entry) {
    return [
        entry.title,
        entry.endsAt
            ? `${formatDateTime(entry.startsAt)} – ${formatDateTime(entry.endsAt)}`
            : `od ${formatDateTime(entry.startsAt)}`,
        appointmentPersonName(entry),
        [entry.vehicleBrand, entry.vehicleModel, entry.vehicleLicensePlate].filter(Boolean).join(" · "),
    ].filter(Boolean).join("\n");
}
