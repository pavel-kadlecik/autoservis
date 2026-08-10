# Příjem zboží na sklad

Zboží se na sklad dostává přes **příjemky**. Příjemka vzniká nahráním PDF dokladu od dodavatele (fakturu nebo dodací list přečte umělá inteligence) a **na sklad se nic nedostane, dokud příjemku nezkontrolujete a nepotvrdíte**.

## Jak naskladnit doklad

1. Otevřete **Sklad → Příjemky** a klikněte na **Import dokladu (PDF)**.
2. Vyberte **formát dokladu**:
   - **PDF nebo fotka** — běžný doklad; údaje z něj přečte umělá inteligence. Doklad můžete i **vyfotit mobilem** nebo naskenovat (JPG, PNG, HEIC) — čitelná fotka stačí,
   - **ISDOC** — elektronická faktura (soubor `.isdoc`). Pokud vám ji dodavatel posílá, použijte ji: údaje jsou přesné a nemusíte je kontrolovat po AI. Typ dokladu se u ISDOC nevybírá, je uvnitř souboru.
3. U PDF vyberte **typ dokladu**:
   - **Faktura** — daňový doklad s rozpisem DPH,
   - **Dodací list** — bez rozpisu DPH (sazba 21 % se doplní automaticky a jde upravit).
4. Nahrajte soubor a klikněte na **Nahrát a zpracovat**. Údaje se načtou automaticky.
5. Pokračujte tlačítkem **Zkontrolovat** na kontrolní obrazovku.

Přijatý **dobropis** zatím naskladnit nelze — pokud vám přijde jako ISDOC, systém ho odmítne. Vrácení dílu dodavateli se eviduje na kartě dílu (viz článek „Opravy stavu skladu").

## Kontrolní obrazovka

Vlevo vidíte přečtené údaje, vpravo originál dokladu pro porovnání. Ikonky u polí říkají, odkud hodnota pochází:

- **zelená fajfka** — hodnota prošla křížovou kontrolou (např. množství × cena sedí na součet),
- **bez ikonky** — hodnota opsaná z dokladu,
- **žlutá ikonka** — hodnota dopočtená nebo doplněná výchozím nastavením — zkontrolujte ji,
- **šedá čárka** — údaj na dokladu není (nevadí, pokud pole není povinné),
- **modrá tužka** — hodnotu jste upravili vy.

**Zelená fajfka není u každého dokladu.** Křížová kontrola potvrdí hodnotu jen tehdy, když ji má
proti čemu porovnat. Na běžné faktuře to jde i u dopočtených čísel — sedí-li dopočet na
rekapitulaci DPH nebo na celkovou částku vytištěnou v dokladu, fajfka přijde. Ale u ručně psaného
dodacího listu, kde je jen cena s DPH a žádný souhrn, by kontrola porovnávala vlastní výpočet sám
se sebou; to nedokazuje nic, takže pole zůstanou žlutá. **Není to chyba** — je to upozornění, že
tenhle doklad za vás nikdo nezkontroloval a čísla musíte porovnat vy.

**Červený rámeček** políčka znamená, že je **povinné a prázdné** (nebo má neplatnou hodnotu) — bez jeho doplnění nejde příjemku potvrdit. Prázdné nepovinné pole (třeba splatnost u dodacího listu) červené není.

Nahoře se zobrazují **neprošlé kontroly** (např. součet řádků nesedí na celkovou částku) — projděte je, opravte hodnoty a uložte tlačítkem **Uložit koncept**. Kontroly se přepočítají.

Řádek, který na doklad nepatří, můžete odebrat košem. Řádek, který **není skladový díl** (práce, spotřební materiál), vyřaďte z naskladnění tlačítkem **Vyřadit z naskladnění** — zůstane na dokladu, ale nenaskladní se (a jde vrátit zpět). Řádky typu „Dodací list č. …" jsou jen informativní a nenaskladňují se.

## Potvrzení a zamítnutí

- **Potvrdit a naskladnit** — teprve teď se zboží přidá na sklad: založí se dodavatel (pokud je nový), skladové karty a šarže s pohyby. Akce je nevratná.
- **Zamítnout** — doklad se odloží (např. špatně přečtený), na sklad se nic nedostane a stejný doklad jde nahrát znovu.

## Storno omylem potvrzené příjemky

Pokud jste doklad potvrdili omylem, otevřete ho v **Sklad → Příjemky** a použijte **Stornovat**. Zboží se odepíše ze skladu, doklad dostane stav „Stornováno" a jeho číslo půjde nahrát znovu. Původní pohyby zůstanou v historii — sklad nic nezatajuje, jen přibude opravný pohyb.

Storno je možné jen **dokud se z příjemky nic nevydalo**. Jakmile díl odešel na zakázku, systém storno odmítne a napíše, kterých šarží se to týká — nesrovnalost pak řešte korekcí na kartě dílu (viz článek „Opravy stavu skladu").

Důvod storna je povinný.

## Časté situace

- **„Doklad … už je naimportovaný"** — stejné číslo dokladu od stejného dodavatele už v systému je. Zamítnutý doklad je možné nahrát znovu, potvrzený ne. Platí to nově i pro doklady **bez čitelného IČO**, kde se shoda hledá podle jména dodavatele a čísla dokladu — dřív šel takový doklad naskladnit opakovaně a pokaždé vznikl další dodavatel.
- **„Volbu ‚pouze provázat' zatím nelze použít"** — faktura odkazuje na dodací list, který už máte naskladněný, ale aplikace neumí poznat, **které řádky faktury** ten dodací list kryje. Doklad proto naskladněte bez provázání, nebo z faktury odeberte řádky, které už na skladě jsou. (Dokud tohle hlášení vidíte, nemůže se stát, že by se zboží naskladnilo dvakrát.)
- **Dodavatel „založí se při potvrzení"** — dodavatel zatím není v databázi; vytvoří se automaticky při potvrzení příjemky.
- **„Dodavatel s IČO … je deaktivovaný"** — s touto firmou jste kdysi ukončili spolupráci a její kartu vyřadili, teď od ní ale přišel doklad. Otevřete **Sklad → Dodavatelé**, dodavatele **aktivujte** a příjemku potvrďte znovu. Aplikace ho záměrně neoživí sama — vyřazení dodavatele bylo vaše rozhodnutí a nemá ho přebít import.
- **Vyřazený díl na dokladu** — pokud katalogové číslo patří skladové kartě, kterou jste dřív vyřadili, karta se při potvrzení **sama vrátí mezi aktivní**. Zboží fyzicky přišlo, takže musí být vidět v ocenění skladu i v inventuře; nová duplicitní karta nevzniká.
- **Ručně psaný dodací list** — vyfoťte ho čitelně. Když má u řádků jen cenu *s DPH*, cenu bez DPH i základ dopočítá systém sám (uvidíte je žlutě „dopočteno"). Díly bez katalogového čísla napárujte ve sloupci **Skladová karta** na existující kartu — číslo pak není potřeba.
- **Do zakázky** lze položky importovat jen z **potvrzených** příjemek (na detailu zakázky přes „Importovat položky").
