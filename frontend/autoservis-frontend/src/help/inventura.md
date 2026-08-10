# Inventura

Inventura je fyzický přepočet skladu: projdete regály, zapíšete, kolik kusů tam **opravdu je**, a systém sám dorovná evidenci. Rozdíly se zapíšou jako skladové pohyby, takže je pak vidět, kdy a proč se stav změnil.

## Jak inventura probíhá

1. Otevřete **Sklad → Inventury** a klikněte na **Zahájit inventuru**. Systém připraví soupis všech aktivních dílů.
2. Procházejte regály a do sloupce **Napočítáno** zapisujte skutečný počet kusů.
3. Průběžně klikejte na **Uložit soupis** — počítat můžete i na několikrát, klidně několik dní.
4. Až budete hotoví, dejte **Uzavřít inventuru**. Teprve teď se sklad srovná.

Otevřená může být vždy jen jedna inventura.

## Prázdné pole neznamená nulu

Řádek, u kterého **nic nevyplníte**, se při uzavření přeskočí — u toho dílu se nic nezmění. Kdyby prázdné pole znamenalo nulu, nedokončená inventura by vynulovala celý sklad.

Když jste díl počítali a opravdu tam žádný není, napište **0** — to už je manko a systém ho odečte.

## Rozdíl a co s ním systém udělá

- **Manko** (napočítáno je méně) — chybějící kusy se odečtou od **nejstarší dodávky**. Pokud by manko bylo větší, než kolik ještě ze všech dodávek zbývá, uzavření se zastaví a upozorní vás — zkontrolujte, jestli jste počítali správně.
- **Přebytek** (napočítáno je více) — nalezené kusy se naskladní jako nová dodávka „Inventura". Proto u nich musíte zadat **nákupní cenu**; předvyplní se poslední známá cena dílu a můžete ji přepsat. Bez ceny by sklad vykazoval nesprávnou hodnotu.

## Během počítání se může vydávat

Pokud během inventury někdo vydá díl na zakázku, nevadí — rozdíl se počítá proti **aktuálnímu** stavu v okamžiku uzavření, takže výdej se nepřepíše.

Zjištěné rozdíly zůstanou na uzavřené inventuře **viditelné natrvalo** — i když je uzavření hned srovná na skladě. Uzavřená inventura je doklad o tom, co se našlo, takže se k ní můžete kdykoli vrátit a zpětně dohledat, kde manko nebo přebytek vznikl.

## Časté situace

- **Spletli jste se v zápisu** — dokud je inventura otevřená, hodnotu prostě přepište a znovu uložte.
- **Chcete inventuru zahodit** — použijte **Zrušit inventuru**; sklad zůstane beze změny.
- **Uzavřenou inventuru nelze vzít zpět.** Špatně zapsaný kus opravte korekcí na kartě dílu (viz článek „Opravy stavu skladu").
