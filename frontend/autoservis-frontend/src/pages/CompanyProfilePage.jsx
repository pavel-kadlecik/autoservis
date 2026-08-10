import { useEffect, useRef, useState } from "react";
import * as React from "react";
import { api, problemMessage } from "../api/api.js";
import LoadingState from "../components/LoadingState.jsx";
import PageHeader from "../components/PageHeader.jsx";
import FormSection from "../components/FormSection.jsx";
import FormActions from "../components/FormActions.jsx";
import RequiredMark from "../components/RequiredMark.jsx";
import { useAlert } from "../context/AlertContext.jsx";
import { useNavigate } from "react-router-dom";
import { focusFirstInvalid } from "../api/formUtils.js";
import ErrorState from "../components/ErrorState.jsx";

/**
 * Stránka „Fakturační údaje" (identita firmy/dodavatele + nastavení číslování faktur).
 * Identita a bankovní spojení se zmrazují na každou vystavenou fakturu; maska
 * číselné řady řídí předvyplňování čísla faktury v dialogu vytvoření.
 */

/**
 * Klientský náhled čísla podle masky — zrcadlí backendový InvoiceNumberMask
 * (tokeny {RRRR} {RR} {MM} {N…}, právě jedna sekvence, výsledek do 20 znaků).
 * Jen pro okamžitou odezvu v UI; závazná validace běží na serveru při uložení.
 *
 * @param {string} mask
 * @returns {{ ok: boolean, text: string }} náhled, nebo česká chyba parseru
 */
export function maskPreview(mask) {
    if (!mask || !mask.trim()) {
        return { ok: false, text: "Maska číselné řady nesmí být prázdná." };
    }
    const source = mask.trim();
    const now = new Date();
    let out = "";
    let seqTokens = 0;
    for (let i = 0; i < source.length; i++) {
        const c = source[i];
        if (c === "}") return { ok: false, text: "Maska obsahuje „}“ bez otevírací závorky." };
        if (c !== "{") {
            out += c;
            continue;
        }
        const end = source.indexOf("}", i);
        if (end < 0) return { ok: false, text: "Maska obsahuje neuzavřenou závorku „{“." };
        const token = source.slice(i + 1, end);
        if (token === "RRRR") {
            out += String(now.getFullYear());
        } else if (token === "RR") {
            out += String(now.getFullYear() % 100).padStart(2, "0");
        } else if (token === "MM") {
            out += String(now.getMonth() + 1).padStart(2, "0");
        } else if (/^N+$/.test(token)) {
            out += "1".padStart(token.length, "0");
            seqTokens += 1;
        } else {
            return { ok: false, text: `Neznámý token {${token}} — povolené jsou {RRRR}, {RR}, {MM} a {N}, {NN}, {NNN}…` };
        }
        i = end;
    }
    if (seqTokens !== 1) {
        return { ok: false, text: "Maska musí obsahovat právě jeden token pořadového čísla {N}, {NN}, {NNN}…" };
    }
    if (out.length > 20) {
        return { ok: false, text: `Číslo podle této masky by mělo ${out.length} znaků — maximum je 20.` };
    }
    return { ok: true, text: out };
}

/** Legenda tokenů masky — data pro tabulku v sekci Číslování faktur. */
const MASK_TOKENS = [
    { token: "{RRRR}", meaning: "rok, 4 číslice (z data vystavení)", example: "2026" },
    { token: "{RR}",   meaning: "rok, poslední 2 číslice",           example: "26" },
    { token: "{MM}",   meaning: "měsíc, 2 číslice",                  example: "08" },
    { token: "{N}, {NN}, {NNN}…", meaning: "pořadové číslo — počet N určuje šířku doplněnou nulami", example: "1 / 01 / 001" },
];

export default function CompanyProfilePage() {

    const [form, setForm]     = useState(null);
    // Bez ošetření chyby zůstal detail navždy na spinneru „Načítám…" (KN-14) —
    // třeba u zastaralého odkazu na smazaný záznam (404).
    const [loadError, setLoadError] = useState("");
    const [saving, setSaving] = useState(false);
    // Stránka do teď nebyla <form>, takže hvězdička u povinného názvu firmy nic nevalidovala
    // (audit 11-F-15) — a přitom se tyhle údaje zmrazují na každou vystavenou fakturu.
    const [validated, setValidated] = useState(false);
    const profileForm = useRef(null);
    const { addAlert } = useAlert();
    const navigate = useNavigate();

    useEffect(() => {
        async function loadProfile() {
            try {
                const data = await api.get("/invoices/company-profile");
                setForm(data);
                setLoadError("");
            } catch (err) {
                setLoadError(problemMessage(err, "Profil firmy se nepodařilo načíst."));
            }
        }
        loadProfile();
    }, []);

    function handleChange(event) {
        const { name, value, type, checked } = event.target;
        setForm(prev => ({ ...prev, [name]: type === "checkbox" ? checked : value }));
    }

    async function handleSave() {
        setValidated(true);
        if (!profileForm.current.checkValidity()) {
            requestAnimationFrame(() => focusFirstInvalid(profileForm));
            return;
        }
        setValidated(false);
        setSaving(true);
        try {
            const updated = await api.put("/invoices/company-profile", {
                name:         form.name,
                ico:          form.ico          || null,
                dic:          form.dic          || null,
                street:       form.street       || null,
                streetNumber: form.streetNumber || null,
                city:         form.city         || null,
                postalCode:   form.postalCode   || null,
                countryCode:  form.countryCode  || null,
                bankAccount:  form.bankAccount  || null,
                iban:         form.iban         || null,
                swift:        form.swift        || null,
                invoiceNumberAuto: !!form.invoiceNumberAuto,
                invoiceNumberMask: form.invoiceNumberMask || null,
                invoiceGapCheckEnabled: !!form.invoiceGapCheckEnabled,
                invoiceGapCheckFrom: form.invoiceGapCheckFrom?.trim() || null,
                cashReceiptNumberSource: form.cashReceiptNumberSource || 'MASK',
                cashReceiptNumberMask: form.cashReceiptNumberMask || null,
                cashReceiptGapCheckEnabled: !!form.cashReceiptGapCheckEnabled,
                cashReceiptGapCheckFrom: form.cashReceiptGapCheckFrom?.trim() || null,
            });
            setForm(updated);
            addAlert("Údaje firmy byly uloženy.", "success");
        } catch (err) {
            // Neúspěch uložení je výsledek akce, ne stav obrazovky → toast (§10.6).
            addAlert(problemMessage(err, "Údaje firmy se nepodařilo uložit."), "danger");
        } finally {
            setSaving(false);
        }
    }

    if (!form && !loadError) return <LoadingState />;
    if (!form) {
        return <ErrorState message={loadError} backTo="/dashboard" backLabel="Zpět na přehled" />;
    }

    return (
        <div>
            <PageHeader
                title="Fakturační údaje"
                subtitle="Tyto údaje se na fakturách objevují jako dodavatel a zmrazí se na každou vystavenou fakturu."
            />

            <p className="text-muted small">Pole označená <RequiredMark /> jsou povinná.</p>

            <form ref={profileForm}
                  className={`needs-validation ${validated ? "was-validated" : ""}`}
                  noValidate>

            <FormSection title="Identita">
                    <div className="row g-3">
                        <div className="col-12">
                            <label className="form-label" htmlFor="name">
                                Název firmy <RequiredMark />
                            </label>
                            <input id="name" name="name" className="form-control"
                                   value={form.name ?? ""} onChange={handleChange} maxLength={255}
                                   required />
                            <div className="invalid-feedback">
                                Název firmy je povinný — je to jméno dodavatele na fakturách.
                            </div>
                        </div>
                        <div className="col-md-6">
                            <label className="form-label" htmlFor="ico">IČO</label>
                            <input id="ico" name="ico" className="form-control"
                                   value={form.ico ?? ""} onChange={handleChange} maxLength={15} />
                        </div>
                        <div className="col-md-6">
                            <label className="form-label" htmlFor="dic">DIČ</label>
                            <input id="dic" name="dic" className="form-control"
                                   value={form.dic ?? ""} onChange={handleChange} maxLength={15} />
                        </div>
                    </div>
            </FormSection>

            <FormSection title="Adresa">
                    <div className="row g-3">
                        <div className="col-md-8">
                            <label className="form-label" htmlFor="street">Ulice</label>
                            <input id="street" name="street" className="form-control"
                                   value={form.street ?? ""} onChange={handleChange} maxLength={255} />
                        </div>
                        <div className="col-md-4">
                            <label className="form-label" htmlFor="streetNumber">Číslo</label>
                            <input id="streetNumber" name="streetNumber" className="form-control"
                                   value={form.streetNumber ?? ""} onChange={handleChange} maxLength={20} />
                        </div>
                        <div className="col-md-6">
                            <label className="form-label" htmlFor="city">Město</label>
                            <input id="city" name="city" className="form-control"
                                   value={form.city ?? ""} onChange={handleChange} maxLength={100} />
                        </div>
                        <div className="col-md-3">
                            <label className="form-label" htmlFor="postalCode">PSČ</label>
                            <input id="postalCode" name="postalCode" className="form-control"
                                   value={form.postalCode ?? ""} onChange={handleChange} maxLength={10} />
                        </div>
                        <div className="col-md-3">
                            <label className="form-label" htmlFor="countryCode">Kód země</label>
                            <input id="countryCode" name="countryCode" className="form-control"
                                   value={form.countryCode ?? ""} onChange={handleChange} maxLength={2}
                                   placeholder="CZ" />
                        </div>
                    </div>
            </FormSection>

            <FormSection title="Bankovní spojení">
                    <div className="row g-3">
                        <div className="col-md-4">
                            <label className="form-label" htmlFor="bankAccount">Číslo účtu</label>
                            <input id="bankAccount" name="bankAccount" className="form-control"
                                   value={form.bankAccount ?? ""} onChange={handleChange} maxLength={34}
                                   placeholder="123456789/0800" />
                        </div>
                        <div className="col-md-5">
                            <label className="form-label" htmlFor="iban">IBAN</label>
                            <input id="iban" name="iban" className="form-control"
                                   value={form.iban ?? ""} onChange={handleChange} maxLength={34}
                                   placeholder="pro zahraniční platby" />
                        </div>
                        <div className="col-md-3">
                            <label className="form-label" htmlFor="swift">SWIFT/BIC</label>
                            <input id="swift" name="swift" className="form-control"
                                   value={form.swift ?? ""} onChange={handleChange} maxLength={11} />
                        </div>
                    </div>
            </FormSection>

            <FormSection title="Číslování faktur">
                    <NumberingFields
                        form={form} onChange={handleChange}
                        autoName="invoiceNumberAuto" maskName="invoiceNumberMask"
                        gapEnabledName="invoiceGapCheckEnabled" gapFromName="invoiceGapCheckFrom"
                        autoLabel="Generovat číslo faktury podle masky"
                        autoHelp={<>
                            <strong>Zapnuto:</strong> při vytváření faktury se číslo předvyplní podle
                            masky a navazuje na číselnou řadu. <strong>Vypnuto:</strong> pole čísla
                            zůstane prázdné. V obou režimech lze před uložením zapsat libovolné číslo —
                            maska je jen předpis pro generování návrhu; hlídá se unikátnost a délka
                            do 20 znaků.
                        </>}
                        /* Hlídání mezer (V89). Smazat lze nepředanou fakturu; u poslední v řadě
                           se číslo uvolní, u starší po ní zůstane díra a `MAX+1` ji sám nezavře.
                           Zavírá se ručně — číslo je při vystavení editovatelné. */
                        gapHelp={<>
                            Nad seznamem faktur se objeví upozornění, když v řadě chybí číslo —
                            typicky po smazání faktury, kterou zákazník nedostal. Hlídá se
                            <strong> aktuální období</strong> podle masky výše (měsíc, nebo rok)
                            a jen čísla, která masce odpovídají. Mezeru zavřete tím, že příští
                            fakturu vystavíte s chybějícím číslem místo navrženého.
                        </>}
                        gapPlaceholder="např. 202608004"
                    />

                    <div className="table-responsive mt-3">
                        <table className="table table-sm table-bordered w-auto mb-1">
                            <caption className="visually-hidden">Legenda tokenů masky číselné řady</caption>
                            <thead>
                                <tr>
                                    <th scope="col">Token</th>
                                    <th scope="col">Význam</th>
                                    <th scope="col">Příklad</th>
                                </tr>
                            </thead>
                            <tbody>
                                {MASK_TOKENS.map(row => (
                                    <tr key={row.token}>
                                        <td className="font-monospace">{row.token}</td>
                                        <td>{row.meaning}</td>
                                        <td className="font-monospace">{row.example}</td>
                                    </tr>
                                ))}
                            </tbody>
                        </table>
                    </div>
                    <div className="form-text">
                        Vše mimo tokeny jsou pevné znaky (lomítko, pomlčka, písmena…). Obsahuje-li
                        maska <span className="font-monospace">{"{MM}"}</span>, řada začíná od 1 každý
                        měsíc; jinak s rokem každý rok; bez roku pokračuje bez konce. Příklad: maska{" "}
                        <span className="font-monospace">{"{N}/{RR}"}</span> dává čísla{" "}
                        <span className="font-monospace">17/26</span>, dnešní výchozí{" "}
                        <span className="font-monospace">{"{RRRR}{MM}{NNN}"}</span> dává{" "}
                        <span className="font-monospace">202608001</span>.
                    </div>
            </FormSection>

            {/* Řada PPD (V92/V93) — mechanismus jako u faktur, navíc režim „podle čísla faktury". */}
            <FormSection title="Číslování pokladních dokladů">
                    <NumberingFields
                        form={form} onChange={handleChange}
                        sourceName="cashReceiptNumberSource" maskName="cashReceiptNumberMask"
                        gapEnabledName="cashReceiptGapCheckEnabled" gapFromName="cashReceiptGapCheckFrom"
                        autoLabel="Zdroj čísla pokladního dokladu"
                        autoHelp={<>
                            <strong>Podle masky:</strong> číslo se předvyplní z vlastní řady PPD.{" "}
                            <strong>Podle čísla faktury:</strong> předvyplní se číslo hrazené faktury —
                            účetní pak páruje platbu s fakturou na první pohled.{" "}
                            <strong>Ručně:</strong> pole zůstane prázdné. Ve všech režimech lze zapsat
                            libovolné číslo — hlídá se unikátnost a délka do 20 znaků.
                        </>}
                        /* Díra vzniká smazáním dokladu (V92) a MAX+1 ji sám nezavře —
                           zavírá se ručním zápisem chybějícího čísla při dalším vystavení.
                           V režimu „podle faktury" se blok skrývá (V93): díry v řadě PPD jsou
                           tam faktury zaplacené převodem a řadu hlídá kontrola mezer faktur. */
                        gapHelp={<>
                            Dialog vystavení pokladního dokladu upozorní, když v řadě chybí číslo —
                            typicky po smazání dokladu uprostřed řady. Hlídá se
                            <strong> aktuální období</strong> podle masky výše. Mezeru zavřete tím,
                            že příští doklad vystavíte s chybějícím číslem místo navrženého.
                        </>}
                        gapPlaceholder="např. PPD202608004"
                    />
                    <div className="form-text mt-3">
                        Tokeny masky jsou stejné jako u faktur — viz legenda výše. Výchozí maska{" "}
                        <span className="font-monospace">{"PPD{RRRR}{MM}{NNN}"}</span> dává{" "}
                        <span className="font-monospace">PPD202608001</span> a navazuje na historickou řadu.
                    </div>
            </FormSection>

            <FormActions onCancel={() => navigate(-1)} onSubmit={handleSave} saving={saving} />
            </form>
        </div>
    );
}

/* ── Sub-komponenta: nastavení jedné číselné řady ──────────────────────
   Faktury (V71/V89) a pokladní doklady (V92/V93) mají identický mechanismus —
   maska s náhledem, hlídání mezer. Liší se ovládáním zdroje čísla: faktury mají
   boolean přepínač (`autoName`), PPD tříhodnotový výběr (`sourceName` —
   MASK/INVOICE/MANUAL). Duplikovat celý blok by znamenalo udržovat ho dvakrát. */
function NumberingFields({ form, onChange, autoName, sourceName, maskName,
                           gapEnabledName, gapFromName,
                           autoLabel, autoHelp, gapHelp, gapPlaceholder }) {
    // Sjednocení obou ovládání na dvě otázky: skládá se číslo podle masky?
    // A má smysl ukazovat hlídání mezer? (V režimu INVOICE nemá — díry v řadě
    // PPD jsou faktury zaplacené převodem, řadu hlídá kontrola mezer faktur.)
    const maskActive = sourceName ? form[sourceName] === 'MASK' : !!form[autoName];
    const gapVisible = !sourceName || form[sourceName] !== 'INVOICE';
    return (
        <>
            {sourceName ? (
                <div className="mb-1" style={{ maxWidth: '22rem' }}>
                    <label className="form-label" htmlFor={sourceName}>{autoLabel}</label>
                    <select id={sourceName} name={sourceName} className="form-select"
                            value={form[sourceName] ?? 'MASK'} onChange={onChange}>
                        <option value="MASK">Podle masky (vlastní řada)</option>
                        <option value="INVOICE">Podle čísla faktury</option>
                        <option value="MANUAL">Ručně</option>
                    </select>
                </div>
            ) : (
                <div className="form-check form-switch mb-1">
                    <input className="form-check-input" type="checkbox" role="switch"
                           id={autoName} name={autoName}
                           checked={!!form[autoName]} onChange={onChange} />
                    <label className="form-check-label" htmlFor={autoName}>{autoLabel}</label>
                </div>
            )}
            <div className="form-text mb-3">{autoHelp}</div>

            <div className="row g-3">
                <div className="col-md-5">
                    <label className="form-label" htmlFor={maskName}>
                        Maska číselné řady <RequiredMark />
                    </label>
                    <input id={maskName} name={maskName}
                           className="form-control font-monospace"
                           value={form[maskName] ?? ""} onChange={onChange}
                           maxLength={40} disabled={!maskActive} required />
                    <div className="invalid-feedback">Maska číselné řady je povinná.</div>
                </div>
                <div className="col-md-7">
                    <label className="form-label">Náhled čísla podle masky</label>
                    {(() => {
                        if (!maskActive) {
                            return (
                                <div className="form-control-plaintext text-muted">
                                    {sourceName && form[sourceName] === 'INVOICE'
                                        ? "Číslo se přebírá z hrazené faktury."
                                        : "Automatické číslování je vypnuté — číslo se zadává ručně."}
                                </div>
                            );
                        }
                        const preview = maskPreview(form[maskName]);
                        return preview.ok
                            ? <div className="form-control-plaintext font-monospace">{preview.text}</div>
                            : <div className="form-control-plaintext text-danger">{preview.text}</div>;
                    })()}
                </div>
            </div>

            {gapVisible ? (
                <>
                    <hr className="my-4" />

                    <div className="form-check form-switch mb-1">
                        <input className="form-check-input" type="checkbox" role="switch"
                               id={gapEnabledName} name={gapEnabledName}
                               checked={!!form[gapEnabledName]} onChange={onChange} />
                        <label className="form-check-label" htmlFor={gapEnabledName}>
                            Hlídat mezery v číselné řadě
                        </label>
                    </div>
                    <div className="form-text mb-3">{gapHelp}</div>

                    <div className="row g-3">
                        <div className="col-md-5">
                            <label className="form-label" htmlFor={gapFromName}>
                                Hlídat od čísla
                            </label>
                            <input id={gapFromName} name={gapFromName}
                                   className="form-control font-monospace"
                                   value={form[gapFromName] ?? ""} onChange={onChange}
                                   maxLength={20} disabled={!form[gapEnabledName]}
                                   placeholder={gapPlaceholder} />
                            <div className="form-text">
                                Nepovinné. Starší čísla se ignorují — hodí se, když jste doklady
                                přenesli z jiného systému a jejich řada nenavazuje.
                            </div>
                        </div>
                    </div>
                </>
            ) : (
                <div className="form-text mt-3">
                    Hlídání mezer je v režimu „podle čísla faktury" vypnuté — hotově se platí jen
                    některé faktury, takže mezery v řadě dokladů nejsou chyba. Souvislost řady
                    hlídá kontrola mezer u <strong>číslování faktur</strong> výše.
                </div>
            )}
        </>
    );
}
