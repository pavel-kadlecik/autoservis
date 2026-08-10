import { useRowActions } from "./useRowActions.js";

export function useVehicleRowActions(toggleStatus) {
    return useRowActions({
        routePath: "/vehicles",
        apiPath: "/vehicles",
        dialogTitle: (action) =>
            action === "activate" ? "Potvrďte aktivaci vozidla" : "Potvrďte deaktivaci vozidla",
        dialogMessage: (rowData, action) =>
            `Opravdu si přejete ${action === "activate" ? "aktivovat" : "deaktivovat"} `
            + `vozidlo ${rowData.brand} ${rowData.model} `
            + `(${rowData.licensePlate || rowData.vin || rowData.machineSerialNumber || 'bez identifikace'})`,
        toggleStatus,
    });
}
