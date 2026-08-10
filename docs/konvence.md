# konvence.md — Pravidla a konvence kódu

> Normativní pravidla projektu + detailní příklady. Číst při práci na Java vrstvě, REST API,
> frontendu nebo při přidávání nového modulu. Zkrácený výtah nejkritičtějších pravidel je v `CLAUDE.md`.

## 1. Pravidla — co VŽDY dělat

| # | Pravidlo |
|---|---|
| R-01 | SQL **výhradně v XML souborech** — anotace `@Select` / `@Insert` se nepoužívají. ⚠️ Historická výjimka: `security/mapper/` (BlacklistMapper, RefreshTokenMapper, částečně UserMapper) používá inline anotace — viz `tech-dluhy.md` |
| R-02 | Tabulky v XML vždy **plně kvalifikované**: `security.users`, `"order".orders` |
| R-03 | Service metoda po INSERT/UPDATE **vždy vrátí objekt znovu načtený z DB** (verify-and-fetch) |
| R-04 | Auditní pole (`created_by`, `handled_by`) **doplní server** z `@AuthenticationPrincipal` |
| R-05 | Po seed datech s explicitními ID vždy volat `setval()` pro synchronizaci sekvence |
| R-06 | Záznamy se **nemažou** — soft-delete přes `is_active = false`. ⚠️ Výjimky: **zakázky** soft-delete nemají — ruší se stavem `CANCELLED` (doklad, odkazují na něj faktury a skladové pohyby); sloupec `is_active` u zakázek je vestigiální, viz `tech-dluhy.md` TD-67. **Koncepty faktur a opravných dokladů** se naopak mažou tvrdě (`DELETE`) — nejsou doklady, viz §18 |
| R-07 | `updated_at` aktualizuje **databázový trigger**, ne aplikace |
| R-08 | `if` bloky mají **vždy složené závorky** — s jedinou výjimkou: **jednořádková guard klauzule**, kde je tělo na témže řádku jako podmínka (`if (id == null) return;`). Zpřesněno 2026-07-31: kód tuhle podobu používá 73×, je čitelná a `goto fail` u ní nevzniká (tělo nelze omylem „přidat pod" podmínku). Rozhodnutí uživatele — viz §8 |
| R-09 | Hotová migrace se **nikdy nemění** — změna = nový soubor s vyšším číslem. ⚠️ Jednorázová odsouhlasená výjimka 2026-08-10: překlad komentářů V1–V94 do češtiny (jen `--` komentáře a texty `COMMENT ON`; SQL strojově ověřeno beze změny) — vyžaduje `flyway repair` na existujících DB, viz docs/preklad-migraci-runbook.md |
| R-10 | Nové moduly: **strict** `findById` (`WHERE id = ? AND is_active = TRUE`) |
| R-11 | Mapování domain↔DTO přes ruční `@Component` konvertory (`model/converter/`) — MapStruct se nepoužívá |
| R-12 | Dead code **smazat** — „možná se bude hodit" není důvod ponechat |
| R-13 | Business validaci (FK, unique) provádět v **service vrstvě** → čistá `BusinessRuleException` |
| R-14 | `id` patří do **URL** (path variable), ne do těla requestu — detail sekce 16 |
| R-15 | U AI integrace platí **„AI čte, kód počítá"** — AI vrací přečtená data, výpočty a validace dělá Java |
| R-16 | **Komentáře česky** — Javadoc, JSDoc, inline, XML, SQL i konfigurační komentáře se píší česky (odborné termíny jako mapper, trigger, DTO zůstávají anglicky). Sjednoceno plošným překladem 2026-08-10 (větev `preklad-komentaru`, evidence docs/preklad-komentaru.md) |

> Původní pravidlo R-15 o „filesystem konektoru" (bash nevidí soubory) platilo pro jiné AI prostředí
> a bylo odstraněno — v Claude Code má shell i nástroje plný přístup k repozitáři.

## 2. Zákazy — co NIKDY nedělat

| # | Zákaz |
|---|---|
| N-01 | **Nikdy `null`** ze service — buď `Optional<X>`, nebo `ResourceNotFoundException` |
| N-02 | **Nikdy UUID** jako PK — projekt používá `BIGSERIAL` / `Long` |
| N-03 | **Nikdy `hikari.connection-init-sql`** — způsobuje selhání startu s Flyway |
| N-04 | **Nikdy `flyway.schemas`** v konfiguraci — schémata spravují migrace přes `CREATE SCHEMA` |
| N-05 | **Nikdy SQL v Java anotacích** (`@Select` atp.) |
| N-06 | **Nikdy `createdBy` přes DTO** — výhradně server-side ze SecurityContext |
| N-07 | **Nikdy query parametr** jako primární identifikátor resource — správně: path variable |
| N-08 | **Nikdy JPA/Hibernate** — persistence výhradně přes MyBatis |
| N-09 | **Nikdy plaintext heslo** v DB — vždy BCrypt hash |
| N-10 | **Nikdy měnit** verzovanou Flyway migraci úspěšně aplikovanou do DB |
| N-11 | **Nikdy `formData.id`** v URL PUT callu — používej `id` z `useParams()` |

---

## 3. Boolean stavová pole

Java pole pojmenovat **bez prefixu `is`** — Lombok pak generuje správné gettery/settery.

```java
// ✅ SPRÁVNĚ
private boolean active;      // DB sloupec: is_active → MyBatis mapuje přes map-underscore-to-camel-case
private boolean enabled;

// Lombok generuje: isActive() / setActive(boolean)

// ❌ ŠPATNĚ
private boolean isActive;    // Lombok by generoval isIsActive()
private Boolean active;      // Wrapper Boolean POUZE pokud NULL má jiný význam než false
```

Pozn.: v UpdateRequest DTO je naopak wrapper `Boolean` žádoucí (null = „pole nebylo v requestu, nech stávající hodnotu") — implementováno v `CustomerDto.UpdateRequest.gdprConsent`/`marketingConsent` (TD-23, vyřešeno): `CustomerConverter.applyUpdate` pole aplikuje jen když `!= null`.

## 4. Návratové hodnoty service metod — nikdy null

```java
// ✅ entita nenalezena → výjimka
public VehicleDto.DetailResponse getById(Long id) {
    return mapper.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Vehicle", id));
}

// ✅ „není přítomen" je legitimní stav → Optional
public Optional<CustomerDto.DetailResponse> findByUserId(Long userId) {
    return mapper.findByUserId(userId).map(converter::toDetailResponse);
}

// ❌ nikdy vracet null
return mapper.findById(id).orElse(null);
```

## 5. Pattern „verify-and-fetch" pro UPDATE/DELETE

Po každé write operaci: ověř existenci (affectedRows), pak načti aktuální stav z DB (trigger mohl změnit `updated_at` aj.).

```java
public VehicleDto.DetailResponse deactivate(Long id) {
    int affectedRows = mapper.deactivate(id);
    return verifyAndFetch(id, affectedRows);
}

private VehicleDto.DetailResponse verifyAndFetch(Long id, int affectedRows) {
    if (affectedRows == 0) {
        throw new ResourceNotFoundException("Vehicle", id);          // 404
    }
    return mapper.findByIdIncludingInactive(id)
            .map(converter::toDetailResponse)
            .orElseThrow(() -> new IllegalStateException(            // 500 — invariant violation
                    "Vehicle " + id + " disappeared between UPDATE and SELECT"));
}

// ❌ vrátit vstupní data bez nového načtení z DB → stale data (updated_at z triggeru chybí)
```

## 6. Audit pole `created_by` — nikdy z klienta

```java
// Controller — userId ze SecurityContext
@PostMapping
public ResponseEntity<VehicleDto.DetailResponse> create(
        @RequestBody @Valid VehicleDto.CreateRequest req,
        @AuthenticationPrincipal AppUserDetails currentUser) {
    return ResponseEntity.status(HttpStatus.CREATED)
            .body(service.create(req, currentUser.getUserId()));
}

// Service — userId jako samostatný parametr
public VehicleDto.DetailResponse create(VehicleDto.CreateRequest req, Long currentUserId) {
    Vehicle vehicle = converter.toDomain(req);
    vehicle.setCreatedBy(currentUserId);   // server-side, ne z DTO
    mapper.insert(vehicle);
    return verifyAndFetch(vehicle.getId(), 1);
}

// DTO — createdBy v CreateRequest NENÍ
```

## 7. Konvertory domain ↔ DTO — ruční `@Component`

```java
@Component
public class VehicleConverter {
    public Vehicle toDomain(VehicleDto.CreateRequest req) { ... }
    public void applyUpdate(Vehicle existing, VehicleDto.UpdateRequest req) { ... }
    public VehicleDto.DetailResponse toDetailResponse(Vehicle v) { ... }
}
```

Co konvertor neřeší: `createdBy`/`updatedBy` (service ze SecurityContext), timestamps (DB default/trigger).

## 8. `if` bloky — složené závorky, s jedinou výjimkou

```java
// ❌ Apple goto-fail bug (2014) — tělo na dalším řádku bez závorek
if (affectedRows == 0)
    throw new ResourceNotFoundException("Vehicle", id);

// ✅ standard
if (affectedRows == 0) {
    throw new ResourceNotFoundException("Vehicle", id);
}

// ✅ povolená výjimka: jednořádková guard klauzule (podmínka i tělo na jednom řádku)
if (id == null) return;
if (rows.isEmpty()) return List.of();
```

**Proč ta výjimka existuje** (zpřesněno 2026-07-31, audit 2026-07-30): pravidlo v původní
bezvýjimečné podobě kód porušoval **73×** — vždy jen v téhle jedné podobě. To není nekázeň, ale
špatně formulované pravidlo: nebezpečí `goto fail` spočívá v tom, že se pod bezzávorkovou podmínku
**přidá druhý řádek**, který se pak vykoná vždy. U guardu na jednom řádku tahle záměna nevznikne —
kdo připisuje druhý příkaz, musí nejdřív udělat nový řádek, a tam už závorky doplní.

Dorovnávat 73 míst bylo zamítnuto: velký diff bez změny chování a s vlastním rizikem.
Vynucení Checkstylem zůstává neudělané — pravidlo je zatím na dohodě, ne na nástroji.

## 9. HTTP statusy

| Status | Kdy |
|---|---|
| 200 OK | GET, PUT update, soft delete (deactivate), activate |
| 201 Created | POST — vytvoření nového resource |
| 204 No Content | DELETE bez těla (položky), reorder |
| 400 Bad Request | validační chyba z `@Valid` |
| 401 Unauthorized | nepřihlášený uživatel |
| 403 Forbidden | nedostatečná role |
| 404 Not Found | `ResourceNotFoundException` |
| 409 Conflict | duplicitní uživatel / idempotence importu |
| 422 Unprocessable Entity | `BusinessRuleException`, `DataIntegrityViolation` |
| 500 Internal Server Error | `IllegalStateException` — invariant violation |

Formát chybové odpovědi (RFC 9457 ProblemDetail + `errors[]`): viz `api.md`.

## 10. REST URL konvence

```
GET    /api/v1/vehicles                  → kolekce (query params = filtr)
GET    /api/v1/vehicles/{id}             → konkrétní resource
POST   /api/v1/vehicles                  → vytvoření
PUT    /api/v1/vehicles/{id}             → úplný update
DELETE /api/v1/vehicles/{id}             → soft delete (200 s deaktivovaným objektem)
POST   /api/v1/vehicles/{id}/activate    → akce na resource
GET    /api/v1/vehicles?customerId=5     → filtr kolekce

❌ GET /api/v1/vehicles?id=5             → id je path variable, ne query param
```

## 11. Slovník metod napříč vrstvami

| Akce | Mapper | Service | Controller (HTTP) |
|---|---|---|---|
| Vytvoření | `insert(entity)` | `create(dto, userId)` | `create(...)` POST → 201 |
| Soft delete | `deactivate(id)` | `deactivate(id)` | `delete(...)` DELETE → 200 |
| Reaktivace | `activate(id)` | `activate(id)` | `activate(...)` POST → 200 |
| Detail | `findById(id)` | `getById(id)` | `getById(...)` GET → 200 |
| Seznam stránkovaný | `search(params)` + `countSearch(params)` | `getPage(params)` | `getAll(...)` GET → 200 |
| Existence check | `existsBy{Field}(value)` | (privátní validace) | — |

## 12. DTO — namespace pattern

```java
public final class VehicleDto {
    private VehicleDto() {}   // namespace, ne bean

    public static class CreateRequest {      // jen to, co klient smí vyplnit
        @NotBlank @Pattern(regexp = "^[A-HJ-NPR-Z0-9]{17}$")
        private String vin;
        @NotNull  private Long customerId;
        // createdBy zde NENÍ
    }
    public static class UpdateRequest {      // bez immutable polí
        @Size(max = 15) private String licensePlate;
        // vin: NENÍ — immutable
    }
    public static class DetailResponse { /* plná odpověď pro GET /{id} */ }
    public static class ListResponse   { /* zúžená odpověď pro výpis; bez internal_note */ }
}
```

Stránkování: `*SearchParams extends BaseParams/SearchParams` (page 1-based, pageSize, sortDesc, search) → `PagedResponse<T>`. Autocomplete parametry jako **record** s `effectiveLimit()` / `normalizedQuery()`.

## 13. `findById` — strict vs permissive

**Strict** (standard pro nové moduly):
```xml
<select id="findById" resultMap="vehicleResultMap">
    SELECT * FROM vehicle.vehicles WHERE id = #{id} AND is_active = TRUE
</select>
<select id="findByIdIncludingInactive" resultMap="vehicleResultMap">
    SELECT * FROM vehicle.vehicles WHERE id = #{id}
</select>
```

**Permissive** (customer modul — historická výjimka, evidováno jako TD-08): bez filtru `is_active`, filtr si aplikuje service. Pro **nové moduly vždy strict**.

## 14. Flyway

### Tři locations podle prostředí

| Location | Obsah | Kde běží |
|---|---|---|
| `classpath:db/migration` | schéma (DDL) + nutná infra (V35 `company_profile`) | všude (prod, dev, test) |
| `classpath:db/demo` | demo/ukázková data (dev seedy: zákazníci, vozidla, zakázky…) | dev/local + test |
| `classpath:db/prod` | produkční seed (role + jeden admin) + produkční varianty migrací (např. schema-only dvojče V58 bez demo seedu) | jen prod |

- **Demo data nikdy do `db/migration`** — dostala by se do produkce. Ukázkový seed → `db/demo`, produkční bootstrap → `db/prod`. (Přesun commitnuté migrace mezi složkami je OK — checksum se nemění; Flyway páruje podle verze.)
- Číslování `V{n}` je **globální přes všechny tři složky** (produkční seed `db/prod/V60` obsadil 60 → schéma migrace pokračují od **V61**).
- Základní profil (dev/test) = `db/migration,db/demo`; `application-prod.yaml` přepisuje na `db/migration,db/prod` + placeholder `admin_password_hash` z env `ADMIN_PASSWORD_HASH` (viz `docs/nasazeni.md`).

```yaml
spring:
  flyway:
    enabled: true
    locations: classpath:db/migration,classpath:db/demo   # základ (dev/test); prod → db/migration,db/prod
    default-schema: public        # flyway_schema_history jde do public
    clean-disabled: true
    validate-on-migrate: true
    out-of-order: false
    # schemas: NESMÍ být nastaveno — schémata tvoří migrace přes CREATE SCHEMA
  datasource:
    hikari:
      # connection-init-sql NESMÍ být nastaveno — rozbíjí start s Flyway
```

Po seedech s explicitními ID:
```sql
SELECT setval('security.users_id_seq', (SELECT MAX(id) FROM security.users));
-- … pro každou tabulku se seed daty
```

Pozn.: přidání ENUM hodnoty + její použití nelze v jedné transakci — viz V17 (`flyway:noAutoCommit` + explicitní COMMIT).

## 15. MyBatis

```yaml
mybatis:
  mapper-locations: classpath:mapper/**/*.xml
  type-aliases-package: cz.palo.autoservis.model.domain
  type-handlers-package: cz.palo.autoservis.config.mybatis
  configuration:
    map-underscore-to-camel-case: true    # is_active → active
    cache-enabled: true
    lazy-loading-enabled: true
```

```xml
<!-- Plně kvalifikované názvy tabulek; schéma "order" v uvozovkách -->
<select id="findById" resultMap="vehicleResultMap">
    SELECT v.id, v.customer_id, v.vin, v.is_active
    FROM vehicle.vehicles v
    JOIN customer.customers c ON c.id = v.customer_id
    WHERE v.id = #{id} AND v.is_active = TRUE
</select>

<!-- ENUM parametry: jdbcType=OTHER (PgEnumTypeHandler) -->
<insert id="insert">
    INSERT INTO vehicle.vehicles (fuel_type, ...)
    VALUES (#{fuelType, jdbcType=OTHER}, ...)
</insert>
```

Při přejmenování DB sloupce projít **celý řetězec**: migrace → doména → `*Mapper.xml` (resultMap i INSERT/UPDATE/WHERE) → service → DTO/converter → frontend. Vynechání XML se projeví až za běhu, ne při kompilaci.

## 16. REST — `id` patří do URL, ne do těla

```
PUT /api/v1/customers/25        ← identifikace (KDE)
Body: { firstName: "Jan", ... } ← data (CO) — id zde NENÍ
```

Frontend: `id` z `useParams()`, ne z `formData`. Backend: `UpdateRequest` DTO `id` neobsahuje — `@PathVariable` je autoritativní.

## 17. Frontend konvence

- Soubory: `XxxPage[Create|Detail|Edit].jsx`, `XxxTable.jsx`, `XxxForm.jsx`, `useXxxRowActions.js`.
- API volání přes `api` klienta (`src/api/api.js`), vždy relativní cesty (`/customers/...`), cookies automaticky.
- Chyby backendu: **vždy `problemMessage(err, fallback)`** z `api/api.js` — složí `detail` i konkrétní hlášky z `errors[]` (validace vrací konstantní `detail` a pole je v `errors[]`; čtení jen `detail` znamenalo, že uživatel dostal „Ověření zadaných údajů selhalo" bez jména pole — audit KN-14). Fallback vždy česky a **nikdy `err.message`** — to je surové tělo odpovědi nebo anglické „Failed to fetch". Uživatelské hlášky přes `useAlert()` (`addAlert(msg, 'success'|'danger')`), ne nativní `alert()`.
- **Načítání dat vždy v `try/catch`.** Selhání nesmí skončit jako prázdný seznam („Zatím žádní zákazníci." u 500) ani jako věčný spinner (404 na detailu): seznam a sekce → `LoadErrorState` (hláška + „Zkusit znovu") místo tabulky, detail stránky → `ErrorState` s cestou zpět. Prázdný stav a chyba jsou dva různé stavy.
- Formulářová validace **zrcadlí DTO** přes `api/validation.js` (vzory a délky IČO, DIČ, telefonu, PSČ, jmen) — server zůstává autoritativní, ale formulář nesmí být volnější než on.
- Sdílený formulář pro create i edit (`isEditMode`, `initialData`, `onSave`).
- Seznamy: debounce 400 ms, `PagedResponse` stránkování, refresh přes `refreshKey`.
- UI texty česky; enum labely/badge centrálně v `src/api/format.js`, ne inline.
- Bootstrap first; MUI jen kde už je (`TableRowActionMenu`, `PaginatorRounded`) — rozhodnutí R-1.
- **Jeden vzor na prvek** (`docs/frontend.md` §10): hlavička stránky jen přes `PageHeader`,
  formulářové sekce přes `FormSection` + `FormActions`, tabulka vždy v `.table-responsive`.
- **Badge jen přes `StatusBadge`** s tónem z `format.js` — žádné `text-bg-*` ani vlastní CSS třídy.
- **Modaly jen přes `Modal`** — `.modal show d-block` se nikde jinde psát nesmí (Esc, focus trap
  a zámek scrollu má jen tato komponenta).
- **Barva tlačítka podle důsledku akce** (`frontend.md` §10.8): modrá = vratná hlavní akce,
  zelená = nevratný posun procesu, šedý obrys = neutrální, červený obrys = rušící;
  nejvýš jedno plné tlačítko na obrazovku.

## 18. Specifické konvence projektu

| Věc | Pravidlo |
|---|---|
| Číslo zákazníka | `ZNK-{rok}-{4č.}` — trigger V9, sekvence CACHE 1 |
| Číslo zakázky | `ZAK-{rok}-{4č.}` — trigger V11 |
| Číslo faktury | dle **masky** z `billing.company_profile.invoice_number_mask` (default `{RRRR}{MM}{NNN}` = `YYYYMM{3č.}`) — skládá **aplikace při VYSTAVENÍ** (`DocumentNumberMask`, MAX+1 přes regex, celé pod `pg_advisory_xact_lock` uvnitř téže transakce). **Koncept číslo nemá** — jinak by ho zrušený koncept spálil a v řadě zůstala mezera. Číslo posílá dialog vystavení: přepínač `invoice_number_auto` řídí jen jeho **předvyplnění** (zapnuto = návrh podle masky, vypnuto = prázdné pole), zapsat lze v obou režimech libovolné neprázdné číslo ≤ 20 znaků — maska nic nevynucuje (číslo mimo masku řadu neposune). VS předvyplňuje dialog z číslic čísla. Unikátnost `uq_invoice_number`, po vystavení neměnné (trigger V71). Detaily `funkce/cislovani-faktur.md`. **Výjimka z pravidla „čísla dokladů řeší DB triggery"** |
| Číslo pokladního dokladu | od **V92** týž mechanismus jako faktura: maska `cash_receipt_number_mask` (default `PPD{RRRR}{MM}{NNN}` = historický formát), skládá aplikace při vystavení pod zámkem řady, číslo posílá dialog (editovatelné), unikátnost `uq_cash_receipt_number`, neměnné (trigger V92). Zdroj předvyplnění řídí ENUM `cash_receipt_number_source` (**V93**): `MASK` = návrh dle masky, `INVOICE` = číslo hrazené faktury (přání účetní — párování zadarmo; hlídání děr PPD je v tomto režimu deaktivované, řadu hlídá kontrola faktur), `MANUAL` = prázdné pole. PPD **nemá koncept** — číslo dostává hned; omylem vystavený doklad jde **stornovat** (V68, zůstává v řadě s důvodem) nebo **smazat** (V92, rozhodnutí uživatele 2026-08-09 — číslo se uvolní, díru uprostřed řady zavře ruční zápis; hlídání děr `cash_receipt_gap_check_*`). Sdílená třída `DocumentNumberMask`. **Druhá výjimka z pravidla „čísla dokladů řeší DB triggery"** |
| Mazání konceptů (výjimka z R-06) | **Koncept faktury i opravného dokladu se maže**, nestornuje (`DELETE /invoices/{id}`, `DELETE /credit-notes/{id}`, oba guardované `WHERE status='DRAFT'`). Důvod: koncept nemá číslo, nikdy neopustil firmu a není vykázaný v DPH — není tedy co archivovat a stornované rozpracované faktury by jen zaplňovaly tabulku (rozhodnutí uživatele 2026-08-02). Vystavený doklad smazat nelze — opravuje se dobropisem (KN-1). Stav `CANCELLED` zůstává v enumu **jen pro data stornovaná dřív**; aplikace ho už nenastaví |
| Faktura ↔ zakázka | **1:1 pro aktivní fakturu** (`uq_invoices_order_active` — částečný index `WHERE status <> 'CANCELLED'`, V48); po stornu lze vystavit novou; dělená fakturace = více zakázek |
| VIN validace | `^[A-HJ-NPR-Z0-9]{17}$` — DB CHECK i DTO `@Pattern` |
| Full-text search | `customer.czech_simple` s `unaccent` → `Novak` najde `Novák` |
| Stránkování | `PagedResponse<T>` + `*SearchParams` |
| Verze API | `/api/v1/...` (`{version}` path variable) |
| CORS (dev) | `localhost:5173`, `127.0.0.1:5173` |
| Seed účty (dev) | `admin` / `manager` / `mechanic`, heslo `Password1!` — **jen dev/test** (`db/demo/V3`). Produkce má jediného admina z `db/prod/V60` s heslem z env (viz `docs/nasazeni.md`) |

## 19. Rolová autorizace endpointů (`@PreAuthorize`)

Výchozí stav: `/api/**` (kromě účtových `/api/*/auth/**`, které stačí `authenticated()`) vyžaduje **některou z pracovních rolí** — `hasAnyRole('ADMIN','MANAGER','MECHANIC')` v `SecurityConfig`. Role `ROLE_CUSTOMER` (zákaznický portál, zatím neexistuje) je tím od API odříznuta (audit K-10 / R-4) — dokud portál nevznikne, nemá tato role kam přistupovat. Mezi pracovními rolemi se granularita přidává **výjimečně** přes `@PreAuthorize`, jen když funkce sama o sobě musí být vyhrazená konkrétní roli (ne jako plošné pravidlo).

- Používá se `@EnableMethodSecurity` (zapnuto v `SecurityConfig`) + `@PreAuthorize("hasRole('X')")` / `hasAnyRole('X','Y')` na **controlleru** (celá třída) nebo na konkrétní metodě.
- Role se zapisují bez prefixu `ROLE_` v anotaci (`hasRole('ADMIN')`) — Spring si prefix doplní sám; v DB (`security.roles.name`) a v `GrantedAuthority` je uložen už i s prefixem (`ROLE_ADMIN`).
- Příklady v kódu: `GoodsReceiptImportController` (`hasAnyRole('ADMIN','MANAGER','MECHANIC')` — import faktur), `UserController` (`hasRole('ADMIN')` — správa uživatelských účtů a rolí, celý controller).
- Frontend nemá vlastní guard proti obcházení (backend je autoritativní) — pouze skrývá UI prvky, které by stejně skončily 403 (`GrantedAuthority` seznam je ve `/auth/me` → `MeResponse.roles`).

---
