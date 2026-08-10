import React, {useRef, useState} from "react";
import {ORDER_STATUS_OPTIONS, getFormDate, fromDatetimeLocal, vehicleLabel} from "../api/format.js";
import AutocompletePair from "./AutocompletePair.jsx";
import {api, problemMessage } from "../api/api.js";
import OrderItemsWrapper from "./OrderItemsWrapper.jsx";
import {useAlert} from "../context/AlertContext.jsx";
import PageHeader from "./PageHeader.jsx";
import FormSection from "./FormSection.jsx";
import RequiredMark from "./RequiredMark.jsx";
import {focusFirstInvalid} from "../api/formUtils.js";
import FormActions from "./FormActions.jsx";


/**
 * Sdílený formulář pro založení i editaci servisní zakázky.
 *
 * @param initialData
 * @param initialItems
 * @param onSave
 * @param onCancel
 * @param title
 * @param readOnly
 * @param isEditMode
 * @param orderId
 * @return {React.JSX.Element}
 * @constructor
 */
export default function OrderForm({initialData, initialItems = [], onSave, onCancel, title, readOnly = {}, isEditMode = true, orderId = null, headerActions = null, cancelLabel = "Zrušit"}) {

    const [formData, setFormData] = useState(initialData);
    const [validated, setValidated] = useState(false);
    const orderForm = useRef(null);

    // Režim zakládání — vybraný zákazník a vozidlo se sledují přes autocomplete.
    // Předvyplněné hodnoty přicházejí z `initialData`, když zakázka vzniká z objednávky
    // v kalendáři (`/orders/new?appointmentId=…`) — jinak jsou obě null a pole jsou prázdná.
    const [selectedCustomer, setSelectedCustomer] = useState(
        initialData?.customerId
            ? {id: initialData.customerId, value: initialData.customerDisplayName ?? ""}
            : null);
    const [selectedVehicle, setSelectedVehicle] = useState(
        initialData?.vehicleId
            ? {id: initialData.vehicleId, value: vehicleLabel(initialData)}
            : null);

    const {addAlert} = useAlert();

    // Endpoint autocomplete vozidel — volitelně filtrovaný podle vybraného zákazníka
    const vehicleEndpoint = selectedCustomer
        ? `/api/v1/vehicles/autocomplete?customerId=${selectedCustomer.id}`
        : `/api/v1/vehicles/autocomplete`;

    function handleCustomerSelect(item) {
        setSelectedCustomer(item);
        setSelectedVehicle(null);
        setFormData(prev => ({...prev, customerId: item ? item.id : null, vehicleId: null}));
    }

    function handleVehicleSelect(item) {
        setSelectedVehicle(item);
        setFormData(prev => ({...prev, vehicleId: item ? item.id : null}));
    }

    function handleChange(e) {
        const {name, value} = e.target;
        setFormData(prev => ({...prev, [name]: value}));
    }

    function handleSave() {
        setValidated(true);

        if (!isEditMode && (!formData.customerId || !formData.vehicleId)) {
            // autocomplete není `:invalid` (hodnotu drží skrytý input), takže
            // se skáče na jeho viditelné pole ručně
            requestAnimationFrame(() => {
                const cil = orderForm.current?.querySelector(
                    !formData.customerId ? '[name="customerId"]' : '[name="vehicleId"]');
                const viditelne = cil?.closest('.col-md-5')?.querySelector('input:not([type="hidden"])');
                viditelne?.scrollIntoView({ behavior: "smooth", block: "center" });
                viditelne?.focus({ preventScroll: true });
            });
            return;
        }

        if (!orderForm.current.checkValidity()) {
            requestAnimationFrame(() => focusFirstInvalid(orderForm));
            return;
        }

        const payload = {
            ...formData,
            estimatedCompletionAt: fromDatetimeLocal(formData.estimatedCompletionAt),
            completedAt: isEditMode ? fromDatetimeLocal(formData.completedAt) : undefined,
            // Prázdné pole = „nezadáno", ne nula. Posílá se explicitní null, ať se to nespoléhá
            // na to, jak si prázdný string přebere deserializace na serveru.
            mileageKmAtIntake: formData.mileageKmAtIntake === "" || formData.mileageKmAtIntake == null
                ? null
                : Number(formData.mileageKmAtIntake),
        };

        setValidated(false);
        return onSave(payload);
    }



    return (
        <div>
            <PageHeader title={title} actions={headerActions} />

            <p className="text-muted small">
                Pole označená <RequiredMark /> jsou povinná.
            </p>
            <form ref={orderForm} className={`needs-validation ${validated ? "was-validated" : ""}`} noValidate>


                <FormSection title="Zákazník a vozidlo">
                {isEditMode ? (
                    <div className="row">
                        <div className="col-md-4">
                            <label className="form-label" htmlFor="ro-customer">Zákazník</label>
                            <input type="text" id="ro-customer" className="form-control" value={readOnly.customerDisplayName ?? ""} disabled/>
                        </div>
                        <div className="col-md-4">
                            <label className="form-label" htmlFor="ro-vehicle">Vozidlo</label>
                            <input type="text" id="ro-vehicle" className="form-control" value={readOnly.vehicleDisplayName ?? ""} disabled/>
                        </div>
                        <div className="col-md-2">
                            <label className="form-label" htmlFor="ro-plate">SPZ</label>
                            <input type="text" id="ro-plate" className="form-control" value={readOnly.vehicleLicensePlate ?? "—"} disabled/>
                        </div>
                        <div className="col-md-2">
                            <label className="form-label" htmlFor="ro-orderNumber">Číslo zakázky</label>
                            <input type="text" id="ro-orderNumber" className="form-control font-monospace" value={readOnly.orderNumber ?? "—"} disabled/>
                        </div>
                    </div>
                ) : (
                    <div className="row mb-3">
                        <div className="col-md-5">
                            <AutocompletePair
                                endpoint="/api/v1/customers/autocomplete"
                                name="customerId"
                                label="Zákazník" required
                                placeholder="Zadejte jméno zákazníka…"
                                onSelect={handleCustomerSelect}
                                initialValue={initialData?.customerDisplayName ?? ""}
                                initialSelectedId={initialData?.customerId ?? ""}
                            />
                            {validated && !formData.customerId && (
                                <div className="text-danger small mt-1">Vyberte zákazníka</div>
                            )}
                            {selectedCustomer && (
                                <p className="mt-2 text-muted small">
                                    <strong>{selectedCustomer.value}</strong>
                                    <span className="ms-2">{selectedCustomer.description}</span>
                                </p>
                            )}
                        </div>
                        <div className="col-md-5">
                            <AutocompletePair
                                key={selectedCustomer?.id ?? 'no-customer'}
                                endpoint={vehicleEndpoint}
                                name="vehicleId"
                                label="Vozidlo" required
                                placeholder={selectedCustomer ? "Zadejte vozidlo…" : "Nejprve vyberte zákazníka…"}
                                onSelect={handleVehicleSelect}
                                initialValue={vehicleLabel(initialData)}
                                initialSelectedId={initialData?.vehicleId ?? ""}
                            />
                            {validated && !formData.vehicleId && (
                                <div className="text-danger small mt-1">Vyberte vozidlo</div>
                            )}
                            {selectedVehicle && (
                                <p className="mt-2 text-muted small">
                                    <strong>{selectedVehicle.value}</strong>
                                    <span className="ms-2">{selectedVehicle.description}</span>
                                </p>
                            )}
                        </div>
                    </div>
                )}
                </FormSection>

                <FormSection title="Zakázka">
                {isEditMode && (
                    <>
                        <div className="row mb-3">
                            <div className="col-md-4">
                                <label className="form-label" htmlFor="status">
                                    Stav <RequiredMark />
                                </label>
                                <select id="status" name="status" className="form-select"
                                        value={formData.status ?? ""} onChange={handleChange} required>
                                    <option value="">— Vyberte stav —</option>
                                    {ORDER_STATUS_OPTIONS
                                        .filter(opt => opt.value !== "")
                                        .map(opt => (
                                            <option key={opt.value} value={opt.value}>{opt.label}</option>
                                        ))}
                                </select>
                                <div className="invalid-feedback">Vyberte stav zakázky</div>
                            </div>
                        </div>
                    </>
                )}

                <div className="row mb-3">
                    <div className="col-md-12">
                        <label className="form-label" htmlFor="description">
                            Popis <RequiredMark />
                        </label>
                        <textarea id="description" name="description" className="form-control"
                                  rows="4" maxLength={2000} value={formData.description ?? ""}
                                  onChange={handleChange} required/>
                        <div className="invalid-feedback">Popis zakázky je povinný</div>
                        <div className="form-text text-end text-muted">
                            {formData.description?.length ?? 0} / 2000
                        </div>
                    </div>
                </div>
                <div className="row mb-3">
                    <div className="col-md-12">
                        <label className="form-label" htmlFor="internalNote">
                            Interní poznámka{" "}
                            <span className="text-muted small">(viditelná pouze zaměstnancům)</span>
                        </label>
                        <textarea id="internalNote" name="internalNote" className="form-control"
                                  rows="3" maxLength={2000} value={formData.internalNote ?? ""}
                                  onChange={handleChange}/>
                    </div>
                </div>

                <div className="row mb-3">
                    <div className="col-md-4">
                        <label className="form-label" htmlFor="estimatedCompletionAt">
                            Odhadovaný termín dokončení
                        </label>
                        <input type="datetime-local" id="estimatedCompletionAt" name="estimatedCompletionAt"
                               className="form-control" value={formData.estimatedCompletionAt ?? ""}
                               onChange={handleChange}/>
                    </div>
                    {isEditMode && (
                        <div className="col-md-4">
                            <label className="form-label" htmlFor="completedAt">
                                Datum skutečného dokončení
                            </label>
                            <input type="datetime-local" id="completedAt" name="completedAt"
                                   className="form-control" value={formData.completedAt ?? ""}
                                   onChange={handleChange}/>
                        </div>
                    )}
                    <div className="col-md-4">
                        <label className="form-label" htmlFor="estimatedPrice">
                            Odhadovaná cena <span className="text-muted small">[Kč s DPH]</span>
                        </label>
                        <input type="number" id="estimatedPrice" name="estimatedPrice"
                               className="form-control" value={formData.estimatedPrice ?? ""}
                               onChange={handleChange} min="0" step="0.01"/>
                        <div className="invalid-feedback">Cena nesmí být záporná</div>
                    </div>
                </div>

                <div className="row mb-3">
                    <div className="col-md-4">
                        <label className="form-label" htmlFor="receivedAt">
                            Datum přijetí vozidla <RequiredMark />
                        </label>
                        <input type="date" id="receivedAt" name="receivedAt"
                               className="form-control" value={formData.receivedAt ?? ""}
                               onChange={handleChange} required/>
                        <div className="invalid-feedback">Zadejte datum přijetí vozidla</div>
                        <div className="form-text text-muted">
                            Tiskne se na zakázkovém listu. Předvyplněno dneškem, lze změnit.
                        </div>
                    </div>
                    <div className="col-md-4">
                        <label className="form-label" htmlFor="mileageKmAtIntake">
                            Stav tachometru při příjmu <span className="text-muted small">[km]</span>
                        </label>
                        <input type="number" id="mileageKmAtIntake" name="mileageKmAtIntake"
                               className="form-control" value={formData.mileageKmAtIntake ?? ""}
                               onChange={handleChange} min="0" max="9999999" step="1"/>
                        <div className="invalid-feedback">Zadejte stav tachometru v rozsahu 0–9 999 999 km</div>
                        <div className="form-text text-muted">
                            {isEditMode
                                ? "Údaj zakázkového listu. Doplnění zpětně už nezaloží odečet v historii vozidla."
                                : "Nepovinné. Vyplněný stav se zapíše i do historie tachometru vozidla."}
                        </div>
                    </div>
                </div>
                </FormSection>

                <FormActions onCancel={onCancel} onSubmit={handleSave} cancelLabel={cancelLabel}
                             submitLabel={isEditMode ? "Uložit" : "Vytvořit zakázku"} />
            </form>

            <OrderItemsWrapper
                initialItems={initialItems}
                orderId={orderId}
            />
        </div>
    );
}
