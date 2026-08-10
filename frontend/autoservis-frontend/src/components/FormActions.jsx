import * as React from "react";
import { useState } from "react";

/**
 * Patička formuláře. Ve formuláři se ruší slovem **Zrušit**, ne „Zpět“ —
 * „Zpět“ je vyhrazené navigaci. Výjimka (2026-08-09): když Uložit na stránce
 * zůstává (editace zakázky), druhé tlačítko nic neruší, jen naviguje, a pak
 * se jmenuje „Zpět“ (`cancelLabel`).
 *
 * @param {Function} onCancel
 * @param {Function} [onSubmit]      - když chybí, tlačítko je type="submit"
 * @param {boolean}  [saving]        - externí override „ukládá se" (blokuje tlačítka, mění popisek);
 *                                     dvojklik je navíc blokován interně, dokud onSubmit (promise) neproběhne
 * @param {string}   [submitLabel]
 * @param {string}   [savingLabel]
 * @param {string}   [cancelLabel]
 */
export default function FormActions({
    onCancel, onSubmit, saving = false,
    submitLabel = "Uložit", savingLabel = "Ukládám…", cancelLabel = "Zrušit",
}) {
    const [busy, setBusy] = useState(false);
    const disabled = saving || busy;

    // Ochrana proti dvojkliku: když onSubmit vrací promise (formulářový handleSave → onSave),
    // tlačítka se po prvním kliknutí zablokují, dokud uložení neproběhne.
    async function handleSubmit() {
        if (busy) return;
        setBusy(true);
        try {
            await onSubmit();
        } finally {
            setBusy(false);
        }
    }

    return (
        <div className="d-flex justify-content-end gap-2 border-top pt-3">
            <button type="button" className="btn btn-outline-secondary px-4"
                    onClick={onCancel} disabled={disabled}>
                {cancelLabel}
            </button>
            <button type={onSubmit ? "button" : "submit"} className="btn btn-primary px-4"
                    onClick={onSubmit ? handleSubmit : undefined} disabled={disabled}>
                {disabled ? savingLabel : submitLabel}
            </button>
        </div>
    );
}
