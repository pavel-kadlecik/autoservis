# 07 — Doména a procesy: věcná správnost provozu servisu

> Audit 2026-07-30 · rozsah: end-to-end průchod daty a stavy tak, jak je dělá skutečný servis
> (zákazník → vozidlo → zakázka → materiál a práce → faktura → platba → PPD; reklamace, storno,
> změna majitele, GDPR, čekání na díl, historie vozu, dashboard, zaměstnanci, protokoly)
> · metoda: čtení celých souborů service vrstvy, mapperů, migrací, DTO a odpovídajících obrazovek
> frontendu; každý nález ověřen podruhé přímo v kódu (hledání guardu výš, DB CHECK, triggeru, testu)
> · **žádný test nebyl spouštěn** (vyžadují Docker), závěry jsou statické.

## Co bylo přečteno

**Povinná četba:** `CLAUDE.md`, `docs/konvence.md`, `docs/tech-dluhy.md`, `docs/roadmapa.md`.

**Funkční dokumenty (`docs/funkce/`):** `dashboard.md`, `zakazky-prehled.md`, `zakazky-marze.md`,
`zamestnanci.md`, `prijmovy-pokladni-doklad.md`, `inventura.md`, `sklad-pohyby.md`
(+ částečně `import-prijemek.md`, `stk-registr.md`, `sprava-uzivatelu.md` přes grep na relevantní pasáže).

**Backend — service:** `OrderServiceImpl`, `OrderItemServiceImpl`, `InvoiceServiceImpl`,
`CreditNoteServiceImpl`, `CashReceiptServiceImpl`, `CustomerServiceImpl`, `VehicleServiceImpl`,
`EmployeeServiceImpl`, `DashboardServiceImpl`, `MileageServiceImpl`, `StockTakeServiceImpl`.

**Backend — ostatní:** `mapper/DashboardMapper.xml` (celý), `mapper/OrderMapper.xml` (celý),
`mapper/EmployeeMapper.xml` (celý), `model/converter/OrderConverter.java`,
`model/converter/EmployeeConverter.java`, `model/dto/dashboard/DashboardDto.java`,
`model/dto/order/OrderDto.java`, `model/dto/order/OrderSearchParams.java`,
`model/dto/customer/CustomerDto.java`, `model/dto/employee/EmployeeDto.java`,
`model/domain/billing/CreditNote.java`, enumy `OrderStatus`, `InvoiceStatus`, `MovementType`,
`MileageSource`, controllery `OrderController`, `EmployeeController`, `InvoiceController` (výběrově).

**Migrace:** `V14__init_billing_schema.sql`, `V48__invoice_order_partial_unique.sql`,
`V51__invoice_payment_record.sql`, `V55__init_credit_notes.sql`, `V57__init_cash_receipts.sql`,
`V35__company_profile_and_supplier_backfill.sql` (tabulka), `V2__init_customer_schema.sql` (GDPR sloupce),
seznam všech migrací a triggerů nad `"order".orders`.

**Frontend:** `App.jsx` (routy), `components/navigation.js`, `pages/OrdersPageDetail.jsx`,
`pages/OrdersPageEdit.jsx`, `pages/VehiclesPageDetail.jsx`, `pages/InvoicesPageDetail.jsx`,
`pages/EmployeesPageEdit.jsx`, `components/OrderForm.jsx`, `components/OrderItemsWrapper.jsx`,
`components/OrderItemFormModal.jsx`, `components/ImportProductFormModal.jsx`,
`components/InvoiceCreateFormModal.jsx`, `components/EmployeeTable.jsx`, `api/employeePayload.js`,
`help/zakazky.md`; adresáře `pages/`, `components/`, `help/`, `templates/pdf/`.

---

## Shrnutí

Jádro provozu — **zákazník → vozidlo → zakázka → položky → faktura → platba → PPD** — je funkční
a v detailech promyšlené: vazba zakázka↔vozidlo↔zákazník se při založení kontroluje, položky se po
vystavení faktury zamknou, výdej ze skladu jde proti konkrétní šarži s `FOR UPDATE`, smazání položky
vrací díl zpět, snapshoty na faktuře jsou úplné, inventura i ruční pohyby jsou postavené správně
(append-only ledger). Stav `WAITING_FOR_PARTS` existuje, takže „čekání na díl" zakázka vyjádřit umí.

Slabina není v hlavní ose, ale ve **vedlejších cestách, které skutečný servis potřebuje denně**:
oprava chyby po vystavení faktury (dobropis je hotový v backendu, ale **z aplikace nedostupný**),
zrušení rozpracované zakázky (**nevrací materiál a nic nevaruje**), pokladní doklad
(**lze vystavit opakovaně na plnou částku a nejde ho zrušit**), servisní historie vozu
(**neexistuje ani endpoint, ani obrazovka**) a doklady pro zákazníka
(**přijímací/předávací protokol chybí úplně**). Dashboard počítá to, co dokumentace tvrdí,
a popisky „(s DPH)" / „(bez DPH)" jsou v UI poctivé — jedinou dírou je, že **dobropis do čísel
nevstupuje vůbec**.

Nálezů: **0 kritických, 0 vysokých, 6 středních, 9 nízkých**. Žádný nález neblokuje provoz,
ale šest z nich by v ostrém provozu vedlo k dokladům nebo číslům, které neodpovídají skutečnosti.

---

## Nálezy

### [P-1] Dobropis (opravný daňový doklad) je hotový v backendu, ale z aplikace se k němu nedá dostat
**Severita:** 🟠 STŘEDNÍ
**Jistota:** OVĚŘENO
**Kde:** `src/main/java/cz/palo/autoservis/controller/CreditNoteController.java:29` (`/api/{version}/credit-notes`),
`src/main/resources/templates/pdf/credit-note.html`, `docs/api.md:172-181`
vs. `frontend/autoservis-frontend/src/pages/InvoicesPageDetail.jsx:105-137` (výčet všech akcí na detailu faktury)
a `frontend/autoservis-frontend/src/App.jsx:46-99` (výčet všech rout).

**Co je špatně:** Backend umí založit dobropis, vystavit ho (řada `OD{YYYYMM}###`, V55) a vytisknout
PDF. Ve frontendu k tomu **není nic** — žádné tlačítko, žádná routa, žádná položka v menu
(`components/navigation.js`). Rekurzivní hledání řetězců `credit-note` / `creditNote` / `dobropis`
ve `frontend/autoservis-frontend/src/` vrací jen skladovou vratku (`StockMovementModal.jsx:26`
— *číslo dobropisu od dodavatele*, jiná věc) a nápovědu ke skladu. Jediná viditelná akce nad
vystavenou fakturou je **„Stornovat"** (`InvoicesPageDetail.jsx:127-135`).

**Scénář selhání:** Zákazník se vrátí týden po opravě: díl byl vadný, dohodne se sleva 2 000 Kč.
Faktura `202607001` je ISSUED a zákazník ji má doma. Obsluha otevře detail faktury a má na výběr:
PDF, Pokladní doklad, Označit zaplaceno, **Stornovat**. Stiskne „Stornovat" — doklad, který zákazník
fyzicky drží a který už je v účetnictví, se v aplikaci překlopí na CANCELLED a zmizí z tržeb.
Správná cesta (opravný daňový doklad podle §45 ZDPH) v aplikaci existuje, ale obsluha se k ní
nedostane.

**Proč to vadí:** Právo/účetnictví — vystavený daňový doklad se v ČR opravuje opravným daňovým
dokladem, ne zpětnou změnou stavu; přesně tohle konstatuje i vlastní analýza projektu
(`docs/analyza-2026-07.md:74-75`: *„…před reálným provozem: CANCELLED u ISSUED/PAID faktury nahradit
vystavením dobropisu"*). Zároveň je to plýtvání — hotová a otestovaná funkce (`CreditNoteServiceTest`)
je pro uživatele neviditelná. V `tech-dluhy.md` je jako odložený evidován jen **částečný** dobropis
(TD-62), chybějící UI zaznamenané není.

**Návrh řešení:** Na detail faktury (stav ISSUED/PAID) přidat akci „Opravný doklad" — modal
s `correctionReason` → `POST /credit-notes` → `POST /credit-notes/{id}/issue` → otevřít
`GET /credit-notes/{id}/pdf` (stejný vzor, jaký už funguje u PPD, `InvoicesPageDetail.jsx:54-64`).
Zvážit, zda po zavedení dobropisu neomezit „Stornovat" jen na stav DRAFT — *rozhodnutí uživatele*
(viz Otevřené otázky).

---

### [P-2] Zrušení zakázky je pouhá změna stavu — nevrací materiál, nevaruje a projde i u vyfakturované zakázky
**Severita:** 🟠 STŘEDNÍ
**Jistota:** OVĚŘENO
**Kde:** `src/main/java/cz/palo/autoservis/service/impl/OrderServiceImpl.java:109-121` (celá metoda `update`),
`src/main/java/cz/palo/autoservis/model/converter/OrderConverter.java:94` (`existingOrder.setStatus(updateRequest.getStatus())`),
`src/main/resources/mapper/OrderMapper.xml:129-139` (UPDATE bez jakéhokoli guardu na stav).
Kontrola opačným směrem existuje: `InvoiceServiceImpl.java:76-81` brání *fakturovat* stornovanou zakázku.

**Co je špatně:** `OrderServiceImpl.update` načte zakázku, přepíše na ni stav z requestu a uloží.
Není tam **žádná** kontrola — ani na pořadí stavů, ani na existující fakturu, ani na už vydaný
materiál. Nad `"order".orders` neexistuje jiný trigger než `trg_orders_updated_at` (V6:64-65)
a `trg_generate_order_number` (V11:25-26), takže guard není ani na DB.

**Scénář selhání (a) — materiál:** Zakázka ZAK-2026-0042, obsluha naimportovala z příjemky
4 tlumiče (vznikly `ISSUE` pohyby, `quantity_remaining` šarže klesl). Zákazník couvne. Obsluha
podle nápovědy (`help/zakazky.md:28` — *„když z opravy sešlo, dáte stav Zrušena"*) přepne stav na
CANCELLED. Díly fyzicky leží zpátky v regálu, ale sklad je má trvale jako vydané: hodnota skladu je
podhodnocená, „pod minimem" hlásí falešný poplach a nákupčí objedná znovu. Aplikace neřekne ani
slovo — přitom cesta k nápravě existuje (smazat položky → `ISSUE_RETURN`,
`OrderItemServiceImpl.java:269-283`), jen ji nikdo nenavrhne. Rozdíl vyplave až při inventuře jako
přebytek.

**Scénář selhání (b) — faktura:** Zakázka má vystavenou fakturu `202607001` (ISSUED). Obsluha
omylem přepne stav zakázky na „Zrušena" — projde. Vznikne platný daňový doklad odkazující na
zrušenou zakázku; `countOrdersToInvoice` ji přestane vidět, ale `sumMargin`
(`DashboardMapper.xml:188-193`) ji dál počítá, protože joinuje přes fakturu bez ohledu na stav
zakázky. Zpět už to nikdo nepozná — kdy a proč byla zrušena, se nikde neeviduje.

**Proč to vadí:** Data a peníze — trvalý rozjezd evidence skladu proti realitě a doklad navěšený
na zrušenou zakázku. Provoz — obsluha nedostane šanci se rozhodnout, protože nedostane informaci.

**Návrh řešení:** V `OrderServiceImpl.update` při přechodu na `CANCELLED`:
(1) odmítnout 422, existuje-li faktura ve stavu ≠ CANCELLED (stejný dotaz, jaký už používá
`OrderItemServiceImpl.requireOrderNotInvoiced`, jen s opačným umístěním);
(2) existují-li položky s `goods_receipt_item_id`, buď je vrátit v téže transakci (`ISSUE_RETURN`,
kód už je hotový), nebo vrátit 422 s výčtem, co je potřeba nejdřív vrátit — *rozhodnutí uživatele*,
která z variant. Souvisí s roadmapou (Order Phase 2 — stavový automat), ale tyhle dva guardy jsou
cross-modulové a samotný automat stavů je nepřinese.

---

### [P-3] Pokladní doklad lze k jedné faktuře vystavit opakovaně, vždy na plnou částku — a nejde zrušit ani zobrazit
**Severita:** 🟠 STŘEDNÍ
**Jistota:** OVĚŘENO
**Kde:** `src/main/java/cz/palo/autoservis/service/impl/CashReceiptServiceImpl.java:64`
(`BigDecimal amountReceived = summary.getTotalGross().setScale(0, RoundingMode.HALF_UP);` — vždy celá částka faktury),
`src/main/resources/db/migration/V57__init_cash_receipts.sql:16-17` (*„Bez unique na invoice_id — k jedné faktuře může
vzniknout víc PPD (dílčí hotovostní úhrady)"*), tabulka V57:30-44 nemá sloupec stavu ani `updated_at`,
`frontend/autoservis-frontend/src/pages/InvoicesPageDetail.jsx:54-64` a `:112-117` (tlačítko bez jakékoli kontroly
existujících dokladů).

**Co je špatně:** Migrace i funkční dokument (`docs/funkce/prijmovy-pokladni-doklad.md:24`)
odůvodňují chybějící unikátnost tím, že umožňuje **dílčí** hotovostní úhrady. Kód dílčí částku
neumí — částku nelze zadat, vždy se ukládá celý `totalGross` faktury zaokrouhlený na koruny.
Tentýž dokument o dvanáct řádků níž (`:33`) říká pravý opak: *„Částečné úhrady zatím ne — PPD =
plná částka faktury."* Výsledkem je, že jediné, co „víc PPD k faktuře" v praxi umožňuje, je
**duplicitní doklad na tutéž částku**. Doklad navíc nemá stav ani mazací endpoint (V57 nemá
`status`, `CashReceiptController` má jen create/get/list/pdf), takže vzniklý duplikát je trvalý.
Seznam už vystavených PPD sice existuje (`GET /cash-receipts?invoiceId=`, `docs/api.md:191`),
ale frontend ho nikde nevolá — obsluha duplikát ani neuvidí.

**Scénář selhání:** Zákazník platí 12 100 Kč v hotovosti. Obsluha klikne „Pokladní doklad",
otevře se PDF `PPD202607005`, tiskárna ale vyhodí prázdný list. Obsluha klikne znovu → vznikne
`PPD202607006`, opět na 12 100 Kč. V pokladní knize jsou dvě po sobě jdoucí čísla souvislé řady,
obě „přijato 12 100 Kč" k téže faktuře; jeden z nich nejde stornovat ani smazat a v aplikaci není
obrazovka, kde by ho někdo uviděl. Účetní najde rozdíl 12 100 Kč mezi pokladnou a bankou/tržbami.

**Proč to vadí:** Peníze a účetnictví — neanulovatelný duplicitní účetní doklad v souvislé číselné
řadě. Zároveň dokumentace slibuje funkci (dílčí úhrady), kterou kód neumí.

**Návrh řešení:** Minimum: před vytvořením zavolat `getByInvoiceId` a při existujícím dokladu buď
vrátit 409, nebo v UI ukázat seznam už vystavených PPD s potvrzením („k této faktuře už existuje
PPD202607005 na 12 100 Kč — opravdu vystavit další?"). Zároveň sjednotit dokumentaci: buď doplnit
zadání částky (a tím dílčí úhrady skutečně umožnit), nebo z `funkce/prijmovy-pokladni-doklad.md:24`
a z hlavičky V57 odůvodnění „dílčí úhrady" odstranit.

---

### [P-4] Dobropis se nikde nepromítne do čísel — plně dobropisovaná faktura zůstane navždy „po splatnosti" a v tržbách
**Severita:** 🟠 STŘEDNÍ
**Jistota:** OVĚŘENO
**Kde:** `src/main/resources/mapper/DashboardMapper.xml:98-101` (`overdueInvoicesWhere` — jen `status='ISSUED' AND due_date < CURRENT_DATE`),
`:140-153` (`sumRevenue` — `status IN ('ISSUED','PAID')`), `:206-245` (`findMonthlyStats` — totéž);
`src/main/java/cz/palo/autoservis/service/impl/CreditNoteServiceImpl.java:37-65` a `:67-90`
(vytvoření ani vystavení dobropisu se stavu faktury nedotýká);
`src/main/java/cz/palo/autoservis/model/domain/billing/CreditNote.java:20-22`
(*„Rozdílové částky a identifikace stran se neukládají"*);
`src/main/resources/db/migration/V55__init_credit_notes.sql:41` — `CREATE INDEX` (ne UNIQUE) na `original_invoice_id`.

**Co je špatně:** Dobropis je v datovém modelu úplně oddělený od faktury: nemá vlastní částky,
nemění stav původní faktury a žádný dashboardový ani statistický dotaz s ním nepočítá. Navíc
`createFromInvoice` nekontroluje, zda k faktuře už dobropis existuje, a DB unikátnost nevynucuje —
k jedné faktuře lze vystavit libovolný počet **plných** dobropisů (MVP = plný dobropis,
`V55:15`).

**Scénář selhání:** Faktura `202607001` na 12 100 Kč, splatnost 10. 7. Zákazník reklamuje, servis
vystaví plný dobropis `OD202607001` a peníze vrátí. Faktura zůstává ISSUED (nezaplacená — a taky už
nikdy zaplacená nebude). Od 11. 7. ji dashboard trvale ukazuje v dlaždici „faktury po splatnosti"
včetně 12 100 Kč v KPI „pohledávky po splatnosti", ve „Tržbách tento měsíc" je započtená plná částka
a v modalu Statistika ji nese měsíční i roční součet. Obsluha bude tu fakturu urgovat u zákazníka,
kterému už peníze vrátila. Číslo tržeb za měsíc je o 12 100 Kč vyšší, než kolik servis skutečně
utržil.

**Proč to vadí:** Peníze a provoz — nadhodnocené tržby, falešná pohledávka a chybné upomínání.
Nedokumentované: `docs/funkce/dashboard.md` (rozhodovací tabulka, ř. 28-45) o dobropisech nemluví
vůbec, takže uživatel nemá jak vědět, že tržba dobropis nezohledňuje.

**Návrh řešení:** (1) Do `sumOverdueInvoices` a `findOverdueInvoicesPreview` přidat
`AND NOT EXISTS (SELECT 1 FROM billing.credit_notes cn WHERE cn.original_invoice_id = i.id AND cn.status = 'ISSUED')`.
(2) Pro tržby a marži rozhodnout, zda dobropis odečítat (u plného dobropisu = vyloučit fakturu z
období), nebo číslo nechat a doplnit popisek — *rozhodnutí uživatele*, protože účetně se dobropis
projeví v období svého vystavení, ne původní faktury. (3) Do `createFromInvoice` doplnit guard proti
druhému dobropisu k téže faktuře (dokud je MVP „plný dobropis"), ideálně i partial unique index
`WHERE status <> 'CANCELLED'` vzorem `uq_invoices_order_active` (V48).

---

### [P-5] Servisní historie vozidla ani zákazníka není nikde dostupná — a v API pro ni ani není cesta
**Severita:** 🟠 STŘEDNÍ
**Jistota:** OVĚŘENO
**Kde:** `src/main/java/cz/palo/autoservis/model/dto/order/OrderSearchParams.java:14-38`
(jen `status`, `overdue` a zděděné `search`/stránkování — žádné `vehicleId`/`customerId`),
`src/main/resources/mapper/OrderMapper.xml:68-103` (`WhereClause` filtr na vozidlo/zákazníka nemá) a
`:229-243` (jediné dotazy podle vozidla/zákazníka jsou `countOpenBy*` — vracejí jen počet),
`src/main/java/cz/palo/autoservis/controller/OrderController.java` (žádný endpoint podle vozidla),
`frontend/autoservis-frontend/src/pages/VehiclesPageDetail.jsx:146-328` (detail vozu: metriky, identifikace,
technické parametry, vlastník, STK, poznámka, metadata, historie tachometru — **zakázky nikde**),
`frontend/autoservis-frontend/src/pages/CustomersPageDetail.jsx:43-44` a `:217`
(načítá jen `/customers/{id}` a `/customers/{id}/vehicles`; jediná tabulka je „Vozidla zákazníka"),
`frontend/autoservis-frontend/src/pages/OrdersPage.jsx:17-48` (nečte URL parametry — `useSearchParams` chybí,
na rozdíl od `InvoicesPage.jsx:15-18`).

**Co je špatně:** Na zakázky se lze dotázat jen fulltextem přes `GET /orders?search=`. Filtr podle
konkrétního vozidla nebo zákazníka v API neexistuje a ani jedna detailní obrazovka na zakázky
neodkazuje. `GET /invoices/customer/{customerId}` v backendu je (`InvoiceController.java:182-185`),
ale frontend ho nevolá nikde.

**Scénář selhání:** Přijede Octavia 4A2 3344, zákazník tvrdí, že spojku měnili „loni na jaře u vás"
a chce reklamaci. Obsluha otevře detail vozidla — vidí VIN, tachometr, STK a majitele, ale ani jednu
zakázku. Musí si SPZ zapamatovat, přejít na Zakázky a přepsat ji do vyhledávacího pole (deep-link
`/orders?search=4A2 3344` stránka nepodporuje). Fulltext `OrderMapper.xml:90-99` SPZ i VIN pokrývá,
takže výsledek nakonec dostane — jenže bez řazení podle data a bez faktur, a u vozu bez SPZ
(`license_plate` je nullable) musí sáhnout po VIN. Stejná situace u zákazníka s pěti auty: „kolik
u nás nechal za poslední rok" nezjistí vůbec.

**Proč to vadí:** Provoz — servisní historie vozu je nejčastěji potřebná informace u pultu
(reklamace, „co jsme tam dělali posledně", odhad příští údržby). Dnes je dosažitelná jen oklikou,
kterou uživatel nemá odkud vědět.

**Návrh řešení:** Doplnit `vehicleId` a `customerId` do `OrderSearchParams` a do sdíleného
`WhereClause` (dvě `<if>` větve, promítnou se i do `countSearch`), na detail vozidla a zákazníka
přidat kartu „Zakázky" s prokliky, na detail zákazníka i „Faktury" přes existující
`GET /invoices/customer/{id}`. Malý zásah, velký provozní zisk.

---

### [P-6] Chybí přijímací a předávací protokol — servis nemá jediný podepsatelný doklad o převzetí vozu a o odsouhlasené ceně
**Severita:** 🟠 STŘEDNÍ
**Jistota:** NÁVRHOVÉ
**Kde:** `src/main/resources/templates/pdf/` obsahuje právě tři dokumenty — `invoice.html`,
`credit-note.html`, `cash-receipt.html` (+ `invoice-styles.html`); tiskový výstup zakázky neexistuje
(`frontend/autoservis-frontend/src/pages/OrdersPageDetail.jsx:68-74` — jediná akce na detailu zakázky je „Editovat").
Ani `docs/roadmapa.md` (Order Phase 2 = stavový automat + `order_type`) protokol neplánuje.

**Co je špatně:** Aplikace umí vytisknout jen doklady *o penězích*. Zakázkový list, přijímací
protokol (stav vozu, tachometr, palivo, výbava, viditelná poškození, podpis zákazníka) ani předávací
protokol v aplikaci nejsou a nejsou ani v plánu.

**Scénář selhání:** Zákazník si vyzvedne vůz a druhý den volá, že na dveřích je škrábanec, který
tam při předání nebyl. Servis nemá nic, čím by doložil stav vozu při převzetí — v aplikaci je jen
volný „Popis zakázky". Druhý typický případ: zákazník tvrdí, že souhlasil s opravou za 6 000 Kč,
faktura je na 11 000 Kč. V aplikaci není nikde zaznamenáno, že a kdy zákazník s navýšením souhlasil
(viz též [P-8]) — jen `estimated_price`, které nikdo nepodepsal.

**Proč to vadí:** Právo a peníze — přijímací protokol je v autoservisu praktický základ smluvního
vztahu a jediná obrana v tomhle typu sporu. Mechanik navíc nemá co vzít k vozu (dnes musí zakázku
opsat nebo si ji vyfotit z obrazovky).

**Návrh řešení:** Nejlevnější varianta s největším přínosem: **tisk zakázkového listu** jako PDF
ze zakázky (stejný vzor jako faktura: Thymeleaf šablona + `PdfRenderer`) — hlavička zákazník/vozidlo,
tachometr při příjmu, popis požadavku, odhad ceny a termín, položky, místo pro podpis „souhlasím
s provedením prací". Předávací protokol může být tentýž dokument v druhém tisku po dokončení.
Míra jistoty: vysoká, že to servis potřebuje; *rozhodnutí uživatele*, jak podrobný protokol chce
(prostý zakázkový list vs. strukturovaný soupis poškození).

---

### [P-7] Materiál na zakázku jde vydat jen přes číslo příjemky, ne přes díl
**Severita:** 🟡 NÍZKÝ
**Jistota:** OVĚŘENO
**Kde:** `frontend/autoservis-frontend/src/components/ImportProductFormModal.jsx:72-79`
(jediný vstup je našeptávač nad `/api/v1/warehouse/goods-receipts/autocomplete`) a `:83-85`
(„Načíst položky" je aktivní až po výběru dokladu);
`src/main/resources/mapper/warehouse/GoodsReceiptMapper.xml:43-83` — našeptávač hledá výhradně
podle `gr.invoice_number` / `gr.order_number`, nikdy podle názvu nebo katalogového čísla dílu;
`docs/funkce/sklad-pohyby.md:60-61` to potvrzuje: *„výdej na zakázku dál běží výhradně přes import
položek do zakázky"*.

**Co je špatně:** Vstupním bodem výdeje je **doklad**, ne **díl**. Uživatel musí nejdřív vědět,
na které dodavatelské faktuře díl přišel.

**Scénář selhání:** Mechanik potřebuje na zakázku brzdové destičky Bosch. V modalu „Import položek"
napíše „Bosch" nebo katalogové číslo → našeptávač nenajde nic, protože hledá jen v číslech dokladů.
Musí odejít na Sklad → najít kartu dílu → v tabulce šarží si přečíst sloupec „Faktura"
(`pages/WarehousePageDetail.jsx:67-68`) → číslo si zapamatovat → vrátit se do zakázky → vybrat typ
importu → doklad → a teprve pak vybrat řádek. Když je díl ve dvou šaržích ze dvou dokladů, projde
tenhle kolotoč dvakrát a FIFO si musí ohlídat sám.

**Proč to vadí:** Provoz — nejčastější denní úkon dílny má nejdelší cestu a jde proti tomu, jak
uživatel přemýšlí („potřebuju díl X"). Riziko chybného výdeje z novější šarže (FIFO se tady
nenabízí, na rozdíl od ručního pohybu, kde je nejstarší šarže předvybraná — `funkce/sklad-pohyby.md:44-46`).

**Návrh řešení:** Přidat do modalu druhý režim „podle dílu": našeptávač nad produkty →
`GET /warehouse/products/{id}/batches` se zbytky → předvybraná nejstarší šarže (FIFO, stejné pravidlo
jako `StockMovementModal`). Backend nic nového nepotřebuje — `importFromReceipt` už pracuje se
seznamem `goodsReceiptItemId`, mění se jen způsob, jak uživatel k tomu ID dojde.

---

### [P-8] Odhad ceny je jen text — nic ho neporovnává se skutečností a „Konečnou cenu" nelze vyplnit
**Severita:** 🟡 NÍZKÝ
**Jistota:** OVĚŘENO
**Kde:** `frontend/autoservis-frontend/src/components/OrderForm.jsx:306-314` (pole „Odhadovaná cena";
grep `finalPrice` v tomto souboru vrací **0 výskytů** — vstupní pole pro konečnou cenu ve formuláři není),
`frontend/autoservis-frontend/src/pages/OrdersPageEdit.jsx:34` (`finalPrice` se do `formData` načte
a přes `...formData` se pošle zpět — takže se jen přenáší, nikdy nemění),
`frontend/autoservis-frontend/src/pages/OrdersPageDetail.jsx:79`
(`<MetricCard label="Konečná cena (s DPH)" value={formatCurrency(order.finalPrice)} />`);
`formatCurrency(null)` vrací pomlčku (`api/format.js:14-17`).
Backend `final_price` nikdy nedopočítává — jediný zápis je z requestu (`OrderConverter.java:99`).

**Co je špatně:** Sloupec `final_price` (V6:41) je v doméně, DTO, konvertoru, mapperu i na detailu
zakázky, ale v aplikaci **neexistuje způsob, jak ho vyplnit**. Kromě seedu (`db/demo/V8:39`) tedy
u každé reálné zakázky zůstane prázdný a metrika „Konečná cena" ukazuje trvale „—". Zároveň nikde
neexistuje krok „zákazník souhlasil s cenou" ani jakékoli srovnání `estimated_price` se součtem
položek.

**Scénář selhání:** Při příjmu se se zákazníkem domluví odhad 6 000 Kč. Během opravy se najde další
závada, položky vyrostou na 11 000 Kč. Aplikace mlčí: souhrn položek v editaci ukazuje 11 000 Kč,
metrika „Odhadovaná cena" na detailu ukazuje 6 000 Kč a nikdo ta dvě čísla nespojí. Zakázku lze
bez varování dokončit a vyfakturovat na dvojnásobek odhadu. Obsluha nemá kam zapsat, že zákazníkovi
volala a on navýšení odsouhlasil.

**Proč to vadí:** Peníze a spory — navýšení ceny bez zaznamenaného souhlasu je nejčastější zdroj
konfliktu v autoservisu. Navíc UI slibuje údaj („Konečná cena"), který nejde naplnit — to je vždy
matoucí.

**Návrh řešení:** Buď (a) `final_price` z UI odstranit a nahradit součtem položek s DPH (jeden
zdroj pravdy, `v_order_item_summary` už ho počítá), nebo (b) doplnit pole do formuláře a k němu
upozornění „součet položek překročil odhad o X %". Minimální krok k souhlasu se změnou ceny:
zaškrtávátko + datum + kým („navýšení odsouhlaseno zákazníkem dne …"), případně jako součást
zakázkového listu z [P-6]. *Rozhodnutí uživatele*, kterou cestou jít.

---

### [P-9] Změna majitele vozidla nekontroluje otevřené zakázky a tiše rozbije invariant, který se při založení hlídá
**Severita:** 🟡 NÍZKÝ
**Jistota:** OVĚŘENO
**Kde:** `src/main/java/cz/palo/autoservis/service/impl/VehicleServiceImpl.java:156-161`
(kontroluje se jen existence nového zákazníka, nic jiného),
proti `src/main/java/cz/palo/autoservis/service/impl/OrderServiceImpl.java:86-95`
(při **založení** zakázky guard `VEHICLE_NOT_OWNED_BY_CUSTOMER`, audit K-12).
Historie vlastnictví neexistuje — `docs/roadmapa.md:119` (Vehicle Phase 5 `ownership_history`) je ⏳.

**Co je špatně:** Invariant „zakázka.customer = vozidlo.customer" se vynucuje jen v okamžiku
založení zakázky. Následná změna `customerId` na vozidle ho může kdykoli porušit a nikdo ji nezachytí.

**Scénář selhání:** Vozidlo V patří zákazníkovi A, běží na něj otevřená zakázka ZAK-2026-0100.
Zákazník A auto prodá; obsluha na vozidle jen přepíše majitele na B. Zakázka dál nese
`customer_id = A` (v seznamu zakázek se zobrazuje A), ale detail vozidla ukazuje B. Fakturuje se
podle `order.customerId`, tedy A — což je nejspíš správně (opravu objednal A), ale nikdo to
nerozhodl a nikdo o tom neví. Zpětně už nelze zjistit, komu vůz v době opravy patřil, protože
historie vlastnictví se nevede.

**Proč to vadí:** Data — stav, který kód jinde považuje za nemožný, vznikne bez varování;
dohledatelnost „čí to bylo auto" se ztrácí.

**Návrh řešení:** Při změně `customerId` na vozidle s otevřenou zakázkou buď 422 s hláškou
„vozidlo má otevřené zakázky, převod majitele je nejdřív dokončete" (`countOpenByVehicleId` už
existuje a používá ho `deactivate`, `VehicleServiceImpl.java:181`), nebo aspoň potvrzovací dialog na
FE. *Rozhodnutí uživatele*: má se převod zakázat, nebo jen upozornit? Plná evidence převodů je
Vehicle Phase 5 (roadmapa) — tenhle guard na ni nemusí čekat.

---

### [P-10] Editace deaktivovaného zaměstnance je mrtvá akce — vždy skončí chybou
**Severita:** 🟡 NÍZKÝ
**Jistota:** OVĚŘENO
**Kde:** `src/main/resources/mapper/EmployeeMapper.xml:66-70`
(`findById` … `WHERE e.id = #{id} AND e.is_active = TRUE` — strict dle R-10),
`src/main/java/cz/palo/autoservis/controller/EmployeeController.java` — javadoc `getById` sám říká
*„Returns the full detail of an **active** employee"*,
`frontend/autoservis-frontend/src/components/EmployeeTable.jsx:38-46`
(`rowActions` nabízí „Editovat" **vždy**, bez ohledu na `e.active`),
`frontend/autoservis-frontend/src/pages/EmployeesPageEdit.jsx:19-31`
(`api.get('/employees/{id}')` → chyba → alert + návrat zpět).

**Co je špatně:** Seznam zaměstnanců umí zobrazit i neaktivní (přepínač „Jen aktivní",
`EmployeesPage.jsx:30`) a u každého řádku nabízí „Editovat", ale endpoint pro neaktivního vrací 404.

**Scénář selhání:** Mechanik k 31. 7. odchází. Obsluha ho deaktivuje, pak si vzpomene, že mu nevyplnila
datum odchodu (`left_at`). Vypne filtr „Jen aktivní", najde ho, klikne „Editovat" → hláška
„Zaměstnance se nepodařilo načíst" a návrat na seznam. Cesta ven existuje (aktivovat → editovat →
deaktivovat), ale uživatel ji nemá odkud uhodnout.

**Proč to vadí:** Provoz — nabízená akce, která nikdy neuspěje. Zároveň deaktivace `left_at` sama
nenastavuje (`EmployeeMapper.xml:110-114`), takže potřeba ho doplnit je běžná.

**Návrh řešení:** Buď `EmployeesPageEdit` přepnout na variantu včetně neaktivních (v mapperu už je
`findByIdIncludingInactive`, `EmployeeMapper.xml:72-76`), nebo v `EmployeeTable.rowActions` položku
„Editovat" u neaktivních vynechat. Zvážit, zda deaktivace nemá `left_at` předvyplnit na dnešek.

---

### [P-11] Úprava zaměstnance přes UI tiše smaže vazbu na přihlašovací účet
**Severita:** 🟡 NÍZKÝ
**Jistota:** OVĚŘENO
**Kde:** `frontend/autoservis-frontend/src/api/employeePayload.js:16-25`
(payload obsahuje `firstName`, `lastName`, `position`, `hourlyRate`, `hiredAt`, `leftAt` — **`userId` chybí**),
`src/main/java/cz/palo/autoservis/model/dto/employee/EmployeeDto.java` — `UpdateRequest` pole `userId` má,
`src/main/java/cz/palo/autoservis/model/converter/EmployeeConverter.java:55`
(`existing.setUserId(request.getUserId());` — bez `!= null` guardu, full-replace),
`src/main/resources/mapper/EmployeeMapper.xml:100` (`SET user_id = #{userId}`).
`EmployeeForm.jsx` pole pro `userId` nemá vůbec (grep `userId` v souboru = 0 výskytů).

**Co je špatně:** UpdateRequest má full-replace sémantiku, ale frontend jedno z jeho polí neposílá.
Chybějící `userId` se deserializuje jako `null` a přepíše uloženou hodnotu.

**Scénář selhání:** Zaměstnanec #1 je podle demo seedu (`db/demo/V58:66-69`) napojený na login
`mechanic` (`user_id = 3`). Manažer mu jen zvýší hodinovou sazbu z 450 na 480 Kč a uloží →
`PUT /employees/1` bez `userId` → `employee.employees.user_id` se nastaví na NULL. Vazba je pryč
a přes UI ji nejde obnovit, protože formulář pole nemá.

**Proč to vadí:** Data — tichá, přes UI nevratná ztráta údaje, který funkční dokument označuje za
součást modelu (`docs/funkce/zamestnanci.md`, rozhodnutí D-5). Dnes je dopad malý, protože `user_id`
zatím nikdo nečte (jediné použití je `existsByUserId` při validaci); jakmile se na něj něco naváže
(atribuce práce podle přihlášeného, zákaznický portál), bude to chybějící data v historii.

**Návrh řešení:** Buď `userId` doplnit do `toEmployeePayload` a do `EmployeeForm` (select nad
`/users`), nebo — dokud UI účet nepřiřazuje — vazbu z `UpdateRequest` vůbec nebrat a `user_id`
v `EmployeeMapper.update` nechat nedotčené. Druhá varianta je menší zásah a odpovídá tomu, že
propojení dnes vzniká jen při zakládání.

---

### [P-12] Výchozí splatnost faktury = datum vystavení, takže každá bezhotovostní faktura je hned druhý den „po splatnosti"
**Severita:** 🟡 NÍZKÝ
**Jistota:** OVĚŘENO
**Kde:** `frontend/autoservis-frontend/src/components/OrderForm.jsx:48` a `:66-75`
(`const today = getFormDate();` … `issueDate: today, dueDate: today, taxableSupplyDate: today`),
`src/main/resources/db/migration/V35__company_profile_and_supplier_backfill.sql:34-49`
(profil firmy nemá žádnou „výchozí splatnost"),
důsledek: `src/main/resources/mapper/DashboardMapper.xml:98-101` (`due_date < CURRENT_DATE`).
Jediná zábrana je DB CHECK `chk_due_date` (`V14__init_billing_schema.sql:49-50`, `due_date >= issue_date`).

**Co je špatně:** Modal vytvoření faktury předvyplní splatnost na den vystavení. Firemnímu zákazníkovi
s převodem se tak standardně vystaví faktura splatná „dnes"; obsluha to musí pokaždé ručně přepsat.

**Scénář selhání:** Firemní zákazník (IČO) si vyzvedne vůz 15. 7., platí převodem. Obsluha vytvoří
fakturu a datum splatnosti nezmění (je předvyplněné a vypadá jako správné). 16. 7. faktura svítí
v dlaždici „faktury po splatnosti", započítá se do KPI „pohledávky po splatnosti" a při filtru
„Po splatnosti" ji obsluha vidí mezi skutečnými dlužníky — přestože zákazník má na zaplacení
zákonných / smluvních 14 dní.

**Proč to vadí:** Provoz — výstražná dlaždice se zaplní falešnými poplachy a přestane fungovat jako
signál. Dopad je čistě v předvyplnění: pole jde přepsat, ale výchozí hodnota je ta, která se v praxi
používá nejčastěji.

**Návrh řešení:** Přidat do `billing.company_profile` `default_due_days` (např. 14) a předvyplnit
`dueDate = issueDate + default_due_days`; u hotovosti ponechat „dnes". Bez migrace jde i menší krok:
předvyplnit `dueDate` podle zvoleného způsobu platby přímo v `OrderForm.openInvoiceModal`.

---

### [P-13] Aplikace neumí GDPR výmaz ani export osobních údajů a souhlas nemá historii
**Severita:** 🟡 NÍZKÝ
**Jistota:** NÁVRHOVÉ
**Kde:** `src/main/resources/db/migration/V2__init_customer_schema.sql:55-56`
(`gdpr_consent BOOLEAN NOT NULL DEFAULT FALSE`, `gdpr_consent_at TIMESTAMPTZ NOT NULL DEFAULT NOW()`),
`src/main/resources/mapper/CustomerMapper.xml:334-336`
(`gdpr_consent_at` se přepíše při **každé** změně příznaku — historie se nedrží),
`src/main/java/cz/palo/autoservis/service/impl/CustomerServiceImpl.java:164-181`
(`deactivate` je jediná „likvidace" — soft-delete + deaktivace vozidel; mazací endpoint neexistuje).
Žádný export osobních údajů v `CustomerController` není.

**Co je špatně:** Aplikace eviduje jen dvoustavový příznak souhlasu a časové razítko poslední změny.
Neumí: (a) vymazat/anonymizovat zákazníka, (b) vydat mu jeho data, (c) doložit, *k čemu* a *kdy*
souhlas dal a kdy ho odvolal (u zákazníka bez souhlasu nese `gdpr_consent_at` stejně nějaké datum,
protože je NOT NULL DEFAULT NOW()).

**Scénář selhání:** Zákazník, který u servisu nechal jen kontakt na poptávku a nikdy nic
nefakturoval, požádá o výmaz (čl. 17 GDPR). Obsluha ho může jen deaktivovat — jméno, telefon, e-mail,
adresa i VIN zůstávají v databázi. Druhý scénář: zákazník napíše, že s marketingem nikdy nesouhlasil;
aplikace umí ukázat jen aktuální hodnotu příznaku a datum poslední změny, ne historii.

**Proč to vadí:** Právo. Míra jistoty je tu záměrně nižší: u zákazníka s vystavenými fakturami
**výmaz vůbec nepřipadá v úvahu** (§ 35 ZDPH / zákon o účetnictví ukládají uchovávat doklady 10 let,
a faktura navíc drží vlastní immutable snapshot údajů), takže správným řešením je anonymizace
zákaznické karty při zachování dokladů — a to je netriviální rozhodnutí, ne prostý guard.

**Návrh řešení:** Rozhodnout rozsah (viz Otevřené otázky) a pak: (1) endpoint „anonymizovat
zákazníka" (přepis jména/kontaktů na zástupné hodnoty + `is_active = false`), povolený jen když
zákazník nemá aktivní zakázku; faktury se nedotknou (mají snapshot). (2) Export vlastních dat jako
prosté JSON/PDF ze `CustomerDto.DetailResponse` + zakázky + faktury. (3) Zvážit tabulku historie
souhlasů (append-only), pokud servis chce souhlas doložitelně prokazovat.

---

### [P-14] Servisní intervaly / opakovaná zakázka neexistují a stav tachometru se při příjmu nezadává
**Severita:** 🟡 NÍZKÝ
**Jistota:** NÁVRHOVÉ
**Kde:** `frontend/autoservis-frontend/src/components/OrderForm.jsx:161-320` (formulář zakázky —
zákazník, vozidlo, stav, popis, interní poznámka, termín, odhad ceny; **tachometr chybí**),
`src/main/java/cz/palo/autoservis/model/dto/order/OrderDto.java:68-88` (`CreateRequest` — pole pro km nemá),
odečet lze zapsat jen zvlášť na detailu vozidla (`pages/VehiclesPageDetail.jsx:318-328`).
Jediná časová připomínka v aplikaci je STK (`DashboardMapper.xml:294-307`); servisní interval nikde,
a `docs/roadmapa.md` (Vehicle Phase 4b/4c, Order Phase 2) ho neplánuje.

**Co je špatně:** Tachometrová historie existuje a je pěkně udělaná (`MileageServiceImpl`,
pravidla pro `INITIAL`), ale příjem zakázky ji nevyužívá — obsluha musí odečet zapsat na jiné
obrazovce a nikdo jí to nepřipomene. A protože se km při zakázce nezaznamenávají spolehlivě,
nelze na nich postavit ani připomínku „za 15 000 km / 12 měsíců výměna oleje".

**Scénář selhání:** Servis chce po roce oslovit zákazníky s blížící se prohlídkou. V aplikaci na to
nemá žádné vodítko: dashboard umí jen STK, tachometr u řady vozů zůstal na hodnotě z registrace
(protože při zakázce ho nikdo nezapsal) a filtr „vozy, které tu nebyly 12 měsíců" neexistuje —
ostatně ani servisní historii vozidla aplikace nezobrazí (viz [P-5]).

**Proč to vadí:** Provoz a tržby — pravidelný servis a obesílání zákazníků je běžný zdroj práce
dílny. Chybějící km při příjmu je navíc datová ztráta, kterou už nikdy nedoženete.

**Návrh řešení:** Malý krok s velkým efektem: do formuláře nové zakázky přidat nepovinné pole
„Stav tachometru [km]" a při uložení z něj založit `MileageHistory` se `source = SERVICE`
(mapper i service už existují, `MileageServiceImpl.addReading`). Teprve nad tím má smysl uvažovat
o servisních intervalech — to je samostatná funkce, ne guard; *rozhodnutí uživatele*, zda ji chce.

---

### [P-15] Nápověda tvrdí, že položky se přidávají na detailu zakázky — přidávají se v editaci
**Severita:** 🟡 NÍZKÝ
**Jistota:** OVĚŘENO
**Kde:** `frontend/autoservis-frontend/src/help/zakazky.md:14` — *„Na detailu zakázky přidáváte položky:"*
proti `frontend/autoservis-frontend/src/pages/OrdersPageDetail.jsx:158` a `:166-223`
(položky jsou tam **read-only**, žádné tlačítko pro přidání) a `:210`
(prázdný stav sám radí: *„Položky přidáte v editaci zakázky."*).
Editační cesta: `OrdersPageEdit.jsx:73-81` → `OrderForm.jsx:322-326` → `OrderItemsWrapper`.

**Co je špatně:** Uživatelská nápověda posílá obsluhu na obrazovku, kde požadovaná akce není.
Aplikace si přitom odporuje sama se sebou — prázdný stav na detailu radí správně.

**Scénář selhání:** Nový zaměstnanec podle nápovědy otevře detail zakázky, hledá tlačítko „Přidat
položku" a nenajde ho. Buď se doptá kolegy, nebo (pravděpodobněji) usoudí, že aplikace je rozbitá.

**Proč to vadí:** Provoz — nápověda je jediný onboarding, který aplikace má, a tohle je hned první
věc, kterou nový uživatel v článku o zakázkách hledá.

**Návrh řešení:** V `help/zakazky.md:14` opravit na „V **editaci** zakázky přidáváte položky:".
Jednořádková oprava textu.

---

## Co bylo ověřeno jako v pořádku

- **Vazba zakázka ↔ vozidlo ↔ zákazník při založení** — `OrderServiceImpl.java:86-95`, vozidlo musí
  existovat, být aktivní a patřit vybranému zákazníkovi (422 `VEHICLE_NOT_OWNED_BY_CUSTOMER`).
- **Zámek položek po vzniku faktury** — `OrderItemServiceImpl.requireOrderNotInvoiced` (`:324-333`)
  hlídá create, import, update i delete; po stornu faktury se zakázka správně odemkne
  (`findByOrderId` filtruje CANCELLED, V48).
- **Vratka materiálu při smazání položky** — `OrderItemServiceImpl.java:269-283`, `ISSUE_RETURN`
  proti původní šarži, přes ledger (stav skladu se nikde nepřepisuje ručně).
- **Výdej ze skladu proti souběhu** — `findByIdsForUpdate` + agregace požadavků per šarže
  (`:133-176`), čistá 422 `QUANTITY_EXCEEDS_REMAINING` místo pádu na DB CHECK.
- **Stavový automat faktury** — `InvoiceStatus.canTransitionTo` + guardovaný UPDATE
  (`InvoiceServiceImpl.transitionTo:421-451`), 409 při souběhu; vystavit nelze fakturu bez položek;
  stav nelze obejít přes PUT (TD-49 opraveno, `:194-207`).
- **Snapshoty na faktuře** — vozidlo (VIN/značka/model/SPZ), zákazník, číslo zakázky, obě strany
  včetně bankovního spojení (`InvoiceServiceImpl.java:112-155`); pozdější změna vozidla nebo adresy
  doklad nepřepíše.
- **Fakturační adresa musí patřit zákazníkovi zakázky** — `:101-106` (422 `ADDRESS_NOT_OWNED_BY_CUSTOMER`).
- **Stornovanou zakázku nelze fakturovat** — `:76-81`.
- **„Čekání na díl" zakázka vyjádřit umí** — `OrderStatus.WAITING_FOR_PARTS` je v enumu, v seznamu
  stavů dashboardu (`DashboardServiceImpl.java:32-33`) i v řazení workflow (`OrderMapper.xml:183-186`).
- **Dashboard — všechny dotazy odpovídají tomu, co tvrdí `docs/funkce/dashboard.md`:**
  „rozpracované" = ne COMPLETED/CANCELLED; „po termínu" = vyplněný `estimated_completion_at`
  v minulosti a neuzavřená zakázka; „k vyfakturování" = COMPLETED bez nestornované faktury;
  „po splatnosti" = jen ISSUED po `due_date`; tržby = `total_gross` faktur ISSUED+PAID podle
  `issue_date`; marže = `(unit_price − purchase_price) × quantity` z položek zakázky s vyplněnou
  nákupní cenou. Stornované doklady se nemíchají nikam. **Popisky v UI nelžou** — dlaždice i modal
  statistiky výslovně píší „Tržby … (s DPH)" a „Marže … (bez DPH)"
  (`DashboardPage.jsx:244`/`:264`, `DashboardStatisticsModal.jsx:84-85`).
- **Preview dlaždic je omezené na 5, počty jsou úplné** — `PREVIEW_LIMIT` v service
  (`DashboardServiceImpl.java:36`, `:101`, `:113`), počet STK se bere z plného seznamu, ne z preview.
- **`FULL JOIN` v `findMonthlyStats`** (`DashboardMapper.xml:236-244`) jsem prošel na kombinacích
  „měsíc jen v tržbách / jen v marži / jen v zakázkách" — `COALESCE(rev.month, mar.month, ord.month)`
  drží; měsíc přítomný jen v `ord` se nevytratí a řádky se nezdvojují.
- **Zaměstnanci — snímek sazby** — `applyLaborEmployee` (`OrderItemServiceImpl.java:349-366`) doplní
  sazbu jen když `purchasePrice == null`, takže pozdější změna sazby historickou položku nepřepíše
  (D-3); mechanika u ne-LABOR položky odmítne 422 (zrcadlí DB CHECK, V59).
- **Deaktivovaný mechanik na existující položce se neztratí** — `findByIdIncludingInactive`
  (`OrderItemServiceImpl.java:361`) a v UI se dohraje jako „(mimo číselník)"
  (`OrderItemFormModal.jsx:20-22`, `:83-87`), takže uložení položky přiřazení nesmaže.
- **Deaktivace zákazníka/vozidla s otevřenou zakázkou** — blokováno 422
  (`CustomerServiceImpl.java:167-175`, `VehicleServiceImpl.java:181-189`).
- **Inventura** — nevyplněný řádek ≠ nula, rozdíl se počítá proti aktuálnímu stavu (ne proti
  snapshotu), manko FIFO po šaržích, přebytek pseudo-příjemkou bez DPH; vše přes pohyby, nikdy
  přímým zápisem stavu (`StockTakeServiceImpl.java:174-299`). Odpovídá `docs/funkce/inventura.md`.
- **PPD — částka, zaokrouhlení a vazba na stav faktury** odpovídají dokumentaci: jen k ISSUED/PAID
  (`CashReceiptServiceImpl.java:50-56`), zaokrouhlení na celé Kč (`:64`), účel platby z čísla faktury
  a VS (`:112-118`).
- **Oprava konceptu faktury má dokumentovanou cestu** — po vzniku DRAFT faktury jsou položky zakázky
  zamčené a položky faktury nemají UI, ale nápověda (`help/zakazky.md:32`) správně říká, že se
  koncept stornuje a zakázka se odemkne; storno DRAFTu nespotřebuje číslo (přiděluje se až při
  vystavení, V49).

---

## Otevřené otázky pro uživatele

1. **Storno vystavené faktury vs. opravný daňový doklad.** Dnes UI nabízí jen „Stornovat" i pro
   ISSUED fakturu ([P-1]). Varianty: **(A)** ponechat storno jen pro DRAFT a pro vystavené doklady
   vždy vyžadovat dobropis — účetně nejčistší, odpovídá vlastní analýze projektu
   (`analyza-2026-07.md:74-75`); **(B)** ponechat obojí a storno povolit jen dokud doklad neopustil
   dům (což aplikace nepozná); **(C)** ponechat dnešní stav a jen doplnit UI dobropisu.
   *Doporučení: (A) po doplnění UI dobropisu, s povinným důvodem storna u DRAFTu.*
   → **rozhodnutí uživatele.**

2. **Dobropis a tržby na přehledu** ([P-4]). Má se plný dobropis od tržeb odečíst v měsíci
   **původní faktury** (číslo za měsíc pak sedí s realitou), nebo v měsíci **vystavení dobropisu**
   (odpovídá účetnímu zachycení)? *Doporučení: v měsíci vystavení dobropisu, jako záporná položka —
   shodné s tím, jak to uvidí účetní.* Z pohledávek po splatnosti se ale dobropisovaná faktura
   vyřadit musí v obou variantách. → **rozhodnutí uživatele.**

3. **Co má udělat zrušení rozpracované zakázky s vydaným materiálem** ([P-2])?
   **(A)** automaticky vrátit vše na sklad (`ISSUE_RETURN`) v téže transakci — pohodlné, ale
   nepravdivé, pokud se díl už reálně použil nebo poškodil; **(B)** zrušení odmítnout, dokud jsou
   na zakázce skladové položky, s hláškou „nejdřív vraťte materiál"; **(C)** zeptat se dialogem
   a nechat volbu na obsluze. *Doporučení: (B) — nutí obsluhu rozhodnout, co se s díly fyzicky stalo,
   a nic za ni nepředstírá.* → **rozhodnutí uživatele.**

4. **Rozsah GDPR** ([P-13]). Chce servis skutečný „výmaz" (anonymizace karty při zachování dokladů),
   nebo stačí dnešní deaktivace a formální odpověď žadateli mimo aplikaci? Anonymizace je
   netriviální — je potřeba rozhodnout, co s VIN a SPZ (osobní údaj jen ve spojení s osobou) a co
   s interními poznámkami. *Doporučení: implementovat anonymizaci zákaznické karty, doklady
   nechat beze změny (mají vlastní snapshot); VIN a vozidlo ponechat, protože historie vozu žije
   dál u nového majitele.* → **rozhodnutí uživatele.**

5. **Přijímací protokol: jak podrobný** ([P-6])? Postačí tisk zakázkového listu s podpisem
   („souhlasím s provedením prací a s odhadem ceny"), nebo servis chce strukturovaný soupis stavu
   vozu (poškození, výbava, palivo, fotky)? *Doporučení: začít zakázkovým listem — pokrývá souhlas
   s cenou i předání vozu a je to jedna Thymeleaf šablona; strukturovaný soupis poškození až podle
   toho, jak často spory reálně nastanou.* → **rozhodnutí uživatele.**

6. **Souhlas s navýšením ceny** ([P-8]). Má se zavést jako pole na zakázce („navýšení odsouhlaseno
   dne / kým"), nebo řešit poznámkou a papírovým protokolem? *Doporučení: pole na zakázce plus
   varování v souhrnu položek, když součet překročí odhad — obojí je levné a chrání servis.*
   → **rozhodnutí uživatele.**

7. **Duplicitní PPD** ([P-3]). Má aplikace druhý pokladní doklad k téže faktuře **zakázat** (409),
   nebo jen **varovat**? Souvisí s tím, zda se do budoucna počítá s dílčími hotovostními úhradami
   (pak je potřeba zadávat částku, a tím i evidence úhrad 1:N — TD-62/R-3).
   *Doporučení: zatím varovat se zobrazením už vystavených dokladů; zakázat až kdyby se ukázalo,
   že se duplicity dějí.* → **rozhodnutí uživatele.**
