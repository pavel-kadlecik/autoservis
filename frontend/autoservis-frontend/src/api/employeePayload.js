/**
 * Sestaví request payload zaměstnance z formulářových hodnot (vzor customerPayload).
 *
 * Formulář drží všechno jako string; backend čeká čísla a null u nevyplněných
 * volitelných polí. `id` do těla nepatří (je v URL, R-14/N-11).
 *
 * @param {Object} form - stav EmployeeForm
 * @returns {Object} payload pro POST/PUT /employees
 */
export function toEmployeePayload(form) {
    const trimmed = (v) => {
        const s = (v ?? "").trim();
        return s === "" ? null : s;
    };

    return {
        firstName: (form.firstName ?? "").trim(),
        lastName:  (form.lastName ?? "").trim(),
        position:  trimmed(form.position),
        hourlyRate: form.hourlyRate === "" || form.hourlyRate == null
            ? null
            : Number(form.hourlyRate),
        hiredAt: trimmed(form.hiredAt),
        leftAt:  trimmed(form.leftAt),
    };
}
