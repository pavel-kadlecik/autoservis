# TECH-DLUHY.md — Autoservis: Technické dluhy a otevřené úkoly

> Živý dokument. Přidávat průběžně, uzavřené označit ✅ a přesunout do sekce "Vyřešeno".
> Priorita: 🔴 blocker · 🟠 vysoká · 🟡 střední · 🟢 nízká

---

## Frontend — VehiclesPageEdit.jsx (kritické bugy)

### TD-01 ✅ Select `yearOfManufacture` nemá `name` atribut
Bylo již opraveno — atribut existoval.

### TD-02 ✅ `name="transmissionType"` místo `name="transmission"`
Bylo již opraveno — správný name existoval.

### TD-03 ✅ `onSave` vždy volá PUT — chybí POST větev pro nové vozidlo
**Opraveno:** `VehiclesPageCreate` má vlastní `onSave` s `api.post`.

### TD-04 ✅ Po uložení chybí `navigate()`
**Opraveno:** Přidáno do `VehiclesPageCreate` i `VehiclesPageEdit`.

### TD-05 ✅ `backPath` prop bez default hodnoty
**Opraveno:** `backPath = "/vehicles"` jako výchozí hodnota.

---

## Frontend — ostatní

### TD-06 ✅ `VehiclesPageDetail.jsx` — překlep v názvu souboru
**Opraveno:** Soubor přejmenován, import v `App.jsx` opraven.

### TD-07 ✅ `VehicleTable.jsx` — confirm dialog používá `displayName`
**Opraveno:** Dialog zobrazuje `${vehicle.brand} ${vehicle.model} (${vehicle.vin})`.

### TD-15 🟡 `AutocompletePair` — předvyplnění závisí na formátu backend objektu
**Problém:** Aby šel `AutocompletePair` předvyplnit při editaci, musí `customer` objekt obsahovat fieldy `value` a `description`. Ty se přidávají ručně v `VehiclesPageEdit.jsx` pomocí spreadu `...data.customer`.
**Dopad:** Pokud se změní formát `AutocompletePair` nebo backend response, musí se opravit na více místech.
**Oprava:** `AutocompletePair` by měla přijímat `initialValue` (string) a `initialSelectedId` (id) jako samostatné props. Refaktorovat při Phase 2 nebo přepisu autocomplete.

---

## Databáze

### TD-16 🟢 Schema `order` — nevhodný název
**Problém:** Schema se jmenuje `order` — rezervované slovo v SQL, vyžaduje uvozovky všude v XML (`"order".orders`). Navíc název neodpovídá doméně — v autoservisu se pracuje se **zakázkami**, ne objednávkami.
**Dopad:** Kosmetický + mírná nepříjemnost v XML mapperech (uvozovky). Funkčně v pořádku.
**Oprava:** Migrace která přesune tabulku do schema `workshop` nebo `repair`. Provést až bude Java vrstva hotová.

### TD-28 🟡 Produkt lze deaktivovat i se zásobou na skladě
**Problém:** `ProductService.deactivate` nehlídá `quantity_on_hand > 0` — kartu s fyzickou zásobou lze deaktivovat.
**Oprava:** Rozhodnout pravidlo (zakázat, nebo povolit jako „doprodej") a doplnit při řezu se skladovými pohyby.

---

## Backend — Java vrstva

### TD-08 🟠 `CustomerMapper.findById` nefiltruje `is_active`
**Problém:** `CustomerMapper.findById` nemá podmínku `AND is_active = TRUE`.
**Dopad:** Deaktivovaný zákazník je dostupný přes GET `/customers/{id}`.
**Oprava:** Přidat `AND c.is_active = TRUE` + `findByIdIncludingInactive` pro interní potřeby.

### TD-09 ✅ Customer PUT endpoint
**Opraveno:** `CustomerController` má `PUT /customers/{id}`, `UpdateRequest` DTO + `update()` service metoda + XML existují.

### TD-10 🟠 Chybí podmíněná validace INDIVIDUAL vs COMPANY — `@ValidCustomerRequest`
**Problém:** `CreateRequest` ani `UpdateRequest` nevalidují povinná pole podle typu zákazníka.
Konkrétně:
- `INDIVIDUAL` → `firstName` a `lastName` jsou povinné, ale žádná anotace to nevynucuje
- `COMPANY` → `companyName` je povinné, ale žádná anotace to nevynucuje
- Prázdné `companyName` u firmy projde přes Bean Validation i přes service až do DB

`GlobalExceptionHandler` má `ValidCustomerRequest` v `CUSTOM_VALIDATOR_ANNOTATIONS` — infrastruktura je připravena, validátor chybí.

**Dotčená místa:**

| Soubor | Kde |
|---|---|
| `CustomerDto.CreateRequest` | Přidat `@ValidCustomerRequest` na třídu |
| `CustomerDto.UpdateRequest` | Přidat `@ValidCustomerRequest` na třídu — pozor: `customerType` zde chybí, musí se přidat |
| `CustomerService.create` | Odebrat `// TODO VALIDACE` blok — nahradí `@ValidCustomerRequest` |
| `CustomerService.update` | Žádná podmíněná validace neexistuje |
| `messages.properties` | Přidat klíče: `REQUIRED_FOR_INDIVIDUAL`, `REQUIRED_FOR_COMPANY` |

**Co implementovat:**
1. Vytvořit anotaci `@ValidCustomerRequest` (`/validation/ValidCustomerRequest.java`)
2. Vytvořit validátor `CustomerRequestValidator implements ConstraintValidator`
3. Logika: pokud `customerType == INDIVIDUAL` → `firstName` a `lastName` nesmí být blank; pokud `COMPANY` → `companyName` nesmí být blank
4. `UpdateRequest` nemá `customerType` — buď ho přidat, nebo validátor načte typ z DB (komplikovanější)
5. Přidat `@ValidCustomerRequest` na obě request třídy
6. Přidat texty do `messages.properties`

**Oprava:** Implementovat před nasazením do produkce — bez toho lze vytvořit firmu bez názvu.

### TD-11 🟡 `internal_note` viditelný pro všechny role
**Problém:** `DetailResponse` obsahuje `internalNote` — zákaznický portál by ho neměl vidět.
**Oprava:** Dvě DTO, nebo `@JsonView`, nebo filtrování v service podle role.

### TD-12 🟢 Vehicle controller — chybí `Location` header u 201
**Oprava:** `ResponseEntity.created(URI.create("/api/v1/vehicles/" + created.getId())).body(created)`.

### TD-13 🟢 `created_by` je nullable bez business důvodu
**Stav:** Acceptable pro výukový projekt, seed data nemají `created_by`.

### TD-17 ✅ Service třídy nemají interface
**Opraveno:** Všechny service třídy mají interface + *ServiceImpl.
Controllery injektují interface.

### TD-18 🟡 FTS vyhledávání zákazníků nefunguje pro celé jméno
**Problém:** Vyhledávání funguje pro jednotlivá slova (`Novák`, `Jan`) ale ne pro celé jméno (`Jan Novák`). PostgreSQL FTS indexuje slova zvlášť — fráze `Jan Novák` neodpovídá žádnému indexovanému tokenu.
**Dopad:** Recepční zadá celé jméno a zákazník se nenajde.
**Oprava:** Kombinovat FTS pro jednotlivá slova s `ILIKE '%Jan Novák%'` pro přesnou frázi, nebo použít `phraseto_tsquery` pro víceslovné výrazy.

### TD-25 🟡 Fulltext vyhledávání zakázek — víceslovný dotaz nenajde shodu napříč sloupci
**Problém:** `WhereClause` v order mapperu hledá celý zadaný řetězec jedním `LIKE '%search%'` v každém sloupci zvlášť (`order_number`, `first_name`, `last_name`, `company_name`, `primary_phone`, `vin`, `license_plate`, `brand`, `model`, `description`). Když uživatel zadá víc slov (např. `Jan Novák`), žádný jednotlivý sloupec celou frázi neobsahuje — `Jan` je v `c.first_name`, `Novák` v `c.last_name` — takže `LIKE '%Jan Novák%'` neprojde nikde a vrátí prázdný výsledek.
**Dopad:** Recepční zadá jméno i příjmení a zakázka se nenajde. Funguje jen jednoslovný dotaz.
**Oprava:** Rozdělit `search` na slova (tokeny) v service vrstvě (`split("\\s+")`), předat jako `List<String> searchTokens` do mapperu a v XML přes `<foreach separator=" AND ">` vygenerovat na každé slovo skupinu `OR` přes všechny sloupce (`<bind>` pro `LIKE` výraz uvnitř smyčky). Každé slovo pak musí být nalezeno alespoň v jednom sloupci, nezávisle na pořadí. Souvisí s TD-18 (stejný problém u FTS zákazníků). Zároveň zvážit `unaccent` na sloupcích i tokenech, aby `Novak` našlo `Novák`.

### TD-19 ✅ `orderNumber` hardcoded v `OrderService.create`
**Opraveno:** Migrace V11 — trigger generuje ZAK-{rok}-{seq}. Hardcoded řádek odebrán, opraveno i u zákazníků.

### TD-20 🟠 Chybějící null guardy na vstupní parametry service metod
**Problém:** Metody přijímající `Long id` nevalidují null vstup. Null projde do DB mapperu jako `WHERE id = NULL` → metoda vyhodí `ResourceNotFoundException` místo správného `IllegalArgumentException`. Porušuje princip fail-fast.

**Dotčené metody:**

| Service | Metoda | Chybí null check na |
|---|---|---|
| `OrderService` | `getById(Long id)` | `id` |
| `VehicleService` | `getById(Long id)` | `id` |
| `CustomerService` | `deactivate(Long id)` | `id` |
| `CustomerService` | `activate(Long id)` | `id` |
| `VehicleService` | `deactivate(Long id)` | `id` |
| `VehicleService` | `activate(Long id)` | `id` |
| `VehicleService` | `update(Long id, ...)` | `id` |
| `OrderService` | `create(CreateRequest, Long userId)` | `userId` |
| `CustomerService` | `create(CreateRequest, Long userId)` | `userId` |

**Dopad:** Nesprávná výjimka při null vstupu — klient dostane 404 místo 400. Testy ověřující null chování selžou.

**Oprava:** Guard na začátek každé dotčené metody:
```java
if (id == null) throw new IllegalArgumentException("ID nesmí být null");
```
Nebo elegantně přes `@Validated` + `@NotNull` anotace na parametrech.

---

## Konfigurace

### TD-26 ✅ ~~`pom.xml` — natvrdo zapsané DB heslo~~ VYŘEŠENO
**Bylo:** Flyway Maven plugin četl `<db.password>1432</db.password>` přímo z `pom.xml`.
**Řešení:** `db.password` se čte z proměnné prostředí `${env.DB_PASSWORD}`; v `pom.xml` zůstal jen placeholder. CLI flyway goals vyžadují `export DB_PASSWORD=...`.

### TD-27 🟢 `application.yaml` — blok `flyway` je pod `mybatis`
**Problém:** `flyway:` je odsazený pod `mybatis:` (tedy `mybatis.flyway`), ne `spring.flyway`. Funguje jen díky shodě s výchozím umístěním migrací.
**Oprava:** Přesunout blok pod `spring:`.

---

## Testy

### TD-14 🟡 Chybějící pokrytí testy ~~Žádné unit ani integration testy~~
**Aktuální stav:** Infrastruktura testů existuje — Testcontainers + PostgreSQL + Flyway funguje. Existuje `CustomerServiceTest` s testy pro `getById()`.
**Zbývá:** Rozšířit testy na všechny service metody (`create`, `deactivate`, `activate`, `update`) a zbývající moduly (`VehicleService`, `OrderService`). Přidat unit testy se Mockito pro business logiku bez DB.
**Oprava:** Postupně doplňovat testy souběžně s vývojem nových funkcí.

---

### TD-22 🟡 `@JsonView` — řízení viditelnosti citlivých dat podle role

**Problém:**
Aktuálně všechny Response DTO vrací všechna pole všem rolím. Některá pole jsou citlivá a nesmí být viditelná pro všechny role.

**Konkrétní případy:**

| DTO | Pole | Vidí | Nevidí |
|---|---|---|---|
| `OrderItemDto.Response` | `purchasePrice` | ADMIN, MANAGER | MECHANIC, CUSTOMER |
| `CustomerDto.Response` | `internalNote` | ADMIN, MANAGER, MECHANIC | CUSTOMER |
| `VehicleDto.Response` | `internalNote` | ADMIN, MANAGER, MECHANIC | CUSTOMER |
| `OrderDto.Response` | `internalNote` | ADMIN, MANAGER, MECHANIC | CUSTOMER |

**Řešení — `@JsonView`:**
```java
public class Views {
    public static class Basic {}                 // mechanic, zákazník
    public static class Manager extends Basic {} // manager + admin vidí vše
}
```
Fieldy v DTO označit `@JsonView`, controller dynamicky vybere pohled podle role.

**→ Status: Odloženo. Řešit před spuštěním zákaznického portálu. Neblokuje aktuální vývoj.**

---

### TD-24 🟡 Role-based security — granulární přístup podle rolí
**Problém:** Každý přihlášený uživatel má přístup ke všem endpointům přes
`.requestMatchers("/api/**").authenticated()`. Role `ROLE_ADMIN`, `ROLE_MANAGER`,
`ROLE_MECHANIC`, `ROLE_CUSTOMER` nejsou využity pro řízení přístupu na úrovni endpointů.

**Dotčené endpointy:**

| Endpoint | Povolené role |
|---|---|
| POST/PUT/DELETE `/api/*/invoices/**` | ADMIN, MANAGER |
| DELETE `/api/*/customers/**` | ADMIN, MANAGER |
| DELETE `/api/*/vehicles/**` | ADMIN, MANAGER |
| GET `/api/*/invoices/**` | ADMIN, MANAGER, MECHANIC |

**Oprava:** Přidat granulární `hasAnyRole()` pravidla do `SecurityConfig.filterChain()`
před obecné `.requestMatchers("/api/**").authenticated()`.

**→ Status: Odloženo. Řešit před spuštěním zákaznického portálu. Neblokuje aktuální vývoj.**

---

### TD-23 🟡 Primitivní `boolean` v DTO — předělat na `Boolean` wrapper
**Problém:** `UpdateRequest` používá primitivní `boolean` pro `gdprConsent` a `marketingConsent`. Primitivní typ má výchozí hodnotu `false` — pokud pole chybí v JSON, Jackson dosadí `false` místo `null`. V současnosti to nevadí protože frontend vždy posílá obě hodnoty, ale je to křehké.

**Dotčená místa:**

| Soubor | Pole |
|---|---|
| `CustomerDto.UpdateRequest` | `gdprConsent`, `marketingConsent` |
| `VehicleDto.UpdateRequest` | zkontrolovat zda obsahuje boolean pole |
| `CustomerConverter.applyUpdate` | `isGdprConsent()` → `getGdprConsent()` |
| `CustomerConverter.applyUpdate` | `isMarketingConsent()` → `getMarketingConsent()` |

**Co implementovat:**
1. Změnit `boolean` → `Boolean` v dotčených DTO
2. Opravit converter — `isX()` → `getX()`
3. V `applyUpdate` přidat null check: `if (req.getGdprConsent() != null)`
4. Frontend měnit nemusí — JSON klíč zůstane stejný

**Výhoda:** `null` = pole nebylo v requestu = nechej stávající hodnotu. Bl¼uvzdornější a konzistentní s REST PATCH sémantikou.

---

## ✅ Vyřešeno
|---|---|---|
| TD-01 | Select `yearOfManufacture` — byl již opravený | Revize dokumentace |
| TD-02 | `name="transmission"` — byl již opravený | Revize dokumentace |
| TD-03 | POST vs PUT větev — oddělené stránky Create/Edit | Vehicle Phase 1 |
| TD-04 | `navigate()` po uložení | Dnešní session |
| TD-05 | `backPath` default hodnota | Dnešní session |
| TD-06 | Překlep v názvu VehiclesPageDetail.jsx | Dnešní session |
| TD-07 | Confirm dialog — správný identifikátor vozidla | Dnešní session |
| — | Alert systém (Context, Provider, AlertContainer) | Dnešní session |
| — | `AutocompletePair` předvyplnění při editaci | Dnešní session |
| — | `PgEnumTypeHandler` — cast chyba odstraněna | Early stage |
| — | Flyway: `connection-init-sql` způsobovalo selhání | Early stage |
| — | `is_active` jako primitive `boolean` → wrapper `Boolean` | Vehicle Phase 1 |
| — | `CustomerService.getById` — chybějící null guard opraven | 2026-05 |
| TD-19 |  orderNumber` hardcoded v `OrderService.create`

---

## Šablona pro nový dluh

```
### TD-XXX priorita Název
**Problém:** Co je špatně.
**Dopad:** Co to způsobuje.
**Oprava:** Konkrétní kroky.
```
