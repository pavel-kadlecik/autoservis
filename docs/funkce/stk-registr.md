# Funkce: STK a registr vozidel (dataovozidlech.cz)

> Funkční dokumentace — **co** funkce dělá a **proč** je postavená takhle.
> Technické detaily vrstev: [databaze.md §3](../databaze.md) · [backend.md §4b](../backend.md) · [api.md](../api.md) (sekce Registr vozidel) · [frontend.md §5](../frontend.md) (sekce STK).
> Uživatelská nápověda: v aplikaci záložka **Nápověda** (`frontend/…/src/help/stk-registr.md`).

## Co funkce dělá

U vozidel eviduje stav STK a další údaje z oficiálního Registru silničních vozidel (Datová kostka RSV, Ministerstvo dopravy) přes veřejné API dataovozidlech.cz:

- **při založení vozidla** se STK načte automaticky (best-effort — výpadek registru založení nezhatí),
- **ve formuláři vozidla** tlačítko „Načíst z registru" předvyplní údaje podle VIN / čísla ORV / čísla TP (dotaz přes ORV předvyplní i VIN — obsluha nemusí opisovat 17 znaků),
- **na detailu vozidla** karta „STK a registr vozidel" s ruční aktualizací,
- **v seznamu vozidel** barevný badge platnosti + filtr „Končící STK" (do 30 dnů / propadlá).

## Klíčová rozhodnutí a proč

| Rozhodnutí | Proč |
|---|---|
| **Snapshoty, ne přepis** — každé volání API = nový řádek `vehicle.registry_snapshots`, append-only | auditní historie „co registr kdy řekl"; surová odpověď v JSONB (~70 polí) šetří opakovaná volání (limit 27 dotazů/min) |
| **`vehicles.stk_valid_until` je cache vlastněná DB triggerem** — aplikace ji nikdy nezapisuje | jediný zdroj pravdy je poslední snapshot; vzor `mileage_history` → `current_mileage_km` (V20) |
| **Datum STK nelze editovat ručně** — žádné pole ve formuláři, žádný endpoint | STK je autoritativní údaj státního registru, ne názor uživatele; zastaralý údaj = „Aktualizovat z registru". Ruční evidence (zahraniční vozidla, EK, pojištění) patří do budoucí Vehicle Phase 4b (`vehicle_inspections`) |
| **`registry_status` je VARCHAR, ne ENUM** | množinu hodnot řídí ministerstvo — reálné API vrátilo „ZÁNIK", který v dokumentaci není; neznámá hodnota nesmí shodit INSERT |
| **Best-effort při create, strict na endpointech** | založení vozidla nesmí spadnout kvůli cizí službě (jen WARN log); ruční lookup/refresh naopak chybu poctivě vrátí (503/422) |
| **HTTP volání mimo DB transakci** | externí volání nesmí držet DB spojení; persistence je jediný INSERT, atomicitu zajistí statement + trigger |
| **Nerozpoznaná hodnota → `null`, nikdy odhad** | mapování paliva („BA 95 B"→PETROL) a výkonu („50 / 5000"→50 kW) je defenzivní; prázdná hodnota nechá pole formuláře na pokoji |

## Čerstvost dat

Data se obnovují **jen on-demand**: při založení vozidla a tlačítkem „Aktualizovat z registru" na detailu. Editace vozidla (PUT) registr nevolá; lookup pro prefill nic neukládá. Badge a filtr „Končící STK" jsou proto jen tak čerstvé jako poslední snapshot (viditelný na detailu jako „Poslední načtení") — u dlouho neotevřeného vozidla mohou být zastaralé. Automatický noční refresh je plánován jako Vehicle Phase 4c ([roadmapa.md](../roadmapa.md) §2.3).

## Chování při chybách

- Registr nedostupný (timeout, rate limit 27/min, špatný klíč, 5xx) → **503** s kódem `REGISTRY_RATE_LIMITED` / `REGISTRY_AUTH_FAILED` / `REGISTRY_TIMEOUT` / `REGISTRY_ERROR`.
- Vozidlo v registru neexistuje → **422** `VEHICLE_NOT_IN_REGISTRY` (validní obchodní odpověď, ne chyba infrastruktury).
- Bez API klíče aplikace normálně běží; volání registru vrací 503 `REGISTRY_AUTH_FAILED`.

## Mapa implementace

- **DB:** migrace `V38__init_vehicle_registry_snapshots.sql` — tabulka, JSONB, trigger `trg_registry_snapshots_sync_stk`, partial index pro filtr.
- **Backend:** `client/VehicleRegistryClient(Impl)` (HTTP, Jackson 3), `model/converter/RegistryConverter` (defenzivní parsing), `service/VehicleRegistryService(Impl)` (lookup / refreshForVehicle / tryRefreshAfterCreate), `controller/VehicleRegistryController` (3 endpointy), orchestrace v `VehicleController.create`. Konfigurace `registry.dataovozidlech.*`, klíč `DATAOVOZIDLECH_API_KEY` (viz [nasazeni.md](../nasazeni.md) §7).
- **Frontend:** `VehicleForm` (prefill blok), `VehiclesPageDetail` (karta), `VehicleTable` + `VehiclesPage` (badge, filtr), `format.js#getStkBadge`.
- **Testy:** `RegistryConverterTest` (mapování), `VehicleRegistryClientTest` (HTTP chyby, MockRestServiceServer), `VehicleRegistryServiceTest` (7 full-stack scénářů vč. DB triggeru).

## Historie

- 2026-07-18/19: navrženo a implementováno (V38, 4 commity `20c0e40`…`4568bce`); ověřeno proti reálnému API (nalezení, nenalezení, odmítnutý klíč).
