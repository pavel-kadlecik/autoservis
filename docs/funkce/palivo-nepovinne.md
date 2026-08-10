# Funkce: Palivo u vozidla je nepovinné

> Funkční dokumentace — co funkce dělá a proč.
> Technické detaily vrstev: [api.md](../api.md) · [backend.md](../backend.md) · [databaze.md](../databaze.md) §4
> Uživatelská nápověda: v aplikaci **Nápověda → Vozidla** (`frontend/…/src/help/vozidla.md`)

## Co se změnilo

Při zakládání i editaci vozidla jde **Palivo** nechat prázdné (volba *— nevyplněno —*). Do V86 bylo
pole povinné. Převodovka nepovinná byla už dřív, jen ji kvůli chybě popsané níž fakticky nešlo
nevyplnit.

## Proč

Do evidence patří i technika **bez vlastního pohonu** — typicky **přívěsný vozík**. Vozí se do servisu
na kontrolu brzd, náprav a osvětlení stejně jako auto, ale žádné palivo nemá.

Povinné pole nutilo obsluhu vybrat hodnotu, která není pravdivá. Nejblíž byla `Ostatní` (`OTHER`), jenže
ta znamená **„jiné palivo, než nabízíme"** — ne „žádné". V přehledu vozidel pak nešlo poznat, které auto
má exotický pohon a které je vozík. Zapsat do evidence nepotvrzený údaj je horší než nechat pole prázdné
(stejná úvaha jako u [objednávky bez zákazníka](planovaci-kalendar.md), V85).

**Proč ne nová hodnota `NONE` v číselníku:** „nevíme, čím jezdí" a „nemá motor" nejsou totéž, ale první
z nich už NULL vyjadřuje. Dvě prázdné hodnoty vedle sebe by musel rozlišovat každý dotaz i každý report
a rozdíl mezi nimi by stejně nikdo spolehlivě nevyplňoval.

## Jak to funguje

| Vrstva | Co se změnilo |
|---|---|
| DB | `vehicle.vehicles.fuel_type` DROP NOT NULL (V86) |
| DTO | `VehicleDto.CreateRequest` i `UpdateRequest` bez `@NotNull` na `fuelType` |
| Jackson | `JacksonConfig` — globální pravidlo `EmptyString → AsNull` pro **všechny** enumy |
| FE | první volba selectu je *— nevyplněno —* s hodnotou `""` (dřív `NONE`) |

Prázdné palivo se v přehledu i na detailu vozidla ukazuje jako `—`, stejně jako každý jiný nevyplněný
údaj.

## Past, na kterou se přišlo cestou

Odebrat `@NotNull` nestačilo — založení vozidla padalo na:

```
HttpMessageNotReadableException: JSON parse error: Cannot coerce empty String ("")
to `cz.palo.autoservis.model.enums.FuelType` value
```

Formulář posílá nevyplněný `<select>` jako **prázdný řetězec**, nikdy jako JSON `null`. Jackson převede
`""` na `null` sám u čísel a dat, ale **u enumů to má ve výchozím stavu zakázané**. Request se tak
rozbije už při deserializaci — tedy dřív, než se ke slovu dostane Bean Validation, a odebrání `@NotNull`
s tím proto nic neudělá.

Je to stejná třída chyby jako **prázdný řetězec místo NULL** u textových polí (V80/V81), jen o vrstvu
výš: u textů ji řeší `blankToNull` v konvertoru, sem ale konvertor vůbec nedosáhne. Pravidlo je proto
zavedené globálně v `JacksonConfig`, ne anotací u jednotlivého pole — týká se každého volitelného enumu
v API. Povinné enumy to neoslabuje: jejich `@NotNull` výsledný `null` odmítne řádným 400 s čitelnou
hláškou místo chyby o parsování.

Druhá past byla na frontendu: prázdný řádek v číselníku paliv i převodovek měl hodnotu `"NONE"` — což
není hodnota ani v Javě, ani v DB. Kdyby ho obsluha vybrala, backend by spadl podruhé, jen na jiné
hlášce. Prázdná volba má nově hodnotu `""`.

## Co se nezměnilo

- **Existující vozidla** — migrace nic nemaže ani nepřepisuje, všechna mají palivo vyplněné. Zpětně se
  nedohledává, které z nich je vozík.
- **Předvyplnění z registru vozidel** — přijde-li palivo z registru, vyplní se dál automaticky.
- **Ostatní povinná pole** vozidla (zákazník, značka, model) zůstávají povinná. VIN byl povinný
  do V90 — viz [vozidla-bez-vin.md](vozidla-bez-vin.md).
