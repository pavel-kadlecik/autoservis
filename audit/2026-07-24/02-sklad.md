# Audit 2/9 — Modul Sklad a importní pipeline

> Součást hloubkového auditu 2026-07-24 (commit `409d3ad`, větev `audit-one`).
> Přehled celého auditu: [00-prehled.md](00-prehled.md).
>
> **Verifikace hlavního auditora:** nálezy V-1 (INNER JOIN na dodavatele ve `findBatchesByProductId`)
> a S-1 (`getPage()-1`) ověřeny druhým čtením — `WarehouseMapper.xml:119-136` má `JOIN warehouse.suppliers`
> (ne LEFT), `ReceiptReviewServiceImpl.java:56` skutečně posílá `params.getPage() - 1`. Potvrzeno.

Rozsah: controllery `warehouse/`, services (Product, Supplier, GoodsReceipt, ReceiptReview, StockMovement, StockTake, StockValuation, LowStock, WarehouseImport, DraftAssembler, DraftVerificationService, ProductMatchingService, IsdocParser, PdfDocumentExtraction), draft model, warehouse DTO/konvertory, 7 XML mapperů, migrace V18–V44. Otevřené dluhy TD-40/TD-41 nereportovány.

---

## Nálezy

### VYSOKÝ

**V-1. Šarže z inventurního přebytku je na kartě dílu neviditelná — INNER JOIN na dodavatele**
- **Soubor:** `WarehouseMapper.xml:119-136` (`findBatchesByProductId`) + `StockTakeServiceImpl.java:240-274` (`applySurpluses`).
- **Důkaz:** dotaz šarží karty dělá `JOIN warehouse.suppliers s ON s.id = gr.supplier_id` — vnitřní join. Pseudo-příjemka `STOCK_TAKE` vzniká **bez dodavatele** (`supplier_id NULL`, povoleno V39/V44), takže její šarže z výsledku vypadne.
- **Dopad/scénář:** po uzavření inventury s přebytkem karta dílu ukazuje `quantity_on_hand` vyšší než součet zobrazených šarží; **modal ručního pohybu** (`StockMovementModal.jsx:34` bere `product.batches`) tuto šarži nenabídne — přebytek nejde ručně korigovat, odepsat ani vrátit. Ocenění (`v_stock_valuation`) a FIFO rozpouštění manka šarži přitom vidí, takže čísla „nesedí" jen v UI karty.
- **Oprava:** `LEFT JOIN warehouse.suppliers` + `COALESCE(s.name, gr.supplier_name_snapshot)` (snapshot je „Inventura").
- **Confidence:** jistý.

**V-2. Ochrana proti dvojímu naskladnění (DL ↔ faktura, volba „jen provázat") stojí na poli, které prompt po modelu nechce**
- **Soubory:** `ReceiptReviewServiceImpl.java:264-267` a `:507-511` (skip řádků krytých LINKED DL podle `line.getDeliveryNoteNumber()`), `PdfDocumentExtractionService.java:59-63` (prompt), `DocumentExtractionResult.java:67`.
- **Důkaz:** skip v confirmu funguje jen, když **ITEM řádek** nese `deliveryNoteNumber`. Prompt ale říká pouze, že řádek typu „Dodací list č. … celkem" je `kind=DELIVERY_NOTE_GROUP` — o vyplňování u ITEM řádků mlčí. Komentář v recordu tvrdí opak toho, co potřebuje confirm. Průvodce tvrdí „model číslo DL přiřazuje i položkám pod ním" — v promptu taková instrukce není. Integrační test si ITEM řádek s DL číslem **dosazuje sám**, takže mezeru nezachytí.
- **Dopad/scénář:** souhrnná faktura → kontrolor zvolí „jen provázat" (LINKED) → pokud model `deliveryNoteNumber` na ITEM řádcích nevyplnil, confirm položky **naskladní podruhé** navzdory volbě uživatele. Zboží v ledgeru dvakrát.
- **Oprava:** doplnit do promptu explicitní instrukci; navíc pojistka v kódu: existuje-li LINKED ref, jejíž číslo nenese žádný ITEM řádek, blokovat confirm (`RECEIPT_INCOMPLETE`).
- **Confidence:** pravděpodobný (mezera v promptu jistá; chování modelu bez instrukce není garantované — a to je ten problém).

### STŘEDNÍ

**S-1. Stránkování seznamu příjemek porušuje 1-based kontrakt (`page - 1`)**
- **Soubor:** `ReceiptReviewServiceImpl.java:56`: `PagedResponse.of(content, params.getPage() - 1, …)`.
- **Důkaz:** `PagedResponse.of` je po TD-50 **1-based**; všech ostatních 7 volajících posílá `params.getPage()`.
- **Dopad:** `page` o 1 nižší, `first` true i na 2. stránce, `last` se rozsvítí později. FE dnes čte jen `totalPages`, takže se to neprojevuje — ale kontrakt je porušen.
- **Oprava:** odstranit `- 1`. Confidence: jistý.

**S-2. Chyby AI extrakce padají jako 500 INTERNAL_ERROR**
- **Soubory:** `PdfDocumentExtractionService.java:107-112` (žádný try/catch), `GlobalExceptionHandler` (žádný handler pro Spring AI výjimky).
- **Dopad:** timeout/přetížení API, chybějící klíč, nevalidní JSON z modelu → generické 500 + ERROR log; „zkuste znovu" není odlišitelné od skutečné chyby. Kontrast: `VehicleRegistryClient` má vlastní `RegistryUnavailableException`.
- **Oprava:** obalit volání, mapovat na 422/503 s kódem `EXTRACTION_FAILED`. Confidence: jistý.

**S-3. Záporná jednotková cena řádku projde completeness gate i DB — záporné ocenění šarže**
- **Soubory:** `ReceiptReviewServiceImpl.validateCompleteness:518-525` (kladnost jen u `quantity`), V18 `goods_receipt_items` (CHECKy jen `quantity_received > 0`, na ceny žádný).
- **Dopad:** AI přečte slevový/zálohový řádek se zápornou cenou → šarže se zápornou nákupní cenou → `v_stock_valuation` nesmysl.
- **Oprava:** v gate vyžadovat `unitPriceExclVat >= 0` a `totalInclVat >= 0`, volitelně CHECK. Confidence: jistý.

**S-4. Jednotka řádku se neporovnává s jednotkou napárované karty**
- **Soubor:** `validateCompleteness:512-517` (jen číselník Z-4) + `resolveProduct:573-598`.
- **Dopad:** řádek „5 bal" napárovaný na kartu v „ks" → pohyb přičte 5 „ks". Obě jednotky jsou v číselníku, gate mlčí; sklad tiše lže o množství.
- **Oprava:** při matchi na existující produkt porovnat kanonizovanou jednotku s `products.unit`. Confidence: jistý (mechanismus).

**S-5. Párovací kaskáda a fallback na SKU ignorují `is_active`**
- **Soubory:** `ProductMatchingMapper.xml:12-17` (bez filtru), `WarehouseImportMapper.xml:53-55` a `:7-9`.
- **Dopad:** karta deaktivovaná (po TD-28 nutně s nulovou zásobou) dostane přes AUTO match novou šarži → zásoba na neaktivní kartě; obchází smysl TD-28. Deaktivovaný dodavatel se tiše páruje.
- **Oprava:** filtr `is_active = TRUE` + při shodě s neaktivní kartou vrátit SUGGESTED, nebo explicitní reaktivace. Confidence: jistý.

**S-6. `PUT /receipts/{id}/draft` přijímá surový `ReceiptDraft` bez validace — NPE → 500, padělatelné stavy polí**
- **Soubory:** `GoodsReceiptReviewController.java:76-81` (bez `@Valid`), `ReceiptReviewServiceImpl.updateDraft:175-215`, `DraftVerificationService.verify:41`.
- **Důkaz:** tělo `{}` → NPE → 500 (má být 400). Klient může poslat pole se `state: "VERIFIED"` — `TrackedField.verify()` nikdy nedegraduje, podvržený stav se uloží a UI ho ukáže jako „ověřeno kódem". Lze přepsat `documentType`/`sourceChannel`.
- **Oprava:** vstupní normalizace/validace (non-null header/lines, reset ne-editovatelných stavů). Confidence: jistý.

**S-7. `verifySupplier` přepisuje výsledek párování a nechává nekonzistentní stav**
- **Soubor:** `DraftVerificationService.verifySupplier:208-233`, `ReceiptReviewServiceImpl.createManualDraft:96-131`.
- **Důkaz:** ruční draft s dodavatelem bez IČO → `AUTO` + `matchedSupplierId`, ale `verify()` přepne `matchState` na `NONE` a `matchedSupplierId` nevyčistí. Confirm použije zapamatované id → žádný dodavatel se nezaloží, editace jména/IČO se zahodí. Opačně: extrahované IČO jiného dodavatele `verify()` bezpodmínečně přepíše (na rozdíl od produktové kaskády, která CONFIRMED respektuje).
- **Oprava:** nechat `matchedSupplierId`/`matchState` na pokoji, je-li napárováno; při NONE id čistit. Confidence: jistý.

**S-8. AI-extrahované bankovní údaje dodavatele se persistují bez zobrazení v review**
- **Soubory:** `ReceiptReviewServiceImpl.resolveSupplier:552-565` (ukládá `bankAccount`, `iban`, `swift`), `ReceiptDraftHeaderForm.jsx` (zobrazuje jen jméno + IČO).
- **Dopad:** „AI čte, kód počítá" u částek drženo, ale u **platebních údajů** nic nekontroluje (IBAN checksum chybí) a člověk je nevidí. Padělané PDF může založit dodavatele s cizím číslem účtu; účetní ho odtud opíše. (Prompt injection do textu je jinak neškodná — React eskapuje, nic se nevrací do promptů.)
- **Oprava:** zobrazit bankovní údaje v review (read-only), přidat IBAN mod-97. Confidence: jistý (tok dat), dopad omezený.

**S-9. Uzavření inventury: manko se počítá z nezamčeného stavu, šarže se zamykají až poté**
- **Soubory:** `StockTakeServiceImpl.close:160-188` (`findItems` čte `quantity_on_hand` bez zámku) → `applyShortages:191-217` (`FOR UPDATE`).
- **Dopad:** výdej commitnutý mezi `findItems` a zámkem se do rozdílu nepromítne — dvojí odečet, nebo pád na CHECK. Rozhodnutí „inventura nezamyká sklad" kryje období počítání, ne okamžik uzavření.
- **Oprava:** číst rozdíly až po zamčení šarží, nebo počítat manko z `SUM(quantity_remaining)`. Confidence: pravděpodobný (okno malé).

**S-10. `documentType=STOCK_TAKE` lze poslat z klienta**
- **Soubory:** `GoodsReceiptImportController.importDocument:48`, `ReceiptDto.CreateDraftRequest:97-99`.
- **Dopad:** uživatel založí „inventurní" příjemku mimo inventuru; V44 pro STOCK_TAKE uvolňuje CHECK úplnosti → sémantika typu se rozmělní, reporting nad `document_type` lže.
- **Oprava:** whitelist INVOICE/DELIVERY_NOTE. Confidence: jistý.

### NÍZKÝ

- **N-1:** Souběžné duplicity končí 422 místo 409 (import téhož dokladu, `open` inventury, `resolveSupplier`) — DB drží konzistenci partial unique indexy, jen status je matoucí. Catch `DuplicateKeyException` → ConflictException.
- **N-2:** Autocomplete příjemek: NULL description pro STOCK_TAKE doklady (`GoodsReceiptMapper.xml:54-57`, `TO_CHAR(total_amount)` bez COALESCE → `x || NULL = NULL`).
- **N-3:** IsdocParser: `.isdocx` (ZIP) přijato, ale nepodporováno; `requireSupportedDocumentType` pustí dokument bez `DocumentType` a neověřuje root element `Invoice` — cizí XML projde jako prázdný draft.
- **N-4:** Validace nahraného souboru je OR přípona/contentType bez magic bytes; klientský contentType jde přímo do `Media` (Anthropic API).
- **N-5:** `confirm` NPE, když draft má `matchedSupplierId` bez `extracted` (`:250`) — dosažitelné jen přes API-crafted PUT.
- **N-6:** Délky AI výstupů se před INSERTem nekontrolují (název > 500, sku > 100) → 422 bez informace, který řádek.
- **N-7:** Mrtvý/pochybný kód: `GET /warehouse/products/import/{id}` (nikde nevoláno, zkopírovaný javadoc); osiřelý javadoc v `ProductServiceImpl:199-203`; nečitelný ternár v `GoodsReceiptServiceImpl.autocomplete:33`.
- **N-8:** Odchylka od R-10: `WarehouseMapper.findById` i `SupplierMapper.findById` jsou permissive bez `findByIdIncludingInactive` varianty (funkčně potřeba, ale konvence žádá strict + pojmenovanou permissive).
- **N-9:** Storno lze provést i na inventurní pseudo-příjemce (`cancel` kontroluje jen `status == CONFIRMED`) → přebytek zmizí mimo protokol. Možná záměr, nedokumentováno.
- **N-10:** `deactivate` produktu je check-then-act — souběžný confirm může naskladnit mezi čtením a UPDATE (TD-28 obejito souběhem). `UPDATE … WHERE id=? AND quantity_on_hand = 0`.
- **N-11:** Zastaralá poznámka v tech-dluzích: TD-46 tvrdí „chybí zakázky a příjemky", ale `ReceiptReviewMapper.xml` už whitelist řazení má. Aktualizovat.

---

## Pozitiva / poznámky

- **Ledger invariant drží** (na aplikační úrovni): jediná cesta zápisu stavu je `insertMovement` + DB trigger; žádný mapper nezapisuje `quantity_on_hand`/`quantity_remaining` přímo (ověřeno grep). CHECKy tvoří druhou linii. Storno (R-C) i inventura (R-H) generují kompenzační/korekční pohyby, nic se nemaže. *(DB audit ale upozorňuje, že append-only není vynucené na úrovni DB — viz [04-databaze.md](04-databaze.md) N-3.)*
- **Souběh systematicky (vzor K6):** `FOR UPDATE` u ručního pohybu, výdeje (s agregací duplicit v requestu), storna i FIFO manka; guarded přechody stavů (0 řádků → 409). Dvojí confirm/storno bezpečně selže.
- **Storno guard promyšlený:** kontroluje `quantity_remaining != quantity_received` i trvající FK z `order_items`.
- **„AI čte, kód počítá" u částek vzorové:** přepočty v `DraftAssembler`/`DraftVerificationService` (BigDecimal, HALF_UP, tolerance z konfigurace, `compareTo`), VERIFIED povyšuje jen kód, IČO checksum mod 11 správně, `temperature: 0.0`. Výjimky: bankovní pole (S-8), znaménka cen (S-3).
- **Idempotence importu dvouvrstvá:** code-check při importu, re-check při confirmu s `excludeReceiptId`, pod tím partial unique index.
- **IsdocParser správně vypnuté XXE/DOCTYPE**, čte lokální jména, dobropisy odmítá.
- **Inventura:** NULL = nepočítáno, rozdíl proti aktuálnímu stavu, FIFO po šaržích, povinná cena přebytku, přebytek přes trigger — čisté; jediná hrana je atomicita uzavření (S-9).
- `client/` obsahuje jen `VehicleRegistryClient`; AI jde přes Spring AI `ChatClient`.

**Souhrn:** 2× VYSOKÝ, 10× STŘEDNÍ, 11× NÍZKÝ. Jádro (ledger, souběh, stavové automaty, idempotence) je řemeslně velmi solidní; nejzávažnější rizika jsou viditelnost inventurních šarží (V-1) a spolehlivost DL↔faktura dedupu závislá na nepromptovaném chování modelu (V-2).
