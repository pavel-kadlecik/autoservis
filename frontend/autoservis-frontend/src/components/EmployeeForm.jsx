import React, { useRef, useState } from "react";
import PageHeader from "./PageHeader.jsx";
import FormSection from "./FormSection.jsx";
import RequiredMark from "./RequiredMark.jsx";
import FormActions from "./FormActions.jsx";
import { focusFirstInvalid } from "../api/formUtils.js";

/**
 * Sdílený formulář pro založení i editaci zaměstnance (vzor UserForm/VehicleForm).
 *
 * Hodinová sazba je náklad práce — mění se jen „do budoucna": při přiřazení mechanika
 * k položce se snímkuje do nákupní ceny položky, takže úprava sazby zde nezmění už
 * rozpracované ani historické zakázky (D-3).
 *
 * @param {Object}   initialData       - předvyplněné hodnoty (prázdné pro create, data z API pro edit)
 * @param {Function} onSave(formData)  - zavolá se se stavem formuláře při odeslání
 * @param {Function} onCancel()        - zavolá se při zrušení
 * @param {string}   title             - nadpis stránky
 * @param {boolean}  [saving=false]
 */
export default function EmployeeForm({ initialData, onSave, onCancel, title, saving = false }) {

    const [formData, setFormData] = useState(initialData);
    const [validated, setValidated] = useState(false);
    const formRef = useRef(null);

    const handleChange = (e) => {
        const { name, value } = e.target;
        setFormData(prev => ({ ...prev, [name]: value }));
    };

    function handleSave() {
        setValidated(true);
        if (formRef.current.checkValidity()) {
            setValidated(false);
            return onSave(formData);
        } else {
            requestAnimationFrame(() => focusFirstInvalid(formRef));
        }
    }

    return (
        <div>
            <PageHeader title={title} />
            <p className="text-muted small">
                Pole označená <RequiredMark /> jsou povinná.
            </p>
            <form ref={formRef}
                  className={`needs-validation ${validated ? 'was-validated' : ''}`}
                  noValidate>

                <FormSection title="Osobní údaje">
                    <div className="row g-3">
                        <div className="col-md-6">
                            <label className="form-label" htmlFor="firstName">Jméno <RequiredMark /></label>
                            <input type="text" id="firstName" name="firstName" className="form-control"
                                   value={formData.firstName} onChange={handleChange}
                                   maxLength={100} required />
                            <div className="invalid-feedback">Zadejte jméno</div>
                        </div>
                        <div className="col-md-6">
                            <label className="form-label" htmlFor="lastName">Příjmení <RequiredMark /></label>
                            <input type="text" id="lastName" name="lastName" className="form-control"
                                   value={formData.lastName} onChange={handleChange}
                                   maxLength={100} required />
                            <div className="invalid-feedback">Zadejte příjmení</div>
                        </div>
                        <div className="col-md-6">
                            <label className="form-label" htmlFor="position">Pozice</label>
                            <input type="text" id="position" name="position" className="form-control"
                                   value={formData.position} onChange={handleChange}
                                   maxLength={100} placeholder="např. Automechanik" />
                        </div>
                        <div className="col-md-6">
                            <label className="form-label" htmlFor="hourlyRate">Hodinová sazba [Kč]</label>
                            <input type="number" id="hourlyRate" name="hourlyRate" className="form-control"
                                   value={formData.hourlyRate} onChange={handleChange}
                                   min="0" step="0.01" />
                            <div className="form-text">Náklad za hodinu práce. Propíše se do položky práce jako nákupní cena a zmrazí se do zakázky.</div>
                        </div>
                    </div>
                </FormSection>

                <FormSection title="Zaměstnanecký poměr">
                    <div className="row g-3">
                        <div className="col-md-6">
                            <label className="form-label" htmlFor="hiredAt">Datum nástupu <RequiredMark /></label>
                            <input type="date" id="hiredAt" name="hiredAt" className="form-control"
                                   value={formData.hiredAt} onChange={handleChange} required />
                            <div className="invalid-feedback">Zadejte datum nástupu</div>
                        </div>
                        <div className="col-md-6">
                            <label className="form-label" htmlFor="leftAt">Datum odchodu</label>
                            <input type="date" id="leftAt" name="leftAt" className="form-control"
                                   value={formData.leftAt} onChange={handleChange}
                                   min={formData.hiredAt || undefined} />
                            <div className="form-text">Prázdné = stále zaměstnán.</div>
                        </div>
                    </div>
                </FormSection>

                <FormActions onCancel={onCancel} onSubmit={handleSave} saving={saving} />
            </form>
        </div>
    );
}
