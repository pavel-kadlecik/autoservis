import React, {useRef, useState} from "react";
import {FUEL_TYPE_OPTIONS, TRANSMISSION_OPTIONS, formatDate} from "../api/format.js";
import AutocompletePair from "./AutocompletePair.jsx";
import {api, problemMessage } from "../api/api.js";
import {useAlert} from "../context/AlertContext.jsx";
import PageHeader from "./PageHeader.jsx";
import FormSection from "./FormSection.jsx";
import RequiredMark from "./RequiredMark.jsx";
import {focusFirstInvalid} from "../api/formUtils.js";
import FormActions from "./FormActions.jsx";

/** Rozsah let pro dropdown roku výroby — posledních 30 let od aktuálního roku sestupně. */
const years = Array.from({length: 30}, (_, i) => new Date().getFullYear() - i);

const VIN_PATTERN = /^[A-HJ-NPR-Z0-9]{17}$/;

/** Typy dokladů, které přijímá dotaz do registru vozidel (dataovozidlech.cz). */
const LOOKUP_TYPES = [
    {value: "vin", label: "VIN"},
    {value: "orv", label: "Číslo ORV (malý TP)"},
    {value: "tp",  label: "Číslo TP"},
];

/**
 * Pole formuláře předvyplnitelná z registru. Přepisují jen non-null hodnoty —
 * uživatel tlačítko stiskl záměrně, takže data z registru jsou směrodatná,
 * ale chybějící údaj z registru nikdy nesmaže ručně zadaný.
 */
const PREFILL_FIELDS = ["vin", "brand", "model", "color",
    "engineDisplacementCcm", "enginePowerKw", "engineCode", "firstRegistrationDate", "fuelType"];

/**
 * Sdílený formulář pro založení i editaci vozidla.
 *
 * @param {Object}   initialData       - předvyplněné hodnoty formuláře (prázdné stringy při zakládání, data z API při editaci)
 * @param {Function} onSave(formData)  - volá se s aktuálním stavem formuláře při odeslání
 * @param {Function} [onCancel()]      - volá se při kliknutí na tlačítko zpět
 * @param {string}   title             - nadpis stránky
 */
export default function VehicleForm({initialData, onSave, onCancel, title, showInitialMileage = false}) {

    const [formData, setFormData]   = useState(initialData);
    const [validated, setValidated] = useState(false);
    const vehicleForm               = useRef(null);
    const [yearError, setYearError] = useState(false);
    const [customer, setCustomer]   = useState(initialData.customer ?? null);

    const [lookupType, setLookupType]       = useState("vin");
    const [lookupValue, setLookupValue]     = useState("");
    const [lookupLoading, setLookupLoading] = useState(false);
    const {addAlert} = useAlert();

    const lookupValueValid = lookupType === "vin"
        ? VIN_PATTERN.test(lookupValue.trim().toUpperCase())
        : lookupValue.trim() !== "";

    async function handleRegistryLookup() {
        setLookupLoading(true);
        try {
            const value = lookupType === "vin"
                ? lookupValue.trim().toUpperCase()
                : lookupValue.trim();
            const data = await api.get(
                `/vehicles/registry-lookup?${lookupType}=${encodeURIComponent(value)}`);

            setFormData(prev => {
                const next = {...prev};
                PREFILL_FIELDS.forEach(field => {
                    if (data[field] != null) next[field] = data[field];
                });
                return next;
            });

            const stkInfo = data.stkValidUntil
                ? ` STK platná do ${formatDate(data.stkValidUntil)}.`
                : "";
            addAlert(`Údaje načteny z registru vozidel.${stkInfo}`, "success");
        } catch (err) {
            const message = problemMessage(err, "Vozidlo se z registru nepodařilo načíst.");
            addAlert(message, "danger");
        } finally {
            setLookupLoading(false);
        }
    }

    function handleSave() {
        setValidated(true);

        const registrationYear  = new Date(formData.firstRegistrationDate).getFullYear();
        const yearOfManufacture = formData.yearOfManufacture;
        const isYearValid       = !registrationYear || registrationYear >= yearOfManufacture;

        setYearError(!isYearValid);

        if (vehicleForm.current.checkValidity() && isYearValid) {
            setValidated(false);
            return onSave(formData);
        } else {
            // až po překreslení — `.is-invalid` u roku výroby přidává až stav výše
            requestAnimationFrame(() => focusFirstInvalid(vehicleForm));
        }
    }

    const handleChange = (e) => {
        const {name, value} = e.target;
        setFormData(prev => ({...prev, [name]: value}));
    };

    const handleCustomerSelect = (item) => {
        const normalized = item
            ? { id: item.id, displayName: item.value, customerNumber: item.description }
            : null;
        setCustomer(normalized);
        setFormData(prev => ({ ...prev, customerId: item ? item.id : null }));
    };

    return (
        <div>
            <PageHeader title={title} />
            <p className="text-muted small">
                Pole označená <RequiredMark /> jsou povinná.
            </p>
            <form ref={vehicleForm} className={`needs-validation ${validated ? "was-validated" : ""}`} noValidate>

                <FormSection title="Zákazník">
                    <div className="row">
                        <div className="col-md-4">
                            {/* Popisek nese `AutocompletePair` jako každé jiné pole (S-11).
                                Dřív byl prázdný a chybějící výška se doháněla záporným
                                marginem, takže blok seděl výš než sousední pole. */}
                            <AutocompletePair
                                endpoint="/api/v1/customers/autocomplete"
                                name="customerId"
                                label="Zákazník"
                                placeholder="Zadejte jméno…"
                                onSelect={handleCustomerSelect}
                                initialValue={formData.customer?.displayName ?? ""}
                                initialSelectedId={formData.customerId}
                            />
                            {/* Potvrzení výběru se zobrazí, až když je co potvrdit —
                                `invisible` placeholder držel místo pro text, který tam nebyl. */}
                            {customer && (
                                <p className="mt-2 text-muted small mb-0">
                                    <strong>{customer.displayName}</strong>
                                    <span className="ms-2">{customer.customerNumber}</span>
                                </p>
                            )}
                        </div>
                    </div>
                </FormSection>

                <FormSection title="Základní údaje">
                    <div className="row mb-3">
                        <div className="col-md-4">
                            <label className="form-label" htmlFor="brand">Značka <RequiredMark /></label>
                            <input type="text" id="brand" name="brand" className="form-control"
                                   value={formData.brand} onChange={handleChange} required/>
                            <div className="invalid-feedback">Zadejte značku vozidla</div>
                        </div>
                        <div className="col-md-4">
                            <label className="form-label" htmlFor="model">Model <RequiredMark /></label>
                            <input type="text" id="model" name="model" className="form-control"
                                   value={formData.model} onChange={handleChange} required/>
                            <div className="invalid-feedback">Zadejte model vozidla</div>
                        </div>
                        <div className="col-md-4">
                            <label className="form-label" htmlFor="selectYearOfManufacture">Rok výroby</label>
                            <select id="selectYearOfManufacture" name="yearOfManufacture"
                                    className={`form-select ${yearError ? "is-invalid" : ""}`}
                                    value={formData.yearOfManufacture} onChange={handleChange}>
                                {years.map(year => <option key={year} value={year}>{year}</option>)}
                            </select>
                            <div className="invalid-feedback">Rok výroby nesmí být větší než rok první registrace</div>
                        </div>
                    </div>

                </FormSection>

                <FormSection title="Registrace a identifikace">
                    <div className="row mb-3">
                        <div className="col-md-8">
                            <label className="form-label" htmlFor="registryLookupValue">
                                Načíst z registru vozidel
                                <span className="text-muted small ms-2">(předvyplní údaje včetně VIN)</span>
                            </label>
                            <div className="input-group">
                                <select className="form-select" style={{maxWidth: "12rem"}}
                                        aria-label="Typ dokladu pro vyhledání v registru"
                                        value={lookupType} disabled={lookupLoading}
                                        onChange={(e) => setLookupType(e.target.value)}>
                                    {LOOKUP_TYPES.map(opt =>
                                        <option key={opt.value} value={opt.value}>{opt.label}</option>
                                    )}
                                </select>
                                <input type="text" id="registryLookupValue" className="form-control"
                                       placeholder={lookupType === "vin" ? "17 znaků" : "číslo dokladu"}
                                       value={lookupValue} disabled={lookupLoading}
                                       onChange={(e) => setLookupValue(e.target.value)}/>
                                <button type="button" className="btn btn-outline-secondary"
                                        onClick={handleRegistryLookup}
                                        disabled={lookupLoading || !lookupValueValid}>
                                    {lookupLoading
                                        ? <><span className="spinner-border spinner-border-sm me-2"
                                                  aria-hidden="true"></span>Načítám…</>
                                        : "Načíst z registru"}
                                </button>
                            </div>
                        </div>
                    </div>
                    <div className="row mb-3">
                        <div className="col-md-4">
                            {/* VIN je od V90 nepovinný — stroje (zahradní traktor, sekačka)
                                ho nemají. HTML pattern se na prázdnou hodnotu neaplikuje,
                                takže vyplněný VIN se dál validuje přísně. */}
                            <label className="form-label" htmlFor="vin">VIN</label>
                            <input type="text" id="vin" name="vin" className="form-control"
                                   value={formData.vin} onChange={handleChange}
                                   pattern="^[A-HJ-NPR-Z0-9]{17}$"/>
                            <div className="invalid-feedback">VIN musí mít přesně 17 znaků (A-Z bez I,O,Q a 0-9)</div>
                        </div>
                        <div className="col-md-4">
                            <label className="form-label" htmlFor="licensePlate">SPZ</label>
                            <input type="text" id="licensePlate" name="licensePlate" className="form-control"
                                   value={formData.licensePlate} onChange={handleChange} maxLength={15}/>
                        </div>
                        <div className="col-md-4">
                            <label className="form-label" htmlFor="registrationDate">Datum první registrace</label>
                            <input type="date" id="registrationDate" name="firstRegistrationDate"
                                   className="form-control" value={formData.firstRegistrationDate}
                                   onChange={handleChange}/>
                        </div>
                    </div>
                    <div className="row mb-3">
                        <div className="col-md-4">
                            <label className="form-label" htmlFor="machineSerialNumber">
                                Výrobní číslo
                                <span className="text-muted small ms-2">(stroje bez VIN)</span>
                            </label>
                            <input type="text" id="machineSerialNumber" name="machineSerialNumber"
                                   className="form-control" value={formData.machineSerialNumber}
                                   onChange={handleChange} maxLength={50}
                                   placeholder="např. 1GXKH34170"/>
                        </div>
                        <div className="col-md-4">
                            <label className="form-label" htmlFor="color">Barva</label>
                            <input type="text" id="color" name="color" className="form-control"
                                   value={formData.color} onChange={handleChange}/>
                        </div>
                    </div>

                </FormSection>

                <FormSection title="Technické specifikace">
                    <div className="row mb-3 align-items-end">
                        <div className="col-md-4">
                            <label className="form-label" htmlFor="selectFuelType">Palivo</label>
                            <select className="form-select" id="selectFuelType" name="fuelType"
                                    value={formData.fuelType} onChange={handleChange}>
                                {FUEL_TYPE_OPTIONS.map(opt =>
                                    <option key={opt.value} value={opt.value}>{opt.label}</option>
                                )}
                            </select>
                        </div>
                        <div className="col-md-4">
                            <label className="form-label" htmlFor="selectTransmissionType">Převodovka</label>
                            <select className="form-select" id="selectTransmissionType" name="transmission"
                                    value={formData.transmission} onChange={handleChange}>
                                {TRANSMISSION_OPTIONS.map(opt =>
                                    <option key={opt.value} value={opt.value}>{opt.label}</option>
                                )}
                            </select>
                        </div>
                        {showInitialMileage && (
                            <div className="col-md-4">
                                <label className="form-label" htmlFor="initialMileageKm">
                                    Najeto <span className="text-muted small">[km]</span>
                                </label>
                                <input type="number" id="initialMileageKm" name="initialMileageKm"
                                       className="form-control" value={formData.initialMileageKm}
                                       onChange={handleChange} min="0"/>
                                <div className="invalid-feedback">Najeto [km] nesmí obsahovat zápornou hodnotu</div>
                            </div>
                        )}
                    </div>
                    <div className="row mb-3">
                        <div className="col-md-4">
                            <label className="form-label" htmlFor="engineDisplacement">
                                Objem <span className="text-muted small">[ccm]</span>
                            </label>
                            <input type="number" id="engineDisplacement" name="engineDisplacementCcm"
                                   className="form-control" value={formData.engineDisplacementCcm}
                                   onChange={handleChange} min="50" max="10000"/>
                            <div className="invalid-feedback">Objem musí být v rozsahu 50 - 10000 [ccm]</div>
                        </div>
                        <div className="col-md-4">
                            <label className="form-label" htmlFor="enginePower">
                                Výkon <span className="text-muted small">[kW]</span>
                            </label>
                            <input type="number" id="enginePower" name="enginePowerKw"
                                   className="form-control" value={formData.enginePowerKw}
                                   onChange={handleChange} min="1" max="2000"/>
                            <div className="invalid-feedback">Výkon musí být v rozsahu 1 - 2000 [kW]</div>
                        </div>
                        <div className="col-md-4">
                            <label className="form-label" htmlFor="engineCode">
                                Kód motoru
                            </label>
                            <input type="text" id="engineCode" name="engineCode"
                                   className="form-control" value={formData.engineCode}
                                   onChange={handleChange} maxLength="30"
                                   placeholder="např. 642.980, CAXA, N47D20"/>
                            <div className="invalid-feedback">Kód motoru může mít maximálně 30 znaků</div>
                        </div>
                    </div>

                </FormSection>

                <FormSection title="Ostatní">
                    <div className="row mb-4">
                        <div className="col-md-12">
                            <label htmlFor="internalNote" className="form-label">Poznámky</label>
                            <textarea className="form-control" id="internalNote" name="internalNote"
                                      rows="3" value={formData.internalNote} onChange={handleChange}/>
                        </div>
                    </div>

                </FormSection>

                <FormActions onCancel={onCancel} onSubmit={handleSave} />
            </form>
        </div>
    );
}
