---
name: novy-endpoint
description: Přidání nového REST endpointu do projektu autoservis přes všechny vrstvy (DTO, converter, mapper XML, service, controller) podle konvencí, včetně synchronizace docs/api.md. Použít při požadavku na nový endpoint nebo změnu existujícího API.
---

# Nový REST endpoint

Checklist vrstev odspodu nahoru. Detaily a příklady: `docs/konvence.md`, katalog existujících endpointů: `docs/api.md`.

## Vrstvy (v tomto pořadí)

1. **XML mapper** (`src/main/resources/mapper/…`): SQL výhradně zde (R-01), tabulky plně kvalifikované, ENUM parametry `jdbcType=OTHER`. Slovník metod: `insert` / `findById` / `search`+`countSearch` / `deactivate` / `activate` / `existsBy{Field}`.
2. **Mapper rozhraní** (`mapper/`): `@Mapper` (ne `@Repository`), návraty `Optional<X>` kde dává smysl.
3. **DTO** (`model/dto/{modul}/`): namespace pattern `XxxDto.CreateRequest / UpdateRequest / DetailResponse / ListResponse`. CreateRequest bez `createdBy`, UpdateRequest bez `id` a immutable polí. Bean Validation anotace. Stránkování: `XxxSearchParams extends SearchParams` → `PagedResponse<T>`.
4. **Converter** (`model/converter/`): ruční `@Component` (`toDomain` / `applyUpdate` / `toDetailResponse`), žádný MapStruct (R-11).
5. **Service** (`service/` + `impl/`): rozhraní + Impl. Nikdy null (N-01), verify-and-fetch po mutacích (R-03), business validace → `BusinessRuleException` (R-13), `@Transactional` na mutacích, `userId` jako parametr z controlleru (R-04).
6. **Controller** (`controller/`): base path `/api/{version}/…`, `id` jen v URL (R-14), audit přes `@AuthenticationPrincipal AppUserDetails`. Statusy: POST→201, GET/PUT/DELETE(soft)→200, DELETE položky/reorder→204. Slovník: `create/getById/getAll/update/delete/activate`.
7. **Frontend** (pokud je součástí zadání): volání přes `api` klienta (`src/api/api.js`), chyby `JSON.parse(err.message).detail`, enum labely do `src/api/format.js`. Viz `docs/frontend.md`.

## Synchronizace dokumentace — povinné

- `docs/api.md`: nový řádek do tabulky příslušného controlleru **a** přepočet v souhrnné tabulce počtů na konci dokumentu.
- Křížová kontrola: `grep -c "@\(Get\|Post\|Put\|Delete\|Patch\)Mapping"` přes controllery musí sedět se součtem v api.md.
- Při novém business pravidle (ruleCode) doplnit i sekci Chybové odpovědi v api.md.

## Ověření

Spusť backend a otestuj endpoint (curl/httpie s cookie z `POST /api/v1/auth/login`, seed účet `admin`/`Password1!`), nebo přes frontend. U mutací zkontroluj audit pole a soft-delete chování v DB.
