import * as React from 'react';
import ConfirmDialog from "./ConfirmDialog.jsx";
import DataTable from "./DataTable.jsx";
import StatusBadge from "./StatusBadge.jsx";
import EmptyState from "./EmptyState.jsx";
import { formatCurrency, formatDate, getActiveLabel, getActiveTone } from "../api/format.js";

import EditIcon from '@mui/icons-material/Edit';
import BlockIcon from '@mui/icons-material/Block';
import CheckCircleIcon from '@mui/icons-material/CheckCircle';
import { useEmployeeRowActions } from "../hooks/useEmployeeRowActions.js";

/** Seznam zaměstnanců nad sdílenou {@link DataTable}. Nestránkovaný — řadí se v prohlížeči. */
export default function EmployeeTable({ employees, toggleStatus, filtered }) {

    const {
        handleMenuAction, confirmAction,
        showConfirm, setShowConfirm,
        dialogTitle, dialogMessage,
    } = useEmployeeRowActions(toggleStatus);

    const columns = [
        { key: "fullName", header: "Jméno", sortable: true, className: "fw-medium",
          render: e => e.fullName },
        { key: "position", header: "Pozice", sortable: true, className: "text-muted small",
          render: e => e.position || "—" },
        { key: "hourlyRate", header: "Hodinová sazba", sortable: true, align: "end",
          sortValue: e => e.hourlyRate != null ? Number(e.hourlyRate) : null,
          render: e => e.hourlyRate != null ? formatCurrency(e.hourlyRate) : "—" },
        { key: "hiredAt", header: "Nástup", sortable: true, className: "text-muted small",
          render: e => formatDate(e.hiredAt) },
        { key: "leftAt", header: "Odchod", sortable: true, className: "text-muted small",
          render: e => e.leftAt ? formatDate(e.leftAt) : "—" },
        { key: "active", header: "Stav",
          render: e => <StatusBadge tone={getActiveTone(e.active)}>{getActiveLabel(e.active)}</StatusBadge> },
    ];

    function rowActions(e) {
        return [
            { id: "edit", label: "Editovat", icon: <EditIcon fontSize="small" /> },
            ...(e.active
                ? [{ id: "deactivate", label: "Deaktivovat", icon: <BlockIcon fontSize="small" />,      color: "error.main" }]
                : [{ id: "activate",   label: "Aktivovat",   icon: <CheckCircleIcon fontSize="small" />, color: "success.main" }]
            ),
        ];
    }

    return (
        <div>
            <DataTable
                columns={columns}
                rows={employees}
                rowActions={rowActions}
                onAction={handleMenuAction}
                clientSort
                emptyState={
                    <EmptyState
                        icon="person-badge"
                        title={filtered ? "Filtru neodpovídá žádný zaměstnanec." : "Zatím žádní zaměstnanci."}
                        hint={filtered ? "Zkuste hledaný výraz zkrátit nebo vypnout „Jen aktivní“."
                                       : "Nového založíte tlačítkem nahoře."}
                    />
                }
            />

            <ConfirmDialog
                title={dialogTitle}
                message={dialogMessage}
                show={showConfirm}
                onConfirm={() => confirmAction()}
                onCancel={() => setShowConfirm(false)}
            />
        </div>
    );
}
