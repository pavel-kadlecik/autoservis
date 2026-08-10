import * as React from 'react';
import ConfirmDialog from "./ConfirmDialog.jsx";
import DataTable from "./DataTable.jsx";
import StatusBadge from "./StatusBadge.jsx";
import EmptyState from "./EmptyState.jsx";

import VisibilityIcon from '@mui/icons-material/Visibility';
import EditIcon from '@mui/icons-material/Edit';
import BlockIcon from '@mui/icons-material/Block';
import CheckCircleIcon from '@mui/icons-material/CheckCircle';
import { useSupplierRowActions } from "../hooks/useSupplierRowActions.js";
import { getActiveLabel, getActiveTone } from "../api/format.js";

/** Seznam dodavatelů nad sdílenou {@link DataTable}. */
export default function SupplierTable({ suppliers, toggleStatus, sort, onSortChange, filtered }) {

    const {
        handleMenuAction, confirmAction,
        showConfirm, setShowConfirm,
        dialogTitle, dialogMessage,
    } = useSupplierRowActions(toggleStatus);

    const columns = [
        { key: "name", header: "Název", sortable: true, className: "fw-medium", render: s => s.name },
        {
            key: "registrationNumber", header: "IČO", sortable: true,
            className: "font-monospace text-muted small",
            render: s => s.registrationNumber || '—',
        },
        {
            key: "vatId", header: "DIČ", sortable: true,
            className: "font-monospace text-muted small",
            render: s => s.vatId || '—',
        },
        { key: "city", header: "Město", sortable: true, className: "text-muted small", render: s => s.city || '—' },
        { key: "email", header: "Email", className: "text-muted small", render: s => s.email || '—' },
        { key: "phone", header: "Telefon", className: "text-muted small", render: s => s.phone || '—' },
        {
            key: "active", header: "Stav",
            render: s => <StatusBadge tone={getActiveTone(s.active)}>{getActiveLabel(s.active)}</StatusBadge>,
        },
    ];

    function rowActions(s) {
        return [
            {id: "detail", label: "Detail",   icon: <VisibilityIcon fontSize="small"/>},
            {id: "edit",   label: "Editovat", icon: <EditIcon fontSize="small"/>},
            ...(s.active
                ? [{id: "deactivate", label: "Deaktivovat", icon: <BlockIcon fontSize="small"/>,      color: "error.main"}]
                : [{id: "activate",   label: "Aktivovat",   icon: <CheckCircleIcon fontSize="small"/>, color: "success.main"}]
            ),
        ];
    }

    return (
        <div>
            <DataTable
                columns={columns}
                rows={suppliers}
                rowActions={rowActions}
                onAction={handleMenuAction}
                sort={sort}
                onSortChange={onSortChange}
                emptyState={
                    <EmptyState
                        icon="truck"
                        title={filtered ? "Filtru neodpovídá žádný dodavatel." : "Zatím žádní dodavatelé."}
                        hint="Dodavatelé vznikají importem dokladu, ručně se nezakládají."
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
