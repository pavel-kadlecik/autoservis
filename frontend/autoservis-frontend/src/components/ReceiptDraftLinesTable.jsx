import * as React from "react";
import FieldStateBadge from "./FieldStateBadge.jsx";
import StatusBadge from "./StatusBadge.jsx";
import { ALLOWED_UNITS } from "../api/units.js";
import { isLineFieldRequired } from "../api/format.js";

/**
 * Editovatelná tabulka řádků draftu příjemky. ITEM řádky jsou editovatelné,
 * DELIVERY_NOTE_GROUP / NOTE řádky se zobrazují jen informativně (při
 * potvrzení se nematerializují).
 *
 * Sloupec „Skladová karta" ukazuje výsledek párovací kaskády (productMatch):
 * AUTO = napárováno převodníkem, SUGGESTED = uživatel musí vybrat z kandidátů
 * nebo zvolit nový produkt, NONE = založí se nová karta.
 *
 * @param {Array}    lines      - draft.lines
 * @param {boolean}  readOnly
 * @param {Function} onChange(index, fieldName, rawValue)
 * @param {Function} onRemove(index)
 * @param {Function} onMatchChange(index, value) - value = productId (number) nebo "NEW"
 * @param {Function} onKindChange(index, kind) - přepnutí ITEM↔NOTE (neskladový řádek)
 */
export default function ReceiptDraftLinesTable({ lines, readOnly, onChange, onRemove, onMatchChange, onKindChange }) {

    function matchCell(line, index) {
        const match = line.productMatch;
        if (!match || match.state === "NONE") {
            return <StatusBadge tone="secondary" title="Karta se založí při potvrzení">nový produkt</StatusBadge>;
        }
        if (match.state === "AUTO") {
            return <StatusBadge tone="success" title="Napárováno automaticky (převodník dodavatele)">
                napárováno #{match.productId}
            </StatusBadge>;
        }
        if (match.state === "CONFIRMED") {
            return <StatusBadge tone="primary" title="Volba potvrzena">
                {match.productId != null ? `karta #${match.productId}` : "nový produkt"}
            </StatusBadge>;
        }
        // SUGGESTED — bez volby nejde doklad potvrdit
        return (
            <select className="form-select form-select-sm border-warning"
                    value="" disabled={readOnly}
                    onChange={(e) => onMatchChange(index,
                        e.target.value === "NEW" ? "NEW" : Number(e.target.value))}>
                <option value="" disabled>Vyberte kartu…</option>
                {(match.candidates ?? []).map((c) => (
                    <option key={c.productId} value={c.productId}>
                        {c.label}{c.reason === "NAME_SIMILARITY" ? " (dle názvu)" : ""}
                    </option>
                ))}
                <option value="NEW">— založit nový produkt —</option>
            </select>
        );
    }

    // Jednotka je uzavřený číselník (Z-4). AI může extrahovat cokoli, proto
    // hodnotu mimo číselník zobrazíme jako dočasnou volbu — reviewer ji vidí
    // a musí vybrat platnou (server jinak blokuje potvrzení: invalidUnits).
    // Přístupný název editovatelné buňky: „<sloupec> — řádek <n>". Bez něj odečítač u osmi
    // editovatelných polí na řádku nepoznal, do kterého sloupce zapisuje (audit 11-F-16),
    // a hlavičky navíc neměly scope, takže vazba na sloupec nebyla ani odvoditelná.
    const COLUMN_LABELS = {
        catalogNumber: "Katalogové číslo",
        name: "Název",
        unit: "MJ",
        quantity: "Množství",
        unitPriceExclVat: "Cena za MJ bez DPH",
        vatRate: "DPH %",
        totalExclVat: "Celkem bez DPH",
        totalInclVat: "Celkem s DPH",
    };

    function cellLabel(name, index) {
        return `${COLUMN_LABELS[name] ?? name} — řádek ${index + 1}`;
    }

    function unitCell(line, index) {
        const tracked = line.unit ?? { value: null, state: "ABSENT" };
        const current = tracked.value ?? "";
        const outsideCatalog = current && !ALLOWED_UNITS.includes(current);
        // Červeně: hodnota mimo číselník, nebo prázdná jednotka u položky (pro ITEM povinná).
        const invalid = outsideCatalog || (!current && line.lineKind === "ITEM");
        return (
            <td style={{ width: "6.5rem" }}>
                <div className="d-flex align-items-center">
                    <select className={`form-select form-select-sm${invalid ? " is-invalid" : ""}`}
                            aria-label={cellLabel("unit", index)}
                            value={current} disabled={readOnly}
                            onChange={(e) => onChange(index, "unit", e.target.value)}>
                        <option value="" disabled>—</option>
                        {outsideCatalog && <option value={current}>{current} (mimo číselník)</option>}
                        {ALLOWED_UNITS.map((u) => <option key={u} value={u}>{u}</option>)}
                    </select>
                    <FieldStateBadge state={tracked.state} />
                </div>
            </td>
        );
    }

    function cell(line, index, name, type = "text", widthClass = "") {
        const tracked = line[name] ?? { value: null, state: "ABSENT" };
        // Červeně jen povinné a prázdné pole — prázdné nepovinné (není na dokladu) neruší.
        const empty = tracked.value == null || tracked.value === "";
        const invalid = empty && isLineFieldRequired(line, name);
        return (
            <td className={widthClass}>
                <div className="d-flex align-items-center">
                    <input type={type}
                           aria-label={cellLabel(name, index)}
                           className={`form-control form-control-sm${invalid ? " is-invalid" : ""}`}
                           value={tracked.value ?? ""}
                           step={type === "number" ? "0.01" : undefined}
                           disabled={readOnly}
                           onChange={(e) => onChange(index, name, e.target.value)} />
                    <FieldStateBadge state={tracked.state} />
                </div>
            </td>
        );
    }

    return (
        <div className="table-responsive">
            <table className="table table-sm align-middle">
                <thead>
                    <tr>
                        <th scope="col" style={{ width: "3rem" }}>#</th>
                        <th scope="col" style={{ width: "15rem" }}>Katalogové číslo</th>
                        <th scope="col">Název</th>
                        <th scope="col" style={{ width: "6.5rem" }}>MJ</th>
                        <th scope="col" style={{ width: "6rem" }}>Množství</th>
                        <th scope="col" className="text-nowrap" style={{ width: "8rem" }}>Cena/MJ bez DPH</th>
                        <th scope="col" style={{ width: "5rem" }}>DPH %</th>
                        <th scope="col" style={{ width: "8rem" }}>Celkem bez DPH</th>
                        <th scope="col" style={{ width: "8rem" }}>Celkem s DPH</th>
                        <th scope="col" style={{ width: "11rem" }}>Skladová karta</th>
                        {!readOnly && <th scope="col" style={{ width: "5.5rem" }}><span className="visually-hidden">Akce</span></th>}
                    </tr>
                </thead>
                <tbody>
                    {lines.map((line, index) => {
                        if (line.lineKind !== "ITEM") {
                            const isNote = line.lineKind === "NOTE";
                            return (
                                <tr key={index} className="table-secondary">
                                    <td>{line.position ?? "—"}</td>
                                    <td colSpan={readOnly ? 9 : 10} className="small fst-italic">
                                        <div className="d-flex align-items-center justify-content-between gap-2">
                                            <span>
                                                {line.lineKind === "DELIVERY_NOTE_GROUP"
                                                    ? <>Skupinový řádek dodacího listu č. <strong>{line.deliveryNoteNumber}</strong> — nenaskladňuje se</>
                                                    : <>Neskladový řádek: {line.name?.value ?? "—"} — nenaskladní se</>}
                                            </span>
                                            {!readOnly && isNote && (
                                                <button type="button"
                                                        className="btn btn-sm btn-outline-secondary flex-shrink-0"
                                                        title="Zařadit zpět jako skladovou položku"
                                                        onClick={() => onKindChange(index, "ITEM")}>
                                                    <i className="bi bi-box-arrow-in-down me-1" />Zařadit jako položku
                                                </button>
                                            )}
                                        </div>
                                    </td>
                                </tr>
                            );
                        }
                        return (
                            <tr key={index}>
                                <td>{line.position ?? index + 1}</td>
                                {cell(line, index, "catalogNumber")}
                                {cell(line, index, "name")}
                                {unitCell(line, index)}
                                {cell(line, index, "quantity", "number")}
                                {cell(line, index, "unitPriceExclVat", "number")}
                                {cell(line, index, "vatRate", "number")}
                                {cell(line, index, "totalExclVat", "number")}
                                {cell(line, index, "totalInclVat", "number")}
                                <td>{matchCell(line, index)}</td>
                                {!readOnly && (
                                    <td>
                                        <div className="d-flex gap-1">
                                            <button type="button" className="btn btn-sm btn-outline-secondary"
                                                    title="Vyřadit z naskladnění (neskladový řádek — práce, spotřební materiál)"
                                                    onClick={() => onKindChange(index, "NOTE")}>
                                                <i className="bi bi-box-arrow-right" />
                                            </button>
                                            <button type="button" className="btn btn-sm btn-outline-danger"
                                                    title="Odebrat řádek"
                                                    onClick={() => onRemove(index)}>
                                                <i className="bi bi-trash" />
                                            </button>
                                        </div>
                                    </td>
                                )}
                            </tr>
                        );
                    })}
                </tbody>
            </table>
        </div>
    );
}
