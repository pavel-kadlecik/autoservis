import { useRowActions } from "./useRowActions.js";

export function useCustomerRowActions(toggleStatus) {
    return useRowActions({
        routePath: "/customers",
        apiPath: "/customers",
        dialogTitle: (action) =>
            action === "activate" ? "Potvrďte aktivaci zákazníka" : "Potvrďte deaktivaci zákazníka",
        dialogMessage: (rowData, action) =>
            `Opravdu si přejete ${action === "activate" ? "aktivovat" : "deaktivovat"} `
            + `zákazníka ${rowData.displayName} (${rowData.customerNumber})?`,
        toggleStatus,
    });
}
