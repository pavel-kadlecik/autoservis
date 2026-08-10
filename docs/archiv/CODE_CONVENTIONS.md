# CODE_CONVENTIONS.md — Autoservis

> Detailní konvence kódu s příklady pro tento projekt.
> Číst když pracuješ na Java vrstvě, REST API, nebo přidáváš nový modul.
> Pravidla ve zkrácené formě jsou v `CLAUDE.md` sekce 1.

---

## 1. Boolean stavová pole

Java pole pojmenovat **bez prefixu `is`** — Lombok pak generuje správné gettery/settery.

```java
// ✅ SPRÁVNĚ
private boolean active;      // DB sloupec: is_active → MyBatis mapuje přes property="active"
private boolean enabled;     // DB sloupec: enabled

// Lombok generuje:
public boolean isActive() { ... }
public void setActive(boolean active) { ... }

// ❌ ŠPATNĚ
private boolean isActive;    // Lombok by generoval isIsActive() — nesmysl
private Boolean active;      // Wrapper Boolean použít POUZE pokud NULL má jiný význam než false
```

---

## 2. Návratové hodnoty service metod — nikdy null

```java
// ✅ SPRÁVNĚ — entita nenalezena → výjimka
public VehicleDto.DetailResponse getById(Long id) {
    return mapper.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Vehicle", id));
}

// ✅ SPRÁVNĚ — "není přítomen" je legitimní stav
public Optional<CustomerDto.DetailResponse> findByUserId(Long userId) {
    return mapper.findByUserId(userId).map(converter::toDetailResponse);
}

// ❌ ŠPATNĚ — nikdy vracet null
public VehicleDto.DetailResponse getById(Long id) {
    return mapper.findById(id).orElse(null);
}
```

---

## 3. Pattern "verify-and-fetch" pro UPDATE/DELETE operace

Po každé write operaci: ověř existence entity (affectedRows), pak načti aktuální stav z DB.

```java
// ✅ V service — deaktivace vozidla
public VehicleDto.DetailResponse deactivate(Long id) {
    int affectedRows = mapper.deactivate(id);
    return verifyAndFetch(id, affectedRows);
}

// ✅ Helper — extrahovat pokud se pattern opakuje 2+ krát ve stejné třídě
private VehicleDto.DetailResponse verifyAndFetch(Long id, int affectedRows) {
    if (affectedRows == 0) {
        // 404 — klient poslal neexistující nebo neaktivní ID
        throw new ResourceNotFoundException("Vehicle", id);
    }
    return mapper.findByIdIncludingInactive(id)
            .map(converter::toDetailResponse)
            .orElseThrow(() -> new IllegalStateException(
                    // 500 — invariant violation: entita zmizela mezi UPDATE a SELECT
                    "Vehicle " + id + " disappeared between UPDATE and SELECT"));
}

// ❌ ŠPATNĚ — vrátit vstupní data bez nového načtení z DB (stale data)
public VehicleDto.DetailResponse deactivate(Long id) {
    mapper.deactivate(id);
    return converter.toDetailResponse(vehicle); // vehicle je stare, trigger updated_at nepromítnut
}
```

---

## 4. Audit pole `created_by` — nikdy z klienta

```java
// ✅ V controlleru — userId ze SecurityContext, ne z requestu
@PostMapping
public ResponseEntity<VehicleDto.DetailResponse> create(
        @RequestBody @Valid VehicleDto.CreateRequest req,
        @AuthenticationPrincipal AppUserDetails currentUser) {
    VehicleDto.DetailResponse created = service.create(req, currentUser.getUserId());
    return ResponseEntity.status(HttpStatus.CREATED).body(created);
}

// ✅ V service — userId jako samostatný parametr
public VehicleDto.DetailResponse create(VehicleDto.CreateRequest req, Long currentUserId) {
    Vehicle vehicle = converter.toDomain(req);
    vehicle.setCreatedBy(currentUserId);  // server-side, ne z DTO
    mapper.insert(vehicle);
    return verifyAndFetch(vehicle.getId(), 1);
}

// ✅ DTO — createdBy zde NENÍ
public static class CreateRequest {
    @NotBlank
    @Pattern(regexp = "^[A-HJ-NPR-Z0-9]{17}$", message = "Neplatný formát VIN")
    private String vin;
    // ... ostatní pole zákazníka
    // createdBy: NENÍ — server ho doplní sám
}
```

---

## 5. Konvertory domain ↔ DTO — ruční `@Component`

Mapování doménových objektů na DTO dělají ruční konvertory (`model/converter/`), ne MapStruct.

```java
@Component
public class VehicleConverter {
    public Vehicle toDomain(VehicleDto.CreateRequest req) { ... }
    public void applyUpdate(Vehicle existing, VehicleDto.UpdateRequest req) { ... }
    public VehicleDto.DetailResponse toDetailResponse(Vehicle v) { ... }
}
```

Co řeší jinde, ne mapováním 1:1:
- `createdBy`, `updatedBy`  → server-side ze SecurityContext (service)
- timestamps (`created_at`) → DB default / trigger

---

## 6. `if` bloky — vždy složené závorky

```java
// ❌ ŠPATNĚ — Apple goto fail bug (iOS security hole 2014)
if (affectedRows == 0)
    throw new ResourceNotFoundException("Vehicle", id);

// ✅ SPRÁVNĚ — vždy závorky, i pro jednořádkové tělo
if (affectedRows == 0) {
    throw new ResourceNotFoundException("Vehicle", id);
}
```

---

## 7. HTTP statusy

| Status | Kdy použít |
|---|---|
| **200 OK** | GET, PUT update, soft delete (deactivate), activate |
| **201 Created** | POST — vytvoření nového resource |
| **400 Bad Request** | Validační chyba z `@Valid` (Bean Validation) |
| **401 Unauthorized** | Nepřihlášený uživatel |
| **404 Not Found** | `ResourceNotFoundException` — entita neexistuje nebo není aktivní |
| **422 Unprocessable Entity** | `BusinessRuleException` — porušení business pravidla (VIN duplicita, zákazník má vozidla) |
| **500 Internal Server Error** | `IllegalStateException` — invariant violation (neočekávaný stav DB) |

---

## 8. REST URL konvence

```
# ✅ SPRÁVNĚ
GET    /api/v1/vehicles               → kolekce (query params pro filtr)
GET    /api/v1/vehicles/{id}          → konkrétní resource
POST   /api/v1/vehicles               → vytvoření
PUT    /api/v1/vehicles/{id}          → úplný update
POST   /api/v1/vehicles/{id}/deactivate   → akce na resource
GET    /api/v1/vehicles?customerId=5  → filtr kolekce

# ❌ ŠPATNĚ
GET    /api/v1/vehicles?id=5          → id je path variable, ne query param
DELETE /api/v1/vehicles/{id}          → (nepoužíváme hard delete — vrátíme 200 s deactivated objektem)
```

---

## 9. Slovník metod — sjednocená pojmenování napříč vrstvami

| Akce | Mapper | Service | Controller (HTTP) |
|---|---|---|---|
| Vytvoření | `insert(entity)` | `create(dto, userId)` | `create(...)` POST → 201 |
| Soft delete | `deactivate(id)` | `deactivate(id)` | `delete(...)` DELETE → 200 |
| Reaktivace | `activate(id)` | `activate(id)` | `activate(...)` POST → 200 |
| Detail | `findById(id)` | `getById(id)` | `getById(...)` GET → 200 |
| Seznam stránkovaný | `search(params)` + `countSearch(params)` | `getPage(params)` | `getAll(...)` GET → 200 |
| Existence check | `existsBy{Field}(value)` | (privátní validace) | — |

---

## 10. DTO struktura — namespace pattern

```java
// Každá entita má jednu třídu jako namespace
public final class VehicleDto {
    private VehicleDto() {}  // zabránit instancování — namespace, ne bean

    // POST body — pouze to co klient smí vyplnit
    public static class CreateRequest {
        @NotBlank
        @Pattern(regexp = "^[A-HJ-NPR-Z0-9]{17}$")
        private String vin;

        @NotNull
        private Long customerId;

        @NotBlank
        private String brand;

        // createdBy zde NENÍ
    }

    // PUT body — bez immutable polí (VIN se nemění, customerId se nemění)
    public static class UpdateRequest {
        @Size(max = 15)
        private String licensePlate;

        private Integer currentMileageKm;
        // vin: NENÍ — immutable
    }

    // Plná odpověď pro GET /vehicles/{id}
    public static class DetailResponse {
        private Long id;
        private String vin;
        private String brand;
        private String model;
        private boolean active;
        // ... všechna pole
    }

    // Zúžená odpověď pro GET /vehicles (výpis)
    public static class ListResponse {
        private Long id;
        private String vin;
        private String brand;
        private String model;
        private String licensePlate;
        // internal_note zde NENÍ — viditelné jen zaměstnancům v DetailResponse
    }
}
```

---

## 11. `findById` — strict vs permissive varianta

**Strict** (vehicle modul, preferováno pro nové):
```xml
<!-- Vrací pouze aktivní entity -->
<select id="findById" resultMap="vehicleResultMap">
    SELECT * FROM vehicle.vehicles
    WHERE id = #{id} AND is_active = TRUE
</select>

<!-- Samostatná metoda pro management (deactivate, activate) -->
<select id="findByIdIncludingInactive" resultMap="vehicleResultMap">
    SELECT * FROM vehicle.vehicles
    WHERE id = #{id}
</select>
```

**Permissive** (customer modul — historická výjimka, TODO sjednotit):
```xml
<!-- Bez filtru — service si filtr aplikuje sama -->
<select id="findById" resultMap="customerResultMap">
    SELECT * FROM customer.customers
    WHERE id = #{id}
</select>
```

Pro **nové moduly vždy strict** — explicitnější, menší riziko vrácení neaktivní entity.

---

## 12. `@Component` vs `@Service` vs `@Mapper`

```java
@Service          // business logika
public class VehicleServiceImpl implements VehicleService { ... }

@Component        // utility, konvertory (domain ↔ DTO)
public class VehicleConverter { ... }

@Mapper           // MyBatis mapper interface — NE @Repository
public interface VehicleMapper { ... }
```

---

## 13. Flyway konfigurace — funkční nastavení

```yaml
spring:
  datasource:
    hikari:
      # connection-init-sql NESMÍ být nastaveno
      # způsobuje detekci schémat Flyway a selhání startu

  flyway:
    enabled: true
    locations: classpath:db/migration
    default-schema: public        # flyway_schema_history jde do public
    clean-disabled: true
    validate-on-migrate: true
    out-of-order: false
    # schemas: property NESMÍ být nastavena
    # schémata spravují migrace samy přes CREATE SCHEMA
```

Po seed datech s explicitními ID — synchronizace sekvencí:
```sql
SELECT setval('security.users_id_seq',    (SELECT MAX(id) FROM security.users));
SELECT setval('customer.customers_id_seq', (SELECT MAX(id) FROM customer.customers));
SELECT setval('vehicle.vehicles_id_seq',   (SELECT MAX(id) FROM vehicle.vehicles));
-- atd. pro každou tabulku se seed daty
```

---

## 14. MyBatis — klíčové konvence

```yaml
# application.yml
mybatis:
  mapper-locations: classpath:mapper/**/*.xml
  type-aliases-package: cz.palo.autoservis.model.domain
  type-handlers-package: cz.palo.autoservis.config.mybatis
  configuration:
    map-underscore-to-camel-case: true   # is_active → active
    cache-enabled: true
    lazy-loading-enabled: true
```

```xml
<!-- Vždy plně kvalifikované názvy tabulek -->
<select id="findById" resultMap="vehicleResultMap">
    SELECT v.id, v.customer_id, v.vin, v.brand, v.is_active
    FROM vehicle.vehicles v          <!-- ← plně kvalifikované -->
    JOIN customer.customers c ON c.id = v.customer_id   <!-- ← cross-schema JOIN -->
    WHERE v.id = #{id} AND v.is_active = TRUE
</select>

<!-- Pro ENUM parametry použít jdbcType=OTHER -->
<insert id="insert">
    INSERT INTO vehicle.vehicles (fuel_type, ...)
    VALUES (#{fuelType, jdbcType=OTHER}, ...)
</insert>
```

---

## 15. REST — `id` patří do URL, ne do těla requestu

**Pravidlo:** Identita resource (jeho `id`) patří výhradně do URL. Tělo requestu (formData, DTO) obsahuje pouze editovatelná data.

```
PUT /api/v1/customers/25        ← identifikace (KDE)
Body: { firstName: "Jan", ... } ← data (CO) — id zde NENÍ
```

**Proč?** Kdyby `id` bylo v body i v URL, vzniká nejednoznačnost:
```
PUT /api/v1/customers/25
Body: { id: 99, firstName: "Jan" }  ← koho edituju? 25 nebo 99?
```

**Na frontendu** — `id` bereme z `useParams()`, ne z `formData`:
```jsx
// ✅ SPRÁVNĚ
const { id } = useParams();
const data = await api.put(`/customers/${id}`, formData);  // id z URL

// ❌ ŠPATNĚ
const data = await api.put(`/customers/${formData.id}`, formData);  // id z formData
```

**Na backendu** — `UpdateRequest` DTO `id` neobsahuje:
```java
// ✅ SPRÁVNĚ — @PathVariable je autoritativní identifikátor
@PutMapping("/{id}")
public ResponseEntity<CustomerDto.DetailResponse> update(
        @PathVariable Long id,           // ← identita z URL
        @RequestBody @Valid UpdateRequest req) {  // ← id zde NENÍ
    return ResponseEntity.ok(service.update(id, req));
}

// UpdateRequest DTO — id záměrně chybí
public static class UpdateRequest {
    private String firstName;
    private String primaryEmail;
    // id: NENÍ — server ho zná z URL
}
```

**Výjimka:** `formData.id` je OK pokud ho backend **ignoruje** a `id` používá výhradně z path variable. Ale čistší je `id` do `formData` vůbec nedávat.
