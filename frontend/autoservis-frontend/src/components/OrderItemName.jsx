import * as React from "react";
import { Link } from "react-router-dom";

/**
 * Buňka s názvem položky a vším, co k němu patří — sdílená editovatelnou i read-only
 * tabulkou položek, aby se nemohly rozejít.
 *
 * <p>Pod názvem se skládají jen ty podřádky, které dávají u konkrétní položky smysl:
 *
 * <ul>
 *   <li><strong>katalogové číslo</strong> — dodavatelské názvy se opakují, takže dvě
 *       položky z různých šarží se běžně jmenují stejně a liší se jen cenou,</li>
 *   <li><strong>původ</strong> (dodavatel · číslo jeho faktury) — odpovídá na otázku
 *       „u koho tenhle díl reklamovat". Díl odejde v záruce za půl roku a doklad, na kterém
 *       přišel, se do 2026-08-07 dohledával ručně ve skladu; řetěz položka → šarže → příjemka
 *       přitom v datech existoval od V18. Proklik vede na příjemku,</li>
 *   <li><strong>mechanik</strong> u položky typu práce,</li>
 *   <li><strong>poznámka</strong> — pole existovalo od V12 a šlo vyplnit v editačním okně,
 *       ale <em>ani jedna</em> tabulka ho nevykreslovala. Co tam mechanik napsal, viděl jen
 *       ten, kdo položku znovu otevřel.</li>
 * </ul>
 *
 * <p>Ručně zadaná položka nemá ani SKU, ani původ — se skladem nemá nic společného.
 */
export default function OrderItemName({ item }) {
    return (
        <>
            {item.name}

            {item.productSku && (
                <span className="d-block small text-muted font-monospace">{item.productSku}</span>
            )}

            {item.supplierName && (
                <span className="d-block small text-muted">
                    <i className="bi bi-truck me-1" aria-hidden="true"></i>
                    {item.goodsReceiptId ? (
                        <Link to={`/warehouse/receipts/${item.goodsReceiptId}/review`}
                              title="Otevřít příjemku, na které díl přišel">
                            {item.supplierName}
                            {item.receiptInvoiceNumber && <> · {item.receiptInvoiceNumber}</>}
                        </Link>
                    ) : (
                        <>{item.supplierName}</>
                    )}
                </span>
            )}

            {item.employeeName && (
                <span className="d-block small text-muted">
                    <i className="bi bi-person-badge me-1" aria-hidden="true"></i>{item.employeeName}
                </span>
            )}

            {item.note && (
                <span className="d-block small text-muted fst-italic">
                    <i className="bi bi-sticky me-1" aria-hidden="true"></i>{item.note}
                </span>
            )}
        </>
    );
}
