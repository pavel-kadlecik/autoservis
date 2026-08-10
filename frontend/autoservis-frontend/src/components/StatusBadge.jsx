import * as React from "react";

/**
 * Jediný vizuální styl odznaku v aplikaci: jemný pill
 * (`bg-*-subtle text-*-emphasis rounded-pill`) — rozhodnutí R-3.
 *
 * Sémantika tónů je pevná (§4.6 analýzy) a mapy hodnota → tón žijí v `format.js`,
 * nikdy inline v komponentě:
 *
 * - `success`   — platné, dokončené, zaplacené
 * - `warning`   — čeká na akci uživatele
 * - `danger`    — chyba, propadlé, stornované
 * - `info`      — probíhá
 * - `secondary` — neaktivní, koncept, „nic“
 * - `primary`   — nové, přijaté
 *
 * @param {string}          tone       - jeden z tónů výše (neznámý spadne na `secondary`)
 * @param {React.ReactNode} children   - text odznaku
 * @param {string}          [className]- doplňkové utility třídy (odsazení apod.)
 * @param {string}          [title]    - tooltip
 */
const TONES = ["success", "warning", "danger", "info", "secondary", "primary"];

export default function StatusBadge({ tone, children, className = "", title }) {
    const safeTone = TONES.includes(tone) ? tone : "secondary";

    return (
        <span className={`badge rounded-pill bg-${safeTone}-subtle text-${safeTone}-emphasis ${className}`.trim()}
              title={title}>
            {children}
        </span>
    );
}
