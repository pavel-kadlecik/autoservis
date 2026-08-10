# 03 — Sklad: celé oběhy zboží + AI import

> Audit 2026-07-30 · rozsah: naskladnění (AI/ISDOC/ruční), review workflow, šarže a ocenění, výdej
> a vratky, storno příjemky, ledger pohybů, inventura, nízké zásoby ·
> metoda: čtení celých souborů (kód, XML mappery, migrace) + adversariální druhé čtení každého
> nálezu STŘEDNÍ+ (hledání guardu výš, DB CHECKu, triggeru, validace DTO, testu).
> Read-only, nic nezměněno mimo tento soubor.

## Co bylo přečteno

**Služby (celé):**
`service/GoodsReceiptService.java`, `service/impl/GoodsReceiptServiceImpl.java`,
`service/ReceiptReviewService` (přes impl), `service/impl/ReceiptReviewServiceImpl.java`,
`service/WarehouseImportService.java`, `service/impl/WarehouseImportServiceImpl.java`,
`service/ProductService` (přes impl), `service/impl/ProductServiceImpl.java`,
`service/StockTakeService` (přes impl), `service/impl/StockTakeServiceImpl.java`,
`service/SupplierService` (přes impl), `service/impl/SupplierServiceImpl.java`,
`service/SupplierNormalizer.java`, `service/ProductMatchingService.java`,
`service/DraftAssembler.java`, `service/DraftVerificationService.java`,
`service/PdfDocumentExtractionService.java`, `service/IsdocParser.java`,
`service/impl/OrderItemServiceImpl.java` (výdej/vratka do zakázky).

**Model:** `model/draft/*` (všech 7), `model/domain/warehouse/*` (MovementType, StockMovement,
GoodsReceipt(Item), Product, Supplier, ReceiptStatus/Source, DocumentType, StockTake*, ReturnReason),
`model/dto/warehouse/*` (ReceiptDto, ReceiptDraftDto, ProductDto, StockTakeDto, StockMovementDto,
GoodsReceiptItemDto, DocumentExtractionResult, SupplierDto, ProductSearchParams…),
`model/dto/order/OrderItemDto.java`, `config/WarehouseImportProperties.java`.

**Konvertory:** `ReceiptConverter`, `GoodsReceiptItemConverter`, `WarehouseProductConverter`,
`SupplierConverter`, `OrderItemConverter`.

**Controllery:** všech 7 v `controller/warehouse/`.

**Mappery (XML, celé):** `warehouse/GoodsReceiptMapper.xml`, `ProductMatchingMapper.xml`,
`ReceiptReviewMapper.xml`, `StockTakeMapper.xml`, `SupplierMapper.xml`, `WarehouseImportMapper.xml`,
`WarehouseMapper.xml`.

**Migrace (celé):** V18, V21, V27, V28, V29, V30, V39, V40, V41, V42, V43, V44, V52, V53, V54, V61.

**Dokumentace:** `CLAUDE.md`, `docs/konvence.md`, `docs/tech-dluhy.md`,
`docs/funkce/import-prijemek.md`, `docs/funkce/inventura.md`, `docs/funkce/sklad-pohyby.md`,
`docs/pruvodce/import-prijemek.md`, relevantní části `docs/api.md`.

**Doplňkově (kontrola tvrzení):** `GlobalExceptionHandler` (mapování DataIntegrityViolation),
`ReceiptReviewServiceTest`, `StockTakeStateMachineTest`, seznam testů v `src/test/java/…/service/`,
FE `ReceiptReviewPage.jsx`, `ReceiptDraftLinesTable.jsx`, `StockTakePageDetail.jsx` — jen jako důkaz,
zda existuje cesta v UI (nálezy o FE do tohoto průchodu nepatří).

## Shrnutí

Skladový modul je z auditovaných oblastí nejlépe promyšlený: ledger je od V52 append-only i na úrovni
DB, denormalizace (`quantity_on_hand`, `quantity_remaining`) se zapisuje **výhradně** triggerem,
všechny stavové přechody (confirm/reject/cancel/close) jsou guarded UPDATE s kontrolou počtu řádků,
souběžný výdej i storno berou šarže `FOR UPDATE` a záporná zásoba je odříznutá dvěma DB CHECKy
(`chk_products_qty`, `chk_items_remaining`). Cestu k zápornému stavu jsem nenašel.

Slabina není v ledgeru, ale **na vstupu — v ochraně proti dvojímu naskladnění**. Dva nezávislé
mechanismy, které tomu mají bránit, v praxi nefungují: dedup „dodací list ↔ souhrnná faktura"
(V41) je navázaný na pole, které extrakce podle vlastního promptu ani kontraktu neplní, a idempotence
importu se u dokladu bez čitelného IČO obejde tím, že potvrzení pokaždé založí *nového* dodavatele,
takže duplicitní kontrola nemá s čím porovnávat. Obojí proběhne tiše, bez chyby, a výsledkem je
dvojnásobná zásoba i dvojnásobná hodnota skladu.

Druhá skupina nálezů je kolem `is_active`: potvrzení příjemky nekontroluje, zda napárovaná karta
(resp. dodavatel) je aktivní — jednou to vyrobí neviditelnou zásobu na deaktivované kartě (a obejde
tím guard TD-28), podruhé shodí potvrzení na nesrozumitelnou 422 z DB constraintu.

Třetí je hranice R-15: kód sice počítá, ale u části dokladů pak **svůj vlastní dopočet použije jako
důkaz** a označí AI přečtené hodnoty za `VERIFIED` — u ručně psaného dodacího listu projdou všechny
kontroly tautologicky a `reconciliation_ok` je vždy `true`.

**Počty:** 🔴 VYSOKÝ 2 · 🟠 STŘEDNÍ 3 · 🟡 NÍZKÝ 3. Kritický nález žádný.

---

## Nálezy

### [SK-1] Volba „Pouze provázat" u dodacího listu nic nezabrání — zboží se naskladní podruhé

**Severita:** 🔴 VYSOKÝ
**Jistota:** PRAVDĚPODOBNÝ (kód a prompt ověřeny; nejistota je jen v tom, zda model pole nevyplní sám od sebe — viz níže)
**Kde:**
- `src/main/java/cz/palo/autoservis/service/impl/ReceiptReviewServiceImpl.java:277-280` — jediná podmínka přeskočení řádku:
  ```java
  if (line.getDeliveryNoteNumber() != null
          && linkedNumbers.contains(line.getDeliveryNoteNumber())) {
      continue;   // zboží už přišlo dodacím listem — jen provázáno, nenaskladňovat
  }
  ```
- `src/main/java/cz/palo/autoservis/service/PdfDocumentExtractionService.java:64-67` — prompt plní
  `deliveryNoteNumber` **jen** u skupinového řádku: *„Řádek typu 'Dodací list č. … celkem …' je
  kind=DELIVERY_NOTE_GROUP: vyplň deliveryNoteNumber …"* — pro ITEM řádky žádná instrukce není.
- `src/main/java/cz/palo/autoservis/model/dto/warehouse/DocumentExtractionResult.java:67` — kontrakt pole
  to říká explicitně: `String deliveryNoteNumber    // jen pro DELIVERY_NOTE_GROUP řádky`
- `src/main/java/cz/palo/autoservis/service/DraftAssembler.java:331-344` (`collectDeliveryNoteRefs`) —
  reference se sbírají výhradně z `DELIVERY_NOTE_GROUP` řádků.
- `docs/pruvodce/import-prijemek.md:308` — tvrdí opak: *„(a model číslo DL přiřazuje i položkám pod
  ním — `deliveryNoteNumber` na ITEM řádku)"*.
- `src/test/java/cz/palo/autoservis/service/ReceiptReviewServiceTest.java:481` — test tu domněnku
  obchází: ITEM řádek si ručně dosadí `"3726026714"` jako poslední argument konstruktoru `Line`.
  Test proto prochází, i když reálná extrakce pole nevyplní.
- `frontend/…/src/components/ReceiptDraftLinesTable.jsx:131` — FE číslo DL u řádku jen **zobrazuje**
  (a to pouze u skupinového řádku); přiřadit ho k položce v kontrolní obrazovce nejde.

**Co je špatně:** Celý dedup „DL ↔ souhrnná faktura" (V41, fáze 7) stojí na tom, že ITEM řádky faktury
nesou číslo dodacího listu, který je kryje. Extrakční kontrakt ani prompt to nevyžadují, `DraftAssembler`
to nedoplňuje a uživatel to v review nemá jak nastavit. Rozhodnutí `LINKED` se tedy uloží do
`receipt_delivery_note_refs` (a `resolution` sedí), ale **materializace ho ignoruje** — podmínka na
řádku 277 je vždy nepravdivá, takže se naskladní všechny ITEM řádky faktury.

**Scénář selhání:**
1. Přijde zboží s dodacím listem `DL-3726026714`, uživatel ho naimportuje jako `DELIVERY_NOTE`
   a potvrdí → vznikne 20 šarží, sklad +20 položek.
2. O týden později dorazí souhrnná faktura LKQ, která tytéž položky opakuje a nahoře má skupinový
   řádek „Dodací list č. 3726026714 celkem …". Import jako `INVOICE`.
3. `matchDeliveryNoteRefs` DL najde → v review vyskočí banner „Faktura kryje dodací list …, který už
   je naskladněn" s volbou. Uživatel zvolí **„Pouze provázat (nenaskladňovat znovu)"** a potvrdí.
4. Očekávané: nevznikne ani jedna šarže, jen vazba. **Skutečné:** vznikne dalších 20 šarží a 20
   pohybů `RECEIPT` — zásoba i hodnota skladu jsou dvojnásobné.

**Proč to vadí:** Tiché zdvojení zásoby a hodnoty skladu (`v_stock_valuation`), zdvojení evidované
pořizovací ceny a rozpad shody s fyzickým stavem. Chyba se projeví až inventurou, kde se ukáže jako
obrovské manko a inventura ho rozpustí do šarží — původ už nedohledatelný. Nejhorší je, že se to stane
právě když uživatel *explicitně* zvolil, že se naskladnit nemá; UI tvrdí opak toho, co backend udělá.

**Návrh řešení:** Nevázat přeskočení na pole, které nikdo neplní. Dvě varianty:
1. **Doplnit prompt + kontrakt** (nejmenší zásah, drží stávající design): do
   `SYSTEM_PROMPT_CORE` přidat instrukci „položkovým řádkům pod skupinovým řádkem dodacího listu
   vyplň týž `deliveryNoteNumber`", opravit javadoc v `DocumentExtractionResult:67` a doplnit
   fallback v `DraftAssembler` — položky mezi dvěma `DELIVERY_NOTE_GROUP` řádky zdědí číslo toho
   předchozího. Test upravit tak, aby ITEM řádek přišel z extrakce **bez** čísla DL.
2. **Nespoléhat na řádkovou vazbu vůbec:** je-li aspoň jedna reference `LINKED`, blokovat
   materializaci celého dokladu a příjemku potvrdit jako „jen doklad, bez naskladnění"
   (u souhrnné faktury, která kryje výhradně už naskladněné DL, je to i významově správné).

Ať tak či tak: dokud to není opravené, nesmí FE nabízet „Pouze provázat" jako splněné —
buď volbu skrýt, nebo hlásit, že položky bude nutné ručně přepnout na `NOTE`.

---

### [SK-2] Doklad od dodavatele bez čitelného IČO lze naskladnit opakovaně — kontrola duplicity se nikdy nechytí

**Severita:** 🔴 VYSOKÝ
**Jistota:** OVĚŘENO
**Kde:**
- `src/main/java/cz/palo/autoservis/service/impl/ReceiptReviewServiceImpl.java:261-270` — pořadí
  operací v `confirm`: **nejdřív** se dodavatel založí, **potom** se kontroluje duplicita:
  ```java
  Long supplierId = resolveSupplier(draft.getSupplier());   // založí nového dodavatele
  ...
  if (importMapper.existsActiveDocument(supplierId, documentNumber, id)) { … 409 … }
  ```
- `…/ReceiptReviewServiceImpl.java:631-649` (`resolveSupplier`) — bez `matchedSupplierId` vždy
  `importMapper.insertSupplier(created)`; žádné hledání podle jména, žádná kontrola.
- `src/main/resources/mapper/warehouse/WarehouseImportMapper.xml:7-9` — `findSupplierIdByIco`
  hledá **jen** podle `registration_number`; `DraftVerificationService.java:222-224` ho volá jen
  když je normalizované IČO nenulové.
- `src/main/resources/mapper/warehouse/WarehouseImportMapper.xml:22-32` (`existsActiveDocument`) —
  klíč je `(supplier_id, invoice_number)`.
- `src/main/resources/db/migration/V43__receipt_cancellation.sql:42-44` — DB pojistka je partial
  unique index nad `(supplier_id, invoice_number)`; dva různí `supplier_id` nekolidují.
- `docs/funkce/import-prijemek.md:125` — dokumentace slibuje pravý opak: *„Duplicitní import se hlásí
  (409) jen když je dodavatel napárovaný; **drafty bez dodavatele hlídá až potvrzení**."*

**Co je špatně:** U dokladu, ze kterého se nepodaří přečíst IČO (ručně psaný dodací list, foto,
malý dodavatel bez IČO na hlavičce — což jsou scénáře, které projekt vědomě podporuje, viz prompt
`PdfDocumentExtractionService.java:41-48` „…nebo je psaný RUKOU, čti údaje pečlivě vizuálně…" a
`import-prijemek.md:117`), zůstane
`matchedSupplierId = null`. Potvrzení proto **pokaždé vloží nový řádek do `warehouse.suppliers`**
s `registration_number = NULL` (NULL v UNIQUE nekoliduje) a teprve pak se ptá, jestli tenhle
dodavatel už doklad daného čísla má. Nový dodavatel žádný doklad nemá — kontrola je z definice
prázdná. Import ji přeskočí taky (`WarehouseImportServiceImpl.java:73` vyžaduje `supplierId != null`).

**Scénář selhání:**
1. Mechanik vyfotí ručně psaný dodací list „DL 145" od místního dodavatele bez IČO, naimportuje,
   potvrdí. Vznikne dodavatel #41 „Autodíly Novák" (IČO NULL) a 6 šarží.
2. Druhý den totéž udělá kolega (nevěděl, že už je to hotové) — nebo tentýž člověk po přerušené práci.
3. Import: `findSupplierIdByIco(null)` se ani nezavolá → `matchedSupplierId = null` → dedup přeskočen → 201.
4. Confirm: `resolveSupplier` založí dodavatele #42 „Autodíly Novák" (opět IČO NULL);
   `existsActiveDocument(42, "DL 145", …)` → `false` → **naskladní se dalších 6 šarží**.
5. Očekávané: 409 `DUPLICATE_IMPORT`. Skutečné: 200 OK, dvojnásobná zásoba, dva dodavatelé se
   stejným jménem, `supplier_products` (samoučení párování) rozštěpené mezi ně.

**Proč to vadí:** Peníze a data — dvojnásobná zásoba a dvojnásobná hodnota skladu bez jakéhokoli
varování; roztříštěná evidence dodavatelů (přehled „Pod minimem" doporučuje objednat u kterékoli
z duplicit); samoučení převodníku se u takového dodavatele nikdy nerozjede, protože klíč
`(supplier_id, supplier_sku)` míří pokaždé jinam. A dokumentovaná záruka („drafty bez dodavatele
hlídá až potvrzení") neplatí.

**Návrh řešení (rozhodnutí uživatele o variantě):**
1. **Minimum:** v `confirm` provést dedup **před** `resolveSupplier` — pro draft bez
   `matchedSupplierId` hledat existující doklad podle `(supplier_name_snapshot, invoice_number)`
   nebo aspoň podle `invoice_number` s upozorněním. Vyžaduje jen přeuspořádání a jeden select.
2. **Systémově:** `resolveSupplier` nesmí zakládat dodavatele „naslepo" — když IČO chybí, dohledat
   podle normalizovaného jména (`lower(unaccent(name))`) a při shodě použít existující kartu;
   při neshodě založit. Doplnit tomu odpovídající částečný unikátní index nad jménem pro dodavatele
   bez IČO.
3. **Doplňkově:** dát reviewerovi možnost vybrat existujícího dodavatele i u AI importu (pole
   `matchedSupplierId` v draftu backend respektuje, FE ho ale nenabízí — dnes lze jen doplnit IČO).

---

### [SK-3] Potvrzení příjemky naskladní na deaktivovanou kartu — zásoba, kterou nevidí ocenění ani inventura

**Severita:** 🟠 STŘEDNÍ
**Jistota:** OVĚŘENO
**Kde:**
- `src/main/java/cz/palo/autoservis/service/impl/ReceiptReviewServiceImpl.java:656-660` —
  `resolveProduct` vrátí uložené `productId` bez jakékoli kontroly:
  ```java
  DraftLine.ProductMatch match = line.getProductMatch();
  if (match != null && match.getProductId() != null) {
      return match.getProductId();
  }
  ```
  (`confirm` už párovací kaskádu nespouští — pracuje s `productMatch` zmrazeným v JSONB draftu.)
- `src/main/java/cz/palo/autoservis/service/impl/ProductServiceImpl.java:140-149` — guard TD-28
  brání deaktivovat kartu se zásobou, ale opačný směr (přidat zásobu na deaktivovanou kartu) nikdo nehlídá.
- `src/main/resources/db/migration/V42__v_stock_valuation.sql:31` — `WHERE p.is_active = TRUE`
  → hodnota takové zásoby v přehledu chybí.
- `src/main/resources/mapper/warehouse/StockTakeMapper.xml:119` (`snapshotActiveProducts`) —
  `WHERE p.is_active = TRUE` → díl se nedostane ani do inventury; potvrzeno testem
  `StockTakeStateMachineTest.open_skipsDeactivatedProducts` (řádky 72-83), který to má jako *záměr*.
- `src/main/resources/mapper/warehouse/WarehouseMapper.xml:46-48` — seznam skladu s `activeOnly`
  kartu vyfiltruje.

**Co je špatně:** `productMatch.productId` se do draftu zapíše ve chvíli importu / uložení konceptu
a v JSONB payloadu zmrzne. Potvrzení ho použije doslova. Mezi tím se karta může deaktivovat —
a `POST /warehouse/products/{id}` to dovolí právě tehdy, když je zásoba nulová, tedy přesně u dílů,
které v draftu čekají na naskladnění. Vznikne šarže + pohyb `RECEIPT` na neaktivní kartě, trigger
zvedne `quantity_on_hand` a invariant „deaktivovaná karta nemá zásobu" (TD-28) je porušený.

**Scénář selhání:**
1. Importuje se faktura, řádek „olejový filtr OF-123" se napáruje `AUTO` na kartu #55 (zásoba 0).
   Doklad zůstane `PENDING_REVIEW` (čeká se na kontrolu).
2. Někdo mezitím uklízí katalog a kartu #55 deaktivuje — projde, `quantity_on_hand = 0`.
3. Reviewer příjemku potvrdí. Vznikne šarže na produktu #55 a pohyb `RECEIPT` +10.
4. Očekávané: buď chyba („karta je deaktivovaná, aktivujte ji nebo vyberte jinou“), nebo
   automatická reaktivace. **Skutečné:** 200 OK, na skladě leží 10 kusů, které:
   - nejsou v hodnotě skladu (`GET /warehouse/stock-valuation`),
   - nejsou v seznamu skladu s filtrem „jen aktivní",
   - **nedostanou se do inventury** → fyzicky napočítané kusy se v soupisu nemají k čemu přiřadit,
   - už nelze kartu ani znovu deaktivovat (`PRODUCT_HAS_STOCK`), aniž by se zásoba nejdřív odepsala.
   Zboží přitom jde normálně vydat do zakázky (`findImportableItems` nefiltruje `is_active`).

**Proč to vadí:** Rozpor mezi hodnotou skladu v aplikaci a skutečností (peníze); zásoba mimo
inventarizaci (§ 29–30 zákona o účetnictví — inventura ji nemůže potvrdit ani opravit); porušení
invariantu, který projekt vědomě zavedl jako TD-28.

**Návrh řešení:** V `confirm` (nebo přímo v `resolveProduct`) ověřit, že cílová karta existuje **a je
aktivní** — mapperem `findProductIdByIdActive` / rozšířením `WarehouseImportMapper`. Když aktivní není:
buď 422 `PRODUCT_INACTIVE` s uvedením řádku (uživatel v review přepáruje nebo kartu aktivuje), nebo
kartu automaticky reaktivovat (naskladnění deaktivovaného dílu je legitimní signál, že se zase používá)
— *rozhodnutí uživatele*, který z těch dvou je provozně příjemnější. Totéž platí pro `productId`
přicházející z klienta ve stavu `CONFIRMED`: `sanitizeClientDraft`
(`ReceiptReviewServiceImpl.java:476-499`) `productMatch` vůbec neošetřuje, takže existenci a
aktivnost karty dnes negarantuje nic než FK.

---

### [SK-4] Deaktivovaný dodavatel nebo karta se stejným SKU zablokuje potvrzení nesrozumitelnou 422

**Severita:** 🟠 STŘEDNÍ
**Jistota:** OVĚŘENO
**Kde:**
- `src/main/resources/mapper/warehouse/WarehouseImportMapper.xml:8` —
  `findSupplierIdByIco`: `… WHERE registration_number = #{ico} AND is_active = TRUE`
- `src/main/resources/mapper/warehouse/WarehouseImportMapper.xml:54` —
  `findProductIdBySku`: `… WHERE sku = #{sku} AND is_active = TRUE`
- `src/main/java/cz/palo/autoservis/service/impl/ReceiptReviewServiceImpl.java:647` a `:664-679` —
  když hledání nic nevrátí, následuje bezpodmínečný `insertSupplier` / `insertProduct`.
- `src/main/resources/db/migration/V18__init_warehouse_schema.sql:114` — `uq_products_sku UNIQUE (sku)`
  (bez ohledu na `is_active`); `V30__rename_supplier_identifier_columns.sql:25` —
  `uq_suppliers_registration_number` (dtto).
- `src/main/java/cz/palo/autoservis/exception/GlobalExceptionHandler.java:370-379` — výsledkem je
  422 `DATA_INTEGRITY_VIOLATION` s hláškou „Data se nepodařilo uložit“.
- Kontrast: `ProductServiceImpl.java:89-91` (`create`) používá `existsBySku`, které `is_active`
  **nefiltruje** (`WarehouseMapper.xml:197-199`), takže ruční zakládání karty vrací čistou
  422 `DUPLICATE_SKU`. Import má tedy jinou (horší) sémantiku než CRUD.

**Co je špatně:** Párovací i „záchranné" dotazy v importu vidí jen aktivní záznamy, ale unikátní
constrainty v DB platí i pro deaktivované. Deaktivovaný dodavatel / deaktivovaná karta tak nejsou
neviditelné — jsou to miny.

**Scénář selhání (dodavatel):**
1. Dodavatel „LKQ CZ" (IČO 25716379) se přestane používat, uživatel ho deaktivuje.
2. Za měsíc dorazí poslední faktura. Import proběhne (201), `SUPPLIER_KNOWN` je `false`,
   `matchState = NONE` — vypadá to jako nový dodavatel.
3. Potvrzení → `insertSupplier` s `registration_number = '25716379'` → porušení
   `uq_suppliers_registration_number` → **422 „Zadaná data porušují databázové omezení / Data se
   nepodařilo uložit"**. Celá transakce se odroluje.
4. Uživatel nemá z hlášky jak zjistit, co dělat. Příjemku nelze potvrdit, dokud někdo netrefí, že má
   jít do „Sklad → Dodavatelé", vypnout filtr „jen aktivní" a dodavatele aktivovat.

**Scénář selhání (produkt):** totéž s deaktivovanou kartou, jejíž SKU se objeví na novém dokladu —
kaskáda ji nenajde (všechny tři kroky filtrují `is_active = TRUE`, viz `ProductMatchingMapper.xml:15,
25, 41`), `resolveProduct` ji nenajde taky a `insertProduct` narazí na `uq_products_sku`.

**Proč to vadí:** Zablokovaný provoz (příjemku nelze naskladnit) plus chybová hláška, ze které
nejde příčinu odvodit — v logu je sice `ex.getMostSpecificCause()`, ale uživatel ho nevidí. Žádný
test to nepokrývá (grep přes `src/test/java` — testy deaktivace se týkají jen CRUD a inventury).

**Návrh řešení:** Sjednotit s CRUD sémantikou — v `resolveSupplier` / `resolveProduct` hledat
**včetně neaktivních** (druhý dotaz `…BySkuIncludingInactive` / `…ByIcoIncludingInactive`) a při nálezu
vrátit srozumitelnou 422 (`SUPPLIER_INACTIVE` / `PRODUCT_INACTIVE` s `id` v `params`), případně
nabídnout automatickou reaktivaci. Souvisí přímo se SK-3, dá se opravit jedním zásahem.

---

### [SK-5] „VERIFIED" u hodnot, které si kód sám dopočítal — u ručně psaného dodacího listu projdou všechny kontroly tautologicky

**Severita:** 🟠 STŘEDNÍ
**Jistota:** OVĚŘENO
**Kde:**
- `src/main/java/cz/palo/autoservis/service/DraftAssembler.java:111-134` (`deriveLineAmounts`) —
  zpětný dopočet: `totalExcl = totalIncl / (1+sazba)` (ř. 120-123) a poté
  `unitPrice = totalExcl / qty` (ř. 125-129).
- `src/main/java/cz/palo/autoservis/service/DraftVerificationService.java:83-94` — kontrola
  `LINE_MATH` počítá právě zpětnou cestu:
  ```java
  boolean exclOk = withinTolerance(qty.multiply(unitPrice), totalExcl);
  BigDecimal factor = BigDecimal.ONE.add(BigDecimal.valueOf(rate).movePointLeft(2));
  boolean inclOk = withinTolerance(totalExcl.multiply(factor), totalIncl);
  ```
  a při `ok` povyšuje `quantity`, `unitPriceExclVat`, `totalExclVat`, `totalInclVat` na `VERIFIED`
  (ř. 89-94).
- `DraftAssembler.java:160-174` — u dokladu bez rekapitulace se hlavička dopočítá z řádků
  (`subtotal = Σ totalExcl`, `vatAmount = Σ incl − Σ excl`, `totalAmount = Σ totalIncl`).
- `DraftVerificationService.java:127-131` a `:104-107` — `RECAP_SUM` a `LINES_SUM_VS_RECAP` bez
  rekapitulace vrací `true` a **do `checks` se ani nezapíšou**.
- `DraftVerificationService.java:149-182` — `SUBTOTAL_PLUS_VAT_EQ_TOTAL` a `LINES_SUM_VS_TOTAL`
  porovnávají hlavičku s tím, z čeho byla právě spočítaná.
- `DraftVerificationService.java:52-58` — při `reconciliationOk` dostanou `VERIFIED` i hlavičkové součty.
- Kontrakt, který to porušuje: `docs/funkce/import-prijemek.md:27` — *„`VERIFIED` = přečteno/dopočteno
  **a** ověřeno křížovou kontrolou"*; `model/draft/FieldState.java:17-18` — *„Přečteno/dopočteno
  A ověřeno deterministickou kontrolou kódu."*

**Co je špatně:** Kontroly jsou napsané správně pro doklad, který tiskne **redundantní** čísla
(množství × cena i součet, základ i s DPH, rekapitulaci i hlavičku). U dokladu, který je netiskne,
si chybějící hodnoty dopočte `DraftAssembler` — a `DraftVerificationService` je pak porovná stejným
vzorcem. Kontrola tak ověřuje vlastní aritmetiku, ne doklad. Výsledkem je zelené „ověřeno" nad
hodnotami, které přečetl výhradně jazykový model.

**Scénář selhání:** Ručně psaný dodací list vyfocený mobilem (přesně scénář z
`import-prijemek.md:117` a promptu `PdfDocumentExtractionService.java:45-48` — *„Když řádek uvádí
jen cenu S DPH … jednotkovou cenu i základ nech ABSENT — dopočítá je kód. NEodhaduj je."*), který má
vyplněný jen sloupec „Cena celkem s DPH":
1. Model přečte `quantity = 4` (ve skutečnosti je tam 1 — rozmazaná čtyřka) a `totalInclVat = 1 210`;
   `unitPriceExclVat` a `totalExclVat` nechá `ABSENT` (prompt to tak vyžaduje).
2. `deriveLineAmounts`: `totalExcl = 1210 / 1,21 = 1000,00` (DERIVED),
   `unitPrice = 1000 / 4 = 250,00` (DERIVED).
3. `verifyLines`: `exclOk` = `4 × 250 = 1000` ✔ (ale to je jen zpětný přepočet vlastního dělení),
   `inclOk` = `1000 × 1,21 = 1210` ✔ (dtto). → `LINE_MATH ok` → množství, jednotková cena i oba
   součty se povýší na **VERIFIED**.
4. Hlavička nemá rekapitulaci → `subtotal/vatAmount/totalAmount` se dopočtou z řádků a obě zbylé
   kontroly projdou triviálně → `reconciliation_ok = true`, hlavička rovněž **VERIFIED**.
5. Kontrolor vidí samá zelená pole, žádnou neprošlou kontrolu a potvrdí. Na skladě jsou 4 kusy
   za 250 Kč místo 1 kusu za 1 000 Kč.

**Proč to vadí:** Jediná reálná pojistka celé AI pipeline je člověk v review — a jemu se ukazuje
signál, který v tomhle typu dokladu nic neznamená. Špatné množství jde přímo do zásoby, špatná
jednotková cena do ocenění skladu i do marže zakázky (`purchasePrice` se přebírá z šarže,
`OrderItemServiceImpl.java:201`). Zároveň to je porušení vlastní definice `VERIFIED` (`FieldState:17`)
a de facto obcházení R-15: kód sice počítá, ale svůj výpočet vydává za ověření.

**Návrh řešení:** Rozlišit „dopočteno" od „ověřeno".
1. `deriveLineAmounts` si poznamená, které pole dopočetlo (stav `DERIVED` už to nese) — a
   `verifyLines` musí přeskočit tu polovinu kontroly, jejíž oba vstupy jsou `DERIVED`/plynou
   ze stejného vzorce: `exclOk` počítat jen když `unitPriceExclVat` **není** `DERIVED` ze zpětného
   kroku 3; `inclOk` jen když `totalInclVat` není `DERIVED` z kroku 4 (resp. `totalExclVat` z kroku 2).
2. Když po tomhle na řádku nezůstane žádná nezávislá kontrola, `LINE_MATH` neoznačovat jako `ok`,
   ale zavést třetí výsledek („nelze ověřit") a pole ponechat na `VERBATIM`/`DERIVED` — na FE žlutě.
3. Totéž pro hlavičku: `SUBTOTAL_PLUS_VAT_EQ_TOTAL` a `LINES_SUM_VS_TOTAL` vyhodnocovat jen tehdy,
   když hlavičkové součty přišly z dokladu (`VERBATIM`), ne když je právě sečetl `mapHeader`.

*Poznámka:* `TrackedField.verify()` (`TrackedField.java:37-41`) záměrně **nepovyšuje** `DEFAULTED`,
takže výchozí sazba DPH 21 % zůstane žlutá — ta část kontraktu drží a je vidět, že autor na tenhle
problém myslel; jen nedotáhl stejnou logiku na `DERIVED`.

---

### [SK-6] Ruční příjemka: IČO omezené na 15 znaků, přestože sloupec i ostatní DTO mají 30

**Severita:** 🟡 NÍZKÝ
**Jistota:** OVĚŘENO
**Kde:**
- `src/main/java/cz/palo/autoservis/model/dto/warehouse/ReceiptDto.java:112` —
  `@Size(max = 15, message = "IČO může mít nejvýše 15 znaků.")`
- `src/main/resources/db/migration/V30__rename_supplier_identifier_columns.sql:21` —
  `ALTER COLUMN registration_number TYPE VARCHAR(30)` s odůvodněním „delší typ pokryje i zahraniční
  formáty registračních čísel (např. německé 'HRB 247469 B')".
- `src/main/java/cz/palo/autoservis/model/dto/warehouse/SupplierDto.java:48` — `@Size(max = 30)`.

**Co je špatně:** `CreateDraftRequest` zůstal na původní délce z V18 (VARCHAR(15)); V30 ji zvedla
na 30 a `SupplierDto` i `docs/api.md:307` to reflektují, ruční příjemka ne.

**Scénář selhání:** Uživatel zakládá ruční příjemku pro německého dodavatele a do pole IČO zadá
„HRB 247469 B" (12 znaků — projde) nebo delší rakouské „FN 123456 x, FB-Gericht" → 400 s hláškou
o 15 znacích, ačkoli tentýž údaj přes `PUT /suppliers/{id}` projde.

**Proč to vadí:** Nekonzistentní API, drobná provozní překážka u zahraničních dodavatelů. Žádná
ztráta dat.

**Návrh řešení:** Sjednotit na `@Size(max = 30)`.

---

### [SK-7] Souběžné otevření dvou inventur vrátí 422 z DB constraintu místo dokumentované 409

**Severita:** 🟡 NÍZKÝ
**Jistota:** OVĚŘENO
**Kde:**
- `src/main/java/cz/palo/autoservis/service/impl/StockTakeServiceImpl.java:108-117` —
  check-then-act: `mapper.findOpenId().ifPresent(… ConflictException …)` a až pak `mapper.insert`.
- `src/main/resources/db/migration/V44__init_stock_takes.sql:68-70` — `uq_stock_take_single_open`
  (partial unique index) je jediná skutečná pojistka.
- `docs/api.md:233` slibuje u `POST /stock-takes` status `409 STOCK_TAKE_ALREADY_OPEN`.

**Co je špatně:** V okně mezi `findOpenId` a `insert` projdou oba požadavky kontrolou; druhý spadne
na unikátním indexu → `DataIntegrityViolationException` → 422 `DATA_INTEGRITY_VIOLATION`
(`GlobalExceptionHandler.java:370-379`), ne 409.

**Scénář selhání:** Dva uživatelé kliknou „Zahájit inventuru" ve stejnou vteřinu → jeden dostane
201, druhý „Zadaná data porušují databázové omezení" místo „Inventura … je otevřená".

**Proč to vadí:** Jen srozumitelnost hlášky a rozpor s `api.md`; data jsou v pořádku (index drží
invariant). Okno je velmi úzké, v jednodílenském provozu prakticky nedosažitelné.

**Návrh řešení:** Zachytit `DuplicateKeyException` kolem `mapper.insert` a přeložit na
`ConflictException("STOCK_TAKE_ALREADY_OPEN", …)` — stejný vzor, jaký modul už používá u guarded
přechodů.

---

### [SK-8] Měrnou jednotku karty lze změnit i u dílu se zásobou — historické šarže se tiše přeznačí

**Severita:** 🟡 NÍZKÝ
**Jistota:** OVĚŘENO
**Kde:**
- `src/main/java/cz/palo/autoservis/service/impl/ProductServiceImpl.java:107-125` (`update`) —
  jediná kontrola je platnost jednotky (`requireValidUnit`), nikoli existence zásoby.
- `src/main/resources/mapper/warehouse/WarehouseMapper.xml:174-187` — `unit` se přepíše natvrdo.
- `src/main/java/cz/palo/autoservis/service/impl/OrderItemServiceImpl.java:200` — položka zakázky
  bere jednotku z **aktuální** karty (`item.setUnit(product.getUnit())`), ne ze šarže.

**Co je špatně:** `goods_receipt_items` jednotku nenese — je jen na kartě. Změna „l" → „ks" u dílu,
kterého je na skladě 12,5, mlčky přeznačí význam všech existujících šarží, zůstatků i historických
pohybů.

**Scénář selhání:** Karta „Motorový olej 5W-30" vedená v litrech (zásoba 12,5 l), uživatel opraví
jednotku na „bal" (protože se to tak objednává) → sklad tvrdí 12,5 bal; ocenění (cena za litr ×
12,5) i výdej do zakázky pak počítají s balením za cenu litru.

**Proč to vadí:** Nepřesná evidence a chybná cena na zakázce. Nejde o ztrátu dat a je to snadno
vratné (zpětná změna jednotky), proto nízká severita — ale je to jediné pole karty, jehož změna
přepisuje význam už zaúčtovaných čísel.

**Návrh řešení:** Buď změnu `unit` zakázat, dokud `quantity_on_hand > 0` (vzor TD-28 /
`PRODUCT_HAS_STOCK`), nebo ji povolit jen s explicitním potvrzením. *Rozhodnutí uživatele* — přísný
zákaz je konzistentní se zbytkem modulu, ale u překlepu při zakládání karty otravný.

---

## Co bylo ověřeno jako v pořádku

**Ledger a integrita zásoby**
- `stock_movements` je append-only i na úrovni DB — `trg_movements_append_only`
  (`V52:29-32`) zakáže UPDATE i DELETE; aplikace pohyb nikdy needituje.
- Stav skladu se **nikdy nezapisuje přímo**: `products.quantity_on_hand` a
  `goods_receipt_items.quantity_remaining` mění výhradně trigger `fn_apply_stock_movement`
  (`V18:283-302`). Ověřeno u všech pěti zapisujících míst (`confirm`, `cancel`,
  `registerManualMovement`, `importFromReceipt`/`delete` u položek zakázky, `close` inventury) —
  `WarehouseMapper.insert`/`update` sloupec `quantity_on_hand` vědomě vynechávají
  (`WarehouseMapper.xml:161`, `:173`).
- Znaménka jsou pokrytá CHECKem pro **všech 6** hodnot `MovementType`: `RECEIPT`/`ISSUE_RETURN` > 0,
  `ADJUSTMENT` ≠ 0, `ISSUE`/`RETURN`/`WRITE_OFF` < 0 (`V29:11-20`) — enum `MovementType.java`
  a CHECK se přesně kryjí, žádný typ „nespadne mimo".
- Složený FK `fk_mov_batch_product (batch_id, product_id)` (`V52:38-42`) znemožňuje pohyb proti
  šarži cizího produktu i na úrovni DB, nejen v service (`ProductServiceImpl.java:232-238`).
- **Zápornou zásobu se nepodařilo vyrobit.** `chk_products_qty` a `chk_items_remaining` (`V18:115`,
  `:211`) jsou nedotčené a všechny cesty úbytku si šarže předem zamykají `FOR UPDATE`
  (`GoodsReceiptMapper.xml:115-124`, `ReceiptReviewMapper.xml:213-230`,
  `StockTakeMapper.xml:147-164`), takže se souběžné výdeje serializují (oprava K6 drží).
- Součet zůstatků šarží odpovídá `quantity_on_hand`: `RECEIPT` zakládá šarži rovnou s
  `quantity_remaining = quantity_received` a trigger u `RECEIPT` šarži nemění; inventurní přebytek
  naopak zakládá šarži s 0 a dorovná ji pohybem. Obě cesty vycházejí konzistentně.

**Review workflow a souběh**
- Všechny čtyři přechody stavu jsou guarded UPDATE s kontrolou počtu řádků → 409
  (`ReceiptReviewMapper.xml:161-254`, `StockTakeMapper.xml:175-193`). Dvojí `confirm` je bezpečný:
  druhá transakce sice materializaci provede, ale guarded UPDATE vrátí 0 řádků a `ConflictException`
  (RuntimeException) celou transakci odroluje.
- Dvojí potvrzení téhož dokladu **s napárovaným dodavatelem** zachytí i DB — partial unique index
  `uq_receipt_supplier_docno` (V43). (Díra je jen u dodavatele bez IČO — SK-2.)
- `TD-59` skutečně drží: `requireWellFormedDraft` + `sanitizeClientDraft`
  (`ReceiptReviewServiceImpl.java:462-510`) srazí padělaný `VERIFIED`/`DEFAULTED` na `VERBATIM`
  a `documentType`/`sourceChannel` se berou autoritativně ze sloupců, ne z těla.
- Completeness gate (`:531-621`) sbírá chyby najednou, blokuje záporné ceny (E6.5), jednotky mimo
  číselník (Z-4), nevyřešené `SUGGESTED` párování, nerozhodnuté DL reference a jinou měnu než CZK (R-F).
- Storno příjemky (`:377-445`) kontroluje obojí — dotčený zůstatek šarže *i* vazbu z `order_items` —
  a šarže si zamyká `FOR UPDATE`; kompenzace je záporný `ADJUSTMENT`, nic se nemaže.

**Inventura**
- Rozdíl se opravdu počítá proti **aktuálnímu** stavu (`StockTakeMapper.xml:133-134`), ne proti
  snapshotu — souběžný výdej během počítání se nepřepíše. Prošel jsem tři souběhové scénáře
  (výdej mezi `findItems` a zápisem korekce): výsledek je buď matematicky správný, nebo skončí
  čistou 422 `STOCK_TAKE_SHORTAGE_EXCEEDS_BATCHES`; tichá chyba nevzniká.
- `counted_quantity IS NULL` = „nepočítáno" se skutečně přeskočí (`StockTakeServiceImpl.java:178`).
- Manko jde FIFO podle `gr.issue_date` se zámkem (`StockTakeMapper.xml:147-164`), přebytek zakládá
  pseudo-příjemku `STOCK_TAKE` bez DPH s číslem inventury (`StockTakeServiceImpl.java:243-298`) —
  odpovídá ČÚS 007 popsanému v `docs/funkce/inventura.md`.
- Číslo `INV-{rok}-NNNN` generuje DB trigger s advisory lockem a per-rok MAX+1 včetně guardu
  proti přetečení (`V61:20-53`) — stejný vzor jako ZAK/faktury.
- Dvojí uzavření: druhá transakce sice pohyby vytvoří, ale guarded `close` vrátí 0 řádků → 409
  a rollback.

**Import a párování**
- Import ukládá **jen** hlavičku + JSONB draft; produkty, šarže ani dodavatel při importu nevznikají
  (`WarehouseImportServiceImpl.java:48-127`) — přesně jak slibuje `import-prijemek.md`.
- Párovací kaskáda nikdy nepovýší heuristiku na `AUTO`: převodník `supplier_products` → AUTO,
  číslo dílu i podobnost názvu jen `SUGGESTED` (`ProductMatchingService.java:54-101`); guard proti
  prázdnému seznamu variant (`:77`) brání `IN ()`.
- Samoučení převodníku se zapisuje jen s katalogovým číslem (`supplier_sku` je NOT NULL) —
  `ReceiptReviewServiceImpl.java:286-290`.
- ISDOC parser má vypnuté DOCTYPE i externí entity (XXE), odmítá dobropisy/vrubopisy 422 a
  neznámý kód jednotky **nepřekládá** (`IsdocParser.java:90-120`, `:224-229`) — správně, dosazení
  defaultu by si vymyslelo měrnou jednotku.
- `existsActiveDocument` a `findDeliveryNoteReceiptId` správně ignorují `REJECTED` i `CANCELLED`,
  takže zamítnutý/stornovaný doklad uvolní číslo (`WarehouseImportMapper.xml:22-32`, `:73-87`).

**Výdej a vratky**
- Ověřoval jsem hypotézu, že editací položky zakázky lze rozejít vydané množství se skladem —
  **neplatí**: `OrderItemConverter.applyUpdate` u položky se šarží (`goodsReceiptItemId != null`)
  zamyká `quantity`, `unit`, `vatRate`, `purchasePrice` i `itemType`, měnit lze jen název, prodejní
  cenu, pozici a poznámku. `ISSUE_RETURN` při smazání položky tedy vždy vrací přesně vydané množství.
- `importFromReceipt` agreguje požadavky per šarže **před** kontrolou zůstatku a šarže zamyká
  `FOR UPDATE` (`OrderItemServiceImpl.java:136-176`) — oprava K6 drží i pro duplicitní řádky v jednom
  requestu.
- Mutace položek zakázky s existující fakturou jsou zablokované (`requireOrderNotInvoiced`), takže
  vrácení zboží na sklad nemůže rozjet už vystavený doklad.

**Ostatní**
- Šarže z inventurního přebytku (bez dodavatele) jsou vidět jak ve view (`V54`), tak na kartě dílu
  (`WarehouseMapper.xml:130` — `COALESCE(s.name, gr.supplier_name_snapshot)`), takže je lze ručně
  korigovat (K-14 skutečně vyřešeno).
- `v_stock_valuation` počítá ze **skutečných** pořizovacích cen po šaržích se zaokrouhlením po
  šarži (V42) a `LEFT JOIN` nezamlčí kartu bez zásoby.
- „Pod minimem" je opt-in přes `min_stock_level` a doporučení dodavatele bere z převodníku
  (`WarehouseMapper.xml:218-245`); díl bez záznamu v převodníku z výpisu nevypadne.
- `source_pdf` (BYTEA) se nikdy nenačítá v seznamu ani v detailu — má vlastní select.
- `docs/api.md` sedí s kódem u všech kontrolovaných endpointů skladu včetně
  `POST /receipts/{id}/reject` (řádek 347), stavových kódů a chybových kódů.

## Otevřené otázky pro uživatele

1. **Deaktivovaná karta / dodavatel při potvrzení příjemky (SK-3, SK-4).** Co má systém udělat —
   *odmítnout* (srozumitelná 422 „karta je deaktivovaná, aktivujte ji nebo přepárujte"), nebo
   *automaticky reaktivovat* (nové zboží na skladě je samo o sobě důkaz, že se díl zase používá)?
   Odmítnutí je konzistentnější s TD-28, automatická reaktivace je provozně plynulejší. Mé
   doporučení: **odmítnout u dodavatele** (deaktivace dodavatele je vědomé obchodní rozhodnutí)
   a **reaktivovat u karty** (deaktivace karty je většinou jen úklid katalogu). Jistota doporučení:
   střední — závisí na tom, jak servis deaktivaci používá.

2. **Dodavatel bez IČO (SK-2).** Má aplikace u dokladu bez čitelného IČO dohledávat existujícího
   dodavatele podle jména (riziko: dva různí dodavatelé s podobným jménem se slijí), nebo má
   reviewer dostat výběr z existujících dodavatelů a založení nového musí být vědomý klik?
   Doporučuji druhé — je to jediné řešení, které zároveň zavře dvojí naskladnění i tříštění
   převodníku. Vyžaduje ale zásah do FE kontrolní obrazovky.

3. **Dedup DL ↔ faktura (SK-1) — která varianta.** Doplnit prompt tak, aby model tagoval položkové
   řádky číslem dodacího listu (levné, ale závislé na chování modelu), nebo přejít na tvrdší pravidlo
   „je-li aspoň jedna reference `LINKED`, doklad se nenaskladňuje vůbec" (deterministické, ale
   nezvládne fakturu, která kromě krytého DL obsahuje i nové položky)? Doporučuji **obojí**:
   deterministické pravidlo jako pojistka + prompt pro jemnější případ.

4. **Čas skladového pohybu.** `moved_at` je vždy `NOW()` (aplikace ho nikdy nenastavuje,
   `WarehouseImportMapper.xml:102-111`), takže příjem faktury z 20. 12. potvrzený 5. 1. se v ledgeru
   objeví v novém roce. Pro účetní uzávěrku a inventuru k datu to může vadit. Je to záměr
   („ledger zaznamenává, kdy jsme se to dozvěděli"), nebo má `RECEIPT` nést `issue_date` dokladu?
   Věc účetní praxe — *rozhodnutí uživatele*, případně po konzultaci s účetní (R-7).

5. **Potvrzení příjemky s `reconciliation_ok = false`.** Dnes projde — completeness gate kontroluje
   jen přítomnost hodnot, ne výsledky aritmetických kontrol
   (`ReceiptReviewServiceImpl.java:254-256`). Chápu to jako záměr („kontrolor má poslední slovo"),
   ale nikde to není napsané. Chcete to explicitně zdokumentovat, nebo vyžadovat u neprošlé
   rekonciliace potvrzení navíc (druhý klik / povinná poznámka)?

---

## Přesahy do jiných průchodů

Marže zakázky přebírá nákupní cenu ze šarže (`OrderItemServiceImpl.java:201`), takže chybná cena
z importu (SK-5) se propíše do zakázky a faktury; autorizace skladových endpointů (`ProductController`,
`StockValuationController` bez `@PreAuthorize`) je vědomý dluh TD-68 a patří do bezpečnostního
průchodu; FE kontrolní obrazovka příjemky neumí vybrat existujícího dodavatele ani přiřadit číslo DL
k řádku — to je frontendový důsledek SK-1/SK-2 a patří do FE průchodu.
