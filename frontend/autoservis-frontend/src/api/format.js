const EMPTY_VALUE = "—";

/** Popisek volby „nic nevybráno" v <select> dropdownech nad volitelnými enumy. */
const EMPTY_OPTION_LABEL = "— nevyplněno —";

const currencyFormatter = new Intl.NumberFormat("cs-CZ", {
    style: "currency",
    currency: "CZK",
    minimumFractionDigits: 2,
});

/**
 * Přepočte částku bez DPH na částku s DPH.
 *
 * Sdílené schválně: cena s DPH se ukazuje v obou tabulkách položek a dvě kopie vzorce
 * by se lišily v zaokrouhlování, takže by u téže položky vyšlo na detailu jiné číslo
 * než v editaci.
 *
 * Řádkový součet se počítá z **částky bez DPH za celý řádek**, ne z jednotkové ceny
 * s DPH — násobit už zaokrouhlené číslo množstvím nasčítá haléřovou odchylku a řádek
 * by se rozešel s fakturou, která DPH počítá taky nad řádkem.
 *
 * @param {number|string|null} net       částka bez DPH
 * @param {number|string|null} vatRate   sazba v procentech (21 = 21 %)
 * @returns {number|null}
 */
export function withVat(net, vatRate) {
    if (net === null || net === undefined || net === "") return null;
    return Number(net) * (1 + Number(vatRate ?? 0) / 100);
}

/**
 * Naformátuje číslo jako českou měnu, např. 1234.5 → "1 234,50 Kč".
 * @param {number|null} value
 * @returns {string}
 */
export function formatCurrency(value) {
    if (value == null) return EMPTY_VALUE;
    return currencyFormatter.format(value);
}

/**
 * Naformátuje skladové množství (BigDecimal z API) pro zobrazení.
 * Sjednocuje čtyři totožné lokální kopie (ProductTable, WarehousePageDetail,
 * StockTakePageDetail, LowStockPage) — U8.1.
 *
 * @param {number|string|null} value
 * @param {string} [unit] - měrná jednotka; připojí se za číslo
 * @returns {string}
 */
export function formatQuantity(value, unit) {
    if (value == null) return EMPTY_VALUE;
    const formatted = Number(value).toLocaleString("cs-CZ");
    return unit ? `${formatted} ${unit}` : formatted;
}

/**
 * Naformátuje prosté číslo v českém locale (oddělovač tisíců).
 * Pro veličiny, které nejsou peníze ani skladové množství — typicky kilometry.
 *
 * @param {number|null} value
 * @returns {string}
 */
export function formatNumber(value) {
    if (value == null) return EMPTY_VALUE;
    return Number(value).toLocaleString("cs-CZ");
}

/**
 * Popisek vozidla ve tvaru „Značka Model - SPZ".
 *
 * <p>Zrcadlí `CONCAT_WS` z `VehicleMapper.xml` (`autocomplete`), aby pole našeptávače vypadalo
 * v editaci stejně jako po výběru z nabídky. Předvyplňovat ho jen SPZ znamenalo, že se text
 * po prvním výběru změnil, přestože šlo o totéž auto.
 *
 * <p>Prázdné části vypadnou: auto bez SPZ (sloupec je nullable) dá „Škoda Octavia", ne
 * „Škoda Octavia - ".
 *
 * @param {{vehicleBrand?: string, vehicleModel?: string, vehicleLicensePlate?: string}|null} vehicle
 * @returns {string} popisek, nebo prázdný řetězec když není z čeho skládat
 */
export function vehicleLabel(vehicle) {
    if (!vehicle) return "";
    const brandModel = [vehicle.vehicleBrand, vehicle.vehicleModel].filter(Boolean).join(" ");
    return [brandModel, vehicle.vehicleLicensePlate].filter(Boolean).join(" - ");
}

/**
 * Vrátí Bootstrap třídu barvy textu podle toho, jak blízko dnešku je
 * odhadované datum dokončení.
 *
 * @param {string|null} date - ISO datum jako řetězec
 * @returns {string} Bootstrap utility třída, nebo prázdný řetězec když datum chybí nebo je v budoucnu
 */
export function getEstimateDateColor(date) {
    if (!date) return "";

    const today = new Date();
    const estimated = new Date(date);

    today.setHours(0, 0, 0, 0);
    estimated.setHours(0, 0, 0, 0);

    if (estimated.getTime() === today.getTime()) return "text-warning";
    if (estimated < today) return "text-danger";
    return "";
}

// ============================================================================
// Kontaktní kanál
// ============================================================================

const CONTACT_CHANNEL_LABELS = {
    EMAIL:  "Email",
    PHONE:  "Telefon",
    SMS:    "SMS",
    PORTAL: "Portál"
};

/** Pole options pro <select> dropdowny kontaktního kanálu. */
export const CONTACT_CHANNEL_OPTIONS = Object.entries(CONTACT_CHANNEL_LABELS)
    .map(([value, label]) => ({value, label}));

/**
 * Vrátí zobrazovaný popisek pro hodnotu enumu kontaktního kanálu.
 * @param {string} channel
 * @returns {string}
 */
export function getChannelLabel(channel) {
    return channel ? CONTACT_CHANNEL_LABELS[channel] : EMPTY_VALUE;
}

// ============================================================================
// Stav zakázky
// ============================================================================

const ORDER_STATUS_LABELS = {
    RECEIVED:          "Přijata",
    DIAGNOSIS:         "Diagnostika",
    WAITING_FOR_PARTS: "Čekání na díly",
    IN_PROGRESS:       "Probíhá",
    READY_FOR_PICKUP:  "K vyzvednutí",
    COMPLETED:         "Dokončena",
    CANCELLED:         "Zrušena"
};

/**
 * Tóny pro StatusBadge (R-3). DIAGNOSIS měla dřív `text-bg-light` — na bílém
 * pozadí prakticky neviditelnou; jako rozpracovaný stav patří k `info`.
 */
const ORDER_STATUS_TONES = {
    RECEIVED:          "primary",
    DIAGNOSIS:         "info",
    WAITING_FOR_PARTS: "warning",
    IN_PROGRESS:       "info",
    READY_FOR_PICKUP:  "primary",
    COMPLETED:         "success",
    CANCELLED:         "secondary"
};

const ORDER_IMPORT_TYPE_LABELS = {
    INVOICE_NUMBER:           "Faktury",
    ORDER_NUMBER:              "Objednávky",
};

/** Pole options pro <select> dropdowny typu importu objednávky. */
export const ORDER_IMPORT_TYPE_OPTIONS = Object.entries(ORDER_IMPORT_TYPE_LABELS)
    .map(([value, label]) => ({value, label}));

/** Pole options pro <select> dropdowny stavu zakázky. */
export const ORDER_STATUS_OPTIONS = Object.entries(ORDER_STATUS_LABELS)
    .map(([value, label]) => ({value, label}));

/**
 * Vrátí tón StatusBadge pro hodnotu enumu stavu zakázky.
 * @param {string} status
 * @returns {string}
 */
export function getOrderStatusTone(status) {
    return ORDER_STATUS_TONES[status] ?? "secondary";
}

/**
 * Vrátí zobrazovaný popisek pro hodnotu enumu stavu zakázky.
 * @param {string} status
 * @returns {string}
 */
export function getOrderStatusLabel(status) {
    return status ? ORDER_STATUS_LABELS[status] ?? status : EMPTY_VALUE;
}

// ============================================================================
// Způsob úhrady faktury
// ============================================================================

const PAYMENT_METHOD_LABELS = {

    CARD: "Kartou",
    CASH: "Hotově",
    TRANSFER: "Převodem",
    CASH_OR_TRANSFER: "Hotově nebo převodem",
    CASH_OR_CARD: "Hotově nebo kartou",
    CARD_OR_TRANSFER: "Kartou nebo převodem"

}

/*** Pole options pro <select> dropdowny způsobu úhrady. */
export const PAYMENT_METHOD_OPTIONS = Object.entries(PAYMENT_METHOD_LABELS)
    .map(([value, label]) => ({value, label}));

/***
 * Vrátí zobrazovaný popisek pro hodnotu enumu způsobu úhrady.
 * @param {string} paymentMethod
 * @returns {string}
 */
export function getPaymentMethodLabel(paymentMethod) {
    return paymentMethod ? PAYMENT_METHOD_LABELS[paymentMethod] ?? paymentMethod : EMPTY_VALUE;
}

// ============================================================================
// Stav faktury
// ============================================================================

const INVOICE_STATUS_LABELS = {
    DRAFT:     "Koncept",
    ISSUED:    "Vystavena",
    PAID:      "Zaplacena",
    CANCELLED: "Stornována",
};

const INVOICE_STATUS_TONES = {
    DRAFT:     "secondary",
    ISSUED:    "primary",
    PAID:      "success",
    CANCELLED: "danger",
};

/**
 * Pole options pro <select> dropdowny stavu faktury.
 *
 * CANCELLED se schválně nenabízí: koncept se od 2026-08-02 maže, ne stornuje, takže tenhle
 * stav nová faktura nedostane a filtr by u většiny servisů vracel prázdno. Popisek v mapě
 * ZŮSTÁVÁ — faktury stornované dřív se pořád musí správně vykreslit v tabulce i na detailu.
 */
export const INVOICE_STATUS_OPTIONS = Object.entries(INVOICE_STATUS_LABELS)
    .filter(([value]) => value !== 'CANCELLED')
    .map(([value, label]) => ({value, label}));

/**
 * Stavy faktury tak, jak je čte obsluha — **jedna lineární osa a jeden terminální stav**
 * (rozhodnutí uživatele 2026-08-08).
 *
 * <pre>
 *   Koncept → Vystavena → Předána → Zaplacena        (osa: kde doklad je)
 *   Dobropisována                                     (terminál: doklad je vyrušený)
 * </pre>
 *
 * <p>Vrací **pole** odznaků, protože dva případy potřebují dva:
 *
 * <ul>
 *   <li><strong>Rozpracovaná oprava</strong> — osa + „Oprava rozpracována". Koncept dobropisu
 *       nemá číslo a nic neopravuje, ale blokuje založení druhé opravy i fakturaci zakázky;
 *       do 2026-08-08 byl na faktuře úplně neviditelný.</li>
 *   <li><strong>Dobropisovaná a zaplacená</strong> — „Dobropisována" + „Zaplacena". Jediný
 *       případ, kdy ještě něco dlužíš: peníze máš a doklad je vyrušený. To musí být vidět.</li>
 * </ul>
 *
 * <p>U dobropisované faktury osa mizí — „Předána" už nic neřídí a byl to jen šum. Datum
 * předání zůstává v údajích na detailu.
 *
 * @param {object} invoice faktura se `status`, `handedOverAt`, `creditedAt`, `hasDraftCreditNote`
 * @returns {Array<{label: string, tone: string}>} odznaky v pořadí zobrazení
 */
export function getInvoiceStates(invoice) {
    if (!invoice) return [];

    // Terminál: vyrušený doklad se po ose dál neposouvá, takže ji nahrazuje.
    if (invoice.creditedAt) {
        const badges = [{label: "Dobropisována", tone: "secondary"}];
        if (invoice.status === 'PAID') {
            badges.push({label: "Zaplacena", tone: "success"});
        }
        return badges;
    }

    const axis = invoice.status === 'ISSUED' && invoice.handedOverAt
        ? {label: `Předána ${formatDate(invoice.handedOverAt)}`, tone: "info"}
        : {label: getInvoiceStatusLabel(invoice.status), tone: getInvoiceStatusTone(invoice.status)};

    return invoice.hasDraftCreditNote
        ? [axis, {label: "Oprava rozpracována", tone: "warning"}]
        : [axis];
}

/**
 * Vrátí zobrazovaný popisek pro hodnotu enumu stavu faktury.
 * @param {string} status
 * @returns {string}
 */
export function getInvoiceStatusLabel(status) {
    return status ? INVOICE_STATUS_LABELS[status] ?? status : EMPTY_VALUE;
}

/**
 * Vrátí tón StatusBadge pro hodnotu enumu stavu faktury.
 * @param {string} status
 * @returns {string}
 */
export function getInvoiceStatusTone(status) {
    return INVOICE_STATUS_TONES[status] ?? "secondary";
}

// ============================================================================
// Stav pokladního dokladu
// ============================================================================

// Pokladní doklad zná jen dva stavy — vystaven a stornován (V68). Vlastní mapa,
// ne INVOICE_STATUS_*: doklad nemá koncept ani „zaplaceno" a nabízet je nemá smysl.
const CASH_RECEIPT_STATUS_LABELS = {
    ISSUED:    "Vystaven",
    CANCELLED: "Stornován",
};

/**
 * Vrátí zobrazovaný popisek pro hodnotu enumu stavu pokladního dokladu.
 * @param {string} status
 * @returns {string}
 */
export function getCashReceiptStatusLabel(status) {
    return status ? CASH_RECEIPT_STATUS_LABELS[status] ?? status : EMPTY_VALUE;
}

/**
 * Vrátí tón StatusBadge pro hodnotu enumu stavu pokladního dokladu.
 * @param {string} status
 * @returns {string}
 */
export function getCashReceiptStatusTone(status) {
    return status === "CANCELLED" ? "danger" : "success";
}

// ============================================================================
// Typ převodovky
// ============================================================================

const TRANSMISSION_LABELS = {
    MANUAL:         'Manuální',
    AUTOMATIC:      'Automatická',
    SEMI_AUTOMATIC: 'Poloautomat',
    CVT:            'CVT',
    DCT:            'Dvouspojková (DCT)',
};

/**
 * Pole options pro <select> dropdowny typu převodovky.
 * Začíná prázdnou volbou — převodovka je volitelná (viz {@link FUEL_TYPE_OPTIONS}).
 */
export const TRANSMISSION_OPTIONS = [
    {value: "", label: EMPTY_OPTION_LABEL},
    ...Object.entries(TRANSMISSION_LABELS).map(([value, label]) => ({value, label})),
];

/**
 * Vrátí zobrazovaný popisek pro hodnotu enumu typu převodovky.
 * @param {string} transmission
 * @returns {string}
 */
export function getTransmissionLabel(transmission) {
    return transmission ? TRANSMISSION_LABELS[transmission] ?? transmission : EMPTY_VALUE;
}

// ============================================================================
// Typ paliva
// ============================================================================

const FUEL_TYPE_LABELS = {
    PETROL:        "Benzín",
    DIESEL:        "Nafta",
    LPG:           "LPG",
    CNG:           "CNG",
    ELECTRIC:      "Elektro",
    HYBRID_PETROL: "Hybrid (benzín)",
    HYBRID_DIESEL: "Hybrid (nafta)",
    HYDROGEN:      "Vodík",
    OTHER:         "Ostatní"
};

/**
 * Pole options pro <select> dropdowny typu paliva.
 * Začíná prázdnou volbou — palivo je od V86 volitelné (přívěs motor nemá).
 * Její hodnota musí zůstat prázdný řetězec: backend ho čte jako „nevyplněno" a ukládá NULL.
 */
export const FUEL_TYPE_OPTIONS = [
    {value: "", label: EMPTY_OPTION_LABEL},
    ...Object.entries(FUEL_TYPE_LABELS).map(([value, label]) => ({value, label})),
];

/**
 * Vrátí zobrazovaný popisek pro hodnotu enumu typu paliva.
 * @param {string} fuelType
 * @returns {string}
 */
export function getFuelLabel(fuelType) {
    return fuelType ? FUEL_TYPE_LABELS[fuelType] : EMPTY_VALUE;
}

/**
 * Rozparsuje registrový řetězec kol (`NapravyPneuRafky`) na neprázdné nápravy.
 * Vstup: `"215/55 R17 94W / 7JX17 ET40 ;\n… ;\n/ ;\n/ ;"` (per náprava, prázdné = "/ ").
 * @param {string} raw
 * @returns {{label: string, spec: string}[]} neprázdné nápravy; prázdné vozidlo → []
 */
export function formatWheels(raw) {
    if (!raw) return [];
    return raw.split(";")
        .map((s, i) => ({ index: i, spec: s.replace(/\s+/g, " ").trim() }))
        // prázdná náprava je jen "/" (bez pneu i ráfku) → po odstranění lomítek/mezer nezbude znak
        .filter((a) => /[0-9a-z]/i.test(a.spec.replace(/[/\s]/g, "")))
        .map((a) => ({ label: `${a.index + 1}. náprava`, spec: a.spec }));
}

// ============================================================================
// Typ zákazníka
// ============================================================================

export const CUSTOMER_TYPE_LABELS = {
    NONE:       '',
    INDIVIDUAL: 'Fyzická osoba',
    COMPANY:    'Firma',
};

/** Pole options pro <select> dropdowny typu zákazníka. */
export const CUSTOMER_TYPE_OPTIONS = Object.entries(CUSTOMER_TYPE_LABELS)
    .map(([value, label]) => ({value, label}));

// ============================================================================
// Typ položky zakázky
// ============================================================================

const ORDER_ITEM_TYPE_LABELS = {
    LABOR:               "Práce",
    MATERIAL:            "Materiál",
    OTHER_SERVICES:      "Ostatní"
};

/** Pole options pro <select> dropdowny typu položky zakázky. */
export const ORDER_ITEM_TYPE_OPTIONS = Object.entries(ORDER_ITEM_TYPE_LABELS)
    .map(([value, label]) => ({value, label}));

/**
 * Vrátí zobrazovaný popisek pro hodnotu enumu typu položky zakázky.
 * @param {string} type
 * @returns {string}
 */
export function getOrderItemTypeLabel(type) {
    return type ? ORDER_ITEM_TYPE_LABELS[type] ?? type : EMPTY_VALUE;
}

// ============================================================================
// Typ adresy
// ============================================================================

const ADDRESS_TYPE_LABELS = {
    BILLING:  'Fakturační',
    CONTACT:  'Kontaktní',
};

/**
 * Vrátí zobrazovaný popisek pro hodnotu enumu typu adresy.
 * @param {string} addressType
 * @returns {string}
 */
export function getAddressTypeLabel(addressType) {
    return ADDRESS_TYPE_LABELS[addressType] ?? EMPTY_VALUE;
}

// ============================================================================
// Zdroj stavu tachometru
// ============================================================================

const MILEAGE_SOURCE_LABELS = {
    SERVICE:  "Servis",
    CUSTOMER: "Zákazník",
    INITIAL:  "Počáteční",
    OTHER:    "Jiné"
};

const MILEAGE_SOURCE_TONES = {
    SERVICE:  "primary",
    CUSTOMER: "info",
    INITIAL:  "secondary",
    OTHER:    "secondary"
};

/**
 * Options pro <select> zdroje stavu tachometru. INITIAL je záměrně vynechán —
 * je vyhrazen pro počáteční záznam vytvořený při registraci vozidla a backend
 * ho na veřejném mileage API odmítá.
 */
export const MILEAGE_SOURCE_OPTIONS = Object.entries(MILEAGE_SOURCE_LABELS)
    .filter(([value]) => value !== "INITIAL")
    .map(([value, label]) => ({value, label}));

/**
 * Vrátí zobrazovaný popisek pro hodnotu enumu zdroje stavu tachometru.
 * @param {string} source
 * @returns {string}
 */
export function getMileageSourceLabel(source) {
    return source ? MILEAGE_SOURCE_LABELS[source] ?? source : EMPTY_VALUE;
}

/**
 * Vrátí tón StatusBadge pro hodnotu enumu zdroje stavu tachometru.
 * @param {string} source
 * @returns {string}
 */
export function getMileageSourceTone(source) {
    return MILEAGE_SOURCE_TONES[source] ?? "secondary";
}

// ============================================================================
// Skladový pohyb
// ============================================================================

const MOVEMENT_TYPE_LABELS = {
    RECEIPT:    "Příjem",
    ISSUE:      "Výdej",
    ISSUE_RETURN: "Vrátka sklad",
    ADJUSTMENT: "Korekce",
    RETURN:     "Vratka dodavatel",
    WRITE_OFF:  "Odpis"
};

const RETURN_REASON_LABELS = {
    DEFECTIVE:         "Vadný díl",
    WRONG_PART:        "Špatný díl",
    DAMAGED_TRANSPORT: "Poškozeno přepravou",
    SURPLUS:           "Přebytek",
    OTHER:             "Jiné"
};

/** Volby důvodu vratky pro formuláře (vzor RECEIPT_STATUS_OPTIONS). */
export const RETURN_REASON_OPTIONS = Object.entries(RETURN_REASON_LABELS)
    .map(([value, label]) => ({ value, label }));

/**
 * Vrátí zobrazovaný popisek pro hodnotu enumu typu skladového pohybu.
 * @param {string} type
 * @returns {string}
 */
export function getMovementTypeLabel(type) {
    return type ? MOVEMENT_TYPE_LABELS[type] ?? type : EMPTY_VALUE;
}

/**
 * Vrátí zobrazovaný popisek pro hodnotu enumu důvodu vratky.
 * @param {string} reason
 * @returns {string}
 */
export function getReturnReasonLabel(reason) {
    return reason ? RETURN_REASON_LABELS[reason] ?? reason : EMPTY_VALUE;
}

// ============================================================================
// Odznak STK (technická kontrola)
// ============================================================================

/** Počet dní před koncem platnosti STK, od kterého se odznak přepne na varování. */
const STK_WARNING_DAYS = 30;

/**
 * Vrátí props odznaku pro datum platnosti STK:
 * bez údaje → "—"/secondary, propadlá → danger, končí do 30 dnů → warning,
 * jinak success. Popisek je naformátované datum.
 *
 * @param {string|null} stkValidUntil - ISO datum ze snapshotu registru
 * @returns {{label: string, tone: string}}
 */
export function getStkBadge(stkValidUntil) {
    if (!stkValidUntil) return { label: EMPTY_VALUE, tone: "secondary" };

    const today = new Date();
    const validUntil = new Date(stkValidUntil);
    today.setHours(0, 0, 0, 0);
    validUntil.setHours(0, 0, 0, 0);

    const warningThreshold = new Date(today);
    warningThreshold.setDate(warningThreshold.getDate() + STK_WARNING_DAYS);

    const label = formatDate(stkValidUntil);
    if (validUntil < today) return { label, tone: "danger" };
    if (validUntil <= warningThreshold) return { label, tone: "warning" };
    return { label, tone: "success" };
}

// ============================================================================
// Pomocné formátovací funkce
// ============================================================================

/**
 * Vrátí iniciály ze zobrazovaného jména.
 * U víceslovných jmen vrací první písmeno prvních dvou slov.
 * U jednoslovných jmen vrací první dva znaky.
 *
 * @param {string} displayName
 * @returns {string} nejvýše dva velké znaky, nebo "??" pro prázdný vstup
 */
export function getInitials(displayName) {
    if (!displayName) return "??";

    const words = displayName.trim().split(/\s+/);
    if (words.length > 1) {
        return (words[0][0] + words[1][0]).toUpperCase();
    }
    return displayName.slice(0, 2).toUpperCase();
}

/**
 * Naformátuje ISO datum pro zobrazení v českém locale.
 * @param {string|null} isoString - datum nebo datum s časem v ISO 8601
 * @returns {string} naformátované datum, nebo "—" pro prázdný vstup
 */
export function formatDate(isoString) {
    if (!isoString) return EMPTY_VALUE;
    return new Date(isoString).toLocaleDateString("cs-CZ");
}

/**
 * Naformátuje ISO datum s časem pro zobrazení v českém locale.
 * @param {string|null} isoString - datum nebo datum s časem v ISO 8601
 * @returns {string} naformátované datum s časem, nebo "—" pro prázdný vstup
 */
export function formatDateTime(isoString) {
    if (!isoString) return EMPTY_VALUE;
    return new Date(isoString).toLocaleString("cs-CZ",{
        day: 'numeric',
        month: 'numeric',
        year: 'numeric',
        hour: '2-digit',
        minute: '2-digit'
    });
}

/**
 * Vrátí dnešní datum ve formátu YYYY-MM-DD pro použití v datumových input polích.
 * @returns {string} dnešní datum ve formátu YYYY-MM-DD
 */
export function getFormDate() {
    const today = new Date();
    const month = String(today.getMonth() + 1).padStart(2, '0');
    const day   = String(today.getDate()).padStart(2, '0');
    return `${today.getFullYear()}-${month}-${day}`;
}

// ============================================================================
// Název země
// ============================================================================

const countryDisplayNames = new Intl.DisplayNames(['cs'], { type: 'region' });

const COUNTRY_NAME_OVERRIDES = {
    CZ: 'Česká republika',
    SK: 'Slovenská republika',
};

/**
 * Převede ISO 3166-1 alpha-2 kód země na její český název.
 * @param {string|null} countryCode - např. "CZ", "DE", "US"
 * @returns {string} lokalizovaný název země, nebo původní kód jako fallback
 */
export function getCountryName(countryCode) {
    if (!countryCode) return EMPTY_VALUE;
    const upper = countryCode.toUpperCase();

    if (COUNTRY_NAME_OVERRIDES[upper]) {
        return COUNTRY_NAME_OVERRIDES[upper];
    }

    try {
        return countryDisplayNames.of(upper) ?? upper;
    } catch {
        return upper;
    }
}

// ============================================================================
// Příjemky (goods receipts) — draft workflow
// ============================================================================

export const RECEIPT_STATUS_LABELS = {
    PENDING_REVIEW: "Čeká na kontrolu",
    CONFIRMED:      "Potvrzeno",
    REJECTED:       "Zamítnuto",
    CANCELLED:      "Stornováno"
};

export const RECEIPT_STATUS_OPTIONS = Object.entries(RECEIPT_STATUS_LABELS)
    .map(([value, label]) => ({ value, label }));

/**
 * Vrátí zobrazovaný popisek pro hodnotu enumu stavu příjemky.
 * @param {string} status
 * @returns {string}
 */
export function getReceiptStatusLabel(status) {
    return status ? RECEIPT_STATUS_LABELS[status] ?? status : EMPTY_VALUE;
}

/**
 * Vrátí tón StatusBadge pro stav příjemky.
 * @param {string} status
 * @returns {string}
 */
export function getReceiptStatusTone(status) {
    switch (status) {
        case "PENDING_REVIEW": return "warning";
        case "CONFIRMED":      return "success";
        case "REJECTED":       return "secondary";
        case "CANCELLED":      return "danger";
        default:               return "secondary";
    }
}

// ============================================================================
// Inventura (stock take)
// ============================================================================

const STOCK_TAKE_STATUS_LABELS = {
    OPEN:      "Probíhá",
    CLOSED:    "Uzavřena",
    CANCELLED: "Zrušena",
};

const STOCK_TAKE_STATUS_TONES = {
    OPEN:      "warning",
    CLOSED:    "success",
    CANCELLED: "secondary",
};

/** Options pro <select> filtr stavu inventury. */
export const STOCK_TAKE_STATUS_OPTIONS = Object.entries(STOCK_TAKE_STATUS_LABELS)
    .map(([value, label]) => ({ value, label }));

/**
 * Vrátí zobrazovaný popisek pro hodnotu enumu stavu inventury.
 * @param {string} status
 * @returns {string}
 */
export function getStockTakeStatusLabel(status) {
    return STOCK_TAKE_STATUS_LABELS[status] ?? status ?? EMPTY_VALUE;
}

/**
 * Vrátí tón StatusBadge pro hodnotu enumu stavu inventury.
 * @param {string} status
 * @returns {string}
 */
export function getStockTakeStatusTone(status) {
    return STOCK_TAKE_STATUS_TONES[status] ?? "secondary";
}

// ============================================================================
// Aktivní / neaktivní záznam (soft-delete)
// ============================================================================

/**
 * Jednotný popisek stavu záznamu napříč entitami. Dřív existovala čtyři znění
 * („● Aktivní“, „neaktivní“, „Deaktivovaný“, nic) — teď jedno.
 * @param {boolean} active
 * @returns {string}
 */
export function getActiveLabel(active) {
    return active ? "Aktivní" : "Neaktivní";
}

/**
 * Tón odznaku pro stav záznamu.
 * @param {boolean} active
 * @returns {string}
 */
export function getActiveTone(active) {
    return active ? "success" : "secondary";
}

// ============================================================================
// Role uživatele
// ============================================================================

const ROLE_LABELS = {
    ROLE_ADMIN:    "Administrátor",
    ROLE_MANAGER:  "Manažer",
    ROLE_MECHANIC: "Mechanik",
    ROLE_CUSTOMER: "Zákazník",
};

/**
 * Vrátí český zobrazovaný popisek pro řetězec GrantedAuthority.
 * Neznámá role se zobrazí bez prefixu ROLE_, ať se do UI nedostane syrový enum.
 * @param {string} role - např. "ROLE_ADMIN"
 * @returns {string}
 */
export function getRoleLabel(role) {
    if (!role) return EMPTY_VALUE;
    return ROLE_LABELS[role] ?? role.replace(/^ROLE_/, "");
}

export const DOCUMENT_TYPE_LABELS = {
    INVOICE:       "Faktura",
    DELIVERY_NOTE: "Dodací list",
    // Systémový typ — pseudo-příjemka inventurních přebytků (nezakládá ji uživatel,
    // vzniká uzavřením inventury). Ve filtru příjemek je legitimní volba.
    STOCK_TAKE:    "Inventurní přebytek"
};

export const DOCUMENT_TYPE_OPTIONS = Object.entries(DOCUMENT_TYPE_LABELS)
    .map(([value, label]) => ({ value, label }));

/**
 * Vrátí zobrazovaný popisek pro hodnotu enumu typu dokladu příjemky.
 * @param {string} type
 * @returns {string}
 */
export function getDocumentTypeLabel(type) {
    return type ? DOCUMENT_TYPE_LABELS[type] ?? type : EMPTY_VALUE;
}

/**
 * Stavy polí draftu příjemky (TrackedField.state) — vizuální metadata (osa PŮVODU
 * hodnoty). VERIFIED = ověřeno křížovou kontrolou; DERIVED/DEFAULTED = dopočteno/
 * doplněno (žlutě, zkontrolovat); ABSENT = údaj není na dokladu (neutrálně — samo
 * o sobě to není chyba); EDITED = změněno uživatelem.
 *
 * Osu „akce nutná" (červený rámeček) řeší zvlášť povinnost pole (REQUIRED_* níže),
 * ne stav — prázdné NEpovinné pole se červeně nebarví.
 */
export const FIELD_STATE_META = {
    VERIFIED:  { label: "Ověřeno křížovou kontrolou",  className: "text-success",   icon: "bi-check-circle-fill" },
    VERBATIM:  { label: "Přečteno z dokladu",          className: "text-body",      icon: "" },
    DERIVED:   { label: "Dopočteno z jiných hodnot",   className: "text-warning",   icon: "bi-calculator" },
    DEFAULTED: { label: "Doplněno výchozí hodnotou",   className: "text-warning",   icon: "bi-sliders" },
    ABSENT:    { label: "Údaj není na dokladu",        className: "text-secondary", icon: "bi-dash-circle" },
    EDITED:    { label: "Upraveno při kontrole",       className: "text-primary",   icon: "bi-pencil-fill" }
};

/**
 * Povinná pole draftu pro potvrzení — ZRCADLÍ backendový completeness gate
 * (ReceiptReviewServiceImpl.validateCompleteness). Slouží jen k vizuálnímu značení
 * „povinné a prázdné" (červený rámeček); autoritativní validace zůstává na serveru.
 * Při změně gate upravit i zde (vzor jako ALLOWED_UNITS v api/units.js).
 */
export const REQUIRED_HEADER_FIELDS = ["documentNumber", "issueDate", "currency",
    "subtotal", "vatAmount", "totalAmount"];

/** Povinná řádková pole ITEM. catalogNumber řeší isLineFieldRequired (jen pro nový produkt). */
export const REQUIRED_LINE_FIELDS = ["name", "quantity", "unitPriceExclVat", "vatRate", "totalInclVat"];

/**
 * Je řádkové pole povinné? Jen pro ITEM řádky (NOTE/DELIVERY_NOTE_GROUP se nevalidují).
 * catalogNumber je povinné jen když řádek zakládá nový produkt (productMatch bez
 * productId) — zrcadlí Fix 3 v completeness gate.
 */
export function isLineFieldRequired(line, fieldName) {
    if (line?.lineKind !== "ITEM") return false;
    if (fieldName === "catalogNumber") {
        return line.productMatch?.productId == null;
    }
    return REQUIRED_LINE_FIELDS.includes(fieldName);
}

/** Popisky deterministických kontrol backendu (DraftVerificationService). */
export const RECEIPT_CHECK_LABELS = {
    LINE_MATH:                  "Matematika řádků (množství × cena, DPH)",
    LINES_SUM_VS_RECAP:         "Součet řádků vs. rekapitulace DPH",
    RECAP_SUM:                  "Rekapitulace vs. hlavičkové součty",
    SUBTOTAL_PLUS_VAT_EQ_TOTAL: "Základ + DPH = celkem",
    LINES_SUM_VS_TOTAL:         "Součet řádků vs. celková částka",
    ICO_CHECKSUM:               "Kontrolní součet IČO dodavatele",
    SUPPLIER_KNOWN:             "Dodavatel nalezen v databázi"
};

/**
 * ISO okamžik ze serveru → hodnota pro `<input type="datetime-local">`.
 *
 * Vstup je UTC (`2026-06-01T14:00:00Z`), ale `datetime-local` pracuje
 * s **místním** časem bez zóny. Dřív se řetězec jen usekl na 16 znaků, takže
 * se z „14:00 UTC" stalo „14:00 místního času" — pole ukazovalo o posun zóny
 * jinou hodinu a každé uložení čas o tentýž posun odsunulo (kumulativně,
 * nalezeno při ověřování U5.1). Proto se okamžik nejdřív posune do místního
 * času a teprve pak ořízne.
 */
export function toDatetimeLocal(isoString) {
    if (!isoString) return "";
    const date = new Date(isoString);
    if (Number.isNaN(date.getTime())) return "";
    return new Date(date.getTime() - date.getTimezoneOffset() * 60000)
        .toISOString().substring(0, 16);
}

/** Hodnota z `<input type="datetime-local">` (místní čas) → ISO okamžik pro API. */
export function fromDatetimeLocal(value) {
    if (!value) return null;
    return new Date(value).toISOString();
}

// ============================================================================
// Plánovací kalendář — typ a stav položky
// ============================================================================

const APPOINTMENT_TYPE_LABELS = {
    BOOKING: "Objednávka",
    CLOSURE: "Zavřeno",
    EVENT:   "Událost",
};

const APPOINTMENT_STATUS_LABELS = {
    PLANNED:   "Naplánováno",
    CONVERTED: "Převedeno na zakázku",
    NO_SHOW:   "Nedostavil se",
    CANCELLED: "Zrušeno",
};

const APPOINTMENT_STATUS_TONES = {
    PLANNED:   "primary",
    CONVERTED: "success",
    NO_SHOW:   "danger",
    CANCELLED: "secondary",
};

/** Options pro <select> stavu objednávky — bez CONVERTED, ten vzniká jen převodem. */
export const APPOINTMENT_STATUS_OPTIONS = Object.entries(APPOINTMENT_STATUS_LABELS)
    .filter(([value]) => value !== "CONVERTED")
    .map(([value, label]) => ({value, label}));

/** Options pro <select> typu položky kalendáře. */
export const APPOINTMENT_TYPE_OPTIONS = Object.entries(APPOINTMENT_TYPE_LABELS)
    .map(([value, label]) => ({value, label}));

/**
 * @param {string} entryType
 * @returns {string}
 */
export function getAppointmentTypeLabel(entryType) {
    return entryType ? APPOINTMENT_TYPE_LABELS[entryType] ?? entryType : EMPTY_VALUE;
}

/**
 * @param {string} status
 * @returns {string}
 */
export function getAppointmentStatusLabel(status) {
    return status ? APPOINTMENT_STATUS_LABELS[status] ?? status : EMPTY_VALUE;
}

/**
 * Tón StatusBadge pro stav objednávky.
 * @param {string} status
 * @returns {string}
 */
export function getAppointmentStatusTone(status) {
    return APPOINTMENT_STATUS_TONES[status] ?? "secondary";
}

/**
 * CSS třída barvy položky kalendáře — jediné místo, kde se rozhoduje.
 *
 * <p>Objednávky se barví podle stavu (`fc-status-*`). Událost má vlastní barvu podle typu
 * (`fc-type-event`), protože stav u ní skoro nic neříká — žije jen v Naplánováno/Zrušeno.
 * Zrušená událost ale barvu stavu dostane: ztlumení je informace, která nesmí zaniknout.
 *
 * @param {{entryType: string, status: string}} entry položka kalendáře
 * @returns {string}
 */
export function getAppointmentToneClass(entry) {
    if (entry.entryType === "EVENT" && entry.status !== "CANCELLED") {
        return "fc-type-event";
    }
    return `fc-status-${entry.status.toLowerCase()}`;
}
