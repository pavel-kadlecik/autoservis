# 01 — Backend jádro: zákazník → vozidlo → zakázka

> Audit 2026-07-30 · rozsah: A1 (zákazník, adresy, kontaktní osoby, vozidlo, tachometr, registr/STK)
> a A2 (zakázka od přijetí po uzavření: stavy, položky, marže, zámek fakturou, rušení) ·
> metoda: čtení celých souborů (service → converter → DTO → mapper XML → migrace → test → docs),
> u každého nálezu STŘEDNÍ+ druhé adversariální čtení proti kódu, DB CHECK/triggerům a testům.
> Read-only průchod — nic mimo tento soubor nebylo změněno.

## Co bylo přečteno

**Java — service:**
`service/impl/CustomerServiceImpl.java`, `VehicleServiceImpl.java`, `OrderServiceImpl.java`,
`OrderItemServiceImpl.java`, `MileageServiceImpl.java`, `VehicleRegistryServiceImpl.java`,
`service/AddressSetValidator.java`, `client/VehicleRegistryClientImpl.java`,
`service/impl/InvoiceServiceImpl.java` (jen část `createFromOrder`, řádky 55–144, kvůli vazbě na zakázku).

**Java — controller:**
`CustomerController.java`, `VehicleController.java`, `OrderController.java`, `OrderItemController.java`,
`MileageController.java`, `VehicleRegistryController.java`, `EmployeeController.java`.

**Java — converter:**
`CustomerConverter.java`, `VehicleConverter.java`, `OrderConverter.java`, `OrderItemConverter.java`,
`OrderItemSummaryConverter.java`, `AddressConverter.java`, `RegistryConverter.java`.

**Java — DTO / doména / enumy:**
`dto/customer/CustomerDto.java`, `dto/customer/AddressDto.java`, `dto/customer/CustomerSearchParams.java`,
`dto/vehicle/VehicleDto.java`, `dto/vehicle/VehicleSearchParams.java`, `dto/order/OrderDto.java`,
`dto/order/OrderItemDto.java`, `dto/order/OrderItemSummaryDto.java`, `dto/order/OrderSearchParams.java`,
`dto/pagination/SearchParams.java`, `domain/order/Order.java`, `domain/order/OrderItem.java`,
`domain/order/OrderItemSummary.java`, `enums/OrderStatus.java`.

**Mappery XML:**
`CustomerMapper.xml`, `VehicleMapper.xml`, `OrderMapper.xml`, `OrderItemMapper.xml`, `AddressMapper.xml`,
`ContactPersonMapper.xml`, `MileageHistoryMapper.xml`, `RegistrySnapshotMapper.xml`.

**Migrace:**
V2, V4, V5, V6, V7, V9, V10, V11, V12, V19, V20, V22, V23, V24, V25, V26, V27, V38, V56, V59, V62, V63,
`db/demo/V47`, `db/prod/V60`.

**Testy (ke kontrole, zda nález nevyvracejí):**
`OrderConverterTest.java` (celý), výpis `@DisplayName` z `OrderCrudServiceTest`, `OrderItemServiceTest`,
seznam `CustomerCrudServiceTest` testů k adresám.

**Dokumentace:**
`CLAUDE.md`, `docs/konvence.md`, `docs/tech-dluhy.md`, `docs/funkce/zakazky-marze.md`,
`docs/funkce/zakazky-prehled.md`, `docs/funkce/zamestnanci.md`, `docs/api.md` (§Zákazníci, Vozidla,
Tachometr, Registr, Zakázky, Položky zakázky), relevantní řádky `docs/databaze.md`,
`frontend/…/src/help/zakazky.md`.

**Frontend (jen kvůli ověření dopadu backendových polí, ne jako předmět auditu):**
`pages/OrdersPageDetail.jsx`, `components/OrderItemsSummary.jsx`, `api/customerPayload.js`,
`pages/CustomersPageDetail.jsx`, `components/OrderItemsWrapper.jsx`.

## Shrnutí

Jádro je celkově v dobrém stavu: konvence R-01…R-14 se drží (SQL jen v XML, plně kvalifikované tabulky,
verify-and-fetch, `created_by` ze SecurityContextu, null guardy, ruční konvertory), zaokrouhlení marže
je řešeno v DB pohledu nad `numeric` a nemíchá bez DPH s DPH. Nejslabším místem je **zakázka jako doklad**:
na rozdíl od faktury (`InvoiceStatus.canTransitionTo`) nemá `OrderStatus` žádný stavový automat, takže
zakázku s vystavenou fakturou lze přes `PUT` přepnout na `CANCELLED`, a zrušení zakázky nijak neřeší už
vydaný materiál. Druhá slabina je kolem zákazníka: `PUT /customers/{id}` s prázdným polem `addresses`
smaže celou adresní sadu (tvrdý DELETE) a časová razítka souhlasů se při zakládání plní chybně
(marketingový souhlas nemá datum vůbec, GDPR datum se plní i bez souhlasu).

Celkem **11 nálezů**: 0 kritických, 0 vysokých, **4 střední**, **7 nízkých**. Žádný nález není opakováním
položky z `tech-dluhy.md`.

---

## Nálezy

### [J-1] Zakázka nemá stavový automat — zakázku s vystavenou fakturou lze přepnout na CANCELLED
**Severita:** 🟠 STŘEDNÍ
**Jistota:** OVĚŘENO
**Kde:** `src/main/java/cz/palo/autoservis/service/impl/OrderServiceImpl.java:109-121`
(+ `src/main/java/cz/palo/autoservis/model/converter/OrderConverter.java:94`
`existingOrder.setStatus(updateRequest.getStatus());`
+ `src/main/java/cz/palo/autoservis/model/dto/order/OrderDto.java:98-99`
+ protipól: `src/main/java/cz/palo/autoservis/model/enums/InvoiceStatus.java:23-28`
(`ALLOWED_TRANSITIONS` + `canTransitionTo`)
+ přiznání v kódu: `src/main/java/cz/palo/autoservis/service/impl/InvoiceServiceImpl.java:74-75`
„Zakázka nemá vlastní stavový automat, proto aspoň tvrdý zákaz nejnesmyslnějšího případu.")

**Co je špatně:** `OrderServiceImpl.update` načte zakázku, zavolá `orderConverter.applyUpdate` a uloží.
Mezi tím neproběhne **žádná** kontrola — ani povolenosti přechodu stavu, ani existence faktury.
`OrderStatus` (`enums/OrderStatus.java`) je holý enum bez mapy povolených přechodů, na rozdíl od
`InvoiceStatus`. Guard `requireOrderNotInvoiced` existuje jen v `OrderItemServiceImpl` (položky),
hlavičky zakázky se netýká. Ověřeno i na úrovni DB: na `"order".orders` jsou pouze triggery
`trg_orders_updated_at` (V6:64) a `trg_generate_order_number` (V11:25/V56) — žádný guard.
`OrderController.update` nemá `@PreAuthorize`, takže to smí kterákoli pracovní role.

**Scénář selhání:**
1. `POST /api/v1/orders` → zakázka ZAK-2026-0007 (RECEIVED).
2. Přidat položky, `POST /api/v1/invoices` z této zakázky, `POST /invoices/{id}/issue` → faktura **ISSUED**
   (má přidělené číslo, je to daňový doklad).
3. `PUT /api/v1/orders/7` s tělem `{"status":"CANCELLED","description":"…"}` → **200 OK**.

Výsledek: zakázka je „Zrušena", ale existuje na ni vystavená (případně i zaplacená) faktura.
`OrderMapper.countOpenByCustomerId` (`OrderMapper.xml:229-235`) ji přestane počítat, takže jde
deaktivovat i zákazníka. Filtr „po termínu" (`OrderMapper.xml:78-81`) ji vyřadí. V seznamu zakázek
se ve sloupci „Faktura" pořád zobrazí „Vystavena" (join na `billing.invoices`), takže řádek sám sobě
odporuje. Stejnou cestou jde `COMPLETED → RECEIVED` i `CANCELLED → IN_PROGRESS`.

**Proč to vadí:** účetní nekonzistence (stornovaná práce s platným daňovým dokladem), rozvolnění
workflow dílny a obcházení vazby, kterou fakturační modul brání jen jedním směrem
(`InvoiceServiceImpl.java:76-81` zakazuje **fakturovat** stornovanou zakázku, ale ne stornovat
fakturovanou). Není to ztráta dat ani peněz — proto ne 🔴.

**Návrh řešení:** doplnit `OrderStatus.canTransitionTo` podle vzoru `InvoiceStatus` (min. `COMPLETED`
a `CANCELLED` jako terminální) a v `OrderServiceImpl.update` před zápisem ověřit přechod + zavolat
sdílený guard „zakázka s nestornovanou fakturou nesmí do CANCELLED" (stejný dotaz jako
`requireOrderNotInvoiced`, jen s jinou hláškou). Kterou množinu přechodů povolit je věcí provozu —
viz Otevřené otázky.

---

### [J-2] `PUT /customers/{id}` s prázdným polem `addresses` natvrdo smaže všechny adresy zákazníka
**Severita:** 🟠 STŘEDNÍ
**Jistota:** OVĚŘENO
**Kde:** `src/main/java/cz/palo/autoservis/model/dto/customer/CustomerDto.java:119-121`
(`@Valid @Size(max = 2) private List<AddressDto.CreateRequest> addresses;` — **bez `@NotEmpty`**,
na rozdíl od `CreateRequest` na řádku 66)
+ `src/main/java/cz/palo/autoservis/service/AddressSetValidator.java:25-27`
(`if (addresses == null || addresses.isEmpty()) { return; // Handled by @NotEmpty }`)
+ `src/main/java/cz/palo/autoservis/service/impl/CustomerServiceImpl.java:144-153`
+ `src/main/resources/mapper/AddressMapper.xml:48-50` (`DELETE FROM customer.addresses WHERE customer_id = …`)

**Co je špatně:** `CustomerServiceImpl.update` větví jen na `addressRequests != null`. Prázdný seznam
tedy projde do `addressSetValidator.validate([])`, který se kvůli komentáři „Handled by `@NotEmpty`"
okamžitě vrátí — jenže `@NotEmpty` je jen na `CreateRequest`, ne na `UpdateRequest`. Následuje
`addressMapper.deleteByCustomerId(id)` a prázdný insert cyklus. Kontrola „právě jedna BILLING adresa"
(`AddressSetValidator.java:29-35`) se na prázdnou sadu nikdy nedostane.

**Scénář selhání:**
1. Zákazník 5 má fakturační a kontaktní adresu.
2. `PUT /api/v1/customers/5` s tělem `{"firstName":"Jan","lastName":"Novák","addresses":[]}` → **200 OK**.
3. `customer.addresses` pro zákazníka 5 je prázdná (tvrdý `DELETE`, ne soft-delete — data jsou nenávratně pryč).
4. `POST /api/v1/invoices` pro tohoto zákazníka skončí **404 „Adresa"**
   (`InvoiceServiceImpl.java:97-99` — `billingAddressId` už neexistuje). Zákazníka nelze fakturovat,
   dokud někdo adresu nezadá znovu.

**Druhé čtení:** frontend tuto cestu nevyrobí — `customerPayload.js:94` vždy vloží aspoň BILLING adresu.
Test na prázdný seznam neexistuje (`CustomerCrudServiceTest` pokrývá `null` = neměnit a neprázdnou sadu,
řádky 145–174). Jde tedy o díru v API kontraktu, ne o chybu viditelnou v UI — proto 🟠, ne 🔴.

**Proč to vadí:** nevratná ztráta dat proti pravidlu R-06 (mazání) a zablokovaná fakturace zákazníka.
Navíc je to jediný způsob, jak dostat zákazníka do stavu „nemá fakturační adresu", který jinak celý
model nepředpokládá.

**Návrh řešení:** buď `@NotEmpty` na `CustomerDto.UpdateRequest.addresses` (400 před vstupem do service),
nebo — čistěji, protože pravidlo patří do service (R-13) — v `AddressSetValidator.validate` rozlišit
`null` (neřeším) od prázdného seznamu (`INVALID_BILLING_ADDRESS_COUNT`, 422). Doporučuji obojí.

---

### [J-3] Časová razítka souhlasů se při zakládání zákazníka plní chybně (evidence GDPR)
**Severita:** 🟠 STŘEDNÍ
**Jistota:** OVĚŘENO (mechanika) / právní váha = rozhodnutí uživatele
**Kde:** `src/main/resources/mapper/CustomerMapper.xml:270-285` (fragment `insertCommonValues`:
řádek 276 `#{marketingConsentAt},`, řádek 278 natvrdo `NOW(),` pro `gdpr_consent_at`)
+ `src/main/java/cz/palo/autoservis/model/converter/CustomerConverter.java:84-106`
(`toDomain` nastaví `marketingConsent`, ale **nikdy** `marketingConsentAt`)
+ `src/main/resources/db/migration/V2__init_customer_schema.sql:53-56`
+ `src/main/resources/mapper/CustomerMapper.xml:337-339` (UPDATE razítko posune jen při **změně** hodnoty)

**Co je špatně:** dvě zrcadlové chyby v jednom INSERTu.
1. `marketing_consent_at` se plní z `#{marketingConsentAt}`, které v Javě nikdo nenastaví
   (grep přes `src/main` a `src/test`: pole se jen čte v resultMapu `CustomerMapper.xml:32`) → při
   zakládání je vždy `NULL`, i když `marketing_consent = TRUE`.
2. `gdpr_consent_at` je natvrdo `NOW()` bez ohledu na hodnotu `gdpr_consent` → i zákazník, který
   souhlas **nedal**, má v DB datum „udělení souhlasu".

**Scénář selhání:**
1. `POST /api/v1/customers` s `{"gdprConsent": false, "marketingConsent": true, …}`.
2. V DB: `gdpr_consent = FALSE`, `gdpr_consent_at = 2026-07-30 10:00` (nesmyslné datum souhlasu, který
   neexistuje), `marketing_consent = TRUE`, `marketing_consent_at = NULL` (chybí datum souhlasu, který existuje).
3. Razítko marketingu se doplní až v okamžiku, kdy někdo hodnotu **překlopí** (UPDATE, `CASE WHEN …
   IS DISTINCT FROM …`). Dokud se souhlas nezmění, zůstane `NULL` navždy.

**Proč to vadí:** `marketing_consent_at` je jediná evidence, **kdy** zákazník marketingový souhlas dal —
GDPR čl. 7 odst. 1 vyžaduje, aby správce byl schopen souhlas doložit. Zároveň `gdpr_consent_at`
u nesouhlasícího zákazníka je aktivně zavádějící údaj. Pole nejsou v žádném DTO, takže si toho v UI
nikdo nevšimne — chyba je tichá.

**Návrh řešení:** v `insertCommonValues` nahradit obě hodnoty podmíněnými:
`CASE WHEN #{marketingConsent} THEN NOW() END` a `CASE WHEN #{gdprConsent} THEN NOW() END`.
U `gdpr_consent_at` to vyžaduje migraci `V{n+1}` na `NULL`-ovatelný sloupec (dnes `NOT NULL DEFAULT NOW()`),
nebo — pokud sloupec má znamenat „datum zápisu karty", ne „datum souhlasu" — přejmenování/komentář a
doplnění samostatného razítka. Která varianta je správná, závisí na tom, jak se souhlasy v servisu sbírají
(viz Otevřené otázky).

---

### [J-4] Zrušení zakázky nijak neřeší už vydaný materiál ze skladu
**Severita:** 🟠 STŘEDNÍ
**Jistota:** OVĚŘENO (chování) / NÁVRHOVÉ (co má být správně) — **rozhodnutí uživatele**
**Kde:** `src/main/java/cz/palo/autoservis/service/impl/OrderServiceImpl.java:111-121`
(přechod na `CANCELLED` = prostý UPDATE, žádná další akce)
+ jediné místo, kde vzniká vratka: `src/main/java/cz/palo/autoservis/service/impl/OrderItemServiceImpl.java:269-283`
(`MovementType.ISSUE_RETURN` jen v `delete(id, userId)`)
+ ověřeno grepem `ISSUE_RETURN` přes `src/main/java` — jen `MovementType.java:7` a uvedený řádek
+ nápověda slibuje jen: `frontend/autoservis-frontend/src/help/zakazky.md`
(„Zakázka se **nemaže** — když z opravy sešlo, dáte stav **Zrušena**." a „Když smažete položku,
která pocházela ze skladu, díl se vrátí zpět na sklad.")

**Co je špatně:** zrušení zakázky a vrácení materiálu jsou dvě zcela nespojené operace. Systém obsluhu
na nesoulad neupozorní a nic ji nenutí položky nejdřív smazat.

**Scénář selhání:**
1. Zakázka ZAK-2026-0007, `POST /orders/7/items/import-from-receipt` — 4 ks brzdových destiček
   ze šarže (vznikne pohyb `ISSUE −4`, `quantity_remaining` šarže klesne o 4).
2. Zákazník opravu odvolá → obsluha podle nápovědy nastaví stav **Zrušena**.
3. Destičky fyzicky leží zpátky v regálu, ale sklad je pořád eviduje jako vydané na **zrušenou** zakázku.
   Ocenění skladu i inventura z nich vycházejí chybně a rozdíl se objeví až při fyzické inventuře.

**Proč to vadí:** trvalý rozjezd mezi fyzickým a evidenčním stavem skladu, a to na místě, kde uživatele
dokumentace aktivně vede („dáte stav Zrušena"). Dopad je tím větší, čím dražší díl.

**Návrh řešení — varianty (volba majitele servisu):**
- **(a) Blokovat** přechod do `CANCELLED`, dokud má zakázka položky se `goods_receipt_item_id`
  (422 s hláškou „nejdřív vraťte materiál na sklad"). Nejmenší zásah, nutí k vědomému rozhodnutí.
- **(b) Automaticky vrátit** — při `CANCELLED` vygenerovat `ISSUE_RETURN` pro všechny šaržové položky.
  Pohodlné, ale **špatně** v případě, kdy díl už byl namontován a zákazník jen odmítl platit.
- **(c) Nechat, jen varovat** v UI a v nápovědě.

Doporučení: **(a)** — je to jediná varianta, která nehádá, co se s dílem fyzicky stalo, a přitom
nedovolí tichý rozjezd. Rozhodnutí je ale na uživateli; souvisí i s tím, jestli má zrušená zakázka
vůbec zůstávat s položkami.

---

### [J-5] `OrderDto.DetailResponse.vehicleId` se nikdy nenaplní → odkaz na vozidlo na detailu zakázky nefunguje
**Severita:** 🟡 NÍZKÝ
**Jistota:** OVĚŘENO
**Kde:** `src/main/java/cz/palo/autoservis/model/converter/OrderConverter.java:31-57`
(`toDetailResponse` nastaví `setCustomerId` na řádku 41, `setVehicleId` **nikde**)
+ `src/main/java/cz/palo/autoservis/model/converter/OrderConverter.java:109-135`
(`toListResponse` nenastaví **ani** `customerId`, **ani** `vehicleId`)
+ pole existují: `src/main/java/cz/palo/autoservis/model/dto/order/OrderDto.java:22-23` (ListResponse)
a `:48-49` (DetailResponse)
+ data z DB jsou k dispozici: `src/main/resources/mapper/OrderMapper.xml:18-19` (resultMap) a `:45`
(`o.customer_id, o.vehicle_id` ve fragmentu `orderColumns`)
+ důsledek: `frontend/autoservis-frontend/src/pages/OrdersPageDetail.jsx:102-104`
+ testová slepá skvrna: `src/test/java/cz/palo/autoservis/model/converter/OrderConverterTest.java:143-160`
(vyjmenovává všechna pole DetailResponse, `getCustomerId()` ověřuje na řádku 146, `getVehicleId()` ne)

**Co je špatně:** doména `Order` má `vehicleId` naplněné, DTO ho deklaruje, mapper ho čte — jen konvertor
ho zapomněl přenést. Přesně ten typ chyby, na který upozorňuje `konvence.md §15` („vynechání se projeví
až za běhu, ne při kompilaci"), jen na vrstvě konvertoru místo XML.

**Scénář selhání:** otevřít detail zakázky → v kartě „Zákazník a vozidlo" je jméno zákazníka proklik,
ale vozidlo jen jako prostý text. FE se degraduje tiše (`order.vehicleId ? <a …> : text`), takže
funkce prostě nikdy nefunguje a nikdo nedostane chybu. V JSON odpovědi `GET /orders/{id}` je
`"vehicleId": null`; v `GET /orders` je `null` i `customerId` i `vehicleId` u každého řádku.

**Proč to vadí:** rozbitá navigace na hlavní pracovní obrazovce; navíc dvě nepoužitá `null` pole
v seznamovém DTO (R-12).

**Návrh řešení:** doplnit `response.setVehicleId(order.getVehicleId());` do `toDetailResponse`
a `setCustomerId`/`setVehicleId` do `toListResponse` (nebo, pokud je seznam nepotřebuje, obě pole
z `ListResponse` smazat). Rozšířit `OrderConverterTest` o assert na `vehicleId`.

---

### [J-6] `orderId` v cestě položky zakázky se vůbec nekontroluje
**Severita:** 🟡 NÍZKÝ
**Jistota:** OVĚŘENO
**Kde:** `src/main/java/cz/palo/autoservis/controller/OrderItemController.java:39-42`, `:50-53`,
`:56-59`, `:69-80`, `:108-113`, `:136-142` (base path `/api/{version}/orders/{orderId}`)
+ `src/main/java/cz/palo/autoservis/service/impl/OrderItemServiceImpl.java:72-79` (`getById(id)` —
parametr `orderId` se do service vůbec nepředá), `:105-117` (`create` — existenci zakázky neověří),
`:236-250` (`update`), `:260-286` (`delete`), `:88-96` (`getSummaryByOrderId`)
+ protipříklad ve stejné codebase: `src/main/java/cz/palo/autoservis/service/impl/MileageServiceImpl.java:184-191`
(`requireReadingOfVehicle` — „čtení jiného vozidla se pod touto cestou tváří jako nenalezené")

**Co je špatně:** položka zakázky je vnořený resource, ale service pracuje jen s `id` položky.
`orderId` z cesty se používá pouze u `create` (nastavení `orderId` na položce), `importFromReceipt`
a `reorder` — a ani tam se neověřuje, že zakázka existuje.

**Scénář selhání (dvě varianty):**
- **Cizí zakázka:** položka 5 patří zakázce 1. `DELETE /api/v1/orders/999/items/5` → **204** a položka
  zakázky 1 je smazána (včetně vratky na sklad). Zakázka 999 nemusí vůbec existovat. Totéž pro
  `GET` a `PUT`. Zámek fakturou obejít nejde — `requireOrderNotInvoiced` čte skutečné
  `existingOrderItem.getOrderId()` (`OrderItemServiceImpl.java:244`, `:267`) — takže o účetní škodu nejde.
- **Neexistující zakázka:** `POST /api/v1/orders/999/items` s validním tělem projde přes
  `requireOrderNotInvoiced(999)` (faktura nenalezena → OK) a spadne až na FK
  `order_items.order_id` → **422 `DATA_INTEGRITY_VIOLATION`** místo čistého **404**.
  `GET /api/v1/orders/999/items` vrátí **200 `[]`**, `GET /api/v1/orders/999/items/summary` vrátí
  **200 s nulovým souhrnem** (`OrderItemSummary.zero`) — obojí předstírá existující prázdnou zakázku.

**Proč to vadí:** porušuje R-13 (business validace patří do service → čistá výjimka) a dělá z vnořené
cesty lež. Klient se zastaralým `orderId` tiše zasáhne jinou zakázku. V modulu tachometru je stejný
problém vyřešen správně — je to nekonzistence uvnitř projektu.

**Návrh řešení:** předat `orderId` do `getById`/`update`/`delete` a ověřit
`item.getOrderId().equals(orderId)` → `ResourceNotFoundException` (vzor `requireReadingOfVehicle`);
v `create`/`getByOrderId`/`getSummaryByOrderId`/`reorder` doplnit `orderMapper.findById(orderId)
.orElseThrow(…)` (nebo `existsById`).

---

### [J-7] Deaktivace zákazníka zneaktivní i jeho vozidla, reaktivace je nevrátí
**Severita:** 🟡 NÍZKÝ
**Jistota:** OVĚŘENO
**Kde:** `src/main/java/cz/palo/autoservis/service/impl/CustomerServiceImpl.java:166-181`
(`deactivate` → `vehicleService.deactivateByCustomerId(id)` na řádku 177)
vs. `src/main/java/cz/palo/autoservis/service/impl/CustomerServiceImpl.java:188-192`
(`activate` — pouze `customerMapper.activate(id)`, žádná kaskáda zpět)
+ `src/main/resources/mapper/VehicleMapper.xml:267-271` (`deactivateByCustomerId`)
+ důsledky: `src/main/resources/mapper/CustomerMapper.xml:199` (`LEFT JOIN vehicle.vehicles v …
AND v.is_active = TRUE`) a `src/main/resources/mapper/VehicleMapper.xml:145` (strict `findById`)

**Co je špatně:** kaskáda je jednosměrná. Nikde se navíc neeviduje, které vozidlo bylo zneaktivněno
kaskádou a které ručně, takže symetrickou reaktivaci ani nelze udělat přesně.

**Scénář selhání:**
1. Zákazník 5 má 3 aktivní vozidla, žádnou otevřenou zakázku.
2. `DELETE /api/v1/customers/5` → zákazník i všechna 3 vozidla `is_active = FALSE`.
3. Omyl → `POST /api/v1/customers/5/activate` → **200**, zákazník je aktivní.
4. `GET /api/v1/customers/5` vrátí **prázdný seznam vozidel** (join filtruje `is_active = TRUE`),
   `GET /api/v1/vehicles/{id}` vrátí **404** (strict `findById`) a na zákazníka nelze založit zakázku
   (`OrderServiceImpl.create:88-89` používá strict `vehicleMapper.findById`).
5. Náprava jen ručně: seznam vozidel s `activeOnly=false` a `POST /vehicles/{id}/activate` pro každé.

**Proč to vadí:** provozně nepříjemná past — karta zákazníka po reaktivaci vypadá prázdná a obsluha
nemá z detailu zákazníka cestu, jak vozidla vrátit. Data se neztrácejí, proto 🟡.

**Návrh řešení:** buď doplnit symetrickou kaskádu do `activate` (jednoduše reaktivovat všechna vozidla
zákazníka — přijatelné, pokud se ručně deaktivovaná vozidla nepovažují za častý případ), nebo kaskádu
z `deactivate` odstranit a spolehnout se na to, že vozidlo neaktivního zákazníka se stejně nedá použít.
Volba je věcí provozu (viz Otevřené otázky).

---

### [J-8] `CustomerSearchParams.customerType` a `city` se nikde nepoužívají — filtr tiše nefunguje
**Severita:** 🟡 NÍZKÝ
**Jistota:** OVĚŘENO
**Kde:** `src/main/java/cz/palo/autoservis/model/dto/customer/CustomerSearchParams.java:31-32`
(`private CustomerType customerType; private String city;`)
+ `src/main/resources/mapper/CustomerMapper.xml:158-179` (fragment `searchWhere` používá jen
`params.activeOnly` a `params.searchTokens`; grep `customerType`/`city` v celém souboru vrací jen
resultMapy, alias `addr_city` a INSERT — žádné použití v `WHERE`)
+ dokumentace je přesto nabízí: `docs/api.md:55`
(„`CustomerSearchParams` (search, **customerType, city**, activeOnly, sortBy)")

**Co je špatně:** dvě mrtvá pole DTO (R-12), navíc inzerovaná v API dokumentaci jako funkční filtry.

**Scénář selhání:** `GET /api/v1/customers?customerType=COMPANY` vrátí **všechny** zákazníky včetně
fyzických osob — bez chyby, bez varování. Klient (nebo AI agent čtoucí `api.md`) filtr použije a dostane
tiše špatný výsledek.

**Proč to vadí:** tichý nesprávný výsledek je horší než chyba; a je to přesně vzor, který v tomto
projektu už jednou bolel (TD-46 — `sortDesc` přijímaný a ignorovaný).

**Návrh řešení:** buď doplnit obě větve do `searchWhere` (`city` přes `EXISTS` na `customer.addresses`),
nebo pole smazat a opravit `api.md:55`. Vzhledem k tomu, že filtr nikdo nepoptává, doporučuji smazat.

---

### [J-9] Nepovinné `position` u položky zakázky je fakticky povinné — DB DEFAULT je nedosažitelný
**Severita:** 🟡 NÍZKÝ
**Jistota:** OVĚŘENO
**Kde:** `src/main/java/cz/palo/autoservis/model/dto/order/OrderItemDto.java:68-69`
(`@PositiveOrZero private Short position;` — bez `@NotNull`) a `:124-125` (`ReorderRequest.position`, totéž)
+ `src/main/resources/mapper/OrderItemMapper.xml:63-72` (`position` je vždy v seznamu sloupců
s hodnotou `#{position}`) a `:94-106` (`reorder` — `WHEN #{item.id} THEN #{item.position}`)
+ `src/main/resources/db/migration/V12__init_order_item_schema.sql:21`
(`position SMALLINT NOT NULL DEFAULT 0`) + `V23__change_order_item_position_default_value.sql`
(`SET DEFAULT 1`)

**Co je špatně:** MyBatis pošle explicitní `NULL`, takže se DB default nikdy neuplatní a padne
`NOT NULL` constraint. Migrace V23, která default nastavuje, je tím pádem mrtvá.

**Scénář selhání:** `POST /api/v1/orders/1/items` s tělem bez klíče `position`
(`{"itemType":"LABOR","name":"Diagnostika","quantity":1,"unit":"hod","unitPrice":600,"vatRate":21}`)
→ Bean Validation projde (pole je nepovinné) → `INSERT … position = NULL` →
`null value in column "position" violates not-null constraint` → **422 `DATA_INTEGRITY_VIOLATION`**
s technickou hláškou z DB. Totéž `PUT /orders/1/items/reorder` s `[{"id":5}]`.
Frontend to nezpůsobí — `OrderItemsWrapper.jsx:196` dosazuje `items.length + 1`.

**Proč to vadí:** porušuje R-13 (surová chyba z DB místo čisté 400/422 ze service) a DTO kontrakt lže
o nepovinnosti pole.

**Návrh řešení:** buď `@NotNull` na obě pole (kontrakt = povinné), nebo — konzistentněji s tím, co už
`importFromReceipt` dělá (`OrderItemServiceImpl.java:187`:
`orderItemMapper.findMaxPositionByOrderId(orderId) + 1`) — doplnit stejný fallback do `create`
a `applyUpdate` zachovat stávající pozici, když přijde `null`.

---

### [J-10] Kontaktní osoby jsou jen ke čtení — v produkci bude sekce vždy prázdná
**Severita:** 🟡 NÍZKÝ
**Jistota:** OVĚŘENO
**Kde:** neexistuje žádný controller ani service pro kontaktní osoby (výpis
`src/main/java/cz/palo/autoservis/controller/` — 15 controllerů + adresář `warehouse`, žádný
`ContactPersonController`); `src/main/resources/mapper/ContactPersonMapper.xml` obsahuje **pouze**
`resultMap`, žádný `insert`/`update`/`delete` (Java rozhraní bylo smazáno v TD-32)
+ data přesto putují ven: `src/main/java/cz/palo/autoservis/model/dto/customer/CustomerDto.java:171`
(`List<ContactPersonDto.Response> contactPersons`)
+ UI sekci vykresluje: `frontend/autoservis-frontend/src/pages/CustomersPageDetail.jsx:173-178`
+ jediný zdroj dat je demo seed `db/demo/V3__seed_initial_data.sql` (produkční `db/prod/V60` kontakty neseeduje)

**Co je špatně:** tabulka `customer.contact_persons` je v produkci naplnitelná jedině přímým SQL.
Ve vývojovém prostředí funkce vypadá hotově (demo data ji ukazují), v produkci bude karta trvale prázdná.

**Scénář selhání:** firemní zákazník „Logistika s.r.o." — obsluha chce zapsat, na koho volat
(dispečer, jméno, telefon). V detailu zákazníka je karta „Kontaktní osoby", ale žádné tlačítko
„Přidat"; v API není endpoint. Údaj skončí v `internalNote` nebo nikde.

**Proč to vadí:** mezera ve funkci u firemních zákazníků, kterou UI i DTO navenek slibují.
Pro dílnu, která pracuje hlavně s firmami, je to citelné.

**Návrh řešení:** buď doplnit CRUD (`ContactPersonMapper` + service + `POST/PUT/DELETE
/customers/{id}/contact-persons`, jako u adres full-replace v rámci `PUT /customers/{id}`),
nebo sekci z detailu zákazníka skrýt a funkci zapsat do `roadmapa.md`. Jestli je funkce potřeba,
je věcí provozu (viz Otevřené otázky).

---

### [J-11] `docs/api.md` popisuje na třech místech chování, které kód nemá
**Severita:** 🟡 NÍZKÝ
**Jistota:** OVĚŘENO
**Kde:**
1. `docs/api.md:60` — „`PUT /customers/{id}` | `CustomerDto.UpdateRequest` (bez customerType
   **a addresses**)" × `src/main/java/cz/palo/autoservis/model/dto/customer/CustomerDto.java:119-121`
   (pole `addresses` v `UpdateRequest` **je**, doplnil ho TD-42, který je jinde v dokumentaci
   označen jako vyřešený).
2. `docs/api.md:72` — „`VehicleSearchParams` (search, activeOnly, **orderBy**, stkExpiring)" ×
   `src/main/java/cz/palo/autoservis/model/dto/vehicle/VehicleSearchParams.java` (žádné pole `orderBy`;
   podle `tech-dluhy.md` TD-46 byl „smazán mrtvý `orderBy` z Vehicle/Order params").
3. `docs/api.md:125` — „Import ze skladu vytváří pohyb `ISSUE`, **mazání/snížení množství** pohyb
   `ISSUE_RETURN`." × `src/main/java/cz/palo/autoservis/model/converter/OrderItemConverter.java:92-102`
   (u položky se `goodsReceiptItemId` je `quantity` **uzamčená** — `PUT` ji nezmění vůbec)
   a `src/main/java/cz/palo/autoservis/service/impl/OrderItemServiceImpl.java:269-283`
   (`ISSUE_RETURN` vzniká **jen** při `delete`).
   Dále `docs/api.md` u `/items/summary` uvádí „(labor/material/service/total, net+gross)" — chybí
   čtyři pole `*Cost` přidaná V63.

**Co je špatně:** dokumentace je v tomto projektu primární vstup pro vývojáře i pro AI agenta
(CLAUDE.md „Mapa dokumentace"). Tři nepravdivá tvrzení v jedné oblasti.

**Scénář selhání:** vývojář/agent podle bodu 1 nedoplní `addresses` do klienta a znovu „objeví" TD-42;
podle bodu 3 očekává, že snížení množství vrátí zboží na sklad, a postaví na tom skladovou logiku,
která se nikdy nespustí.

**Proč to vadí:** dokumentace je podle `CLAUDE.md` závazná a má se po každé změně aktualizovat —
tady se to nestalo.

**Návrh řešení:** opravit tři řádky v `api.md` a doplnit `*Cost` pole k `/items/summary`.

---

## Co bylo ověřeno jako v pořádku

**Zákazník / adresy**
- Podmíněná povinnost jméno vs. firma je řešena dvojkolejně správně: `@ValidCustomerRequest` (400)
  na `CreateRequest` a `requireNameOrCompanyPresent` proti typu z DB (422) v update
  (`CustomerServiceImpl.java:221-238`) — typ zákazníka je immutable, `UpdateRequest` ho nemá.
- `CustomerMapper.update` je full-replace a razítka souhlasů posouvá jen při skutečné změně hodnoty
  (`CustomerMapper.xml:335-339`) — u **update** je logika správná (problém J-3 je jen v insertu).
- TD-23 (`Boolean` = „null neměnit") a TD-42 (full-replace adresní sady v transakci) jsou skutečně
  implementované tak, jak dokumentace tvrdí.
- `AddressSetValidator` pro neprázdnou sadu vynucuje právě jednu BILLING a nejvýš jednu CONTACT
  a doplňuje to partial unique index `uq_addresses_default_per_type` (V2:114-116).
- Autocomplete zákazníka nenabízí deaktivované (`CustomerMapper.xml:378-380`) — nelze na ně založit zakázku.
- Fulltext přes tokeny (TD-18/TD-25) je v `CustomerMapper.xml:170-177` i `OrderMapper.xml:87-101`
  implementovaný přes `CONCAT` s foreach-itemem, tedy bez pasti s `<bind>`; komentáře to vysvětlují.
- `CustomerMapper.findById` je záměrně lenient (komentář `CustomerMapper.xml:185-189`) — souhlasí s TD-08.
- TD-56 („duchový zákazník") je skutečně opraven: `customerColumnsForVehicle` aliasuje `cust_*`
  a `VehicleResultMap` má `columnPrefix="cust_"`; `VehicleServiceImpl.update:157-159` čte majitele
  z přímého `getCustomerId()`.

**Vozidlo / tachometr / registr**
- VIN je hlídán třikrát (DTO `@Pattern`, `existsByVin` v service → čistá 422 `DUPLICATE_VIN`,
  DB CHECK `chk_vehicles_vin_format` + `uq_vehicles_vin`).
- Rok výroby vs. první registrace: service validace (`VehicleServiceImpl.java:245-256`) i DB CHECK
  (`V7`) — a `NULL` v obou hodnotách CHECK korektně propouští.
- `VehicleServiceImpl.create` je `@Transactional` a počáteční stav tachometru se ukládá jako
  `INITIAL` do historie, ne přímo do cache (`VehicleServiceImpl.java:112-123`).
- Cache `current_mileage_km` (V20) i `stk_valid_until`/`wheels` (V38/V62) plní **výhradně** DB triggery
  plným přepočtem (léčí i UPDATE/DELETE); aplikace tyto sloupce nikde nezapisuje — ověřeno
  v `VehicleMapper.xml` INSERT i UPDATE.
- Pravidlo „INITIAL jen jako první čtení, needitovatelné na jiné čtení, nesmazatelné"
  (`MileageServiceImpl.java:71-78`, `:111-118`, `:152-158`) je konzistentní.
- Čtení tachometru cizího vozidla se pod cestou `/vehicles/{vehicleId}/mileage/{readingId}` tváří
  jako 404 (`MileageServiceImpl.java:184-191`) — správný vzor pro vnořený resource.
- Registr: HTTP volání je záměrně **mimo** transakci (komentář `VehicleRegistryServiceImpl.java:26-34`),
  po založení vozidla je best-effort (`VehicleController.java:88`), klient všechny selhání převádí
  na `RegistryUnavailableException` → 503 (`VehicleRegistryClientImpl.java:54-80`, `:95-127`),
  parsování je defenzivní (nerozpoznaná hodnota = `null`, nikdy uhodnutá).
- `raw_response` se ukládá jako JSONB s explicitním castem a čte přes `::text` — bez TypeHandleru,
  ale konzistentně (`RegistrySnapshotMapper.xml:31-35`, `:52`).

**Zakázka / položky / marže**
- Vazba zakázka↔vozidlo↔zákazník při zakládání je hlídaná (`OrderServiceImpl.java:86-95`,
  `VEHICLE_NOT_OWNED_BY_CUSTOMER`), `UpdateRequest` `customerId`/`vehicleId` vůbec neobsahuje
  a `OrderConverter.applyUpdate` je nesahá — pokryto testem
  `OrderConverterTest.applyUpdate_doesNotTouchCustomerOrVehicle`.
- Změna majitele vozidla **nepoškodí historii**: zakázka drží vlastní `customer_id`
  (`OrderMapper.xml:45`, `:224`) a faktura má plný snapshot vozidla i strany
  (`InvoiceServiceImpl.java:112-134`, V33/V34/V50) — historické doklady se nepřepisují.
- Číslo `ZAK-` se resetuje per rok, má advisory lock a LPAD overflow guard (V56) — TD-57 je opravdu vyřešen.
- Zámek položek fakturou (`requireOrderNotInvoiced`) pokrývá `create`, `importFromReceipt`, `update`
  i `delete`; `reorder` je z něj vyňat záměrně (jen pořadí zobrazení) — souhlasí s `api.md`.
- `OrderItemMapper.reorder` má `AND order_id = #{orderId}` (`OrderItemMapper.xml:105`), takže
  přeskládat cizí položky nelze.
- Výdej ze skladu při importu z příjemky je odolný: `distinct` id, `FOR UPDATE` zámek šarží,
  agregace požadovaného množství per šarže před kontrolou (`OrderItemServiceImpl.java:136-176`) — K6.
- Snapshot sazby mechanika: jen u `LABOR` (service guard + DB CHECK V59), zapisuje se jen když
  `purchasePrice` chybí, jednou zapsaný se nepřepočítává (`OrderItemServiceImpl.java:349-366`) —
  odpovídá D-2/D-3/D-6 v `docs/funkce/zamestnanci.md`.
- Šaržová položka má zamčené `quantity`, `unit`, `vatRate`, `itemType` i `purchasePrice`
  (`OrderItemConverter.java:92-102`) — nelze rozjet množství proti skladovému pohybu.
- **Marže**: počítá se v DB pohledu nad `numeric` (žádný `double`), zaokrouhlení `ROUND(…, 2)`
  **po řádku** shodně pro `line_net`, `line_vat` i `*_cost` (V25:18-19, V63:33-36); dělitel `100.0`
  je v Postgresu `numeric`, takže výpočet DPH je přesný. Bez DPH a s DPH se nemíchají — náklad
  i marže vycházejí výhradně z `net`. FE dopočítává jen rozdíl dvou už zobrazených čísel
  (`OrderItemsSummary.jsx:17-19`); JS `Number` se tam používá pro zobrazení, ne pro uložení,
  a hodnota se stejně formátuje na 2 desetinná místa — pro účel „podklad pro ceníkování" je to v pořádku.
- `OrderItemSummary.zero()` zaručuje, že prázdná zakázka vrací nuly, ne `null` (N-01).
- Žádná service metoda v rozsahu nevrací `null` místo `Optional`/výjimky (N-01);
  null guardy na `Long` parametrech (TD-20) jsou doplněny všude, kde konvence žádá.
- `@Transactional` je na všech vícekrokových mutacích v rozsahu (`Customer.create/update/deactivate`,
  `Vehicle.create/update/deactivate`, `Order.update`, `OrderItem.create/importFromReceipt/update/
  delete/reorder`, `Mileage.*`).
- TD-67 je skutečně dotažen: `OrderResultMap` `is_active` nemapuje a komentář to vysvětluje
  (`OrderMapper.xml:33-34`), zbylé `is_active = TRUE` guardy jsou neškodné no-opy.
- Číslování zákazníků v produkci: `db/prod/V60:54` resetuje `customer_number_seq` na 1, takže
  první produkční zákazník dostane `ZNK-{rok}-0001` (dřívější obava z mezery po `START WITH 4` neplatí).
- Rolová autorizace v rozsahu odpovídá `konvence.md §19`: (de)aktivace zákazníka a vozidla je
  ADMIN/MANAGER, mutace zaměstnanců taky, čtení a běžná editace je baseline.

---

## Otevřené otázky pro uživatele

1. **Jaké přechody stavu zakázky mají být povolené?** (nutné pro opravu J-1.) Návrh: dopředné
   přechody volně, `COMPLETED` a `CANCELLED` terminální, s možností vrátit `COMPLETED → IN_PROGRESS`
   (reklamace) pro ADMIN/MANAGER. Má být zakázka s **nestornovanou** fakturou úplně zamčená proti
   změně stavu, nebo jen proti `CANCELLED`?

2. **Co se má stát s materiálem vydaným na zrušenou zakázku?** (J-4.) Blokovat zrušení, dokud jsou
   na zakázce skladové položky (varianta a), vracet automaticky (b), nebo jen varovat (c)?
   Odpověď závisí na tom, jak často se ruší zakázka, kde už byl díl namontován.

3. **Sběr souhlasů — kdy a jak?** (J-3.) Znamená `gdpr_consent_at` „kdy zákazník podepsal souhlas",
   nebo „kdy jsme kartu založili"? Pokud první, potřebuje sloupec migraci na nullable. Sbíráte
   souhlas papírově s vlastním datem (pak by mělo jít o vstupní pole), nebo vždy v okamžiku zadání do systému?

4. **Reaktivace zákazníka a jeho vozidla** (J-7): má reaktivace zákazníka automaticky vrátit i všechna
   jeho vozidla, nebo má obsluha vybírat po jednom? Automatika je pohodlnější, ale vrátí i vozidlo,
   které bylo deaktivované samostatně (prodané, zrušené) ještě před deaktivací zákazníka.

5. **Kontaktní osoby** (J-10): potřebuje servis evidovat kontaktní osoby firemních zákazníků?
   Pokud ano, jde o samostatnou funkci (CRUD + UI); pokud ne, patří sekce z detailu zákazníka pryč.

6. **Filtry zákazníků podle typu a města** (J-8): chybí, nebo se jen nikdy nedodělaly? Podle odpovědi
   se buď doimplementují, nebo pole i řádek v `api.md` zmizí.

7. **Monotonie tachometru** (mimo nálezy, ale za zvážení): nové čtení může být nižší než předchozí
   a nic to nehlásí. Je to záměr (výměna přístrojové desky, oprava překlepu), nebo má systém
   na pokles upozornit?

---

## Přesahy do jiných průchodů

- **Konvence R-08** (`if` vždy se složenými závorkami) je porušena i mimo můj rozsah — nejvíc
  v `service/impl/ReceiptReviewServiceImpl.java` (17 míst: řádky 333, 438, 439, 451, 535, 536, 544,
  545, 546, 552, 558, 605, 613, 614, 615, 689, 700) a `service/impl/StockTakeServiceImpl.java`
  (6 míst: 76, 79, 80, 180, 181, 202); v mém rozsahu jde o `VehicleRegistryServiceImpl.java:125`,
  `client/VehicleRegistryClientImpl.java:83-85`, `:135` a `model/converter/RegistryConverter.java:52-59`,
  `:70`, `:72`, `:92`, `:102`. Celkem cca 40 výskytů — patří do průchodu o konvencích/úklidu.
- **Faktura**: `InvoiceServiceImpl.createFromOrder` končí `IllegalStateException` (→ 500), když není
  nakonfigurovaný `billing.company_profile` (řádky 137-138), místo čitelné 422 — patří do
  fakturačního průchodu. (Kontrola „zakázka nemá položky" naopak existuje, řádek 161-163
  `ORDER_HAS_NO_ITEMS`.)
- **Sklad**: `registry_status` (`VARCHAR(100)`) a `country_code` (`CHAR(2)`) nemají v DTO omezení délky,
  takže delší hodnota končí surovou DB chybou 22001 — obecnější vzor pro průchod o validacích.
- **Frontend**: `OrderItemsSummary.jsx` má nepoužitý prop `bold` u `MarginRow`; `OrderForm.jsx`
  a `OrdersPageDetail.jsx` závisí na polích z J-5.
