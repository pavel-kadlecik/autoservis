# Funkce: Vozidlo bez VIN (stroje) a výrobní číslo

> Funkční dokumentace — co funkce dělá a proč.
> Technické detaily vrstev: [api.md](../api.md) · [backend.md](../backend.md) · [databaze.md](../databaze.md) §3
> Uživatelská nápověda: v aplikaci **Nápověda → Vozidla** (`frontend/…/src/help/vozidla.md`)
> Navazuje na: [palivo-nepovinne.md](palivo-nepovinne.md) (V86 — stejný směr: evidence ne-automobilové techniky)

## Co se změnilo

Při zakládání i editaci vozidla jde **VIN** nechat prázdný. Do V90 byl povinný. Nově je k dispozici
i pole **Výrobní číslo** pro techniku, která se identifikuje výrobním/sériovým číslem místo VIN.

## Proč

Servis opravuje i techniku, která VIN nemá — **zahradní traktory, sekačky**, stará vozidla s číslem
karoserie. Povinný VIN takovou techniku z evidence vylučoval, případně nutil obsluhu vymýšlet falešné
hodnoty (zápis nepravdivého údaje je horší než prázdné pole — stejná úvaha jako u nepovinného paliva,
V86).

Řešení kopíruje **oborový standard** (rešerše 2026-08-08: Shopmonkey, Mitchell 1, Shop-Ware, Fleetio,
Lightspeed DMS, RepairDesk): VIN je ve všech těchto systémech identifikátor pro externí služby
(dekodéry, katalogy dílů, u nás **registr vozidel**), ne podmínka existence záznamu. Bez VIN aplikace
funguje plně, jen nejde použít lookup.

**Proč samostatné pole pro výrobní číslo:** výrobní číslo sekačky má jiný formát i sémantiku než VIN.
Nacpat ho do sloupce `vin` by rozbilo formátový CHECK (17 znaků) a lhalo o tom, co číslo znamená.
Systémy pro malou mechanizaci (RepairDesk, Lightspeed) vedou „VIN nebo serial number" jako dvě pole.

**Proč se nevynucuje „aspoň jeden identifikátor":** zahradní traktor nemusí mít VIN, SPZ ani čitelné
výrobní číslo. Identifikaci pak nese značka + model + zákazník, což pro servisní evidenci stačí.

## Jak to funguje

| Vrstva | Co se změnilo |
|---|---|
| DB | `vin` DROP NOT NULL, +`machine_serial_number VARCHAR(50)` (V90); UNIQUE i formátový CHECK beze změny — na NULL se neaplikují |
| DTO | bez `@NotBlank` na `vin`; `@Pattern` tvaru `^$|^…{17}$` (prázdný řetězec z FE musí projít validací, na NULL ho převede až konvertor — vzor V80/V81) |
| Service | kontrola duplicity VIN jen když je VIN vyplněný; **registry-refresh u vozidla bez VIN vrací `VEHICLE_HAS_NO_VIN`** místo NPE |
| FE formulář | VIN nepovinný (vyplněný se dál validuje přísně), nové pole Výrobní číslo |
| FE zobrazení | tabulky a detail ukazují u strojů výrobní číslo místo VIN, jinak `—`; tlačítko „Aktualizovat z registru" je u vozidla bez VIN zakázané s vysvětlením |
| Autocomplete | třetí řádek nabídky: `COALESCE(vin, machine_serial_number)`; hledat jde i podle výrobního čísla |
| Zakázkový list (PDF) | popisek pole se přepíná `VIN` / `Výr. číslo` podle toho, co vozidlo má |
| Faktura (PDF) | blok „Vozidlo:" se řídí snapshotem **značky** (vyplněná právě když zakázka vozidlo měla) — dřívější guard „VIN nebo SPZ" stroj bez obojího schoval celý včetně značky a modelu; řádky VIN/SPZ se bez hodnot nevytisknou |

## Co se nezměnilo

- **Existující vozidla** — migrace nic nemaže ani nepřepisuje; všechna vozidla z produkce mají VIN
  vyplněný a zůstávají beze změny.
- **Vyplněný VIN** dál podléhá formátu (17 znaků, bez I/O/Q) i unikátnosti.
- **Snapshot na faktuře** (`vehicle_vin_snapshot`, V50) — sloupec byl nullable už od začátku.

## Vědomě odloženo

- **Typ vozidla** (auto / motocykl / přívěs / stroj) — průmysl ho používá jako nositele chování
  (jednotky, pole formuláře). Přidá se, až vznikne konkrétní funkce, která ho potřebuje.
- **Motohodiny** — stroje evidují motohodiny místo km; pole `current_mileage_km` je nepovinné, takže
  nic neblokuje. Případné přepínání jednotek by stálo na typu vozidla (bod výše).
