# Audit 8/9 — Soulad dokumentace s kódem

> Součást hloubkového auditu 2026-07-24 (commit `409d3ad`, větev `audit-one`).
> Přehled celého auditu: [00-prehled.md](00-prehled.md).
>
> **Verifikace:** klíčové nálezy ověřeny druhým nezávislým měřením — počty controllerů (19),
> XML mapperů (21 = 14 + 7 warehouse), endpoint anotací (96) a stav `api.md` ř. 39–48
> (sekce Cookies popisuje stav před opravou TD-31) potvrzeny přímo proti kódu.

Prověřeno: docs/api.md (všech 96 endpointů oběma směry), docs/databaze.md (proti všem 44 migracím kumulativně), docs/backend.md, docs/frontend.md, docs/architektura.md, docs/funkce/*.md (5 dokumentů), docs/nasazeni.md (deploy/, deploy.sh, application-prod.yaml), docs/roadmapa.md + plan-*.md, docs/claude-infrastruktura.md (.claude/), CLAUDE.md, README.md.

---

## Nesoulady

### VYSOKÁ (zavádějící tvrzení, povede k chybě)

**V1. api.md ř. 39–48 — sekce Cookies u Auth popisuje již opravené chyby jako aktuální stav.**
- **Tvrdí:** cookie `jwt` maxAge = 60 min; `jwt_refresh` maxAge = 30 dní; path `/api/v1/auth/refresh` „natvrdo v1"; `secure=false` jako fixní vlastnost; blok „⚠️ Známé nesoulady" uvádí maxAge≠expiration a „secure=false i pro prod".
- **Kód:** `AuthController.java:177–192` (`setTokenCookies`) — maxAge `jwt` = `Duration.ofMillis(jwt.expiration)` → **8 h dev / 15 min prod** (`application.yaml:132`, `application-prod.yaml:17`); maxAge `jwt_refresh` = `jwt.refresh-expiration` → **7 dní**; path = `"/api/" + version + "/auth/refresh"` (dynamické, `AuthController.java:190`); `secure` = `@Value("${jwt.cookie-secure}")` → `false` dev / **`true` prod** (`application-prod.yaml:18`). Javadoc metody výslovně říká, že hodnoty jsou odvozené z konfigurace.
- **Dopad:** čtenář dostane nesprávný obraz o životnosti tokenů a bezpečnosti v produkci; „známé dluhy" už neexistují.
- **Oprava:** přepsat tabulku Cookies na hodnoty z konfigurace (`jwt` = `jwt.expiration` 8 h/15 min; `jwt_refresh` = `jwt.refresh-expiration` 7 dní; path dynamický `/api/{version}/auth/refresh`; `secure` = `jwt.cookie-secure` false dev / true prod) a blok „⚠️ Známé nesoulady" odstranit (případně přesunout do historie s poznámkou „vyřešeno"; zkontrolovat i navazující zmínku v backend.md ř. 75 a tech-dluhy.md).

### STŘEDNÍ (zastaralá fakta)

**S1. api.md ř. 3 — hlavička „16 tříd, 78 endpointů".** Realita: **19 controllerů, 96 endpointů** (11 v `controller/`, 7 v `controller/warehouse/`, `security/controller/AuthController`). Oprava: přepsat na skutečná čísla.

**S2. api.md ř. 338–359 — souhrnná tabulka počtů nesedí v 5 řádcích a v součtu.** AuthController 6 → **5**; ProductController 7 → **9**; GoodsReceiptImportController 1 → **2**; GoodsReceiptReviewController 7 → **8**; úplně chybí **StockTakeController (6)** a **StockValuationController (1)** — přestože oba mají v api.md vlastní sekce (ř. 187–216); „Celkem 86" → **96**. Oprava: doplnit řádky a přepočítat.

**S3. api.md ř. 241 — „jediný endpoint s rolovou autorizací v celém API".** Realita: `@PreAuthorize` má i `POST /receipts/import-isdoc` (`GoodsReceiptImportController.java:81`), celý `GoodsReceiptReviewController` (`:33`) a celý `UserController` (`:30`) — dokument si navíc protiřečí na ř. 247 a 278. Oprava: přeformulovat na výčet tří míst s rolovou autorizací.

**S4. api.md ř. 234+258 vs. kód — `import-isdoc` je zařazen do sekce GoodsReceiptReviewController, ale žije v `GoodsReceiptImportController.java:80`.** Oprava: přesunout řádek do sekce AI importu (souvisí se S2).

**S5. backend.md ř. 39 + architektura.md ř. 99 — „13 + 4 warehouse XML" mapperů.** Realita: **14 + 7** (`src/main/resources/mapper/`): v tabulce backend.md chybí `RegistrySnapshotMapper.xml` (root) a `ProductMatchingMapper.xml`, `ReceiptReviewMapper.xml`, `StockTakeMapper.xml` (warehouse). Oprava: doplnit 4 řádky do tabulky a opravit počty na obou místech.

**S6. architektura.md ř. 91 — „13 REST controllerů (+ warehouse/)".** Realita: 11 v `controller/` + 7 ve `warehouse/` (+ AuthController v `security/`). Oprava: „11 REST controllerů + 7 warehouse (AuthController v security/)".

**S7. backend.md ř. 84 — „budoucí ISDOC/ruční formulář sem jen přidají adaptér".** Obojí je hotové: `IsdocParser.java`, `ReceiptReviewServiceImpl.createManualDraft` (`:92–148`). Oprava: přeformulovat na minulý čas („ISDOC i ruční formulář to potvrdily — přidaly jen adaptér").

**S8. backend.md ř. 72 — „rolová autorizace na dvou místech".** Realita: tři (import + import-isdoc, celý ReviewController, celý UserController) — viz S3. Oprava: sladit výčet.

**S9. backend.md ř. 21 — výčet služeb neúplný.** Chybí `ReceiptReviewService`, `StockTakeService`, `VehicleRegistryService` (a v „samostatných komponentách" `IsdocParser`, `ProductMatchingService`); impl je 16, vyjmenováno 13; strom `service/` má celkem 39 souborů, ne „~23 tříd". Oprava: doplnit výčet a aktualizovat odhad.

**S10. databaze.md ř. 375 — hlavička „## 6. Schéma warehouse (V18, V21, V28–V30)".** Warehouse zásadně mění i **V39–V44** (draft workflow, supplier_products, DL reference, v_stock_valuation, storno, inventura) — obsah sekce je popisuje správně, jen souhrn v nadpisu je zastaralý. Oprava: „(V18, V21, V27–V30, V39–V44)".

**S11. README.md ř. 34 — „Flyway versioned migrations (currently V1–V37)".** Realita: **V1–V44**. Oprava: aktualizovat (nebo číslo vypustit, ať nestárne).

**S12. frontend.md §2 strom — `api/` uvádí 3 soubory, reálně 6.** Chybí `customerPayload.js`, `formUtils.js` a hlavně **`units.js`** (`ALLOWED_UNITS` — FE zrcadlo `warehouse.import.allowed-units`, používané ve `WarehouseForm.jsx` a `ReceiptDraftLinesTable.jsx`), který není zmíněn nikde v dokumentu. Oprava: doplnit strom + odstavec o číselníku jednotek do §4.

**S13. frontend.md §3 routing tabulka — chybí routy `/warehouse/low-stock`, `/warehouse/stock-takes`, `/warehouse/stock-takes/:id`** (`App.jsx:65–67`). Oprava: doplnit tři řádky.

**S14. docs/funkce/import-prijemek.md ř. 15 — ruční formulář „(plánováno, fáze 6)".** Je implementován (`POST /warehouse/receipts`, `GoodsReceiptReviewController.java:45`, `ReceiptReviewServiceImpl.java:92–148`); odporuje i vlastnímu záhlaví dokumentu (ř. 3) a `sklad-pohyby.md:79`. Oprava: „(hotovo)".

### NÍZKÁ (kosmetika, drobné drifty)

| # | Místo | Tvrdí | Realita | Oprava |
|---|---|---|---|---|
| N1 | api.md ř. 73 | parametr `orderBy` u `/vehicles` | `VehicleSearchParams` žádné `orderBy` nemá; řazení jde přes zděděné `sortBy` | `orderBy` → `sortBy` |
| N2 | api.md ř. 266 | řádek `POST /receipts/{id}/reject` | je vložen **za** odstavec ISDOC, mimo tabulku — markdown se nevyrenderuje jako tabulka | přesunout řádek do tabulky (ř. 249–258) |
| N3 | backend.md ř. 16 | „12 vnořených handlerů" v `PgEnumTypeHandler` | **14** (`grep "extends PgEnumTypeHandler"`) | opravit počet |
| N4 | backend.md ř. 30 | „16 ručních konvertorů" | **17** souborů v `model/converter/` (§7.2 sám správně říká 17) | opravit počet |
| N5 | backend.md ř. 127 | „69 testovacích tříd" | 71 souborů `*Test*.java` (vč. `AbstractIntegrationTest`) → **70 testovacích tříd** | opravit počet |
| N6 | databaze.md ř. 73 | token_blacklist hashuje „od V4" | V4 je `add_customer_number_sequence` — „V4" zde označuje nález analýzy, ne Flyway migraci | přeformulovat bez „V4" |
| N7 | architektura.md ř. 23–31 | tabulka stacku | odstavec R-1 (ř. 25–29) je vložen doprostřed tabulky — řádky „Testy"/„Build" z ní vypadnou | přesunout odstavec pod tabulku |
| N8 | frontend.md §10 | „hlídá osm pravidel: …" | výčet má jen 7 položek; 8. (createPortal, rule 3b `check-ui.mjs:128–136`) chybí, byť je popsané v §10.4 | doplnit 8. položku |
| N9 | frontend.md §3 ř. 39 | „vše ostatní vnořeno do RequireAuth" | route `/` (redirect na `/dashboard`) je mimo guard (`App.jsx:95`) — funkčně neškodné | upřesnit |
| N10 | frontend.md §2 ř. 27 | main.jsx „import Bootstrap CSS+JS" | importuje i `index.css`, `css/reset.css`, `css/help.css` | doplnit |
| N11 | roadmapa.md ř. 35 | „GoodsReceiptReviewController, 6 endpoints" | 8 endpointů (přibyl `cancel`, `reject`) | opravit |
| N12 | roadmapa.md ř. 71 | „npm run check (7 pravidel)" | `check-ui.mjs` má 5 hlavních / 8 dílčích kontrol (sám tiskne „8 pravidel") | sladit číslo s frontend.md §10 |
| N13 | plan-oprav.md ř. 7 | „stav značit zaškrtnutím checkboxu v §3" | v souboru žádný checkbox není — z plánu nejde poznat, co je hotové (namátkou B1/A3/B5 hotové jsou) | doplnit checkboxy nebo instrukci přepsat |
| N14 | claude-infrastruktura.md ř. 14–23 | strom `.claude/` | neuvádí existující `launch.json` | doplnit |
| N15 | claude-infrastruktura.md ř. 17 | `settings.local.json` „(neexistuje, dokud ho nevytvoříš)" | soubor existuje (osobní allow pravidla) | závorku odstranit |
| N16 | funkce/import-prijemek.md ř. 3 | „7 fází … (V39–V41)" | rework sahá po **V43/V44**; výčet má 6 položek; chybí ISDOC/foto/storno | rozšířit rozsah i výčet |

---

## Chybějící dokumentace (v kódu existuje, nikde nepopsáno)

1. **`frontend/.../src/api/units.js`** — uzavřený číselník jednotek zrcadlící backend (Z-4); ve frontend.md zcela chybí (viz S12).
2. **Review komponenty příjemek** — `ReceiptDraftHeaderForm.jsx` (92 ř.), `ReceiptDraftLinesTable.jsx` (156 ř.), `ReceiptItemsTable.jsx` (121 ř.), `FieldStateBadge.jsx` + mapy `FIELD_STATE_META`/`RECEIPT_CHECK_LABELS` ve `format.js` — cca 400 řádků UI bez zmínky ve frontend.md §5.
3. **4 XML mappery** (`RegistrySnapshotMapper`, `ProductMatchingMapper`, `ReceiptReviewMapper`, `StockTakeMapper`) chybí v přehledové tabulce backend.md §2 (viz S5).
4. **3 služby** (`ReceiptReviewService`, `StockTakeService`, `VehicleRegistryService`) chybí ve výčtu backend.md §1 (viz S9).
5. **README.md (EN)** — Features/Architecture nezmiňují novější hotové moduly: inventuru, ocenění zásob, ISDOC import, foto/sken import, storno příjemky, STK registr (dataovozidlech.cz), správu uživatelů. Pro portfolio dokument škoda.
6. Mimo dokumentaci, ale nalezeno při auditu (kandidáti na úklid): zastaralý komentář `V39__receipt_draft_workflow.sql:44` („ISDOC — reserved", už implementováno) a zastaralý komentář + `START WITH 4` v `V4__add_customer_number_sequence.sql:15` (seed V3 zakládá 10 zákazníků, komentář mluví o 3) — migrace se nemění, ale stojí za evidenci v tech-dluhy.md.

---

## Stav dokumentace celkově

**Známka: velmi dobrá, s jedním systémovým slabým místem.** Pravidlo z CLAUDE.md „po každé změně aktualizuj dokument" se **věcně daří dodržovat** — popisy chování, stavové automaty, business pravidla, chybové kódy, DB schéma i deploy odpovídají kódu na úroveň detailů:

- **databaze.md** je téměř bezchybná (všech 44 migrací, tabulky, ENUMy, 22 triggerů, 7 views, seedy — 2 drobnosti).
- **nasazeni.md** je plně v souladu (deploy.sh bod po bodu, systemd, sudoers, env, prod checklist).
- **claude-infrastruktura.md**, **funkce/*.md** (kromě záhlaví import-prijemek) a **CLAUDE.md** sedí; u CLAUDE.md nenalezen žádný nesoulad (stack, příkazy, cesty, mapa dokumentace, hook — vše ověřeno).
- **frontend.md** je věrný do detailu chování (apiFetch, refresh, format.js jako jediné místo formátování — ověřeno).

**Systémové slabé místo: souhrnná čísla a přehledové tabulky.** Prakticky všechny nalezené střední nesoulady jsou stejného druhu — počty a výčty, které při postupných změnách nikdo nepřepočítal (počet endpointů/controllerů/mapperů/konvertorů/handlerů/pravidel, rozsahy migrací v nadpisech, README „V1–V37"). Sekce s detaily se aktualizují, souhrny zaostávají. Doporučení: buď souhrnná čísla generovat/kontrolovat skriptem (obdoba grep křížové kontroly ze skillu `novy-endpoint`), nebo je z dokumentů vypustit tam, kde nenesou hodnotu.

**Jediný vysoký nález** (api.md sekce Cookies) je opačný případ — kód se opravil (plán oprav B1), ale dokumentovaný „známý dluh" zůstal, takže dokumentace nyní hlásí neexistující bezpečnostní problém a špatné hodnoty životnosti tokenů. Doporučeno opravit přednostně, včetně navazujících zmínek v backend.md ř. 75 a tech-dluhy.md.
