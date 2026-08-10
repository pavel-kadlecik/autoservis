# 06 — Bezpečnost a autorizace

> Audit 2026-07-30 · rozsah: autorizace endpointů, IDOR, JWT/cookies, CORS/hlavičky, CSRF, secrets,
> logování, upload, SQL injection, rate limiting, chybové odpovědi, enumerace uživatelů ·
> metoda: čtení celých souborů (ne grep-závěry), každý nález ověřen druhým čtením přímo v kódu,
> read-only (nic mimo tento soubor se needitovalo)

## Co bylo přečteno

**Konfigurace bezpečnosti**
- `src/main/java/cz/palo/autoservis/config/security/SecurityConfig.java`
- `src/main/java/cz/palo/autoservis/config/security/CorsProperties.java`

**Celý balíček `security/`**
- `security/controller/AuthController.java`
- `security/filter/JwtAuthenticationFilter.java`, `security/filter/SecurityProblemWriter.java`
- `security/service/`: `JwtService.java`, `AuthenticationService.java`, `LoginAttemptService.java`,
  `BlacklistCleanupService.java`, `TokenHasher.java`, `AppUserDetailsService.java`, `RoleService.java`
- `security/mapper/`: `UserMapper.java`, `BlacklistMapper.java`, `RefreshTokenMapper.java`, `RoleMapper.java`
- `security/model/domain/AppUserDetails.java`, `security/model/dto/ChangePasswordRequest.java`,
  `security/model/dto/LoginRequest.java`

**Všech 23 controllerů** (15 v `controller/`, 7 v `controller/warehouse/`, `security/controller/AuthController`)

**Služby a ostatní Java**
- `exception/GlobalExceptionHandler.java`
- `service/impl/UserServiceImpl.java`, `service/impl/OrderItemServiceImpl.java`,
  `service/impl/MileageServiceImpl.java`, `service/impl/ProductServiceImpl.java`,
  `service/impl/WarehouseImportServiceImpl.java`
- `service/IsdocParser.java`, `service/PdfDocumentExtractionService.java`
- relevantní části `service/impl/InvoiceServiceImpl.java`, `service/impl/StockTakeServiceImpl.java`
- `model/dto/employee/EmployeeDto.java`, `model/dto/dashboard/DashboardDto.java`, `model/dto/user/UserDto.java`

**Konfigurace a nasazení**
- `src/main/resources/application.yaml`, `application-prod.yaml`, `application-local.yaml.example`
- `pom.xml`, `.gitignore`, `deploy.sh`, `deploy/setup.sh`, `deploy/autoservis-backend.service`,
  `deploy/autoservis-sudoers`, `docs/nasazeni.md`
- `src/main/resources/db/prod/V60__prod_seed.sql`, `db/migration/V1__init_security_schema.sql` (tabulka `users`)
- `src/main/resources/mapper/UserMapper.xml` (celý), cílené části `OrderItemMapper.xml`,
  `warehouse/StockTakeMapper.xml`

**Testy**
- `src/test/java/cz/palo/autoservis/web/RoleAuthorizationTest.java`, `JwtAuthFlowTest.java`, `CorsConfigTest.java`
- `src/test/java/cz/palo/autoservis/security/SecurityServicesTest.java`

**Frontend (jen pro dopad)** — `frontend/autoservis-frontend/src/api/api.js`

**Povinná četba** — `CLAUDE.md`, `docs/konvence.md`, `docs/tech-dluhy.md`, `docs/api.md` (sekce autorizace),
`docs/nasazeni.md`

---

## Shrnutí

Základ bezpečnosti je nadprůměrný na projekt této velikosti: **SQL injection nehrozí** (v celém
`src/main/resources/mapper/` není jediný `${}`, řazení jede přes whitelist v `<choose>`),
**secrets nejsou v gitu** (ověřeno i přes `git ls-files` a `git log`), **logování je střídmé**
a hesla ani tokeny se nikam nezapisují, **XXE je v `IsdocParser` ošetřené správně**, tokeny jsou
v DB **jen jako SHA-256 hash**, rotace refresh tokenů včetně detekce reuse funguje a je otestovaná,
chybové odpovědi neúnikají detail implementace a **enumerace uživatelů při přihlášení není možná**.
Rolová matice `@PreAuthorize` odpovídá tomu, co dokumentace slibuje.

Nalezeno **10 nálezů: 0 kritických, 1 vysoký, 3 střední, 6 nízkých.**

Nejzávažnější je **trvalé uzamčení účtu bez samoobslužné obnovy** (S-1): kdokoli z internetu zamkne
10 požadavky jediný produkční admin účet a nikdo ho už neodemkne bez zásahu do databáze. Dva střední
nálezy míří na **odvolání přístupu** — deaktivovaný uživatel dostává 500 místo 401 (S-2) a admin reset
hesla neodvolá běžící sessions, na rozdíl od samoobslužné změny hesla (S-3). Třetí střední (S-4) je
rozpor mezi dokumentovanou architekturou (nginx na portu 80) a produkčním `cookie-secure: true`.

Nízké nálezy jsou skutečně nízké: chybějící kontrola příslušnosti u vnořených položek (nikde
nepřekračuje hranici oprávnění — všechny pracovní role vidí všechno), viditelnost ekonomických
údajů mechanikovi, fail-open baseline mimo `/api/**`, měkká validace uploadu, mezery v testech
autorizace a drift dokumentace.

TD-68 (hodnota skladu a ruční pohyby pro mechanika) je vědomý dluh — **není zde hlášen**.

---

## Nálezy

### [S-1] Trvalé uzamčení účtu bez samoobslužné obnovy — jediný produkční admin se dá vyřadit 10 požadavky

**Severita:** 🔴 VYSOKÝ
**Jistota:** OVĚŘENO
**Kde:**
- `src/main/java/cz/palo/autoservis/security/service/LoginAttemptService.java:25` — `MAX_FAILED_ATTEMPTS = 10`
- `src/main/java/cz/palo/autoservis/security/service/LoginAttemptService.java:43-45` — `if (u.getFailedLoginAttempts() + 1 >= MAX_FAILED_ATTEMPTS) { userMapper.lockAccount(u.getId()); }`
- `src/main/resources/mapper/UserMapper.xml:117-121` — `lockAccount`: `SET account_non_locked = FALSE` (bez časového razítka, bez expirace)
- `src/main/resources/mapper/UserMapper.xml:124-129` — `unlockAccount` (jediná cesta zpět)
- `src/main/java/cz/palo/autoservis/service/impl/UserServiceImpl.java:225` — `userMapper.unlockAccount(id)` — **jediný volající** `unlockAccount` v celém `src/main`
- `src/main/java/cz/palo/autoservis/controller/UserController.java:30` — `@PreAuthorize("hasRole('ADMIN')")` na celém controlleru, tedy i na `POST /users/{id}/reset-password` (ř. 125-130)
- `src/main/java/cz/palo/autoservis/config/security/SecurityConfig.java:83-86` — `/api/*/auth/login` je `permitAll`
- `src/main/resources/db/prod/V60__prod_seed.sql:38-39` — produkce seeduje **jednoho** uživatele: `('admin', 'admin@autoservis.cz', …)`

**Co je špatně:** Zámek účtu je trvalý. Neexistuje časové okno, po kterém by čítač neúspěchů vypršel,
neexistuje plánovaná úloha, která by zámky uvolňovala (jediný `@Scheduled` v projektu je
`BlacklistCleanupService:41`), a sloupec `security.users.account_non_locked` nemá v DB žádný trigger
(`V1__init_security_schema.sql:36` — prostý `BOOLEAN NOT NULL DEFAULT TRUE`). Odemknout lze **výhradně**
přes `POST /users/{id}/reset-password`, který je ADMIN-only. Přihlašovací endpoint je veřejný a bez
jakéhokoli omezení počtu pokusů na IP.

**Scénář selhání:**
1. Produkce běží podle `db/prod/V60` s jediným účtem `admin` (jméno je uhodnutelné a je v dokumentaci).
2. Kdokoli, kdo dosáhne na `POST /api/v1/auth/login`, pošle 10× `{"username":"admin","password":"x"}`.
   Každý neúspěch projde `recordFailure` v `REQUIRES_NEW` transakci, takže se počítadlo commitne
   i přes rollback loginu; desátý nastaví `account_non_locked = FALSE`.
3. Admin se od té chvíle nepřihlásí — dostane 401 `ACCOUNT_LOCKED`.
4. Odemknout ho může jen jiný ADMIN přes `/users/{id}/reset-password`. Žádný jiný ADMIN ale neexistuje.
   MANAGER na `/users` dostane 403 (`UserController:30`).
5. Jediná záchrana je ruční `UPDATE security.users SET account_non_locked = TRUE` přímo v databázi.
6. Rozšíření: útočník to samé udělá se všemi známými přihlašovacími jmény (v malém servisu jsou
   uhodnutelná) → aplikace je pro všechny mimo provoz, natrvalo.

**Proč to vadí:** Provoz. Neautentizovaný útočník (nebo jen otravný skript) trvale odřízne správu
aplikace a případně i všechny uživatele. Obnova vyžaduje přístup k databázi, což majitel servisu
sám neudělá. Zámek účtu má chránit před online hádáním hesla; v této podobě je ale sám o sobě
denial-of-service nástrojem — což je klasický anti-pattern, proti kterému NIST SP 800-63B doporučuje
buď časově omezený zámek, nebo throttling.

**Návrh řešení** (kombinace, nejmenší zásah první):
1. **Časově omezený zámek** — přidat sloupec `locked_until TIMESTAMPTZ` (nová migrace `V{n+1}`),
   `lockAccount` ho nastaví na `NOW() + 15 min`, `AppUserDetailsService`/`findByUsername` počítá
   `account_non_locked` jako `locked_until IS NULL OR locked_until < NOW()`. Zámek pak vyprší sám
   a útok jen zdrží legitimní přihlášení, ne ho zablokuje.
2. **Nepočítat neúspěchy donekonečna** — resetovat čítač, když je poslední neúspěch starší než okno
   (dnes se 10 překlepů rozložených přes rok sečte do zámku).
3. **Chránit i samotný endpoint** — rate limit na `/auth/login` per IP (nejlevněji `limit_req` v nginx;
   10 požadavků je pod jakýmkoli rozumným limitem, takže samotný nginx S-1 neřeší — je to doplněk, ne náhrada).
4. Ať už se zvolí cokoli, **v produkci mít druhý ADMIN účet** jako pojistku (viz otevřené otázky).

---

### [S-2] Deaktivovaný uživatel s živou cookie dostane 500 místo 401 — `UsernameNotFoundException` uniká z JWT filtru

**Severita:** 🟠 STŘEDNÍ
**Jistota:** OVĚŘENO
**Kde:**
- `src/main/java/cz/palo/autoservis/security/filter/JwtAuthenticationFilter.java:100-101` —
  `UserDetails userDetails = userDetailsService.loadUserByUsername(username);` je **mimo** `try/catch`,
  který na ř. 90-98 obaluje jen `jwtService.extractUsername(jwt)`
- `src/main/java/cz/palo/autoservis/security/service/AppUserDetailsService.java:37-39` —
  `orElseThrow(() -> new UsernameNotFoundException(...))`
- `src/main/resources/mapper/UserMapper.xml:66-67` — `findByUsername` má `AND u.enabled = TRUE`,
  takže deaktivovaný účet se **vůbec nenačte**
- `src/main/java/cz/palo/autoservis/security/service/AuthenticationService.java:141-143` — táž past
  na cestě `refresh` (`loadUserByUsername` bez ošetření)
- `src/main/java/cz/palo/autoservis/exception/GlobalExceptionHandler.java:468-476` — catch-all `Exception` → 500
  (pro `UsernameNotFoundException` neexistuje handler)
- `frontend/autoservis-frontend/src/api/api.js:64` — FE reaguje redirectem na `/login` **jen na 401**

**Co je špatně:** `DELETE /users/{id}` nastaví `enabled = FALSE`, ale nezneplatní vydané tokeny
(access token ani refresh token). Když deaktivovaný uživatel pošle další požadavek s ještě platnou
cookie `jwt`, filtr dojde na ř. 101, `loadUserByUsername` vyhodí `UsernameNotFoundException` a ta
**není nikde odchycena**: `JwtAuthenticationFilter` je registrovaný přes
`addFilterBefore(..., UsernamePasswordAuthenticationFilter.class)` (`SecurityConfig:123`), tedy
**před** `ExceptionTranslationFilter`, který ve filter chainu obaluje jen filtry za sebou. Výjimka
proto propadne až do kontejneru → 500. Na `/auth/refresh` je to totéž, jen přes catch-all
`GlobalExceptionHandler` (rovněž 500).

**Scénář selhání:**
1. Admin deaktivuje odcházejícího mechanika (`DELETE /api/v1/users/3`).
2. Mechanik má otevřenou aplikaci s platnou cookie `jwt` (v základní konfiguraci `jwt.expiration`
   8 hodin, `application.yaml:135`).
3. Každý jeho další požadavek → **500**, nikoli 401. FE 500 nepovažuje za konec session
   (`api.js:64` řeší jen 401), takže uživatel zůstane „přihlášený“ a jen mu všechno háže
   „Došlo k neočekávané chybě“; na login se nepřesměruje.
4. `POST /auth/refresh` také vrátí 500 (`AuthenticationService:141`) — FE si myslí, že refresh
   selhal jinak než na autentizaci.
5. Každý takový požadavek zapíše do logu `ERROR` se stack trace (`GlobalExceptionHandler:470`).
   Otevřená karta s pollingem tak zaplaví produkční log falešnými ERROR záznamy až do vypršení tokenu.

**Proč to vadí:** Provoz a diagnostika. Přístup je sice fakticky odříznut (žádný požadavek neprojde,
takže o bezpečnostní díru nejde), ale chová se to jako pád serveru: uživatel nedostane srozumitelné
odhlášení, obsluha vidí v logu ERROR místo očekávaného 401 a skutečné chyby se v tom šumu ztratí.
Zároveň je to porušení kontraktu z `docs/konvence.md §9` (401 = nepřihlášený uživatel).

**Návrh řešení:** V `JwtAuthenticationFilter` obalit i načtení uživatele:
`catch (UsernameNotFoundException e) → securityProblemWriter.writeUnauthorized(request, response, "ACCOUNT_DISABLED", "Účet byl deaktivován.")` a `return`.
V `GlobalExceptionHandler` doplnit `@ExceptionHandler(UsernameNotFoundException.class)` → 401
(pokryje cestu `refresh`). Přidat test: deaktivace uživatele → týž `jwt` cookie → 401 s kódem
(dnes to nepokrývá nic — `SecurityServicesTest:98-105` testuje jen service vrstvu, ne HTTP průchod).
Volitelně zároveň v `UserServiceImpl.deactivate` zavolat `refreshTokenMapper.revokeAllByUserId(id)`,
ať se deaktivace projeví i v `refresh_tokens` (souvisí s S-3).

---

### [S-3] Reset hesla adminem neodvolá běžící sessions — na rozdíl od samoobslužné změny hesla

**Severita:** 🟠 STŘEDNÍ
**Jistota:** OVĚŘENO
**Kde:**
- `src/main/java/cz/palo/autoservis/service/impl/UserServiceImpl.java:220-227` — celé tělo
  `resetPassword`: `updatePasswordHash(...)` + `unlockAccount(id)` a **nic víc**
- `src/main/java/cz/palo/autoservis/security/service/AuthenticationService.java:200-201` — sourozenecká
  cesta `changePassword` naopak volá `refreshTokenMapper.revokeAllByUserId(userId)` (oprava K-6)
- `src/main/java/cz/palo/autoservis/controller/UserController.java:125-130` — endpoint volá jen `userService.resetPassword`
- `src/main/resources/db/migration/V1__init_security_schema.sql:40` — `password_changed_at TIMESTAMPTZ NOT NULL DEFAULT NOW()`
  (sloupec existuje a `UserMapper.xml:233-237` ho při každé změne hesla aktualizuje)

**Co je špatně:** Dvě cesty ke změně hesla mají různé bezpečnostní chování. Samoobslužná
`POST /auth/change-password` odvolá všechny refresh tokeny uživatele — přesně proto, že změna hesla
je standardní způsob, jak useknout útočníka s živou session (javadoc `AuthenticationService:177-181`
to i explicitně říká). Administrátorský `POST /users/{id}/reset-password`, který se používá právě
v situaci, kdy se uživatel přihlásit nemůže (zapomněl heslo, účet je kompromitovaný, zaměstnanec
odešel), refresh tokeny **neodvolá**.

Druhá polovina téhož problému: ani jedna cesta nezneplatní **access token**. Blacklist se plní jen
při logoutu (`AuthenticationService:164`), a `JwtService.isTokenValid` (ř. 131-134) kontroluje pouze
shodu jména a expiraci — nikoli, jestli heslo mezitím nebylo změněno.

**Scénář selhání:**
1. Zaměstnanci ukradnou notebook s přihlášenou aplikací (nebo zaměstnanec odejde ve zlém a má
   session na svém telefonu).
2. Admin udělá to, co je intuitivní a co dokumentace popisuje jako obnovu — resetuje heslo
   (`POST /users/3/reset-password`).
3. Zloděj/bývalý zaměstnanec má stále platný **refresh token** (`jwt.refresh-expiration` = 7 dní,
   `application.yaml:136`). Zavolá `POST /auth/refresh`, dostane novou dvojici tokenů včetně nového
   sedmidenního refresh tokenu, a **pracuje dál** — reset hesla ho vůbec neomezil. Cyklením refreshe
   si přístup udrží libovolně dlouho.
4. Vedlejší, menší varianta: i po korektní samoobslužné změně hesla útočníkův **access token** platí
   do své přirozené expirace (v produkci 15 minut, v základní konfiguraci 8 hodin).

**Proč to vadí:** Bezpečnost. Odvolání přístupu, které nic neodvolá, je horší než žádné — admin má
falešný pocit, že problém vyřešil. Náprava kroku 3 je jediný řádek, který na sourozenecké cestě už
existuje.

**Návrh řešení:**
1. Do `UserServiceImpl.resetPassword` doplnit `refreshTokenMapper.revokeAllByUserId(id)` (metoda je
   `@Transactional`, takže to sedne do stejné transakce). Přidat test podle vzoru
   `RefreshTokenRotationTest.changePassword_revokesAllSessions` (ř. 299).
2. Totéž zvážit v `UserServiceImpl.deactivate` (viz S-2).
3. Pro access token: v `JwtAuthenticationFilter` porovnat `iat` tokenu s `users.password_changed_at`
   (sloupec už existuje a plní se) a token vydaný před poslední změnou hesla odmítnout jako
   `TOKEN_REVOKED`. Stojí to jeden údaj navíc z už načteného `User` — `AppUserDetails` ho jen musí
   začít nést. *(Bod 3 je návrhový; body 1–2 jsou jednoznačné.)*

---

### [S-4] Produkce podle dokumentace běží na HTTP (nginx :80), ale prod profil vynucuje `Secure` cookies

**Severita:** 🟠 STŘEDNÍ
**Jistota:** PRAVDĚPODOBNÝ *(nginx konfigurace není v repu — nelze z kódu potvrdit, jestli je před ním TLS)*
**Kde:**
- `docs/nasazeni.md:26-28` — schéma architektury: „nginx (port 80) ├─ / servíruje dist/ staticky └─ /api/ reverse proxy → localhost:8080“; jinde v dokumentu není zmínka o TLS, certifikátu ani portu 443
- `src/main/resources/application-prod.yaml:27` — `cookie-secure: true`
- `src/main/java/cz/palo/autoservis/security/controller/AuthController.java:180` a `:188` — `.secure(cookieSecure)` na obou cookies
- `src/main/java/cz/palo/autoservis/config/security/SecurityConfig.java:107-109` — HSTS `max-age=31536000; includeSubDomains`

**Co je špatně:** Jsou jen dvě možnosti a obě jsou problém, který se musí vyjasnit.

**Scénář selhání A (pokud je produkce opravdu jen HTTP):**
1. Uživatel otevře `http://dilna-server/` a přihlásí se.
2. Backend odpoví `Set-Cookie: jwt=…; Secure; HttpOnly; SameSite=Strict`.
3. Prohlížeč cookie s atributem `Secure` přijatou přes nešifrované spojení **zahodí** (platí ve všech
   moderních prohlížečích).
4. Následující `GET /api/v1/auth/me` jde bez cookie → 401 → FE přesměruje na `/login`.
   **Přihlásit se nelze vůbec** — nekonečná smyčka login → 401 → login. Aplikace je v produkci
   nepoužitelná a příčina není z chybové hlášky patrná.
5. HSTS hlavička se přes HTTP neuplatní, takže ani ta situaci nevyřeší.

**Scénář selhání B (pokud TLS existuje a dokumentace je zastaralá):** nikdo se z repozitáře nedozví,
jak je nasazení skutečně zabezpečené; `nasazeni.md` je pak návod, který při obnově serveru vyrobí
variantu A. Navíc: pokud je TLS jen na části cest nebo `proxy_pass` běží po HTTP mezi nginx a
backendem bez `X-Forwarded-Proto`, HSTS se nevygeneruje (`httpStrictTransportSecurity` se aplikuje
jen na požadavky, které Spring vidí jako `secure`, a `server.forward-headers-strategy` není
v konfiguraci nastaveno vůbec).

**Proč to vadí:** Buď zablokovaný provoz (A), nebo tokeny a hesla v otevřené síti a nefunkční HSTS
(B). V obou případech je to jednoduše ověřitelné jedním pohledem na server a je to blokující pro
ostrý provoz.

**Návrh řešení:**
1. Zjistit skutečný stav (`curl -I http://…/api/v1/auth/me` vs. `https://…`) a `docs/nasazeni.md`
   uvést do souladu.
2. Pokud TLS není: nasadit ho (Let's Encrypt / vlastní CA pro LAN) a v nginx přesměrovat `:80 → :443`.
   Vypnutí `cookie-secure` je špatná odpověď — znamenalo by přenášet JWT v čitelné podobě.
3. V nginx nastavit `proxy_set_header X-Forwarded-Proto $scheme;` a v `application-prod.yaml`
   `server.forward-headers-strategy: framework`, jinak backend TLS „nevidí“ a HSTS se neposílá.

---

### [S-5] Vnořené položky neověřují příslušnost k rodiči z cesty (položky zakázky a faktury)

**Severita:** 🟡 NÍZKÝ
**Jistota:** OVĚŘENO
**Kde:**
- `src/main/java/cz/palo/autoservis/controller/OrderItemController.java:39-42` (`GET /orders/{orderId}/items/{id}`),
  `:108-113` (`PUT`), `:136-142` (`DELETE`) — `orderId` je v cestě, ale do service se **nepředává**
- `src/main/java/cz/palo/autoservis/service/impl/OrderItemServiceImpl.java:72-79`, `:236-250`, `:260-286` —
  pracují jen s `id` položky
- `src/main/java/cz/palo/autoservis/controller/InvoiceController.java:221-227` a `:237-243` — javadoc
  dokonce říká „invoice ID (used for path scoping)“, ale `invoiceService.updateItem(itemId, …)` /
  `deleteItem(itemId)` `invoiceId` nedostanou
- `src/main/java/cz/palo/autoservis/service/impl/InvoiceServiceImpl.java:377-389`, `:403-413`

**Protipříklad ve stejném repozitáři** (vzor, který se má použít):
- `src/main/java/cz/palo/autoservis/service/impl/MileageServiceImpl.java:184-191` — `requireReadingOfVehicle`
- `src/main/java/cz/palo/autoservis/service/impl/ProductServiceImpl.java:232-238` — `BATCH_PRODUCT_MISMATCH`
- `src/main/resources/mapper/warehouse/StockTakeMapper.xml:166-172` — `WHERE id = #{itemId} AND stock_take_id = #{stockTakeId}`
- `src/main/resources/mapper/OrderItemMapper.xml:101-105` — `reorder` má `AND order_id = #{orderId}`

**Co je špatně:** Segment `{orderId}` / `{invoiceId}` je v URL čistě dekorativní. Server nikdy
neověří, že položka do daného dokladu patří.

**Scénář selhání:** Obsluha má otevřený detail zakázky ZAK-2026-0007 ve dvou kartách prohlížeče.
V jedné mezitím položku smaže a vytvoří jinou, ve druhé (staré) klikne na „Smazat“ u řádku, jehož
`id` už patří **jiné** zakázce. Požadavek jde na `DELETE /orders/7/items/42`, kde položka 42 patří
zakázce 12. Backend položku **z cizí zakázky smaže** (a u materiálové položky k tomu vygeneruje
vratkový skladový pohyb, `OrderItemServiceImpl:269-283`), uživateli vrátí 204 a on nic nepozná.
Se správnou kontrolou by dostal 404 a nic by se nestalo. U faktur je následek stejný, jen na
účetním dokladu (byť jen ve stavu DRAFT — `requireEditableForItem` použije invoiceId **položky**,
takže se aspoň nedá měnit vystavená faktura).

**Proč to vadí:** Data. Nejde o překročení oprávnění — všechny pracovní role mají ke všem zakázkám
i fakturám stejný přístup, takže se nikam nedostanou dál, než už jsou. Je to o tichém provedení
operace na jiném dokladu, než jaký klient adresoval, plus o tom, že REST kontrakt lže. Projekt
tenhle guard jinde standardně má (viz protipříklady), takže jde o nekonzistenci, ne o návrhové rozhodnutí.

**Návrh řešení:** Do `OrderItemService.getById/update/delete` a `InvoiceService.updateItem/deleteItem`
přidat `orderId`/`invoiceId` a hned na začátku ověřit příslušnost — buď v Javě jako
`MileageServiceImpl.requireReadingOfVehicle` (nesouhlas → `ResourceNotFoundException` = 404, ne
prozrazení, že položka existuje jinde), nebo přímo v `WHERE` mapperu jako u `StockTakeMapper.updateItem`.

---

### [S-6] Ekonomické údaje nad rámec dokumentované matice vidí i MECHANIC — mzdové sazby kolegů a měsíční tržby/marže

**Severita:** 🟡 NÍZKÝ
**Jistota:** OVĚŘENO
**Kde:**
- `src/main/java/cz/palo/autoservis/controller/EmployeeController.java:40-44` — `GET /employees`
  bez `@PreAuthorize` (baseline), zatímco mutace na ř. 65/86/101/113 jsou ADMIN/MANAGER
- `src/main/java/cz/palo/autoservis/model/dto/employee/EmployeeDto.java:153-162` — `ListResponse`
  obsahuje `private BigDecimal hourlyRate;`
- `src/main/java/cz/palo/autoservis/controller/DashboardController.java:43-47` — `GET /dashboard/statistics`
  bez `@PreAuthorize`
- `src/main/java/cz/palo/autoservis/model/dto/dashboard/DashboardDto.java:270-276` — `MonthlyStats`
  nese `revenue` a `margin` po měsících
- `docs/api.md:10` — souhrn vyhrazených operací ani jeden z těchto endpointů nezmiňuje

**Co je špatně:** Javadoc `EmployeeController:22-25` čtení seznamu mechanikovi zdůvodňuje potřebou
vybrat, kdo dělal položku typu LABOR — ale odpověď kromě jména nese i **hodinovou sazbu všech
zaměstnanců**. `GET /dashboard/statistics` vrací měsíční tržby a marži celé firmy komukoli
s pracovní rolí.

**Scénář selhání:** Mechanik si v prohlížeči otevře `…/api/v1/employees` (nebo v aplikaci obrazovku
Zaměstnanci, kde `EmployeeTable.jsx:27-29` sloupec „Hodinová sazba“ přímo vykresluje) a přečte si,
kolik berou všichni kolegové. Na `…/api/v1/dashboard/statistics` si zobrazí obrat a marži servisu
po měsících. Ani jedno k jeho práci nepotřebuje a matice v `docs/api.md:10` mu to nepřiznává.

**Proč to vadí:** Osobní údaje (mzdová data zaměstnanců) a citlivé firemní údaje jsou dostupné nad
rámec „need to know“ — v malém servisu je to spíš personální než technický problém, ale je to
konkrétní a snadno odstranitelné.

**Vztah k evidovaným dluhům:** TD-68 řeší **hodnotu skladu** a **ruční pohyby**, TD-22 řeší
`purchasePrice`/`internalNote`. Ani jeden z těchto dvou endpointů žádný z nich nepokrývá — proto to
hlásím, ne jako opakování odloženého.

**Komplikace, kterou je třeba znát před opravou:** `hourlyRate` ze seznamu používá frontend
k předvyplnění nákladové ceny LABOR položky (`frontend/…/src/components/OrderItemsWrapper.jsx:113`).
Prosté vyhození pole ze `ListResponse` tedy rozbije mechanikův pracovní tok — a mechanik nákladovou
cenu položky stejně uvidí (to je TD-22).

**Návrh řešení** — *rozhodnutí uživatele*, jestli má mechanik na tyto údaje vidět. Varianty:
- **(A) Nechat.** Interní aplikace pro zaměstnance, v třech lidech to stejně všichni vědí. Pak jen
  doplnit `docs/api.md:10` a `konvence.md §19`, ať dokumentace odpovídá skutečnosti.
- **(B) Omezit dashboard, zaměstnance nechat.** `@PreAuthorize("hasAnyRole('ADMIN','MANAGER')")` na
  `getStatistics` (případně jen na položky `revenue`/`margin`). Nejmenší zásah, FE se nerozbije.
- **(C) Omezit obojí.** K (B) přidat samostatný „picker“ endpoint/DTO pro mechanika (id + jméno,
  bez sazby) a plný `GET /employees` vyhradit vedení; předvyplnění ceny přesunout na server
  (`applyLaborEmployee` v `OrderItemServiceImpl:349-366` už fallback z `hourlyRate` umí, takže FE
  sazbu posílat vůbec nemusí).

Doporučení: **(B)** teď, **(C)** až se bude řešit TD-22.

---

### [S-7] Baseline mimo `/api/**` je fail-open (`anyRequest().permitAll()`)

**Severita:** 🟡 NÍZKÝ
**Jistota:** OVĚŘENO
**Kde:** `src/main/java/cz/palo/autoservis/config/security/SecurityConfig.java:94` — `.anyRequest().permitAll()`

**Co je špatně:** Poslední pravidlo řetězce je „všechno ostatní je veřejné“. Dnes to nic neodkrývá —
ověřeno: v `pom.xml` **není** `spring-boot-starter-actuator`, všech 23 controllerů mapuje výhradně
pod `/api/{version}/…`, a neznámá cesta končí 404 (`GlobalExceptionHandler:426-435`). Riziko je
v tom, co přijde příště.

**Scénář selhání:** Kdokoli později přidá `spring-boot-starter-actuator` (běžný krok při zavádění
monitoringu), webhook, Swagger UI nebo jakýkoli endpoint mimo `/api/**`. Ten je od první minuty
**veřejný bez přihlášení** a nic na to neupozorní — žádný test to nezachytí, code review si toho
nemusí všimnout, protože nová závislost nemění `SecurityConfig`. `/actuator/env` nebo `/actuator/heapdump`
by pak vydaly konfiguraci včetně hodnot z prostředí.

**Proč to vadí:** Bezpečnostní baseline má být „deny by default“; tady je „allow by default“. Chyba
se projeví až tím, že něco unikne.

**Návrh řešení:** Nahradit za `.anyRequest().denyAll()` a explicitně povolit to málo, co je veřejné
(dnes už povolené `/`, `/index.html`, `/assets/**`, `/favicon.ico`). **Pozor:** Spring Security 6+
filtruje ve výchozím nastavení i `ERROR` dispatch, takže s `denyAll()` je nutné buď povolit `/error`,
nebo `shouldFilterAllDispatcherTypes(false)` — jinak se rozbijí chybové odpovědi. Změnu doprovodit
testem, že `/error` a statické cesty dál fungují.

---

### [S-8] Validace nahraného dokladu jen podle deklarovaného typu **nebo** přípony, bez ověření obsahu

**Severita:** 🟡 NÍZKÝ
**Jistota:** OVĚŘENO
**Kde:**
- `src/main/java/cz/palo/autoservis/controller/warehouse/GoodsReceiptImportController.java:129-136` —
  `boolean acceptedType = …; boolean acceptedName = …; if (!acceptedType && !acceptedName) throw …`
  (tj. stačí splnit **jednu** z podmínek)
- `:106-115` — `validateIsdoc` kontroluje **jen** příponu (`.isdoc`/`.xml`/`.isdocx`), content-type vůbec
- `src/main/java/cz/palo/autoservis/service/PdfDocumentExtractionService.java:104-107` —
  `MimeTypeUtils.parseMimeType(mimeType)` bere klientem deklarovaný typ jako pravdu

**Co je špatně:** Ani jedna z obou cest se nedívá na skutečný obsah souboru (magic bytes `%PDF-`,
`\xFF\xD8` u JPEG, `<Invoice` u ISDOC). Podmínka `!acceptedType && !acceptedName` znamená, že
souboru se správnou příponou projde libovolný content-type a naopak.

**Scénář selhání:**
1. Obsluha omylem (nebo skript záměrně) nahraje na `POST /warehouse/receipts/import` 10MB souboru,
   který PDF ani obrázek není — stačí, aby se jmenoval `faktura.pdf`.
2. Kontrola projde, obsah se pošle **do placeného Anthropic API** (`PdfDocumentExtractionService:115-120`).
3. Model to odmítne → `RuntimeException` → `DocumentExtractionException` → 503 „Doklad se nepodařilo
   automaticky přečíst. Zkuste to prosím znovu.“ Uživatel to podle hlášky zkusí znovu, a znovu.
   Za každý pokus se platí a příčinu (špatný soubor) nikdo nepozná — správně měl dostat 415 hned,
   bez volání modelu.
4. Obdobně `.isdocx` (ZIP kontejner) projde kontrolou přípony a spadne až v parseru na
   `IllegalArgumentException` → 400 „Soubor není platný ISDOC“.

**Proč to vadí:** Peníze (volání AI za nesmysly) a diagnostika (503 „zkuste znovu“ místo 415 „tohle
není PDF“). **Za bezpečnostní díru to nepovažuji** a ověřil jsem proč: soubor se nikam neukládá na
disk (v `src/main/java` není jediný `new File`/`Files.`/`FileOutputStream` — obsah jde do sloupce
`bytea`), takže path traversal přes název nehrozí; zpět se servíruje s natvrdo nastaveným
`MediaType.APPLICATION_PDF` (`GoodsReceiptReviewController:67`) a globálním `X-Content-Type-Options: nosniff`
(`SecurityConfig:102`), takže nahrané HTML se nikdy nespustí jako stránka. Limit velikosti platí
(`application.yaml:37-39`, 10 MB) a žádná dekomprese se nedělá, takže zip bomba nemá kudy.

**Návrh řešení:** Před voláním modelu ověřit prvních pár bajtů (`%PDF-` / JPEG `FF D8 FF` / PNG
`89 50 4E 47` / RIFF+WEBP / HEIC `ftyp`) a při neshodě vrátit 415 se srozumitelnou hláškou.
Podmínku `acceptedType || acceptedName` změnit na `&&` nebo rovnou nahradit kontrolou obsahu.
U ISDOC ověřit, že soubor začíná XML deklarací nebo kořenovým `<Invoice`.

---

### [S-9] Testy rolové autorizace pokrývají 7 z 28 vyhrazených endpointů; odříznutí `ROLE_CUSTOMER` a ADMIN-only `/users` netestuje nic

**Severita:** 🟡 NÍZKÝ
**Jistota:** OVĚŘENO
**Kde:** `src/test/java/cz/palo/autoservis/web/RoleAuthorizationTest.java:66-149` — 7 testů „MECHANIC → 403“
(`invoices/{id}/issue`, `/pay`, `/cancel`, `credit-notes/{id}`, `customers/{id}` DELETE,
`vehicles/{id}` DELETE, `stock-takes/{id}/close`), 1 baseline test, 2 testy „vedení projde“

**Co je špatně:** Z 28 endpointů, které mají nad-baseline omezení (viz tabulka níže), je otestovaných 7.
Netestované jsou zejména:
- **celý `UserController`** (7 endpointů, `hasRole('ADMIN')`) — kdyby se anotace změnila na
  `hasAnyRole('ADMIN','MANAGER')` nebo úplně zmizela, **žádný test nespadne** a MANAGER by mohl zakládat
  účty, měnit role a resetovat hesla (včetně hesla admina);
- **`CashReceiptController`** (4 endpointy, celá třída ADMIN/MANAGER);
- **`EmployeeController`** mutace (4 endpointy);
- **`PUT /invoices/company-profile`** (komentář v testu na ř. 33-34 to přiznává: „Company-profile PUT
  nese stejnou anotaci a je pokrytý tímtéž mechanismem“ — mechanismus ano, konkrétní endpoint ne);
- **samotná baseline**: neexistuje test, že uživatel s rolí mimo pracovní trojici (`ROLE_CUSTOMER`,
  `ROLE_READONLY` — obě jsou v seedu, `db/prod/V60:26-30`) dostane na `/api/**` 403. Přitom právě to
  je smyslem opravy K-10. Kdyby někdo `SecurityConfig:93` změnil na `.authenticated()`, suita zůstane
  zelená.

**Scénář selhání:** Při budoucím refaktoringu (nebo při zavádění zákaznického portálu, kvůli kterému
role `ROLE_CUSTOMER` existuje) někdo změní `SecurityConfig:93` z `hasAnyRole(...)` na `authenticated()`,
aby portál „taky prošel“. Suita 780+ testů zůstane zelená, review to nemusí zachytit — a zákaznický
účet od té chvíle vidí a edituje celou firmu. Přesně ten scénář auditní oprava K-10 řešila.

**Proč to vadí:** Autorizační pravidlo bez testu je jen komentář. Zvlášť u pravidel, která vznikla
jako oprava už jednou nalezené díry.

**Návrh řešení:** Doplnit do `RoleAuthorizationTest`:
1. `MANAGER` → `GET /api/v1/users` → 403 (a `ADMIN` → 200/404, tedy že branou projde);
2. `MECHANIC` → `GET /api/v1/cash-receipts/{id}` → 403;
3. `MECHANIC` → `DELETE /api/v1/employees/{id}` → 403;
4. uživatel s rolí `ROLE_CUSTOMER` → `GET /api/v1/customers` → 403 (baseline test, který dnes chybí úplně).
Všechny čtyři jsou bez-tělové, takže platí zdůvodnění z javadocu testu (ř. 31-34) a nehrozí, že
400 předběhne 403.

---

### [S-10] Dokumentace autorizace a počty v `api.md` neodpovídají kódu

**Severita:** 🟡 NÍZKÝ
**Jistota:** OVĚŘENO
**Kde:**
- `docs/api.md:10` — souhrn vyhrazených operací: „faktura `issue`/`pay`/`cancel`, celý `credit-notes`,
  celý `cash-receipts`, (de)aktivace zákazníka a vozidla, `PUT company-profile`,
  `POST stock-takes/{id}/close`“ — **chybí `EmployeeController`** (4 endpointy s ADMIN/MANAGER,
  `EmployeeController:65,86,101,113`); v per-endpointové tabulce na `docs/api.md:139-142` uvedené jsou,
  takže si dokument protiřečí sám se sebou
- `docs/api.md:3` — „22 tříd, **107 endpointů**; stav 2026-07-25“ — ve skutečnosti **23 controllerů**
  (ověřeno `grep -rl "@RestController" src/main/java` = 25 souborů, z toho `GlobalExceptionHandler`
  a `SecurityProblemWriter` jsou falešné shody z anotace `@RestControllerAdvice` resp. javadocu)
  a **112 endpointů** (můj součet z tabulky níže; drobná odchylka podle toho, jak se počítá
  `GET /cash-receipts?invoiceId` jako samostatný mapping)
- `docs/konvence.md:344-351 (§19)` — uvádí jen dva příklady (`GoodsReceiptImportController`,
  `UserController`); úplná matice v normativním dokumentu není vůbec

**Co je špatně:** `docs/api.md:10` je jediné místo, kde je matice rolí shrnutá na jednom místě, a je
neúplné. `konvence.md §19` na ni ani neodkazuje.

**Scénář selhání:** Vývojář (nebo Claude podle `CLAUDE.md` mapy dokumentace) přidává nový endpoint
do `EmployeeController`, přečte si `konvence.md §19` a `api.md:10`, z obou vyčte, že zaměstnanci
mezi vyhrazené agendy nepatří, a anotaci nepřidá. Nový endpoint pro editaci zaměstnanců je tím
otevřený mechanikovi, aniž by si toho kdokoli všiml — dokumentace to takhle popisovala.

**Proč to vadí:** Provoz vývoje. Nesprávný souhrn autorizační matice je horší než žádný, protože se
podle něj rozhoduje.

**Návrh řešení:** Doplnit `/employees` mutace do výčtu na `docs/api.md:10`, aktualizovat počty na
`docs/api.md:3` a do `konvence.md §19` přidat odkaz „úplná matice viz `api.md`“ (nebo tam matici
přenést celou). Tabulka v tomto reportu je použitelná jako předloha.

---

## Úplná matice endpoint × role

Legenda: **baseline** = `hasAnyRole('ADMIN','MANAGER','MECHANIC')` z `SecurityConfig:93`
(`ROLE_CUSTOMER` a `ROLE_READONLY` sem nesmí). **auth** = jen přihlášení (`SecurityConfig:89`).
**veřejné** = `permitAll` (`SecurityConfig:82-87`).
Sloupec „`@PreAuthorize`“ uvádí, kde anotace sedí — na třídě, nebo na metodě.

### AuthController — `/api/{version}/auth` *(bez `@PreAuthorize`)*
| Metoda | Cesta | `@PreAuthorize` | Efektivní role |
|---|---|---|---|
| POST | `/login` | — | **veřejné** |
| POST | `/refresh` | — | **veřejné** |
| POST | `/logout` | — | **auth** |
| GET | `/me` | — | **auth** |
| POST | `/change-password` | — | **auth** |

### CashReceiptController — `/api/{version}/cash-receipts` *(třída: `hasAnyRole('ADMIN','MANAGER')`, ř. 29)*
| Metoda | Cesta | `@PreAuthorize` | Efektivní role |
|---|---|---|---|
| POST | `/` | třída | ADMIN, MANAGER |
| GET | `/{id}` | třída | ADMIN, MANAGER |
| GET | `/?invoiceId=` | třída | ADMIN, MANAGER |
| GET | `/{id}/pdf` | třída | ADMIN, MANAGER |

### CodeListController — `/api/{version}/code-lists`
| Metoda | Cesta | `@PreAuthorize` | Efektivní role |
|---|---|---|---|
| GET | `/roles` | — | baseline |

### CompanyProfileController — `/api/{version}/invoices/company-profile`
| Metoda | Cesta | `@PreAuthorize` | Efektivní role |
|---|---|---|---|
| GET | `/` | — | baseline |
| PUT | `/` | metoda (ř. 38) | ADMIN, MANAGER |

### CreditNoteController — `/api/{version}/credit-notes` *(třída: `hasAnyRole('ADMIN','MANAGER')`, ř. 30)*
| Metoda | Cesta | `@PreAuthorize` | Efektivní role |
|---|---|---|---|
| POST | `/` | třída | ADMIN, MANAGER |
| POST | `/{id}/issue` | třída | ADMIN, MANAGER |
| GET | `/{id}` | třída | ADMIN, MANAGER |
| GET | `/{id}/pdf` | třída | ADMIN, MANAGER |

### CustomerController — `/api/{version}/customers`
| Metoda | Cesta | `@PreAuthorize` | Efektivní role |
|---|---|---|---|
| GET | `/{id}` | — | baseline |
| GET | `/{id}/vehicles` | — | baseline |
| GET | `/` | — | baseline |
| GET | `/autocomplete` | — | baseline |
| POST | `/` | — | baseline |
| PUT | `/{id}` | — | baseline |
| DELETE | `/{id}` | metoda (ř. 125) | ADMIN, MANAGER |
| POST | `/{id}/activate` | metoda (ř. 137) | ADMIN, MANAGER |

### DashboardController — `/api/{version}/dashboard`
| Metoda | Cesta | `@PreAuthorize` | Efektivní role |
|---|---|---|---|
| GET | `/summary` | — | baseline |
| GET | `/statistics` | — | baseline ⚠️ **S-6** (tržby, marže) |

### EmployeeController — `/api/{version}/employees`
| Metoda | Cesta | `@PreAuthorize` | Efektivní role |
|---|---|---|---|
| GET | `/` | — | baseline ⚠️ **S-6** (`hourlyRate`) |
| GET | `/{id}` | — | baseline |
| POST | `/` | metoda (ř. 65) | ADMIN, MANAGER |
| PUT | `/{id}` | metoda (ř. 86) | ADMIN, MANAGER |
| DELETE | `/{id}` | metoda (ř. 101) | ADMIN, MANAGER |
| POST | `/{id}/activate` | metoda (ř. 113) | ADMIN, MANAGER |

### InvoiceController — `/api/{version}/invoices`
| Metoda | Cesta | `@PreAuthorize` | Efektivní role |
|---|---|---|---|
| POST | `/from-order` | — | baseline |
| GET | `/` | — | baseline |
| PUT | `/{id}` | — | baseline |
| POST | `/{id}/issue` | metoda (ř. 104) | ADMIN, MANAGER |
| POST | `/{id}/pay` | metoda (ř. 119) | ADMIN, MANAGER |
| POST | `/{id}/cancel` | metoda (ř. 134) | ADMIN, MANAGER |
| GET | `/{id}` | — | baseline |
| GET | `/number/{invoiceNumber}` | — | baseline |
| GET | `/order/{orderId}` | — | baseline |
| GET | `/customer/{customerId}` | — | baseline |
| POST | `/{invoiceId}/items` | — | baseline |
| PUT | `/{invoiceId}/items/{itemId}` | — | baseline ⚠️ **S-5** |
| DELETE | `/{invoiceId}/items/{itemId}` | — | baseline ⚠️ **S-5** |

### InvoiceDocumentController — `/api/{version}/invoices`
| Metoda | Cesta | `@PreAuthorize` | Efektivní role |
|---|---|---|---|
| GET | `/{id}/pdf` | — | baseline |

### MileageController — `/api/{version}/vehicles/{vehicleId}/mileage`
| Metoda | Cesta | `@PreAuthorize` | Efektivní role |
|---|---|---|---|
| GET | `/` | — | baseline |
| POST | `/` | — | baseline |
| PUT | `/{readingId}` | — | baseline *(příslušnost ověřena — vzor)* |
| DELETE | `/{readingId}` | — | baseline *(příslušnost ověřena — vzor)* |

### OrderController — `/api/{version}/orders`
| Metoda | Cesta | `@PreAuthorize` | Efektivní role |
|---|---|---|---|
| GET | `/{id}` | — | baseline |
| GET | `/` | — | baseline |
| POST | `/` | — | baseline |
| PUT | `/{id}` | — | baseline |

### OrderItemController — `/api/{version}/orders/{orderId}`
| Metoda | Cesta | `@PreAuthorize` | Efektivní role |
|---|---|---|---|
| GET | `/items/{id}` | — | baseline ⚠️ **S-5** |
| GET | `/items` | — | baseline |
| GET | `/items/summary` | — | baseline |
| POST | `/items` | — | baseline |
| POST | `/items/import-from-receipt` | — | baseline |
| PUT | `/items/{id}` | — | baseline ⚠️ **S-5** |
| PUT | `/items/reorder` | — | baseline *(scopováno v SQL)* |
| DELETE | `/items/{id}` | — | baseline ⚠️ **S-5** |

### UserController — `/api/{version}/users` *(třída: `hasRole('ADMIN')`, ř. 30)*
| Metoda | Cesta | `@PreAuthorize` | Efektivní role |
|---|---|---|---|
| GET | `/{id}` | třída | ADMIN |
| GET | `/` | třída | ADMIN |
| POST | `/` | třída | ADMIN |
| PUT | `/{id}` | třída | ADMIN |
| DELETE | `/{id}` | třída | ADMIN |
| POST | `/{id}/activate` | třída | ADMIN |
| POST | `/{id}/reset-password` | třída | ADMIN ⚠️ **S-1, S-3** |

### VehicleController — `/api/{version}/vehicles`
| Metoda | Cesta | `@PreAuthorize` | Efektivní role |
|---|---|---|---|
| GET | `/{id}` | — | baseline |
| GET | `/` | — | baseline |
| GET | `/autocomplete` | — | baseline |
| POST | `/` | — | baseline |
| PUT | `/{id}` | — | baseline |
| DELETE | `/{id}` | metoda (ř. 121) | ADMIN, MANAGER |
| POST | `/{id}/activate` | metoda (ř. 133) | ADMIN, MANAGER |

### VehicleRegistryController — `/api/{version}/vehicles`
| Metoda | Cesta | `@PreAuthorize` | Efektivní role |
|---|---|---|---|
| GET | `/registry-lookup` | — | baseline |
| POST | `/{vehicleId}/registry-refresh` | — | baseline |
| GET | `/{vehicleId}/registry-snapshots` | — | baseline |

### GoodsReceiptController — `/api/{version}/warehouse/goods-receipts`
| Metoda | Cesta | `@PreAuthorize` | Efektivní role |
|---|---|---|---|
| GET | `/autocomplete` | — | baseline |
| GET | `/{id}/items` | — | baseline |

### GoodsReceiptImportController — `/api/{version}/warehouse/receipts`
| Metoda | Cesta | `@PreAuthorize` | Efektivní role |
|---|---|---|---|
| POST | `/import` | metoda (ř. 45) | ADMIN, MANAGER, MECHANIC *(= baseline, jen explicitně)* |
| POST | `/import-isdoc` | metoda (ř. 81) | ADMIN, MANAGER, MECHANIC *(= baseline)* |

### GoodsReceiptReviewController — `/api/{version}/warehouse/receipts` *(třída: `hasAnyRole('ADMIN','MANAGER','MECHANIC')`, ř. 33 = baseline)*
| Metoda | Cesta | `@PreAuthorize` | Efektivní role |
|---|---|---|---|
| GET | `/` | třída | baseline |
| POST | `/` | třída | baseline |
| GET | `/{id}` | třída | baseline |
| GET | `/{id}/pdf` | třída | baseline |
| PUT | `/{id}/draft` | třída | baseline |
| POST | `/{id}/confirm` | třída | baseline |
| POST | `/{id}/reject` | třída | baseline |
| POST | `/{id}/cancel` | třída | baseline |

### ProductController — `/api/{version}/warehouse/products`
| Metoda | Cesta | `@PreAuthorize` | Efektivní role |
|---|---|---|---|
| GET | `/` | — | baseline |
| GET | `/{id}` | — | baseline |
| GET | `/low-stock` | — | baseline |
| GET | `/import/{id}` | — | baseline *(bez FE volajícího — TD-64)* |
| POST | `/` | — | baseline |
| PUT | `/{id}` | — | baseline |
| DELETE | `/{id}` | — | baseline |
| POST | `/{id}/activate` | — | baseline |
| POST | `/{id}/movements` | — | baseline *(TD-68 — vědomý dluh, nehlásím)* |

### StockTakeController — `/api/{version}/warehouse/stock-takes`
| Metoda | Cesta | `@PreAuthorize` | Efektivní role |
|---|---|---|---|
| GET | `/` | — | baseline |
| GET | `/{id}` | — | baseline |
| POST | `/` | — | baseline |
| PUT | `/{id}/items` | — | baseline *(scopováno v SQL)* |
| POST | `/{id}/close` | metoda (ř. 67) | ADMIN, MANAGER |
| POST | `/{id}/cancel` | — | baseline |

### StockValuationController — `/api/{version}/warehouse/stock-valuation`
| Metoda | Cesta | `@PreAuthorize` | Efektivní role |
|---|---|---|---|
| GET | `/` | — | baseline *(TD-68 — vědomý dluh, nehlásím)* |

### SupplierController — `/api/{version}/warehouse/suppliers`
| Metoda | Cesta | `@PreAuthorize` | Efektivní role |
|---|---|---|---|
| GET | `/` | — | baseline |
| GET | `/{id}` | — | baseline |
| PUT | `/{id}` | — | baseline |
| DELETE | `/{id}` | — | baseline |
| POST | `/{id}/activate` | — | baseline |

**Souhrn:** 23 controllerů, 112 endpointů. Z toho **veřejné 2**, **jen přihlášení 3**, **baseline 79**,
**ADMIN/MANAGER 21**, **ADMIN-only 7** (nad-baseline omezení má tedy 28 endpointů).
Matice `@PreAuthorize` odpovídá tomu, co popisuje
`docs/api.md:10` — až na `EmployeeController` (viz S-10).

---

## Co bylo ověřeno jako v pořádku

**SQL injection**
- V celém `src/main/resources/mapper/` **není jediný výskyt `${}`** (ověřeno `grep -rn '\${' src/main/resources/mapper/` → 0 shod). Všechny parametry jdou přes `#{}`, tedy JDBC bind.
- Dynamické `ORDER BY` je řešené whitelistem v `<choose>/<when>` (`CustomerMapper.xml`, `UserMapper.xml:197-212`, `InvoiceMapper`, `OrderMapper`, `VehicleMapper`, `WarehouseMapper`, `SupplierMapper`, `ReceiptReviewMapper`, `StockTakeMapper`); ostatní `ORDER BY` jsou statické. `sortBy` z klienta se do SQL nikdy nedostane jako text.
- `<bind name="searchLike" value="'%' + params.search + '%'" />` (`UserMapper.xml:147`) skládá jen **hodnotu**, která se pak předává přes `#{searchLike}` — není to injection.

**Secrets**
- `git ls-files` neobsahuje `application-local.yaml` ani `.env`; `git log --all -- src/main/resources/application-local.yaml` je prázdný (soubor nikdy commitnutý nebyl). `.gitignore:7-8` obojí kryje.
- V trackovaných souborech nejsou API klíče ani hesla (kontrola na `sk-ant-*`, PEM hlavičky, literálová hesla) — jediné shody jsou testovací konstanty `Password1!` v testech, což je dokumentovaný dev seed (`konvence.md §18`).
- `pom.xml:27` bere DB heslo z `${env.DB_PASSWORD}`, `application.yaml` má jen placeholdery
  (`${DB_PASSWORD}`, `${JWT_SECRET}`, `${ANTHROPIC_API_KEY}`, `${DATAOVOZIDLECH_API_KEY:}`),
  `application-prod.yaml:15` bere hash admina z `${ADMIN_PASSWORD_HASH}`.
- `deploy/autoservis-backend.service` čte secrets z `EnvironmentFile=/opt/autoservis/.env`; `deploy/autoservis-sudoers` je úzké `NOPASSWD` jen na čtyři `systemctl` akce nad jednou službou — správně.
- `docs/archiv/API_KEY.MD` je trackovaný, ale obsahuje jen návod s placeholdery (`sk-ant-tvuj-klic`), žádný skutečný klíč.

**Logování**
- V `src/main/java` je celkem 13 logovacích volání. Žádné nelogují heslo, token, cookie ani celé request body.
- `GlobalExceptionHandler:373` loguje jen `getMostSpecificCause().getMessage()` (ne celé SQL s parametry), `:470` loguje metodu + URI + stack trace do logu, klientovi jde generická hláška.
- V produkci se `cz.palo.autoservis` sráží na INFO, `org.mybatis` na WARN a `org.springframework.security` na WARN (`application-prod.yaml:17-23`), takže se do produkčního logu nedostane SQL s parametry.

**JWT a tokeny**
- HS256, klíč z Base64 (`JwtService:153-156`) — pokud je klíč generovaný podle návodu v
  `application-local.yaml.example:8` (`openssl rand -base64 48` = 384 bitů), `Keys.hmacShaKeyFor`
  navíc krátký klíč sám odmítne (jjwt vyhodí `WeakKeyException` pod 256 bitů), takže se slabý klíč
  neprotlačí tiše.
- Refresh token není JWT, ale náhodné UUID; v DB je uložený **jen jako SHA-256** (`AuthenticationService:224-226`), stejně jako blacklistované access tokeny (`:164`, `JwtAuthenticationFilter:84`).
- Rotace refresh tokenu při každém použití + detekce reuse (`AuthenticationService:127-132`) — reuse odvolá všechny sessions uživatele. Pokryto `RefreshTokenRotationTest` (rotace, reuse, expirace, idempotentní logout, revokace při změně hesla).
- Blacklist je idempotentní (`ON CONFLICT DO NOTHING`, TD-53) a čistí se hodinovou úlohou (`BlacklistCleanupService:41-48`, test `SecurityServicesTest:129-150`).
- E2e průchod přes skutečné cookies je otestovaný (`JwtAuthFlowTest`) včetně 401 po logoutu a podvrženého tokenu.

**Cookies**
- Obě cookies mají `HttpOnly`, `SameSite=Strict` a `secure` z konfigurace (`AuthController:178-192`); `maxAge` je odvozený z `jwt.expiration`/`jwt.refresh-expiration`, ne natvrdo (TD-31).
- Access cookie je omezená na `/api`, refresh cookie **jen na `/api/{version}/auth/refresh`** — refresh token se tedy neposílá s běžnými požadavky. To je nadstandard.
- Cesta refresh cookie se skládá z `{version}` z URL, ale `ResponseCookie` validuje path (odmítne `;` a řídicí znaky), takže CRLF/cookie injection nehrozí — nejhorší následek je 400.

**CSRF** *(vypnuté — `SecurityConfig:74`)*
- Vypnuté CSRF při cookie-based autentizaci je obecně varovný signál, tady je ale krytý: **obě
  cookies mají `SameSite=Strict`**, takže je prohlížeč neodešle s žádným cross-site požadavkem
  (ani s top-level navigací), a CORS má konkrétní seznam originů s `allowCredentials`, tedy
  cross-origin XHR s cookies neprojde. Klasické CSRF přes `<form>` z cizí domény tím padá.
- **Zbytková rizika, která `SameSite` nekryje** (proto to zmiňuji, ne že bych to hlásil jako nález):
  (a) `SameSite` je na úrovni *site*, ne *origin* — kdyby aplikace jednou žila na `servis.firma.cz`
  a útočník ovládl `cokoli-jineho.firma.cz`, cookie by se odeslala; (b) cookies nemají prefix
  `__Host-`, takže je sourozenecká subdoména může i přepsat. Dokud FE i API běží na jednom originu
  bez sourozeneckých subdomén (dnešní nginx setup), je to teoretické.

**CORS**
- `allowCredentials(true)` je **bez** wildcardu — originy jsou explicitní seznam (`SecurityConfig:162` ← `CorsProperties`). Spring by kombinaci `*` + credentials stejně odmítl, ale tady k ní ani nemůže dojít.
- Produkce má `cors.allowed-origins: ${CORS_ALLOWED_ORIGINS:}` (prázdný default = žádný cross-origin; same-origin požadavky CORS neprochází, takže to nic nerozbije).
- Vazba přes `@ConfigurationProperties`, ne `@Value` (regrese E7), hlídaná dvěma testy (`CorsPropertiesBindingTest`, `CorsConfigTest` — včetně odmítnutí neznámého originu).

**Bezpečnostní hlavičky** (`SecurityConfig:98-122`) — `X-Frame-Options: DENY`, `X-Content-Type-Options: nosniff`, `Referrer-Policy: same-origin`, HSTS `max-age=31536000; includeSubDomains`, CSP s `default-src 'self'`, `frame-ancestors 'none'`, `base-uri 'self'`, `form-action 'self'`. `'unsafe-inline'` u skriptů/stylů je zdokumentovaný ústupek Bootstrapu/Vite. Pozn.: hlavičky platí jen pro obsah servírovaný backendem — statický FE servíruje nginx, kde je nutné je nastavit zvlášť.

**Chybové odpovědi** — `GlobalExceptionHandler:468-476` vrací u catch-all jen generickou hlášku, detail jde do logu; `handleDataIntegrity:370-379` neposílá text DB chyby klientovi; `SecurityProblemWriter` staví identický RFC 9457 tvar i pro 401 z filtru. Ověřeno, že žádný handler neposílá `ex.getClass()`, stack trace ani SQL.

**Enumerace uživatelů** — `handleBadCredentials:201-209` vrací stejnou hlášku pro neznámé jméno i špatné heslo; `AppUserDetailsService` u neznámého uživatele vyhodí `UsernameNotFoundException`, kterou `DaoAuthenticationProvider` ve výchozím nastavení skryje za `BadCredentialsException` (a dělá dummy hash kvůli časovému rozdílu). Deaktivovaný účet se chová stejně jako neexistující (`UserMapper.xml:66-67`). Samoobslužný „zapomenuté heslo“ endpoint neexistuje, takže tam enumerační vektor není. `ACCOUNT_LOCKED` existenci účtu prozradí, ale až po 10 pokusech — vědomý a zdokumentovaný kompromis (`GlobalExceptionHandler:216-219`).

**XXE / XML** — `IsdocParser:92-99`: `disallow-doctype-decl=true`, `ACCESS_EXTERNAL_DTD=""`, `ACCESS_EXTERNAL_SCHEMA=""`, `setXIncludeAware(false)`, `setExpandEntityReferences(false)`. Zakázaný DOCTYPE zároveň vylučuje „billion laughs“. Správně.

**Upload — co je v pořádku** — limit 10 MB (`application.yaml:37-39`); soubor se nikdy neukládá na disk (v `src/main/java` není `new File`/`Files.`/`FileOutputStream`/`createTempFile`), takže path traversal přes název souboru nemá kam; žádná dekomprese ⇒ žádná zip bomba; při stahování zpět se vynucuje `application/pdf` + globální `nosniff`, takže nahrané HTML se nespustí.

**Ostatní ověřené guardy** — poslední admin nejde deaktivovat ani mu nejde odebrat roli (`UserServiceImpl:136-157`, `:182-189`); vlastní účet nejde deaktivovat (`:173-177`); hesla min. 8 znaků ve všech třech DTO; BCrypt (`SecurityConfig:147-149`); session `STATELESS`; `@EnableMethodSecurity` skutečně zapnuté (dokazuje `RoleAuthorizationTest`); v `pom.xml` **není actuator** (žádné `/actuator/**` k odhalení); `shouldNotFilter` používá přesný regex `^/api/[^/]+/auth/(login|refresh)$` (V6), ne `contains`.

---

## Otevřené otázky pro uživatele

1. **Běží produkce na HTTPS?** (S-4) Nejdůležitější otázka celého průchodu. `docs/nasazeni.md`
   popisuje nginx na portu 80 a `application-prod.yaml` posílá `Secure` cookies — pokud TLS není,
   nejde se přihlásit. Ověří se jedním `curl -I`.

2. **Kolik admin účtů má produkce mít?** (S-1) Jediný `admin` z `db/prod/V60` je jediná cesta
   k odemčení účtů a ke správě uživatelů. Doporučení: založit hned po nasazení **druhý** ADMIN účet
   s jiným jménem (ne `admin`) jako pojistku — nezávisle na tom, jestli se opraví časově omezený zámek.

3. **Má mechanik vidět hodinové sazby kolegů a měsíční tržby/marži servisu?** (S-6) Věc rozhodnutí
   majitele. Varianty A/B/C jsou v nálezu; doporučuji minimálně omezit `GET /dashboard/statistics`
   na ADMIN/MANAGER.

4. **Jak dlouhý má být zámek účtu?** (S-1) Návrh je 15 minut, ale je to provozní volba — kratší
   zámek méně obtěžuje obsluhu, delší lépe brzdí hádání hesla. Alternativa je exponenciální
   prodlužování (1 min, 2, 4, 8…).

5. **Má se `/auth/login` chránit rate limitem v nginx?** (S-1, doplněk) Vyžaduje zásah do nginx
   konfigurace, která není v repozitáři — takže je to rozhodnutí a úkol mimo tento kód.

6. **Má admin reset hesla znamenat i odhlášení uživatele ze všech zařízení?** (S-3) Technicky
   doporučuji ano (je to jednořádková změna a sourozenecká cesta to už dělá), ale znamená to, že po
   resetu hesla se uživatel musí všude přihlásit znovu — dobré ověřit, že to majiteli nevadí.
