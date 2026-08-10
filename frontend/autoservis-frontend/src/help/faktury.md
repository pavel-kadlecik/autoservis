# Faktury

Faktura vzniká **ze zakázky** a její obsah je obraz zakázky k okamžiku vytvoření. Prochází stavy **Koncept → Vystavena → Zaplacena** (nebo **Stornována**).

## Jak vystavit fakturu

1. Otevřete **zakázku** a spusťte vytvoření faktury.
2. Vyberte **fakturační adresu** zákazníka, **datum vystavení, splatnosti a zdanitelného plnění** a **způsob úhrady**. Objednal-li zákazník opravu písemnou objednávkou, opište do pole **Číslo objednávky** její číslo — vytiskne se na fakturu a jeho účtárna podle něj doklad spáruje. Formát je libovolný, pole můžete nechat prázdné.
3. Vznikne **koncept** — ještě to **není daňový doklad** (PDF nese vodoznak NÁVRH), jde upravovat a **nemá číslo**.
4. Dejte **Vystavit** — na detailu faktury nebo v řádkovém menu seznamu. V dialogu zkontrolujte **číslo faktury**: je předvyplněné podle masky číselné řady (nastavuje se ve **Fakturačních údajích**) a můžete ho libovolně změnit; musí jen zůstat unikátní a mít nejvýš 20 znaků. Při vypnutém automatickém číslování zadáváte číslo celé sami.
5. Zkontrolujte **datum vystavení** — předvyplní se datem z konceptu a tady ho můžete naposledy upravit. **S tímhle datem faktura odejde.**
6. Zkontrolujte **variabilní symbol** — předvyplní se číslicemi z čísla faktury (`163/26` → `16326`). Přepsat ho můžete a nechat prázdný taky (u hotovosti nemá co párovat).
7. Potvrďte — tím se faktura stává daňovým dokladem.

**Proč číslo až tady:** dokud koncept číslo nemá, nemůže ho ani „spotřebovat". Zrušíte-li koncept,
v číselné řadě po něm nezůstane mezera — číslo dostane až ten doklad, který skutečně odejde
zákazníkovi.

**Datum vystavení určujete vy.** Na faktuře bude přesně to datum, které v dialogu potvrdíte —
aplikace ho nepřepisuje. Změníte-li ho, **přenačte se i návrh čísla**, aby číslo pocházelo
ze stejného období jako datum (doklad k červenci dostane červencové číslo, i když ho vystavujete
v srpnu). Pozor na to u už podaného přiznání k DPH — za volbu data ručíte vy.

**Datum není ničím omezené** — zpětné (kvůli konceptům, které chvíli ležely) i budoucí.
U budoucího data myslete na to, že číslo dokladu pochází z období zvoleného data a řada se může
rozejít s pořadím vystavování — hlídáte to vy, aplikace nebrání.

Pokud by zvolené datum vystavení předběhlo **splatnost**, posune se splatnost o stejnou lhůtu,
jakou jste zadali (např. „14 dní"); splatnost, která je i tak v budoucnu, zůstane beze změny.
**Datum zdanitelného plnění** se nemění — to je den, kdy byla práce odvedena, ne kdy se tiskne papír.

## Stavy faktury

- **Koncept** — rozpracovaná; dokladem není a **nemá číslo**; jde upravit i **smazat**. Smazáním se zahodí i s položkami a v číselné řadě po něm nic nezůstane.
- **Vystavena** — platný doklad s číslem; obsah je **neměnný**. Zákazník ho ale ještě nedostal, takže fakturu lze smazat a vystavit znovu.
- **Předána** — potvrdili jste, že doklad má zákazník. Od téhle chvíle se opravuje jen dobropisem.
- **Zaplacena** — zahrnuje i předání: kdo zaplatil, doklad má. Označíte-li fakturu rovnou jako zaplacenou, předání se potvrdí samo a tlačítko *Předáno zákazníkovi* už nepotřebujete.

**Zadali jste špatné číslo faktury?** Dokud ji zákazník nedostal, je na detailu tlačítko **Vrátit do konceptu** — faktura se vrátí mezi rozpracované, číslo i variabilní symbol se uvolní a položky, strany i data zůstanou. Pak ji vystavíte znovu se správným číslem. Je to šetrnější než ji smazat a skládat znovu ze zakázky.

**Spletli jste se a označili zaplaceno omylem?** Na detailu faktury je tlačítko **Vzít platbu zpět** — faktura se vrátí na *Předána* a záznam o úhradě zmizí. Číslo ani datum vystavení se nemění, doklad platí dál. Nejde to jen tehdy, když jste k faktuře už vystavili **pokladní doklad**; ten nejdřív stornujte.
- **Zaplacena** — označíte na detailu tlačítkem **Označit zaplaceno**.
- **Stornována** — jen u faktur zrušených dřív, než se koncepty začaly mazat. Nové faktury tenhle stav už nedostanou.

## Předání zákazníkovi

Vystavením se doklad **ještě nedostane k zákazníkovi**. Předání potvrzujete tlačítkem
**Předáno zákazníkovi** na detailu faktury — otevře se dialog, ve kterém můžete fakturu
rovnou **poslat zákazníkovi e-mailem** (viz níže), nebo ji jen označit jako předanou
(**Předat bez e-mailu** — třeba když jste ji vytiskli a podali přes pult).

Dokud ho nestisknete, je faktura ve stavu **Vystavena** a dá se s ní ještě zacházet jako
s omylem: **smazat a vystavit znovu**. Po potvrzení přejde na **Předána**. Číslo se přitom uvolní, takže novou fakturu
můžete vystavit se stejným. Po potvrzení předání už to nejde — doklad je u zákazníka a opravuje se
dobropisem.

Kliknout na *Předáno* omylem se dá taky, proto jde tlačítkem **Vzít předání zpět** vrátit. U už
zaplacené faktury to nejde: kdo zaplatil, doklad má.

## Odeslání faktury e-mailem

Fakturu můžete poslat zákazníkovi e-mailem přímo z aplikace — **PDF dokladu odejde jako
příloha**. Dialog se nabídne při **předání** faktury; u už předané faktury je na detailu
i v řádkovém menu akce **Poslat e-mailem** (třeba když zákazník doklad ztratil).

Dialog předvyplní:

- **Komu** — e-mail z karty zákazníka; můžete ho přepsat, nebo doplnit, když na kartě chybí.
- **Předmět a text** — připravenou kostru s číslem faktury, částkou a splatností. Text můžete
  **libovolně doplnit nebo přepsat** — odešle se přesně to, co v dialogu potvrdíte.

**Odeslání e-mailu platí jako předání** — faktura se orazítkuje jako předaná, stejně jako by
ji zákazník dostal přes pult. Když odeslání selže, nezmění se nic.

Odesílá se přes e-mailový účet servisu (nastavuje se na serveru, ne v aplikaci — bez nastavení
akce ohlásí, že odesílání není nakonfigurováno). Kopii každého odeslaného e-mailu i s přílohou
aplikace uloží do složky **Odeslané** vašeho e-mailu — tam je vaše evidence odeslaných faktur;
aplikace vlastní evidenci odeslané pošty nevede.

## Kdy dobropis a kdy smazat

Rozhodují dvě věci naráz:

| | Zákazník doklad **nemá** | Zákazník doklad **má** |
|---|---|---|
| Mění se částka nebo DPH | smazat a vystavit znovu | **dobropis** |
| Mění se jen adresa, symbol, překlep | smazat a vystavit znovu | dobropis to neřeší — daň se nemění |

**Dobropis = zákazník doklad má a mění se částka nebo daň.** Chybí-li jedna z těch podmínek,
dobropis to není. Proto se tlačítko **Vystavit opravný doklad** nabízí až u předané nebo zaplacené
faktury; u nepředané aplikace poradí ji smazat a vystavit znovu. Zákonný rámec: §42 a §45 zákona
o DPH, podrobnosti v článku **Opravný daňový doklad**.

**Smazat** lze **koncept** a **vystavenou fakturu, kterou zákazník ještě nedostal** — tlačítkem
*Smazat* na detailu faktury. Je to nevratné: faktura zmizí i s položkami. Smazáním se zároveň
odemkne zakázka k úpravám a lze ji vyfakturovat znovu.

Faktura s vystaveným dobropisem má v seznamu vedle stavu odznak **Dobropisována** (po najetí myší
ukáže datum opravného dokladu). Stav zůstává *Vystavena* nebo *Zaplacena* — doklad platí dál, jen
je opravený.

Zakázku odemkne i **vystavený dobropis**: dobropisovaná faktura zůstává platným dokladem, jen už
zakázku neblokuje — můžete k ní vystavit fakturu novou, na správnou částku, a předtím opravit
položky zakázky.

## Hlídání mezer v číselné řadě

Smažete-li fakturu, která **nebyla poslední** v řadě, zůstane po ní v číslování díra — další
faktura navazuje na nejvyšší číslo a na to uvolněné se sama nevrátí.

Zapnete-li ve **Fakturačních údajích** volbu *Hlídat mezery v číselné řadě*, objeví se nad
seznamem faktur upozornění s **přesnými chybějícími čísly**. Mezeru zavřete tím, že příští
fakturu vystavíte s chybějícím číslem místo navrženého — pole s číslem je v dialogu vystavení
přepisovatelné. Upozornění pak zmizí samo.

Hlídá se **aktuální období** podle masky číslování (měsíc, nebo rok) a jen čísla, která masce
odpovídají. Volitelně lze nastavit **číslo, od kterého se hlídá** — hodí se, když jste doklady
přenesli z jiného systému a jejich řada nenavazuje.

## QR platba a PDF

- **PDF** faktury otevřete/stáhnete z detailu.
- Faktura nese **QR platbu** — ale jen když má firma ve **Fakturačních údajích** vyplněný **IBAN**. Bez IBANu se QR nevygeneruje. QR je jen na **vystavené** faktuře: koncept je pouhý návrh dokladu a u **zaplacené** ani **stornované** faktury by načtení QR znamenalo platit podruhé. QR nese i **variabilní symbol**, pokud jste ho na faktuře vyplnili.

## Platba v hotovosti a zaokrouhlení

Když zákazník platí hotově, vystavíte k faktuře **příjmový pokladní doklad** (viz článek). Má smysl jen u vystavené nebo zaplacené faktury.

U faktury se způsobem úhrady **hotově** se částka **zaokrouhluje na celé koruny** — haléře se v hotovosti předat nedají. Faktura proto vedle částky celkem ukazuje ještě řádek **Zaokrouhlení** a **Celkem k úhradě**; stejnou částku nese pokladní doklad, QR platba i evidence úhrady, takže všechny doklady k jedné platbě zní na totéž. **Základ daně a DPH se zaokrouhlením nemění** — zákon o DPH (§ 36 odst. 5) ho drží mimo základ daně. U bezhotovostní faktury se nezaokrouhluje nic, platí se na haléře.

V seznamu faktur je proto sloupec **Celkem k úhradě** — částka, kterou zákazník skutečně zaplatí.

## Hledání a filtry

- **Hledat** — číslo faktury, zákazník, SPZ/VIN.
- **Stav** — koncept / vystavená / zaplacená.
- **Splatnost → Po splatnosti** — vystavené faktury po datu splatnosti (koncepty a zaplacené se sem nepočítají).
