import { useRowActions } from "./useRowActions.js";

/**
 * Řádkové akce tabulky zaměstnanců — tenký wrapper nad sdíleným {@link useRowActions}
 * (TD-35). Zaměstnanci mají jen edit + aktivace/deaktivace (soft-delete, D-4), bez
 * detail stránky. Mazání se nikdy neprovádí — jen deaktivace, historii drží FK z položek.
 *
 * @param {Function} toggleStatus - zavolá se po úspěšné (de)aktivaci pro refresh seznamu
 */
export function useEmployeeRowActions(toggleStatus) {
    return useRowActions({
        routePath: "/employees",
        apiPath: "/employees",
        hasDetailAction: false,
        dialogTitle: (action) =>
            action === "activate" ? "Potvrďte aktivaci zaměstnance" : "Potvrďte deaktivaci zaměstnance",
        dialogMessage: (rowData, action) =>
            `Opravdu si přejete ${action === "activate" ? "aktivovat" : "deaktivovat"} `
            + `zaměstnance ${rowData.fullName}?`,
        toggleStatus,
    });
}
