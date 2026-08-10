import * as React from "react";

/**
 * Filtrovací select s popiskem. Prázdná hodnota znamená „bez filtru“ a její
 * text se předává v `emptyLabel` (např. „Všechny stavy“).
 *
 * @param {string}   id
 * @param {string}   label
 * @param {string}   value
 * @param {Function} onChange(value)
 * @param {Array}    options       - [{value, label}], typicky *_OPTIONS z format.js
 * @param {string}   [emptyLabel]
 * @param {string}   [className]
 */
export default function SelectFilter({
    id, label, value, onChange, options, emptyLabel = "Vše", className = "col-12 col-md-3",
}) {
    return (
        <div className={className}>
            <label className="form-label" htmlFor={id}>{label}</label>
            <select id={id} className="form-select" value={value}
                    onChange={(e) => onChange(e.target.value)}>
                <option value="">{emptyLabel}</option>
                {options.map(opt => (
                    <option key={opt.value} value={opt.value}>{opt.label}</option>
                ))}
            </select>
        </div>
    );
}
