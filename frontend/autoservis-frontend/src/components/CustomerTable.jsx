import * as React from 'react';
import ConfirmDialog from "./ConfirmDialog.jsx";
import DataTable from "./DataTable.jsx";
import StatusBadge from "./StatusBadge.jsx";
import EmptyState from "./EmptyState.jsx";

import VisibilityIcon from '@mui/icons-material/Visibility';
import EditIcon from '@mui/icons-material/Edit';
import BlockIcon from '@mui/icons-material/Block';
import CheckCircleIcon from '@mui/icons-material/CheckCircle';
import {useCustomerRowActions} from "../hooks/useCustomerRowActions.js";
import {CUSTOMER_TYPE_LABELS, getActiveLabel, getActiveTone} from "../api/format.js";

/**
 * Seznam zákazníků nad sdílenou {@link DataTable}.
 *
 * @param {Array}    customers
 * @param {Function} toggleStatus - refresh po (de)aktivaci
 * @param {Object}   [sort]       - {by, desc} — vlastní stránka
 * @param {Function} [onSortChange]
 * @param {boolean}  [filtered]   - je aktivní filtr? mění text prázdného stavu
 */
export default function CustomerTable({customers, toggleStatus, sort, onSortChange, filtered}) {

    const {
        handleMenuAction, confirmAction,
        showConfirm, setShowConfirm,
        dialogTitle, dialogMessage,
    } = useCustomerRowActions(toggleStatus);

    const columns = [
        {
            key: "customerNumber", header: "Číslo zákazníka", sortable: true,
            render: c => <span className="font-monospace text-muted small">{c.customerNumber}</span>,
        },
        {
            key: "lastName", header: "Jméno", sortable: true, className: "fw-medium",
            render: c => c.displayName,
        },
        // Typ je filtr (fyzická osoba / firma), ne řadicí kritérium — na serveru
        // pro něj whitelist není.
        {
            key: "customerType", header: "Typ zákazníka",
            render: c => (
                <StatusBadge tone={c.customerType === 'INDIVIDUAL' ? 'info' : 'primary'}>
                    {CUSTOMER_TYPE_LABELS[c.customerType] ?? c.customerType}
                </StatusBadge>
            ),
        },
        {
            key: "primaryEmail", header: "Email", sortable: true, className: "text-muted small",
            render: c => c.primaryEmail || '—',
        },
        // Telefon se neřadí — v DB je volný text (s předvolbou i bez), pořadí by
        // neodpovídalo tomu, co uživatel vidí.
        {
            key: "primaryPhone", header: "Telefon", className: "text-muted small",
            render: c => c.primaryPhone || '—',
        },
        {
            key: "active", header: "Stav",
            render: c => (
                <StatusBadge tone={getActiveTone(c.active)}>{getActiveLabel(c.active)}</StatusBadge>
            ),
        },
    ];

    function rowActions(c) {
        return [
            {id: "detail", label: "Detail",   icon: <VisibilityIcon fontSize="small"/>},
            {id: "edit",   label: "Editovat", icon: <EditIcon fontSize="small"/>},
            ...(c.active
                ? [{id: "deactivate", label: "Deaktivovat", icon: <BlockIcon fontSize="small"/>,      color: "error.main"}]
                : [{id: "activate",   label: "Aktivovat",   icon: <CheckCircleIcon fontSize="small"/>, color: "success.main"}]
            ),
        ];
    }

    return (
        <div>
            <DataTable
                columns={columns}
                rows={customers}
                rowActions={rowActions}
                onAction={handleMenuAction}
                sort={sort}
                onSortChange={onSortChange}
                emptyState={
                    <EmptyState
                        icon="people"
                        title={filtered ? "Filtru neodpovídá žádný zákazník." : "Zatím žádní zákazníci."}
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
