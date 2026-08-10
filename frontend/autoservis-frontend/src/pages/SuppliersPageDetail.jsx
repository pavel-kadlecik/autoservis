import * as React from 'react';
import { useEffect, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import { api, problemMessage } from "../api/api.js";
import { formatDate, getActiveLabel, getActiveTone, getCountryName } from "../api/format.js";
import PageHeader from "../components/PageHeader.jsx";
import EntityAvatar from "../components/EntityAvatar.jsx";
import ConfirmDialog from "../components/ConfirmDialog.jsx";
import StatusBadge from "../components/StatusBadge.jsx";
import { useAlert } from "../context/AlertContext.jsx";
import LoadingState from "../components/LoadingState.jsx";
import DetailCard from "../components/DetailCard.jsx";

export default function SuppliersPageDetail() {

    const { id } = useParams();
    const navigate = useNavigate();
    const { addAlert } = useAlert();

    const [supplier, setSupplier] = useState(null);
    const [showConfirm, setShowConfirm] = useState(false);

    useEffect(() => {
        async function loadSupplier() {
            try {
                const data = await api.get(`/warehouse/suppliers/${id}`);
                setSupplier(data);
            } catch (error) {
                addAlert(problemMessage(error, "Dodavatele se nepodařilo načíst."), "danger");
                navigate("/suppliers");
            }
        }
        loadSupplier();
    }, [id]);

    async function handleToggleStatus() {
        try {
            const updated = supplier.active
                ? await api.delete(`/warehouse/suppliers/${id}`)
                : await api.post(`/warehouse/suppliers/${id}/activate`);
            setSupplier(updated);
        } catch (err) {
            const message = problemMessage(err, "Akci se nepodařilo provést.");
            addAlert(message, "danger");
        } finally {
            setShowConfirm(false);
        }
    }

    if (!supplier) return <LoadingState />;

    return (
        <div>

            <PageHeader
                title={supplier.name}
                subtitle={supplier.registrationNumber ? `IČO ${supplier.registrationNumber}` : null}
                backTo="/suppliers"
                avatar={<EntityAvatar name={supplier.name} />}
                badges={
                    <StatusBadge tone={getActiveTone(supplier.active)}>
                        {getActiveLabel(supplier.active)}
                    </StatusBadge>
                }
                actions={
                    <>
                        <button className="btn btn-outline-secondary"
                                onClick={() => navigate(`/suppliers/${id}/edit`)}>
                            <i className="bi bi-pencil me-1" aria-hidden="true"></i>Editovat
                        </button>
                        <button className={supplier.active ? 'btn btn-outline-danger' : 'btn btn-outline-success'}
                                onClick={() => setShowConfirm(true)}>
                            <i className={`bi bi-${supplier.active ? 'slash-circle' : 'check-circle'} me-1`}
                               aria-hidden="true"></i>
                            {supplier.active ? 'Deaktivovat' : 'Aktivovat'}
                        </button>
                    </>
                }
            />

            {/* ── Hlavní obsah ────────────────────────────────────────── */}
            <div className="row g-3">

                {/* Levý sloupec */}
                <div className="col-md-6">

                    {/* Identifikace */}
                    <DetailCard title="Identifikace">
                        <dl className="row mb-0">
                            <dt className="col-sm-5 text-muted fw-normal">IČO</dt>
                            <dd className="col-sm-7">{supplier.registrationNumber ?? '—'}</dd>

                            <dt className="col-sm-5 text-muted fw-normal">DIČ</dt>
                            <dd className="col-sm-7">{supplier.vatId ?? '—'}</dd>
                        </dl>
                    </DetailCard>

                    {/* Kontakt */}
                    <DetailCard title="Kontakt">
                        <dl className="row mb-0">
                            <dt className="col-sm-5 text-muted fw-normal">Email</dt>
                            <dd className="col-sm-7">
                                {supplier.email
                                    ? <a href={`mailto:${supplier.email}`}>{supplier.email}</a>
                                    : '—'}
                            </dd>

                            <dt className="col-sm-5 text-muted fw-normal">Telefon</dt>
                            <dd className="col-sm-7">{supplier.phone ?? '—'}</dd>
                        </dl>
                    </DetailCard>

                    {/* Adresa */}
                    <DetailCard title="Adresa">
                        {supplier.street || supplier.city ? (
                            <address className="mb-0 text-muted small">
                                {supplier.street && <>{supplier.street}<br /></>}
                                {supplier.postalCode} {supplier.city}<br />
                                {getCountryName(supplier.countryCode)}
                            </address>
                        ) : (
                            <p className="text-muted fst-italic mb-0">Žádná adresa</p>
                        )}
                    </DetailCard>
                </div>

                {/* Pravý sloupec */}
                <div className="col-md-6">

                    {/* Bankovní spojení */}
                    <DetailCard title="Bankovní spojení">
                        <dl className="row mb-0">
                            <dt className="col-sm-5 text-muted fw-normal">Číslo účtu</dt>
                            <dd className="col-sm-7">{supplier.bankAccount ?? '—'}</dd>

                            <dt className="col-sm-5 text-muted fw-normal">IBAN</dt>
                            <dd className="col-sm-7">{supplier.iban ?? '—'}</dd>

                            <dt className="col-sm-5 text-muted fw-normal">SWIFT / BIC</dt>
                            <dd className="col-sm-7">{supplier.swift ?? '—'}</dd>
                        </dl>
                    </DetailCard>

                    {/* Metadata */}
                    <DetailCard title="Metadata">
                        <dl className="row mb-0">
                            <dt className="col-sm-5 text-muted fw-normal">Zadáno</dt>
                            <dd className="col-sm-7 small">{formatDate(supplier.createdAt)}</dd>

                            <dt className="col-sm-5 text-muted fw-normal">Aktualizováno</dt>
                            <dd className="col-sm-7 small">{formatDate(supplier.updatedAt)}</dd>
                        </dl>
                    </DetailCard>
                </div>
            </div>

            {/* ── Potvrzovací dialog ────────────────────────────────── */}
            <ConfirmDialog
                title={supplier.active ? 'Potvrďte deaktivaci' : 'Potvrďte aktivaci'}
                message={supplier.active
                    ? `Opravdu chcete deaktivovat dodavatele ${supplier.name}?`
                    : `Opravdu chcete aktivovat dodavatele ${supplier.name}?`}
                show={showConfirm}
                onConfirm={handleToggleStatus}
                onCancel={() => setShowConfirm(false)}
            />
        </div>
    );
}

