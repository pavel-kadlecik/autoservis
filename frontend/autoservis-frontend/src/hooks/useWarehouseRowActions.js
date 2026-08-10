import { useRowActions } from "./useRowActions.js";

/**
 * Řádkové akce tabulky skladových zásob: navigace na detail/edit a
 * aktivace/deaktivace (s potvrzovacím dialogem), po vzoru row-action hooků
 * vozidel a zákazníků.
 *
 * @param {Function} toggleStatus - zavolá se s ID produktu po úspěšné
 *                                  (de)aktivaci, aby se seznam mohl obnovit
 */
export function useWarehouseRowActions(toggleStatus) {
    return useRowActions({
        routePath: "/warehouse",
        apiPath: "/warehouse/products",
        dialogTitle: (action) =>
            action === "activate" ? "Potvrďte aktivaci položky" : "Potvrďte deaktivaci položky",
        dialogMessage: (rowData, action) =>
            `Opravdu si přejete ${action === "activate" ? "aktivovat" : "deaktivovat"} `
            + `položku ${rowData.name} (${rowData.sku})`,
        toggleStatus,
    });
}
