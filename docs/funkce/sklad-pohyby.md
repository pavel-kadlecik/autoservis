# Skladové pohyby — ruční záporné pohyby (korekce, odpis, vratka, spotřeba)

> Funkční dokument (co + proč). Schéma: `docs/databaze.md` §6, endpointy: `docs/api.md`.
> Stav: ruční záporné pohyby implementovány (E2.1–E2.2 dle `docs/plan-sklad.md`).
> Článek nápovědy v aplikaci: `frontend/…/src/help/sklad-pohyby.md`.

## Princip: stav skladu se odvozuje, nikdy nepřepisuje

Zásoba není údaj, který by šel „opravit". `warehouse.stock_movements` je **append-only kniha pohybů**;
`products.quantity_on_hand` i `goods_receipt_items.quantity_remaining` jsou jen odvozené hodnoty, které
udržuje DB trigger `fn_apply_stock_movement`. Aplikace je nikdy nezapisuje přímo. Každá změna zásoby
tedy musí být **událost s důvodem** — a proto je u ručního pohybu povinná poznámka.

Tentýž princip drží zavedená praxe: SAP má ke každému pohybovému typu jeho storno protipohyb (101/102),
české účetní systémy neumožňují chybnou příjemku smazat — opravuje se dokladem (viz `analyza-sklad-2026-07` §2).

## Typy pohybů a kdo je vytváří

| Typ | Znaménko | Kdo ho vytváří | Stav |
|---|---|---|---|
| `RECEIPT` | + | potvrzení příjemky (`ReceiptReviewServiceImpl.confirm`) | ✅ |
| `ISSUE` | − | **výdej materiálu zakázky** (`OrderItemServiceImpl.issueStock`) — tlačítkem, nebo automaticky při dokončení zakázky; a dorovnání při zvýšení množství už vydané položky | ✅ |
| `ISSUE_RETURN` | + | smazání **vydané** položky zakázky nebo snížení jejího množství (vratka do původní šarže) | ✅ |
| `ADJUSTMENT` | − | **ruční korekce** — `POST /warehouse/products/{id}/movements`; hromadně i uzavření inventury | ✅ (záporná ručně, kladná z inventury) |
| `WRITE_OFF` | − | **ruční odpis** — tentýž endpoint | ✅ |
| `RETURN` | − | **ruční vratka dodavateli** — tentýž endpoint, s důvodem a číslem dobropisu | ✅ |
| `ISSUE` (bez zakázky) | − | **interní spotřeba** — tentýž endpoint, `order_id` zůstává prázdné | ✅ |

## Ruční pohyb: korekce, odpis, vratka a spotřeba

Na kartě produktu (tlačítko **Skladový pohyb**) lze zapsat:

- **Korekce −** (`ADJUSTMENT`) — manko zjištěné přepočtem, nesrovnalost oproti skutečnosti,
- **Odpis** (`WRITE_OFF`) — rozbité, znehodnocené, prošlé zboží,
- **Vratka dodavateli** (`RETURN`) — díl putuje zpět dodavateli (reklamace, špatně dodaný kus),
- **Spotřeba bez zakázky** (`ISSUE` s prázdným `order_id`) — materiál spotřebovaný v režii dílny
  (čistivo, spojovací materiál). Bez ní by uživatel musel volit mezi odpisem (což lže — zboží nebylo
  znehodnoceno) a fiktivní zakázkou.

Všechny čtyři vypadají skladově stejně, ale odpovídají na jinou otázku: *odpis* = „zboží je pryč
a nikdo nám ho neproplatí", *vratka* = „šlo zpátky dodavateli a čekáme dobropis", *spotřeba* =
„použili jsme ho my, jen ne na konkrétní zakázku", *korekce* = „evidence se rozešla se skutečností".

**Šarže se nabízejí od nejstarší** a nejstarší je předvybraná (FIFO, rozhodnutí R-A) — stejné pořadí,
v jakém rozpouští manko inventura. Tabulka šarží na kartě dílu zůstává řazená nejnovější první;
to je historický přehled, ne pořadí odebírání.

Pravidla, která server vynucuje:

- **Pohyb jde vždy proti konkrétní šarži** (`batchId` povinné). Bez šarže by se snížil jen celkový stav,
  ale zůstatky šarží by lhaly a ocenění zásob (fáze E3) by přestalo sedět.
- **Množství se zadává kladné**, server ho znegatuje — ruční pohyb tudy je vždy úbytek.
- **Poznámka je povinná** (min. 3 znaky) — bezdůvodná změna zásoby je přesně to, čemu ledger brání.
- **Nelze odepsat víc, než v šarži zbývá** → 422 `QUANTITY_EXCEEDS_REMAINING`; šarže cizího produktu
  → 422 `BATCH_PRODUCT_MISMATCH`. Šarže se při zápisu zamyká `FOR UPDATE`, takže souběžné korekce
  se serializují (vzor opravy K6).
- **Důvod vratky patří právě a jen k vratce** (`return_reason`) — validace DTO zrcadlí DB CHECK
  `chk_return_reason`; chybějící důvod u vratky i důvod u odpisu je 400. Číslo dobropisu je
  volitelné a smí být rovněž jen u vratky.
- **Spotřeba nemá zakázku** — `order_id` zůstává prázdné; výdej *na zakázku* dál běží výhradně přes
  výdej materiálu zakázky (`POST /orders/{id}/issue-stock`, nebo automaticky při jejím dokončení),
  kde se vazba na zakázku naopak vyžaduje.

> **Pozor — od V83 už import položek do zakázky žádný pohyb nezakládá.** Přidání dílu na zakázku
> je jen **rezervace**: díl leží dál v regálu a klesne pouze *dostupné* množství. Ledger proto
> obsahuje jen skutečné fyzické události, ne změny plánu. Podrobně
> [rezervace-skladu.md](rezervace-skladu.md).

## Kdy díl chybí: přehled „pod minimem"

Karta dílu má volitelné `min_stock_level`. Díly pod ním vypisuje stránka **Sklad → Pod minimem**
(`GET /warehouse/products/low-stock`) i s tím, **kolik chybí** a **u koho objednat**: doporučení
se bere z převodníku `supplier_products` — od dodavatele, který díl dodal naposledy, včetně jeho
katalogového čísla a poslední ceny. Díl bez záznamu v převodníku se vypíše taky, jen bez doporučení.

Je to **podklad pro objednání, ne objednávka** — objednávkový modul aplikace vědomě nemá
(analýza S-9: pro jeden servis je náklad/užitek špatný).

## Přebytek se nezadává pohybem (rozhodnutí R-E)

Kladná korekce by vytvořila zásobu **bez šarže a bez nákupní ceny** — díl by existoval, ale nešlo by
říct, co stál ani odkud je. To by rozbilo jak dohledatelnost (položka zakázky → šarže → faktura
dodavatele), tak budoucí ocenění zásob.

Proto se přebytek naskladňuje **ruční příjemkou** (`POST /warehouse/receipts` → kontrolní obrazovka →
potvrzení), která šarži i cenu založí. Modal ručního pohybu na to uživatele upozorňuje.

## Co je vědomě odloženo

- **Přijatý dobropis jako doklad** (`document_type = CREDIT_NOTE` v draft pipeline) — fáze E5b,
  rozhodnutí **R-G**. Vratka se dnes eviduje ručně (číslo dobropisu je jen pole u pohybu); dobropis
  jako doklad ke kontrole je opačný doklad než příjemka — musel by ukazovat na existující šarže a
  nesměl by zakládat karty, přičemž *která šarže* a *proč* z dokladu stejně vyčíst nejdou.
  Detail: `docs/plan-sklad.md` §6.
- **Cenový dobropis** (oprava ceny bez pohybu zboží) — aplikace nevede závazky ani účetnictví,
  takže by neměl kam jít; skladu se netýká.
- ~~Inventura jako proces~~ — **implementováno** (E6), viz [inventura.md](inventura.md). Generuje
  tytéž `ADJUSTMENT` pohyby, jen hromadně: manko po šaržích FIFO, přebytek šarží v pseudo-příjemce.
- **Storno potvrzené příjemky** — fáze E4, kompenzačními pohyby (rozhodnutí R-C).

## Ověření

Integrační test `ManualStockMovementTest` (Testcontainers): korekce sníží stav i zůstatek šarže
(ověřeno čísly po triggeru), odpis nad zůstatek → 422, šarže cizího produktu → 422, nepovolený typ
pohybu i chybějící poznámka → 400, pohyb je vidět v historii produktu včetně poznámky. Vratka:
uloží důvod i číslo dobropisu, bez důvodu → 400, důvod u odpisu i dobropis u korekce → 400,
nad zůstatek → 422. Spotřeba: sníží stav i zůstatek a v ledgeru má `order_id` prázdné.

`LowStockTest`: díl pod minimem vrátí chybějící množství i doporučeného dodavatele z převodníku;
díl bez převodníku se vypíše bez doporučení (nevypadne); díl nad minimem ani díl bez hlídání
se v přehledu neobjeví.
