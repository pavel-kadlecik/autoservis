# Funkce: Plánovací (objednávkový) kalendář

> Funkční dokumentace — co funkce dělá a proč.
> Technické detaily vrstev: [api.md](../api.md) · [backend.md](../backend.md) · [databaze.md](../databaze.md) §6c
> Uživatelská nápověda: v aplikaci **Nápověda → Plánování** (`frontend/…/src/help/planovani.md`)

## Co funkce dělá

Servis si v kalendáři vede, **kdo a kdy přijede** a **kdy je zavřeno**. Otevírá se na **měsíčním
přehledu** aktuálního měsíce (přání zákazníka 2026-08-04 — po otevření chce vidět celkové vytížení;
dřív se otevíral týden): každý den ukazuje až tři řádky „čas jméno" a zavřené dny, klik na den přepne
na jeho týden. Druhý pohled je týden — **sedm denních karet vedle sebe**, v každé objednávky seřazené
podle času příjezdu.

**Objednávka termínu** vzniká typicky po telefonu: obsluha klikne do volného místa v kalendáři, napíše,
co se bude dělat, a uloží. Zákazník i vozidlo jsou **nepovinné** (V85) — vyplní se, co servis v tu
chvíli ví:

| Co obsluha ví | Co vyplní | Co ukáže kalendář |
|---|---|---|
| stálý zákazník, známé auto | zákazník + vozidlo | jméno zákazníka |
| zná člověka, ale ne auto | zákazník | jméno zákazníka |
| telefonát od někoho mimo evidenci | kontakt („Novák, 777 123 456") | text kontaktu |
| jen že někdo přijede | nic | název práce |

Vybere-li obsluha **jen vozidlo**, zákazníka doplní server z majitele auta — netřeba zadávat totéž
dvakrát. Chybějící údaje se dají doplnit později úpravou objednávky.

Objednávka má **dva tvary** podle toho, co servis v tu chvíli ví:

| Tvar | Kdy | Jak vypadá v kalendáři |
|---|---|---|
| **Časové okno** (od–do) | zákazník počká — výměna oleje, přezutí | rozsah `8:00 – 12:00` |
| **Jen příjezd** (konec prázdný) | zákazník nechá auto, délku opravy nelze před diagnostikou odhadnout | jen `od 8:00` |

Konec se doplní přes **Upravit**, jakmile ho mechanik zná. Chybějící druhý čas je jediné vodítko —
karta k němu nepíše nic navíc, protože „konec neznámý" by jen zopakovalo, co už říká sám tvar.

Objednávka prochází stavy:

| Stav | Kdy nastane |
|---|---|
| Naplánováno | termín domluvený se zákazníkem |
| Převedeno na zakázku | auto přijelo, vznikla zakázka |
| Nedostavil se | zákazník nepřijel a neozval se |
| Zrušeno | zákazník zavolal, že nepřijede — zůstává v historii, ale výchozí filtr „Jen nezrušené" ho v kalendáři schová |

**Blokace dílny** je druhá věc, která v kalendáři bydlí: státní svátek, celozávodní dovolená, revize
zvedáku. Nemá zákazníka ani vozidlo, jen název a časové okno — a do tohoto okna **nelze nikoho objednat**.

**Událost** (V82, přání zákazníka 2026-08-04) je třetí obyvatel kalendáře: školení, návštěva technika,
dovolená jednotlivého zaměstnance. Stejně jako blokace nemá zákazníka ani vozidlo, ale na rozdíl od ní
**objednávkám nebrání** — dovolená jednoho mechanika dílnu nezavírá. U dovolené jde volitelně vybrat
zaměstnanec, takže se dá dohledat, kdo je kdy pryč. V kalendáři se událost odlišuje vlastní barvou
(vysvětlivky pod kalendářem).

**Převod na zakázku** je tlačítko na detailu objednávky. Otevře formulář zakázky předvyplněný ze
objednávky (zákazník, vozidlo, popis práce a — jen když ještě nenastal — konec jako odhad dokončení),
obsluha ho doplní o stav tachometru a uloží.
Vznikne zakázka a objednávka se na ni naváže — v kalendáři zezelená a nabídne odkaz na zakázku. Opačným
směrem vede odkaz z detailu zakázky zpět do kalendáře.

## Klíčová rozhodnutí a proč

| Rozhodnutí | Proč tak, a ne jinak |
|---|---|
| **Samostatná tabulka, ne sloupce na zakázce** | Objednávka vzniká dřív než zakázka (volá se v pondělí, přijede v úterý) a část objednávek se na zakázku nikdy nepromění. Sloupce na `orders` by nutily zakládat prázdné zakázky, které by zkreslily fronty na dashboardu i statistiky. |
| **Jedna tabulka pro objednávky, blokace i události** | Vše je „obdélník v čase" a v kalendáři se kreslí stejně. Další tabulka by znamenala další dotazy, mappery a slučování na frontendu — za rozdíl, který unese jeden sloupec `entry_type` a CHECK constrainty. Událost (V82) je třetí případ téhož vzoru. |
| **Událost neblokuje objednávky** | Blokace říká „dílna je zavřená", událost jen „něco se děje" — dovolená jednoho mechanika nebo školení dílnu nezavírá. Kdyby událost blokovala, obsluha by ji nemohla použít pro nic, co běží souběžně s provozem, a pro zavření dílny už blokace existuje. Události se proto nepočítají ani do varování o překryvu. |
| **Dovolená s vazbou na zaměstnance, ne jen text** | `employee_id` (volitelné) dělá z dovolené evidovaný údaj — jde dohledat, kdo je kdy pryč, a jednou na tom může stavět kapacita. Bez vazby by z dovolené zbyl nadpis „Dovolená Novák", který nikdo strojově nepřečte. Povinná není: událost je i školení nebo návštěva technika, které se zaměstnancem nesouvisí. |
| **Události smí zakládat každý, kdo plánuje** | Rozhodnutí uživatele 2026-08-04: práva jako u objednávek. Blokace zůstává jen vedení — zavřít dílnu je provozní rozhodnutí; zapsat dovolenou nebo školení je běžná evidence. |
| **Neplánuje se na mechanika ani stanoviště** | Rozhodnutí uživatele 2026-08-03. Servis o pár lidech to nepotřebuje a model by narostl o tabulku zdrojů, sloupce v kalendáři a validaci obsazenosti. `employee_id` (od V82 u událostí) tím dostal první využití bez přestavby. |
| **Kolize varují, nezakazují** | Překryv dvou objednávek servis běžně chce — dělá na dvou autech naráz. Tvrdý zákaz by vynutil evidenci kapacity dílny (další nastavení, další obrazovka) kvůli pravidlu, které si obsluha umí posoudit sama. |
| **Blokace naopak zakazuje tvrdě** | „Zavřeno" je fakt, ne odhad. Objednávka do zavřené dílny je vždy chyba, a to i když ji zadá někdo, kdo o svátku neví — což je přesně ten případ, kdy má systém zasáhnout. |
| **A zakazuje i opačným směrem** | Do 2026-08-07 se hlídal jen jeden směr: objednávka do blokace neprošla, ale blokaci šlo nakreslit **přes** existující objednávku — a kalendář pak v jednom okamžiku tvrdil „zavřeno" i „ve 13:00 přijede Novák". Zavřít dílnu na dobu, kdy někdo přijede, je stejná chyba jako opak, takže platí týž tvrdý zákaz (422 `CLOSURE_OVERLAPS_ENTRIES`) — při zakládání, úpravě i přetažení myší. Rozhodnout, co ustoupí, musí obsluha: buď přesune objednávky, nebo zavře jindy. Zrušené a nedostavené se nepočítají, na ty už nikdo nepřijede. |
| **Odhad dokončení se přenáší, jen když konec ještě nenastal** | Přání uživatele 2026-08-07. U vícedenní objednávky („nechá auto do středy") konec znamená, kdy si zákazník přijede pro auto — je to rovnou ten odhad a přepisovat ho ručně je zbytečná práce. U ranního slotu 8–10 znamená jen konec rezervovaného okna: zakázka se zakládá, až auto přijede, tedy typicky po něm. Předvyplnit ho tam navíc nejde ani technicky — `OrderDto.CreateRequest` má na poli `@FutureOrPresent`, takže by se formulář otevřel s hodnotou, kterou server odmítne, a obsluha by ji mazala při každém převodu. Dosadit místo minulého konce „teď" by si vymyslelo termín, který nikdo netvrdil, a podle odhadu se řídí fronta na dashboardu. |
| **Převod v jedné transakci** | Kdyby klient nejdřív založil zakázku a druhým požadavkem ji propojil, selhání toho druhého by nechalo zakázku, o které objednávka neví — a příště by šla vytvořit znovu. Jedna transakce to vylučuje. |
| **`CONVERTED` nejde nastavit ručně** | Ten stav drží vazbu na zakázku. Nastavit ho bez `order_id` by porušilo databázový CHECK a skončilo chybou 500 místo srozumitelné hlášky. |
| **Terminální stavy bez návratu** | Oživení zrušené objednávky nebo té, na kterou nikdo nedorazil, by přepsalo historii, ze které se počítá, kolik lidí nepřijelo. Když se termín domluví znovu, zakládá se nová objednávka. |
| **Konec je volitelný, čas příjezdu ne** | Délku opravy mechanik před diagnostikou nezná — vynucený konec by do dat zapsal číslo, které nikdo netvrdí, a kalendář by podle něj kreslil délku, která nikde neplatí. Čas příjezdu naopak servis zná vždy, protože ho musí zákazníkovi sdělit. |
| **Třetí tvar („přiveze to ve středu") se nezavádí** | Rozhodnutí uživatele 2026-08-03. Objednávka bez hodiny znamená, že zákazník přijede náhodně a bude čekat. Kdyby výjimečně nastala, zapíše se rozumný čas a do poznámky, že přesná hodina není domluvená. |
| **Tvar se pozná z `ends_at`, ne z příznaku** | Samostatný sloupec „má konec" by mohl začít odporovat datům. Jeden údaj = jeden zdroj pravdy. |
| **Zrušené se ve výchozím stavu skrývají** | Zrušená objednávka je informace pro budoucí přehledy („kolik lidí ruší"), ale v denní kartě zabírá řádek a obsluha chce hlavně vidět, že je volno. Bez filtru by bylo pokušení ji rovnou smazat a o data přijít. Přepínač „Jen nezrušené" je zapnutý výchozí a v popisku hlásí, kolik položek schoval — aby uživatel věděl, že něco nevidí. |
| **Přepínač „Celý den"** | Vyplní časy podle otevírací doby daného dne. Zaškrtnutí se **odvozuje z časů**, neukládá se — uložený příznak by se po ruční změně času rozešel se skutečností a tvrdil „celý den" u objednávky od devíti do desíti. Při vypnutém hlídání je nedostupný: nastavení slibuje, že se kalendář otevírací dobou vůbec nezabývá. |
| **Vysvětlivky a otevírací doba u obou pohledů** | Do 2026-08-04 byly jen u týdne, přestože měsíc kreslí řádky v týchž barvách stavů — legenda chyběla přesně tam, kde je barva jediné vodítko. Popisek šrafování se proto zkrátil na „Zavřeno": v měsíci nese oba významy (blokace i den mimo otevírací dobu) a konkrétnější text by o víkendu lhal. |
| **Otevírací doba varuje, nezakazuje** | Nastavení → Otevírací doba (V79) drží týdenní rozvrh a přepínač hlídání. Termín mimo dobu se **označí, ale uloží** — servis občas auto přijme mimo dobu a systém mu v tom nemá bránit (týž princip jako u překryvu). Blokace dílny naproti tomu zakazuje: ta je jednorázové rozhodnutí, tohle jen rozvrh. Hlídá se **příjezd a vyzvednutí**, ne doba mezi nimi — auto přes noc v zavřené dílně stojí běžně. Výchozí stav vypnutý, ať migrace nezačne varovat u dat, která nikdo nezkontroloval. |
| **Objednávka nemusí mít zákazníka ani vozidlo** | Od V85 (rozhodnutí uživatele 2026-08-07) jsou obě vazby volitelné. Termín se domlouvá po telefonu dřív, než servis o autě cokoli ví — „přijedu ve středu ráno, něco to klepe" — a volající často není v evidenci. Vynucená vazba nutila zakládat zákazníka i vozidlo z odhadu, tedy zapsat údaj, který nikdo nepotvrdil; to je horší než prázdné pole. Zdůvodnění V78 („zakázka vozidlo stejně vyžaduje, objednávka bez něj práci jen odsouvá") platí dál, ale míří na špatný okamžik: vozidlo je potřeba, až auto přijede a vzniká zakázka — ne když se domlouvá termín. Převod si vozidlo vyžádá sám. Objednávka zůstává čitelná i prázdná: název práce je povinný vždy. |
| **Kontakt jako volný text, ne strukturovaná pole** | `contact_note` (V85) drží jméno a telefon zákazníka mimo evidenci — bez něj by termín nešlo s nikým přeložit, když se posouvá. Jedno pole, ne „jméno" + „telefon" zvlášť: obsluha si po telefonu poznamená, co stihne, a u „paní Nováková, volá z práce" by struktura jen překážela. Až přibudou SMS připomínky, telefon dostane vlastní sloupec — tehdy pro něj bude použití. |
| **Jen vozidlo → zákazník se dopočítá, a hned se ukáže** | Vybere-li obsluha auto bez zákazníka, server doplní majitele z `vehicles.customer_id` (NOT NULL, takže dopočet vždy vyjde). Formulář ho navíc **dotáhne rovnou do pole** (`GET /vehicles/{id}`), aby obsluha viděla, koho systém k autu přiřadí, a neukládala naslepo; `AutocompletePair` je neřízená, takže se pole přemontuje přes `key`. Selhání toho requestu se mlčí — uložení projde i tak, dopočet je na serveru. Vracet chybu „vyberte i zákazníka" by svazovalo přesně tam, kde má být volnost, a nechat pole prázdné by zahodilo informaci, kterou databáze zná. Vyplní-li obsluha obojí, musí souhlasit — jinak by šlo objednat cizí auto a převod na zakázku by spadl až na konci. |
| **Blokace se čte stejně jako objednávka** | Karta blokace nese čas i šipku u přesahu do dalších dnů (`Zavřeno · 8:00 – so 10:00`, `→ Zavřeno · do 10:00`); celodenní zavřeno se píše `celý den`, ne „0:00 – 0:00". Do 2026-08-04 blokace obojí ignorovala, takže dovolená od pátečních 8:00 do sobotních 10:00 vypadala oba dny stejně — z kalendáře nešlo poznat, že dílna je v pátek ráno a v sobotu dopoledne otevřená. Pokračování odlišuje jen šipka: čárkovaný obvod už znamená „blokace". **Měsíc nese totéž** (přání uživatele 2026-08-07) — do té doby tam stálo holé „Zavřeno", takže z přehledu nešlo poznat, jestli je zavřeno celý den nebo jen na dvě hodiny, ani proč; kvůli tomu se musel otevřít týden. Obava, že časy udělají z buňky změť, se nepotvrdila: při běžné šířce (buňka ~160 px) je čas na jednom řádku a mřížka drží výšku, na úzkém okně se zalomí a buňka o pár pixelů naroste, ale nepřeteče. Dlouhý důvod se ořízne — celý je v tooltipu a v týdnu. |
| **Vedlejší akce v menu „⋯"** | V detailu jsou vidět jen „Upravit" a „Založit zakázku"; nedostavení, zrušení a smazání jsou pod tlačítkem tří teček. Pět barevných tlačítek vedle sebe nedávalo oku kam se dívat a kvůli šířce si vynutilo široký dialog, v němž řídký obsah plaval v prázdnu. Menu je navíc týž vzor (`TableRowActionMenu`), jaký mají řádky tabulek v celé aplikaci. |
| **Terminální stavy jsou jen ke čtení** | Převedenou, zrušenou i nedostavenou objednávku nelze upravit (422, UI tlačítko skrývá). Čas a účastníci jsou fakta o tom, co se stalo — editace by přepisovala historii, ze které se počítá nedostavení, a u převedené by rozešla údaje se zakázkou. Doloženo oborem: Acuity zrušený termín zamyká („isn\u2019t possible to un-cancel"), Tekmetric drží dění po příjezdu na zakázce. Oprava omylu = smazání, ne editace. |
| **Bez stavu „Potvrzeno"** | Původně existoval, zrušen ve V77 (rozhodnutí uživatele). Objednávka vzniká po telefonu se zákazníkem na lince — je potvrzená už v okamžiku založení, takže „Naplánováno" a „Potvrzeno" znamenaly totéž a obsluha musela zbytečně volit. Nepoužívaný stav je horší než žádný: čtenář kalendáře si o něm myslí, že něco znamená. Servis zákazníky před termínem neobvolává ani nezapisuje termíny „tužkou" před domluvou — v obou těch případech by stav smysl měl. Až přibudou SMS připomínky („zákazník odpověděl"), vrátí se; přidat hodnotu do ENUMu je levnější než ji odebrat. |
| **Bez číselné řady** | Číslo dostávají doklady (zákazník, zakázka, faktura, PPD), ne evidenční záznamy. Objednávka je záměr, který se běžně ruší a nikdo se na ni zpětně neodvolává. Řada by přinesla trigger, unikátní index a povinnost řešit díry — bez užitku. |
| **Blokace jen pro vedení** | Zavřít dílnu je provozní rozhodnutí, ne úkon mechanika. Objednávky smí zakládat kdokoli z obsluhy. |
| **Smazání ≠ zrušení, a maže se natvrdo** | Dvě různé události: „vzniklo omylem" (mizí úplně) a „zákazník nepřijede" (zůstává v historii, stav `CANCELLED`). Sloučit je do jednoho by znamenalo přijít o jedno z toho. Soft-delete byl původně obojí — zrušen ve V76 (rozhodnutí uživatele): objednávka není doklad, nikdo na ni neodkazuje a deaktivovaný záznam by v datech jen ležel. Převedenou objednávku smazat nelze; není to omyl, vzešla z ní zakázka. |
| **Denní karty místo časové osy** | Osa byla první podoba a **musela ustoupit** (rozhodnutí uživatele 2026-08-03). Kreslila výšku podle délky termínu, jenže délku opravy mechanik před diagnostikou často nezná — a hlavně: když na jednu hodinu přijede pět aut, osa je rozseká na pět svislých proužků po pár desítkách pixelů. Kapacitu dílny nikde neevidujeme, takže osa slibovala přesnost, kterou data nemají. Seznam podle příjezdu nepředstírá nic. |
| **Bez knihovny kalendáře** | S osou odpadl i důvod pro FullCalendar — v denních kartách nedělal nic, co bychom nenapsali sami, a většina práce na něm byla obcházení jeho vlastního vzhledu (barvy událostí, výška hodiny, `position`, maskování). Odebráno 6 npm balíčků, produkční balík se zmenšil o **258 kB** (76 kB gzip). Datovou aritmetiku (začátek týdne, posun, klíč dne) řeší `api/scheduleDates.js` — ~100 řádků, které se nezmění. |
| **Přetažením se mění den, ne hodina** | Přání uživatele 2026-08-07; do té doby se netáhlo vůbec. Karta dne je seznam, takže svislá poloha při upuštění nenese čas — odvodit z ní hodinu by zapsalo údaj, který nikdo netvrdil. Upuštění proto posune jen datum a **čas i délku ponechá**; hodina se dál mění přes „Upravit". Tím zůstává v platnosti důvod, proč padla časová osa, a přesto odpadá ruční přepisování data u nejčastější změny („zákazník volal, že přijede až ve čtvrtek"). Backend na to byl připravený od začátku: `POST /{id}/time` i `TimeRequest` vznikly už s osou a přežily její zrušení. |
| **Zakázaný cíl svítí červeně už během tažení** | Přání uživatele 2026-08-07. Modrý obrys znamená „sem to půjde", červený „sem ne" — obsluha to vidí dřív, než pustí myš, místo aby se položka vrátila a teprve pak přišla hláška (to vypadá jako netrefené přetažení, ne jako pravidlo). Počítá se na klientovi z už načteného okna (`isDropBlocked`), takže je to <strong>jen vodítko</strong>; rozhoduje dál server. Kdyby se obojí rozešlo, projeví se to vrácením položky a hláškou — nikdy uložením něčeho zakázaného. |
| **Táhne se jen za den příjezdu** | U vícedenní objednávky by chycení řádku „→ pokračuje" neřeklo, kam se má přesunout začátek. Terminální stavy (zrušená, nedostavil se, převedená) se netáhnou vůbec — server je odmítne jako needitovatelné a nabízet akci, která vždy selže, mate víc, než když chybí. Blokaci smí posunout jen vedení, stejně jako ji smí zakládat. |
| **Měsíc se kvůli tažení nepřestavoval** | Buňka dne zůstala `<button>` a řádky uvnitř `<span>` — přidal se jen obalový `<span>` jako cíl upuštění a posluchače tažení. Převést buňku na `<div>` by znamenalo ručně dodělat průchod tabulátorem, spuštění Enterem i popisek pro čtečku, a rozplétat dva kliky přes sebe (na položku × na volné místo buňky). Z dnd-kit se proto **nepoužívá `attributes`**: přidávají `role="button"` a `tabIndex`, čímž by uvnitř buňky vzniklo vnořené tlačítko. Tažení je zrychlovač pro myš; klávesnicová cesta vede přes „Upravit". |
| **Měsíc = mini seznam (styl Google)** | Buňka ukáže až 3 řádky „čas jméno" obarvené stavem, přebytek shrne „+N další"; klik kamkoli přepne na týden dne. Popover po kliknutí by schoval to podstatné o klik dál a hover verze by na tabletu nefungovala vůbec. |
| **Vícedenní položky se opakují v každém dotčeném dni** | Objednávka s vyzvednutím v jiném dni („nechá auto do středy") a vícedenní blokace se ukážou ve všech dnech, kterých se týkají — plně v den příjezdu, dál jako „→ pokračuje" ve stejné barvě stavu s čárkovaným obvodem (barva je informace, ztlumení ji zahazovalo — rozhodnutí uživatele), poslední den „→ do 16:00". Podřádek nese název práce; přesný termín je v tooltipu a v detailu, protože bez data by v jiném týdnu nic neřekl. Tooltip vypisuje **plné datum i čas** začátku a konce — čte se samostatně, takže holý čas by u vícedenní objednávky nestačil; tvar je shodný s detailem po kliknutí. Souvislý pás přes buňky (Google) by chtěl vrstvení nad mřížkou a zalomení týdne — moc práce na okrajový případ. **Objednávka bez konce se přes dny netáhne**: délku neznáme, patří jen do dne příjezdu. Stejná změna (seskupení podle překryvu, `groupOccurrences`) opravila chybu, kdy blokace začínající před zobrazeným oknem z kalendáře úplně zmizela. |

## Čerstvost dat

Kalendář načítá data při každé změně zobrazeného okna (jiný týden, jiný pohled) — žádná cache.
Po každé akci (založení, přesun, změna stavu, smazání) se okno načte znovu, takže obsluha vidí i to,
co mezitím udělal kolega. Varování o překryvu se počítá živě při vyplňování formuláře.

## Mapa implementace

**Databáze** — [V72__init_schedule_schema.sql](../../src/main/resources/db/migration/V72__init_schedule_schema.sql),
demo data [V73__seed_appointments.sql](../../src/main/resources/db/demo/V73__seed_appointments.sql),
volitelný zákazník a vozidlo + kontakt
[V85__appointment_optional_customer.sql](../../src/main/resources/db/migration/V85__appointment_optional_customer.sql).
Popis schématu v [databaze.md](../databaze.md) §6c.

**Backend**
- `mapper/AppointmentMapper.java` + `resources/mapper/AppointmentMapper.xml` — dotaz na časové okno,
  překryvy, blokace
- `service/impl/AppointmentServiceImpl.java` — validace, stavový automat, atomický převod
- `controller/AppointmentController.java` — 10 endpointů, rolová kontrola blokací
- `model/domain/schedule/Appointment.java`, `model/dto/schedule/AppointmentDto.java`,
  `model/converter/AppointmentConverter.java`
- `model/enums/AppointmentType.java`, `AppointmentStatus.java` (+ handlery v `PgEnumTypeHandler`)

**Frontend** (bez knihovny kalendáře)
- `pages/SchedulePage.jsx` — přepínání týden/měsíc, navigace, detail v modalu, akce
- `components/ScheduleDayCard.jsx` — jeden den jako karta se seznamem objednávek
- `components/ScheduleMonth.jsx` — měsíční mřížka: mini seznam „čas jméno", „+N další", zavřené dny
- `components/ScheduleLegend.jsx` — vysvětlivky barev; vzorky nosí tytéž třídy jako objednávky
- `components/AppointmentForm.jsx` — sdílený formulář pro založení i úpravu
- `api/scheduleDates.js` — datová aritmetika (začátek týdne, posun, klíč dne v místním čase)
  + `groupOccurrences`: rozdělení položek do všech dotčených dnů vč. pravidel pro přesahy
  + `shiftToDay`: nový termín po přetažení (posun po kalendářních dnech, ne po milisekundách —
  jinak by objednávka o změně letního času utekla o hodinu)
  + `isDropBlocked`: skončilo by upuštění chybou? (obarvení cíle během tažení; zrcadlí dvojici
  pravidel blokace × objednávka ze service)
- `css/schedule.css` — karty, měsíc, barvy stavů
- `pages/OrdersPageCreate.jsx` — režim „zakázka z objednávky" (`?appointmentId=`)
- `pages/OrdersPageDetail.jsx` — zpětný odkaz „Vzniklo z objednávky"
- `api/format.js` — popisky a barvy stavů + `vehicleLabel` („Značka Model - SPZ", shodné
  s našeptávačem vozidel)

**Testy**
- `service/AppointmentServiceTest.java` — 62 integračních testů (CRUD, blokace, kolize, stavy,
  převod včetně rollbacku, volitelný zákazník i vozidlo a dopočet zákazníka z vozidla)
- `web/AppointmentApiContractTest.java` — 13 kontraktních testů (statusy, `Location`, oprávnění)
- `model/enums/AppointmentStatusTest.java` — parametrizovaná matice přechodů (10 případů)
- `model/converter/AppointmentConverterTest.java` — 9 testů mapování včetně `blankToNull` u kontaktu

## Odloženo

Plánování na mechanika a stanoviště, automatický návrh termínu podle kapacity, SMS a e-mailové
připomínky, objednávání zákazníkem přes web (potřebovalo by náhodný ověřovací kód), opakované události.

## Odloženo

- **Polední pauza** (zavřeno 12:00–13:00) — znamenala by dva intervaly na den, tedy jinou tabulku
  i jinou validaci. Dokud si o ni nikdo neřekne, je to složitost navíc.
- **Svátky** se zapisují ručně jako blokace dílny; kalendář státních svátků nemáme.

## Historie

- **2026-08-03** — přání zákazníka („plánovací kalendář jako Google"). Rešerše konkurence
  (IC Office, AdmWin, EasyWeek, Omnetic) a knihoven; návrh a rozhodnutí výše.
- **2026-08-03** — V72 + V73, doména, DTO a converter; poté mapper, service, controller,
  testy a frontend.
- **2026-08-03** — V74 + V75: konec objednávky je volitelný. Vyplynulo z připomínky uživatele, že
  mechanik délku opravy často nezná; původní `NOT NULL` ho nutil ji vymyslet.
- **2026-08-03** — přestavba vzhledu časové osy: oddělené sloupce dnů, hodina 60 px, vlastní
  vykreslení události, vysvětlivky barev.
- **2026-08-03** — **opuštění časové osy.** Uživatel upozornil, že na jednu hodinu může být
  objednáno pět aut a osa je rozseká na nečitelné proužky, protože kapacita dílny se nikde
  neeviduje. To spolu s často neznámou délkou opravy vzalo ose smysl. Nahrazena denními kartami,
  FullCalendar odebrán z projektu, přetahování myší zrušeno.
- **2026-08-04** — rešerše stavových modelů konkurence (Tekmetric, Square, Acuity, Bookly, AutoLeap) —
  potvrdila čtyřstavový model i zrušení „Potvrzeno"; podle ní zamčena editace terminálních stavů.
- **2026-08-04** — V77: zrušen stav „Potvrzeno", zbyly čtyři stavy; přibyl filtr „Jen nezrušené".
- **2026-08-04** — V76: zrušen soft-delete, objednávka založená omylem se maže natvrdo (204);
  převedenou smazat nelze.
- **2026-08-04** — oprava: klik na objednávku dotahuje plný detail ze serveru. Karty kreslí zúžený
  `ListResponse`, takže editace neměla zákazníka ani vozidlo a uložení padalo na 422 „U objednávky
  je zákazník povinný"; poznámka se v detailu nikdy nezobrazila.
- **2026-08-03** — měsíc povýšen z počtů na mini seznam („8:00 Novák", „+N další") a vícedenní
  položky se opakují ve všech dotčených dnech; opravena ztráta blokace začínající před oknem.
- **2026-08-07** — **přesun termínu tažením** (přání uživatele): objednávka se přetáhne na jiný
  den v týdenním pohledu i v měsíční mřížce, čas a délka zůstávají. Backend se nedotkl —
  `POST /{id}/time` včetně testů existoval od V72, jen mu od zrušení časové osy chyběl klient.
  Měsíc si podržel `<button>` buňky, takže ovládání klávesnicí ani popisky pro čtečku se nezměnily.
- **2026-08-07** — oprava dokumentu: popis tvarů objednávky pořád mluvil jazykem **časové osy**
  („blok o délce termínu", „rozplývající se spodek se třemi tečkami", doplnění konce protažením
  bloku myší), přestože osa i přetahování skončily 2026-08-03. Popis nahrazen tím, co karty
  opravdu kreslí (`8:00 – 12:00`, resp. `od 8:00`; konec se doplňuje přes „Upravit").
- **2026-08-07** — převod na zakázku předvyplňuje **odhad dokončení** koncem objednávky, pokud
  ten čas ještě nenastal (přání uživatele). Do té doby se pole nechávalo prázdné kvůli
  `@FutureOrPresent`; podmínka na budoucnost tu validaci respektuje a vícedenním objednávkám
  ušetří ruční přepis. Dokumentace i nápověda přitom tvrdily, že se „slíbený termín" předvyplňuje —
  nebyla to pravda, opraveno.
- **2026-08-07** — V85: **zrušena povinnost zákazníka i vozidla u objednávky.** Vyplynulo
  z připomínky uživatele, že vazba je svazující — po telefonu často není známé auto ani volající
  sám. Přibyl kontakt na zákazníka mimo evidenci a dopočet zákazníka z vybraného vozidla. Tím se
  ruší rozhodnutí V78 (2026-08-04, vozidlo povinné) i původní pravidlo z V72 (zákazník povinný).
