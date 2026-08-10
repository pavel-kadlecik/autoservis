# Audit 6/9 — Věcná a procesní správnost domény

> Součást hloubkového auditu 2026-07-24 (commit `409d3ad`, větev `audit-one`).
> Přehled celého auditu: [00-prehled.md](00-prehled.md).
>
> Posouzení **skutečného chování kódu**, ne dokumentace. Klíčová tvrzení ověřena v service impl,
> migracích, XML mapperech, PDF šabloně a frontendu. U legislativních tvrzení uveden konkrétní
> paragraf a přiznána nejistota tam, kde jde o výklad.

---

## A. Věcné chyby

### A1. 🔴 VYSOKÝ — Stornovaná faktura trvale zablokuje opětovnou fakturaci zakázky
`V14:40` plný `UNIQUE (order_id)`; `InvoiceServiceImpl.createFromOrder` kontroluje `findByOrderId(...).isPresent()` bez ohledu na stav. Nejběžnější oprava (faktura s chybou → storno → oprava → nová faktura) **nefunguje**: storno odemkne položky (`OrderItemServiceImpl:319` filtruje CANCELLED — systém s další editací počítá), ale novou fakturu už vystavit nelze. Odemykání položek je půl workflow, jehož druhá půlka chybí. Oprava: partial unique + filtr. *(Klíčový nález K-1 — shodně DB N-1, backend V-2, SQL №… )*

### A2. 🔴 VYSOKÝ — Chybí opravný daňový doklad (dobropis); storno vystavené faktury je legislativně nedostatečné
`InvoiceStatus.java:23-28` — jen DRAFT → ISSUED → PAID/CANCELLED; typ „opravný daňový doklad" v billing modulu neexistuje. §42/§45 ZDPH: oprava základu daně u **vystaveného** dokladu se dělá opravným dokladem, ne interním zrušením. **PAID je terminální** — zaplacenou fakturu nejde opravit vůbec, přitom reklamace po zaplacení (vrácení dílu, sleva) je nejčastější reálný důvod dobropisu. Aplikace neumí běžný účetní případ. *(Klíčový nález K-8.)*

### A3. 🟠 STŘEDNÍ–VYSOKÝ — PDF „faktury" lze vytisknout pro DRAFT i CANCELLED bez odlišení
`InvoiceDocumentController.java:31-39` (žádná kontrola stavu); `invoice.html` stav nečte (žádný vodoznak); FE `InvoicesPageDetail.jsx:96` PDF tlačítko bezpodmínečně. Číslo + VS přiděluje trigger už při INSERTu draftu. Draft je od ostré faktury nerozlišitelný — zaměstnanec ho může předat zákazníkovi. Oprava: vodoznak „NÁVRH"/„STORNOVÁNO". *(Shodně backend S-11.)*

### A4. 🟠 STŘEDNÍ — Datum vystavení se fixuje při založení draftu; „Vystavit" ho nemění (a nejde opravit)
`issue_date` povinné v `CreateRequest:119-120`; `UpdateRequest:145-159` ho neobsahuje; `transitionTo` mění jen `status`. Draft z pátku vystavený v pondělí nese páteční datum. Číslo řady z `CURRENT_DATE` při INSERTu se rozejde s issue_date. Lhůtu §28/5 ZDPH (15 dnů od DUZP) nic nehlídá. Oprava: `issue()` má datum razítkovat / validovat; DUZP editovatelné v DRAFTu. *(Souvisí s K-3.)*

### A5. 🟠 STŘEDNÍ — API nekontroluje, že vozidlo patří zákazníkovi zakázky
`OrderServiceImpl.create:86-91` — žádná validace `customerId ↔ vehicle.customerId`. FE filtruje autocomplete „optionally". Kontrast: faktura vlastnictví adresy hlídá. Přes API vznikne zakázka (a doklad) na cizí vozidlo. *(Shodně backend V-3.)*

### A6. 🟠 STŘEDNÍ — Zakázka nemá stavový automat; stav je editovatelné pole
`OrderStatus.java` holý enum; `UpdateRequest:97-98` přijme libovolný status; `update` nevaliduje. `COMPLETED → RECEIVED`, `CANCELLED → IN_PROGRESS` cokoliv. `CANCELLED` **nevrací materiál na sklad**; plně vyfakturovanou zakázku lze označit CANCELLED, faktura zůstane ISSUED. Hlavička (vč. `final_price`) editovatelná i po fakturaci — zamčené jen položky → `final_price` se rozjede od vyfakturované částky. *(Roadmapa Order Phase 2; shodně backend S-12.)*

### A7. 🟠 STŘEDNÍ — Lze vystavit fakturu s nulou položek
`createFromOrder` položky vyžaduje, ale v DRAFTu jdou smazat (`deleteItem:365`) a `issue():197-202` je nekontroluje. Vystavený doklad na 0 Kč bez předmětu plnění — §29/1 ZDPH. *(Shodně backend S-2.)*

### A8. 🟡 NÍZKÝ — Stav tachometru smí klesnout bez kontroly či varování
`MileageServiceImpl.addReading:62-88` — monotonie se nekontroluje ani jako warning. Překlep 15 000 místo 150 000 projde a trigger přepíše cache. Km legitimně „klesne" po výměně budíků → ne tvrdý zákaz, ale soft-warning na místě. *(Shodně backend N-3.)*

### A9. 🟡 NÍZKÝ — Přetečení měsíční řady faktur (>999) způsobí kolizi čísla
`V15` `LPAD(seq, 3, '0')` — řetězec delší než 3 znaky se **ořízne** („1000"→„100"). Tisící faktura v měsíci → unique violation (500). Prakticky nedosažitelné, ale bez chybové hlášky.

### A10. 🟡 NÍZKÝ — `gdpr_consent_at NOT NULL DEFAULT NOW()` i při `gdpr_consent = FALSE`
`V2:55-56`. Systém eviduje razítko „souhlasu", který nebyl udělen. *(Shodně DB N-16.)*

### A11. 🟡 NÍZKÝ — Faktura nenese údaj o zápisu v obchodním rejstříku / živnostenské evidenci
`company_profile` (V35) pole nemá; `invoice.html` nezobrazuje. §435 NOZ vyžaduje na obchodních listinách údaj o zápisu (oddíl/vložka), u OSVČ o zápisu do jiné evidence. Chybí i e-mail/telefon dodavatele.

---

## B. Mezery v procesech (s prioritami)

### Priorita 1 — brání reálnému nasazení

**B1. Evidence úhrad neexistuje.** `markPaid` je jediný bit; nikde `paid_at`, částka úhrady, skutečný způsob, doklad o platbě. Nejde částečná úhrada, přeplatek, „kdy zaplatil". Chybí přehled pohledávek po splatnosti (dueDate se eviduje, nevyhodnocuje) a upomínky. Pro fakturující servis první věc, co začne bolet. *(Klíčový nález K-9.)*

**B2. Pokladna / hotovost.** `payment_method = CASH` existuje, ale žádný příjmový pokladní doklad ani pokladní kniha. Souvisí s B1.

**B3. Přejímka vozidla a schválení kalkulace.** Zakázka nemá: stav km při přejímce (`CreateRequest` km nemá — přitom `mileage_history` se source=SERVICE existuje a proces zakázky ho neplní!), přejímací protokol, podpis/souhlas s kalkulací, evidenci navýšení ceny. §2612 NOZ: zhotovitel musí oznámit podstatné překročení odhadu, jinak nemá na doplatek nárok — `estimated_price` je, proces „zákazník odsouhlasil navýšení" chybí.

**B4. GDPR — právo na výmaz a retence.** Jen soft-delete; žádná anonymizace, konflikt „výmaz vs. archivace daňových dokladů 10 let (§35 ZDPH)" neřešen. Správná odpověď = anonymizace zákaznických polí s ponecháním fakturačních snapshotů (`invoice_party` to umožňuje — snapshoty jsou oddělené). Dnes žádost subjektu údajů nejde splnit jinak než zásahem do DB.

**B5. Role neodpovídají provozu a autorizace je plochá.** Jediné rolové brány: `UserController` (ADMIN), import/review (všichni tři). MECHANIC může vystavovat/stornovat faktury, měnit company profile, uzavírat inventuru. Chybí recepce/přejímací technik a účetní. **Nejhorší: seed V3 obsahuje portálové účty `jan.novak`/`firma.logistika` s ROLE_CUSTOMER a sdíleným `Password1!`, a protože `SecurityConfig:78` pouští každého autentizovaného na všechna `/api/**`, „zákazník" vidí a edituje celou firmu.** TD-24/TD-11 to evidují jako dluh — před produkcí je to KRITICKÉ. *(Klíčový nález K-10.)*

### Priorita 2 — provoz omezují citelně

**B6. Notifikace zákazníkům.** READY_FOR_PICKUP nikoho neinformuje; SMS/e-mail neexistuje. Tabulka `customer_communications` (V2) je **mrtvé schéma** (žádný mapper/service/endpoint). `loyalty_points`: pole ve všech DTO, ale nic je nepřičte/neodečte. Dodělat, nebo smazat (vlastní pravidlo projektu).

**B7. Kontaktní osoby a adresy po založení needitovatelné.** Kontaktní osoby jen read-only join ze seedu (žádný CRUD); adresy = TD-42 🔴. Provozní důsledek vážnější: zákazník se přestěhuje → fakturační adresa nejde změnit → nové faktury nesou špatnou adresu (a A1 znemožňuje opravu stornem).

**B8. Termíny a kapacita.** Jen `estimated_completion_at`; žádný kalendář, plánování mechaniků, stání. Pro >2 mechaniky se plánuje mimo systém.

**B9. Inventurní protokol.** §29–30 zákona o účetnictví vyžadují inventurní soupisy (s podpisy); inventura umí jen obrazovku (přiznáno v dokumentaci). Pro doložitelnost tisk nutný.

**B10. Objednávání dílů a rezervace.** Vědomě nezařazeno; přehled „pod minimem" je slušná náhrada — odklad OK. Bez rezervací ale hrozí, že díl přijatý pro zakázku A vydá mechanik na B.

**B11. Číselné řady natvrdo.** Formáty zadrátované v triggerech (V9/V11/V15); nejde vlastní řada, víc fakturačních řad (hotovost vs. převod), ani offset při migraci z jiného systému.

**B12. E5b — přijatý dobropis skladu.** Odklad zdůvodněný (R-G); vratka s `credit_note_number` stopu drží. Bolí středně: skladově sedí, finančně je dobropis neviditelný. Stačil by přehled „vratky čekající na dobropis".

**B13. Kniha jízd / náhradní vozidla, zálohové faktury** — neexistují; pro cílový rozsah přijatelné.

---

## C. Diskutabilní rozhodnutí (trade-off)

**C1. Faktura ↔ zakázka 1:1.** PRO: jednoduchost, snapshot, provenience. PROTI: fleet zákazník chce souhrnnou fakturu; dělená fakturace vyžaduje umělé zakázky. Pro malý servis obhajitelné, zdokumentované.

**C2. Výdej na zakázku je dokladocentrický, ne dílocentrický.** Mechanik vybírá doklad dodavatele a z něj položky. FIFO předvýběr existuje jen u ručních pohybů a inventury; u výdeje na zakázku FIFO nikdo nedrží. PRO: perfektní dohledatelnost. PROTI: u skladem drženého zboží (oleje, filtry) starší šarže leží, ocenění se rozjede od fyzické rotace. Zaslouží revizi po prvních měsících.

**C3. Fallback prodejní ceny = nákupní cena.** `OrderItemServiceImpl:201-204`: bez `sale_price` položka dostane prodejní = nákupní → **servis tiše prodává s nulovou marží** bez varování. Minimálně UI upozornění nebo konfigurovatelná přirážka by chránily peníze majitele.

**C4. Potvrzení příjemky s `reconciliation_ok = false` dovoleno.** `validateCompleteness` kontroluje úplnost, ne aritmetiku. PRO: člověk má poslední slovo. PROTI: „zkontroloval a rozhodl" vs. „přehlédl" odlišuje jen barva v UI. Explicitní checkbox „potvrzuji přes nesedící kontrolu" by stál málo.

**C5. PDF faktury se nearchivuje — vždy re-render z dat.** Snapshoty drží obsah, ale šablona/logo/podpis/zaokrouhlovací logika jsou živé — dokument za rok nemusí být týž. §34 ZDPH (věrohodnost původu, neporušenost obsahu); u „elektronicky vystavil a poslal PDF" je uložení vyrenderovaného PDF (vzor: `source_pdf` u příjemek už BYTEA archivaci má) bezpečnější.

**C6. „gdpr_consent" jako jediný bool.** Zpracování pro plnění smlouvy souhlas **nevyžaduje** (čl. 6/1/b GDPR) — vyžadovat ho je matoucí a při odvolání neproveditelné. Marketing_consent je naopak správně. Souhlas vázat na účel.

**C7. Zaokrouhlování DPH po řádcích** (views). Legální (§37 ZDPH), běžná ERP praxe, chvályhodně konzistentní napříč zakázkou/fakturou/rekapitulací. Účetní to musí znát.

**C8. Storno příjemky jen dokud se nečerpalo; inventura nezamyká sklad.** Správně konzervativní (R-C, R-H). Drobnost: `StockTakeServiceImpl.close` bez FOR UPDATE má malé okno.

**C9. SPAYD/QR platba** — správně (IBAN, AM 2 des., CC:CZK, X-VS, sanitizovaný MSG, vynechaný BIC se zdůvodněním); chybí jen `X-DT` (datum splatnosti). Kosmetika.

**C10. STK registr** — návrhově nejčistší část (append-only snapshoty, cache triggerem, best-effort/strict, defenzivní mapování). Riziko stáří dat poctivě přiznané a plánované (noční job). Dává provozní smysl.

---

## D. Celkové zhodnocení doménového návrhu

**Silné stránky.** Skladová doména je na poměry výukové aplikace nadstandardně správná (append-only ledger, šarže jako nositel ceny, kompenzační storna, inventura s FIFO rozpouštěním manka, „AI čte, kód počítá"). Snapshot architektura faktur (`invoice_party`, immutable) je přesně to, co právní doklad vyžaduje. Rozhodnutí jsou zdokumentovaná vč. zavržených alternativ.

**Slabé místo je proces, ne data.** Aplikace výborně eviduje *stavy*, ale hůř *přechody a jejich důsledky*: faktura má automat, ale bez dobropisu (A2), bez razítkování data vystavení (A4) a s jednosměrnou pastí po stornu (A1 — nejvážnější); zakázka automat nemá vůbec (A6); platba je jeden bit (B1). Symptomaticky: tři nejdůležitější dokumenty reálného servisu — **přejímací protokol, souhlas s kalkulací, doklad o zaplacení** — v systému neexistují, zatímco skladová vratka má propracovaný důvodový číselník. Investice šla tam, kde byla intelektuálně zajímavá (sklad, AI import), ne tam, kde provoz krvácí nejdřív.

**Legislativně** je jádro daňového dokladu (§29 ZDPH) splněno: obě strany s IČ/DIČ, číslo, datum vystavení, DUZP, rozpis základů a daně po sazbách, rekapitulace. Chybí náležitosti listiny §435 NOZ (A11), režim neplátce, opravné doklady (A2), inventurní soupis (B9); archivace PDF diskutabilní (C5).

### Roadmapa vs. realita — priority neodpovídají tomu, co provozu chybí nejvíc

1. **Billing nemá žádnou další fázi** — končí „✅ Rekapitulace DPH". Dobropis, evidence úhrad a oprava pasti A1 v roadmapě nefigurují, přitom jsou nejvyšší provozní rizika. Doporučeno „Billing Phase 5: opravné doklady + úhrady" s předností před vším ostatním.
2. **Order Phase 2 (stavový automat)** správně identifikovaná — hned po billingu, spolu s přejímkou (km při příjmu, infrastruktura existuje) a polem mechanika.
3. **Vehicle Phase 2 (číselník značek)** a **Dashboard** jsou kosmetika — až po B1–B5.
4. **Sklad**: E1–E8 vzorně; E5b povýšit jen na „přehled vratek čekajících na dobropis".
5. **Před produkcí:** seed hesla a ROLE_CUSTOMER účty (B5 — kritické), CORS/company_profile (TD-33), audit log položek (spory o cenu — B3), GDPR výmaz (B4).

Úhrnem: datový model je připraven na správný servis lépe než procesní vrstva nad ním. Většina A1–A7 jsou lokální opravy v řádu dní — ne přestavba, ale dotažení přechodů tam, kde stavy už existují.
