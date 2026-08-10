# Import příjemek — draft pipeline s kontrolou člověkem

> Funkční dokument (co + proč). Detailní průvodce implementací (soubor po souboru): [docs/pruvodce/import-prijemek.md](../pruvodce/import-prijemek.md). Implementační detaily: `docs/backend.md` §4, schéma: `docs/databaze.md` §6, endpointy: `docs/api.md`. Stav: všech 7 fází implementováno (V39–V41; draft import, confirm/reject, kontrolní UI, identita produktu, ruční příjemka, dedup DL↔faktura) — viz `docs/roadmapa.md` §2.1. Článek nápovědy: `frontend/…/src/help/prijem-zbozi.md`.

## Proč přestavba

Původní import naskladňoval okamžitě při nahrání PDF: AI extrakce → rovnou produkty, šarže a pohyby. To mělo čtyři zásadní vady: (1) dodací list bez rozpisu DPH shodil import na `NOT NULL` — vznikl dočasný hack `vat_amount = 21`; (2) potvrzovací workflow neexistoval, přestože na něm stojí filtry (`CONFIRMED`) pro import položek do zakázky; (3) sklad rostl před jakoukoliv kontrolou, i při selhané rekonciliaci; (4) `products.sku` = kód dodavatele → stejný díl od druhého dodavatele zakládal duplicitní kartu.

## Princip: kanonický draft + tři stavy pole

**Každý vstupní kanál produkuje tentýž kanonický draft** (`model/draft/ReceiptDraft`, uložený v `goods_receipts.draft_payload` JSONB):

- AI extrakce z PDF **nebo fotky/skenu** (hotovo; foto přidáno v E8 dle R-D — model čte obrázek
  stejně jako stránku PDF a prompt už vizuální čtení vyžaduje kvůli poškozeným textovým vrstvám),
- ruční formulář = prázdný draft (plánováno, fáze 6),
- **ISDOC adaptér** (hotovo, E7) — český standard e-fakturace; bez AI, vše jisté.

Nový formát dokladu = nový adaptér; zbytek pipeline (kontrola, potvrzení, materializace) se nemění.

**Každé pole draftu nese stav** — v kontrolní obrazovce se odliší barevně:

| Stav | Význam | Kdo ho nastavuje |
|---|---|---|
| `VERBATIM` | opsáno doslova z dokladu | model (extrakce) |
| `DERIVED` | dopočteno z jiných hodnot dokladu | model nebo `DraftAssembler` |
| `DEFAULTED` | v dokladu není → default z konfigurace | `DraftAssembler` (`warehouse.import.defaults`) |
| `VERIFIED` | přečteno/dopočteno **a** ověřeno křížovou kontrolou | výhradně `DraftVerificationService` |
| `ABSENT` | chybí a nemá default → musí doplnit člověk | `DraftAssembler` |
| `EDITED` | změněno uživatelem při kontrole | review UI (fáze 3–4) |

Značení v kontrolní obrazovce odděluje **původ hodnoty** (ikona: zeleně VERIFIED, žlutě DERIVED/DEFAULTED, neutrálně `ABSENT` = „není na dokladu") od **akce nutné** (červený rámeček): červeně svítí jen pole **povinné a prázdné** nebo s neplatnou hodnotou (jednotka mimo číselník). Povinnost pole na FE zrcadlí completeness gate (`REQUIRED_*` v `src/api/format.js`). `ABSENT` u nepovinného pole (splatnost, DUZP u DL) tedy neruší.

Klíčové rozhodnutí: **jistotu určuje kód, ne model.** LLM neumí kalibrovanou confidence — model jen přiznává původ hodnoty (VERBATIM/DERIVED/ABSENT) a deterministické kontroly (`DraftVerificationService`) povyšují na VERIFIED: matematika řádků, součty vs. rekapitulace DPH, základ + DPH = celkem, kontrolní součet IČO (mod 11), dodavatel nalezen v DB. To je rozšíření původní zásady „AI čte, kód počítá".

**Ověřit se dá jen proti něčemu nezávislému (audit KN-17).** Zásada má zrádnou hranu: chybějící
částky dopočítává `DraftAssembler` (stav DERIVED) — základ z množství × ceny, cenu s DPH ze
základu, hlavičkový součet ze řádků, DPH jako rozdíl celkem − základ. Kontrola, která takovou
hodnotu porovná s tím, z čeho vznikla, **projde vždycky**, ať model přečetl cokoli. Ručně psaný
dodací list bez rekapitulace tak dřív skončil se všemi poli zeleně VERIFIED a
`reconciliation_ok = true`, přestože nebylo ověřeno nic.

Každá kontrola proto nese vedle výsledku i příznak `independent`:

| | Význam |
|---|---|
| `ok: true, independent: true` | ověřeno proti údaji z dokladu / kontrolnímu součtu / DB — pole se povýší na VERIFIED |
| `ok: true, independent: false` | tautologie: porovnával se náš vlastní dopočet sám se sebou → **nic se nepovyšuje** |
| `ok: false` | křížová kontrola neprošla — model něco přečetl špatně |

`reconciliation_ok` = všechny kontroly prošly **a aspoň jedna aritmetická byla nezávislá**.

Zakázaná je **tautologie**, ne kontrola dopočtené hodnoty proti dokladu — na tom rozdílu záleží:

| Kontrola | Kdy je nezávislá |
|---|---|
| `LINE_MATH` | obě půlky rovnice stojí na témže dopočtu, takže se každá počítá jen tehdy, když v ní **žádný** operand není dopočtený |
| `LINES_SUM_VS_RECAP` | vždy, když doklad rekapitulaci má — pochází z dokladu, takže potvrdí i dopočtený základ a rozklíčuje písmenný kód sazby |
| `LINES_SUM_VS_TOTAL` | závisí na **celkové částce** z dokladu (řádky jsou to ověřované); je-li dopočtená i ona, kontrola neprokáže nic |
| `RECAP_SUM` | vždy, když doklad rekapitulaci má |

Prakticky: běžná faktura s rekapitulací a souhrnem se ověří i tehdy, když u řádku chybí cena
s DPH — dopočet potvrdí souhrn z dokladu. Ručně psaný dodací list bez rekapitulace i bez souhrnu
zůstane žlutý, protože není proti čemu ověřovat. Tam musí čísla zkontrolovat reviewer, ne razítko.

## ISDOC — strojový kanál (E7)

Český standard e-faktury (ISDOC 6.0.2, namespace `http://isdoc.cz/namespace/2013`) nese **strojová
data**, takže se nečte AI: `IsdocParser` naparsuje XML a všechna přečtená pole dostanou `VERBATIM`,
chybějící zůstanou `ABSENT`. Dál pokračuje **úplně stejná pipeline** — dopočty, verifikace, párovací
kaskáda, completeness gate i potvrzení. To je payoff kanonického draftu: nový kanál = nový adaptér.

Mapování je postavené proti **oficiálnímu XSD**, ne proti vzorku (soubor v `import/faktury/gemini-gen/`
je syntetický a neúplný — mimo jiné má `unitCode="KGM"` u brzdových destiček):

| ISDOC | Draft |
|---|---|
| `ID`, `IssueDate`, `TaxPointDate`, `DueDate`, `CurrencyCode` | hlavička |
| `AccountingSupplierParty/Party/…` (`PartyName/Name`, `PartyIdentification/ID`, `PartyTaxScheme/CompanyID`, `PostalAddress`) | dodavatel vč. IČO a DIČ |
| `InvoiceLine/Item/SellersItemIdentification/ID` | **katalogové číslo** — identita pro párování |
| `InvoicedQuantity` + `@unitCode` | množství a MJ (převod UN/ECE: C62/H87 → ks, LTR → l, …) |
| `UnitPrice`, `LineExtensionAmount`, `LineExtensionAmountTaxInclusive`, `ClassifiedTaxCategory/Percent` | ceny a sazba řádku |
| `TaxTotal/TaxSubTotal`, `LegalMonetaryTotal` | rekapitulace DPH a součty |

Dvě rozhodnutí, která stojí za vysvětlení:

- **Dobropis a vrubopis se odmítají** (422 `ISDOC_UNSUPPORTED_DOCUMENT_TYPE`). ISDOC je nese pod jiným
  `DocumentType` a naskladnily by zboží místo odepsání — dokud není hotová fáze E5b, je bezpečnější
  je nepustit dál než je tiše zpracovat jako fakturu.
- **Neznámý kód jednotky se nepřekládá** — projde tak, jak je, a kontrolor ho uvidí jako „mimo číselník"
  (Z-4). Dosadit default by znamenalo tiše si vymyslet měrnou jednotku.

Parser čte XML s vypnutými externími entitami a DOCTYPE (XXE) — doklad přichází zvenčí.

## Typ dokladu volí uživatel

`INVOICE` vs. `DELIVERY_NOTE` je multipart parametr uploadu (+ přepínač v `GoodsReceiptImportModal`) — u financí nespoléháme na klasifikaci modelem. U dodacího listu („Není daňový doklad", bez rekapitulace DPH) kód: sazbu dosadí jako `DEFAULTED` 21 % (nebo ji model odvodí z poměru cen s/bez DPH → `DERIVED`), řádkové a hlavičkové součty dopočte. Dopočet jde **oběma směry**: základ → s DPH i **zpětně** s DPH → základ → jednotková cena (ručně psané doklady mívají jen cenu s DPH); vše `DERIVED`. Hardcoded `vat_amount = 21` z dřívějška je pryč.

## Workflow (cílový stav)

```
import PDF / ruční formulář
        │  (uloží se JEN draft — žádné produkty, šarže, pohyby)
        ▼
  PENDING_REVIEW ──► kontrolní obrazovka (editace, párování produktů)
        │                    │
    Potvrdit             Zamítnout
        │                    │
        ▼                    ▼
    CONFIRMED            REJECTED
  (materializace:      (nic nevzniklo;
   dodavatel, produkty, číslo dokladu se
   šarže, pohyby        uvolní pro re-import
   RECEIPT)             — partial unique index V39)
        │
   Stornovat (jen dokud se nečerpalo)
        │
        ▼
    CANCELLED
  (kompenzační pohyby vrátí sklad na nulu;
   původní pohyby zůstávají — ledger je append-only;
   číslo dokladu se uvolní — index rozšířen ve V43)
```

Import položek do zakázky zůstává povolen jen z `CONFIRMED` příjemek — workflow ho konečně zpřístupní.

### Ochrana proti dvojímu naskladnění (audit KN-4)

Dvě samostatné cesty, kterými šlo totéž zboží naskladnit dvakrát:

**a) Volba „pouze provázat" byla mrtvá.** Přeskočení řádku se ptá na `DraftLine.deliveryNoteNumber`,
jenže extrakce ho podle kontraktu plní **jen u skupinového řádku** `DELIVERY_NOTE_GROUP`, ne
u položek — a materializují se právě položky. Podmínka tedy nikdy neplatila a faktura
přefakturovávající už naskladněný dodací list se naskladnila celá znovu.

Chybějící vazbu **nedoplňujeme odhadem**. Přiřadit položky ke skupinovému řádku podle pořadí by
znamenalo hádat rozvržení dokladu (je „Dodací list č. X celkem…" hlavička nad položkami, nebo
součet pod nimi?); při špatném odhadu by se zboží, které fyzicky přišlo, **nenaskladnilo vůbec** —
a tichá chyba tímhle směrem je horší než duplicita. Dokud přiřazení řádků neexistuje
(prompt + kontrolní obrazovka), potvrzení v takové situaci skončí **422
`DELIVERY_NOTE_LINK_NOT_APPLICABLE`** s vysvětlením. Naskladnit doklad normálně jde dál — stačí
provázání nepoužít. Jakmile položka číslo dodacího listu nese, přeskočení funguje a naskladní se
jen nekryté řádky (pokryto testem).

**b) Doklad bez čitelného IČO.** `resolveSupplier` zakládal dodavatele **před** kontrolou
duplicity a unikát na `registration_number` víc `NULL` hodnot povoluje — každý import tak vyrobil
novou dodavatelskou kartu, se kterou dedup podle `supplier_id` neměl co porovnat. Nově se u
dodavatele, který se teprve bude zakládat, kontroluje shoda **čísla dokladu a jména dodavatele**
(`existsActiveDocumentBySupplierName`, case-insensitive) — a to **ještě před completeness gate**,
aby obsluha nedopracovávala doklad, který stejně skončí jako duplicita.

### Deaktivovaná karta dílu a dodavatel při potvrzení (audit KN-16)

Párování v kontrole draftu hledá **jen aktivní** dodavatele i karty — auto-match na vyřazený záznam
by byl horší než přiznat, že shoda není. Jenže unikáty `uq_products_sku` a
`uq_suppliers_registration_number` platí bez ohledu na `is_active`, takže potvrzení pak spadlo na
porušení constraintu a obsluha dostala neinformativní 422 „Data se nepodařilo uložit". Potvrzení
proto oba případy rozlišuje, a **záměrně každý jinak**:

| Situace | Chování | Proč |
|---|---|---|
| Karta dílu se stejným SKU je deaktivovaná | **reaktivuje se** a naskladní se na ni | zboží fyzicky přišlo; vyřazená karta se zásobou by zmizela z `v_stock_valuation` i z inventury a duplicitní karta by rozbila identitu dílu |
| Dodavatel se stejným IČO je deaktivovaný | **422 `SUPPLIER_INACTIVE`** s návodem ho aktivovat | vyřazení dodavatele je rozhodnutí obsluhy (ukončená spolupráce, duplicita) a tiché oživení importem by ho obcházelo |

Rozdíl je záměrný: u karty jde o fyzický kus na regálu, u dodavatele o obchodní vztah.

### Storno potvrzené příjemky (V43, rozhodnutí R-C)

Omylem potvrzený doklad nejde „odpotvrdit" — sklad už o zboží ví. Storno proto **nemaže nic**:
ke každé šarži zapíše kompenzační pohyb (`ADJUSTMENT` na plné přijaté množství, záporně) a doklad
přejde do `CANCELLED`. Vzor je SAP (pohybový typ 101 má storno 102) i česká praxe, kde se chybná
příjemka opravuje dokladem, ne smazáním.

**Povoleno jen dokud se ze šarží nečerpalo** — a to jakkoliv: výdejem na zakázku, ruční korekcí
i odpisem. Kompenzace plného přijatého množství by u už sníženého zůstatku spadla pod nulu
(CHECK `chk_items_remaining`). Kontroluje se proto zůstatek šarže *a* navíc vazba z položek zakázky —
vydané a zase vrácené zboží má zůstatek zpět, ale FK z `order_items` trvá
(`ON DELETE RESTRICT`). Dotčená příjemka → 422 `RECEIPT_ALREADY_USED` s výpisem dotčených šarží;
takovou nesrovnalost je nutné řešit ruční korekcí na kartě dílu (`docs/funkce/sklad-pohyby.md`),
ne stornem celého dokladu. Šarže se při stornu zamykají `FOR UPDATE` (vzor K6) a přechod stavu
je guarded (`WHERE status = 'CONFIRMED'`), takže dvojí storno neprojde.

## Vlastnosti vyplývající ze vzorových dokladů (`import/`)

- **LKQ faktura**: sazba na řádku písmenem („C") → převod přes extrahovanou rekapitulaci (A 0 %, B 12 %, C 21 %), ne hardcode; řádkový součet je tištěn jen bez DPH → s DPH dopočítává kód (`DERIVED`).
- **LKQ faktura se skupinovým řádkem** „Dodací list č. X celkem…": extrakce ho označí `DELIVERY_NOTE_GROUP` (není položka) a číslo DL se sbírá do `deliveryNoteRefs` — podklad pro dedup DL ↔ faktura (fáze 7: volba „provázat / naskladnit znovu").
- **AUTO RAVIRA faktura**: poškozená textová vrstva PDF → prompt instruuje číst vizuálně.
- **Ručně psaný dodací list (foto)**: uvádí často jen cenu s DPH bez jednotkové ceny → kód dopočte **zpětně** základ (`s DPH / (1+sazba)`) i jednotkovou cenu (`DERIVED`); řádky za práci a spotřební materiál kontrolor vyřadí z naskladnění (přepínač `ITEM`↔`NOTE`); díly bez katalogového čísla se napárují na existující kartu — SKU je povinné jen pro nově zakládaný produkt. Prompt instruuje číst i ruční písmo a cenu jen s DPH dát do `totalInclVat`.

## Zvolené kompromisy

- **Defaulty v `application.yaml`** (`warehouse.import.defaults`: vat-rate 21, currency CZK, unit ks, tolerance 0.05) — mění je vývojář, ne mechanik; bean `WarehouseImportProperties` je jediný šev pro případný přesun do DB.
- **Uzavřený číselník měrných jednotek** (`warehouse.import.allowed-units`: ks, l, kg, bal, m, sada, pár) — jednotka mimo číselník blokuje potvrzení (`RECEIPT_INCOMPLETE`, klíč `invalidUnits`) a create/update karty (`INVALID_UNIT`, 422). Vědomě **bez DB CHECKu** — stará data mimo číselník dožijí, validuje/normalizuje se při vstupu; platná jednotka se ukládá kanonicky („KS" → „ks"). FE nabídku zrcadlí konstanta `src/api/units.js` (rozhodnutí Z-4, analyza-sklad-2026-07).
- **`invoice_number` se nepřejmenovává** na `document_number` (TD-40) — sémantiku určuje `document_type` + COMMENT.
- **Dodavatel se při importu nezakládá** — neznámý dodavatel je jen `matchState=NONE` v draftu; založí ho až potvrzení. Odpadá dřívější 422 `SUPPLIER_NOT_EXTRACTED` při importu.
- **Duplicitní import** se hlásí (409) jen když je dodavatel napárovaný; drafty bez dodavatele hlídá až potvrzení.
- **Jen CZK** — potvrzení dokladu v jiné měně blokuje completeness gate (`RECEIPT_INCOMPLETE`, klíč `currency`); měnu lze v review přepsat jako každé jiné pole. Kurzy se zavedou až při reálné potřebě (rozhodnutí R-F, analyza-sklad-2026-07).

## Ověření

- Unit: `DraftAssemblerTest` (mapování sazeb, dopočty, defaulty), `DraftVerificationServiceTest` (IČO checksum, tolerance, **tautologické vs. nezávislé kontroly** — KN-17).
- Integrace (Testcontainers): `WarehouseImportServiceTest` — import ukládá jen draft; písmenná sazba end-to-end; DL defaulty; 409/201 sémantika vč. re-importu po REJECTED. `ReceiptReviewServiceTest` — potvrzení a jeho guardy: **dvojí naskladnění** (nepřiřaditelné provázání DL → 422, přiřaditelné přeskočí jen své řádky, doklad bez IČO podruhé → 409) a **deaktivovaná karta/dodavatel** (reaktivace vs. odmítnutí).
- Manuálně nad reálnými PDF: `./mvnw test -Dtest=PdfDocumentExtractionManualTest -Dmanual.extraction=true` (vyžaduje `ANTHROPIC_API_KEY`; volá skutečné API, mimo CI).
