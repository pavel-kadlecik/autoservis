import * as React from 'react';
import { useEffect, useState } from "react";
import { Link, useNavigate, useParams } from "react-router-dom";
import { api, problemMessage } from "../api/api.js";
import {
    CUSTOMER_TYPE_LABELS,
    formatDate,
    getActiveLabel,
    getActiveTone,
    getAddressTypeLabel,
    getChannelLabel,
    getCountryName,
} from "../api/format.js";
import ContactPersonCard from "../components/ContactPersonCard.jsx";
import ConfirmDialog from "../components/ConfirmDialog.jsx";
import CustomerVehiclesTable from "../components/CustomerVehiclesTable.jsx";
import StatusBadge from "../components/StatusBadge.jsx";
import PageHeader from "../components/PageHeader.jsx";
import LoadingState from "../components/LoadingState.jsx";
import MetricCard from "../components/MetricCard.jsx";
import MetricRow from "../components/MetricRow.jsx";
import DetailCard from "../components/DetailCard.jsx";
import EmptyState from "../components/EmptyState.jsx";
import OrderHistoryTable from "../components/OrderHistoryTable.jsx";
import CustomerInvoicesTable from "../components/CustomerInvoicesTable.jsx";
import ErrorState from "../components/ErrorState.jsx";

/** Kolik zakázek ukáže karta historie; na zbytek vede odkaz do seznamu zakázek. */
const HISTORY_PAGE_SIZE = 10;

export default function CustomersPageDetail() {

    const { id } = useParams();
    const navigate = useNavigate();

    const [customer, setCustomer] = useState(null);
    // Bez ošetření chyby zůstal detail navždy na spinneru „Načítám…" (KN-14) —
    // třeba u zastaralého odkazu na smazaný záznam (404).
    const [loadError, setLoadError] = useState("");
    const [vehicles, setVehicles] = useState([]);
    const [showConfirm, setShowConfirm] = useState(false);
    const [refreshKey, setRefreshKey] = useState(0);

    // Zakázky a faktury zákazníka (KN-27) — vlastní načtení, aby jejich selhání nesundalo
    // celý detail. Zakázky jsou stránkované (jen prvních HISTORY_PAGE_SIZE + celkový počet),
    // faktury vrací endpoint /invoices/customer/{id} celé; faktur je vždy nejvýš tolik,
    // kolik má zákazník zakázek, takže se do karty vejdou bez zkracování.
    const [orders, setOrders] = useState(null);
    const [ordersTotal, setOrdersTotal] = useState(0);
    const [historyError, setHistoryError] = useState(false);
    const [invoices, setInvoices] = useState(null);
    const [invoicesError, setInvoicesError] = useState(false);

    function toggleVehicleStatus() {
        setRefreshKey(prev => prev + 1);
    }

    useEffect(() => {

        async function loadCustomer() {
            try {

                const [customerData, vehiclesData] = await Promise.all([
                    api.get(`/customers/${id}`),
                    api.get(`/customers/${id}/vehicles`),
                ]);

                setCustomer(customerData);
                setVehicles(vehiclesData)

                setLoadError("");
            } catch (err) {
                setLoadError(problemMessage(err, "Zákazníka se nepodařilo načíst."));
            }
        }
        loadCustomer();



    }, [id, refreshKey]);

    useEffect(() => {
        async function loadOrders() {
            setHistoryError(false);
            try {
                const data = await api.get(
                    `/orders?customerId=${id}&page=1&pageSize=${HISTORY_PAGE_SIZE}&sortBy=createdAt&sortDesc=true`);
                setOrders(data.content ?? []);
                setOrdersTotal(data.totalElements ?? 0);
            } catch {
                setOrders([]);
                setHistoryError(true);
            }
        }

        async function loadInvoices() {
            setInvoicesError(false);
            try {
                setInvoices(await api.get(`/invoices/customer/${id}`) ?? []);
            } catch {
                setInvoices([]);
                setInvoicesError(true);
            }
        }

        loadOrders();
        loadInvoices();
    }, [id]);

    async function handleToggleStatus() {
        if (customer.active) {
            const updated = await api.delete(`/customers/${id}`);
            setCustomer(updated);
        } else {
            const updated = await api.post(`/customers/${id}/activate`);
            setCustomer(updated);
        }
        setShowConfirm(false);
    }

    if (!customer && !loadError) return <LoadingState />;
    if (!customer) {
        return <ErrorState message={loadError} backTo="/customers" backLabel="Zpět na zákazníky" />;
    }

    const isCompany = customer.customerType === 'COMPANY';

    return (
        <div>

            <PageHeader
                title={customer.displayName}
                subtitle={customer.customerNumber}
                backTo="/customers"
                badges={
                    <>
                        <CustomerTypeBadge type={customer.customerType} />
                        <StatusBadge tone={getActiveTone(customer.active)}>
                            {getActiveLabel(customer.active)}
                        </StatusBadge>
                    </>
                }
                actions={
                    <>
                        <button className="btn btn-outline-secondary"
                                onClick={() => navigate(`/customers/${id}/edit`)}>
                            <i className="bi bi-pencil me-1" aria-hidden="true"></i>Editovat
                        </button>
                        <button className={customer.active ? 'btn btn-outline-danger' : 'btn btn-outline-success'}
                                onClick={() => setShowConfirm(true)}>
                            <i className={`bi bi-${customer.active ? 'slash-circle' : 'check-circle'} me-1`}
                               aria-hidden="true"></i>
                            {customer.active ? 'Deaktivovat' : 'Aktivovat'}
                        </button>
                    </>
                }
            />

            {/* ── Karty metrik ───────────────────────────────────────── */}
            <MetricRow>
                <MetricCard label="Věrnostní body" value={customer.loyaltyPoints ?? 0} unit="b." />
                <MetricCard label="Zákazník od"    value={formatDate(customer.createdAt)} />
                <MetricCard label="Adresy"         value={customer.addresses?.length ?? 0} />
                <MetricCard label="Vozidla"        value={customer.vehicles?.length ?? '—'} />
            </MetricRow>

            {/* ── Hlavní obsah ────────────────────────────────────────── */}
            <div className="row g-3">

                {/* Levý sloupec */}
                <div className="col-md-6">

                    {/* Kontakt */}
                    <DetailCard title="Kontakt">
                            <dl className="row mb-0">
                                <dt className="col-sm-5 text-muted fw-normal">Email</dt>
                                <dd className="col-sm-7">
                                    {customer.primaryEmail
                                        ? <a href={`mailto:${customer.primaryEmail}`}>{customer.primaryEmail}</a>
                                        : '—'}
                                </dd>

                                <dt className="col-sm-5 text-muted fw-normal">Telefon</dt>
                                <dd className="col-sm-7">{customer.primaryPhone ?? '—'}</dd>

                                <dt className="col-sm-5 text-muted fw-normal">Preferovaný kanál</dt>
                                <dd className="col-sm-7">{getChannelLabel(customer.preferredContactChannel)}</dd>
                            </dl>
                    </DetailCard>

                    {/* Osobní / Firemní údaje */}
                    <DetailCard title={isCompany ? 'Firemní údaje' : 'Osobní údaje'}>
                            <dl className="row mb-0">
                                {isCompany
                                    ? <CompanySection customer={customer} />
                                    : <PersonalSection customer={customer} />}
                            </dl>
                    </DetailCard>
                </div>

                {/* Pravý sloupec */}
                <div className="col-md-6">

                    {/* Adresy */}
                    <DetailCard title="Adresy">
                            {customer.addresses.length === 0 ? (
                                <p className="text-muted fst-italic mb-0">Žádná adresa</p>
                            ) : (
                                customer.addresses.map(address => (
                                    <div key={address.id} className="mb-3">
                                        <div className="fw-medium mb-1">
                                            {getAddressTypeLabel(address.addressType)}
                                            {address.isDefault && (
                                                <StatusBadge tone="secondary" className="ms-2 fw-normal">
                                                    výchozí
                                                </StatusBadge>
                                            )}
                                        </div>
                                        <address className="mb-0 text-muted small">
                                            {address.street} {address.streetNumber}<br />
                                            {address.postalCode} {address.city}<br />
                                            {getCountryName(address.countryCode)}
                                        </address>
                                    </div>
                                ))
                            )}
                    </DetailCard>

                    {/* Kontaktní osoby — pouze pro firmy (nadpis si nese sama) */}
                    {isCompany && (
                        <DetailCard>
                            <ContactPersonCard customer={customer} />
                        </DetailCard>
                    )}

                    {/* Souhlasy */}
                    <DetailCard title="Souhlasy">
                            <dl className="row mb-0">
                                <dt className="col-sm-5 text-muted fw-normal">GDPR</dt>
                                <dd className="col-sm-7">
                                    <ConsentBadge value={customer.gdprConsent} />
                                </dd>

                                <dt className="col-sm-5 text-muted fw-normal">Marketing</dt>
                                <dd className="col-sm-7">
                                    <ConsentBadge value={customer.marketingConsent} />
                                </dd>
                            </dl>
                    </DetailCard>

                    {/* Interní poznámka — zobrazí se pouze pokud existuje */}
                    {customer.internalNote && (
                        <DetailCard title={<><i className="bi bi-lock me-1"></i>Interní poznámka</>}>
                            <p className="text-muted fst-italic mb-0 small">
                                {customer.internalNote}
                            </p>
                        </DetailCard>
                    )}

                    {/* Metadata */}
                    <DetailCard title="Metadata">
                            <dl className="row mb-0">
                                <dt className="col-sm-5 text-muted fw-normal">Zadáno</dt>
                                <dd className="col-sm-7 small">{formatDate(customer.createdAt)}</dd>

                                <dt className="col-sm-5 text-muted fw-normal">Aktualizováno</dt>
                                <dd className="col-sm-7 small">{formatDate(customer.updatedAt)}</dd>
                            </dl>
                    </DetailCard>
                </div>
            </div>

            <DetailCard title="Vozidla zákazníka" className="mt-3">
                <CustomerVehiclesTable
                    vehicles={vehicles}
                    toggleStatus={toggleVehicleStatus}
                />
            </DetailCard>

            <DetailCard title="Zakázky zákazníka" className="mt-3" action={
                ordersTotal > HISTORY_PAGE_SIZE && (
                    <Link className="btn btn-sm btn-outline-secondary" to={`/orders?customerId=${id}`}>
                        Zobrazit všech {ordersTotal}
                        <i className="bi bi-arrow-right ms-1" aria-hidden="true"></i>
                    </Link>
                )
            }>
                {historyError ? (
                    <EmptyState icon="exclamation-triangle"
                                title="Zakázky zákazníka se nepodařilo načíst."
                                hint="Zkuste stránku otevřít znovu." />
                ) : (
                    <OrderHistoryTable
                        orders={orders}
                        emptyTitle="Zákazník u nás zatím žádnou zakázku neměl."
                        emptyHint="Zakázku založíte v sekci Zakázky — zákazníka a jeho vozidlo u ní vyberete." />
                )}
            </DetailCard>

            <DetailCard title="Faktury zákazníka" className="mt-3">
                {invoicesError ? (
                    <EmptyState icon="exclamation-triangle"
                                title="Faktury zákazníka se nepodařilo načíst."
                                hint="Zkuste stránku otevřít znovu." />
                ) : (
                    <CustomerInvoicesTable invoices={invoices} />
                )}
            </DetailCard>

            {/* ── Potvrzovací dialog ────────────────────────────────── */}
            <ConfirmDialog
                title={customer.active ? 'Potvrďte deaktivaci' : 'Potvrďte aktivaci'}
                message={customer.active
                    ? `Opravdu chcete deaktivovat zákazníka ${customer.displayName}?`
                    : `Opravdu chcete aktivovat zákazníka ${customer.displayName}?`}
                show={showConfirm}
                onConfirm={handleToggleStatus}
                onCancel={() => setShowConfirm(false)}
            />
        </div>
    );
}

/* ── Sub-komponenty ─────────────────────────────────────────────── */

function CustomerTypeBadge({ type }) {
    return (
        <StatusBadge tone={type === 'INDIVIDUAL' ? 'info' : 'primary'}>
            {CUSTOMER_TYPE_LABELS[type] ?? type}
        </StatusBadge>
    );
}

function ConsentBadge({ value }) {
    return (
        <StatusBadge tone={value ? 'success' : 'secondary'}>
            {value ? 'Udělen' : 'Neudělen'}
        </StatusBadge>
    );
}

const PersonalSection = ({ customer }) => (
    <>
        <dt className="col-sm-5 text-muted fw-normal">Jméno</dt>
        <dd className="col-sm-7">{customer.firstName ?? '—'}</dd>

        <dt className="col-sm-5 text-muted fw-normal">Příjmení</dt>
        <dd className="col-sm-7">{customer.lastName ?? '—'}</dd>

        <dt className="col-sm-5 text-muted fw-normal">Datum narození</dt>
        <dd className="col-sm-7">{formatDate(customer.birthDate)}</dd>
    </>
);

const CompanySection = ({ customer }) => (
    <>
        <dt className="col-sm-5 text-muted fw-normal">Název firmy</dt>
        <dd className="col-sm-7">{customer.companyName ?? '—'}</dd>

        <dt className="col-sm-5 text-muted fw-normal">IČO</dt>
        <dd className="col-sm-7">{customer.ico ?? '—'}</dd>

        <dt className="col-sm-5 text-muted fw-normal">DIČ</dt>
        <dd className="col-sm-7">{customer.dic ?? '—'}</dd>

        <dt className="col-sm-5 text-muted fw-normal">Právní forma</dt>
        <dd className="col-sm-7">{customer.legalForm ?? '—'}</dd>
    </>
);
