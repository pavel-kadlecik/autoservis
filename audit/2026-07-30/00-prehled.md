# Hloubkový audit projektu Autoservis — 2026-07-30

> **Auditovaný stav:** větev `audit`, commit `c2ebb93`, Flyway V1–V63 (57 migrací v `db/migration`,
> 7 v `db/demo`, 2 v `db/prod`), 346 Java souborů, 25 XML mapperů, 23 controllerů, 112 endpointů,
> ~37 stránek React SPA.
> **Rozsah:** druhý hloubkový audit celé aplikace — backend, frontend, SQL, DB schéma, security,
> testy, dokumentace, nápověda, věcná a procesní správnost provozu, účetnictví a česká legislativa.
> **Metoda:** 11 nezávislých hloubkových průchodů, každý s vlastním úzce vymezeným rozsahem.
> Průchody četly kód **bez** přístupu k `audit/2026-07-24/` (záměrně, aby nedošlo k zakotvení);
> porovnání se starým auditem dělal hlavní auditor až na závěr.
> **Audit je read-only** — nic nebylo opraveno ani commitnuto.

## Jak číst tento audit

| Soubor | Oblast |
|---|---|
| [01-backend-jadro.md](01-backend-jadro.md) | Zákazník → vozidlo → zakázka, položky, marže, snapshoty |
| [02-fakturace-penize.md](02-fakturace-penize.md) | Faktura, PPD, dobropis, úhrady, PDF, zaokrouhlování |
| [03-sklad.md](03-sklad.md) | Příjemky (AI/ISDOC/ruční), šarže, ledger, výdej, inventura |
| [04-sql-mybatis.md](04-sql-mybatis.md) | 25 XML mapperů, resultMapy, dynamické SQL, indexy |
| [05-databaze-migrace.md](05-databaze-migrace.md) | Migrace, constrainty, triggery, views, shoda s `databaze.md` |
| [06-security.md](06-security.md) | Autorizace endpoint po endpointu, IDOR, JWT, upload, secrets |
| [07-domena-procesy.md](07-domena-procesy.md) | Procesní průchody, jak je dělá skutečný servis; dashboard |
| [08-ucetnictvi-legislativa.md](08-ucetnictvi-legislativa.md) | ZDPH, ZoÚ, PPD, inventarizace, GDPR, srovnání s praxí |
| [09-testy.md](09-testy.md) | Pokrytí, plané testy, kvalita assertů, infrastruktura |
| [10-dokumentace.md](10-dokumentace.md) | Javadoc/JSDoc, `docs/`, nápověda v aplikaci |
| [11-frontend.md](11-frontend.md) | React SPA — stavy, validace, přístupnost, vzory |
| [plan-oprav.md](plan-oprav.md) | **Plán oprav po vlnách — co, jak, v jakém pořadí** |

---

## Celkové hodnocení

Od auditu 2026-07-24 projekt výrazně dozrál. Věci, které tehdy byly nejzávažnější, jsou skutečně
opravené a ověřitelné v kódu: storno faktury už nezazdí zakázku, číslo faktury se přiděluje až při
vystavení, snapshot vozidla je plný, ledger je append-only vynucený v DB, rolová autorizace existuje,
suita vyrostla a je mutačně doložená. **Řemeslná úroveň jádra je nadále vysoká** — a audit to
dokládá i negativně: v celém repozitáři není jediný výskyt `${}` v SQL, tabulky jsou plně
kvalifikované bez výjimky, stránkované dotazy mají `search`/`count` s identickou `WHERE`, whitelisty
řazení mají tie-breaker, cestu k záporné zásobě se nepodařilo najít.

**Těžiště nálezů se posunulo z „jak je kód napsaný" na „co se nedotáhlo do konce".** Dominantní
vzorec tohoto auditu je **hotový backend bez cesty k uživateli** a **oprava provedená zpola**:

1. **Opravný daňový doklad je hotový včetně PDF a testů, ale z aplikace k němu nevede nic** — žádné
   tlačítko, žádná routa, žádný článek nápovědy. Nápověda místo toho výslovně radí vystavenou fakturu
   stornovat. To je u dokladu předaného odběrateli postup mimo §42/§45 ZDPH. Starý nález K-8 je tedy
   opravený v backendu a **neúplně opravený jako funkce**.
2. **Inventura nedoloží to, kvůli čemu se dělá.** Rozdíl se počítá proti živému stavu skladu, takže
   po uzavření vykazuje každý řádek nulu a banner hlásí „0 mank a 0 přebytků". Nezávisle na tom
   frontend uzavře inventuru i tehdy, když uložení soupisu selhalo — nevratně a s hláškou o úspěchu.
3. **Peněžní doklady navazující na fakturu nemají guard proti duplicitě.** PPD i dobropis jdou
   vystavit opakovaně, vždy na plnou částku; PPD navíc přímo z UI a bez možnosti zrušení.
4. **Dvě přijatá rozhodnutí starého auditu byla provedena jen zpola** — R-6 (razítko data vystavení)
   a R-5 (overflow guard číselných řad u ZNK). Detail v sekci „Vztah k auditu 2026-07-24".
5. **Dokumentace na několika místech slibuje bezpečnostní garanci, kterou kód neplní** — nejvážněji
   `api.md:47` u admin resetu hesla.

Žádný nález nevyžaduje přestavbu architektury. Naprostá většina je řešitelná guardem v service,
jednou migrací `V64+`, nebo napojením existujícího backendu na frontend.

**Jediný nález, který by měl zastavit nasazení do produkce, je uzamčení účtu (KN-5)** — neautentizovaný
útočník deseti požadavky natrvalo vyřadí jediný produkční administrátorský účet a obnova vyžaduje
ruční zásah v databázi.

---

## Statistika nálezů

Surové počty po průchodech (před konsolidací duplicit):

| # | Průchod | 🔴 KRIT | 🔴 VYS | 🟠 STŘ | 🟡 NÍZ | Σ |
|---|---|---|---|---|---|---|
| 01 | Backend jádro | 0 | 0 | 4 | 7 | 11 |
| 02 | Fakturace a peníze | 0 | 0 | 5 | 7 | 12 |
| 03 | Sklad | 0 | 2 | 3 | 3 | 8 |
| 04 | SQL / MyBatis | 0 | 0 | 1 | 11 | 12 |
| 05 | Databáze a migrace | 0 | 0 | 3 | 5 | 8 |
| 06 | Security | 0 | 1 | 3 | 6 | 10 |
| 07 | Doména a procesy | 0 | 0 | 6 | 9 | 15 |
| 08 | Účetnictví a legislativa | 0 | 1 | 10 | 5 | 16 |
| 09 | Testy | 0 | 0 | 6 | 6 | 12 |
| 10 | Dokumentace a nápověda | 0 | 1 | 9 | 13 | 23 |
| 11 | Frontend | 0 | 1 | 8 | 12 | 21 |
| | **Celkem** | **0** | **6** | **58** | **84** | **148** |

Po konsolidaci napříč průchody: **28 klíčových nálezů** — po rozhodnutích uživatele z 30. 7. 2026
**8 vysokých, 18 středních, 2 přehodnocené na nízké** (viz sekce „Přehodnoceno" níže). Zbytek jsou
lokální nálezy nízké severity popsané v dílčích reportech.

**Žádný nález nemá severitu KRITICKÝ.** Nenašla se ztráta dat v provozu, otevřená autentizační díra
ani cesta k tichému rozjetí skladu do záporu. Nejblíž je KN-5 (neautentizované trvalé vyřazení
admina) — ponechán jako VYSOKÝ, protože jde o odepření služby s obnovou přes DB, ne o průnik.

### Konvergence — kde se průchody nezávisle sešly

Shoda dvou a více průchodů na téže příčině z různých úhlů je signál spolehlivosti, ne duplicita:

| Příčina | Nezávisle nahlásily |
|---|---|
| Dobropis nedostupný z aplikace | 07 (proces), 08 (právo), 10 (chybí článek nápovědy) |
| N plných dobropisů k jedné faktuře | 02 (service), 05 (chybí unikát v DB), 07 (dashboard), 08 (§45) |
| PPD opakovaně na plnou částku, nezrušitelný | 02, 07, 08 |
| Zakázka bez stavového automatu | 01, 07, 09 (chybí i test) |
| Deaktivovaná karta/dodavatel blokuje příjemku | 03 (proces), 04 (kolize na UNIQUE indexu) |
| `gdpr_consent_at` — falešné datum souhlasu | 01, 05, 07, 08 |
| Admin reset hesla neodvolá sessions | 06 (kód), 10 (`api.md` slibuje opak) |
| Marže vs. tržby — dva různé základy | 02, 04 |
| Inventura nedůvěryhodná | 08 (nulové rozdíly), 11 (uzavření po selhaném uložení) |

---

## Klíčové nálezy (konsolidace napříč průchody)

Sloupec „Ověřeno" = hlavní auditor ověřil nález **druhým čtením přímo v kódu**, nezávisle na průchodu.

### 🔴 Vysoké

| # | Nález | Kde | Zdroj | Ověřeno |
|---|---|---|---|---|
| **KN-1** | **Opravný daňový doklad je z aplikace nedostupný.** Backend, PDF i testy hotové, ale ve FE není routa ani tlačítko a v nápovědě není článek; `help/faktury.md:19` místo toho radí „vystavenou fakturu nelze opravit — stornujte ji a vystavte znovu". U dokladu předaného odběrateli a vykázaného v přiznání je to postup mimo §42/§45 ZDPH. | `controller/CreditNoteController.java` bez FE volajícího · `help/faktury.md:19` · `frontend/…/src/help/` (žádný článek) | 07/P-1, 08/L-1, 10 | ✅ přímo |
| **KN-2** | **Uzavřená inventura nedoloží žádné rozdíly.** `difference` se počítá jako `counted_quantity − p.quantity_on_hand`, tedy proti **živému** stavu; `close()` nejdřív zapíše korekční pohyby a pak vrátí detail → po uzavření je rozdíl všude 0 a banner hlásí „0 mank a 0 přebytků". Inventura tak není průkazným záznamem o inventarizačních rozdílech (§29–§30 ZoÚ). | `mapper/warehouse/StockTakeMapper.xml:132-134` · `service/impl/StockTakeServiceImpl.java:184-192` | 08/L-4 | ✅ přímo |
| **KN-3** | **Uzavření inventury proběhne i po selhaném uložení soupisu.** `saveCounts()` má vlastní `catch`, takže `await saveCounts()` nikdy nevyhodí a `POST /close` se provede i tehdy, když se napočítané hodnoty neuložily — nevratně, se zeleným toastem „korekce byly zapsány do skladu". Sesterská `ReceiptReviewPage.confirmReceipt` tentýž problém řeší správně. | `pages/StockTakePageDetail.jsx:108` (+ `:95-97`) | 11/F-1 | ✅ přímo |
| **KN-4** | **Dvojí naskladnění téhož dokladu, dvěma nezávislými cestami.** (a) Volba „Pouze provázat" přeskakuje řádky podle `line.getDeliveryNoteNumber()` na `ITEM` řádcích, jenže kontrakt DTO to pole na `ITEM` řádcích výslovně nemá („jen pro DELIVERY_NOTE_GROUP řádky") a `matchDeliveryNoteRefs` ho tam nikdy nepropíše. (b) `resolveSupplier` založí dodavatele **před** kontrolou duplicity, takže u dokladu bez čitelného IČO vznikne pokaždé nový dodavatel a `existsActiveDocument` nemá s čím porovnávat. | `service/impl/ReceiptReviewServiceImpl.java:261-270, 277-280` · `service/DraftVerificationService.java:191-204` · `model/dto/warehouse/DocumentExtractionResult.java:67` | 03/SK-1, 03/SK-2 | ✅ přímo |
| **KN-5** | **Trvalé uzamčení účtu bez samoobslužné obnovy.** Po 10 neúspěších `lockAccount` nastaví `account_non_locked = FALSE`; neexistuje časová expirace, plánovaná úloha ani DB trigger, a odemyká výhradně ADMIN-only `POST /users/{id}/reset-password`. Produkční seed má jediný účet `admin` → kdokoli z internetu ho 10 požadavky na veřejný `/auth/login` natrvalo vyřadí. | `security/service/LoginAttemptService.java:25,43-45` · `mapper/UserMapper.xml:119,126-127` · `service/impl/UserServiceImpl.java:225` · `db/prod/V60__prod_seed.sql:38-39` | 06/S-1 | ✅ přímo |
| **KN-6** | **Admin reset hesla neodvolá sessions — a dokumentace tvrdí, že ano.** `UserServiceImpl.resetPassword` přehashuje heslo a odemkne účet, ale nevolá `revokeAllByUserId`, kterou sourozenecká `AuthenticationService.changePassword` volá. `api.md:47` přitom výslovně píše „Změna/**reset** hesla rovněž odvolá všechny refresh tokeny uživatele (K-6)". Admin reagující na kompromitaci si myslí, že útočníka odstřihl; ten má platný refresh token dalších 7 dní. | `service/impl/UserServiceImpl.java:218-227` vs. `docs/api.md:47` | 06/S-3, 10/A-1 | ✅ přímo |
| **KN-7** | **PPD lze vystavit opakovaně, vždy na plnou částku, a nelze ho zrušit.** `createFromInvoice` nekontroluje existující doklad a částku bere vždy jako `summary.getTotalGross()` zaokrouhlený na koruny. Tlačítko v UI nemá potvrzení, detail faktury existující doklady nezobrazuje (ač endpoint existuje) a storno PPD neexistuje. Dokumentovaný záměr „víc PPD kvůli dílčím úhradám" není implementovaný — dílčí částku zadat nelze. | `service/impl/CashReceiptServiceImpl.java:40-76` (`:64`) · `pages/InvoicesPageDetail.jsx:54-64` · `db/migration/V57__init_cash_receipts.sql:16-17` | 02/F-3, 07/P-3, 08/L-3 | ✅ přímo |
| **KN-8** | **K jedné faktuře lze vystavit N plných dobropisů.** `createFromInvoice` ověří jen stav původní faktury, ne existenci dřívějšího dobropisu; `credit_notes` nemá unikát na `original_invoice_id`. Každý dobropis nese celou zápornou fakturu → dva dobropisy = dvojnásobné snížení daně na výstupu a záporná pohledávka. Dnes jen přes přímé volání API — v okamžiku, kdy se opraví KN-1, je to dosažitelné z UI. | `service/impl/CreditNoteServiceImpl.java:36-65` · `db/migration/V55__init_credit_notes.sql:24-41` | 02/F-4, 05/D-1, 07/P-4, 08/L-2 | ✅ přímo |

### 🟠 Střední

| # | Nález | Kde | Zdroj |
|---|---|---|---|
| **KN-10** | `issue_date` zamrzne při založení konceptu a při vystavení se nereviduje, přičemž z něj trigger V49 odvozuje prefix čísla. Koncept z března vystavený v červenci dostane březnové číslo *za* všemi březnovými doklady; překlep v roce založí paralelní historickou řadu a umožní vložit doklad do uzavřeného období. **Rozhodnutí R-6 provedeno zpola** (viz níže). | `service/impl/InvoiceServiceImpl.java:217-222` (žádné přerazítkování) · `db/migration/V49__invoice_number_on_issue.sql:34` | 02/F-2, 08/L-7 |
| **KN-11** | Zakázka nemá stavový automat. `OrderServiceImpl.update` neověřuje ani přechod, ani existenci faktury → vyfakturovanou zakázku lze jedním PUT přepnout na `CANCELLED`; `CANCELLED → RECEIVED` projde také. Vydaný materiál zůstane trvale vydaný (sklad podhodnocený, falešné hlášení „pod minimem"). | `service/impl/OrderServiceImpl.java:109-121` · `model/enums/OrderStatus.java` | 01/J-1, 01/J-4, 07/P-2, 09/T-6 |
| **KN-12** | Storno faktury nekontroluje navázané doklady — stornovat lze i fakturu, k níž existuje PPD nebo dobropis. | `service/impl/InvoiceServiceImpl.java:247-254, 421-451` | 02/F-5 |
| **KN-13** | PDF faktury nezná stav „zaplaceno" a QR platba se generuje i pro `PAID` a `CANCELLED`. Kopie dokladu pro zákazníka tak vybízí k druhé úhradě; nápověda přitom tvrdí, že QR je jen na vystavené faktuře. | `templates/pdf/invoice.html:11-20, 255-261` · `service/impl/SpaydBuilder.java:23-31` · `help/faktury.md:24` | 02/F-1, 08/L-12 |
| **KN-14** | Chybová cesta frontendu je slepá ulička: 13 míst načítá data bez `try/catch` (500 se ukáže jako „Zatím žádní zákazníci.", 404 jako věčný spinner), `errors[]` z RFC 9457 se nikde nečte (uživatel dostane jen generické „Ověření zadaných údajů selhalo") a FE nezrcadlí `@Pattern`/`@Size` u IČO, DIČ a telefonu → do téhle situace se uživatel dostane běžným překlepem. **TD-60 je přitom zapsán jako vyřešený.** | `pages/CustomersPage.jsx:39-44` a 12 dalších · `components/CustomerForm.jsx:114-122, 163-166` | 11/F-2, 11/F-3, 11/F-4 |
| **KN-15** | `PUT /customers/{id}` s `addresses: []` nenávratně smaže celou adresní sadu. `UpdateRequest.addresses` nemá `@NotEmpty` (na rozdíl od `CreateRequest`) a `AddressSetValidator` se u prázdného seznamu vrací s komentářem „Handled by @NotEmpty" — což pro update neplatí. Zákazníka pak nelze fakturovat. | `model/dto/customer/CustomerDto.java:119-121` vs. `:66` · `service/AddressSetValidator.java:25-27` · `service/impl/CustomerServiceImpl.java:145-153` | 01/J-2 |
| **KN-16** | Deaktivovaná karta dílu nebo dodavatele rozbije příjemku dvěma způsoby: `findProductIdBySku`/`findSupplierIdByIco` filtrují `is_active = TRUE`, ale unikátní indexy platí bez ohledu na něj → potvrzení spadne na neinformativní 422; a naskladnění na deaktivovanou kartu zmizí z ocenění skladu i z inventury (obchází TD-28). | `mapper/warehouse/WarehouseImportMapper.xml:8, 54` · `service/impl/ReceiptReviewServiceImpl.java:647, 656-679` · `V42:31` · `StockTakeMapper.xml:119` | 03/SK-3, 03/SK-4, 04/S-1, 04/S-11 |
| **KN-17** | Tautologické „VERIFIED": `DraftAssembler` dopočítá chybějící ceny zpětně a `DraftVerificationService` je ověří **stejným vzorcem**. U ručně psaného dodacího listu bez rekapitulace projdou všechny kontroly triviálně a doklad skončí zeleně `VERIFIED` s `reconciliation_ok = true`, přestože nic nezávislého ověřeno nebylo. Hraniční případ zásady R-15. | `service/DraftAssembler.java:111-134` · `service/DraftVerificationService.java:83-94, 149-182` | 03/SK-5 |
| **KN-18** | Deaktivovaný uživatel s ještě platnou cookie shodí **každý** požadavek do 500 — `loadUserByUsername` je volán mimo try/catch filtru a `findByUsername` filtruje `enabled = TRUE`. FE reaguje jen na 401, takže se uživatel neodhlásí a log se plní ERROR se stack trace až do vypršení tokenu. | `security/filter/JwtAuthenticationFilter.java:100-101` · `mapper/UserMapper.xml:66-67` | 06/S-2 |
| **KN-19** | GDPR evidence souhlasů je nepoužitelná jako doklad: `gdpr_consent_at` je `NOT NULL DEFAULT NOW()`, takže i zákazník, který souhlas nedal, má „datum souhlasu"; `marketing_consent_at` se plní z pole, které Java nikdy nenastaví (vždy NULL, i při uděleném souhlasu). Chybí tedy doklad, kdy byl marketingový souhlas udělen (GDPR čl. 7 odst. 1). | `V2__init_customer_schema.sql:55-56` · `mapper/CustomerMapper.xml:276-278` · `model/converter/CustomerConverter.java:84-106` | 01/J-3, 05/D-8, 07/P-13, 08/L-11 |
| **KN-20** | Dobropis je datově odříznutý od faktury i od přehledu — nemění stav faktury a žádný dashboardový dotaz s ním nepočítá. Plně dobropisovaná faktura zůstane napořád v „pohledávkách po splatnosti" i v tržbách měsíce, takže obsluha urguje zákazníka, kterému už peníze vrátila. | `mapper/DashboardMapper.xml:98-101, 140-153` | 07/P-4 |
| **KN-21** | Testovací síť má díry přesně tam, kde jsou peníze: **modul PPD nemá jediný test** (jediný peněžní modul projektu bez sítě); dobropis se ověřuje jen `isNegative()` a `isNotNull()`, takže prohození `totalNet`↔`totalGross` i dodavatel↔odběratel přežije celou suitu; a `ProblemDetailContractTest:286-293` se jmenuje „null identifikátor → 400 INVALID_ARGUMENT", ale asertuje `status().isOk()`. | `service/impl/CashReceiptServiceImpl.java` (0 testů) · `service/CreditNoteServiceTest.java:60-65` · `web/ProblemDetailContractTest.java:286-293` | 09/T-1, 09/T-2, 09/T-3 |
| **KN-22** | Nápověda radí přiřadit roli „zákaznický portál". `SecurityConfig:93` pouští na `/api/**` jen ADMIN/MANAGER/MECHANIC (záměr, K-10), ale `GET /code-lists/roles` vrací všech 5 rolí a `UserForm` je bez filtru nabízí. Takový uživatel se přihlásí a dostane 403 na každé obrazovce — vypadá to jako rozbitá aplikace. | `help/sprava-uzivatelu.md:13` vs. `config/security/SecurityConfig.java:93` | 10/B-1 |
| **KN-23** | „Jen korekce a odpis" tvrdí nápověda i dva Javadocy na pěti místech, zatímco modal nabízí čtyři typy pohybu (přibyly `RETURN` a `ISSUE`). Mechanik podle návodu **odepíše** vadný díl místo vratky — v append-only ledgeru se to už neopraví a chybí `return_reason` i vazba na reklamaci. | `help/sklad-pohyby.md:5,22` · `StockMovementModal.jsx:8` · `ProductService.java:74` vs. `StockMovementDto.java:61-70` | 10/B-3 |
| **KN-24** | Na dokladech chybí údaj o zápisu v obchodním či živnostenském rejstříku (§435 NOZ) — `company_profile` pro něj nemá sloupec. | `V35__company_profile_and_supplier_backfill.sql:34-49` · `templates/pdf/invoice.html:53-70` | 08/L-5 |
| **KN-26** | Vydané doklady se nikam neukládají — PDF se pokaždé generuje znovu a logo i podpis bere živě z classpath. Změna profilu firmy nebo loga tak zpětně změní vzhled už vydaného dokladu. Výklad požadavku na neměnnost je sporný (obsahová data jsou zmrazená ve snapshotech), ale archivace vydaných dokladů dnes neexistuje. | `service/impl/InvoiceDocumentServiceImpl.java:35-51, 81-99` | 08/L-10 |
| **KN-27** | Servisní historie vozidla ani zákazníka neexistuje — chybí i endpoint. Obsluha při příjmu vozu nevidí, co se s ním dělalo dřív. | `model/dto/order/OrderSearchParams.java:14-38` · `pages/VehiclesPageDetail.jsx:146-328` | 07/P-5 |
| **KN-28** | Chybí přijímací a předávací protokol — servis nemá podepsatelný doklad o převzetí vozu. **Návrhové**; rozsah je věcí uživatele. | `templates/pdf/` (jen 3 doklady) | 07/P-6 |

### ⬇️ Přehodnoceno po rozhodnutích uživatele (2026-07-30)

Dva nálezy původně zařazené jako 🟠 STŘEDNÍ byly po odpovědích uživatele sníženy na 🟡 NÍZKÝ.
Zaznamenáno pro dohledatelnost — původní zařazení bylo nadhodnocené.

| # | Nález | Nově | Proč |
|---|---|---|---|
| **KN-9** | Nulová sazba DPH se tiše přepíše na 21 % (`parseInt(itemForm.vatRate) \|\| 21`, `components/OrderItemsWrapper.jsx:195`). Falsy-coalescing v JS: přepíšou se jen hodnoty `0` a `NaN`, u prázdného pole je 21 % rozumný default → reálný dopad má jen zadaná nula. | 🟡 NÍZKÝ | Servis nulovou sazbu **nepoužívá** (v ČR je 0 % fakticky jen na knihy; autoservis jede na 21 %). Praktický dopad na peníze a daň je nulový. Zůstává latentní past — UI tiše přepíše zadanou hodnotu na dokladu, který se stane daňovým záznamem — a stejný vzorec je na množství (`:191`, zadaná 0 → 1 → účtovaná položka). Oprava je jeden řádek, proto zůstává v Vlně 0, ale až na konci. |
| **KN-25** | Aplikace zná jediný daňový režim — chybí „neplátce DPH", PDP a plnění mimo tuzemsko. | 🟡 NÍZKÝ, menší rozsah | Servis **je plátcem DPH** a **nefakturuje do zahraničí** → dnešní jediný režim je pro něj správný; „neplátce" ani zahraniční plnění se nestaví. **Zbývá jedna otázka:** prodej vyřazených dílů či autovraků do sběru jako kovový odpad je v tuzemsku v režimu přenesené daňové povinnosti (§92c ZDPH) i bez jakéhokoli zahraničí — a to aplikace neumí. Potvrdit s účetním. |

**Rozhodnuto navíc (2026-07-30):** **storno faktury se vyhradí konceptům** — u vystaveného dokladu
(ISSUED/PAID) se bude nabízet výhradně dobropis. Dotahuje to rozhodnutí R-1 z auditu 2026-07-24
a odpovídá §42/§45 ZDPH. Dopad na plán je u položky 2.1: kromě obrazovky dobropisu je potřeba zamknout
akci storna ve FE **i na backendu** (`InvoiceServiceImpl.cancel`, `InvoiceStatus.canTransitionTo`).
**Podmínka pořadí:** dobropis musí být funkční dřív, než se storno zamkne — jinak se jedna slepá
ulička vymění za druhou.

---

## Zamítnuté nálezy (co druhé čtení nepotvrdilo)

Uvádím pro doložení, že adversariální kontrola proběhla:

| Tvrzení | Proč zamítnuto |
|---|---|
| „`db/demo/V3` stále zakládá portálové účty, ač dokumentace tvrdí opak" (hlásil průchod 09 mimo svůj rozsah) | V3 je hotová migrace a **měnit se nesmí** (R-09/N-10), takže ty řádky v ní zůstat musí. `db/demo/V46__remove_portal_seed_accounts.sql:15-17` jim odebere roli a vypne přihlášení; řádky se ponechávají záměrně kvůli `ON DELETE SET NULL` z `customer.customers`. Dokumentace nelže. |
| „Editace položky zakázky může rozejít vydané množství se skladem" (hypotéza průchodu 03) | Vyvráceno přímo v kódu: `OrderItemConverter.applyUpdate` u položky se šarží množství zamyká. |
| „Cesta k záporné zásobě" | Hledána cíleně, nenalezena — `chk_products_qty`, `chk_items_remaining` a `SELECT FOR UPDATE` na všech úbytkových cestách drží. |

Dále byly **vědomě nehlášeny jako nálezy** položky evidované v `docs/tech-dluhy.md` (TD-62 částečný
dobropis, TD-67 `is_active` u zakázek, TD-68 autorizace hodnoty skladu, TD-44 audit přístupnosti)
a položky plánované v `roadmapa.md`. Odkazuje se na ně jen tam, kde s nálezem souvisejí.

---

## Vztah k auditu 2026-07-24

Porovnáno až po konsolidaci vlastních nálezů, proti `audit/2026-07-24/00-prehled.md`,
`plan-oprav.md`, `rozhodnuti.md` a `docs/tech-dluhy.md`.

### ✅ Ověřeno opravené

| Starý nález | Kde a jak je opraven |
|---|---|
| **K-1** storno zazdí zakázku | `V48__invoice_order_partial_unique.sql` — částečný unikát `WHERE status <> 'CANCELLED'`; po stornu lze vystavit novou fakturu |
| **K-2** poslednímu adminovi lze odebrat roli | guard v `UserServiceImpl`, kód chyby `CANNOT_REMOVE_LAST_ADMIN` |
| **K-3** číslo faktury dle `CURRENT_DATE` při INSERTu | `V49__invoice_number_on_issue.sql` — trigger přepnut na `BEFORE UPDATE` při přechodu na ISSUED, ověřeno v `pg_trigger` (*částečně — viz „Neúplně opravené"*) |
| **K-4** UPDATE zákazníka zahazuje GDPR souhlas | `CustomerConverter.applyUpdate` aplikuje `Boolean` pole jen když `!= null`; mappery full-replace (*evidence souhlasu má ale jinou vadu — KN-19*) |
| **K-5** děravý snapshot faktury | `V50__invoice_vehicle_full_snapshot.sql` |
| **K-6** změna hesla neruší refresh tokeny | `AuthenticationService.changePassword` volá `revokeAllByUserId` (*jen samoobslužná změna — viz „Neúplně opravené"*) |
| **K-7** refresh tokeny v plaintextu | `TokenHasher` (SHA-256), `V45__widen_refresh_token_for_hash.sql` |
| **K-9** evidence úhrad | `V51__invoice_payment_record.sql`, `InvoiceServiceImpl.markPaid:238` zapisuje datum, částku i způsob |
| **K-10** ROLE_CUSTOMER + seed účty | `db/demo/V46`, `SecurityConfig:93` baseline na pracovní role |
| **K-12** vazba zakázka↔vozidlo↔zákazník | guard v `OrderServiceImpl:86-95` |
| **K-13** ledger append-only | `V52__stock_ledger_integrity.sql` — vynuceno triggerem v DB, ověřeno |
| **K-14** inventurní přebytkové šarže neviditelné | `V54__batch_provenance_left_join_supplier.sql` |
| **K-15** JWT e2e test | doplněn |
| **K-16** api.md cookies | opraveno |
| **K-17/K-18** FE fokus modalů, `addAlert` | opraveno; `Modal` navíc přes `createPortal` (TD-48) |

### ⚠️ Neúplně opravené — oprava existuje, díra zůstala

| Starý nález / rozhodnutí | Co zbylo |
|---|---|
| **K-8 + R-2 + R-7** dobropis | Backend, číselná řada `OD`, PDF i testy hotové přesně dle R-7. **Chybí celá cesta k uživateli** — žádná routa, tlačítko ani článek nápovědy, a nápověda dál radí storno. Funkce fakticky neexistuje pro toho, kdo ji potřebuje → **KN-1**. Navíc chybí guard proti duplicitě (**KN-8**) a napojení na přehled (**KN-20**). |
| **K-6** odvolání sessions při změně hesla | Opraveno jen pro **samoobslužnou** změnu. **Admin reset hesla sessions neodvolá** — a `api.md:47` tvrdí, že ano → **KN-6**. Právě admin reset je přitom ta reakce na kompromitaci, kde na odvolání záleží nejvíc. |
| **R-6** číslování a razítko data faktury | Rozhodnutí znělo: „`issue_date` **při vystavení orazítkovat aktuálním datem**; v DRAFTu ponechat editovatelný". Provedena byla jen první polovina (číslo se přiděluje při ISSUED). `InvoiceServiceImpl.issue:217-222` volá pouze `transitionTo` — přerazítkování data v celé třídě není → **KN-10**. |
| **R-5** overflow guard číselných řad | R-5 žádalo „ošetřit přetečení `LPAD` u **všech tří** řad". ZAK dostal guard ve `V56:34-37`, faktura ve `V49`. **ZNK ho nemá** — `V9__customer_number_trigger.sql:10-14` je beze změny, `LPAD(…, 4, '0')` bez kontroly → 05/D-4. (Reset ZNK po roce byl naopak vědomě zamítnut — to je v pořádku, jen chybí slíbená dokumentace.) |
| **K-1** storno faktury | Částečný unikát odblokoval přefakturaci, ale storno nekontroluje navázané PPD a dobropisy → **KN-12**. |

### 🔁 Regrese — opravou vzniklo něco nového

| Co | Kde |
|---|---|
| Po V49 může být `invoice_number` NULL u konceptu. Hláška při pokusu o změnu položek zamčené zakázky proto zní „Zakázka už má fakturu **null**". | `service/impl/OrderItemServiceImpl.java:330` (02/F-7) |
| `TD-60` (FE chybové stavy, double-submit) je v `tech-dluhy.md` zapsán jako **vyřešený**, ale 13 míst dál načítá data bez `try/catch` a `errors[]` se nikde nečte → **KN-14**. Ochrana proti dvojkliku v `FormActions` funguje; zbytek deklarovaného rozsahu ne. | `pages/CustomersPage.jsx:39-44` a dál |
| E7 zavedla rolovou autorizaci, ale `GET /code-lists/roles` dál nabízí role, které baseline odřízne → **KN-22**. | `SecurityConfig:93` vs. `UserForm` |

### 📌 Vědomě odložené — jen připomenutí, ne nálezy

`TD-62` (částečný dobropis, plná evidence úhrad 1:N) — přímo souvisí s KN-7; rozhodnutí o dílčích
částkách PPD je jeho součástí. `TD-63`/`TD-33` (produkční checklist: seed hesla, placeholder
v `company_profile`) — KN-5 tento balík činí naléhavějším. `TD-67`, `TD-68`, `TD-44`, `TD-66`,
`TD-40`, `TD-41`, `TD-65` beze změny.

### 🆕 Nové — co starý audit neviděl

Moduly, které tehdy neexistovaly nebo byly rozestavěné: **PPD** (KN-7, KN-21), **dobropis jako
funkce** (KN-1, KN-8, KN-20), **zaměstnanci** (07/P-10, P-11, 06/S-6), **inventura jako doklad**
(KN-2, KN-3), **marže** (02/F-6, 04/S-10). Dále nové: uzamčení účtu (KN-5), nulová sazba DPH
(KN-9), dvojí naskladnění (KN-4), tautologické VERIFIED (KN-17), deaktivovaný uživatel → 500
(KN-18), chybějící §435 NOZ (KN-24), jediný daňový režim (KN-25).

---

## Poznámka k metodě a spolehlivosti

- 11 průchodů běželo paralelně a nezávisle, **bez přístupu k starému auditu** — čerstvý pohled byl
  vynucen, ne jen doporučen. Devět příčin nahlásily nezávisle dva a více průchodů (tabulka výše).
- Hlavní auditor ověřil druhým čtením přímo v kódu **všech 8 vysokých nálezů** a reprezentativní
  vzorek středních, včetně obou položek „neúplně opravené" proti rozhodnutím R-5 a R-6.
- Jeden nález byl při ověření **povýšen** z `PRAVDĚPODOBNÝ` na `OVĚŘENO` s přesnějším zdůvodněním
  (KN-4a): zbytková nejistota není v tom, zda guard selže, ale v tom, že závisí na nedeterministickém
  výstupu jazykového modelu — což je samo o sobě porušení R-15.
- Tři tvrzení byla **zamítnuta** (tabulka výše).
- Průchod 05 ověřoval část schématu proti živé dev DB (`localhost:5433`, výhradně `SELECT`); ta je
  namigrovaná jen do V55, objekty z V56–V63 byly ověřeny čtením migrací — u dotčených nálezů je to
  uvedeno.
- **Testy nebyly spouštěny** (vyžadují Docker); testovací suita byla auditována čtením.
- Legislativní část stojí na primárních zdrojích ověřených k 30. 7. 2026, u každého tvrzení je
  uveden zdroj a datum. **Auditor není daňový poradce ani advokát** — závěry části 08 je před ostrým
  provozem nutné potvrdit s účetním.
