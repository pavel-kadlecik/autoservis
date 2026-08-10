# Vozidla

Každé vozidlo patří jednomu zákazníkovi a jsou na něj vázané zakázky. Technické údaje i STK se dají načíst z registru.

## Jak zaevidovat vozidlo

1. **Vozidla → Nové vozidlo** (nebo z detailu zákazníka).
2. Přiřaďte **zákazníka**.
3. Nejrychlejší je **načtení z registru**: zadejte **VIN** (nebo číslo TP/ORV) a nechte načíst — předvyplní se značka, model, palivo, objem, výkon, barva, datum registrace, **kód motoru** i **STK**. Podrobnosti: článek „STK a registr vozidel".
4. Doplňte, co registr nemá — hlavně **SPZ**.
5. Uložte.

Vyplněný VIN musí mít 17 znaků a být jedinečný — dvě vozidla se stejným VIN aplikace nedovolí.

## Technika bez VIN (zahradní traktory, sekačky…)

**VIN vyplňovat nemusíte.** Stroje, které VIN nemají — zahradní traktor, sekačka, stará technika
s číslem karoserie — zaevidujete jen se **značkou a modelem**; identifikaci doplňte polem
**Výrobní číslo** (najdete ho vedle barvy). Výrobní číslo se pak ukazuje v přehledu vozidel,
v našeptávači i na zakázkovém listu místo VIN.

U vozidla bez VIN nefunguje **načtení z registru vozidel** (registr hledá výhradně podle VIN) —
tlačítko na detailu je proto zašedlé. SPZ, palivo i všechna technická pole zůstávají volitelná
jako u každého jiného vozidla.

**Palivo ani převodovku vyplňovat nemusíte** — nechte volbu *— nevyplněno —*. Je to kvůli technice bez
motoru, typicky **přívěsným vozíkům**: ty se vozí na kontrolu brzd a osvětlení jako auto, ale žádné
palivo nemají. Volbu *Ostatní* na ně nepoužívejte — ta znamená „jiné palivo, než je v nabídce", a v
přehledu by pak nešlo poznat vozík od auta s neobvyklým pohonem.

## Detail vozidla

Vidíte technické údaje, **kód motoru**, **kola (pneu/ráfky)** i **STK** — kola a STK se plní z registru a ručně se needitují. Je tu i **historie nájezdu**. Odsud vozidlo **editujete** i deaktivujete.

## Servisní historie

Karta **Servisní historie** ukazuje **10 nejnovějších zakázek** tohoto vozu — číslo, stav, datum přijetí, popis práce, cenu a stav **faktury**. Přes řádkové menu **Detail** se dostanete na celou zakázku. Má-li vůz víc než 10 zakázek, vede z hlavičky karty odkaz **Zobrazit všech N** na seznam zakázek zúžený na tento vůz (zúžení tam poznáte podle popisku nad filtry a jedním klikem ho zrušíte).

Je to odpověď na nejčastější otázku u pultu: *co jsme na tom autě dělali posledně a je to zaplacené.*

## STK

Platnost STK bere aplikace z registru a hlídá ji na **Přehledu** (končící/propadlá do 30 dnů). Údaj je jen tak čerstvý jako poslední načtení — u staršího vozidla si ho na detailu **aktualizujte**. Viz článek „STK a registr vozidel".

## Deaktivace

Prodané nebo sešrotované vozidlo **deaktivujte** — schová se z výpisu, historie i doklady zůstanou. **Vozidlo s otevřenou zakázkou deaktivovat nejde** — nejdřív zakázky dokončete nebo zrušte. Deaktivované najdete zrušením zaškrtnutí **Jen aktivní** a jde zase **aktivovat**.
