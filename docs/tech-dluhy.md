# tech-dluhy.md — Technické dluhy a otevřené úkoly

> Živý dokument. Nový dluh = další číslo TD-xx (číslování pokračuje, nerecykluje se).
> Uzavřené položky přesunout do sekce „Vyřešeno" s datem.
> Priorita: 🔴 blocker · 🟠 vysoká · 🟡 střední · 🟢 nízká
>
> Všechny otevřené položky byly 2026-07-18 **ověřeny proti kódu** (revize při restrukturalizaci dokumentace).

---

## Audit 2026-07-24 — hloubkový audit + opravy (etapy E0–E8)

Kompletní podklady: [`audit/2026-07-24/`](../audit/2026-07-24/) — 9 dílčích reportů, přehled klíčových nálezů
(K-1…K-18), rozhodnutí (R-1…R-9), implementační plán se stavem etap ([plan-implementace.md](../audit/2026-07-24/plan-implementace.md)).
Slito do `devel`+`master` 2026-07-25 (spolu s větví `dashboard`).

**Vyřešeno v tomto auditu** (migrace V45–V55, suita 720 → 774 zelená, ~19 commitů; E7 rolová autorizace,
E8 úklid mrtvého kódu):
storno faktury už nezazdí zakázku (K-1); guard role posledního admina (K-2); číslování faktury podle
issue_date až při vystavení (K-3); UPDATE zákazníka zapisuje GDPR souhlas + full-replace mappery (K-4/K-11);
plný snapshot vozidla na faktuře (K-5); změna hesla odvolá sessions + hash refresh tokenů (K-6/K-7);
opravný daňový doklad §45 vč. PDF (K-8/E5); evidence úhrad + přehled po splatnosti (K-9/E2); odříznutí
ROLE_CUSTOMER + odstranění seed portálových účtů (K-10); ledger append-only + složený FK (K-13); vazba
zakázka↔vozidlo↔zákazník (K-12); inventurní šarže viditelné (K-14); JWT e2e test + reprodukovatelnost suity
(K-15); api.md cookies (K-16); FE fokus modalů + `addAlert` (K-17/K-18); web výjimky → 400/405, validace
stránkování, chyby AI → 503, párování `is_active`, completeness gate záporné ceny, testovatelný obsah SPAYD;
**E7** granulární rolová autorizace (matice R-9, TD-24) + bezpečnostní hlavičky + CORS z konfigurace
(TD-33/1 — pozn.: E7 CORS omylem **rozbil** login přes `@Value`+YAML seznam; opraveno 2026-07-25 přes `@ConfigurationProperties`);
**E8** úklid mrtvého kódu (mapper metody) + aktualizace souhrnných čísel v docs.

**Odloženo auditem jako sledovaný dluh** (rozhodnuto R-8; detail a zdůvodnění v plan-implementace.md):

### TD-56 ✅ VYŘEŠENO 2026-07-25 — „duchový zákazník" (embedded resultMap u vozidla)
`VehicleResultMap` vnořoval zákazníka bez `columnPrefix` → `is_active`/`created_at` kolidovaly se
stejnojmennými sloupci vozidla → `customer.active` v odpovědi nesl stav vozidla, ne majitele.
**Oprava:** `association columnPrefix="cust_"` + fragment `CustomerMapper.customerColumnsForVehicle`
(aliasy `cust_*`, jen mapované sloupce); `findById`/`search` ho používají. No-join dotazy zákazníka
už nevnořují — `VehicleServiceImpl.update` proto čte majitele z přímého `getCustomerId()` (ne z
`getCustomer()`). Test `VehicleServiceTest.getById_customerActiveReflectsOwnerNotVehicle` (suita 781).

### TD-57 ✅ VYŘEŠENO 2026-07-25 — Číselné řady ZAK: reset per rok + sjednocení mechanismu (R-5)
Původní V11 skládalo `ZAK-{rok}-{4č.}` z GLOBÁLNÍ sekvence `order_number_seq` → v novém roce nereset.
**Oprava (V56, vzor faktury V49):** `fn_generate_order_number` přepsán na per-rok MAX+1 + advisory lock;
řada se resetuje per rok a navazuje na existující data (žádná kolize se seedem), LPAD guard přes overflow
`>9999`; nepoužitá `order_number_seq` dropnuta. Testy `DatabaseTriggerTest` (MAX+1 navazuje = 0101; jiný
rok pořadí neovlivní). Suita ověřená plnou bránou.

### TD-58 ✅ VYŘEŠENO 2026-07-25 — TOCTOU guarded-write při editaci DRAFT faktury (audit S-4)
Editace hlavičky/položek DRAFT faktury byla check-then-act (`requireEditable` ověřil DRAFT, zápis už na
stav neguardoval) → souběžný `issue()` mohl v tom okně zmutovat vystavený doklad. **Oprava (vzor K5):**
guardovaný zápis na všech čtyřech mutacích, 0 řádků → 409 `INVOICE_STATE_CHANGED`: `invoiceMapper.update`
`AND status='DRAFT'`; `invoiceItemMapper` `insert` (INSERT…SELECT WHERE EXISTS DRAFT) / `update` / `deleteById`
`AND EXISTS(faktura DRAFT)`. Sdílený helper `invoiceStateChanged`. Testy: `InvoiceStatusTransitionTest`
(mapper-level, ISSUED→0 řádků, DRAFT→1). Suita 786.

### TD-59 ✅ VYŘEŠENO 2026-07-25 — Validace draftu příjemky (audit S-6/S-7)
`PUT /receipts/{id}/draft` přijímal surový model bez `@Valid`. Opraveno v `ReceiptReviewServiceImpl.updateDraft`:
(1) `requireWellFormedDraft` — neúplný payload (např. `{}`) → **400** místo NPE→500;
(2) `sanitizeClientDraft` — kódem-vlastněné stavy z klienta (VERIFIED = jen deterministická kontrola,
DEFAULTED = assembler) se srazí na VERBATIM, pipeline (`verify()`/`fillDerivedValues`) je legitimně přepočte,
padělaný `VERIFIED` se nezaslouží (hranice „AI čte, kód počítá"); (3) `documentType`/`sourceChannel` se berou
autoritativně ze sloupců příjemky, ne z těla. Testy: `ReceiptReviewServiceTest` (malformed→400, padělaný
VERIFIED sražen, documentType ze sloupce). Suita 789.

### TD-60 ✅ VYŘEŠENO 2026-07-31 — Frontend chybové stavy, double-submit, seznam po splatnosti (audit F4/F5/F6, E2.2, E6.7 + KN-14)
Původně: create/edit stránky zahazovaly `err.problem.detail`; CRUD formuláře bez `saving` guardu (dvojklik → duplicita);
detaily/seznamy bez ošetření chyby načtení; chyběl FE seznam „faktury po splatnosti".

**První vlna oprav 2026-07-26** ([oprava-chybove-stavy-2026-07.md](oprava-chybove-stavy-2026-07.md)):
1. mutace i načítání zobrazují konkrétní `err.problem.detail` (vč. `DashboardPage`); zároveň opraven bug změny hesla
   (`ResponseEntity<String>` → 204) a přeloženy všechny anglické backend hlášky do češtiny;
2. ochrana proti dvojkliku centrálně v `FormActions` (blokace tlačítek dokud `onSubmit` promise neproběhne) — pokrývá všechny formuláře;
3. `InvoicesPage` má filtr „Splatnost → Po splatnosti" (deep-link `?overdue=true`), dashboard dlaždice na něj míří.

⚠️ **Tehdejší zápis „vyřešeno" byl nadsazený** (audit 2026-07-30, KN-14 / 11-F-2): oprava doplnila hlášky
do **existujících** `catch` bloků, ale 9 seznamů a 5 detailů žádný catch nemělo — 500 se dál ukazovala jako
„Zatím žádní zákazníci." a 404 na detailu jako věčný spinner. Dokument o opravě přitom mluvil o „~13 catch
blocích u načítání". Poučení: **u FE dluhu nestačí build a rozvaha, chce to proklik** — právě proto se ta
polovina rozsahu nechala zavřít.

**Dotaženo 2026-07-31 (Vlna 4):**
4. `problemMessage(err, fallback)` v `api/api.js` skládá `detail` **i** `errors[]` — konkrétní validační
   hláška se poprvé dostane k uživateli; použito na **43 místech** (dřív ho uměla jedna komponenta z dvaceti);
5. všech 9 seznamů a 5 detailů má `try/catch` + `LoadErrorState` (hláška + „Zkusit znovu"), resp. `ErrorState`
   s cestou zpět; prázdný a chybový stav jsou rozlišené, ocenění skladu už nelže „0,00 Kč";
6. `api/validation.js` zrcadlí serverová DTO (IČO, DIČ, telefon, délky) — formulář zákazníka už není
   volnější než server;
7. `LoginPage` nehlásí anglické „Failed to fetch".
Ověřeno: FE build + `npm run check` (10 pravidel) + **proklik v prohlížeči** (chybové stavy vynucené blokací requestů).

### TD-66 🟢 Uživatelské hlášky přes i18n katalog (`messages.properties`)
Uživatelské chybové texty jsou dnes **inline** v kódu (service výjimky, DTO validace, FE fallbacky) — pragmatické
a správné pro jednojazyčnou aplikaci, ale text je rozházený a rozšíření o další jazyk = zásah do kódu.
**Cíl:** texty vést v katalogu klíčovaném **kódem chyby** (`RESOURCE_NOT_FOUND`, `DUPLICATE_VIN`…) a překládat
přes `MessageSource` — vzor, který projekt **už používá** u custom validátorů (`CUSTOMER_NAME_REQUIRED` →
`messages.properties`). Přínos: text na jednom místě, přidání jazyka = nový `messages_xx.properties` bez změny kódu.
Kontext: [oprava-chybove-stavy-2026-07.md](oprava-chybove-stavy-2026-07.md).

### TD-61 ✅ VYŘEŠENO 2026-07-25 — GlobalExceptionHandler: NoResourceFoundException → 404 (audit S-5)
Neznámá cesta padala do catch-all → 500. **Ověřeno:** backend nemá žádný SPA fallback (žádné
`addResourceHandlers`/`forward:/index.html`/`static/`; SPA servíruje nginx/Vite) → obava auditu o kolizi
neplatí. Přidán `@ExceptionHandler(NoResourceFoundException.class)` → **404** `NOT_FOUND`. Test
`ProblemDetailContractTest.unknownPath_returnsNotFound`.

### TD-62 🟡 Dobropis a evidence úhrad — rozšíření (E5/E2, R-2/R-3)
Částečný dobropis (podmnožina/vlastní částky) a plná evidence úhrad 1:N (částečné úhrady, přeplatky) jsou MVP
záměrně odložené. Před ostrým provozem potvrdit náležitosti dobropisu a číslování s účetním (R-7).

### TD-63 🟢 Produkční příprava — seed hesla, checklist (audit E7.4)
Zbytek balíku „než to pustíme ven" po E7: změnit seed hesla `Password1!`, doplnit placeholder v
company_profile, ověřit `SPRING_PROFILES_ACTIVE=prod` (jinak `cookie-secure=false`). Netestovatelné
z aplikace → produkční runbook.
**Doplněno auditem 2026-07-30 (KN-5):** založit **druhý účet s rolí ADMIN** pod jiným jménem než
`admin`. Zamčený účet umí odemknout jen jiný administrátor; od V64 sice zámek po 15 minutách sám
vyprší, ale útočník opakující požadavky dokáže jediný admin účet držet nepoužitelný trvale.
Krok je v runbooku ([nasazeni.md §7b](nasazeni.md), bod 7). *(Rolová autorizace a security hlavičky hotové v E7 — viz Vyřešeno / TD-24;
CORS z konfigurace opraveno 2026-07-25 — E7 ho rozbil, viz TD-33.)*

### TD-64 🟢 Zbytek mrtvého kódu (po E8.3)
*Mrtvé mapper metody smazány v E8.3 (2026-07-24); souhrnná čísla v `api.md` aktualizována 2026-07-25
(21 controllerů, 101 endpointů, 24 mapperů, 18 konvertorů, 18 handlerů, 55 migrací, 75 test tříd).*
Zbývají dva kandidáti ponechané kvůli testovému pokrytí (mazat = rušit i testy, diskutabilní):
`InvoiceConverter` overloady (1-arg/3-arg — bez produkčního volajícího, jen v `InvoiceConverterTest`) a
`GET /warehouse/products/import/{id}` (bez FE volajícího; service `getByGoodsReceiptId` má testy).

---

## Backend — Java vrstva

### TD-11 🟡 `internal_note` viditelný pro všechny role
`DetailResponse` (customer/vehicle/order) obsahuje `internalNote` — budoucí zákaznický portál ho nesmí vidět. Souvisí s TD-22. Odloženo do doby portálu.

### TD-22 🟡 `@JsonView` — viditelnost citlivých polí podle role
`purchasePrice` (jen ADMIN/MANAGER), `internalNote` (ne CUSTOMER). Řešení přes `Views.Basic`/`Views.Manager` + výběr pohledu podle role. **Odloženo — před spuštěním zákaznického portálu.**

### ~~TD-24~~ ✅ Role-based security — granulární přístup → **vyřešeno E7 (2026-07-24)**
Matice role × operace (audit R-9): vedení-only účetní/správní úkony přes inline `@PreAuthorize`
(faktura issue/pay/cancel, dobropis, (de)aktivace zákazníka/vozidla, profil firmy, uzavření inventury);
`/users` ADMIN-only; baseline `/api/**` = pracovní role. Test `RoleAuthorizationTest`.

### TD-13 🟢 `created_by` nullable bez business důvodu
Přijatelné pro výukový projekt (seed data nemají created_by).

### TD-68 🟢 Autorizace: hodnota skladu a ruční skladové pohyby vidí/dělá i mechanik
**Zjištěno při kompletní kontrole skladu (2026-07-29).** `StockValuationController` (GET hodnota
skladu) ani `ProductController` (`POST /products/{id}/movements` — ruční korekce/odpis/vratka/
spotřeba) nemají `@PreAuthorize` → jsou baseline, takže i **MECHANIC** vidí celkovou finanční
hodnotu skladu a smí dělat stav-měnící odpisy/vratky.
**Stav:** dle dokumentovaného modelu (TD-24 / R-9: baseline `/api/**` = pracovní role) je to
**záměr**, ne chyba — ostrá vedení-only omezení jsou jen u účetních úkonů (faktura, dobropis,
pokladna, uzavření inventury). Otázka je, zda má mechanik vidět hodnotu skladu a provádět korekce.
**Rozhodnutí (odloženo):** buď ponechat (mechanik korekce běžně dělá), nebo omezit na
ADMIN/MANAGER přidáním `@PreAuthorize("hasAnyRole('ADMIN','MANAGER')")` (vzor: `StockTakeController.close`).
Nízké riziko (interní aplikace pro zaměstnance), neblokuje produkci.

---

## Databáze

### TD-16 🟢 Schéma `order` — rezervované slovo
Vyžaduje uvozovky v každém XML (`"order".orders`) a název neodpovídá doméně (zakázky). Oprava = přesun do schématu `workshop`/`repair`; velký zásah, odloženo.

### TD-41 🟢 `goods_receipts.source_pdf` nese i fotky a ISDOC
**Problém:** sloupec se jmenuje `source_pdf`, ale od E7/E8 v něm může být fotka/sken dokladu (JPG, PNG, HEIC); u ISDOC importu zůstává prázdný.
**Dopad:** kosmetický — název sloupce neodpovídá obsahu, stejný typ kompromisu jako TD-40.
**Oprava:** přejmenovat na `source_file` až při větší přestavbě tabulky; sémantiku drží `source_channel` a dokumentace.

### TD-40 🟢 `goods_receipts.invoice_number` nese i čísla dodacích listů
Od V39 je sloupec sémanticky „číslo dokladu" (dle `document_type` faktura nebo dodací list), ale jmenuje se `invoice_number`. Přejmenování by se rozlilo do mapperů, domény, DTO i FE za kosmetický zisk — přijatý kompromis, sémantiku drží `COMMENT ON COLUMN` (V39). Přejmenovat jen při větší přestavbě tabulky.

### TD-65 🟢 V58 mísí DDL schématu `employee` s demo daty
**Problém:** `V58__init_employee_schema.sql` (schéma `employee` — DDL nutné všude) zároveň seeduje 4 demo zaměstnance, a seed #1 má FK `user_id = 3` na mechanika z V3. V produkci V3 neběží → seed by spadl na `fk_employees_user`. Zároveň V59 (`db/migration`) na schéma `employee` závisí, takže V58 nelze z produkce prostě vynechat.
**Řešení (E5, model tří locations):** V58 má **dvě varianty se stejným číslem** — `db/demo/V58` (DDL + demo seed, jen dev/test) a `db/prod/V58` (schema-only, jen prod). Locations se nikdy nepřekrývají, takže nekolidují. Duplikuje se ~40 řádků DDL, ale V58 je immutable (nezmění se), takže dvojče je „zamrzlé".
**Pravidlo pro budoucnost:** demo/ukázková data patří **jen** do `db/demo`, schéma migrace (`db/migration`) žádná demo data neobsahuje (a už vůbec ne s FK na demo řádky). Viz `konvence.md §14`. Dluh je připomínkou vzoru — nový modul ať nemíchá DDL a demo seed v jedné migraci.

### TD-67 🟢 `order.orders.is_active` je nepoužívaný (zakázky nemají soft-delete)
**Problém:** sloupec `is_active` vznikl na začátku ze scaffold vzoru „každá entita má soft-delete" ([V6](../src/main/resources/db/migration/V6__init_order_schema.sql)), jenže zakázky mají vlastní stavový automat včetně `CANCELLED`. Soft-delete zakázky je proti smyslu dokladu (odkazují na ni faktury a skladové pohyby) a **backendový endpoint pro (de)aktivaci nikdy neexistoval** — FE tlačítka „Deaktivovat/Aktivovat" volala neexistující `DELETE /orders/{id}` a `POST /orders/{id}/activate` a vždy spadla. Sloupec je navíc vždy `TRUE` (nic ho nikdy nepřepnulo; historicky kolem něj byl i bug TD-51).
**Rozhodnutí (2026-07-27):** funkce zrušena. `is_active` plumbing odstraněn ze zakázek — FE akce/sloupec „Záznam"/detail tlačítko pryč, backend přestal `is_active` mapovat i selektovat (`Order`, `OrderDto`, `OrderConverter`, `OrderMapper` resultMap + `orderColumns`). Rušení řeší výhradně stav `CANCELLED`.
**Zbývá (odloženo):** DB sloupec `order.orders.is_active` **ponechán** — ničemu nevadí (vždy TRUE), WHERE guardy `is_active = TRUE` v `search`/`countSearch`/`countOpenBy*` zůstaly jako neškodné no-opy. Úplný drop = samostatná migrace `V{n+1}` + odstranění těch guardů; malý zisk, odloženo na větší přestavbu tabulky (stejný typ kompromisu jako TD-40/TD-41).

---

## Konfigurace

### TD-33 🟢 company_profile a seed hesla před produkcí *(splynulo s TD-63)*
1. CORS originy: **E7 (2026-07-24) je přesunul do konfigurace, ale rozbil tím login** (`@Value` neuměl
   navázat YAML seznam → prázdné originy → „Invalid CORS request"). **Opraveno až 2026-07-25** samostatnou
   větví (`@ConfigurationProperties`, viz Vyřešeno). *(Dřívější zápis „vyřešeno E7" byl chybný — E7 to
   nezavřel, E7 to způsobil.)*
2. `billing.company_profile` obsahuje placeholder „DOPLŇTE NÁZEV FIRMY" — faktury by nesly nesmyslného dodavatele.
3. Seed hesla `Password1!` — změnit.
**Charakter:** produkční checklist, ne kód k refaktoringu. Body 2–3 sleduje **TD-63** (jeden runbook „než to pustíme ven").

---

## Frontend

### TD-42 ✅ VYŘEŠENO 2026-07-25 — Adresu existujícího zákazníka nelze změnit
**Řešení (varianta B):** `CustomerDto.UpdateRequest.addresses` je volitelné pole (`null` = neměnit,
neprázdný seznam = full-replace celé sady; vzor TD-23). `CustomerServiceImpl.update` je `@Transactional`
a při dodaných adresách validuje (`AddressSetValidator`) → `AddressMapper.deleteByCustomerId` → insert
(`isDefault = BILLING`, stejně jako create). Nic nedrží FK na `address.id`, faktura má immutable snapshot
(`invoice_party`) → přepis neovlivní vystavené doklady. FE: `CustomerForm` ukazuje adresní sekci i v editu,
`customerPayload.toUpdatePayload` posílá `addresses`. Testy: `CustomerCrudServiceTest` (+2: přepis sady;
`null` = beze změny). Backend suita 776 zelená; FE build/render ověřen v prohlížeči, plný proklik vyžaduje přihlášení.
*(Historie problému níže ponechána jako záznam.)*
**Problém (historický):** `CustomerDto.UpdateRequest` nemá pole `addresses`, takže `PUT /customers/{id}` adresy
ignoruje. Adresa jde zadat **jen při zakládání** zákazníka; potom už ne — přes UI ani přes API.
**Dopad:** běžná provozní situace (zákazník se přestěhuje, firma změní sídlo) nemá řešení.
Fakturační adresa přitom putuje na fakturu, takže se chyba propíše do dokladu.
**Zjištěno:** 2026-07-21 při U0.1 — `CustomerForm` adresní sekci v edit režimu proto skrývá
a nahrazuje ji poznámkou, aby uživateli nepředstírala editaci.
**Oprava:** doplnit `addresses` do `UpdateRequest` + upsert v `CustomerServiceImpl`
(pozor na `isDefault` a na to, že faktura drží snapshot, ne referenci), pak vrátit adresní
sekci do edit režimu `CustomerForm`. Vyžaduje zásah do backendu, proto mimo `plan-ui.md`.

### TD-48 🟢 VYŘEŠENO 2026-07-23 — modal se schovával za obsah stránky (z-index / stacking kontext)
**Problém:** `ChangePasswordModal` vyvolaný ze sidebaru se vykreslil **ZA** kartami formuláře
na „Nastavení firmy" — dialog i backdrop byly pod obsahem. Příčina: `Modal` se renderoval inline
tam, kde byl vyvolán, tedy uvnitř `<nav id="sidebar">`, který má `position: sticky` a tím
**vytváří stacking kontext**. `z-index: 1055` dialogu proto platil jen uvnitř sidebaru; sidebar je
v DOM před `#main-content`, takže se obsah stránky vykreslil nad celým sidebarem včetně modalu.
Modaly z běžných stránek fungovaly jen náhodou — jsou uvnitř `#main-content`, který se kreslí až
po sidebaru.
**Dopad:** každý dialog vyvolaný z prvku zakládajícího stacking kontext (sidebar) byl nepoužitelný.
Toasty (`AlertContainer`) byly nad modalem taky jen shodou pořadí vykreslení, ne dle z-indexu —
byly v jiném kontextu.
**Zjištěno:** 2026-07-23, uživatel („otevři nastavení firmy a stiskni změnu hesla — je to děs").
**Proč to revize U6 minula:** ověřoval jsem `z-index` jako čísla (1080 > 1055 > 1050) a `elementFromPoint`
jen na modalu z detailu vozidla (uvnitř `#main-content`, funguje). Nikdy jsem nezkontroloval, že je
modal ze sidebaru **vizuálně** nahoře nad obsahem. Z-index čísla nelze porovnávat napříč stacking
kontexty — měřil jsem čísla, ne vrstvení.
**Oprava:** `Modal` i `AlertContainer` se renderují **portálem do `document.body`** (`createPortal`).
Tím unikají všem nadřazeným kontextům a z-index platí globálně. Nové pravidlo 3b v `check-ui.mjs`:
celoobrazovková vrstva (`modal-backdrop`/`position-fixed`/`.modal show d-block`) bez `createPortal`
je nález. Ověřeno `elementFromPoint` na třech bodech dialogu (vše „modal") + screenshot na desktopu
i mobilu.

### TD-47 🟢 VYŘEŠENO 2026-07-22 — čas u zakázky se posouval při každém uložení
**Problém:** `toDatetimeLocal()` (zkopírovaná v `OrderForm.jsx` i `OrdersPageEdit.jsx`) usekávala
ISO řetězec ze serveru na 16 znaků. Server vrací UTC, ale `<input type="datetime-local">` pracuje
s místním časem — z „14:00 UTC" se tak stalo „14:00 místního času". Editace ukazovala o posun zóny
jiný čas a **každé uložení hodnotu o tentýž posun odsunulo**, kumulativně (v létě 2 h, v zimě 1 h).
**Dopad:** `estimatedCompletionAt` i `completedAt` u zakázek. Termín slíbený zákazníkovi se tichým
způsobem měnil pokaždé, když někdo zakázku otevřel a uložil — bez jakéhokoli varování.
**Zjištěno:** 2026-07-22 při ověřování U5.1 průchodem uložením (dvě uložení za sebou:
`14:00Z → 12:00Z → 10:00Z`). Detail zakázky chybu neměl — používá `formatDate`.
**Oprava:** `toDatetimeLocal`/`fromDatetimeLocal` sjednoceny do `api/format.js`; převod jde přes
`getTimezoneOffset()`, takže pole ukazuje místní čas a zpětný převod je bezeztrátový. Ověřeno
dvěma uloženími beze změny hodnoty. Poškozená zakázka ZAK-2026-0001 vrácena na `14:00Z` podle
seedu ve `V8__seed_vehicles_and_orders.sql`.

### TD-46 ✅ VYŘEŠENO 2026-07-25 — řazení seznamů
**Stav:** dokončeno (fáze U3R v `plan-ui.md`). Ověřeno 2026-07-25 gripem: `sortDesc` se používá
ve **všech 8 mapperech** (Customer/Invoice/Order/User/Vehicle/ReceiptReview/Supplier/Warehouse) a
i větev `<otherwise>` směr respektuje (např. `OrderMapper.orderSortOrder` — `o.created_at <if sortDesc>DESC`).
Whitelist mají i zakázky (`orderSortOrder`) a příjemky (`ReceiptReviewMapper`). Původní „ČÁSTEČNĚ"
zápis byl zastaralý. *(Historie problému a opravy níže ponechána jako záznam.)*
**Problém (historický):** `SearchParams.sortDesc` přijme DTO, ale **v žádném XML mapperu se nepoužívá**
(grep `sortDesc` v `src/main/resources/mapper/` nevrací nic) — `ORDER BY` se skládá bez
`ASC`/`DESC`, takže řazení je vždy vzestupné. Navíc `sortBy` respektují jen **dva** mappery:
`CustomerMapper.xml:194-200` (lastName, companyName, customerNumber) a `UserMapper.xml:187-201`
(username, email, lastLoginAt). Vozidla, zakázky, faktury, produkty, dodavatelé a příjemky mají
pevné `ORDER BY` a parametr zahodí.
**Dopad:** UI řazení klikem na hlavičku (rozhodnutí R-5, úkol U3.1) je nedodatelné — druhý klik
na sloupec nezmění pořadí, protože server vrátí totéž. Ověřeno voláním API:
`sortBy=lastName&sortDesc=true` vrací stejné pořadí jako `sortDesc=false`.
**Zjištěno:** 2026-07-21 při ověřování U3.1.
**Oprava (hotovo):** směr doplněn dovnitř každé větve whitelistu (fallback `<otherwise>` si nese
vlastní pevný směr, přidat směr za celý `<choose>` by dalo "DESC DESC"). Whitelisty přidány do
CustomerMapper, UserMapper, VehicleMapper, InvoiceMapper, WarehouseMapper a SupplierMapper;
`sortBy` přesunuto do `SearchParams` (dřív jen v Customer/User), smazán mrtvý `orderBy`
z Vehicle/Order params. **`BaseParams.sortDesc` změněn z `true` na `false`** — dokud se parametr
ignoroval, seznamy fakticky řadily vzestupně; ponechat `true` by tiše obrátilo pořadí všem
volajícím, kteří ho neposílají. Pokryto `ListSortingTest` (7 testů, suite 166 zelená).

### TD-43 🟢 MUI kvůli třem prvkům
**Problém:** `@mui/material` + Emotion se táhnou do bundlu (880 kB) kvůli `TableRowActionMenu`
(Menu, IconButton), `PaginatorRounded` (Pagination) a ikonám uvnitř menu.
**Dopad:** dvojí vizuální jazyk a velikost bundlu; jinak funkční a přístupné.
**Rozhodnutí R-1 (2026-07-21):** MUI **zůstává** v dnešním rozsahu, sjednotí se jen ikony
uvnitř menu na Bootstrap Icons. Náhrada Bootstrap dropdownem + vlastním stránkováním je
samostatný pozdější úkol, ne součást `plan-ui.md`.

### TD-44 🟡 Systematický audit přístupnosti
**Problém:** sjednocení UI (fáze U1) přineslo `aria-modal`, focus trap, popisky filtrů a `scope`
na hlavičkách, ale jde o vedlejší produkt, ne o audit. ⚠️ **A `scope` v tom zápisu bylo tvrzení,
ne skutečnost** — audit 2026-07-30 (11-F-16) našel `scope="col"` jedině ve sdílené `DataTable`;
všech sedm ručně psaných tabulek ho nemělo.
**Dopad:** neznámý stav kontrastů, ovládání čtečkou a plné klávesové obsluhy.
**Vlna 4 auditu (2026-07-31) uzavřela jmenovité nálezy**, ne celý audit: `aria-label` na řádkovém
menu (11-F-8), `htmlFor`/`id` na 22 polích příjemky, položky zakázky a hlavičky zakázky,
`aria-label` na editovatelných buňkách soupisu a inventury, `scope="col"` do všech ručně psaných
tabulek (11-F-16), kontrast hlaviček z 4,22:1 na ≈7:1 (11-F-17), přihlašovací obrazovka
(11-F-14) a „Nastavení firmy" jako skutečný `<form>` (11-F-15). Dvě z těch vad hlídá
od té doby `check-ui.mjs`.
**Zbývá:** projít **tab pořadí** a fokusové stavy, ovládání odečítačem end-to-end a zbytek
kontrastů (`text-muted` na barevných pozadích) — tedy systematická část. Rozhodnutí uživatele
2026-07-31: laťkou jsou zatím jmenovité nálezy, plný audit WCAG AA je samostatná etapa.

### TD-45 🟢 Anglický technický text v datech vozidla
**Problém:** historie tachometru ukazuje poznámku „Initial reading migrated from
vehicles.current_mileage_km“ — text ze seedu/migrace viditelný obsluze.
**Oprava:** přepsat v datech (migrace `UPDATE`), ne v UI. Datová, ne UI záležitost.

---

## Nálezy z pokrývání testy (2026-07-23) — ✅ VŠECHNY VYŘEŠENY

> Následující dluhy odhalila fáze pokrytí testy (uzavření TD-14) a **byly téhož dne opraveny**
> (uživatel schválil „oprav to ty"). Každý měl test, který dokládal chybné chování; po opravě
> byl přepsán na správné očekávání. Produkční změny: `GlobalExceptionHandler`, `VehicleConverter`,
> `PagedResponse`, `BlacklistMapper`, `InvoiceServiceImpl`, `OrderMapper.xml`, `SupplierMapper.xml`.

### TD-49 ✅ VYŘEŠENO 2026-07-23 — `PUT /invoices/{id}` obcházel stavový automat faktury
**Problém:** `InvoiceServiceImpl.update` chrání stav řádkem `updated.setStatus(existingInvoice.getStatus())`,
jenže `applyUpdate` mutuje objekt **na místě a vrací tutéž referenci** — `updated` i `existingInvoice`
jsou jeden objekt, který už v tu chvíli nese stav z requestu. Přiřazení je **no-op**.
**Dopad:** z DRAFT faktury lze přes PUT udělat rovnou PAID, mimo `canTransitionTo` i mimo guardovaný
UPDATE (K5). Omezeno na DRAFT (mimo něj `requireEditable` update zakáže), ale i tak obchází celý automat.
Potvrzeno mutačně: mutant „removed call to `Invoice::setStatus`" (řádek 180) přežije = mrtvý kód.
**Oprava (hotovo):** stav se zapamatuje do `originalStatus` PŘED `applyUpdate` a nastaví zpět.
Test: `InvoiceLifecycleTest.update_cannotBypassStateMachine`.

### TD-50 ✅ VYŘEŠENO 2026-07-23 — `PagedResponse.first`/`last` počítal 0-based, API je 1-based
**Problém:** `PagedResponse.of()` nastavuje `first(page == 0)` a `last(page >= totalPages - 1)`, ale
`page` je podle `api.md` **1-based** (první stránka = 1).
**Dopad:** `first` je **vždy false** (paginátor nepozná první stránku) a `last` se rozsvítí **o stránku
dřív** — na předposlední stránce, takže **poslední stránka je pro uživatele nedosažitelná** (FE zakáže
„další"). Postihuje všechny stránkované seznamy.
**Oprava (hotovo):** `first = page <= 1`, `last = page >= totalPages`.
Testy: `PagedResponseTest` (unit, matice stránek) + `ProblemDetailContractTest.pagedResponse*`.

### TD-51 ✅ VYŘEŠENO 2026-07-23 — `OrderMapper.xml` nečetl `is_active`, zakázky měly v API vždy `active=false`
**Problém:** SQL fragment `orderColumns` sloupec `is_active` nevybírá a `OrderResultMap` ho nemapuje,
takže `Order.active` zůstane na Java defaultu `false`. Data v DB jsou přitom správně (`NOT NULL DEFAULT TRUE`).
**Dopad:** `OrderDto.DetailResponse` i `ListResponse` nesou u **každé** zakázky `active=false` bez ohledu
na skutečný stav.
**Oprava (hotovo):** `o.is_active` doplněno do `orderColumns` + `<result property="active"
column="is_active"/>` do `OrderResultMap`.
Test: `OrderCrudServiceTest.detailResponse_activeFlag_reflectsDatabase`.

### TD-52 ✅ VYŘEŠENO 2026-07-23 — nečíselné `id` v cestě vracelo 500 místo 400
**Problém:** `MethodArgumentTypeMismatchException` (např. `/vehicles/abc`) nemá `@ExceptionHandler`
v `GlobalExceptionHandler`, spadne do catch-all na `Exception`.
**Dopad:** překlep v URL vypadá jako pád serveru (500 `INTERNAL_ERROR`) a plní log úrovní ERROR, což
maskuje skutečné chyby. Správně má jít o 400 (klient poslal nesmysl).
**Oprava (hotovo):** přidán `@ExceptionHandler(MethodArgumentTypeMismatchException.class)` → 400
`INVALID_ARGUMENT`. Test: `ProblemDetailContractTest.nonNumericPathId_returnsBadRequest`.

### TD-53 ✅ VYŘEŠENO 2026-07-23 — `logout` nebyl idempotentní
**Problém:** `BlacklistMapper.save` dělá prostý `INSERT` do `security.token_blacklist`, kde je `token`
primární klíč. Druhé odhlášení stejným access tokenem skončí `DuplicateKeyException` → 422
`DATA_INTEGRITY_VIOLATION`.
**Dopad:** dvojklik na „Odhlásit se", opakování požadavku po výpadku sítě nebo odhlášení ze dvou panelů
prohlížeče vrátí uživateli chybu, ačkoli odhlášení má být idempotentní.
**Oprava (hotovo):** `BlacklistMapper.save` → `INSERT ... ON CONFLICT (token) DO NOTHING`.
Test: `RefreshTokenRotationTest.logout_calledTwice_isIdempotent`.

### TD-54 ✅ VYŘEŠENO 2026-07-23 — `SupplierMapper.update` měl PATCH sémantiku, vyplněné pole nešlo vymazat
**Problém:** dynamický `<set>` skládá jen `<if test="x != null">`, takže `null` pole se do UPDATE vůbec
nedostane. Jednou vyplněné IČO, IBAN nebo e-mail dodavatele už přes `PUT /suppliers/{id}` nelze vynulovat.
**Dopad:** odlišné od ostatních modulů (full-replace) — nekonzistentní chování API; obsluha nemá jak
opravit omylem zadaný údaj na prázdný.
**Oprava (hotovo):** `SupplierMapper.update` sladěn s ostatními moduly na full-replace (statické
`SET`); `country_code` (NOT NULL) ponechán přes `COALESCE`, protože ho vymazat nejde.
Testy: `SupplierServiceTest.update_blankRegistrationNumber_clearsStoredValue`,
`update_omittedOptionalFields_areCleared`, `update_omittedCountryCode_isKept`.

### TD-55 ✅ VYŘEŠENO 2026-07-23 — `VehicleConverter` bez null guardu na `getCustomer()`
**Problém:** `toDetailResponse`/`toListResponse` volaly `vehicle.getCustomer().toSummaryResponse()` bez
kontroly na null → vozidlo bez načteného majitele shodilo request na NPE.
**Oprava (hotovo):** obě metody obalují přístup k majiteli `if (getCustomer() != null)`.
Test: `VehicleConverterTest.toDetailResponse_vehicleWithoutCustomer_doesNotThrow`.

---

## Testy

### TD-14 ✅ VYŘEŠENO 2026-07-23 — pokrytí testy
**Stav při uzavření:** **69 testovacích tříd, 720 testů** (celá suite zelená, 1 přeskočen = manuální
PDF test mimo CI). Větvové pokrytí **75,3 %**, instrukční **86,8 %** (z baseline 53,6 % / cca 78 %).
Zahrnuje i testy 7 oprav TD-49…TD-55 odhalených během pokrývání.

**Zavedené nástroje:** JaCoCo (report + `check` s prahy: BUNDLE instrukce ≥ 80 %, větve ≥ 68 %; větvové
pokrytí kritických balíčků `service`/`service.impl`/`security.service`/`model.converter` ≥ 65 %) a PIT
(mutační testování `service.*` + `security.service.*`, spouští se ručně). Detail: `backend.md` §7.

**Doloženo mutačně (PIT), ne jen řádkově:** sweep `service.*` + `security.service.*` = **1063 mutací,
891 zabitých (84 %), test strength 88 %**. Klíčové balíčky výš: konvertory a enumy 100 %,
`DraftVerificationService` 98 %, `DraftAssembler` 97 %, `security.service` 94 %, service.impl třídy
89–97 %. Přeživší mutanti jsou buď pokrytí, nebo zdůvodnění jako ekvivalentní/redundantní guardy
(viz `plan-testy.md`). Mutační testování cestou odhalilo **plané testy** (např. ověření zápisu přes
MyBatis cache místo DB) a **7 skutečných chyb** (TD-49…TD-55).

**Rozsah:** service vrstva (CRUD, business výjimky, soft-delete, audit), stavové automaty faktury/
inventury/review v obou větvích, MyBatis whitelist ORDER BY, web kontrakt ProblemDetail (RFC 9457),
security (rotace refresh tokenu + detekce reuse, JwtService, lockout, blacklist), DB triggery (čtení
vygenerované hodnoty), hranice „AI čte, kód počítá" (deterministické kontroly draftu), časy/zóny.

**Historie — stav 2026-07-22:** 23 testovacích tříd, **166 testů** (celá suite zelená) — přibyl `ListSortingTest` (řazení seznamů, TD-46).

**Historie — stav 2026-07-21:** 22 testovacích tříd, 159 testů. Rozvoj skladu (plán E1–E8) přidal `ManualStockMovementTest`, `StockValuationTest`, `StockTakeTest`, `IsdocImportTest`, `LowStockTest`, `ProductUnitValidationTest`, `WarehouseImportPropertiesTest` a rozšířil `ReceiptReviewServiceTest` o storno a měnu.

**Historie — stav 2026-07-20:** 12 testovacích tříd, 67 testů (celá suite zelená). Přestavba importu přidala: unit `DraftAssemblerTest` + `DraftVerificationServiceTest` + `ProductMatchingServiceTest` (18 testů), integrační `WarehouseImportServiceTest` (5) + `ReceiptReviewServiceTest` (8) a manuální `PdfDocumentExtractionManualTest` (reálné API, mimo CI — `-Dmanual.extraction=true`). Základna `AbstractIntegrationTest` (singleton container, viz `backend.md` §7). Dál doplňovat souběžně s vývojem; prioritně `InvoiceService` (stavový automat).

---

## ✅ Vyřešeno

| TD | Co | Jak / kdy |
|---|---|---|
| TD-01–07 | Frontend bugy VehiclesPageEdit + ostatní (name atributy, POST/PUT větve, navigate, backPath, překlep souboru, confirm dialog) | Vehicle Phase 1, 2026 |
| TD-08 | `CustomerMapper.findById` nefiltruje `is_active` | **Překlasifikováno na záměr** — v XML je komentář „INTENTIONALLY lenient": detail deaktivovaného zákazníka se musí dát otevřít. Pro nové moduly platí strict (R-10). 2026-07 |
| TD-09 | Customer PUT endpoint | Implementováno |
| TD-15 | `AutocompletePair` předvyplnění přes spread | **Vyřešeno** — komponenta má `initialValue`/`initialSelectedId` props. Ověřeno 2026-07 |
| TD-17 | Service třídy bez interface | Všechny mají rozhraní + Impl |
| TD-19 | `orderNumber` hardcoded v `OrderService.create` | Trigger V11 |
| TD-26 | DB heslo natvrdo v pom.xml | `${env.DB_PASSWORD}` |
| TD-29 | Chybí `@EnableScheduling` — blacklist cleanup se nespouštěl | `@EnableScheduling` na `AutoservisApplication`. 2026-07-18 |
| TD-30 | `ConflictException` nemapována → 500 místo 409 | Handler v `GlobalExceptionHandler` → 409, kód nese výjimka (`DUPLICATE_IMPORT` u importu faktur); aktualizována `api.md`. 2026-07-18 |
| TD-27 | `application.yaml` — blok `flyway:` odsazený pod `mybatis:` (čte se jako `mybatis.flyway`, ignorováno) | Blok přesunut pod `spring:` (na úroveň `datasource:`), hodnoty beze změny. 2026-07-20 |
| K1/K2 (analyza-2026-07) | Veřejný `/auth/register` — kdokoli získal přístup; tokeny v těle odpovědi | Endpoint odstraněn, účty zakládá jen admin (`UserController`). 2026-07-20 |
| K5 (analyza-2026-07) | updateStatus faktury bez guardu na aktuální stav — TOCTOU race | WHERE id AND status = expected, 0 řádků → 409 INVOICE_STATE_CHANGED. 2026-07-20 |
| K6 (analyza-2026-07) | Výdej ze skladu check-then-act: duplicitní šarže v requestu / souběh prošly na DB CHECK | Agregace požadavků per šarže + SELECT FOR UPDATE → čistá 422 QUANTITY_EXCEEDS_REMAINING. 2026-07-20 |
| V2 (analyza-2026-07) | Položky zakázky šly měnit i po vystavení faktury (rozjetí faktura↔zakázka, únik zboží při delete) | requireOrderNotInvoiced na mutacích položek — blokováno, existuje-li faktura ≠ CANCELLED. 2026-07-20 |
| TD-31 | Auth cookies: maxAge natvrdo (60 min/30 dní) ≠ konfigurace tokenů, secure=false natvrdo, refresh path natvrdo /api/v1 | maxAge z jwt.expiration/refresh-expiration, secure z jwt.cookie-secure (prod=true), path z @PathVariable version. 2026-07-20 |
| K3 (analyza-2026-07) | FE nikdy nevolal /auth/refresh — rotace tokenů mrtvá, v prod (15min token) by odhlašovalo | apiFetch/getBlob/requireAuth: 401 → single-flight refresh → retry. 2026-07-20 |
| V3b (analyza-2026-07) | Bez limitu přihlášení — infrastruktura lockoutu v DB existovala nevyužitá | LoginAttemptService (REQUIRES_NEW): 10 neúspěchů → zámek účtu, LockedException → 401 ACCOUNT_LOCKED; admin reset hesla odemyká. 2026-07-20 |
| V3a (analyza-2026-07) | Hesla min. 6 znaků (NIST SP 800-63B doporučuje 8+) | `min = 8` ve všech heslových DTO (UserDto, ChangePasswordRequest) + FE validace a nápověda sjednoceny. 2026-07-20 |
| V4 (analyza-2026-07) | token_blacklist ukládal živé JWT v plaintextu — únik DB = použitelné tokeny | TokenHasher (SHA-256 hex) při save i lookup; staré plaintext záznamy dožijí do expirace. 2026-07-20 |
| V5 (analyza-2026-07) | 401 z filtru a entry pointu měly vlastní JSON tvar {status,error} mimo RFC 9457 | SecurityProblemWriter — ProblemDetail + errors[] i mimo @RestControllerAdvice. 2026-07-20 |
| V6 (analyza-2026-07) | shouldNotFilter přes path.contains — nepřesná shoda | Přesný regex ^/api/[^/]+/auth/(login\|refresh)$. 2026-07-20 |
| S1 (analyza-2026-07) | apiFetch parsoval JSON před kontrolou ok — ne-JSON chyby (502/HTML) padaly na SyntaxError; callery křehce JSON.parse(err.message) | ApiError {status, problem, message=raw}; callery čtou err.problem?.detail. 2026-07-20 |
| TD-34 | Nativní window.confirm/alert v InvoiceTable, InvoicesPageDetail, OrderItemsWrapper | Sjednoceno na ConfirmDialog + useAlert dle zbytku aplikace. 2026-07-20 |
| TD-37 | `@mui/material` a `@emotion/styled` verze `"latest"` v package.json — nedeterministický build | Zafixováno na nainstalované verze (`^9.1.1` / `^11.14.1`), `npm install` aktualizoval lockfile. 2026-07-20 |
| TD-38 | `removeAlert` v AlertContext filtroval nad zachycenou hodnotou `alerts` místo funkčního update | `setAlerts(prev => prev.filter(...))`. 2026-07-20 |
| TD-39 | `orderTypeDefs.js` uváděl staré hodnoty `OrderItemType` (DIAGNOSTIC/TOWING/RENTAL/OTHER) místo LABOR/MATERIAL/OTHER_SERVICES | Celý adresář `src/api/typeDefs/` byl mrtvý kód (nikde neimportovaný) — smazán dle R-12, ne synchronizován. 2026-07-20 |
| TD-36 | Dead code: `css/style.css`, `customerDetail.module.css`, CSS pravidlo `body.has-sidebar`, `console.log` v `CustomerVehiclesTable`/`CustomersPageDetail`/`OrderItemsWrapper.handleReorder`, zakomentovaný `ConfirmDialog` blok + nepoužité importy v `OrdersPage`/`OrderTable`, backend `templates/images/avatar.jpg` | Vše smazáno/odstraněno po ověření grepem, že nic není importované/referencované. 2026-07-20 |
| TD-40(FE) | Ochrana rout jen reaktivní — obsah blikl před 401 redirectem | RequireAuth guard kolem Layout route: spinner → /auth/me → obsah. 2026-07-20 |
| TD-35 | 6× copy-paste `useXxxRowActions` (customer/vehicle/order/supplier/warehouse/user) | Společný parametrizovaný `useRowActions` + tenké wrappery se zachovanými signaturami; `useUserRowActions` přidává reset hesla. 2026-07-20 |
| TD-10 | `CustomerDto.CreateRequest`/`UpdateRequest` nevalidovaly povinná pole podle `customerType` — chytal to až DB CHECK → 422 `DATA_INTEGRITY_VIOLATION` místo 400 | `CreateRequest`: class-level `@ValidCustomerRequest` (`validation/` balíček) → 400 `CUSTOMER_NAME_REQUIRED`/`CUSTOMER_COMPANY_REQUIRED`, kódy v `messages.properties`. `UpdateRequest` nemá `customerType` (typ je immutable) — validace přesunuta do `CustomerServiceImpl.requireNameOrCompanyPresent`, čte typ z DB, stejné kódy přes `BusinessRuleException` (422). `@ValidVehicleRequest` **nevytvořen** — `V5__init_vehicle_schema.sql` nemá žádný podmíněný CHECK (jen single-field rozsahy/patterny, ty už hlídá `@Pattern`/`@Min`/`@Max`), odstraněn z `CUSTOM_VALIDATOR_ANNOTATIONS` s komentářem. 2026-07-20 |
| TD-20 | Chybějící null guardy na vstupní `Long` parametry service metod — dotaz s `id = NULL` skončil 404 místo fail-fast 400 | `GlobalExceptionHandler`: nový `@ExceptionHandler(IllegalArgumentException.class)` → 400 `INVALID_ARGUMENT`. Guard `if (id == null) throw new IllegalArgumentException("id nesmí být null")` doplněn na začátek veřejných metod s `Long` identifikátorem ve `VehicleServiceImpl`, `OrderServiceImpl`, `OrderItemServiceImpl`, `InvoiceServiceImpl`, `ProductServiceImpl`, `SupplierServiceImpl`, `MileageServiceImpl`, `UserServiceImpl`, `ReceiptReviewServiceImpl` (celkem 51 metod); `CustomerServiceImpl`/`GoodsReceiptServiceImpl` guard měly už dřív. 2026-07-20 |
| TD-23 | `CustomerDto.UpdateRequest.gdprConsent`/`marketingConsent` byly primitivní `boolean` — chybějící pole v JSON se četlo jako `false`, ne jako „beze změny" | Změněno na `Boolean`; `CustomerConverter.applyUpdate` pole aplikuje jen když `!= null` (na rozdíl od ostatních polí `UpdateRequest`, které mají full-replace sémantiku). FE (`CustomerForm.jsx`/`CustomersPageCreate.jsx`/`CustomersPageEdit.jsx`) posílá obě pole vždy (kontrolované checkboxy) — chování pro stávající klienty beze změny. 2026-07-20 |
| TD-18 | FTS zákazníků nenajde víceslovný dotaz (`Jan Novák` — Jan v first_name, Novák v last_name) | `SearchParams.getSearchTokens()` (sdíleno s `OrderSearchParams` a ostatními search-param třídami) rozdělí dotaz na tokeny; `CustomerMapper.xml searchWhere`: `<foreach separator=" AND">` — každý token musí sedět aspoň na jeden sloupec (first_name/last_name/company_name) přes OR, navíc `unaccent` (diakritika je jedno). 2026-07-20 |
| TD-25 | Fulltext zakázek — víceslovný dotaz nenajde shodu napříč sloupci (`LIKE '%Jan Novák%'` per sloupec selhal) | Stejný vzor jako TD-18 aplikován na `OrderMapper.xml WhereClause` (sdílený `search`/`countSearch`) — zachovaná množina sloupců (order_number, customer first_name/last_name/company_name/primary_phone, vehicle vin/license_plate/brand/model, description) obalena do foreach přes tokeny + `unaccent`. **Poznámka k implementaci:** `<bind>` uvnitř `<foreach>` je v MyBatis bez unikátního jména per-iterace navázaný jen jednou (poslední hodnota tokenu přepsala všechny placeholdery — ověřeno testem, který by jinak tiše procházel) — použit `CONCAT('%', #{token}, '%')` přímo v SQL s referencí na foreach item, který MyBatis váže per-iteraci správně. 2026-07-20 |
| TD-28 | Produkt lze deaktivovat i se zásobou na skladě (`quantity_on_hand > 0`) — tiché rozjetí skladu vs. deaktivovaná karta | **Rozhodnutí: zakázat.** `ProductServiceImpl.deactivate` načte produkt (`warehouseMapper.findById`), a je-li `quantityOnHand > 0`, vyhodí `BusinessRuleException PRODUCT_HAS_STOCK` (422, pole `quantityOnHand` v params) místo provedení UPDATE; nulová zásoba deaktivuje beze změny. 2026-07-20 |
| TD-12 | Chybí `Location` header u 201 | Všechny POST create endpointy vrací `ResponseEntity.created(location)` místo `status(CREATED)`. Location se sestavuje z aktuálního requestu (`ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}")`), nezávisle na `{version}`; u dvou endpointů (`InvoiceController.createFromOrder`, `GoodsReceiptImportController.importDocument`), kde se POST cesta liší od cesty GET-by-id, se poslední segment cesty nahradí. `OrderItemController.createFromReceipt` vrací pole položek (žádné jednotné id) — ponechán beze změny. 2026-07-20 |
| TD-32 | Namespace `warehouse/ProductMapper.xml` je `WarehouseMapper` | Soubor přejmenován na `WarehouseMapper.xml` (`git mv`), namespace se neměnil (už odpovídal). `mapper-locations` je wildcard, žádná konfigurace neodkazovala na název souboru. `ContactPersonMapper.java` byl prázdný nepoužívaný placeholder (žádná metoda, nikde neinjectovaný) — smazán (R-12); `ContactPersonMapper.xml` zůstává (aktivně používaný `resultMap` referencovaný z `CustomerMapper.xml`). 2026-07-20 |
| — | `contextLoads` vyžadoval běžící lokální dev DB (5433), ač dokumentace tvrdila „testy = jen Docker" | `AbstractIntegrationTest` (singleton container pattern), oba testy sjednoceny — `./mvnw test` potřebuje jen Docker. 2026-07-18 || — | Alert systém, PgEnumTypeHandler cast, `connection-init-sql`, `is_active` wrapper Boolean, null guard v `CustomerService.getById` | průběžně 2026 |

---

## Šablona pro nový dluh

```
### TD-XX priorita Název
**Problém:** co je špatně.
**Dopad:** co to způsobuje.
**Oprava:** konkrétní kroky.
```
