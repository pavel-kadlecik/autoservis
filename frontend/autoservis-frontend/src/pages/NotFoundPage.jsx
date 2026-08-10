import * as React from "react";
import ErrorState from "../components/ErrorState.jsx";
import PageHeader from "../components/PageHeader.jsx";

/**
 * Neexistující adresa. Bez této routy vykreslila aplikace prázdnou stránku —
 * uživatel nedostal žádné vysvětlení ani cestu zpět (nález při ověřování U1).
 *
 * `PageHeader` tu není ozdoba: každá stránka má právě jeden `h1` (§10.1)
 * a tahle byla jediná bez nadpisu — pro čtečku obrazovky stránka bez názvu
 * (nález při ověřování U5.1).
 */
export default function NotFoundPage() {
    return (
        <div>
            <PageHeader title="Stránka nenalezena" />
            <ErrorState
                message="Taková stránka neexistuje."
                hint="Zkontrolujte adresu, nebo se vraťte na přehled."
                backTo="/dashboard"
                backLabel="Zpět na Dashboard"
            />
        </div>
    );
}
