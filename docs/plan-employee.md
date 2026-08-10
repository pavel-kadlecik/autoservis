# plan-employee.md — Prováděcí plán: Modul Zaměstnanci

> Nová větev `feature/employee` (z `devel`). Stav: **návrh schválen 2026-07-23, nezapočato.**
> Realizuje Employee modul z `roadmapa.md` + rozšíření z diskuse (sazba v čase, nástup/odchod, historie).

## Kontext
Servis chce evidovat, **kdo odvedl kterou práci**, s jeho **hodinovou sazbou** (náklad práce),
**datem nástupu a odchodu**, a držet tuto vazbu **i historicky** — i po odchodu zaměstnance.
Dnes se náklad práce nikde nevede (proto dashboard umí jen materiálovou marži). Zaměstnanec se
přiřazuje **k položce zakázky typu práce (LABOR)**, ne k celé zakázce — na jednom autě jich může
dělat víc, každý svoje hodiny a sazbu.

## Rozhodnutí

- **D-1 — mechanik na položce, ne na zakázce.** Vazba je `order_items.employee_id`, ne
  `orders.mechanic_id`. Přesné náklady i „kdo dělal co" na úrovni úkonu.
- **D-2 — `employee_id` nullable + `CHECK` jen u LABOR.** `CHECK (employee_id IS NULL OR
  item_type = 'LABOR')` — nepůjde přiřadit mechanika k materiálu (garance na DB, ne jen aplikační).
- **D-3 — snapshot sazby → `purchase_price`.** Sazba se v čase mění; historická zakázka musí nést
  sazbu z doby práce. Při přiřazení mechanika k LABOR položce se jeho **aktuální** `hourly_rate`
  zapíše jako `purchase_price` položky (snímek, vzor = snapshoty na fakturách). Sazba na
  zaměstnanci pak slouží jen k předvyplnění.
- **D-4 — zaměstnanec se nikdy nemaže, jen deaktivuje** (`is_active=false`, volitelně `left_at`).
  Položky ho drží přes FK navždy (soft-delete, R-06) — historická vazba.
- **D-5 — `user_id` nullable.** Zaměstnanec ≠ přihlašovací účet; mechanik nemusí mít login.
  Volitelná vazba na `security.users` jen u těch, kdo se i přihlašují.
- **D-6 — předvyplnění `purchase_price` sazbou (editovatelné) + backendový fallback.** FE po výběru
  mechanika dosadí sazbu do `purchase_price` (jde přepsat) a pošle ji; backend při LABOR položce
  s `employee_id` a prázdnou `purchase_price` doplní sazbu sám (snapshot je garantovaný i bez FE).
- **D-7 — správa zaměstnanců `/employees`** — CRUD (jméno, pozice, sazba, nástup/odchod), jen
  `ADMIN`/`MANAGER` (§19); soft-delete přes deactivate/activate.

## Datový model
```sql
CREATE SCHEMA employee;
CREATE TABLE employee.employees (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT REFERENCES security.users(id),   -- nullable (D-5)
    first_name  VARCHAR(100) NOT NULL,
    last_name   VARCHAR(100) NOT NULL,
    position    VARCHAR(100),
    hourly_rate NUMERIC(10,2) CHECK (hourly_rate IS NULL OR hourly_rate >= 0),
    hired_at    DATE NOT NULL,
    left_at     DATE,                                    -- NULL = stále zaměstnán
    is_active   BOOLEAN NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_employee_dates CHECK (left_at IS NULL OR left_at >= hired_at)
);
-- + fn_set_updated_at trigger (vzor ostatních schémat), seed pár zaměstnanců, setval.

ALTER TABLE "order".order_items
    ADD COLUMN employee_id BIGINT REFERENCES employee.employees(id),
    ADD CONSTRAINT chk_order_items_employee_labor
        CHECK (employee_id IS NULL OR item_type = 'LABOR');
```
Bez dalších změn — `order_items.purchase_price` už existuje (nese snapshot sazby).

## Fáze

### Fáze 1 — DB (migrace přes skill `nova-migrace`)
- Migrace: `employee` schéma + `employees` tabulka + trigger `updated_at` + seed + `setval`.
- Migrace: `order_items.employee_id` + FK + `CHECK` (D-2).
- **Docs:** `databaze.md` (nové schéma, tabulka, sloupec, CHECK).

### Fáze 2 — Backend: správa zaměstnanců (přes skill `novy-endpoint`)
- `model/domain/employee/Employee`, `dto/employee/EmployeeDto` (namespace: Create/Update/List/Detail),
  `converter/EmployeeConverter`, `mapper/EmployeeMapper` + XML (**strict** `findById`, R-10),
  `service/EmployeeService(+Impl)` (CRUD, soft-delete, guard „nelze měnit sazbu do minulosti" —
  jen aktuální), `controller/EmployeeController` (`/api/{version}/employees`, `@PreAuthorize
  hasAnyRole('ADMIN','MANAGER')`).
- Endpoint pro select položky: `GET /employees` (`activeOnly` filtr) — malý seznam, bez autocomplete.
- **Docs:** `api.md` (+EmployeeController), `backend.md` (nový modul).

### Fáze 3 — Vazba na položku + snapshot sazby
- `OrderItem` doména/DTO/mapper (resultMap, INSERT/UPDATE) rozšířit o `employeeId`
  (+ `employeeName` snapshot do Response pro zobrazení).
- `OrderItemServiceImpl.create/update`: u LABOR položky s `employeeId` a prázdnou `purchasePrice`
  doplnit `employee.hourly_rate` (D-6, fallback snapshot); validace CHECK přes `BusinessRuleException`.
- **Docs:** `api.md` (položka zakázky — pole `employeeId`/`employeeName`).

### Fáze 4 — Frontend
- **Správa:** `EmployeesPage` (+Create/Edit), `EmployeeForm`, `EmployeeTable`, `useEmployeeRowActions`
  — přes §10 vzory (`PageHeader`, `DataTable`, `FormSection`/`FormActions`); nav položka jen ADMIN/MANAGER.
- **Položka práce:** v `OrderItemFormModal` u LABOR select **„Zaměstnanec"** (aktivní; při editaci
  ukázat i odešlého — vzor „(mimo číselník)"); po výběru předvyplnit `purchasePrice` sazbou (D-6).
  Logika v `OrderItemsWrapper` (vlastník stavu, konzistentně s jednotkou).
- **Docs:** `frontend.md` (EmployeesPage, OrderItemFormModal select).

### Fáze 5 — Marže práce na dashboardu
- Rozšířit materiálovou marži o **práci**: LABOR položky `(unitPrice − purchasePrice) × quantity`.
- **Docs:** `plan-dashboard.md` (marže práce doplněna), dashboard kód + test.

## Konvence, které platí
SQL jen v XML (R-01), plná kvalifikace (R-02, `"order".order_items`, `employee.employees`), **strict
`findById`** pro nový modul (R-10), soft-delete (R-06), audit `created_by` ze SecurityContext (R-04),
DTO namespace (§12), ruční converter (R-11), verify-and-fetch (R-03), §19 rolová autorizace, §10 UI
vzory + `npm run check`. Migrace přes skill **`nova-migrace`**, endpointy přes **`novy-endpoint`**.

## Dokumentace (souhrn — aktualizovat průběžně)
`databaze.md` (schéma/tabulka/sloupec/CHECK) · `api.md` (EmployeeController + `employeeId` na položce) ·
`backend.md` (modul employee) · `frontend.md` (EmployeesPage + select v položce) ·
`roadmapa.md` (Employee ✅, marže práce odemčena) · **nový** `docs/funkce/zamestnanci.md` (funkční
dokument co+proč) · **nový** help článek `src/help/` (jazykem obsluhy).

## Verifikace
- `EmployeeServiceTest` (CRUD, soft-delete, guardy, datumové CHECK).
- `LaborCostSnapshotTest` — přiřazení mechanika k LABOR položce zapíše `purchase_price` = sazba;
  pozdější změna sazby historickou položku nezmění.
- `CHECK` na ne-LABOR položce → 422; dashboard marže práce test.
- `./mvnw test` zelené; `npm run check` + `build`; e2e průchod (založit zaměstnance → LABOR položka
  s mechanikem → ověřit náklad/marži).

## Soubory (přehled)
**BE nové:** migrace (2×), `domain/employee/Employee`, `dto/employee/EmployeeDto`,
`converter/EmployeeConverter`, `mapper/EmployeeMapper(.java+.xml)`, `service/EmployeeService(+Impl)`,
`controller/EmployeeController`. **BE změny:** `OrderItem` doména/DTO/mapper/service (+`employeeId`,
snapshot). **FE nové:** `pages/EmployeesPage[Create|Edit]`, `components/EmployeeForm/EmployeeTable`,
`hooks/useEmployeeRowActions`, `api/employees.js`. **FE změny:** `OrderItemFormModal`,
`OrderItemsWrapper`, `navigation.js`, dashboard marže. **Test:** `EmployeeServiceTest`,
`LaborCostSnapshotTest`. **Docs:** viz sekce Dokumentace.
