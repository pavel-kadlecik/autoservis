import * as React from "react";
import { useEffect, useState } from "react";
import Modal from "./Modal.jsx";
import { api, problemMessage } from "../api/api.js";
import { formatCurrency } from "../api/format.js";

const MONTH_NAMES = [
    "Leden", "Únor", "Březen", "Duben", "Květen", "Červen",
    "Červenec", "Srpen", "Září", "Říjen", "Listopad", "Prosinec",
];

/**
 * Modal „Statistika" na dashboardu: měsíční tržby, marže a počty zakázek/faktur
 * za zvolený rok (GET /dashboard/statistics). Počítá se živě — nic se neukládá,
 * měsíce jsou zpětně spočitatelné z faktur a položek zakázek.
 *
 * Filtr roku nahrazuje stránkování: rok má nejvýš 12 řádků, není co stránkovat.
 */
export default function DashboardStatisticsModal({ show, onClose }) {

    const [year, setYear]   = useState(null);  // null = aktuální rok (rozhodne server)
    const [data, setData]   = useState(null);
    const [error, setError] = useState(null);

    useEffect(() => {
        if (!show) {
            setYear(null);
            setData(null);
            setError(null);
            return undefined;
        }
        let cancelled = false;
        async function load() {
            try {
                const query = year != null ? `?year=${year}` : "";
                const result = await api.get(`/dashboard/statistics${query}`);
                if (!cancelled) setData(result);
            } catch (err) {
                if (!cancelled) setError(problemMessage(err, "Statistiku se nepodařilo načíst."));
            }
        }
        load();
        return () => { cancelled = true; };
    }, [show, year]);

    // Roční součty jsou prostý součet zobrazených řádků — počítá je klient.
    const totals = (data?.months ?? []).reduce(
        (acc, m) => ({
            revenue:      acc.revenue + Number(m.revenue),
            margin:       acc.margin + Number(m.margin),
            orderCount:   acc.orderCount + m.orderCount,
            invoiceCount: acc.invoiceCount + m.invoiceCount,
        }),
        { revenue: 0, margin: 0, orderCount: 0, invoiceCount: 0 },
    );

    return (
        <Modal show={show} title="Statistika" size="modal-lg" onClose={onClose}>
            {error && <div className="alert alert-danger py-2">{error}</div>}
            {!data && !error && <p className="text-muted mb-0">Načítám…</p>}

            {data && (
                <>
                    <div className="d-flex align-items-center gap-2 mb-3">
                        <label className="form-label mb-0" htmlFor="statsYear">Rok</label>
                        <select id="statsYear" className="form-select form-select-sm w-auto"
                                value={data.year}
                                onChange={(e) => setYear(Number(e.target.value))}>
                            {(data.availableYears.length > 0 ? data.availableYears : [data.year])
                                .map(y => <option key={y} value={y}>{y}</option>)}
                        </select>
                    </div>

                    {data.months.length === 0 ? (
                        <p className="text-muted mb-0">V roce {data.year} zatím nejsou žádná data.</p>
                    ) : (
                        <div className="table-responsive">
                            <table className="table table-sm align-middle mb-2">
                                <thead>
                                    <tr>
                                        <th scope="col">Měsíc</th>
                                        <th scope="col" className="text-end">Zakázky</th>
                                        <th scope="col" className="text-end">Faktury</th>
                                        <th scope="col" className="text-end">Tržby <span className="text-muted fw-normal">(s DPH)</span></th>
                                        <th scope="col" className="text-end">Marže <span className="text-muted fw-normal">(bez DPH)</span></th>
                                    </tr>
                                </thead>
                                <tbody>
                                    {data.months.map(m => (
                                        <tr key={m.month}>
                                            <td>{MONTH_NAMES[m.month - 1]}</td>
                                            <td className="text-end">{m.orderCount}</td>
                                            <td className="text-end">{m.invoiceCount}</td>
                                            <td className="text-end">{formatCurrency(m.revenue)}</td>
                                            <td className={`text-end${Number(m.margin) < 0 ? " text-danger" : ""}`}>
                                                {formatCurrency(m.margin)}
                                            </td>
                                        </tr>
                                    ))}
                                </tbody>
                                <tfoot>
                                    <tr className="fw-bold">
                                        <td>Celkem {data.year}</td>
                                        <td className="text-end">{totals.orderCount}</td>
                                        <td className="text-end">{totals.invoiceCount}</td>
                                        <td className="text-end">{formatCurrency(totals.revenue)}</td>
                                        <td className={`text-end${totals.margin < 0 ? " text-danger" : ""}`}>
                                            {formatCurrency(totals.margin)}
                                        </td>
                                    </tr>
                                </tfoot>
                            </table>
                        </div>
                    )}

                    <div className="text-muted small">
                        Tržby a faktury dle data vystavení (vystavené a zaplacené); marže
                        z vyfakturovaných zakázek — položky bez známého nákladu se nezapočítají;
                        zakázky dle data založení.
                    </div>
                </>
            )}
        </Modal>
    );
}
