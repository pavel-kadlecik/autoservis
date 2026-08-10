import {useEffect, useState} from "react";
import {api, problemMessage} from "../api/api.js";
import {AutocompletePair} from "./AutocompletePair.jsx";
import {APPOINTMENT_TYPE_OPTIONS, fromDatetimeLocal, toDatetimeLocal, vehicleLabel} from "../api/format.js";
import {scheduleFor, shortTime, WEEKDAY_IN} from "../api/openingHours.js";

/**
 * Formulář položky kalendáře — sdílený pro založení i úpravu (§17: jeden formulář pro obojí).
 *
 * <p>Tři podoby v jednom formuláři podle `entryType`:
 * objednávka má zákazníka a vozidlo, blokace dílny jen název a čas, událost (V82) název,
 * čas a volitelného zaměstnance (dovolená). Přepnutí typu proto nepatřičná pole skryje
 * a vynuluje — jinak by je server odmítl (`CLOSURE_MUST_BE_EMPTY`, `EVENT_MUST_BE_EMPTY`,
 * `EMPLOYEE_ONLY_FOR_EVENT`). Při úpravě se typ nemění vůbec, stejně jako na serveru.
 *
 * <p>Varování o překryvu se načítá z `/appointments/overlaps` až při rozostření pole s časem,
 * ne při každém stisku klávesy — jinak by se na každé písmeno posílal dotaz.
 *
 * @param {object|null} initialData položka k úpravě, nebo `null` pro novou
 * @param {string}      defaultStart předvyplněný začátek (ISO) při zakládání klikem do kalendáře
 * @param {string}      defaultEnd   předvyplněný konec (ISO)
 * @param {boolean}     canManageClosures smí uživatel zakládat blokace dílny (ADMIN/MANAGER)
 * @param {Function}    onSave  async (payload) => void
 * @param {Function}    onCancel
 */
export default function AppointmentForm({
                                            initialData = null,
                                            defaultStart = "",
                                            defaultEnd = "",
                                            canManageClosures = false,
                                            openingHours = null,
                                            onSave,
                                            onCancel,
                                        }) {
    const isEditMode = Boolean(initialData?.id);

    const [entryType, setEntryType] = useState(initialData?.entryType ?? "BOOKING");
    const [title, setTitle] = useState(initialData?.title ?? "");
    const [note, setNote] = useState(initialData?.note ?? "");
    const [startsAt, setStartsAt] = useState(
        toDatetimeLocal(initialData?.startsAt ?? defaultStart));
    const [endsAt, setEndsAt] = useState(
        toDatetimeLocal(initialData?.endsAt ?? defaultEnd));
    const [customerId, setCustomerId] = useState(initialData?.customerId ?? "");
    const [vehicleId, setVehicleId] = useState(initialData?.vehicleId ?? "");
    const [contactNote, setContactNote] = useState(initialData?.contactNote ?? "");

    /*
     * Texty obou autocomplete polí držíme tady, protože AutocompletePair je neřízená —
     * `initialValue` čte jen při mountu. Přepsat její obsah zvenčí (doplnit majitele
     * vybraného vozidla) jde jen přemontováním, a to potřebuje znát text, který se má
     * po přemontování zobrazit.
     */
    const [customerName, setCustomerName] = useState(initialData?.customerDisplayName ?? "");
    const [vehicleName, setVehicleName] = useState(vehicleLabel(initialData));

    /*
     * Čítače vynucují přemontování — každé pole zvlášť, ať se nepřepisují navzájem:
     * výběr zákazníka vyprázdní vozidlo, výběr vozidla naopak doplní zákazníka.
     * Kdyby oba stály na `customerId` (jak to dělalo vozidlo dřív), doplnění majitele
     * by přemontovalo i vozidlo a smazalo právě vybranou SPZ.
     */
    const [customerFieldKey, setCustomerFieldKey] = useState(0);
    const [vehicleFieldKey, setVehicleFieldKey] = useState(0);
    const [employeeId, setEmployeeId] = useState(initialData?.employeeId ?? "");
    const [employees, setEmployees] = useState(null);   // null = ještě nenačteno

    /**
     * Otevírací doba dne, do kterého míří zadaný okamžik — text pod pole.
     * Vrací prázdno, když se doba nehlídá nebo datum ještě není vyplněné: mlčet je lepší
     * než psát nápovědu k rozvrhu, který stejně nikdo nevynucuje.
     */
    function hoursHintFor(localValue) {
        if (!openingHours?.openingHoursEnabled || !localValue) {
            return "";
        }
        const day = scheduleFor(openingHours, new Date(localValue));
        if (!day) {
            return "";
        }
        const den = WEEKDAY_IN[day.dayOfWeek];
        return day.opensAt
            ? `Otevřeno ${den} ${shortTime(day.opensAt)}–${shortTime(day.closesAt)}.`
            : `${den.charAt(0).toUpperCase()}${den.slice(1)} má dílna zavřeno.`;
    }

    /**
     * Časy celého pracovního dne pro den, do kterého míří příjezd — nebo `null`, když se z čeho
     * vyjít nedá (rozvrh nenačtený, hlídání vypnuté, zavřený den, prázdné pole „Od").
     *
     * <p>Vypnuté hlídání se respektuje schválně: nastavení slibuje, že se kalendář otevírací
     * dobou vůbec nezabývá, a předvyplňovat podle ní by ten slib porušilo.
     */
    function wholeDayRange() {
        if (!openingHours?.openingHoursEnabled || !startsAt) {
            return null;
        }
        const day = scheduleFor(openingHours, new Date(startsAt));
        if (!day?.opensAt) {
            return null;
        }
        const datum = startsAt.slice(0, 10);
        return {
            start: `${datum}T${day.opensAt.slice(0, 5)}`,
            end: `${datum}T${day.closesAt.slice(0, 5)}`,
        };
    }

    const celyDen = wholeDayRange();

    /*
     * Zaškrtnutí se ODVOZUJE z časů, neukládá se do stavu. Uložený příznak by se po ruční změně
     * času rozešel se skutečností a tvrdil „celý den" u objednávky od devíti do desíti.
     */
    const jeCelyDen = Boolean(celyDen) && startsAt === celyDen.start && endsAt === celyDen.end;

    function toggleWholeDay(checked) {
        if (checked && celyDen) {
            setStartsAt(celyDen.start);
            setEndsAt(celyDen.end);
        } else {
            // Odškrtnutí maže jen konec — příjezd je údaj, který obsluha zadala jako první.
            setEndsAt("");
        }
    }

    const [overlap, setOverlap] = useState(null);
    const [saving, setSaving] = useState(false);
    const [error, setError] = useState(null);

    const isClosure = entryType === "CLOSURE";
    const isEvent = entryType === "EVENT";
    const isBooking = entryType === "BOOKING";

    // Blokace ani událost nesmí mít zákazníka, vozidlo a kontakt, zaměstnanec patří jen události —
    // čistíme hned při přepnutí typu, aby se skryté hodnoty nedostaly do payloadu.
    useEffect(() => {
        if (!isBooking) {
            setCustomerId("");
            setCustomerName("");
            setVehicleId("");
            setVehicleName("");
            setContactNote("");
        }
        if (!isEvent) {
            setEmployeeId("");
        }
    }, [isBooking, isEvent]);

    // Zaměstnanci se načítají líně až pro událost — objednávky a blokace je nepotřebují.
    useEffect(() => {
        if (isEvent && employees === null) {
            api.get("/employees?activeOnly=true")
                .then(setEmployees)
                .catch(() => setEmployees([]));   // formulář musí fungovat i bez seznamu
        }
    }, [isEvent, employees]);

    /**
     * Doplní majitele právě vybraného vozidla do pole „Zákazník".
     *
     * <p>Server si zákazníka při uložení dopočítá sám (V85), takže tohle je čistě UI: obsluha
     * má hned vidět, koho systém k autu přiřadí, a nemusí uložit naslepo. Selhání requestu se
     * proto mlčí — uložení projde i tak.
     */
    async function fillOwnerFromVehicle(id) {
        try {
            const vehicle = await api.get(`/vehicles/${id}`);
            if (!vehicle?.customerId) {
                return;
            }
            setCustomerId(vehicle.customerId);
            setCustomerName(vehicle.customer?.displayName ?? "");
            setCustomerFieldKey((key) => key + 1);
        } catch {
            // Ticho záměrně: dopočet na serveru proběhne i bez zobrazení.
        }
    }

    async function refreshOverlap() {
        if (!startsAt) {
            setOverlap(null);
            return;
        }
        // Bez konce se ptáme na okamžik příjezdu — stejně jako to dělá server u uložení.
        const from = fromDatetimeLocal(startsAt);
        const params = new URLSearchParams({
            startsAt: from,
            endsAt: endsAt ? fromDatetimeLocal(endsAt) : new Date(new Date(from).getTime() + 1000).toISOString(),
        });
        if (isEditMode) {
            params.set("excludeId", String(initialData.id));
        }
        try {
            setOverlap(await api.get(`/appointments/overlaps?${params}`));
        } catch {
            // Varování je doplňková služba — jeho selhání nesmí blokovat uložení.
            setOverlap(null);
        }
    }

    async function handleSubmit(event) {
        event.preventDefault();
        setError(null);
        setSaving(true);
        try {
            await onSave({
                entryType,
                title: title.trim(),
                note: note.trim() || null,
                startsAt: fromDatetimeLocal(startsAt),
                // Prázdné pole = „konec neznámý", ne chyba — server takový tvar přijímá (V74).
                endsAt: endsAt ? fromDatetimeLocal(endsAt) : null,
                customerId: customerId ? Number(customerId) : null,
                vehicleId: vehicleId ? Number(vehicleId) : null,
                contactNote: contactNote.trim() || null,
                employeeId: employeeId ? Number(employeeId) : null,
            });
        } catch (err) {
            setError(problemMessage(err, "Položku se nepodařilo uložit."));
        } finally {
            setSaving(false);
        }
    }

    return (
        <form onSubmit={handleSubmit} noValidate>
            {error && <div className="alert alert-danger" role="alert">{error}</div>}

            {!isEditMode && (
                <div className="mb-3">
                    <label className="form-label" htmlFor="appointment-type">Typ</label>
                    <select id="appointment-type" className="form-select" value={entryType}
                            onChange={(e) => setEntryType(e.target.value)}>
                        {APPOINTMENT_TYPE_OPTIONS
                            .filter((option) => option.value !== "CLOSURE" || canManageClosures)
                            .map((option) => (
                                <option key={option.value} value={option.value}>{option.label}</option>
                            ))}
                    </select>
                    {isClosure && (
                        <div className="form-text">
                            V tomto období nepůjde nikoho objednat.
                        </div>
                    )}
                    {isEvent && (
                        <div className="form-text">
                            Zobrazí se v kalendáři, ale objednávkám nebrání — dovolená
                            jednoho mechanika dílnu nezavírá.
                        </div>
                    )}
                </div>
            )}

            <div className="mb-3">
                <label className="form-label" htmlFor="appointment-title">
                    {titleLabel(entryType)} <span className="text-danger">*</span>
                </label>
                <input id="appointment-title" className="form-control" value={title} maxLength={200}
                       required placeholder={titlePlaceholder(entryType)}
                       onChange={(e) => setTitle(e.target.value)} />
            </div>

            <div className="row g-3 mb-3">
                <div className="col-12 col-md-6">
                    <label className="form-label" htmlFor="appointment-start">
                        Od <span className="text-danger">*</span>
                    </label>
                    <input id="appointment-start" type="datetime-local" className="form-control"
                           value={startsAt} required onBlur={refreshOverlap}
                           onChange={(e) => setStartsAt(e.target.value)} />
                    {/*
                      Otevírací doba se ukazuje u pole, ne až v hlášce po uložení — obsluha
                      potřebuje vědět, do čeho míří, ještě než čas napíše. U blokace dílny se
                      nezobrazuje: tu otevírací doba nijak neomezuje (zavírat zavřenou dílnu
                      je neškodné a „dovolená celý týden" začíná o půlnoci).
                    */}
                    {isBooking && hoursHintFor(startsAt) && (
                        <div className="form-text">{hoursHintFor(startsAt)}</div>
                    )}
                </div>
                <div className="col-12 col-md-6">
                    <label className="form-label" htmlFor="appointment-end">
                        Do {!isBooking && <span className="text-danger">*</span>}
                    </label>
                    <input id="appointment-end" type="datetime-local" className="form-control"
                           value={endsAt} required={!isBooking} onBlur={refreshOverlap}
                           onChange={(e) => setEndsAt(e.target.value)} />
                    <div className="form-text">
                        {isClosure && "U blokace je konec povinný."}
                        {isEvent && "U události je konec povinný."}
                        {isBooking && "Nechte prázdné, když nevíte, jak dlouho oprava potrvá."}
                    </div>
                    {isBooking && hoursHintFor(endsAt) && (
                        <div className="form-text">{hoursHintFor(endsAt)}</div>
                    )}
                </div>
            </div>

            {/*
              Události se překryvy netýkají — neblokují a nejsou blokované; varování „dílna je
              zavřená, uložit nelze" by u dovolené lhalo (server událost do blokace pustí).
            */}
            {!isEvent && overlap?.blockedByClosure && (
                <div className="alert alert-danger" role="alert">
                    <i className="bi bi-x-octagon me-1" aria-hidden="true"></i>
                    V tomto termínu je dílna zavřená — objednávku sem uložit nelze.
                </div>
            )}
            {/*
              Týž překryv znamená u každého typu něco jiného:
                - objednávka × objednávka → dvě auta naráz, servis to běžně zvládá → jen varování,
                - blokace × objednávka → „zavřeno" a „přijede zákazník" se vylučují → tvrdý zákaz.
              Do 2026-08-07 se obojí hlásilo stejně žlutě s „Uložit přesto můžete", což u blokace
              přestalo platit ve chvíli, kdy ji server začal odmítat.
            */}
            {!isEvent && !overlap?.blockedByClosure && overlap?.overlappingCount > 0 && (
                <div className={`alert ${isClosure ? "alert-danger" : "alert-warning"}`} role="alert">
                    <i className={`bi ${isClosure ? "bi-x-octagon" : "bi-exclamation-triangle"} me-1`}
                       aria-hidden="true"></i>
                    Ve stejném čase už {overlap.overlappingCount === 1
                        ? "je 1 objednávka"
                        : `jsou objednávky: ${overlap.overlappingCount}`}.{" "}
                    {isClosure
                        ? "Blokaci sem uložit nelze — přesuňte nejdřív objednávky, nebo zavřete jindy."
                        : "Uložit přesto můžete."}
                    <ul className="mb-0 mt-2 small">
                        {overlap.overlapping.map((item) => (
                            <li key={item.id}>{item.title} — {item.customerDisplayName}</li>
                        ))}
                    </ul>
                </div>
            )}

            {/*
              Předvyplnění celého pracovního dne. Nedostupné, když se nemá z čeho vyjít —
              s vysvětlením proč, aby uživatel nehádal, co je špatně.
            */}
            <div className="form-check mb-3">
                <input className="form-check-input" type="checkbox" id="appointment-whole-day"
                       checked={jeCelyDen} disabled={!celyDen}
                       onChange={(e) => toggleWholeDay(e.target.checked)}
                       onBlur={refreshOverlap} />
                <label className="form-check-label" htmlFor="appointment-whole-day">
                    Celý den
                </label>
                <div className="form-text">
                    {celyDen
                        ? `Vyplní časy podle otevírací doby (${celyDen.start.slice(11)}–${celyDen.end.slice(11)}).`
                        : "Nejdřív vyplňte datum příjezdu ve dni, kdy má dílna otevřeno."}
                </div>
            </div>

            {/*
              Mimo otevírací dobu se jen upozorňuje (rozhodnutí uživatele 2026-08-04) — servis
              občas auto přijme mimo dobu. Blokace dílny naproti tomu ukládání zakazuje, proto
              je výš a červená.
            */}
            {isBooking && !overlap?.blockedByClosure
                && (overlap?.startOutsideOpeningHours
                    || (Boolean(endsAt) && overlap?.endOutsideOpeningHours)) && (
                <div className="alert alert-warning" role="alert">
                    <i className="bi bi-clock-history me-1" aria-hidden="true"></i>
                    {outsideHoursText(overlap, Boolean(endsAt))} Uložit přesto můžete.
                </div>
            )}

            {isEvent && (
                <div className="mb-3">
                    <label className="form-label" htmlFor="appointment-employee">Zaměstnanec</label>
                    <select id="appointment-employee" className="form-select" value={employeeId}
                            onChange={(e) => setEmployeeId(e.target.value)}>
                        <option value="">— bez vazby na zaměstnance —</option>
                        {(employees ?? []).map((employee) => (
                            <option key={employee.id} value={employee.id}>{employee.fullName}</option>
                        ))}
                    </select>
                    <div className="form-text">
                        U dovolené vyberte, koho se týká — půjde pak dohledat, kdo je pryč.
                    </div>
                </div>
            )}

            {isBooking && (
                <div className="row g-3 mb-3">
                    <div className="col-12 col-md-6">
                        {/* key: přemontování po doplnění majitele z vybraného vozidla */}
                        <AutocompletePair
                            key={`customer-${customerFieldKey}`}
                            endpoint="/api/v1/customers/autocomplete"
                            name="customerId"
                            label="Zákazník"
                            placeholder="Zadejte jméno zákazníka…"
                            initialValue={customerName}
                            initialSelectedId={customerId}
                            onSelect={(item) => {
                                setCustomerId(item?.id ?? "");
                                setCustomerName(item?.value ?? "");
                                // Vozidlo patří zákazníkovi — po jeho změně už nemusí sedět.
                                setVehicleId("");
                                setVehicleName("");
                                setVehicleFieldKey((key) => key + 1);
                            }} />
                    </div>
                    <div className="col-12 col-md-6">
                        {/* key: přemontování po změně zákazníka, aby se vyprázdnil i text v poli */}
                        <AutocompletePair
                            key={`vehicle-${vehicleFieldKey}`}
                            endpoint={customerId
                                ? `/api/v1/vehicles/autocomplete?customerId=${customerId}`
                                : "/api/v1/vehicles/autocomplete"}
                            name="vehicleId"
                            label="Vozidlo"
                            placeholder="Zadejte SPZ, značku, model nebo VIN…"
                            initialValue={vehicleName}
                            initialSelectedId={vehicleId}
                            onSelect={(item) => {
                                setVehicleId(item?.id ?? "");
                                setVehicleName(item?.value ?? "");
                                // Zákazníka doplníme jen když chybí — vyplněného nepřepisujeme,
                                // neshodu s majitelem ohlásí server (VEHICLE_NOT_OWNED_BY_CUSTOMER).
                                if (item && !customerId) {
                                    fillOwnerFromVehicle(item.id);
                                }
                            }} />
                        <div className="form-text">
                            Bez vybraného zákazníka se hledá mezi všemi vozidly — majitel se doplní
                            podle vybraného auta.
                        </div>
                    </div>
                    <div className="col-12">
                        <label className="form-label" htmlFor="appointment-contact">Kontakt</label>
                        <input id="appointment-contact" type="text" className="form-control"
                               maxLength={200} value={contactNote}
                               placeholder="Novák, 777 123 456"
                               onChange={(e) => setContactNote(e.target.value)} />
                        <div className="form-text">
                            Pro zákazníka, který v evidenci není — ať je koho zavolat, když se termín
                            posune. Zákazníka i vozidlo lze doplnit později.
                        </div>
                    </div>
                </div>
            )}

            <div className="mb-3">
                <label className="form-label" htmlFor="appointment-note">Poznámka</label>
                <textarea id="appointment-note" className="form-control" rows={2} value={note}
                          onChange={(e) => setNote(e.target.value)} />
            </div>

            <div className="d-flex justify-content-end gap-2">
                <button type="button" className="btn btn-outline-secondary"
                        onClick={onCancel} disabled={saving}>Zrušit</button>
                <button type="submit" className="btn btn-primary" disabled={saving}>
                    {saving ? "Ukládám…" : "Uložit"}
                </button>
            </div>
        </form>
    );
}

/** Popisek pole s názvem — každý typ se ptá na něco jiného. */
function titleLabel(entryType) {
    if (entryType === "CLOSURE") return "Důvod";
    if (entryType === "EVENT") return "Název události";
    return "Co se bude dělat";
}

function titlePlaceholder(entryType) {
    if (entryType === "CLOSURE") return "Státní svátek";
    if (entryType === "EVENT") return "Dovolená";
    return "Výměna oleje";
}

/**
 * Text varování „mimo otevírací dobu" — rozlišuje příjezd a vyzvednutí.
 *
 * <p>Kdyby se obě situace slily do jedné věty, obsluha by nevěděla, který čas má opravit.
 * Doba MEZI příjezdem a vyzvednutím se nehlídá schválně: auto přes noc v zavřené dílně
 * stojí běžně a vícedenní opravy na tom stojí.
 *
 * <p>`hasEnd` musí přijít z formuláře, ne z odpovědi serveru. Při prázdném poli „Do" se totiž
 * do dotazu posílá příjezd + 1 s (aby šel spočítat překryv), takže server hlásí i vyzvednutí
 * mimo dobu — a hláška by mluvila o vyzvednutí, které uživatel nezadal.
 */
function outsideHoursText(overlap, hasEnd) {
    const endOutside = hasEnd && overlap.endOutsideOpeningHours;
    if (overlap.startOutsideOpeningHours && endOutside) {
        return "Příjezd i vyzvednutí padají mimo otevírací dobu.";
    }
    return overlap.startOutsideOpeningHours
        ? "Příjezd padá mimo otevírací dobu."
        : "Vyzvednutí padá mimo otevírací dobu.";
}
