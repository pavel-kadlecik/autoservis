# plan-testy.md — Plán vyčerpávajícího pokrytí testy (TD-14)

> Cíl: systematické pokrytí všech vrstev a modulů, ne pár testů navíc. Uzavření **TD-14**.
> Laťka: **žádné plané aserce** — neprázdná fixtura, aserce na konkrétní hodnotu, každý test
> musí umět chytit chybu. Pokrytí měříme **větvově** (JaCoCo), planost dokazujeme **mutačně** (PIT)
> u `service` a `security`.
>
> Styl: rychlý **unit** bez Spring kontextu, kde jde (logika, konvertory, enumy); `@SpringBootTest`
> + `AbstractIntegrationTest` (singleton container) jen kde test potřebuje DB nebo web vrstvu.
> Principal přes `.with(user(appUserDetails))`, ne `@WithMockUser`.

## Výchozí stav (2026-07-23)

30 testovacích tříd, **152 testů**, suite zelená. Žádné nástroje pro měření pokrytí (JaCoCo/PIT
v `pom.xml` nejsou → pokrytí dnes nikdo neměří).

### Co už je pokryté (needuplikovat)

| Oblast | Třída |
|---|---|
| Import PDF full-stack (201 + DUPLICATE_IMPORT) | `WarehouseImportServiceTest` |
| Review workflow (přechody, storno, měna) | `ReceiptReviewServiceTest` |
| Draft pipeline | `DraftAssemblerTest`, `DraftVerificationServiceTest`, `ProductMatchingServiceTest`, `IsdocImportTest` |
| Sklad | `StockTakeTest`, `StockValuationTest`, `ManualStockMovementTest`, `LowStockTest`, `ProductUnitValidationTest`, `ProductDeactivationTest` |
| Faktura — jen `issue` + guarded updateStatus | `InvoiceStatusTransitionTest` |
| Řazení + whitelist ORDER BY | `ListSortingTest` |
| Security — lockout, blacklist, 401 tvar | `LoginLockoutTest`, `TokenBlacklistTest`, `SecurityProblemWriterTest` |
| Registr vozidel | `VehicleRegistryServiceTest`, `VehicleRegistryClientTest`, `RegistryConverterTest` |
| Ostatní | `NullGuardTest`, `CustomerValidationTest`, `CustomerServiceTest` (jen getById), `OrderSearchTest`, `OrderItemImportTest`, `OrderItemInvoiceLockTest`, `WarehouseImportPropertiesTest`, `GlobalExceptionHandlerTest`, `AutoservisApplicationTests` |

### Díry (nula dedikovaného pokrytí)

- **Service:** `UserServiceImpl` (admin guardy CANNOT_DEACTIVATE_SELF/LAST_ADMIN, DUPLICATE_EMAIL,
  reset hesla), `MileageServiceImpl`, `CompanyProfileServiceImpl`, `SupplierServiceImpl`,
  `VehicleServiceImpl` (mimo registr — create/update/deactivate/activate), `InvoiceDocumentServiceImpl`
  (PDF/QR/SPAYD), `OrderServiceImpl` (create/update/status), `CustomerServiceImpl` (create/update/
  deactivate/reactivate), `ProductServiceImpl` (DUPLICATE_SKU, movements QUANTITY_EXCEEDS/BATCH_MISMATCH, FIFO).
- **Stavový automat faktury:** `pay`, `cancel`, terminální stavy, `ORDER_ALREADY_INVOICED`,
  editace položek jen v DRAFT — **nepokryté** (jen `issue`).
- **Security hloubka:** refresh **rotace + reuse detection** (revokeAll), `JwtService`,
  `AppUserDetailsService`, self-service `changePassword` (INVALID_CURRENT_PASSWORD).
- **Konvertory:** 16 z 17 bez testu (jen `RegistryConverter` pokrytý).
- **Web vrstva:** systematický MockMvc/ProblemDetail kontrakt (status + `code` + `errors[]`)
  per controller — dnes jen `WarehouseImportServiceTest` jde přes MockMvc.
- **DB triggery:** čtení reálně vygenerované hodnoty (ZAK-, ZNK-, číslo faktury, `updated_at`,
  `current_mileage_km`, `quantity_on_hand`) — dnes neověřováno explicitně.
- **Nástroje:** JaCoCo (větvové pokrytí + práh), PIT (mutace `service`/`security`).

---

## Fáze (rozdělení, priorita, unit vs. integrační)

Po **každé** fázi: `./mvnw test` zelená + delta JaCoCo + poznámka k přeživším mutantům + report.

### T0 — Nástroje měření (žádné nové testy) · 🔴 první

- JaCoCo plugin do `pom.xml` (`prepare-agent` + `report` v `test` fázi, zatím **bez prahu** —
  jen baseline report `target/site/jacoco`).
- PIT plugin (`pitest-maven` + `pitest-junit5-plugin`), `targetClasses` omezené na
  `cz.palo.autoservis.service.*` a `cz.palo.autoservis.security.service.*`, mutátory STARTER/DEFAULTS.
  **Od 2026-07-31 (audit KN-21) navíc `model.converter.*` a `model.enums.*`** — konvertory a enumy
  jsou tam, kde žijí stavové automaty a přepisy hodnot z formulářů, tedy třída chyb typu KN-9
  (nulová sazba DPH tiše na 21 %). Historická čísla u fází T1–T8 níže byla měřena v původním
  rozsahu; nové spuštění dá jiné (větší) jmenovatele.
  Spouští se ručně (`./mvnw test-compile org.pitest:pitest-maven:mutationCoverage`), ne v každém `test` běhu.
- **Deliverable T0:** baseline branch-coverage číslo + potvrzení, že PIT proběhne. Prah JaCoCo
  se **nastaví až v T8**, až bude co splnit.

### T1 — Konvertory a čistá logika · unit (bez Spring) · 🟠 nejvyšší ROI — ✅ HOTOVO 2026-07-23

**Výsledek:** 17 nových testovacích tříd, suite 152 → **333 testů** (zelená). Branch coverage
`model.converter` **48,1 % → 94,2 %**, celkem 53,6 % → 60,6 %. PIT nad T1 třídami:
**625/625 zabitých (100 %)**, žádný přeživší.

**Poučení (proč mutační testování není formalita):** první PIT běh dal jen **77 %** a 139 přeživších
odhalilo dvě systematické díry v testech, které JaCoCo ukazovalo jako plně pokryté řádky:
1. `applyUpdate(...)` se volal bez aserce na návratovou hodnotu → mutant „return null" přežil.
   Opraveno asercí `isSameAs(existing)` u všech konvertorů.
2. V `toListResponse`/`toDetailResponse` netvrdily testy všechna mapovaná pole → mutant
   „removed call to setXxx" přežil. Opraveno rozšířením asercí na kompletní sadu polí.

Cestou doplněn i `RegistryConverterTest` (existující): hranice `parsePowerKw` (0, Short.MAX_VALUE,
přetečení int) a `parseDate` (délka 10) + mapování `toLookupResponse`/`toSnapshot`/`toSnapshotResponse`.

Rychlé, deterministické, chytají regrese v mapování. Neprázdná fixtura, aserce na konkrétní pole.

- 16 konvertorů (`Customer`, `Vehicle`, `Order`, `OrderItem`, `Invoice`, `InvoiceItem`
  (+`fromOrderItem`), `Supplier`, `User`, `Mileage`, `Address`, `ContactPerson`, `CompanyProfile`,
  `Receipt`, `GoodsReceiptItem`, `OrderItemSummary`, `WarehouseProduct`): `toDomain`,
  `applyUpdate` (částečný update — null pole nechá stávající, viz TD-23 Boolean), `toResponse`.
  Dokázat, že konvertor **nepřenáší** `createdBy`/timestamps.
- `InvoiceStatus`: `canTransitionTo` pro **všechny** kombinace (matice 4×4 — povolené uspějí,
  zakázané selžou), `isEditable` jen DRAFT.
- `SupplierNormalizer`: trim, odstranění mezer (vč. ` `), prázdné → null, prefix CZ/SK se
  **nestrhává**.
- `TokenHasher.sha256Hex`: známý vstup → známý hex (stabilita hashe).

### T2 — Service CRUD díry · integrační · 🟠 — ✅ HOTOVO 2026-07-23

**Výsledek:** 8 nových tříd (`UserServiceTest`, `VehicleServiceTest`, `MileageServiceTest`,
`SupplierServiceTest`, `CompanyProfileServiceTest`, `CustomerCrudServiceTest`,
`OrderCrudServiceTest`, `ProductCrudServiceTest`), suite 333 → **473 testů** (zelená).
Branch coverage `service.impl` **46,5 % → 67,3 %**, celkem 68,2 % → 69,8 %.
PIT: User/Mileage/Supplier/CompanyProfile **97 % (test strength 100 %)**,
Vehicle/Order/Customer/Product **89 %**.

**Nálezy (produkční kód nezměněn, čeká na rozhodnutí):**
1. 🔴 **`OrderMapper.xml` nečte `is_active`** — fragment `orderColumns` sloupec nevybírá
   a `OrderResultMap` ho nemapuje, takže **každá** zakázka jde do API s `active=false`
   (detail i seznam). Data v DB jsou správně. Dokumentováno testem
   `OrderCrudServiceTest.detailResponse_activeFlag_isAlwaysFalse_knownDefect`, který po opravě
   spadne. Oprava: doplnit `o.is_active` do `orderColumns` + `<result property="active"
   column="is_active"/>` do `OrderResultMap`.
2. 🟡 **`SupplierMapper.update` má PATCH sémantiku** — dynamické `<if test="x != null">`
   znamená, že jednou vyplněné IČO/IBAN/e-mail už nejde přes API vymazat. Odlišné od ostatních
   modulů (full-replace). Zachyceno testem `update_blankRegistrationNumber_keepsStoredValue`.
3. 🟡 **`VehicleConverter` sahá na `vehicle.getCustomer()` bez null kontroly** — vozidlo bez
   načteného majitele shodí request na NPE (dnes nenastane, mappery majitele JOINují).

**Poučení:** PIT odhalil, že `CompanyProfileServiceTest` byl **planý** — ověření „načti znovu přes
service" uvnitř jedné transakce vrací kvůli lokální cache SqlSession **tentýž objekt**, který
service právě zmutovala, takže test prošel i s odstraněným `mapper.update()`. Opraveno čtením
přes `JdbcTemplate` mimo MyBatis.

**Nepokryté mutanty (zdůvodnění):** `lambda$fetchOrFail` ve Vehicle/Customer/Order/Mileage/Supplier
— defenzivní `IllegalStateException` „záznam zmizel mezi UPDATE a SELECT" uvnitř jedné transakce;
nedosažitelné bez umělého zásahu do DB zvenčí. `autocomplete` „subtraction → addition" je
**ekvivalentní mutant**: při `hasMore` je `size == limit+1`, takže `Math.min(size-1, limit)`
i `Math.min(size+1, limit)` dají shodně `limit`.

### T2 — původní zadání fáze

Šťastná cesta + každá `BusinessRuleException`/`ResourceNotFoundException`; soft-delete
(deaktivace **i** reaktivace); audit `created_by` ze serveru (ne z DTO).

- **UserServiceImpl:** create (+ USER_ALREADY_EXISTS), update email/role (+ DUPLICATE_EMAIL),
  deactivate guardy **CANNOT_DEACTIVATE_SELF** a **CANNOT_DEACTIVATE_LAST_ADMIN** (obě větve:
  poslední admin selže, předposlední projde), activate, resetPassword (odemkne účet — návaznost
  na lockout), `replaceRoles`.
- **VehicleServiceImpl:** create (createdBy ze serveru), update (VIN immutable — v UpdateRequest
  není), deactivate/activate, getById 404, `deactivateByCustomerId` kaskáda.
- **MileageServiceImpl:** create/update/delete; **trigger** `current_mileage_km` (viz T6).
- **SupplierServiceImpl:** RUD (bez create), update povinné `name`, deactivate/activate.
- **CompanyProfileServiceImpl:** singleton get + update (name povinné).
- **OrderServiceImpl:** create (ZAK- číslo z triggeru, viz T6), update status/ceny,
  customerId/vehicleId immutable.
- **CustomerServiceImpl:** create INDIVIDUAL i COMPANY, update, deactivate/reactivate,
  permissive `findById` (TD-08 — detail deaktivovaného jde otevřít).
- **ProductServiceImpl:** create (+ DUPLICATE_SKU, INVALID_UNIT), movements QUANTITY_EXCEEDS_REMAINING
  a BATCH_PRODUCT_MISMATCH, **FIFO** výdej po šaržích (od nejstarší), kompenzační ISSUE_RETURN.

### T3 — Stavové automaty (obě větve) · integrační · 🔴 — ✅ HOTOVO 2026-07-23

**Výsledek:** 2 nové třídy (`InvoiceLifecycleTest` 33 testů, `StockTakeStateMachineTest` 13),
suite 473 → **519 testů** (zelená). `service.impl` 67,3 % → **69,7 %**, celkem 70,8 %.
PIT `InvoiceServiceImpl` **91 % (test strength 98 %)**, kombinovaně s inventurou 82 %.

**Nález (produkční kód nezměněn):** 🔴 **`PUT /invoices/{id}` obchází stavový automat.**
`InvoiceServiceImpl.update` se snaží stav ochránit řádkem `updated.setStatus(existingInvoice.getStatus())`,
jenže `applyUpdate` mutuje objekt **na místě a vrací tutéž referenci** — `updated` i `existingInvoice`
jsou jeden objekt, který v tu chvíli už nese stav z requestu. Přiřazení je **no-op**, takže z DRAFT
lze skočit rovnou na PAID bez `canTransitionTo` i bez guardovaného UPDATE (K5). PIT to potvrdil
nezávisle: mutant „removed call to `Invoice::setStatus`" na řádku 180 **přežije**, protože je to
mrtvý kód. Dokumentováno testem `update_canBypassStateMachine_knownDefect`, který po opravě spadne.
Oprava: zapamatovat si stav před `applyUpdate`, nebo `status` z `UpdateRequest` vůbec nečíst.

**Pokryto:** faktura — všechny povolené přechody (DRAFT→ISSUED→PAID, DRAFT/ISSUED→CANCELLED),
oba terminální stavy proti všem třem operacím, `ORDER_ALREADY_INVOICED`, `ORDER_HAS_NO_ITEMS`,
`ADDRESS_NOT_OWNED_BY_CUSTOMER`, zámek hlavičky i položek mimo DRAFT, zmrazené strany dokladu
(dodavatel z profilu firmy včetně IBAN, odběratel včetně IČO/DIČ u firmy, SPZ vozidla).
Inventura — invariant „nejvýš jedna otevřená" včetně uvolnění po uzavření i po zrušení, oba
terminální stavy proti všem operacím, počítadla řádků, naskladnění přebytku.

**Nepokryté mutanty (zdůvodnění):** exception-suppliery `fetchOrFail`/`fetchItemOrFail`/
`requireEditableForItem` a `lambda$createFromOrder$1` (zákazník zakázky) — nedosažitelné, chrání je
FK a jediná transakce. `buildDetail:442` (`orderId == null`) — přes service nelze vytvořit fakturu
bez zakázky. `requireOpen` negace se týká jen **znění české hlášky**, ne chování.

### T3 — původní zadání fáze

U každého automatu: **povolený přechod uspěje I zakázaný selže se správným kódem**.

- **Faktura:** doplnit `pay` (ISSUED→PAID), `cancel` (z DRAFT i ISSUED), terminální PAID/CANCELLED
  → INVALID_STATUS_TRANSITION; `createFromOrder` ORDER_ALREADY_INVOICED (1:1); položky editovatelné
  jen v DRAFT; guarded updateStatus INVOICE_STATE_CHANGED (souběh).
- **Inventura:** **jen jedna otevřená → 409 STOCK_TAKE_ALREADY_OPEN** (druhé otevření selže),
  close/cancel z NEeditovatelného stavu → STOCK_TAKE_NOT_EDITABLE, souběžný close →
  STOCK_TAKE_ALREADY_PROCESSED. (Doplnit chybějící větve k `StockTakeTest`.)
- **Review příjemky:** PENDING_REVIEW → CONFIRMED/REJECTED/CANCELLED; každý přechod z jiného
  než PENDING_REVIEW → RECEIPT_ALREADY_PROCESSED; cancel po použití → RECEIPT_ALREADY_USED.
  (Doplnit k `ReceiptReviewServiceTest`.)

### T4 — Web vrstva / ProblemDetail kontrakt · MockMvc · 🟠 — ✅ HOTOVO 2026-07-23

**Výsledek:** `ProblemDetailContractTest` (29 testů v 6 vnořených skupinách), suite **581 testů**
(zelená). `service.impl` 70,0 %, celkem 71,0 %. PIT `exception.*`: 76 %, **test strength 97 %**.

⚠️ **Oprava měření:** dosavadní počty testů (152 → … → 519) vznikly sečtením `Tests run`
z per-file surefire reportů, jenže vnořené `@Nested` třídy se reportují do souborů pojmenovaných
podle `@DisplayName` a sčítání je míjelo. Autoritativní je souhrn Mavenu (`Results: Tests run`).
Odtud i rozdíl na začátku: v T0 naměřeno 139, ač skutečný stav byl 152.

**Pokryto:** 404 (`RESOURCE_NOT_FOUND` včetně `params.resourceName`/`resourceId`), 400 Bean
Validation (`REQUIRED`, `INVALID_PATTERN`, `SIZE_EXCEEDED`, `INVALID_EMAIL`, vlastní
`CUSTOMER_NAME_REQUIRED`, více porušení najednou), 422 business kódy včetně `params`, 401
(`UNAUTHORIZED`, `BAD_CREDENTIALS` — i pro neexistující účet kvůli prevenci enumerace,
`ACCOUNT_LOCKED`), 403 `ACCESS_DENIED` **v obou větvích** (ADMIN projde, MECHANIC ne),
409 `USER_ALREADY_EXISTS`, 503 `REGISTRY_RATE_LIMITED` (klient mockovaný). U každé odpovědi
se tvrdí status, `Content-Type: application/problem+json`, `title`, `instance` i kód v `errors[]`.
Navíc kontrakt úspěšných odpovědí: `Location` u 201, soft-delete 200 s tělem, `id` z cesty (R-14),
obálka `PagedResponse`.

**Nálezy (produkční kód nezměněn):**
1. 🟠 **`PagedResponse.first` je vždy `false` a `last` se rozsvítí o stránku dřív.**
   `PagedResponse.of()` počítá `first(page == 0)` a `last(page >= totalPages - 1)` — obojí je
   0-based úvaha, ale `page` je podle `api.md` **1-based**. Důsledek: paginátor nepozná první
   stránku a „další" zakáže už na předposlední, takže **poslední stránka je nedosažitelná**.
   Oprava: `first = page <= 1`, `last = page >= totalPages`.
2. 🟡 **Nečíselné id v cestě → 500 `INTERNAL_ERROR` místo 400.**
   `MethodArgumentTypeMismatchException` nemá `@ExceptionHandler`, spadne do catch-all na
   `Exception`. Překlep v URL tak vypadá jako pád serveru a plní log úrovní ERROR.

### T4 — původní zadání fáze

Kontrakt, na kterém závisí frontend. Per reprezentativní controller asertovat **status + `code`
+ `errors[].field`** přesně dle `api.md`.

- Validace `@Valid` → **400** s `errors[]` (např. Customer bez jména → CUSTOMER_NAME_REQUIRED;
  Vehicle špatný VIN → INVALID_PATTERN; User krátké heslo → VALUE_TOO_SMALL).
- 404 RESOURCE_NOT_FOUND (params resourceName/resourceId), 422 business kódy
  (PRODUCT_HAS_STOCK, ORDER_LOCKED_BY_INVOICE, INVALID_UNIT…), 409 (DUPLICATE_IMPORT — už je,
  INVOICE_STATE_CHANGED).
- `id` v path, ne v těle (PUT ignoruje `id` z těla).
- Upload validace (`GoodsReceiptImportController` `ResponseStatusException` 400/415).

### T5 — Security hloubka · integrační · 🔴 — ✅ HOTOVO 2026-07-23

**Výsledek:** 4 nové třídy (`RefreshTokenRotationTest` 13, `JwtServiceTest` 12,
`ChangePasswordTest` 6, `SecurityServicesTest` 6), suite **618 testů** (zelená).
`security.service` **36,4 % → 86,4 %**, celkem 71,7 %.
PIT `security.service.*`: **94 % zabitých, test strength 98 %** (z 88 %).

**Pokryto:** rotace refresh tokenu + **detekce reuse** (odvolání *všech* sessions včetně
nesouvisejícího zařízení, doloženo dvěma paralelními přihlášeními), řetězení rotací, expirovaný
token (odvolá se, ale ostatní sessions nechá — na rozdíl od reuse), logout, `JwtService`
(podpis cizím klíčem → `SignatureException`, expirace, token jiného uživatele, neprůhlednost
a unikátnost refresh tokenu), `changePassword` obě větve + dopad na přihlášení,
`AppUserDetailsService` (zamčený účet se **načte** s příznakem, deaktivovaný se nenačte vůbec),
`RoleService`, úklid blacklistu (staré maže, čerstvé nechá).

**Nález:** 🟡 **logout není idempotentní.** `BlacklistMapper.save` dělá prostý INSERT do tabulky,
kde je `token` primární klíč — druhé odhlášení stejným access tokenem skončí
`DuplicateKeyException` → HTTP 422 `DATA_INTEGRITY_VIOLATION`. Dvojklik na „Odhlásit se",
opakování po výpadku sítě nebo odhlášení ze dvou panelů tak vrátí chybu.
Oprava: `INSERT ... ON CONFLICT (token) DO NOTHING`.

**Nepokryté mutanty (zdůvodnění):** dva `IllegalStateException` suppliery v `AuthenticationService`
(„uživatel nenalezen po úspěšné autentizaci") — nedosažitelné, autentizace by musela projít pro
neexistující účet. `JwtService.isTokenExpired` — návratovou hodnotu nelze pozorovat, protože JJWT
vyhodí `ExpiredJwtException` už při parsování v `extractUsername`; kontrola expirace
v `isTokenValid` je tím pádem **redundantní** (ne chybná).

**Poznámka k psaní testů:** `ChangePasswordTest` je záměrně **bez `@Transactional`** — stejně jako
`LoginLockoutTest`. `LoginAttemptService.recordSuccess` běží v `REQUIRES_NEW` a sahá na tentýž
řádek `security.users`; testovací transakce ho drží zamčený a přihlášení čeká až do timeoutu
(30 s). Stav se vrací ručně v `@AfterEach`.

### T5 — původní zadání fáze

- **Refresh rotace + reuse detection:** platný refresh → nové cookies, starý token odvolán;
  **předložení odvolaného** tokenu → `revokeAllByUserId` (všechny session uživatele pryč),
  INVALID_REFRESH_TOKEN.
- **JwtService:** vygenerování + parse (sub=username), expirace → neplatný, podpis cizím klíčem → odmítnut.
- **AppUserDetailsService:** `loadUserByUsername` filtruje `enabled=TRUE`; zamčený (ale enabled)
  účet se načte a odmítne ho `AuthenticationManager` (LockedException → 401 ACCOUNT_LOCKED).
- **changePassword** (self-service): správné staré heslo projde, špatné → INVALID_CURRENT_PASSWORD (422).
- **Kontrakty 401/403:** chybějící cookie → 401 UNAUTHORIZED; blacklistnutý/expirovaný/neplatný
  token → TOKEN_BLACKLISTED/TOKEN_EXPIRED/TOKEN_INVALID; role — `UserController` bez ROLE_ADMIN
  → 403 ACCESS_DENIED, import bez povolené role → 403 (pozor na TD-24 — **jen ověřit existující
  pravidla**, nevymýšlet nová).

### T6 — DB triggery a časy · integrační · 🟠 — ✅ HOTOVO 2026-07-23

**Výsledek:** `DatabaseTriggerTest` (16 testů), suite **634** (zelená). Balíček `database`.
Bez posunu `service.impl` — triggery jsou SQL, ne Java, takže JaCoCo pro Java balíčky se nehýbe
a PIT se jich netýká (cílí na `service`/`security`); důkazem je, že trigger vystřelí.

**Metodika:** hodnoty se čtou **přímo z DB přes `JdbcTemplate`**, ne přes MyBatis — lokální cache
SqlSession by uvnitř transakce vrátila zapsaný objekt a nedokázala nic o obsahu tabulky.

**Pokryto:** generovaná čísla `ZNK-`/`ZAK-`/faktura (formát, aktuální rok/měsíc **i inkrement dvou
po sobě** — samotný formát by prošel i triggeru vracejícímu pořád totéž), doplnění variabilního
symbolu z čísla faktury, `updated_at` (posune ho trigger i u UPDATE, který ho nezmiňuje, a při
každém opakování), tachometr (sync na vozidlo, rozhoduje nejnovější **datum** ne nejvyšší hodnota,
přepočet po DELETE i UPDATE, srovnání na null po smazání poslední), skladový trigger (RECEIPT
zvedne stav, ale **zbytek šarže nesnižuje** — větev `movement_type <> RECEIPT`), časy/zóny
(round-trip zachová okamžik, **opakované uložení čas neposune** — regrese TD-47, ekvivalentní
okamžiky v různých zónách se uloží shodně).

**Poučení:** seedový INITIAL záznam tachometru má `recorded_date = created_at::date` (**dnešek**),
takže testy „po smazání zbude můj starší záznam" na seedovém vozidle 1 selhaly — seedový záznam
z dneška vyhrál. Testy triggeru tachometru proto běží na **čerstvě založeném vozidle** bez historie.

### T6 — původní zadání fáze

**Číst reálně vygenerovanou hodnotu z DB**, nespoléhat na „nejspíš proběhlo".

- **Generovaná čísla:** insert zakázky → přečíst `order_number` `ZAK-{rok}-NNNN`; zákazníka →
  `ZNK-{rok}-NNNN`; faktury → `YYYYMMNNN`. Asertovat formát **i** inkrement dvou po sobě.
- **`updated_at`:** update řádku → `updated_at` se změní (trigger, ne aplikace); zapsat hodnotu,
  po druhém update ověřit posun.
- **`current_mileage_km`:** vložit vyšší záznam tachometru → trigger přepíše na vozidle; nižší
  záznam nesnižuje.
- **`quantity_on_hand`:** RECEIPT/ISSUE pohyb → trigger změní stav skladu i zbytek šarže.
- **Časy/zóny (TD-47):** ověřit UTC↔lokál na backendu — uložit `estimatedCompletionAt`, načíst,
  uložit **beze změny** znovu → hodnota se **neposune** (round-trip beze ztráty; chyba TD-47 byla
  na FE, tady chráníme serverový kontrakt proti regresi).

### T7 — Hranice „AI čte, kód počítá" — doplnění · unit + integrační · 🟡 — ✅ HOTOVO 2026-07-23

**Výsledek:** `DraftVerificationSumsTest` (30 testů, čistý unit bez Springu/DB), suite **664**
(zelená). `service` (bez impl) 73,1 %, celkem 72,5 %. **Rozšířen PIT scope v `pom.xml`
na `service.*`** (dřív jen `service.impl.*`), aby `DraftAssembler`/`DraftVerificationService`/
`SupplierNormalizer` byly trvale v mutačním záběru.
PIT `DraftVerificationService`: **99/101 zabitých (98 %), test strength 99 %.**

**Pokryto:** všechny souhrnné kontroly (`SUBTOTAL_PLUS_VAT_EQ_TOTAL`, `LINES_SUM_VS_TOTAL`,
`LINES_SUM_VS_RECAP`, `RECAP_SUM`) v obou směrech **a izolovaně** (draft, kde selže právě jedna
kontrola — jinak by mutant „return true" přežil, protože víc selhání = redundance), tolerance 0.05
inkluzivně na hranici, rekapitulace po sazbách (dvě sazby, prohození základů), skupinový LKQ řádek
se do součtů nezapočítává, `SUPPLIER_KNOWN` (AUTO/NONE), normalizace IČO před lookupem,
dedup `matchDeliveryNoteRefs` (napárování dle čísla, respekt k už napárované referenci, jen faktury,
zúžení dle dodavatele). Dedup LINKED materializace, ISDOC dobropis a DUPLICATE_IMPORT byly už
pokryté (`ReceiptReviewServiceTest`, `IsdocImportTest`, `WarehouseImportServiceTest`).

**Poučení:** izolovat selhání jedné souhrnné kontroly je záludné — sumy jsou provázané. Např.
špatný `total` shodí SUBTOTAL_PLUS_VAT **i** LINES_SUM_VS_TOTAL; izolace SUBTOTAL vyžaduje místo
toho špatné `vatAmount` při správném total. Bez izolace mutant „return true" na návratové hodnotě
metody přežije, protože sourozenecká kontrola drží reconciliationOk false.

**Nepokryté mutanty (zdůvodnění):** `verifyLinesVsTotal:167` (null total) a `:175` (null řádkové
incl) jsou **redundantní obranné guardy** — tentýž null zachytí i sourozenecká kontrola
(SUBTOTAL_PLUS_VAT resp. LINE_MATH), jejíž selhání už reconciliationOk shodí. Neexistuje draft,
kde by ten null viděla jen `verifyLinesVsTotal`, takže mutanta nelze izolovat (ekvivalentní pro
výsledek rekonciliace).

**Odloženo do T8:** `DraftAssembler` (mapovací vrstva AI→kanonický draft) má po rozšíření scope
~23 přeživších mutantů (převážně negace null-guardů v `mapHeader`/`mapLine`); má vlastní
`DraftAssemblerTest`, doladí se v závěrečném PIT sweepu.

### T7 — původní zadání fáze

Existující draft testy rozšířit o nepokryté kontroly (AI extrakce **mockovaná** `@MockitoBean`):

- `DraftVerification`: **LINE_MATH** (qty×cena, DPH), **LINES_SUM_VS_RECAP**, **RECAP_SUM**,
  **SUBTOTAL_PLUS_VAT_EQ_TOTAL**, **ICO_CHECKSUM** (mod 11 — platné IČO projde, s chybou selže),
  SUPPLIER_KNOWN. Dokázat, že **matematiku dělá kód** (mock vrátí čísla, verifikace je přepočítá).
- Dedup dodací list ↔ faktura (LINKED řádky se přeskočí při materializaci).
- ISDOC: dobropis/vrubopis → 422 ISDOC_UNSUPPORTED_DOCUMENT_TYPE (doplnit, pokud chybí).
- Duplicitní import → 409 DUPLICATE_IMPORT (ignoruje REJECTED — partial index V39).

### T8 — Mutační testování + uzavření · 🔴 poslední — ✅ HOTOVO 2026-07-23

**Výsledek:** 2 nové třídy k zacelení největších děr — `OrderItemServiceTest` (14; CRUD, souhrn,
řazení, kompenzační ISSUE_RETURN při mazání skladové položky) a `InvoiceDocumentServiceTest`
(4; PDF smoke — validní `%PDF`, QR s IBAN i bez). Rozšířen `DraftAssembler` (`DraftAssemblerMappingTest`
15 + `DraftAssemblerDerivationTest` 10). Suite **707 testů** (zelená). Větvové pokrytí **75,3 %**,
instrukční 87,0 %.

**PIT sweep** `service.*` + `security.service.*`: **1063 mutací, 891 zabitých (84 %), test strength
88 %**, 56 nepokrytých (z 104 v prvním sweepu — PDF a OrderItem testy zacelily největší díry).
Přeživší mutanti dořešeni po třídách (DraftVerificationService 98 %, DraftAssembler 97 %) nebo
zdůvodněni: defenzivní `IllegalStateException` suppliery „disappeared between UPDATE and SELECT"
(nedosažitelné v jedné transakci), redundantní null-guardy kryté sourozeneckou kontrolou,
ekvivalentní mutanty (`new ArrayList<>()` vs `emptyList()`, `Math.min(size±1, limit)` na hranici).

**JaCoCo práh** nastaven a vynucen v `test` fázi (`check` goal): BUNDLE instrukce ≥ 80 %, větve ≥ 68 %;
větvové pokrytí `service`/`service.impl`/`security.service`/`model.converter` ≥ 65 %. Prahy jsou pod
dosaženou hodnotou s rezervou — build shodí až skutečná regrese. `./mvnw test` je vynucuje.

**Dokumentace:** `backend.md` §7 přepsán (organizace testů, nástroje, konvence), `tech-dluhy.md`
**TD-14 uzavřen** + 7 nálezů zapsáno jako TD-49…TD-55 (každý má `_knownDefect` test).

**Nálezy zacelené v T8:** `InvoiceDocumentServiceImpl` (PDF, 28 nepokrytých mutantů → pokryto) a
`OrderItemServiceImpl` (33 nepokrytých/přeživších → pokryto) byly největší díry z prvního sweepu.

---

## Frontend — rozhodnutí (uzavřeno 2026-07-23)

**Varianta (b) — frontend mimo rozsah** tohoto úkolu (rozhodl uživatel). Soustředíme se na
backend: TD-14 je backendový dluh a planý test, který úkol motivoval (`ListSortingTest`), byl
taky backendový. FE se dál ověřuje `npm run check` + `vite build` + prohlížečem. Zavedení
Vitest/RTL (příp. Playwright) zůstává jako samostatný pozdější úkol.

## Tempo (uzavřeno 2026-07-23)

**Fáze po fázi se souhlasem uživatele** (Pravidlo č. 1). Po každé fázi report (co se dělo, jak
dopadly testy, delta pokrytí, přeživší mutanti) a čekání na „pokračuj".

## Definice hotového

1. Tento plán schválen. 2. Každá fáze: nové testy + suite zelená + delta JaCoCo + poznámka k mutantům
+ report. 3. Na konci: `backend.md` §7 a `tech-dluhy.md` (TD-14) aktualizované, JaCoCo práh nastavený,
PIT mutation score doložené.

## Shrnutí — všechny fáze hotové (2026-07-23)

| Fáze | Přidáno | Suite | Klíčové |
|---|---|---|---|
| T0 | nástroje | 152 | JaCoCo + PIT, baseline větve 53,6 % |
| T1 | 17 tříd | 333 | konvertory + enumy, PIT 100 % |
| T2 | 8 tříd | 473 | service díry, PIT 89–97 %, nálezy TD-51/54/55 |
| T3 | 2 třídy | 519 | automaty faktura/inventura, nález TD-49 |
| T4 | 1 třída | 581 | ProblemDetail kontrakt, nálezy TD-50/52 |
| T5 | 4 třídy | 618 | security hloubka, security.service 86,4 %, nález TD-53 |
| T6 | 1 třída | 634 | DB triggery + časy/zóny |
| T7 | 1 třída | 664 | AI hranice, DraftVerification 98 % |
| T8 | 4 třídy | **707** | PDF + OrderItem + DraftAssembler, JaCoCo práh, uzavření TD-14 |

**Výsledek:** 69 testovacích tříd, **720 testů**, větve **75,3 %** (z 53,6 %), instrukce 86,8 %.
Kritické balíčky doloženy mutačně. Odhaleno 7 skutečných chyb (TD-49…TD-55) + několik planých testů
opraveno. **Všech 7 chyb následně opraveno** (uživatel schválil „oprav to ty", 2026-07-23) — každý
`_knownDefect` test přepsán na správné očekávání, viz `tech-dluhy.md`. Frontend zůstal mimo rozsah.
