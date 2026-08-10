# 08 — Účetnictví a česká legislativa (stav k roku 2026)

> Audit 2026-07-30 · rozsah: co aplikace **vystavuje a eviduje** (faktura, opravný daňový doklad,
> příjmový pokladní doklad, skladová evidence, inventura, osobní údaje) proti platné české úpravě
> k roku 2026 · metoda: nejdřív úplné čtení kódu / šablon / migrací, pak dohledání právní úpravy
> na webu, pak porovnání; každý nález ověřen druhým čtením přímo v kódu.

## ⚠️ Povinné upozornění

**Auditor není daňový poradce ani advokát.** Závěry v tomto dokumentu jsou technickou analýzou
kódu porovnanou s veřejně dostupným zněním předpisů a s odbornými výklady. **Před ostrým provozem
je nutné je potvrdit s účetním, případně s daňovým poradcem** — zejména body, kde je výklad sporný
(zaokrouhlování, archivace generovaného PDF, storno vs. opravný doklad).
U každého nálezu je uvedena **míra jistoty** a na čem stojí: technický fakt (kód/migrace) je
ověřitelný, právní kvalifikace je odborný názor.

Právní stav ověřen k 30. 7. 2026. Klíčové zjištění k rámci: **nový zákon o účetnictví v roce 2026
neplatí** — v prosinci 2025 byl teprve předložen sněmovně, nejbližší reálná účinnost je 1. 1. 2027
(spíše 2028). Platí dál zákon č. 563/1991 Sb.
([Crowe](https://www.crowe.com/cz/cs-cz/news/aktualita-k-novemu-zakonu-o-ucetnictvi),
[Deloitte](https://www.deloitte.com/cz-sk/cs/services/audit-assurance/services/novy-zakon-o-ucetnictvi.html)).

---

## Co bylo přečteno

**Šablony (celé):**
`src/main/resources/templates/pdf/invoice.html`, `credit-note.html`, `cash-receipt.html`,
`invoice-styles.html`

**Java (celé):**
`service/impl/InvoiceServiceImpl.java`, `CreditNoteServiceImpl.java`, `CashReceiptServiceImpl.java`,
`StockTakeServiceImpl.java`, `CustomerServiceImpl.java`,
`service/impl/InvoiceDocumentServiceImpl.java`, `CreditNoteDocumentServiceImpl.java`,
`CashReceiptDocumentServiceImpl.java`, `service/impl/SpaydBuilder.java`, `util/AmountInWords.java`,
`service/IsdocParser.java`,
`model/converter/InvoiceConverter.java`, `CreditNoteConverter.java`, `CashReceiptConverter.java`,
`model/dto/billing/InvoiceDto.java`, `CashReceiptDto.java`, `CompanyProfileDto.java`,
`model/domain/billing/CompanyProfile.java`,
`model/enums/InvoiceStatus.java`, `PaymentMethod.java`, `InvoicePartyRole.java`,
`controller/CreditNoteController.java`, `controller/CashReceiptController.java`

**Migrace (celé):**
V2, V14, V15, V18, V31, V32, V33, V34, V35, V37, V42, V44, V49, V50, V51, V55, V57, V61

**Mappery / SQL (celé nebo relevantní bloky):**
`mapper/CreditNoteMapper.xml`, `mapper/warehouse/StockTakeMapper.xml`,
`mapper/InvoiceMapper.xml` (insert/update/updateStatus/recordPayment/select),
`mapper/CustomerMapper.xml` (souhlasy, update)

**Frontend (celé nebo relevantní bloky):**
`pages/InvoicesPageDetail.jsx`, `pages/StockTakePageDetail.jsx`,
`components/InvoiceCreateFormModal.jsx`, `components/OrderItemFormModal.jsx`,
`components/OrderItemsWrapper.jsx`, `src/help/faktury.md`

**Testy:** `service/CreditNoteServiceTest.java`, `service/StockTakeTest.java`

**Dokumentace:** `CLAUDE.md`, `docs/konvence.md`, `docs/tech-dluhy.md`,
`docs/funkce/prijmovy-pokladni-doklad.md`, `docs/funkce/inventura.md`, `docs/api.md` (výřez),
`docs/databaze.md` (výřez)

*Nečteno záměrně: `audit/2026-07-24/` (zákaz v briefu) a ostatní reporty z 2026-07-30 —
překryv s průchodem 02 (fakturace/peníze) je proto možný.*

---

## Shrnutí

Jádro daňového dokladu aplikace zvládá dobře: obě strany se zmraženými snapshoty včetně DIČ,
vlastní číselné řady s advisory lockem a per-měsíc/rok resetem, DUZP i datum vystavení jako
samostatná pole, rekapitulace DPH po sazbách počítaná z položek se stejným zaokrouhlením jako
součty, neměnnost vystaveného dokladu vynucená guardovanými UPDATE. To je nadprůměrné.

Slabina není v tom, co doklad obsahuje, ale v **procesech okolo dokladu**. Nejzávažnější nález:
nápověda v aplikaci uživatele **výslovně instruuje**, aby vystavenou fakturu opravoval stornem
a novým vystavením — což je u dokladu už předaného odběrateli a zahrnutého do přiznání postup
mimo §42/§45 ZDPH. Modul opravného daňového dokladu přitom v backendu existuje, jen **nemá
frontend** a z UI se k němu nelze dostat.

Dále chybí ochrany proti duplicitám u dokladů, které vždy nesou plnou částku (dobropis,
příjmový pokladní doklad), chybí náležitost obchodní listiny podle §435 NOZ (údaj o zápisu
v rejstříku), aplikace nezná režim neplátce DPH ani přenesenou daňovou povinnost, umožňuje
zpětné datování faktury do uzavřeného období a **uzavřená inventura přestane vykazovat zjištěné
rozdíly** (manka/přebytky se v soupisu zobrazí jako nuly), takže sama o sobě není průkazným
záznamem podle §30 ZoÚ.

**Počty:** 🔴 VYSOKÝ 1 · 🟠 STŘEDNÍ 10 · 🟡 NÍZKÝ 5.

---

## Nálezy

### [L-1] Nápověda i UI vedou k opravě vystavené faktury stornem, opravný daňový doklad není z aplikace dosažitelný
**Severita:** 🔴 VYSOKÝ
**Jistota:** OVĚŘENO (technický fakt) / právní kvalifikace: vysoká jistota, potvrdit s účetním
**Kde:**
- `frontend/autoservis-frontend/src/help/faktury.md:19` — „Vystavenou fakturu **nelze opravit přímo** — **stornujte ji a vystavte znovu** (storno zároveň odemkne zakázku k úpravám)."
- `frontend/autoservis-frontend/src/pages/InvoicesPageDetail.jsx:127-135` — tlačítko „Stornovat" se nabízí pro `DRAFT` i `ISSUED`
- `src/main/java/cz/palo/autoservis/model/enums/InvoiceStatus.java:25` — `ISSUED, EnumSet.of(PAID, CANCELLED)`
- `src/main/java/cz/palo/autoservis/controller/CreditNoteController.java` — modul dobropisu existuje (POST `/credit-notes`, `/{id}/issue`, `/{id}/pdf`), ale grep `credit-notes|creditNote` přes celý `frontend/` nevrací **žádného volajícího** (jediné shody jsou `creditNoteNumber` u skladové vratky ve `StockMovementModal.jsx`); v `src/help/` není článek o dobropisu a v `App.jsx` není route

**Co je špatně:** Vystavená faktura je daňový doklad. Nastala-li po jejím vystavení skutečnost
měnící základ daně (reklamace, sleva, chybná položka), zákon požaduje **opravu základu daně podle
§42 ZDPH a vystavení opravného daňového dokladu podle §45** — ne zrušení původního dokladu.
Aplikace nabízí právě tu cestu, kterou zákon pro předaný doklad nepředpokládá, a tu správnou
(dobropis) před uživatelem schovává. Storno konceptu (`DRAFT → CANCELLED`) je naopak v pořádku —
koncept nemá číslo a není daňovým dokladem.

**Scénář selhání:**
1. Servis vystaví fakturu 202607014 na 12 100 Kč, vytiskne a předá zákazníkovi, DPH 2 100 Kč
   zahrne do červencového přiznání a kontrolního hlášení (oddíl A.4).
2. V srpnu zákazník reklamuje díl, dohodne se sleva 2 000 Kč.
3. Obsluha se řídí nápovědou: na detailu faktury klikne **Stornovat** → faktura přejde do
   `CANCELLED`, ze zakázky se vystaví nová faktura s novým číslem a novým datem.
4. **Následek:** doklad, který má odběratel fyzicky v ruce a který je vykázaný v červencovém
   hlášení, je v systému zrušen bez opravného dokladu. Odběratel-plátce má v hlášení doklad, který
   dodavatel popřel → nesoulad v kontrolním hlášení. Snížení daně na výstupu není doloženo
   dokladem podle §45. Správné je: původní faktura zůstane, vystaví se opravný daňový doklad
   `OD202608nnn` s rozdílem −2 000 Kč základu a odpovídající daní, do 15 dnů od zjištění
   rozhodných skutečností a s vynaložením úsilí, aby se dostal odběrateli.

**Proč to vadí:** právo + peníze. Neoprávněné snížení daně na výstupu bez opravného dokladu je
riziko doměrku a penále; nesoulad v kontrolním hlášení vyvolá výzvu správce daně. Zároveň jde
o rozpor mezi tím, co aplikace **umí** (dobropis je hotový v backendu, včetně PDF a testů), a tím,
co uživateli **říká**.

**Návrh řešení:**
1. Přepsat `help/faktury.md` — vystavenou fakturu předanou odběrateli opravovat **výhradně**
   opravným daňovým dokladem; storno vyhradit pro doklad, který ještě nebyl předán ani vykázán.
2. Doplnit FE pro dobropis: na detailu vystavené/zaplacené faktury akce „Opravný daňový doklad"
   (POST `/credit-notes` s důvodem opravy → vystavení → PDF) — endpointy existují.
3. U `ISSUED → CANCELLED` v UI vyžadovat explicitní potvrzení s textem, že storno je určeno pro
   nepředaný doklad, a nabídnout dobropis jako alternativu. *(Zda storno ISSUED úplně zakázat je
   rozhodnutí uživatele/účetní — v praxi se používá pro doklad zachycený před odesláním.)*

**Poznámka k evidenci dluhu:** `docs/analyza-2026-07.md:72-75` (bod V1) tento problém předjímá
(„před reálným provozem: CANCELLED u ISSUED/PAID faktury nahradit vystavením dobropisu"), ale
v `docs/tech-dluhy.md` sledovaný **není** — TD-62 řeší jen částečný dobropis a evidenci úhrad.
Proto je hlášen jako nález, ne jako připomínka dluhu.

*Zdroje: [§42, §45 ZDPH — Podnikatel.cz](https://www.podnikatel.cz/zakony/zakon-c-235-2004-sb-o-dani-z-pridane-hodnoty/f6436242/), ověřeno 30. 7. 2026; lhůta 15 dnů pro vystavení ODD a 7 let pro opravu základu daně (novela účinná od 2025).*

---

### [L-2] K jedné faktuře lze vystavit libovolný počet plných dobropisů — žádný guard
**Severita:** 🟠 STŘEDNÍ
**Jistota:** OVĚŘENO
**Kde:**
- `src/main/java/cz/palo/autoservis/service/impl/CreditNoteServiceImpl.java:37-65` — `createFromInvoice` ověří jen existenci faktury a stav `ISSUED`/`PAID`; **žádná kontrola, zda k faktuře už dobropis existuje**
- `src/main/resources/db/migration/V55__init_credit_notes.sql:36-39` — jediný unique je `uq_credit_note_number`; na `original_invoice_id` je jen FK a index (`:41`)
- `src/main/java/cz/palo/autoservis/model/converter/CreditNoteConverter.java:68-83` — rozdíly = **záporné souhrny celé původní faktury** (`negate(originalSummary.getTotalNet())` atd.), tedy každý dobropis dobropisuje plnou částku
- `src/main/resources/mapper/CreditNoteMapper.xml:43-48` — `findByOriginalInvoiceId` vrací seznam, model s více dobropisy počítá
- `src/test/java/cz/palo/autoservis/service/CreditNoteServiceTest.java` — test na duplicitu neexistuje

**Co je špatně:** Dobropis je v MVP vždy plný (dobropisuje celý základ i daň). Nic nebrání tomu,
aby k téže faktuře vznikly dva a víc, každý s vlastním číslem řady `OD` a každý se stejnou
zápornou částkou.

**Scénář selhání:** Faktura 202607010 na 12 100 Kč (základ 10 000, DPH 2 100), stav PAID.
Obsluha (nebo dvojí volání API po timeoutu sítě) založí a vystaví dva dobropisy: `OD202607001`
a `OD202607002`. Oba PDF nesou „Rozdíl základu −10 000,00 / Rozdíl DPH −2 100,00". Účetní obojí
zaúčtuje → základ daně snížen o 20 000 Kč a daň o 4 200 Kč proti plnění za 10 000 Kč.
Očekávané chování: druhý pokus skončí 422 (`INVOICE_ALREADY_CREDITED`) nebo se dobropis
napočítá jen na dosud nedobropisovaný zbytek.

**Proč to vadí:** peníze + právo. Dvojnásobné snížení daně na výstupu = krácení daně; zároveň
dva doklady na tutéž opravu jsou v evidenci nedohledatelný nepořádek.

**Návrh řešení:** dokud je dobropis plný (TD-62), přidat guard v `CreditNoteServiceImpl` —
existuje-li k faktuře dobropis ve stavu `DRAFT` nebo `ISSUED`, vrátit `BusinessRuleException`
(422). Doplnit částečným unique indexem
`CREATE UNIQUE INDEX ... ON billing.credit_notes (original_invoice_id) WHERE status <> 'CANCELLED'`
(vzor `uq_invoices_order_active`, V48), aby to nešlo obejít souběhem. Až přijde částečný
dobropis, guard nahradit kontrolou „součet dobropisů ≤ částka faktury".

---

### [L-3] K jedné faktuře lze vystavit víc PPD a každý nese plnou částku faktury
**Severita:** 🟠 STŘEDNÍ
**Jistota:** OVĚŘENO
**Kde:**
- `src/main/java/cz/palo/autoservis/service/impl/CashReceiptServiceImpl.java:40-76` — žádná kontrola existujících dokladů; `:64` `BigDecimal amountReceived = summary.getTotalGross().setScale(0, RoundingMode.HALF_UP);` — vždy **celá** částka faktury
- `src/main/resources/db/migration/V57__init_cash_receipts.sql:16-17` — komentář: „Bez unique na invoice_id — k jedné faktuře může vzniknout víc PPD (**dílčí hotovostní úhrady**)"
- `docs/funkce/prijmovy-pokladni-doklad.md:24` totéž, ale `:33` současně říká „Částečné úhrady zatím ne — PPD = plná částka faktury"
- `frontend/.../pages/InvoicesPageDetail.jsx:54-64, 112-117` — tlačítko „Pokladní doklad" je dostupné opakovaně; `busy` blokuje jen po dobu requestu

**Co je špatně:** Dokumentovaný záměr (dílčí hotovostní úhrady) a implementace se rozcházejí.
Částku nelze zadat — vždy se vezme celková částka faktury. Opakované kliknutí tedy nevytvoří
dílčí doklad, ale **druhý doklad na plnou částku** s novým číslem řady.

**Scénář selhání:** Faktura na 6 105,23 Kč, stav ISSUED. Obsluha klikne „Pokladní doklad",
PDF se otevře v novém panelu, obsluha se vrátí a klikne znovu (myslí si, že se nic nestalo).
Vzniknou `PPD202607005` a `PPD202607006`, oba „Přijato 6 105 Kč, Úhrada faktury č. 202607011".
Účetní zaúčtuje oba → v pokladně chybí 6 105 Kč proti skutečnosti. Doklad je navíc podepsaný
a předaný zákazníkovi, takže existují dvě potvrzení o téže platbě.
Očekávané chování: buď 422 s upozorněním na existující doklad, nebo možnost zadat skutečně
přijatou částku a doklad na zbytek.

**Proč to vadí:** peníze + průkaznost pokladní evidence (§11 ZoÚ — účetní doklad musí zachycovat
skutečný obsah účetního případu).

**Návrh řešení (rozhodnutí uživatele mezi variantami):**
a) minimum — před založením zkontrolovat `findByInvoiceId`; existuje-li doklad, vyžádat potvrzení
   nebo vrátit 422;
b) plné řešení — do `CreateRequest` doplnit `amount` (default = zbývající částka), validovat
   „součet PPD ≤ celková částka faktury"; tím teprve vznikne to, co V57 slibuje.
V obou případech sjednotit komentář ve V57 a `docs/funkce/prijmovy-pokladni-doklad.md`
s realitou.

---

### [L-4] Uzavřená inventura přestane vykazovat zjištěné rozdíly — soupis ukáže samé nuly
**Severita:** 🟠 STŘEDNÍ
**Jistota:** OVĚŘENO
**Kde:**
- `src/main/resources/mapper/warehouse/StockTakeMapper.xml:132-134` — `CASE WHEN sti.counted_quantity IS NULL THEN NULL ELSE sti.counted_quantity - p.quantity_on_hand END AS difference` — rozdíl proti **živému** stavu produktu
- `src/main/java/cz/palo/autoservis/service/impl/StockTakeServiceImpl.java:75-95` — `getDetail` počítá `shortageLines`/`surplusLines` z tohoto `difference`
- tamtéž `:184-192` — `close()` nejdřív zapíše korekční pohyby (`applyShortages`/`applySurpluses`), teprve **potom** vrátí `getDetail(id)`
- `src/main/resources/db/migration/V18__init_warehouse_schema.sql:283-302` — trigger `fn_apply_stock_movement` po vložení pohybu dorovná `products.quantity_on_hand`
- `frontend/.../pages/StockTakePageDetail.jsx:62-68` (`diff = counted − currentQuantity`), `:147-153` (hláška „…z toho {shortageLines} mank a {surplusLines} přebytků"), `:192-194` (sloupec „Rozdíl")

**Co je špatně:** Korekční pohyby srovnají `quantity_on_hand` na napočítané množství. Protože
rozdíl se počítá vždy proti aktuálnímu stavu, je **po uzavření u každého řádku nula**. Uzavřená
inventura tak v aplikaci vypadá, jako by žádný rozdíl nenašla. Jediná stopa po manku/přebytku
zůstane v poznámce skladového pohybu („Inventura INV-2026-0001 — manko") a u přebytků v
pseudo-příjemce. Sloupec `expected_quantity` (snapshot při otevření) se sice zachová, ale je
jen informativní a s knihovanou korekcí se nemusí shodovat (výdeje během počítání).

**Scénář selhání:**
1. Sklad má 10 ks dílu, obsluha napočítá 4 → manko 6.
2. Uzavře inventuru. Backend zapíše ADJUSTMENT −6, trigger sníží `quantity_on_hand` na 4.
3. Odpověď na `POST /close` (a každé pozdější otevření detailu) vrátí pro tento řádek
   `difference = 4 − 4 = 0`, `shortageLines = 0`.
4. FE zobrazí zelený banner „Inventura je uzavřená. Napočítáno N řádků, z toho **0 mank a
   0 přebytků**." a ve sloupci Rozdíl nuly.
5. **Následek:** účetní nemá z aplikace doklad o tom, jaké manko inventura zjistila a jak bylo
   oceněno; nelze doložit zaúčtování inventarizačního rozdílu ani vyřadit škodu.
   *(Test `StockTakeTest.shortageConsumesOldestBatchesFirst` ověřuje jen stavy skladu a šarží,
   ne obsah soupisu po uzavření — proto to suita nezachytí. Test
   `differenceUsesCurrentStockNotSnapshot:329-330` asertuje `shortageLines = 0`, ale ve scénáři,
   kde žádný rozdíl skutečně nebyl.)*

**Proč to vadí:** právo + provoz. §30 ZoÚ požaduje, aby inventurní soupis byl **průkazný účetní
záznam**, ze kterého lze zjištěný majetek jednoznačně určit; §30 odst. 7 a násl. řeší
inventarizační rozdíly (manko / přebytek), které se vyúčtují do období, za které se inventarizace
provádí. Soupis, který po uzavření tvrdí „žádné rozdíly", tuto funkci neplní. Uchovávat se má
5 let (§29 odst. 3 ZoÚ).

**Návrh řešení:** při uzavření **zmrazit** zjištěné hodnoty do `stock_take_items` — doplnit
sloupce `closed_expected_quantity` (stav v okamžiku uzavření) a `closed_difference` (a případně
`closed_unit_price`), naplnit je v `close()` **před** zápisem pohybů. `getDetail` u stavu
`CLOSED` číst tyto sloupce, u `OPEN` počítat živě. Zároveň zvážit tiskový protokol inventury
(dokumentace ho v `docs/funkce/inventura.md:82` označuje za nedodělek) s náležitostmi §30:
okamžik zahájení a ukončení, způsob zjištění, podpisové záznamy odpovědných osob.

*Zdroj: [§29–30 zákona o účetnictví — BusinessCenter](https://businesscenter.podnikatel.cz/pravo/zakony/ucto/f1397020/), ověřeno 30. 7. 2026.*

---

### [L-5] Faktura neuvádí údaj o zápisu v obchodním (živnostenském) rejstříku — §435 NOZ
**Severita:** 🟠 STŘEDNÍ
**Jistota:** OVĚŘENO (technický fakt) / právní kvalifikace: vysoká jistota
**Kde:**
- `src/main/resources/db/migration/V35__company_profile_and_supplier_backfill.sql:34-49` — `billing.company_profile` má `name, ico, dic, street, street_number, city, postal_code, country_code, bank_account, iban, swift` — **žádné pole pro zápis v rejstříku**
- `src/main/java/cz/palo/autoservis/model/domain/billing/CompanyProfile.java:24-35` a `model/dto/billing/CompanyProfileDto.java:15-66` — totéž
- `src/main/resources/db/migration/V34__invoice_party_snapshot.sql:34-56` — snapshot strany rovněž bez tohoto pole
- `src/main/resources/templates/pdf/invoice.html:53-70` (dodavatel), `credit-note.html:56-65`, `cash-receipt.html:41-51` — tiskne se jen název, adresa, IČ, DIČ

**Co je špatně:** §435 odst. 1 a 2 občanského zákoníku: každý podnikatel musí na obchodních
listinách (faktura je typická obchodní listina) uvádět jméno a sídlo; podnikatel zapsaný
v obchodním rejstříku navíc **údaj o tomto zápisu včetně oddílu a vložky**, podnikatel zapsaný
v jiném veřejném rejstříku údaj o tom zápisu, a nezapsaný podnikatel údaj o zápisu v jiné
evidenci (typicky živnostenský rejstřík). Bylo-li přiděleno IČO, uvádí se také. Aplikace nemá
kam tento údaj uložit ani jak ho vytisknout — **každá faktura, dobropis i PPD vystavený z této
aplikace tuto náležitost postrádá.**

**Scénář selhání:** Servis je s.r.o. zapsaná u KS v Ostravě, oddíl C, vložka 12345. Vystaví
fakturu — PDF nese jen „Autoservis XY s.r.o., IČ 12345678, DIČ CZ12345678". Chybí
„Zapsáno v OR vedeném KS v Ostravě, oddíl C, vložka 12345". Doklad je z pohledu DPH v pořádku,
ale nesplňuje §435 NOZ jako obchodní listina; při kontrole to je vytýkatelná vada a doplnit
zpětně na už vystavené a předané doklady to nejde.

**Proč to vadí:** právo. Zároveň to není opravitelné retrospektivně — u již vystavených dokladů
je snapshot neměnný.

**Návrh řešení:** migrací přidat `billing.company_profile.register_note VARCHAR(255)`
(volný text, ať pokryje OR i živnostenský rejstřík) a stejné pole do `billing.invoice_party`
(snapshotuje se s ostatní identitou dodavatele); doplnit do `CompanyProfileDto`, obrazovky
Nastavení firmy a do všech tří PDF šablon pod blok dodavatele. Zařadit do produkčního checklistu
TD-63/TD-33 (spolu s náhradou placeholderu „DOPLŇTE NÁZEV FIRMY").

*Zdroje: [§435 NOZ — kurzy.cz](https://www.kurzy.cz/zakony/89-2012-obcansky-zakonik/paragraf-435/), [HSP & Partners](https://www.akhsp.cz/novinky/povinnosti-souvisejici-s-podnikanim-informace-uvadene-na-obchodnich-listinach), ověřeno 30. 7. 2026.*

---

### [L-6] Aplikace zná jediný daňový režim — plátce DPH, tuzemské zdanitelné plnění
**Severita:** 🟠 STŘEDNÍ
**Jistota:** OVĚŘENO (technický fakt) / dopad závisí na provozu → **rozhodnutí uživatele**
**Kde:**
- `src/main/java/cz/palo/autoservis/model/domain/billing/CompanyProfile.java` a `V35:34-49` — žádný příznak „plátce DPH"; grep `vatPayer|vat_payer` přes `src/` nevrací nic
- `src/main/resources/templates/pdf/invoice.html:166-188` (sloupce „DPH %", „DPH"), `:204-221` (blok „Rekapitulace DPH"), `:226-240` („Základ daně", „DPH", „Celkem k úhradě") — tisknou se **vždy**, bezpodmínečně
- `src/main/resources/db/migration/V14__init_billing_schema.sql:64` — `vat_rate SMALLINT NOT NULL DEFAULT 21`; `V12:20` totéž pro položky zakázky; `frontend/.../OrderItemsWrapper.jsx:55,195` default „21"
- `src/main/java/cz/palo/autoservis/model/dto/billing/InvoiceDto.java:141-142` — `note` existuje, ale **netiskne se** (viz L-8), takže není kam umístit povinné formulace §29 odst. 2

**Co je špatně:** Tři chybějící režimy:
1. **Neplátce DPH.** Servis, který není plátcem, nesmí na dokladu vyčíslit daň — §108 odst. 4
   ZDPH ukládá tomu, kdo daň na dokladu uvede, povinnost ji přiznat a zaplatit, bez nároku na
   odpočet. Aplikace by po ručním přenastavení všech sazeb na 0 % sice nevyčíslila částku daně,
   ale doklad by dál nesl sloupec „DPH %", řádek „DPH: 0,00" a blok „Rekapitulace DPH" — což je
   terminologie plátce a pro neplátce zavádějící. Neplátce také nesmí uvádět DIČ jako plátce.
2. **Přenesená daňová povinnost.** §29 odst. 2 písm. c) vyžaduje na dokladu text
   **„daň odvede zákazník"** a doklad se vystavuje bez sazby a výše daně. Pro autoservis to je
   reálné u prodeje odpadu/šrotu (staré díly, autobaterie — příloha č. 5 ZDPH).
3. **Plnění pro zahraniční osobu povinnou k dani.** U opravy pro plátce z jiného členského státu
   je místo plnění v jeho státě (§9 odst. 1) → doklad bez české daně, opět s textem
   „daň odvede zákazník" a s jeho DIČ. `invoice_party.country_code` sice existuje, ale žádná
   logika ho nezohledňuje.

**Scénář selhání:** Servis vezme zakázku pro rakouského dopravce (plátce, ATU…). Vystaví fakturu
— aplikace přidá 21 % DPH a rekapitulaci. Servis odvede daň, kterou odvádět neměl, rakouský
odběratel si ji nemůže odpočíst a bude chtít opravu → nutný opravný doklad, který z UI nejde
vystavit (L-1).

**Proč to vadí:** právo + peníze. U neplátce navíc bezprostřední povinnost odvést nesprávně
vyčíslenou daň.

**Návrh řešení (rozhodnutí uživatele — podle toho, co servis reálně dělá):**
- **Minimum, doporučeno vždy:** doplnit `company_profile.vat_payer BOOLEAN NOT NULL DEFAULT TRUE`,
  snapshotovat na fakturu; je-li `false`, v šabloně vynechat sloupce DPH, rekapitulaci i řádek
  „DPH" a nahradit součet textem „Celkem k úhradě" + poznámkou „Nejsme plátci DPH".
- **Pokud servis fakturuje do zahraničí nebo prodává šrot:** přidat na hlavičku faktury pole
  „daňový režim" (tuzemsko / PDP / plnění mimo tuzemsko) a tisknout odpovídající zákonnou
  formulaci; sazba a výše daně se v těchto režimech neuvádí.
- **Pokud ne:** zdokumentovat omezení v nápovědě, ať se na to nenarazí za provozu.

*Zdroje: [§29 ZDPH — Portál POHODA](https://portal.pohoda.cz/dane-ucetnictvi-mzdy/dph/danovy-doklad/danovy-doklad-a-jeho-nalezitosti/), [§108 odst. 4 — dauc.cz](https://www.dauc.cz/clanky/9058/kdo-je-osobou-povinnou-priznat-nebo-zaplatit-dan), ověřeno 30. 7. 2026.*

---

### [L-7] Datum vystavení faktury zadává uživatel bez omezení a řídí číselnou řadu — lze zpětně vložit doklad do uzavřeného období
**Severita:** 🟠 STŘEDNÍ
**Jistota:** OVĚŘENO
**Kde:**
- `src/main/java/cz/palo/autoservis/model/dto/billing/InvoiceDto.java:124-131` — `issueDate`, `dueDate`, `taxableSupplyDate` mají jen `@NotNull`, žádné `@PastOrPresent` ani vzájemnou vazbu
- `src/main/java/cz/palo/autoservis/service/impl/InvoiceServiceImpl.java:66-174` — `createFromOrder` data nijak nevaliduje; `transitionTo` (`:421-451`) při vystavení také ne
- `src/main/resources/db/migration/V49__invoice_number_on_issue.sql:34` — `v_year_month := TO_CHAR(COALESCE(NEW.issue_date, CURRENT_DATE), 'YYYYMM');`, pořadové číslo = `MAX+1` v rámci **měsíce z issue_date** (`:39-42`)
- `src/main/resources/db/migration/V14__init_billing_schema.sql:49-50` — jediná datová kontrola je `chk_due_date CHECK (due_date >= issue_date)`
- `frontend/.../components/InvoiceCreateFormModal.jsx:66-83` — tři volná `<input type="date">` bez `min`/`max`

**Co je špatně:** Dvě různé cesty ke stejnému problému:
1. **Zpětné datování.** Uživatel může při zakládání konceptu zadat libovolně staré datum
   vystavení. Číslo se pak přidělí do řady toho starého měsíce.
2. **Zapomenutý koncept.** `issueDate` se nastaví při založení konceptu a při skutečném vystavení
   se **neaktualizuje**. Koncept založený 30. 6. a vystavený 5. 7. dostane číslo `202506nnn`
   a nese datum vystavení 30. 6.

**Scénář selhání:** 20. 7. 2026 obsluha založí fakturu a v poli „Datum vystavení" ponechá /
přepíše 15. 1. 2026. Leden 2026 měl 11 faktur (`202601001`–`202601011`). Trigger přidělí
`202601012` s datem vystavení 15. 1. 2026. Lednové přiznání i kontrolní hlášení jsou ale dávno
podané → doklad patří do uzavřeného zdaňovacího období a jeho DPH tam chybí. Řada `202601xxx`
navíc přestane být chronologicky souvislá: doklad s pořadím 012 vznikl o půl roku později než 011.
Očekávané chování: buď odmítnutí (uzamčené období), nebo aspoň varování.

**Proč to vadí:** právo + průkaznost. §29 odst. 1 písm. g) ZDPH požaduje **den vystavení** —
tedy skutečný, ne libovolný. §28 odst. 5 ukládá vystavit doklad do 15 dnů od vzniku povinnosti
přiznat daň. Číselná řada má být souvislá a časově konzistentní.

**Návrh řešení:**
1. Při přechodu `DRAFT → ISSUED` **přepsat `issue_date` na aktuální datum**, pokud se liší —
   datum vystavení je fakt okamžiku vystavení, ne přání z konceptu. *(Alternativa: nechat volbu
   na uživateli, ale pak povolit jen dnešek nebo pár dní zpět.)*
2. Zavést hranici uzavřeného období — buď jednoduše „issue_date nesmí být starší než N dnů"
   (`@PastOrPresent` + business kontrola v service, 422), nebo konfigurovatelné datum uzávěrky
   v `company_profile` (vzor: „uzamčení období" v Pohodě/Money).
3. Doplnit kontrolu vztahu DUZP ↔ datum vystavení (§28 odst. 5 — nejvýše 15 dnů) jako varování.
4. Na FE nastavit `max` u `issueDate`.

---

### [L-8] Poznámka faktury se nikdy nevytiskne na PDF
**Severita:** 🟠 STŘEDNÍ
**Jistota:** OVĚŘENO
**Kde:**
- `src/main/java/cz/palo/autoservis/model/dto/billing/InvoiceDto.java:56` (`private String note;` v `DetailResponse`), `:141-142` (`@Size(max = 2000)` v `CreateRequest`), `:162-163` (`UpdateRequest`)
- `src/main/resources/mapper/InvoiceMapper.xml` — `note` se ukládá i čte
- `frontend/.../pages/InvoicesPageDetail.jsx:168-170` — poznámka se zobrazí na detailu v aplikaci
- `src/main/resources/templates/pdf/invoice.html` — grep `note` v celé šabloně vrací **jen** CSS třídu `footer-note` na řádku 263; `${invoice.note}` se nikde nevykresluje

**Co je špatně:** Uživatel do faktury zadá poznámku (formulář ji nabízí, detail ji zobrazuje,
DB ji uchovává), ale na dokladu, který dostane zákazník a účetní, není. Poznámka je přitom
jediné volné pole na dokladu — a je to místo, kam patří povinné formulace podle §29 odst. 2
(„daň odvede zákazník", odkaz na ustanovení u osvobozeného plnění), stejně jako běžné doložky
(výhrada vlastnictví, záruka, odkaz na objednávku).

**Scénář selhání:** Obsluha napíše do poznámky „Zboží zůstává majetkem prodávajícího do úplného
zaplacení" a fakturu vystaví. PDF poznámku neobsahuje. Servis se domnívá, že výhradu vlastnictví
na dokladu uplatnil, ale doklad ji nenese. Stejně by dopadla jakákoli zákonná doložka.

**Proč to vadí:** provoz + právo (u §29 odst. 2 formulací přímo). Také jde o rozpor mezi UI
(pole je nabízené a zobrazované) a výstupem.

**Návrh řešení:** doplnit do `pdf/invoice.html` blok pod položky nebo nad patičku:
`<div class="party-line" th:if="${invoice.note}" th:text="${invoice.note}"></div>`.
Zvážit totéž u dobropisu (šablona `credit-note.html` žádné volné pole nemá).

---

### [L-9] Zaokrouhlení hotovosti nikde nesedí: faktura, PPD a evidence úhrady tvrdí tři různé částky
**Severita:** 🟠 STŘEDNÍ
**Jistota:** OVĚŘENO (technický fakt) / účetní řešení = **rozhodnutí uživatele**
**Kde:**
- `src/main/java/cz/palo/autoservis/service/impl/CashReceiptServiceImpl.java:64` — přijatá hotovost = `totalGross.setScale(0, HALF_UP)`
- `src/main/java/cz/palo/autoservis/model/converter/CashReceiptConverter.java:61-63` — `rounding = amount − totalGross`, tiskne se v `cash-receipt.html:113-118`
- `src/main/java/cz/palo/autoservis/service/impl/InvoiceServiceImpl.java:238` — `invoiceMapper.recordPayment(id, paid.getTotalGross(), ...)` — do `paid_amount` jde **nezaokrouhlená** částka
- `src/main/resources/templates/pdf/invoice.html:236-240` — „Celkem k úhradě" = `invoice.totalGross` na haléře; faktura nemá řádek zaokrouhlení ani jinou částku k úhradě

**Co je špatně:** Aplikace zaokrouhluje hotovost správně a na správném místě (PPD, mimo základ
daně — to odpovídá §36 odst. 5 ZDPH). Řetězec ale nedotahuje: faktura, kterou zákazník dostane
a podle které platí, dál žádá haléřovou částku, a evidence úhrady (`paid_amount`) zaznamená
peníze, které do pokladny nepřišly. Rozdíl se nikde nezachytí jako zaokrouhlovací rozdíl.

**Scénář selhání:** Faktura 6 105,23 Kč, hotovost. PPD zní na 6 105 Kč (rounding −0,23).
Obsluha klikne „Označit zaplaceno" → `paid_amount = 6105.23`, `paid_at = NOW()`.
V systému je pohledávka vyrovnaná na 6 105,23 Kč, v pokladně 6 105 Kč. Rozdíl 0,23 Kč
nefiguruje nikde — účetní ho musí dohledat porovnáním PPD s fakturou. Při desítkách hotovostních
faktur měsíčně to je trvale rozjetá pokladna vs. pohledávky.
Očekávané chování: faktura s hotovostní úhradou ukáže řádek „Zaokrouhlení" a zaokrouhlenou
částku k úhradě; `paid_amount` odpovídá skutečně přijaté částce.

**Proč to vadí:** peníze (v malém, ale systematicky) + průkaznost účetnictví — účetní doklad má
zachycovat skutečnou částku (§11 odst. 1 písm. d) ZoÚ).

**Návrh řešení (varianty, volba je na účetní):**
a) **Zaokrouhlovat už na faktuře**, je-li `paymentMethod` hotovostní (`CASH`, `CASH_OR_*`) —
   přidat řádek „Zaokrouhlení" a „Celkem k úhradě" v celých Kč; `recordPayment` pak zapíše
   zaokrouhlenou částku. Odpovídá běžné praxi českých fakturačních programů.
b) **Nechat fakturu na haléře** (převodem se platí přesně) a upravit jen `markPaid` tak, aby
   při hotovostní úhradě uložil zaokrouhlenou částku, a doplnit sloupec `rounding_amount`
   na fakturu, aby účetní měla rozdíl kde vidět.
Zaokrouhlení samotné daně (na koruny) je od 1. 4. 2019 nepřípustné — to aplikace **dodržuje**
(všechny výpočty jsou na 2 desetinná místa).

#### Doplněno 2026-07-30 po dohledání aktuálního znění (na vyžádání uživatele)

**Aktuální znění §36 odst. 5 ZDPH je od 1. 10. 2021 (novela č. 355/2021 Sb.) širší, než jsem výše
uvedl:** *„Do základu daně se nezahrnuje částka vzniklá zaokrouhlením celkové výše úplaty při dodání
zboží nebo poskytnutí služby na celou korunu."* Omezení **„při platbě v hotovosti" bylo z textu
vypuštěno** — vyjmutí ze základu daně tedy platí pro **všechny způsoby platby**, ne jen pro hotovost.
Do 30. 9. 2021 platila užší, hotovostní varianta.

⚠️ **Pozor na zastaralé zdroje.** Článek [dauc.cz — Pravidla zaokrouhlování dokladů](https://www.dauc.cz/clanky/9051/pravidla-zaokrouhlovani-dokladu)
dosud cituje **přednovelové** znění („…při platbě v hotovosti…") a tvrdí, že u karty a převodu
zaokrouhlení musí vstoupit do základu daně. To už neplatí. Rozpor byl rozhodnut ve prospěch novely
dvěma nezávislými zdroji (KPMG, Ing. Pavel Běhounek) — viz zdroje níže.

**Dva věcné limity, které platí i po novele:**
1. Zaokrouhlovat lze **jen na celou korunu** matematicky. Zaokrouhlení „dál" (např. na pětikoruny)
   se do základu daně zahrnout **musí** a daní se.
2. U bezhotovostní platby není důvod zaokrouhlovat vůbec (částku lze uvést na dvě desetinná místa);
   u hotovosti to naopak vyžaduje zákon o ochraně spotřebitele, protože haléřové mince neexistují.

**Důsledek, který v původním zápisu chyběl:** pokud se zaokrouhlení přesune na fakturu (varianta a),
musí zaokrouhlenou částku převzít i **QR platba** — `SpaydBuilder` dnes staví částku z `totalGross`.
Jinak by QR kód žádal jinou částku, než je na dokladu „Celkem k úhradě", což je horší než dnešní stav.
Zaokrouhlení proto musí být spočítané **na jednom místě** a odtud čtené fakturou, PDF, QR, PPD
i evidencí úhrady.

*Zdroje: [§36 odst. 5 / zaokrouhlování — Portál POHODA](https://portal.pohoda.cz/dane-ucetnictvi-mzdy/dph/jak-zaokrouhlovat-dle-zakona-o-dph/), [Finanční správa — Výpočet DPH a zaokrouhlování od 1. 10. 2019](https://financnisprava.gov.cz/cs/financni-sprava/novinky/novinky-2019/vypocet-dph-a-zaokrouhlovani), [KPMG — Zaokrouhlování po novele zákona o DPH](https://danovky.cz/cs/zaokrouhlovani-po-novele-zakona-o-dph), [Ing. Pavel Běhounek — Zaokrouhlování DPH](https://www.behounek.eu/l/zaokrouhlovani-dph/), ověřeno 30. 7. 2026. Auditor není daňový poradce — potvrdit s účetním.*

---

### [L-10] Vystavené doklady se neukládají — PDF se generuje znovu a jeho vzhled závisí na živých souborech v classpath
**Severita:** 🟠 STŘEDNÍ
**Jistota:** OVĚŘENO (technický fakt) / právní kvalifikace: **výklad sporný**, potvrdit s účetní
**Kde:**
- `src/main/java/cz/palo/autoservis/service/impl/InvoiceDocumentServiceImpl.java:35-51` — `renderPdf` pokaždé znovu vyrenderuje šablonu; nic se neukládá
- tamtéž `:81-99` — `loadLogoDataUri()` / `loadSignature()` čtou `/templates/images/logo.png` a `signature.png` z classpath **při každém renderu**
- `CreditNoteDocumentServiceImpl.java:26-34`, `CashReceiptDocumentServiceImpl.java:28-38` — totéž
- grep přes `src/main/resources/db/migration/` — jediný BYTEA sloupec pro doklad je `warehouse.goods_receipts.source_pdf` (V18:156, „Original PDF for tax archiving") — tedy jen pro **přijaté** doklady; pro **vydané** doklady žádné úložiště neexistuje

**Co je špatně:** Data dokladu jsou zmrazená správně (strany v `invoice_party`, položky
v `invoice_items`, vozidlo ve snapshotech), takže obsah je stabilní. Ale samotný dokument ne:
vymění-li se logo nebo obrázek podpisu, **změní se i PDF všech historických faktur**. A protože
se nic neukládá, závisí čitelnost dokladu po celou 10letou lhůtu na tom, že aplikace, šablona
i běhové prostředí pojedou. Aplikace navíc nemá žádný export dokladů (žádný CSV/XML/ISDOC
endpoint — grep přes `controller/` najde jen import příjemek).

**Scénář selhání:** Servis v roce 2027 změní logo. Správce daně si v roce 2029 vyžádá fakturu
z roku 2026 — vytištěné PDF nese logo z roku 2027, tedy jiný dokument než ten, který dostal
zákazník. Ještě ostřeji: aplikace se v roce 2030 vyřadí z provozu → doklady za roky 2026–2029
nejsou k dispozici v žádné podobě, jen jako řádky v databázi.

**Proč to vadí:** právo. §34 ZDPH požaduje po celou dobu uchovávání zajistit **věrohodnost
původu, neporušenost obsahu a čitelnost** daňového dokladu; §35/§35a stanoví uchovávání
10 let od konce zdaňovacího období. §31 ZoÚ ukládá uchovávat účetní doklady 5 let.
*(Nejistota: regenerace z neměnných dat je v praxi řadou účetních akceptovaná; sporné je právě
to, že vstupy renderu nejsou všechny zmrazené a že neexistuje žádný export mimo aplikaci.)*

**Návrh řešení:**
1. Krátkodobě a levně: obrázky logo/podpis snapshotovat spolu s ostatní identitou dodavatele
   (nebo je aspoň verzovat a nikdy nepřepisovat soubor pod původním jménem) — tím se odstraní
   jediný živý vstup do renderu.
2. Systémově: při přechodu `DRAFT → ISSUED` vygenerované PDF **uložit** (BYTEA nebo souborové
   úložiště) a `GET /{id}/pdf` u vystaveného dokladu servírovat uložený soubor, ne nový render.
   Stejně pro dobropis a PPD.
3. Doplnit dávkový export dokladů (ZIP s PDF + přehledový CSV/XML) — pro účetní i pro případ
   ukončení provozu aplikace.

*Zdroje: [§34, §35a ZDPH — Podnikatel.cz](https://www.podnikatel.cz/zakony/zakon-c-235-2004-sb-o-dani-z-pridane-hodnoty/f2548742/), [archivační lhůty — Money.cz](https://money.cz/novinky-a-tipy/ucetnictvi-2/archivacni-lhuty-dokladu-z-ucetniho-a-danoveho-hlediska/), ověřeno 30. 7. 2026.*

---

### [L-11] Osobní údaje: chybí výmaz/anonymizace, retenční lhůty a doložitelnost souhlasu
**Severita:** 🟠 STŘEDNÍ
**Jistota:** OVĚŘENO (technický fakt) / **NÁVRHOVÉ** v části právní kvalifikace
**Kde:**
- `src/main/resources/db/migration/V2__init_customer_schema.sql:41-59` — `first_name`, `last_name`, **`birth_date`**, `primary_email`, `primary_phone`, `internal_note`; `:53-56` `marketing_consent` / `gdpr_consent` jako prosté `BOOLEAN` + jeden timestamp
- tamtéž `:55-56` — `gdpr_consent BOOLEAN NOT NULL DEFAULT FALSE`, ale `gdpr_consent_at TIMESTAMPTZ NOT NULL DEFAULT NOW()` → záznam má „datum souhlasu" i tehdy, když souhlas není
- `src/main/resources/mapper/CustomerMapper.xml:334-339` — timestamp se přepíše při každé změně hodnoty; **historie souhlasů se neuchovává**
- `src/main/java/cz/palo/autoservis/service/impl/CustomerServiceImpl.java:164-181` — `deactivate` jen přepne `is_active`; grep `anonym|erasure|retenc` přes `src/` nevrací **nic** — v aplikaci neexistuje žádná cesta k výmazu ani anonymizaci osobních údajů
- `docs/konvence.md` R-06 — soft-delete je záměrné pravidlo projektu

**Co je špatně:** Model je postavený tak, že osobní údaje zákazníka v systému **zůstanou navždy**.
To je pro daňové doklady správně (faktura má vlastní neměnný snapshot v `invoice_party`), ale
kartu zákazníka to nezdůvodňuje: po uplynutí archivačních lhůt (5 let ZoÚ / 10 let ZDPH)
a promlčecích dob není právní titul pro další uchovávání jména, data narození, telefonu a e-mailu.
Tři konkrétní mezery:
1. **Žádná anonymizace.** Právo na výmaz (čl. 17 GDPR) nelze uplatnit ani technicky provést —
   `deactivate` data zachová.
2. **Souhlas jako jediný model.** Pole se jmenuje `gdpr_consent`, ale pro opravu vozidla je
   právním titulem plnění smlouvy (čl. 6 odst. 1 písm. b) GDPR), ne souhlas. Odvolání „souhlasu"
   pak logicky vyžaduje výmaz, který aplikace neumí — a přitom by výmaz byl u fakturačních dat
   v rozporu s archivační povinností. Model, kde je souhlas jen boolean bez znění a verze textu,
   navíc špatně doloží náležitosti čl. 7 odst. 1 GDPR.
3. **`birth_date` bez účelu.** Pole je nabízené a ukládané, ale nikde v aplikaci se nepoužívá
   (faktura ho nepotřebuje) — sbírá se údaj bez účelu, proti zásadě minimalizace (čl. 5 odst. 1
   písm. c) GDPR).

**Scénář selhání:** Zákazník po 12 letech napíše: „Přeji si výmaz svých údajů." Servis nemá
v aplikaci žádnou akci, kterou by to provedl — jediné, co umí, je deaktivace, po níž jméno,
datum narození, telefon i e-mail zůstanou v databázi a v exportech. Žádost nelze vyřídit
ani doložit vyřízení.

**Proč to vadí:** právo (GDPR), riziko stížnosti u ÚOOÚ. Zároveň provoz — databáze roste
o data, která nemá kdo použít.

**Návrh řešení (rozhodnutí uživatele, s účetní/právníkem):**
1. Sepsat **retenční politiku**: co se maže/anonymizuje a kdy (např. karta zákazníka bez zakázky
   po 3 letech; zákazník s fakturami až po 11 letech od poslední faktury — faktura má vlastní
   snapshot, takže anonymizace karty jí neublíží).
2. Doplnit akci **anonymizace zákazníka** (přepis jména na „Anonymizováno #id", vynulování
   `birth_date`, kontaktů, `internal_note`, adres) s guardem na běžící zakázky. Technicky je to
   bezpečné — `billing.invoice_party` je nezávislý snapshot (V34).
3. Oddělit **titul zpracování** od **marketingového souhlasu**: `gdpr_consent` přejmenovat na
   „poučení o zpracování předáno" (nebo zrušit) a evidovat historii marketingového souhlasu
   (tabulka `customer_consents` s datem, kanálem a verzí textu) — to je jediné, co se skutečně
   odvolává.
4. `birth_date` buď zdůvodnit a použít, nebo z formuláře odstranit.

---

### [L-12] QR platba se tiskne i na stornovanou a zaplacenou fakturu (a nápověda tvrdí opak)
**Severita:** 🟡 NÍZKÝ
**Jistota:** OVĚŘENO
**Kde:**
- `src/main/java/cz/palo/autoservis/service/impl/SpaydBuilder.java:23-32` — jediné podmínky jsou `invoiceNumber != null` a vyplněný IBAN; **stav faktury se nekontroluje**
- `src/main/java/cz/palo/autoservis/service/impl/InvoiceDocumentServiceImpl.java:45` — QR se vkládá do kontextu vždy
- `src/main/resources/templates/pdf/invoice.html:16-20` (banner „STORNOVÁNO") a `:255-269` (QR) — obojí se vykreslí současně
- `frontend/.../pages/InvoicesPageDetail.jsx:107-110` — tlačítko PDF je dostupné u všech stavů
- `frontend/autoservis-frontend/src/help/faktury.md:24` — „QR je navíc jen na **vystavené** faktuře" — nesouhlasí s kódem

**Co je špatně:** Stornovaná faktura si ponechá číslo i variabilní symbol (V49 je přiděluje při
vystavení a storno je nemaže), takže její PDF nese funkční QR platbu na plnou částku vedle
červeného nápisu STORNOVÁNO. Totéž u už zaplacené faktury.

**Scénář selhání:** Faktura 202607010 je stornována (např. špatný odběratel). Obsluha zákazníkovi
pošle PDF „pro pořádek". Zákazník naskenuje QR v mobilním bankovnictví a zaplatí zrušený doklad.
Servis pak řeší vrácení platby.

**Proč to vadí:** peníze (v malém) + rozpor kódu s nápovědou (podle briefu je rozpor
dokumentace vs. kód sám o sobě nález).

**Návrh řešení:** v `SpaydBuilder.build` vrátit `null`, není-li stav faktury `ISSUED`
(pro `DRAFT` už to platí implicitně přes chybějící číslo). Doplnit test na `CANCELLED`/`PAID`
(`SpaydBuilder` byl vyčleněn právě proto, aby šel obsah testovat). Opravit `help/faktury.md:24`.

---

### [L-13] Sazba DPH je volné číslo 0–100 — doklad může nést neexistující sazbu
**Severita:** 🟡 NÍZKÝ
**Jistota:** OVĚŘENO
**Kde:**
- `src/main/resources/db/migration/V14__init_billing_schema.sql:75` — `CHECK (vat_rate >= 0 AND vat_rate <= 100)`
- `src/main/resources/db/migration/V12__init_order_item_schema.sql:30` — totéž pro položky zakázky
- `frontend/.../components/OrderItemFormModal.jsx:73-74` — `<input type="number" name="vatRate" min="0" max="100">` (volné pole, ne výběr)
- `src/main/resources/db/migration/V18__init_warehouse_schema.sql:116-117` — `products.default_vat_rate` stejně volné

**Co je špatně:** V ČR jsou k roku 2026 sazby **21 %**, **12 %** a **0 %** (od 1. 1. 2024 se
15 % a 10 % sloučily do 12 %; pro rok 2026 beze změny). Aplikace přijme jakoukoli hodnotu 0–100,
takže překlep („15" místo „12", „2" místo „21") projde až na vystavený, neměnný daňový doklad.

**Scénář selhání:** Mechanik zadá u položky sazbu 15 %. Faktura se vystaví s rekapitulací
„15 % | 1 000,00 | 150,00". Sazba 15 % v ČR neexistuje → vadný daňový doklad; oprava vyžaduje
opravný doklad (který z UI nejde vystavit — L-1).

**Proč to vadí:** právo (§29 odst. 1 písm. k) — sazba daně) + provoz.

**Návrh řešení:** na FE nahradit číselné pole výběrem (21 / 12 / 0) a na backendu validovat
proti číselníku (`CodeListController` už existuje) → 400 při jiné hodnotě. DB CHECK
neměnit — historické doklady musí projít; whitelist patří do aplikační validace, aby šel
při změně sazeb rozšířit bez migrace.

*Zdroj: [Sazby DPH 2026 — jakpodnikat.cz](https://www.jakpodnikat.cz/dph-sazby.php), ověřeno 30. 7. 2026.*

---

### [L-14] Snapshot dodavatele se bere při založení konceptu, ne při vystavení (rozpor s komentářem V35)
**Severita:** 🟡 NÍZKÝ
**Jistota:** OVĚŘENO
**Kde:**
- `src/main/java/cz/palo/autoservis/service/impl/InvoiceServiceImpl.java:136-155` — `companyProfileMapper.find()` a vložení řádku `SUPPLIER` do `invoice_party` probíhá uvnitř `createFromOrder`, tedy při vzniku **konceptu**
- `src/main/resources/db/migration/V35__company_profile_and_supplier_backfill.sql:27-28` — komentář tvrdí: „Nové faktury berou dodavatele z aktuálního stavu této tabulky **v okamžiku vystavení**"
- `V35:70` — seed obsahuje placeholder `'DOPLŇTE NÁZEV FIRMY'` (sleduje TD-33/TD-63)

**Co je špatně:** Dokumentace v migraci a kód se rozcházejí. Prakticky: opraví-li se profil firmy
mezi založením konceptu a jeho vystavením, koncept si nese starou (možná placeholderovou)
identitu a ta se zmrazí do vystaveného dokladu.

**Scénář selhání:** Po nasazení někdo založí koncept faktury dřív, než vyplní Nastavení firmy.
Do `invoice_party` se uloží dodavatel „DOPLŇTE NÁZEV FIRMY" bez IČO a DIČ. Druhý den se profil
doplní, ale koncept už má starý snapshot — po vystavení je to neměnný daňový doklad
s nesmyslným dodavatelem a bez DIČ, tedy bez náležitostí §29 odst. 1 písm. a) a b).

**Proč to vadí:** právo (vadný doklad, neopravitelný) — byť s nízkou pravděpodobností a řešitelné
produkčním checklistem.

**Návrh řešení:** buď snapshot dodavatele přesunout do `transitionTo(ISSUED)` (odpovídalo by
komentáři a je věcně správnější — identita platí k datu vystavení), nebo při vystavení řádek
`SUPPLIER` přepsat aktuálním profilem. V obou případech přidat guard „nelze vystavit fakturu,
dokud profil firmy obsahuje placeholder / nemá vyplněné IČO". Pokud se rozhodne pro současné
chování, opravit komentář ve V35.

---

### [L-15] PDF konceptu dobropisu nemá varovný pruh a data se tisknou v ISO formátu
**Severita:** 🟡 NÍZKÝ
**Jistota:** OVĚŘENO
**Kde:**
- `src/main/resources/templates/pdf/credit-note.html:10` a `:21` — `th:text="${cn.creditNoteNumber} ?: 'NÁVRH'"` — koncept se vytiskne jako „OPRAVNÝ DAŇOVÝ DOKLAD č. NÁVRH", **bez** červeného pruhu, který má faktura (`invoice.html:11-15` „NÁVRH — NENÍ DAŇOVÝ DOKLAD")
- `credit-note.html:36-42` — `th:text="${cn.issueDate}"` a `th:text="${cn.taxableSupplyDate}"` bez `#temporals.format` → vytiskne se `2026-01-01` místo `01.01.2026` (faktura i PPD formátují, `invoice.html:143`, `cash-receipt.html:29`)
- `credit-note.html` — bez loga a bez pole pro razítko/podpis (faktura i PPD je mají)

**Co je špatně:** Tři kosmetické, ale na dokladu viditelné odchylky od zbytku aplikace.
Koncept dobropisu lze omylem předat jako platný doklad; české datum v ISO formátu na účetním
dokladu působí jako chyba.

**Scénář selhání:** Obsluha založí dobropis, otevře PDF, aby si ho zkontrolovala, a rovnou ho
pošle zákazníkovi — dokument nikde neříká, že jde o návrh, jen má u čísla slovo „NÁVRH".
U faktury by ho zastavil červený rámeček přes celou šířku.

**Proč to vadí:** provoz + srozumitelnost dokladu (žádná přímá zákonná vada).

**Návrh řešení:** převzít z `invoice.html` blok s červeným pruhem podmíněný `cn.status == DRAFT`;
data obalit `#temporals.format(..., 'dd.MM.yyyy')`; doplnit logo a blok razítka pro konzistenci
s ostatními doklady.

---

### [L-16] Komentář v `IsdocParser` uvádí špatné kódy typů dokladu a chybějící `DocumentType` projde jako faktura
**Severita:** 🟡 NÍZKÝ
**Jistota:** OVĚŘENO
**Kde:**
- `src/main/java/cz/palo/autoservis/service/IsdocParser.java:108-120` — komentář „Dobropis (**5**) a vrubopis (**6**)"; podle číselníku ISDOC 6.0.2 je **2 = opravný daňový doklad (dobropis)**, **3 = opravný daňový doklad (vrubopis)**, 5 = daňový doklad při přijetí platby, 6 = opravný doklad k DZL, 7 = zjednodušený daňový doklad
- tamtéž `:113-114` — `if (type != null && !DOCUMENT_TYPE_INVOICE.equals(type.trim()))` → chybí-li element `DocumentType` úplně, dokument projde jako faktura

**Co je špatně:** Funkčně je guard bezpečný (projde jen `1`), ale komentář uvádí do omylu každého,
kdo bude fázi E5b (přijaté dobropisy) implementovat — snadno se odmítne/přijme špatný typ.
Druhá věc: `DocumentType` je v ISDOC povinný element; jeho absence je známkou vadného souboru
a měla by skončit odmítnutím, ne tichým zpracováním jako faktura.

**Scénář selhání:** Vývojář podle komentáře přidá podporu „dobropisu = typ 5" — naimportuje
daňový doklad k přijaté platbě jako dobropis a naskladní/odepíše špatné množství.
Druhý scénář: dodavatel pošle ořezaný XML bez `DocumentType`; parser ho vezme jako fakturu
a naskladní zboží z dokladu, který fakturou být nemusí.

**Proč to vadí:** budoucí chyba + průkaznost skladové evidence (mírné).

**Návrh řešení:** opravit komentář podle číselníku ISDOC 6.0.2; `requireSupportedDocumentType`
zpřísnit tak, aby chybějící `DocumentType` skončil `BusinessRuleException`
`ISDOC_UNSUPPORTED_DOCUMENT_TYPE` (nebo 400 „není platný ISDOC").

*Zdroj: [ISDOC 6.0.2 — národní standard](https://isdoc.github.io/doc/isdoc.pdf), číselník DocumentType; ověřeno 30. 7. 2026.*

---

## Srovnání s praxí (Pohoda, Money S3, iDoklad, Fakturoid, ABRA Flexi)

**Co máme lépe než většina.** Zmrazení stran a vozidla do snapshotu je čistší, než jak to řeší
řada malých fakturačních nástrojů (ty často jen odkazují na kartu odběratele a doklad se zpětně
mění). Číselné řady s advisory lockem a per-období resetem, guardované stavové přechody
a append-only skladový ledger jsou úroveň, kterou má z jmenovaných spolehlivě jen Pohoda/ABRA.
Vazba faktura ↔ zakázka ↔ vozidlo (VIN/SPZ na dokladu) je oborová výhoda, kterou obecné
fakturační programy nemají.

**Co mají oni a chybí nám — a chybí to.**
1. **Opravný daňový doklad jako běžná akce na dokladu.** Ve všech jmenovaných je „vystavit
   dobropis" tlačítko vedle faktury; u nás to je jen API (L-1). To je největší rozdíl.
2. **Uzamčení období / zákaz zpětného datování.** Pohoda i Money mají uzávěrku období; u nás
   lze doklad vložit do libovolného měsíce (L-7).
3. **Předání dat účetní.** Všechny jmenované umí export (ISDOC, XML, CSV, přímý import do
   Pohody/Money/Flexi) a podklady pro kontrolní hlášení. My umíme ISDOC jen **číst** (import
   příjemek), ne psát, a nemáme žádný export vydaných dokladů — účetní by data přepisovala
   ručně. Pro reálný provoz je to blokující, i když ne protizákonné.
4. **Režimy DPH** (neplátce, PDP, plnění mimo tuzemsko) — standard i v iDokladu a Fakturoidu (L-6).
5. **Zálohové faktury a daňový doklad k přijaté platbě** — u autoservisu běžné (záloha na díl);
   u nás nejsou vůbec.
6. **Náležitost §435 NOZ** (zápis v rejstříku) je v jmenovaných programech součástí nastavení
   firmy; u nás pole neexistuje (L-5).
7. **Tisk inventurního soupisu** s podpisovými poli — u nás jen obrazovka, a ta po uzavření
   rozdíly ztratí (L-4).

**Co děláme jinak a je to obhajitelné.** PPD jako samostatná evidovaná entita s vlastní řadou
(místo „faktury naležato") je správnější než u některých nástrojů. Ocenění zásob po skutečných
šaržích s FIFO výdejem je konzistentní s §49 vyhlášky 500/2002 Sb. (FIFO je jedna ze dvou
přípustných metod pro oceňování úbytků) a přesnější než průměrování — navíc jednotně použité
i pro rozpouštění manka. Rozhodnutí účtovat inventurní přebytek bez DPH v reprodukční ceně
(ČÚS 007) je zdokumentované a věcně správné.

**Čeho se dnes bát nemusíme.** EET je od 1. 1. 2023 zrušená a v roce 2026 neplatí; nová
„EET 2.0" se připravuje s účinností od 1. 1. 2027 a bude se týkat kontaktních plateb (hotovost,
karta, QR) — pro autoservis s hotovostní pokladnou to bude relevantní, ale ne dřív než 2027.
([MF ČR](https://www.mfcr.cz/cs/dane-a-ucetnictvi/dane/danova-a-celni-legislativa/2023/elektronicka-evidence-trzeb-50868),
[iROZHLAS 4. 5. 2026](https://www.irozhlas.cz/ekonomika/eet-20-2026-2027-navrat-elektronicka-evidence-trzeb_2605041248_ako))

---

## Co bylo ověřeno jako v pořádku

**Náležitosti daňového dokladu (§29 ZDPH)** — faktura obsahuje: označení i DIČ obou stran
(`invoice.html:53-90`), evidenční číslo (`:35`), rozsah a předmět plnění (položky `:163-188`),
den vystavení a DUZP (`:142-152`), jednotkovou cenu bez daně (`:181`), základ daně, sazbu i výši
daně (`:182-185`, `:204-240`). Rekapitulace DPH po sazbách je samostatný blok. **Jediné, co §29
požaduje a nemáme, je sleva** (není-li v jednotkové ceně) — a slevu aplikace jako pojem nezná,
takže se promítá do jednotkové ceny; to je přípustné.

**DUZP vs. datum vystavení vs. splatnost** — tři oddělené sloupce (`V14:27-29`), všechny
`NOT NULL`, všechny se tisknou, `chk_due_date` hlídá `due_date >= issue_date`. Toto je často
chybějící a tady je to správně.

**Výpočet a zaokrouhlení daně** — DPH se počítá po řádcích na 2 desetinná místa
(`V32`, `V37`, `InvoiceItemMapper.xml:83-95`), rekapitulace i součty používají **stejné**
zaokrouhlení, takže vždy sedí na haléř. Zaokrouhlování daně na koruny (od 1. 4. 2019
nepřípustné) se nikde neděje.

**Zaokrouhlení hotovosti mimo základ daně** — `CashReceiptServiceImpl:61-64` + PPD řádek
„Zaokrouhlení" odpovídá §36 odst. 5 ZDPH (rozpis DPH z faktury se nemění). Věcně správné;
problém je jen v návaznosti na fakturu a `paid_amount` (L-9).

**Neměnnost vystaveného dokladu** — `InvoiceMapper.update` má `AND status = 'DRAFT'`,
`InvoiceItemMapper` insert/update/delete jsou guardované na DRAFT, `updateStatus` je guardovaný
na očekávaný stav. Vystavenou fakturu nelze editovat ani přes API.

**Číselné řady** — faktura `{YYYYMM}###`, dobropis `OD{YYYYMM}###`, PPD `PPD{YYYYMM}###`,
inventura `INV-{rok}-{4č.}`, zakázka `ZAK-{rok}-{4č.}`, zákazník `ZNK-{rok}-{4č.}` — vzájemně
nezaměnitelné, přidělované DB triggerem pod advisory lockem, s guardem proti přetečení,
`UNIQUE` na čísle. **Koncepty čísla nespotřebovávají** (V49), takže vystavená řada je souvislá;
stornovaný doklad si číslo ponechá, což je správně (žádné díry). Offsety `SUBSTRING` u všech tří
řad jsem přepočítal a sedí (7 / 9 / 10).

**Označení konceptu a storna na faktuře** — `invoice.html:11-20` tiskne „NÁVRH — NENÍ DAŇOVÝ
DOKLAD" a „STORNOVÁNO". Koncept navíc nemá číslo ani VS, takže je nezaměnitelný.

**§45 náležitosti dobropisu** — šablona `credit-note.html` nese označení „OPRAVNÝ DAŇOVÝ DOKLAD",
vlastní evidenční číslo, evidenční číslo původního dokladu (`:32`), důvod opravy (`:35`),
obě strany s DIČ (`:56-75`) a rozdíly základu, daně i celkové částky po sazbách (`:82-107`).
Věcně tedy §45 odst. 1 pokrývá. *(Lhůtu 15 dnů pro vystavení a doručení aplikace nehlídá —
to je procesní věc obsluhy, ne vada dokladu.)*

**§11 náležitosti PPD** — označení a číslo, datum vyhotovení, obsah a účastníci (příjemce
i plátce), částka číslem i slovy, účel platby, pole „Schválil"/„Podpis". `AmountInWords`
jsem přečetl celý — skloňování měrových slov i tvary 1/2 v pozici tisíců/milionů jsou správně,
rozsah je ohraničený výjimkou nad miliardu.

**Limit plateb v hotovosti** — 270 000 Kč denně mezi týmiž osobami (zákon č. 254/2004 Sb.).
Pro autoservis prakticky nedosažitelný; kontrolu v aplikaci nepovažuji za nutnou, ale je to
otevřená otázka níže.

**Zjednodušený daňový doklad** — aplikace ho nevystavuje a vždy vydá plný daňový doklad.
To je vždy přípustné (limit 10 000 Kč je možnost, ne povinnost).

**Oceňování zásob** — `v_stock_valuation` (V42) počítá hodnotu ze zbytků šarží × jejich
**skutečné pořizovací ceny bez DPH**, výdej i rozpouštění manka jde FIFO
(`StockTakeMapper.findBatchesForShortage` řadí `gr.issue_date ASC, gri.id ASC`). §25 ZoÚ + §49
vyhlášky 500/2002 Sb. připouští pro úbytky vážený aritmetický průměr **nebo FIFO** — použitá
metoda je přípustná a v celé aplikaci jednotná (což §49 rovněž vyžaduje: jeden způsob na
analytický účet).

**Inventurní přebytek** — bez DPH, v reprodukční pořizovací ceně, jako výnos (ČÚS 007), doklad
typu `STOCK_TAKE` bez dodavatele. Zdokumentované a správné.

**Doklad o příjmu zboží** — `warehouse.goods_receipts.source_pdf` archivuje originál přijatého
dokladu (V18:156) — u přijatých dokladů tedy archivace řešena je (na rozdíl od vydaných, L-10).

**SPAYD / QR platba** — řetězec `SPD*1.0*ACC:<IBAN>*AM:<částka>*CC:CZK*X-VS:<VS>*MSG:<text>`
odpovídá standardu ČBA: `ACC` je povinné a je ve tvaru IBAN (BIC volitelný), `AM` je desetinné
číslo s tečkou a 2 místy, `CC` je ISO 4217, `X-VS` je celé číslo do 10 znaků (naše VS = číslo
faktury, 9 číslic), `MSG` je oříznuto na 60 znaků a zbaveno diakritiky i oddělovače `*`.
Částka se bere z `totalGross` faktury, takže QR a doklad nesou totéž. *(Drobnost mimo nález:
`AM` má limit 10 znaků, což vyčerpá částka od 10 000 000,00 Kč výš — pro autoservis nereálné.)*
Zdroj: [qr-platba.cz — specifikace formátu](https://qr-platba.cz/pro-vyvojare/specifikace-formatu/).

**ISDOC import** — struktura odpovídá ISDOC 6.0.2 (čtou se lokální názvy, takže projde i 5.x),
XXE je ošetřeno (`disallow-doctype-decl`, prázdné `ACCESS_EXTERNAL_*`), nečitelná data končí
jako `ABSENT` a dopočítává je až kód („AI čte, kód počítá"). Jednotky UN/ECE Rec. 20 se mapují
na náš číselník a neznámý kód projde k ručnímu posouzení.

**EET** — správně neřešeno, v roce 2026 neexistuje.

**Nový zákon o účetnictví** — v roce 2026 neúčinný; aplikace se řídí 563/1991 Sb., což je
k dnešku správně.

---

## Otevřené otázky pro uživatele

1. **Je servis plátcem DPH?** Odpověď rozhoduje, jestli je L-6 blokující (neplátce nesmí
   vystavit doklad, který aplikace dnes generuje), nebo jen budoucí rozšíření.
2. **Fakturuje servis do zahraničí nebo prodává šrot/odpad?** Pokud ano, je potřeba režim
   přenesené daňové povinnosti a plnění mimo tuzemsko (L-6). Pokud ne, stačí to zdokumentovat.
3. **Storno vystavené faktury — zakázat úplně, nebo nechat s varováním?** (L-1) Praxe se liší:
   část servisů storno používá pro doklad zachycený před předáním. Doporučení: nechat, ale
   s explicitním potvrzením a s dobropisem jako nabízenou alternativou.
4. **Zaokrouhlovat hotovostní fakturu už na faktuře, nebo až na PPD?** (L-9) Varianta a)
   odpovídá běžné praxi českých programů; varianta b) je menší zásah. Rozhodne účetní.
5. **Retenční politika osobních údajů** (L-11): po jaké době od poslední zakázky/faktury se má
   karta zákazníka anonymizovat? Návrh k odsouhlasení: 11 let od poslední faktury (10 let ZDPH
   + rezerva), u zákazníka bez faktur 3 roky.
6. **Má aplikace hlídat limit hotovosti 270 000 Kč?** Při vystavení PPD nad limit by šlo
   zobrazit varování. Pro autoservis pravděpodobně zbytečné — rozhodnutí uživatele.
7. **Export dat pro účetní** — v jaké podobě je účetní chce (ISDOC, XML pro Pohodu/Money,
   CSV, jen PDF)? Bez toho bude ostrý provoz znamenat ruční přepisování.
8. **Uzamčení účetního období** — má aplikace tvrdě bránit vystavení dokladu do už uzavřeného
   měsíce (L-7), nebo stačí varování? Doporučení: konfigurovatelné datum uzávěrky v profilu firmy.
9. **Připravenost na EET 2.0 (od 1. 1. 2027)** — pokud servis přijímá hotovost a platby kartou
   na provozovně, bude se ho evidence týkat. Zařadit do roadmapy jako samostatnou položku,
   až bude znám finální text zákona.
