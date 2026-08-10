# Hloubková analýza skladové domény — 2026-07

*Rozsah: sklad, zásoby, příjem, výdej, pohyby, import dokladů. Pouze analýza a návrhy — žádné změny kódu.
Podklady: docs (architektura, databáze, funkce/průvodce import-prijemek, analyza-2026-07, tech-dluhy, roadmapa),
git historie (V18 `69ddd4f`, V27–V30 `fc2bb24`, V39–V41 `9ca336d`, opravy K6 `7729c53` a V2 `c424dec`),
kód service vrstvy + mappery + FE, webová rešerše (zdroje u Úkolu 2).
Pevný požadavek zadání: načítání zboží z dokumentů (AI extrakce s draft workflow) musí v nějaké formě zůstat.*

---

## Úkol 1 — Archeologie a mapa současného stavu

### 1.1 Vývojové vlny skladu (z git historie)

| Vlna | Migrace | Commit | Co přinesla |
|---|---|---|---|
| 1 | V18 | `69ddd4f` | celé jádro: suppliers, products, goods_receipts, šarže, **pohybový ledger + trigger**, 2 views |
| 2 | V21 | `69ddd4f` | katalogová pole produktu (manufacturer, sale_price, min_stock_level) |
| 3 | V27–V30 | `fc2bb24` | vazba položek zakázky na šarže, ISSUE_RETURN, normalizace identity dodavatele |
| 4 | V39–V41 | `9ca336d` | **přestavba importu na draft pipeline** (kanonický draft, confirm/reject, supplier_products, dedup DL↔faktura) |
| opravy | — | `7729c53`, `c424dec` | K6 (FOR UPDATE + agregace per šarže při výdeji), V2 (zámek položek po fakturaci) |

Klíčová architektonická rozhodnutí jsou doložena v [architektura.md](architektura.md) (tabulka „Klíčová rozhodnutí"): ledger kvůli auditovatelnosti, šarže jako nositel nákupní ceny kvůli dohledatelnosti položka→šarže→faktura, dodavatel vzniká jen importem (jediná brána = konzistence). Původní zdůvodnění návrhu ledgeru je v `docs/archiv/MODUL-WAREHOUSE.md` (§3: „doklad, produkt a pohyb jsou tři různé věci"; §4.5: ledger je append-only účetní deník).

### 1.2 Mapa procesů — co existuje a kde

#### A) Příjem — AI import PDF
[WarehouseImportServiceImpl.java:49](../src/main/java/cz/palo/autoservis/service/impl/WarehouseImportServiceImpl.java:49) `importFromPdf`:
extrakce (`PdfDocumentExtractionService`, tracked fields VERBATIM/DERIVED/ABSENT) → `DraftAssembler.assemble` (sazby, dopočty, defaulty z `warehouse.import.defaults`) → `DraftVerificationService.verify` ([DraftVerificationService.java:38](../src/main/java/cz/palo/autoservis/service/DraftVerificationService.java:38), 7 deterministických kontrol) → `ProductMatchingService.matchLines` → `matchDeliveryNoteRefs` → **jediný INSERT** do `goods_receipts` (PENDING_REVIEW + JSONB draft).

Pojistky a jejich PROČ:
- **Žádná materializace při importu** — invariant přestavby V39; reakce na vadu původní pipeline „sklad rostl před kontrolou" ([funkce/import-prijemek.md](funkce/import-prijemek.md) §Proč přestavba).
- **409 `DUPLICATE_IMPORT`** jen při napárovaném dodavateli ([WarehouseImportServiceImpl.java:71](../src/main/java/cz/palo/autoservis/service/impl/WarehouseImportServiceImpl.java:71)); podruhé se kontroluje při confirm. Idempotenci drží partial unique index `uq_receipt_supplier_docno … WHERE status <> 'REJECTED'` (V39) — zamítnutý doklad uvolní číslo bez mazání auditní stopy.
- **Typ dokladu volí uživatel** (INVOICE/DELIVERY_NOTE) — u financí se nespoléhá na klasifikaci modelem.
- „Jistotu určuje kód, ne model" — LLM nemá kalibrovanou confidence, přiznává jen původ hodnoty; na VERIFIED povyšuje výhradně deterministická verifikace (pruvodce §5).

#### B) Příjem — ruční draft
[ReceiptReviewServiceImpl.java:93](../src/main/java/cz/palo/autoservis/service/impl/ReceiptReviewServiceImpl.java:93) `createManualDraft`: prázdný draft (`source_channel=MANUAL`), táž kontrolní obrazovka, táž verifikace a completeness gate. Payoff kanonického draftu — ruční cesta není druhá větev kódu (pruvodce §12).

#### C) Kontrola a potvrzení
- `PUT /{id}/draft` ([ReceiptReviewServiceImpl.java:174](../src/main/java/cz/palo/autoservis/service/impl/ReceiptReviewServiceImpl.java:174)): přečíslování pozic, `fillDerivedValues` (doplňuje jen ABSENT, nepřepisuje), re-verifikace, re-párování (CONFIRMED volby člověka kaskáda nepřepisuje), sync hlavičkové projekce. Souběžná editace = last-write-wins (vědomý kompromis, pruvodce §15).
- `POST /{id}/confirm` ([ReceiptReviewServiceImpl.java:226](../src/main/java/cz/palo/autoservis/service/impl/ReceiptReviewServiceImpl.java:226)) — **jediné místo vzniku skladových dat**:
  1. completeness gate `validateCompleteness` (:376) — hlavička, ≥1 ITEM, katalogové číslo povinné (sku NOT NULL + identita párování), žádné nevyřešené SUGGESTED ani DL reference; chyby najednou (`RECEIPT_INCOMPLETE` + params);
  2. `resolveSupplier` (:450) — napárovaný, nebo **teprve teď INSERT** dodavatele;
  3. re-check duplicity s `excludeReceiptId`;
  4. per ITEM: `resolveProduct` (:475; pojistka `findProductIdBySku` proti duplicitě sku, `stripBrandPrefix` heuristika :504) → **upsert `supplier_products`** (samoučení) → INSERT šarže (`quantity_remaining = quantity_received`) → INSERT pohybu RECEIPT — stav navýší DB trigger;
  5. guarded UPDATE `WHERE status='PENDING_REVIEW'`, 0 řádků → 409 `RECEIPT_ALREADY_PROCESSED` — optimistická ochrana proti souběhu bez zámků (pruvodce §9).
- `POST /{id}/reject` (:334): jen stavový přechod; nic nevzniklo, není co stornovat.

#### D) Identita produktu a samoučící párování
V40: `manufacturer_part_number` + generovaný `part_number_normalized` (normalizaci vlastní DB — nejde rozejít s aplikací) + převodník `supplier_products` (ERP vzor supplier item cross-reference). Kaskáda [ProductMatchingService.java:40](../src/main/java/cz/palo/autoservis/service/ProductMatchingService.java:40): (1) převodník → AUTO, (2) normalizované číslo dílu → SUGGESTED, (3) pg_trgm podobnost názvu → SUGGESTED, (4) NONE → nová karta. PROČ prefix-parsing nikdy nevede na AUTO: špatný odhad prefixu smí stát jeden klik, nikdy tichou záměnu dílu (:26). Potvrzené párování upsertuje confirm — příště krok 1 automaticky (Rossum-style feedback loop).

#### E) Dedup DL ↔ faktura
V41 `receipt_delivery_note_refs` (LKQ vzor: souhrnná faktura opakuje položky již přijatého DL). Extrakce značí `DELIVERY_NOTE_GROUP`, párování ([DraftVerificationService.java:191](../src/main/java/cz/palo/autoservis/service/DraftVerificationService.java:191)) dohledá přijaté DL, člověk rozhoduje LINKED (řádky se nematerializují) / RESTOCKED; nerozhodnutá reference blokuje confirm. Fakticky jde o **lehký 2-way match** (DL↔faktura) bez objednávkové nohy.

#### F) Šarže jako nositel ceny
`goods_receipt_items`: `unit_price_excl_vat`, `quantity_received/remaining` (CHECK ≥ 0 jako poslední pojistka), provenance view `v_batch_provenance`. Výdej vždy míří na konkrétní šarži → každý kus na zakázce je dohledatelný k faktuře dodavatele. To je fakticky oceňování **skutečnými pořizovacími cenami** (specific identification) — viz Úkol 2.

#### G) Pohybový ledger + trigger
V18: `stock_movements` append-only; `chk_movement_sign` (V18:252, finální po V29) váže znaménko na typ; trigger `fn_apply_stock_movement` (V18:283) přičítá do `products.quantity_on_hand` a (mimo RECEIPT) do `quantity_remaining` šarže. Kvantita se odvozuje výhradně z ledgeru — aplikace ji nikdy nezapisuje ([WarehouseMapper.xml:134](../src/main/resources/mapper/warehouse/WarehouseMapper.xml:134) komentář „intentionally omitted").

#### H) Výdej do zakázky
[OrderItemServiceImpl.java:122](../src/main/java/cz/palo/autoservis/service/impl/OrderItemServiceImpl.java:122) `importFromReceipt`: šarže se čtou `FOR UPDATE` ([GoodsReceiptMapper.xml:110](../src/main/resources/mapper/warehouse/GoodsReceiptMapper.xml:110)) a požadavky se **agregují per šarže** (K6 fix, `7729c53`) → čistá 422 `QUANTITY_EXCEEDS_REMAINING` i při souběhu/duplicitě. Vybírat lze jen z `CONFIRMED` příjemek se `quantity_remaining > 0` ([GoodsReceiptMapper.xml:60](../src/main/resources/mapper/warehouse/GoodsReceiptMapper.xml:60)). Prodejní cena: `product.sale_price`, fallback nákupní cena šarže (:201). Mutace položek blokuje `requireOrderNotInvoiced` (V2 fix, `c424dec`). **Šarži vybírá člověk ručně** — žádný FIFO předvýběr.

#### I) Vratka výdeje (storno položky)
[OrderItemServiceImpl.java:255](../src/main/java/cz/palo/autoservis/service/impl/OrderItemServiceImpl.java:255) `delete`: má-li položka `goods_receipt_item_id`, vloží se `ISSUE_RETURN` (kladný, proti téže šarži — V28/V29) a položka se smaže. Zboží se vrací do původní šarže za původní cenu — čisté.

#### J) Ochrana konzistence katalogu
`ProductServiceImpl.deactivate` ([ProductServiceImpl.java:124](../src/main/java/cz/palo/autoservis/service/impl/ProductServiceImpl.java:124)): produkt se zásobou nelze deaktivovat (TD-28, 422 `PRODUCT_HAS_STOCK`).

### 1.3 Co CHYBÍ nebo je slepé místo

| # | Slepé místo | Doklad v kódu | Závažnost |
|---|---|---|---|
| S-1 | **ADJUSTMENT, RETURN, WRITE_OFF jsou mrtvé typy**: existují v ENUM ([MovementType.java:8](../src/main/java/cz/palo/autoservis/model/domain/warehouse/MovementType.java:8)), v CHECK constraintu, ve FE labelech (`format.js` MOVEMENT_TYPE_LABELS) — ale žádný service, endpoint ani UI je nikdy nevytvoří. Sloupce `return_reason` a `credit_note_number` se jen SELECTují ([WarehouseMapper.xml:121](../src/main/resources/mapper/warehouse/WarehouseMapper.xml:121)), nikdy neplní. | grep `MovementType.` v service: jen RECEIPT, ISSUE, ISSUE_RETURN | vysoká — bez korekce je jediná cesta k opravě stavu ruční SQL |
| S-2 | **Inventura neexistuje** jako proces (soupis, zadání skutečných stavů, protokol, generování korekcí). ADJUSTMENT je připraven, ale viz S-1. | — | vysoká (zákonná povinnost inventarizace, §29–30 zákona o účetnictví) |
| S-3 | **Storno potvrzené příjemky nemožné**: reject funguje jen v PENDING_REVIEW; po CONFIRMED (špatné množství, omylem potvrzeno) neexistuje kompenzační proces. | `requirePendingReview` ([ReceiptReviewServiceImpl.java:357](../src/main/java/cz/palo/autoservis/service/impl/ReceiptReviewServiceImpl.java:357)) | vysoká |
| S-4 | **Vratka dodavateli** (RETURN + přijatý dobropis): DB připravena (return_reason, credit_note_number, archiv §4.5 ji popisuje), proces a UI nikde. Souvisí s V1 z [analyza-2026-07.md](analyza-2026-07.md) (dobropisy vydané). | S-1 | střední |
| S-5 | **Ocenění zásob a účetní pohled**: hodnota skladu se nikde nepočítá (Σ quantity_remaining × unit_price_excl_vat je na jeden SELECT, ale neexistuje view ani endpoint); žádná obrátkovost, žádný reporting. Views `v_stock_on_hand`/`v_batch_provenance` (V18) **aplikace vůbec nepoužívá** (grep bez zásahu — jen ad-hoc SQL nástroj). | — | střední |
| S-6 | **Výdej bez FIFO podpory**: šarže vybírá člověk; nic nenapovídá „ber nejstarší". U dílů s více šaržemi za různé ceny je na uživateli, aby nevybíral nahodile. | H) výše | střední |
| S-7 | **Rezervace zboží na zakázku** neexistuje — výdej proběhne okamžitě při přidání položky. Pro jednoho mechanika ok (výdej = de facto rezervace), pro objednané-nedodané díly nic. | — | nízká–střední |
| S-8 | **Minimální zásoby → objednávání**: `min_stock_level` + filtr lowStockOnly ([WarehouseMapper.xml:56](../src/main/resources/mapper/warehouse/WarehouseMapper.xml:56)) existují, ale žádný návrh objednávky, žádná vazba na dodavatele (ač `supplier_products` zná posledního dodavatele i cenu). | — | nízká |
| S-9 | **Objednávky dodavateli (PO) neexistují** → žádný 3-way match; `order_number` na příjemce je jen opsaný text z dokladu. | — | nízká (vědomě mimo scope) |
| S-10 | **Měrné jednotky**: volný text `unit VARCHAR(20)`, default „ks"; žádný číselník, žádné převody (litr/balení). | — | nízká |
| S-11 | **Měny**: `currency` se ukládá (default CZK), ale nikde se nekontroluje ani nepřepočítává — EUR faktura by potvrzením založila šarže s cenami v EUR, které by se dál tvářily jako CZK (nákupní cena položky zakázky, marže). | DraftAssembler:143 jen DEFAULTED | **střední — tichá datová vada** |
| S-12 | **Množstevní/rámcové slevy**: nic; `supplier_products.last_unit_price_excl_vat` je jen informativní snapshot. | — | nízká |
| S-13 | **Výdej mimo zakázku** (interní spotřeba, dílna): neexistuje — každý ISSUE musí mít zakázku. | ISSUE vzniká jen v importFromReceipt | nízká–střední |
| S-14 | Retence REJECTED draftů (PDF v BYTEA) nerozhodnuta; souběžná editace draftu last-write-wins. | roadmapa §2.1, pruvodce §15 | nízká (evidováno) |

---

## Úkol 2 — Rešerše osvědčených řešení

### 2.1 Skladové modely open-source ERP

**ERPNext** staví na dvouvrstvé architektuře: append-only **Stock Ledger Entry** + agregační **Bin** (actual_qty, valuation_rate) — každý pohyb je immutable záznam, agregát se udržuje pro výkon ([ERPNext docs](https://docs.frappe.io/erpnext/fifo-and-moving-average), [DeepWiki analýza](https://deepwiki.com/frappe/erpnext/5-inventory-management)). *Přenositelnost: to je přesně vzor `stock_movements` + `products.quantity_on_hand` — současný návrh je validován zavedeným systémem; není co měnit.* ERPNext u FIFO drží frontu vrstev [množství, cena] per produkt — naše šarže jsou totéž, jen trvanlivější (mají identitu a provenience).

**Odoo** odděluje pohyby (`stock.move`) od oceňovacích vrstev (`stock.valuation.layer` — jedna vrstva per příjem, čerpá se od nejstarší) a podporuje Standard/AVCO/FIFO ([Odoo 18 docs](https://www.odoo.com/documentation/18.0/applications/inventory_and_mrp/inventory/product_management/inventory_valuation/inventory_valuation_config.html), [cheat sheet](https://www.odoo.com/documentation/19.0/applications/inventory_and_mrp/inventory/inventory_valuation/cheat_sheet.html)). *Přenositelnost: `goods_receipt_items` je de facto valuation layer (cena + zbývající množství). Chybí jen agregační pohled (hodnota skladu) a volitelný FIFO výběr vrstvy. Účetní zaúčtování (journal entries) je mimo scope — aplikace nevede účetnictví.*

**SAP MM** má číslované pohybové typy (101 příjem, 102 = jeho storno) — storno je **opačný pohyb, nikdy smazání** ([Spend Wizard](https://spendwizard.com/sap-tutorial/what-is-a-movement-type-in-sap/)); a 3-way match objednávka–příjemka–faktura s konfigurovatelnými tolerancemi (OMR6), mimo toleranci se faktura blokuje k ručnímu uvolnění ([Ramp: 3-way match v SAP](https://ramp.com/blog/sap-3-way-match), [Doxis](https://www.doxis.com/en/blog/goods-receipt-checks)). *Přenositelnost: (a) vzor „storno = kompenzační pohyb" je přímo návod pro S-3; (b) plný 3-way match je pro servis bez PO procesu overkill — ale dedup DL↔faktura V41 je korektní „2-way match light" a tolerance 0.05 v `DraftVerificationService` je miniaturou tolerančních klíčů. Vědomě nezavádět PO jen kvůli matchi.*

### 2.2 Oceňování zásob v ČR

Legislativa (§ 25 zákona č. 563/1991 Sb., § 49 vyhlášky 500/2002 Sb., ČÚS 015) povoluje: **skutečné pořizovací ceny, FIFO, vážený aritmetický průměr** (přepočet min. 1× měsíčně); **LIFO je zakázáno**. Metody nelze míchat v rámci jednoho analytického účtu ([Ježek software](https://www.jezeksw.cz/aktuality-5/2024/05/16/ocenovani-zasob-vazeny-aritmeticky-prumer-metoda-fifo-pevna-cena), [Money S3](https://money.cz/novinky-a-tipy/ucetnictvi-2/jak-na-spravne-uctovani-a-ocenovani-skladu-pomoci-metody-fifo-a-dalsich/), [Portál POHODA](https://portal.pohoda.cz/dane-ucetnictvi-mzdy/ucetnictvi/ocenovani-zasob/)).

**Pohoda** používá váženou nákupní cenu s přepočtem při každém příjmu ([Stormware příručka](https://www.stormware.cz/prirucka-uctujeme-online/zasoby/oceneni_zasob/)); známý problém jsou záporné stavy rozbíjející průměr ([ucetnictvi-vyhodne.cz](https://www.ucetnictvi-vyhodne.cz/jak-zjistim-a-opravim-zaporne-stavy-v-pohybech-na-skladu)) — u nás nemožné díky CHECK ≥ 0.

*Závěr pro projekt: současný model (výdej z konkrétní šarže za její skutečnou cenu) je legislativně nejčistší varianta — **skutečné pořizovací ceny** — a je auditovatelnější než průměr (každý výdej má dohledatelnou fakturu). Vážený průměr by šarže degradoval a nic nepřinesl. FIFO dává smysl jen jako **pořadí výběru šarží** (UX), ne jako změna oceňovacího modelu — pokud výdej skutečně čerpá nejstarší šarži, výsledek je účetně FIFO i specific identification zároveň.*

### 2.3 Ingesce dokladů a legislativní směr

- **ISDOC** (aktuálně 6.0.2, spravuje ICT Unie, implementace zdarma): český standard e-faktury; varianta `.isdoc.pdf` = běžné PDF s vloženým XML — distribuce e-mailem je běžná. Většina českého účetního SW ISDOC čte i generuje ([isdoc přehled](https://ctenifaktur.cz/blog/co-je-isdoc-format), [Portál POHODA](https://portal.pohoda.cz/dane-ucetnictvi-mzdy/ucetnictvi/prisel-mi-isdoc-co-s-tim/), [Wikipedie](https://cs.wikipedia.org/wiki/ISDOC)).
- **ViDA (EU)**: povinná strukturovaná e-fakturace (EN 16931) pro přeshraniční B2B od **7/2030**, tuzemské B2B se čeká do **2035**; SR už od 2027. ČR dnes povinnost nemá, ale směr je jednoznačný ([ARICOMA](https://www.aricoma.com/inspiration/mandatory-e-invoicing-in-the-eu-from-2030-what-does-vida-mean-and-how-can-you-prepare), [Accace](https://www.accace.cz/e-invoicing-v-cesku-podle-smernice-vida/), [epravo](https://www.epravo.cz/top/clanky/e-invoicing-ve-svetle-smernice-vida-a-narodnich-uprav-119279.html)).
- **AI extrakce s human-in-the-loop**: komerční praxe (Rossum) = template-free extrakce + validační UI, podezřelá data k člověku, čistá projdou; business pravidla a učení z oprav snižují podíl ruční práce ([Rossum: how AI invoice processing works](https://rossum.ai/blog/how-ai-invoice-processing-works/), [AP automation](https://rossum.ai/solutions/accounts-payable/)).

*Závěr: draft pipeline projektu odpovídá špičce praxe (provenience polí je dokonce přísnější než typické confidence skóre). Strategicky správný další adaptér je **ISDOC** — deterministický (bez AI, vše VERBATIM), `receipt_source='ISDOC'` je už rezervován, a `.isdoc.pdf` znamená, že tentýž soubor může jít AI cestou (vizuál) i XML cestou (data) s křížovou kontrolou. E-mailová schránka a foto/scan jsou jen další vstupy do téže pipeline.*

### 2.4 Česká praxe (Pohoda/ABRA/Money)

- Příjemky/výdejky jsou samostatné agendy; dobropis **generuje protipohyb** (výdejku/příjemku) — doklad nikdy nemění stav přímo ([Money S3](https://money.cz/novinky-a-tipy/ucetnictvi-2/jak-na-zasoby-v-ucetnictvi-2-dil/), [ABRA Flexi](https://podpora.flexibee.eu/cs/articles/5602770-nelze-odstranit-chybne-vytvorenou-prijemku-prijatou-fakturu-dobropis) — chybnou příjemku nelze smazat, řeší se opravným dokladem).
- **Inventura** je agenda: soupis k datu (očekávaný stav), zadání skutečností, tisk inventurního soupisu, zaúčtování rozdílů ([Stormware 12.7 Inventura](https://www.stormware.cz/prirucka-pohoda-online/Sklady/Inventura_a_inventurni_seznamy/)).
- ABRA dokumentuje výpočet skladových cen jako samostatný (dávkový) proces ([ABRA help](https://help.abra.eu/cs/20.2/G3/Content/PartS_Sklady/vec_obsah_sklad_vypocet_sklad_cen.htm)).

*Přenositelnost: vzor „oprava = nový kompenzační doklad/pohyb, nikdy mazání" potvrzuje append-only ledger; inventurní agenda je standard, který malému servisu stačí v jednoduché formě (jeden sklad, desítky–stovky karet).*

---

## Úkol 3 — Vyhodnocení a návrh

### (a) CO ZACHOVAT

1. **Pohybový ledger + trigger + CHECK** — identický vzor jako ERPNext (SLE+Bin) a Odoo (stock.move); append-only s odvozeným stavem je auditní zlatý standard. Nulová potřeba změny.
2. **Šarže jako nositel ceny** = oceňování skutečnými pořizovacími cenami — legislativně povolené, auditovatelnější než průměr, a zároveň Odoo-style valuation layer zdarma. Nepřecházet na vážený průměr.
3. **Draft pipeline s provenience polí a completeness gate** — odpovídá (a v disciplíně předčí) komerční human-in-the-loop praxi. Kanonický draft = správná investice, adaptéry se na něj věší levně.
4. **Samoučící `supplier_products`** — standardní ERP cross-reference + Rossum-style učení z potvrzení.
5. **Dedup DL↔faktura (V41)** — správně dimenzovaný „2-way match" bez zavádění PO procesu.
6. **Guarded přechody stavů + FOR UPDATE výdej** — konzistentní concurrency model po opravách K5/K6.
7. **Dodavatel vzniká jen potvrzením dokladu** — jediná brána, dedup přes normalizované registrační číslo (ROZH-007 ponechat odložené).

### (b) CO ZMĚNIT

| # | Změna | Cílový návrh | Dopady | Rozsah |
|---|---|---|---|---|
| Z-1 | **Měny (S-11): zavřít tichou díru** | Ve `validateCompleteness` blokovat confirm pro `currency != CZK` s kódem `UNSUPPORTED_CURRENCY` (než bude kurzový přepočet). Doklad v EUR dnes potichu vyrobí šarže s EUR cenami. | jen service + test; DB beze změny | S |
| Z-2 | **Výdej: FIFO předvýběr šarží (S-6)** | Doplněk pro výběr šarže *podle produktu* (stejný díl ve více šaržích): řadit `issue_date ASC` a předvyplňovat nejstarší s `quantity_remaining > 0`; člověk smí přepsat (specific identification zůstává). Účetně se tím chování přiblíží FIFO bez změny modelu. **Stávající doklad-centrický import na zakázku (autocomplete dokladu → jeho položky, [GoodsReceiptMapper.xml:43](../src/main/resources/mapper/warehouse/GoodsReceiptMapper.xml:43)) zůstává beze změny zachován** — tam je šarže určena vybraným dokladem a FIFO nehraje roli. | FE (řazení + předvýběr), případně řadicí klauzule v mapperu | S |
| Z-3 | **Nepoužívané views (S-5)** | `v_stock_on_hand`/`v_batch_provenance` buď zapojit do reportingu (viz P-4), nebo v `databaze.md` explicitně označit „jen pro ad-hoc SQL". Nemazat migrací bez užitku — ale nenechávat nevyjasněné. | dokumentace, příp. nová migrace až s P-4 | XS |
| Z-4 | **Jednotky (S-10)** | Malý číselník povolených jednotek (ks, l, kg, bal, m, sada) jako CHECK/enum nebo konfigurace + validace v draftu; bez převodů (malý servis je nepotřebuje). | migrace (CHECK), DraftAssembler default, FE select | S |

### (c) CO PŘIDAT (prioritizováno; „→" = je předpokladem)

| Pri | # | Co | Proč | Rozsah |
|---|---|---|---|---|
| 1 | **P-1** | **Ruční skladové pohyby: ADJUSTMENT a WRITE_OFF** — endpoint + modal na kartě produktu (typ, množství, povinná poznámka, u záporných volitelně šarže). Oživí mrtvou infrastrukturu S-1. | Bez korekce je oprava stavu jen ruční SQL; předpoklad inventury. | M |
| 2 | **P-2** | **Storno potvrzené příjemky** (S-3): SAP vzor 102 — kompenzační pohyby (záporný RECEIPT-protipohyb nebo ADJUSTMENT per šarže) + nový stav `CANCELLED`; povoleno **jen pokud ze šarží nebylo čerpáno** (`quantity_remaining = quantity_received` a žádná vazba z order_items), jinak 422. | Omylem potvrzený doklad je reálný scénář; append-only zůstává zachováno. | M (migrace: enum hodnota + audit sloupce; service; FE tlačítko) |
| 3 | **P-3** | **Vratka dodavateli + přijatý dobropis** (S-4): proces nad RETURN — výběr šarže, `return_reason`, `credit_note_number`; ideálně jako nový `document_type = CREDIT_NOTE` v draft pipeline (dobropis je taky doklad ke kontrole!), materializace = RETURN pohyby. | DB je připravená; dobropis dodavatele je běžný (vrácené díly). Zapadá do kanonického draftu. | M–L |
| 4 | **P-4** | **Ocenění a reporting** (S-5): view `v_stock_valuation` (hodnota zásob = Σ quantity_remaining × unit_price_excl_vat, celkem i per produkt) + endpoint + karta na WarehousePage; později obrátkovost z ledgeru. | Jeden SELECT nad existujícími daty; první „účetní" pohled na sklad. | S |
| 5 | **P-5** | **Inventura jako proces** (S-2): tabulka `stock_takes` + items (očekávané množství = snapshot, skutečné = vstup), UI se soupisem, uzavření vygeneruje ADJUSTMENT pohyby s odkazem na inventuru, tisk protokolu. Pohoda vzor zjednodušený na jeden sklad. → vyžaduje P-1 (ADJUSTMENT mechanika). | Zákonná inventarizace; bez ní je ledger teorie. | L |
| 6 | **P-6** | **ISDOC adaptér** (rezervovaný `receipt_source`): parser ISDOC XML → tentýž `ReceiptDraft` (vše VERBATIM, `reconciliation` triviální); u `.isdoc.pdf` možnost křížové kontroly s AI extrakcí. | Deterministická cesta bez AI nákladů; legislativní směr ViDA/2030. | M |
| 7 | **P-7** | **Návrh objednávky** (S-8): stránka „Pod minimem" (filtr existuje) + doporučený dodavatel a poslední cena ze `supplier_products`; jen zobrazení/export, žádný PO modul. | Levná přidaná hodnota nad existujícími daty. | S |
| 8 | **P-8** | **Výdej mimo zakázku** (S-13): povolit ISSUE s `order_id = NULL` + důvod (interní spotřeba) — vyžaduje uvolnit sémantiku, dnes žádný CHECK nebrání, jen service. | Dílna spotřebovává materiál i mimo zakázky. | S |

**Vědomě nepřidávat:** objednávkový modul + plný 3-way match (S-9) a rezervace (S-7) — pro jeden servis s okamžitým výdejem je náklad/užitek špatný; rezervaci supluje stav zakázky WAITING_FOR_PARTS. Multisklady, výrobní čísla/expirace — mimo doménu.

### (d) ROZHODNUTÍ — uzavřeno 2026-07-20

Všech šest rozhodnutí bylo předloženo uživateli s možnostmi a doporučením; výsledky:

**R-A · Oceňování zásob / výběr šarže při výdeji → ROZHODNUTO: šarže ručně + FIFO předvýběr** *(volba uživatele dle doporučení)*
Zůstává výdej z konkrétní šarže (skutečné pořizovací ceny), UI předvyplní nejstarší šarži se zbytkem (Z-2). PROČ: žádná změna DB, plná auditovatelnost (výdej → šarže → faktura), účetně nejčistší povolená metoda; zachovává možnost cíleně vydat konkrétní kus (reklamace). Zavržené alternativy: tvrdý FIFO automat (ztráta cíleného výběru), vážený průměr (velká přestavba, šarže by ztratily smysl nositele ceny).

**R-B · Inventura — pořadí → ROZHODNUTO: nejdřív jednotlivé korekce (P-1), plná inventura (P-5) později** *(přijatý default, uživatel nerozporoval)*
PROČ: ADJUSTMENT mechanika je předpoklad inventury; jednotlivá korekce s povinnou poznámkou pokryje akutní potřebu (rozbití, ztráta) okamžitě, inventurní proces se soupisem je samostatná větší fáze.

**R-C · Storno potvrzené příjemky → ROZHODNUTO: kompenzační storno s guardem** *(volba uživatele dle doporučení)*
Nový stav `CANCELLED` + opačné pohyby (SAP vzor 101/102); povoleno jen pokud ze šarží nebylo čerpáno (`quantity_remaining = quantity_received`, žádná vazba z order_items), jinak 422. PROČ: ledger zůstává append-only, storno má vazbu na doklad; čerpaná příjemka se stornovat nesmí — řeší se korekcí (P-1).

**R-D · Rozsah typů dokladů a kanálů → ROZHODNUTO (delegováno na analýzu): ISDOC ANO, přijatý dobropis ANO, foto/scan ANO, e-mailová schránka ODLOŽENA**
Uživatel rozhodnutí delegoval („důkladně zvaž a rozhodni") s pokynem řádně zdokumentovat. Zdůvodnění:
- **ISDOC adaptér (P-6) — ano.** Deterministický parser (vše VERBATIM, bez AI nákladů a bez rizika halucinace) do téhož kanonického draftu; `receipt_source='ISDOC'` je rezervován od V39, takže jde o naplnění existujícího návrhu. Legislativní směr je jednoznačný (ViDA: přeshraniční B2B 2030, tuzemsko ~2035) a většina českého SW ISDOC už generuje — reálné doklady tímto kanálem existují dnes. Varianta `.isdoc.pdf` navíc umožní křížovou kontrolu XML dat proti AI extrakci téhož PDF.
- **Přijatý dobropis jako `document_type=CREDIT_NOTE` — ano.** Bez dokladu nemá RETURN pohyb (vratka dodavateli, P-3) ukotvení — dobropis je doklad ke kontrole jako každý jiný a patří do draft pipeline („nový formát dokladu = nový adaptér, zbytek se nemění"). Materializace = RETURN pohyby proti šarži + `credit_note_number`.
- **Foto/scan přes AI — ano.** Nejlevnější rozšíření: model čte i obrázky (prompt už dnes instruuje „čti vizuálně" kvůli poškozeným PDF), stačí povolit obrazové MIME typy na uploadu; typ dokladu dál volí člověk. Mechanik vyfotí dodací list mobilem — reálný scénář malého servisu.
- **E-mailová schránka — odložit.** Jediný kanál, který přidává bezobslužnou ingesci: nová integrační plocha (IMAP/forwarding, spam, bezpečnost) a hlavně konflikt s vědomým principem „typ dokladu volí uživatel při uploadu" — u e-mailu by ho musel určovat stroj. Vrátit se k ní až s reálnou potřebou, případně společně s Peppol/ViDA přístupovým bodem.

**R-E · Kladná inventurní korekce a šarže → ROZHODNUTO: inventurní šarže** *(volba uživatele dle doporučení)*
Kladný ADJUSTMENT založí speciální šarži s odhadní cenou (technicky přes speciální příjemku `source_channel=MANUAL`). PROČ: zachová invariant „každý kus má šarži a cenu" — ocenění skladu (P-4) i provenience zůstanou úplné. Zavrženo `batch_id NULL`: vzniklo by zboží bez ceny.

**R-F · Měny → ROZHODNUTO: blokovat ne-CZK při confirm (Z-1)** *(přijatý default, uživatel nerozporoval)*
PROČ: uzavírá tichou datovou vadu (EUR ceny tvářící se jako CZK) nejlevnějším způsobem; kurzový přepočet (ČNB k DUZP) zavést až s prokázanou potřebou.

**R-G · Rozsah vratky dodavateli (upřesnění R-D) → ROZHODNUTO 2026-07-21: nejdřív ruční vratka, dobropis jako doklad odložen** *(volba uživatele dle doporučení)*
Vratka se implementuje jako třetí typ ručního skladového pohybu (`RETURN` s povinným důvodem a volitelným číslem dobropisu), ne jako nový `document_type` v draft pipeline.
PROČ: dobropis je **opačný doklad** než příjemka — musí ukazovat na existující šarže, nesmí zakládat karty ani šarže, a potřeboval by druhou větev `confirm`, vlastní completeness gate i kontrolní obrazovku. Přitom **dvě nejdůležitější pole — která šarže se vrací a proč — z dokladu vyčíst nejdou**; přínos AI extrakce je tu nejnižší ze všech typů dokladů. Ruční vratka uzavře reálnou mezeru (S-4: RETURN je mrtvá ENUM hodnota) zlomkem práce a její materializační logika je stejně podmnožinou budoucího dobropisu. R-D (dobropis ANO) tím není zrušeno, jen odsunuto do fáze E5b — vrátit se k němu, až bude jasné, jak často dobropisy reálně chodí.
Vedlejší zjištění: **cenový dobropis** (oprava ceny bez pohybu zboží), v ČR běžný, nemá v aplikaci kam jít — nevede závazky ani účetnictví. Skončil by jako uložený dokument bez efektu na sklad.

**R-H · Návrh inventury (upřesnění R-B a R-E) → ROZHODNUTO 2026-07-21** *(tři volby uživatele dle doporučení)*
- **Přebytek → pseudo-příjemka „Inventura"**: uzavření inventury založí jednu příjemku `document_type = STOCK_TAKE` bez dodavatele (CHECK `chk_receipt_confirmed_complete` se pro tento typ uvolní migrací) se všemi přebytky. PROČ: `goods_receipt_items.goods_receipt_id` je NOT NULL — šarže bez příjemky vzniknout nemůže, a uvolnit tenhle vztah by rozbilo invariant „každá šarže má původ". Pseudo-příjemka dá přebytku doklad, šarži i cenu, takže ocenění (E3) zůstane úplné.
- **Rozsah = celý sklad, nevyplněný řádek = nepočítáno**: otevření nasnapshotuje všechny aktivní produkty; prázdné napočítané množství neznamená nulu a negeneruje korekci. PROČ: jedna dílna nepotřebuje výběrová řízení nad sortimentem a takhle lze počítat po částech napříč dny.
- **Cena přebytku = poslední nákupní cena, editovatelná**: předvyplní se z nejnovější šarže dílu; bez šarže zůstane prázdná a musí ji doplnit člověk. PROČ: nulová cena by podhodnotila sklad, vždy ruční zadání by zdržovalo.

Doplňující rozhodnutí učiněná v návrhu (nepředkládána zvlášť, plynou z už uzavřených pravidel):
- **Manko se rozpouští po šaržích od nejstarší** (FIFO) — konzistentní s R-A; korekce musí jít proti konkrétní šarži, jinak se rozejde ocenění.
- **Rozdíl se při uzavření počítá proti aktuálnímu stavu**, ne proti snapshotu z otevření — jinak by inventura přepsala výdeje, které během počítání proběhly. Snapshot zůstává informativní.
- **Jen jedna otevřená inventura naráz** (partial unique index) — dvě souběžné by si korekce přepisovaly.

### Fázovaný postup realizace (vzor plan-oprav: malé ověřitelné úkoly)

| Fáze | Úkoly | Ověření |
|---|---|---|
| **E1 — utěsnění** | Z-1 (blok cizí měny), Z-3 (vyjasnit views), Z-4 (číselník jednotek) | testy confirm gate; suite zelená |
| **E2 — ruční pohyby** | P-1 ADJUSTMENT/WRITE_OFF (endpoint, service, modal, doklad v historii pohybů) | integrační test: pohyb → trigger → stav; FE e2e |
| **E3 — hodnota skladu** | P-4 view + endpoint + karta | součet sedí na ruční SQL |
| **E4 — storno příjemky** | P-2 (migrace enum, guard nečerpáno, kompenzace) | test: storno čerpané příjemky → 422 |
| **E5 — vratka dodavateli** | P-3 (RETURN + dobropis jako `CREDIT_NOTE` draft — dle R-D) | test proti šarži |
| **E6 — inventura** | P-5 (stock_takes, soupis, uzavření → ADJUSTMENT) | protokol + rozdílové pohyby |
| **E7 — ISDOC** | P-6 adaptér → tentýž draft | vzorový ISDOC ze specifikace |
| **E8 — komfort** | Z-2 FIFO předvýběr (dle R-A), foto/scan upload (dle R-D), P-7 návrh objednávky, P-8 výdej mimo zakázku | FE e2e |

Každá fáze je samostatně commitovatelná a nechává systém funkční; pořadí E2 → E4/E5/E6 je závazné (ADJUSTMENT mechanika je předpoklad), zbytek lze přehazovat dle priorit.

---

## Zdroje rešerše

ERP modely: [ERPNext FIFO/Moving Average](https://docs.frappe.io/erpnext/fifo-and-moving-average) · [ERPNext inventory architecture (DeepWiki)](https://deepwiki.com/frappe/erpnext/5-inventory-management) · [Odoo inventory valuation](https://www.odoo.com/documentation/18.0/applications/inventory_and_mrp/inventory/product_management/inventory_valuation/inventory_valuation_config.html) · [Odoo valuation cheat sheet](https://www.odoo.com/documentation/19.0/applications/inventory_and_mrp/inventory/inventory_valuation/cheat_sheet.html) · [SAP movement types](https://spendwizard.com/sap-tutorial/what-is-a-movement-type-in-sap/) · [SAP 3-way match (Ramp)](https://ramp.com/blog/sap-3-way-match) · [SAP goods receipt (Doxis)](https://www.doxis.com/en/blog/goods-receipt-checks)
Oceňování ČR: [Ježek software — metody oceňování](https://www.jezeksw.cz/aktuality-5/2024/05/16/ocenovani-zasob-vazeny-aritmeticky-prumer-metoda-fifo-pevna-cena) · [Money — FIFO a spol.](https://money.cz/novinky-a-tipy/ucetnictvi-2/jak-na-spravne-uctovani-a-ocenovani-skladu-pomoci-metody-fifo-a-dalsich/) · [Portál POHODA — oceňování zásob](https://portal.pohoda.cz/dane-ucetnictvi-mzdy/ucetnictvi/ocenovani-zasob/) · [Stormware — ocenění zásob](https://www.stormware.cz/prirucka-uctujeme-online/zasoby/oceneni_zasob/) · [Stormware — inventura](https://www.stormware.cz/prirucka-pohoda-online/Sklady/Inventura_a_inventurni_seznamy/) · [ABRA — výpočet skladových cen](https://help.abra.eu/cs/20.2/G3/Content/PartS_Sklady/vec_obsah_sklad_vypocet_sklad_cen.htm) · [ABRA Flexi — oprava chybné příjemky](https://podpora.flexibee.eu/cs/articles/5602770-nelze-odstranit-chybne-vytvorenou-prijemku-prijatou-fakturu-dobropis) · [záporné stavy v Pohodě](https://www.ucetnictvi-vyhodne.cz/jak-zjistim-a-opravim-zaporne-stavy-v-pohybech-na-skladu)
Ingesce dokladů: [ISDOC — co je (ctenifaktur)](https://ctenifaktur.cz/blog/co-je-isdoc-format) · [Portál POHODA — přišel mi ISDOC](https://portal.pohoda.cz/dane-ucetnictvi-mzdy/ucetnictvi/prisel-mi-isdoc-co-s-tim/) · [ISDOC (Wikipedie)](https://cs.wikipedia.org/wiki/ISDOC) · [ViDA (ARICOMA)](https://www.aricoma.com/inspiration/mandatory-e-invoicing-in-the-eu-from-2030-what-does-vida-mean-and-how-can-you-prepare) · [ViDA v ČR (Accace)](https://www.accace.cz/e-invoicing-v-cesku-podle-smernice-vida/) · [e-invoicing a ViDA (epravo)](https://www.epravo.cz/top/clanky/e-invoicing-ve-svetle-smernice-vida-a-narodnich-uprav-119279.html) · [Rossum — AI invoice processing](https://rossum.ai/blog/how-ai-invoice-processing-works/) · [Rossum — AP automation](https://rossum.ai/solutions/accounts-payable/)
