import * as React from "react";
import { useNavigate } from "react-router-dom";

/**
 * Jednotná hlavička stránky — používá ji seznam, detail i formulář, aby měly
 * všechny stránky stejný nadpis, stejné umístění akcí a stejné chování při
 * zúžení okna (hlavička se láme, nic se nezkracuje na „…“).
 *
 * Barvy akčních tlačítek se řídí §7.1 analýzy (docs/analyza-ui-2026-07.md):
 * nejvýš jedno plné tlačítko na obrazovku — modré `btn-primary` u vratné akce,
 * zelené `btn-success` u nevratného posunu procesu; neutrální akce
 * `btn-outline-secondary`, rušící `btn-outline-danger` vždy jako poslední.
 *
 * **Odsazení pod hlavičkou vlastní výhradně tato komponenta** (`mb-4`). Stránka
 * pod ni nikdy nepřidává vlastní margin a nikdy nerenderuje vlastní `<h1>` —
 * jinak by odstup záležel na tom, co je zrovna pod nadpisem (nadpisy nemají
 * marginy, viz `css/reset.css`).
 *
 * @param {string}          title      - nadpis stránky (vždy h1 ve velikosti .h3)
 * @param {React.ReactNode} [subtitle] - doprovodná identifikace (číslo zákazníka, VIN, SKU)
 * @param {React.ReactNode} [badges]   - stavové odznaky vedle nadpisu
 * @param {React.ReactNode} [avatar]   - `EntityAvatar` v hlavičce detailu
 * @param {string}          [backTo]   - cesta pro tlačítko zpět vlevo od nadpisu; bez ní se nezobrazí
 * @param {string}          [backLabel]- popisek pro čtečky (default „Zpět“)
 * @param {React.ReactNode} [actions]  - tlačítka vpravo
 */
export default function PageHeader({ title, subtitle, badges, avatar, backTo, backLabel = "Zpět", actions }) {

    const navigate = useNavigate();

    return (
        <div className="page-header mb-4">
            {/* Titulní řádek je samostatný, aby podtitulek nadpisem nehýbal —
                nadpis pak začíná na každé stránce ve stejné výšce. */}
            <div className="page-header-row d-flex flex-wrap align-items-center gap-3">
                {backTo && (
                    <button type="button" className="btn btn-sm btn-outline-secondary flex-shrink-0"
                            onClick={() => navigate(backTo)}
                            title={backLabel} aria-label={backLabel}>
                        <i className="bi bi-arrow-left" aria-hidden="true"></i>
                    </button>
                )}

                {avatar}

                <h1 className="h3 mb-0">{title}</h1>
                {badges}

                {actions && <div className="ms-auto d-flex flex-wrap gap-2">{actions}</div>}
            </div>

            {subtitle && <div className="text-muted small mt-1">{subtitle}</div>}
        </div>
    );
}
