import * as React from "react";

/**
 * Hledací pole s **viditelným** popiskem (dřív `visually-hidden`, takže sloupec
 * s hledáním vypadal proti sousednímu selectu s labelem posunutý).
 *
 * @param {string}   id           - musí být unikátní v rámci stránky
 * @param {string}   label
 * @param {string}   placeholder
 * @param {string}   value
 * @param {Function} onChange(value)
 * @param {string}   [hint]       - vysvětlivka pod polem (co všechno se prohledává)
 * @param {string}   [className]  - třídy sloupce mřížky (např. „col-12 col-xl-6“)
 */
export default function SearchFilter({
    id = "search", label = "Hledat", placeholder = "Zadejte hledaný výraz",
    value, onChange, hint, className = "col-12 col-xl-6",
}) {
    return (
        <div className={className}>
            <label className="form-label" htmlFor={id}>{label}</label>
            <div className="input-group">
                <input id={id} type="search" className="form-control"
                       placeholder={placeholder} value={value}
                       onChange={(e) => onChange(e.target.value)} />
                <span className="input-group-text">
                    <i className="bi bi-search" aria-hidden="true"></i>
                </span>
            </div>
            {hint && <div className="form-text">{hint}</div>}
        </div>
    );
}
