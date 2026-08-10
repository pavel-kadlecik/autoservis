# 11 — Frontend

> Audit 2026-07-30 · rozsah: `frontend/autoservis-frontend/` (React 19 SPA) — stavy načítání/prázdno/chyba,
> validace vs. backend, refetch po mutaci, efekty a cleanup, konzistence vzorů dle `docs/frontend.md §10`,
> přístupnost, mobil, mrtvý kód, peníze a čísla, datum/čas, API klient, autorizace v UI.
> metoda: čtení celých souborů (celý `src/`, `scripts/check-ui.mjs`, `package.json`, `vite.config.js`),
> křížové ověření proti backendovým DTO, Flyway migracím a `GlobalExceptionHandler`; spuštěn `npm run check`
> (prošel). Dev server ani build se nespouštěl, v repu se nic neměnilo.

## Co bylo přečteno

**Povinná četba:** `CLAUDE.md`, `docs/konvence.md`, `docs/tech-dluhy.md`, `docs/frontend.md` (546 ř.),
`docs/oprava-chybove-stavy-2026-07.md`.

**Konfigurace a nástroje:** `frontend/autoservis-frontend/package.json`, `vite.config.js`,
`scripts/check-ui.mjs` (celý), `src/index.css`, `src/css/reset.css`, `src/css/help.css`.
ESLint konfigurace v projektu **není** (`.eslintrc*` ani `eslint.config.*` neexistuje, `package.json`
nemá `lint` skript) — jediná statická kontrola je `check-ui.mjs`.

**`src/api/`:** `api.js`, `auth.js`, `format.js`, `customerPayload.js`, `employeePayload.js`,
`formUtils.js`, `units.js` — vše celé.

**`src/` kostra:** `App.jsx`, `main.jsx`, `context/AlertContext.jsx`, `hooks/useRowActions.js`.

**Stránky (celé):** `CustomersPage`, `CustomersPageCreate`, `CustomersPageDetail`, `CustomersPageEdit`,
`VehiclesPage`, `VehiclesPageCreate`, `VehiclesPageDetail`, `OrdersPage`, `OrdersPageCreate`,
`OrdersPageDetail`, `OrdersPageEdit`, `InvoicesPage`, `InvoicesPageDetail`, `WarehousePage`,
`WarehousePageDetail`, `LowStockPage`, `StockTakePageDetail`, `ReceiptsPage`, `ReceiptReviewPage`,
`SuppliersPage`, `SuppliersPageDetail`, `UsersPage`, `EmployeesPage`, `CompanyProfilePage`,
`LoginPage`, `DashboardPage`, `NotFoundPage`.
Cíleně ověřeny odchylky (grep + čtení dotčených úseků) u `VehiclesPageEdit`, `SuppliersPageEdit`,
`UsersPageCreate/Edit`, `EmployeesPageCreate/Edit`, `WarehousePageCreate/Edit`, `StockTakesPage`, `HelpPage`.

**Komponenty (celé):** `Layout`, `Sidebar`, `navigation.js`, `RequireAuth`, `ErrorBoundary`, `PageHeader`,
`DataTable`, `Modal`, `ConfirmDialog`, `FormModal`, `FormActions`, `FormSection`, `DetailCard`, `MetricCard`,
`EmptyState`, `LoadingState`, `ErrorState`, `StatusBadge`, `RequiredMark`, `Alert`, `AlertContainer`,
`AutocompletePair`, `TableRowActionMenu`, `PaginatorRounded`, `CustomerForm`, `VehicleForm`, `OrderForm`,
`WarehouseForm`, `UserForm`, `EmployeeForm`, `OrderItemsWrapper`, `OrderItemsSummary`, `OrderItemsToolbar`,
`OrderItemFormModal`, `ImportProductFormModal`, `InvoiceCreateFormModal`, `MileageFormModal`,
`StockMovementModal`, `ChangePasswordModal`, `ResetPasswordModal`, `ReceiptDraftHeaderForm`,
`ReceiptDraftLinesTable`, `CustomerTable`, `InvoiceTable`, `filters/*` (4).

**Backend pro porovnání validace:** `OrderItemDto.java`, `CustomerDto.java`, `AddressDto.java`,
`UserDto.java`, `ChangePasswordRequest.java`, `exception/GlobalExceptionHandler.java`,
`mapper/InvoiceItemMapper.xml`, `service/impl/StockTakeServiceImpl.java`,
`db/migration/V12__init_order_item_schema.sql`, `V14__init_billing_schema.sql`.

`src/help/*.md` jen letmo (audituje jiný průchod).

## Shrnutí

Frontend je na výukový projekt nadprůměrně disciplinovaný: sdílené komponenty (`DataTable`, `Modal`,
`PageHeader`, `FormSection`/`FormActions`, `StatusBadge`, `EmptyState`/`LoadingState`/`ErrorState`) jsou
skutečně sdílené a používané, formátování je centralizované v `format.js` bez jediné kopie, TD-47
(časové zóny) je opravdu vyřešen jednou implementací, `api.js` má korektní single-flight refresh,
refetch po mutaci funguje všude a v `src/` nezůstal žádný mrtvý soubor ani `console.log`.

Slabinou je **chybová cesta**. TD-60 tvrdí, že „detaily/seznamy bez ošetření chyby načtení" jsou vyřešené —
nejsou: **13 míst** načítá data bez `try/catch`, takže selhání se projeví buď prázdným seznamem
(„Zatím žádní zákazníci."), nebo věčným spinnerem. K tomu se nikde (kromě jedné komponenty) nečte
`err.problem.errors[]`, takže každá 400 z Bean Validation dorazí k uživateli jako generické
„Ověření zadaných údajů selhalo" — a protože FE validace nezrcadlí serverové `@Pattern`/`@Size`
u zákazníka, uživatel se do téhle slepé uličky dostane běžným vstupem (IČO na 5 číslic).

Nejzávažnější je jedno místo v inventuře: uzavření inventury pokračuje i po selhání uložení soupisu
a skončí úspěšnou hláškou — nevratná operace nad daty, která uživatel nezadal.

Statický kontrolor `check-ui.mjs` je dobrý nápad s dírami: hlídá 8 pravidel, ale **ne** povinný
`.table-responsive` (§10.7), **ne** barevnou sémantiku tlačítek (§10.8) a **ne** soubory `.js`.
Všechna tři pravidla, která nehlídá, jsou v kódu porušená.

**Počty:** 🔴 KRITICKÝ 0 · 🔴 VYSOKÝ 1 · 🟠 STŘEDNÍ 8 · 🟡 NÍZKÝ 12 (celkem 21).

---

## Nálezy

### [F-1] Uzavření inventury proběhne i když se soupis neuložil — nevratně a s hláškou o úspěchu
**Severita:** 🔴 VYSOKÝ
**Jistota:** OVĚŘENO
**Kde:** `frontend/autoservis-frontend/src/pages/StockTakePageDetail.jsx:108` (volání),
`:95-97` (spolknutá výjimka v `saveCounts`), `:109-111` (pokračování + toast)

**Co je špatně:** `closeStockTake` nejdřív uloží rozeditované hodnoty voláním `saveCounts()`:

```js
if (Object.keys(edits).length > 0) await saveCounts();
const closed = await api.post(`/warehouse/stock-takes/${id}/close`, { note: note || null });
```

`saveCounts()` má ale vlastní `try/catch`, který výjimku **zachytí a dál ji nepošle**
(`catch (err) { setError(...) }`, řádky 95-97). `await saveCounts()` tedy nikdy nevyhodí, takže
`POST /close` se provede **bez ohledu na to, zda se soupis uložil**. Backend
(`StockTakeServiceImpl.close`, `src/main/java/.../service/impl/StockTakeServiceImpl.java:174-192`)
čte výhradně to, co je v DB (`mapper.findItems(id)`), a řádky bez `countedQuantity` přeskočí
(`:178`). Inventura se pak uzavře podle **neuložených** (starých nebo žádných) hodnot.

**Scénář selhání:**
1. Skladník napočítá 40 řádků inventury (hodnoty jsou zatím jen ve stavu `edits`, netlačil „Uložit soupis").
2. Klikne „Uzavřít inventuru" → potvrdí v dialogu.
3. `PUT /warehouse/stock-takes/{id}/items` selže — např. 409 (`STOCK_TAKE_ALREADY_PROCESSED`),
   422 z validace položky nebo výpadek sítě. Zobrazí se červený banner „Soupis se nepodařilo uložit."
4. Kód pokračuje, `POST /close` uspěje. Inventura přejde do **CLOSED** (nevratně, dialog to sám hlásí:
   „Akce je nevratná").
5. Skladník dostane zelený toast **„Inventura uzavřena — korekce byly zapsány do skladu."**
   Na obrazovce má zároveň červenou hlášku a zelený toast; zelený je ten poslední a viditelnější.
6. Do skladu se nezapsala žádná korekce (nebo se zapsaly korekce podle starší uložené verze soupisu).
   Napočítané hodnoty jsou pryč, inventuru nelze znovu otevřít.

**Proč to vadí:** data + provoz. Nevratná operace se provede nad stavem, který uživatel nezadal,
a je potvrzena hláškou o úspěchu. Sklad zůstane nesrovnaný, ale v systému je „uzavřená inventura",
takže se na to nepřijde do příští inventury. Hodiny počítání se ztratí bez možnosti obnovy.

**Že jde o odchylku, ne o záměr, dokazuje sesterská obrazovka:** `ReceiptReviewPage.confirmReceipt`
(`src/pages/ReceiptReviewPage.jsx:213-219`) řeší tentýž problém správně — uložení draftu volá
**inline** uvnitř svého `try`, takže jeho selhání celý `confirm` zruší.

**Návrh řešení:** stejný vzor jako v `ReceiptReviewPage`: v `closeStockTake` volat `api.put(...items)`
přímo uvnitř `try` (ne přes `saveCounts`), nebo dát `saveCounts` volitelný parametr
`{ rethrow: true }`. Sekundárně: `close` neposílat, pokud `error` není prázdný.

---

### [F-2] Načítání seznamů a detailů nemá ošetřenou chybu — 13 míst (TD-60 tvrdí opak)
**Severita:** 🟠 STŘEDNÍ
**Jistota:** OVĚŘENO
**Kde:** seznamy — `pages/CustomersPage.jsx:39-44`, `pages/VehiclesPage.jsx:39-44`,
`pages/OrdersPage.jsx:39-44`, `pages/InvoicesPage.jsx:35-40`, `pages/SuppliersPage.jsx:36-41`,
`pages/UsersPage.jsx:37-42`, `pages/WarehousePage.jsx:49-54` a `:68-72`, `pages/LowStockPage.jsx:27-29`;
detaily — `pages/CustomersPageDetail.jsx:40-50`, `pages/VehiclesPageDetail.jsx:47-56`,
`pages/InvoicesPageDetail.jsx:33-36`, `pages/WarehousePageDetail.jsx:37-40`,
`pages/CompanyProfilePage.jsx:25-29`; navíc `components/UserForm.jsx:25-27`
(`api.get("/code-lists/roles").then(setRoles)` bez `.catch`).

**Co je špatně:** funkce načítající data nemají `try/catch`. Např. `CustomersPage.jsx:39-44`:

```js
async function loadCustomers() {
    const data = await api.get(`/customers?${params.toString()}`);
    setCustomers(data.content);
    ...
}
const timer = setTimeout(loadCustomers, 400);
```

Návratovou promisu nikdo nekonzumuje → odmítnutí skončí jako *unhandled rejection* v konzoli.
`ErrorBoundary` asynchronní chyby nezachytává, takže uživatel nedostane nic.

**Scénář selhání (seznam):** backend vrátí 500 nebo 503 (restart, chyba SQL) při otevření
`/customers`. `customers` zůstane `[]` → `CustomerTable` vykreslí `EmptyState`
**„Zatím žádní zákazníci."** s nápovědou „Nového založíte tlačítkem nahoře."
Obsluha uvidí, že zákazníci **nejsou**, ne že se je nepodařilo načíst. Totéž u faktur, zakázek,
skladu, uživatelů, dodavatelů. Na `WarehousePage.jsx:68-72` se navíc při selhání
`/warehouse/stock-valuation` vypíše „Hodnota zásob (nákupní, bez DPH) **0,00 Kč**"
(`:161` — `valuation.totalValue ?? 0`), tedy nepravdivé číslo místo prázdné hodnoty.

**Scénář selhání (detail):** uživatel otevře `/customers/999999/detail` (zastaralý odkaz, smazaný
záznam). `api.get` vyhodí 404, `customer` zůstane `null`, komponenta se zasekne na
`if (!customer) return <LoadingState />` (`:68`) — **spinner „Načítám…" navždy**, bez hlášky
a bez cesty ven. Totéž `LowStockPage:33`, `InvoicesPageDetail:80`, `WarehousePageDetail:57`,
`VehiclesPageDetail:142`, `CompanyProfilePage:63`.

**Proč to vadí:** provoz + důvěra v data. Prázdný seznam neodlišený od chyby je horší než chyba —
uživatel podle něj jedná (založí duplicitního zákazníka, protože „tam není"). Věčný spinner
porušuje `docs/frontend.md §10.6` („Chybový stav vždy nabízí cestu ven").

**Vztah k dokumentaci:** `docs/tech-dluhy.md` TD-60 uvádí jako vyřešené mj. „detaily/seznamy bez
ošetření chyby načtení" a `docs/oprava-chybove-stavy-2026-07.md §3.D` mluví o „~13 catch blocích
u načítání". Oprava ale doplnila `err.problem?.detail` do **existujících** catch bloků; místa,
která žádný catch neměla, zůstala nedotčená. Dluh je tedy zapsán jako uzavřený, ale otevřený je.
*(Nehlásím jako „vědomý dluh" právě proto, že je evidován jako vyřešený.)*

**Vzor, jak to vypadá správně, v tomtéž repu:** `pages/DashboardPage.jsx:32-58`
(`error` stav + `EmptyState` s tlačítkem „Zkusit znovu"), `pages/StockTakePageDetail.jsx:31-43`
(`ErrorState` s `backTo`), `pages/EmployeesPage.jsx:26-38` (navíc `cancelled` guard).

**Návrh řešení:** u detailů převzít vzor `StockTakePageDetail` (`error` stav → `<ErrorState … backTo>`),
u seznamů vzor `ReceiptsPage.jsx:56-63` (toast s `err.problem?.detail`) a rozlišit prázdný stav
od chybového (např. `EmptyState icon="exclamation-triangle"` místo běžného prázdna).

---

### [F-3] Validační chyby ze serveru se uživateli ukážou jako „Ověření zadaných údajů selhalo" — `errors[]` se nečte
**Severita:** 🟠 STŘEDNÍ
**Jistota:** OVĚŘENO
**Kde:** `src/main/java/cz/palo/autoservis/exception/GlobalExceptionHandler.java:121-144`
(zdroj), proti tomu FE: `src/pages/CustomersPageCreate.jsx:47`, `CustomersPageEdit.jsx:58`,
`VehiclesPageCreate.jsx:36`, `OrdersPageCreate.jsx:32`, `OrdersPageEdit.jsx:66`,
`UsersPageCreate/Edit`, `EmployeesPageCreate/Edit`, `WarehousePageCreate/Edit`,
`CompanyProfilePage.jsx:57`, `components/OrderItemsWrapper.jsx:215` — všude jen
`err.problem?.detail ?? "<fallback>"`. Jediné místo, které čte `errors[]`, je
`src/components/StockMovementModal.jsx:105-109`.

**Co je špatně:** handler pro `MethodArgumentNotValidException` vrací jako `detail` **konstantní**
větu a konkrétní hlášky ukládá do pole `errors[]`:

```java
errors.add(ErrorDetail.ofField(fieldError.getField(), code, message));
...
return buildProblemDetail(HttpStatus.BAD_REQUEST, "Ověření zadaných údajů selhalo", request, errors);
```

Frontend čte pouze `detail`. `docs/frontend.md:100` přitom pravidlo zná:
„Pole s chybami jednotlivých polí (validace) čti přes `err.problem?.errors`." — dodržuje ho jedna
komponenta z ~20.

**Scénář selhání:** uživatel zakládá firemního zákazníka a do IČO napíše `1234`.
FE nic nevaliduje (viz F-4), pošle POST. Backend odmítne s 400:
`detail = "Ověření zadaných údajů selhalo"`,
`errors = [{ field: "ico", code: "PATTERN", message: "IČO musí mít přesně 8 číslic" }]`.
Uživatel uvidí červený toast **„Ověření zadaných údajů selhalo."** — bez jména pole, bez důvodu,
bez zvýraznění v formuláři. U formuláře zákazníka s ~15 poli nemá jak zjistit, co je špatně.

**Proč to vadí:** provoz. Uživatel je zablokovaný u založení zákazníka/vozidla/uživatele a nemá
informaci k opravě, přestože ji server poslal. V kombinaci s F-4 to není hypotetická situace.

**Návrh řešení:** jeden sdílený helper v `api/api.js` (např. `problemMessage(err, fallback)`),
který složí `detail` + `errors[].message` (vzor už existuje ve `StockMovementModal.jsx:105-109`),
a použít ho ve všech catch blocích mutací. Ideálně navíc mapovat `errors[].field` na
`is-invalid` u konkrétních polí formuláře.

---

### [F-4] Validace formuláře zákazníka nezrcadlí serverová pravidla (IČO, DIČ, telefon, délky)
**Severita:** 🟠 STŘEDNÍ
**Jistota:** OVĚŘENO
**Kde:** `src/components/CustomerForm.jsx:114-117` (IČO), `:119-122` (DIČ), `:163-166` (telefon),
`:105-110` (název firmy), `:124-127` (právní forma), `:133-142` (jméno/příjmení), `:157-161` (e-mail)
— proti `src/main/java/cz/palo/autoservis/model/dto/customer/CustomerDto.java:41-70` (CreateRequest)
a `:74-125` (UpdateRequest).

**Co je špatně:** backend má na těchto polích `@Pattern` / `@Size`, formulář nemá **žádné** odpovídající
`pattern`/`maxLength`, a v jednom případě má limit **volnější** než server:

| Pole | Backend | Frontend |
|---|---|---|
| `ico` | `@Pattern("^$\|^\\d{8}$")` | žádný `pattern`, žádný `maxLength` |
| `dic` | `@Pattern("^$\|^CZ\\d{8,10}$")` | žádný `pattern`, žádný `maxLength` |
| `primaryPhone` | `@Pattern("^$\|^\\+?[\\d\\s\\-()]{7,20}$")` | jen `maxLength={30}` — **delší než server** |
| `firstName` / `lastName` | `@Size(max = 100)` | bez `maxLength` |
| `companyName` | `@Size(max = 255)` | bez `maxLength` |
| `legalForm` | `@Size(max = 100)` | bez `maxLength` |
| `primaryEmail` | `@Size(max = 255)` | bez `maxLength` |
| `postalCode` | `@Pattern("^\\d{3}\\s?\\d{2}$")` | `pattern="\d{3}\s?\d{2}"` ✅ shoda |

`handleSave` (`:55-63`) spoléhá výhradně na `form.checkValidity()`, takže bez HTML atributů projde cokoli.

**Scénář selhání:** obsluha zakládá firmu, vyplní 12 polí, do telefonu napíše
`+420 123 456 789 kl. 22` (23 znaků — FE povolí do 30, server max 21) a do IČO `1234`.
Kliknutí na „Uložit" → formulář neoznačí nic červeně (`checkValidity()` projde) → server vrátí 400 →
uživatel dostane jen toast „Ověření zadaných údajů selhalo" (F-3) → formulář zůstane vyplněný,
ale uživatel neví, které ze dvou vadných polí opravit.

**Proč to vadí:** provoz. Vzor „Pole označená \* jsou povinná" + `needs-validation` slibuje inline
validaci; u poloviny polí je to prázdný slib. Chybný formát IČO je navíc běžná chyba přepisu z dokladu.

**Návrh řešení:** doplnit do `CustomerForm` `pattern`/`maxLength` shodné s DTO a `invalid-feedback`
s hláškou (vzor VIN v `VehicleForm.jsx:213-217`). Zvážit sdílenou konstantu vzorů
(např. `api/validation.js`) s poznámkou „zrcadlí DTO — při změně upravit obojí", jak už projekt
dělá u `ALLOWED_UNITS` (`api/units.js:1-10`) a `REQUIRED_HEADER_FIELDS` (`api/format.js:719-720`).

---

### [F-5] Sazba DPH 0 % se u položky zakázky tiše přepíše na 21 % (a množství 0 na 1)
**Severita:** 🟠 STŘEDNÍ
**Jistota:** OVĚŘENO
**Kde:** `src/components/OrderItemsWrapper.jsx:195` a `:191`

**Co je špatně:**

```js
quantity:      parseFloat(itemForm.quantity) || 1,
unitPrice:     parseFloat(itemForm.unitPrice) || 0,
vatRate:       parseInt(itemForm.vatRate) || 21,
```

`parseInt("0")` je `0`, což je v JS *falsy*, takže `0 || 21` vrátí **21**. Nula je přitom
legitimní hodnota: formulář ji explicitně nabízí (`OrderItemFormModal.jsx:73-74` — `min="0" max="100"`),
DTO ji povoluje (`OrderItemDto.java:63-66` — `@Min(0) @Max(100)`) a DB taky
(`V12__init_order_item_schema.sql:30` — `CHECK (vat_rate >= 0 AND vat_rate <= 100)`).
Stejný problém má množství: `parseFloat("0") || 1` → `1`.

Formulář položky navíc **není `<form>`**, takže `min`/`max` na inputech prohlížeč nikdy nevaliduje
(ukládá se kliknutím na tlačítko, ne submitem) — jsou to jen šipky spinneru.

**Scénář selhání:**
1. Mechanik přidá do zakázky položku pro plnění osvobozené od DPH (např. přeúčtování pro zákazníka
   z jiného státu EU) a do „DPH %" napíše `0`.
2. Uloží. Na server odejde `vatRate: 21`. Tabulka položek pak ukazuje `21 %`
   (`OrderItemTable.jsx:33`), ale nikde není hlášeno, že hodnota byla změněna.
3. Z položek zakázky se vytvoří faktura — `InvoiceItemMapper.xml:38-42` kopíruje `vat_rate` beze změny,
   `V32__v_invoice_price_totals.sql:18` z něj počítá DPH řádku.
4. Doklad odejde zákazníkovi s 21 % DPH, které tam nemá být, a stejná částka se přizná finančnímu úřadu.

**Proč to vadí:** peníze a daně. Tichá změna daňové sazby na dokladu je chyba, kterou nikdo
nezachytí, protože UI vypadá „jako by to tak uživatel zadal".

**Návrh řešení:** nahradit falsy-fallback explicitní kontrolou prázdna:
`vatRate: itemForm.vatRate === "" ? 21 : parseInt(itemForm.vatRate, 10)` a stejně u `quantity`
(prázdno → 1, jinak parsovaná hodnota; nulu odmítnout hláškou, protože `@Positive` na serveru
ji zakazuje). Vzor už v projektu existuje: `WarehouseForm.jsx:38-40`
(`formData.defaultVatRate === "" ? null : Number(...)`).

---

### [F-6] `AutocompletePair` obchází `api` klienta — po vypršení tokenu ukáže „Chyba při načítání: HTTP 401"
**Severita:** 🟠 STŘEDNÍ
**Jistota:** OVĚŘENO
**Kde:** `src/components/AutocompletePair.jsx:105-132` (zejména `:115` a `:126-131`);
konzumenti: `OrderForm.jsx:194` a `:214` (zákazník + vozidlo při zakládání zakázky),
`VehicleForm.jsx:132` (majitel vozidla), `ImportProductFormModal.jsx:72` (doklad k importu).

**Co je špatně:** komponenta volá `fetch(url, {signal})` napřímo, ne přes `api`/`apiFetch`.
Tím jí chybí **refresh-and-retry na 401**, který `api.js:64-74` dělá pro všechna ostatní volání
(single-flight `tryRefresh()`). Chyba se navíc zobrazí jako syrové `err.message`:

```js
if (!res.ok) throw new Error(`HTTP ${res.status}`);
...
.catch((err) => { ... setError(err.message); ... })
```

a v dropdownu se vypíše `Chyba při načítání: HTTP 401` (`:263-267`).
Cookies se posílají (relativní URL → same-origin → default `credentials: 'same-origin'`),
takže o autentizaci samotnou nejde — jde o chybějící obnovu tokenu a o hlášku.

**Scénář selhání (produkce, access token 15 min):**
1. Obsluha otevře „Nová zakázka" a odejde od počítače na 20 minut.
2. Vrátí se, začne psát jméno zákazníka do našeptávače.
3. `GET /api/v1/customers/autocomplete` vrátí 401. Refresh se **nespustí**.
4. Pod polem se objeví `Chyba při načítání: HTTP 401`. Zakázku nelze založit — zákazník i vozidlo
   se vybírají výhradně našeptávačem a bez nich `handleSave` (`OrderForm.jsx:131-142`) odeslání blokuje.
5. Uživatel neví, že stačí načíst stránku znovu; hláška je technická a anglická.

**Proč to vadí:** provoz + `docs/frontend.md §4` a `docs/konvence.md §17` („API volání přes `api` klienta").
Zakládání zakázky je hlavní denní úkon a jediná cesta ven je F5.

**Návrh řešení:** buď volat přes `api.get()` (komponenta si `AbortController` může nechat a řešit
zrušení kontrolou `selectedId`/pořadí odpovědí), nebo minimálně po 401 zavolat `tryRefresh()`
z `api.js` a request jednou zopakovat, a chybu vypsat česky
(„Našeptávač se nepodařilo načíst.").

---

### [F-7] Tlačítko „Zrušit" na stránce „Nové vozidlo" nedělá nic — a jiná cesta ven ze stránky není
**Severita:** 🟠 STŘEDNÍ
**Jistota:** OVĚŘENO
**Kde:** `src/pages/VehiclesPageCreate.jsx:41-47` (chybí prop `onCancel`),
`src/components/VehicleForm.jsx:40` (bez defaultu) a `:317` (`<FormActions onCancel={onCancel} …>`),
`src/components/FormActions.jsx:37-40` (`onClick={onCancel}`)

**Co je špatně:** `VehiclesPageCreate` renderuje formulář bez `onCancel`:

```jsx
<VehicleForm
    initialData={initialData}
    onSave={onSave}
    title="Vytvoření nového vozidla"
    showInitialMileage
/>
```

`VehicleForm` prop dál předá do `FormActions`, kde skončí jako `onClick={undefined}` → tlačítko
„Zrušit" je mrtvé. `VehicleForm` navíc volá `<PageHeader title={title} />` **bez `backTo`**
(`:120`), takže na stránce není ani šipka zpět.

Je to jediná výjimka v aplikaci — ověřeno napříč všemi formulářovými stránkami:
`CustomersPageCreate:55`, `CustomersPageEdit:68`, `VehiclesPageEdit:64`, `OrdersPageCreate:39`,
`OrdersPageEdit:79`, `SuppliersPageEdit:63`, `UsersPageCreate:39`, `UsersPageEdit:55`,
`EmployeesPageCreate:44`, `EmployeesPageEdit:60`, `WarehousePageCreate:39`, `WarehousePageEdit:59`
— všechny `onCancel` předávají.

**Scénář selhání:** uživatel otevře Vozidla → „Nové vozidlo", začne vyplňovat, rozmyslí si to
a klikne „Zrušit". Nestane se **nic** — žádná navigace, žádná hláška. Tlačítko vypadá funkčně
(není `disabled`). Uživatel klikne znovu, pak sáhne po tlačítku zpět v prohlížeči nebo po sidebaru.

**Proč to vadí:** provoz. Nereagující ovládací prvek uživatel čte jako zamrznutí aplikace;
na téhle stránce navíc není žádná jiná nabídnutá cesta ven (`backTo` chybí).

**Návrh řešení:** doplnit `onCancel={() => navigate("/vehicles")}` do `VehiclesPageCreate`
(vzor `WarehousePageCreate.jsx:39`). Zvážit pojistku ve `FormActions`: tlačítko „Zrušit"
nevykreslovat (nebo `disabled`), když `onCancel` chybí — mrtvé tlačítko pak nemůže vzniknout znovu.

---

### [F-8] Řádkové menu tabulek (tři tečky) nemá přístupný název — jediná cesta k akcím ve všech seznamech
**Severita:** 🟠 STŘEDNÍ
**Jistota:** OVĚŘENO
**Kde:** `src/components/TableRowActionMenu.jsx:29-35`

**Co je špatně:**

```jsx
<IconButton size="small" onClick={handleOpen} id={`action-button-${rowData?.id}`}>
    <MoreVertIcon fontSize="small"/>
</IconButton>
```

MUI `IconButton` vyrenderuje `<button>`, jehož jediným obsahem je SVG ikona; MUI ikony mají
`aria-hidden="true"`. Tlačítko tedy nemá **žádný přístupný název** — ani `aria-label`, ani `title`,
ani textový obsah. `id` název netvoří.

Přes tuhle komponentu vedou **všechny** řádkové akce v aplikaci: Detail, Editovat,
Deaktivovat/Aktivovat, Vystavit fakturu, Označit zaplaceno, Stornovat, Reset hesla
(`DataTable.jsx:129-134` — jediná tabulka, kterou seznamy používají).

**Scénář selhání:** uživatel s odečítačem obrazovky projde tabulku zákazníků. U každého řádku
uslyší jen „tlačítko" — bez informace, co dělá a ke kterému řádku patří. Nemá jak zjistit,
že tudy vede editace a deaktivace; v seznamu 10 řádků uslyší 10× „tlačítko".

**Proč to vadí:** přístupnost (WCAG 2.1 AA, 4.1.2 Name, Role, Value). Není to obecné „chybí audit"
(TD-44), ale konkrétní porušení na jediném ovládacím prvku, přes který vede veškerá práce se
záznamy. Oprava je jednořádková.

**Návrh řešení:** `aria-label={`Akce — ${rowData?.name ?? rowData?.displayName ?? rowData?.id}`}`
(nebo prostě `aria-label="Akce"` + `aria-haspopup="menu"` a `aria-expanded={open}`).
Zvážit i doplnění pravidla do `check-ui.mjs` (`IconButton` bez `aria-label`).

---

### [F-9] Nedostupný backend znamená věčný spinner nad celou aplikací
**Severita:** 🟠 STŘEDNÍ
**Jistota:** OVĚŘENO
**Kde:** `src/api/auth.js:11-27` (`requireAuth` — `fetch` bez `try/catch`),
`src/components/RequireAuth.jsx:14-32` (`requireAuth().then(...)` bez `.catch`),
`src/components/Layout.jsx:26-28` (totéž)

**Co je špatně:** `requireAuth()` ošetřuje jen `!response.ok`. Když `fetch` **odmítne** (síťová chyba,
reverse proxy neběží, server restartuje), vyhodí `TypeError: Failed to fetch`. `RequireAuth` promisu
nekonzumuje `.catch`, takže `checking` zůstane `true` a komponenta se zasekne na:

```jsx
if (checking) { return ( <div className="d-flex … vh-100"><div className="spinner-border" …/></div> ); }
```

Guard obaluje **celý** layout (`App.jsx:48`), takže se to netýká jedné stránky, ale celé aplikace.

**Scénář selhání:** uživatel má aplikaci otevřenou, backend se restartuje kvůli nasazení, uživatel
načte stránku znovu (F5). `GET /auth/me` selže na úrovni spojení → **bílá obrazovka s točícím se
spinnerem donekonečna**, bez textu a bez tlačítka. Uživatel nepozná, jestli se to načítá, jestli
je odhlášený, nebo jestli je aplikace rozbitá.

**Proč to vadí:** provoz. Porušuje `docs/frontend.md §10.6` („Chybový stav vždy nabízí cestu ven").
Situace nastane při každém nasazení a při každém výpadku sítě.

**Návrh řešení:** v `auth.js` obalit `fetch` do `try/catch` a při síťové chybě vrátit rozlišitelný
výsledek (např. `throw new Error("NETWORK")`); v `RequireAuth` doplnit `.catch` a místo spinneru
zobrazit `ErrorState` („Server je nedostupný.") s tlačítkem „Zkusit znovu"
(vzor `DashboardPage.jsx:43-58`).

---

### [F-10] Toasty se ze stavu nikdy neodstraní — pole `alerts` roste po celou dobu relace
**Severita:** 🟡 NÍZKÝ
**Jistota:** OVĚŘENO
**Kde:** `src/components/Alert.jsx:17-22`, `src/context/AlertContext.jsx:25-27`

**Co je špatně:** po uplynutí `time` (15 s, `Layout.jsx:48`) se toast jen přestane vykreslovat:

```js
useEffect(() => {
    const timer = setTimeout(() => setVisible(false), time);
    return () => clearTimeout(timer);
}, [time]);

if (!visible) return null;
```

`onClose()` (a tedy `removeAlert`) se volá **jen** z křížku (`:28`). Automaticky skrytý toast tak
zůstane v poli `alerts` v `AlertProvider` napořád.

**Scénář selhání:** obsluha pracuje celý den v jedné otevřené kartě a provede ~300 akcí
(uložení, potvrzení, chyby). V paměti zůstane 300 objektů a 300 namountovaných `<Alert>` komponent
vracejících `null`; každé nové `addAlert` překreslí celý seznam. Uživatelsky se to projeví
nanejvýš mírným zpomalením — neteče nic viditelného.

**Proč to vadí:** únik paměti a zbytečná práce při renderu; roste lineárně s délkou relace.
Nezpůsobí výpadek, ale je to čistá chyba životního cyklu.

**Návrh řešení:** v `Alert` po vypršení volat i `onClose()` (`setTimeout(() => { setVisible(false); onClose(); }, time)`),
nebo lépe auto-hide přesunout do `AlertProvider` (jeden `setTimeout` per alert, který volá `removeAlert`),
a `Alert` nechat bezstavový.

---

### [F-11] Jediná tabulka mimo `.table-responsive` je zrovna souhrn marže — a `check-ui.mjs` toto pravidlo nehlídá
**Severita:** 🟡 NÍZKÝ
**Jistota:** OVĚŘENO
**Kde:** `src/components/OrderItemsSummary.jsx:34-36`; kontrolor `scripts/check-ui.mjs`
(pravidla 1–5, `.table-responsive` mezi nimi není); pravidlo `docs/frontend.md:143`, `:343`, `:506`

**Co je špatně:** `docs/frontend.md:143` říká „**Každá tabulka patří do `<div className="table-responsive">`**".
Ověřeno napříč celým `src/`: obal má `DataTable.jsx:85`, `OrderItemTable.jsx:69`,
`ReceiptItemsTable.jsx:67`, `ReceiptDraftLinesTable.jsx:103`, `GoodsReceiptImportModal.jsx:238`,
`DashboardStatisticsModal.jsx:77`, `StockTakePageDetail.jsx:164`. Nemá ho jediná tabulka:

```jsx
<div className="d-flex justify-content-end">
    <div className="w-75">
    <table className="table w-100 table-hover table-sm">
```

Tabulka má 6 sloupců (Náklad / Marže / Marže % / bez DPH / s DPH) s peněžními částkami
a šířku pevně `w-75`.

**Scénář selhání:** obsluha otevře editaci zakázky na telefonu (375 px). Souhrn marže se nevejde,
nemá vlastní scroll → roztlačí `#main-content` (který podle `index.css:155` **nemá**
`overflow-x: hidden`) a vodorovně se scrolluje **celá stránka**. Hlavička stránky i lišta tlačítek
se posunou mimo obraz — přesně stav, kterému mělo pravidlo U0.3 zabránit.

**Proč to vadí:** kosmetika + použitelnost na mobilu. Podstatnější je, že `npm run check` prošel
bez nálezu (ověřeno: `check-ui: OK — 106 souborů, 8 pravidel bez nálezu`) — kontrolor tohle pravidlo
nezná, takže se porušení může opakovat.

**Návrh řešení:** obalit tabulku `.table-responsive` a do `check-ui.mjs` přidat pravidlo
„`<table` bez předcházejícího `table-responsive` v témže souboru". Při té příležitosti
`OrderItemsSummary.jsx:28` má **anonymní** `export default function({summary})` — pojmenovat
(konvence `docs/konvence.md §17` i čitelnost React DevTools).

---

### [F-12] `btn-outline-primary` se používá 5×, ačkoli §10.8 říká, že neexistuje — a dokumentace si sama odporuje
**Severita:** 🟡 NÍZKÝ
**Jistota:** OVĚŘENO
**Kde:** `src/components/OrderItemsToolbar.jsx:12`, `:16`, `:20`;
`src/pages/VehiclesPageDetail.jsx:273`, `:319`.
Dokumentace: `docs/frontend.md:538` („`btn-outline-primary` neexistuje — neutrální akce je vždy
`btn-outline-secondary`") vs. `docs/frontend.md:359` (příklad `DetailCard`, který ho **předepisuje**:
`action={<button className="btn btn-sm btn-outline-primary">Přidat čtení</button>}`).

**Co je špatně:** normativní dokument obsahuje dvě protichůdná pravidla a kód následuje to horní
(§10.2b). `check-ui.mjs` §10.8 nehlídá vůbec (žádné pravidlo na barvy tlačítek).

**Scénář selhání:** vývojář přidá novou detailovou kartu s akcí, otevře `docs/frontend.md §10.2b`,
zkopíruje vzor s `btn-outline-primary`. `npm run check` projde. Nekonzistence se rozšíří.
Uživatelsky: na detailu vozidla jsou dvě modře orámovaná tlačítka („Aktualizovat z registru",
„Přidat čtení") vedle šedě orámovaného „Editovat" — barva tam nekóduje nic.

**Proč to vadí:** kosmetika a udržovatelnost. Pravidlo „jeden vzor na prvek" ztrácí sílu, když
si dokument protiřečí a kontrolor mlčí.

**Návrh řešení:** rozhodnout jednu variantu (doporučení: držet §10.8 a `btn-outline-secondary`),
opravit příklad na `frontend.md:359`, přepsat 5 výskytů a přidat do `check-ui.mjs` pravidlo
na `btn-outline-primary` / `btn-warning` / `btn-info`.

---

### [F-13] Dokumentace i komentáře v kódu tvrdí, že adresu zákazníka nelze editovat — TD-42 je přitom vyřešený
**Severita:** 🟡 NÍZKÝ
**Jistota:** OVĚŘENO
**Kde:** `docs/frontend.md:164` („**adresy jen v create režimu** — `CustomerDto.UpdateRequest`
pole `addresses` nemá, takže PUT je nepřijímá");
`src/components/CustomerForm.jsx:13-15` (JSDoc: „v edit režimu se adresní sekce nezobrazuje");
`src/pages/CustomersPageEdit.jsx:35-36` („Adresy formulář v edit režimu needituje (UpdateRequest je nezná)").

**Co je špatně:** všechna tři tvrzení jsou nepravdivá. Skutečný stav:
- `CustomerDto.UpdateRequest.addresses` **existuje** (`CustomerDto.java:121`, s komentářem „TD-42").
- `customerPayload.toUpdatePayload` (`src/api/customerPayload.js:86-88`) posílá `addresses`.
- `CustomerForm` renderuje adresní sekci **bez** podmínky na `isEditMode`
  (`CustomerForm.jsx:181-282` — bloky jsou uvnitř holého fragmentu, žádný `isEditMode &&`).

`docs/tech-dluhy.md` TD-42 je zapsaný jako „VYŘEŠENO 2026-07-25", ale synchronizace `frontend.md`
a komentářů se nedotáhla.

**Scénář selhání:** vývojář dostane úkol „umožnit editaci adresy", přečte `frontend.md:164`,
usoudí, že to vyžaduje zásah do backendu, a začne implementovat něco, co už existuje.
Nebo naopak: uvěří komentáři na `CustomersPageEdit.jsx:35` a při refaktoringu adresní bloky
z edit režimu „uklidí", čímž funkci rozbije.

**Proč to vadí:** dokumentace je zdrojem rozhodnutí; nepravdivá dokumentace stojí čas a může vést
k regresi. CLAUDE.md pravidlo „Po každé změně, která mění fakta v dokumentaci, aktualizuj příslušný
dokument" tu nebylo dodrženo.

**Návrh řešení:** přepsat `frontend.md:164` a smazat/opravit oba zastaralé komentáře.

---

### [F-14] Přihlašovací obrazovka: popisky bez `htmlFor`, chybí `autocomplete`, anglická technická chyba
**Severita:** 🟡 NÍZKÝ
**Jistota:** OVĚŘENO
**Kde:** `src/pages/LoginPage.jsx:43-59` (popisky a inputy), `:21` (fallback chybové hlášky)

**Co je špatně:** tři drobnosti na jediné obrazovce, kterou uživatel vidí každý den:

1. `<label className="form-label">Uživatelské jméno</label>` a `Heslo` nemají `htmlFor`, inputy
   nemají `id` ani `aria-label` → pole nemají přístupný název, klik na popisek nezaostří pole.
2. Inputy nemají `autoComplete="username"` / `autoComplete="current-password"` ani `name` —
   správci hesel a prohlížeč nemají podle čeho pole poznat a nabídnout uložené přihlášení.
3. `setError(err.problem?.detail ?? err.message)` — `err.message` je **surové tělo odpovědi**
   (`api.js:6`), a když `fetch` odmítne úplně, je to `TypeError` s textem `Failed to fetch`.

**Scénář selhání:** backend neběží, uživatel klikne „Přihlásit se" → v červeném alertu se objeví
anglické **„Failed to fetch"**. Porušuje `docs/konvence.md §17` („Fallback vždy česky").
Při 502 z reverse proxy se do alertu vypíše HTML tělo chybové stránky jako text.

**Proč to vadí:** kosmetika + přístupnost, ale na nejexponovanější obrazovce aplikace.

**Návrh řešení:** doplnit `id`/`htmlFor`/`name`/`autoComplete`/`required`; fallback změnit na
`err.problem?.detail ?? "Přihlášení se nezdařilo. Zkontrolujte připojení k serveru."`.

---

### [F-15] „Nastavení firmy" není `<form>` — hvězdička povinnosti u názvu firmy nic nevaliduje
**Severita:** 🟡 NÍZKÝ
**Jistota:** OVĚŘENO
**Kde:** `src/pages/CompanyProfilePage.jsx:65-149` (stránka nemá `<form>`, `needs-validation`,
`required` ani `invalid-feedback`), `:72` („Pole označená \* jsou povinná."), `:77-81`
(`Název firmy <RequiredMark />` na inputu bez `required`)

**Co je špatně:** stránka používá `FormSection` + `FormActions` + `RequiredMark`, ale kolem polí
není `<form>`, takže neexistuje `checkValidity()` ani `focusFirstInvalid` a vzor z
`docs/frontend.md §10.3` je dodržený jen vizuálně. Jediné povinné pole (`name`) nemá `required`.

**Scénář selhání:** účetní opraví adresu firmy, omylem smaže obsah pole „Název firmy" a uloží.
Formulář nic nezvýrazní, request odejde s `name: ""`, backend odmítne a uživatel dostane toast
(pravděpodobně generický, viz F-3). Správně měl vidět červené pole hned pod hvězdičkou.

**Proč to vadí:** kosmetika + konzistence. Údaje z téhle stránky se **zmrazují na každou vystavenou
fakturu** (`:69`), takže je to místo, kde má validace fungovat lépe než jinde, ne hůř.

**Návrh řešení:** obalit sekce `<form ref className="needs-validation …" noValidate>`, dát
`required` + `invalid-feedback` na `name` a `handleSave` doplnit o `checkValidity()` +
`focusFirstInvalid` (vzor `WarehouseForm.jsx:32-45`).

---

### [F-16] Formulářová pole na kontrolní obrazovce příjemky a v položce zakázky nemají přístupný název; ručně psané tabulky nemají `scope`
**Severita:** 🟡 NÍZKÝ
**Jistota:** OVĚŘENO
**Kde:** popisky bez `htmlFor` a inputy bez `id`/`aria-label` —
`src/components/ReceiptDraftHeaderForm.jsx:26-35` (funkce `field`, generuje **10** hlavičkových polí
včetně „Základ (bez DPH)", „DPH celkem", „Celkem s DPH") a `:63-72` (dodavatel, IČO);
`src/components/OrderItemFormModal.jsx:41`, `:50`, `:56`, `:61`, `:72`, `:99`, `:104`, `:110` (8 polí);
`src/components/OrderForm.jsx:175`, `:179`, `:183`, `:187` (read-only pole).
Inputy v tabulkách zcela bez názvu — `ReceiptDraftLinesTable.jsx:82-100` (funkce `cell`, 8 sloupců
na řádek) a `:60-80` (`unitCell`), `pages/StockTakePageDetail.jsx:187-200`.
Chybějící `scope="col"` na `<th>` — `OrderItemsSummary.jsx:38-45`, `ReceiptDraftLinesTable.jsx:107-117`,
`StockTakePageDetail.jsx:168-173`, `OrderItemTable.jsx`, `ReceiptItemsTable.jsx`,
`DashboardStatisticsModal.jsx`, `GoodsReceiptImportModal.jsx`.

**Co je špatně:** `docs/tech-dluhy.md` TD-44 uvádí, že fáze U1 přinesla „`scope` na hlavičkách".
Ověřeno: `scope="col"` má **jedině** `DataTable.jsx:94` a `:109`. Všech sedm ručně psaných tabulek
ho nemá. Podobně `docs/frontend.md §10.3` předepisuje `label htmlFor` + `id`; kontrolní obrazovka
příjemky a modal položky zakázky ho nemají u žádného pole.

**Scénář selhání:** uživatel odečítačem otevře kontrolu příjemky. Prochází Tabem 10 hlavičkových
polí a slyší jen „editace, text" — nepozná, které je „Základ bez DPH" a které „Celkem s DPH".
V soupisu řádků je to horší: 8 editovatelných polí na řádek, žádné z nich nemá název ani vazbu
na hlavičku sloupce (chybí `scope`), takže není jak zjistit, do kterého sloupce zapisuje.
Jde přitom o obrazovku, kde se opisují částky z faktury dodavatele.

**Proč to vadí:** přístupnost. TD-44 eviduje, že systematický audit chybí — tohle jsou konkrétní
porušení, navíc na obrazovkách s penězi. `check-ui.mjs` nic z toho nehlídá.

**Návrh řešení:** v `ReceiptDraftHeaderForm.field()` a `ReceiptDraftLinesTable.cell()` generovat
`id` (např. `` `hdr-${name}` ``, `` `line-${index}-${name}` ``) a přidat `htmlFor` / `aria-label`
(u buněk stačí `aria-label={`${popisekSloupce} — řádek ${position}`}`); doplnit `scope="col"`
do ručně psaných `<thead>`. Vzor správného řešení je v `ReceiptItemsTable.jsx:73`, `:95`, `:104`
(`aria-label` na checkboxu i na množství).

---

### [F-17] Kontrast hlaviček tabulek je 4,22:1 — pod WCAG AA
**Severita:** 🟡 NÍZKÝ
**Jistota:** OVĚŘENO
**Kde:** `src/index.css:173-175`

```css
.table thead th { background-color: #f1f3f5; font-size: 0.8rem;
    text-transform: uppercase; letter-spacing: 0.05em;
    color: #6c757d; border-bottom: 2px solid #dee2e6; }
```

**Co je špatně:** `#6c757d` na `#f1f3f5` dává poměr **4,22:1** (spočteno podle WCAG 2.1 relativní
luminance). Text je 0,8 rem ≈ 12,8 px, tedy „normální text", pro který AA požaduje **4,5:1**.
Pravidlo je globální (`.table thead th`), takže se týká hlaviček **všech** tabulek v aplikaci.

Pro srovnání jsem ověřil ostatní šedé texty a ty projdou: `.empty-state` `#6c757d` na bílé = 4,69:1;
Bootstrapí `.text-muted` (rgba(33,37,41,.75)) na bílé ≈ 6,3:1. Problém je jen tento jeden override.

**Scénář selhání:** uživatel na nekalibrovaném monitoru v dílně (odlesky, boční světlo) čte v šeru
hlavičku tabulky „CENA / MJ BEZ DPH" psanou 12,8px verzálkami s prostrkáním — nejhůř čitelná
kombinace, jaká v aplikaci je.

**Proč to vadí:** přístupnost (WCAG 2.1 AA 1.4.3) a čitelnost v provozu. TD-44 přímo zmiňuje
„projít kontrasty" jako neudělané — tohle je konkrétní číslo, které z toho vypadlo.

**Návrh řešení:** ztmavit na `#5a6268` (≈4,9:1 na `#f1f3f5`) nebo `#495057` (≈7,0:1),
případně zesvětlit pozadí hlavičky. Jednořádková změna v `index.css`.

---

### [F-18] `check-ui.mjs` kontroluje jen soubory `.jsx` — `api/`, `hooks/` a `navigation.js` nekontroluje vůbec
**Severita:** 🟡 NÍZKÝ
**Jistota:** OVĚŘENO
**Kde:** `frontend/autoservis-frontend/scripts/check-ui.mjs:25-33` (funkce `walk`, řádek 30:
`else if (entry.endsWith(".jsx")) out.push(path);`)

**Co je špatně:** kontrolor prochází jen `.jsx`. Mimo jeho dohled tak zůstává **11 souborů**
s reálnou logikou: `api/api.js`, `api/auth.js`, `api/format.js`, `api/customerPayload.js`,
`api/employeePayload.js`, `api/formUtils.js`, `api/units.js`, `components/navigation.js`,
`hooks/useRowActions.js` + 6 entitních hooků, `help/index.js`.

Nejcitelnější je pravidlo **1b** („pojmenovaný import z vlastního modulu, který ho neexportuje") —
přesně ta třída chyby, kvůli které pravidlo vzniklo (`formatQuantity` chybějící ve `format.js`,
komentář `:66-69`). Kdyby dnes `hooks/useRowActions.js` importoval z `api/api.js` neexistující
symbol, `npm run check` i `vite build` projdou a spadne to až za běhu při kliknutí na akci řádku.
Totéž pravidlo 1c (chybějící `setXxx`) — hooky drží `useState`, takže se ho to týká.

**Scénář selhání:** vývojář přejmenuje export v `api/format.js` a zapomene na importéra
v `hooks/useUserRowActions.js`. `npm run check` → OK, `npm run build` → OK, prohlížeč →
`ReferenceError` v okamžiku, kdy admin klikne na „Reset hesla".

**Proč to vadí:** kontrolor je podle `docs/frontend.md:18-20` **jediná** ověřovací síť frontendu
(„Frontend nemá testovou sadu … Ověřením změny je proto `npm run check` + `npm run build`").
Díra v jediné síti je nález.

**Návrh řešení:** ve `walk()` přijímat i `.js` (`entry.endsWith(".jsx") || entry.endsWith(".js")`).
Pravidla 2–5 (JSX/CSS) na `.js` souborech nic nenajdou, pravidla 1b a 1c ano.
Zvážit i minimální ESLint (`eslint-plugin-react-hooks`) — projekt dnes žádnou ESLint konfiguraci nemá.

---

### [F-19] „Vystavit" a „Označit zaplaceno" jdou z řádkového menu jedním klikem bez potvrzení
**Severita:** 🟡 NÍZKÝ
**Jistota:** OVĚŘENO
**Kde:** `src/components/InvoiceTable.jsx:60-78` (`handleAction` — `issue`/`pay` volají `transition`
přímo, jen `cancel` otevírá `ConfirmDialog`); shodně `src/pages/InvoicesPageDetail.jsx:119-126`
(`requestTransition('issue')` bez `confirmMessage`)

**Co je špatně:** vystavení faktury je podle `docs/frontend.md §10.8` „posun dokladu, **nevratný**"
(proto `btn-success`), a fakticky přiděluje číslo daňového dokladu a datum vystavení. Přesto se
provede jedním kliknutím v třitečkovém menu, bez dialogu. Storno — které je vratnější v tom smyslu,
že jen označí doklad — potvrzení má.

**Scénář selhání:** obsluha v seznamu faktur otevře menu u konceptu, chtěla „Detail", trefí
o položku níž „Vystavit". Faktura okamžitě dostane číslo a datum vystavení. Zpět to nejde —
jediná cesta je storno, které v číselné řadě nechá stornovaný doklad, jenž se musí vysvětlit účetní.

**Proč to vadí:** účetnictví a provoz. Nevratný krok bez potvrzení je v rozporu s tím, jak jsou
ošetřené ostatní nevratné akce v aplikaci („Potvrdit a naskladnit", „Uzavřít inventuru", „Stornovat"
— všechny mají dialog).

**Návrh řešení:** *rozhodnutí uživatele* — jestli je rychlost hromadného vystavování důležitější než
pojistka. Doporučení: přidat `ConfirmDialog` aspoň pro `issue` (číslo dokladu je nevratné);
u `pay` je záměna méně škodlivá a potvrzení může spíš překážet.

---

### [F-20] Na mobilu se menu po výběru položky nezasune a překrývá obsah
**Severita:** 🟡 NÍZKÝ
**Jistota:** OVĚŘENO
**Kde:** `src/components/Sidebar.jsx:63-79` (`renderItem` — `NavLink` bez `onClick`),
`:22` (JSDoc slibuje „zavírání na mobilu"), `src/index.css:25-33` a `:39-49`
(pod 768 px je `#sidebar` `position: fixed` a přes obsah leží `.sidebar-backdrop`)

**Co je špatně:** pod 768 px se vysunuté menu položí přes obsah. `NavLink` položky ale nikde nevolá
`onCollapse`, takže po kliknutí na „Zákazníci" router sice přejde na seznam, ale menu i tmavý
podklad zůstanou nahoře. JSDoc `Sidebar` přitom „zavírání na mobilu" uvádí jako své chování —
takový kód tam není.

**Scénář selhání:** obsluha na telefonu (375 px) otevře menu, klepne na „Zakázky". Obrazovka se
nezmění — pořád vidí menu a ztmavený podklad. Musí ještě klepnout vedle menu (na backdrop) nebo
na šipku «. Vypadá to, že klepnutí na položku nezabralo, takže lidé klepou opakovaně.

**Proč to vadí:** použitelnost na mobilu. `docs/frontend.md §10.7` mobil označuje jako „nouzový"
(R-2, cíl od 768 px), takže je to nízká priorita — ale JSDoc slibuje něco, co kód nedělá.

**Návrh řešení:** v `renderItem` přidat `onClick` volající `onCollapse()` jen na úzkém okně
(`if (window.matchMedia("(max-width: 767.98px)").matches) onCollapse?.()`), nebo JSDoc opravit,
aby neslibovala nic navíc.

---

### [F-21] Odkazy na zákazníka a vozidlo z detailu zakázky přenačtou celou aplikaci
**Severita:** 🟡 NÍZKÝ
**Jistota:** OVĚŘENO
**Kde:** `src/pages/OrdersPageDetail.jsx:96` a `:103`

```jsx
<a href={`/customers/${order.customerId}/detail`}>{order.customerDisplayName}</a>
<a href={`/vehicles/${order.vehicleId}/detail`}>{vehicleDisplayName || '—'}</a>
```

**Co je špatně:** místo `<Link>` z react-routeru je použit nativní `<a href>`, takže prohlížeč
udělá plné přenačtení SPA. Zbytek aplikace používá `<Link>` nebo `navigate()` — např.
`VehiclesPageDetail.jsx:246` na tentýž cíl (`/customers/{id}/detail`) používá `<Link>`.

**Scénář selhání:** uživatel je na detailu zakázky a klikne na jméno zákazníka. Aplikace se celá
znovu stáhne a nabootuje: `RequireAuth` znovu volá `/auth/me`, `Layout` volá `/auth/me` podruhé,
sidebar znovu načte stav. Místo okamžitého přechodu (~50 ms) čeká uživatel na spinner (stovky ms
až sekundy). Ztratí se i pozice scrollu a historie v rámci SPA.

**Proč to vadí:** výkon a konzistence, nic se neztratí. Na pomalé síti je to viditelné.

**Návrh řešení:** nahradit `<Link to={...}>` (import už na `:3` je — `useNavigate, useParams`;
stačí přidat `Link`).

---

## Co bylo ověřeno jako v pořádku

- **`api.js` (celý)** — `credentials: 'include'` u všech volání, `FormData` bez ručního
  `Content-Type`, tělo se čte jako text **před** kontrolou `ok` (ne-JSON 502 nezpůsobí `SyntaxError`),
  `ApiError {status, problem, message}`, 204 → `null`. Single-flight refresh je implementován
  správně: sdílená `refreshPromise` (`:13-27`), `finally` ji uvolní, `_retried` zabrání zacyklení,
  volání na `/auth/*` refresh nezkoušejí, `/auth/login` se nepřesměrovává. `getBlob` má tutéž logiku
  (`:116-128`). Timeouty ani retry (mimo 401) chybí — vzhledem k reverse proxy vědomě přijatelné.
- **Refetch po mutaci** — funguje všude, kde má: seznamy přes `refreshKey` (Customers/Vehicles/
  Suppliers/Users/Warehouse/Receipts/Employees), faktury přes `onChanged` → `setReload`
  (`InvoicesPage.jsx:100`), detaily přes odpověď mutace (`setProduct(updated)`, `setInvoice(updated)`,
  `setDetail(saved)`). Nenašel jsem místo, kde by uživatel po uložení viděl stará data.
- **TD-47 (časové zóny)** — `toDatetimeLocal`/`fromDatetimeLocal` jsou v `format.js:759-771`
  v jediné kopii; grep napříč `src/` nenašel žádnou lokální variantu ani ruční `substring(0,16)`.
  Používá je jen `OrdersPageEdit.jsx:31-32` a `OrderForm.jsx:151-152` (obousměrně). Vzor je dodržen.
- **Formátování peněz a čísel** — `formatCurrency`/`formatNumber`/`formatQuantity` výhradně
  ve `format.js`, žádná lokální kopie ani inline `toLocaleString`/`toFixed` u částek (U8.1 drží).
  Prošel jsem všechny výpočty v JS: součet položek (`OrdersPageDetail.jsx:168-171`), marže
  (`OrderItemsSummary.jsx:9-18`), cena s DPH (`WarehouseForm.jsx:128`, `WarehousePageDetail.jsx:134-136`),
  trendy na dashboardu (`DashboardPage.jsx:62-63`) — **všechny jsou jen k zobrazení**, žádný výsledek
  se neposílá na server. Autoritativní součty počítá DB (`v_invoice_price_totals`, `v_order_item_prices`).
  Float nepřesnost se nikde neprojeví, protože výstup jde přes `formatCurrency` (2 desetinná místa).
- **Mrtvý kód (R-12)** — žádný nepoužitý soubor v `components/`, `hooks/`, `api/`, `context/`
  (ověřeno křížovým grepem přes všechny `.js`/`.jsx`). Žádný `console.log` (jediný `console.error`
  je legitimní v `ErrorBoundary.jsx:23`). Žádné `TODO`/`FIXME`, žádné zakomentované bloky kódu.
  Z `format.js` jsou nadbytečně exportované tři konstanty používané jen interně
  (`RECEIPT_STATUS_LABELS`, `DOCUMENT_TYPE_LABELS`, `REQUIRED_LINE_FIELDS`) — na nález to nevydá.
- **Efekty a cleanup** — debounce `setTimeout` má všude `clearTimeout` v cleanupu;
  `AutocompletePair` má `AbortController` proti race (`:102-103`, `:134`) a `appendParams` je u jediného
  konzumenta memoizované `useCallback` (`ImportProductFormModal.jsx:22-25`), takže smyčka nehrozí;
  `cancelled` guard mají `RequireAuth`, `EmployeesPage`, `OrderItemsWrapper`, `ReceiptReviewPage`;
  `Modal` čte `onClose`/`closable` přes ref, takže se efekt nere-runuje při psaní (`Modal.jsx:31-37`).
  Žádný `setInterval`. Jediný drobný leak: object URL PDF v `ReceiptReviewPage.jsx:71-79` se
  nezruší, když se komponenta odmountuje dřív, než `getBlob` doběhne — příliš okrajové na nález.
- **Vzory §10** — `PageHeader` používá každá stránka (`check-ui` pravidlo 2 hlídá, prošlo),
  `FormSection`+`FormActions` všechny formuláře, `Modal`+`createPortal` všechny dialogy
  (pravidla 3 a 3b prošla), `StatusBadge` všechny odznaky (pravidlo 4), tóny výhradně z `format.js`.
  Ochrana proti dvojkliku je centrálně ve `FormActions.jsx:25-33` a formuláře skutečně vracejí
  promisu z `handleSave` (ověřeno u všech šesti).
- **Přístupnost — co je hotové:** `DataTable` má `scope="col"` a `aria-sort` na řaditelných
  hlavičkách (`:94-96`), `Modal` má focus trap, `aria-modal`, Esc, zámek scrollu a fokus míří na
  první pole v těle (`:39-87`), toasty mají `role="alert"`, `LoadingState` `role="status"` +
  `aria-live="polite"`, filtrové komponenty mají viditelné popisky s `htmlFor`
  (`SearchFilter`/`SelectFilter`/`ToggleFilter`), `RequiredMark` je `aria-hidden`, `AutocompletePair`
  má kompletní combobox ARIA (`:230-238`), `PaginatorRounded` má `aria-label` na selectu,
  `NotFoundPage` má `h1` přes `PageHeader`.
- **Autorizace v UI** — `Sidebar.jsx:53-61` skrývá položky podle `user.roles` z `/auth/me`
  (`adminOnly` pro Uživatele, `roles: ['ROLE_ADMIN','ROLE_MANAGER']` pro Zaměstnance).
  Ověřeno, že skrytí **není** jedinou ochranou: routy `/users` i `/employees` zůstávají dostupné
  a autoritativní je `@PreAuthorize` na backendu (`docs/konvence.md §19`). Zároveň se neskrývá nic,
  co mechanik legitimně potřebuje — seznam aktivních zaměstnanců si `OrderItemsWrapper.jsx:40-46`
  natáhne přímo pro select mechanika, i když mechanik stránku Zaměstnanci v menu nevidí.
- **Hesla** — FE minimum 8 znaků (`ChangePasswordModal.jsx:28`, `ResetPasswordModal.jsx:23`,
  `UserForm.jsx:83`) přesně odpovídá backendu (`ChangePasswordRequest.java:13`,
  `UserDto.java:31` a `:56` — `@Size(min = 8)`).
- **Validace, kde souhlasí** — `UserForm` (username 3–20, email, min. jedna role) sedí na
  `UserDto`; PSČ v `CustomerForm` sedí na `AddressDto`; VIN v `VehicleForm` sedí na `@Pattern`
  i DB CHECK; jednotky (`api/units.js`) mají v komentáři odkaz na serverovou konfiguraci.
- **`ReceiptReviewPage.confirmReceipt`** (`:207-228`) — správně zpracovaný „ulož a pak potvrď":
  uložení draftu je inline v `try`, takže jeho selhání potvrzení zruší. Přesně to, co chybí F-1.
- **Routing** — `/` → `/dashboard`, catch-all `*` → `NotFoundPage` uvnitř layoutu,
  `ErrorBoundary` s `key={location.pathname}` kolem `<Outlet/>`, `RequireAuth` kolem layout routy.
- **`npm run check`** prošel (`check-ui: OK — 106 souborů, 8 pravidel bez nálezu`) — nálezy
  F-11, F-12, F-16 a F-18 popisují, co kontrolor **nevidí**, ne že by lhal.

## Otevřené otázky pro uživatele

1. **Potvrzení u „Vystavit" faktury (F-19).** Má vystavení faktury vyžadovat potvrzovací dialog?
   Pro: přiděluje nevratně číslo daňového dokladu, omyl se řeší stornem, které zůstane v řadě.
   Proti: pokud se faktury vystavují dávkově po deseti, dialog zdržuje.
   *Rozhodnutí uživatele.* (Doporučení: dialog u `issue`, u `pay` ne.)

2. **Sazba DPH 0 % (F-5).** Používá servis nulovou sazbu (osvobozená plnění, reverse charge do EU,
   vývoz)? Pokud ano, je oprava nutná hned. Pokud ne, sníží se závažnost na „až se to bude hodit" —
   ale oprava je jednořádková, takže bych ji udělal tak jako tak.
   *Rozhodnutí uživatele.*

3. **`btn-outline-primary` (F-12).** Dokumentace si odporuje. Zrušit ho úplně ve prospěch
   `btn-outline-secondary` (přísnější §10.8), nebo ho legalizovat pro akce uvnitř karty
   (`DetailCard action`, `OrderItemsToolbar`) a upravit §10.8? Obojí je obhajitelné, jen to má být
   napsané jednou.
   *Rozhodnutí uživatele.*

4. **Rozsah přístupnosti (F-8, F-16, F-17).** TD-44 eviduje, že systematický audit chybí.
   Má se cílit na WCAG 2.1 AA (pak je potřeba projít i kontrasty, tab pořadí a `aria` u všech
   ručně psaných tabulek), nebo stačí „nezablokovat klávesnici a mít popisky"? Podle odpovědi jde
   F-8 opravit za pět minut, nebo je to samostatná etapa.
   *Rozhodnutí uživatele.*

5. **ESLint.** Projekt nemá žádnou ESLint konfiguraci; `check-ui.mjs` supluje tři runtime pravidla,
   která by `eslint` + `eslint-plugin-react-hooks` pokryl lépe (a přidal by kontrolu závislostí
   `useEffect`). Zavést, nebo zůstat u vlastního kontroloru a jen mu doplnit `.js` a chybějící
   pravidla (F-18)?
   *Rozhodnutí uživatele.*
