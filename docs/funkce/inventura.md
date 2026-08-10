# Inventura — soupis, rozdíly a korekce

> Funkční dokument (co + proč). Schéma: `docs/databaze.md` §6 (V44), endpointy: `docs/api.md`,
> mechanika pohybů: [sklad-pohyby.md](sklad-pohyby.md). Rozhodnutí: **R-H** v `docs/analyza-sklad-2026-07.md`.
> Článek nápovědy: `frontend/…/src/help/inventura.md`.

## Proč vznikla

Inventarizace je zákonná povinnost (§ 29–30 zákona o účetnictví) a zároveň jediný způsob, jak
srovnat evidenci s realitou. Bez ní je pohybový ledger jen teorie: každá nesrovnalost — ztráta,
záměna, chyba při zadávání — v něm zůstane navždy.

Ruční korekce (viz `sklad-pohyby.md`) byla stavební kámen: inventura generuje **tytéž pohyby**,
jen hromadně, s protokolem a v jedné transakci.

## Průběh

```
Zahájit inventuru
        │  (soupis se nasnapshotuje: všechny aktivní produkty)
        ▼
      OPEN ──► vyplňování napočítaných množství (i po částech, napříč dny)
        │                │
    Uzavřít           Zrušit
        │                │
        ▼                ▼
     CLOSED          CANCELLED
 (korekční pohyby:  (sklad beze změny)
  manko −, přebytek +)
```

**Otevřená smí být jen jedna inventura** (partial unique index) — dvě souběžné by si korekce
navzájem přepisovaly.

## Čtyři rozhodnutí, která stojí za vysvětlení

### 1. Nevyplněný řádek znamená „nepočítáno", ne nulu

`counted_quantity IS NULL` se při uzavření **přeskočí**. Kdyby prázdné pole znamenalo nulu,
nedokončená inventura by vynulovala celý sklad. Díky tomu lze počítat po částech — třeba regál denně.

### 2. Rozdíl se počítá proti *aktuálnímu* stavu, ne proti snapshotu

Snapshot z otevření (`expected_quantity`) je jen informativní („co systém čekal, když jsi začal").
Kdyby se korekce počítala proti němu, inventura by přepsala výdeje, které během počítání proběhly.
Proto: `rozdíl = napočítáno − aktuální stav` v okamžiku uzavření.

**Jenže po uzavření už „aktuální stav" ten rozdíl nezná** (V65, audit KN-2). Uzavření zaúčtuje
korekce, které stav srovnají na napočítané množství — živý výpočet pak u každého řádku dá 0
a uzavřená inventura nedoložila ani jedno manko, přestože je právě zaúčtovala. Banner hlásil
„0 mank a 0 přebytků" u dokladu, který jich zaúčtoval třeba dvacet.

Rozdíly se proto při uzavření **zmrazí** do `closed_difference` (a stav, proti kterému byly
počítány, do `closed_expected_quantity`) — `materializeDifferences` běží **před** zápisem korekcí
a v téže transakci, takže když uzavření spadne, odrolují se s ním. Detail uzavřené inventury pak
čte zmrazené hodnoty, ne živý stav. Inventura je doklad a §29–§30 zákona o účetnictví po ní chce
průkazný záznam o zjištěných rozdílech; bez tohohle by ho nesplnila.

Řádky bez napočítaného množství se nezmrazují — „nepočítáno" nesmí zvěcnět na doloženou nulu.

### 3. Manko se rozpouští po šaržích od nejstarší (FIFO)

Fyzicky se počítají kusy, ne šarže — ale korekce musí jít proti konkrétní šarži, jinak by se
rozešlo ocenění zásob (šarže je nositel nákupní ceny). Manko se proto odečítá od nejstarší šarže,
konzistentně s rozhodnutím R-A. Když manko převýší součet zůstatků šarží, uzavření skončí
422 `STOCK_TAKE_SHORTAGE_EXCEEDS_BATCHES` — záporný zůstatek nesmí vzniknout.

### 4. Přebytek vzniká jako šarže v pseudo-příjemce „Inventura"

`goods_receipt_items.goods_receipt_id` je `NOT NULL` — šarže bez příjemky vzniknout nemůže, a
uvolnit ten vztah by rozbilo invariant „každá šarže má původ". Uzavření proto založí **jednu
příjemku typu `STOCK_TAKE`** (bez dodavatele) a v ní šarži za zadanou cenu. Příjemka nese **číslo
inventury** `INV-{rok}-{4 číslice}` (V61) — stejné jako doklad inventury, ať je vazba čitelná
(dřív měla oddělené `INV-{id}`).
CHECK `chk_receipt_confirmed_complete` je pro tento typ uvolněný (V44) — přebytek nemá dodavatele.

**Ocenění a DPH (ČÚS č. 007):** inventurní přebytek je *nalezené zboží*, ne nákup ani dodávka —
účetně **výnos** (účet 648), oceněný **reprodukční pořizovací cenou** (cena, za kterou by se díl
pořídil teď). Proto: hlavička i řádky jsou **bez DPH** (`vat_rate = 0`, `vat_amount = 0`) —
skladová příjemka je jen evidenční doklad bez daňové vazby, DPH by měl jedině daňový doklad
(faktura), který přebytek nemá. Hlavičkové součty (`subtotal`/`total_amount`) = **hodnota zásoby**
= `Σ množství × cena` (bez DPH), aby doklad ukazoval, co se našlo za peníze. DPH přijde na řadu
teprve případným **prodejem** těch dílů, a to sazbou z karty dílu — ne z tohoto dokladu.

Cena přebytku se předvyplní z **nejnovější šarže** dílu a jde přepsat; u dílu bez šarže zůstane
prázdná a uzavření ji vyžádá (422 `STOCK_TAKE_PRICE_MISSING`). Nulová cena by podhodnotila sklad.

Zůstatek nové šarže se zakládá na 0 a dorovná ho až **kladný `ADJUSTMENT`** — stav skladu tak
vzniká jedinou cestou, pohybem přes trigger, stejně jako všude jinde.

## Co inventura nedělá

- **Nezamyká sklad** po dobu počítání. Pro jednu dílnu je to zbytečné; výdeje během počítání
  ošetřuje rozhodnutí č. 2.
- **Negeneruje tiskový protokol** (PDF) — soupis je zatím jen na obrazovce. Doplnit, až bude potřeba.
- **Neřeší inventuru po skladech/umístěních** — aplikace má jeden sklad.

## Ověření

Integrační test `StockTakeTest` (Testcontainers), 10 scénářů: manko rozpuštěné FIFO přes dvě šarže;
přebytek zakládající pseudo-příjemku `STOCK_TAKE` se správnou cenou a zůstatkem; nevyplněný řádek
negeneruje nic; manko nad zůstatky → 422; přebytek bez ceny → 422; druhá otevřená inventura → 409;
dvojí uzavření → 422; **rozdíl proti aktuálnímu stavu** (výdej mezi otevřením a uzavřením);
zrušená inventura je inertní; **uzavřená inventura doloží zjištěné rozdíly** i po zaúčtování
korekcí (V65/KN-2) a **nepočítaný řádek nedostane zmrazenou nulu**.
