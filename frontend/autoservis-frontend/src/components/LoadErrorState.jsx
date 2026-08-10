import * as React from "react";
import EmptyState from "./EmptyState.jsx";

/**
 * Chyba načtení **seznamu nebo sekce** — zobrazuje se místo tabulky (audit KN-14 / 11-F-2).
 *
 * Do zavedení se selhání načtení nepoznalo od prázdna: když `GET /customers` vrátil 500, zůstal
 * seznam prázdný a uživatel přečetl „Zatím žádní zákazníci." — tedy že zákazníci nejsou. Podle
 * toho pak jednal (zakládal duplicitní kartu). Prázdný a chybový stav proto musí být rozlišené.
 *
 * Pro chybu, která zabránila zobrazení **celé stránky** (detail neexistuje, 404), je
 * {@link ErrorState} — ten nabízí cestu zpět na přehled. Tady je uživatel na seznamu, kde už je,
 * takže cesta ven není potřeba; potřebuje možnost to zkusit znovu.
 *
 * @param {string}   message     hláška ze serveru (skládá `problemMessage`)
 * @param {Function} [onRetry]   bez něj se tlačítko nevykreslí
 * @param {string}   [retryLabel]
 */
export default function LoadErrorState({ message, onRetry, retryLabel = "Zkusit znovu" }) {
    return (
        <EmptyState
            icon="exclamation-triangle"
            title={message}
            hint="Data se nepodařilo načíst — nejde o prázdný seznam."
            action={onRetry && (
                <button type="button" className="btn btn-outline-secondary" onClick={onRetry}>
                    <i className="bi bi-arrow-clockwise me-1" aria-hidden="true"></i>{retryLabel}
                </button>
            )}
        />
    );
}
