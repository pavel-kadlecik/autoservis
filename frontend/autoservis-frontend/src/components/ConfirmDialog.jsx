import * as React from 'react';
import Modal from './Modal.jsx';

/**
 * Potvrzovací dialog ano/ne nad sdílenou komponentou {@link Modal}.
 * Rozhraní zůstává stejné jako před U1.4 — volající se nemění.
 *
 * Pro dialog s doplňujícím polem (důvod storna, poznámka) použij `FormModal`,
 * ne `message` s vloženým `<textarea>`.
 *
 * @param {string}          title
 * @param {React.ReactNode} message
 * @param {boolean}         show
 * @param {Function}        onConfirm
 * @param {Function}        onCancel
 * @param {string}          [yesLabel]
 * @param {string}          [noLabel]
 */
export default function ConfirmDialog({title, message, show, onConfirm, onCancel, yesLabel = "Ano", noLabel = "Ne"}) {

    return (
        <Modal
            show={show}
            title={title}
            onClose={onCancel}
            footer={
                <>
                    <button type="button" onClick={onCancel} className="btn btn-outline-secondary">{noLabel}</button>
                    <button type="button" onClick={onConfirm} className="btn btn-primary">{yesLabel}</button>
                </>
            }
        >
            {message}
        </Modal>
    );
}
