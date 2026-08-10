# Opravy stavu skladu

Stav skladu se nikdy nepřepisuje ručně — vždy se zapíše **pohyb**, který ho změní. Díky tomu je u každého kusu dohledatelné, kdy a proč zásoba ubyla nebo přibyla.

Většina pohybů vzniká sama: příjem naskladněním příjemky, výdej přidáním dílu na zakázku, vrácení smazáním položky ze zakázky. Ručně se zadávají čtyři situace — **korekce**, **odpis**, **vratka dodavateli** a **spotřeba bez zakázky**.

## Kdy použít který pohyb

- **Korekce −** — přepočítali jste regál a fyzicky je tam méně kusů, než ukazuje systém (manko, ztráta, dřívější chyba v zadání).
- **Odpis** — díl fyzicky máte, ale už se nedá prodat: rozbil se při manipulaci, je znehodnocený nebo prošlý.
- **Vratka dodavateli** — díl posíláte zpátky: přišel vadný, dodali vám špatný kus nebo poškozený přepravou.
- **Spotřeba bez zakázky** — materiál jste použili v dílně, ale nepatří na žádnou zakázku (čistivo, spojovací materiál, režie).

Všechny čtyři zásobu **snižují**. Rozdíl je v důvodu, a ten se pak dá dohledat v historii pohybů: odpis znamená „zboží je pryč a nikdo nám ho neproplatí", vratka „šlo zpátky dodavateli a čekáme dobropis", spotřeba „použili jsme ho my, jen ne na konkrétní zakázku".

Díl, který jde na zakázku, se **nezadává tudy** — ten se přidá přímo na zakázce přes „Importovat položky".

## Jak zapsat pohyb

1. Otevřete **Sklad** a klikněte na kartu dílu.
2. Nahoře zvolte **Skladový pohyb**.
3. Vyberte **typ** pohybu — korekce, odpis, vratka dodavateli, nebo spotřeba bez zakázky (viz výše, čím se liší).
4. Vyberte **šarži** — tedy konkrétní dodávku, ze které kusy ubývají. U každé vidíte doklad, kolik z ní zbývá a za kolik byla nakoupena.
5. Zadejte **množství** kladným číslem (systém ho sám odečte).
6. U **vratky** navíc vyberte **důvod** (vadný díl, špatný díl, poškozeno přepravou, přebytek, jiné) a případně zapište **číslo dobropisu**, pokud už ho od dodavatele máte.
7. Napište **poznámku** — je povinná. Bez důvodu se stav skladu měnit nedá.
8. Uložte. Stav dílu i zbytek šarže se hned přepočítají a pohyb se objeví v historii dole na kartě.

## Proč se vybírá šarže

Každý kus na skladě patří k nějaké dodávce a má svou nákupní cenu. Kdyby se odepisovalo „z celku", přestalo by sedět, kolik zboží z které faktury ještě máte — a tím i hodnota skladu. Proto se vždy volí konkrétní šarže.

Nabízí se **nejstarší dodávka jako první** a je rovnou předvybraná — obvykle chcete ubírat od nejstarší. Pokud potřebujete jinou (třeba reklamujete konkrétní kus), stačí ji v seznamu přepnout.

Pokud dílu žádná šarže nezbývá, není z čeho odepisovat a modal to oznámí.

## Co dochází: přehled „Pod minimem"

Pokud má díl na kartě vyplněné **minimum**, hlídá ho systém sám. Stránka **Sklad → Pod minimem** ukazuje všechny díly, kterých je méně, než má být — a k nim rovnou **kolik chybí** a **u koho jste je naposledy kupovali**, včetně katalogového čísla dodavatele a poslední ceny. S tím se dá objednávat.

Objednávku samotnou aplikace nevystavuje.

## Přebytek se zadává jinak

Když najdete **víc** kusů, než systém ukazuje, nezadává se to korekcí. Nový kus by neměl žádnou dodávku ani nákupní cenu.

Místo toho použijte **Sklad → Příjemky → Nová ručně** — vyplníte díl, množství a cenu, a zboží se naskladní stejně jako z faktury.

## Časté situace

- **„Požadované množství je větší, než zbývá v šarži"** — v této šarži už tolik kusů není. Zkontrolujte zbytky u jednotlivých šarží a rozdělte odpis mezi ně.
- **Chyba v příjemce** — pokud byla špatně naskladněna celá příjemka, neopravujte to korekcí po kusech; použijte **storno příjemky** (viz článek „Příjem zboží na sklad").
- **Číslo dobropisu zatím nemáte** — nevadí, vratku zapište bez něj. Dobropis se v aplikaci zatím neeviduje jako samostatný doklad, číslo slouží jen k dohledání v historii pohybů.
