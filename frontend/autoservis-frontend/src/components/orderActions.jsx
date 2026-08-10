import React, { useState } from "react";
import { useNavigate } from "react-router-dom";
import VisibilityIcon from "@mui/icons-material/Visibility";
import EditIcon from "@mui/icons-material/Edit";
import FlagIcon from "@mui/icons-material/Flag";
import DescriptionIcon from "@mui/icons-material/Description";
import Inventory2Icon from "@mui/icons-material/Inventory2";
import ReceiptLongIcon from "@mui/icons-material/ReceiptLong";
import DeleteIcon from "@mui/icons-material/Delete";

import { api, problemMessage } from "../api/api.js";
import { ORDER_STATUS_OPTIONS, getOrderStatusLabel, getFormDate } from "../api/format.js";
import { useAlert } from "../context/AlertContext.jsx";
import OrderDeleteDialog from "./OrderDeleteDialog.jsx";
import OrderCancelDialog from "./OrderCancelDialog.jsx";
import InvoiceCreateFormModal from "./InvoiceCreateFormModal.jsx";

/**
 * Akce nad zakázkou — <strong>jedna definice pro celou aplikaci</strong>.
 *
 * <p>Do 2026-08-07 byla každá akce napsaná tam, kde se to zrovna hodilo tomu, kdo ji přidával:
 * mazání a zakázkový list v `OrdersPageDetail`, fakturace v `OrderForm`, výdej ze skladu
 * v `OrderItemsWrapper`, změna stavu zvlášť v detailu a zvlášť v `OrderTable`. Pět akcí na
 * čtyřech místech, žádná sdílená — takže seznam neuměl mazat, editace neuměla vytisknout
 * zakázkový list a na detailu, kam prázdný stav faktur obsluhu výslovně posílal, nešlo
 * fakturovat. Ta nápověda lhala, protože nikdo neměl jak si všimnout, že se místa rozešla.
 *
 * <p>Pravidlo (rozhodnutí uživatele 2026-08-07): <strong>v seznamu jako položky řádkového
 * menu, na detailu a v editaci shodně jako tlačítka.</strong> Odtud plyne rozdělení
 * odpovědnosti tady:
 *
 * <ul>
 *   <li>{@link orderActionItems} — CO se nabízí; čistý popis bez chování, aby ho uměl
 *       vykreslit jak `DataTable` (menu), tak hlavička stránky (tlačítka),</li>
 *   <li>{@link useOrderActions} — CO to udělá; drží dialogy i volání serveru.</li>
 * </ul>
 *
 * Jediná odlišnost mezi místy je navigace sama na sebe: v seznamu se nabízí „Detail"
 * i „Editovat", na detailu chybí „Detail" a v editaci „Editovat".
 */

const STATUS_ACTION = "status:";

/**
 * Popis nabízených akcí pro danou zakázku a místo.
 *
 * @param {object} order    zakázka (id, orderNumber, status)
 * @param {"list"|"detail"|"edit"} context
 * @returns {Array<{id:string,label:string,icon:React.ReactNode,tone?:string,title?:string}>}
 */
export function orderActionItems(order, context) {
    if (!order) return [];

    // Změna stavu patří JEN do menu řádku v seznamu (rozhodnutí uživatele 2026-08-07).
    // Na detailu a v editaci se nenabízí: v editaci je stav běžné pole formuláře, takže
    // druhá cesta k témuž údaji by si s ním konkurovala, a na detailu by šest položek
    // navíc utopilo akce, kvůli kterým tam obsluha jde.
    //
    // Nabízejí se všechny stavy kromě současného a UI je nefiltruje: co neprojde (zrušená
    // zakázka, znovuotevření s fakturou, chybějící díl) odmítne backend hláškou, která
    // řekne proč. Frontend ty podmínky uhádnout nemůže — závisejí na stavu databáze.
    const statusItems = context !== "list" ? [] : ORDER_STATUS_OPTIONS
        .filter(option => option.value !== order.status)
        .map(option => ({
            id: STATUS_ACTION + option.value,
            label: option.value === "COMPLETED" ? "Označit jako dokončenou"
                 : order.status === "COMPLETED" ? `Znovu otevřít — ${option.label}`
                 : `Změnit na „${option.label}"`,
            icon: <FlagIcon fontSize="small"/>,
        }));

    // Zakázkový list, výdej ze skladu a fakturace jsou akce nad OTEVŘENOU zakázkou
    // (rozhodnutí uživatele 2026-08-07): dělají se nad tím, co má člověk před sebou —
    // nad položkami, cenou a stavem. Ze seznamu se tedy nenabízejí, tam by se spouštěly
    // naslepo nad řádkem. V seznamu zůstává jen navigace, změna stavu a smazání.
    const onOpenOrder = context === "list" ? [] : [
        {id: "protocol", label: "Zakázkový list", icon: <DescriptionIcon fontSize="small"/>},
        // U vyfakturované zakázky se nenabízí: faktura se vystavuje až z dokončené zakázky,
        // dokončení materiál vydá a faktura pak položky zamkne — nová rezervace tedy vzniknout
        // nemůže a tlačítko by bylo zaručeně bez efektu (rozhodnutí uživatele 2026-08-08).
        // Po dobropisu se zakázka zase odemkne, `invoiceId` je prázdné a tlačítko se vrátí.
        !order.invoiceId && {
            id: "issue", label: "Vydat ze skladu", icon: <Inventory2Icon fontSize="small"/>,
            title: "Vydá ze skladu materiál rezervovaný na této zakázce. Jinak se vydá sám při dokončení.",
        },
        // Druhou fakturu k zakázce založit nejde (ORDER_ALREADY_INVOICED), takže jakmile
        // nějaká existuje — i jako koncept — tlačítko na ni odkazuje místo toho, aby
        // slibovalo vytvoření, které by server odmítl (rozhodnutí uživatele 2026-08-08).
        {id: "invoice",
         label: order.invoiceId ? "Přejít na fakturu" : "Vytvořit fakturu",
         icon: <ReceiptLongIcon fontSize="small"/>},
    ];

    return [
        context !== "detail" && {id: "detail", label: "Detail", icon: <VisibilityIcon fontSize="small"/>},
        context !== "edit"   && {id: "edit",   label: "Editovat", icon: <EditIcon fontSize="small"/>},
        ...statusItems,
        ...onOpenOrder,
        {id: "delete", label: "Smazat", icon: <DeleteIcon fontSize="small"/>, tone: "danger"},
    ].filter(Boolean);
}

/**
 * Chování akcí — dialogy, volání serveru, hlášky.
 *
 * @param {object}   options
 * @param {Function} [options.onChanged]  zavolá se, když akce změnila zakázku (stav, faktura,
 *                                        výdej) — stránka si podle toho načte data znovu
 * @param {Function} [options.onDeleted]  zavolá se po smazání; seznam se překreslí, detail
 *                                        a editace musí odnavigovat pryč
 * @returns {{run: Function, dialogs: React.ReactNode}}
 */
export function useOrderActions({ onChanged, onDeleted } = {}) {

    const navigate = useNavigate();
    const { addAlert } = useAlert();

    const [orderToCancel, setOrderToCancel] = useState(null);
    const [orderToDelete, setOrderToDelete] = useState(null);
    const [busy, setBusy] = useState(false);

    // Fakturace: modal si nese vlastní rozpracovaná data, protože se v něm vyplňují data
    // a adresa. `invoiceOrder` drží zakázku, ke které se koncept zakládá.
    const [invoiceOrder, setInvoiceOrder] = useState(null);
    const [invoiceData, setInvoiceData] = useState(null);
    const [invoiceAddresses, setInvoiceAddresses] = useState([]);
    const [invoiceError, setInvoiceError] = useState("");
    const [invoiceSaving, setInvoiceSaving] = useState(false);

    async function run(actionId, order) {
        if (actionId === "detail")   return navigate(`/orders/${order.id}/detail`);
        if (actionId === "edit")     return navigate(`/orders/${order.id}/edit`);
        if (actionId === "protocol") return window.open(`/api/v1/orders/${order.id}/protocol`, "_blank");
        if (actionId === "delete")   return setOrderToDelete(order);
        if (actionId === "issue")    return issueStock(order);
        if (actionId === "invoice") {
            return order.invoiceId
                ? navigate(`/invoices/${order.invoiceId}/detail`)
                : openInvoiceModal(order);
        }

        if (actionId.startsWith(STATUS_ACTION)) {
            const status = actionId.slice(STATUS_ACTION.length);
            // Zrušení sahá do skladu (vrací vydaný materiál) a může narazit na fakturu,
            // takže má vlastní dialog i vlastní endpoint. Ostatní přechody jen přepíšou stav.
            if (status === "CANCELLED") return setOrderToCancel(order);
            return changeStatus(order, status);
        }
    }

    async function changeStatus(order, status) {
        try {
            const updated = await api.post(`/orders/${order.id}/status`, { status });
            addAlert(`${order.orderNumber}: stav změněn na „${getOrderStatusLabel(status)}".`, "success");
            onChanged?.(updated);
        } catch (err) {
            addAlert(problemMessage(err, "Stav zakázky se nepodařilo změnit."), "danger");
        }
    }

    /**
     * Tlačítko se nabízí vždy: frontend nepozná, které položky už vydané jsou, a backend
     * si to pohlídá sám (opakované volání nic nezdvojí, chybějící díl odmítne s výčtem).
     */
    async function issueStock(order) {
        setBusy(true);
        try {
            const result = await api.post(`/orders/${order.id}/issue-stock`, {});
            const n = result?.issuedItems ?? 0;
            if (n === 0) {
                addAlert("Není co vydávat — materiál na zakázce už ze skladu odešel.", "info");
            } else {
                const word = n === 1 ? "položka" : (n < 5 ? "položky" : "položek");
                addAlert(`Materiál byl vydán ze skladu — ${n} ${word}.`, "success");
            }
            onChanged?.();
        } catch (err) {
            addAlert(problemMessage(err, "Materiál se nepodařilo vydat ze skladu."), "danger");
        } finally {
            setBusy(false);
        }
    }

    async function openInvoiceModal(order) {
        const today = getFormDate();

        let addresses = [];
        try {
            const customer = await api.get(`/customers/${order.customerId}`);
            addresses = customer.addresses ?? [];
        } catch {
            addresses = [];
        }
        setInvoiceAddresses(addresses);

        // Předvyber: BILLING → výchozí → první
        const defaultAddress =
            addresses.find(a => a.addressType === "BILLING") ??
            addresses.find(a => a.isDefault) ??
            addresses[0];

        setInvoiceData({
            issueDate: today, dueDate: today, taxableSupplyDate: today,
            paymentMethod: "CASH", constantSymbol: "", specificSymbol: "", purchaseOrderNumber: "", note: "",
            billingAddressId: defaultAddress ? defaultAddress.id : "",
        });
        setInvoiceError("");
        setInvoiceOrder(order);
    }

    async function createInvoice() {
        setInvoiceSaving(true);
        setInvoiceError("");
        try {
            // Číslo ani VS se neposílají — koncept je nemá, zadají se při vystavení.
            await api.post("/invoices/from-order", {
                orderId:           invoiceOrder.id,
                billingAddressId:  invoiceData.billingAddressId,
                issueDate:         invoiceData.issueDate,
                dueDate:           invoiceData.dueDate,
                taxableSupplyDate: invoiceData.taxableSupplyDate,
                paymentMethod:     invoiceData.paymentMethod,
                constantSymbol:    invoiceData.constantSymbol || null,
                specificSymbol:    invoiceData.specificSymbol || null,
                purchaseOrderNumber: invoiceData.purchaseOrderNumber || null,
                note:              invoiceData.note || null,
            });
            setInvoiceOrder(null);
            addAlert("Koncept faktury byl vytvořen.", "success");
            onChanged?.();
        } catch (err) {
            setInvoiceError(problemMessage(err, "Fakturu se nepodařilo vytvořit."));
        } finally {
            setInvoiceSaving(false);
        }
    }

    async function cancelOrder(order) {
        setOrderToCancel(null);
        try {
            const updated = await api.post(`/orders/${order.id}/cancel`);
            addAlert(`${order.orderNumber}: zakázka zrušena, materiál se vrátil na sklad.`, "success");
            onChanged?.(updated);
        } catch (err) {
            addAlert(problemMessage(err, "Zakázku se nepodařilo zrušit."), "danger");
        }
    }

    async function deleteDraftInvoice(order) {
        await api.delete(`/invoices/${order.invoiceId}`);
    }

    async function deleteOrder(order) {
        setOrderToDelete(null);
        try {
            await api.delete(`/orders/${order.id}`);
            addAlert(`Zakázka ${order.orderNumber} byla smazána.`, "success");
            onDeleted?.(order);
        } catch (err) {
            addAlert(problemMessage(err, "Zakázku se nepodařilo smazat."), "danger");
        }
    }

    const dialogs = (
        <>
            <OrderCancelDialog
                order={orderToCancel}
                onConfirm={cancelOrder}
                onCancel={() => setOrderToCancel(null)}
                onDeleteDraft={deleteDraftInvoice}
            />

            <OrderDeleteDialog
                order={orderToDelete}
                onConfirm={deleteOrder}
                onCancel={() => setOrderToDelete(null)}
                onDeleteDraft={deleteDraftInvoice}
                /* Vyfakturovanou zakázku smazat nelze nikdy — dialog proto nabídne rovnou
                   zrušení místo slepé uličky „vystavte dobropis a zkuste znovu". */
                onSwitchToCancel={order => { setOrderToDelete(null); setOrderToCancel(order); }}
            />

            <InvoiceCreateFormModal
                show={invoiceOrder !== null}
                formData={invoiceData ?? {}}
                setFormData={setInvoiceData}
                addresses={invoiceAddresses}
                error={invoiceError}
                saving={invoiceSaving}
                onSubmit={createInvoice}
                onCancel={() => setInvoiceOrder(null)}
            />
        </>
    );

    return { run, dialogs, busy };
}

/**
 * Táž nabídka vykreslená jako tlačítka — pro detail a editaci, které ji mají mít shodnou.
 *
 * <p>Změna stavu tu není: {@link orderActionItems} ji mimo seznam nevrací.
 */
export function OrderActionButtons({ order, context, run, busy }) {
    return (
        <>
            {orderActionItems(order, context).map(item => (
                <button key={item.id} type="button" title={item.title}
                        disabled={busy}
                        className={`btn btn-outline-${item.tone === "danger" ? "danger" : "secondary"}`}
                        onClick={() => run(item.id, order)}>
                    {item.label}
                </button>
            ))}
        </>
    );
}

export { STATUS_ACTION };
