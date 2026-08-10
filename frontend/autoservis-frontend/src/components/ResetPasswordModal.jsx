import * as React from 'react';
import {useState} from 'react';
import Modal from './Modal.jsx';

/**
 * Modal jen pro adminy: nastavení nového hesla uživatelskému účtu.
 * Současné heslo se nevyžaduje — na rozdíl od samoobslužné změny vlastního hesla.
 *
 * @param {boolean}  show
 * @param {Function} onConfirm(newPassword)
 * @param {Function} onCancel()
 * @param {string}   username
 */
export default function ResetPasswordModal({show, onConfirm, onCancel, username}) {
    const [newPassword, setNewPassword] = useState("");
    const [validated, setValidated] = useState(false);

    function handleConfirm() {
        if (newPassword.length < 8) {
            setValidated(true);
            return;
        }
        onConfirm(newPassword);
        setNewPassword("");
        setValidated(false);
    }

    function handleCancel() {
        setNewPassword("");
        setValidated(false);
        onCancel();
    }

    return (
        <Modal show={show} title={`Resetovat heslo — ${username}`} onClose={handleCancel}
               footer={
                   <>
                       <button type="button" onClick={handleCancel} className="btn btn-outline-secondary">Zrušit</button>
                       <button type="button" onClick={handleConfirm} className="btn btn-primary">Resetovat</button>
                   </>
               }>
            <label className="form-label" htmlFor="resetNewPassword">Nové heslo</label>
            <input type="password" id="resetNewPassword"
                   className={`form-control ${validated && newPassword.length < 8 ? 'is-invalid' : ''}`}
                   value={newPassword} onChange={(e) => setNewPassword(e.target.value)}
                   minLength={8}/>
            <div className="invalid-feedback">Heslo musí mít alespoň 8 znaků</div>
        </Modal>
    );
}
