import * as React from 'react';
import { useEffect, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import { api, problemMessage } from "../api/api.js";
import ConfirmDialog from "../components/ConfirmDialog.jsx";
import FormModal from "../components/FormModal.jsx";
import { useAlert } from "../context/AlertContext.jsx";
import {
    formatDate,
    formatCurrency,
    formatNumber,
    getCountryName,
    getPaymentMethodLabel,
    getInvoiceStatusLabel,
    getInvoiceStates,
    getInvoiceStatusTone,
    getCashReceiptStatusLabel,
    getCashReceiptStatusTone,
} from "../api/format.js";
import StatusBadge from "../components/StatusBadge.jsx";
import PageHeader from "../components/PageHeader.jsx";
import LoadingState from "../components/LoadingState.jsx";
import DetailCard from "../components/DetailCard.jsx";
import DataTable from "../components/DataTable.jsx";
import EmptyState from "../components/EmptyState.jsx";

import PictureAsPdfIcon from '@mui/icons-material/PictureAsPdf';
import BlockIcon from '@mui/icons-material/Block';
import DeleteIcon from '@mui/icons-material/Delete';
import ErrorState from "../components/ErrorState.jsx";
import InvoiceIssueModal from "../components/InvoiceIssueModal.jsx";
import InvoiceSendEmailModal from "../components/InvoiceSendEmailModal.jsx";
import CashReceiptIssueModal from "../components/CashReceiptIssueModal.jsx";

export default function InvoicesPageDetail() {

    const { id } = useParams();
    const navigate = useNavigate();
    const { addAlert } = useAlert();
    const [invoice, setInvoice] = useState(null);
    // Bez ošetření chyby zůstal detail navždy na spinneru „Načítám…" (KN-14) —
    // třeba u zastaralého odkazu na smazaný záznam (404).
    const [loadError, setLoadError] = useState("");
    const [busy, setBusy] = useState(false);
    const [confirmState, setConfirmState] = useState({ show: false, action: null, message: "" });
    /** Aktivní opravný doklad k této faktuře (nejvýš jeden, V66) — null = žádný. */
    const [creditNote, setCreditNote] = useState(null);
    const [showCreditNoteForm, setShowCreditNoteForm] = useState(false);
    /** Pokladní doklady k faktuře vč. stornovaných — nestornovaný smí být nejvýš jeden (V68). */
    const [cashReceipts, setCashReceipts] = useState([]);
    /** Doklad, pro který je otevřený dialog storna (potřebuje důvod), nebo null. */
    const [receiptToCancel, setReceiptToCancel] = useState(null);
    /** Dialog vystavení PPD — tam se zadává číslo dokladu a datum (V92). */
    const [showCashReceiptForm, setShowCashReceiptForm] = useState(false);
    /** Doklad, pro který je otevřené potvrzení smazání, nebo null. */
    const [receiptToDelete, setReceiptToDelete] = useState(null);
    /** Dialog vystavení — tam se zadává číslo faktury a variabilní symbol. */
    const [showIssueForm, setShowIssueForm] = useState(false);
    /** Potvrzení smazání konceptu (nevratné, proto samostatný dialog). */
    const [confirmDelete, setConfirmDelete] = useState(false);
    /** Dialog e-mailu s fakturou: { mode: 'hand-over' | 'send' }, nebo null (2026-08-08). */
    const [emailDialog, setEmailDialog] = useState(null);

    useEffect(() => {
        async function loadInvoice() {
            try {
                const data = await api.get(`/invoices/${id}`);
                setInvoice(data);
                setLoadError("");
            } catch (err) {
                setLoadError(problemMessage(err, "Fakturu se nepodařilo načíst."));
            }
        }
        loadInvoice();
    }, [id]);

    // Aby detail poznal, že dobropis už existuje, a nabídl ho otevřít místo zakládání druhého.
    // Selhání se schválně nehlásí toastem — je to doplňková informace, ne obsah stránky.
    useEffect(() => {
        let cancelled = false;
        api.get(`/credit-notes?invoiceId=${id}`)
            .then(list => {
                if (cancelled) return;
                setCreditNote(list.find(cn => cn.status !== 'CANCELLED') ?? null);
            })
            .catch(() => { if (!cancelled) setCreditNote(null); });
        return () => { cancelled = true; };
    }, [id]);

    // Obsluha musí vidět, že doklad už vystavila — jinak ho vystaví znovu a diví se chybě.
    // Stejně jako u dobropisu je to doplňková informace: selhání se toastem nehlásí.
    async function loadCashReceipts() {
        try {
            setCashReceipts(await api.get(`/cash-receipts?invoiceId=${id}`));
        } catch {
            setCashReceipts([]);
        }
    }

    useEffect(() => {
        let cancelled = false;
        api.get(`/cash-receipts?invoiceId=${id}`)
            .then(list => { if (!cancelled) setCashReceipts(list); })
            .catch(() => { if (!cancelled) setCashReceipts([]); });
        return () => { cancelled = true; };
    }, [id]);

    async function transition(action) {
        setBusy(true);
        try {
            const updated = await api.post(`/invoices/${id}/${action}`, {});
            setInvoice(updated);
        } catch (err) {
            const message = problemMessage(err, "Stav faktury se nepodařilo změnit.");
            addAlert(message, "danger");
        } finally {
            setBusy(false);
        }
    }

    /**
     * Vystaví koncept — doklad tu dostává číslo, datum vystavení a variabilní symbol z dialogu.
     *
     * Chyba se schválně nechává probublat do dialogu (ne toastem): typicky jde o číslo,
     * které mezitím obsadil někdo jiný, a obsluha ho musí opravit v tom samém formuláři.
     */
    async function issueInvoice({ invoiceNumber, issueDate, variableSymbol }) {
        const updated = await api.post(`/invoices/${id}/issue`, { invoiceNumber, issueDate, variableSymbol });
        setInvoice(updated);
        setShowIssueForm(false);
        addAlert(`Faktura ${updated.invoiceNumber} byla vystavena.`, "success");
    }

    /**
     * Smaže koncept faktury a vrátí se do seznamu — detail smazaného dokladu by po
     * návratu spadl na 404. Zakázka se tím zároveň odemkne k úpravám i k nové fakturaci.
     */
    async function deleteInvoice() {
        setConfirmDelete(false);
        setBusy(true);
        try {
            await api.delete(`/invoices/${id}`);
            addAlert("Koncept faktury byl smazán.", "success");
            navigate('/invoices');
        } catch (err) {
            addAlert(problemMessage(err, "Koncept se nepodařilo smazat."), "danger");
        } finally {
            setBusy(false);
        }
    }

    /**
     * Založí koncept opravného dokladu a rovnou ho otevře.
     *
     * Důvod opravy se **vyžádá dialogem** — §45 ZDPH ho předepisuje jako náležitost dokladu
     * a tiskne se na PDF, takže ho nelze dosadit natvrdo. Doklad se jen zakládá, nevystavuje:
     * číslo řady „OD“ se přiděluje až vystavením a to je nevratné.
     */
    async function createCreditNote({ correctionReason }) {
        setShowCreditNoteForm(false);
        setBusy(true);
        try {
            const created = await api.post('/credit-notes', {
                originalInvoiceId: Number(id),
                correctionReason,
            });
            navigate(`/credit-notes/${created.id}/detail`);
        } catch (err) {
            addAlert(problemMessage(err, "Opravný doklad se nepodařilo založit."), "danger");
        } finally {
            setBusy(false);
        }
    }

    /**
     * Vystaví pokladní doklad — číslo a datum dodává dialog (V92, jako u faktur).
     * Chyba se nechává probublat do dialogu: typicky duplicitní číslo, které
     * obsluha opraví v tom samém formuláři.
     */
    async function createCashReceipt({ receiptNumber, issueDate }) {
        const receipt = await api.post('/cash-receipts', {
            invoiceId: Number(id), receiptNumber, issueDate,
        });
        setShowCashReceiptForm(false);
        await loadCashReceipts();
        window.open(`/api/v1/cash-receipts/${receipt.id}/pdf`, '_blank');
    }

    /**
     * Smaže pokladní doklad (rozhodnutí uživatele 2026-08-09): řadu si obsluha řídí sama —
     * číslo se uvolní a případnou díru zavře ruční zápis při dalším vystavení. Mazat jde
     * i stornovaný doklad; kdo chce záznam zachovat, použije storno.
     */
    async function deleteCashReceipt() {
        const receipt = receiptToDelete;
        setReceiptToDelete(null);
        setBusy(true);
        try {
            await api.delete(`/cash-receipts/${receipt.id}`);
            await loadCashReceipts();
            addAlert(`Pokladní doklad ${receipt.receiptNumber} byl smazán — číslo se uvolnilo.`, "success");
        } catch (err) {
            addAlert(problemMessage(err, "Pokladní doklad se nepodařilo smazat."), "danger");
        } finally {
            setBusy(false);
        }
    }

    /**
     * Stornuje pokladní doklad vystavený omylem.
     *
     * Důvod se **vyžádá dialogem** — doklad zůstává v číselné řadě (účetní záznam se nemaže)
     * a tiskne se na něj i po stornu, takže „proč" musí být doložené. Teprve po stornu jde
     * k faktuře vystavit nový doklad.
     */
    async function cancelCashReceipt({ reason }) {
        const receipt = receiptToCancel;
        setReceiptToCancel(null);
        setBusy(true);
        try {
            await api.post(`/cash-receipts/${receipt.id}/cancel`, { reason });
            await loadCashReceipts();
        } catch (err) {
            addAlert(problemMessage(err, "Pokladní doklad se nepodařilo stornovat."), "danger");
        } finally {
            setBusy(false);
        }
    }

    /**
     * Potvrdí, resp. vezme zpět předání dokladu zákazníkovi (V88).
     *
     * Vystavení předání neznamená — aplikace fakturu neposílá a o odeslání neví nic. Dokud
     * není označená jako předaná, jde omylem vystavenou fakturu ještě smazat; potom už jen
     * dobropisem. „Předáno" jde kliknout omylem taky, proto to lze vzít zpět.
     */
    /**
     * Vrátí vystavenou fakturu do konceptu (2026-08-08) — typicky kvůli špatně zadanému číslu.
     *
     * Šetrnější než smazat a založit znovu: uvolní se číslo a variabilní symbol, ale položky,
     * strany i data zůstanou. Doklad, který nikam neodešel, se opravuje editací, ne dobropisem.
     */
    async function revokeIssue() {
        setBusy(true);
        try {
            setInvoice(await api.delete(`/invoices/${id}/issue`));
            addAlert("Faktura je zpět v konceptu — číslo se uvolnilo.", "success");
        } catch (err) {
            addAlert(problemMessage(err, "Fakturu se nepodařilo vrátit do konceptu."), "danger");
        } finally {
            setBusy(false);
        }
    }

    /**
     * Vezme zpět evidenci úhrady (2026-08-08) — omylem kliknuté „Označit zaplaceno" bylo
     * do téhle změny nevratné a jediná cesta ven vedla přes dobropis, který by ale zapsal
     * „držím peníze zákazníka a dlužím vratku".
     */
    async function revokePayment() {
        setBusy(true);
        try {
            setInvoice(await api.delete(`/invoices/${id}/pay`));
            addAlert("Platba vzata zpět — faktura je zase nezaplacená.", "success");
        } catch (err) {
            addAlert(problemMessage(err, "Platbu se nepodařilo vzít zpět."), "danger");
        } finally {
            setBusy(false);
        }
    }

    /**
     * Vezme předání zpět. Označení předání tudy už nevede — akce „Předáno zákazníkovi"
     * otevírá dialog s nabídkou poslat fakturu e-mailem (2026-08-08).
     */
    async function revokeHandOver() {
        setBusy(true);
        try {
            setInvoice(await api.delete(`/invoices/${id}/hand-over`));
            addAlert("Předání vzato zpět — fakturu lze zase smazat.", "success");
        } catch (err) {
            addAlert(problemMessage(err, "Předání se nepodařilo vzít zpět."), "danger");
        } finally {
            setBusy(false);
        }
    }

    function requestTransition(action, confirmMessage) {
        if (confirmMessage) {
            setConfirmState({ show: true, action, message: confirmMessage });
            return;
        }
        transition(action);
    }

    function confirmTransition() {
        const { action } = confirmState;
        setConfirmState({ show: false, action: null, message: "" });
        transition(action);
    }

    if (!invoice && !loadError) return <LoadingState />;
    if (!invoice) {
        return <ErrorState message={loadError} backTo="/invoices" backLabel="Zpět na faktury" />;
    }

    const activeCashReceipt = cashReceipts.find(r => r.status !== 'CANCELLED') ?? null;

    const itemColumns = [
        { key: "position", header: "#", sortable: true, className: "text-muted",
          sortValue: i => Number(i.position), render: i => i.position },
        { key: "name", header: "Název", sortable: true, sortValue: i => i.name, render: i => i.name },
        { key: "quantity", header: "Množství", sortable: true, align: "end",
          sortValue: i => Number(i.quantity), render: i => formatNumber(i.quantity) },
        { key: "unit", header: "MJ", render: i => i.unit },
        { key: "unitPrice", header: "Cena/MJ bez DPH", sortable: true, align: "end",
          sortValue: i => Number(i.unitPrice), render: i => formatCurrency(i.unitPrice) },
        { key: "vatRate", header: "DPH", align: "end", render: i => `${i.vatRate} %` },
    ];

    const cashReceiptColumns = [
        { key: "receiptNumber", header: "Číslo dokladu",
          render: r => <span className="font-monospace">{r.receiptNumber}</span> },
        { key: "issueDate", header: "Ze dne", className: "text-muted small",
          render: r => formatDate(r.issueDate) },
        { key: "amount", header: "Přijato", align: "end", className: "fw-medium",
          render: r => formatCurrency(r.amount) },
        {
            key: "status", header: "Stav",
            render: r => (
                <>
                    <StatusBadge tone={getCashReceiptStatusTone(r.status)}>
                        {getCashReceiptStatusLabel(r.status)}
                    </StatusBadge>
                    {r.cancellationReason && (
                        <div className="text-muted small mt-1">{r.cancellationReason}</div>
                    )}
                </>
            ),
        },
    ];

    function cashReceiptActions(receipt) {
        if (busy) {
            return [{ id: "pdf", label: "PDF", icon: <PictureAsPdfIcon fontSize="small"/> }];
        }
        return [
            { id: "pdf", label: "PDF", icon: <PictureAsPdfIcon fontSize="small"/> },
            // Stornovat lze jen platný doklad; stornovaný zůstává jen k nahlédnutí.
            ...(receipt.status !== 'CANCELLED'
                ? [{ id: "cancel", label: "Stornovat",
                     icon: <BlockIcon fontSize="small"/>, color: "error.main" }]
                : []),
            // Smazat jde každý doklad (V92, rozhodnutí uživatele) — číslo se uvolní.
            { id: "delete", label: "Smazat",
              icon: <DeleteIcon fontSize="small"/>, color: "error.main" },
        ];
    }

    function handleCashReceiptAction(action, receipt) {
        if (action === 'pdf') {
            window.open(`/api/v1/cash-receipts/${receipt.id}/pdf`, '_blank');
        } else if (action === 'cancel') {
            setReceiptToCancel(receipt);
        } else if (action === 'delete') {
            setReceiptToDelete(receipt);
        }
    }

    return (
        <div>
            <PageHeader
                // Koncept číslo nemá (dostane ho až vystavením), takže bez fallbacku
                // by hlavička stránky zůstala prázdná.
                title={invoice.invoiceNumber ?? "Koncept faktury"}
                /* Zakázka jako odkaz — z faktury se člověk potřebuje vrátit k práci,
                   ze které vznikla. Zakázku s fakturou nelze smazat, takže cíl vždy existuje. */
                subtitle={invoice.orderId
                    ? <>Zakázka <a href={`/orders/${invoice.orderId}/detail`}>
                        {invoice.orderNumberSnapshot ?? '—'}</a></>
                    : `Zakázka ${invoice.orderNumberSnapshot ?? '—'}`}
                backTo="/invoices"
                badges={
                    /* Osa Koncept → Vystavena → Předána → Zaplacena, terminál Dobropisována.
                       Odznaky skládá getInvoiceStates — týž zdroj jako oba seznamy. */
                    <div className="d-flex flex-wrap gap-1">
                        {getInvoiceStates(invoice).map(state => (
                            <StatusBadge key={state.label} tone={state.tone}>{state.label}</StatusBadge>
                        ))}
                    </div>
                }
                actions={
                    <>
                        <button className="btn btn-outline-secondary"
                                onClick={() => window.open(`/api/v1/invoices/${id}/pdf`, '_blank')}>
                            <i className="bi bi-file-earmark-pdf me-1" aria-hidden="true"></i>PDF
                        </button>
                        {/* PPD dává smysl jen k existujícímu dokladu (vystavená/zaplacená faktura).
                            Existuje-li už platný, tlačítko ho jen otevře — druhý vystavit nelze (V68). */}
                        {(invoice.status === 'ISSUED' || invoice.status === 'PAID') && (
                            activeCashReceipt ? (
                                <button className="btn btn-outline-secondary"
                                        onClick={() => window.open(
                                            `/api/v1/cash-receipts/${activeCashReceipt.id}/pdf`, '_blank')}>
                                    <i className="bi bi-cash-coin me-1" aria-hidden="true"></i>Pokladní doklad
                                </button>
                            ) : (
                                <button className="btn btn-outline-secondary" disabled={busy}
                                        onClick={() => setShowCashReceiptForm(true)}>
                                    <i className="bi bi-cash-coin me-1" aria-hidden="true"></i>
                                    Vystavit pokladní doklad
                                </button>
                            )
                        )}
                        {/* Oprava vystaveného dokladu patří dobropisu (§42/§45 ZDPH), ne stornu.
                            Existuje-li už, vede tlačítko na něj — druhý vystavit nelze (V66). */}
                        {/* Dobropis opravuje doklad, který zákazník DOSTAL. U nepředané faktury
                            není co opravovat — smaže se a vystaví znovu (2026-08-08). Backend to
                            vynucuje taky (`INVOICE_NOT_HANDED_OVER`), tohle jen neukazuje
                            slepou uličku. */}
                        {(invoice.status === 'PAID'
                          || (invoice.status === 'ISSUED' && invoice.handedOverAt)) && (
                            creditNote ? (
                                <button className="btn btn-outline-secondary"
                                        onClick={() => navigate(`/credit-notes/${creditNote.id}/detail`)}>
                                    <i className="bi bi-arrow-counterclockwise me-1" aria-hidden="true"></i>
                                    Opravný doklad
                                </button>
                            ) : (
                                <button className="btn btn-outline-secondary" disabled={busy}
                                        onClick={() => setShowCreditNoteForm(true)}>
                                    <i className="bi bi-arrow-counterclockwise me-1" aria-hidden="true"></i>
                                    Vystavit opravný doklad
                                </button>
                            )
                        )}
                        {/* zelená = nevratný posun dokladu (frontend.md §10.8). Vystavení
                            nejde přes potvrzovací dialog jako ostatní přechody — doklad tu
                            dostává číslo a VS, takže potřebuje formulář. */}
                        {invoice.status === 'DRAFT' && (
                            <button className="btn btn-success" disabled={busy}
                                    onClick={() => setShowIssueForm(true)}>Vystavit</button>
                        )}
                        {/* Doklad, který nikam neodešel, se opravuje editací — typicky když
                            se obsluha splete v čísle. Šetrnější než smazat a skládat znovu. */}
                        {invoice.status === 'ISSUED' && !invoice.handedOverAt && (
                            <button className="btn btn-outline-secondary" disabled={busy}
                                    onClick={revokeIssue}
                                    title="Uvolní číslo a variabilní symbol; položky a strany zůstanou">
                                Vrátit do konceptu
                            </button>
                        )}
                        {/* Úhrada je interní záznam, ne daňový doklad — překlep v ní jde
                            opravit. Neprojde, visí-li na faktuře platný pokladní doklad;
                            ten se stornuje zvlášť. */}
                        {invoice.status === 'PAID' && (
                            <button className="btn btn-outline-secondary" disabled={busy}
                                    onClick={revokePayment}
                                    title="Označil jsem platbu omylem">
                                Vzít platbu zpět
                            </button>
                        )}
                        {/* Vyrušený doklad se neplatí (Z1) — dobropis fakturu ekonomicky
                            vynuloval. Backend to odmítá kódem INVOICE_CREDITED. */}
                        {invoice.status === 'ISSUED' && !invoice.creditedAt && (
                            <button className="btn btn-success" disabled={busy}
                                    onClick={() => requestTransition('pay')}>Označit zaplaceno</button>
                        )}
                        {/* Předání zákazníkovi (V88) — teprve jím se doklad stává tím, co
                            se opravuje dobropisem. Otevírá dialog s nabídkou poslat fakturu
                            rovnou e-mailem (2026-08-08) — předat bez e-mailu jde v něm taky. */}
                        {invoice.status === 'ISSUED' && !invoice.handedOverAt && (
                            <button className="btn btn-outline-primary" disabled={busy}
                                    onClick={() => setEmailDialog({ mode: 'hand-over' })}>
                                <i className="bi bi-send-check me-1" aria-hidden="true"></i>
                                Předáno zákazníkovi
                            </button>
                        )}
                        {/* Opakované odeslání předaného dokladu — zákazník fakturu ztratil
                            nebo první odeslání šlo na špatnou adresu. Předání už nemění. */}
                        {(invoice.status === 'ISSUED' || invoice.status === 'PAID')
                          && invoice.handedOverAt && (
                            <button className="btn btn-outline-secondary" disabled={busy}
                                    onClick={() => setEmailDialog({ mode: 'send' })}>
                                <i className="bi bi-envelope me-1" aria-hidden="true"></i>
                                Poslat e-mailem
                            </button>
                        )}
                        {/* U dobropisované faktury už předání zpět vzít nelze (Z2) —
                            vznikla by kombinace „nepředaná + dobropisovaná". */}
                        {invoice.status === 'ISSUED' && invoice.handedOverAt && !invoice.creditedAt && (
                            <button className="btn btn-outline-secondary" disabled={busy}
                                    onClick={revokeHandOver}
                                    title="Označil jsem předání omylem">
                                Vzít předání zpět
                            </button>
                        )}
                        {/* Smazat lze koncept a vystavenou fakturu, kterou zákazník ještě
                            nedostal. Předaný doklad se opravuje dobropisem (§42/§45 ZDPH,
                            audit KN-1). Backend to vynucuje taky (`INVOICE_NOT_DELETABLE`),
                            tohle jen neukazuje slepou uličku. */}
                        {(invoice.status === 'DRAFT'
                          || (invoice.status === 'ISSUED' && !invoice.handedOverAt)) && (
                            <button className="btn btn-outline-danger" disabled={busy}
                                    onClick={() => setConfirmDelete(true)}>
                                Smazat
                            </button>
                        )}
                    </>
                }
            />

            {/* Dobropisovaná faktura zůstává platným dokladem, ale zakázku už neblokuje (V69).
                Bez téhle věty by obsluha nechápala, proč se u zakázky zase nabízí fakturace. */}
            {invoice.creditedAt && (
                <div className="alert alert-warning d-flex align-items-center" role="status">
                    <i className="bi bi-arrow-counterclockwise me-2" aria-hidden="true"></i>
                    <div>
                        K této faktuře byl <strong>{formatDate(invoice.creditedAt)}</strong> vystaven
                        opravný daňový doklad. Doklad zůstává v evidenci, ale zakázku už neblokuje —
                        můžete k ní vystavit fakturu novou.
                    </div>
                </div>
            )}

            {/* ── Strany faktury ─────────────────────────────────────── */}
            <div className="row g-3 mb-3">
                <div className="col-md-6">
                    <PartyCard title="Dodavatel" party={invoice.supplier} />
                </div>
                <div className="col-md-6">
                    <PartyCard title="Odběratel" party={invoice.customer} />
                </div>
            </div>

            {/* ── Údaje faktury ──────────────────────────────────────── */}
            <DetailCard title="Údaje faktury">
                <dl className="row mb-0">
                    <dt className="col-sm-3 text-muted fw-normal">Vystaveno</dt>
                    <dd className="col-sm-3">{formatDate(invoice.issueDate)}</dd>
                    <dt className="col-sm-3 text-muted fw-normal">Splatnost</dt>
                    <dd className="col-sm-3">{formatDate(invoice.dueDate)}</dd>

                    <dt className="col-sm-3 text-muted fw-normal">Datum zdanit. plnění</dt>
                    <dd className="col-sm-3">{formatDate(invoice.taxableSupplyDate)}</dd>
                    <dt className="col-sm-3 text-muted fw-normal">Způsob platby</dt>
                    <dd className="col-sm-3">{getPaymentMethodLabel(invoice.paymentMethod)}</dd>

                    <dt className="col-sm-3 text-muted fw-normal">Variabilní symbol</dt>
                    <dd className="col-sm-3 font-monospace">{invoice.variableSymbol ?? '—'}</dd>
                    <dt className="col-sm-3 text-muted fw-normal">Konstantní symbol</dt>
                    <dd className="col-sm-3 font-monospace">{invoice.constantSymbol ?? '—'}</dd>

                    {/* Nákupní objednávka odběratele (V91) — jen když ji zákazník dodal */}
                    {invoice.purchaseOrderNumber && (
                        <>
                            <dt className="col-sm-3 text-muted fw-normal">Číslo objednávky</dt>
                            <dd className="col-sm-3 font-monospace">{invoice.purchaseOrderNumber}</dd>
                        </>
                    )}
                </dl>
                {invoice.note && (
                    <p className="text-muted fst-italic mb-0 mt-3">{invoice.note}</p>
                )}
            </DetailCard>

            {/* ── Položky ────────────────────────────────────────────── */}
            <DetailCard title="Položky">
                <DataTable
                    columns={itemColumns}
                    rows={invoice.items}
                    clientSort
                    dense
                    emptyState={
                        <EmptyState icon="list-ul" title="Faktura nemá žádné položky."
                                    hint="Položky se na fakturu přenesou ze zakázky při jejím vytvoření." />
                    }
                />
            </DetailCard>

            {/* ── Součty ─────────────────────────────────────────────── */}
            <DetailCard>
                <dl className="row mb-0 text-end justify-content-end">
                    <dt className="col-sm-3 text-muted fw-normal">Základ daně</dt>
                    <dd className="col-sm-2">{formatCurrency(invoice.totalNet)}</dd>
                </dl>
                <dl className="row mb-0 text-end justify-content-end">
                    <dt className="col-sm-3 text-muted fw-normal">DPH</dt>
                    <dd className="col-sm-2">{formatCurrency(invoice.totalVat)}</dd>
                </dl>
                {/* Zaokrouhlení hotovostní úhrady stojí mimo základ daně (§36/5 ZDPH, V67/KN-7),
                    proto samostatný řádek. U nehotovostní úhrady je nulové a nezobrazuje se. */}
                {invoice.rounding != null && Number(invoice.rounding) !== 0 && (
                    <dl className="row mb-0 text-end justify-content-end">
                        <dt className="col-sm-3 text-muted fw-normal">Zaokrouhlení</dt>
                        <dd className="col-sm-2">{formatCurrency(invoice.rounding)}</dd>
                    </dl>
                )}
                <dl className="row mb-0 text-end justify-content-end border-top pt-2">
                    <dt className="col-sm-3 fw-medium">Celkem k úhradě</dt>
                    <dd className="col-sm-2 fw-bold fs-5 mb-0">
                        {formatCurrency(invoice.totalToPay ?? invoice.totalGross)}
                    </dd>
                </dl>
            </DetailCard>

            {/* ── Pokladní doklady ───────────────────────────────────── */}
            {/* Karta se ukazuje, jen když nějaký doklad existuje — u bezhotovostních faktur
                by jinak zela prázdná. Stornované zůstávají vidět: číselná řada je souvislá
                a účetní musí dohledat, co se s dokladem stalo. */}
            {cashReceipts.length > 0 && (
                <DetailCard title="Pokladní doklady">
                    <DataTable
                        columns={cashReceiptColumns}
                        rows={cashReceipts}
                        rowActions={cashReceiptActions}
                        onAction={handleCashReceiptAction}
                        dense
                    />
                </DetailCard>
            )}

            <ConfirmDialog
                title="Potvrďte akci"
                message={confirmState.message}
                show={confirmState.show}
                onConfirm={confirmTransition}
                onCancel={() => setConfirmState({ show: false, action: null, message: "" })}
            />

            <FormModal
                show={showCreditNoteForm}
                title="Vystavit opravný daňový doklad"
                intro={<>Doklad se založí jako <strong>koncept</strong> — evidenční číslo dostane
                    až vystavením. Rozdílové částky se odvodí z této faktury.</>}
                fields={[{
                    name: "correctionReason",
                    label: "Důvod opravy",
                    type: "textarea",
                    rows: 2,
                    required: true,
                    maxLength: 500,
                    hint: "Povinná náležitost opravného dokladu (§45 ZDPH) — vytiskne se na doklad. "
                        + "Např. „Reklamace — vrácení dílu“ nebo „Chybně účtované množství“.",
                }]}
                submitLabel="Založit koncept"
                onSubmit={createCreditNote}
                onCancel={() => setShowCreditNoteForm(false)}
                saving={busy}
            />

            <FormModal
                show={receiptToCancel != null}
                title={`Stornovat pokladní doklad ${receiptToCancel?.receiptNumber ?? ''}`}
                intro={<>Doklad <strong>zůstane v číselné řadě</strong> a půjde vytisknout, jen
                    přestane platit — účetní záznam se zachová. Teprve po stornu (nebo smazání)
                    lze k faktuře vystavit nový doklad.</>}
                fields={[{
                    name: "reason",
                    label: "Důvod storna",
                    type: "textarea",
                    rows: 2,
                    required: true,
                    maxLength: 255,
                    hint: "Vytiskne se na stornovaný doklad. Např. „Vystaveno omylem“ "
                        + "nebo „Zákazník nakonec zaplatil převodem“.",
                }]}
                submitLabel="Stornovat doklad"
                onSubmit={cancelCashReceipt}
                onCancel={() => setReceiptToCancel(null)}
                saving={busy}
            />

            {/* Vystavení PPD — číslo a datum zadává obsluha (V92), jako u vystavení faktury. */}
            <CashReceiptIssueModal
                show={showCashReceiptForm}
                invoice={invoice}
                onIssue={createCashReceipt}
                onCancel={() => setShowCashReceiptForm(false)}
            />

            {/* Smazání PPD je nevratné a u stornovaného dokladu bere i doložený důvod storna —
                hláška to musí říct, proto vlastní dialog, ne sdílený `confirmState`. */}
            <ConfirmDialog
                title={`Smazat pokladní doklad ${receiptToDelete?.receiptNumber ?? ''}?`}
                message={receiptToDelete?.status === 'CANCELLED'
                    ? "Stornovaný doklad se smaže i s doloženým důvodem storna a nelze ho vrátit. "
                      + "Číslo se uvolní pro další vystavení."
                    : "Doklad se nenávratně smaže a číslo se uvolní — další doklad ho dostane znovu. "
                      + "Faktura přestane mít navázaný pokladní doklad. Chcete-li záznam v řadě "
                      + "zachovat, použijte místo smazání storno."}
                show={receiptToDelete != null}
                onConfirm={deleteCashReceipt}
                onCancel={() => setReceiptToDelete(null)}
            />

            <InvoiceIssueModal
                show={showIssueForm}
                invoice={invoice}
                onIssue={issueInvoice}
                onCancel={() => setShowIssueForm(false)}
            />

            {/* Předání s nabídkou e-mailu i opakované odeslání — týž dialog, dva režimy. */}
            <InvoiceSendEmailModal
                show={emailDialog !== null}
                invoice={invoice}
                mode={emailDialog?.mode}
                onDone={(updated, { sent }) => {
                    setEmailDialog(null);
                    setInvoice(updated);
                    addAlert(sent
                        ? `Faktura ${updated.invoiceNumber} odeslána e-mailem.`
                        : "Faktura označena jako předaná zákazníkovi.", "success");
                }}
                onCancel={() => setEmailDialog(null)}
            />

            {/* Vlastní dialog, ne sdílený `confirmState` — mazání není přechod stavu
                a hláška musí říct, že je nevratné (položky jdou s konceptem). */}
            <ConfirmDialog
                title={invoice.status === 'DRAFT' ? "Smazat koncept faktury?" : "Smazat vystavenou fakturu?"}
                message={invoice.status === 'DRAFT'
                    ? "Koncept se smaže i s položkami a nelze ho vrátit. Zakázku pak můžete znovu upravit a vyfakturovat."
                    : `Faktura ${invoice.invoiceNumber} se smaže i s položkami a nelze ji vrátit. `
                      + "Jde to jen proto, že jste ji neoznačili jako předanou zákazníkovi — pokud ji "
                      + "přesto někdo dostal, smazat ji nesmíte a musíte vystavit dobropis. "
                      + "Číslo se uvolní; příští fakturu můžete vystavit s ním."}
                show={confirmDelete}
                onConfirm={deleteInvoice}
                onCancel={() => setConfirmDelete(false)}
            />
        </div>
    );
}

/* ── Sub-komponenta: karta strany ──────────────────────────────────── */

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
                    {(party.bankAccount || party.iban || party.swift) && (
                        <div className="mt-2 text-muted">
                            {party.bankAccount && <div>Účet: {party.bankAccount}</div>}
                            {party.iban && <div>IBAN: {party.iban}</div>}
                            {party.swift && <div>SWIFT: {party.swift}</div>}
                        </div>
                    )}
                </address>
            ) : (
                <p className="text-muted fst-italic mb-0">Neuvedeno</p>
            )}
        </DetailCard>
    );
}
