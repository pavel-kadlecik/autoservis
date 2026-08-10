import * as React from "react";
import { useNavigate } from "react-router-dom";
import EmptyState from "./EmptyState.jsx";

/**
 * Chyba, která zabránila zobrazení celé stránky. Na rozdíl od dřívějšího holého
 * `alert alert-danger` vždy nabízí cestu ven — uživatel nesmí uváznout (S-22).
 *
 * @param {string} message     - co se nepodařilo (text z ProblemDetail nebo fallback)
 * @param {string} backTo      - kam vede tlačítko zpět
 * @param {string} [backLabel] - popisek tlačítka
 * @param {string} [hint]      - vysvětlení pod hláškou; `null` ho vypne
 */
export default function ErrorState({
    message, backTo, backLabel = "Zpět na přehled",
    hint = "Zkontrolujte, zda záznam stále existuje, nebo se vraťte na přehled.",
}) {
    const navigate = useNavigate();

    return (
        <EmptyState
            icon="exclamation-triangle"
            title={message}
            hint={hint}
            action={
                <button type="button" className="btn btn-outline-secondary"
                        onClick={() => navigate(backTo)}>
                    <i className="bi bi-arrow-left me-1" aria-hidden="true"></i>{backLabel}
                </button>
            }
        />
    );
}
