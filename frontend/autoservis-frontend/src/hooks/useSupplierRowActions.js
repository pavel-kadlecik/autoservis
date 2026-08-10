import { useRowActions } from "./useRowActions.js";

/**
 * Logika řádkových akcí tabulky dodavatelů (detail / edit / aktivace / deaktivace).
 * Dodavatelé jsou jen RUD, takže tady žádná akce „create" není.
 *
 * @param {Function} toggleStatus - zavolá se po úspěšné (de)aktivaci pro refresh seznamu
 */
export function useSupplierRowActions(toggleStatus) {
    return useRowActions({
        routePath: "/suppliers",
        apiPath: "/warehouse/suppliers",
        dialogTitle: (action) =>
            action === "activate" ? "Potvrďte aktivaci dodavatele" : "Potvrďte deaktivaci dodavatele",
        dialogMessage: (rowData, action) =>
            `Opravdu si přejete ${action === "activate" ? "aktivovat" : "deaktivovat"} `
            + `dodavatele ${rowData.name}?`,
        toggleStatus,
    });
}
