import * as React from "react";

/** Tón hodnoty. Barva sama o sobě nesmí nést význam (R-7) — je to zvýraznění
 *  údaje, který je jinde na stránce vysvětlený slovem. */
const TONE_CLASS = {
    danger:  "text-danger",
    success: "text-success",
    warning: "text-warning-emphasis",
};

/**
 * Dlaždice s jedním číslem nad detailem záznamu (U4.1).
 *
 * Do U4.1 byla tahle komponenta zkopírovaná ve čtyřech detailových stránkách,
 * z toho jedna kopie uměla navíc červenou hodnotu. Sjednoceno sem, `danger`
 * nahrazeno obecným `tone`.
 *
 * Jednotka se zobrazí jen u vyplněné hodnoty — „— ks" nedává smysl.
 *
 * @param {string} label
 * @param {*}      value  - `null`/`undefined` se vykreslí jako „—"
 * @param {string} [unit]
 * @param {"danger"|"success"|"warning"} [tone]
 */
export default function MetricCard({ label, value, unit, tone }) {
    const empty = value == null || value === "—";
    return (
        <div className="col-6 col-md-3">
            <div className="p-3 rounded-3 h-100" style={{ background: 'var(--bs-secondary-bg, #f8f9fa)' }}>
                <div className="text-muted small mb-1">{label}</div>
                <div className={`h5 mb-0 fw-medium ${TONE_CLASS[tone] ?? ''}`}>
                    {empty ? '—' : value}
                    {unit && !empty &&
                        <span className="small fw-normal text-muted ms-1">{unit}</span>}
                </div>
            </div>
        </div>
    );
}
