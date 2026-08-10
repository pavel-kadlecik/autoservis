# plan-oprav.md — Prováděcí plán oprav (červenec 2026)

> Zdroj nálezů: [analyza-2026-07.md](analyza-2026-07.md) (kódy K/V/S) + [tech-dluhy.md](tech-dluhy.md) (kódy TD).
> Plán je psaný tak, aby každý úkol mohl **samostatně provést i slabší model** — úkoly jsou malé,
> mají přesné soubory, postup, akceptační kritéria a testy. Jak úkoly zadávat: sekce §2.
>
> Stav úkolu značit zaškrtnutím checkboxu v §3 (dělá vykonavatel po splnění akceptačních kritérií).

---

## 1. Pravidla pro vykonavatele (platí pro KAŽDÝ úkol)

1. **Pracuj jen na zadaném úkolu.** Nic „při té příležitosti" neopravuj, nerefaktoruj, nepřejmenovávej. Když najdeš jiný problém, jen ho napiš do závěrečného shrnutí.
2. **Před začátkem si přečti:** tento dokument (celý úkol včetně „Co NEDĚLAT"), `CLAUDE.md`, a dokumenty uvedené u úkolu v poli *Čti*.
3. **Neměň commitnuté Flyway migrace** (vynucuje hook). Nová migrace = skill `nova-migrace`. V tomto plánu žádný úkol migraci nevyžaduje.
4. **Po dokončení:**
   a. ověř všechna akceptační kritéria úkolu a napiš ke každému výsledek,
   b. spusť testy dle §4 (minimálně kompilaci `./mvnw test-compile` a testy dotčeného modulu),
   c. aktualizuj dokumentaci uvedenou u úkolu (typicky `tech-dluhy.md` — přesun položky do „Vyřešeno" s datem, příp. `api.md`),
   d. zaškrtni checkbox úkolu v §3 tohoto souboru,
   e. **necommituj** — commit dělá uživatel po kontrole (pokud v zadání nestojí jinak).
5. **Když si nejsi jistý, zastav se a napiš, co je nejasné.** Neimprovizuj — polovičatá oprava je horší než žádná.
6. Kód piš stylem okolního kódu (javadoc anglicky u security/billing, česky u nového skladu — drž se souboru, který upravuješ). Chybové hlášky pro uživatele česky, kódy chyb VELKÝMI_PODTRŽÍTKY.

## 2. Jak úkoly zadávat (pokyny pro uživatele)

**Před prvním úkolem (jednorázově):**
1. Dokonči a commitni rozpracovanou větev `sklad` (přestavba importu) — úkoly A1, A4 a C-fáze sahají do souborů, které jsou na `sklad` změněné. Plán spouštěj až z čisté pracovní kopie (ideálně po merge `sklad` → `devel`).
2. Pro testy je potřeba Docker (Testcontainers). Na tomto stroji Docker není — viz §4, co lze ověřit bez něj a kdy testy pustit jinde.
3. Každý úkol dělej v nové session (čistý kontext) a ideálně na vlastní větvi (`oprava/A2-register` apod.).

**Šablona zadání (zkopíruj a doplň ID úkolu):**

```
Přečti si docs/plan-oprav.md — nejdřív celou sekci §1 (pravidla), pak úkol <ID>.
Proveď úkol <ID> přesně podle zadání — provedení schvaluji ("udělej to ty").
Pracuj výhradně na tomto úkolu. Po dokončení:
- vypiš akceptační kritéria a u každého ✓/✗ s důkazem (výstup příkazu, číslo řádku),
- spusť ověření dle §4 a vlož výstup,
- aktualizuj dokumentaci dle zadání a zaškrtni checkbox v §3.
Necommituj.
```

**Po dokončení úkolu (tvoje kontrola):**
1. Přečti si shrnutí a diff (`git diff`).
2. Spusť `/code-review` na pracovní změny.
3. Ověř ručně dle „Ruční ověření" u úkolu (většinou 1–2 minuty s běžícím backendem/frontendem).
4. Commitni sám (konvence: `Oprava <ID>: <název úkolu>`); teprve pak zadej další úkol.

**Pořadí a závislosti:** Fáze A → B → C → D (v rámci fáze podle čísel). Výjimky, které NELZE prohodit:
- A2 **před** B4 a B6 (odstranění registrace mění soubory, kterých se týkají),
- B1 **před** B2 (refresh interceptor spoléhá na správné cookie parametry),
- C1 **před** C2 (sjednocené chybové hlášky staví na ApiError).
Nikdy nezadávej dva úkoly současně (paralelní sessions) — sahají do sdílených souborů.

---

## 3. Přehled úkolů a stav

| | ID | Název | Nález | Velikost | Riziko |
|---|---|---|---|---|---|
| [x] | A1 | Flyway blok pod `spring:` | TD-27/K7 | XS | nízké |
| [x] | A2 | Odstranit veřejnou registraci | K1+K2 | S | nízké |
| [x] | A3 | Guarded UPDATE stavu faktury | K5 | S | nízké |
| [x] | A4 | Výdej ze skladu: agregace + FOR UPDATE | K6 | M | střední |
| [x] | A5 | Zámek zakázky po fakturaci | V2 | S | nízké |
| [x] | B1 | Auth cookies z konfigurace | K4/TD-31 | S | střední |
| [x] | B2 | FE: refresh interceptor | K3 | M | střední |
| [x] | B3 | Zapojit zamykání účtu po neúspěšných loginech | V3b | M | střední |
| [x] | B4 | Hesla min. 8 znaků | V3a | XS | nízké |
| [x] | B5 | Blacklist: hash místo raw tokenu | V4 | S | nízké |
| [x] | B6 | Jednotný 401 formát + přesný shouldNotFilter | V5+V6 | S | nízké |
| [x] | C1 | FE: ApiError v api.js | S1 | S | střední |
| [x] | C2 | FE: ConfirmDialog + useAlert všude | TD-34 | S | nízké |
| [x] | C3 | FE: drobnosti (verze, removeAlert, typeDefs, dead code) | TD-36–39 | S | nízké |
| [x] | C4 | FE: route guard | TD-40(FE) | S | nízké |
| [x] | C5 | FE: sjednotit useXxxRowActions | TD-35 | M | střední |
| [x] | D1 | Validátory @ValidCustomerRequest/@ValidVehicleRequest | TD-10 | M | nízké |
| [x] | D2 | Null guardy + IllegalArgumentException→400 + Boolean v Update DTO | TD-20+TD-23 | S | nízké |
| [x] | D3 | Víceslovné hledání (zákazníci, zakázky) | TD-18+TD-25 | M | střední |
| [x] | D4 | Deaktivace produktu se zásobou | TD-28 | S | nízké |
| [x] | D5 | REST kosmetika: Location header; přejmenování ProductMapper.xml | TD-12+TD-32 | XS | nízké |

**Mimo tento plán (nezadávat slabšímu modelu, vyžadují návrh):** V1 dobropisy (roadmapa — nová entita, číselná řada, účetní pravidla), TD-24 plošné role, TD-22 @JsonView, TD-11 internal_note, TD-16 přejmenování schématu `order`. TD-13, TD-40(DB) — přijaté kompromisy, beze změny.

---

## 4. Testování a ověřování

- `./mvnw test-compile` — kompilace včetně testů, běží bez Dockeru. **Minimální ověření každého backendového úkolu.**
- `./mvnw test` — celá sada vyžaduje **Docker** (Testcontainers, singleton container `AbstractIntegrationTest`). Na vývojovém stroji Docker není → dvě možnosti: (a) nainstalovat/spustit Docker Desktop, (b) úkol dokončit s napsanými testy a celou sadu spustit později hromadně tam, kde Docker je. Testy se **píšou vždy**, i když je hned nejde spustit.
- Cílený běh: `./mvnw test -Dtest=NazevTestu`.
- Backend ručně: spustit z IDE (profil `local`, port 8080, DB na 5432 — pozor, `lokalni-databaze.md` uvádí 5433, na tomto stroji platí 5432).
- Frontend: `cd frontend/autoservis-frontend && npm run dev` → http://localhost:5173, přihlásit se seed účtem `admin`/`Password1!`.
- U FE úkolů vždy zkontrolovat konzoli prohlížeče (žádné nové chyby) a síťovou záložku.

---

## 5. Zadání úkolů

### A1 · Flyway konfigurace pod `spring:` (TD-27/K7)

**Proč:** Blok `flyway:` v `application.yaml` je odsazený pod `mybatis:`, takže se čte jako neexistující `mybatis.flyway.*` a **všechna nastavení (`clean-disabled`, `validate-on-migrate`, `locations`) se ignorují** — migrace fungují jen náhodou přes defaulty Spring Bootu.

**Soubory:** `src/main/resources/application.yaml`.
**Čti:** docs/konvence.md §14 (cílová podoba).

**Postup:**
1. V `application.yaml` najdi blok `flyway:` (cca ř. 68, odsazený 2 mezerami pod `mybatis:`).
2. Celý blok (včetně komentářů a zakomentovaných řádků `baseline-on-migrate`, `placeholders`) přesuň do sekce `spring:` — tj. zařaď ho na stejnou úroveň jako `datasource:`/`ai:` s odsazením 2 mezery, vnitřek 4 mezery. Hodnoty neměň.
3. Sekce `mybatis:` zůstane bez vnořeného `flyway`.

**Co NEDĚLAT:** neměnit žádné hodnoty; nesahat na `application-prod.yaml` ani `-local`.

**Akceptační kritéria:**
- `flyway:` je přímý potomek `spring:`; pod `mybatis:` už není.
- Backend nastartuje (z IDE, profil local) a v logu je řádek Flyway o validaci migrací (`Successfully validated 41 migrations` nebo obdobný).

**Dokumentace:** tech-dluhy.md — TD-27 přesunout do „Vyřešeno" s datem.

---

### A2 · Odstranit veřejnou registraci (K1+K2)

**Proč:** `/auth/register` je `permitAll` a založený účet má přístup ke všem business endpointům (skoro vše chce jen `authenticated()`). Účty nyní zakládá výhradně admin přes `UserController` — self-registrace je pozůstatek a bezpečnostní díra. Navíc jako jediný endpoint vrací tokeny v těle odpovědi (XSS riziko), v rozporu s cookie-only strategií.

**Soubory:**
- `src/main/java/cz/palo/autoservis/security/controller/AuthController.java`
- `src/main/java/cz/palo/autoservis/security/service/AuthenticationService.java`
- `src/main/java/cz/palo/autoservis/config/security/SecurityConfig.java`
- `src/main/java/cz/palo/autoservis/security/filter/JwtAuthenticationFilter.java`
- `src/main/java/cz/palo/autoservis/security/model/dto/RegisterRequest.java` (smazat)

**Čti:** docs/api.md (sekce auth), docs/konvence.md §19.

**Postup:**
1. `AuthController`: smaž metodu `register(...)` a související import. Uprav javadoc třídy (odstraň zmínku o registraci).
2. `AuthenticationService`: smaž metodu `register(...)`. Metodu `issueTokenPair` a `UserAlreadyExistsException` NEmazat, pokud je používá něco jiného — ověř `grep -r "UserAlreadyExistsException" src/` a `grep -r "issueTokenPair"`; `issueTokenPair` zůstává pro login/refresh.
3. `SecurityConfig.filterChain`: z `permitAll` bloku odstraň řádek `"/api/*/auth/register"`. Řádky login a refresh ponech.
4. `JwtAuthenticationFilter.shouldNotFilter`: odstraň podmínku `path.contains("/auth/register")`.
5. Smaž `RegisterRequest.java` — před smazáním ověř `grep -r "RegisterRequest" src/`, že ho nepoužívá nic jiného (UserController má vlastní `UserDto.CreateRequest`). Pokud používá, zastav se a nahlas.
6. Zkontroluj testy: `grep -ri "register" src/test/` — pokud nějaký test registruje uživatele přes API/service, přepiš ho na založení přes `UserMapper.save` (vzor v `AbstractIntegrationTest` nebo jiných testech) nebo na přihlášení seed účtem `admin`/`Password1!`.
7. Zkontroluj frontend: `grep -ri "register" frontend/autoservis-frontend/src/` — očekává se žádný výskyt (registrační stránka neexistuje). Pokud něco najdeš, nahlas, nemaž.

**Co NEDĚLAT:** nesahat na `/auth/login`, `/auth/refresh`, `/auth/logout`, `/auth/me`, `/auth/change-password`; nesahat na `UserController`.

**Akceptační kritéria:**
- `grep -r "register" src/main/java` nenajde žádný auth-registrační kód (výskyty typu `registerCorsConfiguration` jsou v pořádku).
- `./mvnw test-compile` projde.
- Ruční ověření: `POST http://localhost:8080/api/v1/auth/register` vrací 401 (endpoint už není public ani neexistuje); login `admin` funguje.

**Dokumentace:** docs/api.md — odstranit endpoint register; docs/architektura.md — pokud registraci zmiňuje, opravit; tech-dluhy.md — zapsat do „Vyřešeno" jako „K1/K2 z analyza-2026-07".

---

### A3 · Guarded UPDATE stavu faktury (K5)

**Proč:** `InvoiceServiceImpl.transitionTo` kontroluje povolený přechod v Javě a pak provede `UPDATE ... SET status ... WHERE id = ...` bez podmínky na aktuální stav. Dva souběžné požadavky (dvojklik, dva uživatelé) oba projdou kontrolou a druhý tiše přepíše prvního — u právního dokladu. Vzor správného řešení už v projektu je: `ReceiptReviewServiceImpl.confirm` + `requireUpdated` → 409.

**Soubory:**
- `src/main/resources/mapper/InvoiceMapper.xml` (statement `updateStatus`, cca ř. 169)
- `src/main/java/cz/palo/autoservis/mapper/InvoiceMapper.java` (signatura `updateStatus`)
- `src/main/java/cz/palo/autoservis/service/impl/InvoiceServiceImpl.java` (metoda `transitionTo`, cca ř. 324)
- nový test `src/test/java/cz/palo/autoservis/service/InvoiceStatusTransitionTest.java`

**Čti:** docs/backend.md, vzor: `ReceiptReviewServiceImpl.requireUpdated` (ř. 320) a `ReceiptReviewMapper.xml` statement `confirm`.

**Postup:**
1. `InvoiceMapper.java`: změň signaturu na
   ```java
   int updateStatus(@Param("id") Long id,
                    @Param("status") InvoiceStatus status,
                    @Param("expectedStatus") InvoiceStatus expectedStatus);
   ```
2. `InvoiceMapper.xml` — `updateStatus` doplň o guard (typeHandler zapiš stejně, jako je u `status` v témže souboru):
   ```xml
   UPDATE billing.invoices
   SET status = #{status, typeHandler=cz.palo.autoservis.config.mybatis.PgEnumTypeHandler$InvoiceStatusHandler}
   WHERE id = #{id}
     AND status = #{expectedStatus, typeHandler=cz.palo.autoservis.config.mybatis.PgEnumTypeHandler$InvoiceStatusHandler}
   ```
3. `InvoiceServiceImpl.transitionTo`: volání změň na `invoiceMapper.updateStatus(id, target, current)`. Když `affectedRows == 0`, vyhoď místo dosavadního `IllegalStateException`:
   ```java
   throw new ConflictException("INVOICE_STATE_CHANGED",
           "Fakturu " + id + " mezitím změnil někdo jiný. Načtěte ji znovu.");
   ```
   Předběžnou kontrolu `canTransitionTo` (422 `INVALID_STATUS_TRANSITION`) ponech — dává hezčí chybu v běžném případě; guard řeší jen souběh. Metodu `verifyAndFetchAfterUpdate` v tomto místě nahraď přímým `fetchOrFail(id)` po úspěšném update (jiná volání `verifyAndFetchAfterUpdate` neměň).
4. Test (`extends AbstractIntegrationTest`, vzor v `CustomerServiceTest`): založ přes existující mappery/servisy fakturu ve stavu DRAFT (nejjednodušší: použij seed data z V16, nebo vytvoř zakázku + fakturu přes service — okoukej setup z existujících testů). Ověř:
   - `service.issue(id, userId)` → status ISSUED;
   - druhý `service.issue(id, userId)` → `BusinessRuleException` s kódem `INVALID_STATUS_TRANSITION` (chytí to už předběžná kontrola);
   - přímé `invoiceMapper.updateStatus(id, InvoiceStatus.PAID, InvoiceStatus.DRAFT)` vrátí **0** (guard funguje — faktura je ISSUED, ne DRAFT);
   - `invoiceMapper.updateStatus(id, InvoiceStatus.PAID, InvoiceStatus.ISSUED)` vrátí **1**.

**Co NEDĚLAT:** neměnit enum `InvoiceStatus` ani `canTransitionTo`; neměnit ostatní statementy v XML.

**Akceptační kritéria:**
- `updateStatus` v XML obsahuje `AND status = #{expectedStatus...}`.
- 0 affected rows v `transitionTo` → `ConflictException` (HTTP 409, kód `INVOICE_STATE_CHANGED`).
- Nový test existuje a `./mvnw test-compile` projde (spuštění dle §4).

**Dokumentace:** docs/api.md — u přechodových endpointů faktury doplnit možnou 409; tech-dluhy.md → „Vyřešeno" (K5).

---

### A4 · Výdej ze skladu: agregace požadavků + FOR UPDATE (K6)

**Proč:** `OrderItemServiceImpl.importFromReceipt` validuje množství proti `quantity_remaining` stylem check-then-act: (a) duplicitní `goodsReceiptItemId` v jednom requestu se validují každý zvlášť proti témuž zůstatku, (b) souběžné výdeje z téže šarže projdou oba. Data zachrání DB CHECK (`chk_items_remaining >= 0`), ale uživatel dostane nic neříkající 422 `DATA_INTEGRITY_VIOLATION` místo `QUANTITY_EXCEEDS_REMAINING`.

**Soubory:**
- `src/main/java/cz/palo/autoservis/service/impl/OrderItemServiceImpl.java` (metoda `importFromReceipt`, ř. 89–165)
- `src/main/java/cz/palo/autoservis/mapper/GoodsReceiptMapper.java` + příslušný XML (`src/main/resources/mapper/warehouse/…` — najdi soubor se statementem `findByIds`)
- test `src/test/java/cz/palo/autoservis/service/OrderItemImportTest.java` (nový)

**Postup:**
1. Do `GoodsReceiptMapper` přidej metodu `List<GoodsReceiptItem> findByIdsForUpdate(@Param("ids") List<Long> ids);` a do XML statement — zkopíruj existující `findByIds` a přidej na konec `FOR UPDATE`:
   ```xml
   <select id="findByIdsForUpdate" resultMap="...stejná jako findByIds...">
       SELECT ... FROM warehouse.goods_receipt_items
       WHERE id IN
       <foreach item="id" collection="ids" open="(" separator="," close=")">#{id}</foreach>
       FOR UPDATE
   </select>
   ```
   (Přesnou podobu sloupců/resultMap převezmi z existujícího `findByIds` — neduplikuj ručně.)
2. V `importFromReceipt` nahraď `goodsReceiptMapper.findByIds(ids)` za `findByIdsForUpdate(ids)`. Metoda už je `@Transactional`, zámky se drží do konce transakce.
3. Před validaci vlož agregaci požadavků per šarže:
   ```java
   Map<Long, BigDecimal> requestedPerBatch = importRequest.stream()
           .collect(Collectors.groupingBy(
                   GoodsReceiptItemDto.ImportRequest::getGoodsReceiptItemId,
                   Collectors.reducing(BigDecimal.ZERO,
                           GoodsReceiptItemDto.ImportRequest::getQuantity, BigDecimal::add)));
   ```
   a stávající cyklus validace nahraď cyklem přes `requestedPerBatch.entrySet()` — porovnávej **součet** proti `quantityRemaining` dané šarže. Chybový kód a parametry (`goodsReceiptItemId`, `requested` = součet, `remaining`) zachovej.
4. Zbytek metody (vytváření položek a pohybů po jednotlivých requestech) neměň.
5. Test (`extends AbstractIntegrationTest`): připrav šarži se `quantity_remaining = 4` (vlož přes `WarehouseImportMapper.insertReceipt` + `insertReceiptItem`, vzor v `WarehouseImportServiceTest` / `ReceiptReviewServiceTest`). Ověř:
   - request se dvěma položkami téže šarže 3 + 3 ks → `BusinessRuleException` kód `QUANTITY_EXCEEDS_REMAINING` (dnes by prošel až na DB chybu);
   - request 2 + 2 ks → projde, `quantity_remaining` po výdeji = 0;
   - request 5 ks z jedné položky → `QUANTITY_EXCEEDS_REMAINING` (regrese stávajícího chování).

**Co NEDĚLAT:** neměnit trigger ani CHECK constrainty v DB; neměnit `insertMovement`; nezavádět jinou izolační úroveň transakcí.

**Akceptační kritéria:** tři testovací scénáře výše; `./mvnw test-compile` projde; `findByIds` (bez FOR UPDATE) zůstává pro ostatní použití — ověř `grep -rn "findByIds" src/main/java`.

**Dokumentace:** tech-dluhy.md → „Vyřešeno" (K6); docs/backend.md pokud popisuje výdej ze skladu.

---

### A5 · Zámek zakázky po fakturaci (V2)

**Proč:** Položky zakázky lze přidávat/měnit/mazat i poté, co k zakázce existuje faktura — faktura (snapshot položek z okamžiku vytvoření) a zakázka se tiše rozjedou; smazání položky navíc vrací zboží na sklad. Projekt vzor zámku už má (`requireEditable` u faktur, `requirePendingReview` u příjemek), jen chybí u zakázek.

**Rozhodnutí (schváleno v analýze):** mutace položek zakázky jsou blokované, existuje-li k zakázce faktura ve stavu jiném než CANCELLED. `reorder` (jen pořadí zobrazení) zůstává povolený.

**Soubory:**
- `src/main/java/cz/palo/autoservis/service/impl/OrderItemServiceImpl.java`
- test `src/test/java/cz/palo/autoservis/service/OrderItemInvoiceLockTest.java` (nový; nebo přidej metody do testu z A4)

**Postup:**
1. Do `OrderItemServiceImpl` injektuj `InvoiceMapper` (přidej field do `@RequiredArgsConstructor` sady). Poznámka do javadocu helperu: závislost order→billing je vědomý kompromis (zámek dokladu patří k zakázce, samostatný stavový automat zakázky je mimo rozsah).
2. Přidej privátní helper:
   ```java
   private void requireOrderNotInvoiced(Long orderId) {
       invoiceMapper.findByOrderId(orderId)
               .filter(inv -> inv.getStatus() != InvoiceStatus.CANCELLED)
               .ifPresent(inv -> {
                   throw new BusinessRuleException(
                           "ORDER_LOCKED_BY_INVOICE", "orderId",
                           "Zakázka už má fakturu " + inv.getInvoiceNumber() + " — položky nelze měnit.",
                           Map.of("orderId", orderId, "invoiceId", inv.getId()));
               });
   }
   ```
   (Přesné gettery ověř podle `Invoice` domain třídy.)
3. Zavolej helper na začátku metod: `create`, `importFromReceipt`, `update` (orderId vezmi z načtené existující položky), `delete` (dtto). `reorder`, `getByOrderId`, `getById`, `getSummaryByOrderId` neměň.
4. Test: vytvoř zakázku s položkou a fakturu k ní (vzor setupu viz test z A3); ověř, že `create`/`update`/`delete` položky vyhodí `BusinessRuleException` s kódem `ORDER_LOCKED_BY_INVOICE` a že před vytvořením faktury tytéž operace fungují.

**Co NEDĚLAT:** nezavádět stavový automat zakázky; neměnit `OrderServiceImpl`; neblokovat čtecí metody ani `reorder`.

**Akceptační kritéria:** testy dle bodu 4; `./mvnw test-compile`; ruční ověření — u vyfakturované zakázky FE zobrazí chybovou hlášku při pokusu přidat položku (hláška z `detail` pole se propaguje automaticky).

**Dokumentace:** docs/api.md (nová 422 u order-items endpointů), docs/funkce/ pokud existuje dokument k zakázkám; tech-dluhy.md → „Vyřešeno" (V2).

---

### B1 · Auth cookies z konfigurace (K4/TD-31)

**Proč:** Cookie `jwt` má natvrdo maxAge 60 min (token platí 8 h dev / 15 min prod), refresh cookie 30 dní (token 7 dní), `secure=false` natvrdo i pro produkci, path refresh cookie natvrdo `/api/v1/...` v controlleru mapovaném na `/api/{version}/...`.

**Soubory:**
- `src/main/java/cz/palo/autoservis/security/controller/AuthController.java`
- `src/main/resources/application.yaml`, `application-prod.yaml`

**Postup:**
1. Do `application.yaml` pod `jwt:` přidej `cookie-secure: false` (s komentářem „true v produkci — HTTPS only"); do `application-prod.yaml` pod `jwt:` přidej `cookie-secure: true`.
2. V `AuthController` injektuj:
   ```java
   @Value("${jwt.expiration}")         private long jwtExpirationMs;
   @Value("${jwt.refresh-expiration}") private long refreshExpirationMs;
   @Value("${jwt.cookie-secure}")      private boolean cookieSecure;
   ```
3. `setTokenCookies` a `deleteTokenCookies` přijmou navíc `String version` (předá ji každý endpoint z `@PathVariable("version") String version` — do signatur handlerů `login`, `refresh`, `logout` přidej `@PathVariable String version`):
   - access cookie: `.maxAge(Duration.ofMillis(jwtExpirationMs))`, `.secure(cookieSecure)`, path `/api` beze změny;
   - refresh cookie: `.maxAge(Duration.ofMillis(refreshExpirationMs))`, `.secure(cookieSecure)`, `.path("/api/" + version + "/auth/refresh")`;
   - v delete variantách maxAge(0), secure/path stejně jako výše.
4. Zkontroluj, že nikde jinde se tyto cookies nenastavují: `grep -rn "ResponseCookie" src/main/java`.

**Co NEDĚLAT:** neměnit SameSite (`Strict` zůstává); neměnit názvy cookies; neměnit `jwt.expiration` hodnoty.

**Akceptační kritéria:**
- žádný natvrdo zapsaný maxAge/secure/path-verze v `AuthController` (kromě access path `/api`);
- ruční ověření: po loginu v DevTools → Application → Cookies: `jwt` má Expires ≈ now + 8 h, `jwt_refresh` ≈ now + 7 dní, path `/api/v1/auth/refresh`; logout obě smaže.

**Dokumentace:** tech-dluhy.md — TD-31 → „Vyřešeno"; docs/nasazeni.md — poznámka o `jwt.cookie-secure=true` v produkci.

---

### B2 · FE: refresh interceptor s single-flight (K3)

**Proč:** Frontend nikdy nevolá `/auth/refresh` — na 401 rovnou přesměruje na login. Celá rotace refresh tokenů je tak nevyužitá a s produkčním 15min tokenem by aplikace odhlašovala každých 15 minut. **Kritický detail:** rotace = každý refresh zneplatní předchozí token; když by N souběžných requestů vyvolalo N refreshů, druhý by vypadal jako reuse útok a server by revokoval všechny sessions. Proto single-flight (jedna sdílená Promise).

**Soubory:**
- `frontend/autoservis-frontend/src/api/api.js`
- `frontend/autoservis-frontend/src/api/auth.js`

**Čti:** docs/frontend.md; backend kontrakt: `POST /api/v1/auth/refresh` bez těla, cookies; 200 = nové cookies, 400/401 = konec sezení.

**Postup:**
1. Do `api.js` přidej nad `apiFetch`:
   ```js
   let refreshPromise = null;

   /** Jediný souběžný refresh (rotace tokenů — dva paralelní refreshe by se navzájem zabily). */
   function tryRefresh() {
       if (!refreshPromise) {
           refreshPromise = fetch(`${API_BASE}/auth/refresh`, {
               method: 'POST',
               credentials: 'include',
           })
               .then((r) => r.ok)
               .catch(() => false)
               .finally(() => { refreshPromise = null; });
       }
       return refreshPromise;
   }
   ```
2. V `apiFetch` nahraď blok `if (response.status === 401) { window.location.href = '/login'; return; }`:
   ```js
   if (response.status === 401) {
       const isAuthCall = path.startsWith('/auth/');
       if (!isAuthCall && !options._retried && await tryRefresh()) {
           return apiFetch(path, { ...options, _retried: true });
       }
       window.location.href = '/login';
       return;
   }
   ```
3. Stejnou logiku aplikuj v `api.getBlob` (401 → `tryRefresh()` → jeden retry → jinak redirect).
4. V `auth.js` uprav `requireAuth`: při `!response.ok` nejdřív jeden pokus o refresh (importuj/exportuj `tryRefresh` z api.js) a opakování `/auth/me`; teprve pak redirect.

**Co NEDĚLAT:** neukládat tokeny do JS/localStorage; nezavádět časovače proaktivního refreshe; neměnit backend.

**Akceptační kritéria (ruční ověření, viz postup):**
1. Do `application-local.yaml` dočasně nastav `jwt.expiration: 30000` (30 s) a restartuj backend.
2. Přihlas se, počkej >30 s, klikni na libovolný seznam → data se načtou, v Network je vidět `POST /auth/refresh` (200) a zopakovaný původní request; **žádný redirect na login**.
3. Otevři stránku s více souběžnými requesty (detail zakázky) po expiraci → v Network je `/auth/refresh` **jen jednou**.
4. Smaž v DevTools obě cookies → další klik přesměruje na login.
5. Vrať `jwt.expiration` na původní hodnotu.

**Dokumentace:** docs/frontend.md — popsat refresh chování `apiFetch`; tech-dluhy.md → „Vyřešeno" (K3).

---

### B3 · Zapojit zamykání účtu po neúspěšných přihlášeních (V3b)

**Proč:** Bez limitu pokusů je BCrypt jediná brzda online brute-force. Infrastruktura už existuje a **nikdo ji nevolá**: sloupce `failed_login_attempts`, `account_non_locked` a mapper metody `UserMapper.incrementFailedAttempts/resetFailedAttempts/updateLastLogin/lockAccount`.

**Rozhodnutí:** limit 10 neúspěšných pokusů → zámek účtu (`account_non_locked = FALSE`); odemyká admin (reset hesla přes `UserController` NEBO nová admin akce — viz krok 5). Počítadlo nuluje úspěšné přihlášení.

**Soubory:**
- `src/main/java/cz/palo/autoservis/security/service/AuthenticationService.java`
- `src/main/java/cz/palo/autoservis/exception/GlobalExceptionHandler.java`
- `src/main/java/cz/palo/autoservis/service/impl/UserServiceImpl.java` + `UserController` (odemknutí)
- test `src/test/java/cz/palo/autoservis/service/LoginLockoutTest.java` (nový)

**Postup:**
1. Ověř si nejdřív: `AppUserDetails.isAccountNonLocked()` — mapuje se z `account_non_locked`? (`grep -n "accountNonLocked" src/main/java -r`). Pokud vrací natvrdo `true`, oprav mapování z `User` domain objektu. Zamčený účet pak Spring Security odmítne `LockedException` už v `authenticationManager.authenticate`.
2. `AuthenticationService.login`: obal `authenticationManager.authenticate(...)` do try/catch:
   ```java
   try {
       authenticationManager.authenticate(...);
   } catch (BadCredentialsException e) {
       userMapper.findByUsername(request.username()).ifPresent(u -> {
           userMapper.incrementFailedAttempts(u.getId());
           if (u.getFailedLoginAttempts() + 1 >= MAX_FAILED_ATTEMPTS) {
               userMapper.lockAccount(u.getId());
           }
       });
       throw e;
   }
   ```
   `private static final int MAX_FAILED_ATTEMPTS = 10;`. Po úspěšné autentizaci zavolej `userMapper.updateLastLogin(user.getId())` (nuluje počítadlo i zapisuje čas — viz javadoc mapperu).
3. `GlobalExceptionHandler`: přidej handler pro `org.springframework.security.authentication.LockedException` → 401 s kódem `ACCOUNT_LOCKED` a hláškou „Účet je uzamčen po opakovaných neúspěšných přihlášeních. Kontaktujte administrátora." (Vzor: `handleBadCredentials`.) **Pozor na enumeraci účtů:** hláška nesmí prozradit, zda username existuje — LockedException nastane až u existujícího účtu se správným zámkem, to je akceptované.
4. Ověř, že increment probíhá i při vypnuté transakci rollbackem: login vyhazuje výjimku → `@Transactional` by increment odrolloval! **Řešení:** metoda `login` je `@Transactional` — increment proveď v samostatné komponentě s `@Transactional(propagation = Propagation.REQUIRES_NEW)` (malá `@Service LoginAttemptService` se dvěma metodami `recordFailure(username)`, `recordSuccess(userId)`), kterou `AuthenticationService` volá. Tohle je nejčastější chyba téhle úlohy — nepřeskoč ji.
5. Odemknutí: do `UserServiceImpl.resetPassword` (admin reset hesla) přidej `userMapper.resetFailedAttempts(id)` + odemčení účtu. Pokud `UserMapper` nemá metodu `unlockAccount`, přidej ji (analogicky k `lockAccount`, `SET account_non_locked = TRUE, failed_login_attempts = 0`). Admin reset hesla = odemčení, samostatný endpoint nezaváděj.
6. Test: 10× špatné heslo na seed účtu `mechanic` → 11. pokus se **správným** heslem vrací LockedException/401 `ACCOUNT_LOCKED`; admin reset hesla → login projde. (Pozn.: testuj přes `AuthenticationService`, ne HTTP.)

**Co NEDĚLAT:** nezavádět IP rate limiting ani externí knihovny (bucket4j apod.); neměnit BCrypt; nemazat počítadlo jinde než při úspěchu/odemčení.

**Akceptační kritéria:** test dle kroku 6; `AppUserDetails.isAccountNonLocked` odráží DB; increment přežije rollback login transakce (ověřeno testem — po neúspěšném loginu je `failed_login_attempts` v DB zvýšené).

**Dokumentace:** docs/api.md (nový kód `ACCOUNT_LOCKED`), docs/backend.md (sekce security), tech-dluhy.md → „Vyřešeno" (V3b).

---

### B4 · Hesla minimálně 8 znaků (V3a)

**Proč:** NIST SP 800-63B doporučuje min. 8 znaků; projekt má 6.

**Soubory:** `src/main/java/cz/palo/autoservis/model/dto/user/UserDto.java` (ř. 32 a 57), `src/main/java/cz/palo/autoservis/security/model/dto/ChangePasswordRequest.java`.

**Postup:** Najdi všechna heslová pole: `grep -rn "min = 6" src/main/java`. Změň na `min = 8` a uprav texty hlášek („alespoň 8 znaků"). Zkontroluj FE: `grep -rn "6 znak" frontend/autoservis-frontend/src` a případné client-side hlášky sjednoť. Seed hesla `Password1!` mají 10 znaků — beze změny.

**Co NEDĚLAT:** nezavádět composition rules (velké písmeno/číslice povinně) — NIST je nedoporučuje; neměnit existující hesla.

**Akceptační kritéria:** `grep -rn "min = 6" src/` nic nenajde; vytvoření uživatele s heslem `sedmzn7` (7 znaků) vrací 400 `SIZE_EXCEEDED`/velikostní chybu; s 8 znaky projde.

**Dokumentace:** docs/api.md pokud uvádí pravidla hesel; tech-dluhy.md → „Vyřešeno" (V3a).

---

### B5 · Blacklist: SHA-256 hash místo raw tokenu (V4)

**Proč:** Tabulka `security.token_blacklist` ukládá živé JWT v plaintextu — únik zálohy DB = použitelné tokeny až do vypršení. Ukládat se má otisk.

**Soubory:**
- nová utilita `src/main/java/cz/palo/autoservis/security/service/TokenHasher.java`
- `src/main/java/cz/palo/autoservis/security/service/AuthenticationService.java` (logout)
- `src/main/java/cz/palo/autoservis/security/filter/JwtAuthenticationFilter.java` (isBlacklisted)

**Postup:**
1. `TokenHasher` — statická metoda:
   ```java
   public static String sha256Hex(String token) {
       try {
           MessageDigest md = MessageDigest.getInstance("SHA-256");
           byte[] digest = md.digest(token.getBytes(StandardCharsets.UTF_8));
           StringBuilder sb = new StringBuilder(64);
           for (byte b : digest) { sb.append(String.format("%02x", b)); }
           return sb.toString();
       } catch (NoSuchAlgorithmException e) {
           throw new IllegalStateException("SHA-256 unavailable", e);
       }
   }
   ```
2. `AuthenticationService.logout`: `blacklistMapper.save(TokenHasher.sha256Hex(accessToken))`. Zkontroluj signaturu `BlacklistMapper.save` — pokud ukládá i expiraci tokenu, parametry zachovej.
3. `JwtAuthenticationFilter`: `blacklistMapper.isBlacklisted(TokenHasher.sha256Hex(jwt))`.
4. Ověř délku sloupce: hash má 64 znaků — `grep -n "token_blacklist" src/main/resources/db/migration/V1__init_security_schema.sql` (sloupec je dimenzovaný na celé JWT, 64 znaků se vejde). Migrace není potřeba.
5. **Známý důsledek (přijatý):** dřívější plaintextové záznamy přestanou matchovat — tokeny odhlášené před nasazením budou znovu platné do své expirace (max. 8 h dev / 15 min prod). Uveď to v commit message / shrnutí.

**Co NEDĚLAT:** žádná migrace; neměnit `BlacklistCleanupService`; nehashovat refresh tokeny (ty jsou náhodné UUID v jiné tabulce s jinou úlohou — mimo rozsah).

**Akceptační kritéria:** login → logout → opakovaný request s týmž access tokenem vrací 401 „Token is blacklisted"; v DB tabulce je 64znakový hex, ne JWT (`SELECT token FROM security.token_blacklist LIMIT 1`).

**Dokumentace:** docs/databaze.md pokud popisuje obsah sloupce; docs/backend.md sekce security; tech-dluhy.md → „Vyřešeno" (V4).

---

### B6 · Jednotný 401 formát + přesný shouldNotFilter (V5+V6)

**Proč:** Entry point v `SecurityConfig` a `JwtAuthenticationFilter.sendUnauthorized` píšou ruční JSON `{"status":401,"error":"..."}` — jiný tvar než RFC 9457 ProblemDetail zbytku API. A `shouldNotFilter` porovnává přes `contains`, což je nepřesné.

**Soubory:**
- nová třída `src/main/java/cz/palo/autoservis/security/filter/SecurityProblemWriter.java` (nebo obdobný název v `exception/`)
- `src/main/java/cz/palo/autoservis/config/security/SecurityConfig.java`
- `src/main/java/cz/palo/autoservis/security/filter/JwtAuthenticationFilter.java`

**Postup:**
1. `SecurityProblemWriter` — statická metoda `writeUnauthorized(HttpServletResponse response, String code, String detail)`: nastaví status 401, `Content-Type: application/problem+json;charset=UTF-8` a zapíše JSON:
   ```json
   {"type":"about:blank","title":"Unauthorized","status":401,"detail":"<detail>","errors":[{"code":"<code>","message":"<detail>"}]}
   ```
   Sestav přes Jackson `ObjectMapper` (statická instance), ne ruční řetězení — detail může obsahovat uvozovky. Struktura musí odpovídat tomu, co vrací `GlobalExceptionHandler.buildProblemDetail` (porovnej reálné odpovědi).
2. `SecurityConfig` entry point → `SecurityProblemWriter.writeUnauthorized(response, "UNAUTHORIZED", "Přihlášení je vyžadováno.")`.
3. `JwtAuthenticationFilter.sendUnauthorized` → tatáž utilita, kódy: `TOKEN_BLACKLISTED`, `TOKEN_EXPIRED`, `TOKEN_INVALID` (dle větve).
4. `shouldNotFilter` přepiš na přesnou shodu:
   ```java
   private static final Pattern PUBLIC_AUTH = Pattern.compile("^/api/[^/]+/auth/(login|refresh)$");
   ...
   return PUBLIC_AUTH.matcher(request.getServletPath()).matches();
   ```
   (register už neexistuje po A2 — ověř; kdyby A2 ještě neproběhlo, zastav se.)
5. FE kontrola: `grep -rn '"error"' frontend/autoservis-frontend/src` — nic nesmí parsovat starý tvar `{status,error}`. `apiFetch` na 401 tělo nečte (redirect/refresh), takže změna je bezpečná; ověř.

**Akceptační kritéria:** request bez cookie na `/api/v1/customers` vrací 401 s `Content-Type: application/problem+json` a polem `errors[0].code == "UNAUTHORIZED"`; s prošlým tokenem `TOKEN_EXPIRED`; login/refresh nadále nefiltrované (login funguje).

**Dokumentace:** docs/api.md — sekce chyb, 401 varianty; tech-dluhy.md → „Vyřešeno" (V5, V6).

---

### C1 · FE: ApiError místo `throw new Error(text)` (S1)

**Proč:** `apiFetch` parsuje JSON **před** kontrolou `response.ok` — ne-JSON chyba (HTML 502 z proxy, prázdné tělo) shodí `SyntaxError` a ztratí status. Každý volající pak dělá křehké `JSON.parse(err.message)`.

**Soubory:** `frontend/autoservis-frontend/src/api/api.js` + volající (najdeš grepem).

**Postup:**
1. Do `api.js` přidej a exportuj:
   ```js
   export class ApiError extends Error {
       /** @param {number} status @param {object|null} problem RFC 9457 tělo @param {string} rawText */
       constructor(status, problem, rawText) {
           super(rawText || `HTTP ${status}`);   // message = raw text kvůli zpětné kompatibilitě
           this.name = 'ApiError';
           this.status = status;
           this.problem = problem;               // { title, detail, errors: [...] } nebo null
       }
   }
   ```
2. Přepiš závěr `apiFetch`:
   ```js
   const text = await response.text();
   if (!response.ok) {
       let problem = null;
       try { problem = text ? JSON.parse(text) : null; } catch { /* ne-JSON tělo (proxy, HTML) */ }
       throw new ApiError(response.status, problem, text);
   }
   if (!text) return null;
   return JSON.parse(text);
   ```
   (Kontrola 204 zůstává před tím.)
3. Volající: `grep -rn "JSON.parse(err" frontend/autoservis-frontend/src` a `grep -rn "JSON.parse(e" ...`. Každé místo přepiš na `err.problem?.detail ?? 'obecná hláška'` / `err.problem?.errors` — **zachovej stávající texty fallbacků**. Díky `message = rawText` nic nespadne, i kdybys nějaké místo minul, ale projdi všechna.

**Co NEDĚLAT:** neměnit signatury `api.get/post/put/delete/upload`; nepřidávat axios ani jinou závislost.

**Akceptační kritéria:** `grep -rn "JSON.parse(err" frontend/...` nenajde nic; ruční test — vypnutý backend + kliknutí na seznam zobrazí srozumitelnou chybu (ne SyntaxError v konzoli); validační chyba formuláře (např. prázdné povinné pole zákazníka) se zobrazuje stejně jako dřív.

**Dokumentace:** docs/frontend.md — popsat ApiError kontrakt; tech-dluhy.md → „Vyřešeno" (S1).

---

### C2 · FE: ConfirmDialog + useAlert i v posledních třech místech (TD-34)

**Soubory:** `InvoiceTable.jsx`, `InvoicesPageDetail.jsx`, `OrderItemsWrapper.jsx` (handleItemDelete).
**Postup:** Vzor vezmi z `CustomerTable.jsx` (ConfirmDialog stav + `useAlert()`). V každém z tří souborů nahraď `window.confirm(...)` za ConfirmDialog (stav `confirmState` s payloadem akce) a `alert(...)` za `addAlert(msg, 'danger'|'success')`. Texty potvrzení zachovej.
**Co NEDĚLAT:** neměnit business logiku mazání/stornování; nevytvářet nový dialogový komponent.
**Akceptační kritéria:** `grep -rn "window.confirm\|window.alert\|[^.]alert(" frontend/autoservis-frontend/src/components frontend/autoservis-frontend/src/pages` — žádný nativní dialog; ruční test: storno faktury a smazání položky zakázky zobrazí ConfirmDialog a toast.
**Dokumentace:** tech-dluhy.md → „Vyřešeno" (TD-34).

---

### C3 · FE: drobnosti — verze, removeAlert, typeDefs, dead code (TD-37, TD-38, TD-39, TD-36)

**Postup (čtyři nezávislé kroky v jednom úkolu):**
1. **TD-37:** `frontend/autoservis-frontend/package.json` — zjisti nainstalované verze `npm ls @mui/material @emotion/styled @emotion/react` a zapiš je místo `"latest"` s `^`. Spusť `npm install` (aktualizace lockfile) a `npm run build`.
2. **TD-38:** `src/context/AlertContext.jsx` — `removeAlert` přepiš na funkční update: `setAlerts(prev => prev.filter(a => a.id !== id))`.
3. **TD-39:** `src/api/typeDefs/orderTypeDefs.js` — hodnoty `OrderItemType` sjednoť s backendem (LABOR/MATERIAL/OTHER_SERVICES — ověř v `src/main/java/.../model/enums/OrderItemType.java` a `format.js`).
4. **TD-36:** smaž dle seznamu v tech-dluhy.md TD-36: `css/style.css`, `customerDetail.module.css`, pravidlo `body.has-sidebar`, `console.log` v uvedených komponentách, zakomentovaný kód v `OrdersPage`, nepoužité importy, backend `templates/images/avatar.jpg`. Před smazáním každého souboru ověř grepem, že není importovaný.

**Akceptační kritéria:** `npm run build` projde; `grep -rn "latest" package.json` nic; `grep -rn "console.log" src/components src/pages` (FE) čisté; aplikace se chová beze změny (proklikat zákazníky/zakázky).
**Dokumentace:** tech-dluhy.md → „Vyřešeno" (TD-36, 37, 38, 39).

---

### C4 · FE: route guard s loading stavem (TD-40 FE)

**Proč:** Ochrana je jen reaktivní — chráněný obsah blikne, než přijde 401.

**Soubory:** nový `src/components/RequireAuth.jsx`, úprava `src/App.jsx`.

**Postup:**
1. `RequireAuth.jsx`: komponenta, která při mountu zavolá `requireAuth()` z `api/auth.js`; stavy: `checking` (vrať spinner — Bootstrap `spinner-border` vycentrovaný), `ok` (vrať `children`), redirect řeší `requireAuth` samo.
2. `App.jsx`: obal layout route: `<Route element={<RequireAuth><Layout /></RequireAuth>}>`. `/login` neobaluj.
3. Pokud jednotlivé stránky volají `requireAuth()` v `useEffect`, nech je být (duplicitní kontrola neškodí) — NEREFAKTORUJ je v tomto úkolu.

**Akceptační kritéria:** nepřihlášený uživatel na `/customers` neuvidí ani záblesk tabulky (jen spinner → login); přihlášený projde normálně; F5 na detailu funguje.
**Dokumentace:** docs/frontend.md; tech-dluhy.md → „Vyřešeno" (TD-40 FE část).

---

### C5 · FE: jeden parametrizovaný useRowActions (TD-35)

**Proč:** 5× téměř identický hook (customer/vehicle/order/supplier/warehouse/user — ověř aktuální počet).

**Postup:**
1. Přečti si všechny `src/hooks/use*RowActions.js` a vypiš rozdíly (cesty, texty, speciální akce).
2. Vytvoř `src/hooks/useRowActions.js` s parametry `{ resourcePath, apiPath, labels }` pokrývajícími průnik chování; speciality (pokud nějaký hook dělá něco navíc) ponech v tenkém wrapperu, který volá společný hook.
3. Původní hooky nahraď wrappery (zachovej názvy a signatury — komponenty se nemění!), nebo je smaž a uprav importy v komponentách — vyber méně invazivní variantu podle rozsahu rozdílů a napiš, kterou jsi zvolil a proč.

**Akceptační kritéria:** `npm run build`; ruční proklik: detail/edit/deaktivace/aktivace řádku ve všech pěti tabulkách funguje; žádná duplicitní logika mazání (grep na `deactivate` v hooks).
**Dokumentace:** docs/frontend.md; tech-dluhy.md → „Vyřešeno" (TD-35).

---

### D1 · Validátory @ValidCustomerRequest / @ValidVehicleRequest (TD-10)

**Proč:** Podmíněná povinnost polí (INDIVIDUAL → firstName+lastName; COMPANY → companyName) se dnes chytá až o DB CHECK → nesrozumitelná 422 místo 400. `GlobalExceptionHandler.CUSTOM_VALIDATOR_ANNOTATIONS` už s oběma anotacemi počítá — infrastruktura čeká.

**Soubory:** nový balíček `src/main/java/cz/palo/autoservis/validation/`, `CustomerDto.java`, `VehicleDto.java`, `messages.properties` (najdi: `grep -rn "messages" src/main/resources`).

**Postup:**
1. Vytvoř anotaci `@ValidCustomerRequest` (class-level, `@Constraint(validatedBy = CustomerRequestValidator.class)`) a validátor implementující `ConstraintValidator<ValidCustomerRequest, Object>` — pracuj přes rozhraní/reflexi NE — místo toho udělej **dvě konkrétní anotace-použití**: validátor napiš generický přes malé rozhraní, NEBO jednodušeji dva validátory (`CustomerCreateValidator` pro CreateRequest, `CustomerUpdateValidator` pro UpdateRequest). **Zvol jednodušší cestu: jeden validátor pro CreateRequest.**
   - Logika: `customerType == INDIVIDUAL` → `firstName` a `lastName` neprázdné, jinak violation s template `CUSTOMER_NAME_REQUIRED` na příslušné pole (`constraintViolationBuilder.addPropertyNode("firstName")`); `COMPANY` → `companyName` neprázdné, template `CUSTOMER_COMPANY_REQUIRED`.
   - Template = **kód chyby**, ne text (handler ho přeloží přes MessageSource) — viz `GlobalExceptionHandler` javadoc.
2. `UpdateRequest` nemá `customerType` → validaci proveď v `CustomerServiceImpl.update`: načti existující typ z DB a zkontroluj tamtéž (BusinessRuleException se stejnými kódy). Do `UpdateRequest` typ NEpřidávej (typ zákazníka je immutable).
3. Totéž pro `@ValidVehicleRequest` — nejdřív zjisti, jaká podmíněná pravidla pro vozidlo dávají smysl: podívej se do `V5__init_vehicle_schema.sql` na CHECK constrainty. Pokud žádné podmíněné pravidlo neexistuje, anotaci **nevytvářej** a jen ji vyřaď z `CUSTOM_VALIDATOR_ANNOTATIONS` s poznámkou — napiš to do shrnutí.
4. Přidej klíče do `messages.properties` (+ česká varianta, existuje-li `messages_cs.properties`).
5. Test: POST zákazníka COMPANY bez `companyName` → 400 s `errors[0].code == "CUSTOMER_COMPANY_REQUIRED"` (ne 422). Vzor testu: `GlobalExceptionHandlerTest`.

**Akceptační kritéria:** test dle 5; INDIVIDUAL bez příjmení → 400; validní požadavky procházejí beze změny.
**Dokumentace:** docs/api.md (nové kódy chyb); tech-dluhy.md → „Vyřešeno" (TD-10).

---

### D2 · Null guardy + IllegalArgumentException→400 + Boolean v UpdateRequest (TD-20+TD-23)

**Postup:**
1. `GlobalExceptionHandler`: nový handler `@ExceptionHandler(IllegalArgumentException.class)` → 400, kód `INVALID_ARGUMENT`, hláška z výjimky. Zařaď ho NAD catch-all `Exception` handler (pořadí v souboru; Spring si vybere specifičtější sám, ale drž konvenci souboru — sekce 400).
2. Null guardy: do service metod, které přijímají `Long id` a nemají guard, přidej na začátek:
   ```java
   if (id == null) { throw new IllegalArgumentException("id nesmí být null"); }
   ```
   Rozsah: `VehicleServiceImpl`, `OrderServiceImpl`, `OrderItemServiceImpl`, `InvoiceServiceImpl`, `ProductServiceImpl`, `SupplierServiceImpl`, `MileageServiceImpl`, `UserServiceImpl` — projdi veřejné metody s `Long` parametrem (id, orderId, customerId…). Vzor: `CustomerServiceImpl`.
3. TD-23: `CustomerDto.UpdateRequest` — `gdprConsent`/`marketingConsent` z `boolean` na `Boolean`; v `CustomerConverter.applyUpdate` aplikuj jen ne-null hodnoty (`if (req.getGdprConsent() != null) ...`). Zkontroluj FE — posílá formulář obě pole vždy? (`grep -n "gdprConsent" frontend -r`). Pokud ano, chování se nemění.

**Akceptační kritéria:** `./mvnw test-compile`; PUT zákazníka bez `gdprConsent` v JSON nezmění uloženou hodnotu (ruční test nebo test v `CustomerServiceTest`); volání service s null id vrací 400, ne 404.
**Dokumentace:** tech-dluhy.md → „Vyřešeno" (TD-20, TD-23).

---

### D3 · Víceslovné hledání zákazníků a zakázek (TD-18+TD-25)

**Proč:** „Jan Novák" nenajde zákazníka (Jan je v first_name, Novák v last_name) — LIKE per sloupec ani jednoslovná FTS to neumí.

**Soubory:** `CustomerMapper.xml` (searchWhere, ř. ~137), `OrderMapper.xml` (obdoba — najdi fulltext podmínku), příslušné service (tokenizace), testy.

**Postup:**
1. **Zákazníci** — v `CustomerServiceImpl` (nebo v `CustomerSearchParams`) rozděl `search` na tokeny: `search.trim().split("\\s+")` → `List<String> searchTokens` (přidej getter do params; prázdný seznam když search chybí). V XML nahraď stávající `<if test="params.search...">` blok:
   ```xml
   <if test="params.searchTokens != null and !params.searchTokens.isEmpty()">
       AND
       <foreach item="token" collection="params.searchTokens" open="(" separator=" AND " close=")">
           <bind name="tokenLike" value="'%' + token + '%'" />
           (LOWER(unaccent(c.first_name))      LIKE LOWER(unaccent(#{tokenLike}))
            OR LOWER(unaccent(c.last_name))    LIKE LOWER(unaccent(#{tokenLike}))
            OR LOWER(unaccent(c.company_name)) LIKE LOWER(unaccent(#{tokenLike})))
       </foreach>
   </if>
   ```
   Sémantika: každé slovo se musí najít aspoň v jednom sloupci. `unaccent` — ověř, že extension je dostupná v `customer` search path (používá ji FTS index z migrace V2; pokud je v jiném schématu, kvalifikuj `public.unaccent` podle toho, jak to dělají migrace).
2. **Zakázky** — stejný vzor aplikuj na fulltext podmínku v `OrderMapper.xml` (sloupce dle stávající LIKE podmínky — description, čísla, jména zákazníka…; zachovej stávající množinu sloupců, jen ji obal do foreach přes tokeny).
3. `countSearch` používá tentýž `<sql>` fragment — ověř, že se změna propsala do obou dotazů.
4. Testy (`CustomerServiceTest` rozšíření + nový pro Order): „Jan Novák" najde zákazníka Jan Novák; „novak jan" (přehozené pořadí, bez diakritiky) také; „Novák Neexistujici" nenajde nic.

**Co NEDĚLAT:** nesahat na autocomplete FTS (czech_simple) — funguje pro jedno slovo a má vlastní index; neměnit ORDER BY ani stránkování.

**Akceptační kritéria:** testy dle 4; jednoslovné hledání funguje jako dřív.
**Dokumentace:** tech-dluhy.md → „Vyřešeno" (TD-18, TD-25); docs/funkce/ pokud hledání popisuje.

---

### D4 · Deaktivace produktu se zásobou (TD-28)

**Rozhodnutí (výchozí, uživatel může před zadáním změnit):** deaktivaci produktu s `quantity_on_hand > 0` **zakázat** — 422 `PRODUCT_HAS_STOCK` s množstvím v params. („Doprodej" varianta by vyžadovala nový stav; zákaz je bezpečnější a vratný.)

**Soubory:** `src/main/java/cz/palo/autoservis/service/impl/ProductServiceImpl.java` (metoda `deactivate`), test.

**Postup:** V `deactivate` načti produkt (`findById`/`findByIdIncludingInactive` — dle vzoru v témže service), a když `quantityOnHand > 0` (BigDecimal `compareTo`), vyhoď `BusinessRuleException("PRODUCT_HAS_STOCK", "quantityOnHand", "Produkt má zásobu na skladě (" + qty + ") — nelze deaktivovat.", Map.of("quantityOnHand", qty))`. Test: produkt se zásobou → 422; s nulovou zásobou → deaktivace projde.

**Akceptační kritéria + dokumentace:** test; docs/api.md (nový kód); tech-dluhy.md → „Vyřešeno" (TD-28). FE hlášku zobrazí z `detail` automaticky.

---

### D5 · REST kosmetika: Location header + přejmenování XML (TD-12+TD-32)

**Postup:**
1. **TD-12:** najdi controllery vracející `ResponseEntity.status(HttpStatus.CREATED)` (`grep -rn "HttpStatus.CREATED" src/main/java`) a přepiš na `ResponseEntity.created(URI.create("/api/v1/<resource>/" + created.getId())).body(created)`. Pozor: cestu sestav podle skutečného mappingu controlleru.
2. **TD-32:** přejmenuj `src/main/resources/mapper/warehouse/ProductMapper.xml` na `WarehouseMapper.xml` (soubor odpovídá Java rozhraní `WarehouseMapper` — namespace uvnitř se nemění!). Ověř, že žádná konfigurace neodkazuje na název souboru (mapper-locations je wildcard). Smaž prázdný placeholder `ContactPersonMapper.java`, pokud je stále prázdný a nepoužívaný (`grep -rn "ContactPersonMapper" src/`) — pokud používaný, nech být a nahlas.

**Akceptační kritéria:** `./mvnw test-compile` + start aplikace (MyBatis při startu zvaliduje mappery); POST nového zákazníka vrací header `Location`; tech-dluhy.md → „Vyřešeno" (TD-12, TD-32).

---

## 6. Po dokončení všech fází

1. Spustit celou testovací sadu s Dockerem (`./mvnw test`) — vše zelené.
2. `npm run build` — bez chyb a varování.
3. Projít produkční checklist TD-33 (CORS originy, company_profile placeholder, seed hesla) — to je ruční konfigurační práce před nasazením, ne úkol pro model.
4. Zvážit zadání velkých témat mimo plán (V1 dobropisy, TD-24 role) — ta vyžadují návrhovou diskusi, ne přímé provedení.
