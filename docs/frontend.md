# frontend.md — Průvodce frontendem

> React SPA v `frontend/autoservis-frontend/`. První dokumentace frontendu (do 2026-07 neexistovala).
> Endpointy, které FE volá → `api.md`.

Stack: React 19, React Router 7, Vite 8; Bootstrap 5.3 (dominantní) + MUI (bodově) + dnd-kit (drag-and-drop).
Žádná kalendářová knihovna — plánovací kalendář je vlastní (viz `/schedule` níže); FullCalendar byl
vyzkoušen a 2026-08-03 odebrán, protože po přechodu na denní karty už nedělal nic užitečného. Jazyk UI: čeština. Žádný TypeScript, žádné JSDoc typy — `src/api/typeDefs/` byl neimportovaný mrtvý kód, smazán 2026-07-20 (TD-39).

## 1. Spuštění a build

```bash
cd frontend/autoservis-frontend
npm install
npm run dev        # Vite dev server na :5173
npm run build      # produkční build (dist/)
npm run check      # statická kontrola UI konvencí (viz §10)
```

⚠️ Frontend **nemá testovou sadu** (žádný vitest/jest). Ověřením změny je proto `npm run check`
+ `npm run build` + průchod dotčených stránek v prohlížeči — build sám o sobě nedokazuje skoro nic,
protože neznámý identifikátor ani porušená konvence ho nezastaví.

`vite.config.js`: proxy `'/api' → 'http://localhost:8080'` — všechny API cesty jsou relativní, cookies fungují same-origin. Žádné `.env` — `API_BASE = '/api/v1'` je natvrdo v `api.js`; produkce spoléhá na reverse proxy.

## 2. Struktura `src/`

```
main.jsx            ← createRoot, <StrictMode>, <AlertProvider>; import Bootstrap CSS+JS
App.jsx             ← BrowserRouter + všechny routy
api/                ← api.js (fetch wrapper), auth.js, format.js
components/         ← ~60 komponent (sdílené UI + entitní); components/filters/ = lišta filtrů
pages/              ← ~34 stránek (vzor XxxPage / Create / Detail / Edit)
hooks/              ← useRowActions (sdílená logika) + useXxxRowActions (6× tenký wrapper, řádkové akce tabulek)
context/            ← AlertContext (jediný globální stav)
css/                ← reset.css, help.css; index.css je o úroveň výš (src/index.css)
```

## 3. Routing (App.jsx)

`/login` je mimo layout; vše ostatní vnořeno do `<RequireAuth><Layout /></RequireAuth>` (Sidebar + AlertContainer + `<Outlet>`).

| Cesta | Stránka |
|---|---|
| `/login` | LoginPage |
| `/` → redirect `/dashboard` | DashboardPage (KPI + fronty „vyžaduje pozornost"/„provoz" z `/dashboard/summary`) |
| `/customers`, `/customers/new`, `/customers/:id/detail`, `/customers/:id/edit` | Customers* |
| `/vehicles` + new/detail/edit | Vehicles* |
| `/schedule` | `SchedulePage` — plánovací kalendář **bez knihovny**. Týden = sedm `ScheduleDayCard` vedle sebe (objednávky seřazené podle příjezdu, blokace jako pruhovaná karta, prázdný den s „+“), měsíc = `ScheduleMonth` jako mini seznam (až 3× „čas jméno", „+N další"), klik na den přepne na týden. Vícedenní položky rozděluje `groupOccurrences` do všech dotčených dnů — v den příjezdu plně, dál „→ pokračuje" v barvě stavu s čárkovaným obvodem; objednávka **bez konce** jen do dne příjezdu (délku neznáme). Datová aritmetika v `api/scheduleDates.js` (vše v **místním čase** — klíč dne z `toISOString()` by objednávku po 22:00 letního času hodil do dalšího dne). Klik na objednávku **dotáhne `GET /appointments/{id}`** a teprve pak otevře detail v `Modal` — karty kreslí `ListResponse` bez `customerId`, `vehicleId` a `note`, takže bez toho dotazu neměl editační formulář zákazníka ani vozidlo a uložení padalo na 422; termín se mění přes „Upravit“, **přetahování myší tu není**. Barvy odpovídají tónům `StatusBadge`. Přepínač **„Jen nezrušené"** (výchozí zapnuto) schová zrušené objednávky a v popisku ukáže, kolik jich schoval; filtruje se **na klientu**, protože API umí jen rovnost na jeden stav a data okna jsou stejně načtená |
| `/settings/opening-hours` | `OpeningHoursPage` — týdenní rozvrh otevírací doby (**ADMIN/MANAGER**) a přepínač hlídaní. Ukládá se celý týden naráz, `PUT /opening-hours`; zavřený den = oba časy prázdné — přepínač u dne by šel rozhodit do stavu „zavřeno, ale otevírá se v sedm“. Pomůcky sdílí s kalendářem v `api/openingHours.js` — tam je i jediný převod `Date.getDay()` (0 = neděle) na ISO číslo dne (1 = pondělí), které používá server i PostgreSQL |
| `/orders` + new/detail/edit | Orders* — `/orders/new?appointmentId=…` je režim „zakázka z objednávky": formulář se předvyplní z objednávky a ukládá přes `POST /appointments/{id}/convert` (jedna transakce), ne přes `POST /orders`. Detail zakázky ukazuje zpětný odkaz „Vzniklo z objednávky" (`GET /appointments/by-order/{id}`; 404 = zakázka vznikla přímo, načítá se zvlášť, aby to neshodilo stránku) |
| `/warehouse` + new/detail/edit | Warehouse* (produkty) |
| `/warehouse/receipts`, `/warehouse/receipts/:id/review` | ReceiptsPage (seznam příjemek, filtry stav/typ, import PDF, založení ruční příjemky), ReceiptReviewPage (kontrolní obrazovka: editovatelný draft s barevnými stavy polí, párování na skladové karty, banner dedupu DL↔faktura, **sbalitelný** náhled PDF, přidávání řádků, potvrdit/zamítnout) |
| `/suppliers` + detail/edit (**bez** `/new` — dodavatelé vznikají jen importem) | Suppliers* |
| `/invoices`, `/invoices/:id/detail` | Invoices* — seznam ukazuje sloupec **Celkem k úhradě** (`totalToPay`, u hotovosti zaokrouhlené; V67). Detail navíc řeší **pokladní doklady**: tlačítko „Vystavit pokladní doklad" s potvrzením částky (existuje-li platný, jen ho otevře) a karta se seznamem dokladů vč. stornovaných — akce PDF a Stornovat (dialog na povinný důvod), V68/KN-7. U dobropisované faktury (`creditedAt`) je nahoře banner vysvětlující, že doklad zůstává platný, ale zakázku už neblokuje (V69) |
| `/credit-notes/:id/detail` | `CreditNotesPageDetail` — opravný daňový doklad (dobropis). **Nemá vlastní seznam ani položku v menu**: vždy patří k jedné faktuře, chodí se na něj z jejího detailu. Detail faktury si proto načítá `GET /credit-notes?invoiceId=` a tlačítko vede buď na založení (dialog na §45 důvod opravy), nebo na existující doklad — druhý vystavit nelze (V66, audit KN-1/KN-8) |
| `/settings/company` (profil firmy; `/invoices/settings` → redirect, U2.1) | CompanyProfilePage |
| `*` (cokoli jiného) | NotFoundPage — bez catch-all routy vykreslila neznámá adresa prázdnou stránku |
| `/users`, `/users/new`, `/users/:id/edit` (**bez** `/detail` — edit stránka slouží i jako detail) | Users* — admin CRUD účtů, nav položka v Sidebaru viditelná jen pro `ROLE_ADMIN` |
| `/employees`, `/employees/new`, `/employees/:id/edit` (**bez** `/detail`) | Employees* — CRUD zaměstnanců, nav položka jen pro `ROLE_ADMIN`/`ROLE_MANAGER` (D-7) |
| `/help`, `/help/:slug` | HelpPage (uživatelská nápověda) |

**Navigace (U2):** struktura menu je **data** v `src/components/navigation.js` (`NAV_SECTIONS`),
`Sidebar` je jen vykreslení + chování. Čtyři bloky: provoz, rozbalovací skupina **Sklad**
(Přehled skladu, Příjemky, Pod minimem, Inventury, Dodavatelé), oddělená rozbalovací skupina
**Nastavení** (Zaměstnanci — jen ADMIN/MANAGER, Fakturační údaje, Uživatelé — jen admin)
a samostatná Nápověda pod ní. Odsazení `ps-5` v JSX je zrušené, podpoložky mají třídu `.nav-sub`.

- **Aktivní položka = nejdelší shoda cesty** (`activeNavPath`), ne `end` na `NavLink`.
  `/warehouse/receipts` zvýrazní jen Příjemky, `/warehouse/5/detail` jen Přehled skladu.
  `className` se `NavLinku` předává **jako funkce** — jinak si router přidá vlastní třídu
  `active` a u prefixových cest svítí dvě položky naráz.
- **Skupina se sbaluje**, stav v `localStorage` (`sidebar.groups`). Aktivní stránka uvnitř má
  přednost před uloženým sbalením, aby uživatel na svou stránku v menu viděl.
- **Menu se jen přepíná vysunuto/zasunuto** — jedno chování na všech šířkách, žádný breakpoint
  a žádný překryv. Zasunuté se **nevykresluje vůbec** (schovávat ho CSS by znamenalo přebíjet
  Bootstrapí `.d-flex`, které má `!important`) a obsah dostane celou šířku. Vysouvá tlačítko ☰
  v obsahu, zasouvá šipka « v hlavičce panelu; stav v `localStorage` (`sidebar.open`).

**Error boundary (U0.2):** `ErrorBoundary` (`src/components/ErrorBoundary.jsx`, class komponenta —
hook ekvivalent `componentDidCatch` neexistuje) obaluje v `Layout.jsx` **jen `<Outlet />`**, takže pád
stránky nechá sidebar funkční. Místo bílé obrazovky ukáže kartu „Něco se pokazilo" s tlačítky
„Zkusit znovu" (reset stavu) a „Zpět na Dashboard"; `error.message` se vypisuje jen v dev
(`import.meta.env.DEV`). Boundary má `key={location.pathname}` — přechod jinam ji resetuje sám,
uživatel nemusí reloadovat.

**Route guard: `RequireAuth`** (`src/components/RequireAuth.jsx`) obaluje layout route. Při mountu zavolá `requireAuth()` (`GET /auth/me`); dokud odpověď nedorazí, zobrazí vycentrovaný spinner (`spinner-border` v `d-flex justify-content-center align-items-center vh-100`) místo `children` — chráněný obsah tedy neblikne před 401 redirectem. Neúspěch řeší `requireAuth()` samo (redirect na `/login`), `RequireAuth` jen čeká. `apiFetch` navíc přesměruje na `/login` při 401 kdykoli později (expirovaná session po refreshi stránky). `Layout` si nezávisle volá `requireAuth()` znovu kvůli datům pro Sidebar (jméno, role) — duplicitní síťové volání, vědomý kompromis (žádný auth context zatím není zaveden).

**Otevírací doba v kalendáři** (`SchedulePage`): rozvrh se načítá **jednou při otevření stránky**, ne s každým posunem týdne — mění se řádově jednou za rok a jde o sedm řádků. Zavřený den se jen ztlumí (`is-closed-day`) a napíše „Zavřeno“; **tlačítko „+“ zůstává**, protože otevírací doba objednávku nezakazuje, jen na ni upozorní — skrývat ho by slibovalo zákaz, který neplatí. Ztlumení místo šrafování proto, že šrafovaný podklad patří blokaci dílny a pravidelně zavřená neděle by s ní splynula.

**Escape nad otevřeným menu** (`TableRowActionMenu` uvnitř dialogu) zavírá menu, ne celé okno — `Modal` se na Escape podívá, jestli není otevřený MUI popover. Menu se totiž vykresluje portálem do `body`, tedy mimo dialog, a událost by jinak zavřela obojí najednou.

## 4. API vrstva (`src/api/`)

### api.js
- `apiFetch(path, options)` nad nativním `fetch`; vždy `credentials: 'include'` (HTTP-only JWT cookie).
- `Content-Type: application/json`; při `FormData` se hlavička nenastavuje (multipart boundary doplní prohlížeč).
- 401 → **refresh-and-retry**: mimo volání na `/auth/*` (`isAuthCall`) se zavolá `tryRefresh()` (single-flight — sdílená Promise `refreshPromise`, souběžné 401 čekají na tentýž `POST /auth/refresh`, nespustí druhý — rotace refresh tokenu by druhý refresh zneplatnila jako reuse útok a server by revokoval všechny sessions); po úspěchu se **jednou** zopakuje původní request (`options._retried` hlídá, že se to nezacyklí); když refresh selže nebo šlo už o opakovaný pokus, `window.location.href = '/login'`. Volání na `/auth/*` (login, refresh, change-password) refresh nikdy nezkouší — jinak by neúspěšný login vyvolal zbytečný refresh pokus. 204/prázdné tělo → `null`; non-2xx → `throw new ApiError(status, problem, text)`.
- Export: `api.get / post / put / delete / upload(path, formData) / getBlob(path)` (getBlob = binární zdroj s cookies → object URL, volající musí `URL.revokeObjectURL`) a `tryRefresh()` — stejná single-flight refresh logika, používá ji i `getBlob` (přímý `fetch`, ne `apiFetch`) a `auth.js#requireAuth`.
- `getBlob(path)` na 401 dělá tutéž refresh-and-retry logiku jako `apiFetch` (interní parametr `_retried`, nevolat ručně). Chyby nevyhazuje — na jiný non-2xx vrací `null`.

### ApiError

`apiFetch` čte tělo odpovědi vždy jako text **před** kontrolou `response.ok` (ne-JSON tělo — HTML 502 z proxy, prázdná odpověď — už nezpůsobí `SyntaxError` a ztrátu statusu). Na non-2xx vyhodí `ApiError extends Error`:
- `status` — HTTP status kód.
- `problem` — tělo naparsované jako JSON (typicky RFC 9457 ProblemDetail `{ title, detail, errors: [...] }`), nebo `null`, když tělo nebylo JSON.
- `message` — surový text těla (zpětná kompatibilita; když je `problem` `null`, `message` je jediný zdroj informace).

### problemMessage — jediný způsob, jak z chyby udělat hlášku

```js
import { api, problemMessage } from "../api/api.js";
…
} catch (err) {
    addAlert(problemMessage(err, "Zákazníka se nepodařilo vytvořit."), "danger");
}
```

`problemMessage(err, fallback)` složí `detail` **a** konkrétní hlášky z `errors[]`. Do vlny 4 čtěl frontend jen `detail`, takže u validace uživatel viděl konstantní „Ověření zadaných údajů selhalo" bez jména pole a bez důvodu — přesnou hlášku („IČO má přesně 8 číslic") server přitom poslal v `errors[]` (audit KN-14 / 11-F-3). Vzor `errors[]` uměla jediná komponenta z dvaceti.

Fallback se použije, když tělo není ProblemDetail (HTML 502 z proxy, `TypeError` z nedostupné sítě). **`err.message` se do hlášky nikdy nedává** — je to surové tělo odpovědi nebo anglické „Failed to fetch" (konvence §17: fallback česky).

### Chybové stavy načítání

Prázdný stav a chyba jsou **dva různé stavy** — do vlny 4 se slévaly a 500 na seznamu zákazníků se ukázala jako „Zatím žádní zákazníci.", podle čeho obsluha zakládala duplicity (audit 11-F-2).

| Kde | Co se zobrazí |
|---|---|
| Seznam nebo sekce (9 seznamů, karty historie) | `LoadErrorState` **místo tabulky** — hláška + „Zkusit znovu" (obnoví přes `refreshKey`) |
| Detail stránky (5 detailů) | `ErrorState` s `backTo` — spinner jen dokud se čeká, pak cesta zpět na přehled |
| Doplňkový údaj (ocenění skladu na `WarehousePage`) | hodnota se **skryje** a řekne se proč; dřív se ukázalo nepravdivé „0,00 Kč" |
| Neúspěch akce (uložení, přechod stavu) | toast `addAlert(problemMessage(…), "danger")` — je to výsledek akce, ne stav obrazovky (§10.6) |

### validation.js — vzory zrcadlící DTO

`api/validation.js` drží `pattern`/`maxLength` pro IČO, DIČ, telefon, PSČ a délky jmen **shodné se serverovými DTO** (audit 11-F-4: formulář zákazníka nevaliduje nic a u telefonu byl dokonce volnější než server — 30 vs. 21 znaků). Server zůstává autoritativní; formulář nesmí být volnější. Při změně DTO se mění obojí — u každé konstanty je v komentáři zdroj pravdy.

### auth.js
`requireAuth()` (`GET /auth/me`), `logout()` (`POST /auth/logout` + redirect). Obě volají `fetch` napřímo, ne přes `api` klienta. `requireAuth()` na `!response.ok` zkusí jednou `tryRefresh()` (import z `api.js`) a zopakuje `/auth/me`; teprve při dalším neúspěchu redirect na `/login` a `return null`. `logout()` refresh nezkouší — vždy jen smaže cookies na serveru a přesměruje.

### format.js
Bezstavové helpery: `formatCurrency`/`formatDate` (Intl `cs-CZ`), `formatNumber` a
`formatQuantity(value, unit)` (množství s jednotkou, bez zbytečných desetinných nul),
`toDatetimeLocal`/`fromDatetimeLocal` (převod okamžiku ze serveru na `datetime-local` a zpět),
`getInitials`, `getCountryName`, `getEstimateDateColor` + mapy enum → label/badge a `*_OPTIONS` pole pro selecty (order status, payment method, invoice status, fuel, transmission, movement type, …). Prázdná hodnota = konstanta `EMPTY_VALUE = "—"`.

**Formátuje se výhradně tady** (U8.1). Ve stránkách a komponentách nesmí být vlastní
`formatQuantity`/`formatMoney` ani inline `toLocaleString` — dřív existovaly čtyři kopie
a `formatMoney` dával jiný výstup než `formatCurrency`, takže tatáž cena vypadala na dvou
obrazovkách jinak.

## 5. Vzory komponent

### Seznamové stránky
Stav filtrů + stránkování v `useState`, **debounce 400 ms** přes `setTimeout` v `useEffect`, refresh
přes inkrement `refreshKey`. Skládají: `PageHeader` + `ListToolbar` s filtry
(`SearchFilter`, `SelectFilter`, `ToggleFilter` — `components/filters/`) + `XxxTable` nad `DataTable`
+ `PaginatorRounded` (MUI Pagination + select velikosti stránky). **Výchozí velikost stránky je
všude 10.** Dřívější `InputFilter` a `CheckBox` nahradily filtrové komponenty a byly smazány.

**Každá změna, která mění množinu výsledků, musí volat `setPage(1)`** — hledání, filtry, řazení
i velikost stránky. Číslo stránky je součástí dotazu na backend, takže bez resetu se uživatel
na stránce 3 po zadání hledaného výrazu dívá na třetí stránku jednoprvkového výsledku, tedy
na prázdnou tabulku. U `SearchFilter`/`ToggleFilter`/`SelectFilter` se to píše přímo do handleru:
`onChange={(value) => { setSearch(value); setPage(1); }}`.

### Tabulky
Seznamy staví na sdílené **`DataTable`** (U3.1): sloupce jsou data, sloupec Akce používá
`TableRowActionMenu` (MUI třitečkové menu) s akcemi z hooku `useXxxRowActions`, `ConfirmDialog`
řeší deaktivaci/aktivaci. Převedeny **všechny seznamové stránky**: zákazníci, vozidla, zakázky,
dodavatelé, uživatelé (U3.2), sklad, příjemky, inventury, pod minimem (U3.3) a faktury (U3.4).
Vnořené tabulky v kartách detailů (`dense` + `clientSort`): `CustomerVehiclesTable` (U4.1),
`MileageHistoryTable` (U4.2) a od servisní historie (KN-27) `OrderHistoryTable` (zakázky vozu
i zákazníka) a `CustomerInvoicesTable` (faktury zákazníka). Vnořené tabulky mají **jedinou
řádkovou akci Detail** — historie je ke čtení, doklad se mění na své vlastní obrazovce.

**Řazení serverové** drží stránka (`sort = {by, desc}`) a posílá ho jako `sortBy`/`sortDesc`;
seřaditelné jsou jen sloupce, které má backend ve whitelistu (viz `api.md`) — nabízet klikatelnou
hlavičku, která nic neudělá, by mátlo.

**Řazení klientské** (`clientSort`) je pro endpointy, které vracejí celé pole bez stránkování —
inventury a Pod minimem. `DataTable` si `rows` seřadí sama podle `sortValue` sloupce; posílat
řazení na server by tu byla zbytečná režie. Porovnává se `localeCompare(…, "cs")`, čísla a data
numericky, **prázdné hodnoty jdou vždy nakonec** (v obou směrech) — „nevyplněno" není nejmenší
hodnota, je to chybějící údaj.

**Každá tabulka patří do `<div className="table-responsive">`** (U0.3). `#main-content` už nemá
`overflow-x: hidden` — široké tabulky se scrollují uvnitř svého obalu, ne aby se sloupec Akce
usekl mimo obraz a stal se nedostupným. K tomu má `#main-content` `min-width: 0`, jinak by ho
tabulka jako flex položku roztáhla a hlavička stránky by se rozbila.

Stavové přechody faktur (Vystavit / Označit zaplaceno / Stornovat) byly do U3.4 v řádku jako
`btn-group` textových tlačítek — jediná tabulka v aplikaci, která to tak měla. Teď jsou
v třitečkovém menu jako akce ostatních seznamů; potvrzení řeší stejný `ConfirmDialog`.

### Row-action hooky (`hooks/`)
Sjednoceno v C5 (TD-35, 2026-07-20): sdílená logika žije v `useRowActions.js`, entitní hooky (`useCustomerRowActions`, `useVehicleRowActions`, `useOrderRowActions`, `useSupplierRowActions`, `useWarehouseRowActions`) jsou tenké wrappery nad ním — komponenty (tabulky) se nemění, importují a volají stejně jako předtím.

`useRowActions({ routePath, apiPath, hasDetailAction, dialogTitle, dialogMessage, toggleStatus })`:
- `routePath` — základ cesty pro `navigate` (`detail`/`edit`), `apiPath` — základ REST cesty pro `DELETE`/`POST .../activate` (u dodavatelů a skladu se liší od `routePath`, protože API má jinou strukturu než FE routy — `/warehouse/suppliers`, `/warehouse/products`).
- `hasDetailAction` (default `true`) — vypíná akci `detail` (uživatelé nemají detail stránku).
- `dialogTitle(action)` a `dialogMessage(rowData, action)` — funkce vracející text potvrzovacího dialogu; per-entitní texty (skloňování, závorky s detaily, přítomnost/nepřítomnost koncového „?") se **nesjednocují** — každý wrapper si předá vlastní přesné znění.
- Vrací stejné rozhraní jako dřívější hooky: `{ handleMenuAction, confirmAction, showConfirm, setShowConfirm, dialogTitle, dialogMessage }`.

`useUserRowActions` zůstal samostatný nad `useRowActions` (ne čistý parametrický wrapper) — navíc řeší modál resetu hesla (`showResetPassword`, `confirmResetPassword`, `resetPasswordUsername`), což nemá obdobu u ostatních entit a vynucovalo by si do sdíleného hooku speciální větev jen pro jednu entitu.

### Formuláře
Sdílené pro create i edit (`isEditMode`, `initialData`, `onSave`, `onCancel`): `CustomerForm` (INDIVIDUAL/COMPANY přepínání, vnořené adresy, Bootstrap validace `needs-validation`; **adresy jen v create režimu** — `CustomerDto.UpdateRequest` pole `addresses` nemá, takže PUT je nepřijímá; formulářový tvar adres a převody na API řeší `src/api/customerPayload.js` — `withAddressState`, `splitAddresses`, `toCreatePayload`, `toUpdatePayload`, sdílené oběma stránkami, U0.1), `VehicleForm` (VIN pattern, `AutocompletePair` pro zákazníka), `OrderForm` (create: autocomplete zákazník+vozidlo; edit: + položky a fakturace), `SupplierForm`, `WarehouseForm`.

Od U5.1 mají **všechny (i `UserForm`) shodné rozvržení**: `PageHeader` s názvem, sekce jako
`FormSection` (dřív `h5.text-primary.border-bottom`) a patičku `FormActions` (dřív tři různá
znění tlačítek — „Zpět/Uložit", „Zpět/Uložit změny", …; jednotně je to **„Zrušit" + „Uložit"**,
`OrderForm` v create režimu „Vytvořit zakázku"). Výjimka (rozhodnutí uživatele 2026-08-09):
**editace zakázky** má „Zpět" + „Uložit" — Uložit na stránce **zůstává** (jen uloží, ukáže alert
a znovu načte data; obsluha typicky pokračuje položkami či tiskem listu) a druhé tlačítko tedy nic
neruší, jen naviguje na přehled, čemuž odpovídá popisek „Zpět" (`FormActions cancelLabel`).
Sekce se zobrazují podmíněně tam, kde to
formulář dělal i dřív — nový zákazník ukáže zbytek až po volbě typu, „Kontaktní adresa"
až po zaškrtnutí. Šířku drží layout, žádný formulář nemá vlastní `container`.

Vzor pro nový formulář je v §10.3.

### STK z registru vozidel (VehicleForm, VehiclesPageDetail, VehicleTable, VehiclesPage)
- **`VehicleForm`** — blok „Načíst z registru vozidel" v sekci Registrace a identifikace: select typu dokladu (VIN / číslo ORV / číslo TP) + pole + tlačítko se spinnerem → `GET /vehicles/registry-lookup`. Prefill přepisuje pole **jen non-null hodnotami** z registru (vin, brand, model, color, objem, výkon, datum 1. registrace, fuelType) — dotaz přes ORV/TP předvyplní i VIN. Chyby přes `useAlert()` s `detail` z ProblemDetail.
- **`VehiclesPageDetail`** — karta „STK a registr vozidel": badge platnosti (`getStkBadge`), stav v registru, evidenční prohlídka a poslední načtení z `GET /vehicles/{id}/registry-snapshots` (nejnovější snapshot), tlačítko „Aktualizovat z registru" → `POST /vehicles/{id}/registry-refresh` + reload.
- **`VehicleTable`** — sloupec STK s badge; **`VehiclesPage`** — checkbox „Končící STK" (`stkExpiring` do query).
- **`format.js`** — `getStkBadge(stkValidUntil)` → `{label, className}`: bez dat secondary „—", propadlá danger, do 30 dnů warning, jinak success.

### ARES (CustomerForm)
- **`CustomerForm`** — u typu COMPANY tlačítko „Načíst z ARES" v input-group u pole IČO (aktivní až při 8 číslicích) → `GET /customers/ares-lookup?ico=…`. Prefill **jen non-null hodnotami**: companyName, dic a adresa sídla do **fakturační adresy** (`billingAddress`). Stejné UX jako registr vozidel: spinner, chyby přes `useAlert()` + `problemMessage`.

### Modály
Všechny dialogy staví na sdílené komponentě **`Modal`** (§10.4) — `ConfirmDialog`,
`MileageFormModal`, `OrderItemFormModal`, `ImportProductFormModal`, `GoodsReceiptImportModal`,
`InvoiceCreateFormModal`, `ManualReceiptModal`, `StockMovementModal`, `ResetPasswordModal`,
`ChangePasswordModal`. Bootstrap JS API se nepoužívá, jen jeho CSS třídy.

Dialogy, které něco ukládají, předávají **`closable={!saving}`**: během ukládání zmizí křížek
a nezabírá Esc ani klik na pozadí, takže uživatel nemůže zavřít okno nad rozdělaným požadavkem
a zůstat v nejistotě, jestli se akce provedla.

Zvláštnosti: `GoodsReceiptImportModal` = upload PDF/ISDOC + volba typu dokladu (faktura/dodací
list), limit 10 MB shodně s BE; výsledek je souhrn draftu s výsledky kontrol — nic se
nenaskladňuje. Prop `closeLabel` — z `ReceiptsPage` naviguje „Zkontrolovat" na review.

### Správa uživatelů (UsersPage, UserForm, UserTable, Sidebar)
- **`UsersPage`** — stejný seznamový vzor jako ostatní moduly (search, „jen aktivní", stránkování); sloupec Role zobrazuje badge pro každou přiřazenou roli.
- **`UserForm`** — sdílený pro create/edit; při `isEditMode` je `username` needitovatelné (`disabled`) a mizí pole hesla (heslo se řeší zvlášť přes reset/změnu, ne v tomto formuláři). Role se vybírají jako checkboxy — seznam se natahuje z `GET /code-lists/roles` (`RoleDto[]` — id, name, description), ne staticky. Endpoint vrací **jen přiřaditelné** role (ADMIN/MANAGER/MECHANIC); `ROLE_CUSTOMER` a `ROLE_READONLY` filtruje backend, protože baseline `/api/**` je odřízne a účet s nimi by dostal 403 na každé obrazovce (audit KN-22) — formulář proto žádný vlastní filtr nemá a mít nemá.
- **`ResetPasswordModal`** — admin nastaví nové heslo libovolnému uživateli bez znalosti současného (`POST /users/{id}/reset-password`); otevírá se z `TableRowActionMenu` v `UserTable`.
- **`ChangePasswordModal`** — self-service změna vlastního hesla (`POST /auth/change-password`, vyžaduje současné heslo); dostupná komukoli přihlášenému přes odkaz „Změnit heslo" v Sidebaru (ne přes `UsersPage`, ke které mají přístup jen admini).
- **`Sidebar`** — položka „Uživatelé" viditelná jen když `user.roles` obsahuje `ROLE_ADMIN` (role přišly z `GET /auth/me`, viz §7); odkaz „Změnit heslo" je viditelný vždy.
- Detail proč: `docs/funkce/sprava-uzivatelu.md`, hloubka implementace: `docs/pruvodce/sprava-uzivatelu.md`.

### Správa zaměstnanců (EmployeesPage, EmployeeForm, EmployeeTable, Sidebar)
- **`EmployeesPage`** — seznamový vzor, ale **nestránkovaný**: `GET /employees?activeOnly=` vrací prostý `List` (malý číselník), hledání (jméno/pozice) i řazení běží v prohlížeči (`DataTable clientSort`). Přepínač „Jen aktivní" jde na backend (parametr), refresh přes `refreshKey`.
- **`EmployeeForm`** — sdílený pro create/edit (jméno, příjmení, pozice, hodinová sazba, datum nástupu/odchodu). Payload staví `api/employeePayload.js` (prázdné → null, sazba na číslo; `id` do těla nepatří). Sazba je náklad práce — mění se jen „do budoucna", historii drží snapshot na položce (D-3).
- **`EmployeeTable`** + **`useEmployeeRowActions`** (tenký wrapper nad sdíleným `useRowActions`, TD-35) — jen edit + aktivace/deaktivace (soft-delete D-4, bez detailu, nikdy hard delete).
- **`Sidebar`** — položka „Zaměstnanci" viditelná jen pro `ROLE_ADMIN`/`ROLE_MANAGER` (navigace nese `roles: [...]`, filtr rozšířen vedle `adminOnly`); backend je autoritativní (§19), FE prvek jen skrývá. Mechanik stránku v menu nevidí, ale seznam aktivních čte přes select u položky.
- Detail proč: `docs/funkce/zamestnanci.md`.

### Přesun termínu tažením (`SchedulePage`, `ScheduleDayCard`, `ScheduleMonth`)

Druhé použití dnd-kit. `DndContext` sedí v `SchedulePage` a obaluje **oba** pohledy; cílem upuštění
je den (`useDroppable`), taženou položkou objednávka (`useDraggable`). Nový termín počítá
`shiftToDay` ve `scheduleDates.js` — posun **po kalendářních dnech**, ne přičtením milisekund,
protože den s přechodem na letní čas má 23 nebo 25 hodin. Ukládá `POST /appointments/{id}/time`,
optimisticky s vrácením stavu a hláškou při 422 (typicky blokace dílny) — týž vzor jako u položek
zakázky.

**Cíl se během tažení barví podle proveditelnosti** — modře povolený den, červeně zakázaný
(`is-drop-target` × `is-drop-blocked`). Rozhoduje `isDropBlocked` ve `scheduleDates.js`, počítané
z už načteného okna: `SchedulePage` si přes `useMemo` drží množinu zakázaných dnů pro právě
taženou položku a předává ji kartám. Je to **jen vodítko**, zdrojem pravdy zůstává server — když
se rozejdou, položka se po upuštění vrátí a přijde hláška; zakázané uložení tím neprojde.

Tři věci, které se liší od řazení položek a mají důvod:

- **`MouseSensor` + `TouchSensor` místo `PointerSensor`.** Myš spouští tažení po 8 px, dotyk až po
  250 ms držení: kalendář se svisle scrolluje, takže na prahu vzdálenosti by každé posunutí prstem
  začalo táhnout objednávku místo rolování.
- **`attributes` z `useDraggable` se nepoužívají.** Přidávají `role="button"` a `tabIndex={0}`, což
  by uvnitř buňky měsíce (sama `<button>`) vytvořilo vnořené tlačítko a zdvojilo průchod
  tabulátorem. Tažení je zrychlovač pro myš; klávesnicová cesta ke změně termínu vede přes „Upravit".
- **`DragOverlay` místo transformace na místě.** Karta dne i buňka měsíce mají vlastní přetečení,
  takže by se položka při tažení k okraji oříznula.

Měsíc se kvůli tomu **nepřestavoval**: buňka zůstala `<button>` (drží si `aria-label`, Tab i Enter),
řádky uvnitř zůstaly `<span>` bez `onClick`, takže klik dál probublá na buňku a přepne na týden.
Přibyl jen obalový `<span class="schedule-month-drop">` jako cíl upuštění — `<div>` by tam nesměl,
`<button>` smí obsahovat jen phrasing content.

**Blokace v měsíci nemá vlastní řádek** — veze ji příznak „Zavřeno", který od 2026-08-07 nese
totéž co karta v týdnu: čas z `closureTimeText` a pod ním důvod (`Zavřeno · celý den`
+ „Školení techniků"). Uchopitelný je proto ten příznak, ne řádek; podmínky přebírá tatáž
`canDragOccurrence`, takže platí i tady „za první den" a „jen vedení". Je-li den zavřený pouze
podle otevírací doby, žádný výskyt neexistuje a zůstane holé „Zavřeno" bez popisu i bez tažení.

### AutocompletePair (`components/`)
Klíčová reusable komponenta (~300 řádků): debounced autocomplete s viditelným textem + skrytým ID inputem, `AbortController` proti race conditions, klávesová navigace, `appendParams` pro dynamické filtry. Kontrakt: `GET {endpoint}?q&limit` → `{data:[{id,value,description,detail}], hasMore}`.

`detail` je **volitelný třetí řádek** nabídky — plní ho jen našeptávač vozidel (VIN), ostatní ho nechávají prázdný a řádek se nevykreslí.

**Komponenta je neřízená:** `initialValue` a `initialSelectedId` čte jen při mountu, takže ji zvenčí přepsat nelze — jedině přemontováním přes `key`. Toho využívá `AppointmentForm`, když po výběru vozidla doplní do sousedního pole majitele auta. Pozor na past: obě pole se nesmí přemontovávat podle téže hodnoty, jinak doplnění jednoho smaže výběr v druhém (proto tam má každé pole vlastní čítač, ne sdílené `customerId`).

**Předvyplněný text musí odpovídat tomu, co vrátí výběr z nabídky.** Vozidla se skládají helperem `vehicleLabel()` ve `format.js` (`Značka Model - SPZ`), který zrcadlí `CONCAT_WS` v `VehicleMapper.xml`. Do 2026-08-07 se předvyplňovala jen SPZ, takže se text pole po prvním výběru změnil, přestože šlo o totéž auto.

### Dialogy zrušení a smazání zakázky

`OrderCancelDialog` a `OrderDeleteDialog` — dva protějšky se **stejnými třemi větvemi** podle
faktury (bez faktury / koncept / vystavená). Sjednoceno 2026-08-07: mazání mělo do té doby jen
prosté ano/ne, takže obsluha s konceptem dostala suché 422, musela odejít do faktur, koncept
smazat a vrátit se — zatímco zrušení jí totéž nabídlo jedním tlačítkem.

Co která větev znamená, rozhoduje sdílená `invoiceBlock(order)`; kdyby si stav faktury každý
dialog vykládal po svém, jeden by nabídl storno konceptu tam, kde by ho druhý odmítl.

Rozdíl mezi nimi je u **vystavené faktury** a je věcný: zrušení se ptá jen na *aktivní* fakturu,
takže dobropis zakázku uvolní a dialog na fakturu prokliká. Mazání se ptá na *jakoukoli* — po
vyfakturované zakázce zůstává řádek navždy, takže „vystavte dobropis a zkuste znovu" by byla
slepá ulička. Dialog proto nabídne rovnou **zrušení místo mazání**.

### Buňka názvu položky (`components/OrderItemName.jsx`)

Sdílená editovatelnou i read-only tabulkou. Pod názvem skládá jen podřádky, které u dané
položky dávají smysl: **katalogové číslo**, **původ** (dodavatel · číslo jeho faktury, proklik
na příjemku), **mechanik** u práce a **poznámka**.

Poznámka je nejstarší z nich — pole existuje od V12 a jde vyplnit v editačním okně, ale do
2026-08-07 ji **ani jedna** tabulka nevykreslovala: co tam mechanik napsal, viděl jen ten, kdo
položku znovu otevřel.

### Ceny s DPH v řádcích položek

Souhrn pod tabulkou částky s DPH nesl od začátku, **jednotlivé řádky ne** — přitom právě nad
řádkem se se zákazníkem domlouvá cena. Od 2026-08-07 má každý řádek „Cena/ks s DPH" i „Celkem
s DPH" (obě tabulky položek: editační i read-only na detailu).

Přepočet dělá `withVat(net, vatRate)` ve `format.js` — sdílený schválně, dvě kopie vzorce by se
lišily v zaokrouhlování a u téže položky by vyšlo na detailu jiné číslo než v editaci. Řádkový
součet se počítá **z částky bez DPH za celý řádek**, ne z jednotkové ceny s DPH: násobit už
zaokrouhlené číslo množstvím nasčítá haléřovou odchylku proti faktuře, která DPH počítá taky nad
řádkem. Celkový součet na detailu se z téhož důvodu sčítá **po řádcích** — položky mohou mít různé
sazby, takže jedna sazba nad součtem by dala jiné číslo.

### Akce nad zakázkou (`components/orderActions.jsx`)

**Jedna definice pro seznam, detail i editaci** (rozhodnutí uživatele 2026-08-07: *v seznamu jako
položky řádkového menu, na detailu a v editaci shodně jako tlačítka*).

Do té doby byla každá akce napsaná tam, kde se to zrovna hodilo: mazání a zakázkový list
v `OrdersPageDetail`, fakturace v `OrderForm`, výdej ze skladu v `OrderItemsWrapper`, změna stavu
zvlášť v detailu a zvlášť v `OrderTable`. Pět akcí na čtyřech místech, žádná sdílená — takže
seznam neuměl mazat, editace neuměla vytisknout zakázkový list a **na detailu, kam prázdný stav
faktur obsluhu výslovně posílal, nešlo fakturovat**. Ta nápověda lhala, protože nikdo neměl jak si
všimnout, že se místa rozešla.

Modul dělí odpovědnost na dvě části:

| Export | Co řeší |
|---|---|
| `orderActionItems(order, context)` | **co** se nabízí — čistý popis bez chování, aby ho uměl vykreslit `DataTable` (menu) i hlavička stránky (tlačítka) |
| `useOrderActions({onChanged, onDeleted})` | **co to udělá** — volání serveru, hlášky a všechny tři dialogy (zrušení, smazání, vytvoření faktury) |
| `OrderActionButtons` | vykreslení pro detail a editaci; stavy jdou do `TableRowActionMenu`, protože šest tlačítek by hlavičku rozstřelilo |

`context` je `"list" | "detail" | "edit"` a mění **jen navigaci sama na sebe**: v seznamu se nabízí
„Detail" i „Editovat", na detailu chybí „Detail", v editaci „Editovat". Zbytek sady je všude
totožný.

### Položky zakázky (nejsložitější celek)
`OrderItemsWrapper` („controller"): drží stav položek, CRUD, **drag-and-drop reorder** (optimistický update + rollback při chybě, `PUT /orders/:id/items/reorder`), summary, import z příjemky. Skládá `OrderItemTable` (dnd-kit: `DndContext` + `SortableContext`, `PointerSensor` s distance 8; druhé použití dnd-kit je přesun termínu v kalendáři — viz níže), `OrderItemsSummary`, `OrderItemsToolbar`, modály. Lišta nese **jen práci s položkami** — „Vydat ze skladu" a „Vytvořit fakturu" odsud 2026-08-07 odešly do hlavičky stránky, protože jsou to akce nad celou zakázkou a tady je našel jen ten, kdo šel do editace.

**Mechanik na LABOR položce** (D-1): wrapper natáhne aktivní zaměstnance (`GET /employees?activeOnly=true`) a předá je `OrderItemFormModal`, kde se u typu LABOR ukáže select „Mechanik". Po výběru wrapper předvyplní `purchasePrice` sazbou mechanika (D-6, jde přepsat); při přepnutí typu na ne-LABOR mechanika zahodí. Odešlý mechanik u editované položky se nabídne jako „(mimo číselník)" (vzor jednotky). `OrderItemTable` zobrazí jméno mechanika jako podřádek pod názvem.

### Náhled dokladu na kontrolní obrazovce (ReceiptReviewPage)
Náhled originálu zabírá `col-lg-5`, takže na soupis řádků zbývá 579 px z 1280 — u dokladu s mnoha
sloupci je to málo. Tlačítkem **„Skrýt náhled"** se panel sbalí na svislý pruh u pravého okraje
(`.pdf-rail`, `writing-mode: vertical-rl`, `position: sticky`) a draft dostane plnou šířku (935 px).
Volba se pamatuje v `localStorage` pod klíčem `receipt.pdfPreview` — obsluha, která kontroluje
doklady podle papíru, si náhled zavře jednou a zůstane zavřený.

### Uživatelská nápověda (HelpPage)
Route `/help/:slug` + položka „Nápověda" v sidebaru. Články jsou statické markdown soubory v `src/help/` (Vite import `?raw`), registrované v `src/help/index.js` (`HELP_ARTICLES` — slug, title, content); render přes `react-markdown` + **`remark-gfm`** (rozhodnutí R-4, U7.2) — fungují tedy **tabulky**,
přeškrtnutý text, automatické odkazy i zaškrtávací seznamy. HTML v článcích zůstává zakázané:
`rehype-raw` se vědomě nepoužívá, jinak by obsah článku mohl vložit libovolnou značku do stránky. Přidání článku = nový `.md` + řádek do registru. Psáno jazykem obsluhy servisu; párový dokument pro vývojáře patří do `docs/funkce/` (viz CLAUDE.md).

**Typografie: `src/css/help.css`** (U7.1). Existuje proto, že `reset.css` maže marginy nadpisům,
odstavcům i seznamům — v aplikaci je totiž řeší komponenty. Nápověda ale komponenty nemá, je to
vykreslený markdown, takže článek byl jeden slitý blok, kde se nadpis dotýkal textu nad i pod sebou.
Pravidla platí **jen uvnitř `.help-article`**, ať se rytmus textu nevlije do zbytku aplikace.
Šířka textu je omezená na `68ch`; seznam článků má `.help-nav` se `position: sticky` od 768 px výš.
Tabulky se obalují `.help-table` s `overflow-x: auto` — široká tabulka scrolluje uvnitř a neroztáhne
stránku, stejné pravidlo jako `.table-responsive` v aplikaci (§10.7).

**Nadpisy se posouvají o úroveň níž** (`components` u `<Markdown>`): `#` v článku se vykreslí jako
`h2.help-h1`, `##` jako `h3.help-h2`. Stránka má jediné `h1` („Nápověda" v `PageHeader`) — jinak by
čtečka obrazovky hlásila dva názvy stránky. Velikost řídí **třída, ne úroveň značky**, takže markdown
zůstává čitelný i mimo aplikaci.

## 6. Globální stav

Jediný context: `AlertContext` — `addAlert(message, type)`, typy **`success` | `danger` | `info`**
(`info` = neutrální oznámení: zamítnuto, stornováno, zrušeno — není to chyba ani úspěch).
`AlertContainer` se renderuje **portálem do `document.body`** (fixed top-right, auto-hide 15 s)
a má **`z-index: 1080`**, tedy nad modalem (1055): toast často oznamuje výsledek akce spuštěné
právě z dialogu a pod ním by ho nikdo neviděl. Portál je nutný proto, aby byl toast v témže
kořenovém kontextu jako modal — bez něj se nad modal dostával jen shodou pořadí vykreslení. Kdy toast a kdy inline alert řeší §10.6. Vše ostatní je lokální `useState`. Žádný
Redux/Zustand.

## 7. Auth flow z pohledu FE

1. `LoginPage` → `POST /auth/login` → cookies nastaví server → `navigate('/dashboard')`.
2. Auth stav se nikde nedrží — spoléhá se na cookie; `Layout` volá `/auth/me` jen kvůli jménu (a od zavedení správy uživatelů i rolím) v sidebaru.
3. 401 kdekoli → nejdřív jeden pokus o single-flight refresh (`tryRefresh()` v `apiFetch`/`getBlob`/`requireAuth`), teprve při jeho selhání redirect na `/login`.
4. Logout: odkaz v Sidebaru → `POST /auth/logout` + redirect.
5. **Role z `/auth/me`** (`MeResponse.roles`, pole `GrantedAuthority` řetězců jako `ROLE_ADMIN`) se používají jen k **skrytí UI** (položka „Uživatelé" v Sidebaru) — nejde o route guard, autoritativní je vždy backendový `@PreAuthorize`. Bez role v odpovědi (starý token před rozšířením `MeResponse`) by se položka jen neukázala, žádná chyba.
6. **Změna hesla** má dvě různé cesty: self-service (`ChangePasswordModal`, kdokoliv, vyžaduje současné heslo) vs. admin reset (`ResetPasswordModal` na `UsersPage`, jen `ROLE_ADMIN`, bez současného hesla). Nejde zaměňovat.

## 8. Konvence

- Pojmenování: `XxxPage[Create|Detail|Edit].jsx`, `XxxTable.jsx`, `XxxForm.jsx`, `useXxxRowActions.js`.
- `id` při editaci vždy z `useParams()`, nikdy z `formData` (viz `konvence.md`).
- UI texty česky; chybové hlášky z backendu přes `err.problem?.detail` (`ApiError` z `api.js`, viz sekce 4).
- Bootstrap first; MUI jen `TableRowActionMenu` a `PaginatorRounded`.

## 9. Stav sjednocení UI

**Plán [plan-ui.md](plan-ui.md) je dokončený (22. 7. 2026).** Nálezy S-01…S-28 z
[analyza-ui-2026-07.md](analyza-ui-2026-07.md) jsou vyřešené; §10 níže je **závazný vzor pro nový
kód** a `npm run check` jeho dodržování hlídá staticky.

Co zůstalo otevřené a **proč to plán neřešil**:

| Věc | Kde je zapsaná | Proč mimo plán |
|---|---|---|
| ~~Adresu existujícího zákazníka nelze změnit~~ | ~~TD-42~~ | **vyřešeno 2026-07-25** — `UpdateRequest.addresses` je volitelné pole (full-replace) a `CustomerForm` adresní sekci v editu ukazuje; tenhle řádek tu zůstal stát a tvrdil opak (audit 6.3) |
| Systematický audit přístupnosti | TD-44 | vlastní disciplína, ne otázka jednotného vzhledu; jmenovité nálezy uzavřela Vlna 4 auditu |
| Anglický text ze seedu v historii tachometru | TD-45 | datová oprava migrací, ne UI |
| Řazení zbývajících sloupců bez whitelistu | TD-46 | dopočítané sloupce (hodnota zásoby, stav) nemá kam řadit |
| MUI kvůli třem prvkům | TD-43 | rozhodnutí R-1: **ponechat**, výměna by byla čistá práce navíc |

Nové nekonzistence patří do [tech-dluhy.md](tech-dluhy.md), ne sem.

---

## 10. UI konvence

**Kontrola: `npm run check`** (`scripts/check-ui.mjs`) — hlídá deset pravidel, která `vite build`
nezachytí: komponenta použitá bez importu (v JS legální zápis, spadne až za běhu), **pojmenovaný
import z lokálního modulu, který ho neexportuje**, **`setXxx()` volané, ale nikde v souboru
nezavedené** (zbytek po odstraněném `useState`), vlastní `<h1>` místo `PageHeader`, ručně psaný
`.modal show d-block` místo `Modal`, celoobrazovková vrstva bez `createPortal`, `text-bg-*` místo
`StatusBadge`, `text-truncate` na tlačítku a od vlny 4 auditu dvě pravidla přístupnosti:
**`<IconButton>` bez `aria-label`** (MUI ikona je `aria-hidden`, takže tlačítko nemá žádný
přístupný název — a přes `TableRowActionMenu` vedou všechny řádkové akce) a **`<th>` v `<thead>`
bez `scope="col"`** (ručně psané tabulky ho neměly ani jedna). Všechna tři „runtime" pravidla
vznikla z reálných pádů: `StatusBadge is not defined` na detailu příjemky, `formatQuantity`
chybějící ve `format.js`, a `setRejectNote` zapomenutý v obsluze tlačítka, kvůli kterému přestalo
fungovat „Zamítnout".
Pouštět po každé změně UI a povinně před uzavřením fáze plánu.

> Závazné od 2026-07-21 (fáze U1 plánu). Odůvodnění a katalog variant, které tyto vzory nahradily,
> je v [analyza-ui-2026-07.md](analyza-ui-2026-07.md) §4.
> **Pravidlo: každý stavební prvek má právě jeden vzor.** Když potřebuješ jiný, uprav sdílenou
> komponentu — nezakládej variantu ve stránce.

### 10.1 Hlavička stránky — `PageHeader`

Používá ji **seznam, detail i formulář**, aby měly stejný nadpis (`h1.h3`) a stejné umístění akcí.

```jsx
<PageHeader
    title={customer.displayName}
    subtitle={customer.customerNumber}     // číslo dokladu, VIN, SKU — ne do nadpisu
    backTo="/customers"                    // ikonové tlačítko vlevo od nadpisu
    badges={<StatusBadge tone="success">Aktivní</StatusBadge>}
    actions={<>
        <button className="btn btn-outline-secondary">Editovat</button>
        <button className="btn btn-outline-danger">Deaktivovat</button>
    </>}
/>
```

- **„Zpět“ patří jen do `backTo`**, nikdy mezi akce vpravo. Ve formuláři se ruší slovem **„Zrušit“**. Výjimka: zůstává-li Uložit na stránce (editace zakázky), druhé tlačítko formuláře jen naviguje a jmenuje se „Zpět“ (viz §Formuláře).
- Hlavička se láme (`flex-wrap`) — nikdy `flex-nowrap` + `text-truncate` na tlačítku.

### 10.2 Seznamová stránka

```jsx
<PageHeader title="Zákazníci" actions={<button className="btn btn-primary">Nový zákazník</button>} />

<ListToolbar>
    <SearchFilter id="customerSearch" label="Hledat zákazníka"
                  placeholder="Jméno, příjmení nebo název firmy"
                  value={search} onChange={setSearch} className="col-12 col-xl-8" />
    <ToggleFilter id="customerActiveOnly" label="Jen aktivní"
                  checked={isActive} onChange={setIsActive} className="col-auto pb-2" />
</ListToolbar>
```

- **Popisek nad každým filtrem**, i nad hledáním (žádné `visually-hidden`).
- `id` se předává zvenčí — komponenty nemají natvrdo zadané `id` ani texty.
- Tlačítko vytvoření je v `PageHeader`, ne mezi filtry.
- Tabulka vždy uvnitř `.table-responsive` (viz §5 Tabulky).

### 10.2b Detailová stránka — `MetricRow`, `MetricCard`, `DetailCard`

```jsx
<PageHeader title={customer.displayName} subtitle={customer.customerNumber} backTo="/customers" />

<MetricRow>
    <MetricCard label="Věrnostní body" value={customer.loyaltyPoints ?? 0} unit="b." />
    <MetricCard label="Skladem" value={formatQuantity(p.quantityOnHand)} unit={p.unit}
                tone={p.lowStock ? "danger" : undefined} />
</MetricRow>

<DetailCard title="Kontakt">…</DetailCard>

<DetailCard title="Historie tachometru"
            action={<button className="btn btn-sm btn-outline-primary">Přidat čtení</button>}>
    …
</DetailCard>
```

- `DetailCard` drží spodní mezeru (`mb-3`) — stránka ji nepřidává. Do U4.1 měla polovina
  detailů `mb-3` a druhá `mb-4`, takže se rozestupy lišily stránku od stránky.
- Nadpis sekce je `h2.h6` — stránka má jediné `h1` v `PageHeader`. Titulek smí být i JSX
  (ikona před textem).
- `fullHeight` je pro kartu, která vyplňuje buňku mřížky (`h-100`, dvojice Dodavatel/Odběratel
  na faktuře) — mezeru tam drží gutter řádku, ne karta.
- `MetricCard` bez hodnoty vypíše „—" a **jednotku pak neukazuje** („— ks" nedává smysl).
  `tone` je jen zvýraznění údaje, který je jinde vysvětlený slovem (R-7 — barva sama význam nenese).

### 10.3 Formulář

```jsx
<PageHeader title="Editace zákazníka" backTo="/customers" />
<p className="text-muted small">Pole označená <RequiredMark /> jsou povinná.</p>

<form ref={formRef} className={`needs-validation ${validated ? "was-validated" : ""}`} noValidate>
    <FormSection title="Základní údaje">
        <div className="row g-3">
            <div className="col-md-4">
                <label className="form-label" htmlFor="firstName">Jméno <RequiredMark /></label>
                <input id="firstName" name="firstName" className="form-control" required … />
                <div className="invalid-feedback">Zadejte jméno zákazníka</div>
            </div>
        </div>
    </FormSection>

    <FormActions onCancel={onCancel} onSubmit={handleSave} saving={saving} />
</form>
```

- `FormSection` je karta se stejným nadpisem jako `DetailCard` — detail a formulář vypadají
  jako dvě podoby téže stránky.
- **Žádný `container`** ve formuláři — šířku drží layout.
- Zelenou validační zpětnou vazbu potlačuje `index.css`: `was-validated` maluje jen chyby,
  ne fajfky do prázdných nepovinných polí.
- **Každé `required` pole má v popisku `<RequiredMark />`** a nad formulářem je věta
  „Pole označená \* jsou povinná." Hvězdička je `aria-hidden` — čtečce povinnost sdělí
  atribut `required`, jinak by ji ohlásila dvakrát.
- **Po neúspěšné validaci se skáče na první chybu**: `focusFirstInvalid(formRef)`
  z `api/formUtils.js`, volané v `else` větvi `handleSave` uvnitř `requestAnimationFrame`
  (chyby nastavované stavem, např. rok výroby u vozidla, přibydou až po překreslení).
  Bez toho uživatel po odeslání dlouhého formuláře kouká na nezměněnou obrazovku.
- `AutocompletePair` nemůže mít `required` — hodnotu drží skrytý input a prohlížeč by
  validoval napsaný text, ne výběr. Povinnost proto hlásí `aria-required` a prop `required`
  vykreslí značku; kontrola je v `handleSave`.

### 10.4 Modaly — `Modal`

`Modal` je **jediné** místo s `.modal show d-block`. Řeší Esc, klik na pozadí, `modal-backdrop`,
focus trap, vrácení fokusu a `body.modal-open` (zámek scrollu).

**Renderuje se portálem do `document.body`** (`createPortal`), stejně jako `AlertContainer`.
Bez toho dialog vyvolaný z komponenty uvnitř stacking kontextu (sidebar má `position: sticky`)
zůstal v tom kontextu uvězněný a jeho `z-index` neplatil vůči obsahu stránky — `ChangePasswordModal`
se pak vykreslil ZA kartami formuláře. `z-index` má smysl porovnávat jen uvnitř téhož stacking
kontextu; portál dá modalu i toastu společný kořenový kontext, takže 1080 (toast) > 1055 (dialog)
> 1050 (backdrop) konečně platí. `npm run check` hlídá, že žádná celoobrazovková vrstva nevznikne
bez `createPortal`.

```jsx
<Modal show={show} title="Skladový pohyb" onClose={handleClose} closable={!saving}
       footer={<>
           <button className="btn btn-outline-secondary" onClick={handleClose}>Zrušit</button>
           <button className="btn btn-primary" onClick={handleSubmit}>Uložit pohyb</button>
       </>}>
    {error && <div className="alert alert-danger py-2">{error}</div>}
    …
</Modal>
```

- Pořadí tlačítek vždy **`Zrušit` → hlavní akce**; chyba vždy jako první prvek těla.
- Během ukládání `closable={false}` — dialog nejde zavřít uprostřed requestu.
- Zrušit je **`btn-outline-secondary`**, ne plné šedé — §10.8 zná jen obrys.
- Fokus po otevření míří na první pole v **těle** dialogu, ne na křížek v hlavičce
  (hledat v celém dialogu nestačí, křížek je v pořadí první).
- Potvrzení ano/ne = `ConfirmDialog` (staví na `Modal`). Dialog s doplňujícím polem
  **nesmí** být `ConfirmDialog` s vloženým `<textarea>` — na to je `FormModal`.

**`FormModal`** = dialog, který se před akcí na něco doptá (důvod storna, poznámka
k uzavření inventury). Pole se popisují deklarativně, komponenta zařídí `required`,
značku povinnosti, fokus, odeslání Enterem a hlášku **uvnitř** dialogu:

```jsx
<FormModal
    show={showCancel}
    title="Stornovat příjemku?"
    intro={<p>Naskladněné zboží se odepíše kompenzačními pohyby…</p>}
    fields={[{ name: "note", label: "Důvod", type: "textarea", maxLength: 500,
               required: true, requiredMessage: "Důvod storna je povinný." }]}
    submitLabel="Stornovat"
    onSubmit={({ note }) => cancelReceipt(note)}
    onCancel={() => setShowCancel(false)}
    saving={saving}
/>
```

### 10.5 Badge — `StatusBadge`

Jediný styl: jemný pill `bg-*-subtle text-*-emphasis rounded-pill`. Plné `text-bg-*` se nepoužívá.

| Tón | Význam |
|---|---|
| `success` | platné, dokončené, zaplacené |
| `warning` | čeká na akci uživatele |
| `danger` | chyba, propadlé, stornované |
| `info` | probíhá |
| `secondary` | neaktivní, koncept |
| `primary` | nové, přijaté |

Mapy hodnota → tón žijí **v `format.js`** (`getOrderStatusTone`, `getInvoiceStatusTone`,
`getReceiptStatusTone`, `getStockTakeStatusTone`, `getMileageSourceTone`, `getStkBadge().tone`,
`getActiveTone`), nikdy inline ve stránce.

Stav záznamu se píše jednotně přes `getActiveLabel` / `getActiveTone` („Aktivní“ / „Neaktivní“).

### 10.6 Prázdný, načítací a chybový stav

```jsx
<EmptyState icon="people" title="Zatím žádní zákazníci." hint="Nového založíte tlačítkem nahoře." />
<LoadingState />                       {/* celostránkové načítání */}
<LoadingState label="Načítám PDF…" inline />
<ErrorState message={error} backTo="/warehouse/receipts" backLabel="Zpět na příjemky" />
```

**Chybový stav vždy nabízí cestu ven** — nikdy holý `alert alert-danger` bez tlačítka.

Alerty:
- **Toast (`addAlert`)** = výsledek akce, po které se pokračuje jinde (uložení, potvrzení, smazání).
  Typy `success` | `danger` | `info`.
- **Inline alert** = trvalý stav obrazovky (neprošlé kontroly příjemky, „inventura je uzavřená“).
  Neúspěch uložení do téhle kategorie **nepatří** — je to výsledek akce, tedy toast.
- **Chyba uvnitř dialogu** zůstává v dialogu (`FormModal`, `MileageFormModal`): týká se hodnot,
  na které se uživatel právě dívá, a dialog po ní zůstává otevřený.
- Tvar chybové hlášky: **„<Předmět> se nepodařilo <sloveso>.“** Bez „Zkuste to znovu“, vždy s tečkou.
  Hlášky ze serveru (`err.problem.detail`) se nepřepisují — mají přednost před fallbackem.
- **Neúspěch, který uživatel jinak nepozná, musí mít toast.** Přeskládání položek zakázky se při
  chybě jen tiše vrátilo do původního pořadí a vypadalo to jako chyba přetahování.

### 10.7 Responsivita

Cíl: použitelné **od 768 px** (rozhodnutí R-2). Telefon jen nouzově — tabulky se scrollují.

- Tabulky vždy v `.table-responsive`; `#main-content` nemá `overflow-x: hidden` a má `min-width: 0`.
- Seznamy staví na `DataTable` (§10.2): sloupce jako data, sloupec „Akce" s třitečkovým menu,
  `emptyState`, volitelný `onRowClick` (teprve on přidá `.table-clickable` a tím `cursor: pointer`)
  a **řazení klikem na hlavičku** — `sortBy`/`sortDesc` drží stránka a posílá je do API.
- Hlavičky a lišty filtrů se lámou; `text-truncate` na tlačítku je zakázané.
- `cursor: pointer` na řádku jen přes `.table-clickable` (nastavuje ho tabulka, když je řádek
  opravdu klikatelný) — ne globálně na `.table-hover`.
- **Scrollbar uvnitř tmavé plochy se styluje**, jinak systémový světlý pruh vypadá jako cizí
  element nalepený na obsah: `scrollbar-width: thin` + `scrollbar-color: <táhlo> transparent`,
  a pro WebKit (Chrome < 121, Safari) navíc `::-webkit-scrollbar*`. Vzor je `#sidebar` v `index.css`.
- Postranní panel, který ukrajuje šířku hlavnímu obsahu (náhled dokladu), musí jít **sbalit**
  na svislý pruh — vzor `.pdf-rail`, volba do `localStorage`.
- **Hlavní menu se jen přepíná vysunuto/zasunuto** a stav mění vždy jen uživatel, nikdy šířka
  okna (volba v `localStorage`, klíč `sidebar.open`). Pod 768 px se vysunuté menu jen *položí
  přes* obsah (`position: fixed` + `.sidebar-backdrop`, klik na podklad ho zasune) — vedle sebe
  by z 375 px zbylo obsahu 135 px a dlaždice detailu měly 48 px. Chování menu se tím nemění,
  mění se jen to, jestli obsah zmáčkne, nebo překryje.

### 10.8 Barevná sémantika tlačítek

Barva kóduje **důsledek** akce, ne důležitost. Nejvýš **jedno plné** tlačítko na obrazovku.

| Tón | Třída | Význam | Příklady |
|---|---|---|---|
| Modrá plná | `btn-primary` | hlavní akce, **vratná** | Nový zákazník, Uložit, Vytvořit zakázku |
| Zelená plná | `btn-success` | posun dokladu/procesu, **nevratný** | Potvrdit a naskladnit, Vystavit, Označit zaplaceno, Uzavřít inventuru |
| Šedý obrys | `btn-outline-secondary` | neutrální, navigace | Editovat, PDF, Zrušit, Uložit koncept |
| Červený obrys | `btn-outline-danger` | ruší, odebírá, zamítá | Deaktivovat, Stornovat, Zamítnout |
| Zelený obrys | `btn-outline-success` | **jen** protějšek červeného obrysu na témže přepínači | Aktivovat (na místě Deaktivovat) |

- Zelený obrys nezakládá samostatný tón: je to tentýž knoflík, který u neaktivního záznamu
  mění význam z „odebrat" na „vrátit". Nikde jinde se nepoužívá.
- `btn-outline-primary` neexistuje — neutrální akce je vždy `btn-outline-secondary`.
- Pořadí zleva doprava: **neutrální → hlavní → destruktivní**; destruktivní oddělené (`ms-auto`).
  V `PageHeader` je celý blok akcí zarovnaný doprava, takže tam stačí pořadí — `ms-auto` má smysl
  jen v lištách přes celou šířku (procesní obrazovky).
- Na **procesních obrazovkách** (kontrola příjemky, inventura) je průběžné uložení neutrální
  („Uložit koncept", „Uložit soupis") a plné zelené je až vyvrcholení procesu („Potvrdit
  a naskladnit", „Uzavřít inventuru"). Uložení rozdělané práce není totéž co její dokončení.
- `btn-warning` a `btn-info` se na tlačítka nepoužívají (kontrast + kolize s významem alertů).
- Barva nikdy nenese informaci sama — každé tlačítko má sloveso, destruktivní i potvrzení.
