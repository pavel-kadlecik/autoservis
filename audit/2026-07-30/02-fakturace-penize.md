# 02 — Fakturace a peníze (faktura, PPD, dobropis, úhrady)

> Audit 2026-07-30 · rozsah: A3 + průřez „peníze a čísla" — životní cyklus faktury, číselné řady,
> snapshoty, DPH a zaokrouhlení, QR platba, evidence úhrad, příjmový pokladní doklad, opravný daňový
> doklad, PDF výstupy.
> Metoda: čtení celých souborů (Java, XML mappery, migrace, Thymeleaf šablony, dotčený frontend),
> ověřování proti migracím jako zdroji pravdy, druhé adversariální čtení u každého nálezu STŘEDNÍ+.
> Testy nespouštěny (vyžadují Docker) — testovací třídy čteny jako důkaz zamýšleného chování.
> Report nečte `audit/2026-07-24/` (dle briefu).

## Co bylo přečteno

**Migrace (celé):** `V14__init_billing_schema.sql`, `V15__invoice_number_trigger.sql`,
`V17__add_draft_status_to_invoice.sql`, `V31__add_invoice_payment_method_type.sql`,
`V32__v_invoice_price_totals.sql`, `V33__invoice_customer_order_snapshot.sql`,
`V34__invoice_party_snapshot.sql`, `V35__company_profile_and_supplier_backfill.sql`,
`V36__invoice_vehicle_license_plate_snapshot.sql`, `V37__v_invoice_vat_summary.sql`,
`V48__invoice_order_partial_unique.sql`, `V49__invoice_number_on_issue.sql`,
`V50__invoice_vehicle_full_snapshot.sql`, `V51__invoice_payment_record.sql`,
`V55__init_credit_notes.sql`, `V57__init_cash_receipts.sql`, `db/demo/V16__seed_invoices.sql`.

**Java — service:** `InvoiceService`, `InvoiceServiceImpl`, `InvoiceDocumentService`,
`InvoiceDocumentServiceImpl`, `PdfRenderer`, `SpaydBuilder`, `CashReceiptService`,
`CashReceiptServiceImpl`, `CashReceiptDocumentService`, `CashReceiptDocumentServiceImpl`,
`CreditNoteService`, `CreditNoteServiceImpl`, `CreditNoteDocumentService`,
`CreditNoteDocumentServiceImpl`, `CompanyProfileService`, `CompanyProfileServiceImpl`,
`util/AmountInWords`.

**Java — controllery:** `InvoiceController`, `InvoiceDocumentController`, `CashReceiptController`,
`CreditNoteController`, `CompanyProfileController`.

**Java — konvertory:** `InvoiceConverter`, `InvoiceItemConverter`, `CashReceiptConverter`,
`CreditNoteConverter`, `CompanyProfileConverter`.

**Java — doména/DTO/enumy/mapper interfaces:** `model/domain/billing/*` (Invoice, InvoiceItem,
InvoiceListRow, InvoiceParty, InvoiceSummary, InvoiceVatSummary, CashReceipt, CreditNote,
CompanyProfile), `model/dto/billing/*` (InvoiceDto, InvoiceItemDto, InvoiceSearchParams,
CashReceiptDto, CreditNoteDto, CompanyProfileDto), `InvoiceStatus`, `PaymentMethod`,
`InvoicePartyRole`, `InvoiceMapper`, `CashReceiptMapper`, `CreditNoteMapper`.

**XML mappery:** `InvoiceMapper.xml`, `InvoiceItemMapper.xml`, `InvoicePartyMapper.xml`,
`CashReceiptMapper.xml`, `CreditNoteMapper.xml`, `CompanyProfileMapper.xml`, `DashboardMapper.xml`.

**Šablony PDF:** `templates/pdf/invoice.html`, `credit-note.html`, `cash-receipt.html`,
`invoice-styles.html`.

**Frontend (dotčené):** `pages/InvoicesPageDetail.jsx`, `components/InvoiceCreateFormModal.jsx`,
`components/OrderForm.jsx` (část fakturace), `pages/CompanyProfilePage.jsx` (část ukládání),
`components/InvoiceTable.jsx` (sloupce se součty), `help/faktury.md`,
`help/prijmovy-pokladni-doklad.md`.

**Testy (čteno, nespouštěno):** `InvoiceLifecycleTest`, `InvoiceStatusTransitionTest` (hlavička),
`InvoiceDocumentServiceTest`, `CreditNoteServiceTest`, `AmountInWordsTest`.

**Dokumentace:** `CLAUDE.md`, `docs/konvence.md`, `docs/tech-dluhy.md`,
`docs/funkce/prijmovy-pokladni-doklad.md`, relevantní části `docs/api.md` a `docs/databaze.md`,
`OrderItemServiceImpl.requireOrderNotInvoiced` (zámek zakázky fakturou).

## Shrnutí

Fakturační jádro je v dobrém stavu: peníze se všude počítají v `BigDecimal` (v celém balíčku
`model/domain/billing`, `model/dto/billing` ani v tiskových službách není jediný `double`/`float`),
DPH a součty počítá **jedna** dvojice DB views (`v_invoice_price_totals`, `v_invoice_vat_summary`)
se **shodným zaokrouhlením po řádku**, takže rekapitulace DPH vždy sedí s celkovými součty na haléř;
PDF, API i seznam čtou tytéž view hodnoty — druhý zdroj pravdy pro částky faktury jsem nenašel.
Stavový automat je vynucený jak předkontrolou, tak guardovaným `UPDATE ... WHERE status = expected`
na všech čtyřech mutacích, číselné řady faktury / dobropisu / PPD mají korektní advisory lock,
overflow guard i správné offsety `SUBSTRING`, a snapshoty (strany, zákazník, zakázka, celé vozidlo)
jsou zmražené a testy doložené.

Nálezy jsou proto soustředěné do **okrajů dokladového toku**, kde chybí guard nebo zpětná vazba:
tiskový výstup nereflektuje stav *zaplaceno* (a QR platba se generuje i pro zaplacenou fakturu),
`issue_date` zamrzne už při založení konceptu a nikdo ho při vystavení nereviduje, příjmový pokladní
doklad vždy účtuje plnou částku faktury a jde ho vystavit opakovaně bez jakékoli viditelnosti,
dobropis nemá ochranu proti dvojímu plnému dobropisu a faktura o něm neví, a storno faktury
nekontroluje už vystavené navázané doklady.

**Počty:** 🔴 KRITICKÝ 0 · 🔴 VYSOKÝ 0 · 🟠 STŘEDNÍ 5 · 🟡 NÍZKÝ 7.

**Poznámka k pokrytí testy** (není nález): `CashReceiptService` nemá vlastní testovou třídu —
zaokrouhlení PPD na celé Kč, pole `rounding` ani odvození stran/DPH z faktury nejsou pokryté ničím
kromě jednotkového `AmountInWordsTest`.

---

## Nálezy

### [F-1] PDF faktury nezná stav „zaplaceno" a nese funkční QR platbu i u zaplacené faktury
**Severita:** 🟠 STŘEDNÍ
**Jistota:** OVĚŘENO
**Kde:** `src/main/resources/templates/pdf/invoice.html:11-20` (vodoznaky), `:236-240`
(„Celkem k úhradě"), `:255-261` (QR); `src/main/java/cz/palo/autoservis/service/impl/SpaydBuilder.java:23-31`;
`frontend/autoservis-frontend/src/pages/InvoicesPageDetail.jsx:107-110` (PDF vždy dostupné)

**Co je špatně:**
Šablona má vodoznak jen pro `DRAFT` („NÁVRH — NENÍ DAŇOVÝ DOKLAD") a `CANCELLED` („STORNOVÁNO").
Pro stav `PAID` **není nic** — dokument dál tvrdí „Celkem k úhradě: X CZK" a v patičce má QR platbu.
`SpaydBuilder.build` (řádky 25–31) přeskočí QR pouze když `invoiceNumber == null` (koncept) nebo když
dodavatel nemá IBAN; **stav faktury nekontroluje vůbec**. Přitom `InvoiceDto.DetailResponse` už
pole `paidAt` / `paidAmount` / `paidMethod` nese (`InvoiceDto.java:63-66`) — šablona je nepoužívá.
Stejný QR se vygeneruje i pro `CANCELLED` fakturu (tam je aspoň vodoznak jako částečná pojistka).

**Scénář selhání:**
1. Faktura 202607001 na 6 105,23 Kč je vystavena, zákazník ji zaplatí převodem.
2. Obsluha ji označí „Zaplaceno" (`POST /invoices/{id}/pay`).
3. Zákazník o týden později požádá o kopii dokladu; obsluha otevře „PDF" na detailu faktury.
4. PDF vypadá naprosto stejně jako před zaplacením — nadpis „Celkem k úhradě", VS, datum splatnosti
   a **plně funkční QR platba na 6 105,23 Kč**.
5. Zákazník (nebo jeho účetní) doklad zaplatí podruhé.
Správně: dokument zaplacené faktury má nést informaci „Zaplaceno dne … částkou …" a QR platbu
vynechat, protože není co platit.

**Proč to vadí:** peníze — přeplatek, který se musí ručně dohledat a vrátit; u firemního odběratele
s automatickým zpracováním QR/ISDOC je dvojí úhrada realistická. Zároveň nápověda aplikace
(`frontend/…/src/help/faktury.md:24`) uživateli tvrdí *„QR je navíc jen na vystavené faktuře"* —
to podle kódu neplatí.

**Návrh řešení:** v `SpaydBuilder.build` přidat guard `if (invoice.getStatus() != InvoiceStatus.ISSUED)
return null;` (koncept už odchytí kontrola čísla) a do `invoice.html` doplnit blok pro `PAID`
(vodoznak/razítko „ZAPLACENO" + řádek `paidAt`/`paidAmount`/`paidMethod`, které DTO už má).
Volitelně přejmenovat „Celkem k úhradě" na „Celkem" u zaplacené faktury.

---

### [F-2] `issue_date` zamrzne při založení konceptu a při vystavení se nereviduje — číselná řada není chronologická
**Severita:** 🟠 STŘEDNÍ
**Jistota:** OVĚŘENO
**Kde:** `src/main/java/cz/palo/autoservis/service/impl/InvoiceServiceImpl.java:215-222` (`issue`),
`:421-451` (`transitionTo`); `src/main/resources/mapper/InvoiceMapper.xml:189-194` (`updateStatus`
mění pouze `status`); `src/main/resources/db/migration/V49__invoice_number_on_issue.sql:34`
(`TO_CHAR(COALESCE(NEW.issue_date, CURRENT_DATE), 'YYYYMM')`);
`src/main/java/cz/palo/autoservis/model/dto/billing/InvoiceDto.java:124-125` (jen `@NotNull`);
`frontend/autoservis-frontend/src/components/InvoiceCreateFormModal.jsx:67-71` (volný `<input type="date">`)

**Co je špatně:**
V49 správně přesunula přidělení čísla z INSERT na přechod do `ISSUED` a odvodila prefix z `issue_date`
místo `CURRENT_DATE`. `issue_date` ale pochází z těla `POST /invoices/from-order`, zapisuje se **při
založení konceptu** a už se nikdy nemění: `UpdateRequest` pole `issueDate` neobsahuje
(`InvoiceDto.java:150-164`), `issue()` ho nepřepisuje a `updateStatus` ho v SQL vůbec nemá. Validace
je jen `@NotNull` — žádná horní ani dolní mez, žádná kontrola vůči již vystaveným dokladům. Jediné
omezení v DB je `chk_due_date CHECK (due_date >= issue_date)` (V14:49-50).
Chování je dokumentované testem `InvoiceLifecycleTest.java:581-598`, který explicitně vystaví
„dnes" doklad s `issue_date = 2026-03-15` a očekává číslo `202603…`.

**Scénář selhání:**
1. V březnu 2026 servis normálně vystaví faktury `202603001` … `202603005` (poslední 31. 3.).
2. 15. 3. 2026 někdo založí koncept faktury (`issueDate = 2026-03-15`) a nechá ho ležet.
3. 30. 7. 2026 ho najde a klikne „Vystavit“.
4. Trigger vezme `issue_date = 2026-03-15` → prefix `202603` → přidělí `202603006`, tedy **číslo
   za všemi březnovými doklady, ale s datem vystavení 15. 3.** Uvnitř měsíce tak čísla nejsou
   seřazená podle data vystavení a doklad vznikne do už uzavřeného období.
5. Varianta s překlepem: obsluha při zakládání konceptu omylem zadá `2016-03-15` → po vystavení
   doklad dostane číslo `201603001` a založí paralelní historickou řadu.

**Proč to vadí:** peníze/účetnictví a provoz — nesouvislé a nechronologické evidenční číslo dokladu
se špatně vysvětluje a špatně opravuje (číslo je immutable, `uq_invoice_number`, a doklad nejde
smazat, jen stornovat). Zároveň to částečně maří záměr V49 („číslo řady sedí s datem vystavení
dokladu").

**Návrh řešení:** dvě varianty, výběr je *rozhodnutí uživatele*:
(a) **Přerazítkovat při vystavení** — `issue()` nastaví `issue_date = CURRENT_DATE` (a případně
posune `due_date` o původní počet dní splatnosti); pak číslo vždy sedí s reálným datem vystavení.
(b) **Ponechat volbu data, ale ohraničit ji** — validace v service při `issue()`: `issue_date` nesmí
být v budoucnosti a nesmí být starší než X dní / nesmí spadat do měsíce, kde už existuje doklad
s pozdějším `issue_date`; jinak `BusinessRuleException`.
V obou případech doplnit `min`/`max` na `<input type="date">` v `InvoiceCreateFormModal`.

---

### [F-3] Příjmový pokladní doklad vždy účtuje plnou částku faktury a lze ho vystavit opakovaně bez jakékoli viditelnosti
**Severita:** 🟠 STŘEDNÍ
**Jistota:** OVĚŘENO
**Kde:** `src/main/java/cz/palo/autoservis/service/impl/CashReceiptServiceImpl.java:38-76`;
`src/main/java/cz/palo/autoservis/model/dto/billing/CashReceiptDto.java:19-23` (request má jen `invoiceId`);
`src/main/resources/db/migration/V57__init_cash_receipts.sql:30-46` (bez unique na `invoice_id`);
`frontend/autoservis-frontend/src/pages/InvoicesPageDetail.jsx:54-64` a `:112-117` (tlačítko bez potvrzení, bez výpisu existujících dokladů);
`src/main/java/cz/palo/autoservis/controller/CashReceiptController.java:58-61` (GET seznam existuje, FE ho nevolá)

**Co je špatně:**
`createFromInvoice` nikdy nečte existující doklady k faktuře — částku bere výhradně z
`invoiceMapper.findSummaryByInvoiceId(...)` a zaokrouhlí na celé Kč
(`CashReceiptServiceImpl.java:58-64`). Klient částku ovlivnit nemůže (`CreateRequest` má jediné pole
`invoiceId`). Tabulka `cash_receipts` nemá na `invoice_id` unikát — a to *záměrně*, protože
`V57:16-17` i nápověda (`help/prijmovy-pokladni-doklad.md`, sekce „Dobré vědět") to zdůvodňují
**dílčími hotovostními úhradami**. Dílčí částka ale není implementovaná: každý další PPD je
znovu na **plnou** částku faktury. Doklad navíc nemá stav, nejde stornovat ani smazat (V57 nemá
`updated_at` ani status; service nemá žádnou rušící metodu) a na detailu faktury se seznam už
vystavených PPD **nikde nezobrazuje**, ačkoli endpoint `GET /cash-receipts?invoiceId=…` existuje.

**Scénář selhání:**
1. Faktura 202607001 na 6 105,23 Kč je ISSUED, zákazník platí hotově.
2. Obsluha klikne „Pokladní doklad" → vznikne `PPD202607001` na 6 105 Kč, otevře se PDF v novém panelu.
3. Tiskárna nezabere; obsluha se vrátí na detail faktury a klikne znovu → vznikne `PPD202607002`,
   **opět na plných 6 105 Kč**. `busy` guard chrání jen po dobu běhu requestu, ne opakované kliknutí.
4. Na detailu faktury není nic, z čeho by obsluha poznala, že už existují dva doklady.
5. V pokladní knize sedí příjem 12 210 Kč proti faktuře na 6 105,23 Kč a chybný doklad nelze zrušit —
   nápověda k tomu říká „chybný nechte být".
Správně: buď je PPD k faktuře idempotentní (druhé volání vrátí existující doklad / 409), nebo je
částka součástí requestu a server hlídá, že součet PPD nepřekročí částku faktury.

**Proč to vadí:** peníze — evidence hotovosti nadhodnocená o násobek částky, bez možnosti opravy
v aplikaci. Zároveň jde o rozpor mezi dokumentovaným záměrem (dílčí úhrady) a kódem (vždy plná částka).

**Návrh řešení:** minimum bez zásahu do modelu: (1) `InvoicesPageDetail` načte a zobrazí
`GET /cash-receipts?invoiceId={id}` (seznam čísel + částek) a tlačítko doplní o potvrzení, existuje-li
už doklad; (2) v service porovnat `SUM(amount)` existujících PPD s `totalGross` a při překročení
vyhodit `BusinessRuleException` (`CASH_RECEIPT_EXCEEDS_INVOICE`). Plná podpora dílčích úhrad
(částka v `CreateRequest`) je *rozhodnutí uživatele* a navazuje na odloženou evidenci 1:N (TD-62).

---

### [F-4] Dobropis: žádná ochrana proti opakovanému plnému dobropisu; faktura o dobropisu neví
**Severita:** 🟠 STŘEDNÍ
**Jistota:** OVĚŘENO
**Kde:** `src/main/java/cz/palo/autoservis/service/impl/CreditNoteServiceImpl.java:36-65`
(`createFromInvoice` — jediná kontrola je stav původní faktury);
`src/main/resources/db/migration/V55__init_credit_notes.sql:41` (`CREATE INDEX idx_credit_notes_original` — **ne** UNIQUE);
`src/main/java/cz/palo/autoservis/mapper/CreditNoteMapper.java:20` (`findByOriginalInvoiceId` — existuje, nikdo ho nevolá);
`src/main/java/cz/palo/autoservis/model/converter/CreditNoteConverter.java:68-73` (rozdíly = záporné souhrny celé faktury)

**Co je špatně:**
`createFromInvoice` ověří jen to, že původní faktura je `ISSUED` nebo `PAID`. Nekontroluje, zda
k ní už dobropis existuje, a DB to nehlídá (index na `original_invoice_id` je běžný, ne unikátní).
Protože MVP je *plný* dobropis (`CreditNoteConverter` neguje celé souhrny faktury), každý další
dobropis odečítá znovu **celou** částku dokladu. Metoda `CreditNoteMapper.findByOriginalInvoiceId`,
která by kontrolu umožnila, je v mapperu i v XML definovaná, ale v celém `src/` ji nikdo nevolá.
Souběžně platí, že faktura o svém dobropisu nic neví: `InvoiceDto.DetailResponse` žádné pole
o opravných dokladech nemá, `InvoiceMapper` na `credit_notes` nesahá — v seznamu i na detailu
zůstává faktura se svou původní kladnou částkou a stavem `PAID`.

**Scénář selhání:**
1. Faktura 202607001 (PAID, 6 105,23 Kč) — reklamace, MANAGER vystaví dobropis `OD202607001`
   na −6 105,23 Kč.
2. O týden později (jiný pracovník, nebo opakovaný požadavek po timeoutu) proběhne `POST /credit-notes`
   znovu → vznikne `OD202607002`, opět na **−6 105,23 Kč**. Nic to nezablokuje.
3. Faktura se v aplikaci nadále tváří jako zaplacená na 6 105,23 Kč; nikde není vidět, že už byla
   dvakrát celá dobropisována.
4. V účetnictví je odečteno 12 210,46 Kč proti dokladu na 6 105,23 Kč.
Správně: druhý plný dobropis k téže faktuře má být odmítnut (nebo musí být částečný a hlídaný na
zbývající částku) a faktura má na detailu ukazovat navázané opravné doklady.

**Proč to vadí:** peníze a účetní správnost. Riziko je snížené tím, že **dobropis nemá žádné UI**
(grep `credit-notes` ve `frontend/autoservis-frontend/src` nevrací nic) — jde tedy o API-only cestu
dostupnou rolím ADMIN/MANAGER, ne o dvojklik v prohlížeči. To je důvod pro STŘEDNÍ, ne VYSOKÝ.

**Návrh řešení:** v `createFromInvoice` použít už existující `findByOriginalInvoiceId` a odmítnout
druhý *nestornovaný* dobropis (`BusinessRuleException INVOICE_ALREADY_CREDITED`), případně doplnit
partial unique index. Do `InvoiceDto.DetailResponse` přidat seznam/příznak opravných dokladů, ať
je z detailu faktury vidět, že byla dobropisována. *Rozhodnutí uživatele:* zda plný dobropis má
navíc překlopit fakturu do zvláštního stavu, nebo zůstat čistě odděleným dokladem.

---

### [F-5] Storno faktury nekontroluje už vystavené navázané doklady (PPD, dobropis)
**Severita:** 🟠 STŘEDNÍ
**Jistota:** OVĚŘENO
**Kde:** `src/main/java/cz/palo/autoservis/service/impl/InvoiceServiceImpl.java:247-254` (`cancel`),
`:421-451` (`transitionTo` — jediná dodatečná kontrola je `INVOICE_HAS_NO_ITEMS`, a to jen pro cíl `ISSUED`);
`src/main/java/cz/palo/autoservis/model/enums/InvoiceStatus.java:23-28` (`ISSUED → CANCELLED` povoleno);
`src/main/resources/db/migration/V57__init_cash_receipts.sql:41-42` a `V55__init_credit_notes.sql:37-38` (prosté FK bez jakéhokoli guardu)

**Co je špatně:**
Přechod `ISSUED → CANCELLED` neprovádí žádnou kontrolu navázaných dokladů. K faktuře přitom už
mohl vzniknout příjmový pokladní doklad (potvrzení, že pokladna přijala hotovost) nebo opravný
daňový doklad. Po stornu zůstávají oba doklady beze změny a bez jakéhokoli označení, a navíc se
díky částečnému unikátnímu indexu `uq_invoices_order_active` (V48) zakázka odemkne pro novou
fakturu — takže k jedné hotovostní úhradě může existovat PPD odkazující na stornovaný doklad
a zároveň nová faktura na tutéž práci.

**Scénář selhání:**
1. Faktura 202607001 je ISSUED. Zákazník platí hotově, obsluha vystaví `PPD202607001` na 6 105 Kč.
   Fakturu ale **neoznačí** jako zaplacenou (PPD stav faktury nemění — viz `CashReceiptServiceImpl`,
   nikde nevolá `markPaid`).
2. Ukáže se chyba v položkách. Protože faktura je stále `ISSUED` (ne `PAID`), jde ji stornovat —
   `POST /invoices/{id}/cancel` projde bez varování.
3. Zakázka se odemkne, vznikne nová faktura 202607014.
4. V pokladně zůstává `PPD202607001` s účelem „Úhrada faktury č. 202607001" — dokladu, který už
   neexistuje jako platný daňový doklad — a nová faktura nemá žádný doklad o přijaté hotovosti.
Správně: storno má buď vyžadovat, aby k faktuře nebyl vystavený PPD/dobropis, nebo tuto skutečnost
obsluze aspoň nahlásit a doklad označit.

**Proč to vadí:** účetní stopa — pokladní doklad a opravný doklad ukazují na zrušený doklad, a to
bez jakéhokoli záznamu; ruční dohledání je jediná cesta ven.

**Návrh řešení:** v `cancel()` načíst `cashReceiptMapper.findByInvoiceId(id)` a
`creditNoteMapper.findByOriginalInvoiceId(id)`; při neprázdném výsledku *rozhodnutí uživatele*:
(a) tvrdě odmítnout (`BusinessRuleException INVOICE_HAS_CASH_RECEIPTS` / `…_HAS_CREDIT_NOTES`) —
oprava se pak musí udělat dobropisem, nebo (b) povolit, ale vrátit varování a zobrazit navázané
doklady v potvrzovacím dialogu na FE. Varianta (a) je konzistentní s tím, že `PAID → CANCELLED`
už dnes zakázané je.

---

### [F-6] Marže na přehledu se počítá z položek zakázky, tržby z položek faktury — mohou se rozejít
**Severita:** 🟡 NÍZKÝ
**Jistota:** OVĚŘENO
**Kde:** `src/main/resources/mapper/DashboardMapper.xml:163-193` (`sumMargin` — `FROM "order".order_items oi`),
`:140-153` (`sumRevenue` — `billing.v_invoice_price_totals`), `:206-245` (`findMonthlyStats` — totéž);
`src/main/java/cz/palo/autoservis/service/impl/InvoiceServiceImpl.java:337-415`
(`addItem`/`updateItem`/`deleteItem` — položky faktury jsou v DRAFT plně editovatelné);
`src/main/java/cz/palo/autoservis/service/impl/OrderItemServiceImpl.java:324-333`
(`requireOrderNotInvoiced` — položky zakázky se naopak vznikem faktury zamknou)

**Co je špatně:**
Tržby berou částky z položek **faktury** (přes view), marže z položek **zakázky**. Položky faktury
jsou samostatná kopie, kterou lze v konceptu libovolně měnit, mazat i přidávat — položky zakázky
se v tu chvíli naopak zamykají, takže se do nich změna nikdy nepromítne. Obě čísla se pak počítají
nad jinou realitou.

**Scénář selhání:**
1. Zakázka má položku „Brzdové destičky" — nákup 800, prodej 1 200 Kč.
2. Vznikne koncept faktury (položky zakázky se zamknou). Zákazník reklamuje, vedoucí dá slevu:
   na **faktuře** změní jednotkovou cenu položky z 1 200 na 900 Kč (`PUT /invoices/{id}/items/{itemId}`)
   a fakturu vystaví.
3. Přehled ukáže tržbu podle faktury (900 × 1,21), ale marži podle zakázky — **400 Kč místo 100 Kč**.
4. Totéž při smazání položky z konceptu faktury: tržba klesne, marže zůstane, jako by se položka
   prodala.

**Proč to vadí:** manažerský ukazatel „Marže" je tichý — nikde není vidět, že se rozešel s tržbou.
Není to ztráta dat ani peněz, proto NÍZKÝ; pro rozhodování o cenách je to ale zavádějící.
*(Pozn.: `sumMargin` může patřit do rozsahu průchodu o přehledu/dashboardu — uvádím zde, protože
příčina je na straně fakturace: dvě nezávisle editovatelné kopie položek.)*

**Návrh řešení:** počítat marži z `billing.invoice_items` a nákupní cenu brát JOINem přes
`invoice_items.order_item_id → order_items.purchase_price` — pak stojí tržba i marže nad týmiž
řádky. Alternativně (levněji) zakázat editaci cen na položkách faktury a slevu řešit na zakázce
před vystavením faktury.

---

### [F-7] Hláška „Zakázka už má fakturu null" u zamčené zakázky s konceptem
**Severita:** 🟡 NÍZKÝ
**Jistota:** OVĚŘENO
**Kde:** `src/main/java/cz/palo/autoservis/service/impl/OrderItemServiceImpl.java:330`
(`"Zakázka už má fakturu " + inv.getInvoiceNumber() + " — položky nelze měnit."`),
`:324-333` (`requireOrderNotInvoiced` používá `findByOrderId`, který vrací i DRAFT);
`src/main/resources/mapper/InvoiceMapper.xml:224-229` (`findByOrderId` filtruje jen `status <> 'CANCELLED'`);
`src/main/resources/db/migration/V49__invoice_number_on_issue.sql:20` (`invoice_number` je u konceptu NULL)

**Co je špatně:** text výjimky vkládá `invoiceNumber`, který je od V49 u konceptu `NULL`.
Řetězcová konkatenace z něj udělá literál „null". Regrese zavedená V49 — před ní měl číslo i koncept.

**Scénář selhání:** obsluha založí koncept faktury k zakázce, pak se vrátí do zakázky a zkusí
upravit položku. Dostane 422 s hláškou **„Zakázka už má fakturu null — položky nelze měnit."**
Uživatel nemá z hlášky šanci poznat, o kterou fakturu jde.

**Proč to vadí:** provoz — matoucí chybová hláška v běžné situaci (koncept se zakládá dřív, než se
vystaví). Data ani peníze v ohrožení nejsou.

**Návrh řešení:** `inv.getInvoiceNumber() != null ? "faktura " + číslo : "rozpracovaný koncept faktury"`,
ID faktury je v `params` už dnes k dispozici pro proklik.

---

### [F-8] Vymazání „Kódu země" v Nastavení firmy shodí uložení na chybu z databáze
**Severita:** 🟡 NÍZKÝ
**Jistota:** OVĚŘENO
**Kde:** `src/main/resources/mapper/CompanyProfileMapper.xml:47` (`country_code = #{countryCode}` — bez `COALESCE`);
`src/main/resources/db/migration/V35__company_profile_and_supplier_backfill.sql:43` (`country_code CHAR(2) NOT NULL DEFAULT 'CZ'`);
`src/main/java/cz/palo/autoservis/model/dto/billing/CompanyProfileDto.java:55-56` (jen `@Size(max = 2)`, není `@NotBlank`);
`frontend/autoservis-frontend/src/pages/CompanyProfilePage.jsx:48` (`countryCode: form.countryCode || null`)

**Co je špatně:** `UPDATE` zapisuje `country_code` napřímo, sloupec je `NOT NULL`, DTO povinnost
nevynucuje a frontend prázdné pole posílá jako `null`. Ostatní moduly tenhle případ řeší
`COALESCE` (viz `SupplierMapper.update`, TD-54 to explicitně zmiňuje jako vzor pro NOT NULL
`country_code`) — `CompanyProfileMapper` výjimku nemá.

**Scénář selhání:**
1. Uživatel otevře „Nastavení firmy", opraví IBAN a přitom smaže obsah pole „Kód země".
2. Klikne Uložit → FE pošle `countryCode: null` → `not-null constraint` → 422
   `DATA_INTEGRITY_VIOLATION`.
3. **Neuloží se nic** — ani opravený IBAN. Toast ukáže technickou hlášku o porušení integrity.
Správně: buď 400 s hláškou „Kód země je povinný", nebo (konzistentně se `SupplierMapper`) ponechat
původní hodnotu.

**Proč to vadí:** provoz — jediné prázdné pole zablokuje uložení celého fakturačního profilu
a uživatel dostane hlášku, ze které příčinu nepozná. Porušuje R-13 (business validace patří do
service/DTO, ne na DB CHECK).

**Návrh řešení:** `@NotBlank` na `CompanyProfileDto.UpdateRequest.countryCode` (a `@Size(min = 2, max = 2)`),
nebo `country_code = COALESCE(#{countryCode}, country_code)` ve stejném duchu jako `SupplierMapper`.

---

### [F-9] `dueDate < issueDate` propadne až na DB CHECK místo čisté business validace
**Severita:** 🟡 NÍZKÝ
**Jistota:** OVĚŘENO
**Kde:** `src/main/resources/db/migration/V14__init_billing_schema.sql:49-50`
(`CONSTRAINT chk_due_date CHECK (due_date >= issue_date)`);
`src/main/java/cz/palo/autoservis/model/dto/billing/InvoiceDto.java:124-131` (CreateRequest — jen `@NotNull` na obou datech, žádná křížová validace),
`:151` (UpdateRequest.dueDate bez validace);
`src/main/java/cz/palo/autoservis/service/impl/InvoiceServiceImpl.java:66-174` (`createFromOrder` datum nekontroluje)

**Co je špatně:** vztah mezi datem vystavení a splatnosti hlídá výhradně DB CHECK. R-13 přitom
požaduje business validaci v service vrstvě s čistou `BusinessRuleException`; projekt tenhle vzor
jinde dodržuje (`@ValidCustomerRequest`, TD-10).

**Scénář selhání:** obsluha ve formuláři vytvoření faktury přehodí data (splatnost 1. 7., vystavení
15. 7.) → `POST /invoices/from-order` skončí 422 `DATA_INTEGRITY_VIOLATION` s obecnou hláškou
o porušení integrity, místo 400 s „Datum splatnosti nesmí předcházet datu vystavení". Totéž přes
`PUT /invoices/{id}` (mapper zapisuje `due_date = COALESCE(#{dueDate}, due_date)`).

**Proč to vadí:** UX a konzistence chybových kódů; žádný datový dopad (DB drží integritu).

**Návrh řešení:** class-level validátor na `InvoiceDto.CreateRequest` (vzor `@ValidCustomerRequest`)
a kontrola v `InvoiceServiceImpl.update` proti uloženému `issue_date` (UpdateRequest `issueDate`
nemá, takže se musí porovnat s DB).

---

### [F-10] Dokumentace se rozchází s kódem na čtyřech místech (billing)
**Severita:** 🟡 NÍZKÝ
**Jistota:** OVĚŘENO
**Kde:**
1. `docs/databaze.md:323` — „Triggery: `trg_invoices_updated_at`; `trg_invoices_generate_number` (**BEFORE INSERT**)."
   Ve skutečnosti V49 trigger dropla a znovu vytvořila jako podmíněný **BEFORE UPDATE**
   (`V49__invoice_number_on_issue.sql:58-66`). Tentýž dokument to o 25 řádků výš (`:298`) popisuje správně.
2. `docs/databaze.md:292-394` — sekce „Schéma `billing`" má v nadpisu uvedeno „(… V55, V57)", ale
   tabulku **`billing.credit_notes` (V55) vůbec nepopisuje** (podsekce jsou jen `invoices`,
   `invoice_items`, `invoice_party`, `company_profile`, `cash_receipts` — ověřeno gripem na `### billing`).
   Tabulka existuje od V55 a je aktivně používaná.
3. `docs/api.md:174` — „MVP = plný dobropis; **PDF (E5.2)** a částečný dobropis **jsou plánované**."
   PDF je hotové a hned na `:181` je jeho endpoint dokumentovaný
   (`CreditNoteDocumentServiceImpl`, šablona `templates/pdf/credit-note.html`).
4. `frontend/autoservis-frontend/src/help/faktury.md:24` — „QR je navíc jen na **vystavené** faktuře."
   Podle `SpaydBuilder.java:23-31` se QR vygeneruje i pro `PAID` a `CANCELLED` (viz F-1).

**Co je špatně:** `docs/databaze.md` má být „schéma rekonstruované z migrací" a CLAUDE.md ukládá
dokumentaci po každé změně synchronizovat. Body 1–2 znamenají, že se z dokumentu nedá spolehlivě
odvodit, kdy se přiděluje číslo faktury ani že tabulka dobropisů existuje.

**Scénář selhání:** vývojář (nebo agent) plánující změnu číslování si podle `databaze.md:323`
ověří, že trigger běží na INSERT, a navrhne opravu, která na skutečném `BEFORE UPDATE` triggeru
nefunguje. U bodu 2 návrh nové migrace nad dobropisy vznikne bez znalosti existujících sloupců
a constraintů.

**Proč to vadí:** dokumentace je v tomhle projektu pracovní nástroj; nepravdivá je horší než žádná.

**Návrh řešení:** opravit `databaze.md:323` na „BEFORE UPDATE (podmíněný, přechod do ISSUED — V49)“,
doplnit podsekci `### billing.credit_notes (V55)` do §5, přeformulovat větu v `api.md:174` a
sladit nápovědu s chováním QR (nebo naopak chování s nápovědou — viz F-1).

---

### [F-11] Mrtvá mapper metoda `CreditNoteMapper.findByOriginalInvoiceId`
**Severita:** 🟡 NÍZKÝ
**Jistota:** OVĚŘENO
**Kde:** `src/main/java/cz/palo/autoservis/mapper/CreditNoteMapper.java:20`;
`src/main/resources/mapper/CreditNoteMapper.xml:43-48`

**Co je špatně:** metoda je deklarovaná v interface i implementovaná v XML, ale v celém `src/`
(produkce i testy) ji nikdo nevolá — ověřeno `grep -rn "findByOriginalInvoiceId" src/`, který vrací
právě a jen tato dvě místa definice. Porušuje R-12 („dead code smazat"). Není krytá ani testem,
takže na ni nesedí výjimka evidovaná v TD-64 (tam jde o kód ponechaný kvůli testovému pokrytí).

**Scénář selhání:** provozně žádný — jde o čistotu kódu. Prakticky se ale ukazuje jako *chybějící
volání*: přesně tahle metoda je to, co by uzavřelo nález F-4 (ochrana proti dvojímu dobropisu)
a částečně F-5.

**Proč to vadí:** nekonzistence s R-12 a matoucí signál („kontrola existuje" — ale nikdo ji nevolá).

**Návrh řešení:** buď ji zapojit do `CreditNoteServiceImpl.createFromInvoice` (preferováno, řeší F-4),
nebo smazat.

---

### [F-12] PDF opravného dokladu tiskne syrová čísla a ISO data, nekonzistentně s fakturou
**Severita:** 🟡 NÍZKÝ
**Jistota:** OVĚŘENO
**Kde:** `src/main/resources/templates/pdf/credit-note.html:38` a `:41`
(`th:text="${cn.issueDate}"`, `th:text="${cn.taxableSupplyDate}"`), `:95-97` a `:101-103`
(`th:text="${line.base}"`, `${cn.totalNetDifference}` …).
Srovnej `templates/pdf/invoice.html:143` (`#temporals.format(..., 'dd.MM.yyyy')`) a `:183-185`, `:229`
(`#numbers.formatDecimal(..., 1, 'WHITESPACE', 2, 'COMMA')` + `' CZK'`).

**Co je špatně:** šablona dobropisu vypisuje `BigDecimal` a `LocalDate` přes výchozí `toString()`.
Faktura i pokladní doklad používají české formátování (mezera jako oddělovač tisíců, desetinná
čárka, `dd.MM.yyyy`, jednotka měny). Šablona navíc nemá `<title>`, logo ani razítko/podpis, které
oba ostatní doklady mají.

**Scénář selhání:** dobropis na −12 345,60 Kč se vytiskne jako `-12345.60` bez měny a datum
vystavení jako `2026-07-30`. Doklad, který jde zákazníkovi i účetní, vypadá jinak než faktura
od téže firmy a částka se čte hůř (bez oddělovače tisíců).

**Proč to vadí:** kosmetika a konzistence dokladů; žádný numerický dopad — hodnoty jsou správné.

**Návrh řešení:** převzít formátování z `invoice.html` (`#numbers.formatDecimal` + `' CZK'`,
`#temporals.format`) a doplnit hlavičku s logem a blok razítko/podpis, ať jsou všechny tři doklady
z jedné dílny.

---

## Co bylo ověřeno jako v pořádku

**Peníze a zaokrouhlení**
- V celém rozsahu (`model/domain/billing`, `model/dto/billing`, `Invoice*/CashReceipt*/CreditNote*ServiceImpl`,
  `SpaydBuilder`, `AmountInWords`) **není žádný `double`/`float`** — ověřeno gripem, vše `BigDecimal`/`NUMERIC`.
- `v_invoice_price_totals` (V32) a `v_invoice_vat_summary` (V37) používají **identický výraz**
  `ROUND(quantity*unit_price, 2)` a `ROUND(quantity*unit_price*vat_rate/100.0, 2)`, jen s jiným
  `GROUP BY`. Součet rekapitulace po sazbách proto vždy dá celkové součty na haléř a
  `total_gross = total_net + total_vat` platí přesně (žádné „ztracené haléře").
- `InvoiceItemMapper.xml:82-85` a `:92-95` počítají `net`/`vat`/`gross` na řádku **stejným výrazem**
  jako views — řádek faktury v PDF sedí na součet i na rekapitulaci.
- PDF faktury čte položky i součty z `InvoiceService.getById` (tedy z týchž views) —
  `InvoiceDocumentServiceImpl.java:41`. **Žádný paralelní dopočet v Javě.** Totéž PPD
  (`CashReceiptDocumentServiceImpl.java:29`) a dobropis (`CreditNoteDocumentServiceImpl.java:27`).
- Seznam faktur (`InvoiceMapper.xml:256-271`), detail zákazníka (`:236-249`) i dlaždice přehledu
  (`DashboardMapper.xml:104-127`, `:140-153`) berou částky z téhož view. Jeden zdroj.
- PPD zaokrouhluje na celé Kč `HALF_UP` (`CashReceiptServiceImpl.java:64`), rozdíl vykazuje jako
  `rounding = amount − totalGross` (`CashReceiptConverter.java:61-63`) a PDF ho tiskne jen když je
  nenulový (`cash-receipt.html:113-118`) — rozpis DPH se nemění, což odpovídá dokumentovanému záměru.
- `AmountInWords` je ohraničený, deterministický a otestovaný (`AmountInWordsTest`, 29 parametrizovaných
  případů + haléře + hraniční hodnoty); odmítá null, zápornou částku i ≥ 1 mld.

**Číselné řady**
- Faktura (V49), dobropis (V55) i PPD (V57) mají per-měsíc `pg_advisory_xact_lock`, `MAX+1` nad
  vlastní tabulkou, guard proti přetečení `>999` a **správné offsety** `SUBSTRING`: faktura od 7
  (prefix 6 znaků), dobropis od 9 (`OD` + 6), PPD od 10 (`PPD` + 6). Řady jsou vzájemně nezaměnitelné.
- Číslo se u faktury i dobropisu přiděluje až přechodem do `ISSUED` (podmíněný `BEFORE UPDATE`
  s `WHEN`), takže koncepty řadu nespotřebovávají; stornovaná faktura si číslo ponechá (žádné
  recyklování).

**Stavový automat a souběh**
- `InvoiceStatus.ALLOWED_TRANSITIONS` odpovídá dokumentaci; `PAID` i `CANCELLED` jsou terminální.
- Všechny čtyři mutace mají guardovaný zápis: `updateStatus` (`WHERE status = expected`),
  `InvoiceMapper.update` (`AND status = 'DRAFT'`), `InvoiceItemMapper.insert`
  (`INSERT … SELECT … WHERE EXISTS (… DRAFT)`), `update`/`deleteById` (`AND EXISTS (… DRAFT)`).
  0 řádků → `ConflictException INVOICE_STATE_CHANGED` (409), ne tichá mutace.
- Dvojí `issue` / dvojí `pay` je odmítnuto předkontrolou (`INVALID_STATUS_TRANSITION`) i guardem;
  pokryto `InvoiceLifecycleTest.issue_twice_isRejected`, `paid_isTerminal`.
- `update` neobejde automat — `originalStatus` se čte **před** `applyUpdate` (TD-49 opraveno,
  `InvoiceServiceImpl.java:198-200`), doloženo testem.
- Souběžné `createFromOrder` na jednu zakázku: service kontrolu má, a i kdyby prohrála závod,
  chytí to partial unique index `uq_invoices_order_active` (V48).
- `markPaid` je jedna transakce (`transitionTo` + `recordPayment`), takže úhrada se nezapíše
  k faktuře, jejíž přechod neprošel.

**Snapshoty**
- `createFromOrder` zmrazuje jméno zákazníka, číslo zakázky a **celé vozidlo** (SPZ, VIN, značka,
  model) do sloupců faktury (`InvoiceServiceImpl.java:112-119`), obě strany do `invoice_party`
  (`:122-155`) včetně bankovního spojení dodavatele.
- `InvoiceConverter.toDetailResponse:75-78` čte vozidlo **výhradně ze snapshotu**, nikde z živého
  `Order`/`Vehicle`. `buildDetail` sice `Order` načítá, ale konvertor ho na žádné pole nepoužije.
- `invoice_party` nemá `updated_at` ani UPDATE metodu v mapperu — snapshot je opravdu neměnný.
  Změna profilu firmy se do vystavené faktury nepromítne (doloženo
  `InvoiceLifecycleTest.createFromOrder_freezesSupplierPartyFromCompanyProfile`).
- Fakturační adresa se ověřuje proti vlastníkovi (`ADDRESS_NOT_OWNED_BY_CUSTOMER`).

**QR platba (SPAYD)**
- Formát `SPD*1.0*ACC:…*AM:…*CC:CZK*X-VS:…*MSG:…` odpovídá standardu; částka je `totalGross`
  se `setScale(2, HALF_UP)` a `toPlainString()` (žádná exponenciální notace), VS je variabilní
  symbol faktury, zpráva je zbavená diakritiky, hvězdiček a oříznutá na 60 znaků.
  Vyčlenění do `SpaydBuilder` umožňuje obsah testovat (`SpaydBuilderTest`).
  *(Chybějící guard na stav je nález F-1.)*

**Autorizace a kontrakt**
- Účetní úkony jsou vyhrazené vedení: `issue`/`pay`/`cancel` (`InvoiceController:104,119,134`),
  celý `CashReceiptController:29`, celý `CreditNoteController:30`, `PUT company-profile`
  (`CompanyProfileController:38`) — odpovídá dokumentované matici.
- `POST` endpointy vracejí 201 s `Location`; `createdBy` se plní ze `@AuthenticationPrincipal`,
  nikdy z DTO (R-04/N-06 dodrženo ve faktuře, PPD i dobropisu).
- SQL je výhradně v XML, tabulky plně kvalifikované, ENUM parametry přes `PgEnumTypeHandler`
  (R-01/R-02/N-05 dodrženo).
- Po INSERT/UPDATE se vždy načítá znovu z DB (`getById`/`fetchOrFail`/`fetchItemOrFail`) — R-03.

---

## Otevřené otázky pro uživatele

1. **Datum vystavení: přerazítkovat, nebo ohraničit?** (F-2) Má „Vystavit" použít dnešní datum
   a přepsat to, co bylo zadané při zakládání konceptu (jednodušší, řada je pak vždy chronologická),
   nebo má datum zůstat na obsluze a jen se validovat (nesmí být v budoucnu / do uzavřeného měsíce)?
   Souvisí s tím, jestli servis potřebuje vystavovat doklady zpětně.

2. **Dílčí hotovostní úhrady u PPD** (F-3). Dnes je každý PPD na plnou částku faktury, ale model
   i nápověda počítají s více doklady k jedné faktuře. Chcete (a) idempotentní PPD „jeden na fakturu",
   nebo (b) doplnit částku do requestu a hlídat součet proti faktuře? Varianta (b) je krok
   k odložené evidenci úhrad 1:N (TD-62).

3. **Má vystavení PPD samo označit fakturu jako zaplacenou?** Dnes ne — dokumentované rozhodnutí B
   („aplikace nezná realitu pokladny lépe než obsluha"). Důsledek: faktura s vystaveným PPD se dál
   počítá do dlaždice „po splatnosti" na přehledu, dokud někdo nesklikne „Označit zaplaceno".
   Souvisí i s tím, že `paid_amount` se ukládá s haléři (`totalGross`), zatímco PPD účtuje
   zaokrouhlené celé koruny — mají se srovnat?

4. **Storno faktury s vystaveným PPD/dobropisem: zakázat, nebo jen varovat?** (F-5)

5. **Dobropis nemá žádné uživatelské rozhraní.** Backend (API + PDF) je hotový a zdokumentovaný,
   ale ve `frontend/autoservis-frontend/src` se `credit-notes` nevolá nikde. Je to záměr (agenda se
   zatím dělá mimo aplikaci), nebo chybějící kus? Bez UI je nález F-4 méně pravděpodobný, s UI by
   jeho závažnost stoupla.

6. **Poznámka na faktuře se netiskne.** Pole „Poznámka" (`InvoiceDto.CreateRequest.note`, max 2000
   znaků) se ukládá a zobrazuje na detailu (`InvoicesPageDetail.jsx:168-170`), ale v `pdf/invoice.html`
   se nikde nevykresluje. Je poznámka interní (pak by to měl formulář říct), nebo má jít na doklad?

7. **„Tržby" na přehledu se počítají s DPH** (`DashboardMapper.xml:140-153` — `SUM(total_gross)`).
   Účetně se tržbou obvykle myslí částka bez DPH. Je to záměr (kolik peněz přiteklo), nebo se má
   dlaždice přepnout na `total_net`, případně popsat jako „Fakturováno s DPH"?

8. **Konstantní symbol se netiskne do QR platby.** Faktura ho na PDF ukazuje
   (`invoice.html:123-126`), ale `SpaydBuilder` `X-KS` nepřidává. Používáte KS reálně?
