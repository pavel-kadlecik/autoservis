# Zakázkový list — doklad o převzetí vozu

> Funkční dokument (co + proč). Endpoint: `GET /orders/{id}/protocol` (`docs/api.md` §Zakázky).
> Migrace: **V70** (`mileage_km_at_intake`). Článek nápovědy: `frontend/…/src/help/zakazky.md`
> §Zakázkový list. Zavedeno 2026-07-31 (audit 2026-07-30, nález KN-28, Vlna 3 bod 3.3).

## Proč vznikl

Servis neměl **jediný podepsatelný doklad o převzetí vozu**. Aplikace tiskla tři dokumenty —
fakturu, opravný daňový doklad a příjmový pokladní doklad — všechny až o peníze *po* opravě.
Chybělo to, co se v dílně podepisuje jako první: čí vůz servis přijal, v jakém stavu, co se má
udělat a za kolik to má přibližně být.

Bez toho stojí spor o rozsah nebo cenu na tvrzení proti tvrzení, a stav tachometru při příjmu —
údaj, který se později už nedohledá — se nikde neevidoval (audit 07/P-14).

## Co doklad obsahuje

| Blok | Obsah |
|---|---|
| Hlavička | logo, „ZAKÁZKOVÝ LIST", číslo zakázky, **datum přijetí** (`received_at`, V94 — zadává uživatel, do V94 se tisklo auditní `created_at`) |
| Servis | profil firmy (`company_profile`) — název, adresa, IČ, DIČ |
| Zákazník | jméno / firma, adresa (fakturační → výchozí → první), IČ, telefon, e-mail |
| Převzaté vozidlo | značka a model, SPZ, VIN, rok výroby, **stav tachometru při příjmu**, předpokládané dokončení |
| Požadovaná práce | popis zakázky (`description`) |
| Odhad ceny | `estimated_price` s DPH + věta o tom, že navýšení servis předem oznámí (§1746 NOZ) |
| Podpisy | dva řádky — za servis a zákazník, oba s datem |

**Není to daňový doklad.** Nemá číselnou řadu ani snapshoty stran a tiskne se ze **živých** dat
zakázky, zákazníka a profilu firmy. To je vědomý rozdíl proti faktuře: zakázkový list se tiskne
při příjmu vozu, není evidenčním dokladem podle ZoÚ a nemá co zmrazovat.

## Datum přijetí zadává uživatel (V94)

Hlavička do V94 tiskla `created_at` — auditní okamžik vzniku záznamu v DB. Uživatel s automatickým
datem nesouhlasil a má pravdu: vůz mohl přijet včera večer a zakázka se zapisuje až dnes, papír pak
tvrdil špatné datum. Proto má zakázka **`received_at`** — obchodní datum přijetí vozidla, povinné
pole formuláře **předvyplněné dneškem** (automatika jako výchozí hodnota, ne diktát). Hodnota není
ničím omezená, ani do budoucna (rozhodnutí uživatele 2026-08-09 — zodpovědnost nese obsluha).
Je editovatelná i zpětně — list se tiskne ze živých dat, oprava se propíše. `created_at` zůstává
čistě auditní a needitovatelný.

## Jediná zmrazená hodnota: tachometr při příjmu (V70)

`"order".orders.mileage_km_at_intake` je **snímek**, ne odkaz na `vehicle.current_mileage_km`.
Papír, který zákazník podepsal, musí být reprodukovatelný i po letech a po dalších odečtech —
kdyby doklad čítal „aktuální" stav vozu, tiskl by po roce jiné číslo, než na kterém je podpis.

Zároveň se při **zakládání** zakázky zapíše odečet do `vehicle.mileage_history`
(`OrderServiceImpl.recordIntakeMileage`, zdroj `SERVICE`, poznámka s číslem zakázky), takže se km
z příjmu propíšou i do odometru vozu. Dvě čísla o téže věci jsou tu vědomě: **jedno patří dokladu,
druhé odometru vozidla.** Zápis obojího je v jedné transakci (`create` je `@Transactional`).

**Dodatečné dopsání km přes editaci zakázky odečet nezakládá.** Jinak by každá další editace sypala
do historie duplicity; odometr se plní na kartě vozidla, kde na to je „Přidat čtení".

**Údaj je nepovinný** (CHECK při NULL prochází) — vůz může přijet odtažený nebo s nefunkčním
tachometrem a doklad musí vzniknout i tak.

## Kde to je

- `OrderDocumentService` / `OrderDocumentServiceImpl` — skládá kontext (zakázka, zákazník + adresa,
  vozidlo, profil firmy, logo) a renderuje `templates/pdf/order-protocol.html` sdíleným
  `PdfRenderer`em; vzor je `CashReceiptDocumentServiceImpl`.
**Zakázkový list smazané zakázky neplatí** (rozhodnutí uživatele 2026-08-07). Mazání znamená
„záznam neměl vzniknout", takže s ním padá i podepsaný papír — zakázka se nekonala. Potřebuje-li
zákazník doklad, vytiskne se nový z té zakázky, která opravdu existuje. Má to jeden důsledek, který
je dobré znát: číslo zakázky se od V56 skládá jako `MAX + 1` za rok, takže se smazáním nejvyšší
zakázky **uvolní a přidělí další nové**. Podepsaný list se tedy může jmenovat stejně jako pozdější,
úplně jiná zakázka.

- `OrderController.protocol` — `GET /orders/{id}/protocol`, `inline; filename="zakazkovy-list-{číslo}.pdf"`.
- `templates/pdf/order-protocol.html` — nad sdílenými `pdf/invoice-styles`, aby tisk vypadal jako
  ostatní doklady ze systému.
- Frontend: tlačítko „Zakázkový list" na detailu zakázky (`window.open`, vzor faktur — detail
  zakázky měl do teď jedinou akci Editovat) a pole „Stav tachometru při příjmu" v `OrderForm`.

**Vedlejší oprava:** `OrderConverter` nenaplňoval `vehicleId` (audit 01/J-5), takže odkaz na vozidlo
z detailu zakázky nefungoval a dokumentová služba by si vůz nedohledala. Doplněno do detailní
i seznamové odpovědi.

## Co se vědomě nestaví

- **Strukturovaný soupis poškození** (checklist karoserie, výbava, palivo) — rozhodnutí uživatele
  2026-07-31: jednoduchý list pokryje potřebu, soupis by byl samostatná etapa s vlastní tabulkou.
- **Předávací protokol při vydání vozu** jako druhý dokument — dnešní list má podpisové řádky pro
  obě strany a slouží obojímu.
- **Evidence souhlasu s navýšením ceny** (07/P-8) — na dokladu je věta o povinnosti oznámit, ale
  samotný souhlas se nikam neukládá.

## Ověření

`OrderProtocolDocumentTest`: km z příjmu se uloží na zakázku **a** založí odečet vozidla se zdrojem
SERVICE a číslem zakázky v poznámce; bez km žádný odečet nevzniká; dopsání km editací odečet
nezakládá; PDF je validní neprázdný dokument (`%PDF-`) i u zakázky bez tachometru a bez odhadu ceny.
