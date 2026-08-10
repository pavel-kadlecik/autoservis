import * as React from "react";

/**
 * Zaškrtávací filtr („Jen aktivní“, „Končící STK“, „Nízká dostupnost“).
 * Nahrazuje `CheckBox` s natvrdo zadaným textem i `id` a inline psané
 * `form-check` bloky ve stránkách — obojí dělalo totéž dvěma způsoby.
 *
 * @param {string}   id
 * @param {string}   label
 * @param {boolean}  checked
 * @param {Function} onChange(checked)
 * @param {string}   [className]
 */
export default function ToggleFilter({ id, label, checked, onChange, className = "col-auto" }) {
    return (
        <div className={className}>
            <div className="form-check">
                <input type="checkbox" className="form-check-input" id={id}
                       checked={checked} onChange={(e) => onChange(e.target.checked)} />
                <label className="form-check-label text-nowrap" htmlFor={id}>{label}</label>
            </div>
        </div>
    );
}
