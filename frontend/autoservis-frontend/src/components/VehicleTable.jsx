import * as React from 'react';
import ConfirmDialog from "./ConfirmDialog.jsx";
import DataTable from "./DataTable.jsx";
import StatusBadge from "./StatusBadge.jsx";
import EmptyState from "./EmptyState.jsx";

import VisibilityIcon from '@mui/icons-material/Visibility';
import EditIcon from '@mui/icons-material/Edit';
import BlockIcon from '@mui/icons-material/Block';
import CheckCircleIcon from '@mui/icons-material/CheckCircle';
import {getActiveLabel, getActiveTone, getFuelLabel, getStkBadge} from "../api/format.js";
import {useVehicleRowActions} from "../hooks/useVehicleRowActions.js";

/**
 * Seznam vozidel nad sdílenou {@link DataTable}.
 *
 * @param {Array}    vehicles
 * @param {Function} toggleStatus
 * @param {Object}   [sort]        - {by, desc}
 * @param {Function} [onSortChange]
 * @param {boolean}  [filtered]
 */
export default function VehicleTable({vehicles, toggleStatus, sort, onSortChange, filtered}) {

    const {
        handleMenuAction, confirmAction,
        showConfirm, setShowConfirm,
        dialogTitle, dialogMessage,
    } = useVehicleRowActions(toggleStatus);

    const columns = [
        {
            key: "vin", header: "VIN", sortable: true,
            // Stroj bez VIN (V90) ukáže výrobní číslo — sloupec je „identifikátor", ne striktně VIN.
            render: v => <span className="font-monospace text-muted small">{v.vin ?? v.machineSerialNumber ?? '—'}</span>,
        },
        {
            key: "licensePlate", header: "SPZ", sortable: true, className: "fw-medium",
            render: v => v.licensePlate || '—',
        },
        // Zákazník se neřadí — displayName se skládá až na klientovi z typu zákazníka,
        // server pro seznam vozidel odpovídající klíč nemá.
        { key: "customer", header: "Zákazník", render: v => v.customer?.displayName ?? '—' },
        { key: "brand", header: "Značka", sortable: true, render: v => v.brand },
        // Model nemá vlastní klíč — je součástí řazení podle značky (viz VehicleMapper).
        { key: "model", header: "Model", render: v => v.model },
        {
            key: "yearOfManufacture", header: "Rok", sortable: true, align: "end",
            render: v => v.yearOfManufacture ?? '—',
        },
        // Palivo je enum — abecední pořadí českých popisků by neodpovídalo pořadí
        // hodnot v DB, proto se neřadí.
        { key: "fuelType", header: "Palivo", render: v => getFuelLabel(v.fuelType) },
        {
            key: "stkValidUntil", header: "STK", sortable: true,
            render: v => (
                <StatusBadge tone={getStkBadge(v.stkValidUntil).tone}>
                    {getStkBadge(v.stkValidUntil).label}
                </StatusBadge>
            ),
        },
        {
            key: "active", header: "Stav",
            render: v => <StatusBadge tone={getActiveTone(v.active)}>{getActiveLabel(v.active)}</StatusBadge>,
        },
    ];

    function rowActions(v) {
        return [
            {id: "detail", label: "Detail",   icon: <VisibilityIcon fontSize="small"/>},
            {id: "edit",   label: "Editovat", icon: <EditIcon fontSize="small"/>},
            ...(v.active
                ? [{id: "deactivate", label: "Deaktivovat", icon: <BlockIcon fontSize="small"/>,      color: "error.main"}]
                : [{id: "activate",   label: "Aktivovat",   icon: <CheckCircleIcon fontSize="small"/>, color: "success.main"}]
            ),
        ];
    }

    return (
        <div>
            <DataTable
                columns={columns}
                rows={vehicles}
                rowActions={rowActions}
                onAction={handleMenuAction}
                sort={sort}
                onSortChange={onSortChange}
                emptyState={
                    <EmptyState
                        icon="car-front"
                        title={filtered ? "Filtru neodpovídá žádné vozidlo." : "Zatím žádná vozidla."}
                        hint={filtered ? "Zkuste hledaný výraz zkrátit nebo vypnout filtry."
                                       : "Nové založíte tlačítkem nahoře."}
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
