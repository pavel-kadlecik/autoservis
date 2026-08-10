# Funkce: Zaměstnanci a náklad práce

> Funkční dokumentace — **co** funkce dělá a **proč** je postavená takhle.
> Technické detaily vrstev: [databaze.md §6b](../databaze.md) (schéma `employee.employees`, `order_items.employee_id`) · [backend.md](../backend.md) (modul employee, `OrderItemService`) · [api.md](../api.md) (sekce Zaměstnanci + Položky zakázky) · [frontend.md §5](../frontend.md) (Správa zaměstnanců + select u položky) · [konvence.md §19](../konvence.md) (`@PreAuthorize`).
> Uživatelská nápověda: v aplikaci záložka **Nápověda** (`frontend/…/src/help/zamestnanci.md`).
> Prováděcí plán: [docs/plan-employee.md](../plan-employee.md) (rozhodnutí D-1…D-7).

## Co funkce dělá

Servis eviduje **kdo odvedl kterou práci** a za **jakou hodinovou sazbu** (náklad práce) — dosud se náklad práce nikde nevedl, takže marži šlo počítat jen z materiálu.

- **Správa zaměstnanců** (`/employees`, jen ADMIN/MANAGER): seznam s hledáním a filtrem „jen aktivní", založení/úprava (jméno, pozice, hodinová sazba, datum nástupu/odchodu), de/aktivace (soft-delete).
- **Přiřazení mechanika k položce práce**: v položce zakázky typu **LABOR** se vybere mechanik; jeho **aktuální** hodinová sazba se předvyplní do nákupní ceny položky a **zmrazí** se tam.
- Na jedné zakázce může dělat víc mechaniků — každý na své položce, se svými hodinami a sazbou.
- Odemyká **marži práce** na dashboardu (fáze 5): `(prodejní − nákupní) × množství` i pro práci.

## Klíčová rozhodnutí a proč

| Rozhodnutí | Proč |
|---|---|
| **Mechanik na položce, ne na zakázce** (`order_items.employee_id`, D-1) | přesné náklady a „kdo dělal co" na úrovni úkonu; na jednom autě dělá víc lidí |
| **`employee_id` jen u LABOR** — DB `CHECK` + service guard `EMPLOYEE_ONLY_ON_LABOR` (D-2) | mechanika nelze přiřadit k materiálu; garance je na databázi, ne jen v aplikaci |
| **Sazba se snímkuje do `purchase_price`** (D-3) | sazba se v čase mění; historická zakázka musí nést sazbu **z doby práce**. Vzor jsou snapshoty na fakturách. Jednou zapsaný snímek se nepřepočítává → pozdější změna sazby zaměstnance historickou položku nezmění |
| **Předvyplnění + backendový fallback** (D-6) | frontend po výběru mechanika dosadí sazbu do nákupní ceny (jde přepsat) a pošle ji; když je prázdná, doplní ji backend sám — snapshot je garantovaný i bez frontendu |
| **Zaměstnanec se nikdy nemaže, jen deaktivuje** (`is_active`, volitelně `left_at`, D-4) | položky ho drží přes FK navždy — historická vazba přežije odchod. Odešlý se u editace položky nabídne jako „(mimo číselník)" |
| **`user_id` je volitelný** (D-5) | zaměstnanec ≠ přihlašovací účet; mechanik nemusí mít login. Vazba na `security.users` je unikátní a nepovinná |
| **Správa jen ADMIN/MANAGER, čtení seznamu i pro mechanika** (D-7, §19) | zakládání a sazby jsou úkon vedení; mechanik ale potřebuje číst aktivní seznam pro select u položky, proto jsou `GET` na baseline a jen mutace mají `@PreAuthorize` |
| **Sazba se mění jen „do budoucna"** | úprava `hourly_rate` slouží k předvyplnění nových snímků; do rozpracovaných ani historických zakázek nesahá (plyne ze snapshotu, není potřeba zvláštní guard) |

## Chování při chybách

- Datum odchodu před datem nástupu → **422** `INVALID_EMPLOYEE_DATES` (hlídá i DB `CHECK`).
- `user_id`, který už drží jiný zaměstnanec → **422** `DUPLICATE_EMPLOYEE_USER`.
- Neexistující `user_id` při založení/úpravě → **404**.
- Mechanik přiřazený k ne-LABOR položce → **422** `EMPLOYEE_ONLY_ON_LABOR`.
- Neexistující mechanik na LABOR položce → **404**.
- Přístup na mutace `/employees/**` bez role ADMIN/MANAGER → **403** `ACCESS_DENIED`.

## Datový model (shrnutí)

- **`employee.employees`** (V58): `user_id` (nullable, unique), jméno, `position`, `hourly_rate`, `hired_at`, `left_at`, `is_active` + audit; `CHECK` na nezápornost sazby a `left_at ≥ hired_at`.
- **`"order".order_items.employee_id`** (V59): FK → `employee.employees` (`ON DELETE RESTRICT`), `CHECK (employee_id IS NULL OR item_type = 'LABOR')`. Náklad práce nese existující `purchase_price`.
