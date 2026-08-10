import * as React from 'react';
import {useState} from 'react';
import {api, problemMessage } from "../api/api.js";
import {useAlert} from "../context/AlertContext.jsx";
import Modal from "./Modal.jsx";

/**
 * Samoobslužný modal, ve kterém si přihlášený uživatel mění vlastní heslo.
 * Vyžaduje současné heslo — na rozdíl od adminského {@code ResetPasswordModal}.
 *
 * @param {boolean}  show
 * @param {Function} onClose()
 */
export default function ChangePasswordModal({show, onClose}) {
    const {addAlert} = useAlert();
    const [currentPassword, setCurrentPassword] = useState("");
    const [newPassword, setNewPassword] = useState("");
    const [error, setError] = useState("");

    function handleClose() {
        setCurrentPassword("");
        setNewPassword("");
        setError("");
        onClose();
    }

    async function handleConfirm() {
        if (newPassword.length < 8) {
            setError("Nové heslo musí mít alespoň 8 znaků.");
            return;
        }
        try {
            await api.post("/auth/change-password", {currentPassword, newPassword});
            addAlert("Heslo bylo úspěšně změněno.", "success");
            handleClose();
        } catch (err) {
            const message = problemMessage(err, "Heslo se nepodařilo změnit.");
            setError(message);
        }
    }

    return (
        <Modal show={show} title="Změnit heslo" onClose={handleClose}
               footer={
                   <>
                       <button type="button" onClick={handleClose} className="btn btn-outline-secondary">Zrušit</button>
                       <button type="button" onClick={handleConfirm} className="btn btn-primary">Změnit heslo</button>
                   </>
               }>
            {error && <div className="alert alert-danger py-2">{error}</div>}
            <div className="mb-3">
                <label className="form-label" htmlFor="currentPassword">Současné heslo</label>
                <input type="password" id="currentPassword" className="form-control"
                       value={currentPassword} onChange={(e) => setCurrentPassword(e.target.value)}/>
            </div>
            <div>
                <label className="form-label" htmlFor="newPassword">Nové heslo</label>
                <input type="password" id="newPassword" className="form-control"
                       value={newPassword} onChange={(e) => setNewPassword(e.target.value)}
                       minLength={8}/>
            </div>
        </Modal>
    );
}
