# architektura.md — Přehled systému

> Celkový obraz aplikace: stack, moduly, tok requestu, mapa repozitáře, integrace.
> Detaily: DB → `databaze.md` · API → `api.md` · backend → `backend.md` · frontend → `frontend.md`.

## Co je Autoservis

Webová aplikace pro správu autoservisu: zákazníci, vozidla (s historií km), servisní zakázky s položkami, fakturace (vč. PDF s QR platbou), sklad s AI importem dodavatelských faktur. **Výuková aplikace** — každý krok se zavádí správně, nebo vůbec. Určeno pro zaměstnance servisu (zákaznický portál je jen otevřená úvaha, viz `roadmapa.md`).

## Technologický stack

| Vrstva | Technologie | Verze |
|---|---|---|
| Jazyk / framework | Java + Spring Boot | 21 + 4.0.3 |
| Persistence | MyBatis (výhradně XML mappery) | mybatis-spring-boot-starter 4.0.1 |
| Databáze | PostgreSQL (multi-schema) | 16+ |
| Migrace | Flyway (aktuálně V1–V73) | 11+ |
| Bezpečnost | Spring Security + jjwt (JWT v HTTP-only cookies) | 7 + 0.13.0 |
| AI | Spring AI — Anthropic (extrakce PDF faktur) | BOM 2.0.0-M4, model `claude-sonnet-4-6` |
| PDF výstup | openhtmltopdf + Thymeleaf šablony | 1.1.40 |
| QR platba | ZXing (SPAYD / „QR Platba") | 3.5.4 |
| Frontend | React + React Router + Vite | 19 + 7 + 8 |
| UI | Bootstrap 5.3 (dominantní) + MUI (bodově) + dnd-kit | |

**UI stack — rozhodnutí R-1 (2026-07-21): MUI se ponechává.** Používají ho jen tři místa
(`TableRowActionMenu`, `PaginatorRounded`, ikony); výměna za Bootstrap by byla čistá práce navíc
bez užitku pro uživatele. Sjednocení vzhledu proběhlo **uvnitř** Bootstrapu — sdílené komponenty
a závazné konvence jsou v `frontend.md` §10, staticky je hlídá `npm run check`. Zapsáno jako
TD-43 pro případ, že by se poměr sil někdy otočil.
| Testy | Spring Boot Test + Testcontainers (PostgreSQL) | |
| Build | Maven (wrapper `mvnw`) / npm | |

⚠️ Spring Boot 4, Security 7 a Spring AI 2.0.0-M4 jsou bleeding-edge/milestone verze (v pom.xml je zapnutý Spring milestones repozitář).

## Moduly

Modul = DB schéma. Závislosti tečou zleva doprava:

```
security ──┐
customer ──┼─→ vehicle ─→ "order" (zakázky + položky) ─→ billing (faktury)
           │                 ↑  ↑
           │                 │  └── warehouse (sklad, dodavatelé, příjemky)
           │                 │       (položky zakázky lze importovat ze šarží skladu)
           └── schedule (plánovací kalendář) ┘
                (objednávka termínu vzniká PŘED zakázkou; převodem na ni ukáže order_id)
```

| Modul | Schéma | Obsah | Stav |
|---|---|---|---|
| Autentizace | `security` | users, roles, refresh_tokens, token_blacklist; JWT + rotace refresh tokenů | funkční e2e |
| Zákazníci | `customer` | customers, addresses, contact_persons, communications; FTS s unaccent | funkční e2e |
| Vozidla | `vehicle` | vehicles + mileage_history (trigger cache km) | funkční e2e |
| Zakázky | `"order"` | orders + order_items (LABOR/MATERIAL/OTHER_SERVICES), drag-and-drop řazení, import ze skladu | funkční e2e |
| Fakturace | `billing` | invoices (1:1 k zakázce, stavový automat, snapshoty stran), invoice_items, company_profile; PDF + QR | funkční e2e |
| Sklad | `warehouse` | suppliers, products, příjemky (AI z PDF/fotky, ISDOC, ruční) s draft workflow a stornem, šarže, pohybový ledger (korekce, odpis, vratka, spotřeba), ocenění zásob, inventura, přehled pod minimem | funkční e2e; plán `plan-sklad.md` E1–E8 hotový, otevřený zůstává dobropis jako doklad (E5b) |
| Plánování | `schedule` | appointments — objednávky termínů (BOOKING) a blokace dílny (CLOSURE) v jedné tabulce; objednávka vzniká **před** zakázkou a lze ji na ni převést | funkční e2e; `funkce/planovaci-kalendar.md` |
| Dashboard | — | úvodní přehled: KPI + fronty „vyžaduje pozornost"/„provoz" z agregačního `/dashboard/summary` (read-only nad ostatními moduly) | funkční e2e; `funkce/dashboard.md` |

## Tok requestu

```
React SPA (Vite dev :5173, proxy /api → :8080)
   │  fetch, credentials: 'include' (JWT cookie)
   ▼
JwtAuthenticationFilter (cookie `jwt` → blacklist check → parse → SecurityContext)
   ▼
Controller (/api/{version}/…, @Valid DTO, @AuthenticationPrincipal pro audit)
   ▼
Service (@Service, business validace → BusinessRuleException; @Transactional na mutacích)
   ▼
Mapper interface (@Mapper) → XML mapper (src/main/resources/mapper/**)
   ▼
PostgreSQL (multi-schema; triggery generují čísla dokladů a udržují updated_at,
            cache km a stav skladu; views počítají cenové souhrny)
```

Chyby: `GlobalExceptionHandler` → RFC 9457 ProblemDetail + `errors[]` (viz `api.md`).

Část business logiky je **záměrně v DB**: číslování dokladů (triggery V9/V11/V15), `updated_at`, přepočet `current_mileage_km`, stav skladu z pohybového ledgeru, cenové/DPH souhrny (views V25/V32/V37).

## Mapa repozitáře

```
autoservis/
├── CLAUDE.md                      ← vstupní bod pro AI (pravidla + mapa docs)
├── README.md                      ← přehled projektu (EN)
├── pom.xml, mvnw                  ← Maven build (backend v kořeni)
├── deploy.sh                      ← jednorázový deploy (pull, build, restart)
├── deploy/                        ← systemd unit, sudoers, setup.sh
├── docs/                          ← dokumentace (tento adresář); docs/archiv/ = historie
├── src/main/java/cz/palo/autoservis/
│   ├── config/                    ← mybatis/ (PgEnumTypeHandler), security/ (SecurityConfig)
│   ├── controller/                ← 16 REST controllerů (+ 7 ve warehouse/ = 23, 127 endpointů)
│   ├── service/ + service/impl/   ← business logika (rozhraní + impl)
│   ├── model/                     ← domain/ (POJO po modulech), dto/ (namespace pattern),
│   │                                 enums/, converter/ (ruční @Component konvertory)
│   ├── mapper/                    ← MyBatis rozhraní
│   ├── exception/                 ← výjimky + GlobalExceptionHandler
│   └── security/                  ← AuthController, JWT filtr/service, vlastní mappery
├── src/main/resources/
│   ├── mapper/**/*.xml            ← veškeré SQL (19 + 7 warehouse = 26 XML)
│   ├── db/migration/              ← Flyway V1–V73
│   ├── templates/pdf/             ← Thymeleaf šablony dokladů (faktura, dobropis, PPD, zakázkový list) + styly, fonts/, images/
│   └── application*.yaml          ← base / prod / local(.example)
└── frontend/autoservis-frontend/  ← React SPA (src/api, components, pages, hooks, context)
```

## Integrace

- **Anthropic Claude (Spring AI):** `PdfDocumentExtractionService` — doklad dodavatele (faktura / dodací list) jako PDF **nebo fotka/sken** → `Media` → strukturovaná extrakce do recordu (`.entity()`), každé pole s přiznaným původem (VERBATIM/DERIVED/ABSENT). Zásada: **„AI čte, kód počítá"** — dopočty, mapování sazeb a křížové kontroly dělá Java (`DraftAssembler` + `DraftVerificationService`); import ukládá jen draft ke kontrole. Vyžaduje `ANTHROPIC_API_KEY`. Detail: `docs/funkce/import-prijemek.md`.
- **ISDOC (bez AI):** `IsdocParser` — český standard e-faktury (XSD 6.0.2) parsovaný přes JAXP do **téhož kanonického draftu**; strojová data, všechna pole VERBATIM, XXE vypnuto. Nový vstupní kanál = nový adaptér, zbytek pipeline beze změny.
- **PDF faktury:** Thymeleaf HTML → openhtmltopdf → byte[]; DejaVu fonty (česká diakritika), logo/podpis jako data-URI, QR Platba (SPAYD) přes ZXing.
- **Deploy:** systemd služba `autoservis-backend` na Linux serveru `/opt/autoservis`; viz `nasazeni.md`.

## Klíčová architektonická rozhodnutí (a proč)

| Rozhodnutí | Důvod |
|---|---|
| MyBatis místo JPA/Hibernate | výukový cíl: plná kontrola nad SQL |
| Multi-schema (modul = schéma) | izolace modulů, čitelnost; cross-schema FK jsou záměrné |
| BIGSERIAL místo UUID | výkon a jednoduchost |
| JWT v HTTP-only cookie místo Authorization headeru | odolnost proti XSS |
| Faktura ↔ zakázka 1:1 | jednoduchost; dělená fakturace = více zakázek |
| Snapshoty na faktuře (invoice_party, …) | faktura je právní doklad — nesmí sledovat pozdější změny dat |
| Sklad jako pohybový ledger + trigger | auditovatelnost; stav se odvozuje, nikdy ručně nepřepisuje |
| Šarže (goods_receipt_items) jako nositel nákupní ceny | dohledatelnost: položka zakázky → šarže → faktura dodavatele |
| Ruční konvertory místo MapStruct | explicitnost, výukový cíl |
| Dodavatel vzniká jen importem faktury (bez ručního Create) | jediná brána = konzistence dat, dedup přes normalizované registrační číslo |
| Kanonický draft příjemky (AI PDF/foto, ISDOC, ruční) | nový formát dokladu = nový adaptér; kontroly, párování i potvrzení zůstávají jedním kódem |
| Oceňování zásob skutečnými pořizovacími cenami (šarže), ne průměrem | legislativně čisté i auditovatelné; výdej dohledatelný k faktuře dodavatele (rozhodnutí R-A) |
| Opravy skladu vždy pohybem, nikdy přepisem stavu | ledger je append-only: storno příjemky i inventura generují kompenzační pohyby (R-C, R-H) |

Historie původních rozhodnutí: `docs/archiv/ROZHODNUTI_A_KONVENCE.md` (nezávazné).
