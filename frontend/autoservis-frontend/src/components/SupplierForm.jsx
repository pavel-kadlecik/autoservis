import React, { useRef, useState } from "react";
import PageHeader from "./PageHeader.jsx";
import FormSection from "./FormSection.jsx";
import RequiredMark from "./RequiredMark.jsx";
import {focusFirstInvalid} from "../api/formUtils.js";
import FormActions from "./FormActions.jsx";

/**
 * Sdílený formulář pro editaci dodavatele.
 *
 * Dodavatelé jsou jen RUD (bez ručního zakládání — vznikají importem PDF
 * faktury), takže tenhle formulář slouží výhradně k editaci.
 *
 * @param {Object}   initialData      - předvyplněné hodnoty formuláře (data z API, null hodnoty namapované na prázdné stringy)
 * @param {Function} onSave(formData) - volá se s aktuálním stavem formuláře při odeslání
 * @param {Function} onCancel()       - volá se při kliknutí na tlačítko zpět
 * @param {string}   title            - nadpis stránky
 */
export default function SupplierForm({ initialData, onSave, onCancel, title }) {

    const [formData, setFormData] = useState(initialData);
    const [validated, setValidated] = useState(false);
    const supplierForm = useRef(null);

    const handleChange = (e) => {
        const { name, value } = e.target;
        setFormData(prev => ({ ...prev, [name]: value }));
    };

    function handleSave() {
        setValidated(true);
        if (supplierForm.current.checkValidity()) {
            setValidated(false);
            return onSave(formData);
        } else {
            requestAnimationFrame(() => focusFirstInvalid(supplierForm));
        }
    }

    return (
        <div>
            <PageHeader title={title} />
            <p className="text-muted small">
                Pole označená <RequiredMark /> jsou povinná.
            </p>
            <form ref={supplierForm}
                  className={`needs-validation ${validated ? 'was-validated' : ''}`}
                  noValidate>

                <FormSection title="Základní údaje">
                    <div className="row mb-3">
                        <div className="col-md-12">
                            <label className="form-label" htmlFor="name">Název dodavatele <RequiredMark /></label>
                            <input type="text" id="name" name="name" className="form-control"
                                   value={formData.name} onChange={handleChange} maxLength={255} required/>
                            <div className="invalid-feedback">Zadejte název dodavatele</div>
                        </div>
                    </div>
                    <div className="row mb-3">
                        <div className="col-md-6">
                            <label className="form-label" htmlFor="registrationNumber">IČO</label>
                            <input type="text" id="registrationNumber" name="registrationNumber"
                                   className="form-control" value={formData.registrationNumber}
                                   onChange={handleChange} maxLength={30}/>
                        </div>
                        <div className="col-md-6">
                            <label className="form-label" htmlFor="vatId">DIČ</label>
                            <input type="text" id="vatId" name="vatId" className="form-control"
                                   value={formData.vatId} onChange={handleChange} maxLength={20}/>
                        </div>
                    </div>

                </FormSection>

                <FormSection title="Adresa">
                    <div className="row mb-3">
                        <div className="col-md-12">
                            <label className="form-label" htmlFor="street">Ulice</label>
                            <input type="text" id="street" name="street" className="form-control"
                                   value={formData.street} onChange={handleChange} maxLength={255}/>
                        </div>
                    </div>
                    <div className="row mb-3">
                        <div className="col-md-5">
                            <label className="form-label" htmlFor="city">Město</label>
                            <input type="text" id="city" name="city" className="form-control"
                                   value={formData.city} onChange={handleChange} maxLength={100}/>
                        </div>
                        <div className="col-md-3">
                            <label className="form-label" htmlFor="postalCode">PSČ</label>
                            <input type="text" id="postalCode" name="postalCode" className="form-control"
                                   value={formData.postalCode} onChange={handleChange} maxLength={10}/>
                        </div>
                        <div className="col-md-4">
                            <label className="form-label" htmlFor="countryCode">Kód země</label>
                            <input type="text" id="countryCode" name="countryCode" className="form-control"
                                   value={formData.countryCode} onChange={handleChange} maxLength={2}/>
                        </div>
                    </div>

                </FormSection>

                <FormSection title="Bankovní spojení">
                    <div className="row mb-3">
                        <div className="col-md-4">
                            <label className="form-label" htmlFor="bankAccount">Číslo účtu</label>
                            <input type="text" id="bankAccount" name="bankAccount" className="form-control"
                                   value={formData.bankAccount} onChange={handleChange} maxLength={50}/>
                        </div>
                        <div className="col-md-5">
                            <label className="form-label" htmlFor="iban">IBAN</label>
                            <input type="text" id="iban" name="iban" className="form-control"
                                   value={formData.iban} onChange={handleChange} maxLength={34}/>
                        </div>
                        <div className="col-md-3">
                            <label className="form-label" htmlFor="swift">SWIFT / BIC</label>
                            <input type="text" id="swift" name="swift" className="form-control"
                                   value={formData.swift} onChange={handleChange} maxLength={11}/>
                        </div>
                    </div>

                </FormSection>

                <FormSection title="Kontakt">
                    <div className="row mb-4">
                        <div className="col-md-6">
                            <label className="form-label" htmlFor="email">Email</label>
                            <input type="email" id="email" name="email" className="form-control"
                                   value={formData.email} onChange={handleChange} maxLength={255}/>
                            <div className="invalid-feedback">Zadejte platnou emailovou adresu</div>
                        </div>
                        <div className="col-md-6">
                            <label className="form-label" htmlFor="phone">Telefon</label>
                            <input type="text" id="phone" name="phone" className="form-control"
                                   value={formData.phone} onChange={handleChange} maxLength={30}/>
                        </div>
                    </div>

                </FormSection>

                <FormActions onCancel={onCancel} onSubmit={handleSave} />
            </form>
        </div>
    );
}
