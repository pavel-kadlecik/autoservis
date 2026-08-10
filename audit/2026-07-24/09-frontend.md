# Audit 9/9 — Frontend (React 19 SPA)

> Součást hloubkového auditu 2026-07-24 (commit `409d3ad`, větev `audit-one`).
> Přehled celého auditu: [00-prehled.md](00-prehled.md).
>
> **Verifikace hlavního auditora:** F2 (`OrderItemsWrapper.jsx:205` volá `addAlert` bez importu
> `useAlert` v souboru → ReferenceError), F3 (`OrderItemsSummary.jsx:7` holý `return` → undefined)
> a F1 (`Modal.jsx:79` závislost `[show, closable, onClose]` + bezpodmínečný `cil?.focus()` na ř. 72)
> ověřeny přímo. Potvrzeno.

Auditováno čtením kódu (bez buildu/dev serveru). `npm run check` prochází (100 souborů, 8 pravidel bez nálezu). Známé TD-42/43/44/45/46 nereportovány. **Auth vrstva** (`api.js` single-flight refresh, `RequireAuth`, `auth.js`) je korektní — rekurze hlídaná `_retried`, `/auth/*` refresh nezkouší, souběh sdílí `refreshPromise`. **Bezpečnost FE je čistá** (žádné `dangerouslySetInnerHTML`, `rehype-raw` vypnuté → HTML v nápovědě escapované, žádné `console.log`, v `localStorage` jen UI preference).

---

## Nálezy

### VYSOKÝ

**F1 — Modaly přeskakují fokus při psaní do jiného než prvního pole**
- **Soubor:** `components/Modal.jsx:31-79` (kořen); projev v `ChangePasswordModal`, `StockMovementModal`, `OrderItemFormModal`, `InvoiceCreateFormModal`, `ManualReceiptModal`.
- **Popis:** efekt má závislosti `[show, closable, onClose]` a bezpodmínečně volá `cil?.focus()` (ř. 72) na první pole. Když je `onClose` nestabilní reference měnící se při každém renderu vyvolaném psaním, efekt se re-runuje po každém stisku klávesy a vrací fokus na první pole. Nastává, když modal drží vlastní stav polí a `handleClose` je uvnitř (ChangePasswordModal, StockMovementModal), nebo je stav v rodiči, který se při psaní překresluje a rekreuje handler (OrderItemFormModal, InvoiceCreateFormModal, ManualReceiptModal).
- **Důkaz asymetrie:** `MileageFormModal` a `ResetPasswordModal` zasažené NEjsou — první má stabilní `onClose={onCancel}` (rodič se při psaní nepřekresluje), druhý má jediné pole (re-fokus neviditelný).
- **Dopad:** klíčové formuláře (změna hesla, položka zakázky, ruční příjemka, vytvoření faktury, skladový pohyb) jsou při vyplňování druhého a dalších polí prakticky nepoužitelné.
- **Oprava:** oddělit autofocus (efekt jen `[show]`) od keydown listeneru, nebo `onClose`/`closable` číst přes `useRef` a z autofocus efektu vyjmout.
- **Confidence:** pravděpodobný (mechanismus jistý; doporučeno potvrdit spuštěním — psaní 8znakového hesla v „Nové heslo").

**F2 — `addAlert` není v `OrderItemsWrapper` importován → ReferenceError při selhání reorderu**
- **Soubor:** `components/OrderItemsWrapper.jsx:205` (`handleReorder`).
- **Popis:** catch blok volá `addAlert(...)`, ale komponenta neimportuje `useAlert` ani nemá `addAlert` v scope (ověřeno grepem — jediný výskyt je toto volání). Při selhání `PUT /orders/:id/items/reorder` catch sám vyhodí `ReferenceError`. Rollback (`setItems(items)`) proběhne, ale toast se nikdy nezobrazí a vznikne neošetřená rejection — přesně scénář, který §10.6 popisuje jako důvod, proč tam toast má být.
- **Oprava:** `import { useAlert } from "../context/AlertContext.jsx"` + `const { addAlert } = useAlert();`.
- **Confidence:** jistý.

### STŘEDNÍ

**F3 — `OrderItemsSummary` vrací `undefined` při selhání načtení souhrnu → pád do ErrorBoundary**
- **Soubor:** `components/OrderItemsSummary.jsx:6-7`; spouštěč `OrderItemsWrapper.jsx:30` (`.catch(() => setSummary(null))`).
- **Popis:** `if (!summary) return` (bez hodnoty) vrací `undefined`; React 19 to vyhodnotí jako chybu renderu → celý formulář zakázky spadne do ErrorBoundary. Zamýšleno bylo nevykreslit nic.
- **Oprava:** `return null;`. Confidence: jistý.

**F4 — Create/Edit stránky ignorují `err.problem.detail` (odchylka od §10.6 a §17)**
- **Soubory:** `CustomersPageCreate.jsx:46-47`, `CustomersPageEdit.jsx:57-58`, `OrdersPageCreate.jsx:30-31`, `OrdersPageEdit.jsx:65-66`, `VehiclesPageCreate.jsx:34-35`, `VehiclesPageEdit.jsx:52-53`, `WarehousePageCreate.jsx:29-30`, `WarehousePageEdit.jsx:48-49`, `VehiclesPageDetail.jsx:123-124,136-137`.
- **Popis:** vzor `catch (problemDetail) { addAlert("… se nepodařilo …", "danger"); }` — server detail se zahazuje. §10.6: „Hlášky ze serveru (`err.problem.detail`) se nepřepisují." Ostatní stránky to dodržují → nekonzistence, ne záměr. Parametr catch je navíc chybně pojmenován `problemDetail`, ač jde o `ApiError`.
- **Dopad:** konkrétní 4xx/409/422 hlášky (duplicitní VIN, duplicitní IČO, „zakázka má fakturu, nelze upravit") se uživateli neukážou.
- **Oprava:** `catch (err) { addAlert(err.problem?.detail ?? "…", "danger"); }`. Confidence: jistý.

**F5 — Chybí ochrana proti dvojímu odeslání ve všech CRUD formulářích**
- **Soubory:** `CustomerForm.jsx:330`, `VehicleForm.jsx:307`, `OrderForm.jsx:318` (+ `SupplierForm`, `UserForm`, `WarehouseForm`) — `<FormActions onSubmit={handleSave} />` bez `saving`; stránky nesledují stav ukládání.
- **Popis:** `FormActions` umí `saving` (blokuje tlačítka), ale žádný z hlavních formulářů ho nepředává. Tlačítko „Uložit"/„Vytvořit zakázku" zůstává během async POST/PUT aktivní.
- **Dopad:** dvojklik / pomalá síť → dvě volání POST → duplicitní zákazník/vozidlo/zakázka. Kontrast: modaly, `ReceiptReviewPage`, `StockTakePageDetail`, `LoginPage` ochranu mají.
- **Oprava:** stránka drží `saving` (`useState`), předá formuláři → `FormActions saving={saving}`. Confidence: jistý.

**F6 — Detailové a seznamové stránky bez ošetření chyby při načtení → nekonečný spinner / tichý prázdný seznam**
- **Soubory:** `InvoicesPageDetail.jsx:32-38`, `WarehousePageDetail.jsx:36-42`, `VehiclesPageDetail.jsx:47-56` (bez try/catch); seznamy `CustomersPage.jsx:39-44`, `OrdersPage.jsx:34-39`.
- **Popis:** selže-li úvodní `api.get`, u detailů zůstane objekt `null` → `LoadingState` navždy; u seznamů neošetřená rejection, tabulka prázdná bez hlášky. §10.6 vyžaduje cestu ven. `StockTakePageDetail`, `ReceiptReviewPage`, `ReceiptsPage` to řeší správně — nekonzistence.
- **Oprava:** try/catch → `ErrorState` (detail) / toast (seznam). Confidence: jistý.

### NÍZKÝ

- **F7 — `VehiclesPageDetail.handleToggleStatus` bez try/catch** (`:74-83`): selže-li DELETE, dialog zůstane otevřený bez důvodu. Obalit jako `WarehousePageDetail`. Jistý.
- **F8 — `StockTakePageDetail.closeStockTake` spolkne selhání průběžného uložení** (`:92-107`): `saveCounts` chytá vlastní chybu a nevyhazuje, `await` doběhne i při selhání → inventura se uzavře nad naposledy uloženým soupisem. `saveCounts` ať při chybě vyhodí. Pravděpodobný.
- **F9 — Seznamové fetche bez `AbortController` (race)** (`CustomersPage`, `OrdersPage`, `ReceiptsPage`…): debounce brání novým fetchům, ale rozběhnutý neruší → last-arrived-wins. `AutocompletePair` to řeší správně. Jistý.
- **F10 (a11y) — `LoginPage` inputy bez asociace label↔input** (`:43-59`): `<label>` bez `htmlFor`, inputy bez `id`. Doplnit. Jistý.
- **F11 (nit) — `getFuelLabel` bez fallbacku** (`format.js:270-272`): neznámý enum vrátí `undefined` (sesterské funkce mají `?? type`).

---

## Dopady kontextu z backend auditu (ověřeno na FE)

- **POST/PUT položky faktury vrací net/vat/gross=null:** FE **odolný** — `OrderItemTable` řádkové součty nezobrazuje, `OrderItemsSummary` se přepočítává ze serveru přes `useEffect([items])`. Bez viditelného rozbití.
- **Autocomplete nabízí deaktivované zákazníky:** `OrderForm`/`VehicleForm` nemají filtr na `active` — deaktivovaný zákazník/vozidlo lze vybrat. FE to opravit nemůže (backend je autoritativní).
- **Seznam příjemek posílá page-1 (first/last se rozjede):** FE **imunní** — `PaginatorRounded` řídí navigaci přes `totalPages`+`page`, nikdy nečte `first`/`last`. Totéž všechny seznamy.

## Konzistence se vzory §10 — souhrn

Velmi dobrá. `PageHeader`, `FormSection`/`FormActions`, `StatusBadge` (tóny z `format.js`), `Modal` přes `createPortal`, `.table-responsive`, `EmptyState`/`LoadingState`/`ErrorState`, barvy tlačítek dle důsledku jsou dodržené. `format.js` je jediné místo formátování; TD-47 vyřešeno — `toDatetimeLocal`/`fromDatetimeLocal` sjednoceny přes `getTimezoneOffset`, žádná lokální kopie nezůstala.

Drobné odchylky nezachycené checkem:
- `OrderItemTable.jsx:57` ruční `<h2>` + ručně stavěná tabulka místo `DataTable` — ospravedlnitelné (jediné dnd-kit použití).
- Chybové hlášky create/edit nedodržují §10.6 (F4) — jediná systematická odchylka.
- `InvoicesPageDetail.jsx:96` otevírá PDF přes `window.open('/api/v1/…/pdf')` místo `api.getBlob` (jinde použitý vzor s refresh-retry) — při vypršené session nový tab spadne na login.

## Pozitiva

- Auth klient (`api.js`): single-flight refresh, ochrana proti rekurzi (`_retried`), `/auth/*` vyňaty, tělo čteno jako text před `ok` (odolné vůči ne-JSON 502). `getBlob` sdílí logiku a uvolňuje object URL.
- `AutocompletePair`: `AbortController`, `onMouseDown`+`preventDefault` proti předčasnému blur, klávesová navigace, `useId`, `aria-*`.
- `ReceiptReviewPage` a `StockTakePageDetail`: vzorové — `cancelled` guardy, revokace PDF blobu, `saving` guardy, `err.problem.detail`.
- `Modal`: focus-trap, návrat fokusu, zámek scrollu, `createPortal` (TD-48). `AlertContainer` portálem `z-index:1080`. `AlertContext.removeAlert` funkční update (TD-38).
- `DataTable`: `clientSort` s NULLS-LAST a `localeCompare("cs")`, `aria-sort`.
- Nápověda: `remark-gfm` bez `rehype-raw` (HTML escapované), obal tabulek se scrollem.

**Souhrn:** 2× VYSOKÝ (F1 fokus modalů, F2 ReferenceError reorder), 3× STŘEDNÍ, 6× NÍZKÝ. Jediná systematická odchylka od konvencí je zahazování `err.problem.detail` na create/edit stránkách (F4). Auth a bezpečnost FE jsou v pořádku.
