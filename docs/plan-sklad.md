# plan-sklad.md — Prováděcí plán rozvoje skladu (červenec 2026)

> Zdroj: [analyza-sklad-2026-07.md](analyza-sklad-2026-07.md) — nálezy S-x, změny Z-x, přidání P-x,
> rozhodnutí R-A…R-H (§3d tamtéž; v úkolech níže jsou relevantní rozhodnutí zopakována, číst celou analýzu není nutné).
> Vzor a pravidla převzata z [plan-oprav.md](plan-oprav.md): úkoly jsou malé, mají přesné soubory,
> postup, akceptační kritéria a testy, aby je mohl samostatně provést i slabší model.
>
> **Rozsah:** fáze E1–E8 rozpracované do úkolů a **hotové**. Otevřené zůstává jen E5b
> (dobropis jako doklad, rozhodnutí R-G) — rámcově v §6.

---

## 1. Pravidla pro vykonavatele (platí pro KAŽDÝ úkol)

Platí všech 6 pravidel z [plan-oprav.md](plan-oprav.md) §1 (jen zadaný úkol; čti CLAUDE.md + pole *Čti*;
po dokončení ověř kritéria, spusť testy, aktualizuj dokumentaci, zaškrtni checkbox, **necommituj**;
při nejistotě se zastav; styl okolního kódu — sklad má javadoc česky). Navíc pro tento plán:

7. **Migrace:** na rozdíl od plan-oprav některé úkoly migraci VYŽADUJÍ — vždy přes skill `nova-migrace`
   (číslování, šablona, checklist, sync `databaze.md`). Číslo V{n} v zadání je orientační — skill určí skutečné další volné.
8. **Skladový invariant:** `products.quantity_on_hand` a `goods_receipt_items.quantity_remaining` NIKDY
   nezapisuje aplikace — mění je výhradně trigger `fn_apply_stock_movement` po INSERTu do `stock_movements`.
   Každý úkol, který hýbe zásobou, vkládá pohyb; nikdy neupdatuje stavy přímo.
9. **Nové procesy = funkční dokument + nápověda:** kde to úkol uvádí, patří do akceptačních kritérií
   i `docs/funkce/*.md` a článek v `frontend/…/src/help/` (vzor: `prijem-zbozi.md`).

## 2. Jak úkoly zadávat (pokyny pro uživatele)

Stejný postup jako u plan-oprav §2: každý úkol v nové session, vlastní větev (`sklad/E2-1-pohyby` apod.),
šablona zadání:

```
Přečti si docs/plan-sklad.md — nejdřív celou sekci §1 (pravidla), pak úkol <ID>.
Proveď úkol <ID> přesně podle zadání — provedení schvaluji ("udělej to ty").
Pracuj výhradně na tomto úkolu. Po dokončení:
- vypiš akceptační kritéria a u každého ✓/✗ s důkazem (výstup příkazu, číslo řádku),
- spusť ověření dle §5 a vlož výstup,
- aktualizuj dokumentaci dle zadání a zaškrtni checkbox v §3.
Necommituj.
```

**Pořadí a závislosti:** E1 → E2 → E3 → E4 (v rámci fáze podle čísel). Tvrdé závislosti:
- **E2.1 před E4.2** (storno používá mechaniku záporného ADJUSTMENT z E2.1),
- **E3.1 před E3.2** (endpoint čte view).
Nikdy dva úkoly současně — E2/E4 sahají do týchž souborů (ReceiptReview/Warehouse service+mappery).

**Před prvním úkolem:** commitni/merguj vše rozpracované (větev `opravy`); testy vyžadují Docker.

---

## 3. Přehled úkolů a stav

| | ID | Název | Zdroj | Velikost | Riziko |
|---|---|---|---|---|---|
| [x] | E1.1 | Blokace ne-CZK dokladů při potvrzení | Z-1/S-11, R-F | XS | nízké |
| [x] | E1.2 | Vyjasnit nepoužívané views v dokumentaci | Z-3/S-5 | XS | žádné |
| [x] | E1.3 | Číselník měrných jednotek (konfigurace + validace) | Z-4/S-10 | S | nízké |
| [x] | E2.1 | BE: ruční záporné pohyby (ADJUSTMENT−, WRITE_OFF) | P-1/S-1 | M | střední |
| [x] | E2.2 | FE: modal ručního pohybu na kartě produktu | P-1 | S | nízké |
| [x] | E2.3 | Funkční dokument + nápověda skladových pohybů | P-1, R-E | S | žádné |
| [x] | E3.1 | Migrace: view `v_stock_valuation` | P-4/S-5 | XS | nízké |
| [x] | E3.2 | BE: endpoint hodnoty skladu | P-4 | S | nízké |
| [x] | E3.3 | FE: karta hodnoty skladu na WarehousePage | P-4 | S | nízké |
| [x] | E4.1 | Migrace: stav CANCELLED + audit sloupce příjemky | P-2/S-3 | S | střední |
| [x] | E4.2 | BE: storno potvrzené příjemky s guardem | P-2, R-C | M | střední |
| [x] | E4.3 | FE: tlačítko storna + aktualizace dokumentace | P-2 | S | nízké |
| [x] | E5a.1 | BE: vratka dodavateli jako typ ručního pohybu (RETURN) | P-3/S-4, R-G | S | nízké |
| [x] | E5a.2 | FE: vratka v modalu ručního pohybu | P-3 | S | nízké |
| [x] | E5a.3 | Dokumentace vratky (funkční dokument, nápověda, api.md) | P-3 | XS | žádné |
| [x] | E6.1 | Migrace: stock_takes + items, typ STOCK_TAKE, uvolnění CHECK | P-5, R-H | M | střední |
| [x] | E6.2 | BE: založení inventury (snapshot) a zápis napočítaných množství | P-5 | M | nízké |
| [x] | E6.3 | BE: uzavření inventury → korekce (manko FIFO, přebytek příjemkou) | P-5, R-H | L | **vysoké** |
| [x] | E6.4 | FE: stránka inventury (soupis, zadávání, uzavření) | P-5 | M | střední |
| [x] | E6.5 | Dokumentace inventury (funkční dokument + nápověda) | P-5 | S | žádné |
| [x] | E7.1 | BE: ISDOC parser → kanonický draft + endpoint | P-6, R-D | M | střední |
| [x] | E7.2 | FE: nahrání ISDOC v import modalu | P-6 | S | nízké |
| [x] | E7.3 | Dokumentace ISDOC kanálu | P-6 | XS | žádné |
| [x] | E8.1 | FIFO pořadí a předvýběr šarže v modalu pohybu | Z-2/S-6, R-A | XS | nízké |
| [x] | E8.2 | Foto/scan dokladu do AI importu | R-D | S | nízké |
| [x] | E8.3 | Přehled „pod minimem" s doporučeným dodavatelem | P-7/S-8 | M | nízké |
| [x] | E8.4 | Výdej mimo zakázku (interní spotřeba) | P-8/S-13 | S | nízké |
| [x] | E8.5 | Dokumentace komfortních funkcí | — | XS | žádné |

---

## 4. Úkoly

### E1.1 · Blokace ne-CZK dokladů při potvrzení (Z-1, rozhodnutí R-F)

**Proč:** EUR faktura by dnes potvrzením založila šarže s cenami v EUR, které se dál tváří jako CZK
(nákupní cena položky zakázky, budoucí hodnota skladu). Rozhodnuto blokovat do doby reálné potřeby kurzů.

*Čti:* `docs/funkce/import-prijemek.md`, [ReceiptReviewServiceImpl.java](../src/main/java/cz/palo/autoservis/service/impl/ReceiptReviewServiceImpl.java) (`validateCompleteness`).

**Postup:**
1. V `validateCompleteness` (ReceiptReviewServiceImpl:376): pokud `header.currency.value != null`
   a není `"CZK"` (case-insensitive), přidej do `missing` klíč `currency` s hodnotou
   `"nepodporovaná měna: <hodnota> — podporována je jen CZK"`. (Zůstává součástí `RECEIPT_INCOMPLETE`,
   žádný nový kód chyby — měna jde v review přepsat jako každé jiné pole.)
2. Test do `ReceiptReviewServiceTest`: draft s `currency=EUR` → confirm vyhodí `RECEIPT_INCOMPLETE`
   s `currency` v params; po přepsání na CZK projde.

**Co NEDĚLAT:** neměnit DraftAssembler (default CZK zůstává), neblokovat import ani uložení draftu —
jen potvrzení; nezavádět kurzy.

**Akceptační kritéria:**
- [ ] confirm draftu s EUR → 422 `RECEIPT_INCOMPLETE`, params obsahují `currency`;
- [ ] CZK i chybějící měna (doplní se defaultem) procházejí beze změny chování;
- [ ] nový test zelený, celá suite zelená.

**Dokumentace:** `funkce/import-prijemek.md` — jedna věta do „Zvolené kompromisy" (jen CZK, R-F).

---

### E1.2 · Vyjasnit nepoužívané views (Z-3)

**Proč:** `v_stock_on_hand` a `v_batch_provenance` (V18) aplikace nikde nečte — vypadají jako mrtvý kód,
ale jsou to vědomé ad-hoc SQL nástroje. Nevyjasněný stav je horší než obojí.

**Postup:** Jen dokumentace — v `databaze.md` §9 k oběma views doplnit poznámku
„aplikace nečte; určeno pro ad-hoc SQL/ladění (analyza-sklad-2026-07 Z-3)". Žádná migrace, žádný kód.

**Akceptační kritéria:** — [ ] poznámka v `databaze.md` §9 u obou views.

---

### E1.3 · Číselník měrných jednotek (Z-4)

**Proč:** `unit` je volný text — „ks", „KS", „kus" vytvoří tři varianty. Malému servisu stačí uzavřený
seznam bez převodů. **Zvolený mechanismus: konfigurace + validace v service, BEZ DB CHECKu** —
existující data (a AI extrakce cizích variant) nesmí spadnout na constraint; normalizuje/validuje se
při vstupu, kde jde chyba srozumitelně vrátit.

*Čti:* `WarehouseImportProperties.java`, `DraftAssembler.java`, `validateCompleteness`, `ProductDto`.

**Postup:**
1. Do `warehouse.import` v `application.yaml` přidej `allowed-units: [ks, l, kg, bal, m, sada, pár]`
   (+ getter ve `WarehouseImportProperties`).
2. `validateCompleteness`: jednotka řádku mimo seznam (case-insensitive) → do `missing` klíč
   `invalidUnits` se seznamem pozic. `DraftAssembler` default „ks" zůstává.
3. `ProductServiceImpl.create/update`: jednotka mimo seznam → `BusinessRuleException("INVALID_UNIT", …)`.
4. FE: v `ReceiptDraftLinesTable` a formuláři produktu (`WarehousePageCreate/Edit`) změnit input na select
   ze seznamu (seznam natvrdo ve FE konstantě zrcadlící yaml — stejný kompromis jako defaulty importu).
5. Testy: unit pro validaci, integrace confirm s neplatnou jednotkou.

**Co NEDĚLAT:** žádná migrace, žádné převody jednotek, nemigrovat existující data (stará „KS" dožijí,
opraví se při nejbližší editaci).

**Akceptační kritéria:**
- [ ] confirm s jednotkou „krabice" → 422 s `invalidUnits`;
- [ ] create produktu s neplatnou jednotkou → 422 `INVALID_UNIT`; s „ks" projde;
- [ ] FE nabízí select (ruční příjemka i karta produktu); suite zelená.

**Dokumentace:** `funkce/import-prijemek.md` (kompromisy), `api.md` (kód `INVALID_UNIT`).

---

### E2.1 · BE: ruční záporné pohyby — ADJUSTMENT− a WRITE_OFF (P-1)

**Proč:** typy existují v ENUM/CHECK od V18, ale nic je nevytváří — oprava stavu skladu dnes = ruční SQL.
Záporná korekce (manko, rozbití) a odpis jsou nejmenší užitečný krok; je předpokladem inventury (E6) i storna (E4).
**Rozhodnutí R-E:** kladná korekce (přebytek) se v této fázi NEřeší pohybem — mapuje se na existující
ruční příjemku (zakládá šarži s cenou); ADJUSTMENT se zde používá výhradně záporně.

*Čti:* `databaze.md` §6 (chk_movement_sign, trigger), [OrderItemServiceImpl.java:122](../src/main/java/cz/palo/autoservis/service/impl/OrderItemServiceImpl.java) (vzor FOR UPDATE + validace zůstatku), `WarehouseImportMapper.xml` (insertMovement), `api.md`, `konvence.md`.

**Postup:**
1. Nový endpoint `POST /api/{version}/warehouse/products/{id}/movements`
   (`ProductController` nebo nový `StockMovementController` — dle velikosti zvol menší zásah).
   DTO `StockMovementDto.CreateRequest`: `movementType` (jen `ADJUSTMENT`/`WRITE_OFF` — jiné 400),
   `quantity` (kladné číslo; service ho znegatuje), `batchId` (**povinné** — pohyb jde vždy proti šarži,
   aby trigger snížil i `quantity_remaining` a ocenění zůstalo konzistentní), `note` (**povinná**, min 3 znaky).
2. Service (`ProductServiceImpl` nebo nový `StockMovementServiceImpl`): načti šarži
   `goodsReceiptMapper.findByIdsForUpdate` (vzor K6), ověř: šarže patří k produktu {id},
   `quantity ≤ quantity_remaining` (jinak 422 `QUANTITY_EXCEEDS_REMAINING` — stejný kód jako výdej),
   vlož pohyb se záporným množstvím, `created_by` z principala. Vrať aktualizovaný detail produktu.
3. `api.md`: nový endpoint + kódy chyb. Testy (integrace, Testcontainers):
   ADJUSTMENT− sníží stav i zůstatek šarže (ověřit hodnoty po triggeru); WRITE_OFF přes zůstatek → 422;
   bez poznámky → 400; typ RECEIPT v requestu → 400.

**Co NEDĚLAT:** neimplementovat RETURN (patří k dobropisu, fáze E5) ani kladný ADJUSTMENT (E6);
nesahat na trigger ani CHECK; neměnit výdejovou cestu.

**Akceptační kritéria:**
- [ ] pohyb vzniká jen s povinnou poznámkou a šarží; stav + zůstatek šarže mění trigger (v testu ověřeno čísly);
- [ ] překročení zůstatku → 422 `QUANTITY_EXCEEDS_REMAINING`; nepovolený typ → 400;
- [ ] pohyb je vidět v historii produktu (`findMovementsByProductId`) včetně poznámky;
- [ ] `api.md` aktualizováno; suite zelená.

---

### E2.2 · FE: modal ručního pohybu na kartě produktu (P-1)

*Čti:* `WarehousePageDetail.jsx` (tab pohybů/šarží), vzor modalu `GoodsReceiptImportModal.jsx`, `api/format.js` (labely typů už existují).

**Postup:** Na `WarehousePageDetail` tlačítko „Skladový pohyb" → modal: typ (select: Korekce − / Odpis),
šarže (select z šarží se zbytkem > 0, zobrazit „doklad · zbývá X · cena"), množství, poznámka.
Po úspěchu refresh detailu (stav, šarže, pohyby). Chyby přes `err.problem?.detail` (ApiError vzor).
U přebytku modal nenabízí nic — text s odkazem „Přebytek naskladni ruční příjemkou" (R-E).

**Akceptační kritéria:**
- [ ] pohyb jde zadat, stav a historie se obnoví bez reloadu stránky;
- [ ] validační chyby z BE se zobrazí srozumitelně; množství > zůstatek ukáže 422 hlášku;
- [ ] `npm run build` bez chyb.

---

### E2.3 · Funkční dokument + nápověda skladových pohybů (P-1, R-E)

**Postup:** Nový `docs/funkce/sklad-pohyby.md` (CO+PROČ: typy pohybů, kdo je vytváří, mapování
přebytek→ruční příjemka dle R-E, co je vědomě odloženo — RETURN/inventura s odkazem na §6 tohoto plánu)
+ článek nápovědy `frontend/…/src/help/sklad-pohyby.md` (+ registrace v `help/index.js`).
Aktualizuj `architektura.md` (řádek modulu Sklad) a `databaze.md` §7 (poznámka, že ADJUSTMENT−/WRITE_OFF
už kód vytváří; RETURN zatím ne).

**Akceptační kritéria:** — [ ] oba dokumenty existují a odpovídají implementaci E2.1–E2.2; odkazy fungují.

---

### E3.1 · Migrace: view `v_stock_valuation` (P-4)

**Proč:** hodnota zásob = Σ `quantity_remaining × unit_price_excl_vat` přes šarže — data existují,
pohled chybí. View (ne endpoint s SQL) — konzistence se vzorem cenových views V25/V32/V37.

**Postup:** Skillem `nova-migrace` nová migrace (V42 orientačně): view `warehouse.v_stock_valuation` —
per produkt (`product_id`, `sku`, `name`, `quantity_on_hand`, `stock_value` = SUM přes jeho šarže,
zaokrouhlení po šarži `ROUND(qty·price, 2)` — stejná filozofie jako views V25) + jen aktivní produkty.
Celkový součet si sečte endpoint. `databaze.md` §9 + index migrací.

**Akceptační kritéria:** — [ ] migrace projde na čisté DB i na dev datech; ruční SQL kontrola součtu sedí; `databaze.md` aktualizována.

---

### E3.2 · BE: endpoint hodnoty skladu (P-4)

**Postup:** `GET /api/{version}/warehouse/stock-valuation` → `{ totalValue, items: [{productId, sku, name, quantityOnHand, stockValue}] }`.
Mapper select nad view (do `WarehouseMapper.xml`), service, DTO, `api.md`. Integrační test: příjem 2 šarží
za různé ceny + částečný výdej → hodnota odpovídá zbytku × ceně šarže.

**Akceptační kritéria:** — [ ] endpoint vrací součet i rozpad; test s výdejem zelený; `api.md` aktualizováno.

---

### E3.3 · FE: karta hodnoty skladu (P-4)

**Postup:** Na `WarehousePage` souhrnná karta „Hodnota zásob (nákupní, bez DPH)" + sloupec hodnoty
v tabulce produktů (data z E3.2 — jeden fetch, žádné N+1). Formátování přes existující `formatCurrency`.

**Akceptační kritéria:** — [ ] karta + sloupec zobrazují hodnoty; prázdný sklad ukazuje 0; build bez chyb.

---

### E4.1 · Migrace: stav CANCELLED + audit sloupce příjemky (P-2)

**Proč:** storno potvrzené příjemky (R-C) potřebuje stav a auditní stopu.
⚠️ **PostgreSQL neumí přidat ENUM hodnotu a použít ji v téže transakci** — stejná past jako V17;
skill `nova-migrace` + vzor V17 (`flyway:noAutoCommit`/oddělení), pokud by migrace hodnotu rovnou používala
(nebude — jen ji přidává, ale ověř).

**Postup:** Skillem `nova-migrace`: `ALTER TYPE warehouse.receipt_status ADD VALUE 'CANCELLED'`;
`goods_receipts` + `cancelled_at TIMESTAMPTZ`, `cancelled_by BIGINT → security.users ON DELETE SET NULL`,
`cancellation_note VARCHAR(500)`. Partial unique index `uq_receipt_supplier_docno` rozšířit:
`WHERE status NOT IN ('REJECTED','CANCELLED')` — stornovaný doklad uvolní číslo pro re-import
(DROP + CREATE indexu v téže migraci; stejná sémantika jako u REJECTED). `databaze.md` (§6, §7, §11).

**Akceptační kritéria:** — [ ] migrace projde na čisté DB i dev datech; enum má 4 hodnoty; index uvolňuje číslo po CANCELLED; `databaze.md` aktualizována.

---

### E4.2 · BE: storno potvrzené příjemky s guardem (P-2, rozhodnutí R-C)

**Proč:** omylem potvrzený doklad dnes nejde vzít zpět. **R-C:** kompenzační pohyby (ledger zůstává
append-only, SAP vzor 101/102), povoleno JEN pokud ze šarží nebylo čerpáno.

*Čti:* `ReceiptReviewServiceImpl` (confirm — zrcadlový vzor, guarded UPDATE), E2.1 (mechanika záporného pohybu), `databaze.md` §6.

**Postup:**
1. `POST /warehouse/receipts/{id}/cancel` (`GoodsReceiptReviewController`), body `{ note }` (povinná).
2. Service: načti příjemku (jen `CONFIRMED` — jinak 422 `RECEIPT_NOT_CANCELLABLE`); šarže příjemky
   `FOR UPDATE`; **guard nečerpáno**: každá šarže `quantity_remaining == quantity_received`
   A neexistuje `order_items.goods_receipt_item_id` na žádnou z nich — jinak 422
   `RECEIPT_ALREADY_USED` (params: které šarže/kolik čerpáno). Pozn.: samotné `remaining == received`
   nestačí — vydané a vrácené zboží (ISSUE + ISSUE_RETURN) má remaining zpět, ale FK z položek může trvat.
3. Kompenzace: per šarže vlož `ADJUSTMENT` se záporným `quantity_received`, `batch_id` = šarže,
   `note` = „Storno příjemky <číslo dokladu>" (trigger vynuluje stav i zůstatky).
4. Guarded UPDATE: `SET status='CANCELLED', cancelled_* … WHERE id=# AND status='CONFIRMED'`;
   0 řádků → 409 `RECEIPT_ALREADY_PROCESSED` (vzor confirm).
5. Rozšířit dotaz `existsActiveDocument` (`WarehouseImportMapper.xml`) — dnes filtruje jen
   `status <> 'REJECTED'`; nově i `<> 'CANCELLED'`, jinak by stornovaný doklad blokoval re-import
   (nález vykonavatele E1.1; index z E4.1 to řeší jen na úrovni DB constraintu).
6. `api.md`. Testy: storno nečerpané → stav produktu 0, zůstatky 0, status CANCELLED, číslo dokladu
   lze znovu importovat; storno čerpané → 422 a nic se nezměnilo; storno PENDING_REVIEW → 422; dvojí storno → 409.

**Co NEDĚLAT:** nemazat šarže ani pohyby (audit!); neřešit storno čerpané příjemky „částečně";
nesahat na reject flow.

**Akceptační kritéria:**
- [ ] všechny 4 testovací scénáře zelené; kompenzační pohyby viditelné v historii produktu;
- [ ] supplier_products se stornem NEMĚNÍ (vědomě — převodník je znalost, ne stav);
- [ ] `api.md` aktualizováno; suite zelená.

---

### E4.3 · FE: tlačítko storna + dokumentace (P-2)

**Postup:** Na detailu potvrzené příjemky (`ReceiptReviewPage` v read-only režimu CONFIRMED) tlačítko
„Stornovat" → `ConfirmDialog` s povinnou poznámkou; chyba `RECEIPT_ALREADY_USED` zobrazí, které šarže
jsou čerpané. Po úspěchu refresh (badge CANCELLED). Aktualizuj `funkce/import-prijemek.md`
(workflow diagram + nový stav), help `prijem-zbozi.md`, `databaze.md` je z E4.1.

**Akceptační kritéria:** — [ ] storno projde z UI vč. poznámky; čerpaná příjemka ukáže srozumitelnou chybu; dokumenty aktualizovány; build bez chyb.

---

### E5a.1 · BE: vratka dodavateli jako typ ručního pohybu (P-3, S-4, rozhodnutí R-G)

**Proč:** `RETURN` je od V18 mrtvá hodnota — sloupce `return_reason` a `credit_note_number` se jen
čtou, nikdy neplní. Vratka je přitom mechanicky **totéž co odpis**: záporný pohyb proti konkrétní
šarži. Nezakládá se proto nový endpoint — `RETURN` se přidá jako třetí typ do endpointu z E2.1.

*Čti:* `docs/funkce/sklad-pohyby.md`, [ProductServiceImpl.registerManualMovement](../src/main/java/cz/palo/autoservis/service/impl/ProductServiceImpl.java), `StockMovementDto`, `databaze.md` §6 (chk_return_reason).

**Postup:**
1. `StockMovementDto.CreateRequest`: povolit i `RETURN`; přidat `returnReason` (enum `ReturnReason`)
   a `creditNoteNumber` (`@Size(max = 50)`).
2. **Validace zrcadlí DB CHECK `chk_return_reason`**: důvod je povinný právě a jen u `RETURN`
   (u ADJUSTMENT/WRITE_OFF musí být prázdný) — `@AssertTrue`, tedy 400. Číslo dobropisu je
   volitelné a smí být jen u `RETURN` (dobropis k odpisu nedává smysl).
3. Service: `returnReason` a `creditNoteNumber` propsat do pohybu. Zbytek beze změny —
   zámek šarže `FOR UPDATE`, kontrola zůstatku i negace množství platí stejně.
4. Testy do `ManualStockMovementTest`: RETURN sníží stav i zůstatek a uloží důvod + číslo dobropisu;
   RETURN bez důvodu → 400; ADJUSTMENT s důvodem → 400; RETURN nad zůstatek → 422.

**Co NEDĚLAT:** žádná migrace (enum i sloupce existují od V18); neimplementovat `CREDIT_NOTE`
jako typ dokladu (E5b, odloženo); nesahat na trigger ani CHECK.

**Akceptační kritéria:**
- [ ] RETURN vytvoří pohyb s důvodem i číslem dobropisu, stav a zůstatek sníží trigger;
- [ ] validace zrcadlí DB CHECK (chybějící i přebývající důvod → 400); nad zůstatek → 422;
- [ ] pohyb je v historii produktu s odznakem důvodu (FE už `getReturnReasonLabel` používá);
- [ ] `api.md` aktualizováno; suite zelená.

---

### E5a.2 · FE: vratka v modalu ručního pohybu (P-3)

**Postup:** V `StockMovementModal` přidat třetí typ „Vratka dodavateli"; při jeho volbě zobrazit
**select důvodu** (`RETURN_REASON_LABELS` už ve `format.js` existují) a volitelné pole
„Číslo dobropisu". Při přepnutí typu zpět na korekci/odpis obě pole vyprázdnit, ať se neposílají.
Text o přebytku (R-E) ponechat.

**Akceptační kritéria:**
- [ ] vratku lze zadat vč. důvodu a čísla dobropisu; stav i historie se obnoví bez reloadu;
- [ ] důvod je vynucen před odesláním; po přepnutí typu se pole nepošlou;
- [ ] `npm run build` bez chyb.

---

### E5a.3 · Dokumentace vratky (P-3)

**Postup:** `docs/funkce/sklad-pohyby.md` — RETURN přesunout z „vědomě odloženo" mezi implementované
typy, doplnit rozdíl vratka vs. odpis a poznámku, že cenový dobropis aplikace neřeší (R-G).
Nápověda `sklad-pohyby.md` — sekce „Vrácení dílu dodavateli" (nahradit dosavadní větu o odložení).
`databaze.md` §7 — u `movement_type` vyznačit, že RETURN už kód vytváří.

**Akceptační kritéria:** — [ ] dokumenty odpovídají implementaci; žádná zmínka, že vratka chybí.

---

### E6.1 · Migrace: inventura (P-5, rozhodnutí R-H)

**Proč:** inventarizace je zákonná povinnost a bez ní je ledger teorie. Ruční korekce (E2.1) je její
stavební kámen — inventura generuje tytéž pohyby, jen hromadně a s protokolem.

*Čti:* `databaze.md` §6 (goods_receipts, CHECK V39), `docs/analyza-sklad-2026-07.md` §3d (R-E, R-H).

**Postup** (skill `nova-migrace`, orientačně V44):
1. ENUM `warehouse.stock_take_status` = OPEN, CLOSED, CANCELLED.
2. `ALTER TYPE warehouse.document_type ADD VALUE 'STOCK_TAKE'` — **`flyway:noAutoCommit` + COMMIT**
   (hodnotu hned používá nový CHECK; past z V17).
3. **Uvolnit `chk_receipt_confirmed_complete`** (drop + create): pro `document_type = 'STOCK_TAKE'`
   se nevyžaduje dodavatel, číslo dokladu ani částky — inventurní přebytek je nemá.
4. `stock_takes`: id, `status` (default OPEN), note VARCHAR(500), `opened_at/by`, `closed_at/by`,
   `surplus_receipt_id` → goods_receipts ON DELETE RESTRICT (pseudo-příjemka přebytků),
   created_at/updated_at + trigger. **Partial unique index `WHERE status = 'OPEN'`** — jen jedna
   otevřená inventura naráz.
5. `stock_take_items`: id, `stock_take_id` → CASCADE, `product_id` → RESTRICT,
   `expected_quantity` NUMERIC(12,3) NOT NULL (snapshot při otevření),
   `counted_quantity` NUMERIC(12,3) NULL (**NULL = nepočítáno, ne nula**),
   `surplus_unit_price` NUMERIC(12,2) NULL, UNIQUE (stock_take_id, product_id),
   CHECK na nezápornost, created_at/updated_at + trigger.
6. `databaze.md` (§6 tabulky, §7 ENUMy, §9 nic, §11 index migrací).

**Co NEDĚLAT:** neměnit `goods_receipt_items.goods_receipt_id` na nullable (invariant „šarže má původ");
negenerovat zatím žádné pohyby.

**Akceptační kritéria:**
- [ ] migrace projde na čisté DB i dev datech; druhá otevřená inventura selže na indexu;
- [ ] příjemku typu STOCK_TAKE lze uložit jako CONFIRMED bez dodavatele a částek, ostatní typy dál CHECK vyžaduje;
- [ ] `databaze.md` aktualizována.

---

### E6.2 · BE: založení inventury a zápis napočítaných množství (P-5)

**Postup:**
1. `POST /warehouse/stock-takes` — otevře inventuru: vloží hlavičku a **nasnapshotuje všechny aktivní
   produkty** do položek (`expected_quantity` = aktuální `quantity_on_hand`, `surplus_unit_price`
   předvyplněná z **nejnovější šarže** dílu, jinak NULL). Druhá otevřená → 409 `STOCK_TAKE_ALREADY_OPEN`.
2. `GET /warehouse/stock-takes/{id}` — soupis s rozdílem (`counted − aktuální stav`) a `GET /stock-takes`
   (seznam). `PUT /warehouse/stock-takes/{id}/items` — dávkový zápis napočítaných množství a cen
   přebytku; jen ve stavu OPEN (jinak 422 `STOCK_TAKE_NOT_EDITABLE`).
3. `POST /{id}/cancel` — zruší otevřenou inventuru (nic negeneruje).
4. Testy: snapshot pokrývá aktivní produkty; druhá inventura → 409; zápis množství; zápis do uzavřené → 422.

**Akceptační kritéria:** — [ ] otevření nasnapshotuje sklad; opakované otevření 409; zápis funguje jen v OPEN; `api.md` aktualizováno.

---

### E6.3 · BE: uzavření inventury → korekce (P-5, R-H) — **nejrizikovější úkol plánu**

**Postup:**
1. `POST /warehouse/stock-takes/{id}/close`: jen z OPEN (guarded UPDATE, 0 řádků → 409).
2. Pro každou položku **s vyplněným `counted_quantity`**: rozdíl = `counted − aktuální quantity_on_hand`
   (**ne proti snapshotu** — během počítání mohl proběhnout výdej). Rozdíl 0 → nic.
3. **Manko (rozdíl < 0):** rozpustit po šaržích produktu **od nejstarší** (FIFO, `FOR UPDATE`),
   dokud není pokryto; per šarže záporný `ADJUSTMENT` s poznámkou „Inventura #{id}".
   Když je manko větší než součet zůstatků šarží → 422 `STOCK_TAKE_SHORTAGE_EXCEEDS_BATCHES`
   (nesmí vzniknout záporný zůstatek).
4. **Přebytek (rozdíl > 0):** jednou za celou inventuru založit příjemku `STOCK_TAKE`
   (`source_channel = MANUAL`, bez dodavatele, `invoice_number = "INV-{id}"`, status CONFIRMED),
   do ní per produkt šarži s `surplus_unit_price` (**chybí-li cena → 422 `STOCK_TAKE_PRICE_MISSING`**)
   a `quantity_remaining = 0`, a k ní **kladný `ADJUSTMENT`** — trigger dorovná stav i zůstatek šarže.
   ID příjemky uložit do `stock_takes.surplus_receipt_id`.
5. Vše v jedné transakci; `databaze.md`/`api.md`.
6. Testy: manko se rozpustí FIFO přes dvě šarže; přebytek založí šarži s cenou a zvedne stav;
   nevyplněný řádek negeneruje nic; manko nad zůstatky → 422; chybějící cena přebytku → 422;
   dvojí uzavření → 409; **rozdíl se počítá proti aktuálnímu stavu** (výdej mezi otevřením a uzavřením).

**Co NEDĚLAT:** neupravovat `quantity_on_hand` ani `quantity_remaining` přímo — jen pohyby;
nemazat položky inventury (protokol musí zůstat).

**Akceptační kritéria:** — [ ] všech 7 testovacích scénářů zelených; po uzavření stav odpovídá napočítanému množství; protokol zůstává čitelný.

---

### E6.4 · FE: stránka inventury (P-5)

**Postup:** `/warehouse/stock-takes` — seznam a založení; detail se soupisem: sloupce díl, MJ,
očekáváno, **napočítáno (input)**, rozdíl (barevně), cena přebytku (input jen když je rozdíl kladný).
Průběžné ukládání („Uložit soupis"), tlačítko „Uzavřít inventuru" s `ConfirmDialog` shrnujícím počet
mank a přebytků. Odkaz do Sidebaru. Chyby z BE přes `err.problem?.detail`.

**Akceptační kritéria:** — [ ] soupis lze vyplnit a uložit; rozdíly se počítají; uzavření projde a stav skladu odpovídá; build bez chyb.

---

### E6.5 · Dokumentace inventury (P-5)

**Postup:** nový `docs/funkce/inventura.md` (proces, R-H rozhodnutí, proč FIFO manko a pseudo-příjemka
u přebytku, proč se počítá proti aktuálnímu stavu) + článek nápovědy `inventura.md` (+ `help/index.js`).
Aktualizovat `funkce/sklad-pohyby.md` (inventura už není odložená) a `roadmapa.md`.

**Akceptační kritéria:** — [ ] oba dokumenty odpovídají implementaci; v sklad-pohyby.md už inventura nefiguruje jako odložená.

---

### E7.1 · BE: ISDOC parser → kanonický draft (P-6, R-D)

**Proč:** ISDOC je český standard e-faktury — data jsou **strojová a jistá**, takže odpadá AI extrakce
i její náklad a riziko. Payoff kanonického draftu: nový kanál = nový adaptér, zbytek pipeline (kontroly,
párování, completeness gate, potvrzení) se nemění. `receipt_source = 'ISDOC'` je rezervován od V39.

*Čti:* `docs/pruvodce/import-prijemek.md` §4–6 (draft, DraftAssembler), `WarehouseImportServiceImpl`,
`model/draft/*`. **Struktura ISDOC ověřena proti oficiálnímu XSD 6.0.2** (isdoc.cz), ne podle vzorku —
soubor `import/faktury/gemini-gen/Faktura_Bezna.isdoc` je syntetický a neúplný.

**Mapování** (namespace `http://isdoc.cz/namespace/2013`):

| ISDOC | Draft |
|---|---|
| `ID`, `IssueDate`, `TaxPointDate`, `DueDate`, `CurrencyCode` | hlavička (VERBATIM) |
| `AccountingSupplierParty/Party/PartyName/Name` | dodavatel — název |
| `…/PartyIdentification/ID`, `…/PartyTaxScheme/CompanyID` | IČO, DIČ |
| `…/PostalAddress/{StreetName,BuildingNumber,CityName,PostalZone}` | adresa |
| `InvoiceLine/Item/SellersItemIdentification/ID` | **katalogové číslo** (na tom stojí párování) |
| `InvoiceLine/Item/Description` | název položky |
| `InvoicedQuantity` + `@unitCode` | množství + MJ (převod kódů UN/ECE) |
| `UnitPrice`, `LineExtensionAmount`, `LineExtensionAmountTaxInclusive` | ceny řádku |
| `ClassifiedTaxCategory/Percent` | sazba DPH |
| `TaxTotal/TaxSubTotal/{TaxableAmount,TaxAmount,TaxCategory/Percent}` | rekapitulace DPH |
| `LegalMonetaryTotal/{TaxExclusiveAmount,TaxInclusiveAmount}`, `TaxTotal/TaxAmount` | součty |

**Postup:**
1. `IsdocParser` (`service/`): DOM parsing přes JAXP (žádná nová závislost), **s vypnutými externími
   entitami** (XXE — `disallow-doctype-decl`). Vše VERBATIM; chybějící pole → ABSENT (nic se nedomýšlí).
2. **Převod `unitCode`** (UN/ECE Rec. 20) na náš číselník: `C62`/`H87` → ks, `LTR` → l, `KGM` → kg,
   `MTR` → m, `SET` → sada, `PR` → pár. Neznámý kód **nechat tak, jak je** — reviewer ho uvidí jako
   „mimo číselník" a opraví; nedosazovat default, to by tiše lhalo.
3. **`DocumentType` z ISDOC:** 1 = faktura → INVOICE. **Dobropis (kód 3) a vrubopis odmítnout**
   422 `ISDOC_UNSUPPORTED_DOCUMENT_TYPE` — naskladnily by zboží místo odepsání (E5b není hotové).
4. `WarehouseImportServiceImpl.importFromIsdoc`: parser → `fillDerivedValues` → verifikace → párování
   → dedup DL → jediný INSERT (`source_channel = ISDOC`, `source_pdf` NULL, `extraction_model` NULL).
   Idempotence a 409 `DUPLICATE_IMPORT` beze změny.
5. Endpoint `POST /warehouse/receipts/import-isdoc` (multipart, bez parametru typu — ten je v souboru).
6. Testy: parsing realistického ISDOC (fixture dle XSD) mapuje všechna pole; dobropis → 422;
   nevalidní XML → 400; import uloží jen draft (0 skladových dat); převod jednotek.

**Co NEDĚLAT:** nevolat AI; needitovat existující AI cestu; neimplementovat generování ISDOC (jen čtení).

**Akceptační kritéria:**
- [ ] ISDOC faktura se naimportuje jako draft se všemi poli VERBATIM a projde toutéž verifikací;
- [ ] katalogová čísla se párují stávající kaskádou (supplier_products) beze změny kódu;
- [ ] dobropis odmítnut 422; rozbité XML 400; suite zelená.

---

### E7.2 · FE: nahrání ISDOC v import modalu (P-6)

**Postup:** V `GoodsReceiptImportModal` přidat volbu kanálu (PDF / ISDOC). U ISDOC skrýt výběr typu
dokladu (je v souboru), `accept=".isdoc,.xml"` a volat nový endpoint. Po úspěchu stejná navigace
do kontrolní obrazovky.

**Akceptační kritéria:** — [ ] ISDOC lze nahrát a otevře se v review; PDF cesta beze změny; build bez chyb.

---

### E7.3 · Dokumentace ISDOC kanálu (P-6)

**Postup:** `funkce/import-prijemek.md` — ISDOC z „rezervováno" mezi hotové kanály, tabulka mapování
a poznámka o odmítnutí dobropisů; nápověda `prijem-zbozi.md` (jak nahrát ISDOC a proč je přesnější
než PDF); `api.md` (nový endpoint).

**Akceptační kritéria:** — [ ] dokumenty odpovídají implementaci; nikde nezůstalo „ISDOC rezervováno".

---

### E8.1 · FIFO pořadí a předvýběr šarže v modalu pohybu (Z-2, R-A)

**Proč:** `findBatchesByProductId` řadí šarže **nejnovější první** (`issue_date DESC`) — modal ručního
pohybu tak nabízí nejnovější šarži, což jde proti FIFO (R-A) i proti tomu, jak manko rozpouští
inventura. Odpis a korekce mají ubírat od nejstarší.

**Postup:** Seřadit šarže v `StockMovementModal` podle `issueDate` **vzestupně** a předvybrat první
se zbytkem. Tabulku šarží na kartě dílu **nechat beze změny** — je to historický přehled, tam dává
„nejnovější první" smysl.

**Akceptační kritéria:** — [ ] modal nabízí nejstarší šarži jako první a předvybere ji; tabulka na kartě beze změny; build bez chyb.

---

### E8.2 · Foto/scan dokladu do AI importu (rozhodnutí R-D)

**Proč:** mechanik doklad často vyfotí mobilem. Prompt už dnes umí číst vizuálně (kvůli poškozené
textové vrstvě), takže jde jen o vpuštění obrázků dovnitř.

**Postup:**
1. `PdfDocumentExtractionService`: MIME typ **z nahraného souboru**, ne natvrdo `application/pdf`
   (podpis metody `extract(bytes, documentType, mimeType)`).
2. Controller: povolit `image/jpeg`, `image/png`, `image/heic` a přípony `.jpg/.jpeg/.png/.heic`;
   `source_filename` zůstává, `source_pdf` nese originál (sloupec je BYTEA, název je historický — TD-40 analogie).
3. FE: `accept` u PDF kanálu rozšířit o obrázky, text modalu upravit („PDF nebo fotka dokladu").

**Co NEDĚLAT:** neměnit prompt ani draft pipeline; nepřidávat OCR knihovnu (čte model).

**Akceptační kritéria:** — [ ] JPEG/PNG projde importem stejně jako PDF; nepodporovaný typ → 415; suite zelená.

---

### E8.3 · Přehled „pod minimem" s doporučeným dodavatelem (P-7, S-8)

**Proč:** `min_stock_level` i filtr `lowStockOnly` existují, ale nic neřekne, **u koho** díl objednat.
`supplier_products` přitom zná posledního dodavatele i jeho poslední cenu.

**Postup:**
1. View nebo select: produkty s `min_stock_level IS NOT NULL AND quantity_on_hand < min_stock_level`,
   k nim **poslední** záznam ze `supplier_products` (dodavatel, jeho kód, poslední cena, kdy naposled).
2. Endpoint `GET /warehouse/products/low-stock` → `LowStockDto.Response` (chybějící množství = min − stav).
3. FE stránka „Pod minimem" (odkaz v menu pod Skladem): díl, skladem, minimum, chybí, doporučený
   dodavatel + poslední cena; prázdný stav „vše je nad minimem".
4. Test: díl pod minimem s převodníkem vrátí dodavatele i cenu; díl bez převodníku projde s prázdnými poli.

**Co NEDĚLAT:** negenerovat objednávku ani PDF — jen přehled (objednávkový modul je mimo scope, S-9).

**Akceptační kritéria:** — [ ] endpoint vrací jen díly pod minimem vč. doporučeného dodavatele; stránka je v menu; suite zelená.

---

### E8.4 · Výdej mimo zakázku (P-8, S-13)

**Proč:** dílna spotřebuje materiál i mimo zakázku (čistivo, spojmateriál). Dnes musí uživatel volit
mezi odpisem (což lže — zboží nebylo znehodnoceno) a fiktivní zakázkou.

**Postup:** Povolit `ISSUE` jako čtvrtý typ ručního pohybu s `order_id = NULL` (DB to umožňuje,
`chk_movement_sign` vyžaduje jen zápornou hodnotu). V modalu volba „Spotřeba (bez zakázky)".
Poznámka zůstává povinná — ta nese důvod.

**Co NEDĚLAT:** neměnit výdej na zakázku (ten dál běží přes import položek a váže `order_id`).

**Akceptační kritéria:** — [ ] spotřeba sníží stav i zůstatek šarže a v historii je bez zakázky; suite zelená.

---

### E8.5 · Dokumentace komfortních funkcí

**Postup:** `funkce/sklad-pohyby.md` (spotřeba jako čtvrtý typ, FIFO pořadí nabídky),
`funkce/import-prijemek.md` (foto/scan kanál), nový oddíl v nápovědě `sklad-pohyby.md`
a `prijem-zbozi.md`, `api.md` (low-stock endpoint, rozšířené MIME typy).

**Akceptační kritéria:** — [ ] všechny čtyři funkce jsou popsané v funkčním dokumentu i nápovědě.

---

## 5. Ověřování (každý úkol)

- Kompilace: `./mvnw test-compile`; testy: `./mvnw test` (vyžaduje Docker/Testcontainers).
- FE úkoly: `npm run build` v `frontend/autoservis-frontend`; ruční ověření proti běžícímu backendu.
- Úkoly s migrací: ověřit průchod na čisté DB (Testcontainers to dělá) **i** na lokální dev DB.
- Ruční ověření dle úkolu (1–2 min): typicky provést akci v UI a zkontrolovat historii pohybů na kartě produktu.

## 6. Rámcové fáze E5–E8 (detail vznikne později)

Pořadí a obsah dle analýzy §3c; rozhodnutí R-A…R-F platí i zde.

- **E5b — přijatý dobropis jako doklad** (P-3, R-D) — **odloženo, rozhodnutí R-G (2026-07-21)**:
  nový `document_type = CREDIT_NOTE` v draft pipeline. Odloženo proto, že dobropis je *opačný*
  doklad než příjemka: musí ukazovat na existující šarže, nesmí zakládat karty, potřebuje druhou
  větev `confirm`, vlastní completeness gate i kontrolní obrazovku — a **dvě nejdůležitější pole
  (která šarže a proč) z dokladu vyčíst nejdou**, takže přínos AI extrakce je tu nejnižší ze všech
  typů dokladů. Vrátit se k tomu, až bude jasné, jak často dobropisy reálně chodí.
  Pozn.: cenový dobropis (oprava ceny bez pohybu zboží) nemá v aplikaci kam jít — nevede závazky
  ani účetnictví; skončil by jako uložený dokument bez efektu na sklad.
- ~~E6 — inventura~~ — **hotovo** (V44, úkoly E6.1–E6.5). Tisk protokolu (PDF) zůstává otevřený.
- ~~E7 — ISDOC adaptér~~ — **hotovo** (úkoly E7.1–E7.3). Křížová kontrola u `.isdoc.pdf` (XML vs. AI
  extrakce téhož souboru) zůstává jako možné rozšíření.
- ~~E8 — komfort~~ — **hotovo** (úkoly E8.1–E8.5): FIFO předvýběr šarže, foto/scan do AI
  importu, přehled „pod minimem" s doporučeným dodavatelem, výdej mimo zakázku.
