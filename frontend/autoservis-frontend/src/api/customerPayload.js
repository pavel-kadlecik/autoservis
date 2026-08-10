/**
 * Sdílený tvar formulářového stavu zákazníka a jeho převod na tvar API.
 *
 * Formulář (CustomerForm) pracuje se dvěma pojmenovanými adresami + přepínačem,
 * API očekává plochý seznam adres s typem. Create i edit musí používat tentýž
 * převod, jinak se logika obou stránek rozejde (viz U0.1).
 */

/** Prázdná adresa; countryCode předvyplněné, drtivá většina zákazníků je z ČR. */
export const EMPTY_ADDRESS = {
    street:       "",
    streetNumber: "",
    city:         "",
    postalCode:   "",
    countryCode:  "CZ",
};

/**
 * Doplní do formulářového stavu adresní klíče, které CustomerForm čte bez ochrany.
 * Volá se na initialData v obou režimech — brání pádu při neúplném vstupu.
 *
 * @param {Object} formData - stav formuláře (může adresní klíče postrádat)
 * @returns {Object} stav s garantovanými klíči billingAddress/contactAddress/hasSeparateContact
 */
export function withAddressState(formData) {
    return {
        ...formData,
        billingAddress:     { ...EMPTY_ADDRESS, ...(formData.billingAddress ?? {}) },
        contactAddress:     { ...EMPTY_ADDRESS, ...(formData.contactAddress ?? {}) },
        hasSeparateContact: formData.hasSeparateContact ?? false,
    };
}

/**
 * Rozloží seznam adres z API (`DetailResponse.addresses`) na formulářový tvar.
 * Používá se pro předvyplnění formuláře v edit režimu; adresy jdou od TD-42
 * i upravit (PUT /customers/{id} je přijímá, full-replace).
 *
 * @param {Array} addresses - pole adres z API, může být undefined
 * @returns {{billingAddress: Object, contactAddress: Object, hasSeparateContact: boolean}}
 */
export function splitAddresses(addresses) {
    const list    = addresses ?? [];
    const billing = list.find(a => a.addressType === "BILLING");
    const contact = list.find(a => a.addressType === "CONTACT");

    return {
        billingAddress:     { ...EMPTY_ADDRESS, ...pickAddressFields(billing) },
        contactAddress:     { ...EMPTY_ADDRESS, ...pickAddressFields(contact) },
        hasSeparateContact: Boolean(contact),
    };
}

/** Vybere z adresy API jen pole, se kterými pracuje formulář (bez id, isDefault, addressType). */
function pickAddressFields(address) {
    if (!address) return {};
    return {
        street:       address.street       ?? "",
        streetNumber: address.streetNumber ?? "",
        city:         address.city         ?? "",
        postalCode:   address.postalCode   ?? "",
        countryCode:  address.countryCode  ?? "CZ",
    };
}

/**
 * Převede formulářový stav na tělo POST /customers.
 * UI-only pole (billingAddress, contactAddress, hasSeparateContact) se vyjmou
 * a nahradí plochým seznamem `addresses` s typem, jak ho očekává CreateRequest.
 *
 * @param {Object} formData - stav formuláře
 * @returns {Object} tělo requestu pro POST /customers
 */
export function toCreatePayload(formData) {
    return withFlatAddresses(formData);
}

/**
 * Převede formulářový stav na tělo PUT /customers/{id}.
 * Od TD-42 nese i `addresses` (full-replace, stejný tvar jako create) — server
 * starou adresní sadu nahradí touto. UI-only pole se vyjmou.
 *
 * @param {Object} formData - stav formuláře
 * @returns {Object} tělo requestu pro PUT /customers/{id}
 */
export function toUpdatePayload(formData) {
    return withFlatAddresses(formData);
}

/** Vyjme UI-only adresní klíče a nahradí je plochým seznamem `addresses` s typem. */
function withFlatAddresses(formData) {
    const { billingAddress, contactAddress, hasSeparateContact, ...customer } = formData;

    const addresses = [{ ...billingAddress, addressType: "BILLING" }];
    if (hasSeparateContact) {
        addresses.push({ ...contactAddress, addressType: "CONTACT" });
    }

    return { ...customer, addresses };
}
