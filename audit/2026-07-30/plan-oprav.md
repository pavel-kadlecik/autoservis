# Plán oprav — audit 2026-07-30

> Návrh pořadí oprav pro 28 klíčových nálezů z [00-prehled.md](00-prehled.md).
> Rozděleno do vln podle **závislostí a rizika**, ne podle severity — co musí být první, protože
> na tom stojí ostatní.
>
> **Respektuje pravidla projektu:** hotové migrace se nemění (jen nové `V{n+1}`, další volné číslo
> je **V64**), soft-delete místo mazání, SQL výhradně v XML, žádné mazání dat.
>
> ⚠️ **Čísla migrací uvedená u jednotlivých bodů jsou orientační** — ukazují, kolik migrací je
> potřeba a v jakém pořadí, ne definitivní jména souborů. Konkrétní číslo se přiděluje až při
> implementaci podle skutečně obsazených čísel, protože vlny se nemusí dělat v tomto pořadí a
> číslování je globální přes všechny tři `db/` locations (viz `konvence.md §14`).
>
> Velikost: **S** = hodiny · **M** = 1–2 dny · **L** = déle.
> 🔷 = **rozhodnutí uživatele** — provozní nebo účetní preference, kterou auditor nehádá.

---

## Vlna 0 — Okamžité guardy (nic na nich nestojí, všechno jsou malé zásahy)

> ### ✅ HOTOVO 2026-07-30 — všech 7 bodů implementováno
>
> Suita **873 testů zelená** (z 861 před vlnou, +12 nových), JaCoCo brána prošla, frontend build
> i `npm run check` OK. Nic není commitnuto.
>
> | Bod | Stav | Nové testy | Poznámka k provedení |
> |---|---|---|---|
> | 0.1 | ✅ | 5 | Migrace **V64** `security.users.locked_at` + `lockout.duration: 15m` |
> | 0.2 | ✅ | 3 | `api.md:47` se opravou stal pravdivým — neměněn |
> | 0.4 | ✅ | — | FE, ověřeno buildem a kontrolorem; proklik chybí (viz níže) |
> | 0.5 | ✅ | 2 | **Rozsah rozšířen** — díra byla i na `/auth/refresh` |
> | 0.6 | ✅ | 4 | **Plán byl v tomto bodě chybný** — viz níže |
> | 0.7 | ✅ | 3 | **Role byly dvě**, ne jedna; zaveden jediný zdroj pravdy |
> | 0.3 | ✅ | — | FE, logika ověřena v Node (10 případů) |
>
> **Tři odchylky od plánu, které si zaslouží pozornost:**
>
> 1. **0.6 — plán doporučoval `@NotEmpty`, což by způsobilo horší chybu.** `@NotEmpty` odmítá
>    i `null`, čímž by z adres udělal povinné pole při každé editaci a zabil sémantiku
>    „null = neměnit" (TD-42). Použito `@Size(min = 1)`, které `null` propouští. Doplněn guard
>    i ve validátoru těsně před `deleteByCustomerId`.
> 2. **0.5 — nález popisoval jen filtr, díra měla dvě strany.** `UsernameNotFoundException` neměla
>    handler vůbec, takže i `/auth/refresh` (které FE po každé 401 automaticky opakuje) vracelo 500.
>    Opraveno obojí; nový kód chyby `ACCOUNT_UNAVAILABLE`.
> 3. **0.2 — `revokeAllByUserId` neměla žádný test**, ani pro opravu K-6 z auditu 2026-07-24.
>    Odstranění toho řádku by celou suitou prošlo. Doplněny testy pro obě cesty.
>
> **Zbývá ověřit v prohlížeči** (backend ani dev DB neběžely): body 0.4 a 0.3. Backend změny jsou
> pokryté suitou, u FE je to build + `check-ui` + rozvaha. Doporučuji jeden společný proklik
> po spuštění databáze — detail u příslušných bodů.

Tahle vlna je záměrně složená z nezávislých drobností s vysokým poměrem přínos/riziko. Dá se udělat
celá v jednom sezení a nic dalšího neblokuje.

### 0.1 · Uzamčení účtu — časová expirace a druhý admin
- **Řeší:** KN-5 (🔴 jediný nález, který by měl zastavit nasazení do produkce)
- **Rozsah:** `LoginAttemptService` (expirace zámku podle `locked_at` + `failed_login_attempts`),
  `UserMapper.xml` (nový `unlockIfExpired` / čtení `locked_at`), migrace **V64** pro sloupec
  `security.users.locked_at`. Produkční runbook: založit druhý ADMIN účet s jiným jménem než `admin`.
- **Riziko regrese:** nízké — dotýká se jen neúspěšné větve přihlášení. Pozor na `REQUIRES_NEW`
  transakci, aby expirace neproběhla v rollbacknuté transakci.
- **Testovat:** 10 neúspěchů → zámek; po uplynutí lhůty přihlášení projde; úspěšné přihlášení
  resetuje čítač; admin reset dál odemyká okamžitě.
- **Velikost:** S
- 🔷 **Délka zámku.** Návrh **15 minut** (kompromis mezi obtěžováním obsluhy a brzděním hádání hesla).
  Alternativa: exponenciální prodlužování. Rate limit v nginx je vhodný doplněk, **ne náhrada** —
  10 požadavků projde pod jakýmkoli rozumným limitem.

### 0.2 · Admin reset hesla odvolá sessions
- **Řeší:** KN-6
- **Rozsah:** `UserServiceImpl.resetPassword` → volat `revokeAllByUserId` (jeden řádek, vzor je
  o třídu vedle v `AuthenticationService.changePassword`). Bez toho by se opravovala jen dokumentace,
  což je horší varianta.
- **Riziko regrese:** nízké. Uživatel se po resetu musí přihlásit znovu na všech zařízeních — to je
  žádoucí chování, ale je to změna oproti dnešku.
- **Testovat:** po admin resetu je starý refresh token neplatný (dnes chybí test úplně).
- **Velikost:** S

### 0.3 · Formulář položky nesmí tiše přepisovat zadané hodnoty
- **Řeší:** KN-9 — ⬇️ **přehodnoceno na 🟡 NÍZKÝ** (rozhodnutí uživatele 2026-07-30)
- **Rozsah:** `components/OrderItemsWrapper.jsx:195` — `parseInt(x) || 21` → explicitní kontrola na
  prázdný řetězec; totéž u `parseFloat(...) || 1` na množství (`:191`), kde se zadaná 0 mění na 1,
  tedy na účtovanou položku.
- **Riziko regrese:** nízké.
- **Testovat:** položka se sazbou 0 % i s množstvím 0 projde beze změny hodnoty.
- **Velikost:** S
- **Rozhodnuto 2026-07-30:** servis nulovou sazbu DPH **nepoužívá** → praktický dopad na peníze a daň
  je dnes nulový. Původní zařazení 🟠 STŘEDNÍ bylo nadhodnocené. Zůstává latentní past (UI tiše
  přepíše zadanou hodnotu na dokladu, který se stane daňovým záznamem) a v Vlně 0 zůstává jen proto,
  že oprava je jeden řádek. **Není důvod ji řadit před bezpečnostní body 0.1/0.2.**

### 0.4 · Uzavření inventury nesmí proběhnout po selhaném uložení
- **Řeší:** KN-3
- **Rozsah:** `pages/StockTakePageDetail.jsx:108` — uložení soupisu provést inline v `try` bloku
  `closeStockTake` (vzor: `ReceiptReviewPage.confirmReceipt`, kde je to udělané správně), nebo nechat
  `saveCounts` chybu propagovat.
- **Riziko regrese:** nízké.
- **Testovat:** ruční proklik — vyvolat chybu uložení a ověřit, že se inventura **neuzavře** a hláška
  je červená. FE nemá testovací síť.
- **Velikost:** S

### 0.5 · Deaktivovaný uživatel s živou cookie → 401, ne 500
- **Řeší:** KN-18
- **Rozsah:** `JwtAuthenticationFilter:100-101` — `loadUserByUsername` vtáhnout do try/catch filtru
  a na `UsernameNotFoundException` odpovědět přes `SecurityProblemWriter` 401.
- **Riziko regrese:** nízké.
- **Testovat:** deaktivace uživatele během platné session → další požadavek 401, FE odhlásí.
- **Velikost:** S

### 0.6 · Prázdné pole adres nesmí smazat adresní sadu
- **Řeší:** KN-15
- **Rozsah:** `CustomerDto.UpdateRequest.addresses` → `@NotEmpty` (sladit s `CreateRequest:66`),
  případně i odstranit zavádějící komentář „Handled by @NotEmpty" v `AddressSetValidator:25-27`.
- **Riziko regrese:** nízké — FE tuhle cestu dnes nevyrábí.
- **Testovat:** `PUT` s `addresses: []` → 400; s `null` → beze změny; s neprázdným → full-replace.
- **Velikost:** S

### 0.7 · Nenabízet role, které baseline stejně odřízne
- **Řeší:** KN-22
- **Rozsah:** `CodeListController` / `UserForm` — filtrovat `ROLE_CUSTOMER` a `ROLE_READONLY`;
  opravit `help/sprava-uzivatelu.md:13`.
- **Riziko regrese:** nízké.
- **Velikost:** S
- 🔷 Filtrovat na backendu, nebo jen ve formuláři a v nápovědě? Doporučuji na backendu — jediné místo.

**Doporučené pořadí uvnitř vlny:** 0.1 → 0.2 → 0.4 → 0.5 → 0.6 → 0.7 → 0.3.
*(0.3 posunuto na konec po rozhodnutí 2026-07-30 — nulová sazba se nepoužívá, takže jde o latentní
past, ne o živý problém.)*

---

## Vlna 1 — Sklad a inventura jako důvěryhodný záznam

> ### ✅ HOTOVO 2026-07-30 — všech 5 bodů implementováno
>
> Suita **882 testů zelená** (z 873 po Vlně 0, +9 nových), JaCoCo brána prošla, FE build
> i `npm run check` OK. Migrace **V65**.
>
> | Bod | Stav | Nové testy | Poznámka |
> |---|---|---|---|
> | 1.5 | ✅ | — | Opraveno **6** míst, ne 5 — název článku byl duplikovaný i v rejstříku nápovědy |
> | 1.3 | ✅ | 2 | Řešeno jinak než plán — viz níže |
> | 1.1 | ✅ | 2 | Migrace **V65**, zmrazení rozdílů před korekcemi, bez backfillu |
> | 1.4 | ✅ | 2 | Pravidlo muselo být **zpřesněno** — viz níže |
> | 1.2 | ✅ | 3 | Podle rozhodnutí uživatele: neodhadovat, odmítnout a vysvětlit |
>
> **Čtyři odchylky od plánu:**
>
> 1. **1.3 — nerušil jsem `AND is_active = TRUE` z párovacích dotazů,** jak plán navrhoval. Ty
>    dotazy slouží k párování draftu a auto-match na vyřazenou firmu by byl horší než přiznat, že
>    shoda není. Přidány explicitní protikusy `findInactive*` a fix umístěn do fáze potvrzení.
> 2. **1.4 — první verze pravidla byla příliš přísná** a shodila `WarehouseImportServiceTest`.
>    Fixtura ukázala, že dopočtená cena s DPH **má** nezávislé potvrzení v rekapitulaci a souhrnu
>    z dokladu. Pravidlo zpřesněno per kontrolu: zakázaná je tautologie, ne kontrola dopočtu proti
>    dokladu. Test měl pravdu, ne původní návrh.
> 3. **1.2 — kontrola duplicity přesunuta před completeness gate.** Vynutil to test: obsluze se
>    u opakovaného importu hlásilo „doplňte chybějící údaje" místo „doklad už tu je".
> 4. **1.2 — audit se mýlil v odůvodnění.** Tvrdil, že vadu provázání maskuje integrační test
>    s ručně dosazeným číslem DL; takový test **neexistuje**, cesta nebyla pokrytá vůbec.
>    Nález sám platil.
>
> **Vědomě neřešeno:** zásoba na deaktivované kartě v `v_stock_valuation` / inventuře (KN-16,
> zbytek) — po opravě se tam nová zásoba nedostane a TD-28 brání deaktivaci karty se zásobou,
> takže cesta je uzavřená z obou stran; změna view by znamenala migraci a posun vykazované
> hodnoty skladu.
>
> **Zbývá jako samostatný úkol:** přiřazení řádků faktury k dodacímu listu (prompt + kontrolní
> obrazovka). Do té doby volba „pouze provázat" končí srozumitelnou 422 místo tichého dvojího
> naskladnění.

Musí být před fakturačními pracemi: inventura a příjemky určují **ocenění skladu**, ze kterého se
počítá marže a nákladová strana zakázky. Opravovat doklady nad rozjetým skladem nemá smysl.

### 1.1 · Uzavřená inventura musí doložit zjištěné rozdíly
- **Řeší:** KN-2 (legislativa §29–§30 ZoÚ)
- **Rozsah:** dnes `StockTakeMapper.xml:132-134` počítá rozdíl proti živému `p.quantity_on_hand`.
  Migrace **V65**: doplnit do `warehouse.stock_take_items` sloupce, které se při uzavření
  materializují (`closed_expected_quantity`, `closed_difference`), + `StockTakeServiceImpl.close`
  je naplní **před** zápisem korekčních pohybů; `getDetail` je u uzavřené inventury čte z nich.
- **Riziko regrese:** střední — dotýká se uzavírání inventury, které je nevratné. Otevřená inventura
  musí dál počítat rozdíl živě (to je správně).
- **Testovat:** otevřít → napočítat manko i přebytek → uzavřít → detail vykazuje **původní** rozdíly,
  ne nuly; počty `shortageLines`/`surplusLines` sedí; korekční pohyby v ledgeru odpovídají.
- **Velikost:** M
- ✅ **Rozhodnuto 2026-07-30 (uživatel): varianta (a) — rozdíly se při uzavření materializují**
  do sloupců. Inventura je doklad, musí být reprodukovatelná i po pozdějších pohybech.
  Zamítnutá varianta (b) — počítat proti zamrzlému `sti.expected_quantity` z okamžiku otevření —
  byla levnější, ale u dlouho otevřené inventury s běžnými pohyby by vykazovala nesmysly.
  **Nutná pojistka:** zápis materializovaných rozdílů a zápis korekčních pohybů musí být v **jedné
  transakci** (`close` už `@Transactional` je) a rozdíly se musí spočítat **před** korekcemi —
  jinak vznikne přesně dnešní nula. Uzavření je nevratné, takže tady se chyba už neopraví.

### 1.2 · Zavřít obě cesty k dvojímu naskladnění
- **Řeší:** KN-4
- **Rozsah:**
  (a) *Provázání dodacího listu:* přesunout rozhodnutí z per-řádkového `deliveryNoteNumber` na
  tvrdé pravidlo — je-li `DeliveryNoteRef` ve stavu LINKED, **řádky kryté tímto dokladem se
  nematerializují vůbec**; doplnit i extrakční prompt. Dnešní guard visí na poli, které podle
  kontraktu DTO na `ITEM` řádcích není → porušení R-15 („AI čte, kód počítá").
  (b) *Doklad bez IČO:* v `ReceiptReviewServiceImpl.confirm` provést kontrolu duplicity **před**
  `resolveSupplier`, nebo ji u nově zakládaného dodavatele udělat podle názvu + čísla dokladu.
  Soubory: `ReceiptReviewServiceImpl.java:261-280`, `DraftVerificationService.java:191-204`,
  `PdfDocumentExtractionService`, `WarehouseImportMapper.xml`.
- **Riziko regrese:** **vysoké** — jde o srdce importu. Nutná pečlivá regrese na všech třech kanálech
  (AI PDF, ISDOC, ruční).
- **Testovat:** dvojí import téhož dokladu bez IČO → 409; faktura odkazující na už naskladněný dodací
  list → řádky se nenaskladní podruhé; integrační test **nesmí** číslo DL na `ITEM` řádek dosazovat
  ručně (dnešní test tím vadu maskuje).
- **Velikost:** L
- 🔷 **Dodavatel bez IČO.** (a) dohledávat podle jména — riziko slití dvou firem; (b) dát reviewerovi
  výběr z existujících dodavatelů a založení nového udělat vědomým klikem *(doporučuji)*. Varianta (b)
  vyžaduje zásah do FE.

### 1.3 · Deaktivovaná karta dílu a dodavatele
- **Řeší:** KN-16
- **Rozsah:** odstranit `AND is_active = TRUE` z „pojistek proti duplicitě"
  (`WarehouseImportMapper.xml:8, 54`) — filtr vypíná pojistku právě tam, kde je potřeba — a doplnit
  srozumitelnou `BusinessRuleException`. Dále rozhodnout chování `v_stock_valuation` (V42:31) a
  inventury (`StockTakeMapper.xml:119`) vůči neaktivní kartě se zásobou.
- **Riziko regrese:** střední.
- **Testovat:** potvrzení příjemky s deaktivovanou kartou/dodavatelem → čitelná 422 nebo úspěšná
  reaktivace, nikdy „Data se nepodařilo uložit"; zásoba na neaktivní kartě se objeví v ocenění.
- **Velikost:** M
- 🔷 Odmítnout, nebo automaticky reaktivovat? Doporučuji **odmítnout u dodavatele** (slití firem je
  horší než chvíle práce navíc) a **reaktivovat kartu dílu** (zboží fyzicky přišlo).

### 1.4 · Kontroly draftu nesmějí ověřovat vlastní dopočet
- **Řeší:** KN-17
- **Rozsah:** `DraftVerificationService` — odlišit „ověřeno proti údaji z dokladu" od „dopočteno
  kódem"; u dokladu bez rekapitulace nevydávat `VERIFIED` ani `reconciliation_ok = true`, ale
  neutrální stav, který reviewera upozorní, že křížová kontrola nebyla možná.
- **Riziko regrese:** střední — mění se, co uživatel vidí zeleně.
- **Testovat:** ručně psaný dodací list bez rekapitulace → pole **nejsou** VERIFIED;
  doklad s rekapitulací → beze změny.
- **Velikost:** M
- 🔷 Má potvrzení příjemky s `reconciliation_ok = false` vyžadovat potvrzení navíc? Dnes projde tiše.

### 1.5 · Nápověda a Javadoc o typech skladového pohybu
- **Řeší:** KN-23
- **Rozsah:** `help/sklad-pohyby.md:5,22`, `StockMovementModal.jsx:8`, `ProductService.java:74`,
  `ProductController.java:127` — dorovnat na čtyři skutečné typy.
- **Riziko regrese:** žádné (jen texty), ale provozní dopad je reálný: mechanik podle návodu odepíše
  vadný díl místo vratky a v append-only ledgeru se to už neopraví.
- **Velikost:** S

**Doporučené pořadí:** 1.5 (okamžitě, je to text) → 1.3 → 1.1 → 1.4 → 1.2 (nejrizikovější nakonec,
s klidem na regresi).

---

## Vlna 2 — Fakturační doklady: dotáhnout, co je rozestavěné

> ### ✅ HOTOVO 2026-07-30 — všech 7 bodů uzavřeno
>
> Suita **906 testů zelená** (z 882 po Vlně 1, +24 nových), JaCoCo brána prošla, FE build
> i `npm run check` OK. Migrace **V66, V67, V68**.
>
> | Bod | Stav | Nové testy | Poznámka |
> |---|---|---|---|
> | 2.2 | ✅ | 2 | Migrace **V66** — částečný unikát, jeden aktivní dobropis na fakturu |
> | 2.1 | ✅ | — | FE stránka dobropisu + dialog na §45 důvod; storno vystavené faktury **zamčeno** |
> | 2.3 | ✅ | 2 | Dobropis se odečítá z tržeb i z pohledávek po splatnosti |
> | 2.4 + 2.6 | ✅ | 13 | Migrace **V67** (zaokrouhlení na jednom místě) a **V68** (jeden platný PPD + storno); modul PPD dostal **první testy vůbec** (nález T-1) |
> | 2.7 | ✅ | 3 | Razítko data vystavení; existující test kódoval staré chování a musel se srovnat |
> | 2.5 | ✅ | 1 | **Řešeno jinak než plán** — viz níže |
>
> **2.5 — proč test místo kontroly v kódu.** Plán chtěl v `InvoiceServiceImpl.cancel` výslovnou
> kontrolu navázaných dokladů. Po zamčení storna (2.1) je taková větev **nedosažitelná**: PPD
> i dobropis jdou vystavit jen k ISSUED/PAID faktuře a tu už stornovat nelze vůbec. Přidat kód,
> který nikdy neproběhne, by porušilo R-12 (mrtvý kód). Invariant proto drží regresní test
> `cancel_isRejectedWhileDocumentsAreLinkedToTheInvoice` — kdyby se zámek storna v budoucnu
> uvolnil, spadne a přinutí navázané doklady dořešit. Hodnota nálezu KN-12 zůstává, dluh nevzniká.
>
> **Rozhodnutí uživatele během vlny:** druhý PPD k faktuře zakázat; PPD fakturu sám nezaplacuje;
> zaokrouhlovat už na faktuře (varianta a); datum vystavení **vždy** přerazítkovat při vystavení
> (zpětné datování se nestaví). Servis **neprodává šrot** → režim přenesené daňové povinnosti
> podle §92c ZDPH je pro tuhle aplikaci neaplikovatelný a nestaví se.
>
> **Neuzavřeno:** KN-3 a KN-9 nebyly proklikány v prohlížeči (backend ani dev DB během prací
> neběžely) — obojí je pokryté testy, ale vizuální ověření chybí.

Největší hodnota celého plánu. Stojí na Vlně 1 (ocenění) a částečně na Vlně 0.

### 2.1 · Zpřístupnit opravný daňový doklad
- **Řeší:** KN-1 (🔴), navazuje KN-20
- **Rozsah:** FE — routa, stránka dobropisu, tlačítko na detailu faktury, stažení PDF; článek
  nápovědy `help/dobropis.md` + zápis do `help/index.js`; **přepsat `help/faktury.md:19`**, aby
  neradilo storno u vystaveného dokladu. Backend je hotový, sahat do něj netřeba.
- **Riziko regrese:** nízké na backendu, střední na FE (nová obrazovka).
- **Testovat:** celá cesta reklamace: vystavená faktura → dobropis → PDF obsahuje označení „opravný
  daňový doklad", evidenční číslo původního dokladu, důvod opravy a rozdíly.
- **Velikost:** M
- ✅ **Rozhodnuto 2026-07-30 (uživatel): storno jen pro DRAFT.** U vystaveného dokladu (ISSUED/PAID)
  se nabízí **výhradně dobropis**. Odpovídá to §42/§45 ZDPH a dotahuje rozhodnutí R-1 z auditu
  2026-07-24, které storno vyhrazovalo faktuře, „která nikdy neopustila firmu".
  **Dopad na rozsah 2.1:** kromě nové obrazovky dobropisu je potřeba
  (a) odebrat/zamknout akci „Stornovat" pro ISSUED a PAID v `InvoicesPageDetail.jsx:127-135`
  a `components/InvoiceTable.jsx`,
  (b) doplnit guard i na backendu — `InvoiceServiceImpl.cancel` musí storno vystaveného dokladu
  odmítnout `BusinessRuleException`, protože FE není autoritativní,
  (c) upravit `InvoiceStatus.canTransitionTo` (dnes `ISSUED → CANCELLED` a `PAID → CANCELLED` povoluje),
  (d) přepsat `help/faktury.md:19`.
  **Pozor na regresi:** tímhle se ruší jediná dnešní cesta, jak se zbavit omylem vystavené faktury,
  která ještě neopustila firmu. Dobropis (2.1) musí být funkční **dřív**, než se storno zamkne —
  jinak vznikne slepá ulička opačným směrem. Bod 2.5 (storno kontroluje navázané doklady) se tím
  z velké části stává bezpředmětným pro ISSUED/PAID, zůstává jen pro DRAFT.
  **Velikost 2.1 se tím posouvá z M na M–L.**

### 2.2 · Guard proti duplicitnímu dobropisu
- **Řeší:** KN-8
- **Rozsah:** `CreditNoteServiceImpl.createFromInvoice` — kontrola existujícího dobropisu; migrace
  **V66** s částečným unikátem na `credit_notes(original_invoice_id) WHERE status <> 'CANCELLED'`
  (vzor `uq_invoices_order_active` z V48). **Musí jít ruku v ruce s 2.1** — jakmile vznikne UI,
  je duplicita dosažitelná klikáním.
- **Riziko regrese:** nízké.
- **Testovat:** druhý dobropis k téže faktuře → 422/409; po stornu dobropisu lze vystavit nový.
- **Velikost:** S
- 🔷 **Kolik dobropisů k jedné faktuře?** Částečný unikát „jeden aktivní" *(doporučuji pro dnešní MVP)*
  vs. pravidlo „součet dobropisů ≤ faktura", pokud se plánuje částečný dobropis dle TD-62.

### 2.3 · Dobropis se musí promítnout do přehledu
- **Řeší:** KN-20
- **Rozsah:** `DashboardMapper.xml:98-101, 140-153` — dobropisované faktury vyřadit z pohledávek
  po splatnosti a zohlednit v tržbách.
- **Riziko regrese:** nízké.
- **Testovat:** plně dobropisovaná faktura nefiguruje v „po splatnosti".
- **Velikost:** S
- 🔷 **Do kterého měsíce dobropis patří?** Doporučuji **měsíc vystavení dobropisu** (odpovídá běžné
  praxi); z pohledávek se vyřadí v obou variantách. Věc účetní — potvrdit.

### 2.4 · PPD: duplicita, částka, zrušení, viditelnost
- **Řeší:** KN-7 (🔴)
- **Rozsah:** `CashReceiptServiceImpl.createFromInvoice` (guard + volitelná částka), storno PPD,
  zobrazení existujících dokladů na detailu faktury (endpoint už existuje), potvrzovací dialog
  v `InvoicesPageDetail.jsx:54-64`. Případně migrace **V67** (částečný unikát / stav dokladu).
- **Riziko regrese:** střední — je to peněžní doklad, mění se jeho vznik.
- **Testovat:** druhý PPD k téže faktuře; PPD na dílčí částku; storno PPD; součet PPD vs. `paid_amount`.
- **Velikost:** M → **S–M** (bez dílčích částek je zásah menší)
- ✅ **Rozhodnuto 2026-07-30 (uživatel): druhý PPD k téže faktuře zakázat.**
  Dílčí hotovostní úhrady se nestaví — zůstávají jako TD-62.
  **Dopad na rozsah 2.4:**
  (a) `CashReceiptServiceImpl.createFromInvoice` — guard na existující nestornovaný PPD →
  `BusinessRuleException`/`ConflictException` (**409**, protože jde o konflikt stavu, ne o vadný vstup);
  (b) migrace **V67** — částečný unikát `cash_receipts(invoice_id) WHERE status <> 'CANCELLED'`
  (vzor `uq_invoices_order_active` z V48), aby to nedrželo jen na aplikační vrstvě;
  (c) částka zůstává vždy plná (`summary.getTotalGross()`) — **odpadá** práce na volitelné částce;
  (d) storno PPD je potřeba i tak (omyl při vystavení) a po stornu musí být možné vystavit nový —
  proto částečný unikát, ne plný;
  (e) zobrazení existujících dokladů na detailu faktury + potvrzovací dialog zůstávají.
- ✅ **Rozhodnuto 2026-07-30 (uživatel): PPD fakturu sám nezaplacuje** — označení „zaplaceno"
  zůstává samostatným ručním krokem obsluhy.
  **Důsledek, na který je potřeba myslet:** tím zůstávají dva zápisy o téže platbě (PPD a evidence
  úhrady) a mohou se rozejít — přesně to, co dnes 08/L-9 popisuje. Guard proti duplicitě (výše)
  to neřeší. Proto je nutné, aby `InvoiceServiceImpl.markPaid` zapisoval do `paid_amount`
  **tutéž částku, jakou nese PPD** (dnes zapisuje nezaokrouhlený `totalGross`), a aby detail faktury
  existující PPD zobrazoval — obsluha musí vidět, že doklad už vystavila.
- ✅ **Rozhodnuto 2026-07-30 (uživatel): varianta (a) — zaokrouhlovat už na faktuře.**
  *Právní rámec dohledán na vyžádání uživatele, viz 08/L-9 „Doplněno 2026-07-30".*
  **Stále platí:** potvrdit s účetním před ostrým provozem — auditor není daňový poradce.
  Zamítnuta varianta (b) „zaokrouhlit jen na PPD + sloupec `rounding_amount`" — přidávala čtvrté
  číslo a nechávala fakturu žádat jinou částku, než zákazník zaplatí.
  Provedení: zaokrouhlovat na faktuře, je-li předepsaný způsob úhrady hotovostní.
  Faktura dostane řádek „Zaokrouhlení" a „Celkem k úhradě" v celých Kč; PPD, `paid_amount`,
  PDF **a QR platba** pak čtou tutéž částku.
  *Právní opora:* §36 odst. 5 ZDPH ve znění od 1. 10. 2021 (novela 355/2021 Sb.) — částka vzniklá
  zaokrouhlením celkové úplaty na celou korunu se **nezahrnuje do základu daně**, a to už u všech
  způsobů platby, ne jen u hotovosti. Zaokrouhlovat lze **jen na celou korunu** matematicky;
  zaokrouhlení „dál" se danit musí.
  *Proč (a) a ne (b):* dnešní problém není v tom, že se zaokrouhluje špatně, ale že se zaokrouhluje
  **na třech místech nezávisle**. Varianta (b) přidává čtvrté číslo (`rounding_amount`) a nechává
  fakturu žádat jinou částku, než zákazník zaplatí. Varianta (a) problém ruší konstrukcí — jeden
  výpočet, jeden zdroj pravdy.
  **Implementační podmínka:** zaokrouhlení spočítat na **jednom místě** (view `v_invoice_price_totals`
  nebo jediný Java helper) a odtud ho čtou všichni konzumenti. `SpaydBuilder` dnes staví částku
  z `totalGross` — pokud by se neupravil, QR kód by žádal jinou částku než doklad, což je horší
  než dnešní stav.
  **Zbývá hraniční případ:** faktura předepsaná na převod, zaplacená hotově. Tam zaokrouhlí PPD
  a `paid_amount` přebere jeho částku; rozdíl je legitimní zaokrouhlovací rozdíl dle §36/5.
  Zdokumentovat, neřešit zvlášť.

### 2.5 · Storno faktury musí vidět navázané doklady
- **Řeší:** KN-12
- **Rozsah:** `InvoiceServiceImpl.cancel` — kontrola existujícího PPD a dobropisu.
- **Riziko regrese:** nízké.
- **Velikost:** S
- **Po rozhodnutí 2026-07-30 se rozsah zmenšil:** storno se zamyká pro ISSUED i PAID (viz 2.1), takže
  vystavenou fakturu s navázaným PPD nebo dobropisem už nebude možné stornovat vůbec. Tenhle bod tím
  zbývá jen jako pojistka pro DRAFT (ke konceptu PPD ani dobropis vzniknout nemůže — obojí vyžaduje
  ISSUED/PAID) a jako **obrana do hloubky**, pokud by se zámek storna v budoucnu uvolnil.
  Doporučuji ponechat, je to pár řádků — ale prioritu má nízkou.

### 2.6 · PDF faktury: stav dokladu, QR a zaokrouhlení
- **Řeší:** KN-13 + provedení rozhodnutí o zaokrouhlení z 2.4
- **Rozsah:** `SpaydBuilder:23-31` — negenerovat QR pro `PAID`/`CANCELLED`
  **a brát částku ze zaokrouhleného celkem, ne z `totalGross`**; `templates/pdf/invoice.html` —
  viditelné označení stavu (zaplaceno / stornováno) + řádek „Zaokrouhlení" a „Celkem k úhradě"
  v celých Kč u hotovostní úhrady. Zaokrouhlení spočítat na **jednom místě** (nová verze view
  `v_invoice_price_totals`, tedy vlastní migrace, nebo jediný Java helper) a odtud ho čtou
  faktura, PDF, QR, PPD i `paid_amount`.
- **Riziko regrese:** **střední** (původně nízké) — sahá se na cenové souhrny, ze kterých čte
  celá fakturace. Zaokrouhlení nesmí prosáknout do základu daně ani do rozpisu DPH
  (§36/5: rozdíl je mimo základ daně) a nesmí se zaokrouhlovat sama daň (od 1. 4. 2019 nepřípustné).
- **Testovat:** PDF zaplacené faktury nemá QR a nese označení „zaplaceno"; hotovostní faktura
  s haléři má řádek zaokrouhlení a celkem v celých Kč; **částka v QR se rovná „Celkem k úhradě"**;
  rozpis DPH po sazbách zůstane beze změny; PPD a `paid_amount` nesou tutéž částku jako faktura.
- **Velikost:** S → **M** (po rozhodnutí o zaokrouhlení)
- **Poznámka k pořadí:** 2.6 se tím stává závislé na 2.4 (společný výpočet zaokrouhlení) —
  dělat je jako jeden celek, ne odděleně.

### 2.7 · Dokončit rozhodnutí R-6 — razítko data vystavení
- **Řeší:** KN-10
- **Rozsah:** `InvoiceServiceImpl.issue:217-222` — před přechodem na ISSUED orazítkovat `issue_date`
  aktuálním datem (přesně to R-6 rozhodlo a nebylo provedeno); v DRAFTu ponechat editovatelné.
  Doplnit validaci `dueDate >= issueDate` v service místo propadu na DB CHECK (02/F-9).
- **Riziko regrese:** střední — mění se číslo, které doklad dostane. Nutné ověřit, že trigger V49
  čte přerazítkovanou hodnotu ve stejné transakci.
- **Testovat:** koncept založený v březnu a vystavený v červenci dostane červencové číslo a datum;
  řada zůstane chronologická.
- **Velikost:** M
- 🔷 **Zpětné datování.** Přerazítkovat vždy *(doporučuji, plní R-6)*, nebo povolit vědomě zpětně
  datovanou fakturu s validací proti uzavřenému období? Souvisí s otázkou uzamčení účetního období
  (08/L-7).

**Doporučené pořadí:** 2.2 → 2.1 → 2.3 → **2.4 + 2.6 jako jeden celek** → 2.7 → 2.5.

Tři podmínky pořadí, které vyplynuly z rozhodnutí 2026-07-30:
1. **Guard před UI** (2.2 před 2.1) — aby duplicitní dobropis nikdy nebyl klikatelný.
2. **2.1 musí být funkční dřív, než se zamkne storno** — jinak se jedna slepá ulička vymění za druhou.
3. **2.4 a 2.6 dělat společně** — obojí stojí na jednom výpočtu zaokrouhlení; rozdělit je znamená
   vyrobit ten rozpor podruhé.

2.5 na konec, protože zámek storna z něj udělal převážně obranu do hloubky.

---

## Vlna 3 — Zakázka jako proces

> ### ✅ HOTOVO 2026-07-31 — všechny 3 body implementovány
>
> Suita **975 testů zelená** (z 909 po Vlně 2, +66 nových), JaCoCo brána prošla, FE build
> i `npm run check` OK. Migrace **V70**.
>
> | Bod | Stav | Nové testy | Poznámka |
> |---|---|---|---|
> | 3.1 | ✅ | 57 | `OrderStatus` automat + dva guardy zrušení; matice 7×7 unit + 12 integračních |
> | 3.2 | ✅ | 4 | Filtry `vehicleId`/`customerId` + tři karty historie; rozsah **rozšířen** o faktury zákazníka |
> | 3.3 | ✅ | 5 | Migrace **V70**, zakázkový list PDF, tachometr při příjmu (řeší i 07/P-14) |
>
> **Čtyři odchylky a nálezy nad rámec plánu:**
>
> 1. **Zápis rozhodnutí u 3.1 byl ve dvou detailech proti kódu.** `ISSUE_RETURN` nemůže nést
>    `return_reason` (DB CHECK `chk_return_reason` ho vyhrazuje typu `RETURN`, tj. vratce
>    dodavateli) a ručně ho vytvořit nelze — endpoint ručních pohybů povoluje jen `ADJUSTMENT`,
>    `WRITE_OFF`, `RETURN` a `ISSUE`. Jediná cesta k vratce výdejky je **smazání položky zakázky**.
>    Věcná podstata rozhodnutí platí, hláška proto posílá obsluhu na smazání položek; zdůvodnění
>    v `docs/funkce/zakazky-stavy.md`.
> 2. **Regrese 02/F-7 („Zakázka už má fakturu **null**") opravena spolu s 3.1** — koncept nemá číslo
>    (V49), takže zřetězení s `invoiceNumber` lhalo. Zaveden `Invoice.describe()`, který používá nový
>    guard i původní zámek položek.
> 3. **Nález 01/J-5 opraven cestou** — `OrderConverter` nenaplňoval `vehicleId` (ani `customerId`
>    v seznamu), takže odkaz na vozidlo z detailu zakázky byl mrtvý a zakázkový list by si vůz
>    nedohledal. Jeden řádek na dvou místech.
> 4. **Smazán mrtvý filtr** `.filter(status != CANCELLED)` v `requireOrderNotInvoiced` — mapper
>    stornované nevrací od V48 (R-12). Srovnány i dva zastaralé popisy zámku v `api.md`, které
>    po V69 mlčely o `credited_at`, a záhlaví `databaze.md` („V1–V67" vs. index na V69).
>
> **Rozhodnutí uživatele během vlny:** nezměněný stav není přechod (popis a ceny uzavřené zakázky
> zůstávají editovatelné); select stavu ve FE zůstává otevřený, hlásí backend; karty historie
> zobrazují 10 nejnovějších zakázek + odkaz na zúžený seznam; na detail zákazníka se doplnily
> i **faktury** (endpoint existoval, FE ho nikdy nevolal); zakázkový list v jednoduché podobě
> (bez strukturovaného soupisu poškození); tachometr z příjmu se zapisuje i do historie vozidla.
>
> ### ✅ Proklik v prohlížeči 2026-07-31 — dotažen i dluh z Vln 0–2
>
> Prostředí poprvé nahoře (dev DB 5433 + backend s V70 + Vite), ověřeno v běžící aplikaci:
>
> | Kontrola | Výsledek |
> |---|---|
> | **KN-3** uzavření inventury po selhaném uložení | `POST /close` **vůbec neodešel**, červená hláška, inventura zůstala „Probíhá" a soupis neuložený |
> | **KN-9** sazba 0 % / množství 0 | uloženo `dph: 0`; nula v množství odešla jako 0 a backend ji odmítl (dřív se tiše uložila 1) |
> | Zrušení s materiálem | 422 s výčtem položek; po smazání položky sklad 2 → 4 a v ledgeru `ISSUE −2` + `ISSUE_RETURN +2` |
> | Zrušení s vystavenou fakturou · zámek položek · terminální přechod | 422 se správnou hláškou |
> | Karty servisní historie, faktury zákazníka, deep-link, PDF zakázkového listu, pole tachometru | vykreslené a funkční |
>
> **Proklik našel chybu, kterou testy propustily:** `Invoice.describe()` vracelo 1. pád, takže
> hláška zněla „má **faktura** 202607007". Testy kontrolovaly jen výskyt čísla dokladu, ne tvar
> hlášky. Opraveno (4. pád + kontrakt v Javadocu), testy teď kontrolují celou vazbu.
>
> **Potvrzena KN-14 (Vlna 4):** u zamítnutého množství vidí uživatel generické „Ověření zadaných
> údajů selhalo" místo konkrétní hlášky z `errors[]`.
>
> **Vědomě nedotaženo:** zrušení zakázky po vrácení materiálu se v UI nedokončilo — `CANCELLED` je
> terminální, takže by to nevratně zrušilo demo zakázku; větev drží test.
> **Sklad dev DB byl prázdný**, proklik proto vytvořil fixturu (karta `TEST-PROKLIK-1` se 4 ks,
> dodavatel a potvrzená příjemka `TEST-PROKLIK-001`, zrušená inventura INV-2026-0001) — ponechána
> záměrně pro budoucí prokliky.

### 3.1 · Stavový automat zakázky
- **Řeší:** KN-11
- **Rozsah:** `OrderStatus` — mapa povolených přechodů (vzor `InvoiceStatus.canTransitionTo`);
  `OrderServiceImpl.update` — guard na přechod **a** na existenci nestornované faktury.
- **Riziko regrese:** střední — dnes je vše povolené, takže cokoli se zakáže, může rozbít zavedený
  postup obsluhy.
- **Testovat:** matice povolených i zakázaných přechodů; zakázka s vystavenou fakturou nejde zrušit.
- **Velikost:** M
- ✅ **Rozhodnuto 2026-07-30 (uživatel): volný pohyb mezi provozními stavy, konec terminální.**
  Konkrétně:
  - `RECEIVED` · `DIAGNOSIS` · `WAITING_FOR_PARTS` · `IN_PROGRESS` · `READY_FOR_PICKUP` —
    libovolný přechod mezi nimi, oběma směry (servis reálně skáče: díl přijde poškozený → zpět
    na čekání);
  - z kteréhokoli provozního stavu → `COMPLETED` nebo `CANCELLED`;
  - `COMPLETED` a `CANCELLED` jsou **terminální** — žádný návrat (z `COMPLETED` zpět by odemklo
    editaci položek hotové práce, z `CANCELLED` zpět by oživilo zakázku, jejíž materiál se už
    vrátil na sklad);
  - `CANCELLED` **nelze**, existuje-li k zakázce nestornovaná faktura.
- ✅ **Rozhodnuto 2026-07-30 (uživatel): materiál — zrušení odmítnout, dokud není vrácen.**
  Zrušení zakázky s položkami držícími šarži vrátí **422** s výčtem těch položek; obsluha musí
  materiál nejdřív vrátit pohybem `ISSUE_RETURN` (má vlastní `return_reason`), pak zakázku zruší.
  Automatické vracení bylo zamítnuto — obešlo by důvod vrácení a v append-only ledgeru se to
  neopraví; navíc část materiálu může být fyzicky namontovaná nebo poškozená.
  **Důsledek pro UI:** hláška musí říct **které** položky to blokují a ideálně nabídnout odkaz na
  vrácení — jinak obsluha uvidí jen „nelze zrušit" a nebude vědět proč.

### 3.2 · Servisní historie vozidla
- **Řeší:** KN-27
- **Rozsah:** filtr zakázek podle `vehicleId` (`OrderSearchParams`, `OrderMapper.xml`) + sekce na
  detailu vozidla a zákazníka.
- **Riziko regrese:** nízké, jde o čtení.
- **Velikost:** M

### 3.3 · Přijímací a předávací protokol
- **Řeší:** KN-28 (**návrhové**)
- **Velikost:** L
- 🔷 **Rozsah je věcí uživatele:** jednoduchý zakázkový list s podpisem, nebo strukturovaný soupis
  poškození a stavu vozu? Souvisí i s otázkou, zda evidovat souhlas zákazníka s navýšením ceny
  (07/P-8) a stav tachometru při příjmu (07/P-14).

---

## Vlna 4 — Chybová cesta frontendu

> ### ✅ HOTOVO 2026-07-31 — oba body, včetně prokliku
>
> FE build + `npm run check` (nově **10 pravidel**) OK. Backend nezměněn, suita zůstává na
> **975 testech**. Celá vlna ověřená v běžící aplikaci — chybové stavy vynucené blokací requestů.
>
> | Bod | Stav | Rozsah |
> |---|---|---|
> | 4.1 | ✅ | `problemMessage` na 43 místech, `try/catch` v 9 seznamech + 5 detailech, `LoadErrorState`, `api/validation.js` |
> | 4.2 | ✅ | `aria-label` řádkových menu, 22 polí s přístupným názvem, `scope` v 7 tabulkách, kontrast, login, `<form>` u profilu firmy |
>
> **Dvě chyby našel až proklik — ne build, ne rozvaha:**
>
> 1. **Hláška se u 404 zdvojila** („Zákazník s ID 9 neexistuje: Zákazník s ID 9 neexistuje") —
>    `ResourceNotFoundException` posílá tentýž text v `detail` i v `errors[0]`. `problemMessage`
>    teď duplicitu zahazuje.
> 2. **Vzor telefonu se tiše ignoroval.** HTML atribut `pattern` prohlížeč kompiluje s příznakem
>    `v`, ve kterém je neescapované `(` nebo `)` uvnitř třídy znaků syntaktická chyba — a vadný
>    vzor se **neaplikuje vůbec**. Serverový regex (Java) je přitom platný, takže zkopírovaný
>    zápis vypadal správně a nevaliduje nic: „+420 123 456 789 klapka 22" prošlo. Escapováno,
>    ostatní čtyři vzory v aplikaci ověřeny jako platné.
>
> To je druhý den v řadě, kdy proklik našel vadu, kterou testy ani build nezachytily (v Vlně 3 to
> byl pád v hlášce). U frontendu, který testovací síť nemá, je proklik jediné skutečné ověření.
>
> **Rozhodnutí uživatele:** laťkou přístupnosti jsou jmenovité nálezy, ne plný audit WCAG AA
> (zbytek zůstává TD-44); chybový stav seznamu jako sdílená komponenta s „Zkusit znovu";
> dvě nová pravidla do `check-ui.mjs`, ať se vady nevrátí.
>
> **Opraveno i mimo zadání:** zápis **TD-60** (byl označený jako vyřešený, ačkoli chyběla polovina
> rozsahu — teď je u něj i poučení proč) a tvrzení v **TD-44**, že fáze U1 přinesla `scope`
> na hlavičkách (přinesla ho jen sdílené `DataTable`).

### 4.1 · Dotáhnout TD-60
- **Řeší:** KN-14
- **Rozsah:** 13 míst doplnit o `try/catch` a chybový stav; číst `errors[]` z ProblemDetail (dnes se
  zahazuje na ~20 místech); zrcadlit backendové `@Pattern`/`@Size` v `CustomerForm` (IČO, DIČ,
  telefon — FE má `maxLength 30`, backend 20).
- **Riziko regrese:** nízké, ale plošné — dotýká se mnoha stránek.
- **Testovat:** ruční proklik se shozeným backendem a s překlepem v IČO. FE nemá testovací síť.
- **Velikost:** M
- **Poznámka:** po dokončení **opravit zápis TD-60 v `tech-dluhy.md`** — dnes je označen jako
  vyřešený, ačkoli je splněná jen část rozsahu (ochrana proti dvojkliku).

### 4.2 · Přístupnost — jmenovité nálezy
- **Řeší:** 11/F-8 (třitečkové menu bez názvu — jediná cesta ke všem akcím řádku), F-14, F-15, F-16, F-17
- **Velikost:** S–M
- 🔷 **Cílová laťka:** WCAG AA, nebo „nezablokovat klávesnici"? Rozhoduje, jestli jde o pětiminutovou
  opravu, nebo samostatnou etapu (TD-44).

---

## Vlna 5 — Právo, evidence, doklady

Ničemu neblokuje cestu, ale před ostrým provozem je to nutné. **Celou vlnu potvrdit s účetním.**

| # | Řeší | Rozsah | Velikost |
|---|---|---|---|
| 5.1 | KN-19 | GDPR: migrace **V68** — `gdpr_consent_at` nullable, oprava plnění `marketing_consent_at` v `CustomerConverter`/`CustomerMapper.xml`. 🔷 Znamená `gdpr_consent_at` „kdy zákazník podepsal", nebo „kdy jsme kartu založili"? 🔷 Co při odvolání souhlasu — dnes se datum udělení nenávratně přepíše. | M |
| 5.2 | KN-24 | Migrace **V69** — sloupec pro zápis v obchodním/živnostenském rejstříku (§435 NOZ) + do všech tří PDF šablon. | S |
| 5.3 | KN-25 | ⬇️ **Přehodnoceno na 🟡 NÍZKÝ, rozsah výrazně menší.** **Rozhodnuto 2026-07-30:** servis **je plátcem DPH** a **nefakturuje do zahraničí** → příznak „neplátce DPH", plnění mimo tuzemsko ani zahraniční PDP se **nestaví**. Dnešní jediný režim (plátce, tuzemsko, 21/12 %) je pro tento servis správný. 🔷 **Zbývá jedna otázka:** prodává servis vyřazené díly nebo autovraky do sběru jako **kovový odpad**? Tuzemský odběr šrotu je v režimu přenesené daňové povinnosti (§92c ZDPH) i bez jakéhokoli zahraničí — a to aplikace neumí. Pokud ano, je potřeba doplnit PDP alespoň pro tenhle jeden případ. Potvrdit s účetním. | S |
| 5.4 | KN-26 | Archivace vydaných dokladů — uložit vygenerované PDF, ne generovat znovu z živých dat. 🔷 Výklad neměnnosti je sporný (obsahová data zmrazená jsou); rozhodnout, zda archivovat. | M |
| 5.5 | — | 🔷 **Export pro účetní neexistuje** (ani ISDOC, ani CSV/XML) — pro ostrý provoz to znamená ruční přepisování. V jaké podobě chce účetní data? | M–L |
| 5.6 | 05/D-4 | Dokončit R-5: overflow guard `LPAD` u řady ZNK (migrace **V70**, vzor `V56:34-37`) + doplnit slíbenou dokumentaci, že ZNK je celoživotní řada bez resetu. | S |

**Mimo rozsah oprav, ale patří do roadmapy:** EET 2.0 od 1. 1. 2027 se hotovostní pokladny bude
týkat; limit plateb v hotovosti 270 000 Kč (zákon č. 254/2004 Sb.) aplikace nehlídá.

---

## Vlna 6 — Testy a dokumentace

> ### ✅ HOTOVO 2026-07-31 — všechny čtyři body
>
> | Bod | Co se udělalo |
> |---|---|
> | 6.1 | Smazán **planý test** (asertoval `isOk()` u případu jménem „→ 400"); asserty dobropisu zesíleny na konkrétní hodnoty a strany; PIT rozšířen na `model.converter.*` a `model.enums.*` |
> | 6.2 | Rolová autorizace: **+11 testů** (chybělo 9 z 16 vyhrazených míst, mj. `PUT` profilu firmy — IBAN na fakturách); nový test na 422 u chybějícího profilu |
> | 6.3 | Sedm nepravdivých míst v dokumentaci a Javadocu; mrtvá pole filtrů smazána; R-08 zpřesněno |
> | 6.4 | Nápověda: 6 názvů srovnáno s UI, „Dashboard" → „Přehled", dlaždice Marže, uzamčení účtu |
>
> **Rozhodnutí uživatele:** mrtvé filtry `customerType`/`city` **smazat** (R-12); R-08 **zpřesnit**
> na „kromě jednořádkové guard klauzule" místo dorovnávání 73 míst; chybějící profil firmy vracet
> **422 `COMPANY_PROFILE_MISSING`** místo 500; PIT **rozšířit** na konvertory a enumy.
>
> **Co se opravilo v kódu, ne jen v textu:**
> - `InvoiceServiceImpl` — chybějící profil firmy je 422 s návodem („doplňte ho v Nastavení firmy"),
>   ne `IllegalStateException` → 500. Javadoc slibovala 422 už předtím, kód ne.
> - `CustomerSearchParams` — pole `customerType` a `city` smazána; `api.md` je popisovala jako
>   funkční filtry, ale mapper je v žádné `WHERE` klauzuli nečetl.
> - Šest Javadoců: dva zkopírované od sousední metody (popisovaly detail místo seznamu), `@throws`
>   na výjimku, která nemůže nastat, `FAK-2025-0001` místo skutečného formátu `202607001`,
>   `{@link #save}` na zrušenou registraci, `{@link vehicle}` 6× místo `{@link Vehicle}`.
>
> **Ověřená souhrnná čísla** (dřív zastaralá): 110 endpointů ve 22 controllerech (bylo „107 / 13"),
> 25 XML mapperů (bylo „13 + 4"), Flyway V1–V70 (bylo V69 na dvou místech a V67 v záhlaví
> `databaze.md`), trigger čísla faktury je **BEFORE UPDATE** od V49 (v `databaze.md` stálo
> BEFORE INSERT), `backend.md §3` popisoval rolovou autorizaci „na dvou místech" — stav před E7.
>
> **Nový test hned našel vadu, kterou audit neviděl:** `PUT /invoices/company-profile` bez
> `countryCode` skončil surovým **422 z porušení integrity** (`country_code CHAR(2) NOT NULL`,
> V35) místo validační 400 s hláškou. Frontend kód země vždy posílá, takže se to v provozu
> neprojevilo — ale kontrakt lhal. Doplněno `@NotBlank @Size(min = 2, max = 2)` a srovnán
> popis v `api.md`.
>
> **Vědomě neuděláno:** Checkstyle na vynucení R-08 (pravidlo zůstává na dohodě) a `check-ui`
> pravidlo na kontrolu názvů tlačítek proti UI (hrubý heuristický nápad z auditu — přínos
> nejistý, riziko planých nálezů vysoké).

### 6.1 · Peněžní moduly bez sítě
- **Řeší:** KN-21
- **Rozsah:** testy PPD (zaokrouhlení na celé Kč, guard ISSUED/PAID, číselná řada, PDF);
  dobropis — nahradit `isNegative()`/`isNotNull()` kontrolou hodnot a stran (dnes přežije prohození
  `totalNet`↔`totalGross` i dodavatel↔odběratel); opravit planý `ProblemDetailContractTest:286-293`.
- **Velikost:** M
- 🔷 Rozšířit PIT o `model.converter.*` a `model.enums.*`? Levné a KN-21 by odhalilo automaticky;
  alternativa je opravit formulaci v dokumentaci (dnes tvrdí 100 % pro balíčky, které v `targetClasses`
  nejsou).

### 6.2 · Testy k opravám z Vln 0–3
Ke každé opravě test, který by bez ní selhal. Zvlášť: stavový automat zakázky (obě větve),
rolová autorizace zbylých 8 z 16 vyhrazených míst (mj. `PUT` profilu firmy — IBAN na fakturách),
overflow guardy číselných řad.
- **Velikost:** M

### 6.3 · Dokumentace, která lže
- **Rozsah:** `api.md:47` (odvolání sessions — **spolu s opravou 0.2**), souhrnná čísla
  (`api.md`, `architektura.md` „Flyway V1–V44", `backend.md`, `tech-dluhy.md`), chybějící
  `billing.credit_notes` v `databaze.md`, „BEFORE INSERT" u triggeru faktury, `api.md` filtry
  `customerType`/`city`, zastaralý `backend.md §3` (popisuje security před E7), `frontend.md`
  o needitovatelné adrese (TD-42 je vyřešen), lživé Javadocy (10/A-2 až A-6).
- **Velikost:** M
- 🔷 Filtry `customerType`/`city` — doimplementovat, nebo smazat mrtvá pole DTO i řádek v `api.md`?
  Doporučuji **smazat** (R-12).
- 🔷 Pravidlo **R-08** (složené závorky) kód porušuje **73×**. To není nekázeň, to je špatně
  formulované pravidlo — doporučuji zpřesnit na „kromě jednořádkové guard klauzule" a doplnit
  Checkstyle, místo dorovnávání 73 míst.
- 🔷 **JSDoc:** `frontend.md` a `plan-ui.md` si odporují — rozhodnout, co platí (verdikt a odůvodnění
  v 10/A-7).

### 6.4 · Nápověda
- **Rozsah:** chybí článek o dobropisu (viz 2.1); 6 tlačítek pojmenovaných jinak než v UI;
  „Dashboard" vs. „Přehled"; nápověda vůbec nezmiňuje uzamčení účtu po 10 pokusech (po opravě 0.1
  to bude potřeba).
- **Velikost:** S
- 🔷 Investovat do provázanosti — `helpSlug` v `PageHeader` a vyhledávání v nápovědě? Dnes z aplikace
  nevede do nápovědy jediný kontextový odkaz.

---

## Doporučené pořadí prací — jednou větou na vlnu

| Vlna | Věta |
|---|---|
| **0** | Sedm malých nezávislých guardů, které zavírají bezpečnostní a datové díry — udělat hned a nečekat na nic. |
| **1** | Srovnat sklad a inventuru, protože z jejich čísel žije marže i nákladová strana zakázky. |
| **2** | Dotáhnout fakturační doklady do konce — hlavně zpřístupnit hotový dobropis, aby servis přestal opravovat vystavené faktury stornem. |
| **3** | Dát zakázce stavový automat, aby se vyfakturovaná práce nedala zrušit jedním PUT. |
| **4** | Opravit chybovou cestu frontendu, aby uživatel viděl, co se pokazilo, místo prázdné tabulky. |
| **5** | Doplnit právní náležitosti a evidenci — celé potvrdit s účetním před ostrým provozem. |
| **6** | Zavřít testovací díry v peněžních modulech a srovnat dokumentaci s realitou. |

**Před nasazením do produkce musí být hotová minimálně Vlna 0** (kvůli KN-5) **a bod 2.1** (kvůli
KN-1 — bez dostupného dobropisu není fakturace legislativně použitelná pro ostrý provoz, což
konstatoval už audit 2026-07-24 v rozhodnutí R-2).
