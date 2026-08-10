# Rezervace skladu — díl na zakázce vs. díl v regálu

> Funkční dokument (co + proč). Skladové pohyby: [sklad-pohyby.md](sklad-pohyby.md).
> Stavy zakázky: [zakazky-stavy.md](zakazky-stavy.md). Endpointy: `docs/api.md`.
> Článek nápovědy: `frontend/…/src/help/zakazky.md` §Materiál ze skladu.
> Zavedeno 2026-08-05/06 (rozprava o modulu zakázek, Etapa 1), migrace V83.

## Co se změnilo a proč

Do V83 bylo **přidání dílu na zakázku okamžitě výdejem ze skladu**. Import z příjemky rovnou
zapsal pohyb `ISSUE` a snížil stav. Z toho plynulo skoro všechno, co obsluhu na modulu zakázek
štvalo:

- zakázku s materiálem **nešlo zrušit ani smazat**, dokud se položky ručně nesmazaly — u osmi
  dílů devět potvrzovacích dialogů;
- ve skladovém deníku vznikaly pohyby z **administrativy**, ne z fyziky: „vydáno 4, vráceno 4,
  vydáno 3", přestože se ve skutečnosti jen opravil počet;
- **množství u skladové položky nešlo změnit** a zadané číslo se navíc tiše zahodilo.

Nově je přidání dílu jen **rezervace**: díl leží dál v regálu a klesne pouze *dostupné*
množství. Ze skladu odejde teprve výdejem. Ledger tak obsahuje jen skutečné fyzické události
a plán se od skutečnosti odděluje.

Rešerše devíti autoservisních systémů a čtyř ERP (2026-08-05) ukázala, že rezervaci má
**každý** z nich — R.O. Writer a Mitchell 1 „Committed", Protractor „On Work Order",
AutoFluent „Available", Odoo „Reserved", SAP rezervace. Nikdo nedělá „nestane se nic až
do konce".

## Tři čísla o zásobě

| Číslo | Co znamená | Kdo se podle něj řídí |
|---|---|---|
| **Skladem** (`quantityOnHand`) | fyzicky v regálu | inventura, ocenění skladu |
| **Rezervováno** (`quantityReserved`) | slíbeno otevřeným zakázkám, ještě nevydáno | rozpad na kartě dílu |
| **Dostupné** (`quantityAvailable`) | `skladem − rezervováno` | plánování, hlídání minima |

**Rezervace nikdy nesnižuje fyzický stav.** Kdyby do něj vstupovala, hlásila by inventura manko
u dílů, které v regálu leží. Shodují se na tom SAP, Business Central, Odoo i NetSuite; SAP to
říká doslova: *„Reservation is a kind of requirement and has no impact on stock level at all."*

**Rezervace se neukládá, odvozuje se** — položka zakázky s vazbou na šarži
(`order_items.goods_receipt_item_id`, V27), na neuzavřené zakázce, ke které neexistuje výdejový
pohyb. Uložený příznak vedle pohybového ledgeru by se s ním mohl rozejít; takhle zůstává ledger
jediným zdrojem pravdy. Kde to je: `WarehouseMapper.xml`, fragment `reservedQuantity`.

**Ručně zadaná položka materiálu** (bez vazby na šarži) nerezervuje nic — sklad se jí netýká.

## Životní cyklus dílu na zakázce

```
přidání dílu z příjemky
        ↓
   REZERVOVÁNO        díl v regálu, klesne jen dostupné, ledger se nedotkne
        ↓
   tlačítko „Vydat ze skladu“  NEBO  dokončení zakázky — co nastane dřív
        ↓
     VYDÁNO           pohyb ISSUE, klesne fyzický stav
```

**Výdej vydává celou zakázku najednou** (rozhodnutí uživatele 2026-08-05) — vše, co ještě vydáno
nebylo. Opakované volání nic nezdvojí, protože už vydané položky do výběru nespadnou; naopak
položka, jejíž výdej se vrátil, se vydá znovu.

**Rozlišení vydané od rezervované** drží sloupec `stock_movements.order_item_id` (V83). Dosud
pohyb nesl jen `order_id`, takže u zakázky s více řádky z různých šarží to nešlo poznat. Sloupec
je **záměrně bez cizího klíče**: ledger je append-only (V52) a žádná varianta `ON DELETE`
neprojde, aniž by něco rozbila — `CASCADE` maže řádky ledgeru, `SET NULL` vyvolá UPDATE, který
shodí append-only trigger a tím rozbije mazání položky, `RESTRICT` to mazání zablokuje rovnou.
Id se v PostgreSQL nerecykluje, odkaz proto zůstává jednoznačný i po smazání položky.

**Vydáno je buď nic, nebo přesně tolik, kolik položka říká.** Na tom invariantu rozlišení stojí —
částečně vydaná položka by se jevila jako celá vydaná a zbytek by se nevydal nikdy. Drží ho
`syncIssuedQuantity` (viz níž).

## Co se stane při jednotlivých úkonech

| Úkon | Rezervovaná položka | Vydaná položka |
|---|---|---|
| **Změna množství** | jen se změní slib, sklad se nehne | rozdíl dorovná protipohyb — snížení vrátí přebytek, zvýšení dovydá |
| **Smazání položky** | nezapíše se nic | vrátí se `ISSUE_RETURN` na *vydané* množství |
| **Zrušení zakázky** | projde, rezervace se uvolní | odmítnuto `ORDER_HAS_ISSUED_MATERIAL` |
| **Dokončení zakázky** | materiál se vydá | už vydané se nevydá podruhé |

Porovnává se vždy s **ledgerem**, ne s předchozí hodnotou položky: deník je jediný zdroj pravdy
o tom, co odešlo, a rovnou tak vyjde i opakovaná změna nebo částečné vrácení. Slouží k tomu
`WarehouseImportMapper.findIssuedQuantityByOrderItemId` — obrácený součet pohybů položky.

**Deník tím popisuje realitu.** Při opravě 4 → 3 zapíše *vratka 1 ks*, ne dosavadní *vratka 4 ks
+ výdej 3 ks*, což se nikdy nestalo.

## Kdy dokončení neprojde

Rezervace šarži **nezamyká** — drží jen dostupnost. Mezitím ji tedy může vyprázdnit inventurní
korekce, odpis nebo vratka dodavateli. Při dokončení pak není co vydat: vrací se 422
`STOCK_MISSING_FOR_ISSUE` s výčtem chybějícího a **celá změna se vrátí zpět**, takže zakázka
zůstane nedokončená. Dokončená zakázka s materiálem, který na skladě není, by rozešla papír
a regál.

Souběh o poslední kus se naopak odhalí **při plánování**, ne u pultu: import validuje proti
dostupnému a druhý zájemce dostane 422 `QUANTITY_EXCEEDS_REMAINING` s hláškou, která rozlišuje,
jestli díl chybí, nebo jen leží slíbený jinde. Šarže se zamykají `FOR UPDATE` (vzor K6).

> **Samotný zámek na to nestačí — a první verze to nedělala správně.** Rezervace zůstatek šarže
> vůbec nemění, takže zámek konkurenční transakci sice pozdrží, ale nic jí neřekne. A dokud se
> součet rezervací počítal poddotazem **uvnitř** zamykajícího SELECTu, vyhodnotil se nad snímkem
> pořízeným při **startu příkazu** — tedy dřív, než první transakce svou rezervaci commitla.
> Druhá tak viděla rezervováno 0 a poslední kus dostaly obě.
> Opraveno rozdělením na dva příkazy: nejdřív se šarže zamkne (`findByIdsForUpdate`), teprve pak
> se **samostatným dotazem** (`findReservedByBatchIds`) načtou rezervace — v režimu READ COMMITTED
> dostane každý příkaz čerstvý snímek. Pokrývá `StockReservationConcurrencyTest`, který drží první
> transakci otevřenou na západce, takže souběh je deterministický, ne náhoda časování.

> **Proč se výdej validuje proti fyzickému zbytku, a ne proti dostupnému:** vydávané položky jsou
> samy součástí rezervace, takže proti dostupnému by se odečetly podruhé a výdej by neprošel nikdy.

## Co uvidí obsluha

- **Přehled skladu** — tři sloupce v pořadí, v jakém se to počítá: **Skladem − Rezervováno =
  Dostupné** (od 2026-08-06; do té doby byla rezervace jen poznámkou pod dostupným, takže nešla
  porovnat mezi řádky). Odznak *nízká zásoba* patří k dostupnému. Řádek bez rezervace má ve
  sloupci pomlčku, ne nulu — sloupec nul by řádky s rezervací utopil.
- **Karta dílu** — tabulka **Rezervováno na zakázkách** s číslem zakázky (proklik), zákazníkem,
  stavem, množstvím a datem. Odpovídá na otázku „proč je dostupné míň, než mám v regálu";
  obsluha se může domluvit na přerovnání místo objednávání.
- **Zakázka** — tlačítko **Vydat ze skladu** v hlavičce (seznam, detail i editace, viz
  `orderActions.jsx`); je to zkratka, ne povinný krok.
- **Položky zakázky** — sloupec **Sklad** se stavem *Rezervováno / Vydáno / —* a pod názvem
  katalogové číslo dílu (doplněno 2026-08-07, komponenta `OrderItemStock`). Do té doby vypadal
  ruční materiál, rezervace i výdej v tabulce identicky, přestože jde o tři různé stavy — model
  je zavedl do dat, ale na obrazovku se nepropsaly. SKU rozliší dvě položky z různých šarží se
  shodným dodavatelským názvem.
- **Okno výběru šarží při importu na zakázku** — také dvě čísla, „Zbývá" a „Dostupné" (týž rozdíl
  jako ve skladu, jen o úroveň níž: šarže místo dílu). Množství k importu se stropuje **dostupným**.
  Šarže rezervovaná celá zůstane v seznamu, ale nejde vybrat.

> **Doplněno 2026-08-06 — chybějící kus téhle funkce.** Okno šarží se do té doby řídilo jen zbytkem
> šarže: vstup „4" označilo za platný a server ho odmítl s `QUANTITY_EXCEEDS_REMAINING`, tedy až po
> odeslání — a protože je import jedna transakce, spadla s ním celá dávka včetně řádků, které byly
> v pořádku. Při víc kolizích se navíc hlásí první z nich, takže obsluha objevovala kolize v kolech.
> Byla to přesně ta frustrace, kvůli které rezervace vznikly, jen posunutá o krok dál. Rezervace se
> teď v nabídce počítá **týmž fragmentem** `reservedOnBatch` jako validace importu, aby se ta dvě
> pravidla nemohla rozejít. Na tuhle cestu do té doby neexistoval **žádný** test — proto mezera
> přežila zavedení celého modelu.

**Minimum se hlídá proti dostupnému**, a to na obou místech stejně — v seznamu skladu (filtr
`lowStockOnly`) i na samostatné obrazovce „Pod minimem" (`findLowStock`, včetně `missingQuantity`).
Obrazovka „Pod minimem" proto ukazuje **Skladem · Rezervováno · Dostupné** (doplněno 2026-08-06):
dokud tam stálo jen fyzické množství, nevycházela na ní aritmetika — *skladem 2, minimum 5,
chybí 4* — protože `missingQuantity` se počítá z dostupného, ale rezervace se nezobrazovala,
přestože ji `LowStockDto` posílá. Táž chyba jako u okna šarží: backend přepnutý na dostupné,
obrazovka zůstala u fyzického.
Díl slíbený jiné zakázce je pro další práci nedostupný a proti fyzickému stavu by se pod minimem
objevil až ve chvíli, kdy fyzicky odejde — tedy pozdě na objednání.

## Ověření

- `OrderItemImportTest` — import nesnižuje šarži, teprve výdej ano; opakovaný výdej nic nezdvojí.
- `OrderItemServiceTest` — smazání rezervace nezaloží pohyb, smazání vydané položky vrátí
  vydané množství; změna množství ve všech třech variantách (rezervace / snížení / zvýšení).
- `OrderStatusTransitionTest` — zakázku s pouhou rezervací lze zrušit; s vydaným materiálem ne;
  dokončení materiál vydá; chybí-li díl, dokončení neprojde a stav zůstane; opakované uložení
  dokončené zakázky nevydá podruhé.
- `LowStockTest` — rezervace sníží dostupné a díl spadne pod minimum, fyzický stav zůstane.
- `ImportableBatchesTest` — nabídka šarží nese zbytek, rezervaci i dostupné; po výdeji rezervace
  mizí a klesne zbytek; celá rezervovaná šarže se dál nabízí s nulou dostupných; zrušení zakázky
  rezervaci uvolní.
- `WarehouseProductConverterTest`, `OrderItemConverterTest` — odvozená pole a odemčené množství.
- `StockReservationConcurrencyTest` — dvě zakázky o poslední kus: rezervaci dostane jen první.
  Jediný netransakční test v projektu (vlákna na sebe musí vidět), po sobě uklízí ručně.
- `OrderItemApiContractTest` — HTTP kontrakt výdeje: tvar odpovědi, opakované volání, 422
  s výčtem chybějícího a **404 u neexistující zakázky** na všech čtyřech cestách.

> **Vyřešeno 2026-08-07.** Do té doby vracel `POST /issue-stock` u neexistující zakázky
> 200 s `issuedItems: 0` — `findReservedByOrderId` prostě nic nenašel. Ukázalo se, že díra
> je v celé službě: `GET /items` vracel `200 []`, `/items/summary` samé nuly a `POST /items`
> doběhl až k INSERTu a spadl na cizím klíči, takže obsluze vyšlo 422 „Zadaná data porušují
> databázové omezení". Služba tedy nerozlišovala **prázdnou** zakázku od **neexistující**
> a u zápisu nechala probublat chybu integrity, přestože pravidlo projektu je odmítnout dřív
> a česky. Zavedeno `requireOrderExists` na všech vstupech, které berou `orderId`.
