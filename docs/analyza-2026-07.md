# Analýza stavu projektu Autoservis — komplexní revize

*Datum: 2026-07-20 · Rozsah: backend (Java/Spring), databáze, bezpečnost, frontend (React), testy, konfigurace, dokumentace. Srovnáváno s praxí zavedených systémů (ERP/účetní software, bezpečnostní standardy OWASP, REST/RFC konvence).*

---

## 1. Celkové hodnocení

Projekt je na svou velikost **výrazně nadprůměrně disciplinovaný**. Věci, které bývají slabinou i komerčních systémů, jsou tu vyřešené správně a s rozmyslem:

| Oblast | Řešení v projektu | Srovnání s osvědčenou praxí |
|---|---|---|
| Skladová evidence | Append-only pohybový ledger + DB trigger + CHECK constrainty; stav se odvozuje, nikdy ručně nepřepisuje | Přesně takto to dělají zralé ERP (SAP MM, Odoo stock ledger). Ledger = auditovatelnost, CHECK = poslední pojistka proti záporné zásobě |
| Fakturace | Snapshoty stran, zákazníka, SPZ; číslování triggerem s advisory lockem; stavový automat v enum | Snapshot faktury jako právního dokladu je zákonný požadavek — mnoho hobby projektů to nemá, tady ano |
| Chybový model | RFC 9457 ProblemDetail + strojové kódy v `errors[]`, centrální handler | Moderní standard (Spring 6+); strojové kódy jako překladové klíče = správná i18n architektura |
| AI import | „AI čte, kód počítá" — extrakce s provenancí polí (VERBATIM/DERIVED/ABSENT), deterministické křížové kontroly v Javě, draft → lidská kontrola → materializace | Lepší návrh než řada komerčních AI-import řešení, která AI výstup rovnou zapisují. Human-in-the-loop + přiznaný původ hodnot je přesně to, co se v produkčních AI pipeline osvědčilo |
| SQL vrstva | 100 % parametrizované dotazy v XML, žádná `${}` interpolace nalezena → **žádný vektor SQL injection** | Splňuje OWASP; plně kvalifikované tabulky snižují riziko search_path útoků |
| Refresh tokeny | Rotace + detekce reuse s revokací všech sessions | Doporučení OAuth2 BCP (RFC 9700) — málokdo to implementuje; tady ano (ale viz nález K3 — frontend to nevyužívá!) |
| Dokumentace | Živý tech-debt registr ověřovaný proti kódu, funkční dokumenty, průvodci, hooks vynucující pravidla | Nadstandard i proti firemním projektům |

Slabiny se koncentrují do tří míst: **(a) autentizační obvod má díry na okrajích** (registrace, cookies, mrtvý refresh), **(b) souběh (concurrency) je řešen nekonzistentně** — nový kód skladu správně, starší fakturace ne, **(c) doménová úplnost fakturace a zakázek** (storno vydané faktury, editace po fakturaci).

---

## 2. Kritické nálezy — řešit před produkcí

### K1 · Veřejná registrace dává plný přístup do systému
**Kde:** [SecurityConfig.java:76](src/main/java/cz/palo/autoservis/config/security/SecurityConfig.java) (`/auth/register` permitAll) + [AuthenticationService.java:70](src/main/java/cz/palo/autoservis/security/service/AuthenticationService.java).
**Problém:** Kdokoli na internetu si může založit účet. Registrovaný uživatel nedostane žádnou roli, ale to nevadí — téměř všechny endpointy vyžadují jen `authenticated()` (rolová autorizace je záměrně výjimečná, konvence §19). Výsledek: **anonymní útočník se sám pustí ke všem zákazníkům, vozidlům, fakturám**.
**Proč je to proti praxi:** Interní podnikové aplikace (a přesně tím Autoservis je — „určeno pro zaměstnance servisu") mají uzavřený onboarding: účty zakládá admin. Projekt už admin CRUD uživatelů má (`UserController`, `hasRole('ADMIN')`) — veřejný register je pozůstatek z doby před ním.
**Návrh:** endpoint odstranit (R-12: dead code smazat), nebo přinejmenším vyřadit z `permitAll` a nechat jen za ADMIN. Zakládání účtů nechat výhradně na `UserController`.

### K2 · `/auth/register` vrací tokeny v těle odpovědi
**Kde:** [AuthController.java:51](src/main/java/cz/palo/autoservis/security/controller/AuthController.java:51) — `ResponseEntity<TokenResponse>`.
**Problém:** Javadoc téže třídy deklaruje „tokens are transmitted exclusively via HTTP-only cookies, never in response bodies" — a hned první endpoint to porušuje. Token v těle je čitelný z JS → přesně ten XSS vektor, kvůli kterému projekt zvolil HTTP-only cookies.
**Návrh:** Splyne s K1 — pokud register zůstane, sjednotit s `/login` (cookies, prázdné tělo).

### K3 · Refresh token flow je na frontendu mrtvý — celá rotační mašinerie se nepoužívá
**Kde:** [api.js:34](frontend/autoservis-frontend/src/api/api.js:34) — na 401 rovnou `window.location.href = '/login'`; [auth.js](frontend/autoservis-frontend/src/api/auth.js) volá jen `/me` a `/logout`. Nikde ve frontendu se nevolá `/auth/refresh`.
**Problém:** Backend má pečlivě vybudovanou rotaci refresh tokenů s detekcí reuse — ale klient ji nikdy nezavolá. V devu to maskuje 8h access token; **v produkci (15 min) by uživatele vyhazovalo na login každých 15 minut** a 7denní refresh tokeny by jen hnily v DB.
**Proč je to proti praxi:** Standardní SPA vzor je interceptor: 401 → jednou zavolat refresh (single-flight, aby N souběžných requestů nevyvolalo N refreshů — s rotací by druhý refresh vypadal jako reuse útok a revokoval všechny sessions!) → zopakovat původní request → teprve při neúspěchu redirect na login.
**Návrh:** doplnit do `apiFetch` refresh-and-retry se single-flight zámkem (sdílená Promise). Pozor na souhru s rotací — bez single-flight si aplikace sama způsobí „reuse detekci".

### K4 · Cookie parametry v rozporu s konfigurací tokenů (= známý TD-31, potvrzuji a zpřesňuji)
**Kde:** [AuthController.java:172–217](src/main/java/cz/palo/autoservis/security/controller/AuthController.java:172).
**Potvrzený stav:** access cookie maxAge 60 min vs. token 8 h (dev) / 15 min (prod); refresh cookie 30 dní vs. token 7 dní; `secure(false)` natvrdo; path refresh cookie natvrdo `/api/v1/...` v controlleru mapovaném na `/api/{version}/...`.
**Dopad dnes:** skutečná délka sezení v devu je 60 minut (cookie umře dřív než token) — a protože refresh flow nefunguje (K3), je to *reálná* UX vada, ne teoretická. `secure=false` v produkci za HTTPS znamená, že token odejde i po HTTP.
**Návrh:** maxAge odvodit z `jwt.expiration` / `jwt.refresh-expiration` (injektovat do controlleru), `secure` per profil (`@Value` z konfigurace), path sestavit z verze requestu. Malý, ohraničený zásah.

### K5 · Stavový automat faktury má TOCTOU race — na rozdíl od příjemek
**Kde:** [InvoiceServiceImpl.java:324](src/main/java/cz/palo/autoservis/service/impl/InvoiceServiceImpl.java:324) + [InvoiceMapper.xml:169](src/main/resources/mapper/InvoiceMapper.xml:169).
**Problém:** `transitionTo` načte fakturu, ověří `canTransitionTo` v Javě, pak provede `UPDATE ... SET status = ... WHERE id = ...` **bez podmínky na aktuální stav**. Dva souběžné požadavky (issue vs. cancel; dvojklik na „Uhrazeno") oba projdou kontrolou a druhý tiše přepíše prvního — u právního dokladu.
**Proč je to důležité:** Novější kód v [ReceiptReviewServiceImpl.java:320](src/main/java/cz/palo/autoservis/service/impl/ReceiptReviewServiceImpl.java:320) to řeší učebnicově: guarded UPDATE (`WHERE status = 'PENDING_REVIEW'`), 0 řádků → 409 `RECEIPT_ALREADY_PROCESSED`. Fakturace, která vznikla dřív, tenhle vzor nemá — nekonzistence uvnitř jednoho projektu.
**Návrh:** `updateStatus` doplnit o `AND status = #{expectedStatus}`; 0 affected rows → `ConflictException` (409), stejně jako u příjemek. Pár řádků, vysoká hodnota.

### K6 · Výdej ze skladu: check-then-act na `quantity_remaining`
**Kde:** [OrderItemServiceImpl.java:102–115](src/main/java/cz/palo/autoservis/service/impl/OrderItemServiceImpl.java:102).
**Problém:** Validace „požadavek ≤ zbývá" proběhne v Javě nad načtenými šaržemi, teprve pak se vloží pohyb. Dvě díry: (a) souběžné výdeje z téže šarže oba projdou kontrolou; (b) **duplicitní `goodsReceiptItemId` v jednom requestu** se validují každý zvlášť proti témuž `quantityRemaining` — 2×3 ks ze šarže se 4 ks projde.
**Co zachraňuje data:** DB CHECK `chk_items_remaining >= 0` (V18) — konzistence se nerozbije. Ale uživatel dostane generické 422 `DATA_INTEGRITY_VIOLATION` místo srozumitelného `QUANTITY_EXCEEDS_REMAINING`, a to jen díky poslední pojistce.
**Proč je to proti praxi:** Skladové systémy výdej serializují — buď `SELECT ... FOR UPDATE` nad šarží, nebo atomický `UPDATE ... WHERE quantity_remaining >= x`. Ledger + trigger to tu dělá napůl (trigger je atomický, kontrola ne).
**Návrh:** (1) v service nejdřív sečíst požadavky per šarže (opraví duplicitní ID levně a hned), (2) načítat šarže `FOR UPDATE` (nový select v XML), aby i souběh končil čistou business chybou.

### K7 · Flyway konfigurace se ve skutečnosti nepoužívá (= TD-27, ale zvyšuji závažnost)
**Kde:** [application.yaml:68](src/main/resources/application.yaml:68) — blok `flyway:` je odsazen pod `mybatis:`, tj. čte se jako `mybatis.flyway.*`, které nic neznamená.
**Problém:** `clean-disabled: true`, `validate-on-migrate: true`, `locations` — **všechno je ignorováno**; migrace fungují jen proto, že výchozí hodnoty Spring Boot autokonfigurace náhodou odpovídají. To je tichá past: kdokoli v budoucnu přidá `spring.flyway.*` nastavení do tohoto bloku (např. placeholders), nic se nestane a nikdo se to nedozví.
**Návrh:** přesunout blok pod `spring:` (jednořádková změna odsazení) a ověřit start aplikace. Priorita vyšší než 🟢 — konfigurace, která vypadá závazně a není, je horší než žádná.

---

## 3. Významné nálezy — doménová správnost a bezpečnostní hygiena

### V1 · Storno vydané faktury nestačí — chybí opravný daňový doklad
**Kde:** stavový automat `InvoiceStatus` (ISSUED → CANCELLED).
**Problém:** V českém účetnictví je vydaná faktura právní doklad; její „zrušení" po vystavení se řeší **opravným daňovým dokladem (dobropisem)**, ne změnou stavu. Zavedené systémy (Pohoda, ABRA, Fakturoid, iDoklad) storno vydané faktury vždy materializují jako nový doklad s vlastním číslem.
**Návrh:** pro výukovou fázi je stav CANCELLED obhajitelný (a nechat DRAFT → CANCELLED bez dokladu), ale před reálným provozem: CANCELLED u ISSUED/PAID faktury nahradit vystavením dobropisu (nová entita, vlastní číselná řada). Patří do roadmapy jako samostatná fáze fakturace.

### V2 · Zakázku lze měnit i po vystavení faktury
**Kde:** `OrderItemServiceImpl.create/update/delete` — žádný guard; `OrderServiceImpl` se stavem zakázky vůbec nepracuje (enum `order_status` existuje jen v DB a na FE).
**Problém:** Faktura kopíruje položky v okamžiku vytvoření (správně, snapshot). Ale položky zakázky lze dál přidávat/mazat/měnit i poté, co k ní existuje vydaná faktura → faktura a zakázka se rozjedou a nikdo se to nedozví. Mazání položky navíc vrací zboží na sklad (`ISSUE_RETURN`) — u vyfakturované zakázky je to nechtěný únik.
**Proč je to proti praxi:** Servisní/ERP systémy zamykají doklad, jakmile na něj navazuje doklad vyšší (zakázka → faktura). Projekt tenhle vzor už zná — `requireEditable` u faktury, `requirePendingReview` u příjemky — jen ho neaplikuje na zakázky.
**Návrh:** `requireNotInvoiced(orderId)` (existuje `findByOrderId`) na mutační metody položek zakázky; do budoucna promyslet stavový automat zakázky (COMPLETED/CANCELLED by také měly zamykat).

### V3 · Politika hesel: minimum 6 znaků, bez dalších pravidel, bez rate limitu
**Kde:** [RegisterRequest.java:14](src/main/java/cz/palo/autoservis/security/model/dto/RegisterRequest.java:14); login bez omezení pokusů (žádný lockout, žádný delay, v pom.xml žádný bucket4j apod.).
**Proč je to proti praxi:** NIST SP 800-63B doporučuje min. 8 znaků + kontrolu proti slovníku uniklých hesel; a hlavně **throttling na login** — bez něj je BCrypt jediná brzda online brute-force.
**Návrh:** min. 8 znaků sjednotit napříč (register/reset/change), jednoduchý in-memory rate limit na `/auth/login` (per username+IP). Seed hesla `Password1!` — už evidováno v TD-33.

### V4 · Blacklist ukládá tokeny v plaintextu a stojí DB dotaz na každý request
**Kde:** `BlacklistMapper.save(accessToken)` + check v [JwtAuthenticationFilter.java:75](src/main/java/cz/palo/autoservis/security/filter/JwtAuthenticationFilter.java:75).
**Problém:** (a) únik DB = použitelné živé tokeny (do vypršení); ukládat se má SHA-256 hash. (b) Každý autentizovaný request = 2 DB dotazy (blacklist + `loadUserByUsername`). Na dnešní škále neškodné, ale je fér to vědět.
**Srovnání:** Standard je: krátký access token (15 min) → blacklist je volitelný luxus; pokud ano, hashovaný a ideálně v paměti/cache. Načítání uživatele z DB per request je naopak legitimní volba (okamžitá deaktivace účtu funguje) — jen ji dokumentovat jako vědomou.
**Návrh:** hashovat před uložením (jednoduchá změna v `save`/`isBlacklisted`), zbytek nechat.

### V5 · Ruční JSON chybové odpovědi mimo jednotný formát
**Kde:** [SecurityConfig.java:66](src/main/java/cz/palo/autoservis/config/security/SecurityConfig.java:66) (entry point), [JwtAuthenticationFilter.java:127](src/main/java/cz/palo/autoservis/security/filter/JwtAuthenticationFilter.java:127).
**Problém:** `{"status":401,"error":"..."}` — jiný tvar než RFC 9457 ProblemDetail, na kterém stojí zbytek API i frontendové parsování chyb. Filtr běží mimo `@RestControllerAdvice`, takže si formát musí vyrobit sám — ale měl by vyrábět stejný.
**Návrh:** malý helper (nebo `HandlerExceptionResolver` delegace), který v obou místech sestaví ProblemDetail JSON s `errors[]`.

### V6 · `shouldNotFilter` porovnává přes `contains`
**Kde:** [JwtAuthenticationFilter.java:54](src/main/java/cz/palo/autoservis/security/filter/JwtAuthenticationFilter.java:54).
**Problém:** `path.contains("/auth/login")` — obejde filtr i pro hypotetické `/api/v1/cokoliv/auth/login`. Dnes nezneužitelné (SecurityConfig by stejně chtěl autentizaci), ale je to nepřesná shoda tam, kde má být přesná — a nekonzistence se `requestMatchers("/api/*/auth/login")` o vrstvu výš.
**Návrh:** sjednotit na tentýž vzor (`AntPathMatcher` / `PathPatternRequestMatcher`).

---

## 4. Střední nálezy — robustnost a kvalita

### S1 · `apiFetch` spadne na ne-JSON chybové odpovědi a chybový kontrakt je křehký
**Kde:** [api.js:44](frontend/autoservis-frontend/src/api/api.js:44) — `JSON.parse(text)` běží **před** kontrolou `response.ok`; každý volající pak dělá `JSON.parse(err.message)`.
**Problém:** 502 od proxy (HTML), timeout, prázdné tělo s chybovým statusem → `SyntaxError` místo srozumitelné chyby; status kód se do error objektu vůbec nepropaguje. Vzor „parsuj message v každém catch" je duplicitní a rozbitný.
**Srovnání:** Zavedená praxe (axios, ky, react-query ekosystém) je typovaná chyba: `class ApiError extends Error { status, problem }`, parsovaná jednou v klientu.
**Návrh:** v `apiFetch`: nejdřív `response.ok`; při chybě zkusit `JSON.parse`, při neúspěchu fallback `{ detail: text || statusText }`; vyhazovat `ApiError` se statusem. Volající pak čtou `err.problem?.detail` bez parsování. (Souvisí s TD-34.)

### S2 · MyBatis konfigurace obsahuje dvě „spící" volby
**Kde:** [application.yaml:59–61](src/main/resources/application.yaml:59).
**Problém:** (a) `cache-enabled: true` — druhá úroveň cache se aktivuje jen s `<cache/>` v XML (nikde není), takže je dnes inertní; ale kdyby ji někdo přidal, při vícero mapperech nad stejnými tabulkami (WarehouseImportMapper vs. ReceiptReviewMapper vs. WarehouseMapper!) hrozí stale čtení. (b) `lazy-loading-enabled: true` — projekt nepoužívá nested associations, POJO opouštějí session; lazy loading by selhal za běhu.
**Návrh:** obě vypnout (nebo komentářem přiznat, že jsou vypnutelné) — konfigurace má tvrdit jen to, co platí. `default-statement-timeout: 30` naopak pochvala — málokdo ho nastavuje.

### S3 · Verze `"latest"` v package.json (= TD-37, potvrzuji jako reálné riziko)
`@mui/material` a `@emotion/styled` na `latest` = nedeterministický build; nová major MUI verze rozbije build bez jakékoli změny v repu. Zafixovat. (Chybí i lockfile audit — `npm ci` v CI by to vynutil.)

### S4 · PDF v databázi (bytea)
`goods_receipts.source_pdf` + limit 10 MB. Pro současný objem v pořádku a zjednodušuje zálohy (jedna DB); kód správně čte PDF odděleným dotazem (`findPdfById`), takže netíží běžné SELECTy. Jen vědomě sledovat růst — při stovkách příjemek/rok to vydrží roky, při vyšším objemu přejít na filesystem/objektové úložiště s odkazem v DB. Žádná akce teď.

### S5 · Testy: realita je lepší, než tvrdí dokumentace — ale díry jsou přesně tam, kde to bolí
**Stav:** 13 testovacích tříd (tech-dluhy.md TD-14 uvádí 4 — dokument je po přestavbě importu zastaralý). Import pipeline je pokrytá slušně (DraftAssembler, DraftVerification, ProductMatching, ReceiptReview, WarehouseImport).
**Chybí:** `InvoiceService` (stavový automat — priorita už dle TD-14), `AuthenticationService` (rotace, reuse detekce), `OrderItemService.importFromReceipt/delete` (skladové pohyby — právě místa z K5/K6/V2). Testy psát ideálně spolu s opravami K5/K6 — opravy bez testů by se mohly tiše vrátit.

### S6 · Bleeding-edge závislosti
Spring Boot 4.0.3 + Security 7 = čerstvé GA, obhajitelné pro výukový projekt. **Spring AI 2.0.0-M4 je milestone** v produkční cestě importu — API se mezi M verzemi láme. Návrh: hlídat vydání 2.0 GA a přejít hned po něm; do té doby žádné další Spring AI API nerozšiřovat.

### S7 · Časové API v auth vrstvě
`System.currentTimeMillis` + `LocalDateTime.now()` + `ZoneId.systemDefault()` ([AuthenticationService.java:224](src/main/java/cz/palo/autoservis/security/service/AuthenticationService.java:224)) — expirace refresh tokenů závisí na časové zóně serveru; změna TZ (přesun serveru, kontejner s UTC) posune platnost. Praxe: `Instant`/UTC všude, `TIMESTAMPTZ` v DB (ta už je). Drobnost, ale levná při nejbližším zásahu do auth.

### S8 · Frontendové dluhy TD-34/35/36/38/39/40 — potvrzeny, beze změny priority
Copy-paste `useXxxRowActions` (5×), nativní `confirm/alert` na třech místech, dead code, `removeAlert` closure, zastaralé typeDefs, chybějící route guard (obsah blikne). Nic z toho není urgentní; TD-35 + S1 by šly spojit do jednoho úklidového zásahu do FE infrastruktury.

---

## 5. Co explicitně pochválit (a neměnit)

1. **Draft workflow příjemek** — `TrackedField` s provenancí, completeness gate, guarded confirm s 409, samoučící `supplier_products` převodník. Tohle je návrh, který by obstál v komerčním produktu.
2. **Optimistická kontrola u příjemek** (`requireUpdated` → 409 „mezitím zpracoval někdo jiný") — vzor k rozkopírování do fakturace (K5).
3. **DB jako aktivní vrstva** — triggery na číslování s advisory lockem, `updated_at`, přepočet zásob, CHECK constrainty jako poslední linie. Vědomé a zdokumentované rozhodnutí, ne nahodilost.
4. **Detekce reuse refresh tokenu s revokací všech sessions** — nadstandard (jen ji zapojit, K3).
5. **Dokumentační disciplína** — tech-dluhy ověřované proti kódu, funkční dokumenty, hook proti editaci migrací a archivu.

---

## 6. Prioritizovaný plán

| Pořadí | Co | Proč teď | Odhad rozsahu |
|---|---|---|---|
| 1 | **K1+K2** zavřít/odstranit veřejný register | jediná skutečná díra dostupná anonymně | malý (smazání endpointu + FE nic nepoužívá) |
| 2 | **K7** flyway blok pod `spring:` | jednořádková oprava tiché pasti | triviální |
| 3 | **K5** guarded UPDATE stavu faktury | právní doklad + vzor už v projektu existuje | malý + test |
| 4 | **K6** součet per šarže + `FOR UPDATE` při výdeji | správnost skladu, čisté chyby | malý–střední + test |
| 5 | **V2** zámek zakázky po fakturaci | konzistence zakázka ↔ faktura | malý |
| 6 | **K3+K4** refresh interceptor na FE + cookies z konfigurace | podmínka produkce (15min tokeny) | střední |
| 7 | **V3–V6** hesla, rate limit, hash blacklistu, jednotný 401 formát, `shouldNotFilter` | bezpečnostní hygiena před produkcí | střední (nezávislé drobnosti) |
| 8 | **S1** ApiError v `apiFetch` (+ TD-34) | robustnost FE, odemyká čistší error handling všude | malý–střední |
| 9 | **V1** dobropisy | doménová úplnost — roadmapa, ne hotfix | velký (nová entita + číselná řada) |
| 10 | **S2, S3, S5, S7, S8** | průběžný úklid při nejbližších zásazích do daných míst | drobné |

Doporučené zápisy do `tech-dluhy.md`: K1/K2 (nový TD, 🔴), K3 (nový TD, 🟠), K5 (nový TD, 🟠), K6 (nový TD, 🟠), V1 (roadmapa), V2 (nový TD, 🟠), V3–V6 (nové TD, 🟡), S1 (rozšíření TD-34), TD-27 povýšit na 🟠, TD-14 aktualizovat počty testů, TD-37 potvrzen.

---

## 7. Metodická poznámka

Analýza vychází z přímého čtení kódu (security vrstva kompletně, fakturace, sklad/import kompletně, vzorky mapperů a migrací, FE API vrstva a routing, konfigurace, pom.xml, testovací sada) a z konfrontace s dokumentací (`architektura.md`, `konvence.md`, `tech-dluhy.md`). Všechny existující TD položky, kterých se nálezy dotýkají, jsou uvedeny u nálezů — nic z tech-dluhů jsem nepřepisoval jako vlastní objev; nové nálezy K1–K3, K5, K6, V1–V6, S1, S2, S7 v registru dosud nejsou.
