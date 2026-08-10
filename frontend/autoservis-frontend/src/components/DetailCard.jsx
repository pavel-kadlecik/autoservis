import * as React from "react";

/**
 * Sekce detailové stránky — karta s nadpisem (U4.1).
 *
 * Nadpis je `h2` (stránka má jediné `h1` v `PageHeader`), vizuálně zmenšené
 * třídou `h6`. Do U4.1 byly tyhle karty psané ručně na šesti detailech a lišily
 * se odsazením (`mb-3` vs. `mb-4`) — spodní mezeru teď drží komponenta.
 *
 * @param {React.ReactNode} [title]      - bez něj se hlavička nevykreslí (karta součtů faktury)
 * @param {React.ReactNode} [action]     - tlačítko vpravo v hlavičce karty
 * @param {boolean} [fullHeight]         - karta vyplňuje buňku mřížky (`h-100`);
 *                                         mezeru pak drží gutter řádku, ne karta
 * @param {string}  [className]
 */
export default function DetailCard({ title, action, fullHeight = false, className = "", children }) {
    const spacing = fullHeight ? "h-100" : "mb-3";
    return (
        <section className={`card border-0 shadow-sm ${spacing} ${className}`.trim()}>
            <div className="card-body">
                {title && (
                    action ? (
                        <div className="d-flex align-items-center justify-content-between mb-3">
                            <h2 className="h6 text-uppercase text-muted mb-0">{title}</h2>
                            {action}
                        </div>
                    ) : (
                        <h2 className="h6 text-uppercase text-muted mb-3">{title}</h2>
                    )
                )}
                {children}
            </div>
        </section>
    );
}
