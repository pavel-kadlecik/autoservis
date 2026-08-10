import React, {useRef, useState} from "react";
import {CONTACT_CHANNEL_OPTIONS, CUSTOMER_TYPE_OPTIONS, getFormDate} from "../api/format.js";
import {withAddressState} from "../api/customerPayload.js";
import {api, problemMessage} from "../api/api.js";
import {useAlert} from "../context/AlertContext.jsx";
import PageHeader from "./PageHeader.jsx";
import FormSection from "./FormSection.jsx";
import RequiredMark from "./RequiredMark.jsx";
import { ICO_PATTERN, ICO_MAX, DIC_PATTERN, DIC_MAX, PHONE_PATTERN, PHONE_MAX, CUSTOMER_MAX } from "../api/validation.js";
import {focusFirstInvalid} from "../api/formUtils.js";
import FormActions from "./FormActions.jsx";

/**
 * Sdílený formulář pro založení i editaci zákazníka.
 *
 * Adresy se vyplňují **jen při zakládání** — PUT /customers/{id} je nepřijímá
 * (CustomerDto.UpdateRequest pole `addresses` nemá), takže v edit režimu se
 * adresní sekce nezobrazuje a jen se připomene, kde se adresa zadává.
 *
 * @param {Object}   initialData          - předvyplněné hodnoty formuláře (prázdné stringy při zakládání, data z API při editaci)
 * @param {Function} onSave(formData)     - volá se s aktuálním stavem formuláře při odeslání
 * @param {Function} onCancel()           - volá se při kliknutí na tlačítko zpět
 * @param {string}   title                - nadpis stránky
 * @param {boolean}  [isEditMode=true]    - true = editace (výběr typu zákazníka skrytý, GDPR checkbox zamčený)
 *                                          false = zakládání (výběr typu zákazníka viditelný)
 */
export default function CustomerForm({initialData, onSave, onCancel, title, isEditMode = true}) {

    const [formData, setFormData] = useState(() => withAddressState(initialData));
    const [validated, setValidated] = useState(false);
    const customerForm = useRef(null);
    const isCompany = formData.customerType === 'COMPANY';
    const customerTypeSelected = formData.customerType !== "NONE";

    const [aresLoading, setAresLoading] = useState(false);
    const {addAlert} = useAlert();
    const icoValid = /^\d{8}$/.test((formData.ico ?? "").trim());

    /**
     * Předvyplní firmu z ARES podle IČO — stejný vzor jako registr vozidel
     * (VehicleForm): jen non-null hodnoty přepisují, chybějící údaj nikdy
     * nesmaže ručně zadaný. Adresa sídla jde do fakturační adresy.
     */
    async function handleAresLookup() {
        setAresLoading(true);
        try {
            const ico = formData.ico.trim();
            const data = await api.get(`/customers/ares-lookup?ico=${encodeURIComponent(ico)}`);

            setFormData(prev => {
                const next = {...prev};
                if (data.companyName != null) next.companyName = data.companyName;
                if (data.dic != null) next.dic = data.dic;
                const address = {};
                ["street", "streetNumber", "city", "postalCode", "countryCode"].forEach(field => {
                    if (data[field] != null) address[field] = data[field];
                });
                next.billingAddress = {...prev.billingAddress, ...address};
                return next;
            });

            addAlert("Údaje firmy načteny z ARES.", "success");
        } catch (err) {
            addAlert(problemMessage(err, "Firmu se z ARES nepodařilo načíst."), "danger");
        } finally {
            setAresLoading(false);
        }
    }

    const handleChange = (e) => {
        const {name, value} = e.target;
        const today = getFormDate();
        if (name === "birthDate" && today < value) return;
        setFormData(prev => ({...prev, [name]: value}));
    };

    const handleCheckbox = (e) => {
        const {name, checked} = e.target;
        setFormData(prev => ({...prev, [name]: checked}));
    };

    // Změna pole uvnitř vnořené adresy (billingAddress / contactAddress).
    // Nestačí běžný handleChange, protože měníme klíč uvnitř objektu,
    // ne top-level pole. První argument říká, kterou adresu upravujeme.
    const handleAddressChange = (addressKey, e) => {
        const {name, value} = e.target;
        setFormData(prev => ({
            ...prev,
            [addressKey]: {...prev[addressKey], [name]: value},
        }));
    };

    function handleSave() {
        setValidated(true);
        if (customerForm.current.checkValidity()) {
            setValidated(false);
            return onSave(formData);
        } else {
            requestAnimationFrame(() => focusFirstInvalid(customerForm));
        }
    }

    return (
        <div>
            <PageHeader title={title} />
            <p className="text-muted small">
                Pole označená <RequiredMark /> jsou povinná.
            </p>
            <form ref={customerForm}
                  className={`needs-validation ${validated ? 'was-validated' : ''}`}
                  noValidate>

                {!isEditMode && (
                    <FormSection title="Vyberte typ zákazníka">
                        <div className="col-md-4">
                            <label className="form-label" htmlFor="customerType">Typ</label>
                            <select
                                id="customerType"
                                name="customerType"
                                className="form-select mb-3"
                                value={formData.customerType}
                                onChange={handleChange}
                            >
                                {CUSTOMER_TYPE_OPTIONS.map(opt => (
                                    <option key={opt.value} value={opt.value}>{opt.label}</option>
                                ))}
                            </select>
                        </div>
                    </FormSection>
                )}

                {customerTypeSelected && (


                    <>
                        <FormSection title="Základní údaje">

                        {isCompany ? (

                            <div>
                                <div className="row mb-3">
                                    <div className="col-md-12">
                                        <label className="form-label" htmlFor="companyName">Název firmy <RequiredMark /></label>
                                        <input type="text" id="companyName" name="companyName"
                                               className="form-control" value={formData.companyName}
                                               onChange={handleChange} required
                                               maxLength={CUSTOMER_MAX.companyName}/>
                                        <div className="invalid-feedback">Zadejte název firmy</div>
                                    </div>
                                </div>
                                {/* IČO má širší sloupec (5/12) — o místo v input-group se dělí
                                    s tlačítkem ARES a v col-md-4 by nebylo vidět všech 8 číslic */}
                                <div className="row mb-3">
                                    <div className="col-md-5">
                                        <label className="form-label" htmlFor="ico">
                                            IČO
                                            <span className="text-muted small ms-2">(předvyplní název, DIČ a adresu)</span>
                                        </label>
                                        {/* invalid-feedback musí být uvnitř .input-group,
                                            jinak na něj sourozenecký CSS selektor nedosáhne */}
                                        <div className="input-group">
                                            <input type="text" id="ico" name="ico" className="form-control"
                                                   value={formData.ico} onChange={handleChange}
                                                   pattern={ICO_PATTERN} maxLength={ICO_MAX}
                                                   inputMode="numeric" disabled={aresLoading}/>
                                            <button type="button" className="btn btn-outline-secondary"
                                                    onClick={handleAresLookup}
                                                    disabled={aresLoading || !icoValid}>
                                                {aresLoading
                                                    ? <><span className="spinner-border spinner-border-sm me-2"
                                                              aria-hidden="true"></span>Načítám…</>
                                                    : "Načíst z ARES"}
                                            </button>
                                            <div className="invalid-feedback">IČO má přesně 8 číslic</div>
                                        </div>
                                    </div>
                                    <div className="col-md-4">
                                        <label className="form-label" htmlFor="dic">DIČ</label>
                                        <input type="text" id="dic" name="dic" className="form-control"
                                               value={formData.dic} onChange={handleChange}
                                               pattern={DIC_PATTERN} maxLength={DIC_MAX}
                                               placeholder="CZ12345678"/>
                                        <div className="invalid-feedback">DIČ je ve tvaru CZ a 8–10 číslic</div>
                                    </div>
                                    <div className="col-md-3">
                                        <label className="form-label" htmlFor="legalForm">Právní forma</label>
                                        <input type="text" id="legalForm" name="legalForm" className="form-control"
                                               value={formData.legalForm} onChange={handleChange}
                                               maxLength={CUSTOMER_MAX.legalForm}/>
                                    </div>
                                </div>
                            </div>
                        ) : (
                            <div className="row mb-3">
                                <div className="col-md-4">
                                    <label className="form-label" htmlFor="firstName">Jméno <RequiredMark /></label>
                                    <input type="text" id="firstName" name="firstName" className="form-control"
                                           value={formData.firstName} onChange={handleChange} required
                                           maxLength={CUSTOMER_MAX.firstName}/>
                                    <div className="invalid-feedback">Zadejte jméno zákazníka</div>
                                </div>
                                <div className="col-md-4">
                                    <label className="form-label" htmlFor="lastName">Příjmení <RequiredMark /></label>
                                    <input type="text" id="lastName" name="lastName" className="form-control"
                                           value={formData.lastName} onChange={handleChange} required
                                           maxLength={CUSTOMER_MAX.lastName}/>
                                    <div className="invalid-feedback">Zadejte příjmení zákazníka</div>
                                </div>
                                <div className="col-md-4">
                                    <label className="form-label" htmlFor="birthDate">Datum narození</label>
                                    <input type="date" id="birthDate" name="birthDate" className="form-control"
                                           value={formData.birthDate} onChange={handleChange}
                                           max={getFormDate()}/>
                                </div>
                            </div>
                        )}
                        </FormSection>

                        <FormSection title="Kontakt">
                        <div className="row">
                            <div className="col-md-4">
                                <label className="form-label" htmlFor="primaryEmail">Email</label>
                                <input type="email" id="primaryEmail" name="primaryEmail" className="form-control"
                                       value={formData.primaryEmail} onChange={handleChange}
                                       maxLength={CUSTOMER_MAX.primaryEmail}/>
                                <div className="invalid-feedback">Zadejte platnou emailovou adresu</div>
                            </div>
                            <div className="col-md-4">
                                <label className="form-label" htmlFor="primaryPhone">Telefon</label>
                                <input type="text" id="primaryPhone" name="primaryPhone" className="form-control"
                                       value={formData.primaryPhone} onChange={handleChange}
                                       pattern={PHONE_PATTERN} maxLength={PHONE_MAX}
                                       inputMode="tel"/>
                                <div className="invalid-feedback">
                                    Telefon: 7–20 znaků, jen číslice, mezery, pomlčky a závorky
                                </div>
                            </div>
                            <div className="col-md-4">
                                <label className="form-label" htmlFor="preferredContactChannel">Preferovaný kanál</label>
                                <select id="preferredContactChannel" name="preferredContactChannel"
                                        className="form-select" value={formData.preferredContactChannel}
                                        onChange={handleChange}>
                                    {CONTACT_CHANNEL_OPTIONS.map(opt => (
                                        <option key={opt.value} value={opt.value}>{opt.label}</option>
                                    ))}
                                </select>
                            </div>
                        </div>

                        </FormSection>

                        <>
                        <FormSection title="Fakturační adresa">
                        <div className="row mb-3">
                            <div className="col-md-8">
                                <label className="form-label" htmlFor="billingStreet">Ulice <RequiredMark /></label>
                                <input type="text" id="billingStreet" name="street"
                                       className="form-control" value={formData.billingAddress.street}
                                       onChange={(e) => handleAddressChange("billingAddress", e)} required/>
                                <div className="invalid-feedback">Zadejte ulici</div>
                            </div>
                            <div className="col-md-4">
                                <label className="form-label" htmlFor="billingStreetNumber">Číslo popisné <RequiredMark /></label>
                                <input type="text" id="billingStreetNumber" name="streetNumber"
                                       className="form-control" value={formData.billingAddress.streetNumber}
                                       onChange={(e) => handleAddressChange("billingAddress", e)} required/>
                                <div className="invalid-feedback">Zadejte číslo popisné</div>
                            </div>
                        </div>
                        <div className="row mb-3">
                            <div className="col-md-5">
                                <label className="form-label" htmlFor="billingCity">Město <RequiredMark /></label>
                                <input type="text" id="billingCity" name="city"
                                       className="form-control" value={formData.billingAddress.city}
                                       onChange={(e) => handleAddressChange("billingAddress", e)} required/>
                                <div className="invalid-feedback">Zadejte město</div>
                            </div>
                            <div className="col-md-3">
                                <label className="form-label" htmlFor="billingPostalCode">PSČ <RequiredMark /></label>
                                <input type="text" id="billingPostalCode" name="postalCode"
                                       className="form-control" value={formData.billingAddress.postalCode}
                                       onChange={(e) => handleAddressChange("billingAddress", e)}
                                       pattern="\d{3}\s?\d{2}" required/>
                                <div className="invalid-feedback">Zadejte PSČ ve tvaru 750 00</div>
                            </div>
                            <div className="col-md-4">
                                <label className="form-label" htmlFor="billingCountryCode">Země (kód) <RequiredMark /></label>
                                <input type="text" id="billingCountryCode" name="countryCode"
                                       className="form-control" value={formData.billingAddress.countryCode}
                                       onChange={(e) => handleAddressChange("billingAddress", e)}
                                       maxLength={2} required/>
                                <div className="invalid-feedback">Zadejte dvoupísmenný kód země</div>
                            </div>
                        </div>

                        <div className="form-check mb-3">
                            <input type="checkbox" id="hasSeparateContact" name="hasSeparateContact"
                                   className="form-check-input" checked={formData.hasSeparateContact}
                                   onChange={handleCheckbox}/>
                            <label className="form-check-label" htmlFor="hasSeparateContact">
                                Kontaktní adresa je jiná než fakturační
                            </label>
                        </div>
                        </FormSection>

                        {formData.hasSeparateContact && (
                            <>
                                <FormSection title="Kontaktní adresa">
                                <div className="row mb-3">
                                    <div className="col-md-8">
                                        <label className="form-label" htmlFor="contactStreet">Ulice <RequiredMark /></label>
                                        <input type="text" id="contactStreet" name="street"
                                               className="form-control" value={formData.contactAddress.street}
                                               onChange={(e) => handleAddressChange("contactAddress", e)} required/>
                                        <div className="invalid-feedback">Zadejte ulici</div>
                                    </div>
                                    <div className="col-md-4">
                                        <label className="form-label" htmlFor="contactStreetNumber">Číslo popisné <RequiredMark /></label>
                                        <input type="text" id="contactStreetNumber" name="streetNumber"
                                               className="form-control" value={formData.contactAddress.streetNumber}
                                               onChange={(e) => handleAddressChange("contactAddress", e)} required/>
                                        <div className="invalid-feedback">Zadejte číslo popisné</div>
                                    </div>
                                </div>
                                <div className="row mb-3">
                                    <div className="col-md-5">
                                        <label className="form-label" htmlFor="contactCity">Město <RequiredMark /></label>
                                        <input type="text" id="contactCity" name="city"
                                               className="form-control" value={formData.contactAddress.city}
                                               onChange={(e) => handleAddressChange("contactAddress", e)} required/>
                                        <div className="invalid-feedback">Zadejte město</div>
                                    </div>
                                    <div className="col-md-3">
                                        <label className="form-label" htmlFor="contactPostalCode">PSČ <RequiredMark /></label>
                                        <input type="text" id="contactPostalCode" name="postalCode"
                                               className="form-control" value={formData.contactAddress.postalCode}
                                               onChange={(e) => handleAddressChange("contactAddress", e)}
                                               pattern="\d{3}\s?\d{2}" required/>
                                        <div className="invalid-feedback">Zadejte PSČ ve tvaru 750 00</div>
                                    </div>
                                    <div className="col-md-4">
                                        <label className="form-label" htmlFor="contactCountryCode">Země (kód) <RequiredMark /></label>
                                        <input type="text" id="contactCountryCode" name="countryCode"
                                               className="form-control" value={formData.contactAddress.countryCode}
                                               onChange={(e) => handleAddressChange("contactAddress", e)}
                                               maxLength={2} required/>
                                        <div className="invalid-feedback">Zadejte dvoupísmenný kód země</div>
                                    </div>
                                </div>
                                </FormSection>
                            </>
                        )}
                        </>

                        <FormSection title="Souhlas">
                        <div className="form-check">
                            <input type="checkbox" id="gdprConsent" name="gdprConsent"
                                   className="form-check-input" checked={formData.gdprConsent}
                                   onChange={handleCheckbox} disabled={isEditMode} required={!isEditMode}/>
                            <label className="form-check-label" htmlFor="gdprConsent">
                                Souhlas se zpracováním osobních údajů (GDPR)
                                {/* povinný jen při zakládání — v editaci je pole zamčené */}
                                {!isEditMode && <> <RequiredMark /></>}
                            </label>
                            <div className="invalid-feedback">
                                Zákazník musí udělit souhlas se zpracováním osobních údajů
                            </div>
                        </div>
                        <div className="form-check">
                            <input type="checkbox" id="marketingConsent" name="marketingConsent"
                                   className="form-check-input" checked={formData.marketingConsent}
                                   onChange={handleCheckbox}/>
                            <label className="form-check-label" htmlFor="marketingConsent">
                                Souhlas se zpracováním osobních údajů pro marketingové účely a zasílání obchodních sdělení
                            </label>
                        </div>
                        </FormSection>

                        <FormSection title="Ostatní">
                        <div className="row">
                            <div className="col-md-12">
                                <label className="form-label" htmlFor="internalNote">Interní poznámka</label>
                                <textarea id="internalNote" name="internalNote" className="form-control"
                                          rows={3} value={formData.internalNote} onChange={handleChange}/>
                                <div className="form-text text-muted">
                                    Viditelná pouze zaměstnancům, zákazník ji nevidí.
                                </div>
                            </div>
                        </div>
                        </FormSection>

                        <FormActions onCancel={onCancel} onSubmit={handleSave} />
                    </>
                )}
            </form>
        </div>
    );
}
