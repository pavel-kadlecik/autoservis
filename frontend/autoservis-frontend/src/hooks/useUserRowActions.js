import { useState } from "react";
import { api } from "../api/api.js";
import { useAlert } from "../context/AlertContext.jsx";
import { useRowActions } from "./useRowActions.js";

/**
 * Řádkové akce tabulky uživatelů. Obaluje sdílený useRowActions (detail je
 * vypnutý — uživatelé mají jen edit/aktivaci/deaktivaci) a přidává flow
 * resetu hesla, které u ostatních entit nemá obdobu, a proto zůstává mimo
 * sdílený hook, místo aby se do něj lámalo násilím.
 *
 * @param {Function} toggleStatus - zavolá se po úspěšné (de)aktivaci pro refresh seznamu
 */
export function useUserRowActions(toggleStatus) {
    const { addAlert } = useAlert();

    const [showResetPassword, setShowResetPassword] = useState(false);
    const [resetPasswordRowData, setResetPasswordRowData] = useState(null);

    const base = useRowActions({
        routePath: "/users",
        apiPath: "/users",
        hasDetailAction: false,
        dialogTitle: (action) =>
            action === "activate" ? "Potvrďte aktivaci uživatele" : "Potvrďte deaktivaci uživatele",
        dialogMessage: (rowData, action) =>
            `Opravdu si přejete ${action === "activate" ? "aktivovat" : "deaktivovat"} `
            + `uživatele ${rowData.username}?`,
        toggleStatus,
    });

    function handleMenuAction(action, rowData) {
        if (action === 'reset-password') {
            setResetPasswordRowData(rowData);
            setShowResetPassword(true);
            return;
        }
        base.handleMenuAction(action, rowData);
    }

    async function confirmResetPassword(newPassword) {
        try {
            await api.post(`/users/${resetPasswordRowData.id}/reset-password`, { newPassword });
            addAlert(`Heslo uživatele ${resetPasswordRowData.username} bylo resetováno.`, "success");
        } catch (err) {
            let message = "Heslo se nepodařilo resetovat.";
            message = err.problem?.detail ?? message;
            addAlert(message, "danger");
        } finally {
            setShowResetPassword(false);
        }
    }

    return {
        ...base,
        handleMenuAction,
        showResetPassword,
        setShowResetPassword,
        confirmResetPassword,
        resetPasswordUsername: resetPasswordRowData?.username ?? "",
    };
}
