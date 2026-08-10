# CLAUDE.md — Autoservis

Webová aplikace pro správu autoservisu (zákazníci, vozidla, zakázky, fakturace, sklad s AI importem faktur). **Výuková aplikace** — každý krok se zavádí správně, nebo vůbec. Komunikace s uživatelem **česky**.

## Pravidlo č. 1

**VŽDY nejdřív ukaž CO a PROČ chceš změnit — provedení rozhoduje uživatel.**
Výjimka: uživatel explicitně řekne „udělej to ty" / „oprav to ty".

## Stack

| Vrstva | Technologie |
|---|---|
| Backend | Java 21, Spring Boot 4, Maven (kořen repa) |
| Persistence | MyBatis — SQL **výhradně v XML** (`src/main/resources/mapper/`), žádné JPA |
| DB | PostgreSQL multi-schema (modul = schéma), Flyway migrace |
| Security | Spring Security + JWT v HTTP-only cookies |
| AI | Spring AI Anthropic — extrakce PDF faktur („AI čte, kód počítá") |
| Frontend | React 19 + Vite, `frontend/autoservis-frontend/` (Bootstrap 5.3 + bodově MUI) |

## Spuštění

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=local   # backend :8080
./mvnw test                                                # testy (vyžaduje Docker — Testcontainers)
cd frontend/autoservis-frontend && npm run dev             # frontend :5173 (proxy /api → :8080)
```

Lokální konfigurace: `application-local.yaml` (gitignorováno, šablona `.example`). Env: `DB_PASSWORD`, `JWT_SECRET`, `ANTHROPIC_API_KEY`.

## Mapa dokumentace — když děláš X, čti Y

| Úkol | Dokument |
|---|---|
| Změna DB / nová migrace | [docs/databaze.md](docs/databaze.md) — schéma rekonstruované z migrací |
| Nový/změněný endpoint | [docs/api.md](docs/api.md) + [docs/konvence.md](docs/konvence.md) |
| Práce na Java vrstvě | [docs/backend.md](docs/backend.md) + [docs/konvence.md](docs/konvence.md) |
| Práce na frontendu | [docs/frontend.md](docs/frontend.md) |
| Celkový obraz systému | [docs/architektura.md](docs/architektura.md) |
| Nová funkce / změna chování funkce | [docs/funkce/](docs/funkce/) — funkční dokument (co+proč) + článek nápovědy v aplikaci (`frontend/…/src/help/`) |
| Hluboké pochopení implementace funkce (onboarding, review) | [docs/pruvodce/](docs/pruvodce/) — detailní průvodce (soubor po souboru, kód + proč); volitelný, pro větší funkce |
| Před návrhem větší změny | [docs/tech-dluhy.md](docs/tech-dluhy.md) + [docs/roadmapa.md](docs/roadmapa.md) |
| Deploy, secrets, API klíče | [docs/nasazeni.md](docs/nasazeni.md) |
| Lokální dev DB (spouštění, zálohy, řešení problémů) | [docs/lokalni-databaze.md](docs/lokalni-databaze.md) |
| Hooks, permissions, skills (`.claude/`) | [docs/claude-infrastruktura.md](docs/claude-infrastruktura.md) |

**Po každé změně, která mění fakta v dokumentaci (migrace, endpoint, pravidlo), aktualizuj příslušný dokument.**

## Nejkritičtější pravidla (úplný seznam: docs/konvence.md)

- Hotová Flyway migrace se **nikdy nemění** — změna = nový soubor `V{n+1}__*.sql` + aktualizace `databaze.md`.
- SQL jen v XML mapperech; tabulky plně kvalifikované (`"order".orders` — schéma order v uvozovkách).
- Nikdy `null` ze service — `Optional` nebo `ResourceNotFoundException`. Business validace v service → `BusinessRuleException`.
- Soft-delete přes `is_active`; `updated_at` a čísla dokladů (`ZNK-`, `ZAK-`, `OD`…) řeší DB triggery. Výjimka: **číslo faktury** (od V71) a **číslo PPD** (od V92, zdroj MASK/INVOICE/MANUAL dle V93) skládá aplikace podle masky z Fakturačních údajů (viz `docs/konvence.md` §18).
- Audit (`created_by`) doplňuje server z `@AuthenticationPrincipal`, nikdy z DTO.
- `id` patří do URL (path variable), ne do těla requestu; na FE z `useParams()`.
- Domain ↔ DTO přes ruční `@Component` konvertory (žádný MapStruct); DTO namespace pattern (`XxxDto.CreateRequest`).
- Dead code smazat — „možná se bude hodit" není důvod.

## Zákazy

- **Needitovat nic v `docs/archiv/`** — archiv je jen ke čtení, není závazný. *(vynucuje hook)*
- Žádné commity/push bez vyzvání uživatele.
- Neměnit commitnuté migrace, nemazat data (soft-delete). *(vynucuje hook — `.claude/hooks/guard-immutable.js`)*
- Do gitu nikdy secrets (API klíče, hesla) — viz `docs/nasazeni.md`.
