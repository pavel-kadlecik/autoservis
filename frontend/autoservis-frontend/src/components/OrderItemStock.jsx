import * as React from "react";
import StatusBadge from "./StatusBadge.jsx";

/**
 * Stav položky vůči skladu — sdílený editovatelnou i read-only tabulkou položek.
 *
 * <p>Do 2026-08-07 tyhle tři případy vypadaly v tabulce naprosto stejně, přestože znamenají
 * něco úplně jiného. Rezervační model (V83) je zavedl do dat, ale na obrazovku se nepropsaly:
 *
 * <ul>
 *   <li><strong>ruční materiál</strong> — sklad se ho netýká, žádná vazba na šarži,</li>
 *   <li><strong>rezervováno</strong> — díl leží dál v regálu, jen je pro tuhle zakázku slíbený,</li>
 *   <li><strong>vydáno</strong> — díl z regálu odešel a je na autě.</li>
 * </ul>
 *
 * <p>Rozdíl je praktický: mechanik potřebuje vědět, jestli si díl už vzal, a při rušení
 * zakázky se vrací jen to, co bylo vydáno.
 *
 * <p>Částečný výdej běžně nevzniká — výdej i srovnání množství pracují s celou položkou —
 * ale zobrazuje se poctivě, kdyby k němu ledger přece jen vedl. Tichá „Vydáno" u poloviny
 * kusů by lhala právě ve chvíli, kdy se něco pokazilo.
 */
export default function OrderItemStock({ item }) {

    if (!item.fromStock) return <span className="text-body-tertiary">—</span>;

    const issued = Number(item.issuedQuantity ?? 0);
    const quantity = Number(item.quantity ?? 0);

    if (issued <= 0) {
        return (
            <StatusBadge tone="secondary"
                         title="Díl je pro tuhle zakázku rezervovaný — fyzicky leží dál v regálu.">
                Rezervováno
            </StatusBadge>
        );
    }

    if (issued < quantity) {
        return (
            <StatusBadge tone="warning" title={`Ze skladu odešlo ${issued} z ${quantity}.`}>
                Vydáno částečně
            </StatusBadge>
        );
    }

    return (
        <StatusBadge tone="success" title="Díl už ze skladu odešel.">
            Vydáno
        </StatusBadge>
    );
}
