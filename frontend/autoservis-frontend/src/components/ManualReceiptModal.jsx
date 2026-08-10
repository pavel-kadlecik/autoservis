import * as React from "react";
import Modal from "./Modal.jsx";

/**
 * Založení prázdné příjemky ručně (bez dokladu k importu). Vytvoří jen koncept —
 * hlavičku i položky vyplní uživatel v kontrolní obrazovce, naskladní se až potvrzením.
 *
 * Řízená komponenta: stav i handlery vlastní stránka příjemek.
 *
 * @param {boolean}  show
 * @param {Object}   form          - {documentType, supplierName, supplierRegistrationNumber}
 * @param {Function} setForm
 * @param {string}   [error]
 * @param {boolean}  saving
 * @param {Function} onSubmit()
 * @param {Function} onClose()
 */
export default function ManualReceiptModal({ show, form, setForm, error, saving, onSubmit, onClose }) {

    return (
        <Modal show={show} title="Nová příjemka ručně" onClose={onClose} closable={!saving}
               footer={
                   <>
                       <button type="button" className="btn btn-outline-secondary" disabled={saving} onClick={onClose}>
                           Zrušit
                       </button>
                       <button type="button" className="btn btn-primary" disabled={saving} onClick={onSubmit}>
                           {saving ? "Zakládám…" : "Založit a vyplnit"}
                       </button>
                   </>
               }>
            <p className="text-muted">
                Založí se prázdný koncept — položky a hlavičku vyplníte
                v kontrolní obrazovce, naskladní se až potvrzením.
            </p>

            <div className="mb-3">
                <label className="form-label d-block">Typ dokladu</label>
                {["INVOICE", "DELIVERY_NOTE"].map((t) => (
                    <div className="form-check form-check-inline" key={t}>
                        <input type="radio" id={`manual-${t}`} className="form-check-input"
                               name="manualDocumentType" checked={form.documentType === t}
                               onChange={() => setForm((p) => ({ ...p, documentType: t }))} />
                        <label className="form-check-label" htmlFor={`manual-${t}`}>
                            {t === "INVOICE" ? "Faktura" : "Dodací list"}
                        </label>
                    </div>
                ))}
            </div>

            <div className="mb-3">
                <label className="form-label" htmlFor="manualSupplierName">Dodavatel</label>
                <input type="text" id="manualSupplierName" className="form-control"
                       maxLength={255} value={form.supplierName}
                       onChange={(e) => setForm((p) => ({ ...p, supplierName: e.target.value }))} />
            </div>

            <div className="mb-3">
                <label className="form-label" htmlFor="manualSupplierIco">IČO</label>
                <input type="text" id="manualSupplierIco" className="form-control"
                       maxLength={15} value={form.supplierRegistrationNumber}
                       onChange={(e) => setForm((p) => ({ ...p, supplierRegistrationNumber: e.target.value }))} />
                <div className="form-text">
                    Podle IČO se dodavatel dohledá v databázi; neznámý se založí při potvrzení.
                </div>
            </div>

            {error && <div className="alert alert-danger py-2">{error}</div>}
        </Modal>
    );
}
