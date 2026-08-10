import * as React from 'react';
import { useEffect, useState } from "react";
import { Link, useNavigate, useParams } from "react-router-dom";
import { api, problemMessage } from "../api/api.js";
import ConfirmDialog from "../components/ConfirmDialog.jsx";
import { useAlert } from "../context/AlertContext.jsx";
import {
    formatDate,
    formatCurrency,
    getCountryName,
    getInvoiceStatusLabel,
    getInvoiceStatusTone,
} from "../api/format.js";
import StatusBadge from "../components/StatusBadge.jsx";
import PageHeader from "../components/PageHeader.jsx";
import LoadingState from "../components/LoadingState.jsx";
import ErrorState from "../components/ErrorState.jsx";
import DetailCard from "../components/DetailCard.jsx";

/**
 * Detail opravného daňového dokladu (dobropisu, §45 ZDPH).
 *
 * Doklad nemá vlastní položky ani vlastní strany — §45 rozdíly i identifikace stran se
 * odvozují z původní faktury (rozhodnutí R-7), takže se tu jen zobrazují. Editovat nejde nic:
 * dobropis vzniká z faktury a mění se pouze stavem DRAFT → ISSUED.
 *
 * Stav dokladu recykluje `InvoiceStatus`, proto i tady sedí `getInvoiceStatusLabel/Tone`.
 */
export default function CreditNotesPageDetail() {

    const { id } = useParams();
    const navigate = useNavigate();
    const { addAlert } = useAlert();
    const [creditNote, setCreditNote] = useState(null);
    const [error, setError] = useState("");
    const [busy, setBusy] = useState(false);
    const [confirmIssue, setConfirmIssue] = useState(false);
    /** Potvrzení smazání konceptu opravného dokladu (nevratné). */
    const [confirmDelete, setConfirmDelete] = useState(false);

    useEffect(() => {
        let cancelled = false;
        async function load() {
            try {
                const data = await api.get(`/credit-notes/${id}`);
                if (!cancelled) setCreditNote(data);
            } catch (err) {
                if (!cancelled) setError(problemMessage(err, "Opravný doklad se nepodařilo načíst."));
            }
        }
        load();
        return () => { cancelled = true; };
    }, [id]);

    async function issue() {
        setConfirmIssue(false);
        setBusy(true);
        try {
            setCreditNote(await api.post(`/credit-notes/${id}/issue`, {}));
            addAlert("Opravný doklad vystaven.", "success");
        } catch (err) {
            addAlert(problemMessage(err, "Opravný doklad se nepodařilo vystavit."), "danger");
        } finally {
            setBusy(false);
        }
    }

    /**
     * Smaže koncept opravného dokladu a vrátí se na fakturu — detail smazaného dokladu
     * by po návratu spadl na 404. Teprve tím se u faktury zase uvolní založení dobropisu.
     */
    async function deleteDraft() {
        setConfirmDelete(false);
        setBusy(true);
        try {
            await api.delete(`/credit-notes/${id}`);
            addAlert("Koncept opravného dokladu byl smazán.", "success");
            navigate(`/invoices/${creditNote.originalInvoiceId}/detail`);
        } catch (err) {
            addAlert(problemMessage(err, "Koncept se nepodařilo smazat."), "danger");
        } finally {
            setBusy(false);
        }
    }

    if (!creditNote && !error) return <LoadingState />;
    if (!creditNote) return <ErrorState message={error} backTo="/invoices" backLabel="Zpět na faktury" />;

    const isDraft = creditNote.status === 'DRAFT';

    return (
        <div>
            <PageHeader
                title={creditNote.creditNoteNumber
                    ? `Opravný daňový doklad ${creditNote.creditNoteNumber}`
                    : "Opravný daňový doklad (koncept)"}
                subtitle={`K faktuře ${creditNote.originalInvoiceNumber ?? '—'}`}
                backTo={`/invoices/${creditNote.originalInvoiceId}/detail`}
                backLabel="Zpět na fakturu"
                badges={
                    <StatusBadge tone={getInvoiceStatusTone(creditNote.status)}>
                        {getInvoiceStatusLabel(creditNote.status)}
                    </StatusBadge>
                }
                actions={
                    <>
                        <button className="btn btn-outline-secondary"
                                onClick={() => window.open(`/api/v1/credit-notes/${id}/pdf`, '_blank')}>
                            <i className="bi bi-file-earmark-pdf me-1" aria-hidden="true"></i>PDF
                        </button>
                        {/* zelená = nevratný posun dokladu (frontend.md §10.8) */}
                        {isDraft && (
                            <button className="btn btn-success" disabled={busy}
                                    onClick={() => setConfirmIssue(true)}>Vystavit</button>
                        )}
                        {/* Bez mazání byla omylem založená oprava slepá ulička: vystavit ji
                            obsluha nechce a nový dobropis k faktuře už založit nešlo. */}
                        {isDraft && (
                            <button className="btn btn-outline-danger" disabled={busy}
                                    onClick={() => setConfirmDelete(true)}>Smazat</button>
                        )}
                    </>
                }
            />

            {isDraft && (
                <div className="alert alert-warning" role="alert">
                    <i className="bi bi-exclamation-triangle me-2" aria-hidden="true"></i>
                    Koncept zatím <strong>nemá evidenční číslo</strong> a není platným daňovým
                    dokladem. Číslo řady „OD" se přidělí až vystavením — to už je nevratné.
                </div>
            )}

            {/* ── Strany (snapshoty původní faktury) ─────────────────── */}
            <div className="row g-3 mb-3">
                <div className="col-md-6">
                    <PartyCard title="Dodavatel" party={creditNote.supplier} />
                </div>
                <div className="col-md-6">
                    <PartyCard title="Odběratel" party={creditNote.customer} />
                </div>
            </div>

            {/* ── §45 náležitosti ────────────────────────────────────── */}
            <DetailCard title="Údaje opravného dokladu">
                <dl className="row mb-0">
                    <dt className="col-sm-3 text-muted fw-normal">Evidenční číslo</dt>
                    <dd className="col-sm-3 font-monospace">{creditNote.creditNoteNumber ?? '—'}</dd>
                    <dt className="col-sm-3 text-muted fw-normal">Opravovaný doklad</dt>
                    <dd className="col-sm-3">
                        <Link to={`/invoices/${creditNote.originalInvoiceId}/detail`}>
                            {creditNote.originalInvoiceNumber ?? `#${creditNote.originalInvoiceId}`}
                        </Link>
                    </dd>

                    <dt className="col-sm-3 text-muted fw-normal">Datum vystavení</dt>
                    <dd className="col-sm-3">{formatDate(creditNote.issueDate)}</dd>
                    <dt className="col-sm-3 text-muted fw-normal">DUZP</dt>
                    <dd className="col-sm-3">{formatDate(creditNote.taxableSupplyDate)}</dd>

                    <dt className="col-sm-3 text-muted fw-normal">Důvod opravy</dt>
                    <dd className="col-sm-9">{creditNote.correctionReason}</dd>
                </dl>
            </DetailCard>

            {/* ── §45 rozdíly ────────────────────────────────────────── */}
            <DetailCard title="Rozdíly oproti původnímu dokladu">
                {creditNote.vatDifferences?.length > 0 && (
                    <div className="table-responsive mb-3">
                        <table className="table table-sm mb-0">
                            <thead>
                                <tr>
                                    <th scope="col" scope="col">Sazba</th>
                                    <th scope="col" scope="col" className="text-end">Rozdíl základu</th>
                                    <th scope="col" scope="col" className="text-end">Rozdíl daně</th>
                                </tr>
                            </thead>
                            <tbody>
                                {creditNote.vatDifferences.map((line) => (
                                    <tr key={line.vatRate}>
                                        <td>{line.vatRate} %</td>
                                        <td className="text-end">{formatCurrency(line.base)}</td>
                                        <td className="text-end">{formatCurrency(line.vat)}</td>
                                    </tr>
                                ))}
                            </tbody>
                        </table>
                    </div>
                )}
                <dl className="row mb-0 text-end justify-content-end">
                    <dt className="col-sm-3 text-muted fw-normal">Rozdíl základu daně</dt>
                    <dd className="col-sm-2">{formatCurrency(creditNote.totalNetDifference)}</dd>
                </dl>
                <dl className="row mb-0 text-end justify-content-end">
                    <dt className="col-sm-3 text-muted fw-normal">Rozdíl daně</dt>
                    <dd className="col-sm-2">{formatCurrency(creditNote.totalVatDifference)}</dd>
                </dl>
                <dl className="row mb-0 text-end justify-content-end border-top pt-2">
                    <dt className="col-sm-3 fw-medium">Rozdíl celkem</dt>
                    <dd className="col-sm-2 fw-bold fs-5 mb-0">
                        {formatCurrency(creditNote.totalGrossDifference)}
                    </dd>
                </dl>
            </DetailCard>

            <ConfirmDialog
                title="Vystavit opravný doklad?"
                message={"Dokladu se přidělí evidenční číslo řady „OD“ a jeho obsah už nepůjde změnit."}
                show={confirmIssue}
                onConfirm={issue}
                onCancel={() => setConfirmIssue(false)}
            />

            <ConfirmDialog
                title="Smazat koncept opravného dokladu?"
                message="Koncept se smaže a nelze ho vrátit. K faktuře pak půjde založit nový opravný doklad."
                show={confirmDelete}
                onConfirm={deleteDraft}
                onCancel={() => setConfirmDelete(false)}
            />
        </div>
    );
}

/* ── Sub-komponenta: karta strany (snapshot z původní faktury) ─────── */
function PartyCard({ title, party }) {
    return (
        <DetailCard title={title} fullHeight>
            {party ? (
                <address className="mb-0 small">
                    <div className="fw-medium mb-1">{party.name}</div>
                    {(party.street || party.city) && (
                        <div className="text-muted">
                            {party.street} {party.streetNumber}<br />
                            {party.postalCode} {party.city}<br />
                            {getCountryName(party.countryCode)}
                        </div>
                    )}
                    {(party.ico || party.dic) && (
                        <div className="mt-2 text-muted">
                            {party.ico && <div>IČO: {party.ico}</div>}
                            {party.dic && <div>DIČ: {party.dic}</div>}
                        </div>
                    )}
                </address>
            ) : (
                <p className="text-muted fst-italic mb-0">Neuvedeno</p>
            )}
        </DetailCard>
    );
}
