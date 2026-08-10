import * as React from "react";

/**
 * Sekce formuláře jako karta — stejný vzor jako `DetailCard`, takže detail
 * a formulář vypadají jako dvě podoby téže stránky. Nahrazuje dřívější
 * `h5.text-primary.border-bottom`.
 *
 * @param {string}          title
 * @param {React.ReactNode} [hint]     - vysvětlivka pod nadpisem sekce
 * @param {React.ReactNode} children
 */
export default function FormSection({ title, hint, children }) {
    return (
        <section className="card border-0 shadow-sm mb-3">
            <div className="card-body">
                <h2 className="h6 text-uppercase text-muted mb-1">{title}</h2>
                {hint && <p className="text-muted small mb-3">{hint}</p>}
                <div className={hint ? "" : "mt-3"}>{children}</div>
            </div>
        </section>
    );
}
