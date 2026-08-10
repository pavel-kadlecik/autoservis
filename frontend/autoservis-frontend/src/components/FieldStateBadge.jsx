import * as React from "react";
import { FIELD_STATE_META } from "../api/format.js";

/**
 * Drobná ikona vedle pole draftu příjemky vyjadřující jeho stav
 * (VERIFIED / DERIVED / DEFAULTED / ABSENT / EDITED). VERBATIM se nezobrazuje —
 * normálně přečtené pole nemá poutat pozornost.
 *
 * @param {string} state - hodnota TrackedField.state
 */
export default function FieldStateBadge({ state }) {
    const meta = FIELD_STATE_META[state];
    if (!meta || !meta.icon) return null;

    return (
        <i className={`bi ${meta.icon} ${meta.className} ms-1`}
           title={meta.label}
           aria-label={meta.label} />
    );
}
