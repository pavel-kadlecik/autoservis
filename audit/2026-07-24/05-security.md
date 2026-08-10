# Audit 5/9 — Bezpečnostní vrstva a konfigurace

> Součást hloubkového auditu 2026-07-24 (commit `409d3ad`, větev `audit-one`).
> Přehled celého auditu: [00-prehled.md](00-prehled.md).
>
> Defenzivní interní review vlastního kódu. Každé tvrzení o chybějící ochraně ověřeno i v ostatních
> vrstvách (filtr, `SecurityConfig`, migrace, service).
>
> **Verifikace hlavního auditora:** N1 (`revokeAllByUserId` má jediné volání — reuse detekce ve
> `AuthenticationService.refresh:128`, v change/reset password chybí), N2 (`UserServiceImpl.update:92-118`
> bez last-admin guardu, guard jen v `deactivate:146`) a seed ROLE_CUSTOMER účty (`V3:54,57`) ověřeny
> přímo. Potvrzeno.

## Nálezy

### VYSOKÝ

**N1 — Změna hesla ani admin reset neruší aktivní sessions (refresh tokeny)** · `AuthenticationService.java:182-196`, `UserServiceImpl.java:179-188` · confidence: jistý
`changePassword()` jen přepíše hash; `resetPassword()` přepíše hash + `unlockAccount()`. **Ani jeden nevolá `refreshTokenMapper.revokeAllByUserId(...)`.** `revokeAllByUserId` má jediné volání — reuse detekce v `refresh()` (ř. 128). Scénář: útočník má session oběti; oběť/admin změní heslo v domnění, že tím útočníka odřízne — útočníkův refresh token dál razí access tokeny po celých 7 dní. „Změna hesla = odhlásit všude" neplatí. Oprava: v obou metodách zavolat `revokeAllByUserId(userId)` (mapper i dotaz existují). *(Klíčový nález K-6.)*

**N2 — Admin může přes `PUT /users/{id}` odebrat roli ADMIN poslednímu adminovi (i sobě)** · `UserServiceImpl.java:90-118` · confidence: jistý
`update()` dělá `deleteRoles` + `insertRoles` bez pojistky. Guard „poslední admin" i „ne sám sebe" je jen v `deactivate()` (ř. 134-150). Trvalý lockout privilegované funkce (celý `UserController` je `hasRole('ADMIN')`) → oprava jen zásahem do DB. Oprava: přenést kontrolu do `update()`. *(Klíčový nález K-2 — shodně backend S-9. Pozitivní: eskalace role zdola není možná, role mění jen ADMIN.)*

### STŘEDNÍ

**N3 — Refresh tokeny v DB v plaintextu** · `V1:112-118`, `RefreshTokenMapper.java` · confidence: jistý
Nález V4 přešel na SHA-256 hash u `token_blacklist`, ale refresh tokeny zůstaly plaintext (`token VARCHAR(36)`, holé UUID). Únik DB zálohy = přímo použitelné refresh tokeny s životností 7 dní. Odůvodnění „opaque = out of scope" je vůči modelu úniku DB nekonzistentní. Oprava: hashovat stejně jako blacklist. *(Klíčový nález K-7, sladit s K-6.)*

**N4 — CORS originy natvrdo `localhost:5173` + `allowCredentials(true)`** · `SecurityConfig.java:122-139` · confidence: jistý (kryto TD-33)
V prod nefunkční; `application-prod.yaml` originy nepřekrývá (CORS je jen v Javě). Doporučení: originy do konfigurace (`@Value`), v prod reálná doména; nikdy `*` ani reflexe originu.

**N5 — Chybí bezpečnostní hlavičky (CSP, HSTS, nosniff) a `frameOptions` vypnut** · `SecurityConfig.java:83-84` · confidence: pravděpodobný
`.headers(h -> h.frameOptions(...disable))` vypíná X-Frame-Options pro celou aplikaci; žádné CSP/HSTS/nosniff. V repu ani `deploy/` není nginx konfigurace, která by je doplnila. Oprava: znovu zapnout frameOptions (sameOrigin), přidat CSP + nosniff, v prod HSTS — Spring `headers` nebo prokazatelně nginx; doplnit do `deploy/` + `nasazeni.md`.

### NÍZKÝ

**N6 — Account lockout per-účet bez per-IP throttlingu → DoS zamykáním** · `LoginAttemptService.java:25,40-47` · jistý
10 pokusů zamkne účet, odemyká jen admin. Útočník znající uživatelská jména cíleně zamyká legitimní účty. `getFailedLoginAttempts()+1 >= MAX` čte hodnotu před incrementem — při souběhu drobně nepřesné. Přijatelné pro výukovou app; ideál per-IP rate limit.

**N7 — Verbose security logování v base konfiguraci** · `application.yaml:87-91` · jistý
`org.springframework.security: DEBUG`, `...DaoAuthenticationProvider: TRACE`. Prod přebíjí na WARN (OK). Cesta loggeru `...web.authentication.dao.AbstractUserDetails.DaoAuthenticationProvider` **neexistuje** (reálně `org.springframework.security.authentication.dao.DaoAuthenticationProvider`) — TRACE fakticky nic nedělá. Opravit/smazat mrtvou cestu.

**N8 — `LoginRequest` bez validačních anotací** · jistý
`@Valid` na recordu bez `@NotBlank` nic nevynutí; `null` propadne do `authenticationManager` (skončí 401). Kosmetika.

## Ověřený stav známých položek

**TD-24 (rolová autorizace) — trvá, dle plánu odloženo.** `/api/**` plošně `authenticated()` (`SecurityConfig:78`). `@PreAuthorize` jen `UserController` (ADMIN), import/review příjemek (ADMIN|MANAGER|MECHANIC). Faktury (issue/pay/cancel/delete items), `CompanyProfileController.PUT`, mazání zákazníků/vozidel běží pod pouhým `authenticated()`, tj. i MECHANIC. Konzistentní se záměrem TD-24, ale N1/N2 ukazují díry i uvnitř ADMIN prostoru. Doporučeno řešit N2 spolu s TD-24.

**TD-33 (CORS + company_profile + seed hesla) — trvá.** CORS natvrdo (N4), seed hesla `Password1!` (V3, komentář „CHANGE BEFORE PRODUCTION"), placeholder firmy. Charakter produkčního checklistu potvrzen.

**TD-31 (cookies) — vyřešeno, potvrzeno.** `AuthController` čte `jwt.cookie-secure`/`expiration`/`refresh-expiration` z konfigurace; prod má `cookie-secure: true`, `expiration: 900000` (15 min). Cookies HttpOnly, SameSite=Strict, access na `/api`, refresh úzce na `/api/{version}/auth/refresh`, maxAge z expirace, logout maxAge=0. **Reziduum:** bez `SPRING_PROFILES_ACTIVE=prod` platí base `cookie-secure: false` — ověřit při nasazení. *(Pozn.: `api.md` tuto opravu ještě nepromítla — viz [08-dokumentace.md](08-dokumentace.md) V1.)*

## Pozitiva

- **JWT bez algorithm-confusion** — `Jwts.parser().verifyWith(HMAC).parseSignedClaims()` (jjwt 0.12+) vynucuje algoritmus; `alg=none` nemožné.
- **Blacklist access tokenů hashovaný SHA-256** (V4).
- **Rotace refresh tokenů + detekce reuse** — použití revokovaného tokenu revokuje všechny sessions.
- **Lockout přes `REQUIRES_NEW`** — counter přežije rollback login transakce.
- **User enumeration na loginu potlačeno** — jednotná hláška, dummy BCrypt proti timing útoku.
- **Error handling neúniká detaily** — catch-all 500 generický, stack trace jen do logu; `SecurityProblemWriter` sjednocuje 401 do RFC 9457.
- **XXE ošetřeno** v `IsdocParser` — disallow-doctype-decl, prázdné ACCESS_EXTERNAL_DTD/SCHEMA, XIncludeAware(false).
- **Upload validace:** limit 10 MB, kontrola content-type i přípony; bez path traversal (soubor v paměti jako byte[]); SSRF plocha nulová.
- **Prompt-injection plocha AI importu omezená** modelem „AI čte, kód počítá"; injektovaný text nemá privilegovaný nástroj.
- **Žádné reálné secrets v gitu.** `application-local.yaml` gitignorovaný, nikdy necommitnut (ověřeno `git log --all`). JWT secret v historii byl dev placeholder z tutoriálů; `ANTHROPIC_API_KEY` v historii jen placeholder. Deploy: `.env` mimo git, `chmod 600`, dedikovaný user, úzké NOPASSWD sudoers.
- **CSRF vypnutý je zde obhajitelný** — SameSite=Strict cookies, žádné cross-site POST; access cookie scoped na `/api`. Doporučení: při budoucích cross-origin klientech / SameSite=Lax CSRF vrátit.

## Poznámka k závislostem (bleeding-edge)

- **Spring Boot 4.0.3 + Security 7 + Spring AI 2.0.0-M4** (milestone, repo `repo.spring.io/milestone` aktivní) — velmi čerstvé/nefinální. Riziko: supply-chain a stabilita API (milestone se mění). Pro výukový projekt akceptovatelné; před ostrým provozem přepnout Spring AI na GA a zvážit odstranění milestone repozitáře.
- **jjwt 0.13.0** — bezpečné API (`verifyWith`/`parseSignedClaims`), žádné třídy zranitelností jako u 0.9.x. OK.

**Shrnutí priorit:** nejdřív N1 (invalidace sessions při změně/resetu hesla) a N3 (hash refresh tokenů) — malá změna, velký dopad, patří k sobě. N2 (last-admin guard) spolu s TD-24. N4/N5 součást produkčního checklistu (rozšíření TD-33 o security hlavičky).
