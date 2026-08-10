# Modul WAREHOUSE (sklad) — návrh, rozhodnutí a import faktur z PDF

> Referenční dokument modulu skladového hospodářství. Vznikl při návrhu importu
> položek z PDF faktur dodavatelů do skladu. Popisuje doménovou úvahu, strukturu
> tabulek, klíčová rozhodnutí, mechanismus dohledatelnosti, vratky a plánovaný
> import přes Spring AI. Migrace: `V18__init_warehouse_schema.sql`, katalogová pole `V21__add_product_catalogue_fields.sql`.

---

## 1. Proč tento modul vznikl a co řeší

Autoservis při opravách spotřebovává díly. Ty nakupuje od dodavatelů (AUTO RAVIRA,
Auto Kelly, Inter Cars a podobně), kteří ke každé dodávce vystavují fakturu —
nejčastěji v PDF. Na faktuře je seznam položek: katalogové číslo dílu, název,
množství, nákupní cena bez DPH, sazba a cena s DPH. Tyto díly fyzicky přijdou do
servisu, naskladní se a později se vydávají na konkrétní zakázky, nebo se vracejí
dodavateli, pokud jsou vadné nebo špatně dodané.

Cílem modulu je tento koloběh podchytit v databázi tak, aby z něj nikdy nevypadla
nejdůležitější informace: **odkud se každý kus na skladě vzal**. Konkrétně musí být
kdykoliv dohledatelné, z které faktury a z které objednávky díl pochází. To není
formalita — je to nutnost pro reklamace (vracíte konkrétní vadný díl konkrétnímu
dodavateli a potřebujete odkázat na původní fakturu), pro účetnictví (nákupní cena
má svůj doklad) i pro daňový audit (přijaté doklady se archivují ze zákona).

Sekundárním, ale velmi praktickým cílem je odbourat ruční přepisování. Faktura
dodavatele se nahraje jako PDF, jazykový model z ní vytáhne položky a ty se založí
na sklad. Mechanik výsledek jen zkontroluje a potvrdí. Tomuto importu se věnuje
kapitola 8.

---

## 2. Proč nepoužíváme stávající tabulky faktur

V projektu už existuje schéma `billing` s tabulkami `billing.invoices` a
`billing.invoice_items`. Mohlo by se zdát logické uložit do nich i faktury od
dodavatelů. To by ale byla doménová chyba, a stojí za to vysvětlit proč, protože
jde o jeden z nejčastějších omylů při návrhu fakturačních systémů.

Tabulky `billing.invoices` reprezentují **faktury odchozí** — tedy doklady, které
*vy vystavujete svým zákazníkům*. Generují se ze zakázky, nesou `order_id` a
`customer_id`, jejich číslo faktury i variabilní symbol generuje váš vlastní
databázový trigger a jejich stavový životní cyklus (návrh, vystaveno, zaplaceno)
odpovídá tomu, jak vy fakturujete ven.

Faktura od dodavatele je pravý opak — je to doklad **příchozí**. Neváže se na vaši
zakázku ani na vašeho zákazníka. Její číslo a variabilní symbol jste nevytvořili vy,
ale dodavatel, a vy je musíte uložit přesně tak, jak je vystavil; rozhodně je nesmí
přepsat váš trigger. A její životní cyklus je jiný: přijata, zkontrolována,
proplacena. Smíchat příchozí a odchozí faktury do jedné tabulky znamená smíchat
nákup a prodej, což se vymstí u výpočtu DPH, u reportů i v účetnictví, kde nákup a
prodej stojí na opačných stranách.

Proto má sklad vlastní příchozí doklad. A protože pro malý servis platí, že jedna
PDF faktura odpovídá jedné dodávce zboží, sloučili jsme dva koncepty, které by ve
velkém ERP byly oddělené (účetní faktura a skladová příjemka), do jediné hlavičky
`warehouse.goods_receipts`. Tím jsme ušetřili celou jednu vrstvu tabulek, aniž
bychom o cokoliv přišli. Kdyby v budoucnu přibyl modul závazků (accounts payable),
dá se účetní část od skladové oddělit; teď by to byla zbytečná složitost.

---

## 3. Klíčová myšlenka: doklad, produkt a pohyb jsou tři různé věci

Většina nedorozumění kolem skladu pramení z toho, že se tři odlišné koncepty
vnímají jako jeden. Vyplatí se je důsledně rozlišit, protože celá struktura tabulek
z tohoto rozlišení vychází.

**Doklad** je záznam o tom, co a za kolik nám dodavatel naúčtoval. Je neměnný —
faktura, jakmile je vystavena, se už nemění. V našem modelu mu odpovídají tabulky
`goods_receipts` (hlavička) a `goods_receipt_items` (řádky).

**Produkt**, jinak skladová karta, je *typ* dílu. „Sada rozvodu Toyota, katalogové
číslo SU001A3082“ je jeden produkt — i kdybyste ji koupili desetkrát během roku,
na skladě je to pořád tentýž typ zboží. Produktu odpovídá tabulka `products`.

**Pohyb** je událost, která mění množství na skladě: příjem, výdej do zakázky,
vratka, odpis, inventurní korekce. Pohybům odpovídá tabulka `stock_movements` a
jejich postupné sčítání dává aktuální stav skladu.

Rozdíl mezi dokladem a pohybem je jemný, ale zásadní. Doklad říká „toto nám bylo
naúčtováno“ — je to obraz minulosti, který se nemění. Pohyb říká „takto se v tomto
okamžiku změnil náš stav“. Z jedné příjemky vznikne sada příjmových pohybů, ale
další pohyby (výdeje, vratky) už s původní fakturou přímo nesouvisí — jen se k ní
přes šarži dají dohledat. Tohle oddělení je to, co dělá sklad robustním.

---

## 4. Struktura tabulek

Modul tvoří pět tabulek v novém schématu `warehouse`. Následuje podrobný popis
každé z nich a vysvětlení, proč vypadá tak, jak vypadá.

### 4.1 `warehouse.suppliers` — číselník dodavatelů

Dodavatelé se opakují. Od AUTO RAVIRA nakoupíte v průběhu let mnohokrát a nechcete
jejich IČO, DIČ a bankovní spojení přepisovat na každé faktuře znovu. Proto má
dodavatel vlastní kartu, na kterou se příjemky odkazují.

Hlavní rozhodnutí padlo ve chvíli, kdy přišla řeč na vratky. Vratka i reklamace se
vždy řeší s konkrétním dodavatelem — vracíte zboží jemu, on vám vystaví dobropis a
vy chcete vidět, kolik máte u kterého dodavatele otevřených vratek nebo nevyřízených
dobropisů. To se bez samostatné tabulky dodavatelů dělá špatně. Proto je
`suppliers` master tabulka a dedup probíhá podle IČO, které je v rámci systému
unikátní (`uq_suppliers_ico`). Dodavatel se nikdy nemaže, jen deaktivuje přes
`is_active` (soft delete dle konvence projektu).

### 4.2 `warehouse.products` — skladová karta

Produkt je deduplikovaný typ dílu identifikovaný katalogovým číslem v poli `sku`
(z anglického *stock keeping unit*), které je unikátní (`uq_products_sku`). Při
importu faktury se pro každý řádek nejdřív zkusí dohledat produkt podle SKU; pokud
neexistuje, založí se nový. Díky tomu se stejný díl ze dvou různých faktur sloučí
pod jednu kartu a vy vidíte souhrnný stav.

Klíčové pole je `quantity_on_hand` — aktuální množství na skladě. Je to ale
**denormalizovaná hodnota**, tedy záměrná kopie pro rychlost. Skutečná pravda o
stavu je součet všech pohybů v knize `stock_movements`. Proč tu denormalizaci máme?
Protože dotaz „kolik mám na skladě sady rozvodů“ by jinak musel pokaždé sčítat celou
historii pohybů daného produktu, což je u často dotazovaného údaje zbytečně drahé.
Stejný vzor používáme jinde v projektu — například `current_mileage_km` u vozidla je
také denormalizace. O konzistenci této hodnoty se nestará aplikace, ale databázový
trigger, popsaný v kapitole 6. Constraint `chk_products_qty` navíc hlídá, že stav
nikdy neklesne pod nulu.

### 4.3 `warehouse.goods_receipts` — příjemka (hlavička faktury)

Jedna PDF faktura odpovídá jedné příjemce. V hlavičce žijí dvě informace, na kterých
celému zadání nejvíc záleží: `invoice_number` (číslo faktury z PDF) a `order_number`
(číslo objednávky z PDF). Faktura AUTO RAVIRA navíc nese „Původní číslo obj.“, pro
které máme pole `original_order_number`. Tyto údaje se ukládají tak, jak jsou na
dokladu — negenerujeme je, jen je věrně přebíráme.

Hlavička dále nese datum vystavení, splatnosti a uskutečnění zdanitelného plnění
(DUZP), finanční souhrn (základ daně, DPH, celková částka, měna) a snapshot názvu
dodavatele. Ten snapshot stojí za vysvětlení: kromě cizího klíče na `suppliers`
máme název dodavatele zkopírovaný i přímo na příjemce v poli
`supplier_name_snapshot`. Není to chyba ani zbytečnost. Faktura je daňový doklad a
musí navždy nést údaje tak, jak byly v okamžiku jejího vystavení. Když dodavatel za
rok změní název nebo adresu, aktualizuje se jeho karta v `suppliers`, ale stará
faktura musí zůstat beze změny. Proto se kombinuje odkaz na živá data (cizí klíč) se
zamrazeným snapshotem. Je to standardní produkční vzor.

Hlavička obsahuje i pole spojená s importem z PDF: `status` (stav procesu kontroly),
`reconciliation_ok` (sedí součet řádků na celkovou částku?), `extraction_model`
(který AI model doklad zpracoval), `source_filename` a `source_pdf`, kam se ukládá
originální PDF jako binární data pro daňovou archivaci. Význam těchto polí
vysvětluje kapitola 8.

Dvě omezení tu chrání integritu. Cizí klíč na dodavatele je `ON DELETE RESTRICT` —
dodavatele s navázanými fakturami nelze smazat, což odpovídá konvenci projektu pro
business vazby. A unikátní constraint `uq_receipt_invoice` nad dvojicí
`(supplier_id, invoice_number)` zajišťuje **idempotenci importu**: tutéž fakturu od
téhož dodavatele nelze omylem naimportovat dvakrát.

### 4.4 `warehouse.goods_receipt_items` — řádky příjemky neboli šarže

Tady se odehrává to nejdůležitější. Každý řádek faktury je samostatná **šarže**
(dávka) konkrétního produktu, která má vlastní nákupní cenu a vlastní zbývající
množství. Šarže je most mezi dokladem a skladem a zároveň nositel dohledatelnosti.

Proč šarže, a ne prostý součet na kartě produktu? Ze dvou důvodů. První je historie
nákupních cen: stejný díl koupíte dnes za jednu cenu a za půl roku dráž. Kdybyste
měli na produktu jen jedno číslo, tahle historie by se přepsala a ztratila. S
šaržemi vznikne při každém nákupu nová dávka s vlastní cenou a minulost zůstává
zachována. Druhý důvod je právě dohledatelnost: každá šarže má cizí klíč na příjemku
(`goods_receipt_id`), a tím pádem z každé šarže — a přes pohyby z každého kusu na
skladě — vede cesta zpět na číslo faktury i objednávky.

Řádek nese `name_snapshot`, což je název položky opsaný doslova z faktury (na rozdíl
od názvu na kartě produktu, který se může postupně kultivovat). Dále `quantity_received`
(kolik přišlo, neměnný údaj), `quantity_remaining` (kolik ze šarže ještě fyzicky je),
nákupní cenu bez DPH, sazbu a cenu s DPH. Cizí klíč na produkt je `ON DELETE
RESTRICT`, na příjemku naopak `ON DELETE CASCADE` — smazání příjemky smaže její
řádky, protože ty bez ní nedávají smysl (vztah vlastnictví dle konvence).

### 4.5 `warehouse.stock_movements` — kniha pohybů

Kniha pohybů je účetní deník skladu. Je **append-only** — jen se do ní přidává,
nikdy se needituje a nemaže. Každý jednotlivý příjem, výdej, vratka, odpis a
inventurní korekce je samostatný řádek se znaménkovým množstvím: kladné u příjmu,
záporné u výdeje a vratky. Součet všech pohybů daného produktu dává jeho skutečný
stav; to, co je na kartě produktu v `quantity_on_hand`, je jen rychlá kopie tohoto
součtu.

Pohyb nese `movement_type` (druh), `quantity` (znaménkové množství), volitelně
`batch_id` (z které šarže), `order_id` (do které zakázky šel výdej), u vratek
`return_reason` a `credit_note_number`, a auditní pole `created_by` a `moved_at`.
Tato append-only kniha je to, co skladu dává paměť: kdykoliv lze zrekonstruovat, kdo
co kdy kam pohnul. To je nutné pro inventuru i pro řešení sporů.

Dva CHECK constrainty hlídají smysluplnost. `chk_movement_sign` váže znaménko na
druh pohybu — příjem musí být kladný, výdej, vratka a odpis záporné, korekce může
být obojí (ale ne nula). A `chk_return_reason` zajišťuje, že důvod vratky je vyplněn
právě a jen u pohybu typu RETURN, nikde jinde.

---

## 5. Jak je zaručena dohledatelnost faktury a objednávky

Toto byl hlavní požadavek zadání, a proto si zaslouží samostatné shrnutí. Řetěz
odkazů vypadá takto:

```
pohyb (stock_movements)
   └─ batch_id ─► šarže (goods_receipt_items)
                     └─ goods_receipt_id ─► příjemka (goods_receipts)
                                                ├─ invoice_number   (číslo faktury)
                                                ├─ order_number     (číslo objednávky)
                                                └─ supplier_id ─► dodavatel
```

Vezměte libovolný kus na skladě, libovolný výdej do zakázky nebo libovolnou vratku.
Přes jeho šarži se dostanete na příjemku a z ní přečtete číslo faktury i číslo
objednávky a zjistíte dodavatele. Tato vlastnost je v migraci ověřena testem — výdej
EGR ventilu i vratka vadného těsnění se v testu skutečně dohledaly zpět na fakturu
202500684 a objednávku 202500684. Pro běžné použití slouží pohled
`warehouse.v_batch_provenance`, který tento řetěz spojuje za vás a u každé šarže
rovnou ukazuje zbývající množství, fakturu, objednávku a dodavatele.

Důležitý důsledek pro reklamace: protože vratka je pohyb proti konkrétní šarži,
automaticky zná i dodavatele a původní fakturu, aniž bychom přidávali jakoukoliv
další vazbu. Když dodavatel vystaví dobropis s odkazem na původní fakturu, máte ho
čím spárovat.

---

## 6. Stav skladu a trigger, který ho udržuje

Rozhodli jsme se, že kniha pohybů je jediný zdroj pravdy o množství, ale že
nejčastěji dotazované hodnoty (stav produktu a zbytek šarže) budeme držet
denormalizované pro rychlost. Aby tyto kopie nikdy nemohly „uplavat“ od reality,
neudržuje je aplikace, ale databáze sama, přes trigger
`trg_apply_stock_movement` nad funkcí `warehouse.fn_apply_stock_movement()`.

Funguje to takto: po každém vložení řádku do `stock_movements` trigger upraví
`quantity_on_hand` příslušného produktu o znaménkové množství pohybu, a pokud je
pohyb navázán na šarži a nejde o příjem, upraví i `quantity_remaining` té šarže.
Příjem (RECEIPT) je výjimka — šarži se nedotýká, protože ta se zakládá rovnou
„plná“, tedy s `quantity_remaining` rovným `quantity_received`. Ostatní pohyby
(výdej, vratka, odpis) zbytek šarže snižují.

Výhoda tohoto přístupu je v tom, že konzistence stavu je zaručena bez ohledu na to,
která aplikační cesta pohyb zapsala. Ať už díl naskladní importér z PDF, ručně
mechanik, nebo dávkový skript, stav se vždy přepočítá stejně a správně. A protože na
`quantity_on_hand` i `quantity_remaining` jsou CHECK constrainty zakazující záporné
hodnoty, pokus o přečerpání skladu (výdej více, než je k dispozici) skončí chybou už
na úrovni databáze — což je v testu ověřeno.

---

## 7. Vratky a reklamace

Díly se v servisu běžně vracejí — jsou vadné, špatně dodané, poškozené přepravou
nebo zbytečné. Tento případ jsme nemodelovali zvlášť složitě, protože díky šaržím ho
zvládne obyčejný pohyb.

Vratka je pohyb typu RETURN se záporným množstvím proti té šarži, ze které vracený
díl pochází. V jedné transakci sníží zbytek šarže i stav produktu — přesně jako
výdej, jen s jiným druhem. Navíc se u něj povinně vyplňuje `return_reason`, což je
číselník důvodů (vadný, špatně dodaný, poškozený přepravou, přebytek, jiné), aby
šlo později vyhodnotit, kolik a proč se u kterého dodavatele vrací. Pole
`credit_note_number` zůstává zpočátku prázdné: díl vrátíte hned, ale dobropis od
dodavatele dorazí třeba za týden, a tehdy ho doplníte.

Zvolili jsme zatím „lehkou“ variantu — vratka jako jednotlivý pohyb. Pokud se
ukáže, že potřebujete vracet hromadně více položek pod jedním dobropisem s vlastním
součtem, dá se později přidat samostatný doklad vratky (`supplier_returns`) jako
zrcadlo příjemky. Pro současný objem to ale není potřeba a začínat tím by bylo
předčasné.

---

## 8. Import faktury z PDF přes Spring AI

Naskladnění z faktury má proběhnout automaticky: mechanik nahraje PDF, model z něj
vytáhne položky a ty se založí na sklad. K tomu využijeme Spring AI, protože projekt
běží na Spring Boot.

Tady je důležitá poznámka ke kompatibilitě. Projekt je na Spring Boot 4, a ten
vyžaduje Spring AI 2.0, který je v době psaní zatím jen ve verzi milestone
(2.0.0-M4), nikoliv v GA — to se očekává až v polovině roku 2026. Pro produkci to
znamená vědomé rozhodnutí: buď použít milestone s tím, že se jeho API ještě může
drobně měnit (a import faktur do GA případně držet za přepínačem funkcí), nebo s
tímto modulem počkat na finální verzi. Funkčně milestone běží.

Vlastní extrakce je v Spring AI elegantní. PDF se modelu předá jako příloha typu
`application/pdf` (Anthropic Claude podporuje čtení PDF přímo) a cílový tvar
výsledku se popíše jako obyčejná Java třída. Spring AI z té třídy sám odvodí JSON
schéma, připojí ho k dotazu, model vrátí JSON a knihovna ho rovnou naparsuje do
objektu. Ručně psát prompt s JSON ani parsovat odpověď není potřeba. Teplota dotazu
se nastaví na nulu, protože u extrakce dat nechceme kreativitu, ale co nejvěrnější
doslovný přepis.

Tok importu pak vypadá takto. Model vrátí hlavičku faktury (číslo, objednávka,
data, souhrn), údaje o dodavateli a seznam položek. Aplikace nejdřív ověří
**rekonciliaci** — sečte ceny řádků a porovná je s celkovou částkou faktury. Tohle
je u finančních dat zásadní pojistka, protože model může číslo přečíst špatně; když
součet nesedí, příjemka se označí jako nesrovnaná a musí ji zkontrolovat člověk. Pak
se dohledá nebo založí dodavatel podle IČO, založí se hlavička příjemky s číslem
faktury a objednávky, a pro každý řádek se dohledá nebo založí produkt podle
katalogového čísla, vytvoří se šarže a zapíše příjmový pohyb. Celá příjemka se uloží
ve stavu PENDING_REVIEW. Teprve když ji mechanik zkontroluje a potvrdí, přejde do
stavu CONFIRMED. Lidský souhlas u financí a u dat z AI je povinný — automat nikdy
nepotvrzuje sám.

Originální PDF se ukládá do pole `source_pdf` pro daňovou archivaci. U malého servisu
je binární uložení přímo v databázi nejjednodušší; při větším objemu by se originály
přesunuly do objektového úložiště a v databázi by zůstal jen odkaz.

---

## 9. Konvence a strategie cizích klíčů

Modul důsledně dodržuje konvence projektu. Primární klíče jsou `BIGSERIAL`
mapované na Javu jako `Long`, cizí klíče `BIGINT`. Časová razítka jsou `TIMESTAMPTZ`
(v Javě `OffsetDateTime`) a sloupce `updated_at` se aktualizují databázovým
triggerem `warehouse.fn_set_updated_at()`, nikoliv aplikační logikou. Výčtové typy
jsou PostgreSQL `ENUM` definované v tomto schématu a v MyBatis se na ně použije
vlastní `PgEnumTypeHandler`. Záznamy se neodstraňují, ale deaktivují přes
`is_active`. V MyBatis XML se používají plně kvalifikované názvy tabulek
(`warehouse.products` a podobně).

Strategie `ON DELETE` se řídí povahou vazby. Vztah vlastnictví, kde potomek bez
rodiče nedává smysl, používá `CASCADE` — tak je navázána příjemka na své řádky.
Business vazba, kde entita má smysl sama o sobě, používá `RESTRICT` — tak je
chráněn dodavatel s fakturami, produkt se šaržemi a šarže s pohyby. Auditní vazba na
toho, kdo záznam vytvořil (`created_by` na `security.users`), používá `SET NULL` —
uživatel může ze systému odejít, ale historický záznam zůstane zachován.

---

## 10. Co bylo otestováno

Migrace byla aplikována na čistou PostgreSQL 16 a celý modul prošel sadou testů.
Naskladnila se kompletní faktura AUTO RAVIRA se šesti položkami; stav skladu po
naskladnění odpovídal a rekonciliace faktury vyšla přesně (součet řádků 26 107 Kč
se rovná celkové částce). Ověřil se výdej dílu do zakázky i vratka vadného dílu —
v obou případech klesl jak stav produktu, tak zbytek příslušné šarže, a oba pohyby
se daly dohledat zpět na číslo faktury a objednávky. Nakonec proběhly záporné testy,
které potvrdily, že databáze odmítne nevalidní data: přečerpání skladu, vratku bez
důvodu, důvod vratky u jiného typu pohybu, příjem se záporným množstvím, duplicitní
fakturu téhož dodavatele i duplicitní katalogové číslo produktu.

---

## 11. Stav implementace

Databázová vrstva je hotová a ověřená. Na ni navázala Java i frontend vrstva.

**Hotovo:** doménové objekty a MyBatis mappery; **produkt (skladová karta)** —
přehled skladu, detail (se šaržemi a pohyby), plný CRUD a katalogová pole
(výrobce, varianta, prodejní cena, hlídání nízké zásoby, migrace `V21`); extrakční
služba nad Spring AI pro čtení PDF faktur.

**Rozpracováno / další kroky:** CRUD dodavatelů; workflow příjemek
(potvrzení/zamítnutí naimportované faktury); napojení tlačítka „Importovat
dodací list" na frontendu; import položek faktury přímo na zakázku — založí řádek
zakázky typu MATERIAL a zároveň `ISSUE` pohyb, takže se sníží sklad i šarže.
