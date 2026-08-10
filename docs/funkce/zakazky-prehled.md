# Zakázky — přehled, filtry a stav fakturace

> Funkční dokument (co + proč). Endpointy: `docs/api.md` §Zakázky. Rozhodnutí o zrušení
> soft-delete: `docs/tech-dluhy.md` TD-67. Článek nápovědy: `frontend/…/src/help/zakazky.md`
> (téma `zakazky` v `help/index.js`).
> Stav: sloupec fakturace + filtry stav/po termínu implementovány (2026-07-27).

## Princip: seznam zakázek odpovídá na tři provozní otázky

Výpis zakázek (`GET /orders`) je hlavní pracovní obrazovka dílny. Kromě identifikace
(číslo, zákazník, vozidlo) a stavu zakázky odpovídá na tři otázky, které si obsluha
klade nejčastěji: **je to vyfakturované?**, **chci vidět jen zakázky v konkrétním
stavu**, a **co mi přetéká přes slíbený termín?**

## Sloupec „Faktura" — stav fakturace zakázky

Sloupec ukazuje stav **aktivní** (nestornované) faktury zakázky, nebo „—", když zakázka
fakturu nemá. Hodnoty jsou tři + prázdno:

| Zobrazeno | Znamená | Stav faktury |
|---|---|---|
| „—" (muted) | nefakturováno (nebo jen stornované faktury) | žádná aktivní |
| **Koncept** | koncept založen, ale ještě není doklad (číslo dostane až vystavením) | `DRAFT` |
| **Vystavena** | doklad vystaven | `ISSUED` |
| **Zaplacena** | doklad uhrazen | `PAID` |

**Proč tři stavy, ne binární „vyfakturováno ano/ne":** koncept (`DRAFT`) ještě **není
doklad** — nemá ani číslo, to i daňovou povahu dostává až vystavením (PDF nese vodoznak NÁVRH). Kdyby sloupec u konceptu ukazoval „Vystavena",
lhal by. „—" je proto prostý text, ne odznak — *nefakturováno* není stav faktury.

**Jak vzniká (odvozená projekce, ne uložený sloupec):** `OrderMapper.search` má
`LEFT JOIN billing.invoices … status <> 'CANCELLED'` a stav promítá do
`OrderDto.ListResponse.invoiceStatus`. Partial unique index `uq_invoices_order_active`
(V48) zaručuje **nejvýš jednu aktivní fakturu na zakázku**, takže join nenásobí řádky a
nepotřebuje `GROUP BY`. Stornované faktury se ignorují záměrně — po stornu lze zakázku
fakturovat znovu, takže „má fakturu" = „má **aktivní** fakturu". Řadit podle tohoto
sloupce nelze (není v whitelistu `orderSortOrder` — jde o odvozeninu, ne sloupec `orders`).

## Filtr podle stavu zakázky

Select „Stav zakázky" (prázdné = všechny) filtruje výpis na jeden stav `OrderStatus`.
Filtr je ve **sdíleném** `WhereClause` mapperu, takže se promítá i do `countSearch` —
stránkování počítá jen odpovídající řádky, ne celý seznam.

Pozor na rozdíl: *řazení* podle stavu (klik na hlavičku „Stav") existovalo už dřív a řadí
podle pořadí ve workflow, ne abecedně. Tenhle select **filtruje**, neřadí.

## Filtr „Po termínu"

Select „Termín" s volbou „Po termínu" (jinak „Vše") vrací zakázky, které jsou **po
slíbeném termínu a přitom nedokončené**: `estimated_completion_at < now()` a
`status NOT IN ('COMPLETED','CANCELLED')`. Zakázky bez termínu vypadnou samy
(`NULL < now()` není pravda).

Je to přímá analogie filtru „Po splatnosti" u faktur (`overdue`) — stejný vzhled i
chování, jen místo splatnosti dokladu jde o termín dokončení práce. „Uzavřená" zakázka
(dokončená/zrušená) už po termínu být nemůže, i kdyby termín dávno minul.

## Servisní historie vozidla a zákazníka (KN-27)

Do 2026-07-31 se na zakázky konkrétního vozu dalo dosáhnout **jen fulltextem** přes SPZ nebo VIN —
oklikou, kterou obsluha nemá odkud vědět, a u vozu bez SPZ (pole je nullable) jedině přes VIN.
Servisní historie vozu je přitom nejčastěji potřebná informace u pultu (reklamace, „co jsme tam
dělali posledně").

**Backend:** `OrderSearchParams` má `vehicleId` a `customerId`, obojí ve **sdíleném** `WhereClause`,
takže se filtr promítá i do `countSearch` a stránkování počítá jen zúžený seznam. Filtruje se přes
`o.vehicle_id` / `o.customer_id` (indexy `idx_orders_vehicle_id`, `idx_orders_customer_id` z V53),
ne přes JOINnuté tabulky. Filtr kolekce query parametrem je vzor z `konvence.md §10`
(`GET /vehicles?customerId=5`), ne porušení R-14 — `id` v URL identifikuje *resource*, tady jde
o zúžení seznamu.

**Frontend:** karta „Servisní historie" na detailu vozidla a karty „Zakázky zákazníka" +
„Faktury zákazníka" na detailu zákazníka (`OrderHistoryTable`, `CustomerInvoicesTable` — vnořené,
`dense`, klientské řazení, jediná akce Detail). Zakázky se berou po **10 nejnovějších**; je-li jich
víc, odkaz „Zobrazit všech N" vede na `/orders?vehicleId=…` (resp. `?customerId=…`). `OrdersPage`
proto čte `useSearchParams` — dosud je neuměla, na rozdíl od `InvoicesPage` — a zúžení **pojmenuje**
(dohledá SPZ vozu / jméno zákazníka) a nabídne „Zrušit zúžení". Bez toho by seznam mlčky ukazoval
výsek.

**Proč faktury bez stránkování:** `GET /invoices/customer/{id}` vrací celý seznam (endpoint
existoval už dřív, FE ho nikdy nevolal — týž vzorec „hotový backend bez cesty k uživateli", jaký
audit vytkl u dobropisu). Faktur je nejvýš tolik jako zakázek, takže se do karty vejdou celé
a nepotřebují druhý filtr v API.

**Karty načítají data vlastním requestem s `try/catch`** — kdyby historie selhala, nesmí sundat celý
detail vozidla nebo zákazníka. Chyba se ukáže jen v té kartě.

## Rušení zakázky: stavem `CANCELLED`, ne mazáním (R-06 výjimka)

> Kudy smí zakázka mezi stavy projít a za jakých podmínek ji lze zrušit (aktivní faktura, nevrácený
> materiál) řeší od 2026-07-31 stavový automat — samostatný dokument
> [zakazky-stavy.md](zakazky-stavy.md).

Zakázka je **doklad** — odkazují na ni faktury (`order_id`) a skladové pohyby (výdej
`ISSUE` na zakázku). Proto se nemaže ani „nedeaktivuje": ruší se **stavem `CANCELLED`**.
Obecný projektový soft-delete přes `is_active` (R-06) se u zakázek **záměrně nepoužívá** —
byl to jen scaffold z počátku projektu, backendový endpoint (de)aktivace nikdy neexistoval
a příznak jen zdvojoval význam `CANCELLED`. Odstraněno 2026-07-27, plné odůvodnění a osud
DB sloupce viz `tech-dluhy.md` TD-67.

## Co je vědomě odloženo

- **Řazení podle stavu fakturace** — vyžadovalo by rozšíření whitelistu `orderSortOrder`
  o odvozený sloupec; zatím bez poptávky.

## Ověření

`OrderInvoiceStatusProjectionTest`: `invoiceStatus` přes celý životní cyklus faktury
(null → DRAFT → ISSUED), po stornu zpět null (join filtruje CANCELLED).
`OrderSearchTest`: filtr stavu vrací jen daný stav a promítá se do `countSearch`; filtr
`overdue` vrátí otevřenou zakázku po termínu a vynechá dokončenou, budoucí i bez termínu.
