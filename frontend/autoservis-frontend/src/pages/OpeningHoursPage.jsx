import {useEffect, useState} from "react";
import {useNavigate} from "react-router-dom";

import {api, problemMessage} from "../api/api.js";
import LoadingState from "../components/LoadingState.jsx";
import ErrorState from "../components/ErrorState.jsx";
import PageHeader from "../components/PageHeader.jsx";
import FormSection from "../components/FormSection.jsx";
import FormActions from "../components/FormActions.jsx";
import {useAlert} from "../context/AlertContext.jsx";
import {WEEKDAY_NAMES} from "../api/openingHours.js";

/**
 * Nastavení → Otevírací doba.
 *
 * <p>Rozvrh se ukládá <strong>celý týden naráz</strong> (stejně to vyžaduje server): kdyby šly dny
 * ukládat po jednom, dala by se evidence nechat v půlce — pondělí podle nového rozvrhu, úterý podle
 * starého — a nikdo by nepoznal, který stav platí.
 *
 * <p><strong>Zavřeno se zadává vyprázdněním obou časů</strong>, ne zvláštním přepínačem u každého
 * dne. Sedm přepínačů navíc by říkalo totéž co prázdná pole a šlo by je rozhodit do nesmyslného
 * stavu „zavřeno, ale otevírá se v sedm".
 */
export default function OpeningHoursPage() {
    const {addAlert} = useAlert();
    const navigate = useNavigate();

    const [days, setDays] = useState(null);
    const [enabled, setEnabled] = useState(false);
    const [loadError, setLoadError] = useState(null);
    const [saving, setSaving] = useState(false);
    const [formError, setFormError] = useState(null);

    useEffect(() => {
        api.get("/opening-hours")
            .then((data) => {
                setDays(data.days);
                setEnabled(data.openingHoursEnabled);
                setLoadError(null);
            })
            .catch((err) => setLoadError(problemMessage(err, "Otevírací dobu se nepodařilo načíst.")));
    }, []);

    function setDayTime(dayOfWeek, field, value) {
        setDays((current) => current.map((day) => day.dayOfWeek === dayOfWeek
            ? {...day, [field]: value || null}
            : day));
    }

    /** Zavřít den = vyprázdnit oba časy naráz; jinak by zbyl poloviční stav, který server odmítne. */
    function closeDay(dayOfWeek) {
        setDays((current) => current.map((day) => day.dayOfWeek === dayOfWeek
            ? {...day, opensAt: null, closesAt: null}
            : day));
    }

    function openDay(dayOfWeek) {
        setDays((current) => current.map((day) => day.dayOfWeek === dayOfWeek
            ? {...day, opensAt: "07:00:00", closesAt: "17:00:00"}
            : day));
    }

    async function handleSave() {
        setSaving(true);
        setFormError(null);
        try {
            const saved = await api.put("/opening-hours", {openingHoursEnabled: enabled, days});
            setDays(saved.days);
            setEnabled(saved.openingHoursEnabled);
            addAlert("Otevírací doba byla uložena.", "success");
        } catch (err) {
            setFormError(problemMessage(err, "Otevírací dobu se nepodařilo uložit."));
        } finally {
            setSaving(false);
        }
    }

    if (loadError) {
        return <ErrorState message={loadError}/>;
    }
    if (!days) {
        return <LoadingState/>;
    }

    return (
        <div>
            <PageHeader
                title="Otevírací doba"
                subtitle="Kdy má dílna otevřeno. Plánovací kalendář podle toho ztlumí zavřené dny a upozorní na termín mimo."
            />

            <FormSection title="Týdenní rozvrh">
                <div className="table-responsive">
                    <table className="table align-middle mb-0">
                        <thead>
                        <tr>
                            <th scope="col" style={{width: "12rem"}}>Den</th>
                            <th scope="col">Otevřeno od</th>
                            <th scope="col">Zavřeno v</th>
                            <th scope="col" style={{width: "10rem"}}></th>
                        </tr>
                        </thead>
                        <tbody>
                        {days.map((day) => {
                            const closed = !day.opensAt;
                            return (
                                <tr key={day.dayOfWeek}>
                                    <th scope="row" className="fw-normal">
                                        {WEEKDAY_NAMES[day.dayOfWeek]}
                                    </th>
                                    <td>
                                        {closed
                                            ? <span className="text-secondary">Zavřeno celý den</span>
                                            : <input type="time" className="form-control"
                                                     style={{maxWidth: "9rem"}}
                                                     aria-label={`Otevřeno od — ${WEEKDAY_NAMES[day.dayOfWeek]}`}
                                                     value={(day.opensAt ?? "").slice(0, 5)}
                                                     onChange={(e) => setDayTime(day.dayOfWeek, "opensAt",
                                                         e.target.value ? `${e.target.value}:00` : null)}/>}
                                    </td>
                                    <td>
                                        {!closed && (
                                            <input type="time" className="form-control"
                                                   style={{maxWidth: "9rem"}}
                                                   aria-label={`Zavřeno v — ${WEEKDAY_NAMES[day.dayOfWeek]}`}
                                                   value={(day.closesAt ?? "").slice(0, 5)}
                                                   onChange={(e) => setDayTime(day.dayOfWeek, "closesAt",
                                                       e.target.value ? `${e.target.value}:00` : null)}/>
                                        )}
                                    </td>
                                    <td>
                                        <button type="button" className="btn btn-sm btn-outline-secondary"
                                                onClick={() => closed
                                                    ? openDay(day.dayOfWeek)
                                                    : closeDay(day.dayOfWeek)}>
                                            {closed ? "Otevřít" : "Zavřít celý den"}
                                        </button>
                                    </td>
                                </tr>
                            );
                        })}
                        </tbody>
                    </table>
                </div>
            </FormSection>

            <FormSection title="Hlídání v kalendáři">
                <div className="form-check">
                    <input className="form-check-input" type="checkbox" id="opening-hours-enabled"
                           checked={enabled} onChange={(e) => setEnabled(e.target.checked)}/>
                    <label className="form-check-label" htmlFor="opening-hours-enabled">
                        Upozorňovat na termíny mimo otevírací dobu
                    </label>
                </div>
                <div className="form-text">
                    {/*
                      Vědomě jen upozornění, ne zákaz (rozhodnutí uživatele 2026-08-04): servis
                      občas auto přijme mimo dobu a systém mu v tom nemá bránit. Týž princip jako
                      u překryvu objednávek — kdo stojí v dílně, ví to líp než systém.
                    */}
                    Objednávku mimo otevírací dobu půjde uložit i tak — je to upozornění, ne zákaz.
                    Vypnuté hlídání znamená, že se kalendář otevírací dobou vůbec nezabývá.
                </div>
            </FormSection>

            {formError && <div className="alert alert-danger py-2">{formError}</div>}

            <FormActions onCancel={() => navigate(-1)} onSubmit={handleSave} saving={saving}/>
        </div>
    );
}
