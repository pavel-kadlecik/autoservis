import * as React from "react";
import { getFuelLabel } from "../api/format.js";
import ConfirmDialog from "./ConfirmDialog.jsx";
import DataTable from "./DataTable.jsx";
import EmptyState from "./EmptyState.jsx";
import LoadingState from "./LoadingState.jsx";
import { useVehicleRowActions } from "../hooks/useVehicleRowActions.js";

import VisibilityIcon from '@mui/icons-material/Visibility';
import EditIcon from '@mui/icons-material/Edit';
import BlockIcon from '@mui/icons-material/Block';
import CheckCircleIcon from '@mui/icons-material/CheckCircle';

/**
 * Vozidla zákazníka na jeho detailu — poslední tabulka mimo sdílenou
 * {@link DataTable} (U4.1). Je vnořená v kartě, proto `dense`.
 *
 * Řadí se **klientsky**: endpoint vrací všechna vozidla zákazníka najednou,
 * bez stránkování.
 */
export default function CustomerVehiclesTable({ vehicles, toggleStatus }) {

    const { handleMenuAction, confirmAction, showConfirm, setShowConfirm,
        dialogTitle, dialogMessage } = useVehicleRowActions(toggleStatus);

    if (!vehicles) return <LoadingState inline />;

    const columns = [
        {
            key: "vin", header: "VIN", sortable: true,
            // Stroj bez VIN (V90) ukáže výrobní číslo — sloupec je „identifikátor", ne striktně VIN.
            render: v => <span className="font-monospace text-muted small">{v.vin ?? v.machineSerialNumber ?? '—'}</span>,
        },
        { key: "licensePlate", header: "SPZ", sortable: true, className: "fw-medium", render: v => v.licensePlate },
        { key: "brand", header: "Značka", sortable: true, render: v => v.brand },
        { key: "model", header: "Model", sortable: true, render: v => v.model },
        {
            key: "yearOfManufacture", header: "Rok", sortable: true,
            sortValue: v => (v.yearOfManufacture == null ? null : Number(v.yearOfManufacture)),
            render: v => v.yearOfManufacture,
        },
        {
            key: "fuelType", header: "Palivo", sortable: true,
            sortValue: v => getFuelLabel(v.fuelType),
            render: v => getFuelLabel(v.fuelType),
        },
    ];

    function rowActions(v) {
        return [
            { id: "detail", label: "Detail",   icon: <VisibilityIcon fontSize="small"/> },
            { id: "edit",   label: "Editovat", icon: <EditIcon fontSize="small"/> },
            ...(v.active
                ? [{ id: "deactivate", label: "Deaktivovat", icon: <BlockIcon fontSize="small"/>, color: "error.main" }]
                : [{ id: "activate",   label: "Aktivovat",   icon: <CheckCircleIcon fontSize="small"/>, color: "success.main" }]),
        ];
    }

    return (
        <>
            <DataTable
                columns={columns}
                rows={vehicles}
                rowActions={rowActions}
                onAction={handleMenuAction}
                clientSort
                dense
                emptyState={
                    <EmptyState icon="car-front"
                                title="Zákazník nemá evidované žádné vozidlo."
                                hint="Vozidlo přidáte v sekci Vozidla — zákazníka u něj vyberete jako vlastníka." />
                }
            />

            <ConfirmDialog
                title={dialogTitle}
                message={dialogMessage}
                show={showConfirm}
                onConfirm={() => confirmAction()}
                onCancel={() => setShowConfirm(false)}
            />
        </>
    );
}
