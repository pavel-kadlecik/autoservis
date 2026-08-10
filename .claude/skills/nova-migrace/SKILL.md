---
name: nova-migrace
description: Vytvoření nové Flyway migrace podle konvencí projektu autoservis. Použít vždy, když se má měnit DB schéma (nová tabulka, sloupec, ENUM, view, trigger, seed) — provede číslování, šablonu, checklist a synchronizaci docs/databaze.md.
---

# Nová Flyway migrace

Workflow pro změnu DB schématu. Existující migrace se **nikdy nemění** (hlídá hook) — každá změna = nový soubor.

## Postup

0. **Kam soubor patří — tři Flyway locations:**
   - **Schéma / DDL + nutná infrastruktura** → `src/main/resources/db/migration/` (běží ve **všech** prostředích: prod, dev, test).
   - **Demo / ukázková data** (seed zákazníků, vozidel, zakázek, faktur…) → `src/main/resources/db/demo/` — **jen** dev/local + test. Demo data **nikdy** nedávej do `db/migration` — dostala by se do produkce.
   - **Produkční bootstrap** (role, admin účet…) → `src/main/resources/db/prod/` — jen produkce.
1. **Zjisti další číslo:** vezmi nejvyšší `V{n}` **napříč `db/migration`, `db/demo` i `db/prod`** (číslo může obsadit i produkční seed — viz `db/prod/V60`) a přidej +1. Číslování je globální přes všechny tři složky.
2. **Název:** `V{n}__{co_dela}.sql` — anglicky, snake_case, sloveso (`add_`, `init_`, `rename_`, `reduce_`…). Vzor viz index migrací v `docs/databaze.md` §11.
3. **Napiš SQL podle konvencí projektu** (`docs/konvence.md`, `docs/databaze.md` §0):
   - tabulky vždy plně kvalifikované; schéma `order` v uvozovkách: `"order".orders`
   - PK `BIGSERIAL`, FK `BIGINT`, časy `TIMESTAMPTZ`, soft-delete `is_active BOOLEAN NOT NULL DEFAULT TRUE`
   - `updated_at` přes trigger `trg_{tabulka}_updated_at` → `{schema}.fn_set_updated_at()` (funkce už v každém schématu existuje)
   - ON DELETE: CASCADE (vlastnictví) / RESTRICT (business vazba) / SET NULL (audit `created_by`)
   - nové ENUMy jako PostgreSQL typ v příslušném schématu; v Javě pak handler do `PgEnumTypeHandler`
   - po seedu s explicitními ID vždy `setval()` (pravidlo R-05)
4. **Pozor na ENUM pasti:**
   - přidání hodnoty do existujícího ENUMu + její použití NELZE v jedné transakci → `-- flyway:noAutoCommit` + explicitní `COMMIT` (vzor: V17)
   - redukce ENUMu = přestavba přes TEXT (vzor: V24)
5. **Aplikuj:** spusť backend (`./mvnw spring-boot:run -Dspring-boot.run.profiles=local`) — Flyway migruje při startu. Zkontroluj log, že migrace prošla.
6. **Synchronizuj dokumentaci — povinné:**
   - `docs/databaze.md`: dotčená tabulka/ENUM/view + nový řádek do indexu migrací (§11)
   - pokud změna zasahuje API/backend/FE, aktualizuj i `docs/api.md` / `docs/backend.md` / `docs/frontend.md`
7. **Java řetězec při přejmenování sloupce** (celý, jinak selže až za běhu): migrace → domain objekt → `*Mapper.xml` (resultMap i INSERT/UPDATE/WHERE) → service → DTO/converter → frontend.

## Ověření

Proti živé DB (localhost:5433, uživatel `postgres`, db `autoservis`):
```
psql -h localhost -p 5433 -U postgres -d autoservis -c "\d {schema}.{tabulka}"
psql -h localhost -p 5433 -U postgres -d autoservis -tAc "SELECT version FROM public.flyway_schema_history ORDER BY installed_rank DESC LIMIT 1"
```
Pokud DB neběží, požádej uživatele o spuštění (viz `docs/lokalni-databaze.md`).
