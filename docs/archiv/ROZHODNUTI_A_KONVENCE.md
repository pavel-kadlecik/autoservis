# Autoservis — Rozhodnutí a konvence projektu

> ⚠️ **ARCHIVNÍ DOKUMENT**
> Tento soubor zachycuje architektonická rozhodnutí a konvence z průběhu vývoje a nemusí odrážet aktuální stav projektu.
> **Autoritativní zdroj pravdy je `CLAUDE.md`** v kořeni projektu — aktuální konvence a stav projektu čti tam.
> Při konfliktu mezi tímto souborem a `CLAUDE.md` má `CLAUDE.md` přednost.
> Interní referenční dokument. Slouží jako základ pro všechny další iterace.
> Aktualizovat při každé zásadní změně architektury.

---

## 1. Technologický stack

| Vrstva | Technologie | Verze |
|---|---|---|
| Java | Java | 21 |
| Framework | Spring Boot | 4.0.3 |
| Databáze | PostgreSQL | 16+ |
| Persistence | MyBatis | mybatis-spring-boot-starter 4.0.1 |
| Migrace | Flyway | 11.14.1 |
| Bezpečnost | Spring Security | 7.0.3 |
| Mapování DTO | Ruční konvertory (`@Component`) | — |
| Boilerplate | Lombok | 1.18.42 |
| Build | Maven | — |

---

## 2. Architektura databáze

### 2.1 Schémata — konvence pojmenování
- Každý aplikační modul má **vlastní PostgreSQL schéma** pojmenované dle modulu.
- Schéma `security` je **sdílené** — obsahuje autentizační a autorizační tabulky používané všemi moduly.
- Schéma `public` slouží **výhradně pro Flyway** (`flyway_schema_history`).

```
security   → users, roles, user_roles         (sdílené, referencováno z všech modulů)
customer   → customers, addresses, ...        (modul zákazníci)
vehicle    → vehicles, ...                    (modul vozidla)
order      → service_orders, ...              (modul zakázky — budoucí)
public     → flyway_schema_history only
```

### 2.2 Cross-schema FK pravidlo
- Všechny auditní sloupce (`created_by`, `handled_by`, `assigned_by`) jsou FK na `security.users(id)`.
- Cross-schema FK jsou záměrné a správné: `REFERENCES security.users(id)`.
- V MyBatis XML jsou vždy **plně kvalifikované názvy** tabulek: `security.users`, `customer.customers`, `vehicle.vehicles` atd.

### 2.3 Primární klíče
- Typ: **`BIGSERIAL`** (PostgreSQL auto-increment).
- Java typ: **`Long`**.
- UUID se **nepoužívá** — rozhodnutí z důvodu jednoduchosti a výkonu.
- Cizí klíče: typ **`BIGINT`**.

### 2.4 Časová razítka
- Všude používáme **`TIMESTAMPTZ`** (timestamp with time zone).
- Java typ: **`OffsetDateTime`**.
- Sloupce `updated_at` jsou automaticky aktualizovány **databázovým triggerem** `fn_set_updated_at()` — nikoli aplikační logikou.
- Každé schéma má svou vlastní funkci triggeru: `security.fn_set_updated_at()`, `customer.fn_set_updated_at()`, `vehicle.fn_set_updated_at()` atd.

### 2.5 Soft delete
- Záznamy se **nemažou** — používá se sloupec `is_active BOOLEAN`.
- Výjimka: `customer_communications` — ty se smažou CASCADE s customerem.

### 2.6 Enum typy
- PostgreSQL `ENUM` typy jsou definovány v příslušném schématu.
- V Javě odpovídají Java `enum` třídám.
- MyBatis vyžaduje vlastní `PgEnumTypeHandler` — standardní handler zapisuje VARCHAR, ale PostgreSQL odmítne přiřazení bez `::cast`.
- Při zápisu se používá `setObject(i, value, Types.OTHER)`.

### 2.7 ON DELETE strategie pro FK
Konvence napříč moduly:

| Typ vazby | Strategie | Příklad |
|---|---|---|
| **Vlastnictví** (parent → child, child existuje jen díky parentovi) | `CASCADE` | `customer.customers → customer.addresses` |
| **Business vazba** (entita má smysl sama o sobě) | `RESTRICT` | `customer.customers → vehicle.vehicles` |
| **Auditní vazba** (`created_by`, `handled_by` atd.) | `SET NULL` | `security.users → vehicle.vehicles.created_by` |

---

## 3. Flyway konfigurace — finální a funkční

```yaml
spring:
  datasource:
    hikari:
      # connection-init-sql NESMÍ být nastaveno
      # způsobuje detekci schémat Flyway a selhání startu

  flyway:
    enabled: true
    locations: classpath:db/migration
    default-schema: public      # flyway_schema_history jde do public
    clean-disabled: true
    validate-on-migrate: true
    out-of-order: false
    # schemas: property NESMÍ být nastavena
    # schémata spravují migrace samy přes CREATE SCHEMA
```

### 3.1 Konvence pojmenování migračních souborů
```
V{n}__{popis_snake_case}.sql   → verzovaná migrace (spustí se jednou, neměnná)
R__{popis}.sql                  → opakovatelná migrace (při změně checksum)
```

### 3.2 Pořadí migrací — historie
```
V1 → init_security_schema      (schema security — users, roles, user_roles)
V2 → init_customer_schema      (schema customer — kompletní modul)
V3 → seed_initial_data         (seed do security a customer)
V4 → sequences                 (customer_number_seq atd.)
V5 → init_vehicle_schema       (schema vehicle — tabulka vehicles)
```
Další migrace pokračují v číslování V6, V7, ... viz sekce 11 (roadmap).

### 3.3 Seed data — povinná synchronizace sekvencí
Po vložení seed dat s explicitními BIGINT ID **musíme vždy** volat `setval()`:
```sql
SELECT setval('security.users_id_seq', (SELECT MAX(id) FROM security.users));
```
Bez toho první aplikační INSERT selže na unique konfliktu.

### 3.4 Zlaté pravidlo migrací
- Verzovaná migrace se po nasazení **nikdy nemění**.
- Změna schématu = nový soubor s vyšším číslem verze.
- Výjimka: migrace která **nikdy úspěšně neproběhla** (Flyway ji rollbackoval) — tu lze opravit přímo.

---

## 4. MyBatis konfigurace

```yaml
mybatis:
  mapper-locations: classpath:mapper/**/*.xml
  type-aliases-package: cz.palo.autoservis.model.domain
  type-handlers-package: cz.palo.autoservis.config.mybatis
  configuration:
    map-underscore-to-camel-case: true
    log-impl: org.apache.ibatis.logging.slf4j.Slf4jImpl
    cache-enabled: true
    lazy-loading-enabled: true
```

### 4.1 Konvence MyBatis
- SQL je **výhradně v XML souborech** — anotace (`@Select` atd.) se nepoužívají.
- Každá tabulka má vlastní Mapper interface + XML soubor.
- Názvy tabulek v XML jsou vždy **plně kvalifikované**: `security.users`, `customer.customers`, `vehicle.vehicles`.
- Pro vnořené objekty (adresy, role) se používá `<collection>` s `columnPrefix` pro zamezení kolizí názvů sloupců.
- `ResultMap` se definuje pro každou tabulku zvlášť a může být referencován z jiného mapperu.

### 4.2 @MapperScan
```java
@MapperScan("cz.palo.autoservis.mapper")
```
Musí odpovídat skutečnému balíčku projektu.

---

## 5. Spring Security integrace

### 5.1 Oddělení User a Customer
- `security.users` = autentizační data (Spring Security `UserDetails`).
- `customer.customers` = business data zákazníka.
- Zákazník **může existovat bez** `users` záznamu (přišel jednou, nemá portálový účet).
- Zaměstnanec (mechanik) má `users` záznam **bez** `customers` záznamu.

### 5.2 AppUserDetails
- `AppUserDetails implements UserDetails` — adapter z doménového `User` na Spring Security.
- Nese navíc `userId` (Long) pro použití v controllerech přes `@AuthenticationPrincipal`.

### 5.3 UserDetailsService
- `AppUserDetailsService` načítá uživatele **jedním JOIN dotazem**: `users + user_roles + roles`.
- Žádný N+1 problém.

### 5.4 Seed hesla
- Všechna seed hesla jsou: `Password1!`
- BCrypt hash: `$2a$12$RfJPRJHqbKmHRfJwQqJyVeJ9RkGz8fGjxVLLtS5vz3S4kYvMVpxWG`
- **Změnit před produkčním nasazením.**

---

## 6. Architektura Java aplikace

### 6.1 Vrstvení
```
Controller  → Service interface → ServiceImpl → Mapper interface → XML → DB
```

### 6.2 Mapování objektů
- **Doménový objekt** (`model/domain/`) — čisté POJO, žádné JPA anotace, žádné DTO závislosti.
- **DTO** (`model/dto/`) — API kontrakt, validace, oddělené od domény.
- **Konvertory** (`model/converter/`) — ruční `@Component` třídy `*Converter` (toDomain / applyUpdate / toDetailResponse). MapStruct se nepoužívá.
- Statické `from()` metody uvnitř DTO se **nepoužívají**.

### 6.3 Výjimky
- `ResourceNotFoundException` → HTTP 404.
- `ConflictException` → HTTP 409.
- `GlobalExceptionHandler` vrací RFC 7807 `ProblemDetail`.

### 6.4 Stránkování
- `PagedResponse<T>` — generická obálka pro stránkované výsledky.
- `CustomerSearchParams` — parametry vyhledávání předávané jako `@Param("params")` do MyBatis.

---

## 7. Čísla zákazníků a zakázek
- Zákazník: formát `ZNK-{rok}-{4cif}` — generuje trigger (V9), sekvence `customer.customer_number_seq` (`CACHE 1` od V10 kvůli mezerám).
- Zakázka: formát `ZAK-{rok}-{4cif}` — generuje trigger (V11), sekvence `order.order_number_seq`.
- Faktura: `YYYYMM+3cif` — generuje trigger (V15) přes advisory lock.

---

## 8. Full-text search
- PostgreSQL konfigurace `customer.czech_simple` odvozená od `simple` + `public.unaccent`.
- `unaccent` odstraní diakritiku: hledání `Novak` najde `Novák`.
- GIN index na sloupcích `first_name`, `last_name`, `company_name`, `ico`.
- Vyhledávání kombinuje FTS + `ILIKE` pro email a telefon.

---

## 9. Aktuálně implementované moduly

| Modul | Schema | DB + migrace | Java vrstva | Stav |
|---|---|---|---|---|
| Security | security | ✅ | ✅ | Funkční end-to-end |
| Zákazníci | customer | ✅ | ✅ | Funkční end-to-end |
| Vozidla (+ historie km) | vehicle | ✅ | ✅ | Funkční end-to-end |
| Zakázky (+ položky) | order | ✅ | ✅ | Funkční end-to-end |
| Fakturace | billing | ✅ | ✅ | Backend hotový, frontend plánován |
| Sklad | warehouse | ✅ | ✅ | Přehled + CRUD produktů; dodavatelé/příjemky/import rozpracováno |

Legenda: ✅ hotovo · 🔄 rozpracováno · ⏳ plánováno · ❌ nezahájeno

---

## 10. Modul VEHICLE — specifická rozhodnutí

### 10.1 Identifikace vozidla
- **VIN** je primární business identifikátor — `NOT NULL UNIQUE`.
- **Validace VIN regexem** v DB: `^[A-HJ-NPR-Z0-9]{17}$` (17 znaků, bez `I`, `O`, `Q`).
- **SPZ není unikátní** — může se časem měnit (přepisy, přenosy značek). NULL je povoleno (nezaregistrovaná vozidla).

### 10.2 Vlastnictví
- Vozidlo musí mít **právě jednoho** vlastníka (`customer_id NOT NULL`).
- `ON DELETE RESTRICT` — nelze smazat zákazníka, který má v evidenci vozidla.
- V Phase 5 přibude tabulka `vehicle.ownership_history` pro historii vlastnictví napříč zákazníky.

### 10.3 Značka a model — postupný přechod na číselník
- **Phase 1** (aktuální): `brand` a `model` jako `VARCHAR(100) NOT NULL` — volný text.
- **Phase 2** (plánováno): tabulky `vehicle.brands` a `vehicle.models` (M:1, model patří značce). Migrace převede stávající textové hodnoty na FK.
- Důvod postupného přechodu: učební + možnost ukázat data migration.

### 10.4 Najeté kilometry
- **Phase 1**: pouze skalární `current_mileage_km` na vozidle (denormalizace).
- **Phase 3** (hotovo, V20): tabulka `vehicle.mileage_history` s historií odečtů (zdroj, datum, hodnota).
- Skalární `current_mileage_km` zůstává jako denormalizace; udržuje ji trigger.

### 10.5 Audit a soft delete
- Stejné konvence jako customer: `is_active`, `created_at`, `updated_at` (přes trigger), `created_by` → `security.users`.
- `created_by` má `ON DELETE SET NULL` (uživatel může odejít, vozidlo zůstane).

---

## 11. Roadmap modulu VEHICLE

| Phase | Obsah | Migrace | Java | Stav |
|---|---|---|---|---|
| **Phase 1** | Tabulka `vehicles`, ENUMy, trigger, Java vrstva + frontend | V5, V7 | ✅ | ✅ hotovo |
| **Phase 2** | Číselník `brands` + `models`, data migration ze stringů na FK | V? | — | ⏳ Plánováno |
| **Phase 3** | Historie km (`mileage_history`) | V20 | ✅ | ✅ hotovo |
| **Phase 4** | Doklady a termíny — STK, EK, pojištění (`vehicle_inspections`) | V? | — | ⏳ Plánováno |
| **Phase 5** | Historie vlastnictví (`ownership_history`) | V? | — | ⏳ Plánováno |

**Souběžně s Phase 1** — vybudování end-to-end Java vrstvy pro vozidla:
- `Vehicle` doménový objekt
- `VehicleMapper` (MyBatis interface + XML)
- `VehicleService` + `VehicleServiceImpl`
- `VehicleDto`, `CreateVehicleDto`, `UpdateVehicleDto`
- `VehicleController` (REST endpointy)
- `VehicleConverter` (`@Component`)

---

## 12. Aktuální pozice v projektu

Aktuální stav modulů a otevřená rozhodnutí udržuje `ROZVOJOVA-MAPA.md`, závazné konvence a stav projektu `CLAUDE.md`.

Naposledy hotovo: sklad — přehled a CRUD produktů (V21), historie km u vozidel (V20). Rozpracováno: dodavatelé, příjemky a import faktur dodavatele z PDF na zakázku.
