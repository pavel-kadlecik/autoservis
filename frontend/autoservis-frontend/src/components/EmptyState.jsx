import * as React from "react";

/**
 * Jednotný prázdný stav — nahrazuje čtyři dřívější varianty (řádek v tbody,
 * odstavec, alert, nic). Styl `.empty-state` žije v `index.css`.
 *
 * @param {string}          [icon]   - název Bootstrap ikony bez prefixu „bi-“
 * @param {string}          title    - hlavní věta („Zatím žádní zákazníci.“)
 * @param {React.ReactNode} [hint]   - doplňující věta drobným písmem
 * @param {React.ReactNode} [action] - tlačítko (např. „Nový zákazník“)
 */
export default function EmptyState({ icon = "inbox", title, hint, action }) {
    return (
        <div className="empty-state">
            <i className={`bi bi-${icon} d-block`} aria-hidden="true"></i>
            <p className="mb-1">{title}</p>
            {hint && <p className="small mb-3">{hint}</p>}
            {action}
        </div>
    );
}
