# Příjmový pokladní doklad

Když zákazník zaplatí fakturu **v hotovosti**, vystavíte mu příjmový pokladní
doklad (PPD) — potvrzení, že jste hotovost přijali do pokladny.

## Jak doklad vytvořit

1. Otevřete detail faktury.
2. Klikněte na **Vystavit pokladní doklad**. Tlačítko je u faktury, která už je
   **vystavená** nebo **zaplacená** (u konceptu se nezobrazuje — zatím není co potvrzovat).
3. V dialogu zkontrolujte **číslo dokladu** (předvyplní se podle nastavení — z vlastní
   číselné řady, nebo **číslem hrazené faktury**; vždy ho můžete přepsat) a **datum
   vystavení**. Doklad se vytvoří a rovnou se otevře jako **PDF** k tisku.

Datum vystavení není ničím omezené — doklad si můžete připravit i **dopředu**, třeba den
před příchodem zákazníka. Že hotovost v uvedený den opravdu přišla, ručíte vy.

Zdroj čísla (podle masky / podle čísla faktury / ručně) nastavíte ve **Fakturačních
údajích** v sekci *Číslování pokladních dokladů*.

Vystavené doklady pak najdete na detailu faktury v kartě **Pokladní doklady** —
odtud si je můžete kdykoli znovu vytisknout. Pokud už platný doklad existuje,
tlačítko nahoře se změní na **Pokladní doklad** a jen ho otevře.

Doklad si program vyplní sám podle faktury:

- **částku** — tu, která je na faktuře uvedená jako **Celkem k úhradě**
  (u hotovosti zaokrouhlená na celé koruny, protože haléře fyzicky předat nejde;
  rozdíl je na faktuře i na dokladu uveden jako *Zaokrouhlení*) — číslem i **slovy**,
- **účel platby** (např. „Úhrada faktury č. 202607001, VS 202607001"),
- **firmu** (příjemce hotovosti) a **od koho** bylo přijato (zákazník),
- **rozpis DPH**.

## Dobré vědět

- Každý doklad má vlastní **pořadové číslo** (výchozí řada `PPD…`), které se už nemění.
- K jedné faktuře může být **jen jeden platný doklad**. Když ho zkusíte vystavit
  podruhé, program to odmítne a napíše, který doklad už existuje — chrání to
  pokladnu před tím, aby vykazovala hotovost dvakrát.
- Obsah dokladu se po vytvoření **nemění**.

## Když jste doklad vystavili omylem

Máte dvě možnosti — vyberte podle toho, jestli už doklad někdo viděl:

**Smazat** — doklad zmizí úplně a jeho **číslo se uvolní**: další doklad ho dostane
znovu, řada zůstane bez děr. Hodí se na omyl, který objevíte hned. Smažete-li
doklad uprostřed řady, vznikne díra — zavřete ji tak, že u příštího dokladu
přepíšete navržené číslo na to chybějící (dialog vás na díru upozorní, máte-li
ve Fakturačních údajích zapnuté *Hlídání mezer*).

**Stornovat** — doklad zůstane v seznamu i v číselné řadě a jde vytisknout; na PDF
má červený pruh **STORNOVÁNO** s datem a důvodem. Důvod je povinný — za rok se
bude hodit vědět, proč doklad neplatí. Storno použijte, když už doklad dostal
zákazník nebo účetní: záznam zůstane doložitelný.

Obojí najdete na detailu faktury v kartě **Pokladní doklady**. Po smazání
i po stornu můžete k faktuře vystavit doklad nový; smazání navíc uvolní fakturu
i pro **návrat do konceptu**.
