import * as React from "react";
import { useEffect, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import { api, problemMessage } from "../api/api.js";
import { formatDate, formatQuantity, getStockTakeStatusLabel, getStockTakeStatusTone } from "../api/format.js";
import { useAlert } from "../context/AlertContext.jsx";
import FormModal from "../components/FormModal.jsx";
import StatusBadge from "../components/StatusBadge.jsx";
import PageHeader from "../components/PageHeader.jsx";
import LoadingState from "../components/LoadingState.jsx";
import ErrorState from "../components/ErrorState.jsx";

/**
 * Soupis inventury (E6.4, P-5): vyplnění napočítaných množství a uzavření.
 *
 * Prázdné pole = **nepočítáno** (řádek negeneruje korekci), ne nula. Rozdíl se
 * počítá proti aktuálnímu stavu skladu, takže výdej během počítání se nepřepíše.
 */
export default function StockTakePageDetail() {

    const { id } = useParams();
    const navigate = useNavigate();
    const { addAlert } = useAlert();

    const [detail, setDetail] = useState(null);
    const [edits, setEdits] = useState({});      // itemId → {countedQuantity, surplusUnitPrice}
    const [saving, setSaving] = useState(false);
    const [error, setError] = useState("");
    const [showClose, setShowClose] = useState(false);

    useEffect(() => {
        async function load() {
            try {
                setDetail(await api.get(`/warehouse/stock-takes/${id}`));
            } catch (err) {
                setError(problemMessage(err, "Inventuru se nepodařilo načíst."));
            }
        }
        load();
    }, [id]);

    if (!detail && !error) return <LoadingState />;
    if (!detail) return <ErrorState message={error} backTo="/warehouse/stock-takes" backLabel="Zpět na inventury" />;

    const readOnly = detail.status !== "OPEN";

    /** Hodnota k zobrazení: rozeditovaná má přednost před uloženou. */
    function valueOf(item, field) {
        const edit = edits[item.id];
        if (edit && edit[field] !== undefined) return edit[field] ?? "";
        return item[field] ?? "";
    }

    function change(item, field, raw) {
        setEdits((prev) => ({
            ...prev,
            [item.id]: { ...prev[item.id], [field]: raw === "" ? null : Number(raw) },
        }));
    }

    /** Rozdíl počítaný živě z rozeditované hodnoty proti aktuálnímu stavu. */
    function differenceOf(item) {
        const counted = valueOf(item, "countedQuantity");
        if (counted === "" || counted === null) return null;
        return Number(counted) - Number(item.currentQuantity);
    }

    /**
     * Uloží rozeditovaný soupis a **chybu nechá probublat** na volajícího.
     *
     * Záměrně bez `try/catch`: uzavření inventury je nevratné, takže musí selhat spolu
     * s uložením. Kdyby si tato funkce chybu ošetřila sama, `await` by nikdy nevyhodil
     * a `closeStockTake` by inventuru uzavřel podle NEULOŽENÝCH hodnot — se zeleným
     * hlášením o úspěchu (audit KN-3). Ošetření chyby patří volajícímu, který ví,
     * jestli se pokračuje dál.
     *
     * Odesílá se EFEKTIVNÍ zobrazená hodnota, ne jen rozeditovaná: když uživatel
     * pole needitoval, vezme se aktuální hodnota z detailu (u ceny je to předvyplněná
     * cena ze snapshotu). Jinak by se needitovaná cena poslala jako null a přepsala
     * předvyplněnou hodnotu v DB → falešné STOCK_TAKE_PRICE_MISSING při uzavření.
     */
    async function persistCounts() {
        const items = Object.keys(edits).map((itemId) => {
            const item = detail.items.find((i) => i.id === Number(itemId));
            const edit = edits[itemId];
            const resolve = (field) =>
                edit[field] !== undefined ? edit[field] : (item?.[field] ?? null);
            return {
                id: Number(itemId),
                countedQuantity: resolve("countedQuantity"),
                surplusUnitPrice: resolve("surplusUnitPrice"),
            };
        });
        const saved = await api.put(`/warehouse/stock-takes/${id}/items`, { items });
        setDetail(saved);
        setEdits({});
        return saved;
    }

    /** Tlačítko „Uložit soupis" — chybu zobrazí a dál se nepokračuje. */
    async function saveCounts() {
        if (Object.keys(edits).length === 0) {
            addAlert("Není co uložit — soupis jste nezměnili.", "info");
            return;
        }
        setSaving(true);
        setError("");
        try {
            await persistCounts();
            addAlert("Soupis uložen.", "success");
        } catch (err) {
            setError(problemMessage(err, "Soupis se nepodařilo uložit."));
        } finally {
            setSaving(false);
        }
    }

    async function closeStockTake({ note }) {
        setShowClose(false);
        setSaving(true);
        setError("");
        try {
            // Uložení je uvnitř TOHOTO try (vzor ReceiptReviewPage.confirmReceipt) — když
            // selže, vyhodí a k uzavření se vůbec nedojde. Uzavírá se jen uložený soupis.
            if (Object.keys(edits).length > 0) await persistCounts();
            const closed = await api.post(`/warehouse/stock-takes/${id}/close`, { note: note || null });
            setDetail(closed);
            addAlert("Inventura uzavřena — korekce byly zapsány do skladu.", "success");
        } catch (err) {
            setError(problemMessage(err, "Inventuru se nepodařilo uzavřít."));
        } finally {
            setSaving(false);
        }
    }

    async function cancelStockTake() {
        setSaving(true);
        try {
            setDetail(await api.post(`/warehouse/stock-takes/${id}/cancel`));
            addAlert("Inventura zrušena — sklad zůstal beze změny.", "info");
        } catch (err) {
            setError(problemMessage(err, "Inventuru se nepodařilo zrušit."));
        } finally {
            setSaving(false);
        }
    }

    return (
        <div>
            <PageHeader
                title={detail.stockTakeNumber ? `Inventura ${detail.stockTakeNumber}` : `Inventura z ${formatDate(detail.openedAt)}`}
                subtitle={`Zahájena ${formatDate(detail.openedAt)}`}
                backTo="/warehouse/stock-takes"
                backLabel="Zpět na inventury"
                badges={
                    <StatusBadge tone={getStockTakeStatusTone(detail.status)}>
                        {getStockTakeStatusLabel(detail.status)}
                    </StatusBadge>
                }
            />

            {error && <div className="alert alert-danger py-2">{error}</div>}

            {detail.status === "CLOSED" && (
                <div className="alert alert-success py-2">
                    Inventura je uzavřená. Napočítáno {detail.countedLines} řádků,
                    z toho {detail.shortageLines} mank a {detail.surplusLines} přebytků.
                    {detail.surplusReceiptId && <> Přebytky naskladněny příjemkou #{detail.surplusReceiptId}.</>}
                </div>
            )}

            {detail.status === "OPEN" && (
                <div className="alert alert-light border py-2 small text-muted">
                    <i className="bi bi-info-circle me-1"></i>
                    Prázdné pole znamená <strong>nepočítáno</strong> — takový řádek se při uzavření
                    přeskočí. Rozdíl se počítá proti aktuálnímu stavu, takže výdeje během počítání
                    se nepřepíšou.
                </div>
            )}

            <div className="table-responsive">
                <table className="table table-sm table-hover align-middle">
                    <thead className="table-light">
                    <tr>
                        <th scope="col">SKU</th>
                        <th scope="col">Název dílu</th>
                        <th scope="col" className="text-end">Skladem</th>
                        <th scope="col" className="text-end" style={{ width: "8rem" }}>Napočítáno</th>
                        <th scope="col" className="text-end">Rozdíl</th>
                        <th scope="col" className="text-end" style={{ width: "9rem" }}>Cena přebytku</th>
                    </tr>
                    </thead>
                    <tbody>
                    {detail.items.map((item) => {
                        const diff = differenceOf(item);
                        const diffClass = diff == null ? "text-muted"
                            : diff < 0 ? "text-danger" : diff > 0 ? "text-success" : "";
                        return (
                            <tr key={item.id}>
                                <td><code className="small">{item.sku}</code></td>
                                <td>{item.name}</td>
                                <td className="text-end">{formatQuantity(item.currentQuantity)} {item.unit}</td>
                                <td>
                                    {/* aria-label: v soupisu jsou dvě editovatelná pole na řádek
                                        a bez názvu nebylo poznat, které je které (audit 11-F-16). */}
                                    <input type="number" className="form-control form-control-sm text-end"
                                           aria-label={`Napočítáno — ${item.name}`}
                                           min="0" step="0.001" disabled={readOnly}
                                           value={valueOf(item, "countedQuantity")}
                                           onChange={(e) => change(item, "countedQuantity", e.target.value)} />
                                </td>
                                <td className={`text-end fw-semibold ${diffClass}`}>
                                    {diff == null ? "—" : (diff > 0 ? "+" : "") + formatQuantity(diff)}
                                </td>
                                <td>
                                    {diff != null && diff > 0 ? (
                                        <input type="number" className="form-control form-control-sm text-end"
                                               aria-label={`Cena přebytku — ${item.name}`}
                                               min="0" step="0.01" disabled={readOnly}
                                               value={valueOf(item, "surplusUnitPrice")}
                                               onChange={(e) => change(item, "surplusUnitPrice", e.target.value)} />
                                    ) : <span className="text-muted small">—</span>}
                                </td>
                            </tr>
                        );
                    })}
                    </tbody>
                </table>
            </div>

            {!readOnly && (
                // §10.8: průběžné uložení je neutrální, vrcholná nevratná akce zelená
                <div className="d-flex gap-2 mt-3">
                    <button className="btn btn-outline-secondary" onClick={saveCounts} disabled={saving}>
                        Uložit soupis
                    </button>
                    <button className="btn btn-success" onClick={() => setShowClose(true)} disabled={saving}>
                        Uzavřít inventuru
                    </button>
                    <button className="btn btn-outline-danger ms-auto" onClick={cancelStockTake} disabled={saving}>
                        Zrušit inventuru
                    </button>
                </div>
            )}

            <FormModal
                show={showClose}
                title="Uzavřít inventuru?"
                intro={
                    <>
                        <p>Rozdíly se zapíšou do skladu jako korekční pohyby — manka se odečtou
                            od nejstarších šarží, přebytky se naskladní novou příjemkou.</p>
                        <p className="text-muted small mb-2">
                            Řádky bez napočítaného množství se přeskočí. Akce je nevratná.
                        </p>
                    </>
                }
                fields={[{
                    name: "note", label: "Poznámka", type: "textarea", maxLength: 500,
                    hint: "Volitelné — proč se inventura dělala, co se našlo.",
                }]}
                submitLabel="Uzavřít a zapsat korekce"
                onSubmit={closeStockTake}
                onCancel={() => setShowClose(false)}
                saving={saving}
            />
        </div>
    );
}
