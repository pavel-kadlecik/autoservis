import OrderItemTable from "./OrderItemTable.jsx";
import * as React from "react";
import {useEffect, useState} from "react";
import {api, problemMessage} from "../api/api.js";
import ImportProductFormModal from "./ImportProductFormModal.jsx";
import OrderItemsSummary from "./OrderItemsSummary.jsx";
import OrderItemsToolbar from "./OrderItemsToolbar.jsx";
import OrderItemFormModal from "./OrderItemFormModal.jsx";
import ConfirmDialog from "./ConfirmDialog.jsx";
import {useAlert} from "../context/AlertContext.jsx";

/**
 * Číslo z formulářového pole s fallbackem pro **prázdné** pole.
 *
 * Nahrazuje vzor `parseFloat(x) || fallback`, který je falsy-coalescing: v JavaScriptu je `0`
 * nepravdivá hodnota, takže zadanou NULU považoval za nevyplněno a tiše ji přepsal fallbackem
 * (audit KN-9). U sazby DPH se tak z 0 % stalo 21 % — a hodnota se přes položku zakázky
 * zkopírovala do faktury, tedy na daňový doklad. U množství se z 0 stala 1, tedy účtovaná položka.
 *
 * Rozlišuje tři stavy, které starý zápis sléval do jednoho:
 * prázdné pole → `fallback` · platné číslo (včetně nuly) → to číslo · nesmysl → `fallback`.
 *
 * @param {string|number|null|undefined} raw hodnota z formuláře
 * @param {number} fallback použije se JEN pro prázdné pole nebo nečíselný vstup
 * @returns {number}
 */
function numberFromField(raw, fallback) {
    if (raw === "" || raw === null || raw === undefined) {
        return fallback;
    }
    const parsed = Number(raw);
    return Number.isFinite(parsed) ? parsed : fallback;
}

export default function OrderItemsWrapper({initialItems, orderId}) {

    const {addAlert} = useAlert();

    // Stav položek zakázky
    const [items, setItems] = useState(initialItems);
    const [receiptItems, setReceiptItems] = useState([]);
    const [itemError, setItemError] = useState(null);
    const [showModal, setShowModal] = useState(false);
    const [showInvoiceCreateModal, setShowInvoiceCreateModal] = useState(false);
    const [selectionGriItems, setSelectionGriItems] = useState({});
    const [loading, setLoading] = useState(false);
    const [errorGriItems, setErrorGriItems] = useState(null);
    const [saving, setSaving] = useState(false);
    const [summary, setSummary] = useState({});
    const [confirmDeleteState, setConfirmDeleteState] = useState({show: false, itemId: null});
    const [employees, setEmployees] = useState([]);

    useEffect(() => {
        let cancelled = false;
        api.get(`/orders/${orderId}/items/summary`)
            .then(data => { if (!cancelled) setSummary(data); })
            .catch(() => { if (!cancelled) setSummary(null); });
        return () => { cancelled = true; };
    }, [items, orderId]);

    // Aktivní mechanici pro select u LABOR položky. Odešlé záznamy se v číselníku
    // nenabízejí — u editace se dohrají jako „(mimo číselník)" (viz OrderItemFormModal).
    useEffect(() => {
        let cancelled = false;
        api.get(`/employees?activeOnly=true`)
            .then(data => { if (!cancelled) setEmployees(data); })
            .catch(() => { if (!cancelled) setEmployees([]); });
        return () => { cancelled = true; };
    }, []);

    const emptyItemForm = {
        itemType:      "LABOR",
        name:          "",
        quantity:      "1",
        unit:          "ks",    // i u práce se výchozí účtuje po kusech (paušál za úkon)
        purchasePrice: "",
        unitPrice:     "",
        vatRate:       "21",
        position:      "",
        note:          "",
        fromStock:     false,
        employeeId:    "",   // mechanik u LABOR položky (D-1)
        employeeName:  "",
    };

    const [itemForm, setItemForm]           = useState(emptyItemForm);
    const [editingItemId, setEditingItemId] = useState(null);
    const [showItemForm, setShowItemForm]   = useState(false);


    async function handleImportSelected(selection) {
        if (Object.keys(selection).length === 0) return;

        const payload = Object.entries(selection).map(([id, qty]) => ({
            goodsReceiptItemId: Number(id),
            quantity: Number(qty),
        }));

        setErrorGriItems(null);
        setSaving(true);
        try {
            const created = await api.post(`/orders/${orderId}/items/import-from-receipt`, payload);
            setItems(prev => [...prev, ...created]);
            setSelectionGriItems({});
            setShowModal(false);          // zavřít JEN při úspěchu (v try)
        } catch (err) {
            setErrorGriItems(problemMessage(err, "Položky dokladu se nepodařilo importovat."));
        } finally {
            setSaving(false);
        }

    }

    function handleItemChange(e) {
        const {name, value} = e.target;
        // Jednotka se po přepnutí typu vrací na "ks" — i u práce, která se od 2026-08-03
        // účtuje buď po hodinách, nebo po kusech (paušál za úkon); zamčená už není.
        // Mechanik dává smysl jen u práce (D-2) — při přepnutí typu ho zahoď.
        if (name === "itemType") {
            const isLabor = value === "LABOR";
            setItemForm(prev => ({
                ...prev,
                itemType:     value,
                unit:         "ks",
                employeeId:   isLabor ? prev.employeeId : "",
                employeeName: isLabor ? prev.employeeName : "",
            }));
            return;
        }
        // Přepnutí na hodiny dorovná sazbu mechanika, pokud si obsluha cenu ještě nevyplnila
        // — jinak by musela sazbu hledat ručně jen proto, že vybírala v opačném pořadí.
        if (name === "unit") {
            setItemForm(prev => {
                const emp = employees.find(e2 => String(e2.id) === String(prev.employeeId));
                const fillRate = value === "hod" && emp && !prev.purchasePrice;
                return {
                    ...prev,
                    unit:          value,
                    purchasePrice: fillRate ? String(emp.hourlyRate ?? "") : prev.purchasePrice,
                };
            });
            return;
        }
        // Po výběru mechanika předvyplň jeho aktuální sazbu do nákupní ceny (D-6) — jde přepsat.
        // Jen u hodin: sazba za hodinu dosazená jako cena za kus by byla tiše špatné číslo
        // v nákladech (rozhodnutí uživatele 2026-08-03).
        if (name === "employeeId") {
            const emp = employees.find(emp => String(emp.id) === String(value));
            setItemForm(prev => ({
                ...prev,
                employeeId:    value,
                employeeName:  emp ? emp.fullName : "",
                purchasePrice: prev.unit === "hod" && emp && emp.hourlyRate != null
                        ? String(emp.hourlyRate)
                        : prev.purchasePrice,
            }));
            return;
        }
        setItemForm(prev => ({...prev, [name]: value}));
    }

    function handleFormVisibility () {
        setShowItemForm(true);
        setEditingItemId(null);
        setItemForm(emptyItemForm);
        setItemError(null);
    }

    async function handleOnSubmitImportProduct(form) {
        setLoading(true);
        setErrorGriItems(null);
        try {
            const imported = await api.get(`/warehouse/goods-receipts/${form.id}/items`);
            setReceiptItems(imported);     // nahraď, do VLASTNÍHO seznamu modalu
            setSelectionGriItems({});      // nový doklad → vyresetuj výběr
        } catch (err) {
            setErrorGriItems(problemMessage(err, "Položky dokladu se nepodařilo načíst."));
            setReceiptItems([]);
        } finally {
            setLoading(false);
        }
    }


    function handleImportItems () {
        setShowModal(true);
    }

    function handleCreateInvoice() {
        setShowInvoiceCreateModal(true);
    }

    function handleItemEdit(item) {
        setEditingItemId(item.id);
        setItemForm({
            itemType:      item.itemType      ?? "LABOR",
            name:          item.name          ?? "",
            quantity:      item.quantity      ?? "1",
            unit:          item.unit          ?? "ks",
            purchasePrice: item.purchasePrice ?? "",
            unitPrice:     item.unitPrice     ?? "",
            vatRate:       item.vatRate       ?? "21",
            position:      item.position      ?? "",
            note:          item.note          ?? "",
            fromStock:     item.fromStock     ?? false,
            employeeId:    item.employeeId    ?? "",
            employeeName:  item.employeeName  ?? "",
        });
        setShowItemForm(true);
        setItemError(null);
    }

    function handleItemCancel() {
        setShowItemForm(false);
        setEditingItemId(null);
        setItemForm(emptyItemForm);
        setItemError(null);
    }

    async function handleItemSave() {
        if (!itemForm.name?.trim()) {
            setItemError("Název položky je povinný.");
            return;
        }
        if (itemForm.unitPrice === "" || itemForm.unitPrice === null) {
            setItemError("Prodejní cena je povinná.");
            return;
        }

        const payload = {
            itemType:      itemForm.itemType,
            name:          itemForm.name,
            quantity:      numberFromField(itemForm.quantity, 1),
            unit:          itemForm.unit,
            purchasePrice: itemForm.purchasePrice !== "" ? parseFloat(itemForm.purchasePrice) : null,
            unitPrice:     numberFromField(itemForm.unitPrice, 0),
            // Math.trunc drží celočíselnou sazbu (DTO má Short) — stejně jako dřívější parseInt,
            // které z „21.5" udělalo 21. Nula ale nově projde jako nula.
            vatRate:       Math.trunc(numberFromField(itemForm.vatRate, 21)),
            position:      itemForm.position !== "" ? parseInt(itemForm.position) : items.length + 1,
            note:          itemForm.note || null,
            // employeeId jen u LABOR (D-2); u ostatních typů posíláme null
            employeeId:    itemForm.itemType === "LABOR" && itemForm.employeeId
                               ? Number(itemForm.employeeId)
                               : null,
        };

        try {
            if (editingItemId) {
                const updated = await api.put(`/orders/${orderId}/items/${editingItemId}`, payload);
                setItems(prev => prev.map(i => i.id === editingItemId ? updated : i));
            } else {
                const created = await api.post(`/orders/${orderId}/items`, payload);
                setItems(prev => [...prev, created]);
            }
            handleItemCancel();
        } catch(err) {
            const message = problemMessage(err, "Položku se nepodařilo uložit.");
            setItemError(message);
        }
    }

    function handleItemDelete(itemId) {
        setConfirmDeleteState({show: true, itemId});
    }

    async function confirmItemDelete() {
        const {itemId} = confirmDeleteState;
        setConfirmDeleteState({show: false, itemId: null});
        try {
            await api.delete(`/orders/${orderId}/items/${itemId}`);
            setItems(prev => prev.filter(i => i.id !== itemId));
        } catch (err) {
            setItemError(problemMessage(err, "Položku se nepodařilo smazat."));
        }
    }

    async function handleReorder(newItems) {
        // 1. Okamžitě aktualizuj UI (optimistický update)
        setItems(newItems);

        // 2. Pošli nové pořadí na API
        const payload = newItems.map((item, index) => ({
            id: item.id,
            position: index + 1,
        }));

        try {
            await api.put(`/orders/${orderId}/items/reorder`, payload);
        } catch (err) {
            // Rollback sám o sobě nestačí — pořadí by se uživateli beze slova
            // vrátilo zpátky a vypadalo by to jako chyba přetahování.
            setItems(items);
            addAlert(problemMessage(err, "Nové pořadí položek se nepodařilo uložit."), "danger");
        }
    }

    return (
        <>
            <OrderItemTable
            items={items}
            onEdit={handleItemEdit}
            onDelete={handleItemDelete}
            onReorder={handleReorder}
            />
            <OrderItemsSummary summary={summary} />
            <OrderItemsToolbar
                itemError={itemError}
                showItemForm={showItemForm}
                handleFormVisibility={handleFormVisibility}
                handleImportItems={handleImportItems}
                saving={saving}
            />
            <OrderItemFormModal
                show={showItemForm}
                editingItemId={editingItemId}
                itemForm={itemForm}
                itemError={itemError}
                employees={employees}
                onChange={handleItemChange}
                onSave={handleItemSave}
                onCancel={handleItemCancel}
            />
            <ImportProductFormModal
                items={receiptItems}        // ← šarže dokladu, NE položky zakázky
                loading={loading}
                selection={selectionGriItems}
                setSelection={setSelectionGriItems}
                show={showModal}
                onCancel={() => setShowModal(false)}
                onSubmit={handleOnSubmitImportProduct}
                onImport={handleImportSelected}
                error={errorGriItems}
                saving={saving}
            />
            <ConfirmDialog
                title="Smazat položku"
                message="Opravdu chcete smazat tuto položku?"
                show={confirmDeleteState.show}
                onConfirm={confirmItemDelete}
                onCancel={() => setConfirmDeleteState({show: false, itemId: null})}
            />
        </>
    )
}