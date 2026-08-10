# backend.md — Průvodce backendem

> Vrstvy, MyBatis, security, AI import, PDF generování, konfigurace, testy.
> DB schéma → `databaze.md` · endpointy → `api.md` · pravidla kódu → `konvence.md`.

Kořen balíčků: `cz.palo.autoservis`. Aplikační třída `AutoservisApplication` má `@SpringBootApplication` + `@MapperScan({"cz.palo.autoservis.mapper", "cz.palo.autoservis.security.mapper"})`.

## 1. Vrstvy

```
Controller → Service (rozhraní + impl) → Mapper (rozhraní) → XML mapper → PostgreSQL
                 ↕ Converter (domain ↔ DTO, ruční @Component)
```

### config/
- `config/mybatis/PgEnumTypeHandler` — abstraktní `BaseTypeHandler` pro PostgreSQL ENUMy (`setObject(i, name, Types.OTHER)`); obsahuje 12 vnořených konkrétních handlerů (CustomerType, OrderStatus, InvoiceStatus, MovementType, …). Registrace přes `type-handlers-package`.
- `config/mybatis/UuidTypeHandler` — Java `UUID` ↔ PG `uuid`.
- `config/security/SecurityConfig` — viz sekce 3.

### service/ (~24 tříd)
Rozhraní + `impl/`: CompanyProfile, Customer, Employee, GoodsReceipt, InvoiceDocument, Invoice, Mileage, OrderItem, Order, Product, Supplier, User, Vehicle, WarehouseImport.
Samostatné komponenty: `AddressSetValidator` (business validace adres), `PdfDocumentExtractionService` (Spring AI extrakce dokladů), `DraftAssembler` (extrakce → kanonický draft), `DraftVerificationService` (deterministické kontroly draftu, viz §4), `SupplierNormalizer` (normalizace registračního čísla: trim, odstranění mezer vč. ` `, prázdné → null; prefix CZ/SK se nestrhává).
Security services: `AppUserDetailsService`, `AuthenticationService` (login/register/refresh/logout + self-service `changePassword`), `BlacklistCleanupService`, `JwtService`, `RoleService`.
`UserService(Impl)` je admin CRUD nad `security.users` (odlišné od `AuthenticationService`, který řeší jen účet přihlášeného uživatele). Detail: `docs/funkce/sprava-uzivatelu.md`.

### model/
- `domain/` — čisté POJO po modulech (billing, customer, employee, order, user, vehicle, warehouse). Bez JPA anotací.
- `dto/` — namespace pattern (`CustomerDto.CreateRequest` atd.), `pagination/` (`BaseParams`, `SearchParams`, `PagedResponse<T>`), `autocomplete/`, `*SearchParams`, records pro autocomplete parametry a výsledek importu.
- `enums/` — zrcadlí PG ENUMy (viz `databaze.md` §7). `InvoiceStatus` nese **stavový automat** (`canTransitionTo`, `isEditable` — jen DRAFT). ISSUED→CANCELLED je od auditu KN-1 **zakázáno**: vystavený doklad se opravuje dobropisem, ne stornem (§42/§45 ZDPH); zdůvodnění je v Javadocu enumu. `OrderStatus` má od auditu KN-11 vlastní automat (`canTransitionTo`, `isTerminal`): mezi provozními stavy volně oběma směry, `COMPLETED`/`CANCELLED` terminální, nezměněný stav není přechod. Terminalita je definovaná jako „není v `OPERATIONAL`", takže nová hodnota v ENUMu defaultně blokuje. Podmínky zrušení závislé na DB (aktivní faktura, nevrácený materiál) drží `OrderServiceImpl.requireAllowedStatusChange` — viz `funkce/zakazky-stavy.md`.
- `converter/` — 17 ručních `@Component` konvertorů (`toDomain` / `applyUpdate` / `toResponse`); `InvoiceItemConverter.fromOrderItem` převádí položku zakázky na položku faktury.

### exception/
`ResourceNotFoundException` (404), `BusinessRuleException` (422, nese ruleCode/field/params), `UserAlreadyExistsException` (409), `InvalidRefreshTokenException` (401), `ConflictException` (409, nese code — např. `DUPLICATE_IMPORT`), `GlobalExceptionHandler` (RFC 9457, viz `api.md`).

## 2. MyBatis

Konfigurace (`application.yaml`): `mapper-locations: classpath:mapper/**/*.xml`, `type-aliases-package: …model.domain`, `type-handlers-package: …config.mybatis`, `map-underscore-to-camel-case: true`, `cache-enabled: true`, `lazy-loading-enabled: true`, `default-fetch-size: 100`, `default-statement-timeout: 30`.

XML mappery (`src/main/resources/mapper/`, 18 + 7 warehouse = 25; ověřeno 2026-07-31):

| XML | Tabulky / views | Zajímavosti |
|---|---|---|
| CustomerMapper | customer.* + JOIN vehicles | 3 resultMapy, nested `<collection>` s `columnPrefix` (addr_/cp_/v_), oddělené `insertIndividual`/`insertCompany`, dynamic search |
| AddressMapper | customer.addresses | `clearDefault` (jediná default adresa per typ) |
| ContactPersonMapper | — | jen resultMap (používá CustomerMapper); Java rozhraní `ContactPersonMapper` bylo prázdný placeholder — smazáno (TD-32, 2026-07-20), XML zůstává |
| VehicleMapper | vehicle.vehicles + JOIN customers | nejvíc metod: autocomplete, findByVin, hardDelete, deactivateByCustomerId |
| MileageHistoryMapper | vehicle.mileage_history | CRUD + existsByVehicleId |
| EmployeeMapper | employee.employees | strict `findById` + `findByIdIncludingInactive`, `findAll(activeOnly)`, `existsByUserId(excludeId)` (unikát loginu) |
| OrderMapper | "order".orders + JOINy | countOpenBy…, dynamic search |
| OrderItemMapper | "order".order_items + v_order_item_summary | `reorder` přes `<foreach>` |
| InvoiceMapper | billing.invoices + views totals/vat + JOINy | 4 resultMapy, `updateStatus`, `issueWithNumber` (číslo + VS + data z dialogu + přechod do ISSUED v jednom UPDATE, guard `WHERE status='DRAFT'`), `lockNumberSeries` (`pg_advisory_xact_lock` nad řadou období), `markCredited`, `findMaxSequence` (V71 — MAX pořadí řady přes `regexp_match` s regexem z `DocumentNumberMask`); `findByOrderId` vrací **aktivní** fakturu zakázky (ne stornovanou ani dobropisovanou — shodně s `uq_invoices_order_active`, V69) |
| InvoiceItemMapper | billing.invoice_items | `insertBatch` (`<foreach>`) |
| InvoicePartyMapper | billing.invoice_party | insert + findByInvoiceId (snapshot) |
| CashReceiptMapper | billing.cash_receipts | `findActiveByInvoiceId` (guard proti druhému platnému PPD, V68) + `cancel` (UPDATE hlídaný stavem `ISSUED` — atomický přechod); od V92 číslování dle masky (`findMaxSequence`, `findNumbersByRegex`, `lockNumberSeries`, `findByReceiptNumber`) a `deleteById` (tvrdé DELETE, rozhodnutí uživatele 2026-08-09) |
| CompanyProfileMapper | billing.company_profile | singleton: find + update (od V71 vč. `invoice_number_auto`/`invoice_number_mask`, od V92 i `cash_receipt_number_*` a `cash_receipt_gap_check_*`) |
| UserMapper | security.users + roles | UserWithRolesResultMap (jeden JOIN, žádné N+1); account-lock metody + admin CRUD (search/insert/update/deactivate/activate, `replaceRoles` přes `user_roles`) |
| RoleMapper | security.roles | getAll |
| warehouse/SupplierMapper | warehouse.suppliers | dynamic update se 14× `<if>` |
| warehouse/WarehouseMapper | products, stock_movements, goods_receipt_items + JOINy | soubor přejmenován z ProductMapper.xml na WarehouseMapper.xml (TD-32, 2026-07-20), odpovídá namespace i Java rozhraní `WarehouseMapper` |
| warehouse/GoodsReceiptMapper | goods_receipts, goods_receipt_items | findImportableItems, existsConfirmed |
| warehouse/WarehouseImportMapper | suppliers/products/receipts/items/movements | čistě insert/lookup pro AI import |

Vzory: `useGeneratedKeys` (id zpět do objektu), typeHandlery plně kvalifikované vč. vnořené třídy (`PgEnumTypeHandler$CustomerTypeHandler`), plně kvalifikované názvy tabulek (`"order".orders`), ENUM parametry s `jdbcType=OTHER`. Generovaná čísla dokladů řeší **DB triggery**, ne Java — výjimky: **faktury** (V71) a **pokladní doklady** (V92) čísluje aplikace dle masky (`DocumentNumberMask`, viz `konvence.md` §18).

## 3. Security

- **SecurityConfig:** `@EnableWebSecurity` + `@EnableMethodSecurity`, stateless, CSRF vypnuto, CORS jen `localhost:5173` / `127.0.0.1:5173` (natvrdo — před produkcí změnit). Pravidla v pořadí: `OPTIONS /**` permitAll → `/api/*/auth/{login,register,refresh}` permitAll → statika (`/`, `/index.html`, `/assets/**`, `/favicon.ico`) permitAll → `/api/**` authenticated → `anyRequest()` permitAll (SPA fallback — široké pravidlo). BCrypt (10 rounds). Custom entry point vrací JSON 401.
- **JwtAuthenticationFilter:** čte cookie `jwt` → blacklist check (hash, viz níže) → parse (expirace/neplatnost → 401 JSON) → SecurityContext. `shouldNotFilter` přeskakuje login/register/refresh.
- **JwtService:** access token = JWT HS256 (`sub`=username), klíč `jwt.secret` (Base64). Refresh token = opaque UUID, validita výhradně v DB (`security.refresh_tokens`).
- **AuthenticationService:** login přes `AuthenticationManager`; refresh s **rotací** a detekcí reuse (revoked token → `revokeAllByUserId`); logout → access do blacklistu, refresh revoke.
- **Blacklist ukládá hash, ne raw token (V4, analyza-2026-07):** `TokenHasher.sha256Hex` (`security/service/`) — statická utilita, žádný stav. `AuthenticationService.logout` hashuje access token před `BlacklistMapper.save`; `JwtAuthenticationFilter` hashuje před `isBlacklisted` lookupem. `BlacklistMapper` samotný nehashuje nic — ukládá/porovnává, co dostane (mapper je anotační, historická výjimka R-01). Únik zálohy DB tak nedává použitelný bearer token. Migrace nebyla potřeba (`token` je `VARCHAR(512)`, 64znakový hex se vejde); dřívější plaintextové záznamy přestaly matchovat a dožijí do expirace.
- **Zamykání účtu po neúspěšných loginech (V3b, analyza-2026-07):** `LoginAttemptService` (`security/service/`) — dvě metody, obě `@Transactional(propagation = REQUIRES_NEW)`, protože `AuthenticationService.login` je taky `@Transactional` a při `BadCredentialsException` by se increment jinak odrolloval spolu s ní. `recordFailure(username)` inkrementuje `failed_login_attempts`; při dosažení 10 pokusů (`MAX_FAILED_ATTEMPTS`) zamkne účet (`account_non_locked = FALSE`). `recordSuccess(userId)` volá `updateLastLogin` (nuluje počítadlo, zapisuje čas). `AppUserDetails.isAccountNonLocked()` se mapuje z DB sloupce `account_non_locked` (přes `User` doménový objekt) — u zamčeného účtu `AuthenticationManager` vyhodí `LockedException` ještě před kontrolou hesla, `GlobalExceptionHandler` ji mapuje na 401 `ACCOUNT_LOCKED`. `AppUserDetailsService.loadUserByUsername` filtruje jen `enabled = TRUE`, ne `account_non_locked` — zamčený (ale enabled) účet se tedy načte a o odmítnutí rozhoduje `AuthenticationManager`, ne SQL. **Deaktivovaný** účet se naopak nenačte vůbec (`UsernameNotFoundException`); filtr i `GlobalExceptionHandler` ji mapují na 401 `ACCOUNT_UNAVAILABLE` (dřív 500, audit KN-18).
  **Expirace zámku (V64, audit KN-5):** zámek **není trvalý**. `lockAccount` orazítkuje `security.users.locked_at` a třetí metoda `releaseExpiredLock(username)` — volaná z `login` **před** autentizací, protože Spring Security kontroluje stav účtu dřív než heslo — uvolní zámek, kterému uplynula lhůta `lockout.duration` (`application.yaml`, výchozí 15 min). Rozhoduje guardovaný `UserMapper.unlockIfLockExpired` (podmínky ve `WHERE`, čas se porovnává **výhradně v DB**, aby se nemíchaly hodiny aplikace a databáze); je taky `REQUIRES_NEW`, jinak by ho následné špatné heslo odrollovalo a staré počítadlo by účet hned zamklo znovu. Do V64 byl zámek trvalý a odemykal ho jen admin reset hesla — deset požadavků na veřejný `/auth/login` tak dokázalo natrvalo vyřadit jediný produkční admin účet. Admin reset (`UserServiceImpl.resetPassword` → `UserMapper.unlockAccount`, nuluje počítadlo i razítko) odemyká dál okamžitě; samostatná admin akce „jen odemknout" zavedená není.
- **Blacklist cleanup:** `BlacklistCleanupService` má `@Scheduled(fixedRate = 1 h)`; scheduling zapíná `@EnableScheduling` na `AutoservisApplication`.
- **Role:** `ROLE_ADMIN`, `ROLE_MANAGER`, `ROLE_MECHANIC`, `ROLE_CUSTOMER` (+ `ROLE_READONLY` jen v DB seedu, kód ji nevyužívá). **Od E7** je baseline `/api/**` = `hasAnyRole('ADMIN','MANAGER','MECHANIC')` v `SecurityConfig` (tím je `ROLE_CUSTOMER` od API odříznuta, audit K-10/R-4) a nad rámec baseline je **šestnáct míst vyhrazeno vedení** (`hasAnyRole('ADMIN','MANAGER')`): faktura `issue`/`pay`/`cancel`, celý `CashReceiptController` a `CreditNoteController`, (de)aktivace zákazníka, vozidla a zaměstnance, `create`/`update` zaměstnance, `PUT` profilu firmy a uzavření inventury. `UserController` je jediné **ADMIN-only** místo. Popis „autorizace se používá na dvou místech" tady stál z doby před E7 (audit 6.3); úplný seznam je v `api.md` §Autorizace rolí, pokrytí testy v `RoleAuthorizationTest`. Granularita mezi pracovními rolemi se přidává jen tam, kde funkce sama musí být vyhrazená — plošné rozvíjení zůstává dluhem (TD-24).
- **Správa uživatelů:** `UserController` (`/api/v1/users`) je admin CRUD nad účty — vytvoření, úprava emailu/rolí, de/aktivace, reset hesla. Guardy v `UserServiceImpl.deactivate`: nelze deaktivovat vlastní účet (`CANNOT_DEACTIVATE_SELF`) ani posledního enabled uživatele s `ROLE_ADMIN` (`CANNOT_DEACTIVATE_LAST_ADMIN`) — obojí `BusinessRuleException` → 422. Samostatně od toho `POST /auth/change-password` (v `AuthController`/`AuthenticationService`) řeší **self-service** změnu vlastního hesla — vyžaduje současné heslo, dostupné komukoli přihlášenému, ne jen adminovi. **Obě cesty odvolávají všechny refresh tokeny uživatele** (`revokeAllByUserId`): self-service od opravy K-6, admin reset až od auditu KN-6 — do té doby admin reagující na kompromitovaný účet neodstřihl nic a držitel ukradeného tokenu si obnovoval přístup dalších 7 dní, přestože `api.md` tvrdil opak. Detail: `docs/pruvodce/sprava-uzivatelu.md`.

Cookie parametry a známé nesoulady (maxAge vs. jwt.expiration, `secure=false`, refresh path natvrdo `v1`): viz `api.md` sekce Auth.

## 4. AI import dokladů — draft pipeline

Import ukládá **jen draft**; produkty, šarže a pohyby vznikají až potvrzením příjemky (review workflow, fáze 3). Detailní funkční popis: `docs/funkce/import-prijemek.md`.

Tok (`WarehouseImportServiceImpl`, `@Transactional`):

1. **Extrakce** — `PdfDocumentExtractionService.extract(pdf, DocumentType)`: PDF jako `Media(application/pdf)`, sdílený český SYSTEM_PROMPT + dodatek per typ dokladu (faktura / dodací list — typ volí **uživatel** při uploadu). Výsledek `.entity(DocumentExtractionResult.class)` — každé sledované pole je dvojice `{value, state}`, kde state = VERBATIM / DERIVED / ABSENT (model přiznává původ hodnoty, nikdy nevymýšlí). Písmenné kódy sazeb (LKQ „C") se opisují doslova + zvlášť rekapitulace DPH; skupinové řádky „Dodací list č. X" jsou `DELIVERY_NOTE_GROUP`, ne položky.
2. **Skládání draftu** — `DraftAssembler`: extrakce → kanonický `model/draft/ReceiptDraft` (jednotný mezistupeň všech kanálů — budoucí ISDOC/ruční formulář sem jen přidají adaptér). Mapuje písmenné sazby přes rekapitulaci, dopočítává chybějící součty **oběma směry** (sdílený `deriveLineAmounts`: základ → s DPH i **zpětně** s DPH → základ → jednotková cena u dokladů, které uvádějí jen cenu s DPH — typicky ručně psané dodací listy; DERIVED) a dosazuje defaulty (DEFAULTED) z `WarehouseImportProperties` (`warehouse.import.defaults`: sazba 21 %, CZK, ks, tolerance 0.05).
3. **Verifikace** — `DraftVerificationService` („kód počítá"): LINE_MATH (qty × cena, DPH), LINES_SUM_VS_RECAP, RECAP_SUM, SUBTOTAL_PLUS_VAT_EQ_TOTAL, LINES_SUM_VS_TOTAL, ICO_CHECKSUM (mod 11), SUPPLIER_KNOWN (lookup dle normalizovaného IČO — dodavatel se při importu NEZAKLÁDÁ). Nahrazuje dřívější `InvoiceReconciliationValidator`.
   **Na VERIFIED se povyšuje jen proti nezávislému protějšku** (audit KN-17): dopočtené hodnoty (DERIVED) porovnané s tím, z čeho vznikly, jsou tautologie — projdou vždycky. Každá kontrola proto nese i příznak `independent` a `reconciliation_ok` = všechny kontroly OK **a aspoň jedna aritmetická nezávislá**. Rekapitulace a celková částka z dokladu jsou plnohodnotný protějšek i pro dopočtený řádek; ručně psaný dodací list bez obojího zůstane neověřený. Detail: `docs/funkce/import-prijemek.md`.
4. **Idempotence** — `existsActiveDocument(supplierId, documentNumber)` (ignoruje REJECTED — partial unique index V39) → `ConflictException("DUPLICATE_IMPORT")`; kontroluje se jen při napárovaném dodavateli. Při **potvrzení** navíc `existsActiveDocumentBySupplierName` pro dodavatele bez čitelného IČO (audit KN-4b) — ten se zakládá znovu při každém importu, takže dedup podle `supplier_id` by neměl s čím porovnávat; běží **před** completeness gate, aby obsluha nedopracovávala doklad, který stejně skončí jako duplicita.
5. **Zápis** — jediný INSERT do `goods_receipts`: hlavička = projekce draftu (nullable), `draft_payload` = draft serializovaný Jackson `ObjectMapper` (JSONB přes `CAST(#{draftPayload} AS jsonb)`), status `PENDING_REVIEW`, originál PDF + `extraction_model`. **Žádné** products/goods_receipt_items/stock_movements.

Konfigurace: `spring.ai.anthropic.api-key=${ANTHROPIC_API_KEY}`, model `claude-sonnet-4-6`, `temperature: 0.0`, `max-tokens: 8192` (tracked fields zvětšují výstup). Endpoint a chybové stavy: `api.md`. Manuální ověření extrakce nad reálnými PDF (`import/`): `./mvnw test -Dtest=PdfDocumentExtractionManualTest -Dmanual.extraction=true` (vyžaduje ANTHROPIC_API_KEY).

**Review workflow** (`ReceiptReviewService(Impl)` + `ReceiptReviewMapper`): seznam/detail (bez BYTEA `source_pdf` — PDF má vlastní select), `updateDraft` (re-run verifikace + párování + sync hlavičkové projekce), `confirm` (completeness gate vč. nevyřešených SUGGESTED párování; katalogové číslo je povinné jen pro **nově zakládaný** produkt, napárovaný řádek SKU nepotřebuje → resolve/insert dodavatele → resolve produktů z `productMatch` / založení karty → **upsert `supplier_products` (samoučení; přeskočí se u řádku bez katalogového čísla — `supplier_sku` je NOT NULL)** → insert šarží + pohybů RECEIPT → `CONFIRMED`), `reject`. Přechody stavů guarded `WHERE status='PENDING_REVIEW'` s kontrolou počtu řádků → souběh = `ConflictException("RECEIPT_ALREADY_PROCESSED")`. Materializační inserty se sdílejí z `WarehouseImportMapper`.

**Párovací kaskáda** (`ProductMatchingService` + `ProductMatchingMapper`, V40): (1) přesná shoda v převodníku `supplier_products` → AUTO; (2) normalizované číslo dílu — plné i bez brand prefixu („EL 871.180" → „871180") proti `part_number_normalized`/normalizovanému sku → SUGGESTED (potvrzuje člověk); (3) pg_trgm podobnost názvu (> 0.45, top 3) → SUGGESTED; (4) NONE → nová karta. Prefix-parsing nikdy nevede na AUTO. CONFIRMED volby uživatele kaskáda nepřepisuje.

## 4b. Externí klienti — registr vozidel (dataovozidlech.cz)

První klasický REST klient v projektu (Spring AI má vlastní infrastrukturu). Vzor pro další externí služby:

- **Bean:** `vehicleRegistryRestClient` (`config/registry/VehicleRegistryClientConfig`) — `RestClient` s base-url, hlavičkou `API_KEY` a krátkými timeouty (`JdkClientHttpRequestFactory` nad JDK `HttpClient`; Boot modul `spring-boot-http-client` není na classpath). Properties: `registry.dataovozidlech.*` (`VehicleRegistryProperties`, `@ConfigurationProperties`), klíč `${DATAOVOZIDLECH_API_KEY:}` — prázdný default, aby boot nespadl bez klíče.
- **Klient:** `client/VehicleRegistryClient(Impl)` — `GET /api/vehicletechnicaldata/v2` s parametry `vin`/`tp`/`orv` (kombinují se jako AND). Odpověď `{Status, Data}`; tělo se čte jako String a parsuje ručně (**Jackson 3** — `tools.jackson`, Boot 4 default), protože rate-limit vrací plain-text. Vrací `Optional<RegistryFetchResult>` (mapovaný record + surový `Data` JSON pro JSONB) — empty = vozidlo v registru není.
- **Chyby → `RegistryUnavailableException(code)` → 503** (`GlobalExceptionHandler`): `REGISTRY_RATE_LIMITED` (HTTP 429 nebo textové tělo, limit 27 dotazů/min), `REGISTRY_AUTH_FAILED` (401/403, log ERROR), `REGISTRY_TIMEOUT` (síť), `REGISTRY_ERROR` (ostatní). „Nenalezeno" NENÍ 503 — service vrství na `BusinessRuleException` → 422.
- **Mapování:** `RegistryConverter` — defenzivní parsing (`Palivo` → `FuelType`, výkon z „50 / 5000", ANO/NE, ISO datetime → LocalDate); nerozpoznaná hodnota → `null`, nikdy odhad.
- **Sémantika volání:** strict (refresh/lookup endpointy propouštějí chybu) vs. best-effort (`tryRefreshAfterCreate` při založení vozidla jen loguje WARN). HTTP volání běží **mimo DB transakci** — persistence snapshotu je jediný INSERT.

### ARES (ares.gov.cz)

Druhý externí klient, postavený podle stejného vzoru — jednodušší, protože nic nepersistuje (lookup jen pro prefill formuláře zákazníka):

- **Bean:** `aresRestClient` (`config/registry/AresClientConfig`), properties `registry.ares.*` (`AresProperties`) — **bez API klíče**, ARES je veřejné API MF ČR.
- **Klient:** `client/AresClient(Impl)` — `GET /ekonomicke-subjekty/{ico}`; HTTP 404 = subjekt neexistuje → `Optional.empty()`. Tělo se parsuje ručně přes `JsonNode` a mapuje rovnou na `AresDto.LookupResponse` (obchodniJmeno, dic — jen ve formátu `CZ\d{8,10}`, jinak null; sídlo: ulice s fallbackem část obce → obec pro vesnice bez ulic, číslo popisné/orientační „1561/4a", PSČ vč. `pscTxt` pro zahraniční sídla).
- **Chyby → `AresUnavailableException(code)` → 503**: `ARES_RATE_LIMITED` (429), `ARES_TIMEOUT` (síť), `ARES_ERROR` (ostatní).
- **Service:** `AresLookupService(Impl)` — validace IČO (8 číslic + kontrolní číslice mod 11; vadné IČO → 422 `INVALID_ICO` bez volání ARES), nenalezeno → 422 `SUBJECT_NOT_IN_ARES`. Bez `@Transactional` a bez DB.

## 5. Generování PDF dokladů

`InvoiceDocumentServiceImpl`: Thymeleaf `pdf/invoice.html` (+ `invoice-styles.html` přes `th:replace`) → HTML → openhtmltopdf (`useFastMode`) → byte[]. Stejný postup a tytéž styly používají `CreditNoteDocumentServiceImpl` (`pdf/credit-note`), `CashReceiptDocumentServiceImpl` (`pdf/cash-receipt`) a `OrderDocumentServiceImpl` (`pdf/order-protocol` — **zakázkový list**, KN-28).

Zakázkový list je jediný z nich, který **není daňový doklad**: tiskne se ze živých dat zakázky, zákazníka a profilu firmy, bez číselné řady a bez snapshotů stran. Jediná zmrazená hodnota je stav tachometru při příjmu (`orders.mileage_km_at_intake`, V70) — je na podepsaném papíře. Endpoint je `GET /orders/{id}/protocol`, ne `/pdf`, protože zakázka sama doklad není. Viz `funkce/zakazkovy-list.md`.

Totéž PDF faktury odchází i **e-mailem zákazníkovi** (`InvoiceEmailServiceImpl`, 2026-08-08): `JavaMailSender` přes SMTP účet servisu (`spring.mail.*`, Seznam; login je secret s prázdným defaultem → bez konfigurace 422 `EMAIL_NOT_CONFIGURED`). Úspěšné odeslání razítkuje předání (`handOver`), selhané ne; po odeslání se kopie zprávy uloží přes IMAP do složky Odeslaných (best-effort — Seznam SMTP poštu do Odeslaných sám neukládá). Viz `funkce/odesilani-faktur-emailem.md`.

- **Fonty:** DejaVuSans (+Bold) z `templates/fonts/` — česká diakritika; registrace best-effort (bez fontu se PDF přesto vygeneruje).
- **Obrázky:** `templates/images/logo.png`, `signature.png` jako base64 data-URI (`avatar.jpg` v adresáři je nepoužitý — dead asset).
- **QR Platba (SPAYD):** `SPD*1.0*ACC:<IBAN>*AM:<totalToPay>*CC:CZK[*X-VS:<VS>]*MSG:<text>` — bez BIC, zpráva bez diakritiky, max 60 znaků; ZXing QR 600×600. Bez IBAN dodavatele se QR negeneruje. `AM` je částka **k úhradě** (u hotovosti zaokrouhlená, V67) — QR nesmí znít na jinou částku než doklad. QR se negeneruje ani u **zaplacené a stornované** faktury (audit KN-7: naskenovat QR na zaplaceném dokladu = duplicitní platba).

## 6. Konfigurace

| Soubor | Účel | Klíčové odlišnosti |
|---|---|---|
| `application.yaml` | base | DB `localhost:5432/autoservis` user `postgres`, heslo `${DB_PASSWORD}`; logging DEBUG; `jwt.expiration` 8 h; `config.import: optional:application-local.yaml`; multipart max 10 MB |
| `application-prod.yaml` | profil `prod` | DB user `autoservis_app`; logging INFO/WARN; `jwt.expiration` 15 min |
| `application-local.yaml` | lokální override, **gitignorováno** | skutečné secrets; committed je jen `.example` |

Env proměnné: `DB_PASSWORD`, `JWT_SECRET`, `ANTHROPIC_API_KEY`, `DATAOVOZIDLECH_API_KEY` (a `DB_PASSWORD` i pro Flyway Maven plugin). Ukládání klíčů: `nasazeni.md`.

## 7. Testy

Infrastruktura: **`AbstractIntegrationTest`** — společná základna integračních testů. `@SpringBootTest` + `@ActiveProfiles("test")` + Testcontainers **singleton container pattern**: jeden statický `PostgreSQLContainer("postgres:16-alpine")` startovaný ve static bloku (záměrně bez `@Testcontainers`/`@Container` — ty by kontejner zastavily po první testovací třídě), `@DynamicPropertySource` přesměruje datasource, Flyway migrace běží automaticky. Díky cache Spring kontextu bootuje kontext jen jednou za běh. Obsahuje hack `System.setProperty("api.version","1.47")` kvůli docker-java.

Profil `test` (`src/test/resources/application-test.yaml`, audit E4.1) dodává neškodný testovací `jwt.secret` a dummy `spring.ai.anthropic.api-key`, takže suita **nepotřebuje env proměnné** (`JWT_SECRET`/`ANTHROPIC_API_KEY`) ani gitignorovaný `application-local.yaml` — jen běžící Docker. AI extrakce se v testech mockuje (`@MockitoBean`), reálné volání se nekoná.

**Stav 2026-07-24 (audit E0–E4):** **742 testů** (1 přeskočen = manuální PDF), celá suite zelená. Přibyl mj. e2e test JWT filtru a cookie flow (`JwtAuthFlowTest`, audit K-15 — dřív se filtr přes `.with(user(...))` obcházel). Historie: 720 testů při uzavření TD-14 (2026-07-23).

Spuštění: `./mvnw test` — vyžaduje **jen** běžící Docker (Testcontainers), lokální dev DB ani secrets v prostředí nejsou potřeba.

### 7.1 Nástroje pokrytí

- **JaCoCo** (`jacoco-maven-plugin`): report po `test` fázi do `target/site/jacoco/`, poté `check` vynutí prahy — BUNDLE instrukce ≥ 80 %, větve ≥ 68 %; větvové pokrytí kritických balíčků (`service`, `service.impl`, `security.service`, `model.converter`) ≥ 65 %. Prahy jsou pod dosaženou hodnotou s rezervou, zvyšovat s přibývajícím pokrytím. **Cíl je větvové pokrytí, ne řádková procenta.**
- **PIT / pitest** (`pitest-maven`, ruční spuštění `./mvnw test-compile org.pitest:pitest-maven:mutationCoverage`): mutační testování balíčků `service.*`, `security.service.*` a od auditu KN-21 také `model.converter.*` a `model.enums.*` — důkaz, že testy nejsou plané. Manuální `PdfDocumentExtractionManualTest` je z běhu vyloučen.

### 7.2 Organizace testů

- **Unit bez Spring kontextu** (rychlé, bez DB) — čistá logika a mapování: 17 konvertorů (`model/converter/*ConverterTest`), `InvoiceStatusTest` (matice přechodů 4×4), `OrderStatusTest` (matice přechodů 7×7), `SupplierNormalizerTest`, `TokenHasherTest`, `DraftAssemblerTest`/`DraftAssemblerMappingTest`/`DraftAssemblerDerivationTest` (skládání draftu), `DraftVerificationServiceTest`/`DraftVerificationSumsTest` (deterministické kontroly „kód počítá"), `ProductMatchingServiceTest`, `GlobalExceptionHandlerTest`, `RegistryConverterTest`, `WarehouseImportPropertiesTest`.
- **Integrační** (`AbstractIntegrationTest`, reálná DB) — service vrstva a stavové automaty: `UserServiceTest`, `VehicleServiceTest`, `MileageServiceTest`, `SupplierServiceTest`, `CompanyProfileServiceTest`, `CustomerCrudServiceTest`/`CustomerServiceTest`/`CustomerValidationTest`, `OrderCrudServiceTest`/`OrderSearchTest`/`OrderStatusTransitionTest` (stavový automat zakázky + podmínky zrušení)/`OrderProtocolDocumentTest` (zakázkový list + tachometr při příjmu), `ProductCrudServiceTest`/`ProductUnitValidationTest`/`ProductDeactivationTest`, `InvoiceLifecycleTest`/`InvoiceStatusTransitionTest` (celý životní cyklus faktury), `StockTakeTest`/`StockTakeStateMachineTest` (inventura), `ReceiptReviewServiceTest`, `ManualStockMovementTest`, `LowStockTest`, `StockValuationTest`, `OrderItemImportTest`/`OrderItemInvoiceLockTest`, `IsdocImportTest`, `WarehouseImportServiceTest`, `VehicleRegistryServiceTest`, `AresLookupServiceTest`, `ListSortingTest`, `NullGuardTest`.
- **Security** (`security/*`) — `RefreshTokenRotationTest` (rotace + detekce reuse), `JwtServiceTest`, `ChangePasswordTest`, `SecurityServicesTest`, `LoginLockoutTest`, `TokenBlacklistTest`, `SecurityProblemWriterTest`.
- **Web / kontrakt** — `ProblemDetailContractTest` (MockMvc; RFC 9457 kontrakt napříč moduly: 400/401/403/404/409/422/503, `code`+`errors[]`+`Content-Type`).
- **DB triggery** — `database/DatabaseTriggerTest` (čte vygenerované hodnoty přímo z DB přes `JdbcTemplate`: čísla ZNK-/ZAK-/faktura, `updated_at`, sync tachometru, skladový trigger, časy/zóny).
- **Klient / smoke** — `VehicleRegistryClientTest`, `AresClientTest`, `AutoservisApplicationTests` (`contextLoads`).

**Konvence:** principal se předává jako skutečný `AppUserDetails` přes `user(...)`, ne `@WithMockUser` (dal by null u `@AuthenticationPrincipal AppUserDetails`). Testy volající `login()` po zápisu do `security.users` musí být **bez `@Transactional`** (`LoginAttemptService` běží v `REQUIRES_NEW` a čekal by na zámek řádku) a uklízet v `@AfterEach` — vzor `LoginLockoutTest`, `ChangePasswordTest`. Zápis do DB se ověřuje přímým `JdbcTemplate` dotazem, ne opětovným čtením přes MyBatis (lokální cache SqlSession vrátí týž objekt).

## 8. Známé zvláštnosti (shrnutí, detaily v tech-dluhy.md)

- Cookie nesoulady + `secure=false` (viz `api.md`).
- TODO komentáře: `InvoiceExtractionResult` (rename ico/dic), `ProductDto`.
- Dead: `avatar.jpg` v templates/images, nepoužitý import Flyway `JsonUtils` v `SupplierNormalizer`.
