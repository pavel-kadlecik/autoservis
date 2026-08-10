import * as React from "react";
import TableRowActionMenu from "./TableRowActionMenu.jsx";

/**
 * Jediná tabulka v aplikaci. Sjednocuje čtyři dřívější způsoby řádkových akcí
 * (třitečkové menu, btn-group s texty, dvojice textových tlačítek, klik na řádek)
 * na jeden: **sloupec „Akce" s třitečkovým menu**. Klikatelný řádek je volitelný
 * a teprve on přidává `cursor: pointer` — dřív ho mělo globální pravidlo i tam,
 * kde po kliknutí nenastalo nic.
 *
 * Řazení (rozhodnutí R-5): hlavička sloupce se `sortable` je `<button>`
 * s `aria-sort`; stav řazení vlastní stránka, tabulka jen hlásí změnu.
 *
 * @param {Array}    columns      - [{key, header, align?: 'end'|'center', sortable?, sortKey?, className?, render(row)}]
 * @param {Array}    rows
 * @param {Function} [rowKey]     - row → klíč (default `row.id`)
 * @param {Function} [rowActions] - row → pole akcí pro TableRowActionMenu; bez něj se sloupec Akce nevykreslí
 * @param {Function} [onAction]   - (actionId, row) z menu
 * @param {Function} [onRowClick] - klik na řádek; teprve s ním je řádek klikatelný
 * @param {React.ReactNode} [emptyState] - co zobrazit místo řádků, když je seznam prázdný
 * @param {Object}   [sort]       - {by, desc}
 * @param {Function} [onSortChange] - (sortKey, desc); u `clientSort` se nevolá
 * @param {boolean}  [clientSort] - řadit v prohlížeči (jen pro **nestránkované** seznamy,
 *                                  které mají v `rows` všechna data — na stránkovaném seznamu
 *                                  by seřadilo jen aktuální stránku a tvářilo se, že seřadilo vše)
 * @param {boolean}  [dense]      - kompaktní varianta pro tabulky uvnitř karet
 */
export default function DataTable({
    columns, rows, rowKey = row => row.id,
    rowActions, onAction, onRowClick,
    emptyState, sort, onSortChange, clientSort = false, dense = false,
}) {
    const [innerSort, setInnerSort] = React.useState(sort ?? { by: null, desc: false });
    const activeSort = clientSort ? innerSort : sort;
    const hasActions = Boolean(rowActions);
    const colSpan = columns.length + (hasActions ? 1 : 0);

    const visibleRows = React.useMemo(() => {
        if (!clientSort || !activeSort?.by) return rows;

        const column = columns.find(c => (c.sortKey ?? c.key) === activeSort.by);
        if (!column) return rows;

        const value = row => (column.sortValue ? column.sortValue(row) : row[column.key]);
        const dir = activeSort.desc ? -1 : 1;

        // Kopie: řadit vstupní pole na místě by přepsalo stav rodiče.
        return [...rows].sort((a, b) => {
            const x = value(a);
            const y = value(b);
            // Prázdné hodnoty vždy na konec, nezávisle na směru (jako NULLS LAST v SQL).
            if (x == null || x === "") return y == null || y === "" ? 0 : 1;
            if (y == null || y === "") return -1;
            if (typeof x === "number" && typeof y === "number") return (x - y) * dir;
            if (x instanceof Date && y instanceof Date) return (x - y) * dir;
            return String(x).localeCompare(String(y), "cs") * dir;
        });
    }, [rows, columns, clientSort, activeSort]);

    /** Je sloupec vůbec ovladatelný? Bez `onSortChange` ani `clientSort` nemá klik co dělat. */
    function isSortable(column) {
        return Boolean(column.sortable) && (clientSort || Boolean(onSortChange));
    }

    function headerSort(column) {
        const key = column.sortKey ?? column.key;
        if (!isSortable(column)) return;
        // stejný sloupec → otoč směr, jiný → nový sloupec vzestupně
        const desc = activeSort?.by === key ? !activeSort.desc : false;
        if (clientSort) {
            setInnerSort({ by: key, desc });
        } else {
            onSortChange(key, desc);
        }
    }

    function ariaSort(column) {
        const key = column.sortKey ?? column.key;
        if (!isSortable(column)) return undefined;
        if (activeSort?.by !== key) return "none";
        return activeSort.desc ? "descending" : "ascending";
    }

    return (
        <div className="table-responsive">
            <table className={`table table-hover align-middle${dense ? " table-sm" : ""}`
                + (onRowClick ? " table-clickable" : "")}>
                <thead>
                <tr>
                    {columns.map(column => {
                        const key = column.sortKey ?? column.key;
                        const isSorted = activeSort?.by === key;
                        return (
                            <th key={column.key} scope="col"
                                className={column.align ? `text-${column.align}` : undefined}
                                aria-sort={ariaSort(column)}>
                                {isSortable(column) ? (
                                    <button type="button" className="table-sort"
                                            onClick={() => headerSort(column)}>
                                        {column.header}
                                        <i className={`bi ms-1 ${isSorted
                                            ? (activeSort.desc ? "bi-caret-down-fill" : "bi-caret-up-fill")
                                            : "bi-chevron-expand opacity-50"}`} aria-hidden="true"></i>
                                    </button>
                                ) : column.header}
                            </th>
                        );
                    })}
                    {hasActions && <th scope="col" className="text-end">Akce</th>}
                </tr>
                </thead>
                <tbody>
                {visibleRows.length === 0 ? (
                    <tr>
                        <td colSpan={colSpan} className="p-0">
                            {emptyState}
                        </td>
                    </tr>
                ) : visibleRows.map(row => (
                    <tr key={rowKey(row)} data-id={row.id}
                        onClick={onRowClick ? () => onRowClick(row) : undefined}>
                        {columns.map(column => (
                            <td key={column.key}
                                className={[column.align ? `text-${column.align}` : "", column.className ?? ""]
                                    .filter(Boolean).join(" ") || undefined}>
                                {column.render(row)}
                            </td>
                        ))}
                        {hasActions && (
                            <td className="text-end" onClick={e => e.stopPropagation()}>
                                <TableRowActionMenu rowData={row} onAction={onAction}
                                                    actions={rowActions(row)} />
                            </td>
                        )}
                    </tr>
                ))}
                </tbody>
            </table>
        </div>
    );
}
