import React from "react";
import { ORDER_ITEM_TYPE_OPTIONS } from "../api/format.js";
import { ALLOWED_UNITS, LABOR_UNITS } from "../api/units.js";
import Modal from "./Modal.jsx";
import RequiredMark from "./RequiredMark.jsx";

/**
 * Modal pro přidání/editaci položky zakázky (staví na sdílené komponentě Modal).
 *
 * Řízená komponenta: stav (itemForm) i handlery vlastní rodič.
 * U skladové položky (itemForm.fromStock === true) jsou pole daná původem zamčená.
 */
export default function OrderItemFormModal({ show, editingItemId, itemForm, itemError, employees = [], onChange, onSave, onCancel }) {

    if (!show) return null;

    const isEdit = Boolean(editingItemId);
    const locked = itemForm.fromStock;   // skladová položka → zamčená pole
    const isLabor = itemForm.itemType === "LABOR";
    // Práci lze účtovat po hodinách i po kusech (paušál za úkon, rozhodnutí uživatele
    // 2026-08-03). Zbytek číselníku (kg, litry, sada…) u práce smysl nedává, tak ho
    // nenabízíme — dřív byla jednotka u LABOR natvrdo zamčená na „hod".
    const unitOptions = isLabor ? LABOR_UNITS : ALLOWED_UNITS;
    // Přiřazený mechanik, který už není v aktivním číselníku (odešel) — ukázat jako „(mimo číselník)".
    const assignedOutOfList = isLabor && itemForm.employeeId
        && !employees.some(emp => String(emp.id) === String(itemForm.employeeId));

    return (
        <Modal show={show} size="modal-lg" onClose={onCancel}
               title={isEdit ? "Upravit položku" : "Nová položka"}
               footer={
                   <>
                       <button type="button" className="btn btn-outline-secondary" onClick={onCancel}>
                           Zrušit
                       </button>
                       <button type="button" className="btn btn-primary" onClick={onSave}>
                           {isEdit ? "Uložit úpravu" : "Přidat položku"}
                       </button>
                   </>
               }>
                        {itemError && <div className="alert alert-danger py-2">{itemError}</div>}

                        <div className="row g-3">
                            <div className="col-md-4">
                                <label className="form-label" htmlFor="item-itemType">Typ</label>
                                <select id="item-itemType" name="itemType" className="form-select" disabled={locked}
                                        value={itemForm.itemType} onChange={onChange}>
                                    {ORDER_ITEM_TYPE_OPTIONS.map(opt => (
                                        <option key={opt.value} value={opt.value}>{opt.label}</option>
                                    ))}
                                </select>
                            </div>
                            <div className="col-md-8">
                                <label className="form-label" htmlFor="item-name">Název <RequiredMark /></label>
                                <input type="text" id="item-name" name="name" className="form-control"
                                       value={itemForm.name} onChange={onChange} maxLength={255}/>
                            </div>

                            <div className="col-md-3">
                                <label className="form-label" htmlFor="item-quantity">Množství</label>
                                {/* Množství jde měnit i u skladové položky (V83): dokud je díl
                                    jen rezervovaný, mění se pouhý slib a sklad se nehne; u už
                                    vydaného dorovná rozdíl protipohyb. Dřív bylo pole zamčené
                                    a zadané číslo se tiše zahodilo. */}
                                <input type="number" id="item-quantity" name="quantity" className="form-control"
                                       value={itemForm.quantity} onChange={onChange} min="0.01" step="0.01"/>
                            </div>
                            <div className="col-md-3">
                                <label className="form-label" htmlFor="item-unit">Jednotka</label>
                                <select id="item-unit" name="unit" className="form-select"
                                        disabled={locked}
                                        value={itemForm.unit} onChange={onChange}>
                                    {itemForm.unit && !unitOptions.includes(itemForm.unit) && (
                                        <option value={itemForm.unit}>{itemForm.unit} (mimo číselník)</option>
                                    )}
                                    {unitOptions.map(u => <option key={u} value={u}>{u}</option>)}
                                </select>
                                {isLabor && (
                                    <div className="form-text">
                                        {itemForm.unit === "hod"
                                            ? "Sazba mechanika se předvyplní do nákupní ceny."
                                            : "Paušál za úkon — nákupní cenu zadejte sami."}
                                    </div>
                                )}
                            </div>
                            <div className="col-md-3">
                                <label className="form-label" htmlFor="item-vatRate">DPH %</label>
                                <input type="number" name="vatRate" className="form-control" disabled={locked}
                                       value={itemForm.vatRate} onChange={onChange} min="0" max="100"/>
                            </div>

                            {isLabor && (
                                <div className="col-12">
                                    <label className="form-label" htmlFor="employeeId">Mechanik</label>
                                    <select id="employeeId" name="employeeId" className="form-select"
                                            value={itemForm.employeeId ?? ""} onChange={onChange}>
                                        <option value="">— nepřiřazeno —</option>
                                        {assignedOutOfList && (
                                            <option value={itemForm.employeeId}>
                                                {(itemForm.employeeName || `#${itemForm.employeeId}`)} (mimo číselník)
                                            </option>
                                        )}
                                        {employees.map(emp => (
                                            <option key={emp.id} value={emp.id}>{emp.fullName}</option>
                                        ))}
                                    </select>
                                    <div className="form-text">
                                        {itemForm.unit === "hod"
                                            ? "Po výběru se do nákupní ceny předvyplní jeho hodinová sazba (jde přepsat)."
                                            : "Sazba se nepředvyplní — u paušálu za úkon by hodinová sazba byla špatné číslo."}
                                    </div>
                                </div>
                            )}

                            <div className="col-md-6">
                                <label className="form-label" htmlFor="item-purchasePrice">Nákupní cena [Kč bez DPH]</label>
                                <input type="number" id="item-purchasePrice" name="purchasePrice" className="form-control" disabled={locked}
                                       value={itemForm.purchasePrice} onChange={onChange} min="0" step="0.01"/>
                            </div>
                            <div className="col-md-6">
                                <label className="form-label" htmlFor="item-unitPrice">Prodejní cena [Kč bez DPH] <RequiredMark /></label>
                                <input type="number" id="item-unitPrice" name="unitPrice" className="form-control"
                                       value={itemForm.unitPrice} onChange={onChange} min="0" step="0.01"/>
                            </div>

                            <div className="col-12">
                                <label className="form-label" htmlFor="item-note">Poznámka</label>
                                <input type="text" id="item-note" name="note" className="form-control"
                                       value={itemForm.note} onChange={onChange} maxLength={500}/>
                            </div>
                        </div>

                        {locked && (
                            <div className="form-text mt-2">
                                Skladová položka — jednotka, nákupní cena a DPH se řídí příjemkou a nelze je zde měnit.
                                Množství upravit lze: dokud díl neodešel ze skladu, změní se jen rezervace; u už vydaného
                                se rozdíl srovná skladovým pohybem.
                            </div>
                        )}
        </Modal>
    );
}