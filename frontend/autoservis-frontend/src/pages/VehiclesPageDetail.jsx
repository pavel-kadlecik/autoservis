import * as React from 'react';
import { useEffect, useState } from "react";
import { useNavigate, useParams, Link } from "react-router-dom";
import { api, problemMessage } from "../api/api.js";
import { formatDate, formatNumber, formatWheels, getActiveLabel, getActiveTone, getFuelLabel, getInitials, getStkBadge, getTransmissionLabel } from "../api/format.js";
import StatusBadge from "../components/StatusBadge.jsx";
import PageHeader from "../components/PageHeader.jsx";
import EntityAvatar from "../components/EntityAvatar.jsx";
import { useAlert } from "../context/AlertContext.jsx";
import ConfirmDialog from "../components/ConfirmDialog.jsx";
import MileageHistoryTable from "../components/MileageHistoryTable.jsx";
import MileageFormModal from "../components/MileageFormModal.jsx";
import OrderHistoryTable from "../components/OrderHistoryTable.jsx";
import LoadingState from "../components/LoadingState.jsx";
import MetricCard from "../components/MetricCard.jsx";
import MetricRow from "../components/MetricRow.jsx";
import DetailCard from "../components/DetailCard.jsx";
import EmptyState from "../components/EmptyState.jsx";
import ErrorState from "../components/ErrorState.jsx";

/** Kolik zakázek ukáže karta servisní historie; na zbytek vede odkaz do seznamu zakázek. */
const HISTORY_PAGE_SIZE = 10;

export default function VehiclesPageDetail() {

    const { id } = useParams();
    const navigate = useNavigate();
    const { addAlert } = useAlert();

    const [vehicle, setVehicle] = useState(null);
    // Bez ošetření chyby zůstal detail navždy na spinneru „Načítám…" (KN-14) —
    // třeba u zastaralého odkazu na smazaný záznam (404).
    const [loadError, setLoadError] = useState("");
    const [readings, setReadings] = useState([]);
    const [snapshots, setSnapshots] = useState([]);

    // Servisní historie (KN-27) — vlastní stav i vlastní načtení: kdyby seznam zakázek selhal,
    // nesmí to sundat celý detail vozidla. `ordersTotal` je počet VŠECH zakázek vozu (server ho
    // vrací v PagedResponse), i když se v kartě zobrazuje jen prvních HISTORY_PAGE_SIZE.
    const [orders, setOrders] = useState(null);
    const [ordersTotal, setOrdersTotal] = useState(0);
    const [ordersError, setOrdersError] = useState(false);
    const [showConfirm, setShowConfirm] = useState(false);
    const [refreshingRegistry, setRefreshingRegistry] = useState(false);

    // UI stav historie tachometru
    const [modalShow, setModalShow] = useState(false);
    const [editingReading, setEditingReading] = useState(null);
    const [modalError, setModalError] = useState(null);
    const [savingReading, setSavingReading] = useState(false);
    const [deleteReadingId, setDeleteReadingId] = useState(null);

    useEffect(() => {
        reloadVehicleAndReadings();
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [id]);

    useEffect(() => {
        async function loadOrderHistory() {
            setOrdersError(false);
            try {
                const data = await api.get(
                    `/orders?vehicleId=${id}&page=1&pageSize=${HISTORY_PAGE_SIZE}&sortBy=createdAt&sortDesc=true`);
                setOrders(data.content ?? []);
                setOrdersTotal(data.totalElements ?? 0);
            } catch {
                setOrders([]);
                setOrdersError(true);
            }
        }

        loadOrderHistory();
    }, [id]);

    /**
     * Znovu načte vozidlo, záznamy tachometru a snapshoty z registru. Vozidlo
     * se načítá také proto, že jeho cache sloupce current_mileage_km a
     * stk_valid_until přepočítávají DB triggery při každé změně záznamu
     * tachometru nebo snapshotu.
     */
    async function reloadVehicleAndReadings() {
        try {
            const [vehicleData, readingsData, snapshotsData] = await Promise.all([
                api.get(`/vehicles/${id}`),
                api.get(`/vehicles/${id}/mileage`),
                api.get(`/vehicles/${id}/registry-snapshots`),
            ]);
            setVehicle(vehicleData);
            setReadings(readingsData ?? []);
            setSnapshots(snapshotsData ?? []);
            setLoadError("");
        } catch (err) {
            setLoadError(problemMessage(err, "Vozidlo se nepodařilo načíst."));
        }
    }

    /** Stáhne aktuální údaje o STK ze státního registru vozidel a uloží snapshot. */
    async function handleRegistryRefresh() {
        setRefreshingRegistry(true);
        try {
            await api.post(`/vehicles/${id}/registry-refresh`);
            await reloadVehicleAndReadings();
            addAlert("Údaje z registru vozidel byly aktualizovány.", "success");
        } catch (err) {
            const message = problemMessage(err, "Registr vozidel se nepodařilo načíst.");
            addAlert(message, "danger");
        } finally {
            setRefreshingRegistry(false);
        }
    }

    async function handleToggleStatus() {
        if (vehicle.active) {
            const updated = await api.delete(`/vehicles/${id}`);
            setVehicle(updated);
        } else {
            const updated = await api.post(`/vehicles/${id}/activate`);
            setVehicle(updated);
        }
        setShowConfirm(false);
    }

    function openAddReading() {
        setEditingReading(null);
        setModalError(null);
        setModalShow(true);
    }

    function openEditReading(reading) {
        setEditingReading(reading);
        setModalError(null);
        setModalShow(true);
    }

    function closeReadingModal() {
        setModalShow(false);
        setEditingReading(null);
        setModalError(null);
    }

    async function submitReading(form) {
        const payload = {
            mileageKm:    form.mileageKm === "" ? null : Number(form.mileageKm),
            recordedDate: form.recordedDate || null,
            source:       form.source,
            note:         form.note && form.note.trim() ? form.note.trim() : null,
        };

        setSavingReading(true);
        setModalError(null);
        try {
            if (editingReading) {
                await api.put(`/vehicles/${id}/mileage/${editingReading.id}`, payload);
                addAlert("Čtení tachometru bylo upraveno", "success");
            } else {
                await api.post(`/vehicles/${id}/mileage`, payload);
                addAlert("Čtení tachometru bylo přidáno", "success");
            }
            closeReadingModal();
            await reloadVehicleAndReadings();
        } catch (err) {
            setModalError(problemMessage(err, "Čtení se nepodařilo uložit. Zkontrolujte zadané hodnoty."));
        } finally {
            setSavingReading(false);
        }
    }

    async function handleDeleteReading() {
        try {
            await api.delete(`/vehicles/${id}/mileage/${deleteReadingId}`);
            addAlert("Čtení tachometru bylo smazáno", "success");
            setDeleteReadingId(null);
            await reloadVehicleAndReadings();
        } catch (err) {
            addAlert(problemMessage(err, "Čtení se nepodařilo smazat."), "danger");
            setDeleteReadingId(null);
        }
    }

    if (!vehicle && !loadError) return <LoadingState />;
    if (!vehicle) {
        return <ErrorState message={loadError} backTo="/vehicles" backLabel="Zpět na vozidla" />;
    }

    const customer = vehicle.customer;

    return (
        <div>
            <PageHeader
                title={`${vehicle.brand} ${vehicle.model}`}
                subtitle={vehicle.vin ?? vehicle.machineSerialNumber}
                backTo="/vehicles"
                avatar={<EntityAvatar name={`${vehicle.brand} ${vehicle.model}`} />}
                badges={
                    <StatusBadge tone={getActiveTone(vehicle.active)}>
                        {getActiveLabel(vehicle.active)}
                    </StatusBadge>
                }
                actions={
                    <>
                        <button className="btn btn-outline-secondary"
                                onClick={() => navigate(`/vehicles/${id}/edit`)}>
                            <i className="bi bi-pencil me-1" aria-hidden="true"></i>Editovat
                        </button>
                        <button className={vehicle.active ? 'btn btn-outline-danger' : 'btn btn-outline-success'}
                                onClick={() => setShowConfirm(true)}>
                            <i className={`bi bi-${vehicle.active ? 'slash-circle' : 'check-circle'} me-1`}
                               aria-hidden="true"></i>
                            {vehicle.active ? 'Deaktivovat' : 'Aktivovat'}
                        </button>
                    </>
                }
            />

            <MetricRow>
                <MetricCard label="Rok výroby"      value={vehicle.yearOfManufacture ?? '—'} />
                <MetricCard label="Výkon"           value={vehicle.enginePowerKw}             unit="kW" />
                <MetricCard label="Objem motoru"    value={vehicle.engineDisplacementCcm}     unit="ccm" />
                <MetricCard label="Stav tachometru" value={formatNumber(vehicle.currentMileageKm)}
                            unit="km" />
            </MetricRow>

            <div className="row g-3">
                <div className="col-md-6">
                    <DetailCard title="Identifikace">
                        <dl className="row mb-0">
                            <dt className="col-sm-5 text-muted fw-normal">SPZ</dt>
                            <dd className="col-sm-7 fw-medium">{vehicle.licensePlate ?? '—'}</dd>

                            <dt className="col-sm-5 text-muted fw-normal">VIN</dt>
                            <dd className="col-sm-7">
                                {vehicle.vin ? <code className="small">{vehicle.vin}</code> : '—'}
                            </dd>

                            {vehicle.machineSerialNumber && (
                                <>
                                    <dt className="col-sm-5 text-muted fw-normal">Výrobní číslo</dt>
                                    <dd className="col-sm-7">
                                        <code className="small">{vehicle.machineSerialNumber}</code>
                                    </dd>
                                </>
                            )}

                            <dt className="col-sm-5 text-muted fw-normal">Rok výroby</dt>
                            <dd className="col-sm-7">{vehicle.yearOfManufacture ?? '—'}</dd>

                            <dt className="col-sm-5 text-muted fw-normal">První registrace</dt>
                            <dd className="col-sm-7">{formatDate(vehicle.firstRegistrationDate)}</dd>
                        </dl>
                    </DetailCard>

                    <DetailCard title="Technické parametry">
                        <dl className="row mb-0">
                            <dt className="col-sm-5 text-muted fw-normal">Palivo</dt>
                            <dd className="col-sm-7">
                                <StatusBadge tone="primary">
                                    {getFuelLabel(vehicle.fuelType)}
                                </StatusBadge>
                            </dd>

                            <dt className="col-sm-5 text-muted fw-normal">Převodovka</dt>
                            <dd className="col-sm-7">{getTransmissionLabel(vehicle.transmission)}</dd>

                            <dt className="col-sm-5 text-muted fw-normal">Objem motoru</dt>
                            <dd className="col-sm-7">
                                {vehicle.engineDisplacementCcm != null ? `${vehicle.engineDisplacementCcm} ccm` : '—'}
                            </dd>

                            <dt className="col-sm-5 text-muted fw-normal">Výkon</dt>
                            <dd className="col-sm-7">
                                {vehicle.enginePowerKw != null ? `${vehicle.enginePowerKw} kW` : '—'}
                            </dd>

                            <dt className="col-sm-5 text-muted fw-normal">Kód motoru</dt>
                            <dd className="col-sm-7 font-monospace">{vehicle.engineCode ?? '—'}</dd>

                            <dt className="col-sm-5 text-muted fw-normal">Barva</dt>
                            <dd className="col-sm-7">{vehicle.color ?? '—'}</dd>

                            <dt className="col-sm-5 text-muted fw-normal">Kola (pneu / ráfky)</dt>
                            <dd className="col-sm-7">
                                {formatWheels(vehicle.wheels).length > 0 ? (
                                    formatWheels(vehicle.wheels).map((axle) => (
                                        <div key={axle.label} className="small">
                                            <span className="text-muted">{axle.label}:</span>{" "}
                                            <span className="font-monospace">{axle.spec}</span>
                                        </div>
                                    ))
                                ) : '—'}
                            </dd>
                        </dl>
                    </DetailCard>
                </div>

                <div className="col-md-6">
                    <DetailCard title="Vlastník vozidla">
                        {customer ? (
                            <Link to={`/customers/${customer.id}/detail`} className="text-decoration-none">
                                <div className="d-flex align-items-center gap-3 p-2 rounded"
                                     style={{ background: 'var(--bs-secondary-bg, #f8f9fa)' }}>
                                    <div style={{
                                        width: 40, height: 40, borderRadius: '50%',
                                        background: '#343a40', display: 'flex',
                                        alignItems: 'center', justifyContent: 'center',
                                        color: 'white', fontSize: 13, fontWeight: 500, flexShrink: 0
                                    }}>
                                        {getInitials(customer.displayName)}
                                    </div>
                                    <div className="flex-grow-1">
                                        <div className="fw-medium text-body">{customer.displayName}</div>
                                        <div className="small text-muted">
                                            {customer.customerNumber}
                                            {customer.customerType === 'INDIVIDUAL' ? ' · Fyzická osoba' : ' · Firma'}
                                        </div>
                                    </div>
                                    <i className="bi bi-arrow-right text-muted"></i>
                                </div>
                            </Link>
                        ) : (
                            <p className="text-muted fst-italic mb-0">Zákazník není přiřazen</p>
                        )}
                    </DetailCard>

                    <DetailCard title="STK a registr vozidel" action={
                        // Registr hledá výhradně podle VIN — stroj bez VIN (V90) v něm není.
                        <button className="btn btn-sm btn-outline-primary"
                                onClick={handleRegistryRefresh}
                                disabled={refreshingRegistry || !vehicle.vin}
                                title={!vehicle.vin ? "Vozidlo nemá VIN — v registru vozidel není." : undefined}>
                            {refreshingRegistry
                                ? <><span className="spinner-border spinner-border-sm me-1"
                                          aria-hidden="true"></span>Načítám…</>
                                : <><i className="bi bi-arrow-clockwise me-1"></i>Aktualizovat z registru</>}
                        </button>
                    }>
                        <dl className="row mb-0">
                            <dt className="col-sm-5 text-muted fw-normal">STK platná do</dt>
                            <dd className="col-sm-7">
                                <StatusBadge tone={getStkBadge(vehicle.stkValidUntil).tone}>
                                    {getStkBadge(vehicle.stkValidUntil).label}
                                </StatusBadge>
                            </dd>

                            <dt className="col-sm-5 text-muted fw-normal">Stav v registru</dt>
                            <dd className="col-sm-7">{snapshots[0]?.registryStatus ?? '—'}</dd>

                            <dt className="col-sm-5 text-muted fw-normal">Evidenční prohlídka</dt>
                            <dd className="col-sm-7">{formatDate(snapshots[0]?.lastInspectionDate)}</dd>

                            <dt className="col-sm-5 text-muted fw-normal">Poslední načtení</dt>
                            <dd className="col-sm-7 small">{formatDate(snapshots[0]?.fetchedAt)}</dd>
                        </dl>
                    </DetailCard>

                    {vehicle.internalNote && (
                        <DetailCard title="Interní poznámka">
                            <p className="text-muted fst-italic mb-0 small">{vehicle.internalNote}</p>
                        </DetailCard>
                    )}

                    <DetailCard title="Metadata">
                        <dl className="row mb-0">
                            <dt className="col-sm-5 text-muted fw-normal">Zadáno</dt>
                            <dd className="col-sm-7 small">{formatDate(vehicle.createdAt)}</dd>

                            <dt className="col-sm-5 text-muted fw-normal">Aktualizováno</dt>
                            <dd className="col-sm-7 small">{formatDate(vehicle.updatedAt)}</dd>
                        </dl>
                    </DetailCard>
                </div>
            </div>

            <DetailCard title="Servisní historie" className="mt-3" action={
                ordersTotal > HISTORY_PAGE_SIZE && (
                    <Link className="btn btn-sm btn-outline-secondary" to={`/orders?vehicleId=${id}`}>
                        Zobrazit všech {ordersTotal}
                        <i className="bi bi-arrow-right ms-1" aria-hidden="true"></i>
                    </Link>
                )
            }>
                {ordersError ? (
                    <EmptyState icon="exclamation-triangle"
                                title="Servisní historii se nepodařilo načíst."
                                hint="Zkuste stránku otevřít znovu." />
                ) : (
                    <OrderHistoryTable
                        orders={orders}
                        emptyTitle="Na tomto vozidle jsme zatím nic nedělali."
                        emptyHint="Zakázku na vozidlo založíte v sekci Zakázky." />
                )}
            </DetailCard>

            <DetailCard title="Historie tachometru" action={
                <button className="btn btn-sm btn-outline-primary" onClick={openAddReading}>
                    <i className="bi bi-plus-lg me-1"></i>Přidat čtení
                </button>
            } className="mt-3">
                <MileageHistoryTable
                    readings={readings}
                    onEdit={openEditReading}
                    onDelete={(readingId) => setDeleteReadingId(readingId)}
                />
            </DetailCard>

            <ConfirmDialog
                title={vehicle.active ? 'Potvrďte deaktivaci' : 'Potvrďte aktivaci'}
                message={vehicle.active
                    ? `Opravdu chcete deaktivovat vozidlo ${vehicle.brand} ${vehicle.model} (${vehicle.licensePlate || vehicle.vin || vehicle.machineSerialNumber || 'bez identifikace'})?`
                    : `Opravdu chcete aktivovat vozidlo ${vehicle.brand} ${vehicle.model}?`}
                show={showConfirm}
                onConfirm={handleToggleStatus}
                onCancel={() => setShowConfirm(false)}
            />

            <MileageFormModal
                show={modalShow}
                reading={editingReading}
                allowInitial={(!editingReading && readings.length === 0) || (editingReading?.source === 'INITIAL')}
                error={modalError}
                saving={savingReading}
                onSubmit={submitReading}
                onCancel={closeReadingModal}
            />

            <ConfirmDialog
                title="Smazat čtení tachometru"
                message="Opravdu chcete smazat tento záznam o stavu tachometru? Aktuální stav vozidla se automaticky přepočítá."
                show={deleteReadingId != null}
                onConfirm={handleDeleteReading}
                onCancel={() => setDeleteReadingId(null)}
                yesLabel="Smazat"
                noLabel="Zrušit"
            />
        </div>
    );
}

