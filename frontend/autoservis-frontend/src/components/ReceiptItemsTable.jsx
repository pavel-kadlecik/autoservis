import React, { useEffect, useRef } from "react";
import { formatCurrency, formatQuantity } from "../api/format.js";

/** Převede syrovou hodnotu výběru (string z inputu, nebo číslo) na číslo. */
function toNumber(value) {
    const n = parseFloat(value);
    return Number.isNaN(n) ? 0 : n;
}

/**
 * Kolik lze ze šarže ještě slíbit. Server to posílá spočítané; `quantityRemaining` je
 * záložní hodnota pro případ, že by šarže dorazila z dotazu, který rezervace nepočítá.
 */
function availableOf(item) {
    return Number(item.quantityAvailable ?? item.quantityRemaining);
}

/**
 * Je vyplněné množství u vybraného řádku špatně?
 *
 * Pravidlo je export schválně: tabulka podle něj maluje řádek červeně a tlačítko importu
 * podle něj blokuje odeslání. Dvě kopie by se rozešly a vznikl by přesně ten stav, který
 * tahle komponenta opravuje — UI tvrdí něco jiného než to, co projde.
 */
export function isSelectionInvalid(item, raw) {
    const num = toNumber(raw);
    return raw === "" || num <= 0 || num > availableOf(item);
}

/** Má výběr aspoň jeden špatně vyplněný řádek? */
export function hasInvalidSelection(items, selection) {
    return items.some(it => it.id in selection && isSelectionInvalid(it, selection[it.id]));
}

/**
 * Interaktivní tabulka importovatelných řádků příjemky (šarží).
 *
 * Jeden řádek = jedna šarže (goods_receipt_item): uživatel zaškrtne šarže k importu
 * a nastaví, kolik z každé.
 *
 * <p><strong>Množství se stropuje DOSTUPNÝM množstvím, ne zbytkem šarže.</strong> Rezervace
 * fyzickým stavem nehýbe — díl slíbený jiné otevřené zakázce leží dál v regále, takže
 * `quantityRemaining` ho pořád počítá. Dokud se tabulka řídila jím, nabízela cizí kusy:
 * vstup „4" označila za platný, server ho odmítl s QUANTITY_EXCEEDS_REMAINING a protože
 * je import jedna transakce, spadla s ním celá dávka včetně řádků, které byly v pořádku.
 * Obsluha se o kolizi dozvěděla až po odeslání, a u víc kolidujících řádků po jedné.
 *
 * <p>Obě čísla proto stojí vedle sebe (rozhodnutí uživatele 2026-08-06), stejně jako
 * „Skladem" a „Dostupné" na kartě dílu: „Zbývá" odpovídá na *kolik toho mám*, „Dostupné"
 * na *kolik můžu slíbit*. Řádek, kde je vše rezervované, zůstává vidět, jen nejde vybrat —
 * obsluha díky tomu pozná „díl tu není" od „díl tu je, ale je slíbený jinam" a může
 * zakázky přerovnat místo objednávání.
 *
 * Prezentační/řízená komponenta — stav výběru vlastní rodič a předává ho dovnitř.
 * Tvar výběru je { [itemId]: quantity }, přesně to, co v dalším kroku spotřebuje POST
 * dávkového importu. Stavy načítání / prázdno / chyba řeší rodič; tahle tabulka
 * předpokládá, že se vykresluje jen s aspoň jednou položkou.
 *
 * @param {Object[]} items              - importovatelné šarže z GET /warehouse/goods-receipts/{id}/items
 * @param {Object}   selection          - { [itemId]: quantityToImport }
 * @param {Function} onSelectionChange  - volá se s novým objektem výběru
 */
export default function ReceiptItemsTable({ items, selection, onSelectionChange }) {

    const selectAllRef = useRef(null);

    // „Vybrat vše" se počítá jen z řádků, které vybrat jde — jinak by zaškrtnutí všeho
    // nikdy nedosáhlo stavu „vše vybráno" a checkbox by zůstal viset v indeterminate.
    const selectableItems = items.filter(it => availableOf(it) > 0);
    const selectedCount   = Object.keys(selection).length;
    const allSelected     = selectableItems.length > 0 && selectedCount === selectableItems.length;
    const someSelected    = selectedCount > 0 && !allSelected;

    // Nativní stav indeterminate jde nastavit jen imperativně.
    useEffect(() => {
        if (selectAllRef.current) selectAllRef.current.indeterminate = someSelected;
    }, [someSelected]);

    function toggleRow(item) {
        const next = { ...selection };
        if (item.id in next) {
            delete next[item.id];
        } else {
            next[item.id] = availableOf(item);   // default = vše, co lze slíbit
        }
        onSelectionChange(next);
    }

    function setQuantity(item, raw) {
        onSelectionChange({ ...selection, [item.id]: raw });
    }

    function toggleAll() {
        if (allSelected) {
            onSelectionChange({});
        } else {
            const next = {};
            selectableItems.forEach(it => { next[it.id] = availableOf(it); });
            onSelectionChange(next);
        }
    }

    const totalPurchase = items.reduce((sum, it) => (
        it.id in selection ? sum + toNumber(selection[it.id]) * Number(it.unitPriceExclVat ?? 0) : sum
    ), 0);

    return (
        <div className="table-responsive">
        <table className="table table-sm table-hover align-middle mb-0">
            <thead className="table-light">
            <tr>
                <th scope="col" style={{ width: "2.5rem" }} className="text-center">
                    <input ref={selectAllRef} type="checkbox" className="form-check-input"
                           checked={allSelected} onChange={toggleAll}
                           disabled={selectableItems.length === 0} aria-label="Vybrat vše"/>
                </th>
                <th scope="col">Název položky</th>
                <th scope="col" className="text-end">Zbývá</th>
                <th scope="col" className="text-end">Dostupné</th>
                <th scope="col" className="text-end" style={{ width: "9rem" }}>Importovat</th>
                <th scope="col" className="text-end">Nákup bez DPH</th>
                <th scope="col" className="text-end">DPH</th>
            </tr>
            </thead>
            <tbody>
            {items.map(item => {
                const selected  = item.id in selection;
                const remaining = Number(item.quantityRemaining);
                const available = availableOf(item);
                const reserved  = remaining - available;
                const blocked   = available <= 0;
                const raw       = selected ? selection[item.id] : "";
                const invalid   = selected && isSelectionInvalid(item, raw);

                return (
                    <tr key={item.id} className={selected ? "" : "text-muted"}>
                        <td className="text-center">
                            <input type="checkbox" className="form-check-input"
                                   checked={selected} disabled={blocked}
                                   onChange={() => toggleRow(item)}
                                   aria-label={`Vybrat ${item.nameSnapshot}`}/>
                        </td>
                        <td>
                            {item.nameSnapshot}
                            {blocked && (
                                <span className="d-block small text-warning-emphasis">
                                    <i className="bi bi-lock me-1" aria-hidden="true"></i>
                                    Celé množství je rezervované na jiné zakázky.
                                </span>
                            )}
                        </td>
                        <td className="text-end">{formatQuantity(item.quantityRemaining)}</td>
                        <td className={`text-end ${blocked ? "text-danger" : "fw-semibold"}`}>
                            {formatQuantity(available)}
                            {reserved > 0 && !blocked && (
                                <span className="d-block small text-muted">
                                    {formatQuantity(reserved)} rezervováno
                                </span>
                            )}
                        </td>
                        <td className="text-end">
                            <input type="number" min="0" max={available} step="any"
                                   className={`form-control form-control-sm text-end ${invalid ? "is-invalid" : ""}`}
                                   value={raw} disabled={!selected}
                                   onChange={e => setQuantity(item, e.target.value)}
                                   aria-label={`Množství k importu — ${item.nameSnapshot}`}/>
                            {invalid && <div className="invalid-feedback">Max {formatQuantity(available)}</div>}
                        </td>
                        <td className="text-end text-muted">{formatCurrency(item.unitPriceExclVat)}</td>
                        <td className="text-end">{item.vatRate} %</td>
                    </tr>
                );
            })}
            </tbody>
            <tfoot>
            <tr className="border-top">
                <td colSpan={4} className="text-muted small">{selectedCount} z {items.length} vybráno</td>
                <td colSpan={3} className="text-end fw-semibold">{formatCurrency(totalPurchase)}</td>
            </tr>
            </tfoot>
        </table>
        </div>
    );
}
