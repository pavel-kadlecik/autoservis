# api.md — Katalog REST API

> Kompletní katalog endpointů, odvozený z kódu controllerů (22 tříd — 15 + 7 ve `warehouse/`, **110 endpointů**; stav 2026-07-31).
> Při přidání/změně endpointu aktualizuj tento dokument. Detailní konvence viz `konvence.md`.

Společné vlastnosti:

- **Prefix:** `/api/{version}/...` — `version` je path proměnná (prakticky vždy `v1`), v metodách se nečte, slouží jen k verzování URL.
- **Autentizace:** JWT v HTTP-only cookie `jwt`; veřejné jsou jen `login`, `refresh` (viz sekce Auth). Vše ostatní pod `/api/**` vyžaduje přihlášení. Účty zakládá výhradně admin přes `UserController` — veřejná registrace neexistuje.
- **Autorizace rolí (E7, audit R-9):** baseline `/api/**` smí všechny pracovní role (ADMIN/MANAGER/MECHANIC — `SecurityConfig`). Nad rámec toho jsou účetní a správní úkony vyhrazeny **vedení (`@PreAuthorize hasAnyRole('ADMIN','MANAGER')`)**: faktura `issue`/`pay`/`cancel`, celý `credit-notes`, celý `cash-receipts`, (de)aktivace zákazníka, vozidla i **zaměstnance** (`DELETE`/`activate`), `create`/`update` zaměstnance, `PUT company-profile`, `POST stock-takes/{id}/close` — **šestnáct míst** celkem. Správa uživatelů (`/users`) je **ADMIN-only**. Mechanik dostane na vyhrazené operaci **403**. U jednotlivých endpointů je omezení uvedeno ve sloupci „Autorizace" jen tam, kde je nad baseline.
- **Stránkované seznamy:** `*SearchParams` (query: `page` 1-based, `pageSize`, `sortBy`, `sortDesc`, `search`, modulové filtry) → `PagedResponse<T>` (`content`, `page`, `pageSize`, `totalElements`, `totalPages`, `first`, `last`).
- **Řazení:** `sortBy` je **klíč z whitelistu** v XML mapperu, ne název sloupce v DB (identifikátor
  nejde předat přes `#{}`, takže whitelist je zároveň ochrana proti SQL injection). Neznámá hodnota
  spadne na výchozí řazení endpointu. `sortDesc` (default `false` = vzestupně) platí jen pro sloupce
  z whitelistu; výchozí větev si nese vlastní pevný směr. Podporované klíče:
  - `/customers` — `lastName`, `companyName`, `customerNumber`, `primaryEmail`
  - `/users` — `username`, `email`, `lastLoginAt`
  - `/vehicles` — `vin`, `licensePlate`, `brand`, `yearOfManufacture`, `stkValidUntil`
  - `/invoices` — `invoiceNumber`, `customerName`, `issueDate`, `dueDate`, `totalGross` (klíč `totalGross` řadí podle `totalToPay`, tj. podle zobrazené částky k úhradě — V67)
  - `/warehouse/products` — `sku`, `quantityOnHand`, `salePrice` (výchozí `name`)
  - `/warehouse/suppliers` — `registrationNumber`, `city` (výchozí `name`)
- **Autocomplete:** `GET .../autocomplete?q&limit` → `{ data: [{id, value, description}], hasMore }` (limit max 100, default 10).
- **Soft delete:** `DELETE /{id}` = deaktivace, vrací **200 s objektem** (ne 204); reaktivace `POST /{id}/activate`.
- **201 Created:** POST create endpointy vrací hlavičku `Location` s URL nově vytvořeného zdroje (`ResponseEntity.created(...)`, sestaveno z aktuálního requestu — nezávislé na `{version}`). Výjimka: `POST /orders/{orderId}/items/import-from-receipt` vrací pole položek (žádné jednotné id), Location nemá.
- **Audit:** write operace berou `@AuthenticationPrincipal AppUserDetails` → `createdBy` doplňuje server, nikdy klient.
- **Chyby:** RFC 9457 `ProblemDetail` (`application/problem+json`) rozšířený o pole `errors[]` — viz sekce Chyby.

---

## Auth — `/api/{version}/auth` (AuthController)

| HTTP | Cesta | Request | Response | Status | Přístup |
|---|---|---|---|---|---|
| POST | `/auth/login` | `LoginRequest` {username, password} | prázdné tělo; tokeny do cookies | 200; 401 `BAD_CREDENTIALS`; 401 `ACCOUNT_LOCKED` po 10 neúspěšných pokusech (V3b) — zámek vyprší po `lockout.duration` (15 min, V64) | veřejné |
| POST | `/auth/refresh` | — (čte cookie `jwt_refresh`) | prázdné tělo; nové cookies (rotace) | 200; 400 bez cookie | veřejné |
| POST | `/auth/logout` | — (čte cookies) | prázdné tělo | 204; 400 bez cookie `jwt` | přihlášený |
| GET | `/auth/me` | — | `MeResponse` {id, username, email, roles[]} | 200; 401 | přihlášený |
| POST | `/auth/change-password` | `ChangePasswordRequest` {currentPassword, newPassword ≥ 8} | prázdné tělo | 204; 422 `INVALID_CURRENT_PASSWORD` | přihlášený (self-service, mění vlastní heslo) |

**Cookies** (všechny `HttpOnly`, `SameSite=Strict`; `secure` = `jwt.cookie-secure` → `false` dev / `true` prod). Hodnoty jsou odvozené z konfigurace, ne natvrdo (`AuthController.setTokenCookies`):

| Cookie | Obsah | Path | maxAge |
|---|---|---|---|
| `jwt` | access token (JWT HS256; `sub`=username) | `/api` | `jwt.expiration` → 8 h dev / 15 min prod |
| `jwt_refresh` | refresh token (opaque UUID; v DB uložen jako SHA-256 hash — V45/K-7) | `/api/{version}/auth/refresh` (dynamicky dle verze) | `jwt.refresh-expiration` → 7 dní |

Refresh s **rotací**: starý token se odvolá; předložení již odvolaného tokenu (reuse detection) odvolá všechny session uživatele. Změna/reset hesla rovněž odvolá všechny refresh tokeny uživatele (K-6). Logout: access token → blacklist (`security.token_blacklist`, SHA-256 hash), refresh → revoke.

---

## Zákazníci — `/api/{version}/customers` (CustomerController)

| HTTP | Cesta | Request | Response | Status |
|---|---|---|---|---|
| GET | `/customers` | `CustomerSearchParams` (search, activeOnly, sortBy) | `PagedResponse<CustomerDto.ListResponse>` | 200 |
| GET | `/customers/{id}` | — | `CustomerDto.DetailResponse` (vč. addresses, contactPersons, vehicles) | 200 |
| GET | `/customers/{id}/vehicles` | — | `List<VehicleDto.SummaryResponse>` | 200 |
| GET | `/customers/autocomplete` | `q`, `limit` | `AutocompleteResponse` | 200 |
| POST | `/customers` | `CustomerDto.CreateRequest` (customerType, jméno/firma, ico/dic, kontakty, gdprConsent, `addresses[]` 1–2, …) | `DetailResponse` | 201 |
| PUT | `/customers/{id}` | `CustomerDto.UpdateRequest` (bez customerType a addresses) | `DetailResponse` | 200 |
| DELETE | `/customers/{id}` | — | `DetailResponse` (deaktivovaný) | 200 |
| POST | `/customers/{id}/activate` | — | `DetailResponse` | 200 |

Validace: `ico` pattern `^$|^\d{8}$`, `dic` `^$|^CZ\d{8,10}$`, PSČ v adresách `^\d{3}\s?\d{2}$`. Podmíněná povinnost jména/firmy podle `customerType` (TD-10): `CreateRequest` má class-level `@ValidCustomerRequest` → 400 `CUSTOMER_NAME_REQUIRED` (pole `firstName`/`lastName`, INDIVIDUAL) / `CUSTOMER_COMPANY_REQUIRED` (pole `companyName`, COMPANY); `UpdateRequest` nemá `customerType` (immutable) — stejné kódy jako `BusinessRuleException` → 422, kontrola podle typu už uloženého v DB.

`UpdateRequest.gdprConsent`/`marketingConsent` jsou typu `Boolean` (ne `boolean`, TD-23) — chybí-li pole v JSON těle (`null`), uložená hodnota v DB se nezmění; ostatní pole `UpdateRequest` mají full-replace sémantiku (chybějící/`null` hodnota přepíše na `null`/prázdné).

## ARES — `/api/{version}/customers` (CustomerAresController)

| HTTP | Cesta | Request | Response | Status |
|---|---|---|---|---|
| GET | `/customers/ares-lookup` | query `ico` (8 číslic) | `AresDto.LookupResponse` (ico, companyName, dic — jen ve formátu `CZ\d{8,10}`, jinak null; adresa sídla: street, streetNumber, city, postalCode, countryCode) — pro prefill formuláře zákazníka, nic nezapisuje | 200; 422 `INVALID_ICO` (špatná délka nebo kontrolní číslice mod 11 — ARES se ani nevolá) / `SUBJECT_NOT_IN_ARES`; 503 `ARES_*` |

Veřejné API MF ČR (ares.gov.cz) — bez API klíče. Literál `/ares-lookup` má přednost před šablonou `/{id}` v `CustomerController` (stejný vzor jako `/vehicles/registry-lookup`).

## Vozidla — `/api/{version}/vehicles` (VehicleController)

| HTTP | Cesta | Request | Response | Status |
|---|---|---|---|---|
| GET | `/vehicles` | `VehicleSearchParams` (search, activeOnly, orderBy, **stkExpiring** — STK do 30 dnů/propadlá) | `PagedResponse<VehicleDto.ListResponse>` (vč. `stkValidUntil`) | 200 |
| GET | `/vehicles/{id}` | — | `VehicleDto.DetailResponse` (vč. `customer` summary, `stkValidUntil` a `wheels` — read-only z registru) | 200 |
| GET | `/vehicles/autocomplete` | `q`, `limit`, `customerId` | `AutocompleteResponse` | 200 |
| POST | `/vehicles` | `VehicleDto.CreateRequest` (customerId, brand, model; volitelně VIN `^$|^[A-HJ-NPR-Z0-9]{17}$` — nepovinný od V90, stroje bez VIN, `machineSerialNumber` ≤ 50 (V90), `fuelType` (V86), `transmission`, `initialMileageKm`, …) | `DetailResponse` — pozn.: po create proběhne best-effort načtení STK z registru **po** odeslání odpovědi transakce; `stkValidUntil` v odpovědi create ještě není, detail si ho načte čerstvý | 201 |
| PUT | `/vehicles/{id}` | `VehicleDto.UpdateRequest` (bez initialMileageKm) | `DetailResponse` | 200 |
| DELETE | `/vehicles/{id}` | — | `DetailResponse` (deaktivované) | 200 |
| POST | `/vehicles/{id}/activate` | — | `DetailResponse` | 200 |

**Našeptávač vozidel hledá i podle SPZ** a vrací **tři řádky**: `value` = „Značka Model - SPZ",
`description` = majitel, `detail` = VIN (u stroje bez VIN výrobní číslo — V90; hledat jde i podle
něj). Do V85 nesl jen značku s modelem a VIN a SPZ neuměl ani
najít — nevadilo to, dokud `customerId` seznam zúžil na auta jednoho zákazníka. Objednávka bez
zákazníka (V85) ale hledá napříč **všemi** vozidly, kde jsou tři „BMW 3 Series" k nerozeznání a SPZ
je pro dílnu hlavní identifikátor. `detail` je volitelné pole `AutocompleteItem` — ostatní
našeptávače ho nechávají prázdné a třetí řádek se pak nevykreslí.

**Prázdný řetězec u ENUMu = `null`** (platí pro celé API, ne jen pro vozidla). Formuláře posílají
nevybraný `<select>` jako `""`; `JacksonConfig` to globálním pravidlem `EmptyString → AsNull` čte jako
nevyplněno. U volitelných polí (`fuelType` od V86, `transmission`) tak vznikne NULL, u povinných to
odmítne `@NotNull` řádným 400 `REQUIRED`. Bez toho request padal na 400
`HttpMessageNotReadableException` už při deserializaci — viz [funkce/palivo-nepovinne.md](funkce/palivo-nepovinne.md).

## Tachometr — `/api/{version}/vehicles/{vehicleId}/mileage` (MileageController)

| HTTP | Cesta | Request | Response | Status |
|---|---|---|---|---|
| GET | `/mileage` | — | `List<MileageDto.Response>` | 200 |
| POST | `/mileage` | `MileageDto.CreateRequest` (mileageKm 0–9 999 999, recordedDate past-or-present, source) | `Response` | 201 |
| PUT | `/mileage/{readingId}` | `MileageDto.UpdateRequest` | `Response` | 200 |
| DELETE | `/mileage/{readingId}` | — | — | 204 |

`vehicles.current_mileage_km` přepočítává DB trigger (viz `databaze.md` §3).

## Registr vozidel (STK) — `/api/{version}/vehicles` (VehicleRegistryController)

Integrace dataovozidlech.cz (Datová kostka RSV, limit 27 dotazů/min). Detail architektury: `backend.md` §4b.

| HTTP | Cesta | Request | Response | Status |
|---|---|---|---|---|
| GET | `/vehicles/registry-lookup` | query `vin` / `tp` / `orv` (alespoň jeden; kombinují se jako AND) | `RegistryDto.LookupResponse` (vin, brand, model, color, objem, výkon, datum 1. registrace, fuelType — nerozpoznané palivo = null, stkValidUntil, lastInspectionDate, registryStatus) — pro prefill formuláře, nic nezapisuje | 200; 422 `MISSING_LOOKUP_PARAM` / `INVALID_VIN` / `INVALID_LOOKUP_PARAM` / `VEHICLE_NOT_IN_REGISTRY`; 503 `REGISTRY_*` |
| POST | `/vehicles/{vehicleId}/registry-refresh` | — (VIN se bere z vozidla) | `RegistryDto.SnapshotResponse` (id, stkValidUntil, lastInspectionDate, registryStatus, fetchedAt); trigger propíše `vehicles.stk_valid_until` | 200; 404; 422 `VEHICLE_NOT_IN_REGISTRY` / `VEHICLE_HAS_NO_VIN` (V90 — vozidlo bez VIN v registru není, FE tlačítko zakazuje); 503 `REGISTRY_*` |
| GET | `/vehicles/{vehicleId}/registry-snapshots` | — | `List<RegistryDto.SnapshotResponse>` (nejnovější první) | 200 |

## Zakázky — `/api/{version}/orders` (OrderController)

| HTTP | Cesta | Request | Response | Status |
|---|---|---|---|---|
| GET | `/orders` | `OrderSearchParams` (fulltext přes číslo/zákazníka/vozidlo/popis; volitelně `statuses` = filtr podle stavů, opakovaný parametr, `overdue=true` = jen po termínu, `vehicleId` / `customerId` = servisní historie vozu/zákazníka) | `PagedResponse<OrderDto.ListResponse>` | 200 |
| GET | `/orders/{id}` | — | `OrderDto.DetailResponse` (vč. `invoiceStatus` a `invoiceId` aktivní faktury) | 200 |
| POST | `/orders` | `OrderDto.CreateRequest` (customerId, vehicleId, description, `receivedAt`, estimated*, volitelně `mileageKmAtIntake`) | `DetailResponse` | 201 |
| PUT | `/orders/{id}` | `OrderDto.UpdateRequest` (status, description, ceny, `receivedAt`, `mileageKmAtIntake`; customerId/vehicleId neměnné) | `DetailResponse` | 200; 422 `INVALID_STATUS_TRANSITION` / `ORDER_HAS_ACTIVE_INVOICE` / `ORDER_HAS_ISSUED_MATERIAL` / `STOCK_MISSING_FOR_ISSUE` (přechod do `COMPLETED` vydá rezervovaný materiál) |
| DELETE | `/orders/{id}` | — | — **tvrdě smaže zakázku** i s položkami a odečtem tachometru; **vrátí vydaný materiál** | 204; 404; 422 `ORDER_HAS_INVOICE_CANNOT_DELETE` |
| POST | `/orders/{id}/status` | `OrderDto.StatusRequest` {status} | `DetailResponse` | 200; 422 `INVALID_STATUS_TRANSITION` / `ORDER_HAS_ACTIVE_INVOICE` / `ORDER_HAS_ISSUED_MATERIAL` / `ORDER_REOPEN_BLOCKED_BY_INVOICE` / `STOCK_MISSING_FOR_ISSUE` |
| POST | `/orders/{id}/cancel` | — | `DetailResponse` — **vrátí veškerý vydaný materiál** a zruší zakázku v jedné transakci | 200; 404; 422 `ORDER_HAS_ACTIVE_INVOICE` |
| GET | `/orders/{id}/protocol` | — | **PDF zakázkového listu** (A4, `inline`, `zakazkovy-list-{orderNumber}.pdf`) | 200; 404 |

`ListResponse` nese pole `invoiceStatus` — stav **aktivní** faktury zakázky, nebo `null` (nefakturováno / jen stornované či dobropisované faktury). Odvozeno v `OrderMapper.search` LEFT JOINem na `billing.invoices` (`status <> 'CANCELLED' AND credited_at IS NULL`); partial unique index `uq_invoices_order_active` (V48 + V69) zaručuje nejvýš jednu aktivní fakturu, takže join nenásobí řádky. Filtr `statuses` (opakovaný parametr, `statuses=RECEIVED&statuses=DIAGNOSIS`) je ve sdíleném `WhereClause`, takže se promítá i do `countSearch` (stránkování počítá jen filtrované). Řadit podle `invoiceStatus` nelze (není v whitelistu `orderSortOrder`). Filtr `overdue=true` vrací jen zakázky „po termínu" — `estimated_completion_at < now()` a `status NOT IN ('COMPLETED','CANCELLED')` (analogie faktur `overdue`; zakázky bez termínu vypadnou samy).

**Smazání zakázky (V84) — a proč to není totéž co zrušení.** Mazání je vyhrazené pro záznam, který **nikdy neměl vzniknout**: překlep, špatné auto. Zakázka, u které k práci nedošlo, se **ruší** stavem `CANCELLED` a v evidenci zůstává jako obchodní fakt (kolik rozpočtů zákazníci odmítli). Je to vědomá výjimka z R-06 (soft-delete).

Blokuje **jen účetní stopa**: projde zakázka, po které nezůstala žádná faktura — ani stornovaná či dobropisovaná (422 `ORDER_HAS_INVOICE_CANNOT_DELETE`). Rozhoduje, jestli po zakázce *kdy* zůstal doklad, ne jestli je zrovna platný; vystavená faktura tedy mazání znemožní **natrvalo**, i po dobropisu. FK na `billing.invoices` to blokuje i na úrovni databáze, service odmítá dřív, aby obsluha dostala hlášku místo chyby integrity.

**Skladový pohyb mazání nebrání** (2026-08-07, V87). Do té doby blokoval jakýkoli — i vratku — takže omylem založená zakázka, na kterou stihl někdo vydat díl, zůstala v evidenci navždy, i když se materiál dávno vrátil. Mazání teď **vrátí veškerý vydaný materiál** stejně jako zrušení; pohyby v append-only ledgeru zůstávají a nesou ID zakázky, které už na nic neukazuje (proto V87 zahodila `fk_mov_order`). Po smazané zakázce tak zbývá vyrovnaný pár pohybů s nulovým dopadem na zásobu a historie skladu zůstává pravdivá: materiál opravdu odešel a vrátil se. **Pouhá rezervace** mazání nebránila nikdy — díl regál neopustil a smazáním položek se slib jen uvolní (V83).

Kaskádou odejdou **položky zakázky** a **odečet tachometru z příjmu** (`vehicle.mileage_history`, V84) — u zakázky založené na špatném voze by zůstal v historii cizího auta. Objednávka v kalendáři, ze které zakázka vznikla, se vrátí na `PLANNED` a jde ji převést znovu: smazaná zakázka byla omyl, ale domluvený termín ne. **Číslo zakázky se po smazání recykluje** — od V56 ho skládá trigger jako `MAX + 1` za daný rok, takže smazáním nejvyšší zakázky se číslo uvolní a přidělí další nové. Ověřeno naživo 2026-08-07. Je to přijaté (rozhodnutí uživatele): `ZAK-` není daňový doklad a **zakázkový list smazané zakázky přestává platit** — zakázka se nekonala. Potřebuje-li ho zákazník, vytiskne se nový z té zakázky, která opravdu existuje. Pozor při čtení skladového ledgeru: pohyby smazané zakázky si nesou její `order_id` (jednoznačné, `id` se nerecykluje), ale číslo v textu poznámky už může patřit jiné zakázce. Podrobně `docs/funkce/rezervace-skladu.md`.

**Zakázkový list (KN-28).** `GET /orders/{id}/protocol` vrací PDF k podpisu při převzetí vozu — servis, zákazník, vozidlo, **stav tachometru při příjmu**, požadovaná práce, odhad ceny a dva podpisové řádky. Cesta je `/protocol`, ne `/pdf` jako u faktury, dobropisu a PPD: zakázka sama doklad není, takže „PDF zakázky" by nepojmenovalo, o který dokument jde. Není to daňový doklad — nemá číselnou řadu ani snapshoty stran a tiskne se ze živých dat; jediná zmrazená hodnota je `mileageKmAtIntake` na zakázce (V70). `mileageKmAtIntake` je nepovinné (0–9 999 999) a při **zakládání** zakázky z něj vznikne i odečet v `vehicle.mileage_history` (zdroj `SERVICE`, poznámka s číslem zakázky); dodatečné dopsání přes `PUT` už odečet nezakládá. Hlavička listu tiskne `receivedAt` (V94) — **povinné datum přijetí vozidla**, které zadává uživatel (FE předvyplní dneškem; hodnota bez omezení, i budoucí — rozhodnutí uživatele 2026-08-09). Do V94 se tisklo auditní `createdAt`, jenže vůz mohl přijet jindy, než se zakázka zapisovala. Podrobně `docs/funkce/zakazkovy-list.md`.

**Servisní historie (KN-27).** Filtry `vehicleId` a `customerId` zúží seznam na zakázky jednoho vozu, resp. jednoho zákazníka napříč jeho vozidly. Obojí je ve **sdíleném** `WhereClause`, takže se promítá i do `countSearch`; kombinují se s ostatními filtry i mezi sebou (`AND`). Filtrují přes `o.vehicle_id` / `o.customer_id` (indexy z V53). Karty servisní historie na detailu vozidla a zákazníka je volají s `pageSize=10&sortBy=createdAt&sortDesc=true`; faktury zákazníka berou z existujícího `GET /invoices/customer/{customerId}`.

**Stavový automat zakázky (KN-11).** `PUT` prochází brankou `OrderServiceImpl.requireAllowedStatusChange`: mezi provozními stavy (`RECEIVED`, `DIAGNOSIS`, `WAITING_FOR_PARTS`, `IN_PROGRESS`, `READY_FOR_PICKUP`) je pohyb volný oběma směry, z každého z nich vede přechod na `COMPLETED` nebo `CANCELLED`. **Terminální je nově jen `CANCELLED`** (2026-08-06) — jiný cíl vrací 422 `INVALID_STATUS_TRANSITION` s hláškou „Zrušenou zakázku už nelze přepnout do jiného stavu. Má-li se na voze znovu pracovat, založte novou zakázku." Nezměněný stav není přechod a projde vždy (požadavek nese celý záznam, takže popis a ceny uzavřené zakázky zůstávají editovatelné; položky zamyká faktura, ne stav zakázky). Do `CANCELLED` navíc nesmí zakázka s **aktivní fakturou** (422 `ORDER_HAS_ACTIVE_INVOICE`; hláška radí storno konceptu, resp. dobropis u vystaveného dokladu) ani s materiálem držícím skladovou šarži (422 `ORDER_HAS_ISSUED_MATERIAL`; `params.orderItemIds` + výčet položek v hlášce — vrací se smazáním položky, které vytvoří pohyb `ISSUE_RETURN`). Podrobně `docs/funkce/zakazky-stavy.md`.

**Fakturovat lze až dokončenou zakázku** (2026-08-05). „Dokončena" znamená „práce hotová a vyúčtovatelná" — je to okamžik, kdy se vydá materiál a od kterého má doklad co vyúčtovat. Do téhle změny šlo vystavit fakturu i na zakázku ve stavu „Přijata", na které se ještě nesáhlo na auto. Řetěz je: práce hotová → `Dokončena` (výdej ze skladu) → faktura → předání. Jiný stav vrací 422 `ORDER_NOT_INVOICEABLE`; u zrušené zakázky zní hláška jinak než u rozpracované. **Vědomý dopad:** zálohová faktura předem tím není možná.

**Zakázku bez položek nelze dokončit** → 422 `ORDER_HAS_NO_ITEMS`. Prázdná zakázka nic neunese: fakturu z ní vystavit nejde a jako hotová práce by v přehledech lhala. Kontrola je předsazená před fakturaci, aby se obsluha dozvěděla dřív a na místě, kde se to dá spravit. Okrajové případy drží — diagnostika bez opravy má položku typu práce, záruční oprava má položky s nulovou cenou; zakázka, na které se nakonec nic nedělalo, se **ruší**, ne dokončuje.

**Znovuotevření dokončené zakázky.** `COMPLETED` byl do 2026-08-06 slepá ulička: omylem kliknuté „Dokončena" nešlo vzít zpět ani zrušit, a protože zakázka tehdy neměla mazání, zůstalo to v evidenci navždy. Odůvodnění „návrat by odemkl editaci položek" přitom neplatilo — položky zamyká faktura, ne stav. Nově vede z `COMPLETED` přechod jak zpět do provozu, tak na `CANCELLED`.

Podmínka: zakázka **nesmí mít aktivní fakturu** → 422 `ORDER_REOPEN_BLOCKED_BY_INVOICE` (hláška radí storno konceptu, resp. dobropis). Jinak by vedle sebe stála rozpracovaná práce a doklad, který ji vyúčtoval jako hotovou. Znovuotevření zároveň **vrátí vydaný materiál do rezervace**: díl fyzicky zůstává na autě, ruší se jen výdej, aby se při dalším dokončení neodepsal podruhé (netto dopad na sklad nula). `completedAt` se vynuluje.

**Datum dokončení se doplňuje samo.** Přechod do `COMPLETED` nastaví `completed_at` na dnešek, pokud ho volající neposlal; znovuotevření ho vynuluje. Váže se na **přechod**, ne na výsledný stav — `PUT` je full-replace a obsluha smí datum u dokončené zakázky legitimně vymazat.

**`POST /orders/{id}/status`** je vyhrazená cesta pro samotnou změnu stavu. Prochází **toutéž brankou** jako `PUT`, takže automat ani podmínky neobchází, ale zapisuje jen `status` a `completed_at` — full-replace by při rychlé změně ze seznamu přepsal popis a ceny hodnotami, které si klient nenačetl (TD-47).

**`POST /orders/{id}/cancel`** zruší zakázku a **vrátí veškerý vydaný materiál** na sklad, obojí v jedné transakci (2026-08-06). Bez ní šlo zrušení jen ručně: smazat vydané položky po jedné (tím se vracely) a teprve pak přepnout stav — u osmi dílů devět potvrzení. Frontend na zrušení volá výhradně tuhle cestu; `ORDER_HAS_ISSUED_MATERIAL` na `PUT` / `POST /status` tak zůstává záchytnou sítí pro přímé volání API.

**Detail zakázky nese aktivní fakturu** (`invoiceStatus`, `invoiceId`) — týž LEFT JOIN a týž predikát jako seznam, tedy shodný s partial unique indexem `uq_invoices_order_active`, takže nenásobí řádky. Dialog zrušení podle toho volí, co nabídne: bez faktury prosté potvrzení, u **konceptu** rovnou jeho storno (koncept číslo nemá, v řadě nevznikne mezera), u **vystavené** proklik na fakturu, odkud se vystavuje dobropis.

Vrací se **všechno, bez odškrtávání**: ze zrušené zakázky nemá co zbýt. Díly, které zůstaly namontované na voze, zákazník zaplatí — patří na **novou** zakázku, kterou obsluha založí. Aktivní faktura zrušení dál blokuje (`ORDER_HAS_ACTIVE_INVOICE`) a kontroluje se **první**, aby se materiál nevracel kvůli akci, která stejně neprojde. Podrobně `docs/funkce/zakazky-stavy.md`.

**Přechod do `COMPLETED` vydá rezervovaný materiál** (V83). Přidání dílu na zakázku je jen rezervace — díl leží dál v regálu. Dokončení je okamžik, kdy je jisté, že se oprava stala, takže tady vzniknou skladové pohyby `ISSUE`. Materiál vydaný dřív ručně (`POST /orders/{id}/issue-stock`) se nevydá podruhé a identita (uložení už dokončené zakázky) výdej nespouští vůbec. Není-li rezervovaný díl na skladě (mezitím ho snědla inventura, odpis nebo vratka), vrací se 422 `STOCK_MISSING_FOR_ISSUE` s výčtem chybějícího a **celá změna se vrací zpět** — zakázka zůstane nedokončená.

## Plánovací kalendář — `/api/{version}/appointments` (AppointmentController)

Objednávky termínů (`BOOKING`), blokace dílny (`CLOSURE`) a obecné události (`EVENT`, V82)
v jedné tabulce. Objednávka vzniká **dřív než zakázka** a je na ní nezávislá — převodem se naváže,
ale přežije i její smazání. Událost (školení, dovolená zaměstnance) nemá zákazníka ani vozidlo,
volitelně nese `employeeId` a na rozdíl od blokace **neblokuje** plánování objednávek.

| HTTP | Cesta | Request | Response | Status |
|---|---|---|---|---|
| GET | `/appointments` | `from`, `to` (ISO okamžiky, povinné); volitelně `entryType`, `status` | `List<AppointmentDto.ListResponse>` | 200; 422 `INVALID_RANGE` |
| GET | `/appointments/overlaps` | `startsAt`, `endsAt`; volitelně `excludeId` | `OverlapResponse` {overlappingCount, overlapping[], blockedByClosure} | 200 |
| GET | `/appointments/{id}` | — | `DetailResponse` | 200; 404 |
| GET | `/appointments/by-order/{orderId}` | — | `DetailResponse` | 200; **404 = zakázka vznikla přímo** (běžný stav) |
| POST | `/appointments` | `CreateRequest` (entryType, title, startsAt; **`endsAt` volitelný**, dále note/customerId/vehicleId/employeeId) | `DetailResponse` | 201; 400; 403 (CLOSURE bez role vedení); 404 (neexistující zaměstnanec); 422 `CLOSURE_END_REQUIRED` / `EVENT_END_REQUIRED` / `CUSTOMER_REQUIRED` / `VEHICLE_REQUIRED` / `EVENT_MUST_BE_EMPTY` / `EMPLOYEE_ONLY_FOR_EVENT` |
| PUT | `/appointments/{id}` | `UpdateRequest` (title, note, časy, customerId, vehicleId, employeeId — **bez** entryType a status) | `DetailResponse` | 200; 404; 422 `APPOINTMENT_TERMINAL_READONLY` / `CUSTOMER_REQUIRED` / `VEHICLE_REQUIRED` / `EVENT_MUST_BE_EMPTY` / `EMPLOYEE_ONLY_FOR_EVENT` |
| POST | `/appointments/{id}/time` | `TimeRequest` {startsAt, **endsAt volitelný**} | `DetailResponse` | 200; 404; 422 `APPOINTMENT_IN_CLOSURE` / `CLOSURE_OVERLAPS_ENTRIES` / `APPOINTMENT_TERMINAL_READONLY` |
| POST | `/appointments/{id}/status` | `StatusRequest` {status} | `DetailResponse` | 200; 422 `INVALID_STATUS_TRANSITION` / `STATUS_NOT_SETTABLE` / `STATUS_NOT_ALLOWED_FOR_CLOSURE` / `STATUS_NOT_ALLOWED_FOR_EVENT` |
| POST | `/appointments/{id}/convert` | `OrderDto.CreateRequest` | **`OrderDto.DetailResponse`** (vzniklá zakázka) | 201; 422 `ALREADY_CONVERTED` / `NOT_CONVERTIBLE` / `INVALID_STATUS_TRANSITION` |
| DELETE | `/appointments/{id}` | — | — | **204**; 403 (CLOSURE bez role vedení); 404; 422 `APPOINTMENT_CONVERTED_CANNOT_DELETE` |

**Bez stránkování.** `GET /appointments` vrací prostý seznam — časové okno je přirozený limit a kalendář
stejně potřebuje celý týden naráz. Překryv okna a události se počítá jako `starts_at < to AND from < ends_at`,
takže vícedenní blokace zasahující do okna se vrátí i tehdy, když začala před ním.

**Objednávka nemusí mít konec (V74).** `endsAt = null` znamená „zákazník nechá auto, konec neznámý“ —
délku opravy nelze před diagnostikou odhadnout. Časové kontroly takovou objednávku posuzují
**jen podle příjezdu** (interně `starts_at + 1 s`), takže se nevymýšlí žádná délka. Přetažení
(`POST /{id}/time` s `endsAt: null`) posune jen příjezd; **protažení za spodní okraj konec doplní** —
to je jediný způsob, jak délku určit. Blokace dílny konec mít musí (422 `CLOSURE_END_REQUIRED`),
jinak by zavřela dílnu natrvalo — a událost taky (422 `EVENT_END_REQUIRED`, V82): „dovolená navždy"
není termín.

### Otevírací doba

| Metoda | Cesta | Tělo | Odpověď | Statusy |
|---|---|---|---|---|
| GET | `/opening-hours` | — | `{openingHoursEnabled, days[7]}` — `days[i]` = {dayOfWeek 1–7, opensAt, closesAt} | 200 |
| PUT | `/opening-hours` | `UpdateRequest` (týž tvar) | tentýž objekt | 200; **403 (jen ADMIN/MANAGER)**; 400 (prázdný rozvrh); 422 `INCOMPLETE_WEEK` / `INCOMPLETE_OPENING_HOURS` / `INVALID_OPENING_HOURS` |

**Ukládá se celý týden naráz.** Kdyby šly dny ukládat po jednom, dala by se evidence nechat
v půlce — pondělí podle nového rozvrhu, úterý podle starého — a nikdo by nepoznal, co platí.
`dayOfWeek` je 1 = pondělí … 7 = neděle (ISO-8601, shodné s `EXTRACT(ISODOW)` i `DayOfWeek`).
**Oba časy `null` = zavřeno celý den**; jeden vyplněný a druhý ne je 422 `INCOMPLETE_OPENING_HOURS`.

**Otevírací doba jen varuje, nezakazuje (rozhodnutí uživatele 2026-08-04).** `GET /appointments/overlaps`
vrací `startOutsideOpeningHours` a `endOutsideOpeningHours`; uložení projde i tak. Servis občas auto
přijme mimo dobu — týž princip jako u překryvu objednávek. Při `openingHoursEnabled = false` jsou obě
pole vždy `false`.

**Hlídá se příjezd a vyzvednutí, ne doba mezi nimi.** Auto přes noc v zavřené dílně stojí běžně
a vícedenní opravy (V74) na tom stojí; otevírací doba se týká chvil, kdy u toho musí někdo být.

**Objednávka nemusí mít zákazníka ani vozidlo (V85).** Obojí je volitelné — termín se domlouvá po
telefonu dřív, než servis zákazníka i auto zná, a vynucená vazba nutila zapsat údaj z odhadu.
Čitelnost drží `title`, který je povinný vždy. Kontakt na volajícího mimo evidenci nese `contactNote`
(volný text, max 200). Ruší se tím kódy `CUSTOMER_REQUIRED` a `VEHICLE_REQUIRED` — objednávka bez
obou vrací **201**.

**Jen `vehicleId` bez `customerId` → server zákazníka dopočítá** z majitele vozidla
(`vehicle.vehicles.customer_id` je NOT NULL). Jsou-li vyplněné oba a neodpovídají si, platí dál
422 `VEHICLE_NOT_OWNED_BY_CUSTOMER` — jinak by šlo objednat cizí auto a převod na zakázku by selhal
až na konci.

Blokace dílny ani událost nesmí mít zákazníka, vozidlo ani kontakt (422 `CLOSURE_MUST_BE_EMPTY`,
`EVENT_MUST_BE_EMPTY`).

**Kolize varují, nezakazují (rozhodnutí uživatele 2026-08-03).** Překryv dvou objednávek servis běžně chce
(dvě auta naráz) a kapacita dílny se nikde neeviduje — `POST` i `PUT` proto projdou. Klient si počet zjistí
přes `GET /overlaps` a zobrazí varování; rozhodnutí zůstává na obsluze.

**Blokace × objednávka je tvrdé pravidlo, a to v obou směrech.**

| Co se zakládá | Co už tam je | Výsledek |
|---|---|---|
| objednávka | blokace | 422 `APPOINTMENT_IN_CLOSURE` |
| blokace | objednávka nebo událost | 422 `CLOSURE_OVERLAPS_ENTRIES` |
| objednávka | objednávka | projde (jen varování) |

Druhý řádek přibyl 2026-08-07: do té doby šlo blokaci nakreslit přes existující objednávku a kalendář
pak tvrdil „zavřeno" i „přijede zákazník" naráz. Kontroluje se v `POST /appointments`,
`PUT /appointments/{id}` i `POST /appointments/{id}/time` (přetažení myší). Zrušené a nedostavené
položky se nepočítají — na ty už nikdo nepřijede. `blockedByClosure` v odpovědi `/overlaps`
předpovídá první řádek; druhý zatím předpověď nemá, klient se ho dozví až z chyby.

**Převod na zakázku je atomický.** `POST /{id}/convert` v jedné transakci zavolá `OrderService.create`
(logika zakázky se nekopíruje), nastaví `order_id` a stav `CONVERTED`. Alternativa „klient založí zakázku
a pak ji propojí" by při selhání druhého kroku nechala osiřelou zakázku, o které objednávka neví. Partial
unique index `uq_appointments_order` navíc zaručuje nejvýš jednu objednávku na zakázku.

**Stavy jsou čtyři** (V77): `PLANNED` → `CONVERTED` / `NO_SHOW` / `CANCELLED`. Původní `CONFIRMED`
zrušen — objednávka vzniká po telefonu se zákazníkem, takže je potvrzená už při založení.

**Terminální objednávka je jen ke čtení** (422 `APPOINTMENT_TERMINAL_READONLY` na `PUT` i `/time`).
Čas a účastníci jsou fakta o tom, co se stalo — přepsáním by se znehodnotila statistika nedostavení
a u převedené by se údaje rozešly se zakázkou. Obor to drží stejně (Acuity: zrušený termín nejde
od-zrušit ani upravit). UI tlačítko Upravit u terminálních skrývá; server je autoritativní.

**Stav `CONVERTED` nelze nastavit ručně** (422 `STATUS_NOT_SETTABLE`) — drží vazbu na zakázku, kterou
hlídá CHECK `chk_appointments_converted_order`. Terminální stavy (`CONVERTED`, `NO_SHOW`, `CANCELLED`)
nemají návrat; oživení se řeší novou objednávkou, aby historie „kdo nedorazil" zůstala pravdivá.

**Oprávnění.** Čtení a práce s objednávkami spadá pod výchozí `hasAnyRole('ADMIN','MANAGER','MECHANIC')`.
Zakládání a mazání **blokací dílny** je navíc omezeno na ADMIN/MANAGER (§19) — zavřít dílnu není rozhodnutí
mechanika. Kontrola je v controlleru, ne v `@PreAuthorize`: závisí na *hodnotě* `entryType`, na kterou anotace nedosáhne.

**Smazání ≠ zrušení.** `DELETE` maže **natrvalo** (V76) a vrací 204 — je pro položku, která vznikla
omylem a nikdy neměla existovat. Když zákazník jen nepřijede, patří to do stavu (`CANCELLED` /
`NO_SHOW`) a objednávka zůstane v kalendáři i ve statistikách. Objednávku, ze které už vznikla
zakázka, smazat nelze (422 `APPOINTMENT_CONVERTED_CANNOT_DELETE`) — není to omyl a odkaz „Vzniklo
z objednávky" na detailu zakázky je platný záznam.

Podrobně `docs/funkce/planovaci-kalendar.md`.

## Položky zakázky — `/api/{version}/orders/{orderId}` (OrderItemController)

| HTTP | Cesta | Request | Response | Status |
|---|---|---|---|---|
| GET | `/items` | — | `List<OrderItemDto.Response>` (`fromStock`, odvozené `issuedQuantity`, `productSku` a původ dílu `goodsReceiptId`/`supplierName`/`receiptInvoiceNumber`; `employeeId` + `employeeName` u LABOR) | 200 |
| GET | `/items/{id}` | — | `Response` | 200 |
| GET | `/items/summary` | — | `OrderItemSummaryDto.Response` (labor/material/service/total, net+gross) | 200 |
| POST | `/items` | `OrderItemDto.CreateRequest` (itemType, name, quantity > 0, unit, unitPrice ≥ 0, vatRate 0–100, volitelně `employeeId` u LABOR) | `Response` | 201; 422 `ORDER_LOCKED_BY_INVOICE` / `EMPLOYEE_ONLY_ON_LABOR`; 404 (neznámý employeeId) |
| POST | `/items/import-from-receipt` | `List<GoodsReceiptItemDto.ImportRequest>` {goodsReceiptItemId, quantity} | `List<Response>` | 201; 422 `ORDER_LOCKED_BY_INVOICE` / `QUANTITY_EXCEEDS_REMAINING` |
| POST | `/issue-stock` | — | `{ "issuedItems": n }` — kolik položek se vydalo | 200; 404 (neznámá zakázka); 422 `STOCK_MISSING_FOR_ISSUE` |
| PUT | `/items/{id}` | `UpdateRequest` (vč. volitelného `employeeId` u LABOR) | `Response` | 200; 422 `ORDER_LOCKED_BY_INVOICE` / `EMPLOYEE_ONLY_ON_LABOR`; 404 |
| PUT | `/items/reorder` | `List<ReorderRequest>` {id, position} | — | 204 |
| DELETE | `/items/{id}` | — | — | 204; 422 `ORDER_LOCKED_BY_INVOICE` |

**Výpis položek nese stav vůči skladu** (2026-08-07). `fromStock` a `issuedQuantity` spolu rozliší tři případy, které do té doby vypadaly v tabulce položek naprosto stejně: **ruční materiál** (sklad se ho netýká), **rezervace** (`fromStock` a `issuedQuantity = 0` — díl leží dál v regálu) a **výdej** (`issuedQuantity > 0` — díl je na autě). Rozdíl je praktický: mechanik potřebuje vědět, jestli si díl už vzal, a při rušení zakázky se vrací jen to, co bylo vydáno.

`issuedQuantity` se odvozuje z ledgeru fragmentem `issuedQuantity` v `OrderItemMapper.xml` — týmž, jakým se řídí `findIssuedByOrderId` a `findReservedByOrderId`. Do té doby stál ten výraz v mapperu dvakrát opsaný; se třetím použitím by se kopie nevyhnutelně rozešly.

**Původ dílu** (`goodsReceiptId`, `supplierName`, `receiptInvoiceNumber`, 2026-08-07) odpovídá na otázku „u koho tenhle díl reklamovat“. Díl odejde v záruce za půl roku a doklad, na kterém přišel, se do té doby dohledával ručně ve skladu — přestože řetěz položka → šarže → příjemka existuje v datech od V18. `null` u ručně zadané položky.

`productSku` je katalogové číslo dílu (přes šarži na kartu dílu; `null` u ruční položky). **Na fakturu nepatří** — slouží obsluze k ověření, že naimportovala správný díl. Dodavatelské názvy se opakují, takže dvě položky z různých šarží se běžně jmenují úplně stejně a liší se jen cenou.

**Neznámá zakázka vrací 404 na všech cestách, které berou `orderId`** (2026-08-07). Služba do té doby nerozlišovala **prázdnou** zakázku od **neexistující**: `GET /items` vrátil `200 []`, `/items/summary` samé nuly a `POST /issue-stock` dokonce `200 {"issuedItems": 0}`, tedy „hotovo, nebylo co vydat" — překlep v URL i práce nad mezitím smazanou zakázkou tak prošly tiše. `POST /items` navíc doběhl až k INSERTu a spadl na cizím klíči, takže obsluze vyšlo 422 „Zadaná data porušují databázové omezení"; pravidlo projektu je opačné — odmítnout dřív a česky, ne nechat probublat chybu integrity.

**Rezervace vs. výdej (V83, rozprava 2026-08-05).** Import ze skladu **nevytváří skladový pohyb** — je to jen **rezervace**: díl leží dál v regálu, sníží se pouze *dostupné* množství (`quantity_on_hand − rezervace`), fyzického stavu se to nedotkne. Sama existence položky s vazbou na šarži tu rezervaci vyjadřuje, nikam se neukládá. Díky tomu zrušení i smazání zakázky rezervaci prostě uvolní, bez jediného zápisu do ledgeru.

Množství se proto při importu validuje proti **dostupnému**, ne proti zbytku šarže → 422 `QUANTITY_EXCEEDS_REMAINING`; hláška rozlišuje, jestli díl chybí, nebo jen leží slíbený jiné zakázce (params `remaining`, `reserved`, `available`). Bez toho by dva lidé naplánovali tentýž poslední kus a druhý by na to přišel až u výdeje.

Fyzicky díl odejde ve dvou okamžicích — **co nastane dřív**: buď ručně přes `POST /issue-stock`, nebo **automaticky při přechodu zakázky do `COMPLETED`** (`PUT /orders/{id}`). Dokončení je okamžik, kdy je jisté, že se oprava opravdu stala. Spouští se jen na **skutečném přechodu**, ne na identitě — uložení už dokončené zakázky (oprava překlepu v popisu) výdej neopakuje. Chybí-li rezervovaný díl na skladě, vrátí `PUT /orders/{id}` 422 `STOCK_MISSING_FOR_ISSUE` a **celá změna se vrátí zpět**: zakázka zůstane nedokončená, protože dokončená zakázka s materiálem, který na skladě není, by rozešla papír a regál.

`POST /issue-stock` vydá **celou zakázku najednou**, tedy vše, co ještě vydáno nebylo. Opakované volání nic nezdvojí (vydané položky do výběru nespadnou); naopak položka, jejíž výdej se vrátil, se vydá znovu. Šarže se zamykají `FOR UPDATE` (vzor K6) a validují proti **fyzickému zbytku** — vydávané položky jsou samy součástí rezervace, takže proti dostupnému by výdej neprošel nikdy. Zmizel-li rezervovaný díl mezitím ze skladu (inventura, odpis, vratka), vrátí se 422 `STOCK_MISSING_FOR_ISSUE` s výčtem, co chybí.

Mazání položky zakládá `ISSUE_RETURN` **jen tehdy, byla-li vydaná**, a vrací *vydané* množství (to se může lišit od množství položky, když se po výdeji upravilo). U pouhé rezervace nevzniká pohyb žádný — dřív se vratka zakládala vždy, což by na sklad přidalo zboží, které z něj nikdy neodešlo.

**Změna množství u skladové položky** (`PUT /items/{id}`) je nově povolená. Do V83 se u položky s vazbou na šarži zadané množství **tiše zahodilo** — `OrderItemConverter.applyUpdate` ho přeskočil a odpověď vrátila starou hodnotu, aniž by aplikace cokoli řekla. Opravit omyl v počtu tedy šlo jen smazáním položky a novým importem. Nově se sklad dorovná podle toho, co se fyzicky stalo: u **pouhé rezervace** se nezapisuje nic (mění se jen slib, díl regál neopustil), u **vydané** položky vznikne rozdílový pohyb — snížení vrátí rozdíl `ISSUE_RETURN`, zvýšení ho dovydá `ISSUE`, a nestačí-li zásoba, vrací se 422 `STOCK_MISSING_FOR_ISSUE`. Porovnává se s **ledgerem**, ne s předchozí hodnotou položky, takže vyjde i opakovaná změna nebo částečné vrácení. Ostatní pole skladové položky (jednotka, DPH, typ, nákupní cena) zůstávají zamčená — jsou to snímky ze šarže.

Kolize cest `PUT /items/{id}` vs `/items/reorder` — Spring preferuje přesnou shodu.

Mechanik se přiřazuje k **položce** typu LABOR (`employeeId`, D-1) — DB CHECK `chk_order_items_employee_labor` (jen u LABOR, D-2) je v service předsazen jako 422 `EMPLOYEE_ONLY_ON_LABOR`. Při přiřazení se **aktuální** hodinová sazba mechanika snímkuje do `purchase_price`, a to jen když je prázdná **a jednotka položky je `hod`** (D-6 fallback; frontend ji předvyplní a pošle). Práci lze totiž od 2026-08-03 účtovat i po kusech — paušálem za úkon — a hodinová sazba dosazená jako cena za kus by byla tiše špatné číslo v nákladech; pole tam proto zůstane prázdné. Jednou zapsaný snímek se nepřepočítává — pozdější změna sazby zaměstnance historickou položku nezmění (D-3). `employeeName` v odpovědi je čtený přes JOIN, není uložený.

Existuje-li k zakázce **aktivní** faktura (nestornovaná a nedobropisovaná — týž predikát jako `uq_invoices_order_active`), jsou položky zakázky zamčené — `create`/`importFromReceipt`/`update`/`delete` vrací 422 `ORDER_LOCKED_BY_INVOICE` (faktura je snapshot položek z okamžiku vytvoření, dodatečná změna by ji tiše rozjela od zakázky). Hláška doklad jmenuje přes `Invoice.describe()` (ve 4. pádě, do vazby „má …"): „Zakázka už má fakturu 202607001", u konceptu „koncept faktury" — číslo se přiděluje až při vystavení (V49), takže dřív hlásila „fakturu null". `reorder` (jen pořadí zobrazení) zámkem není dotčen (V2, analyza-2026-07).

## Zaměstnanci — `/api/{version}/employees` (EmployeeController)

Evidence mechaniků a jejich hodinové sazby (náklad práce). Mechanik se přiřazuje k **položce** zakázky typu LABOR (viz Položky zakázky, `employeeId`); sazba se přitom snímkuje do `purchase_price` (D-3).

| HTTP | Cesta | Request | Response | Status | Autorizace |
|---|---|---|---|---|---|
| GET | `/employees` | query `activeOnly` (default `false`) | `List<EmployeeDto.ListResponse>` (id, fullName, position, hourlyRate, hiredAt, leftAt, active) — malý seznam bez stránkování; slouží i jako select položky | 200 | baseline |
| GET | `/employees/{id}` | — | `EmployeeDto.DetailResponse` | 200 | baseline |
| POST | `/employees` | `EmployeeDto.CreateRequest` (firstName, lastName, hiredAt povinné; volitelně userId, position, hourlyRate ≥ 0, leftAt) | `DetailResponse` | 201; 422 `INVALID_EMPLOYEE_DATES` / `DUPLICATE_EMPLOYEE_USER`; 404 (neznámý userId) | ADMIN/MANAGER |
| PUT | `/employees/{id}` | `EmployeeDto.UpdateRequest` | `DetailResponse` | 200; 422 `INVALID_EMPLOYEE_DATES` / `DUPLICATE_EMPLOYEE_USER`; 404 | ADMIN/MANAGER |
| DELETE | `/employees/{id}` | — | `DetailResponse` (deaktivovaný) | 200 | ADMIN/MANAGER |
| POST | `/employees/{id}/activate` | — | `DetailResponse` | 200 | ADMIN/MANAGER |

Čtení (`GET`) je na baseline (i mechanik) — potřebuje aktivní seznam pro select u LABOR položky. Správa (mutace) je vyhrazena vedení (D-7, §19). `userId` je volitelná vazba na login (D-5), unikátní; `leftAt ≥ hiredAt` hlídá i DB CHECK.

## Faktury — `/api/{version}/invoices` (InvoiceController)

| HTTP | Cesta | Request | Response | Status |
|---|---|---|---|---|
| POST | `/invoices/from-order` | `InvoiceDto.CreateRequest` (orderId, billingAddressId, issue/due/taxableSupplyDate, paymentMethod, symboly, `purchaseOrderNumber` — číslo objednávky zákazníka, volný text max 100, V91) — **číslo ani variabilní symbol tu nejsou**, koncept je nemá | `DetailResponse` (`invoiceNumber` = `null`) | 201; 422 `DUE_DATE_BEFORE_ISSUE_DATE` / `ORDER_ALREADY_INVOICED` / `ORDER_NOT_INVOICEABLE` (zakázka není dokončená) |
| GET | `/invoices/next-number` | `issueDate` (ISO datum, nepovinné — default dnešek) | `NextNumberResponse` (`auto`, `invoiceNumber`) — návrh dalšího čísla řady dle masky (V71) pro **dialog vystavení**, **nic nerezervuje**; při vypnutém automatu `auto=false` bez návrhu | 200 |
| GET | `/invoices/number-gaps` | — | `NumberGapsResponse` (`enabled`, `missingNumbers[]`, `periodDate`) — chybějící čísla řady za **aktuální období** (V89) | 200 |
| GET | `/invoices` | `InvoiceSearchParams` (search, status, issueDateFrom/To, `overdue`=true → jen ISSUED po splatnosti) | `PagedResponse<ListResponse>` | 200 |
| GET | `/invoices/{id}` | — | `DetailResponse` (vč. supplier/customer party, items, vatSummary, totals; u zaplacené i `paidAt`/`paidAmount`/`paidMethod`) | 200 |
| GET | `/invoices/number/{invoiceNumber}` | — | `DetailResponse` | 200 |
| GET | `/invoices/order/{orderId}` | — | `DetailResponse` | 200 |
| GET | `/invoices/customer/{customerId}` | — | `List<ListResponse>` | 200 |
| PUT | `/invoices/{id}` | `UpdateRequest` (dueDate, symboly, paymentMethod, status, note, `purchaseOrderNumber`) — `issueDate` v něm **není**, datum vystavení se přes PUT nemění | `DetailResponse` | 200; 422 `DUE_DATE_BEFORE_ISSUE_DATE` |
| POST | `/invoices/{id}/issue` | `InvoiceDto.IssueRequest` (**invoiceNumber** — povinné, max 20; **issueDate** — povinné, hodnota bez omezení; **variableSymbol** — nepovinný, jen číslice max 10) | `DetailResponse` (DRAFT→ISSUED; **tady doklad dostává číslo, VS a definitivní datum vystavení**) | 200; 422 `INVALID_STATUS_TRANSITION`/`INVOICE_HAS_NO_ITEMS`/`DUPLICATE_INVOICE_NUMBER`/`INVOICE_NUMBER_MISSING`; 409 `INVOICE_STATE_CHANGED` |
| POST | `/invoices/{id}/pay` | — | `DetailResponse` (ISSUED→PAID; **orazítkuje i předání**, není-li potvrzené) | 200; 422 `INVALID_STATUS_TRANSITION`; 409 `INVOICE_STATE_CHANGED` |
| DELETE | `/invoices/{id}/pay` | — | `DetailResponse` — **vezme platbu zpět** (PAID→ISSUED, smaže `paid_*`) | 200; 422 `INVOICE_NOT_PAID` / `INVOICE_HAS_CASH_RECEIPT`; 409 | `hasAnyRole('ADMIN','MANAGER')` |
| DELETE | `/invoices/{id}/issue` | — | `DetailResponse` — **vrátí do konceptu** (ISSUED→DRAFT, uvolní číslo i VS) | 200; 422 `INVOICE_NOT_ISSUED` / `INVOICE_NOT_DELETABLE` (předaná) / `INVOICE_HAS_LINKED_DOCUMENTS`; 409 | `hasAnyRole('ADMIN','MANAGER')` |
| POST | `/invoices/{id}/hand-over` | — | `DetailResponse` — **potvrdí předání dokladu zákazníkovi** (V88); idempotentní | 200; 422 `INVOICE_NOT_ISSUED` (koncept se nepředává); 409 | `hasAnyRole('ADMIN','MANAGER')` |
| DELETE | `/invoices/{id}/hand-over` | — | `DetailResponse` — vezme předání zpět | 200; 422 `INVOICE_ALREADY_PAID`; 409 | `hasAnyRole('ADMIN','MANAGER')` |
| GET | `/invoices/{id}/email-draft` | — | `InvoiceEmailDto.DraftResponse` (`recipient` — e-mail z karty zákazníka, může být `null`; `subject`; `body` — kostra textu) — návrh e-mailu pro dialog odeslání, **nic neodesílá** | 200; 422 `INVOICE_NOT_ISSUED` (koncept se neposílá) | `hasAnyRole('ADMIN','MANAGER')` |
| POST | `/invoices/{id}/send-email` | `InvoiceEmailDto.SendRequest` (`recipient` — povinný validní e-mail; `subject` max 200; `body` max 5000 — **finální znění z dialogu, server nic nedoplňuje**) | `DetailResponse` — odešle fakturu e-mailem s PDF přílohou; **nepředanou orazítkuje jako předanou** | 200; 422 `INVOICE_NOT_ISSUED` / `EMAIL_NOT_CONFIGURED` / `EMAIL_SEND_FAILED` | `hasAnyRole('ADMIN','MANAGER')` |
| DELETE | `/invoices/{id}` | — | — **smaže koncept i nepředanou vystavenou fakturu** (V88), včetně položek a stran (FK CASCADE) | 204; 404; 422 `INVOICE_NOT_DELETABLE` (předaná nebo zaplacená — opravuje se dobropisem, KN-1) / `INVOICE_HAS_LINKED_DOCUMENTS` (visí na ní PPD nebo dobropis); 409 `INVOICE_STATE_CHANGED` |
| POST | `/invoices/{invoiceId}/items` | `InvoiceItemDto.CreateRequest` | `Response` | 201 |
| PUT | `/invoices/{invoiceId}/items/{itemId}` | `UpdateRequest` | `Response` | 200 |
| DELETE | `/invoices/{invoiceId}/items/{itemId}` | — | — | 204 |

**Fakturu jde poslat e-mailem zákazníkovi** (`/email-draft` + `/send-email`, 2026-08-08) — PDF dokladu jako příloha, odesílá se přes SMTP účet servisu (`spring.mail.*`, typicky Seznam; přihlášení je secret s prázdným defaultem — bez něj aplikace startuje a odeslání vrací 422 `EMAIL_NOT_CONFIGURED`). Kostru e-mailu skládá server z dat dokladu a karty zákazníka, obsluha ji v dialogu upraví a odešle se přesně potvrzené znění. **Úspěšné odeslání razítkuje předání** (e-mail ve schránce = doklad u zákazníka, V88); selhané ho nechává být. Evidence odeslaných e-mailů se v aplikaci **nevede** — evidencí je složka Odeslané e-mailového účtu, kam aplikace po odeslání sama uloží kopii přes IMAP (Seznam to za SMTP klienty nedělá; uložení je best-effort, výpadek IMAP odeslání neshodí) (rozhodnutí uživatele 2026-08-08). Detailně: [funkce/odesilani-faktur-emailem.md](funkce/odesilani-faktur-emailem.md).

**Špatně zadané číslo se opravuje vrácením do konceptu** (`DELETE /invoices/{id}/issue`, 2026-08-08). Doklad, který nikam neodešel, se opravuje editací, ne dobropisem. Smazat a vystavit znovu by fungovalo taky, ale zahodí i adresu, data a symboly a nutí fakturu skládat znovu ze zakázky — tohle uvolní **jen číslo a variabilní symbol**, zbytek zůstane. Neprojde u předané (→ dobropis), zaplacené (→ nejdřív vzít platbu zpět) ani s navázaným pokladním dokladem.

Implementace jde **dvěma UPDATE v jedné transakci** a na pořadí záleží: trigger `trg_invoices_number_immutable` (V71) zakazuje změnu čísla, když `OLD.status <> 'DRAFT'`, takže jedním příkazem by to spadlo. Nejdřív se ruší vystavení (číslo se nemění → trigger nevystřelí), pak se maže číslo (už z konceptu). Není to obcházení zábrany, ale přesně to, co říká: *dokud je doklad vystavený, číslo se nemění.* Mezistav „koncept s číslem" projde i CHECKem `chk_invoice_issued_has_number`, který číslo žádá jen u `ISSUED`/`PAID`.

**Omylem kliknuté „Označit zaplaceno" jde vzít zpět** (`DELETE /invoices/{id}/pay`, 2026-08-08). Do té doby bylo nevratné a jediná cesta ven vedla přes dobropis — jenže „Dobropisována + Zaplacena" znamená *držím peníze zákazníka a dlužím vratku*, takže u překlepu by šlo o lež v datech. Úhrada přitom **není daňový doklad**, ale interní záznam; číslo ani datum vystavení se nemění, mizí jen `paid_at`/`paid_amount`/`paid_method`.

Neprojde, visí-li na faktuře **platný pokladní doklad** (422 `INVOICE_HAS_CASH_RECEIPT`) — ten má vlastní číselnou řadu a stornuje se zvlášť. **Předání se nevrací**: jsou to dvě nezávislé věci a razítko mohlo vzniknout dřív ručně.

`PAID` zůstává v `ALLOWED_TRANSITIONS` **terminální**. Zápis `PAID → ISSUED` byl vrácen: zpřístupnil totiž `transitionTo(id, ISSUED)`, tedy cestu znovu vystavit zaplacenou fakturu a přidělit jí nové číslo (odhalil test `paid_isTerminal`). Vzetí platby proto jede vlastním guardovaným UPDATE.

**Stavy faktury: jedna osa a jeden terminál** (2026-08-08).

```
Koncept → Vystavena → Předána → Zaplacena     osa: kde doklad je
Dobropisována                                  terminál: doklad je vyrušený
```

Dobropisovaná faktura se po ose **dál neposouvá** — proto dvě zábrany: nelze ji označit zaplaceno (422 `INVOICE_CREDITED`; úhrada na vyrušený doklad je nesmysl) a nelze u ní vzít předání zpět (týž kód; jinak by vznikla kombinace „nepředaná + dobropisovaná", kterou dobropis sám vylučuje). Do té doby druhou situaci zastavila až kontrola navázaných dokladů při mazání — tedy pojistka, ne pravidlo.

Zobrazení skládá `getInvoiceStates()` ve `format.js` a vrací **pole** odznaků, protože dva případy potřebují dva: **rozpracovaná oprava** (osa + „Oprava rozpracována") a **dobropisovaná zaplacená** („Dobropisována" + „Zaplacena" — jediný případ, kdy ještě něco dlužíš: peníze máš a doklad je vyrušený). U dobropisované osa mizí; „Předána" už nic neřídí.

**Koncept dobropisu je nově vidět.** Nemá číslo a `credited_at` nenastavuje, ale blokuje založení druhé opravy i fakturaci zakázky — do 2026-08-08 byl na faktuře úplně neviditelný. Seznamy ho nesou jako `hasDraftCreditNote`.

**Hlídání mezer v číselné řadě** (V89) je volitelné (`company_profile.invoice_gap_check_enabled` + `invoice_gap_check_from`) a odpovídá na to, co otevřela V88: smazáním faktury, která **není poslední** v řadě, vznikne díra a `MAX+1` ji sám nezavře. Zavírá se ručně — číslo je při vystavení editovatelné — a `GET /invoices/number-gaps` na ni upozorní nad seznamem faktur.

Hlídá se **jen aktuální období** podle masky (rozhodnutí uživatele 2026-08-08): tentýž měsíc se díra zavře nejsnáz, takže hláška zůstane akceschopná, a přetečením do dalšího období zmizí sama — trvalé upozornění, se kterým nejde nic dělat, by se za týden přestalo číst. `invoice_gap_check_from` umlčí starší čísla, typicky doklady přenesené z jiného systému.

Detekce je **v aplikaci, ne v SQL** — masku umí rozebrat jen `DocumentNumberMask` (do V92 `InvoiceNumberMask`), proto je i generátor čísel mimo DB trigger. Očekávaná čísla skládá **touž metodou, která je přiděluje** (`mask.format`), a porovná je se skutečností; druhý parser čísel v projektu neexistuje, takže se kontrola nemůže s generátorem rozejít. `enabled: false` znamená vypnuto, **ne** „řada je souvislá".

**Předání zákazníkovi je vlastní příznak, ne stav** (`handed_over_at`, V88). Ve **výpisu** je ale součástí zobrazeného stavu, ne druhý odznak: vystavená faktura se ukazuje jako **Vystavena** → **Předána** (rozhodnutí uživatele 2026-08-08). Předání totiž mění, co s dokladem lze dělat — nepředaný jde smazat a vystavit znovu, předaný už jen dobropisovat — takže je to posun dokladu dál, ne příznak vedle stavu. První verze ukazovala „Vystavena + Nepředáno", což byl dvojitý zápor a nutilo to číst dva odznaky kvůli jednomu stavu. Zaplacená faktura je předaná vždy, vlastní popisek nepotřebuje — a `POST /pay` proto `handed_over_at` rovnou **orazítkuje**, není-li už potvrzené. Bez toho by u faktury zaplacené na místě zůstal v datech záznam „zákazník doklad nedostal" o dokladu, se kterým se všude zachází jako s předaným. Dřívější ruční potvrzení se nepřepíše. Skládá to `getInvoiceState()` ve `format.js` — jeden popisek pro seznam faktur, seznam u zákazníka i detail. Odznak nese i **datum předání** („Předána 8. 8. 2026"), a to všude stejně; do tooltipu nepatří, protože ten se na dotykovém displeji nezobrazí. Vystavená faktura se dělí na *nepředanou* a *předanou* — a jen ta druhá je doklad, který se opravuje dobropisem. Do V88 aplikace přisuzovala předání už samotnému vystavení, přestože o odeslání nevěděla nic a fakturu **sama neposílá**: věděla jen, že někdo klikl na „Vystavit", což může být i překlep starý deset vteřin. Za každý takový překlep pak v evidenci ležela dvojice dokladů dokazující, že se někdo upsal.

Nepředanou a nezaplacenou fakturu lze proto **smazat**; číslo se uvolní a příští vystavení ho může použít (řada je `MAX+1`, mezera po smazané poslední nevznikne, u starší ji lze zavřít ručně — číslo je editovatelné). Zaplacená faktura je blokovaná i bez příznaku: kdo platí, doklad má. Předání jde **vzít zpět**, protože i ono se dá kliknout omylem.

Příznak je sloupec, ne stav — předání je nezávislé na zaplacení, takže automat níž zůstává lineární.

**Faktura s navázaným PPD nebo dobropisem se nesmaže** → 422 `INVOICE_HAS_LINKED_DOCUMENTS`. Tuhle kontrolu chtěl nález KN-12 a byla tehdy vyhodnocena jako nedosažitelná (mrtvý kód R-12), protože vystavenou fakturu nešlo smazat vůbec. Otevřením mazání nepředané faktury se stala dosažitelnou — a bez ní probublávala obsluze `DataIntegrityViolationException` z cizího klíče místo české hlášky.

Stavový automat `InvoiceStatus` (v enumu): DRAFT→{ISSUED}, **ISSUED→{PAID}**, PAID terminální.
Hodnota `CANCELLED` zůstává **jen pro historická data** — aplikace ji od 2026-08-02 nenastaví,
protože koncept se místo storna **maže** (rozhodnutí uživatele: stornované rozpracované faktury
jen zaplňovaly tabulku a koncept není doklad). Filtry `status <> 'CANCELLED'`
(`uq_invoices_order_active`, `findByOrderId`) proto platí dál kvůli dřív stornovaným fakturám.

**Zahodit lze jen koncept** (audit KN-1): vystavenou ani zaplacenou fakturu smazat nelze —
oprava patří opravnému daňovému dokladu (§42/§45 ZDPH, `/credit-notes`). Pokus vrací **422
`INVOICE_NOT_DELETABLE`** s návodem. Smazaný koncept uvolní zakázku k úpravám i k nové fakturaci.
Položky editovatelné jen v DRAFT. Faktura ↔ zakázka je 1:1 (`ORDER_ALREADY_INVOICED`). Přechodové
endpointy (`issue`/`pay`) i `DELETE` mají guardovaný zápis `WHERE status = expectedStatus` (K5,
analyza-2026-07) — pokud mezi kontrolou a zápisem fakturu změnil jiný požadavek, vrací se 409
`INVOICE_STATE_CHANGED` místo tichého přepsání (vzor: `ReceiptReviewServiceImpl.confirm`).

**Dobropisovaná faktura uvolní zakázku (V69).** `DetailResponse` **i `ListResponse`** nesou `creditedAt` — kdy byl k faktuře **vystaven** dobropis (koncept nic neuvolňuje). Faktura zůstává ve stavu ISSUED/PAID a platným dokladem, ale přestává být **aktivní** fakturou zakázky: `POST /invoices/from-order` na tutéž zakázku znovu projde, `GET /invoices/order/{orderId}` ji už nevrací a v seznamu zakázek se sloupec Faktura vyprázdní. Bez toho by chybná vystavená faktura zamkla zakázku navždy — storno vystaveného dokladu je od KN-1 zakázané a dobropis stav faktury nemění. Uvolní se i editace položek zakázky (`ORDER_LOCKED_BY_INVOICE`), protože oprava položek je typicky důvod, proč se dobropisuje. V `ListResponse` je pole proto, že **stav faktury se dobropisem nemění** — bez něj vypadala opravená faktura v seznamu stejně jako platná pohledávka; UI podle něj vedle stavu vykreslí odznak „Dobropisována".

**Číslo a VS vznikají při vystavení.** Koncept je nemá (`invoiceNumber` = `null`, hlášky ho jmenují jako „koncept faktury" přes `Invoice.describe()`), takže **zrušený koncept nedělá do řady mezeru**. `POST /invoices/{id}/issue` vezme nad řadou daného období `pg_advisory_xact_lock` (souběžné vystavení nedostane totéž pořadí), určí číslo, ověří jeho unikátnost → 422 `DUPLICATE_INVOICE_NUMBER`, a číslo, VS, data i stav zapíše **jedním guardovaným UPDATE** (`WHERE status='DRAFT'`).

Číslo posílá vždy **dialog vystavení**; přepínač `company_profile.invoice_number_auto` řídí jen jeho **předvyplnění** — zapnutý → návrh z `GET /invoices/next-number`, vypnutý → prázdné pole. V obou režimech lze zapsat libovolné číslo (maska je předpis pro generování návrhu, ne validační pravidlo — rozhodnutí uživatele 2026-08-02); hlídá se neprázdnost, unikátnost a délka ≤ 20. Návrh **nic nerezervuje**: souběh dvou dialogů se stejným číslem vyřeší až zámek řady a `uq_invoice_number` → 422 a frontend si řekne o čerstvý návrh. Podrobně `funkce/cislovani-faktur.md`.

**Datum vystavení volí obsluha (rozhodnutí uživatele 2026-08-07).** Na fakturu jde datum, které přijde v `IssueRequest` — dialog vystavení ho předvyplní datem z konceptu a obsluha ho tam může upravit. Server ho **nepřepisuje**; dřív se razítkovalo dneškem (audit KN-10), takže datum zadané při zakládání se na doklad nikdy nedostalo.

Původní důvod razítka — aby se číslo a datum nerozešly o období — drží teď **sladění řady se zvoleným datem**: dialog tahá návrh přes `GET /invoices/next-number?issueDate=…` a `pg_advisory_xact_lock` se bere nad řadou téhož období. Změna data v dialogu proto přenačte i návrh čísla.

**Hodnota data je bez omezení** (od 2026-08-09; dřív 422 `ISSUE_DATE_IN_FUTURE`) — zpětné datování (leželý koncept je legitimní důvod) i budoucí datum. Odpovědnost nese obsluha: pozor na už podané přiznání k DPH a u budoucího data na to, že číslo pochází z řady období zvoleného data.

`dueDate` se posune o **původní lhůtu splatnosti**, jen když by ji zvolené datum vystavení předběhlo (jinak by doklad narazil na CHECK `chk_due_date`); splatnost v budoucnu zůstává. `taxableSupplyDate` se nemění — DUZP je fakt okamžiku plnění (§21 ZDPH), ne vystavení.

**Zaokrouhlení hotovosti (V67, audit KN-7/L-9).** `DetailResponse` i `ListResponse` nesou vedle
`totalNet`/`totalVat`/`totalGross` také **`totalToPay`** (a detail navíc `rounding`). U faktury
s `paymentMethod = CASH` je celková částka zaokrouhlená na celé Kč; rozdíl stojí dle §36 odst. 5
ZDPH **mimo základ daně**, takže `totalNet`, `totalVat` ani rozpis DPH se nemění a platí
`totalToPay = totalGross + rounding`. Tutéž hodnotu nese PDF („Celkem k úhradě"), QR platba,
příjmový pokladní doklad i `paid_amount` — dřív si zaokrouhloval jen PPD a doklady se rozcházely.
U kombinovaných způsobů (`CASH_OR_TRANSFER`, `CASH_OR_CARD`) se nezaokrouhluje: o formě platby se
rozhoduje až při úhradě. Seznam faktur zobrazuje `totalToPay` a **řadí podle ní** (klíč `sortBy`
se kvůli kompatibilitě dál jmenuje `totalGross`).

## PDF faktury — (InvoiceDocumentController)

| HTTP | Cesta | Response | Status |
|---|---|---|---|
| GET | `/invoices/{id}/pdf` | `byte[]`, `application/pdf`, `Content-Disposition: inline; filename="faktura-{id}.pdf"` | 200 |

## Opravný daňový doklad — `/api/{version}/credit-notes` (CreditNoteController)

Dobropis dle §45 ZDPH (audit R-7). Váže se na vystavenou/zaplacenou fakturu; §45 rozdíly (základ/daň/celkem vč. rozpadu po sazbách, záporné) a strany se odvozují z původní faktury; číslo řady `OD{YYYYMM}###` se přidělí až při vystavení. MVP = plný dobropis; PDF (E5.2) a částečný dobropis jsou plánované.

| HTTP | Cesta | Request | Response | Status |
|---|---|---|---|---|
| POST | `/credit-notes` | `CreateRequest` {originalInvoiceId, correctionReason, [taxableSupplyDate]} | `DetailResponse` | 201; 404 (faktura); 422 `INVOICE_NOT_CORRECTABLE` (jen ISSUED/PAID) / `INVOICE_NOT_HANDED_OVER` (zákazník ji nedostal — smazat a vystavit znovu) / `INVOICE_ALREADY_CREDITED` (KN-8) |
| POST | `/credit-notes/{id}/issue` | — | `DetailResponse` (DRAFT→ISSUED, přidělí číslo OD) — **orazítkuje původní fakturu jako dobropisovanou** a tím uvolní zakázku pro novou fakturu (V69) | 200; 422 `INVALID_STATUS_TRANSITION`; 409 `CREDIT_NOTE_STATE_CHANGED` |
| DELETE | `/credit-notes/{id}` | — | — **smaže koncept** opravného dokladu; teprve tím jde k faktuře založit nový (bez toho byla omylem založená oprava slepá ulička) | 204; 404 neexistuje; 422 `CREDIT_NOTE_NOT_DELETABLE` (vystavený doklad); 409 `CREDIT_NOTE_STATE_CHANGED` |
| GET | `/credit-notes?invoiceId={id}` | — | `List<DetailResponse>` — dobropisy k faktuře (i stornované; aktivní může být nejvýš jeden, V66) | 200 |
| GET | `/credit-notes/{id}` | — | `DetailResponse` (§45 rozdíly + strany z původní faktury) | 200; 404 |
| GET | `/credit-notes/{id}/pdf` | — | `byte[]`, `application/pdf`, `Content-Disposition: inline; filename="dobropis-{id}.pdf"` | 200; 404 |

**Dobropis jde vystavit až k PŘEDANÉ faktuře** (2026-08-08). Opravuje základ daně nebo daň v **cizí** evidenci — u dokladu, který zákazník nikdy nedostal, není co opravovat: nemá ho a odpočet z něj neuplatnil. Správná cesta je fakturu **smazat a vystavit znovu** (V88), ne k ní vyrábět druhý doklad. Zaplacená faktura se za předanou považuje vždy: kdo platí, doklad má.

Účetní pravidlo, ze kterého to plyne: dobropis je namístě, když (a) doklad je u odběratele **a zároveň** (b) mění se **základ daně nebo daň** (§42/§43 ZDPH). Chybí-li jedna z podmínek, dobropis to není — překlep v adrese nebo ve variabilním symbolu se opravuje jinak, protože daň nemění.


## Příjmový pokladní doklad — `/api/{version}/cash-receipts` (CashReceiptController)

Potvrzení příjmu hotovosti k úhradě faktury (§11 ZoÚ). Váže se na vystavenou/zaplacenou fakturu; přijatá částka = **`totalToPay` faktury** (u hotovosti zaokrouhlená na celé Kč — počítá view, ne doklad, viz Zaokrouhlení hotovosti výše; rozdíl vrací pole `rounding`, § 36/5 ZDPH — rozpis DPH se nemění), účel platby doplní server. **Číslo dokladu od V92 posílá klient** (dialog s návrhem z `next-number`, editovatelné) — předvyplnění řídí `cash_receipt_number_source` z profilu firmy (V93): `MASK` = návrh dle masky `cash_receipt_number_mask` (mechanismus faktur z V71), `INVOICE` = číslo hrazené faktury (dosazuje FE), `MANUAL` = prázdné pole. Účastníci (příjemce = dodavatel, plátce = odběratel) a rozpis DPH se odvozují z faktury; částka je i **slovy** (`amountInWords`). K jedné faktuře smí být **nejvýš jeden nestornovaný** doklad (V68, audit KN-7) — fakturu pro nový uvolní storno nebo smazání. Vyhrazeno vedení (ADMIN/MANAGER).

| HTTP | Cesta | Request | Response | Status |
|---|---|---|---|---|
| POST | `/cash-receipts` | `CreateRequest` {invoiceId, receiptNumber ≤20 povinné (V92), issueDate nepovinné = dnešek, hodnota bez omezení — i budoucí (2026-08-09, dřív 422 `CASH_RECEIPT_ISSUE_DATE_IN_FUTURE`; doklad se připravuje před příchodem zákazníka)} | `DetailResponse` | 201; 404 (faktura); 422 `INVOICE_NOT_ISSUED` (jen ISSUED/PAID), `DUPLICATE_CASH_RECEIPT_NUMBER`; **409 `CASH_RECEIPT_ALREADY_EXISTS`** (k faktuře už platný doklad je — V68) |
| GET | `/cash-receipts/next-number?issueDate=` | — | `NextNumberResponse` {source: MASK/INVOICE/MANUAL, receiptNumber} — návrh řady jen u `MASK`, **nic nerezervuje** (V92/V93); u `INVOICE` dosazuje číslo faktury FE | 200; 422 `CASH_RECEIPT_NUMBER_SERIES_OVERFLOW` |
| GET | `/cash-receipts/number-gaps` | — | `NumberGapsResponse` {enabled, missingNumbers[], periodDate} — díry aktuálního období (V92); vypnutá kontrola **nebo režim `INVOICE`** (V93) = enabled false a prázdný seznam | 200 |
| DELETE | `/cash-receipts/{id}` | — | — | 204; 404. **Tvrdé DELETE** (V92, rozhodnutí uživatele): číslo se uvolní, faktura přestane mít navázaný doklad; mazat jde i stornovaný |
| POST | `/cash-receipts/{id}/cancel` | `CancelRequest` {reason, ≤255, povinný} | `DetailResponse` (status CANCELLED) | 200; 404; 422 `CASH_RECEIPT_ALREADY_CANCELLED` |
| GET | `/cash-receipts/{id}` | — | `DetailResponse` (částka slovy, účastníci + rozpis DPH z faktury) | 200; 404 |
| GET | `/cash-receipts?invoiceId={id}` | — | `DetailResponse[]` (doklady k faktuře **i stornované** — číselná řada je souvislá) | 200 |
| GET | `/cash-receipts/{id}/pdf` | — | `byte[]`, `application/pdf`, `Content-Disposition: inline; filename="pokladni-doklad-{id}.pdf"` | 200; 404 |

**Storno (V68) vs. smazání (V92).** Storno nechá doklad v číselné řadě — jde vytisknout a PDF nese pruh **STORNOVÁNO** s povinným důvodem a datem (`status`, `cancelledAt`, `cancellationReason` v `DetailResponse`). Smazání (rozhodnutí uživatele 2026-08-09) doklad odstraní úplně a uvolní číslo — MAX+1 ho nabídne znovu (bylo-li poslední), díru uprostřed řady zavře ruční zápis chybějícího čísla; hlídání děr viz `number-gaps`. Volba mezi stornem (auditní stopa) a smazáním (čistá řada) je na obsluze.

## Profil firmy — `/api/{version}/invoices/company-profile` (CompanyProfileController)

| HTTP | Cesta | Request | Response | Status |
|---|---|---|---|---|
| GET | `/invoices/company-profile` | — | `CompanyProfileDto.Response` | 200 |
| PUT | `/invoices/company-profile` | `UpdateRequest` (**name**, **countryCode**, **invoiceNumberAuto**, **invoiceNumberMask**, **cashReceiptNumberSource** a **cashReceiptNumberMask** povinné, adresa, banka) | `Response` | 200; 400 (chybí povinné pole); 422 `INVALID_INVOICE_NUMBER_MASK` / `INVALID_CASH_RECEIPT_NUMBER_MASK` |

Není-li profil vyplněný vůbec, `POST /invoices/from-order` vrátí **422 `COMPANY_PROFILE_MISSING`** s návodem doplnit ho ve Fakturačních údajích — dřív to byl `IllegalStateException` → **500** (audit 10/A-3), přestože jde o nevyplněné nastavení, ne o pád serveru.

Profil od V71 nese i **nastavení číslování faktur**: `invoiceNumberAuto` (přepínač skládání dle masky a předvyplňování) a `invoiceNumberMask` (tokeny `{RRRR}` `{RR}` `{MM}` `{N…}`; validuje `DocumentNumberMask` (do V92 `InvoiceNumberMask`), i při vypnutém přepínači — po zapnutí musí být hned použitelná). Od V92/V93 obdobně **číslování pokladních dokladů**: `cashReceiptNumberSource` (`MASK`/`INVOICE`/`MANUAL`), `cashReceiptNumberMask` (validuje se vždy), `cashReceiptGapCheckEnabled` + `cashReceiptGapCheckFrom` (hlídání děr; v režimu `INVOICE` ho aplikace deaktivuje).

## Sklad: produkty — `/api/{version}/warehouse/products` (ProductController)

| HTTP | Cesta | Request | Response | Status |
|---|---|---|---|---|
| GET | `/products` | `ProductSearchParams` (search, activeOnly, lowStockOnly) | `PagedResponse<ProductDto.ListResponse>` (pole `quantityOnHand`, `quantityReserved`, `quantityAvailable`, `lowStock`) | 200 |
| GET | `/products/{id}` | — | `DetailResponse` (vč. `batches[]`, `movements[]` a `reservations[]`) | 200 |
| GET | `/products/import/{id}` | `{id}` = ID příjemky | `List<ListResponse>` | 200 |
| GET | `/products/low-stock` | — | `List<LowStockDto>` | 200 |
| POST | `/products` | `CreateRequest` (sku, name, unit, ceny, minStockLevel) | `DetailResponse` | 201; 422 `INVALID_UNIT` / `DUPLICATE_SKU` |
| PUT | `/products/{id}` | `UpdateRequest` | `DetailResponse` | 200; 422 `INVALID_UNIT` / `DUPLICATE_SKU` |
| DELETE | `/products/{id}` | — | `DetailResponse` (deaktivovaný) | 200; 422 `PRODUCT_HAS_STOCK` |
| POST | `/products/{id}/activate` | — | `DetailResponse` | 200 |
| POST | `/products/{id}/movements` | `StockMovementDto.CreateRequest` (movementType, batchId, quantity, note) | `DetailResponse` | 200; 400 (validace); 404; 422 `QUANTITY_EXCEEDS_REMAINING` / `BATCH_PRODUCT_MISMATCH` |

Deaktivace produktu se zásobou na skladě (`quantity_on_hand > 0`) je zakázaná — 422 `PRODUCT_HAS_STOCK` (parametr `quantityOnHand` v `params`); nejdřív je nutné zásobu vyskladnit (TD-28, analyza-2026-07).

**Tři čísla o zásobě (V83).** `quantityOnHand` je **fyzický stav** — kolik kusů leží v regálu; proti němu se dělá inventura i ocenění skladu. `quantityReserved` je, kolik z toho je slíbeno otevřeným zakázkám a ještě nevydáno; **neukládá se, odvozuje se** z položek zakázek (položka s vazbou na šarži, na neuzavřené zakázce, bez výdejového pohybu), aby vedle pohybového ledgeru nevznikl druhý záznam téhož faktu. `quantityAvailable` = rozdíl obojího a je to jediné číslo, se kterým se dá plánovat.

**Rezervace nikdy nesnižuje fyzický stav** — kdyby do něj vstupovala, hlásila by inventura manko u dílů, které v regálu leží. Naopak `lowStock` i filtr `lowStockOnly` se počítají z **dostupného**: díl slíbený jiné zakázce je pro další práci nedostupný a proti fyzickému stavu by se pod minimem objevil až ve chvíli, kdy fyzicky odejde — tedy pozdě na objednání. Řadit podle dostupného nelze (není ve whitelistu `productSortOrder`).

Podrobně `docs/funkce/rezervace-skladu.md`.

`DetailResponse.reservations[]` rozepisuje rezervace **po zakázkách** (`orderId`, `orderNumber`, `customerName`, `orderStatus`, `quantity`, `reservedAt`; součet odpovídá `quantityReserved`, nejnovější první). Odpovídá na otázku „proč je dostupné míň, než mám v regálu" — obsluha vidí, kdo si díl slíbil, a může se domluvit na přerovnání místo objednávání.

**Ruční skladový pohyb** (`POST /products/{id}/movements`, E2.1/E5a): záporná korekce (`ADJUSTMENT`), odpis (`WRITE_OFF`) nebo vratka dodavateli (`RETURN`) proti konkrétní šarži. `quantity` je kladné, server ho znegatuje; `batchId` a `note` (min 3 znaky) povinné. Jiný typ nebo chybějící pole → 400; šarže nepatřící k produktu → 422 `BATCH_PRODUCT_MISMATCH`; požadavek nad zbytek šarže → 422 `QUANTITY_EXCEEDS_REMAINING`. Stav skladu i zůstatek šarže sníží DB trigger. Kladný přebytek se řeší ruční příjemkou (R-E), ne tímto endpointem. Šarže se zamyká `FOR UPDATE` (souběh, vzor K6).

Povolené typy: `ADJUSTMENT`, `WRITE_OFF`, `RETURN` a `ISSUE` (**interní spotřeba bez zakázky** — `order_id` zůstává prázdné; výdej na zakázku dál běží jen přes import položek do zakázky).

**Pod minimem** (`GET /products/low-stock`, E8.3): aktivní díly s vyplněným `min_stock_level`, kterých je na skladě méně. Vrací i `missingQuantity` a **doporučeného dodavatele** z převodníku `supplier_products` (poslední dodavatel, jeho katalogové číslo, poslední cena). Díl bez záznamu v převodníku se vrátí s prázdnými dodavatelskými poli. Podklad pro objednání — objednávkový modul aplikace nemá (S-9).

U vratky se navíc posílá `returnReason` (**povinný právě a jen u `RETURN`** — validace zrcadlí DB CHECK `chk_return_reason`; chybějící i přebývající důvod → 400) a volitelné `creditNoteNumber` (rovněž jen u vratky). Přijatý dobropis jako samostatný doklad zatím neexistuje — rozhodnutí R-G, fáze E5b.

Měrná jednotka musí patřit do uzavřeného číselníku (`warehouse.import.allowed-units`: ks, l, kg, bal, m, sada, pár) — jinak 422 `INVALID_UNIT` (params `unit`, `allowed`); platná jednotka se ukládá kanonicky („KS" → „ks"). Stejný číselník hlídá completeness gate importu příjemky (klíč `invalidUnits` v `RECEIPT_INCOMPLETE`) — Z-4, analyza-sklad-2026-07.

## Sklad: inventura — `/api/{version}/warehouse/stock-takes` (StockTakeController)

| HTTP | Cesta | Request | Response | Status |
|---|---|---|---|---|
| GET | `/stock-takes` | `StockTakeSearchParams` (page/pageSize/sort; `search` = číslo+poznámka, `status` = filtr stavu) | `PagedResponse<StockTakeDto.ListResponse>` | 200 |
| GET | `/stock-takes/{id}` | — | `DetailResponse` (soupis + rozdíly) | 200; 404 |

U **otevřené** inventury je `currentQuantity` živý stav skladu a `difference` se počítá proti němu.
U **uzavřené** obojí pochází ze sloupců `closed_*` zmrazených při uzavření (V65) — po zaúčtování
korekcí se totiž živý stav rovná napočítanému množství a doklad by vykazoval samé nuly (audit KN-2).
Inventury uzavřené před V65 mají `closed_*` prázdné a chovají se jako dřív.
| POST | `/stock-takes` | `CreateRequest` (note) | `DetailResponse` | 201; 409 `STOCK_TAKE_ALREADY_OPEN` |
| PUT | `/stock-takes/{id}/items` | `ItemsUpdateRequest` | `DetailResponse` | 200; 404; 422 `STOCK_TAKE_NOT_EDITABLE` |
| POST | `/stock-takes/{id}/close` | `CloseRequest` (note) | `DetailResponse` | 200; 422 `STOCK_TAKE_NOT_EDITABLE` / `STOCK_TAKE_SHORTAGE_EXCEEDS_BATCHES` / `STOCK_TAKE_PRICE_MISSING`; 409 `STOCK_TAKE_ALREADY_PROCESSED` |
| POST | `/stock-takes/{id}/cancel` | — | `DetailResponse` | 200; 422 `STOCK_TAKE_NOT_EDITABLE` |

Otevření nasnapshotuje všechny aktivní produkty (`expectedQuantity`) a předvyplní cenu přebytku
z nejnovější šarže dílu. **`countedQuantity = null` znamená „nepočítáno"** — takový řádek se při
uzavření přeskočí (není to nula). Rozdíl se počítá proti **aktuálnímu** stavu (`currentQuantity`),
ne proti snapshotu, aby inventura nepřepsala výdeje proběhlé během počítání.

Uzavření generuje korekce v jedné transakci: manko záporným `ADJUSTMENT` po šaržích **od nejstarší**
(FIFO), přebytek novou šarží v pseudo-příjemce `document_type = STOCK_TAKE` a kladným `ADJUSTMENT`.
Detail a zdůvodnění: `docs/funkce/inventura.md`, rozhodnutí R-H.

Inventura má **číslo dokladu** `stockTakeNumber` = `INV-{rok}-{4 číslice}` (per rok, generuje DB trigger,
V61) — v `ListResponse` i `DetailResponse`. Výpis je **stránkovaný** (`PagedResponse`); řazení přes
whitelist `StockTakeMapper` (`stockTakeNumber`, `status`, `openedAt` [default, sestupně], `closedAt`, `note`).
Filtry (sdílený `searchWhere` → search i countSearch): `search` (LIKE přes `stock_take_number` + `note`)
a `status` (enum `StockTakeStatus`).

## Sklad: ocenění zásob — `/api/{version}/warehouse/stock-valuation` (StockValuationController)

| HTTP | Cesta | Request | Response | Status |
|---|---|---|---|---|
| GET | `/stock-valuation` | — | `StockValuationDto.Response` (`totalValue`, `items[]`) | 200 |

Hodnota skladu v **nákupních cenách bez DPH**, počítaná v DB (view `warehouse.v_stock_valuation`, V42)
jako Σ (zbytek šarže × cena té šarže), zaokrouhleno po šarži. Vychází ze **skutečných pořizovacích cen**
(rozhodnutí R-A) — ne z průměru: dvě šarže téhož dílu za různé ceny přispějí každá svou cenou.
`items[]` obsahuje i aktivní produkty bez zásoby (hodnota 0), aby přehled skladu nezamlčel kartu.

## Dashboard — `/api/{version}/dashboard` (DashboardController)

| HTTP | Cesta | Request | Response | Status |
|---|---|---|---|---|
| GET | `/dashboard/summary` | — | `DashboardDto.Summary` | 200 |
| GET | `/dashboard/statistics?year={rok}` | `year` volitelný (bez něj aktuální rok) | `DashboardDto.Statistics` | 200 |

Jedno agregované volání pro úvodní přehled (BFF vzor) — samé **read-only** počty, součty a krátká
preview nad existujícími daty; nic nemění. `Summary` má šest sekcí:

- **`orders`** — `byStatus` (rozpracované po stavech, bez COMPLETED/CANCELLED), `inProgressTotal`,
  `overdueCount`+`overdue[]` (po odhadovaném termínu `estimated_completion_at`), `readyForPickupCount`+
  `readyForPickup[]`, `toInvoiceCount` (COMPLETED bez nestornované faktury).
- **`invoices`** — `overdueCount`+`overdueTotal`+`overdue[]` (ISSUED po `due_date`), `draftCount`.
- **`warehouse`** — `belowMinimumCount`+`belowMinimum[]` (reuse `LowStockDto`), `stockValue`
  (view V42), `pendingReceiptsCount` (draft PENDING_REVIEW), `openStockTake` (nebo `null`).
- **`vehicles`** — `stkExpiringCount`+`stkExpiring[]` (STK ≤ 30 dní nebo propadlá; čerstvost dle
  posledního snapshotu registru — noční refresh je roadmapa §2.4).
- **`revenue`** — `currentMonth`/`previousMonth` (Σ faktur ISSUED+PAID podle `issue_date`) **minus vystavené dobropisy** podle `issue_date` dobropisu (audit KN-20). Koncept dobropisu se neodečítá.
- **`margin`** — marže z položek **vyfakturovaných** zakázek (`Σ (unit_price − purchase_price) × quantity`),
  `total`/`material`/`labor` × `currentMonth`/`previousMonth` (podle `issue_date` faktury, zrcadlí `revenue`).
  Náklad práce (snímek sazby v `purchase_price` u LABOR, D-3) tuto marži teprve odemyká — dřív šel spočítat
  jen materiál. Položky s **prázdnou** `purchase_price` (neznámý náklad) se vynechávají; `total` zahrnuje
  i ostatní služby, proto může být vyšší než `material + labor`.

Preview seznamy jsou omezené na 5 řádků (řazené podle naléhavosti), počty jsou úplné. Dlaždice na
frontendu prokliká na existující seznamy/detaily — dashboard nic needituje. Detail: `docs/funkce/dashboard.md`.

`Statistics` (modal „Statistika") je měsíční řada zvoleného roku — **počítá se živě, nic se neukládá**
(uložený agregát by byl druhý zdroj pravdy a rozešel by se např. při stornu faktury): `year`,
`availableYears[]` (roky s daty, nejnovější první) a `months[]` — jen měsíce, kde něco je: `month` (1–12),
`revenue` (s DPH; ISSUED+PAID dle `issue_date`, zrcadlí `revenue` ze summary), `margin` (bez DPH; logika
`margin` ze summary), `invoiceCount` (vystavené v měsíci), `orderCount` (zakázky **založené** v měsíci dle
`created_at` — počet vyfakturovaných by kvůli 1:1 faktura↔zakázka kopíroval `invoiceCount`).

## Sklad: dodavatelé — `/api/{version}/warehouse/suppliers` (SupplierController)

RUD bez Create — dodavatel vzniká **výhradně importem PDF faktury**.

| HTTP | Cesta | Request | Response | Status |
|---|---|---|---|---|
| GET | `/suppliers` | `SupplierSearchParams` (search, activeOnly) | `PagedResponse<SupplierDto.ListResponse>` | 200 |
| GET | `/suppliers/{id}` | — | `DetailResponse` | 200 |
| PUT | `/suppliers/{id}` | `UpdateRequest` (name povinné, registrationNumber ≤ 30, vatId ≤ 20, …) | `DetailResponse` | 200 |
| DELETE | `/suppliers/{id}` | — | `DetailResponse` (deaktivovaný) | 200 |
| POST | `/suppliers/{id}/activate` | — | `DetailResponse` | 200 |

## Sklad: příjemky — `/api/{version}/warehouse/goods-receipts` (GoodsReceiptController)

| HTTP | Cesta | Request | Response | Status |
|---|---|---|---|---|
| GET | `/goods-receipts/autocomplete` | `q`, `limit`, `importType` (INVOICE_NUMBER / ORDER_NUMBER) | `AutocompleteResponse` | 200 |
| GET | `/goods-receipts/{id}/items` | — | `List<GoodsReceiptItemDto.Response>` (`quantityRemaining` + `quantityReserved` a `quantityAvailable`) | 200 |

**Nabídka šarží pro import na zakázku nese tři čísla, ne jedno** (2026-08-06). `quantityRemaining` je zbytek šarže v regále, `quantityReserved` díly slíbené otevřeným zakázkám a `quantityAvailable` jejich rozdíl — tedy kolik si lze ještě slíbit. Je to týž rozdíl jako „Skladem" / „Dostupné" na kartě dílu, jen o úroveň níž (šarže místo dílu).

Dokud dotaz vracel jen zbytek, okno výběru nabízelo kusy držené jinou zakázkou: vstup „4" označilo za platný (`max` se řídil zbytkem) a server ho odmítl s `QUANTITY_EXCEEDS_REMAINING` — až po odeslání a s rollbackem **celé dávky**, protože import je jedna transakce. Při víc kolidujících řádcích se navíc chyba hlásí po jedné, takže obsluha objevovala kolize v několika kolech. Rezervace se přitom počítá **týmž SQL fragmentem** (`reservedOnBatch`) jako validace při importu, aby se obě pravidla nemohla rozejít.

**Vyčerpané šarže** (`quantity_remaining = 0`) se dál nenabízejí. Šarže, kde něco leží, ale je to celé rezervované, se naopak vrací — okno ji zobrazí zašedivělou a nevybratelnou, aby obsluha poznala „díl tu není" od „díl tu je, ale je slíbený jinam" a mohla zakázky přerovnat místo objednávání (rozhodnutí uživatele 2026-08-06).

`quantityReserved` a `quantityAvailable` jsou `null` u dotazů, které rezervace nepočítají — „nevím" se záměrně netváří jako „nic není rezervováno".

## Sklad: AI import PDF — `/api/{version}/warehouse/receipts` (GoodsReceiptImportController)

| HTTP | Cesta | Request | Response | Status | Autorizace |
|---|---|---|---|---|---|
| POST | `/receipts/import` | multipart `file` (PDF, limit 10 MB serverem) + `documentType` (INVOICE / DELIVERY_NOTE — volí uživatel) | `ReceiptDraftDto.ImportResponse` (receiptId, documentType, status, documentNumber, supplierName, supplierMatched, reconciliationOk, totalAmount, checks[], items[]) | 201; 400 chybí soubor/typ; 415 není PDF; 409 duplicitní doklad (jen při napárovaném dodavateli); | `@PreAuthorize hasAnyRole('ADMIN','MANAGER','MECHANIC')` (= baseline explicitně) |

Import ukládá **jen draft** (`PENDING_REVIEW` + JSONB `draft_payload`) — nic se nenaskladňuje, dodavatel se nezakládá; materializaci řeší potvrzení příjemky (níže). `checks[]` = výsledky deterministických kontrol (`LINE_MATH`, `LINES_SUM_VS_RECAP`, `RECAP_SUM`, `SUBTOTAL_PLUS_VAT_EQ_TOTAL`, `LINES_SUM_VS_TOTAL`, `ICO_CHECKSUM`, `SUPPLIER_KNOWN`). Každá kontrola má `{code, ok, independent, position}`; **`independent: false` znamená, že kontrola porovnávala hodnotu dopočtenou kódem s tím, z čeho vznikla — tedy neprokázala nic** a její `ok: true` se nesmí číst jako ověření (audit KN-17). `reconciliationOk` je `true`, jen když všechny kontroly prošly **a aspoň jedna aritmetická byla nezávislá**. Detail: `backend.md` §4 + `docs/funkce/import-prijemek.md`.

## Sklad: review workflow příjemek — `/api/{version}/warehouse/receipts` (GoodsReceiptReviewController)

Celý controller `@PreAuthorize hasAnyRole('ADMIN','MANAGER','MECHANIC')`.

| HTTP | Cesta | Request | Response | Status |
|---|---|---|---|---|
| GET | `/receipts` | `ReceiptSearchParams` (page, pageSize, search, status, documentType, dateFrom, dateTo) | `PagedResponse<ReceiptDto.ListResponse>` | 200 |
| POST | `/receipts` | `ReceiptDto.CreateDraftRequest { documentType, supplierId? / supplierName? + supplierRegistrationNumber? }` | `ReceiptDto.DetailResponse` (prázdný MANUAL draft) | 201; 404 neznámý supplierId |
| GET | `/receipts/{id}` | — | `ReceiptDto.DetailResponse` (hlavička + `draft` = kanonický ReceiptDraft se stavy polí) | 200; 404 |
| GET | `/receipts/{id}/pdf` | — | `application/pdf` (inline, originál) | 200; 404 doklad bez PDF |
| PUT | `/receipts/{id}/draft` | tělo = `ReceiptDraft` (editovaný; stavy EDITED nastavuje FE) | `ReceiptDto.DetailResponse` (po přepočtu kontrol) | 200; 404; 422 RECEIPT_NOT_EDITABLE; 409 RECEIPT_ALREADY_PROCESSED |
| POST | `/receipts/{id}/confirm` | — | `ReceiptDto.DetailResponse` | 200; 404; 422 RECEIPT_NOT_EDITABLE / RECEIPT_INCOMPLETE / RECEIPT_DRAFT_MISSING; 409 DUPLICATE_IMPORT / RECEIPT_ALREADY_PROCESSED |
| POST | `/receipts/{id}/cancel` | `CancelRequest` (note — **povinná**) | `ReceiptDto.DetailResponse` | 200; 400 (chybí důvod); 404; 422 RECEIPT_NOT_CANCELLABLE / RECEIPT_ALREADY_USED; 409 RECEIPT_ALREADY_PROCESSED |
| POST | `/receipts/import-isdoc` | multipart `file` (.isdoc/.xml) | `ReceiptDraftDto.ImportResponse` | 201; 400 (nečitelné XML); 415; 422 `ISDOC_UNSUPPORTED_DOCUMENT_TYPE`; 409 DUPLICATE_IMPORT |

Endpoint `/receipts/import` přijímá od E8 kromě PDF i **fotku nebo sken** dokladu (JPG, PNG, HEIC, WebP — rozhodnutí R-D); MIME typ souboru se předává modelu, zbytek pipeline je shodný. Jiný formát → 415.

**ISDOC import** (E7): parser čte český standard e-faktury a plní **tentýž kanonický draft** jako AI
cesta — bez AI, všechna přečtená pole `VERBATIM`. Typ dokladu se nezadává (je v souboru); dobropisy
a vrubopisy se odmítají 422, protože by naskladnily zboží místo odepsání. `source_channel = ISDOC`,
`source_pdf` a `extraction_model` zůstávají prázdné. Detail: `docs/funkce/import-prijemek.md`.
| POST | `/receipts/{id}/reject` | `ReceiptDto.RejectRequest { note ≤500 }` (volitelné tělo) | `ReceiptDto.DetailResponse` | 200; 404; 422 RECEIPT_NOT_EDITABLE; 409 RECEIPT_ALREADY_PROCESSED |

Potvrzení = completeness gate (nic ABSENT na povinných polích, ≥1 položka, vyřešená SUGGESTED párování produktů i rozhodnutí u napárovaných DL referencí) → vyřešení dodavatele (match dle IČO / insert z extrahovaných dat) → dedup → materializace produktů, šarží a pohybů `RECEIPT` (sklad navýší DB trigger; řádky kryté `LINKED` dodacím listem se přeskočí) → `CONFIRMED`. Zamítnutí nematerializuje nic a uvolní číslo dokladu (partial unique index V39). Přechody stavů jsou guarded (`WHERE status='PENDING_REVIEW'`) — souběh → 409. Draft se v detailu i PUT přenáší přímo jako `ReceiptDraft` (je to už serializační model JSONB payloadu — paralelní DTO strom by strukturu jen dubloval).

## Číselníky — `/api/{version}/code-lists` (CodeListController)

| HTTP | Cesta | Response | Status |
|---|---|---|---|
| GET | `/code-lists/roles` | `List<RoleDto>` | 200 |

Role, které lze **přiřadit účtu** — ne všechny řádky `security.roles`. Filtruje se podle
`SecurityConfig.WORKING_ROLES` (ADMIN/MANAGER/MECHANIC), takže `ROLE_CUSTOMER` a `ROLE_READONLY`
se nenabízejí: baseline `/api/**` je odřízne a účet s nimi by dostal 403 na každé obrazovce
(audit KN-22). V databázi zůstávají.

## Uživatelé (admin) — `/api/{version}/users` (UserController)

Admin CRUD nad `security.users` — přihlašovací účty a přiřazení rolí. **`@PreAuthorize hasRole('ADMIN')`** na celém controlleru — jediné ADMIN-only místo v API (účetní a správní operace jsou vyhrazené vedení, viz §Autorizace rolí). Sebeobslužná změna vlastního hesla je v `/auth/change-password`, ne zde.

| HTTP | Cesta | Request | Response | Status |
|---|---|---|---|---|
| GET | `/users` | `UserSearchParams` (search, roleId, activeOnly, sortBy) | `PagedResponse<UserDto.ListResponse>` | 200 |
| GET | `/users/{id}` | — | `UserDto.DetailResponse` (vč. rolí, `lastLoginAt`, `failedLoginAttempts`) | 200 |
| POST | `/users` | `CreateRequest` {username 3–20, email, password ≥ 8, roleIds[] neprázdné} | `DetailResponse` | 201; 409 `USER_ALREADY_EXISTS` |
| PUT | `/users/{id}` | `UpdateRequest` {email, roleIds[]} (username needitovatelné) | `DetailResponse` | 200; 422 `DUPLICATE_EMAIL` |
| DELETE | `/users/{id}` | — | `DetailResponse` (deaktivovaný — `enabled=FALSE`) | 200; 422 `CANNOT_DEACTIVATE_SELF` / `CANNOT_DEACTIVATE_LAST_ADMIN` |
| POST | `/users/{id}/activate` | — | `DetailResponse` | 200 |
| POST | `/users/{id}/reset-password` | `ResetPasswordRequest` {newPassword ≥ 8} | `DetailResponse` | 200 |

`enabled` je pro tuto tabulku ekvivalentem `is_active` u ostatních modulů (žádný soft-delete sloupec navíc). Guardy v `UserServiceImpl.deactivate` brání zamčení aplikace: nelze deaktivovat vlastní účet ani posledního uživatele s rolí `ROLE_ADMIN`.

---

## Chybové odpovědi

`GlobalExceptionHandler` (`@RestControllerAdvice`) vrací RFC 9457 **ProblemDetail** + rozšíření `errors[]`:

```json
{
  "status": 422, "title": "Unprocessable Entity",
  "detail": "…", "instance": "/api/v1/customers/5",
  "errors": [ { "field": "ico", "code": "DUPLICATE_REGISTRATION_NUMBER", "message": "…", "params": { } } ]
}
```

| Výjimka | HTTP | Kód |
|---|---|---|
| `MethodArgumentNotValidException` | 400 | dle anotace: `REQUIRED`, `SIZE_EXCEEDED`, `INVALID_EMAIL`, `INVALID_PATTERN`, `VALUE_TOO_SMALL/LARGE`, jinak `INVALID_VALUE`; vlastní class-level validátory (`CUSTOM_VALIDATOR_ANNOTATIONS`, dnes jen `@ValidCustomerRequest`) nesou kód přímo v šabloně: `CUSTOMER_NAME_REQUIRED`, `CUSTOMER_COMPANY_REQUIRED` (TD-10) |
| `IllegalArgumentException` | 400 | `INVALID_ARGUMENT` — neplatný argument předaný do servisní vrstvy (typicky `null` identifikátor u null guardů, TD-20); zpráva z `ex.getMessage()` |
| `BadCredentialsException` | 401 | `BAD_CREDENTIALS` (stejná zpráva pro jméno i heslo — prevence enumerace) |
| `LockedException` | 401 | `ACCOUNT_LOCKED` — účet uzamčen po 10 neúspěšných přihlášeních (V3b). Zámek **vyprší** po `lockout.duration` (výchozí 15 min, V64/KN-5); admin reset hesla odemyká okamžitě |
| `UsernameNotFoundException` | 401 | `ACCOUNT_UNAVAILABLE` — deaktivovaný (nebo smazaný) účet s ještě platným access tokenem; dřív padalo do catch-all jako 500, nejvíc na `/auth/refresh`, které FE po 401 automaticky opakuje (KN-18) |
| `InvalidRefreshTokenException` | 401 | `INVALID_REFRESH_TOKEN` |
| `AccessDeniedException` | 403 | `ACCESS_DENIED` |
| `ResourceNotFoundException` | 404 | `RESOURCE_NOT_FOUND` (params: resourceName, resourceId) |
| `UserAlreadyExistsException` | 409 | `USER_ALREADY_EXISTS` |
| `ConflictException` | 409 | vlastní `code` (např. `DUPLICATE_IMPORT` u duplicitního importu faktury, `INVOICE_STATE_CHANGED` u souběžné změny stavu faktury) |
| `BusinessRuleException` | 422 | vlastní `ruleCode` (např. `ORDER_ALREADY_INVOICED`, `SUPPLIER_NOT_EXTRACTED`, `CANNOT_DEACTIVATE_SELF`, `CANNOT_DEACTIVATE_LAST_ADMIN`, `DUPLICATE_EMAIL`, `INVALID_CURRENT_PASSWORD`, `INVALID_EMPLOYEE_DATES`/`DUPLICATE_EMPLOYEE_USER` u `/employees` — datum odchodu před nástupem, resp. login už drží jiný zaměstnanec, `EMPLOYEE_ONLY_ON_LABOR` u položky zakázky — mechanik jen k typu LABOR (D-2), `CUSTOMER_NAME_REQUIRED`/`CUSTOMER_COMPANY_REQUIRED` u `PUT /customers/{id}` — TD-10, typ zákazníka je immutable a kontroluje se podle záznamu v DB, `EMPTY_ADDRESS_SET` u `PUT /customers/{id}` — prázdný seznam adres by full-replace smazal celou sadu; `null` = neměnit, KN-15, `SUPPLIER_INACTIVE` u potvrzení příjemky — doklad je od deaktivovaného dodavatele, KN-16, `DELIVERY_NOTE_LINK_NOT_APPLICABLE` u potvrzení příjemky — volbu „pouze provázat" nelze uplatnit, protože nejde určit, které řádky faktury dodací list kryje, KN-4a, `INVOICE_ALREADY_CREDITED` u `POST /credit-notes` — k faktuře už aktivní opravný doklad existuje, KN-8, `INVOICE_NOT_DELETABLE` u `DELETE /invoices/{id}` — vystavenou/zaplacenou fakturu nelze smazat, opravuje se dobropisem, KN-1; `CREDIT_NOTE_NOT_DELETABLE` u `DELETE /credit-notes/{id}` — vystavený opravný doklad má číslo řady OD a maže se taky nesmí) |
| `DataIntegrityViolationException` | 422 | `DATA_INTEGRITY_VIOLATION` |
| ostatní | 500 | `INTERNAL_ERROR` (bez technických detailů) |
| `RegistryUnavailableException` | 503 | `REGISTRY_RATE_LIMITED` / `REGISTRY_AUTH_FAILED` / `REGISTRY_TIMEOUT` / `REGISTRY_ERROR` — registr vozidel nedostupný |
| `AresUnavailableException` | 503 | `ARES_RATE_LIMITED` / `ARES_TIMEOUT` / `ARES_ERROR` — ARES nedostupný |

401 z filtru/entry pointu (mimo `GlobalExceptionHandler`, běží před `DispatcherServlet`): `SecurityProblemWriter` sestavuje **stejný** ProblemDetail tvar ručně (V5, analyza-2026-07) — `Content-Type: application/problem+json;charset=UTF-8`:

| Zdroj | Kód |
|---|---|
| `SecurityConfig` entry point (chybějící/neplatná autentizace) | `UNAUTHORIZED` |
| `JwtAuthenticationFilter` — token na blacklistu (logout) | `TOKEN_BLACKLISTED` |
| `JwtAuthenticationFilter` — expirovaný token | `TOKEN_EXPIRED` |
| `JwtAuthenticationFilter` — neplatný/nezparsovatelný token | `TOKEN_INVALID` |
| `JwtAuthenticationFilter` — účet mezitím deaktivován (KN-18) | `ACCOUNT_UNAVAILABLE` |

Výjimky z jednotného formátu:
- `GoodsReceiptImportController` používá `ResponseStatusException` (400/415) pro validaci uploadu.

Frontend chyby čte vzorem `err.problem?.detail ?? fallback` — `api.js` parsuje RFC 9457 ProblemDetail do `ApiError.problem`; fallback vždy česky.

---

## Souhrn počtů (pro křížovou kontrolu)

| Controller | Endpointů |
|---|---|
| AuthController | 5 |
| CodeListController | 1 |
| CompanyProfileController | 2 |
| OpeningHoursController | 2 |
| CustomerController | 8 |
| CustomerAresController | 1 |
| DashboardController | 2 |
| InvoiceController | 21 |
| InvoiceDocumentController | 1 |
| CreditNoteController | 6 |
| CashReceiptController | 8 |
| MileageController | 4 |
| OrderController | 8 |
| AppointmentController | 10 |
| OrderItemController | 9 |
| VehicleController | 7 |
| VehicleRegistryController | 3 |
| EmployeeController | 6 |
| GoodsReceiptController | 2 |
| GoodsReceiptImportController | 2 |
| GoodsReceiptReviewController | 8 |
| ProductController | 9 |
| StockTakeController | 6 |
| StockValuationController | 1 |
| SupplierController | 5 |
| UserController | 7 |
| **Celkem** | **144** |
