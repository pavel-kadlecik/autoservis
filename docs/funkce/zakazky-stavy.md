# Zakázka — stavový automat a podmínky zrušení

> Funkční dokument (co + proč). Seznam, filtry a sloupec fakturace: [zakazky-prehled.md](zakazky-prehled.md).
> Endpointy: `docs/api.md` §Zakázky. Článek nápovědy: `frontend/…/src/help/zakazky.md` §Stav zakázky.
> Zavedeno 2026-07-31 (audit 2026-07-30, nález KN-11, Vlna 3 bod 3.1).

## Co se změnilo a proč

Do zavedení automatu byl stav zakázky **prosté pole**: `PUT /orders/{id}` uložil, co přišlo.
Vyfakturovanou zakázku šlo jedním požadavkem přepnout na `CANCELLED`, `CANCELLED → RECEIVED`
prošlo taky a vydaný materiál zůstal trvale vydaný. Vedle sebe pak stála „zrušená" práce a platný
daňový doklad na ni — a sklad podhodnocený o díly, které fyzicky ležely v regálu.

Faktura svůj automat měla od začátku (`InvoiceStatus.canTransitionTo`). Zakázka ho teď má taky.

## Povolené přechody

```
RECEIVED · DIAGNOSIS · WAITING_FOR_PARTS · IN_PROGRESS · READY_FOR_PICKUP · COMPLETED
     ↕ libovolně mezi sebou, oběma směry
       (návrat z COMPLETED = znovuotevření, jen bez aktivní faktury — níže)
     → CANCELLED   (JEDINÝ terminální, se dvěma podmínkami — níže)
```

**Mezi provozními stavy je pohyb volný, i dozadu.** Servis reálně skáče: díl přijde poškozený,
takže se z „Probíhá" vrací na „Čekání na díly"; diagnostika se otevírá znovu. Automat vynucující
jediné pořadí by obsluhu nutil lhát o stavu vozu. Zakázáno je proto jen to, co je věcně nevratné.

**Terminální je jen `CANCELLED`** (od 2026-08-06). Ze zrušené zpět by oživilo zakázku, jejíž
materiál se už vrátil na sklad; má-li se na voze znovu pracovat, zakládá se nová zakázka.

**`COMPLETED` byl do té doby terminální taky — a byla to chyba.** Omylem kliknuté „Dokončena"
tím bylo v celé aplikaci nevratné: zakázka nešla vrátit do provozu ani zrušit, a protože tehdy
neexistovalo ani mazání, zůstala v evidenci navždy. Znemožňovalo to i reklamaci, tedy zcela
běžnou situaci — auto se vrátí a v opravě se pokračuje. Odůvodnění „návrat by odemkl editaci
položek hotové práce" navíc **neplatilo**: položky zamyká faktura, ne stav zakázky.

**Reklamace se řeší novou zakázkou, ne znovuotevřením** (rozhodnutí uživatele 2026-08-07).
Vrátí-li se auto s vadou z dřívější opravy, zakládá se **nová zakázka** — jako každá jiná práce.
Plán počítal se samostatnou funkcí (vazba na původní zakázku, datum uplatnění, hlídání zákonné
30denní lhůty, nulová fakturace u uznané reklamace); ta je **zamítnutá jako předčasná**, ne
odložená k dodělání. Nezavádí se tedy ani typ zakázky „reklamace", ani vazba mezi zakázkami.

Praktický dopad je jen jeden: vazba na původní opravu se v evidenci nedrží strojově. Dohledá se
přes **servisní historii vozidla** (karta vozu → zakázky), což je stejně místo, kam se obsluha
u pultu dívá. Reklamaci dílu **u dodavatele** to neomezuje vůbec — původ dílu (dodavatel a číslo
jeho faktury) nese od 2026-08-07 přímo položka zakázky, s proklikem na příjemku.

**Znovuotevření má vlastní podmínku a vlastní důsledek.** Projde jen u zakázky **bez aktivní
faktury** (422 `ORDER_REOPEN_BLOCKED_BY_INVOICE`; hláška radí storno konceptu, resp. dobropis) —
jinak by vedle sebe stála rozpracovaná práce a doklad, který ji vyúčtoval jako hotovou. Zároveň
se **vydaný materiál vrátí do rezervace**: díl fyzicky zůstává na autě, ruší se jen výdej, aby se
při dalším dokončení neodepsal ze skladu podruhé (netto dopad nula). `completed_at` se vynuluje.

**Nezměněný stav není přechod.** `PUT` nese celý záznam včetně stavu, takže i oprava překlepu
v popisu dokončené zakázky přijde se stavem `COMPLETED`. Identita proto projde — terminalita
znamená „žádný návrat do provozu", ne zámek celého záznamu. Popis, finální cenu i datum dokončení
lze u uzavřené zakázky dopsat. Zámek editace **položek** drží vystavená faktura, ne stav zakázky
(`ORDER_LOCKED_BY_INVOICE`).

Kde to je: `OrderStatus.canTransitionTo` / `isTerminal` / `isReopenable` (tvar workflow),
`OrderServiceImpl.requireAllowedStatusChange` (branka, kterou projde každá změna stavu — prochází
jí `PUT /orders/{id}` i vyhrazený `POST /orders/{id}/status`).

**Terminální stav je definován jako „není provozní ani vratný"**, ne výčtem. Nová hodnota
v ENUMu, o které nikdo nerozhodl, tím z automatu nic nepustí — chyba se ozve hned, místo aby se
tiše povolily všechny přechody. Enum drží dvě množiny: `OPERATIONAL` (pět provozních stavů)
a `REOPENABLE` (`COMPLETED`).

**Datum dokončení se doplňuje samo.** Přechod do `COMPLETED` nastaví `completed_at` na dnešek,
pokud ho volající neposlal; znovuotevření ho vynuluje. Váže se na **přechod**, ne na výsledný
stav — `PUT` je full-replace a obsluha smí datum u dokončené zakázky legitimně vymazat.

## Dvě podmínky zrušení

Obojí platí **jen pro cíl `CANCELLED`**. Dokončit zakázku s fakturou i s vydaným materiálem je
naprosto normální — materiál je namontovaný a faktura je právě to, co má z hotové práce vzniknout.

### 1. Zakázka s aktivní fakturou se zrušit nedá — `ORDER_HAS_ACTIVE_INVOICE` (422)

„Aktivní faktura" **se v tomto guardu nepočítá**: použije se `InvoiceMapper.findByOrderId`, která od
V69 vrací nestornovanou **a** nedobropisovanou fakturu — přesně to, co pouští částečný unikát
`uq_invoices_order_active`. Vlastní dotaz by ty dva predikáty rozešel a v jednom z nich by vznikla
tichá díra.

Cesta z blokace je proto stejná jako u přefakturování:

| Faktura | Co udělat | Hláška radí |
|---|---|---|
| `DRAFT` (koncept) | stornovat koncept | „Zakázku nelze zrušit — má koncept faktury. Nejdřív ho stornujte." |
| `ISSUED` / `PAID` | vystavit dobropis (§42/§45 ZDPH) | „Zakázku nelze zrušit — má fakturu 202607007. Vystavenou fakturu nelze stornovat — vystavte k ní opravný daňový doklad (dobropis), ten zakázku uvolní." |

Obojí zakázku uvolní i pro zrušení. Bez druhého řádku by šlo o slepou uličku: storno vystaveného
dokladu je od KN-1 zakázané, takže bez dobropisu by omylem vyfakturovanou zakázku nešlo zrušit
nikdy.

**Hláška jmenuje doklad přes `Invoice.describe()`** — „fakturu 202607001", nebo „koncept faktury",
dokud číslo není přidělené. Od V49 se číslo přiděluje až při vystavení, takže prosté zřetězení
s `invoiceNumber` tvrdilo obsluze „fakturu **null**" (audit 02/F-7). Stejný popis používá i zámek
položek v `OrderItemServiceImpl.requireOrderNotInvoiced`, kde ta hláška vznikla.

Popis je **ve 4. pádě**, protože oba volající ho vkládají do vazby „má …". První verze vracela
1. pád a hláška pak v prohlížeči zněla „má **faktura** 202607007" — chyba, kterou odhalil až
proklik, ne testy: ty kontrolovaly, že hláška obsahuje číslo dokladu, ne že je česky.

### 2. Zakázka držící materiál ze skladu se zrušit nedá — `ORDER_HAS_ISSUED_MATERIAL` (422)

Blokující je jen položka, jejíž materiál **skutečně odešel ze skladu** —
`OrderItemMapper.findIssuedByOrderId`. Hláška vyjmenuje **které** položky to jsou, s množstvím
a jednotkou, a pošle obsluhu na jejich smazání; `params` nese `orderItemIds`.

> **Změna od V83 (rezervační model).** Do zavedení rezervací blokovala **každá** položka s vazbou
> na šarži (`goods_receipt_item_id != null`), tedy i zakázka, ze které ze skladu nikdy nic
> neodešlo — přesně ta bolest, kvůli které rezervace vznikly. Hláška navíc o takovém dílu tvrdila
> „materiál vydaný ze skladu", přestože ležel v regálu. **Zakázku s pouhou rezervací lze zrušit**;
> rezervace se tím jen uvolní a do skladu se nezapisuje nic. Podrobně
> [rezervace-skladu.md](rezervace-skladu.md).

### Mazání vrací materiál taky (2026-08-07)

Mazání blokovala do té doby i **skladová** stopa: `countMovementsByOrderId` počítal jakýkoli
pohyb, tedy i vratku. Zakázka omylem založená na špatném voze, na kterou stihl někdo vydat díl,
tak zůstala v evidenci navždy — i když se materiál dávno vrátil a sklad byl v pořádku.

Nově blokuje **jen faktura**. Mazání vrátí veškerý vydaný materiál stejně jako zrušení a pohyby
v append-only ledgeru zůstávají; V87 kvůli tomu zahodila `fk_mov_order`, protože `SET NULL` by
jako UPDATE odmítl trigger `trg_movements_append_only` a kaskáda by znamenala přepisování
skladové historie. Pohyb pak nese ID zakázky, které už na nic neukazuje — týž vzor jako
`order_item_id` ve V83, a `id` se v PostgreSQLu nerecykluje, takže odkaz zůstává jednoznačný.

Rozdíl proti zrušení tím není v tom, *co snese*, ale **co znamená**: smazaná zakázka nikdy neměla
vzniknout, zrušená byla skutečná a jen k opravě nedošlo.

### Zrušení vrací materiál samo — `POST /orders/{id}/cancel` (2026-08-06)

Guard výše popisuje, co odmítne **prostá změna stavu** (`PUT` / `POST /status`). Vedle ní stojí
vyhrazená cesta `POST /orders/{id}/cancel`, která vydaný materiál **vrátí a zakázku zruší v jedné
transakci**. Frontend na zrušení volá právě ji; hláška `ORDER_HAS_ISSUED_MATERIAL` tak zůstává
záchytnou sítí pro přímé volání API, ne každodenní zkušeností obsluhy.

**Vrací se všechno, bez odškrtávání** (rozhodnutí uživatele 2026-08-06). Plán počítal s dialogem,
kde obsluha zaškrtne díly ležící zpátky v regále. Uživatel navrhl lepší model: **ze zrušené zakázky
nemá co zbýt.** Díly, které zůstaly namontované na voze, zákazník zaplatí — patří tedy na **novou
zakázku**, kterou obsluha založí a která obsahuje jen skutečně použité díly. Zrušená zakázka se
vyčistí celá a fakturuje se jen to, co se opravdu stalo. Odpadá tím nejen dialog, ale i rozpor,
kdy by zrušená zakázka nesla vydaný materiál, který nikdo nevyúčtoval.

**Faktura se řeší z téhož dialogu.** Detail i seznam nesou `invoiceStatus` a `invoiceId` aktivní
faktury (`OrderMapper.findById` / `search`, shodný predikát s `uq_invoices_order_active`), takže
`OrderCancelDialog` ví, co nabídnout: **koncept** smaže spolu se zrušením (číslo nemá, v řadě
nevznikne mezera), u **vystavené** faktury nepotvrzuje nic a pošle obsluhu na fakturu, odkud se
vystaví dobropis. Bez toho končila cesta hláškou a obsluha musela hádat, kam jít.

Následnou zakázku aplikace **nezakládá ani nenabízí** (rozhodnutí uživatele 2026-08-06) — obsluha
ji vytvoří sama; import dílů z příjemky je stejný jako u kterékoli jiné zakázky.

**Pořadí uvnitř `cancel` je záměrné:** nejdřív `requireNoActiveInvoice`, pak vrácení materiálu, pak
teprve branka `requireAllowedStatusChange`. Vracet materiál kvůli zrušení, které stejně neprojde,
nemá smysl; a branku nelze pustit dřív, protože do vrácení by `requireMaterialReturned` odmítla.

Tím padá poznámka o nesouměrnosti z plánu: hromadné zrušení i smazání jednotlivé položky se teď
chovají stejně — vrátí materiál po jednom potvrzení.

**Proč smazání položky, a ne „vratka":** vratka `ISSUE_RETURN` vzniká v celé aplikaci jen ze
zakázky — smazáním vydané položky nebo snížením jejího množství
(`OrderItemServiceImpl.delete` / `syncIssuedQuantity`); endpoint ručních pohybů povoluje jen
`ADJUSTMENT`, `WRITE_OFF`, `RETURN` a `ISSUE`. Dokud tedy vydaná položka existuje, drží šarži,
a smazání je nejkratší cesta, jak ji uvolnit.

> **Poznámka k zápisu rozhodnutí v plánu oprav.** Rozhodnutí u bodu 3.1 mluví o vrácení „pohybem
> `ISSUE_RETURN` (má vlastní `return_reason`)". `return_reason` ale patří **právě a jen** k typu
> `RETURN` (vratka dodavateli) — vynucuje to DB CHECK `chk_return_reason` (V18) a zrcadlí
> `StockMovementDto`. `ISSUE_RETURN` důvod nést nemůže; nese poznámku „Storno položky zakázky #…".
> Věcná podstata rozhodnutí (odmítnout, dokud není vráceno) platí, cesta je „smaž položku".

**Automatické vracení bylo zamítnuto** (rozhodnutí uživatele 2026-07-30): obešlo by rozhodnutí, co
se s dílem fyzicky stalo — může být namontovaný nebo poškozený — a v append-only ledgeru se takový
pohyb už neopraví. **Od 2026-08-06 to neplatí pro zrušení** — viz `POST /orders/{id}/cancel` výše:
otázka „co se s dílem stalo" má odpověď na následné zakázce, ne ve skladovém pohybu.

## Jak se stav mění v UI

**Nabídkou přímo v seznamu a na detailu zakázky** (2026-08-06), ne přes editační formulář. Stav se
mění nejčastěji ze všech polí, ale dosud se kvůli němu muselo otevřít celé `OrdersPageEdit`, uložit
full-replace `PUT` — a nechat se odnavigovat zpět na seznam, s ním pryč i filtr a stránka. Vyhrazený
`POST /orders/{id}/status` zapisuje jen `status` a `completed_at`; full-replace by při rychlé změně
ze seznamu přepsal popis a ceny hodnotami, které si klient nenačetl (týž typ chyby jako TD-47).
U dokončené zakázky se položky jmenují „Znovu otevřít — {stav}", ať je zřejmé, že to není obyčejná
změna.

**UI nabízí všechny stavy kromě současného a nefiltruje je** (rozhodnutí uživatele 2026-08-05).
Podmínky, které přechod skutečně zablokují, závisejí na stavu databáze — aktivní faktura u zrušení
i u znovuotevření, vydaný materiál, chybějící díl na skladě. Frontend je uhádnout nemůže, takže by
buď zašeďoval nesprávně, nebo by musel zdvojit pravidla, která patří do service. Autoritativní je
backend a jeho hláška.

Proto ty hlášky vyjmenovávají položky a říkají, co udělat — je to jediné, co obsluha uvidí.

## Ověření

- `OrderStatusTest` (unit, bez Spring kontextu) — celá matice 7×7 s ručně vypsanými očekáváními,
  zvlášť pohyb dozadu, znovuotevření, terminalita, identita a `null` cíl.
- `OrderStatusTransitionTest` (integrační) — zakázané přechody nezmění stav v DB; zrušení blokuje
  vystavená i konceptová faktura a po stornu konceptu / po vystavení dobropisu (V69) projde;
  **zrušit lze zakázku s pouhou rezervací**, s vydaným materiálem ne a po jeho vrácení zase ano;
  dokončení vydá materiál a doplní `completed_at`; **znovuotevření** bez faktury projde, s fakturou
  ne, vrací výdej do rezervace a opakované dokončení odepíše jen jednou; vyhrazená cesta prochází
  toutéž brankou.
- `OrderStatusTransitionTest` — detail nese aktivní fakturu: nefakturovaná `null`, koncept `DRAFT`
  s ID, po vystavení `ISSUED`, po dobropisu zase `null` (zakázka je zase volná).
- `OrderStatusTransitionTest` — `POST /cancel` vrátí veškerý vydaný materiál a zruší zakázku jedním
  voláním; s aktivní fakturou odmítne a materiál **nechá vydaný** (faktura se kontroluje první).
- `OrderItemInvoiceLockTest` — hláška zámku položek jmenuje koncept, ne „fakturu null".
