import * as React from "react";

/**
 * Jednotný stav načítání — nahrazuje čtyři dřívější varianty
 * („Načítám...“, „Načítám…“, „Načítám“, holý spinner).
 *
 * @param {string}  [label]  - text vedle spinneru
 * @param {boolean} [inline] - true = kompaktní varianta dovnitř karty či modalu
 */
export default function LoadingState({ label = "Načítám…", inline = false }) {
    return (
        <div className={`d-flex align-items-center gap-2 text-muted ${inline ? "py-2" : "p-4"}`}
             role="status" aria-live="polite">
            <span className="spinner-border spinner-border-sm" aria-hidden="true"></span>
            <span>{label}</span>
        </div>
    );
}
