import * as React from "react";
import {formatCurrency} from "../api/format.js";

/**
 * Marže se počítá z cen BEZ DPH: marže = tržba (bez DPH) − náklad.
 * DPH je průběžná položka a do marže nevstupuje.
 * Marže % = marže / tržba (obchodní marže z prodejní ceny). Při nulové tržbě „—".
 */
function marginPercent(net, margin) {
    const n = Number(net);
    if (!n) return null;
    return (margin / n) * 100;
}

function MarginRow({label, net, cost, bold}) {
    const margin = Number(net) - Number(cost);
    const pct = marginPercent(net, margin);
    const cls = margin < 0 ? "text-danger" : "text-success";
    return (
        <>
            <td className="text-end">{formatCurrency(cost)}</td>
            <td className={`text-end ${cls}`}>{formatCurrency(margin)}</td>
            <td className={`text-end ${cls}`}>{pct === null ? "—" : `${pct.toFixed(0)} %`}</td>
        </>
    );
}

export default function({summary}) {

    if (!summary)
        return null;

    return (
        <div className="d-flex justify-content-end">
            <div className="w-75">
            <table className="table w-100 table-hover table-sm">
                <thead>
                <tr>
                    <th scope="col"></th>
                    <th scope="col" className="text-end">Náklad</th>
                    <th scope="col" className="text-end">Marže</th>
                    <th scope="col" className="text-end">Marže %</th>
                    <th scope="col" className="text-end">bez DPH</th>
                    <th scope="col" className="text-end">s DPH</th>
                </tr>
                </thead>
                <tbody>
                <tr>
                    <td>Práce</td>
                    <MarginRow net={summary.laborNet} cost={summary.laborCost}/>
                    <td className="text-end">{formatCurrency(summary.laborNet)}</td>
                    <td className="text-end">{formatCurrency(summary.laborGross)}</td>
                </tr>
                <tr>
                    <td>Materiál</td>
                    <MarginRow net={summary.materialNet} cost={summary.materialCost}/>
                    <td className="text-end">{formatCurrency(summary.materialNet)}</td>
                    <td className="text-end">{formatCurrency(summary.materialGross)}</td>
                </tr>
                <tr>
                    <td>Ostatní</td>
                    <MarginRow net={summary.serviceNet} cost={summary.serviceCost}/>
                    <td className="text-end">{formatCurrency(summary.serviceNet)}</td>
                    <td className="text-end">{formatCurrency(summary.serviceGross)}</td>
                </tr>
                <tr className="fw-bold">
                    <td>Celkem</td>
                    <MarginRow net={summary.totalNet} cost={summary.totalCost}/>
                    <td className="text-end">{formatCurrency(summary.totalNet)}</td>
                    <td className="text-end">{formatCurrency(summary.totalGross)}</td>
                </tr>
                </tbody>
            </table>
            <p className="text-muted small text-end mt-1 mb-0">
                Marže se počítá z cen bez DPH. U ruční položky bez zadané nákupní ceny
                je náklad 0 → marže vychází 100 %.
            </p>
            </div>
        </div>
    );

}
