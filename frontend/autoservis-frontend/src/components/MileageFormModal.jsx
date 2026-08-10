import React, { useEffect, useRef, useState } from "react";
import { MILEAGE_SOURCE_OPTIONS, getMileageSourceLabel, getFormDate } from "../api/format.js";
import Modal from "./Modal.jsx";

const EMPTY_FORM = { mileageKm: "", recordedDate: "", source: "SERVICE", note: "" };

/**
 * Dialog pro přidání a úpravu odečtu tachometru, nad sdílenou {@link Modal}.
 *
 * @param {boolean}  show              - zda je modal viditelný
 * @param {Object}   [reading]         - odečet k editaci; null/undefined = přidání
 * @param {boolean}  allowInitial      - nabídnout zdroj INITIAL (jen pro první odečet vozidla)
 * @param {string}   [error]           - chybová hláška k zobrazení uvnitř modalu
 * @param {boolean}  saving            - zamyká tlačítka po dobu běžícího requestu
 * @param {Function} onSubmit(form)    - volá se s hodnotami formuláře při potvrzení
 * @param {Function} onCancel()        - volá se při zavření/zrušení
 */
export default function MileageFormModal({ show, reading, allowInitial = false, error, saving, onSubmit, onCancel }) {

    const [form, setForm] = useState(EMPTY_FORM);
    const [validated, setValidated] = useState(false);
    const formRef = useRef(null);

    useEffect(() => {
        if (!show) return;
        if (reading) {
            setForm({
                mileageKm:    reading.mileageKm ?? "",
                recordedDate: reading.recordedDate ?? getFormDate(),
                source:       reading.source ?? "SERVICE",
                note:         reading.note ?? "",
            });
        } else {
            // U prvního odečtu vozidla je výchozí INITIAL (výchozí stav tachometru).
            setForm({ ...EMPTY_FORM, recordedDate: getFormDate(), source: allowInitial ? "INITIAL" : "SERVICE" });
        }
        setValidated(false);

    }, [show, reading, allowInitial]);

    if (!show) return null;

    const isEdit = Boolean(reading);
    // Editace výchozího odečtu: jeho zdroj INITIAL zůstává viditelný a zamčený.
    const isInitialEdit = isEdit && reading.source === "INITIAL";

    // INITIAL se nabízí jen při přidávání úplně prvního odečtu, nebo při editaci
    // existujícího výchozího stavu (tam zůstává zamčený, nejde přeznačit).
    const sourceOptions = (allowInitial || isInitialEdit)
        ? [{ value: "INITIAL", label: getMileageSourceLabel("INITIAL") }]
        : MILEAGE_SOURCE_OPTIONS;

    function handleChange(e) {
        const { name, value } = e.target;
        setForm(prev => ({ ...prev, [name]: value }));
    }

    function handleSubmit() {
        setValidated(true);
        if (!formRef.current.checkValidity()) return;
        onSubmit(form);
    }

    return (
        <Modal show={show} onClose={onCancel} closable={!saving}
               title={isEdit ? "Upravit čtení tachometru" : "Nové čtení tachometru"}
               footer={
                   <>
                       <button type="button" className="btn btn-outline-secondary" onClick={onCancel} disabled={saving}>
                           Zrušit
                       </button>
                       <button type="button" className="btn btn-primary" onClick={handleSubmit} disabled={saving}>
                           {isEdit ? "Uložit úpravu" : "Přidat čtení"}
                       </button>
                   </>
               }>
                        {error && <div className="alert alert-danger py-2">{error}</div>}
                        <form ref={formRef}
                              className={`needs-validation ${validated ? "was-validated" : ""}`}
                              noValidate>
                            <div className="row g-3">
                                <div className="col-md-6">
                                    <label className="form-label" htmlFor="mileageKm">
                                        Stav tachometru <span className="text-muted small">[km]</span>
                                    </label>
                                    <input type="number" id="mileageKm" name="mileageKm"
                                           className="form-control" value={form.mileageKm}
                                           onChange={handleChange} min="0" max="9999999" required/>
                                    <div className="invalid-feedback">Zadejte stav tachometru (0–9 999 999)</div>
                                </div>
                                <div className="col-md-6">
                                    <label className="form-label" htmlFor="recordedDate">Datum odečtu</label>
                                    <input type="date" id="recordedDate" name="recordedDate"
                                           className="form-control" value={form.recordedDate}
                                           onChange={handleChange} max={getFormDate()} required/>
                                    <div className="invalid-feedback">Datum nesmí být v budoucnosti</div>
                                </div>
                                <div className="col-md-6">
                                    <label className="form-label" htmlFor="source">Zdroj</label>
                                    <select id="source" name="source" className="form-select"
                                            value={form.source} onChange={handleChange}
                                            disabled={isInitialEdit} required>
                                        {sourceOptions.map(opt => (
                                            <option key={opt.value} value={opt.value}>{opt.label}</option>
                                        ))}
                                    </select>
                                    {isInitialEdit && (
                                        <div className="form-text">
                                            Počáteční čtení nelze přeřadit ani smazat, jen upravit hodnotu.
                                        </div>
                                    )}
                                </div>
                                <div className="col-12">
                                    <label className="form-label" htmlFor="note">Poznámka</label>
                                    <textarea id="note" name="note" className="form-control" rows="2"
                                              value={form.note} onChange={handleChange} maxLength={2000}/>
                                </div>
                            </div>
                        </form>
        </Modal>
    );
}
