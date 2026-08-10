# Audit 7/9 — Testovací suita

> Součást hloubkového auditu 2026-07-24 (commit `409d3ad`, větev `audit-one`).
> Přehled celého auditu: [00-prehled.md](00-prehled.md).
>
> **Verifikace:** nálezy V1, V2 a S1 ověřeny druhým nezávislým čtením — `src/test/resources`
> skutečně neexistuje, `application.yaml:131` má `secret: ${JWT_SECRET}` bez defaultu, žádný test
> nepracuje s cookie ani s kódy `TOKEN_BLACKLISTED`/`TOKEN_INVALID`, a test
> `ProblemDetailContractTest.java:278–285` skutečně asertuje `isOk()` na validním volání.

Audit proveden čtením: všech klíčových testovacích tříd v `src/test/java/` (≈35 tříd přečteno celé, zbytek vzorově + grep na vzory planých asercí), `pom.xml`, `AbstractIntegrationTest`, relevantních míst v `src/main` (GlobalExceptionHandler, import controller, konfigurace), `docs/backend.md` §7, `docs/plan-testy.md`, `docs/tech-dluhy.md`. Testy nespouštěny (vyžadují Docker/WSL).

---

## Nálezy

### VYSOKÝ

**V1 — Suite závisí na gitignorovaných secrets; „vyžaduje jen Docker" neplatí na čistém stroji**
- Soubory: `src/main/resources/application.yaml:27` (`api-key: ${ANTHROPIC_API_KEY}`), `:131` (`secret: ${JWT_SECRET}` — **bez defaultu**), `src/test/java/cz/palo/autoservis/AbstractIntegrationTest.java:47-52`, **`src/test/resources/` neexistuje vůbec**.
- Důkaz: `@DynamicPropertySource` přepisuje jen datasource. `JwtService` má `@Value("${jwt.secret}")` (řádek 35) — bez resolvované hodnoty spadne vytvoření beanu, tj. **celý kontext a všech 720 testů**. Placeholder se dnes plní z `src/main/resources/application-local.yaml` (existuje lokálně, je gitignorovaný, a protože leží v main resources, je na testovacím classpath) nebo z env.
- Dopad: (a) čistý clone / CI (žádné `.github/workflows` neexistují) testy nespustí — v rozporu s `backend.md` §7 „vyžaduje **jen** běžící Docker"; (b) testy běží s **reálným** `ANTHROPIC_API_KEY` v kontextu — jediná pojistka proti placenému API volání je, že každý import test nezapomene na `@MockitoBean PdfDocumentExtractionService`.
- Návrh: založit `src/test/resources/application-test.yaml` (testovací `jwt.secret`, dummy `spring.ai.anthropic.api-key`) nebo doplnit do `AbstractIntegrationTest` `@DynamicPropertySource` pro tyto klíče. Confidence: **jistý**.

**V2 — JWT filter chain a auth cookie flow nemají žádný e2e test**
- Soubory: `security/filter/JwtAuthenticationFilter.java`, `security/controller/AuthController.java` vs. celý `src/test`.
- Důkaz: grep — kódy `TOKEN_BLACKLISTED` / `TOKEN_INVALID` se v testech nevyskytují vůbec, `TOKEN_EXPIRED` jen v unit testu `SecurityProblemWriterTest` (formát JSON, ne chování filtru). Všechny MockMvc testy autentizují přes `.with(user(...))`, což **obchází** JWT filtr — filtr nikdy nedostal cookie `jwt`.
- Netestováno: pozitivní cesta cookie → SecurityContext, blacklist lookup ve filtru, expirovaný/poškozený token → 401 se správným kódem, `shouldNotFilter` výjimky, `AuthController` na HTTP úrovni (Set-Cookie atributy HttpOnly/path/maxAge, refresh **z cookie**, logout endpoint, `/auth/me`). Právě tady žijí známé nesoulady (maxAge vs. expiration, natvrdo path `v1` — api.md) a **žádný test je nechrání proti regresi**. (Pozn. auditu: dle nálezu 08/V1 jsou tyto nesoulady v kódu už opravené a zastaralá je dokumentace — o to spíš chybí regresní test, který by správné hodnoty fixoval.)
- Návrh: MockMvc test „plný kruh": `POST /auth/login` → vzít cookie z odpovědi → `GET /api/v1/...` s cookie (200) → logout → týž request → 401 `TOKEN_BLACKLISTED`; + ručně vyrobený expirovaný/cizí token v cookie → `TOKEN_EXPIRED`/`TOKEN_INVALID`. Confidence: **jistý**.

### STŘEDNÍ

**S1 — Planý, zavádějící test v kontraktní třídě**
- `web/ProblemDetailContractTest.java:278-285` — `nullIdentifierFromService_returnsInvalidArgument`, DisplayName „null identifikátor ze service → **400 INVALID_ARGUMENT** (TD-20)", ale tělo volá validní `GET /api/v1/vehicles/1/mileage` a asertuje `status().isOk()`. Test netestuje nic z toho, co tvrdí (nemůže chytit žádnou regresi TD-20 na HTTP úrovni). Návrh: smazat, nebo skutečně vyvolat `IllegalArgumentException` přes HTTP (dnes prakticky nejde — pak smazat a nechat pokrytí na `GlobalExceptionHandlerTest`). Confidence: **jistý**.

**S2 — Křehká vazba na seed migrace (magic konstanty, přesné počty)**
- Nejtvrdší případ: `ProblemDetailContractTest.java:494-510` — asertuje `totalPages == 4` a `content.length() == 5` při pageSize 5, tj. **přesně 20 vozidel v seedu**; `:471-482` totéž (`content.length()==5`, `last=false`). Jakákoli budoucí migrace přidávající/ubírající seed vozidlo shodí kontraktní testy TD-50.
- Další rozptýlené závislosti: vozidlo 7 = „má otevřenou zakázku" (`ProblemDetailContractTest:246`, `VehicleServiceTest:38`), adresa 2/4/5 a jejich vlastnictví (`InvoiceLifecycleTest:46,183,533`), VIN `WBA3A5C50DF595551`, „Jan Novák", „Logistika ABC s.r.o.", `ReceiptReviewServiceTest:488-491` („první zakázka bez faktury" ze seedu).
- Dopad: nová seed migrace → kaskáda pádů na místech, která s ní nesouvisí; přesná čísla stránek jsou nejrizikovější.
- Návrh: u TD-50 testů odvodit očekávané `totalPages` z `totalElements` v odpovědi (nebo si vozidla pro stránkování založit v testu); seedové konstanty centralizovat do jedné `SeedFixtures` třídy s komentářem „při změně seedu změň tady". Confidence: **jistý** (mechanismus), dopad pravděpodobný.

**S3 — QR platba / SPAYD bez jediné obsahové aserce**
- `service/InvoiceDocumentServiceTest.java` — 4 testy, vše smoke (`%PDF-`, délka > 1000, s IBAN / bez IBAN). Formát SPAYD řetězce (`AM:` = správná částka!, `X-VS`, `MSG` bez diakritiky ≤ 60 znaků, absence BIC) není nikde tvrzen — **chybná částka v QR platbě by prošla celou suitou i PIT** (mutanty v konstrukci řetězce zabíjí jen „PDF se vygenerovalo").
- Návrh: extrahovat sestavení SPAYD do package-private metody/komponenty a napsat unit test na přesný řetězec pro známou fakturu (vč. hraničních případů: bez VS, dlouhá zpráva, diakritika). Confidence: **jistý**.

**S4 — Souběhové guardy K5/K6 testovány jen sekvenční simulací, nikdy skutečným souběhem**
- K5: `InvoiceLifecycleTest.java:298-323`, `InvoiceStatusTransitionTest.java:114-137` — „souběh" = přímé přepnutí stavu mapperem v téže transakci + aserce 0 affected rows. K6: `OrderItemImportTest.java` testuje agregaci 3+3 > 4, ale `SELECT ... FOR UPDATE` (jádro opravy) se vícevlákně netestuje — pod `@Transactional` (jedno spojení) to ani nejde.
- Dopad: guardovaný UPDATE je ověřen dobře na úrovni SQL sémantiky; co ověřeno není, je že service skutečně drží zámek po celou dobu transakce (např. regrese přesunem validace mimo zámek by prošla).
- Návrh: jeden ne-transakční test s `ExecutorService` + `CyclicBarrier`: dvě vlákna současně `importFromReceipt` z téže šarže (2+3 ks při remaining 4) → právě jedno musí selhat, `quantity_remaining ≥ 0`; úklid v `@AfterEach` (vzor `LoginLockoutTest`). Confidence: **jistý** (že chybí).

**S5 — PIT scope nekryje konvertory (a exception/client), ačkoli dokumentace tvrdí opak**
- `pom.xml:320-323`: `targetClasses` = jen `service.*` + `security.service.*`. `model.converter` — kde plan-testy hlásí „PIT 100 %" a kde PIT odhalil dvě systémové díry — v trvalém sweepu **není**; regres asercí v 17 konvertorových testech mutačně nikdo neuhlídá. Totéž `exception.*` (uváděno PIT 76 %) a `client`.
- Návrh: přidat `<param>cz.palo.autoservis.model.converter.*</param>` (unit testy, běh bude rychlý) + `exception.*`; aktualizovat backend.md §7.1. Confidence: **jistý**.

**S6 — Netestované větve `GlobalExceptionHandler`**
- `exception/GlobalExceptionHandler.java`: `DataIntegrityViolationException` (ř. 368, 422 `DATA_INTEGRITY_VIOLATION`), catch-all `Exception` → 500 `INTERNAL_ERROR` (ř. 394 — grep: `INTERNAL_ERROR` se v testech nevyskytuje), `InvalidRefreshTokenException` → 401 přes HTTP (ř. 240; service vrstva krytá, HTTP překlad ne). Právě 500 cesta je kontrakt, na kterém FE staví „něco se pokazilo". Návrh: unit testy po vzoru `GlobalExceptionHandlerTest` (stačí, handler je čistý). Confidence: **jistý**.

**S7 — Upload validace importu (400/415) bez testu**
- `controller/warehouse/GoodsReceiptImportController.java:106-137` — `validatePdf`/`validateIsdoc`: prázdný soubor → 400, špatný typ → 415, akceptace fotek (R-D: image/*, .heic, .webp). Grep: žádný test neposílá špatný soubor na `/import`. `ResponseStatusException` navíc obchází `GlobalExceptionHandler` — tvar chybové odpovědi (ne-ProblemDetail?) není nikde zdokumentován testem. Confidence: **jistý**.

**S8 — Frontend nemá žádné testy**
- `frontend/autoservis-frontend/package.json` — žádný Vitest/Jest/RTL/Playwright; jediný „check" je `node scripts/check-ui.mjs`. Jde o vědomé rozhodnutí (plan-testy.md, uzavřeno 2026-07-23), ale TD-50 ukázal, že chyby žijí přesně na FE↔BE hranici (paginátor). Doporučený minimální rozsah, až se otevře: (1) Vitest + RTL na čisté utility (formátování, api.js error parsing ProblemDetail, paginátor first/last), (2) Playwright smoke: login → seznam zakázek → detail → PDF faktury, (3) kontraktní parsování `errors[]`. Confidence: **jistý**.

### NÍZKÝ

**N1 — `REQUIRES_NEW` zápisy unikají transakčnímu rollbacku testů**
- Každý `login()` uvnitř `@Transactional` testu (`RefreshTokenRotationTest`, `TokenBlacklistTest`, neúspěšný login v `ProblemDetailContractTest:346-370`) commitne `failed_login_attempts`/`last_login` přes `LoginAttemptService` (REQUIRES_NEW) **mimo** testovací transakci; ne-transakční testy (`LoginLockoutTest`, `ChangePasswordTest`) navíc nechávají v DB commitnuté refresh tokeny. Dnes to nic nerozbíjí (nikdo počty neasertuje, counter admina resetuje kterýkoli úspěšný login), ale je to latentní vazba na pořadí testů — kdyby přibylo víc testů se špatným heslem admina, počítadlo se blíží k 10 a lockout shodí nesouvisející test. Návrh: dedikovaný testovací účet pro chybové login testy. Confidence: **pravděpodobný** (dopad), jistý (mechanismus).

**N2 — Slabá aserce v terminálních stavech inventury**
- `StockTakeStateMachineTest.java:133-134, 156-157` — dvojí `close` asertuje jen `isInstanceOf(RuntimeException.class)`; prošel by i NPE nebo úplně jiná chyba. Ostatní operace mají přesný `STOCK_TAKE_NOT_EDITABLE`. Návrh: asertovat konkrétní výjimku/kód i u close. Confidence: **jistý**.

**N3 — Zastaralý javadoc `VehicleConverterTest`**
- `model/converter/VehicleConverterTest.java:21-23` tvrdí „sahají na `getCustomer()` **bez null kontroly** … fixtury to respektují", ale TD-55 je opravené a tentýž soubor (ř. 236-243) má test `toDetailResponse_vehicleWithoutCustomer_doesNotThrow`. Matoucí pro čtenáře. Confidence: **jistý**.

**N4 — Aserce absolutních počtů řádků tabulek**
- `ReceiptReviewServiceTest.java:612` (`totalElements == 1`), `:128-131` (`count(suppliers) == 1` …) — drží jen díky tomu, že warehouse nemá seed a všechny ostatní testy rollbackují. Kterýkoli budoucí commitovaný zápis (nebo warehouse seed migrace) to shodí. Robustnější je delta (before/after) jako v `cancelUsedReceiptFails`. Confidence: **jistý** (vzor), dopad podmíněný.

**N5 — Paralelizace není explicitně zamčená**
- `pom.xml` nemá konfiguraci surefire (default: sekvenčně, 1 fork — se singleton containerem správně) a neexistuje `junit-platform.properties`. Kdyby kdokoli zapnul JUnit `parallel.enabled`, ne-transakční testy mutující seed účty (`LoginLockoutTest`, `ChangePasswordTest` — oba sahají na tytéž řádky `security.users`) a testy asertující absolutní počty se rozsypou nedeterministicky. Návrh: `junit-platform.properties` s `junit.jupiter.execution.parallel.enabled=false` jako záměrný zámek + komentář. Confidence: jistý (stav), spekulativní (dopad).

**N6 — Drobné netestované okraje**
- `CodeListController` (GET /code-lists/roles) — žádný test (RoleService krytá); `InvoiceDocumentController` na HTTP úrovni (Content-Type/Content-Disposition PDF faktury) — service krytá, controller ne; customer/vehicle autocomplete testovány jen na service úrovni (HTTP parametry `q`/`limit` bez testu; goods-receipts autocomplete HTTP test má `ReceiptReviewServiceTest:139-143`). Confidence: **jistý**.

---

## Mapa mezer v pokrytí

| Oblast | Stav | Poznámka |
|---|---|---|
| Service CRUD + business výjimky (10 modulů) | ✅ výborné | obě větve guardů, audit ze serveru, soft-delete vratnost, 404/422 kódy s params |
| Stavové automaty (faktura, inventura, review příjemky) | ✅ výborné | povolené i zakázané přechody, terminální stavy proti všem operacím |
| DB triggery (čísla dokladů, updated_at, tachometr, sklad, časy) | ✅ výborné | čtení přes JdbcTemplate, formát **i** inkrement, roky dynamicky (1. 1. 2027 nic nerozbije) |
| Sklad (FIFO manko, přebytek, ruční pohyby, vratky, storno, dedup DL↔faktura) | ✅ výborné | ledger append-only, kompenzační pohyby, delta aserce |
| Draft pipeline „AI čte, kód počítá" | ✅ výborné | AI mock jen na hranici, verifikace izolovaně po kontrolách; reálná PDF v manuálním testu |
| Security services (rotace+reuse, lockout, blacklist hash, changePassword, JwtService podpis/expirace) | ✅ výborné | včetně negativních větví |
| ProblemDetail kontrakt (400/401/403/404/409/422/503) | ✅ dobré | až na planý TD-20 test (S1) a chybějící 500/422-integrity (S6) |
| Konvertory (17×, unit) | ✅ dobré | plné sady polí; ale mimo trvalý PIT scope (S5) |
| Řazení + whitelist ORDER BY (8 seznamů) | ✅ dobré | fixtury proti triviálnímu průchodu prázdným seznamem |
| **JWT filter + auth cookies e2e** | ❌ **nulové** | V2 — největší funkční díra |
| **SPAYD/QR obsah** | ⚠️ jen smoke | S3 — částka v QR nechráněná |
| **GlobalExceptionHandler: 500, DATA_INTEGRITY, INVALID_REFRESH via HTTP** | ❌ chybí | S6 |
| **Upload validace importu (400/415, fotky)** | ❌ chybí | S7 |
| **Souběh K5/K6 skutečnými vlákny** | ⚠️ jen sekvenčně | S4 |
| PDF extrakce (PdfDocumentExtractionService – stavba promptu) | ⚠️ jen manuálně | záměr (reálné API), přijatelné |
| CodeList, InvoiceDocument HTTP hlavičky, autocomplete HTTP | ⚠️ okraje | N6 |
| SecurityConfig chain (SPA fallback, statika permitAll) | ❌ chybí | souvisí s V2 |
| **Frontend** | ❌ nulové | S8 — vědomé rozhodnutí, doporučen rozsah |
| Reprodukovatelnost běhu (secrets, CI) | ❌ | V1 — žádné src/test/resources, žádné CI |

## Pozitiva

1. **Mutačně doložená suite** — PIT není formalita: odhalil plané testy (MyBatis SqlSession cache u CompanyProfile, `applyUpdate` bez aserce návratové hodnoty) a přeživší mutanti jsou individuálně zdůvodnění v plan-testy.md. To je nadstandard.
2. **Metodická disciplína**: zápis do DB se ověřuje `JdbcTemplate`, ne opětovným čtením přes MyBatis (dokumentovaná past); fixtury mají guardy „musí být neprázdná, jinak test nic nedokazuje"; stavové automaty se testují v obou směrech; hraniční případy (tolerance 0.05 inkluzivně, `size == limit` u hasMore, rok výroby == rok registrace) jsou vědomě pokryté.
3. **Správný singleton container pattern** v `AbstractIntegrationTest` (záměrně bez `@Testcontainers`, Ryuk úklid, jeden Spring kontext, žádný `@DirtiesContext`) + korektní výjimky z `@Transactional` tam, kde REQUIRES_NEW drží zámky (`LoginLockoutTest`, `ChangePasswordTest` s ručním úklidem).
4. **Testy jako dokumentace nálezů**: všech 7 oprav TD-49…TD-55 má existující regresní test se **správným** očekáváním (ověřeno po jednom: `update_cannotBypassStateMachine`, `PagedResponseTest` matice, `detailResponse_activeFlag_reflectsDatabase`, `nonNumericPathId_returnsBadRequest`, `logout_calledTwice_isIdempotent`, `update_blankRegistrationNumber_clearsStoredValue` + 2 sourozenci, `toDetailResponse_vehicleWithoutCustomer_doesNotThrow`).
5. **Věrné mocky**: mockuje se výhradně skutečná externí hranice (AI extrakce, HTTP registr — ten navíc s vlastním `MockRestServiceServer` testem klienta vč. plain-text rate-limit těla); vše ostatní jde přes reálnou DB. Časové aserce jsou dynamické (`LocalDate.now().getYear()`) — přelom roku suite nerozbije.

Celkově jde o výrazně nadprůměrnou suitu; kritická rizika nejsou v tom, co testy tvrdí, ale v tom, co vůbec neprocházejí: JWT filtr/cookies (V2), reprodukovatelnost přes secrets (V1) a obsah QR platby (S3).
