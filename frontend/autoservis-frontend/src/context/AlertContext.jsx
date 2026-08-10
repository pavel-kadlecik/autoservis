import {createContext, useContext, useState} from "react";

export const AlertContext = createContext(null);

/**
 * Globální toasty. Pravidlo, kdy toast a kdy inline alert, je v
 * `docs/frontend.md` §10.6: **toast = výsledek akce**, po které se pokračuje
 * jinde nebo se stránka překreslí; **inline alert = trvalý stav obrazovky**,
 * který se týká toho, co uživatel právě vidí.
 */

export function AlertProvider({children}) {
    const [alerts, setAlerts] = useState([]);

    /**
     * @param {string} message
     * @param {"success"|"danger"|"info"} type - `info` = neutrální oznámení
     *        (zamítnuto, stornováno, zrušeno); není to chyba ani úspěch
     */
    function addAlert(message, type) {
        const id = Date.now() + Math.random();
        setAlerts(prev => [...prev, {id, message, type}]);
    }

    function removeAlert(id) {
        setAlerts(prev => prev.filter(value => value.id !== id));
    }

    return (
        <AlertContext.Provider value={{alerts, addAlert, removeAlert}}>
            {children}
        </AlertContext.Provider>
    )
}

export function useAlert() {
    return useContext(AlertContext);
}
