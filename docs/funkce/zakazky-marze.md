# Marže v editaci zakázky

> Funkční dokument (co + proč). DB: `docs/databaze.md` §pohledy (V63). Náklad práce
> a snapshot sazby: `docs/funkce/zamestnanci.md` (D-3/D-6). Párový článek nápovědy:
> `frontend/…/src/help/zakazky.md` §Souhrn.
> Stav: implementováno 2026-07-30.

## Princip: cenu položek nastavuješ podle marže, ne naslepo

Souhrnná tabulka v editaci zakázky (Práce / Materiál / Ostatní / Celkem) dosud
ukazovala jen tržby (bez DPH, s DPH). Obsluha ale položky **ceníkuje právě tady** —
a k rozhodnutí „za kolik to prodám" potřebuje vidět, **kolik ji to stojí a co jí
zbyde**. Proto tabulka nově ukazuje per kategorie i **Náklad**, **Marži (Kč)**
a **Marži (%)**.

Marže se počítá **výhradně z cen bez DPH**: `marže = tržba bez DPH − náklad`.
DPH je pro plátce průběžná položka (vybere ji a odvede státu), není to výnos ani
náklad — „marže s DPH" neexistuje. Procento je obchodní marže z prodejní ceny:
`marže / tržba bez DPH`.

## Odkud se bere náklad

Náklad položky je `purchase_price × quantity`. `purchase_price` plní tři cesty:

| Typ položky | Zdroj nákladu |
|---|---|
| **LABOR** | hodinová sazba přiřazeného mechanika — snapshot při přiřazení (D-3/D-6), pozdější změna sazby položku nepřepíše |
| **MATERIAL** ze skladu | nákupní cena šarže z příjemky (needitovatelná na položce) |
| ruční položka (kterýkoli typ) | zadá uživatel do pole „Nákupní cena [Kč bez DPH]" |

Položka **bez nákupní ceny má náklad 0** → marže vyjde 100 %. To je vědomý
kompromis (viz níže), FE na něj upozorňuje popiskem pod tabulkou.

## Jak se počítá: ve view, ne na frontendu

V63 rozšiřuje `"order".v_order_item_summary` (`CREATE OR REPLACE`, V25 zůstává
nedotčená) o `labor_cost`, `material_cost`, `service_cost`, `total_cost`:
`SUM(ROUND(quantity × COALESCE(purchase_price, 0), 2))` per kategorie.

**Proč ve view a ne součtem na FE:** souhrn už z view čte (tržby) — náklad počítaný
jinde by dřív či později „ujel" (jiné zaokrouhlení, jiný filtr položek). Takhle je
jeden zdroj pravdy a **stejná filozofie zaokrouhlení po řádku** jako u `line_net`
(V25/V32/V37): haléřová konzistence mezi řádky a součty.

Samotnou **marži a procento dopočítává FE** (`OrderItemsSummary.jsx`) z `net − cost` —
je to prostý rozdíl dvou zobrazených čísel, ukládat ho nemá smysl. Záporná marže se
zobrazuje červeně, kladná zeleně; při nulové tržbě je procento „—".

## Viditelnost: vidí ji každý, kdo edituje zakázku

`purchase_price` je v DTO položky a ve formuláři už od zavedení — kdo smí editovat
zakázku, nákupní ceny vidí. Marže tedy **nezpřístupňuje nic nového**, jen to sčítá.
Omezení na vedení (management-only) je vědomě odložený záměr, stejná otázka jako
u ocenění skladu — viz `tech-dluhy.md` TD-68 (a TD-22).

## Co je vědomě odloženo

- **Role-gating marže/nákladů** — čeká na celkové rozhodnutí o autorizaci citlivých
  čísel (TD-22/TD-68), nemá smysl řešit izolovaně pro jednu tabulku.
- **Varování na prodej pod nákupní cenou** (u karty dílu i položky) — diskutováno
  2026-07-30, zatím záměrně bez implementace; záporná marže je v souhrnu vidět
  červeně, což pro teď stačí.
- **Náklad = 0 u položek bez nákupní ceny** — alternativa (položky bez nákladu
  z marže vynechat, jako to dělá dashboardový `sumMargin`) by rozbila rovnost
  `total_net − total_cost = marže celkem`; v souhrnu jedné zakázky je srozumitelnější
  počítat vše a upozornit popiskem.

## Ověření

`OrderItemServiceTest.summary_aggregatesCost`: materiál 3 ks × (prodej 300 / nákup
100) → `materialCost 300`, marže 600; `summary_splitsByItemType` ověřuje náklad 0
u položek bez nákupní ceny (COALESCE); `summary_emptyOrder_isZero` nulový souhrn.
`OrderItemSummaryConverterTest`: přenos všech čtyř `*Cost` polí do DTO.
