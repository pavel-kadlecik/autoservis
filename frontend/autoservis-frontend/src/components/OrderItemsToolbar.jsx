import React from "react";

/**
 * Lišta akcí nad tabulkou položek zakázky — jen práce s položkami.
 *
 * „Vydat ze skladu" a „Vytvořit fakturu" odsud odešly do hlavičky stránky (2026-08-07):
 * jsou to akce nad celou zakázkou, ne nad položkami, a tady je našel jen ten, kdo šel
 * do editace. Prázdný stav v seznamu faktur přitom obsluhu posílal na detail zakázky,
 * kde žádné takové tlačítko nebylo.
 */
export default function OrderItemsToolbar({ itemError, showItemForm, handleFormVisibility, handleImportItems }) {
    return (
        <>
            {itemError && !showItemForm && (
                <div className="alert alert-danger py-2 mb-3">{itemError}</div>
            )}

            <div className="d-flex justify-content-end gap-2">
                <button type="button" className="btn btn-outline-primary btn-sm mb-3"
                        onClick={handleFormVisibility}>
                    + Přidat položku
                </button>
                <button type="button" className="btn btn-outline-primary btn-sm mb-3"
                        onClick={handleImportItems}>
                    + Importovat položky
                </button>
            </div>
        </>
    );
}
