import * as React from "react";
import { useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { api, problemMessage } from "../api/api.js";
import {
    formatCurrency, formatNumber, formatQuantity, formatDate,
    getOrderStatusLabel, getOrderStatusTone, getStkBadge,
} from "../api/format.js";
import PageHeader from "../components/PageHeader.jsx";
import MetricRow from "../components/MetricRow.jsx";
import MetricCard from "../components/MetricCard.jsx";
import StatusBadge from "../components/StatusBadge.jsx";
import LoadingState from "../components/LoadingState.jsx";
import EmptyState from "../components/EmptyState.jsx";
import DashboardStatisticsModal from "../components/DashboardStatisticsModal.jsx";

/**
 * Úvodní přehled. Dashboard je rozcestník k akci, ne galerie čísel: každá
 * dlaždice odkazuje na už existující seznam nebo detail. Řazení podle
 * naléhavosti — nejdřív „vyžaduje pozornost", pak běžný provoz.
 *
 * Data z jednoho volání {@code /dashboard/summary} (BFF vzor). Preview je
 * omezené na 5 řádků, počty jsou úplné.
 */
export default function DashboardPage() {

    const navigate = useNavigate();
    const [summary, setSummary] = useState(null);
    const [error, setError]     = useState(null);
    const [showStatistics, setShowStatistics] = useState(false);

    useEffect(() => {
        async function load() {
            try {
                setSummary(await api.get("/dashboard/summary"));
            } catch (err) {
                setError(problemMessage(err, "Zkuste to prosím znovu."));
            }
        }
        load();
    }, []);

    if (error) return (
        <div>
            <PageHeader title="Přehled" />
            <EmptyState
                icon="exclamation-triangle"
                title="Přehled se nepodařilo načíst."
                hint={error}
                action={
                    <button type="button" className="btn btn-outline-secondary"
                            onClick={() => window.location.reload()}>
                        <i className="bi bi-arrow-clockwise me-1" aria-hidden="true"></i>Zkusit znovu
                    </button>
                }
            />
        </div>
    );
    if (summary === null) return <LoadingState />;

    const { orders, invoices, warehouse, vehicles, revenue, margin } = summary;
    const monthTrend = revenue.currentMonth - revenue.previousMonth;
    const marginTrend = margin.totalCurrentMonth - margin.totalPreviousMonth;

    return (
        <div>
            <PageHeader title="Přehled" subtitle="Co dnes potřebuje pozornost."
                        actions={
                            <button type="button" className="btn btn-outline-secondary"
                                    onClick={() => setShowStatistics(true)}>
                                <i className="bi bi-graph-up me-1" aria-hidden="true"></i>Statistika
                            </button>
                        } />

            <DashboardStatisticsModal show={showStatistics} onClose={() => setShowStatistics(false)} />

            {/* KPI řádek */}
            <MetricRow>
                <MetricCard label="Rozpracované zakázky" value={orders.inProgressTotal} />
                <MetricCard label="K vyfakturování" value={orders.toInvoiceCount}
                            tone={orders.toInvoiceCount > 0 ? "warning" : undefined} />
                <MetricCard label="Pohledávky po splatnosti (s DPH)" value={formatCurrency(invoices.overdueTotal)}
                            tone={invoices.overdueCount > 0 ? "danger" : undefined} />
                <MetricCard label="Hodnota skladu (bez DPH)" value={formatCurrency(warehouse.stockValue)} />
            </MetricRow>

            {/* Vyžaduje pozornost */}
            <h2 className="h6 text-uppercase text-muted mb-2">
                <i className="bi bi-exclamation-triangle me-2" aria-hidden="true"></i>Vyžaduje pozornost
            </h2>
            <div className="row g-3 mb-4">
                <Tile
                    icon="clock-history" tone="danger" title="Zakázky po termínu"
                    count={orders.overdueCount}
                    emptyText="Žádná zakázka není po termínu."
                    footerLabel="Všechny zakázky" onFooter={() => navigate("/orders")}
                    items={orders.overdue}
                    rowKey={o => o.id}
                    onRow={o => navigate(`/orders/${o.id}/detail`)}
                    renderRow={o => (
                        <Row
                            primary={o.orderNumber}
                            secondary={vehicleText(o)}
                            trailing={<span className="text-danger small fw-medium">+{o.daysOverdue} dní</span>}
                        />
                    )}
                />
                <Tile
                    icon="cash-stack" tone="danger" title="Faktury po splatnosti"
                    count={invoices.overdueCount}
                    subtitle={invoices.overdueCount > 0 ? formatCurrency(invoices.overdueTotal) : null}
                    emptyText="Žádná faktura není po splatnosti."
                    footerLabel="Zobrazit po splatnosti" onFooter={() => navigate("/invoices?overdue=true")}
                    items={invoices.overdue}
                    rowKey={i => i.id}
                    onRow={i => navigate(`/invoices/${i.id}/detail`)}
                    renderRow={i => (
                        <Row
                            primary={i.invoiceNumber}
                            secondary={i.customerName}
                            trailing={
                                <span className="text-end">
                                    <span className="d-block small fw-medium">{formatCurrency(i.amount)}</span>
                                    <span className="d-block text-danger small">+{i.daysOverdue} dní</span>
                                </span>
                            }
                        />
                    )}
                />
                <Tile
                    icon="box-seam" tone="warning" title="Sklad pod minimem"
                    count={warehouse.belowMinimumCount}
                    emptyText="Všechny hlídané díly jsou nad minimem."
                    footerLabel="Pod minimem" onFooter={() => navigate("/warehouse/low-stock")}
                    items={warehouse.belowMinimum}
                    rowKey={p => p.productId}
                    onRow={p => navigate(`/warehouse/${p.productId}/detail`)}
                    renderRow={p => (
                        <Row
                            primary={p.name}
                            secondary={p.supplierName ?? "zatím bez dodavatele"}
                            trailing={<span className="text-warning-emphasis small fw-medium">chybí {formatQuantity(p.missingQuantity, p.unit)}</span>}
                        />
                    )}
                />
                <Tile
                    icon="card-checklist" tone="warning" title="Končící / propadlá STK"
                    count={vehicles.stkExpiringCount}
                    emptyText="Žádnému vozidlu nekončí STK do 30 dnů."
                    note="Čerstvost dle posledního načtení z registru."
                    footerLabel="Všechna vozidla" onFooter={() => navigate("/vehicles")}
                    items={vehicles.stkExpiring}
                    rowKey={v => v.id}
                    onRow={v => navigate(`/vehicles/${v.id}/detail`)}
                    renderRow={v => {
                        const badge = getStkBadge(v.stkValidUntil);
                        return (
                            <Row
                                primary={v.licensePlate}
                                secondary={v.label}
                                trailing={<StatusBadge tone={badge.tone}>{badge.label}</StatusBadge>}
                            />
                        );
                    }}
                />
            </div>

            {/* Provoz */}
            <h2 className="h6 text-uppercase text-muted mb-2">
                <i className="bi bi-clipboard-check me-2" aria-hidden="true"></i>Provoz
            </h2>
            <div className="row g-3">
                {/* Rozpracované dle stavu */}
                <div className="col-12 col-md-6 col-xl-4">
                    <div className="card border-0 shadow-sm h-100">
                        <div className="card-body">
                            <h3 className="h6 mb-3">Rozpracované dle stavu</h3>
                            {orders.inProgressTotal === 0 ? (
                                <p className="text-muted small mb-0">Žádná rozpracovaná zakázka.</p>
                            ) : (
                                <ul className="list-unstyled mb-0">
                                    {Object.entries(orders.byStatus).map(([status, count]) => (
                                        <li key={status}
                                            className="d-flex align-items-center justify-content-between py-1">
                                            <StatusBadge tone={getOrderStatusTone(status)}>
                                                {getOrderStatusLabel(status)}
                                            </StatusBadge>
                                            <span className="fw-medium">{count}</span>
                                        </li>
                                    ))}
                                </ul>
                            )}
                        </div>
                    </div>
                </div>

                <Tile
                    icon="check2-circle" tone="success" title="Připraveno k vyzvednutí"
                    count={orders.readyForPickupCount}
                    emptyText="Žádné vozidlo nečeká na vyzvednutí."
                    footerLabel="Všechny zakázky" onFooter={() => navigate("/orders")}
                    items={orders.readyForPickup}
                    rowKey={o => o.id}
                    onRow={o => navigate(`/orders/${o.id}/detail`)}
                    renderRow={o => (
                        <Row primary={o.orderNumber} secondary={vehicleText(o)} />
                    )}
                />

                {/* Sklad — úkoly */}
                <div className="col-12 col-md-6 col-xl-4">
                    <div className="card border-0 shadow-sm h-100">
                        <div className="card-body">
                            <h3 className="h6 mb-3">Sklad — úkoly</h3>
                            <button type="button"
                                    className="btn btn-link p-0 d-flex align-items-center justify-content-between w-100 text-decoration-none mb-2"
                                    onClick={() => navigate("/warehouse/receipts")}>
                                <span><i className="bi bi-inboxes me-2" aria-hidden="true"></i>Příjemky ke kontrole</span>
                                {warehouse.pendingReceiptsCount > 0
                                    ? <StatusBadge tone="warning">{warehouse.pendingReceiptsCount}</StatusBadge>
                                    : <span className="text-muted small">0</span>}
                            </button>
                            {warehouse.openStockTake ? (
                                <button type="button"
                                        className="btn btn-link p-0 d-flex align-items-center justify-content-between w-100 text-decoration-none"
                                        onClick={() => navigate(`/warehouse/stock-takes/${warehouse.openStockTake.id}`)}>
                                    <span><i className="bi bi-clipboard-data me-2" aria-hidden="true"></i>Probíhá inventura</span>
                                    <StatusBadge tone="info">otevřená</StatusBadge>
                                </button>
                            ) : (
                                <div className="d-flex align-items-center justify-content-between text-muted">
                                    <span><i className="bi bi-clipboard-data me-2" aria-hidden="true"></i>Probíhá inventura</span>
                                    <span className="small">žádná</span>
                                </div>
                            )}
                        </div>
                    </div>
                </div>

                {/* Tržby */}
                <div className="col-12 col-md-6 col-xl-4">
                    <div className="card border-0 shadow-sm h-100">
                        <div className="card-body">
                            <h3 className="h6 mb-3">Tržby tento měsíc <span className="text-muted fw-normal">(s DPH)</span></h3>
                            <div className="h4 mb-1 fw-medium">{formatCurrency(revenue.currentMonth)}</div>
                            <div className="small text-muted">
                                Minulý měsíc {formatCurrency(revenue.previousMonth)}
                                {revenue.previousMonth > 0 && (
                                    <span className={`ms-2 ${monthTrend >= 0 ? "text-success" : "text-danger"}`}>
                                        <i className={`bi bi-arrow-${monthTrend >= 0 ? "up" : "down"}-short`} aria-hidden="true"></i>
                                        {formatCurrency(Math.abs(monthTrend))}
                                    </span>
                                )}
                            </div>
                            <div className="small text-muted mt-2">Z vystavených a zaplacených faktur.</div>
                        </div>
                    </div>
                </div>

                {/* Marže */}
                <div className="col-12 col-md-6 col-xl-4">
                    <div className="card border-0 shadow-sm h-100">
                        <div className="card-body">
                            <h3 className="h6 mb-3">Marže tento měsíc <span className="text-muted fw-normal">(bez DPH)</span></h3>
                            <div className="h4 mb-1 fw-medium">{formatCurrency(margin.totalCurrentMonth)}</div>
                            <div className="small text-muted">
                                Materiál {formatCurrency(margin.materialCurrentMonth)}
                                {" · "}Práce {formatCurrency(margin.laborCurrentMonth)}
                            </div>
                            <div className="small text-muted mt-1">
                                Minulý měsíc {formatCurrency(margin.totalPreviousMonth)}
                                {margin.totalPreviousMonth > 0 && (
                                    <span className={`ms-2 ${marginTrend >= 0 ? "text-success" : "text-danger"}`}>
                                        <i className={`bi bi-arrow-${marginTrend >= 0 ? "up" : "down"}-short`} aria-hidden="true"></i>
                                        {formatCurrency(Math.abs(marginTrend))}
                                    </span>
                                )}
                            </div>
                            <div className="small text-muted mt-2">Z vyfakturovaných zakázek; položky bez známého nákladu se nezapočítají.</div>
                        </div>
                    </div>
                </div>
            </div>
        </div>
    );
}

/** „Značka model" pro řádek zakázky; prázdné složí na SPZ. */
function vehicleText(o) {
    const label = [o.vehicleLabel, o.vehicleLicensePlate].filter(Boolean).join(" · ");
    return label || "—";
}

/**
 * Dlaždice s frontou: nadpis, počet, max 5 proklikávacích řádků a odkaz na celý
 * seznam. Prázdná fronta = pozitivní hláška, ne prázdné místo.
 */
function Tile({ icon, tone, title, count, subtitle, note, items, rowKey, onRow, renderRow, emptyText, footerLabel, onFooter }) {
    return (
        <div className="col-12 col-md-6 col-xl-4">
            <div className="card border-0 shadow-sm h-100">
                <div className="card-body d-flex flex-column">
                    <div className="d-flex align-items-center justify-content-between mb-2">
                        <h3 className="h6 mb-0">
                            <i className={`bi bi-${icon} me-2 text-${tone}`} aria-hidden="true"></i>{title}
                        </h3>
                        {count > 0 && <StatusBadge tone={tone}>{count}</StatusBadge>}
                    </div>

                    {subtitle && <div className="text-muted small mb-2">{subtitle}</div>}

                    {count === 0 ? (
                        <p className="text-success small mb-0">
                            <i className="bi bi-check-circle me-1" aria-hidden="true"></i>{emptyText}
                        </p>
                    ) : (
                        <>
                            <ul className="list-unstyled mb-0 flex-grow-1">
                                {items.map(item => (
                                    <li key={rowKey(item)}>
                                        <button type="button"
                                                className="btn btn-link text-reset text-decoration-none w-100 px-0 py-1 border-bottom"
                                                onClick={() => onRow(item)}>
                                            {renderRow(item)}
                                        </button>
                                    </li>
                                ))}
                            </ul>
                            <button type="button"
                                    className="btn btn-sm btn-link px-0 mt-2 align-self-start text-decoration-none"
                                    onClick={onFooter}>
                                {footerLabel}<i className="bi bi-arrow-right ms-1" aria-hidden="true"></i>
                            </button>
                        </>
                    )}

                    {note && <div className="text-muted fst-italic small mt-2">{note}</div>}
                </div>
            </div>
        </div>
    );
}

/** Jeden řádek fronty: hlavní text, doplněk a volitelný pravý sloupec. */
function Row({ primary, secondary, trailing }) {
    return (
        <div className="d-flex align-items-center justify-content-between gap-2 text-start">
            <span className="text-truncate">
                <span className="d-block small fw-medium text-truncate">{primary}</span>
                <span className="d-block text-muted small text-truncate">{secondary}</span>
            </span>
            {trailing && <span className="flex-shrink-0">{trailing}</span>}
        </div>
    );
}
