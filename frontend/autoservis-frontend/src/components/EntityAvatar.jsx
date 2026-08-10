import * as React from "react";
import { getInitials } from "../api/format.js";

/**
 * Kolečko s iniciálami entity v hlavičce detailu. Nahrazuje čtyři kopie téhož
 * inline stylu a dvě emoji ikony (🚗/📦) — emoji mělo jen pár entit a nešlo
 * odvodit z dat, takže identita stránky vypadala pokaždé jinak.
 *
 * Výchozí velikost odpovídá `min-height` hlavičky (`.page-header` v index.css),
 * aby nadpis seděl ve stejné výšce na stránce s avatarem i bez něj.
 *
 * @param {string} name   - zobrazované jméno, ze kterého se berou iniciály
 * @param {number} [size] - průměr v px
 */
export default function EntityAvatar({ name, size = 40 }) {
    return (
        <div className="d-flex align-items-center justify-content-center flex-shrink-0 rounded-circle"
             aria-hidden="true"
             style={{
                 width: size, height: size,
                 background: "#343a40", color: "#fff",
                 fontSize: Math.round(size / 2.9), fontWeight: 500,
             }}>
            {getInitials(name)}
        </div>
    );
}
