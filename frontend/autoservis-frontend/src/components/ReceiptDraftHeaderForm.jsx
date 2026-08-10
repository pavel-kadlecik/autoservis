import * as React from "react";
import FieldStateBadge from "./FieldStateBadge.jsx";
import StatusBadge from "./StatusBadge.jsx";
import { REQUIRED_HEADER_FIELDS } from "../api/format.js";

/**
 * Editovatelná hlavička draftu příjemky. Pracuje přímo nad kanonickým draftem
 * (ReceiptDraft.header — pole jsou TrackedField {value, state}).
 *
 * @param {Object}   header     - draft.header
 * @param {Object}   supplier   - draft.supplier
 * @param {boolean}  readOnly   - true pro CONFIRMED/REJECTED
 * @param {Function} onChange(fieldName, rawValue) - editace pole (stav řeší rodič)
 * @param {Function} [onSupplierChange(fieldName, rawValue)] - editace dodavatele
 *                   (jen když není napárovaný AUTO — ručně založené drafty)
 */
export default function ReceiptDraftHeaderForm({ header, supplier, readOnly, onChange, onSupplierChange }) {

    function field(label, name, type = "text", colClass = "col-md-4") {
        const tracked = header[name] ?? { value: null, state: "ABSENT" };
        const value = tracked.value ?? "";
        // Červeně jen povinné a prázdné pole — prázdné nepovinné (není na dokladu) neruší.
        const invalid = (value === "") && REQUIRED_HEADER_FIELDS.includes(name);
        // id/htmlFor generované z názvu pole: bez nich neměla ani jedna z deseti položek
        // hlavičky přístupný název a odečítač u „Základ bez DPH" i „Celkem s DPH" přečetl
        // jen „editace, text" (audit 11-F-16). Je to obrazovka, kde se opisují částky z faktury.
        const inputId = `hdr-${name}`;
        return (
            <div className={colClass}>
                <label className="form-label small mb-1" htmlFor={inputId}>
                    {label}
                    <FieldStateBadge state={tracked.state} />
                </label>
                <input type={type}
                       id={inputId}
                       className={`form-control form-control-sm${invalid ? " is-invalid" : ""}`}
                       value={value}
                       step={type === "number" ? "0.01" : undefined}
                       disabled={readOnly}
                       onChange={(e) => onChange(name, e.target.value)} />
            </div>
        );
    }

    const extracted = supplier?.extracted;
    const supplierEditable = !readOnly && supplier?.matchState !== "AUTO" && onSupplierChange;

    return (
        <>
            {!supplierEditable && (
                <div className="mb-2">
                    <span className="text-muted small">Dodavatel: </span>
                    <strong>{extracted?.name ?? "—"}</strong>
                    {extracted?.registrationNumber && (
                        <span className="text-muted small"> · IČO {extracted.registrationNumber}</span>
                    )}
                    {supplier?.matchState === "AUTO" && (
                        <StatusBadge tone="success" className="ms-2">nalezen v databázi</StatusBadge>
                    )}
                    {supplier?.matchState === "NONE" && (
                        <StatusBadge tone="secondary" className="ms-2">založí se při potvrzení</StatusBadge>
                    )}
                </div>
            )}
            {supplierEditable && (
                <div className="row g-2 mb-2">
                    <div className="col-md-6">
                        <label className="form-label small mb-1" htmlFor="hdr-supplier-name">Dodavatel</label>
                        <input type="text" id="hdr-supplier-name" className="form-control form-control-sm"
                               value={extracted?.name ?? ""} maxLength={255}
                               onChange={(e) => onSupplierChange("name", e.target.value)} />
                    </div>
                    <div className="col-md-3">
                        <label className="form-label small mb-1" htmlFor="hdr-supplier-ico">IČO</label>
                        <input type="text" id="hdr-supplier-ico" className="form-control form-control-sm"
                               value={extracted?.registrationNumber ?? ""} maxLength={15}
                               onChange={(e) => onSupplierChange("registrationNumber", e.target.value)} />
                    </div>
                    <div className="col-md-3 d-flex align-items-end">
                        <StatusBadge tone="secondary" className="mb-1">založí se při potvrzení</StatusBadge>
                    </div>
                </div>
            )}

            <div className="row g-2 mb-3">
                {field("Číslo dokladu", "documentNumber")}
                {field("Číslo objednávky", "orderNumber")}
                {field("Původní objednávka", "originalOrderNumber")}
                {field("Datum vystavení", "issueDate", "date")}
                {field("Splatnost", "dueDate", "date")}
                {field("DUZP", "taxableSupplyDate", "date")}
                {field("Základ (bez DPH)", "subtotal", "number")}
                {field("DPH celkem", "vatAmount", "number")}
                {field("Celkem s DPH", "totalAmount", "number")}
                {field("Měna", "currency", "text", "col-md-2")}
            </div>
        </>
    );
}
