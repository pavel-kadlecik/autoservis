import React, { useState, useCallback } from "react";
import { ORDER_IMPORT_TYPE_OPTIONS } from "../api/format.js";
import AutocompletePair from "./AutocompletePair.jsx";
import ReceiptItemsTable, { hasInvalidSelection } from "./ReceiptItemsTable.jsx";
import Modal from "./Modal.jsx";
import RequiredMark from "./RequiredMark.jsx";
import LoadingState from "./LoadingState.jsx";

const EMPTY_FORM = { id: "", importType: "" };


export default function ImportProductFormModal({
                                                   items, loading, error, selection, setSelection,
                                                   show, saving, onSubmit, onCancel, onImport                                              }) {
    const [form, setForm] = useState(EMPTY_FORM);

    // našeptávači přidám importType jako parametr.
    // useCallback: stabilní reference mezi rendery, mění se jen když se změní
    // form.importType — AutocompletePair má teď appendParams v poli závislostí
    // svého efektu, takže díky tomu nedochází ke zbytečným re-fetchům při
    // každém renderu tohoto modalu.
    const handleAppendParams = useCallback(
        () => [{ key: "importType", value: form.importType }],
        [form.importType]
    );

    if (!show) return null;

    // Odeslat nejde, dokud je některý řádek vyplněný nad dostupné množství. Dřív to
    // prošlo až k serveru, ten import odmítl a protože je to jedna transakce, spadla
    // s ním celá dávka — včetně řádků, které byly v pořádku.
    const blocked = hasInvalidSelection(items ?? [], selection);

    /**
     * Proč je tlačítko šedé. Zašedlé tlačítko bez vysvětlení je slepá ulička — obsluha
     * netuší, jestli něco zapomněla, nebo je aplikace rozbitá. Důvod proto stojí vedle
     * tlačítka jako text, ne jen v `title`: tooltip se na dotykovém displeji nezobrazí
     * a myší ho nikdo hledat nebude.
     *
     * Pořadí odpovídá průchodu oknem — hlásí se vždy ten nejbližší krok, který chybí.
     */
    const blockReason =
        saving                              ? null
      : !items || items.length === 0        ? "Vyberte doklad a načtěte jeho položky."
      : blocked                             ? "Některý řádek má vyšší množství, než je dostupné — opravte červeně označené pole."
      : Object.keys(selection).length === 0 ? "Zaškrtněte aspoň jednu položku k importu."
      : null;


    // změna v selectu "Typ importu"
    function handleChange(e) {
        const { name, value } = e.target;
        setForm(prev => ({ ...prev, [name]: value }));
    }

    // uživatel vybral doklad v našeptávači -> uložím jeho id do form
    function handleGoodsReceiptSelect(item) {
        setForm(prev => ({ ...prev, id: item?.id }));
    }

    return (
        <Modal show={show} size="modal-lg" title="Import položek zakázky"
               onClose={onCancel} closable={!saving}
               footer={
                   <>
                       {blockReason && (
                           <span className="small text-body-secondary me-auto text-start">
                               <i className="bi bi-info-circle me-1" aria-hidden="true"></i>
                               {blockReason}
                           </span>
                       )}
                       <button type="button" className="btn btn-outline-secondary" onClick={onCancel} disabled={saving}>
                           Zrušit
                       </button>
                       <button type="button" className="btn btn-primary"
                               onClick={() => onImport(selection)}
                               disabled={saving || blockReason !== null}>
                           {saving ? "Importuji…" : "Import položek"}
                       </button>
                   </>
               }>
                    <div>
                        <form className="needs-validation" noValidate>
                            <div className="row mb-3">
                                <div className="col-md-12">
                                    <label className="form-label" htmlFor="importType">
                                        Typ importu <RequiredMark />
                                    </label>
                                    <select id="importType" name="importType" className="form-select"
                                            value={form.importType} onChange={handleChange} required>
                                        <option value="">— Vyberte typ importu —</option>
                                        {ORDER_IMPORT_TYPE_OPTIONS
                                            .filter(opt => opt.value !== "")
                                            .map(opt => <option key={opt.value} value={opt.value}>{opt.label}</option>)}
                                    </select>
                                </div>
                                <div className="col-md-12">
                                    <AutocompletePair
                                        endpoint="/api/v1/warehouse/goods-receipts/autocomplete"
                                        name="goodsReceiptId"
                                        label="Číslo dokladu"
                                        placeholder="Vyberte doklad"
                                        onSelect={handleGoodsReceiptSelect}
                                        appendParams={handleAppendParams}
                                    />
                                </div>
                                <div className="d-flex w-100 col-md-2 justify-content-end text-nowrap mt-3">
                                    <button type="button" className="btn btn-primary"
                                            onClick={() => onSubmit(form)} disabled={saving || !form.id}>
                                        Načíst položky
                                    </button>
                                </div>
                            </div>
                        </form>

                        {/* stavy tabulky */}
                        {loading && <LoadingState label="Načítám položky…" inline />}
                        {error && <div className="alert alert-danger py-2">{error}</div>}
                        {!loading && !error && items?.length > 0 && (
                            <ReceiptItemsTable items={items} selection={selection} onSelectionChange={setSelection} />
                        )}
                    </div>
        </Modal>
    );
}