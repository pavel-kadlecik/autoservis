import * as React from 'react';
import ConfirmDialog from "./ConfirmDialog.jsx";
import ResetPasswordModal from "./ResetPasswordModal.jsx";
import DataTable from "./DataTable.jsx";
import StatusBadge from "./StatusBadge.jsx";
import EmptyState from "./EmptyState.jsx";
import {formatDate, getActiveLabel, getActiveTone, getRoleLabel} from "../api/format.js";

import EditIcon from '@mui/icons-material/Edit';
import BlockIcon from '@mui/icons-material/Block';
import CheckCircleIcon from '@mui/icons-material/CheckCircle';
import KeyIcon from '@mui/icons-material/Key';
import {useUserRowActions} from "../hooks/useUserRowActions.js";

/** Seznam uživatelských účtů nad sdílenou {@link DataTable}. */
export default function UserTable({users, toggleStatus, sort, onSortChange, filtered}) {

    const {
        handleMenuAction, confirmAction,
        showConfirm, setShowConfirm,
        dialogTitle, dialogMessage,
        showResetPassword, setShowResetPassword,
        confirmResetPassword, resetPasswordUsername,
    } = useUserRowActions(toggleStatus);

    const columns = [
        { key: "username", header: "Uživatelské jméno", sortable: true, className: "fw-medium",
          render: u => u.username },
        { key: "email", header: "Email", sortable: true, className: "text-muted small",
          render: u => u.email },
        {
            key: "roles", header: "Role",
            render: u => (u.roles ?? []).map(role => (
                <StatusBadge key={role} tone="secondary" className="me-1" title={role}>
                    {getRoleLabel(role)}
                </StatusBadge>
            )),
        },
        {
            key: "lastLoginAt", header: "Poslední přihlášení", sortable: true,
            className: "text-muted small",
            render: u => formatDate(u.lastLoginAt),
        },
        {
            key: "enabled", header: "Stav",
            render: u => <StatusBadge tone={getActiveTone(u.enabled)}>{getActiveLabel(u.enabled)}</StatusBadge>,
        },
    ];

    function rowActions(u) {
        return [
            {id: "edit", label: "Editovat", icon: <EditIcon fontSize="small"/>},
            {id: "reset-password", label: "Resetovat heslo", icon: <KeyIcon fontSize="small"/>},
            ...(u.enabled
                ? [{id: "deactivate", label: "Deaktivovat", icon: <BlockIcon fontSize="small"/>,      color: "error.main"}]
                : [{id: "activate",   label: "Aktivovat",   icon: <CheckCircleIcon fontSize="small"/>, color: "success.main"}]
            ),
        ];
    }

    return (
        <div>
            <DataTable
                columns={columns}
                rows={users}
                rowActions={rowActions}
                onAction={handleMenuAction}
                sort={sort}
                onSortChange={onSortChange}
                emptyState={
                    <EmptyState
                        icon="person-gear"
                        title={filtered ? "Filtru neodpovídá žádný uživatel." : "Zatím žádní uživatelé."}
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

            <ResetPasswordModal
                show={showResetPassword}
                username={resetPasswordUsername}
                onConfirm={(newPassword) => confirmResetPassword(newPassword)}
                onCancel={() => setShowResetPassword(false)}
            />
        </div>
    );
}
