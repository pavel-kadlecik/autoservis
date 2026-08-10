import { useState } from "react";
import { useNavigate } from "react-router-dom";
import { api } from "../api/api.js";
import { useAlert } from "../context/AlertContext.jsx";

/**
 * Sdílená logika řádkových akcí tabulek: navigace na detail/edit a
 * aktivace/deaktivace s potvrzovacím dialogem a chybovou hláškou.
 *
 * Vznikl sjednocením téměř identických hooků (useCustomerRowActions,
 * useVehicleRowActions, useSupplierRowActions, useWarehouseRowActions,
 * useUserRowActions — viz TD-35). Konkrétní entity mají tenké wrappery ve
 * vlastních souborech, které jen předvyplní parametry (cesty, texty dialogu)
 * a případně přidají vlastní speciality (např. reset hesla u uživatelů).
 *
 * Zakázky tento hook nepoužívají — nemají soft-delete (ruší se stavem CANCELLED),
 * takže OrderTable řeší jen detail/edit navigaci přímo (viz tech-dluhy.md TD-67).
 *
 * @param {object} config
 * @param {string} config.routePath - základ cesty pro navigaci `detail`/`edit`, např. "/customers"
 *   (výsledek: `${routePath}/{id}/detail`, `${routePath}/{id}/edit`)
 * @param {string} config.apiPath - základ REST cesty pro delete/activate, např. "/warehouse/suppliers"
 *   (u některých entit se liší od routePath — API má jinou strukturu než FE routy)
 * @param {boolean} [config.hasDetailAction=true] - zda `handleMenuAction` zpracovává akci 'detail'
 *   (uživatelé nemají detail stránku, jen edit)
 * @param {(action: 'activate'|'deactivate') => string} config.dialogTitle - titulek potvrzovacího dialogu
 * @param {(rowData: object, action: 'activate'|'deactivate') => string} config.dialogMessage - text potvrzovacího dialogu
 * @param {(id: number|string) => void} config.toggleStatus - zavolá se s id po úspěšné (de)aktivaci
 *   (typicky přepne stav položky v lokálním seznamu bez refetch)
 */
export function useRowActions({
    routePath,
    apiPath,
    hasDetailAction = true,
    dialogTitle,
    dialogMessage,
    toggleStatus,
}) {
    const navigate = useNavigate();
    const { addAlert } = useAlert();

    const [showConfirm, setShowConfirm] = useState(false);
    const [invokedRowData, setInvokedRowData] = useState(null);
    const [currentAction, setCurrentAction] = useState(null);

    function handleMenuAction(action, rowData) {
        setInvokedRowData(rowData);

        switch (action) {
            case 'detail':
                if (hasDetailAction) {
                    navigate(`${routePath}/${rowData.id}/detail`);
                }
                break;
            case 'edit':
                navigate(`${routePath}/${rowData.id}/edit`);
                break;
            case 'activate':
            case 'deactivate':
                setCurrentAction(action);
                setShowConfirm(true);
                break;
        }
    }

    async function confirmAction() {
        try {
            const data = "deactivate" === currentAction
                ? await api.delete(`${apiPath}/${invokedRowData.id}`)
                : await api.post(`${apiPath}/${invokedRowData.id}/activate`);
            toggleStatus(data.id);
        } catch (err) {
            let message = "Akci se nepodařilo provést.";
            message = err.problem?.detail ?? message;
            addAlert(message, "danger");
        } finally {
            setShowConfirm(false);
        }
    }

    const resolvedDialogTitle =
        currentAction === "activate" || currentAction === "deactivate"
            ? dialogTitle(currentAction)
            : "";

    const resolvedDialogMessage = invokedRowData
        ? dialogMessage(invokedRowData, currentAction)
        : "";

    return {
        handleMenuAction,
        confirmAction,
        showConfirm,
        setShowConfirm,
        dialogTitle: resolvedDialogTitle,
        dialogMessage: resolvedDialogMessage,
    };
}
