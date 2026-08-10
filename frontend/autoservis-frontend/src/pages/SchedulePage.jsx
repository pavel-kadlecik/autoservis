import {useCallback, useEffect, useMemo, useState} from "react";
import {useNavigate} from "react-router-dom";
import {DndContext, DragOverlay, MouseSensor, TouchSensor, useSensor, useSensors} from "@dnd-kit/core";

import PageHeader from "../components/PageHeader.jsx";
import Modal from "../components/Modal.jsx";
import ConfirmDialog from "../components/ConfirmDialog.jsx";
import StatusBadge from "../components/StatusBadge.jsx";
import LoadErrorState from "../components/LoadErrorState.jsx";
import AppointmentForm from "../components/AppointmentForm.jsx";
import ScheduleLegend from "../components/ScheduleLegend.jsx";
import ScheduleDayCard, {appointmentPersonName} from "../components/ScheduleDayCard.jsx";
import ScheduleMonth from "../components/ScheduleMonth.jsx";
import TableRowActionMenu from "../components/TableRowActionMenu.jsx";
import EventBusyIcon from "@mui/icons-material/EventBusy";
import BlockIcon from "@mui/icons-material/Block";
import DeleteForeverIcon from "@mui/icons-material/DeleteForever";
import {api, problemMessage} from "../api/api.js";
import {useAlert} from "../context/AlertContext.jsx";
import {
    formatDateTime,
    getAppointmentStatusLabel,
    getAppointmentStatusTone,
    getAppointmentTypeLabel,
} from "../api/format.js";
import {
    addDays,
    addMonths,
    dayKey,
    groupOccurrences,
    isDropBlocked,
    monthGridDays,
    monthLabel,
    shiftToDay,
    startOfMonth,
    startOfWeek,
    timeOfDay,
    weekDays,
    weekRangeLabel,
} from "../api/scheduleDates.js";
import {isClosedDay, scheduleFor, summarizeSchedule} from "../api/openingHours.js";
import "../css/schedule.css";

/** Výchozí hodina nové objednávky, když otevírací doba není nastavená nebo se nehlídá. */
const DEFAULT_ARRIVAL_HOUR = 8;

/**
 * Plánovací kalendář — objednávky termínů a blokace dílny.
 *
 * <h3>Proč denní karty a ne časová osa</h3>
 * <p>Osa byla první podoba a musela ustoupit (rozhodnutí uživatele 2026-08-03). Kreslí výšku podle
 * délky termínu, jenže délku opravy mechanik před diagnostikou často nezná — a hlavně: když na
 * jednu hodinu přijede pět aut, osa je rozseká na pět svislých proužků po několika desítkách
 * pixelů. Kapacitu dílny nikde neevidujeme, takže osa slibovala přesnost, kterou data nemají.
 *
 * <p>Seznam seřazený podle příjezdu nepředstírá nic: pět aut na devátou jsou pět čitelných řádků
 * a objednávka bez konce se napíše jako „od 9:30“.
 *
 * <p><strong>Přetahování myší tu není</strong> — termín se mění přes „Upravit“. V kartách by
 * navíc nebylo kam přesně upustit, protože svislá poloha nenese čas.
 */
export default function SchedulePage() {
    const navigate = useNavigate();
    const {addAlert} = useAlert();

    /*
     * Výchozí je MĚSÍC (přání zákazníka 2026-08-04) — po otevření chce vidět celkový přehled
     * vytíženosti, detail týdne je na jeden klik. Kotva musí být startOfMonth, ne startOfWeek:
     * když začátek aktuálního týdne spadne do minulého měsíce (1. den měsíce v neděli),
     * kalendář by se otevřel na minulém měsíci.
     */
    const [view, setView] = useState("month");           // "week" | "month"
    const [anchor, setAnchor] = useState(() => startOfMonth(new Date()));
    const [entries, setEntries] = useState([]);
    const [onlyActive, setOnlyActive] = useState(true);
    const [loadError, setLoadError] = useState(null);
    const [canManageClosures, setCanManageClosures] = useState(false);
    const [openingHours, setOpeningHours] = useState(null);

    const [formState, setFormState] = useState(null);
    const [detail, setDetail] = useState(null);
    const [confirmDelete, setConfirmDelete] = useState(null);

    // Zobrazený rozsah: týden = 7 dnů, měsíc = celé týdny mřížky (35 nebo 42 dnů).
    const range = useMemo(() => {
        if (view === "week") {
            const from = startOfWeek(anchor);
            return {from, to: addDays(from, 7)};
        }
        const days = monthGridDays(startOfMonth(anchor));
        return {from: days[0], to: addDays(days[days.length - 1], 1)};
    }, [view, anchor]);

    useEffect(() => {
        api.get("/auth/me")
            .then((me) => setCanManageClosures(
                (me.roles ?? []).some((role) => role === "ROLE_ADMIN" || role === "ROLE_MANAGER")))
            .catch(() => setCanManageClosures(false));
    }, []);

    /*
     * Rozvrh se načítá jednou při otevření stránky, ne s každým posunem týdne — mění se řádově
     * jednou za rok a jde o sedm řádků.
     */
    useEffect(() => {
        api.get("/opening-hours")
            .then(setOpeningHours)
            .catch(() => setOpeningHours(null));   // kalendář musí fungovat i bez rozvrhu
    }, []);

    const load = useCallback(async () => {
        try {
            const params = new URLSearchParams({
                from: range.from.toISOString(),
                to: range.to.toISOString(),
            });
            setEntries(await api.get(`/appointments?${params}`));
            setLoadError(null);
        } catch (err) {
            setLoadError(problemMessage(err, "Kalendář se nepodařilo načíst."));
        }
    }, [range]);

    useEffect(() => {
        load();
    }, [load]);

    /**
     * Výskyty podle dne — vícedenní položky se opakují v každém dotčeném dni
     * (viz groupOccurrences; tamtéž pravidlo, že objednávka bez konce má jen den příjezdu).
     *
     * <p>Filtr „jen nezrušené" se aplikuje <strong>tady, ne na serveru</strong>: API umí filtrovat
     * jen na jeden konkrétní stav (rovnost), ne na „všechny kromě zrušených", a data zobrazeného
     * okna jsou stejně už načtená. Přepnutí je tím okamžité a nestojí další dotaz.
     */
    const occurrences = useMemo(() => {
        const visible = onlyActive
            ? entries.filter((entry) => entry.status !== "CANCELLED")
            : entries;
        return groupOccurrences(visible, range.from, range.to);
    }, [entries, range, onlyActive]);

    /** Kolik položek filtr zrovna schovává — bez toho by uživatel nevěděl, že něco nevidí. */
    const hiddenCount = onlyActive
        ? entries.filter((entry) => entry.status === "CANCELLED").length
        : 0;

    /*
     * MouseSensor + TouchSensor místo jednoho PointerSensoru, protože každý potřebuje jinou
     * podmínku spuštění:
     *   - myš: 8 px pohybu — pod tím je to klik, který musí dál otevřít detail (týž práh
     *     jako u řazení položek zakázky),
     *   - dotyk: 250 ms držení. Kalendář se svisle scrolluje, takže na prahu vzdálenosti by
     *     každé posunutí prstem začalo táhnout objednávku místo rolování.
     */
    const sensors = useSensors(
        useSensor(MouseSensor, {activationConstraint: {distance: 8}}),
        useSensor(TouchSensor, {activationConstraint: {delay: 250, tolerance: 8}}),
    );

    /** Položka držená myší — kreslí ji DragOverlay, aby ji karta neořízla. */
    const [dragged, setDragged] = useState(null);

    /**
     * Dny, kam se právě tažená položka pustit nedá — karta se pak obarví červeně místo modře.
     *
     * <p>Počítá se dopředu pro celé okno (7 nebo 42 dnů), ne až při najetí na konkrétní kartu:
     * obsluha tak vidí rovnou, kam smí, a nemusí to hledat pokusem. Mimo tažení je množina
     * prázdná, takže se nic nepočítá zbytečně.
     */
    const blockedDays = useMemo(() => {
        if (!dragged) {
            return new Set();
        }
        const days = view === "week" ? weekDays(range.from) : monthGridDays(startOfMonth(anchor));
        return new Set(days
            .filter((day) => isDropBlocked(dragged, day, entries))
            .map(dayKey));
    }, [dragged, entries, view, range, anchor]);

    /**
     * Upuštění položky na jiný den — posune termín přes `POST /appointments/{id}/time`.
     *
     * <p>Nejdřív se překreslí kalendář a teprve pak jde požadavek: přesun musí být okamžitý,
     * jinak položka po upuštění na půl vteřiny „skočí" zpátky. Když server odmítne (typicky
     * blokace dílny → 422), vrátí se původní stav <strong>a řekne se proč</strong> — samotné
     * vrácení bez hlášky vypadá jako by se přetažení nepovedlo trefit.
     */
    async function handleDragEnd({active, over}) {
        setDragged(null);
        const entry = active?.data.current?.entry;
        const targetDay = over?.data.current?.date;
        if (!entry || !targetDay) {
            return;
        }

        const shifted = shiftToDay(entry, targetDay);
        if (!shifted) {
            return;   // upuštěno na týž den — není co měnit
        }

        const previous = entries;
        setEntries((list) => list.map((item) =>
            item.id === entry.id ? {...item, ...shifted} : item));

        try {
            await api.post(`/appointments/${entry.id}/time`, shifted);
            load();   // dotáhne stav ze serveru, ať se případné odchylky projeví
        } catch (err) {
            setEntries(previous);
            addAlert(problemMessage(err, "Termín se nepodařilo přesunout."), "danger");
        }
    }

    function shift(direction) {
        setAnchor((current) => view === "week"
            ? addDays(current, 7 * direction)
            : addMonths(current, direction));
    }

    function goToday() {
        setAnchor(view === "week" ? startOfWeek(new Date()) : startOfMonth(new Date()));
    }

    function openNewOn(date) {
        const start = new Date(date);
        // Předvyplní se čas otevření toho dne — nabízet osmou ráno v dílně, která otevírá
        // v sedm, znamená, že obsluha první hodinu ručně přepisuje.
        const day = openingHours?.openingHoursEnabled ? scheduleFor(openingHours, date) : null;
        const hour = day?.opensAt ? Number(day.opensAt.slice(0, 2)) : DEFAULT_ARRIVAL_HOUR;
        const minute = day?.opensAt ? Number(day.opensAt.slice(3, 5)) : 0;
        start.setHours(hour, minute, 0, 0);
        setFormState({mode: "create", defaultStart: start.toISOString()});
    }

    /**
     * Detail se DOTAHUJE ze serveru, nestačí položka z kalendáře.
     *
     * <p>Karty kreslí `ListResponse`, který schválně nenese `customerId`, `vehicleId` ani `note` —
     * do buňky se nevejdou. Předat ho rovnou do detailu a odtud do editace znamenalo, že formulář
     * neměl zákazníka ani vozidlo a uložení skončilo na 422 „U objednávky je zákazník povinný";
     * poznámka se v detailu nikdy nezobrazila. Jeden dotaz na kliknutí to řeší a `ListResponse`
     * může zůstat zúžený.
     */
    async function openDetail(entry) {
        try {
            setDetail(await api.get(`/appointments/${entry.id}`));
        } catch (err) {
            addAlert(problemMessage(err, "Detail objednávky se nepodařilo načíst."), "danger");
        }
    }

    async function handleCreate(payload) {
        await api.post("/appointments", payload);
        addAlert("Položka kalendáře byla založena.", "success");
        setFormState(null);
        load();
    }

    async function handleUpdate(payload) {
        await api.put(`/appointments/${formState.initialData.id}`, payload);
        addAlert("Změny byly uloženy.", "success");
        setFormState(null);
        load();
    }

    async function changeStatus(id, status, message) {
        try {
            await api.post(`/appointments/${id}/status`, {status});
            addAlert(message, "success");
            setDetail(null);
            load();
        } catch (err) {
            addAlert(problemMessage(err, "Stav se nepodařilo změnit."), "danger");
        }
    }

    /**
     * Vedlejší akce detailu — vše, co se dělá výjimečně nebo je nevratné.
     *
     * <p>Ve footeru zůstávají viditelná jen dvě tlačítka, kvůli kterým se detail otevírá.
     * Pět barevných tlačítek vedle sebe (červená, oranžová, červená, šedá, modrá) nedávalo
     * oku kam se dívat a kvůli šířce si vynutilo `modal-lg`, v němž pak řídký obsah plaval
     * v prázdnu. Menu je navíc týž vzor, jaký mají řádky tabulek v celé aplikaci.
     *
     * <p>Terminální objednávce (převedená, zrušená, po nedostavení) zbude v menu nanejvýš
     * „Smazat" a footer nemá žádné hlavní tlačítko — to je správně, je to uzavřený záznam.
     */
    function detailActions(entry) {
        const plannedBooking = entry.entryType === "BOOKING" && entry.status === "PLANNED";
        // Událost zná jen zrušení — „nedorazil" je pojem objednávky (V82).
        const plannedEvent = entry.entryType === "EVENT" && entry.status === "PLANNED";
        return [
            ...(plannedBooking ? [
                {id: "noShow", label: "Nedorazil", icon: <EventBusyIcon fontSize="small"/>},
                {id: "cancel", label: "Zrušit termín",
                 icon: <BlockIcon fontSize="small"/>, color: "error.main"},
            ] : []),
            ...(plannedEvent ? [
                {id: "cancel", label: "Zrušit událost",
                 icon: <BlockIcon fontSize="small"/>, color: "error.main"},
            ] : []),
            // Převedená objednávka nevznikla omylem — vzešla z ní zakázka a server smazání odmítne.
            ...(!entry.orderId ? [
                {id: "delete", label: "Smazat natrvalo",
                 icon: <DeleteForeverIcon fontSize="small"/>, color: "error.main"},
            ] : []),
        ];
    }

    function handleDetailAction(action, entry) {
        if (action === "noShow") {
            changeStatus(entry.id, "NO_SHOW", "Označeno jako nedostavení.");
        } else if (action === "cancel") {
            changeStatus(entry.id, "CANCELLED",
                entry.entryType === "EVENT" ? "Událost zrušena." : "Objednávka zrušena.");
        } else if (action === "delete") {
            setConfirmDelete(entry);
        }
    }

    async function handleDelete() {
        try {
            await api.delete(`/appointments/${confirmDelete.id}`);
            addAlert("Položka byla trvale smazána.", "success");
            setConfirmDelete(null);
            setDetail(null);
            load();
        } catch (err) {
            addAlert(problemMessage(err, "Položku se nepodařilo odstranit."), "danger");
        }
    }

    return (
        <>
            <PageHeader
                title="Plánování"
                subtitle="Objednávky termínů, blokace dílny a události"
                actions={
                    <button type="button" className="btn btn-primary"
                            onClick={() => openNewOn(new Date())}>
                        <i className="bi bi-plus-lg me-1" aria-hidden="true"></i>Nová položka
                    </button>
                }
            />

            <div className="card">
                <div className="card-body">

                    <div className="schedule-toolbar">
                        <div className="btn-group btn-group-sm" role="group" aria-label="Posun v čase">
                            <button type="button" className="btn btn-outline-secondary"
                                    onClick={() => shift(-1)} aria-label="Předchozí">
                                <i className="bi bi-chevron-left" aria-hidden="true"></i>
                            </button>
                            <button type="button" className="btn btn-outline-secondary" onClick={goToday}>
                                Dnes
                            </button>
                            <button type="button" className="btn btn-outline-secondary"
                                    onClick={() => shift(1)} aria-label="Následující">
                                <i className="bi bi-chevron-right" aria-hidden="true"></i>
                            </button>
                        </div>

                        <h2 className="schedule-range">
                            {view === "week" ? weekRangeLabel(range.from) : monthLabel(startOfMonth(anchor))}
                        </h2>

                        <div className="schedule-toolbar-right">
                            <div className="form-check schedule-filter">
                                <input className="form-check-input" type="checkbox" id="schedule-only-active"
                                       checked={onlyActive}
                                       onChange={(e) => setOnlyActive(e.target.checked)} />
                                <label className="form-check-label" htmlFor="schedule-only-active">
                                    Jen nezrušené
                                    {hiddenCount > 0 && (
                                        <span className="schedule-filter-count"> ({hiddenCount} skryto)</span>
                                    )}
                                </label>
                            </div>

                            <div className="btn-group btn-group-sm" role="group" aria-label="Zobrazení">
                                <button type="button"
                                        className={`btn ${view === "week" ? "btn-secondary" : "btn-outline-secondary"}`}
                                        onClick={() => setView("week")}>Týden</button>
                                <button type="button"
                                        className={`btn ${view === "month" ? "btn-secondary" : "btn-outline-secondary"}`}
                                        onClick={() => setView("month")}>Měsíc</button>
                            </div>
                        </div>
                    </div>

                    {loadError ? (
                        <LoadErrorState message={loadError} onRetry={load} />
                    ) : (
                        <DndContext sensors={sensors}
                                    onDragStart={({active}) => setDragged(active.data.current?.entry ?? null)}
                                    onDragEnd={handleDragEnd}
                                    onDragCancel={() => setDragged(null)}>
                            {view === "week" ? (
                                <div className="schedule-week">
                                    {weekDays(range.from).map((day) => (
                                        <ScheduleDayCard
                                            key={dayKey(day)}
                                            date={day}
                                            occurrences={occurrences.get(dayKey(day)) ?? []}
                                            onSelect={openDetail}
                                            onAdd={openNewOn}
                                            closed={isClosedDay(openingHours, day)}
                                            canManageClosures={canManageClosures}
                                            dropBlocked={blockedDays.has(dayKey(day))}
                                        />
                                    ))}
                                </div>
                            ) : (
                                <ScheduleMonth
                                    monthStart={startOfMonth(anchor)}
                                    occurrences={occurrences}
                                    openingHours={openingHours}
                                    onPickDay={(day) => {
                                        setAnchor(startOfWeek(day));
                                        setView("week");
                                    }}
                                    canManageClosures={canManageClosures}
                                    blockedDays={blockedDays}
                                />
                            )}

                            {/*
                              Tažená položka se kreslí v overlayi, ne na místě: karta dne
                              i buňka měsíce mají vlastní přetečení, takže by se objednávka
                              při tažení k okraji oříznula.
                            */}
                            <DragOverlay dropAnimation={null}>
                                {dragged && (
                                    <div className="schedule-drag-ghost">
                                        {timeOfDay(dragged.startsAt)} {appointmentPersonName(dragged) || dragged.title}
                                    </div>
                                )}
                            </DragOverlay>
                        </DndContext>
                    )}

                    {/*
                      Vysvětlivky i otevírací doba stojí ZA větvením, takže platí pro týden
                      i měsíc. Do 2026-08-04 byly jen v týdnu — měsíc přitom kreslí řádky
                      v týchž barvách stavů, takže legenda chyběla přesně tam, kde je barva
                      jediné vodítko. Při chybě načtení se neukazují: vysvětlovat prázdno nemá smysl.
                    */}
                    {!loadError && (
                        <>
                            <ScheduleLegend />
                            {openingHours?.days?.length > 0 && (
                                <p className="schedule-hours-note">
                                    <i className="bi bi-clock me-1" aria-hidden="true"></i>
                                    Otevírací doba: {summarizeSchedule(openingHours)}
                                    {!openingHours.openingHoursEnabled && (
                                        <span className="text-secondary"> — nehlídá se</span>
                                    )}
                                </p>
                            )}
                        </>
                    )}
                </div>
            </div>

            {/* ---------- formulář ---------- */}
            <Modal show={Boolean(formState)}
                   title={formState?.mode === "edit" ? "Úprava položky" : "Nová položka kalendáře"}
                   size="modal-lg"
                   onClose={() => setFormState(null)}>
                {formState && (
                    <AppointmentForm
                        initialData={formState.initialData ?? null}
                        defaultStart={formState.defaultStart ?? ""}
                        canManageClosures={canManageClosures}
                        openingHours={openingHours}
                        onSave={formState.mode === "edit" ? handleUpdate : handleCreate}
                        onCancel={() => setFormState(null)}
                    />
                )}
            </Modal>

            {/* ---------- detail ---------- */}
            {/*
              Akce jsou ve `footer` sdíleného Modalu (stejně jako u faktury, importu či položky
              zakázky) — footer je oddělený pruh, sám je zarovná doprava a dá jim mezery.
              Vlevo menu „⋯" s vedlejšími akcemi, vpravo to hlavní; dialog proto zůstává
              v běžné šířce a obsah v něm neplave.
            */}
            <Modal show={Boolean(detail)} title={detail?.title}
                   onClose={() => setDetail(null)}
                   footer={detail && (
                       <>
                           {/*
                             Popisek „Akce" je text dialogu, ne součást menu — v tabulkách jsou
                             tři tečky bez popisku správně (vzor se opakuje v každém řádku),
                             tady se s nimi uživatel potká jednou a bez slova by je přehlédl.
                           */}
                           {detailActions(detail).length > 0 && (
                               <span className="me-auto d-inline-flex align-items-center gap-1">
                                   <span className="text-secondary">Akce</span>
                                   <TableRowActionMenu
                                       rowData={detail}
                                       rowLabel={detail.title}
                                       actions={detailActions(detail)}
                                       onAction={handleDetailAction}
                                   />
                               </span>
                           )}
                           {/*
                             Terminální objednávka je uzavřený záznam — Upravit se nenabízí
                             a server to jistí 422 (APPOINTMENT_TERMINAL_READONLY).
                           */}
                           {!["CONVERTED", "NO_SHOW", "CANCELLED"].includes(detail.status) && (
                               <button type="button" className="btn btn-outline-secondary"
                                       onClick={() => {
                                           setFormState({mode: "edit", initialData: detail});
                                           setDetail(null);
                                       }}>
                                   Upravit
                               </button>
                           )}
                           {detail.entryType === "BOOKING" && detail.status === "PLANNED" && (
                               <button type="button" className="btn btn-primary"
                                       onClick={() => navigate(`/orders/new?appointmentId=${detail.id}`)}>
                                   <i className="bi bi-clipboard2-check me-1" aria-hidden="true"></i>
                                   Založit zakázku
                               </button>
                           )}
                       </>
                   )}>
                {detail && (
                <dl className="row mb-0">
                        <dt className="col-4">Typ</dt>
                        <dd className="col-8">{getAppointmentTypeLabel(detail.entryType)}</dd>

                        <dt className="col-4">Stav</dt>
                        <dd className="col-8">
                            <StatusBadge tone={getAppointmentStatusTone(detail.status)}>
                                {getAppointmentStatusLabel(detail.status)}
                            </StatusBadge>
                        </dd>

                        <dt className="col-4">Termín</dt>
                        <dd className="col-8">
                            {detail.endsAt
                                ? `${formatDateTime(detail.startsAt)} – ${formatDateTime(detail.endsAt)}`
                                : `od ${formatDateTime(detail.startsAt)}`}
                        </dd>

                        {/*
                          Zákazník i vozidlo jsou od V85 volitelné, takže se vypisuje pomlčka —
                          vynechat celý řádek by budilo dojem, že se údaj někam ztratil.
                          Kontakt je vlastní řádek jen když je vyplněný: u zákazníka z evidence
                          zůstává prázdný a prázdná kolonka „Kontakt" by mátla.
                        */}
                        {detail.entryType === "BOOKING" && (
                            <>
                                <dt className="col-4">Zákazník</dt>
                                <dd className="col-8">{detail.customerDisplayName || "—"}</dd>

                                {detail.contactNote && (
                                    <>
                                        <dt className="col-4">Kontakt</dt>
                                        <dd className="col-8">{detail.contactNote}</dd>
                                    </>
                                )}

                                <dt className="col-4">Vozidlo</dt>
                                <dd className="col-8">
                                    {[
                                        [detail.vehicleBrand, detail.vehicleModel].filter(Boolean).join(" "),
                                        detail.vehicleLicensePlate,
                                    ].filter(Boolean).join(" · ") || "—"}
                                </dd>
                            </>
                        )}

                        {detail.entryType === "EVENT" && detail.employeeDisplayName && (
                            <>
                                <dt className="col-4">Zaměstnanec</dt>
                                <dd className="col-8">{detail.employeeDisplayName}</dd>
                            </>
                        )}

                        {detail.note && (
                            <>
                                <dt className="col-4">Poznámka</dt>
                                <dd className="col-8">{detail.note}</dd>
                            </>
                        )}

                        {detail.orderId && (
                            <>
                                <dt className="col-4">Zakázka</dt>
                                <dd className="col-8">
                                    <button type="button" className="btn btn-link p-0"
                                            onClick={() => navigate(`/orders/${detail.orderId}/detail`)}>
                                        {detail.orderNumber ?? "Otevřít zakázku"}
                                    </button>
                                </dd>
                            </>
                        )}
                </dl>
                )}
            </Modal>

            <ConfirmDialog
                show={Boolean(confirmDelete)}
                title="Smazat natrvalo?"
                message={"Záznam se smaže natrvalo a nepůjde obnovit. Použijte jen u položky, "
                    + "která vznikla omylem — když zákazník jen nepřijede, zvolte „Zrušit termín“, "
                    + "objednávka pak zůstane v historii."}
                yesLabel="Smazat natrvalo"
                noLabel="Zpět"
                onConfirm={handleDelete}
                onCancel={() => setConfirmDelete(null)}
            />
        </>
    );
}
