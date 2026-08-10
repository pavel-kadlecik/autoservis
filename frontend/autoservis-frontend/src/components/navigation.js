/**
 * Struktura hlavního menu jako data, ne ručně psaný seznam <li> (U2.1).
 *
 * Sidebar se z toho vykreslí sám, takže přidání položky je jeden řádek tady
 * a chování (aktivní stav, rozbalování, offcanvas) se řeší na jednom místě.
 *
 * Aktivní položku určuje **nejdelší shoda cesty** (`activeNavPath` níže), ne
 * `end` na NavLinku. `end` sice zabránilo dvojímu zvýraznění u `/warehouse`
 * (prefix `/warehouse/receipts`), ale zároveň zhaslo menu na podstránkách jako
 * `/warehouse/5/detail`. Nejdelší shoda řeší obojí jedním pravidlem.
 */
export const NAV_SECTIONS = [
    {
        id: "provoz",
        items: [
            { to: "/dashboard", label: "Přehled",  icon: "speedometer2" },
            { to: "/customers", label: "Zákazníci",  icon: "people" },
            { to: "/vehicles",  label: "Vozidla",    icon: "car-front" },
            { to: "/schedule",  label: "Plánování",  icon: "calendar-week" },
            { to: "/orders",    label: "Zakázky",    icon: "clipboard2-check" },
            { to: "/invoices",  label: "Faktury",    icon: "receipt" },
        ],
    },
    {
        id: "sklad",
        group: { label: "Sklad", icon: "box-seam" },
        items: [
            { to: "/warehouse",                  label: "Přehled skladu", icon: "boxes" },
            { to: "/warehouse/receipts",         label: "Příjemky",       icon: "file-earmark-arrow-down" },
            { to: "/warehouse/low-stock",        label: "Pod minimem",    icon: "exclamation-triangle" },
            { to: "/warehouse/stock-takes",      label: "Inventury",      icon: "clipboard-check" },
            { to: "/suppliers",                  label: "Dodavatelé",     icon: "truck" },
        ],
    },
    {
        id: "nastaveni",
        separated: true,
        group: { label: "Nastavení", icon: "gear" },
        items: [
            { to: "/employees",        label: "Zaměstnanci",       icon: "person-badge", roles: ['ROLE_ADMIN', 'ROLE_MANAGER'] },
            { to: "/settings/company", label: "Fakturační údaje",  icon: "building-gear" },
            // Otevírací doba je provozní údaj, ne fakturační — proto vlastní položka, ne záložka
            // ve Fakturačních údajích. Mění ji vedení, stejně jako blokace dílny.
            { to: "/settings/opening-hours", label: "Otevírací doba", icon: "clock", roles: ['ROLE_ADMIN', 'ROLE_MANAGER'] },
            { to: "/users",            label: "Uživatelé",         icon: "person-gear", adminOnly: true },
        ],
    },
    {
        // Nápověda není nastavení — zůstává samostatně pod skupinou.
        id: "napoveda",
        items: [
            { to: "/help", label: "Nápověda", icon: "question-circle" },
        ],
    },
];

/** Všechny položky napříč sekcemi (pro určení aktivní cesty). */
const ALL_ITEMS = NAV_SECTIONS.flatMap(section => section.items);

/** Sedí cesta položky na URL — buď přesně, nebo jako nadřazená sekce. */
function matchesPath(to, pathname) {
    return pathname === to || pathname.startsWith(to + "/");
}

/**
 * Vrátí `to` položky, která má být zvýrazněná — **vyhrává nejdelší shoda**.
 *
 * `/warehouse/receipts` tak zvýrazní Příjemky (ne zároveň Přehled skladu),
 * ale `/warehouse/5/detail` zvýrazní Přehled skladu, protože delší shoda
 * neexistuje. Neznámá cesta nezvýrazní nic.
 *
 * @param {string} pathname
 * @returns {string|null}
 */
export function activeNavPath(pathname) {
    return ALL_ITEMS
        .map(item => item.to)
        .filter(to => matchesPath(to, pathname))
        .sort((a, b) => b.length - a.length)[0] ?? null;
}
