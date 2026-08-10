import React, { useRef, useState } from "react";
import { ALLOWED_UNITS } from "../api/units.js";
import PageHeader from "./PageHeader.jsx";
import FormSection from "./FormSection.jsx";
import RequiredMark from "./RequiredMark.jsx";
import {focusFirstInvalid} from "../api/formUtils.js";
import FormActions from "./FormActions.jsx";
import {formatCurrency} from "../api/format.js";

/**
 * Sdílený formulář pro založení i editaci skladového dílu (skladové karty).
 *
 * <p>Číselná pole se před {@code onSave} normalizují (prázdný string → null),
 * takže stránky mohou data formuláře odeslat tak, jak jsou.
 *
 * @param {Object}   initialData      - předvyplněné hodnoty (prázdné stringy při zakládání, data z API při editaci)
 * @param {Function} onSave(formData) - volá se s normalizovaným stavem formuláře při odeslání
 * @param {Function} [onCancel()]     - volá se při kliknutí na tlačítko zpět
 * @param {string}   title            - nadpis stránky
 */
export default function WarehouseForm({ initialData, onSave, onCancel, title }) {

    const [formData, setFormData]   = useState(initialData);
    const [validated, setValidated] = useState(false);
    const warehouseForm             = useRef(null);

    function handleChange(e) {
        const { name, value } = e.target;
        setFormData(prev => ({ ...prev, [name]: value }));
    }

    function handleSave() {
        setValidated(true);
        if (warehouseForm.current.checkValidity()) {
            setValidated(false);
            return onSave({
                ...formData,
                defaultVatRate: formData.defaultVatRate === "" ? null : Number(formData.defaultVatRate),
                salePrice:      formData.salePrice === "" ? null : Number(formData.salePrice),
                minStockLevel:  formData.minStockLevel === "" ? null : Number(formData.minStockLevel),
            });
        } else {
            requestAnimationFrame(() => focusFirstInvalid(warehouseForm));
        }
    }

    return (
        <div>
            <PageHeader title={title} />
            <p className="text-muted small">
                Pole označená <RequiredMark /> jsou povinná.
            </p>
            <form ref={warehouseForm} className={`needs-validation ${validated ? "was-validated" : ""}`} noValidate>

                <FormSection title="Základní údaje">
                    <div className="row mb-3">
                        <div className="col-md-4">
                            <label className="form-label" htmlFor="sku">SKU (katalogové číslo) <RequiredMark /></label>
                            <input type="text" id="sku" name="sku" className="form-control"
                                   value={formData.sku} onChange={handleChange} maxLength={100} required/>
                            <div className="invalid-feedback">Zadejte SKU</div>
                        </div>
                        <div className="col-md-5">
                            <label className="form-label" htmlFor="name">Název dílu <RequiredMark /></label>
                            <input type="text" id="name" name="name" className="form-control"
                                   value={formData.name} onChange={handleChange} maxLength={500} required/>
                            <div className="invalid-feedback">Zadejte název dílu</div>
                        </div>
                        <div className="col-md-3">
                            <label className="form-label" htmlFor="manufacturer">Výrobce</label>
                            <input type="text" id="manufacturer" name="manufacturer" className="form-control"
                                   value={formData.manufacturer} onChange={handleChange} maxLength={255}/>
                        </div>
                        <div className="col-md-4">
                            <label className="form-label" htmlFor="manufacturerPartNumber">Číslo dílu výrobce</label>
                            <input type="text" id="manufacturerPartNumber" name="manufacturerPartNumber"
                                   className="form-control" value={formData.manufacturerPartNumber ?? ""}
                                   onChange={handleChange} maxLength={100}/>
                            <div className="form-text">Párovací identita napříč dodavateli (bez prefixu dodavatele).</div>
                        </div>
                    </div>

                </FormSection>

                <FormSection title="Zařazení">
                    <div className="row mb-3">
                        <div className="col-md-6">
                            <label className="form-label" htmlFor="variant">Varianta / aplikace</label>
                            <input type="text" id="variant" name="variant" className="form-control"
                                   value={formData.variant} onChange={handleChange} maxLength={255}
                                   placeholder="např. 2.0 TDI 2013-2016"/>
                        </div>
                        <div className="col-md-3">
                            <label className="form-label" htmlFor="unit">Měrná jednotka <RequiredMark /></label>
                            <select id="unit" name="unit" className="form-select"
                                    value={formData.unit} onChange={handleChange} required>
                                {formData.unit && !ALLOWED_UNITS.includes(formData.unit) && (
                                    <option value={formData.unit}>{formData.unit} (mimo číselník)</option>
                                )}
                                {ALLOWED_UNITS.map((u) => <option key={u} value={u}>{u}</option>)}
                            </select>
                            <div className="invalid-feedback">Vyberte měrnou jednotku</div>
                        </div>
                        <div className="col-md-3">
                            <label className="form-label" htmlFor="defaultVatRate">
                                DPH <span className="text-muted small">[%]</span>
                            </label>
                            <input type="number" id="defaultVatRate" name="defaultVatRate" className="form-control"
                                   value={formData.defaultVatRate} onChange={handleChange} min="0" max="100"/>
                            <div className="invalid-feedback">DPH musí být v rozsahu 0 - 100 %</div>
                        </div>
                    </div>

                </FormSection>

                <FormSection title="Ceny a sklad">
                    <div className="row mb-3">
                        <div className="col-md-4">
                            <label className="form-label" htmlFor="salePrice">
                                Prodejní cena <span className="text-muted small">[Kč bez DPH]</span>
                            </label>
                            <input type="number" id="salePrice" name="salePrice" className="form-control"
                                   value={formData.salePrice} onChange={handleChange} min="0" step="0.01"/>
                            <div className="invalid-feedback">Prodejní cena nesmí být záporná</div>
                            {formData.salePrice !== "" && formData.defaultVatRate !== "" &&
                             !isNaN(Number(formData.salePrice)) && !isNaN(Number(formData.defaultVatRate)) && (
                                <div className="form-text">
                                    = {formatCurrency(Number(formData.salePrice) * (1 + Number(formData.defaultVatRate) / 100))} s DPH
                                </div>
                            )}
                        </div>
                        <div className="col-md-4">
                            <label className="form-label" htmlFor="minStockLevel">Min. stav (hlídání)</label>
                            <input type="number" id="minStockLevel" name="minStockLevel" className="form-control"
                                   value={formData.minStockLevel} onChange={handleChange} min="0" step="0.001"/>
                            <div className="form-text">Necháte prázdné = dostupnost se nehlídá.</div>
                            <div className="invalid-feedback">Minimální stav nesmí být záporný</div>
                        </div>
                    </div>

                </FormSection>

                <FormSection title="Ostatní">
                    <div className="row mb-4">
                        <div className="col-md-12">
                            <label htmlFor="note" className="form-label">Poznámka</label>
                            <textarea className="form-control" id="note" name="note"
                                      rows="3" value={formData.note} onChange={handleChange} maxLength={500}/>
                        </div>
                    </div>

                </FormSection>

                <FormActions onCancel={onCancel} onSubmit={handleSave} />
            </form>
        </div>
    );
}
