import * as React from "react";
import Modal from "./Modal.jsx";
import RequiredMark from "./RequiredMark.jsx";

/**
 * Dialog, který se na něco doptá, než akci provede (U6.2).
 *
 * Vznikl proto, že se k tomu zneužíval {@link ConfirmDialog}: do jeho `message`
 * se vkládal `<textarea>`. Takové pole nemělo `required`, značku povinnosti ani
 * fokus a povinnost důvodu se kontrolovala až v obslužné funkci — chybová hláška
 * se pak ukázala **mimo dialog**, na stránce pod ním.
 *
 * `ConfirmDialog` zůstává pro čisté ano/ne.
 *
 * @param {boolean}  show
 * @param {string}   title
 * @param {React.ReactNode} [intro]   - vysvětlení nad poli (co se stane)
 * @param {Array}    fields           - [{name, label, type?: "textarea"|"text"|"number",
 *                                        required?, maxLength?, hint?, rows?}]
 * @param {string}   submitLabel
 * @param {string}   [cancelLabel]
 * @param {Function} onSubmit(values) - dostane objekt {name: hodnota}
 * @param {Function} onCancel
 * @param {boolean}  [saving]
 * @param {string}   [error]          - chyba ze serveru, vypíše se uvnitř dialogu
 */
export default function FormModal({
    show, title, intro, fields = [], submitLabel, cancelLabel = "Zpět",
    onSubmit, onCancel, saving = false, error,
}) {
    const [values, setValues]       = React.useState({});
    const [validated, setValidated] = React.useState(false);
    const formRef = React.useRef(null);

    // Po zavření se hodnoty zahodí — příště se dialog otevře prázdný.
    React.useEffect(() => {
        if (!show) {
            setValues({});
            setValidated(false);
        }
    }, [show]);

    function handleSubmit(e) {
        e?.preventDefault();
        setValidated(true);
        if (!formRef.current?.checkValidity()) {
            formRef.current?.querySelector(":invalid")?.focus();
            return;
        }
        onSubmit(values);
    }

    function handleCancel() {
        setValues({});
        setValidated(false);
        onCancel();
    }

    return (
        <Modal show={show} title={title} onClose={handleCancel} closable={!saving}
               footer={
                   <>
                       <button type="button" className="btn btn-outline-secondary"
                               onClick={handleCancel} disabled={saving}>
                           {cancelLabel}
                       </button>
                       <button type="button" className="btn btn-primary"
                               onClick={handleSubmit} disabled={saving}>
                           {saving ? "Pracuji…" : submitLabel}
                       </button>
                   </>
               }>
            {/* Enter v poli odešle dialog, ať se nemusí sahat na myš. */}
            <form ref={formRef} onSubmit={handleSubmit}
                  className={`needs-validation ${validated ? "was-validated" : ""}`} noValidate>

                {error && <div className="alert alert-danger py-2">{error}</div>}

                {intro}

                {fields.map(field => {
                    const id = `formModal-${field.name}`;
                    const common = {
                        id,
                        name: field.name,
                        className: "form-control",
                        value: values[field.name] ?? "",
                        maxLength: field.maxLength,
                        required: field.required,
                        onChange: e => setValues(v => ({ ...v, [field.name]: e.target.value })),
                    };
                    return (
                        <div className="mb-2" key={field.name}>
                            <label className="form-label" htmlFor={id}>
                                {field.label}{field.required && <> <RequiredMark /></>}
                            </label>
                            {field.type === "textarea"
                                ? <textarea {...common} rows={field.rows ?? 3} />
                                : <input type={field.type ?? "text"} {...common} />}
                            {field.hint && <div className="form-text">{field.hint}</div>}
                            {field.required && (
                                <div className="invalid-feedback">
                                    {field.requiredMessage ?? "Vyplňte prosím toto pole."}
                                </div>
                            )}
                        </div>
                    );
                })}
            </form>
        </Modal>
    );
}
