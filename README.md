# Autoservis

Full-stack systém pro správu autoservisu, budovaný jako výukový/portfoliový projekt: backend v Javě, návrh REST API, modelování databáze a moderní React frontend.

## Technologie

| Vrstva | Technologie |
|---|---|
| Jazyk / framework | Java 21, Spring Boot 4 |
| Persistence | MyBatis (výhradně XML mappery), PostgreSQL 16+ (multi-schema), Flyway |
| Security | Spring Security + JWT (HTTP-only cookies, rotace refresh tokenu) |
| AI | Spring AI (Anthropic Claude) — extrakce dodavatelských faktur z PDF |
| PDF výstup | openhtmltopdf + Thymeleaf, QR platby (ZXing / SPAYD) |
| Frontend | React 19 + React Router 7 + Vite, Bootstrap 5.3 (+ MUI) |

## Funkce

- **Zákazníci** — CRUD pro soukromé osoby i firmy, adresy, kontaktní osoby, GDPR souhlasy, fulltextové hledání s podporou české diakritiky.
- **Vozidla** — evidence s validací VIN, editovatelná historie tachometru (deník odečtů s cache synchronizovanou v DB).
- **Zakázky** — životní cyklus, položky (práce / materiál / ostatní služby) s řazením drag-and-drop, import položek ze skladových šarží.
- **Fakturace** — faktury vázané na zakázky se stavovým automatem (DRAFT → ISSUED → PAID), neměnné snapshoty stran, rekapitulace DPH, PDF export s QR platbou, dobropisy a příjmové pokladní doklady.
- **Sklad** — skladové karty, dodavatelské příjemky importované z PDF přes Claude, sledování šarží, append-only deník skladových pohybů jako jediný zdroj pravdy, inventury.
- **Autentizace** — bezstavové JWT v HTTP-only cookies, rotace refresh tokenů s detekcí opakovaného použití, blacklist tokenů.

## Architektura

```
React SPA → REST (/api/v1) → Controller → Service → MyBatis XML → PostgreSQL
```

- Multi-schema databáze (`security`, `customer`, `vehicle`, `order`, `billing`, `warehouse`, `employee`, `schedule`), mezischémové FK záměrně.
- Čísla dokladů, `updated_at`, skladové stavy a cenové souhrny udržují DB triggery a views (čísla faktur a PPD skládá aplikace podle masky).
- Chybové odpovědi ve formátu RFC 9457 ProblemDetail se strojovými kódy.
- Verzované Flyway migrace.

Kompletní dokumentace žije v [`docs/`](docs/): [architektura](docs/architektura.md) · [databaze](docs/databaze.md) · [api](docs/api.md) · [backend](docs/backend.md) · [frontend](docs/frontend.md) · [konvence](docs/konvence.md) · [tech-dluhy](docs/tech-dluhy.md) · [roadmapa](docs/roadmapa.md) · [nasazeni](docs/nasazeni.md). Historické dokumenty jsou archivované v `docs/archiv/`.

## Zprovoznění

Předpoklady: Java 21, PostgreSQL 16+, Node.js 20.19+ (nebo 22+), Docker (jen pro testy).

```sql
CREATE DATABASE autoservis;
```

(Schémata, tabulky i rozšíření `unaccent`/`pg_trgm` si při prvním startu vytvoří Flyway sám. Řešení potíží s lokální DB: [docs/lokalni-databaze.md](docs/lokalni-databaze.md).)

Zkopíruj `src/main/resources/application-local.yaml.example` na `application-local.yaml` a doplň tři hodnoty:

1. **heslo k DB**,
2. **JWT klíč** (`openssl rand -base64 48`),
3. **Anthropic API klíč** — ⚠️ bez vyplněné hodnoty aplikace vůbec nenastartuje (v konfiguraci je `${ANTHROPIC_API_KEY}` bez defaultu). Pro vyzkoušení bez AI importu faktur stačí ponechat dummy hodnotu ze šablony; reálný klíč pro import PDF vydává [console.anthropic.com](https://console.anthropic.com).

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=local   # backend na :8080, Flyway migruje automaticky
cd frontend/autoservis-frontend && npm install && npm run dev   # frontend na :5173 (proxy /api)
```

Seed účty (jen lokální vývoj): `admin` / `manager` / `mechanic`, heslo `Password1!`.

## Stav

Všechny hlavní moduly (auth, zákazníci, vozidla, zakázky, fakturace vč. FE a PDF, sklad vč. AI importu a dodavatelů, zaměstnanci, plánovací kalendář, dashboard) fungují end-to-end; aplikace běží v produkci. Otevřené body sleduje [docs/roadmapa.md](docs/roadmapa.md) a [docs/tech-dluhy.md](docs/tech-dluhy.md).

Produkční nasazení (systemd + nginx): [docs/nasazeni.md](docs/nasazeni.md).
