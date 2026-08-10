# Zakázky

Zakázka je záznam o opravě jednoho vozidla — co se dělá, kdo a za kolik, a v jakém je stavu. Z hotové zakázky se vystavuje faktura.

## Jak založit zakázku

1. **Zakázky → Nová zakázka**.
2. Vyberte **zákazníka** a jeho **vozidlo** (nabízejí se jen vozidla toho zákazníka).
3. Napište **popis** (co se má udělat), případně odhad ceny a termín dokončení.
4. Uložte. Zakázka dostane **číslo** (ZAK-{rok}-…).

## Položky — práce a díly

Na detailu zakázky přidáváte položky:

- **Práce** — úkon mechanika; účtuje se buď **po hodinách** (`hod`), nebo **po kusech** (`ks`, paušál za úkon — výchozí volba). Lze přiřadit konkrétního **zaměstnance**; jeho hodinová sazba se předvyplní do nákupní ceny, ale **jen u hodin** — u paušálu ji zadáte sami, protože sazba za hodinu není cena za úkon,
- **Materiál / díl** — buď ručně, nebo tlačítkem **Importovat položky** z **potvrzené** skladové příjemky (díl se tím na zakázce **rezervuje** — viz níž). Okno výběru ukazuje u každé šarže dvě čísla: **Zbývá** (kolik kusů leží v regále) a **Dostupné** (kolik si z nich ještě můžete slíbit — zbytek už drží jiné otevřené zakázky). Naimportovat lze nejvýš to druhé. Když tlačítko **Import položek** nejde stisknout, okno vedle něj napíše proč — jestli ještě nejsou načtené položky, není nic zaškrtnuté, nebo je někde vyplněné vyšší množství, než je dostupné. Šarže, kterou má celou rezervovanou někdo jiný, zůstane v seznamu vidět, ale nejde vybrat — poznáte tak, že díl na skladě je, jen je slíbený jinam, a můžete zakázky přerovnat místo objednávání,
- **Ostatní služby**.

U každé položky vidíte **cenu za kus s DPH** i **celkem s DPH** — tedy to, co za ni zákazník zaplatí. Ostatní ceny (nákup, prodejní cena za kus) jsou bez DPH, jak je u vyúčtování zvykem.

Pořadí položek přeskládáte tažením. Dole je **souhrn** (práce, materiál, celkem — bez DPH i s DPH) včetně **nákladu a marže** po kategoriích. Marže = prodejní cena − nákupní cena (obojí bez DPH); u ruční položky bez vyplněné nákupní ceny se náklad počítá jako 0, takže marže vyjde 100 % — chcete-li marži reálnou, nákupní cenu vyplňte.

## Materiál ze skladu — rezervace a výdej

Když na zakázku přidáte díl ze skladu, **fyzicky se nikam nepřesune** — leží dál v regálu, jen je pro tuhle zakázku **rezervovaný**. Ve skladu proto uvidíte tři sloupce vedle sebe: **Skladem** (co je v regálu, proti tomu se dělá inventura), **Rezervováno** (kolik z toho už drží otevřené zakázky) a **Dostupné** (co lze ještě naplánovat na další zakázku). Na kartě dílu je vidět, které zakázky ho drží.

Ze skladu díl odejde ve dvou okamžicích — co nastane dřív:

- tlačítkem **Vydat ze skladu** na zakázce, když si ho mechanik bere,
- **automaticky při dokončení zakázky**, pokud jste tlačítko nepoužili.

Když rezervovaný díl mezitím ze skladu zmizel (inventura, odpis, vratka dodavateli), aplikace zakázku **nedokončí** a napíše, co chybí.

**Množství jde upravit i po přidání.** Dokud je díl jen rezervovaný, změní se pouze rezervace a sklad se nehne. U už vydaného dílu se rozdíl srovná sám: snížíte-li množství, přebytek se vrátí na sklad; zvýšíte-li ho, chybějící kusy se dovydají. Jednotku, DPH ani nákupní cenu měnit nelze — ty se řídí příjemkou.

Když smažete položku, která pocházela ze skladu, vrátí se zpět jen to, co už bylo **vydáno**. U pouhé rezervace se sklad nemění, protože z něj nic neodešlo.

Pod názvem dílu najdete i **dodavatele a číslo jeho faktury** — proklikem se dostanete na příjemku, na které díl přišel. Hodí se, když díl po čase odejde v záruce a potřebujete ho u dodavatele reklamovat. **Poznámka**, kterou u položky napíšete, je vidět taky rovnou v seznamu.

**V seznamu položek to poznáte ve sloupci „Sklad"**: *Rezervováno* (díl leží v regálu a čeká), *Vydáno* (díl už ze skladu odešel) a pomlčka u materiálu, který jste zapsali ručně a se skladem nesouvisí. Pod názvem dílu je navíc jeho **katalogové číslo** — dodavatelské názvy se opakují, takže dvě položky se často jmenují stejně a liší se jen cenou; podle čísla ověříte, že jste naimportovali ten správný. Na faktuře číslo není.

## Zakázkový list (doklad o převzetí vozu)

Na detailu zakázky je tlačítko **Zakázkový list** — otevře PDF k vytištění a podpisu při převzetí vozu. Obsahuje servis a zákazníka, vozidlo (SPZ, VIN, rok), **stav tachometru při příjmu**, požadovanou práci, odhad ceny a dva podpisové řádky (servis a zákazník).

Na dokladu je i věta, že práce nad uvedený rozsah servis předem oznámí a bez souhlasu zákazníka ji neprovede. Zakázkový list **není daňový doklad** — konečnou cenu vyúčtuje faktura po dokončení.

**Datum přijetí vozidla** se na zakázkovém listu tiskne z pole „Datum přijetí vozidla" na formuláři zakázky. Předvyplní se dnešek, ale můžete zapsat kterékoli datum — třeba včerejšek, když vůz přijel večer a zakázku zapisujete až ráno. Datum jde kdykoli opravit v editaci zakázky a nový tisk listu už ponese opravenou hodnotu.

**Stav tachometru** vyplňte při zakládání zakázky (pole „Stav tachometru při příjmu"). Zapíše se dvakrát: na zakázku, kde už se nemění, protože je na podepsaném dokladu, a do **historie tachometru vozidla**, takže se rovnou aktualizuje i stav vozu. Když ho dopíšete až později v editaci zakázky, do historie vozidla se už nepřidá — tam ho zadáte na detailu vozidla tlačítkem **Přidat čtení**. Údaj je nepovinný (vůz může přijet odtažený).

## Stav zakázky

Stav ukazuje, kde zakázka je: **Přijata → Diagnostika → Čekání na díly → Probíhá → K vyzvednutí**. Mezi těmito stavy se pohybujete volně, klidně i dozadu — když díl přijde poškozený, vrátíte zakázku z **Probíhá** na **Čekání na díly**.

Z kteréhokoli z nich zakázku uzavřete na **Dokončena**, nebo **Zrušena**.

**Stav změníte na dvě kliknutí** — v seznamu zakázek přes nabídku na konci řádku. Do editace kvůli tomu chodit nemusíte a seznam si podrží filtr i stránku, na které jste byli. V editaci stav měníte přímo polem **Stav** ve formuláři.

**Kde co najdete:**

| Akce | Seznam (nabídka na konci řádku) | Detail a editace (tlačítka v hlavičce) |
|---|---|---|
| Otevřít detail / editaci | ano | ano |
| Změnit stav | ano | — *(v editaci polem ve formuláři)* |
| Zakázkový list | — | ano |
| Vydat ze skladu | — | ano |
| Vytvořit fakturu | — | ano |
| Smazat | ano | ano |

Zakázkový list, výdej a fakturace se dělají nad otevřenou zakázkou, kde vidíte položky i cenu — ze seznamu by se spouštěly naslepo. **Detail a editace nabízejí totéž**, takže se mezi nimi kvůli akci přepínat nemusíte.

**Dokončenou zakázku lze vrátit do práce.** Když se auto vrátí nebo jste na „Dokončena" klikli omylem, přepnete ji zpět na kterýkoli pracovní stav, případně ji rovnou zrušíte. Datum dokončení se přitom vymaže a materiál, který už ze skladu odešel, se vrátí do rezervace — díl zůstává na autě, jen se při dalším dokončení neodepíše podruhé. **Nejde to jen u zakázky, která má fakturu:** nejdřív stornujte koncept, nebo k vystavené faktuře vystavte dobropis.

### Co se stane s materiálem při zrušení

Aplikace se zeptá a po potvrzení **vrátí na sklad všechen materiál, který ze zakázky odešel** — najednou, položky mazat nemusíte. Pouhé rezervace se prostě uvolní; z regálu z nich stejně nic neodešlo.

**Zůstal-li nějaký díl namontovaný na voze, založte na něj novou zakázku** — zákazník ho zaplatí. Zrušená zakázka má být prázdná: vyúčtuje se jen to, co se opravdu udělalo.

**Zrušená zakázka je konečná** — zpět do práce ji nepřepnete. Má-li se na voze znovu pracovat, založte novou zakázku. Popis, finální cenu i datum dokončení u uzavřené zakázky dopsat můžete.

### Zrušit, nebo smazat?

**Zrušit** znamená „zakázka byla skutečná, ale k opravě nedošlo" — třeba když zákazník viděl rozpočet a odmítl. Zůstane v evidenci, protože je to obchodní údaj.

**Smazat** je pro zakázku, která **nikdy neměla vzniknout** — překlep, špatné auto, založeno dvakrát. Zmizí úplně, i s položkami a se zápisem tachometru, který se při jejím založení přidal do historie vozidla. Vzít zpět to nejde.

**Materiál ani u jedné z těch akcí řešit nemusíte** — vrátí se na sklad sám. Pouhá rezervace se prostě uvolní.

**Objednávka v kalendáři**, ze které zakázka vznikla, se při smazání vrátí na **Naplánováno** a jde ji převést znovu — termín se zákazníkem přece domluvený byl.

#### Co kdy projde

| Stav zakázky | Zrušit | Smazat |
|---|---|---|
| prázdná / jen práce | ano | ano |
| jen rezervovaný materiál | ano | ano |
| vydaný materiál | ano — vrátí se sám | ano — vrátí se sám |
| vydaný a zase vrácený | ano | ano |
| koncept faktury | ano — okno nabídne storno konceptu | ano — okno nabídne storno konceptu |
| vystavená nebo zaplacená faktura | ne — nejdřív dobropis | **nikdy** |
| dobropisovaná faktura | ano | **nikdy** |

**Jediné, co obě akce brzdí, je faktura.** Sklad ne.

- **Koncept faktury** — nemusíte nikam chodit. Okno nabídne **„Stornovat koncept a zrušit"**, resp. **„Stornovat koncept a smazat"**, a udělá obojí naráz. Koncept nikam neodešel a číslo nemá, takže v číselné řadě nevznikne mezera.
- **Vystavená nebo zaplacená faktura** — vedle sebe by stála zrušená práce a platný daňový doklad na ni. Vystavenou fakturu **stornovat nelze**; vystavte k ní **opravný daňový doklad (dobropis)** a tím se zakázka pro zrušení uvolní. Okno vás na fakturu rovnou proklikne.
- **Smazat vyfakturovanou zakázku nepůjde nikdy**, ani po dobropisu — po zakázce zůstal doklad a účetní stopa se nemaže. Okno vám proto místo mazání nabídne **zrušení**, což je u fakturované zakázky ta správná cesta.

## Fakturace

Fakturu vytvoříte **ze zakázky — až když je ve stavu Dokončena**. Dřív to nejde: faktura má vyúčtovat hotovou práci, a dokončení je zároveň okamžik, kdy se ze skladu vydá materiál. Zakázku bez jediné položky proto ani dokončit nelze; když se na voze nakonec nic nedělalo, zrušte ji. Jakmile faktura vznikne, položky zakázky se **zamknou** — už nejdou měnit (faktura je jejich obraz k danému okamžiku).

Zakázka se zase odemkne (a jde fakturovat znovu) ve dvou případech:

- **stornujete koncept** faktury — ten nikam neodešel a nemá číslo,
- **vystavíte k faktuře opravný daňový doklad** (dobropis) — tak se opravuje už vystavená faktura, kterou stornovat nelze. Původní faktura zůstane v evidenci, ale zakázku přestane blokovat.

Ve výpisu zakázek sloupec **Faktura** ukazuje stav fakturace: „—" (nefakturováno), **Koncept**, **Vystavena**, **Zaplacena**.

## Hledání a filtry

- **Hledat** — číslo, zákazník, vozidlo (SPZ/VIN), popis. Pole je rovnou nad tabulkou.
- **Filtr** — tlačítko otevře okno se **zaškrtávacími poli stavů** (dá se jich zapnout víc najednou, třeba všechny rozpracované) a přepínačem **Jen po termínu**.

Co je zapnuté, ukazují **odznaky nad tabulkou**; křížkem u odznaku filtr po jednom vypnete, odkazem **Zrušit filtr** všechny naráz. Nastavení si prohlížeč pamatuje, takže po příštím přihlášení najdete seznam tak, jak jste ho nechali. Ve výchozím stavu jsou skryté **zrušené** zakázky — je to vidět na odznacích a stačí je zaškrtnout.

Seznam umí být **zúžený na jedno vozidlo nebo zákazníka** — tak se otevře, když kliknete na **Zobrazit všech N** v kartě servisní historie na detailu vozu nebo zákazníka. Nad filtry pak stojí popisek, na koho je seznam zúžený, a tlačítkem **Zrušit zúžení** se vrátíte ke všem zakázkám.
