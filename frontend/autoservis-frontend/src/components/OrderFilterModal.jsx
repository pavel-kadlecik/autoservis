import React, { useEffect, useState } from "react";
import Modal from "./Modal.jsx";
import { ORDER_STATUS_OPTIONS, getOrderStatusLabel } from "../api/format.js";

/**
 * Filtr zakázek — zaškrtávací pole stavů a přepínač „Po termínu".
 *
 * Proč zaškrtávátka místo rozbalovacího seznamu: běžný dotaz obsluhy zní „ukaž mi
 * rozpracované", což je pět stavů zároveň. Select uměl vybrat jen jeden.
 *
 * Rozpracované se pracuje s KOPIÍ nastavení, aby zavření křížkem nebo Escapem filtr
 * nezměnilo — použije se teprve tlačítkem „Použít".
 */
export default function OrderFilterModal({ show, statuses, overdue, onApply, onClose }) {
    const [draftStatuses, setDraftStatuses] = useState(statuses);
    const [draftOverdue, setDraftOverdue] = useState(overdue);

    // Při každém otevření se rozpracovaná kopie srovná se skutečně platným filtrem —
    // jinak by v dialogu zůstaly škrtance z minula, které uživatel zahodil.
    useEffect(() => {
        if (show) {
            setDraftStatuses(statuses);
            setDraftOverdue(overdue);
        }
    }, [show, statuses, overdue]);

    function toggleStatus(value) {
        setDraftStatuses(prev =>
            prev.includes(value) ? prev.filter(s => s !== value) : [...prev, value]);
    }

    const allValues = ORDER_STATUS_OPTIONS.map(o => o.value);
    const allChecked = allValues.every(v => draftStatuses.includes(v));

    return (
        <Modal
            show={show}
            title="Filtr zakázek"
            onClose={onClose}
            footer={
                <>
                    <button type="button" className="btn btn-outline-secondary" onClick={onClose}>
                        Zrušit
                    </button>
                    <button type="button" className="btn btn-primary"
                            onClick={() => onApply(draftStatuses, draftOverdue)}>
                        Použít
                    </button>
                </>
            }
        >
            <fieldset className="mb-3">
                <legend className="form-label fw-semibold fs-6">Stav zakázky</legend>

                <div className="form-check mb-2 pb-2 border-bottom">
                    <input className="form-check-input" type="checkbox" id="filter-status-all"
                           checked={allChecked}
                           onChange={() => setDraftStatuses(allChecked ? [] : allValues)}/>
                    <label className="form-check-label fw-semibold" htmlFor="filter-status-all">
                        Všechny stavy
                    </label>
                </div>

                {ORDER_STATUS_OPTIONS.map(option => (
                    <div className="form-check" key={option.value}>
                        <input className="form-check-input" type="checkbox"
                               id={`filter-status-${option.value}`}
                               checked={draftStatuses.includes(option.value)}
                               onChange={() => toggleStatus(option.value)}/>
                        <label className="form-check-label" htmlFor={`filter-status-${option.value}`}>
                            {getOrderStatusLabel(option.value)}
                        </label>
                    </div>
                ))}

                <div className="form-text mt-2">
                    Nezaškrtnutý žádný stav znamená totéž co všechny — seznam se pak podle stavu nefiltruje.
                </div>
            </fieldset>

            <fieldset>
                <legend className="form-label fw-semibold fs-6">Termín</legend>
                <div className="form-check">
                    <input className="form-check-input" type="checkbox" id="filter-overdue"
                           checked={draftOverdue}
                           onChange={e => setDraftOverdue(e.target.checked)}/>
                    <label className="form-check-label" htmlFor="filter-overdue">
                        Jen po termínu
                    </label>
                </div>
                <div className="form-text mt-2">
                    Nedokončené zakázky, kterým už uplynul slíbený termín dokončení.
                </div>
            </fieldset>
        </Modal>
    );
}
