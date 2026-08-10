import React from "react";
import { PAYMENT_METHOD_OPTIONS, getAddressTypeLabel } from "../api/format.js";
import Modal from "./Modal.jsx";
import RequiredMark from "./RequiredMark.jsx";

/**
 * Modal s formulářem pro vytvoření faktury ze servisní zakázky.
 *
 * Stav vlastní rodič (OrderForm) a předává ho dolů přes props —
 * rodič drží formData a mění je přes setFormData.
 *
 * Číslo faktury ani variabilní symbol tu nejsou — koncept je nemá, obojí se zadává
 * až v dialogu vystavení (InvoiceIssueModal). Díky tomu zrušený koncept nespálí číslo
 * a řada zůstane souvislá.
 *
 * @param {Object}   formData      - aktuální hodnoty formuláře (issueDate, dueDate, taxableSupplyDate, paymentMethod, constantSymbol, specificSymbol, purchaseOrderNumber, note)
 * @param {Function} setFormData   - state setter od rodiče
 * @param {string}   error         - chybová hláška k zobrazení (z neúspěšného odeslání), nebo prázdno
 * @param {Function} onSubmit      - volá se, když uživatel potvrdí vytvoření
 * @param {boolean}  saving        - true po dobu běžícího create requestu (zamyká tlačítka)
 * @param {boolean}  show          - zda je modal viditelný
 * @param {Function} onCancel      - volá se při zavření/zrušení modalu
 */
export default function InvoiceCreateFormModal({ formData, setFormData, addresses = [], error, onSubmit, saving, show, onCancel }) {

    function handleChange(e) {
        const { name, value } = e.target;
        setFormData(prev => ({ ...prev, [name]: value }));
    }

    if (!show) {
        return null;
    }

    return (
        <Modal show={show} title="Vytvoření faktury" onClose={onCancel} closable={!saving} size="modal-lg"
               footer={
                   <>
                       <button type="button" className="btn btn-outline-secondary" onClick={onCancel} disabled={saving}>
                           Zrušit
                       </button>
                       <button type="button" className="btn btn-primary" onClick={onSubmit} disabled={saving}>
                           {saving ? "Vytvářím…" : "Vytvořit"}
                       </button>
                   </>
               }>
                        {error && <div className="alert alert-danger py-2">{error}</div>}

                        <p className="text-muted small">Pole označená <RequiredMark /> jsou povinná.</p>

                        <div className="row g-3">
                            <div className="col-12">
                                <label className="form-label" htmlFor="billingAddressId">
                                    Fakturační adresa <RequiredMark />
                                </label>
                                {addresses.length === 0 ? (
                                    <div className="alert alert-warning py-2 mb-0">
                                        Zákazník nemá žádnou adresu. Nejprve mu ji přidejte, pak lze fakturu vystavit.
                                    </div>
                                ) : (
                                    <select id="billingAddressId" name="billingAddressId" className="form-select"
                                            value={formData.billingAddressId ?? ""} onChange={handleChange} required>
                                        <option value="">— Vyberte adresu —</option>
                                        {addresses.map(a => (
                                            <option key={a.id} value={a.id}>
                                                {getAddressTypeLabel(a.addressType)}: {a.street} {a.streetNumber}, {a.postalCode} {a.city}
                                            </option>
                                        ))}
                                    </select>
                                )}
                            </div>
                        </div>

                        <div className="row g-3 mt-1">
                            <div className="col-md-4">
                                <label className="form-label" htmlFor="issueDate">
                                    Datum vystavení <RequiredMark />
                                </label>
                                <input type="date" id="issueDate" name="issueDate"
                                       className="form-control" value={formData.issueDate}
                                       onChange={handleChange} required />
                            </div>
                            <div className="col-md-4">
                                <label className="form-label" htmlFor="dueDate">
                                    Datum splatnosti <RequiredMark />
                                </label>
                                <input type="date" id="dueDate" name="dueDate"
                                       className="form-control" value={formData.dueDate}
                                       onChange={handleChange} required />
                            </div>
                            <div className="col-md-4">
                                <label className="form-label" htmlFor="taxableSupplyDate">
                                    Datum zdanit. plnění <RequiredMark />
                                </label>
                                <input type="date" id="taxableSupplyDate" name="taxableSupplyDate"
                                       className="form-control" value={formData.taxableSupplyDate}
                                       onChange={handleChange} required />
                            </div>
                        </div>

                        <div className="row g-3 mt-1">
                            <div className="col-md-6">
                                <label className="form-label" htmlFor="paymentMethod">Způsob platby</label>
                                <select id="paymentMethod" name="paymentMethod" className="form-select"
                                        value={formData.paymentMethod} onChange={handleChange}>
                                    {PAYMENT_METHOD_OPTIONS.map(opt => (
                                        <option key={opt.value} value={opt.value}>{opt.label}</option>
                                    ))}
                                </select>
                            </div>
                        </div>

                        <div className="row g-3 mt-1">
                            <div className="col-md-4">
                                <label className="form-label" htmlFor="constantSymbol">Konstantní symbol</label>
                                <input type="text" id="constantSymbol" name="constantSymbol"
                                       className="form-control" value={formData.constantSymbol}
                                       onChange={handleChange} maxLength={15} />
                            </div>
                            <div className="col-md-4">
                                <label className="form-label" htmlFor="specificSymbol">Specifický symbol</label>
                                <input type="text" id="specificSymbol" name="specificSymbol"
                                       className="form-control" value={formData.specificSymbol}
                                       onChange={handleChange} maxLength={15} />
                            </div>
                            {/* Nákupní objednávka odběratele (V91) — číslo dodává zákazník ze svého
                                systému, proto volný text bez formátového omezení. Tiskne se na fakturu. */}
                            <div className="col-md-4">
                                <label className="form-label" htmlFor="purchaseOrderNumber">Číslo objednávky</label>
                                <input type="text" id="purchaseOrderNumber" name="purchaseOrderNumber"
                                       className="form-control" value={formData.purchaseOrderNumber}
                                       onChange={handleChange} maxLength={100}
                                       placeholder="objednávka zákazníka" />
                            </div>
                        </div>

                        <div className="row g-3 mt-1">
                            <div className="col-12">
                                <label className="form-label" htmlFor="note">Poznámka</label>
                                <textarea id="note" name="note" className="form-control" rows="2"
                                          value={formData.note} onChange={handleChange} maxLength={2000} />
                            </div>
                        </div>

                        {/* Číslo řady se přiděluje až při vystavení — jinak by zrušený koncept
                            udělal do číslování mezeru. Obsluze to musí být jasné dřív, než ho
                            v dialogu začne hledat. */}
                        <div className="alert alert-info py-2 mt-3 mb-0 small" role="note">
                            <i className="bi bi-info-circle me-1" aria-hidden="true"></i>
                            Číslo faktury a variabilní symbol se zadávají až při vystavení — koncept je ještě nemá.
                            Datum vystavení tam půjde ještě upravit.
                        </div>
        </Modal>
    );
}
