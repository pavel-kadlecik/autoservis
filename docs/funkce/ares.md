# Funkce: Načítání firmy z ARES (ares.gov.cz)

> Funkční dokumentace — **co** funkce dělá a **proč** je postavená takhle.
> Technické detaily vrstev: [backend.md §4b](../backend.md) (sekce ARES) · [api.md](../api.md) (sekce ARES) · [frontend.md](../frontend.md) (sekce ARES).
> Uživatelská nápověda: v aplikaci záložka **Nápověda → Zákazníci** (`frontend/…/src/help/zakaznici.md`).

## Co funkce dělá

Ve formuláři zákazníka typu **firma** předvyplní údaje z ARES (Administrativní registr ekonomických subjektů, MF ČR) podle IČO — obsluha zadá 8 číslic, klikne na **„Načíst z ARES"** a formulář doplní:

- **název firmy** (`obchodniJmeno`),
- **DIČ** (jen ve formátu `CZ…` — zahraniční formát by neprošel validací formuláře, pole se nechá prázdné),
- **adresu sídla** do fakturační adresy (ulice, číslo popisné/orientační, město, PSČ, kód země).

Vše lze po načtení ručně přepsat; uložení probíhá běžným `POST /customers` — lookup sám **nic nezapisuje**.

## Klíčová rozhodnutí a proč

| Rozhodnutí | Proč |
|---|---|
| **Volání přes backend, ne fetch z prohlížeče** | stejná konvence jako registr vozidel: jednotné chyby (`BusinessRuleException`/503 ProblemDetail), nezávislost na CORS politice státní služby, validace IČO v kódu |
| **Žádná persistence, žádné snapshoty** | na rozdíl od STK není co sledovat v čase — jde o jednorázový prefill; uloží se až zákazník, kterého obsluha zkontrolovala |
| **Validace IČO včetně kontrolní číslice (mod 11) před voláním** | IČO s vadným kontrolním součtem nemůže existovat — okamžitá 422 `INVALID_ICO` je rychlejší i srozumitelnější než „nenalezeno" z ARES |
| **Právní forma se nepředvyplňuje** | ARES vrací číselník (`pravniForma: "112"`); mapování kódů na text zatím neřešíme (rozhodnutí 2026-08-09) |
| **Adresa sídla → fakturační adresa (BILLING)** | fakturační adresa se tiskne na doklady, sídlo z ARES je pro ni autoritativní zdroj |
| **Tlačítko, ne automatický dotaz při psaní** | uživatel má kontrolu, žádné zbytečné požadavky na státní API (fair-use limity) |
| **Prefill jen non-null hodnotami** | chybějící údaj v ARES nikdy nesmaže ručně zadanou hodnotu (vzor VehicleForm) |
| **Fallback ulice: `nazevUlice` → část obce → obec** | vesnice bez názvů ulic evidují sídlo jako „obec + číslo popisné" |

## Chování při chybách

- ARES nedostupný (timeout, rate limit, 5xx) → **503** s kódem `ARES_TIMEOUT` / `ARES_RATE_LIMITED` / `ARES_ERROR`.
- IČO neexistuje → **422** `SUBJECT_NOT_IN_ARES` (validní obchodní odpověď, ne chyba infrastruktury).
- Vadné IČO (délka, kontrolní číslice) → **422** `INVALID_ICO`, ARES se nevolá.
- Bez konfigurace navíc — ARES je **veřejné API bez klíče** (na rozdíl od `DATAOVOZIDLECH_API_KEY`).

## Mapa implementace

- **DB:** žádná změna (žádná migrace).
- **Backend:** `config/registry/AresProperties` + `AresClientConfig` (bean `aresRestClient`, `registry.ares.*`), `client/AresClient(Impl)` (`GET /ekonomicke-subjekty/{ico}`, 404 → empty), `model/dto/ares/AresDto.LookupResponse`, `service/AresLookupService(Impl)` (validace IČO), `controller/CustomerAresController` (`GET /customers/ares-lookup`), `exception/AresUnavailableException` → 503.
- **Frontend:** `CustomerForm` — input-group IČO + tlačítko se spinnerem, prefill companyName/dic/billingAddress.
- **Testy:** `AresClientTest` (HTTP + mapování adresy, MockRestServiceServer), `AresLookupServiceTest` (5 full-stack scénářů přes MockMvc).

## Historie

- 2026-08-09: navrženo a implementováno; ověřeno proti reálnému API (MICROSOFT s.r.o. — IČO 47123737, neexistující IČO → 404).
