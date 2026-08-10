/**
 * Pomocníci pro formuláře (U5.2).
 *
 * Bootstrap `was-validated` chybu obarví, ale nic víc — na dlouhém formuláři
 * uživatel po neúspěšném uložení kouká na nezměněnou obrazovku a neví, kam se
 * dívat. První chybné pole je přitom často mimo viditelnou část stránky.
 */

/**
 * Odscrolluje na první neplatné pole formuláře a zaostří ho.
 *
 * `focus()` sám o sobě nestačí: u `type="date"` a `select` prohlížeč scrolluje
 * jen tak, aby bylo pole těsně v obraze, takže skončí nalepené u hrany.
 * Proto nejdřív `scrollIntoView({block: "center"})` a teprve pak fokus
 * (`preventScroll`, ať ho fokus nepřescrolluje zpátky).
 *
 * @param {React.RefObject<HTMLFormElement>} formRef
 * @returns {boolean} true, když se nějaké neplatné pole našlo
 */
export function focusFirstInvalid(formRef) {
    const form = formRef?.current;
    if (!form) return false;

    const invalid = form.querySelector(":invalid, .is-invalid");
    if (!invalid) return false;

    invalid.scrollIntoView({ behavior: "smooth", block: "center" });
    invalid.focus({ preventScroll: true });
    return true;
}
