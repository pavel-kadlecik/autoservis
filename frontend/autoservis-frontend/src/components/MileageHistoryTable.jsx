import * as React from "react";
import { formatDate, formatNumber, getMileageSourceLabel, getMileageSourceTone } from "../api/format.js";
import StatusBadge from "./StatusBadge.jsx";
import DataTable from "./DataTable.jsx";
import EmptyState from "./EmptyState.jsx";

/**
 * Historie stavu tachometru vozidla, od nejnovějšího záznamu (U4.2 — nad
 * sdílenou {@link DataTable}, `dense`, protože je uvnitř karty).
 *
 * Nejnovější odečet je ten, který plní `vehicle.currentMileageKm`, a nese odznak
 * „aktuální". Dřív měl navíc podbarvený řádek — to je stejná informace dvakrát
 * a žádná jiná tabulka v aplikaci řádky nepodbarvuje.
 *
 * Záznamy typu INITIAL (výchozí stav) jdou upravit, ale ne smazat — bez nich by
 * historie neměla počátek.
 *
 * Řadí se klientsky: endpoint vrací celou historii najednou.
 *
 * @param {Object[]} readings        - z GET /vehicles/{id}/mileage (seřazeno sestupně)
 * @param {Function} onEdit(reading)
 * @param {Function} onDelete(id)
 */
export default function MileageHistoryTable({ readings, onEdit, onDelete }) {

    const rows = readings ?? [];
    const newestId = rows[0]?.id;

    const columns = [
        {
            key: "recordedDate", header: "Datum odečtu", sortable: true,
            sortValue: r => r.recordedDate,
            render: r => (
                <>
                    {formatDate(r.recordedDate)}
                    {r.id === newestId && (
                        <StatusBadge tone="success" className="ms-2">aktuální</StatusBadge>
                    )}
                </>
            ),
        },
        {
            key: "mileageKm", header: "Stav [km]", sortable: true, align: "end",
            sortValue: r => (r.mileageKm == null ? null : Number(r.mileageKm)),
            className: "fw-semibold",
            render: r => formatNumber(r.mileageKm),
        },
        {
            key: "source", header: "Zdroj", sortable: true,
            sortValue: r => getMileageSourceLabel(r.source),
            render: r => (
                <StatusBadge tone={getMileageSourceTone(r.source)}>
                    {getMileageSourceLabel(r.source)}
                </StatusBadge>
            ),
        },
        {
            key: "note", header: "Poznámka", sortable: true, className: "text-muted small",
            sortValue: r => r.note,
            render: r => r.note || "—",
        },
    ];

    function rowActions(reading) {
        return [
            { id: "edit", label: "Upravit", icon: <i className="bi bi-pencil" /> },
            ...(reading.source !== "INITIAL"
                ? [{ id: "delete", label: "Smazat", icon: <i className="bi bi-trash" />, color: "error.main" }]
                : []),
        ];
    }

    function handleAction(action, reading) {
        if (action === "edit") onEdit(reading);
        if (action === "delete") onDelete(reading.id);
    }

    return (
        <DataTable
            columns={columns}
            rows={rows}
            rowActions={rowActions}
            onAction={handleAction}
            clientSort
            dense
            emptyState={
                <EmptyState icon="speedometer2"
                            title="Vozidlo zatím nemá žádný záznam o stavu tachometru."
                            hint="První odečet přidáte tlačítkem „Přidat čtení“ v hlavičce této karty." />
            }
        />
    );
}
