import * as React from "react";

/**
 * Lišta filtrů nad tabulkou. Láme se (`row g-2`), takže při zúžení okna
 * sjedou filtry pod sebe místo aby se zkracovaly na „…“.
 *
 * Tlačítko vytvoření sem **nepatří** — to je akce stránky a patří do `PageHeader`.
 *
 * @param {string} [align] - vertikální zarovnání sloupců. Default `align-items-end`
 *   drží ovládací prvky zarovnané dole (funguje i pro checkbox bez horního labelu
 *   z `ToggleFilter` a pro víceřádkové labely). Toolbar, kde má `SearchFilter` hint
 *   (vysvětlivku pod polem) a žádný ToggleFilter, si volí `align-items-start`, aby
 *   se pole zarovnala nahoře a hint jen neškodně visel dole (viz OrdersPage).
 */
export default function ListToolbar({ children, align = "align-items-end" }) {
    return <div className={`row g-2 mb-3 ${align}`}>{children}</div>;
}
