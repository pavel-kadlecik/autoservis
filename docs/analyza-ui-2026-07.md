# analyza-ui-2026-07.md — Hloubková analýza UI/UX (červenec 2026)

> Rozsah: celý frontend `frontend/autoservis-frontend/src` — 25 stránek, ~40 komponent, `api/format.js`,
> `help/`, `index.css`, `css/reset.css`, Sidebar/Layout/App.
> Metoda: čtení veškerého kódu FE + průchod běžící aplikace v prohlížeči (backend :8080, FE :5173,
> přihlášený uživatel, viewport 1200×860 a 760×860).
> **Tento dokument nic neimplementuje** — prováděcí plán je v [plan-ui.md](plan-ui.md).

Značení: **S-xx** = nález/nekonzistence, **R-x** = otevřené rozhodnutí pro uživatele.
Priorita: **P0** = funkční vada nebo blokující UX, **P1** = viditelná nekonzistence, **P2** = kosmetika/úklid.

---

## 1. Shrnutí

Aplikace má **dobrý základ** (Bootstrap 5.3, čitelný sidebar, konzistentní ikonografie Bootstrap Icons,
centrální `format.js` pro labely a badge). Problém není vzhled jednotlivé obrazovky — ten je vesměs
v pořádku — ale **počet paralelních variant téhož vzoru**. Napříč aplikací existuje:

| Vzor | Kolik variant | Kde |
|---|---|---|
| Nadpis stránky | **5** | `<h1>` / `<h1 class="mb-0">` / `<h1 class="h4 mb-0">` / `<h2 class="mb-4">` / `<h2 … border-bottom>` |
| Hlavička stránky (nadpis + akce) | **4** | list bez akcí / list s akcemi v `row` / flex s `ms-auto` / detail `justify-content-between` |
| Řádkové akce v tabulce | **4** | MUI třitečkové menu / `btn-group` s texty / textová tlačítka Upravit+Smazat / klik na celý řádek |
| Sekce uvnitř stránky | **3** | `h6 text-uppercase text-muted` v kartě / `h5 text-primary border-bottom` / `h2 border-bottom pb-3` |
| Badge | **5** | `text-bg-*` / `bg-*-subtle text-*-emphasis` / totéž + `rounded-pill` / vlastní `.badge-individual` / holý `text-bg-secondary` |
| Loading stav | **4** | „Načítám..." / „Načítám…" / „Načítám" / spinner |
| Prázdný stav | **4** | řádek v `<tbody>` / `<p class="text-muted fst-italic">` / `alert-success` / nic |
| Formulář — rozvržení | **2** | plochý (`h5 text-primary`) / kartový (`card` + `h6 uppercase`) |
| Formulář — šířka stránky | **2** | plná šířka / `container` (max-width 960) |

K tomu **jedna funkční vada P0** (editace zákazníka spadne na bílou stránku) a **jedna P0 responsivity**
(při šířce okna pod ~1250 px se akční tlačítka i sloupec Akce stanou nedostupnými).

---

## 2. ÚKOL 1 — Vizuální inventura

### 2.1 Layout stránky

`Layout.jsx:15-21` — `d-flex` + `<Sidebar>` (fixních 240 px, `index.css:4-13`) + `<main id="main-content">`
s `padding: 24px` a **`overflow-x: hidden`** (`index.css:25`). Žádný breakpoint, žádné skrývání sidebaru,
žádný `max-width` obsahu. Alerty (`AlertContainer.jsx:9`) jsou `position-fixed top-0 end-0`.

**Nález S-01 (P0) — layout se nepřizpůsobí užšímu oknu.** Sidebar má pevných 240 px bez ohledu na šířku
okna; hlavičkové řádky seznamů používají `col-8` / `col-4` + `flex-nowrap` + `text-truncate`
(`WarehousePage.jsx:122-152`, `CustomersPage.jsx:63-82`, `VehiclesPage.jsx:65-92`, `UsersPage.jsx:60-79`).
Naměřeno v prohlížeči:

- při **1200×860** mají na `/warehouse` obě akční tlačítka („Import faktury (PDF)", „Přidat nový záznam")
  šířku **26 px** — jsou nečitelná a nepoužitelná;
- při **760 px** zmizí i checkbox „Nízká dostupnost" a z tlačítek zbyde „In" a „Př";
- na `/customers` při 850 px končí poslední buňka tabulky na x=899 při šířce okna 850 — a protože
  `#main-content` má `overflow-x: hidden`, **sloupec Akce nelze nascrollovat**, je trvale nedostupný.

Dopad: aplikace je fakticky použitelná jen na širokém monitoru; na notebooku 1366×768 se
sidebarem a systémovým zoomem už je hlavička Skladu rozbitá.

### 2.2 Hlavičky stránek (nadpis + akční tlačítka)

Čtyři vzory:

| Vzor | Příklad | Nadpis |
|---|---|---|
| A — samostatný `h1`, akce v `row` pod ním | `CustomersPage.jsx:62-82`, `VehiclesPage.jsx:64-92`, `OrdersPage.jsx:59-78`, `WarehousePage.jsx:121-152`, `UsersPage.jsx:59-79`, `ReceiptsPage.jsx:124-166`, `SuppliersPage.jsx:57-75`, `InvoicesPage.jsx:52-72` | `<h1>` (2,5 rem) |
| B — `d-flex` + `h1 mb-0` + tlačítko `ms-auto` | `StockTakesPage.jsx:49-54`, `LowStockPage.jsx:35-41`, `StockTakePageDetail.jsx:124-135`, `ReceiptReviewPage.jsx:253-272` | `<h1 class="mb-0">` |
| C — detail: avatar + `h1.h4` + badge vlevo, skupina tlačítek vpravo | `CustomersPageDetail.jsx:70-108`, `VehiclesPageDetail.jsx:141-172`, `OrdersPageDetail.jsx:67-107`, `SuppliersPageDetail.jsx:52-89`, `WarehousePageDetail.jsx:57-93`, `InvoicesPageDetail.jsx:66-101` | `<h1 class="h4 mb-0">` (1,5 rem) |
| D — formulář: `h2` uvnitř komponenty formuláře | `CustomerForm.jsx:55`, `VehicleForm.jsx:112`, `WarehouseForm.jsx:41`, `SupplierForm.jsx:35`, `UserForm.jsx:48`, `OrderForm.jsx:171` | `<h2 class="mb-4">` |

Plus **E** — `CompanyProfilePage.jsx:65` `<h1 class="mb-1">` + podtitulek `<p class="text-muted">`
(jediná stránka s podtitulkem).

**Nález S-02 (P1) — velikost nadpisu nese jinou informaci než úroveň stránky.** Detail entity má nadpis
menší (h4) než seznam (h1) a formulář je h2. Uživatel vidí tři různé velikosti pro tři stránky téže entity.

**Nález S-03 (P1) — tlačítko „Zpět" je nahodilé.** Má ho `WarehousePageDetail.jsx:78-80`,
`InvoicesPageDetail.jsx:97-99`, `LowStockPage.jsx:37-40` („Zpět na sklad"),
`StockTakePageDetail.jsx:131-134` („Zpět na inventury"), `ReceiptReviewPage.jsx:267-271`
(„Zpět na příjemky"). **Nemá** ho detail zákazníka, vozidla, zakázky ani dodavatele. Ve formulářích je
„Zpět" naopak vždy (jako sekundární tlačítko u „Uložit"), takže totéž slovo znamená jednou navigaci
zpět a jednou zrušení editace.

**Nález S-04 (P1) — pořadí a styl akcí v hlavičce detailu se liší.**
Zákazník/vozidlo/zakázka/dodavatel: `Editovat` + `Deaktivovat` (2 tlačítka).
Sklad: `Zpět`, `Editovat`, `Skladový pohyb`, `Deaktivovat` — čtyři tlačítka, z toho tři
`btn-outline-secondary`, takže hlavní akce („Skladový pohyb") vizuálně nevyčnívá
(`WarehousePageDetail.jsx:77-92`). Faktura: stavové akce (`Vystavit` / `Označit zaplaceno`) jako
**plné** `btn-primary`/`btn-success`, pak `PDF` a `Zpět` (`InvoicesPageDetail.jsx:76-100`).

### 2.3 Tabulky

Základ je jednotný: `table table-hover`, hlavička stylovaná globálně v `index.css:29-31`
(uppercase, 0,8 rem, šedá, 2px spodní linka). Rozdíly:

- **hustota:** seznamy používají `table table-hover` (plná výška řádku), vnořené tabulky na detailech
  `table table-sm` (`WarehousePageDetail.jsx:145`, `OrderItemTable.jsx:61`, `MileageHistoryTable.jsx:25`,
  `OrdersPageDetail.jsx:261`, `InvoicesPageDetail.jsx:143`);
- **`align-middle`** má jen část tabulek (`ProductTable.jsx:42`, `InvoiceTable.jsx:57`, `ReceiptsPage.jsx:168`,
  `LowStockPage.jsx:56`), ostatní ne (`CustomerTable.jsx:21`, `VehicleTable.jsx:22`, `OrderTable.jsx:27`,
  `SupplierTable.jsx:21`, `UserTable.jsx:25`) — řádky s badge mají pak text zarovnaný nahoru;
- **`thead table-light`** má jen část tabulek (`LowStockPage.jsx:57`, `MileageHistoryTable.jsx:26`,
  `OrderItemTable.jsx:62`, `WarehousePageDetail.jsx:146`), jinde se spoléhá na globální CSS;
- **`scope="col"`** je v `CustomerTable`, `VehicleTable`, `OrderTable`, `SupplierTable`, `UserTable`,
  `ProductTable`, `InvoiceTable`, `StockTakesPage`, `InvoicesPageDetail` — ale chybí v `ReceiptsPage.jsx:171-178`,
  `LowStockPage.jsx:59-65`, `StockTakePageDetail.jsx:160-166`, `OrderItemTable.jsx:64-68`,
  `MileageHistoryTable.jsx:28-32`, `WarehousePageDetail.jsx:148-154`;
- **`table-responsive`** obal má jen `LowStockPage.jsx:55` a `StockTakePageDetail.jsx:156` — nikde jinde,
  což ve spojení s S-01 znamená nedostupné sloupce;
- **řazení kliknutím na hlavičku neexistuje nikde** — `sortBy` je ve všech seznamech zamrzlá konstanta
  (`CustomersPage.jsx:16-17` `useState('lastName')` bez setteru, stejně `VehiclesPage.jsx:16-17`,
  `OrdersPage.jsx:15-16`, `WarehousePage.jsx:19-20`); `SuppliersPage` a `InvoicesPage` `sortBy` neposílají vůbec.

**Nález S-05 (P1) — čtyři různé způsoby, jak z řádku spustit akci:**

1. **MUI třitečkové menu** `TableRowActionMenu` — `CustomerTable.jsx:47`, `VehicleTable.jsx:52`,
   `OrderTable.jsx:61`, `SupplierTable.jsx:50`, `ProductTable.jsx:90`, `UserTable.jsx:53`,
   `CustomerVehiclesTable.jsx:49`;
2. **`btn-group` s textovými tlačítky** — `InvoiceTable.jsx:85-116` (Detail ikona + Vystavit/Zaplaceno/Storno);
3. **dvě textová tlačítka ve sloupci bez hlavičky** — `OrderItemTable.jsx:26-31`,
   `MileageHistoryTable.jsx:55-66` (Upravit / Smazat);
4. **klik na celý řádek, žádný sloupec akcí** — `ReceiptsPage.jsx:186`, `StockTakesPage.jsx:77`,
   `LowStockPage.jsx:70` (`style={{cursor:"pointer"}}` inline).

Navíc `index.css:28` dává `cursor: pointer` **všem** řádkům `.table-hover` — i tam, kde řádek klikatelný
není (zákazníci, vozidla, zakázky, dodavatelé, uživatelé, sklad). Uživatel klikne a nic se nestane.

### 2.4 Filtry, hledání, stránkování

`InputFilter.jsx` — `input-group` s `type="search"`, ikonou lupy a **`visually-hidden`** labelem.
Používá napevno `id="filter-search"` (`InputFilter.jsx:12,16`) — při dvou instancích na stránce
duplicitní ID. `CheckBox.jsx` je hardcodovaný „Jen aktivní" s `id="onlyActiveCheck"` a — chybně —
s obalem `className="col-4"` (`CheckBox.jsx:6`), přestože se vkládá do flexu, kde `col-4` nedává smysl.

**Nález S-06 (P1) — filtry mají tři různá rozvržení a nekonzistentní labely.**
`InvoicesPage.jsx:53-71` má nad selectem viditelný `<label>Stav</label>` a řádek `align-items-end`;
`ReceiptsPage.jsx:125-166` má dva selecty **bez labelu** (jen `<option>` „Všechny stavy" / „Všechny typy");
ostatní seznamy nemají select vůbec. Checkbox „Končící STK" (`VehiclesPage.jsx:76-83`) a „Nízká
dostupnost" (`WarehousePage.jsx:133-140`) jsou psané inline, zatímco „Jen aktivní" je komponenta —
dva vizuálně stejné prvky, dvě implementace.

**Nález S-07 (P2) — placeholdery hledání nemají jednotný tvar.** „Jméno, příjmení nebo název firmy",
„VIN, SPZ, model, značka, zákazník", „**č**íslo zakázky, zákazník nebo vozidlo" (malé písmeno,
`OrdersPage.jsx:65`), „SKU nebo název dílu", „Číslo faktury, zákazník, VIN nebo SPZ",
„Číslo dokladu nebo dodavatel", „Uživatelské jméno nebo email", „Název, IČO nebo město".

**Nález S-08 (P2) — `PaginatorRounded` má třídu `card-footer` bez karty.** `PaginatorRounded.jsx:10`
používá `card-footer bg-white`, ale komponenta není uvnitř `.card` — třída jen přidává padding a rámeček
nahodile. Select velikosti stránky má `aria-label="Default select example"` (`PaginatorRounded.jsx:14`) —
zapomenutý text ze šablony Bootstrapu. Výchozí velikost stránky se liší: 5 (zákazníci, vozidla, zakázky,
dodavatelé, faktury) vs. 10 (sklad, příjemky, uživatelé). Stránkování chybí na `StockTakesPage` a `LowStockPage`.

### 2.5 Formuláře

Dva **různé** vzory rozvržení:

- **A — plochý formulář se sekcemi:** `<h5 class="text-primary border-bottom pb-2">` + `row`/`col-md-*`,
  Bootstrap validace `needs-validation` + `was-validated`, dole `Zpět` + `Uložit`.
  `CustomerForm.jsx`, `VehicleForm.jsx`, `SupplierForm.jsx`, `WarehouseForm.jsx`, `UserForm.jsx`, `OrderForm.jsx`.
- **B — kartový formulář:** `<section class="card border-0 shadow-sm">` + `<h3 class="h6 text-uppercase text-muted">`,
  bez `needs-validation`, dole jen `Uložit`. Jediný výskyt: `CompanyProfilePage.jsx:74-164`.

Další rozdíly uvnitř vzoru A:

| Věc | Rozdíl |
|---|---|
| Obal stránky | `VehicleForm.jsx:111` a `WarehouseForm.jsx:40` mají `container mt-4` (max-width 960 px, centrováno); `CustomerForm`, `SupplierForm`, `UserForm` nemají nic (plná šířka); `OrderForm.jsx:170` má `container mt-4` |
| Povinná pole | `*` s `text-danger` má `OrderForm.jsx:246,266`, `CompanyProfilePage.jsx:80`, `GoodsReceiptImportModal.jsx:135,164`, `ImportProductFormModal.jsx:55`, `OrderItemFormModal`; **nemá** `CustomerForm`, `VehicleForm`, `SupplierForm`, `UserForm`, `WarehouseForm` — tam se povinnost pozná až po odeslání |
| Popisek tlačítka uložení | „Uložit změny" (`CustomerForm:299`, `VehicleForm:288`, `SupplierForm:129`) vs. „Uložit" (`WarehouseForm:135`, `UserForm:102`, `CompanyProfilePage:162`) vs. „Vytvořit zakázku" (`OrderForm:326`) |
| Jednotky v labelu | `[km]`, `[ccm]`, `[kW]`, `[Kč]`, `[%]`, `[Kč bez DPH]` — tvar `<span class="text-muted small">[x]</span>`; `CompanyProfilePage` a `SupplierForm` jednotky nemají vůbec |
| Chyby validace | vždy `invalid-feedback` pod polem; **nikde** není souhrn chyb nahoře ani skok na první chybu |

**Nález S-09 (P0) — editace zákazníka spadne na bílou stránku.** `CustomersPageEdit.jsx:18-33` sestavuje
`initialData` **bez** klíčů `billingAddress`, `contactAddress` a `hasSeparateContact`, ale
`CustomerForm.jsx:166` (a dále 173, 182, 189, 197) čte `formData.billingAddress.street` bez ochrany.
Ověřeno v prohlížeči: `/customers/1/edit` vyrenderuje prázdnou stránku, v konzoli
`An error occurred in the <CustomerForm> component`. Aplikace nemá error boundary, takže se
neukáže žádná hláška — uživatel vidí jen bílo a musí použít sidebar.

**Nález S-10 (P1) — `was-validated` maluje zelené fajfky i do prázdných nepovinných polí.** Ověřeno
na `/customers/new`: po kliknutí na „Uložit změny" dostanou zelenou ✓ i prázdné „Datum narození",
„Email", „Telefon" a „Interní poznámka". Vizuální šum, který odvádí pozornost od skutečných chyb.
Zdroj: `CustomerForm.jsx:57` (a stejně ve všech ostatních formulářích) — `was-validated` zapíná
v Bootstrapu obojí zpětnou vazbu.

**Nález S-11 (P2) — „Zákazník" ve `VehicleForm` nemá label a drží prázdné místo.**
`VehicleForm.jsx:118-132`: `AutocompletePair` s `label=""`, obalený `style={{marginTop: -1 + 'em'}}`,
pod ním odstavec s `invisible` placeholderem „placeholder", který rezervuje výšku. Ruční záplata layoutu.

### 2.6 Modaly

**Devět** ručně psaných modalů, všechny stejným způsobem: `<div className="modal show d-block"
style={{backgroundColor: 'rgba(0,0,0,0.5)'}}>` — `ConfirmDialog.jsx:7`, `MileageFormModal.jsx:65`,
`OrderItemFormModal`, `ImportProductFormModal.jsx:41`, `GoodsReceiptImportModal.jsx:87`,
`InvoiceCreateFormModal.jsx:30`, `ResetPasswordModal.jsx:36`, `ChangePasswordModal.jsx:45`,
`StockMovementModal.jsx:117`, plus **desátý inline** přímo ve stránce (`ReceiptsPage.jsx:230-287`).

**Nález S-12 (P1) — modaly nemají chování modalu.** Ověřeno v prohlížeči (modal položky zakázky):
`Escape` modal nezavře, `document.body` nemá zámek scrollu (`overflow: visible` — pozadí se scrolluje),
neexistuje `.modal-backdrop` element, není focus trap ani `aria-modal`. Kliknutí mimo dialog nezavírá.

**Nález S-13 (P1) — rozdíly mezi modaly navzájem:**

| Modal | Křížek ✕ | Pořadí tlačítek | Text zrušení |
|---|---|---|---|
| `ConfirmDialog` | ne | Ne / Ano | „Ne" (parametrizovatelné) |
| `MileageFormModal` | ano | Zrušit / Uložit | „Zrušit" |
| `GoodsReceiptImportModal` | ano | Zrušit / Nahrát | „Zrušit" |
| `InvoiceCreateFormModal` | ano | Zrušit / Vytvořit | „Zrušit" |
| `ImportProductFormModal` | ano | **Import / Zrušit** (obráceně, `:92-100`) | „Zrušit" |
| `StockMovementModal` | **ne** | Zrušit / Uložit pohyb | „Zrušit" |
| `ChangePasswordModal` | **ne** | Zrušit / Změnit heslo | „Zrušit" |
| `ResetPasswordModal` | **ne** | Zrušit / Resetovat | „Zrušit" |
| `ReceiptsPage` (inline) | ano | Zrušit / Založit a vyplnit | „Zrušit" |

Chyby uvnitř modalu: většina `<div className="alert alert-danger py-2">` v těle,
ale `ResetPasswordModal.jsx:45-48` používá `is-invalid` na inputu a `StockMovementModal.jsx:78-89`
si validuje ručně a chyby skládá do jednoho stringu.

`ConfirmDialog` se navíc používá i jako **formulář**: `ReceiptReviewPage.jsx:397-434` a
`StockTakePageDetail.jsx:216-232` vkládají do `message` celé `<textarea>` — potvrzovací dialog tak plní
roli, na kterou nebyl navržen (nemá `required`, focus ani submit na Enter).

### 2.7 Badge a stavové barvy

Pět vizuálních stylů badge (kompletní výčet v tabulce §1). Konkrétně:

- **`text-bg-*`** (plná barva) — stavy zakázky (`format.js:78-86`), faktury (`format.js:158-163`),
  příjemky (`format.js:520-528`), inventury (`StockTakesPage.jsx:9-11`, `StockTakePageDetail.jsx:126-127` —
  **duplicitní mapa**, ne v `format.js`), STK (`format.js:401-416`), zdroj tachometru (`format.js:309-314`),
  typ položky zakázky (`OrderItemTable.jsx:19`, `OrdersPageDetail.jsx:277`), role uživatele
  (`UserTable.jsx:43`), párování řádku příjemky (`ReceiptDraftLinesTable.jsx:25-35`);
- **`bg-*-subtle text-*-emphasis rounded-pill`** — „● Aktivní/Neaktivní" na detailech
  (`CustomersPageDetail.jsx:271-272`, `VehiclesPageDetail.jsx:374-375`, `OrdersPageDetail.jsx:228-229`,
  `SuppliersPageDetail.jsx:200-201`, `WarehousePageDetail.jsx:71-72`, `ProductTable.jsx:86-87`),
  typ zákazníka (`CustomersPageDetail.jsx:277-278`), palivo (`VehiclesPageDetail.jsx:210`),
  „nízká zásoba" (`ProductTable.jsx:77`), „aktuální" (`MileageHistoryTable.jsx:41`);
- **`bg-*-subtle` bez pill** — souhlasy (`CustomersPageDetail.jsx:283-284`), „výchozí"
  (`CustomersPageDetail.jsx:175`), „neaktivní" (`SupplierTable.jsx:39`), důvod vratky (`WarehousePageDetail.jsx:206`);
- **vlastní CSS třídy** `.badge-individual` / `.badge-company` (`index.css:34-35`) — jediné použití
  `CustomerTable.jsx:40`; duplikují to, co `CustomersPageDetail.jsx:277-278` řeší Bootstrapem;
- **`rounded-pill` vs. hranatý u téhož stavu:** stav zakázky je pill v `OrderTable.jsx:48`
  i `OrdersPageDetail.jsx:82`, ale stav faktury je hranatý (`InvoiceTable.jsx:79`, `InvoicesPageDetail.jsx:70`)
  a stav příjemky taky (`ReceiptsPage.jsx:196`).

**Nález S-14 (P1) — „aktivní/neaktivní" má čtyři různá znění i styly:** „● Aktivní" (pill, subtle) na
detailech, „neaktivní" (malé, subtle, bez pill) v `SupplierTable.jsx:39`, „Aktivní"/„**Deaktivovaný**"
(pill, `text-bg-*`) v `UserTable.jsx:48-50`, a v ostatních seznamech (zákazníci, vozidla, zakázky)
stav není vidět **vůbec** — přestože filtr „Jen aktivní" lze vypnout.

**Nález S-15 (P1) — `text-bg-light` je prakticky neviditelný.** Stav zakázky `DIAGNOSIS`
(`format.js:80`) a zdroj tachometru `OTHER` (`format.js:313`) se vykreslí jako téměř bílý badge
na bílém pozadí — ověřeno na `/orders` (řádek ZAK-2026-0002).

**Nález S-16 (P2) — role se zobrazují jako syrové enumy.** `UserTable.jsx:43` vypisuje `ROLE_ADMIN`,
`ROLE_MANAGER`, `ROLE_CUSTOMER` — v rozporu s konvencí „enum labely centrálně v `format.js`"
(`konvence.md` §17).

### 2.8 Alerty a hlášky

Dva nezávislé kanály:

1. **Toasty** — `AlertContext` + `AlertContainer.jsx:9` (`position-fixed top-0 end-0 p-2`), auto-hide 15 s.
   Typy: `success`, `danger`, ale i `info` (`StockTakePageDetail.jsx:76,114`, `ReceiptReviewPage.jsx:217,237`) —
   `frontend.md` §6 přitom uvádí jen success/danger.
2. **Inline alerty ve stránce** — `alert alert-danger py-2` (`StockTakesPage.jsx:56`,
   `StockTakePageDetail.jsx:137`, `ReceiptReviewPage.jsx:274`, `CompanyProfilePage.jsx:71`),
   `alert alert-success` (`CompanyProfilePage.jsx:70`, `LowStockPage.jsx:44`),
   `alert alert-light border` jako informační poznámka (`StockTakePageDetail.jsx:148`,
   `StockMovementModal.jsx:206`), `alert alert-warning`, `alert alert-secondary`.

**Nález S-17 (P1) — není pravidlo, kdy toast a kdy inline.** Tatáž třída operací se hlásí různě:
uložení profilu firmy → inline zelený alert (`CompanyProfilePage.jsx:70`), uložení soupisu inventury →
toast (`StockTakePageDetail.jsx:85`), uložení zákazníka → toast (`CustomersPageCreate.jsx:71`),
chyba načtení příjemky → inline červený alert **místo celé stránky** (`ReceiptReviewPage.jsx:246`),
chyba načtení příjemek v seznamu → toast (`ReceiptsPage.jsx:62`).

**Nález S-18 (P2) — toast má z-index 11, modal 1055.** `Alert.jsx:18` obaluje alert `style={{zIndex: 11}}`;
otevřený modal (z-index 1055) toast překryje, takže hláška o výsledku akce spuštěné z modalu není vidět.

**Nález S-19 (P2) — chybové texty nemají jednotný tvar.** „Akci se nepodařilo provést.",
„Operace se nezdařila.", „Zákazníka se nepodařilo editovat. Zkuste to znovu.",
„Položku se nepodařilo uložit. Zkuste to znovu" (bez tečky, `OrderItemsWrapper.jsx:169`),
„Doklad se nepodařilo zpracovat.".

### 2.9 Typografie, odstupy, ikonografie

`css/reset.css:14-18` maže marginy nadpisům i odstavcům; `index.css` je restituuje jen pro tabulky a sidebar.
Vertikální rytmus tak vzniká výhradně z utilit (`mb-3`, `mb-4`, `g-3`), a proto se liší stránku od stránky:
mezera pod `h1` je 0 (seznamy — odstup dělá až `mb-3` filtrového řádku), 4 (`CompanyProfilePage.jsx:65`),
nebo žádná (flex hlavičky).

Ikony: **dvě knihovny**. Bootstrap Icons (`bi bi-*`) v sidebaru, tlačítkách a detailech;
**MUI ikony** (`@mui/icons-material`) výhradně v `TableRowActionMenu` (`CustomerTable.jsx:5-8` atd.).
Vedle toho **emoji** jako ikona entity: 🚗 (`VehiclesPageDetail.jsx:151`), 📦 (`WarehousePageDetail.jsx:65`),
zatímco zákazník/zakázka/dodavatel mají kolečko s iniciálami (`getInitials`). Dva vizuální jazyky pro totéž.

MUI se dále používá pro `Pagination` (`PaginatorRounded.jsx:1`) a `Menu`/`IconButton`
(`TableRowActionMenu.jsx:3`). Jinde nikde — MUI tedy táhne do bundlu Emotion + celý theming kvůli
třem prvkům.

**Nález S-20 (P2) — mrtvé CSS.** `index.css:38-42` `#loading-overlay` a `index.css:45-46` `.empty-state`
nejsou nikde použité (ověřeno grepem). `.empty-state` přitom přesně popisuje vzor, který stránky
řeší pokaždé jinak (S-22).

**Nález S-21 (P2) — mrtvé importy.** `CustomersPageDetail.jsx:14` importuje `VehicleTable`
a `:16` `logout` — ani jedno se v souboru nepoužívá. `ImportProductFormModal.jsx:27` má
`const canImport = true;`, které se nikde nečte.

### 2.10 Prázdné, načítací a chybové stavy

**Prázdný stav** — čtyři varianty:
- řádek v `<tbody>` s `colSpan`: „Žádné příjemky." (`ReceiptsPage.jsx:183`), „Zatím žádná inventura
  neproběhla." (`StockTakesPage.jsx:70-72`), „Žádné položky skladu neodpovídají filtru."
  (`ProductTable.jsx:58-60`), „Žádné faktury" (`InvoiceTable.jsx:122`, bez tečky, jiné zarovnání);
- odstavec: „Zakázka nemá žádné položky." (`OrderItemTable.jsx:80`), „Žádné šarže. Díl zatím nebyl
  naskladněn." (`WarehousePageDetail.jsx:143`), „Žádná adresa" (`CustomersPageDetail.jsx:168`);
- zelený alert: „Všechny hlídané díly jsou nad minimem." (`LowStockPage.jsx:44-47`);
- **nic** — `CustomerTable`, `VehicleTable`, `OrderTable`, `SupplierTable`, `UserTable` při prázdném
  seznamu vykreslí jen hlavičku tabulky.

**Načítání** — čtyři varianty: `<div className="p-4 text-muted">Načítám...</div>` (6×, tři tečky),
totéž s výpustkou „Načítám…" (4×), holé `<div>Načítám…</div>` bez stylu (`ReceiptReviewPage.jsx:245`),
`<div>Načítám</div>` bez teček (`CustomerVehiclesTable.jsx:20`), a spinner (`RequireAuth`,
`VehicleForm.jsx:185`, `VehiclesPageDetail.jsx:274`). Skeleton nikde.

**Nález S-22 (P1) — chybový stav stránky nenabízí cestu ven.** Ověřeno na `/warehouse/receipts/1/review`
(neexistující ID): stránka vykreslí jen `<div className="alert alert-danger">Příjemku se nepodařilo
načíst.</div>` (`ReceiptReviewPage.jsx:246`) — bez nadpisu, bez tlačítka zpět. Stejně
`StockTakePageDetail.jsx:44`. Jinde se chyba řeší toastem + `navigate` pryč
(`OrdersPageDetail.jsx:37-38`, `SuppliersPageDetail.jsx:24-25`) — třetí chování je pád bez hlášky (S-09).

### 2.11 České texty

Konzistentní je infinitiv v tlačítkách („Vytvořit", „Uložit", „Editovat", „Zamítnout") a vykání
v hláškách („Opravdu chcete deaktivovat…?"). Odchylky:

- **Anglicismus v hlavičce tabulky:** „Status" (`OrderTable.jsx:31`) vs. „Stav" všude jinde
  (`ProductTable.jsx:51`, `UserTable.jsx:32`, `InvoiceTable.jsx:65`, `ReceiptsPage.jsx:176`).
- **„e-mail" / „Email" / „E-MAIL":** `CustomerTable.jsx:27` a `SupplierTable.jsx:28` píší `e-mail`,
  `UserTable.jsx:29` `Email`, formuláře `Email` — vizuálně to sjednotí `text-transform: uppercase`
  v CSS, ve zdroji ne.
- **Vlastní název akce pro totéž:** „Přidat nový záznam" (`WarehousePage.jsx:148`) vs. „Vytvořit …"
  na všech ostatních seznamech; „Import faktury (PDF)" (`WarehousePage.jsx:145`) vs.
  „Import dokladu (PDF)" (`ReceiptsPage.jsx:154`) — přitom otevírají **týž** modal.
- **Zkratky v hlavičkách:** „Mn." / „Jedn." (`OrderItemTable.jsx:66-67`, `OrdersPageDetail.jsx:266-267`)
  vs. „Množství" / „MJ" (`InvoicesPageDetail.jsx:148-149`, `ProductTable.jsx:47`).
- **Anglický text v datech:** na `/vehicles/1/detail` je v historii tachometru poznámka
  „Initial reading migrated from vehicles.current_mileage_km" — technický text ze seedu viditelný obsluze.
- **Tři tečky vs. výpustka:** „Načítám..." vs. „Načítám…", „Ukládám…", „Zakládám…" — v jednom
  produktu obojí.

### 2.12 Navigace

`Sidebar.jsx` — plochý `<ul>`, kde „podřízenost" je jen odsazení `ps-5` (`Sidebar.jsx:49,59,64,69,74`).

**Nález S-23 (P1) — odsazení lže o hierarchii.** „Dodavatelé" (`/suppliers`) je odsazen jako podpoložka
Skladu, ale je to samostatný modul — při jeho otevření se „Sklad" nezvýrazní (ověřeno).
„Nastavení firmy" (`/invoices/settings`) je odsazeno pod Fakturami, ale jde o globální nastavení firmy.

**Nález S-24 (P1) — dvě položky svítí jako aktivní zároveň.** `NavLink` bez `end` matchuje prefix,
takže na `/invoices/settings` je aktivní „Faktury" **i** „Nastavení firmy" a na `/warehouse/receipts`
„Sklad" **i** „Příjemky" (ověřeno na screenshotech). Naproti tomu na `/warehouse/1/detail` je aktivní
jen „Sklad" — chování tedy vypadá nahodile.

**Nález S-25 (P2) — podpoložky nejdou sbalit a zabírají místo trvale.** Sidebar má 13 položek,
z toho 4 odsazené; nic se neskrývá, takže i uživatel, který sklad nepoužívá, ho má stále na očích.

**Nález S-26 (P2) — `logout` odkaz.** `Sidebar.jsx:108` `<a href="#" onClick={logout}>` bez
`preventDefault` (evidováno už v `frontend.md` §9); po odhlášení zůstane v URL `#`.
Sousední „Změnit heslo" `preventDefault` má (`:105`) — nekonzistence v jednom bloku.

---

## 3. ÚKOL 2a — Souhrnný katalog nekonzistencí

| ID | Priorita | Nález | Hlavní důkaz | Dopad |
|---|---|---|---|---|
| S-09 | **P0** | Editace zákazníka padá na bílou stránku | `CustomersPageEdit.jsx:18-33` × `CustomerForm.jsx:166` | Funkce je nepoužitelná, bez hlášky |
| S-01 | **P0** | Layout se nepřizpůsobí užšímu oknu; sloupec Akce nedostupný | `index.css:4-25`, `WarehousePage.jsx:122-152` | Aplikace nepoužitelná pod ~1250 px |
| S-05 | P1 | 4 způsoby řádkových akcí + `cursor:pointer` na neklikatelných řádcích | `index.css:28`, `InvoiceTable.jsx:85` | Uživatel neví, kde akce hledat |
| S-12 | P1 | Modaly bez Esc, focus trapu a zámku scrollu | `ConfirmDialog.jsx:7` (+8 dalších) | Nestandardní chování, přístupnost |
| S-13 | P1 | Modaly se liší křížkem i pořadím tlačítek | `ImportProductFormModal.jsx:92-100` | Náhodné kliknutí na destruktivní volbu |
| S-02 | P1 | 5 variant nadpisu stránky | §2.2 tabulka | Vizuální nesourodost |
| S-03 | P1 | „Zpět" nahodile a ve dvou významech | `WarehousePageDetail.jsx:78` × `CustomersPageDetail` | Nepředvídatelná navigace |
| S-04 | P1 | Pořadí/styl akcí v hlavičce detailu | `WarehousePageDetail.jsx:77-92` | Hlavní akce nevyčnívá |
| S-06 | P1 | Tři rozvržení filtrů, labely jen někde | `InvoicesPage.jsx:63` × `ReceiptsPage.jsx:134` | Nesourodé seznamy |
| S-14 | P1 | „Aktivní/neaktivní" ve 4 zněních, v seznamech chybí | `UserTable.jsx:48` × `SupplierTable.jsx:39` | Stav záznamu není čitelný |
| S-15 | P1 | `text-bg-light` je neviditelný | `format.js:80,313` | Stav zakázky nelze přečíst |
| S-17 | P1 | Toast vs. inline alert bez pravidla | `CompanyProfilePage.jsx:70` × `StockTakePageDetail.jsx:85` | Uživatel hlášku přehlédne |
| S-22 | P1 | Chybový stav bez cesty ven | `ReceiptReviewPage.jsx:246` | Uživatel uvázne |
| S-23 | P1 | Odsazení v menu lže o hierarchii | `Sidebar.jsx:49,74` | Špatný mentální model |
| S-24 | P1 | Dvě položky menu aktivní zároveň | `Sidebar.jsx:44-52` | Nejasné „kde jsem" |
| S-10 | P1 | Zelené fajfky v prázdných nepovinných polích | `CustomerForm.jsx:57` | Šum při opravě chyb |
| S-16 | P1 | Syrové `ROLE_*` v UI | `UserTable.jsx:43` | Technický text u obsluhy |
| S-07 | P2 | Placeholdery bez jednotného tvaru | `OrdersPage.jsx:65` | Kosmetika |
| S-08 | P2 | `card-footer` bez karty, „Default select example" | `PaginatorRounded.jsx:10-14` | Kosmetika + a11y |
| S-11 | P2 | Autocomplete zákazníka bez labelu, ruční `marginTop` | `VehicleForm.jsx:118-132` | Křivé zarovnání |
| S-18 | P2 | Toast pod modalem (z-index 11 vs. 1055) | `Alert.jsx:18` | Hláška není vidět |
| S-19 | P2 | Nejednotné chybové texty | §2.8 | Kosmetika |
| S-20 | P2 | Mrtvé CSS `.empty-state`, `#loading-overlay` | `index.css:38-46` | Úklid |
| S-21 | P2 | Mrtvé importy a proměnná | `CustomersPageDetail.jsx:14,16` | Úklid |
| S-25 | P2 | Menu nejde sbalit | `Sidebar.jsx:22-90` | Přeplněný sidebar |
| S-26 | P2 | `logout` bez `preventDefault` | `Sidebar.jsx:108` | `#` v URL |
| S-27 | P2 | Duplicitní lokální formátovače | níže | Rozjeté formátování |
| S-28 | P2 | Řazení tabulek neexistuje, `sortBy` je zamrzlý stav | `CustomersPage.jsx:16-17` | Chybějící funkce, mrtvý stav |

**S-27 detailně — duplicitní formátovače vedle `format.js`:**
`formatQuantity` je definovaný 4× (`ProductTable.jsx:11-14`, `WarehousePageDetail.jsx:10-13`,
`StockTakePageDetail.jsx:9-12`, `LowStockPage.jsx:7-10`), `formatMoney` 2×
(`ProductTable.jsx:17-20`, `WarehousePageDetail.jsx:15-18`) — a `formatMoney` dává **jiný výstup**
než centrální `formatCurrency` (`format.js:14-17`): „162,20 Kč" ručním skládáním vs. `Intl` s pevnou
mezerou. Vedle toho se čísla formátují i inline: `vehicle.currentMileageKm.toLocaleString('cs-CZ')`
(`VehiclesPageDetail.jsx:179`), `reading.mileageKm.toLocaleString("cs-CZ")` (`MileageHistoryTable.jsx:47`),
`Number(b.quantityRemaining).toLocaleString("cs-CZ")` (`StockMovementModal.jsx:178`). V tabulce položek
zakázky se ceny nezformátují vůbec (`OrderItemTable.jsx:23-25` — „500", „750"), zatímco souhrn
pod ní používá `formatCurrency` („1 625,00 Kč") — na jedné obrazovce dva zápisy téže veličiny.
Mapa stavů inventury je duplicitní mimo `format.js` (`StockTakesPage.jsx:8-12` a
`StockTakePageDetail.jsx:126-129`).

---

## 4. ÚKOL 2b — Návrh UI konvencí (rozšíření `docs/frontend.md`)

Návrh nové kapitoly „§10 UI konvence" — jeden vzor pro každý stavební prvek. Vzorové snippety
předpokládají tři nové sdílené komponenty (`PageHeader`, `StatusBadge`, `EmptyState`, `Modal`)
a jeden nový CSS soubor pro nápovědu.

### 4.1 Stránka a hlavička

Jediná komponenta `PageHeader` pro **všechny** typy stránek:

```jsx
// components/PageHeader.jsx
export default function PageHeader({ title, subtitle, meta, badges, backTo, actions }) {
    const navigate = useNavigate();
    return (
        <div className="page-header d-flex flex-wrap align-items-start gap-3 mb-4">
            <div className="me-auto">
                <div className="d-flex align-items-center gap-2 flex-wrap">
                    {backTo && (
                        <button className="btn btn-sm btn-outline-secondary"
                                onClick={() => navigate(backTo)} aria-label="Zpět">
                            <i className="bi bi-arrow-left" />
                        </button>
                    )}
                    <h1 className="h3 mb-0">{title}</h1>
                    {badges}
                </div>
                {subtitle && <div className="text-muted small mt-1">{subtitle}</div>}
            </div>
            {actions && <div className="d-flex flex-wrap gap-2">{actions}</div>}
        </div>
    );
}
```

Pravidla:
- **`h1.h3` na každé stránce** (seznam, detail, formulář) — jedna velikost, jedna úroveň.
- Doprovodná identifikace (číslo zákazníka, VIN, SKU) jde do `subtitle`, ne do nadpisu.
- **Akce vpravo, pořadí zleva doprava: neutrální → hlavní → destruktivní.** Barvu určuje
  sémantika z **§7.1** (modrá plná = hlavní vratná akce, zelená plná = nevratný posun procesu,
  šedý obrys = neutrální, červený obrys = rušící). Na obrazovce je nejvýš **jedno plné** tlačítko.
- **„Zpět" je ikonové tlačítko vlevo od nadpisu** — nikdy mezi akcemi vpravo, aby se nepletlo
  se „Zpět" ve smyslu „zrušit editaci" (to se přejmenuje na **„Zrušit"**).
- Hlavička se **láme** (`flex-wrap`), takže neexistuje stav, kdy se tlačítko zúží na 26 px.

### 4.2 Seznamová stránka

```jsx
<PageHeader title="Zákazníci"
            actions={<button className="btn btn-primary" onClick={…}>
                       <i className="bi bi-plus-lg me-1" />Nový zákazník
                     </button>} />

<ListToolbar>                       {/* row g-2 align-items-end, láme se */}
    <SearchFilter value={search} onChange={setSearch}
                  label="Hledat zákazníka" placeholder="Jméno, příjmení nebo název firmy" />
    <SelectFilter label="Stav" value={status} onChange={setStatus} options={…} />
    <ToggleFilter id="activeOnly" label="Jen aktivní" checked={…} onChange={…} />
</ListToolbar>

<DataTable columns={…} rows={…} rowActions={…} emptyText="Zatím žádní zákazníci." />
<Paginator … />
```

Pravidla:
- **Popisek nad každým filtrem** (i nad hledáním) — žádné `visually-hidden`; sjednoceno pomocí
  `SearchFilter`/`SelectFilter`/`ToggleFilter` (nahrazují `InputFilter` a `CheckBox`, ruší hardcodovaná `id`).
- Tlačítko vytvoření je **v hlavičce**, ne mezi filtry.
- Tabulka vždy v `table-responsive`, vždy `table table-hover align-middle`, vždy `scope="col"`.
- **Jediný vzor řádkové akce: třitečkové menu ve sloupci „Akce"** (`text-end`). Stavové přechody
  (faktury) patří do menu, ne do `btn-group`. Řádek je klikatelný pouze tehdy, když ho tabulka
  označí `clickable` — a `cursor:pointer` se pak nastaví jen na ni (zruší se globální `index.css:28`).
- Prázdný stav vždy přes `EmptyState` (§4.6), nikdy prázdné `tbody`.

### 4.3 Detail

```jsx
<PageHeader title={customer.displayName} subtitle={customer.customerNumber}
            badges={<><StatusBadge active={customer.active} /><TypeBadge … /></>}
            backTo="/customers"
            actions={<>
                <button className="btn btn-outline-secondary">…Editovat</button>
                <button className="btn btn-outline-danger">…Deaktivovat</button>
            </>} />

<MetricRow>…</MetricRow>              {/* dnešní MetricCard, vytažený do components/ */}

<div className="row g-3">
    <div className="col-lg-6">
        <DetailCard title="Kontakt"> <dl className="row mb-0">…</dl> </DetailCard>
    </div>
</div>
```

- `MetricCard` je dnes zkopírovaný **4×** (`CustomersPageDetail.jsx:287-300`,
  `VehiclesPageDetail.jsx:378-391`, `OrdersPageDetail.jsx:232-245`, `WarehousePageDetail.jsx:244-257`)
  → jedna komponenta `MetricCard` + `MetricRow`.
- `DetailCard` = `<section className="card border-0 shadow-sm mb-3">` + `<h2 className="h6 text-uppercase text-muted mb-3">`.
  Sekce v detailu tedy zůstávají u dnešního „h6 uppercase muted" vzoru (je nejrozšířenější a čitelný).
- Ikona entity: **jednotně kolečko s iniciálami** (`getInitials`) — emoji 🚗/📦 se ruší, protože
  polovina entit ho nemá a nejde odvodit z dat.
- Vnořené tabulky (šarže, pohyby, tachometr, vozidla zákazníka) vždy uvnitř `DetailCard` s nadpisem —
  dnes visí tabulka vozidel na detailu zákazníka bez jakéhokoli nadpisu (`CustomersPageDetail.jsx:248-251`).

### 4.4 Formulář

Jediný vzor = **kartový**, protože se lépe láme a odděluje sekce i bez barevných linek:

```jsx
<PageHeader title="Editace zákazníka" backTo="/customers" />

<form ref={formRef} className={`needs-validation ${validated ? "was-validated" : ""}`} noValidate>
    <FormSection title="Základní údaje">
        <div className="row g-3">
            <div className="col-md-4">
                <label className="form-label" htmlFor="firstName">
                    Jméno <RequiredMark />
                </label>
                <input id="firstName" name="firstName" className="form-control"
                       value={formData.firstName} onChange={handleChange} required />
                <div className="invalid-feedback">Zadejte jméno zákazníka</div>
            </div>
        </div>
    </FormSection>

    <FormActions onCancel={onCancel} saving={saving} submitLabel="Uložit" />
</form>
```

Pravidla:
- `FormSection` = `<section className="card border-0 shadow-sm mb-3">` + `h2.h6.text-uppercase.text-muted`
  — **stejný vzor jako `DetailCard`**, takže detail a formulář vypadají jako dvě podoby téže stránky.
- **Povinná pole vždy `<RequiredMark />`** (`<span className="text-danger" aria-hidden="true">*</span>`)
  + jednou nahoře vysvětlivka „Pole označená * jsou povinná".
- Řádky vždy `row g-3` + `col-md-*`; **žádný `container`** — šířku drží layout, ne formulář.
- `FormActions` = `d-flex justify-content-end gap-2 border-top pt-3`, tlačítka **„Zrušit"** (secondary)
  a **„Uložit"** (primary, se stavem `saving` → „Ukládám…"). Slovo „Zpět" se ve formuláři nepoužívá.
- Do `index.css` přidat potlačení zelené zpětné vazby (řeší S-10):
  ```css
  .was-validated .form-control:valid,
  .was-validated .form-select:valid,
  .was-validated .form-check-input:valid {
      border-color: var(--bs-border-color);
      background-image: none;
  }
  .was-validated .form-check-input:valid ~ .form-check-label { color: inherit; }
  ```
- Po neúspěšné validaci **skočit na první chybné pole**:
  `formRef.current.querySelector(':invalid')?.scrollIntoView({block:'center'})` + `focus()`.

### 4.5 Modal

Jedna komponenta `Modal` (obálka), nad ní `ConfirmDialog` a `FormModal`:

```jsx
// components/Modal.jsx — jediné místo, kde je ".modal show d-block"
export default function Modal({ show, title, size, onClose, children, footer, closable = true }) {
    useEffect(() => {
        if (!show) return;
        document.body.classList.add("modal-open");
        const onKey = (e) => { if (e.key === "Escape" && closable) onClose(); };
        document.addEventListener("keydown", onKey);
        return () => { document.body.classList.remove("modal-open");
                       document.removeEventListener("keydown", onKey); };
    }, [show, closable, onClose]);

    if (!show) return null;
    return (
        <>
            <div className="modal-backdrop fade show" />
            <div className="modal fade show d-block" role="dialog" aria-modal="true"
                 onMouseDown={(e) => { if (closable && e.target === e.currentTarget) onClose(); }}>
                <div className={`modal-dialog modal-dialog-centered ${size ?? ""}`}>
                    <div className="modal-content">
                        <div className="modal-header">
                            <h2 className="modal-title fs-5">{title}</h2>
                            {closable && <button type="button" className="btn-close" onClick={onClose} />}
                        </div>
                        <div className="modal-body">{children}</div>
                        <div className="modal-footer">{footer}</div>
                    </div>
                </div>
            </div>
        </>
    );
}
```

Pravidla: **vždy křížek**, **vždy Esc a klik na pozadí** (kromě probíhajícího ukládání),
**vždy pořadí `Zrušit` → hlavní akce**, chyby uvnitř modalu vždy `alert alert-danger py-2` nahoře v těle.
Potvrzovací dialog s doplňujícím polem (důvod storna/zamítnutí) přestane být `ConfirmDialog` a stane se
`FormModal` — dostane `required` i submit na Enter.
`z-index` toastu zvýšit nad modal (`AlertContainer` → `z-index: 1080`).

### 4.6 Badge, prázdný stav, alert

```jsx
// components/StatusBadge.jsx — nahrazuje 6 kopií + .badge-individual/.badge-company
export default function StatusBadge({ tone, children }) {   // tone: success|danger|warning|info|secondary|primary
    return <span className={`badge rounded-pill bg-${tone}-subtle text-${tone}-emphasis`}>{children}</span>;
}
```

- **Jeden vizuální styl badge v celé aplikaci:** `rounded-pill` + `bg-*-subtle text-*-emphasis`
  (jemný, čitelný, nekonkuruje tlačítkům). Plné `text-bg-*` se ruší — tím zmizí i neviditelný
  `text-bg-light` (S-15).
- **Sémantika barev je pevná:** `success` = platné/dokončené/zaplacené, `warning` = čeká na akci
  uživatele, `danger` = chyba/propadlé/stornované, `info` = probíhá, `secondary` = neaktivní/koncept,
  `primary` = nové/přijaté. Všechny mapy zůstávají v `format.js` (včetně dnes duplicitní mapy inventury).
- **Stav „Aktivní/Neaktivní" ukazovat i v seznamech** — jednotné znění „Aktivní" / „Neaktivní".

```jsx
// components/EmptyState.jsx — konečně využije .empty-state z index.css
export default function EmptyState({ icon = "inbox", title, hint, action }) {
    return (
        <div className="empty-state">
            <i className={`bi bi-${icon} d-block`} />
            <p className="mb-1">{title}</p>
            {hint && <p className="small mb-3">{hint}</p>}
            {action}
        </div>
    );
}
```

Alerty — pravidlo:
- **Toast (`addAlert`)** = výsledek akce, po které se pokračuje jinde nebo se stránka překreslí
  (uložení, potvrzení, smazání). Typy `success` | `danger` | `info`; `info` doplnit do `frontend.md`.
- **Inline alert** = trvalý stav obrazovky, který se týká toho, co uživatel právě vidí
  (neprošlé kontroly příjemky, „inventura je uzavřená", chyba načtení).
- **Chyba načtení celé stránky** = `EmptyState` s ikonou `exclamation-triangle`, textem a tlačítkem
  zpět na seznam — nikdy holý alert bez cesty ven (S-22).
- Tvar chybové hlášky: **„<Předmět> se nepodařilo <sloveso>."** + volitelně druhá věta s radou.
  Bez „Zkuste to znovu" (nic nového uživateli neříká), vždy s tečkou.

### 4.7 Formátování hodnot

Vše přes `format.js`; do něj doplnit a všude použít:

```js
export function formatQuantity(value, unit) { … }   // nahradí 4 lokální kopie
export function formatNumber(value) { … }           // nahradí inline toLocaleString
// formatMoney se ruší — jediný zápis peněz je formatCurrency (Intl)
```

---

## 5. ÚKOL 2c — Návrh nové navigace

### 5.1 Cílová struktura menu

Podle zadání se „Nastavení firmy" stěhuje na hlavní úroveň k profilu. Návrh rozdělení sidebaru
do tří bloků:

```
PROVOZ
  Dashboard
  Zákazníci
  Vozidla
  Zakázky
  Faktury

SKLAD                      ← rozbalovací skupina
  Přehled skladu           (/warehouse)
  Příjemky                 (/warehouse/receipts)
  Pod minimem              (/warehouse/low-stock)
  Inventury                (/warehouse/stock-takes)
  Dodavatelé               (/suppliers)

─────────────────────────  (spodní blok, oddělený linkou)
  Nastavení firmy          (/settings/company)
  Uživatelé                (/users)          — jen ROLE_ADMIN
  Nápověda                 (/help)
─────────────────────────
  robert
  Změnit heslo
  Odhlásit se
```

Poznámky:
- **„Sklad" přestává být odkazem** a stane se hlavičkou skupiny; přehled skladu dostane vlastní
  položku „Přehled skladu". Tím zmizí dnešní dvojznačnost, kdy „Sklad" je zároveň stránka i rodič.
- **„Dodavatelé" zůstávají ve skupině Sklad** (patří tam významově), ale skupina se rozbalí i pro
  `/suppliers` — hierarchie a zvýraznění se konečně shodnou (řeší S-23).
- **„Nastavení firmy"** se přesouvá do spodního bloku vedle „Uživatelé" (obojí je konfigurace, ne
  denní provoz) a route se mění na `/settings/company`; stará `/invoices/settings` zůstane jako redirect.
- Spodní blok je odsazen linkou, ne `ps-5` — odsazení `ps-5` se ruší úplně.

### 5.2 Chování rozbalovací skupiny — varianty

| | Varianta | Chování | Pro | Proti |
|---|---|---|---|---|
| **A** | **Accordion s perzistencí** | Klik na hlavičku skupinu rozbalí/sbalí; stav v `localStorage`; skupina se **automaticky rozbalí**, když je aktivní některá její položka | Předvídatelné, respektuje volbu uživatele, přežije refresh | Trocha stavu navíc |
| **B** | Vždy rozbalené (dnešní stav bez `ps-5`) | Nic se neskrývá | Nejjednodušší, vše na dvě kliknutí | Sidebar poroste s každým modulem; skupina nemá smysl |
| **C** | Auto-accordion (rozbalená jen aktivní skupina) | Otevření jiné sekce ostatní sbalí | Sidebar je vždy krátký | Uživateli mizí položky pod rukama; při jedné skupině působí svévolně |
| **D** | Flyout na hover (jako v mini režimu) | Skupina se rozbalí v překryvu | Úsporné | Špatné na dotyku, nefunguje s klávesnicí bez práce navíc |

**Doporučení: A.** Perzistence v `localStorage` (klíč `sidebar.groups`), výchozí stav rozbaleno,
auto-rozbalení aktivní skupiny má přednost před uloženým stavem. Hlavička skupiny je `<button>`
s `aria-expanded` a šipkou `bi-chevron-down`/`bi-chevron-right`.

### 5.3 Aktivní stav

Řeší S-24: `NavLink` dostane **`end`** u každé položky, jejíž cesta je prefixem jiné položky
(`/warehouse`, `/invoices`). Zvýraznění pak platí:

- **aktivní položka** = plné `bg-primary` (dnešní styl);
- **aktivní skupina** (některé dítě je aktivní, skupina sbalená) = hlavička dostane jemné zvýraznění
  `text-white` + tečka/levý pruh, ne plnou modrou — aby se neplela s aktivní položkou;
- detailní a formulářové routy (`/customers/5/edit`) zvýrazní **rodičovskou položku seznamu** —
  to `NavLink` bez `end` dělá správně a zůstane.

### 5.4 Mobil a úzká okna

Cíl (viz otevřené rozhodnutí R-2): aplikace má být použitelná od **768 px** (tablet na šířku).

- **≥ 992 px** — sidebar staticky, jak je dnes (240 px).
- **< 992 px** — sidebar se skryje mimo obraz (`transform: translateX(-100%)`), v obsahu se objeví
  hlavička s tlačítkem ☰ (`bi-list`), sidebar se otevírá jako offcanvas přes obsah s podkladem;
  zavírá se klikem na položku, na podklad a klávesou Esc.
- `#main-content` **ztratí `overflow-x: hidden`**; místo toho každá tabulka dostane `table-responsive`,
  takže se dá vodorovně scrollovat (řeší nedostupný sloupec Akce).
- Hlavičky a lišty filtrů `flex-wrap` — nikdy `flex-nowrap` + `text-truncate` na tlačítku.

Na telefonu (< 576 px) zůstane tabulka scrollovatelná; převod tabulek do „kartového" zobrazení
je mimo rozsah tohoto plánu (viz R-2).

---

## 6. ÚKOL 2d — Stylování nápovědy

**Ověřeno:** `HelpPage.jsx:40` dává obalu třídu `help-article`, ale **žádné CSS pro ni neexistuje**
(grep přes celý frontend nenašel jiný výskyt). Naměřené hodnoty v prohlížeči:
`h1 { margin: 0 }`, `h2 { margin: 0 }`, `p { margin: 0 }`, `ul { margin: 0 }` — protože
`css/reset.css:14-18` marginy maže a nic je pro obsah článků nevrací. Článek je proto jeden slitý blok:
nadpis se dotýká odstavce nad i pod sebou.

Návrh — nový soubor `src/css/help.css` (importovaný v `main.jsx`):

```css
.help-article {
    max-width: 68ch;              /* měřítko řádku pro souvislý text */
    font-size: 1rem;
    line-height: 1.65;
    color: var(--bs-body-color);
}

/* Nadpisy: velký odstup nahoře, malý dole — text patří ke svému nadpisu. */
.help-article h1 { font-size: 1.75rem; line-height: 1.25; margin: 0 0 1rem; }
.help-article h2 { font-size: 1.35rem; line-height: 1.3;  margin: 2.25rem 0 .75rem;
                   padding-bottom: .35rem; border-bottom: 1px solid var(--bs-border-color); }
.help-article h3 { font-size: 1.1rem;  line-height: 1.35; margin: 1.75rem 0 .5rem; }
.help-article > *:first-child { margin-top: 0; }

.help-article p  { margin: 0 0 1rem; }
.help-article ul,
.help-article ol { margin: 0 0 1rem; padding-left: 1.5rem; }
.help-article li { margin-bottom: .35rem; }
.help-article li > ul,
.help-article li > ol { margin: .35rem 0 .5rem; }

.help-article strong { font-weight: 600; }
.help-article code   { background: var(--bs-secondary-bg); padding: .1em .35em;
                       border-radius: 4px; font-size: .9em; }
.help-article blockquote { margin: 0 0 1rem; padding: .75rem 1rem;
                           border-left: 3px solid var(--bs-primary);
                           background: var(--bs-secondary-bg); border-radius: 0 6px 6px 0; }
.help-article hr { margin: 2rem 0; }
.help-article a  { text-decoration: underline; text-underline-offset: 2px; }
```

Doplňkové změny na stránce nápovědy:
- **Nadpis stránky nesmí soupeřit s nadpisem článku.** Dnes je „Nápověda" `h1` (2,5 rem) a hned pod ním
  `h1` článku (2,5 rem). Řešení: `PageHeader title="Nápověda" subtitle={article.title}` a v článcích
  se `#` nadpis vynechá **nebo** se v CSS zmenší na 1,75 rem (výše zvoleno druhé — články zůstanou
  čitelné i samostatně).
- Seznam článků vlevo (`list-group`) je funkční; přidat mu `position: sticky; top: 1.5rem`,
  aby při dlouhém článku neodjížděl.
- Na úzkém okně (< 768 px) dát seznamu `col-12` a článku `col-12` (dnes `col-md-3`/`col-md-9`
  se láme až pod 768 px — to je v pořádku, jen ověřit po zavedení offcanvas sidebaru).
- Zvážit `remark-gfm` (R-4) — dnes se v článcích tabulky nesmí používat.

---

## 7. ÚKOL 2e — Rozhodnutí

> **Stav k 21. 7. 2026:** R-1, R-2, R-3, R-5 a R-7 uživatel **rozhodl** (viz ✅ u variant).
> **Stav k 22. 7. 2026:** R-4 rozhodnuto (**b**, viz níže). Otevřené zůstává už jen **R-6**
> (postupně vs. naráz) — v praxi se vyřešilo samo tím, že plán běží fázi po fázi, tedy variantou **a**.

### R-1 · MUI vs. čistý Bootstrap — ✅ ROZHODNUTO: **a) ponechat v dnešním rozsahu**
MUI je dnes ve třech místech: `TableRowActionMenu` (Menu, IconButton, ikony), `PaginatorRounded`
(Pagination) a ikony v definicích akcí v 7 tabulkách. Kvůli tomu se do bundlu táhne `@mui/material`
+ Emotion.

| | Možnost | Pro | Proti |
|---|---|---|---|
| **a** | **Ponechat MUI v dnešním rozsahu**, jen sjednotit ikony na Bootstrap Icons uvnitř menu | Nulové riziko, dropdown a stránkování fungují a jsou přístupné | Zůstává dvojí knihovna a velikost bundlu |
| b | Nahradit MUI Bootstrap dropdownem + vlastním stránkováním | Jeden vizuální jazyk, menší bundle | Práce navíc, Bootstrap dropdown v tabulce vyžaduje ošetření přetečení |
| c | Rozšířit MUI (např. i na tabulky a formuláře) | Bohatší komponenty | Přepis celého FE, popírá „Bootstrap first" |

**Rozhodnuto: a.** Sjednotí se jen ikony uvnitř třitečkového menu na Bootstrap Icons.
Přechod na čistý Bootstrap (varianta b) se zapisuje do `tech-dluhy.md` jako samostatný pozdější úkol.

### R-2 · Cílová responsivita — ✅ ROZHODNUTO: **a) použitelné od 768 px**
| | Možnost | Znamená |
|---|---|---|
| **a** | **Použitelné od 768 px** (tablet), telefon jen nouzově (tabulky scrollují) | Offcanvas sidebar, `flex-wrap` hlavičky, `table-responsive` všude |
| b | Plná podpora telefonu (≥ 360 px) | Navíc kartové zobrazení tabulek, přepracované formuláře — velký rozsah |
| c | Jen opravit dnešní rozpad na desktopu (≥ 1100 px) | Nejlevnější, ale notebook 1366 px se sidebarem zůstane těsný |

**Rozhodnuto: a.** Servis se obsluhuje z počítače; tablet je reálný (příjem vozidla, inventura),
telefon zatím ne. Varianta b se dá dodělat později bez zahození práce.

### R-3 · Vizuální styl badge — ✅ ROZHODNUTO: **a) jemný pill**
| | Možnost | Vzhled |
|---|---|---|
| **a** | **Jemný pill** `bg-*-subtle text-*-emphasis rounded-pill` všude | Klidný, nekonkuruje tlačítkům; dnes už převažuje na detailech |
| b | Plný `text-bg-*` všude | Výraznější stavy v hustých tabulkách; nutno vyřadit `light` |
| c | Jemný v detailech, plný v tabulkách (dnešní stav kodifikovaný) | Zachovává vzhled, ale zůstávají dvě pravidla |

**Rozhodnuto: a** — jednodušší pravidlo a rovnou odstraní neviditelný `text-bg-light` (S-15).

### R-4 · GFM tabulky v nápovědě — ✅ ROZHODNUTO 22. 7. 2026: **b) přidat `remark-gfm`**
Dnes čistý CommonMark, v článcích se musí místo tabulek psát seznamy (`help/index.js:13`).
**a)** ponechat, **b)** přidat `remark-gfm` (tabulky, strikethrough, autolinky).
**Rozhodnuto: b** — články o skladu a fakturaci se bez tabulek píší těžkopádně; závislost je malá
a renderer je jen pro naše vlastní obsahy. Zapracováno v U7.2; HTML v článcích zůstává zakázané
(`rehype-raw` se nepřidává).

### R-5 · Řazení tabulek kliknutím na hlavičku — ✅ ROZHODNUTO: **a) doplnit do `DataTable`**
Backend `sortBy`/`sortDesc` podporuje, FE je má jako zamrzlý `useState` bez setteru (S-28).
**Rozhodnuto: a** — u seznamů nad 20 řádky (sklad, příjemky) to obsluha ocení; je to malý úkol,
když už vzniká sdílená tabulka. Zapracováno do úkolu U3.1.

### R-6 · Rozsah zavedení konvencí — ⏳ OTEVŘENO
| | Možnost | Znamená |
|---|---|---|
| **a** | **Postupně**: nejdřív sdílené komponenty, pak modul po modulu | Kontrolovatelné, každý krok jde ověřit; delší doba s dvojím vzhledem |
| b | Naráz ve všech souborech | Krátké přechodné období; velké riziko regresí, těžké review |

**Doporučení: a** — odpovídá zavedenému způsobu práce (plan-sklad, plan-oprav) a Pravidlu č. 1.

### R-7 · Barva akcí — ✅ ROZHODNUTO: **b) čtyřtónová sémantika** (viz §7.1)
Uživatel zadal: pokud je významové odlišení tlačítek barvou z hlediska UX opodstatněné, zavést ho.
Je — viz odůvodnění a plná specifikace v **§7.1** níže. Původní varianta a) („všechno modré")
se zamítá: ztratila by jediný signál, kterým UI dnes odlišuje nevratné dokončení procesu
od běžného uložení.

---

## 7.1 Barevná sémantika tlačítek (specifikace)

**Princip: barva kóduje _důsledek_ akce, ne její četnost ani důležitost pro vývojáře.**
Uživatel se musí z barvy dozvědět jednu věc — *co se stane, když to zmáčknu*. Proto stačí
čtyři tóny; pátý už by nesl informaci, kterou nikdo nerozliší.

| Tón | Třída | Význam | Kde |
|---|---|---|---|
| **Modrá plná** | `btn-primary` | **Hlavní akce obrazovky, vratná.** Něco vznikne nebo se uloží a dá se to vzít zpět další editací. | „Nový zákazník", „Uložit", „Vytvořit zakázku", „Založit a vyplnit" |
| **Zelená plná** | `btn-success` | **Posun dokladu/procesu do dalšího stavu, nevratný.** Vznikají účetní nebo skladové důsledky, které už nejdou jen tak přepsat. | „Potvrdit a naskladnit", „Vystavit", „Označit zaplaceno", „Uzavřít inventuru" |
| **Šedý obrys** | `btn-outline-secondary` | **Neutrální vedlejší akce**, nic nemění nebo jen naviguje. | „Editovat", „PDF", „Import dokladu (PDF)", „Zrušit", „Zpět", „Uložit koncept" |
| **Červený obrys** | `btn-outline-danger` | **Negativní nebo rušící akce.** Odebírá, ruší, zamítá. Vždy s potvrzovacím dialogem. | „Deaktivovat", „Stornovat", „Zamítnout", „Smazat", „Zrušit inventuru" |

**Proč zrovna oddělit modrou a zelenou.** Rozdíl mezi „Uložit" a „Potvrdit a naskladnit" není
v důležitosti, ale v tom, že druhé jmenované **založí šarže a skladové pohyby, které už z ledgeru
nezmizí** (jen se kompenzují stornem). Totéž „Vystavit fakturu" — vzniká právní doklad s číslem
z řady. Uživatel, který si na modrou „Uložit" zvykl jako na bezpečný reflex, potřebuje u těchto
tlačítek zaváhat. Barva je nejlevnější způsob, jak to zařídit, a je to jediný signál, který dnes
`ReceiptReviewPage.jsx:363` (jediné `btn-success` v aplikaci) nese — jen nesystematicky.

**Pravidla použití:**

1. **Na jedné obrazovce je nejvýš jedno plné tlačítko.** Buď modré, nebo zelené — nikdy obojí
   vedle sebe jako dva soupeřící „primary". Kde proces vrcholí (kontrola příjemky, inventura),
   je plné zelené a průběžné uložení klesá na `btn-outline-secondary` („Uložit koncept",
   „Uložit soupis") — to je i dnešní chování `ReceiptReviewPage`, jen se kodifikuje.
2. **Pořadí zleva doprava: neutrální → hlavní → destruktivní.** Destruktivní tlačítko je vždy
   poslední a oddělené (`ms-auto`), aby se do něj netrefil ukvapený klik.
3. **Žlutá a azurová se na tlačítka nepoužívají.** `btn-warning` a `btn-info` mají na bílém pozadí
   nedostatečný kontrast textu a kolidovaly by s významem, který mají alerty a badge.
4. **Barva nikdy nenese informaci sama.** Každé tlačítko má sloveso v popisku, destruktivní navíc
   potvrzovací dialog. Pro barvoslepé uživatele tak zůstává UI plně čitelné — zelená a červená
   se navíc nikdy nevyskytují jako jediný rozdíl mezi dvěma sousedními tlačítky.
5. **Vztah k badge (R-3):** badge jsou po sjednocení jemné pill (`bg-*-subtle`), tlačítka plná —
   zelený badge „Zaplacena" a zelené tlačítko „Označit zaplaceno" se proto vizuálně nepletou
   a významově na sebe naopak správně ukazují.

**Dopad na dnešní kód:** změnou projde `StockTakePageDetail.jsx:207` („Uzavřít inventuru"
`btn-primary` → `btn-success`), `InvoicesPageDetail.jsx:79` („Vystavit" `btn-primary` → `btn-success`)
a `InvoiceTable` (stavové akce po přesunu do menu dostanou barvu položky menu).
`ReceiptReviewPage.jsx:363` a `InvoicesPageDetail.jsx:87` zůstávají, jak jsou — nově ale podle
zapsaného pravidla, ne náhodou.

---

## 8. Co tato analýza záměrně neřeší

- **Dashboard** je prázdný placeholder (`DashboardPage.jsx`) — jeho obsah je produktová otázka,
  ne UI konzistence; patří do `roadmapa.md`.
- **Přístupnost nad rámec zjištěného** (kontrasty, čtečky, plná klávesová obsluha) — zmíněné body
  (aria-modal, focus trap, labely filtrů, `scope`) jsou vedlejším produktem sjednocení; systematický
  audit a11y je samostatná práce.
- **Backend a API** — žádný nález nevyžaduje změnu serveru; jedinou výjimkou je případné řazení (R-5),
  které backend už umí.
