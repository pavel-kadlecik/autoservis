# 09 — Testy

> Audit 2026-07-30 · rozsah: celý `src/test/java/` + `src/test/resources/`, konfigurace JaCoCo/PIT v `pom.xml`,
> porovnání se souhrnnými čísly v `docs/backend.md` §7, `docs/tech-dluhy.md` a `docs/plan-testy.md`
> · metoda: **statické čtení celých souborů, testy se nespouštěly** (vyžadují Docker). Nálezy o „planých testech"
> jsou vždy doložené konkrétní mutací produkčního kódu, kterou by daný test přežil; každá mutace byla
> ověřena čtením dotčeného produkčního kódu (service, konvertor, XML mapper, migrace).

## Co bylo přečteno

**Povinná četba:** `CLAUDE.md`, `docs/konvence.md`, `docs/tech-dluhy.md`, `docs/backend.md` §7 (ř. 124–148),
`docs/plan-testy.md` (výsledkové řádky fází), `pom.xml`.

**Testy — všech 84 souborů pod `src/test/java/` a `src/test/resources/application-test.yaml`:**

- Infrastruktura: `AbstractIntegrationTest`, `AutoservisApplicationTests`, `prod/ProdSeedIntegrationTest`,
  `application-test.yaml`
- Fakturace a peníze: `InvoiceLifecycleTest`, `InvoiceStatusTransitionTest`, `InvoiceDocumentServiceTest`,
  `CreditNoteServiceTest`, `model/enums/InvoiceStatusTest`, `impl/SpaydBuilderTest`, `util/AmountInWordsTest`,
  `DashboardServiceTest`
- Zakázky: `OrderCrudServiceTest`, `OrderSearchTest`, `OrderItemServiceTest`, `OrderItemImportTest`,
  `OrderItemInvoiceLockTest`, `OrderInvoiceStatusProjectionTest`, `LaborCostSnapshotTest`
- Sklad: `ReceiptReviewServiceTest`, `WarehouseImportServiceTest`, `WarehouseImportPropertiesTest`,
  `IsdocImportTest`, `ManualStockMovementTest`, `StockTakeTest`, `StockTakeStateMachineTest`,
  `StockValuationTest`, `LowStockTest`, `ProductCrudServiceTest`, `ProductDeactivationTest`,
  `ProductUnitValidationTest`, `ProductMatchingServiceTest`, `SupplierServiceTest`, `SupplierNormalizerTest`
- AI draft pipeline: `DraftAssemblerTest`, `DraftAssemblerMappingTest`, `DraftAssemblerDerivationTest`,
  `DraftVerificationServiceTest`, `DraftVerificationSumsTest`, `PdfDocumentExtractionManualTest`
- Zákazníci / vozidla / uživatelé: `CustomerCrudServiceTest`, `CustomerServiceTest`, `CustomerValidationTest`,
  `VehicleServiceTest`, `MileageServiceTest`, `UserServiceTest`, `EmployeeServiceTest`, `CompanyProfileServiceTest`,
  `VehicleRegistryServiceTest`, `client/VehicleRegistryClientTest`, `NullGuardTest`, `ListSortingTest`
- Security: `RefreshTokenRotationTest`, `JwtServiceTest`, `ChangePasswordTest`, `SecurityServicesTest`,
  `LoginLockoutTest`, `TokenBlacklistTest`, `filter/SecurityProblemWriterTest`, `service/TokenHasherTest`
- Web / kontrakt: `ProblemDetailContractTest`, `RoleAuthorizationTest`, `JwtAuthFlowTest`, `CorsConfigTest`,
  `config/security/CorsPropertiesBindingTest`, `exception/GlobalExceptionHandlerTest`,
  `model/dto/pagination/PagedResponseTest`
- DB: `database/DatabaseTriggerTest`
- Konvertory (16): `Address`, `CompanyProfile`, `ContactPerson`, `Customer`, `Invoice`, `InvoiceItem`,
  `Mileage`, `Order`, `OrderItem`, `OrderItemSummary`, `Receipt`, `Registry`, `Supplier`, `User`,
  `Vehicle`, `WarehouseProduct`
- Nástroj: `tool/GeneratePasswordHashTest`

**Produkční kód přečtený kvůli ověření nálezů:** `CashReceiptServiceImpl`, `CashReceiptDocumentServiceImpl`,
`CashReceiptController`, `CreditNoteConverter`, `CompanyProfileController`, `OrderServiceImpl.update`,
`SupplierServiceImpl.update`, `VehicleServiceImpl.update`, `EmployeeServiceImpl.update`,
`StockTakeServiceImpl.requireOpen`, `AuthenticationService`, `GlobalExceptionHandler` (seznam handlerů),
`OrderStatus`, `mapper/DashboardMapper.xml`, migrace `V32`, `V37`, `V49`, `V55`, `V56`, `V57`, `V61`,
`db/demo/V3`.

---

## Shrnutí

Testovací síť je **nadprůměrně kvalitní** — výrazně nad úrovní, jakou má většina projektů tohoto rozsahu.
Autoři evidentně mysleli na mutační testování: fixtury mají záměrně rozdílné hodnoty (aby prohození polí
prasklo), u stavových automatů se testují **obě** větve, u součtů se izolují jednotlivé kontroly tak, aby
každá sama shodila rekonciliaci, a projekt si je vědom pasti „ověření zápisu přes MyBatis cache"
(`CompanyProfileServiceTest` ji obchází přímým `JdbcTemplate` dotazem). Klasické antivzory se prakticky
nevyskytují: **nula** `try/catch` polykajících chybu, **nula** `assertDoesNotThrow`, `verify()` na mocku
se používá jen tam, kde má význam. Reálné počty: **83 testovacích tříd, 790 testovacích metod, ~859
spuštěných testů**.

Nálezy jsou proto úzké a cílené. Nejzávažnější je **jeden zcela nepokrytý peněžní modul** (příjmový
pokladní doklad — 4 endpointy, zaokrouhlování na celé Kč, vlastní číselná řada, PDF) a **tři plané aserce
nad penězi** (dobropis, tržby na dashboardu, částka po splatnosti), které projdou i s podstatně rozbitou
logikou. K tomu jeden test, který tvrdí pravý opak toho, co má v názvu, a mezera v testech rolové
autorizace přesně u endpointu, který mění IBAN na fakturách.

**Počty:** KRITICKÝ 0 · VYSOKÝ 0 · STŘEDNÍ 6 · NÍZKÝ 6.

---

## Nálezy

### [T-1] Modul pokladních dokladů (PPD) nemá jediný test
**Severita:** 🟠 STŘEDNÍ
**Jistota:** OVĚŘENO
**Kde:** `src/main/java/cz/palo/autoservis/service/impl/CashReceiptServiceImpl.java:50` a `:64`,
`src/main/java/cz/palo/autoservis/controller/CashReceiptController.java:29`,
`src/main/java/cz/palo/autoservis/service/impl/CashReceiptDocumentServiceImpl.java:28`,
`src/main/java/cz/palo/autoservis/model/converter/CashReceiptConverter.java`,
`src/main/resources/db/migration/V57__init_cash_receipts.sql:73`
(negativní důkaz: `grep -rn "CashReceipt\|cashReceipt" src/test/java` → **0 výskytů**)

**Co je špatně:** Celý modul PPD — service, konvertor, PDF renderer, controller se čtyřmi endpointy
i DB trigger číselné řady `PPD{YYYYMM}###` — nemá v suitě žádný test. Přitom obsahuje netriviální
peněžní a právní logiku:

- `CashReceiptServiceImpl.java:64` — `summary.getTotalGross().setScale(0, RoundingMode.HALF_UP)`:
  přijatá hotovost se zaokrouhluje na celé koruny (§36 odst. 5 ZDPH).
- `CashReceiptServiceImpl.java:50` — guard „PPD jen k ISSUED/PAID faktuře" (`INVOICE_NOT_ISSUED`).
- `CashReceiptServiceImpl.java:112` — `buildPurpose` skládá účel platby dle §11 zákona o účetnictví.
- `V57:73` — guard proti přetečení řady (`>999` dokladů/měsíc) + advisory lock.

Utilita pro částku slovy (`AmountInWordsTest`, 34 testů) pokrytá je — ale nikde se netestuje,
že se na doklad dostane **správná částka**, ke které se ta slova vážou.

**Scénář selhání:** Někdo změní `RoundingMode.HALF_UP` na `HALF_DOWN` nebo `setScale(0)` na `setScale(2)`,
případně z podmínky na ř. 50 vypadne `|| invoice.getStatus() == InvoiceStatus.PAID`. Suita zůstane zelená.
V provozu: PPD k zaplacené faktuře přestane jít vystavit (blokovaný provoz u pokladny), nebo se vytiskne
doklad na 1 210 Kč, zatímco v kase je 1 210 Kč zaokrouhlených jinam — pokladní doklad nesedí s pokladní
knihou a s fakturou.

**Proč to vadí:** Peníze a účetní doklad se zákonnými náležitostmi. Jde o nejmladší funkci projektu
(commity `46ca627`, `c2ebb93` z posledních dnů) a je jediným peněžním modulem bez sítě — všechny ostatní
(faktura, dobropis, sklad, marže) testy mají.

**Návrh řešení:** Přidat `CashReceiptServiceTest` (integrační, vzor `CreditNoteServiceTest`) s minimem:
(1) `createFromInvoice` k ISSUED faktuře → částka je `ROUND(totalGross)` **na konkrétní hodnotě**
(fixtura např. 1 210,49 → 1 210 a 1 210,50 → 1 211, ať zaokrouhlení není planě ověřené);
(2) k DRAFT i CANCELLED faktuře → 422 `INVOICE_NOT_ISSUED` (obě větve);
(3) `receiptNumber` odpovídá `PPD\d{6}\d{3}` a druhý doklad v měsíci má pořadí o 1 vyšší (trigger);
(4) `purpose` obsahuje číslo faktury i VS;
(5) `renderPdf` → `%PDF-`;
(6) v `RoleAuthorizationTest` doplnit MECHANIC → 403 na `/cash-receipts` (viz T-5).

---

### [T-2] Dobropis: částky i strany dokladu se ověřují jen znaménkem a `notNull`
**Severita:** 🟠 STŘEDNÍ
**Jistota:** OVĚŘENO
**Kde:** `src/test/java/cz/palo/autoservis/service/CreditNoteServiceTest.java:60-65`
(+ produkční kód `src/main/java/cz/palo/autoservis/model/converter/CreditNoteConverter.java:70-72` a `:88`/`:90`;
`CreditNoteConverter` **nemá** vlastní unit test — v `model/converter/` chybí)

**Co je špatně:** Jediný test, který se dívá na obsah dobropisu, tvrdí:

```java
assertThat(cn.getSupplier()).as("strany ze snapshotu původní faktury").isNotNull();
assertThat(cn.getCustomer()).isNotNull();
assertThat(cn.getTotalGrossDifference()).as("rozdíl je záporný (dobropis snižuje)").isNegative();
assertThat(cn.getVatDifferences()).isNotEmpty();
assertThat(cn.getVatDifferences()).allSatisfy(line -> assertThat(line.getVat()).isNotPositive());
```

Nikde se nesrovnává s částkou původní faktury a nikde se neověřuje, že dodavatel je dodavatel.
Zbylý test dobropisu (`renderPdf_producesValidPdf`, ř. 113–122) kontroluje jen hlavičku `%PDF-`.

**Jakou mutaci by přežil:**
1. Prohození řádků `CreditNoteConverter.java:70` a `:72` (`totalNetDifference` ← `getTotalGross()`,
   `totalGrossDifference` ← `getTotalNet()`) — obě hodnoty zůstanou záporné, test projde.
2. Vynásobení kterékoli z částek konstantou (nebo záměna `getTotalVat()` za `getTotalNet()`) — projde.
3. Prohození větví na `CreditNoteConverter.java:88` a `:90` (`SUPPLIER` ↔ `CUSTOMER`) — obě strany
   jsou `notNull`, test projde. `InvoiceConverterTest.toDetailResponse_assignsPartiesByRole` přesně
   tuhle záměnu u faktury hlídá; u dobropisu, který stejnou logiku duplikuje, ekvivalent chybí.

**Scénář selhání:** Refaktoring `CreditNoteConverter` prohodí `totalNet` a `totalGross`. Vystaví se
opravný daňový doklad, kde „celkem s DPH" nese základ daně (o 21 % nižší). Doklad jde zákazníkovi
i do přiznání k DPH. Suita zelená, PIT konvertory nemutuje (viz T-9).

**Proč to vadí:** Opravný daňový doklad podle §45 ZDPH — chybná částka nebo prohozené strany znamenají
špatné přiznání k DPH na obou stranách obchodu.

**Návrh řešení:** (a) V `CreditNoteServiceTest.createFromInvoice_buildsDraftWithNegativeDifferences`
načíst původní fakturu a tvrdit rovnost s opačným znaménkem:
`assertThat(cn.getTotalGrossDifference()).isEqualByComparingTo(original.getTotalGross().negate())`
(a totéž pro net/vat i pro řádky rekapitulace po sazbách). (b) Doplnit `CreditNoteConverterTest`
podle vzoru `InvoiceConverterTest` — zejména test „strany podle role, ne podle pořadí" s obráceným
pořadím na vstupu.

---

### [T-3] `ProblemDetailContractTest`: test na „400 INVALID_ARGUMENT" tvrdí `status().isOk()`
**Severita:** 🟠 STŘEDNÍ
**Jistota:** OVĚŘENO
**Kde:** `src/test/java/cz/palo/autoservis/web/ProblemDetailContractTest.java:286-293`
(vnořená třída `InvalidArgument`, `@DisplayName("400 INVALID_ARGUMENT")` na ř. 283)

**Co je špatně:** Test se jmenuje `nullIdentifierFromService_returnsInvalidArgument`, jeho `@DisplayName`
zní „null identifikátor ze service → 400 INVALID_ARGUMENT (TD-20)", komentář uvnitř mluví o
„Autocomplete vozidel bez povinného parametru" — a tělo je:

```java
mockMvc.perform(get("/api/v1/vehicles/1/mileage").with(user(admin())))
        .andExpect(status().isOk());
```

Volá se validní endpoint s validním id, žádná `IllegalArgumentException` nevznikne a očekává se **200**.
Test tedy o TD-20 nedokazuje nic; komentář navíc odkazuje na úplně jinou cestu, než jakou volá
(zjevný drift při úpravě testu).

**Jakou mutaci by přežil:** Odstranění anotace `@ExceptionHandler(IllegalArgumentException.class)`
z `GlobalExceptionHandler.java:157`. Suita zůstane zelená, protože:
- `GlobalExceptionHandlerTest:52` volá `handler.handleIllegalArgument(...)` **přímo jako metodu**,
  takže chybějící registraci handleru nezachytí;
- `NullGuardTest` (9 testů) ověřuje jen to, že service `IllegalArgumentException` vyhodí, ne co z ní
  udělá web vrstva;
- druhý test v téže vnořené třídě (`nonNumericPathId_returnsBadRequest`, ř. 296–305) prochází jiným
  handlerem (`MethodArgumentTypeMismatchException`, `GlobalExceptionHandler.java:177`).

Reálný důsledek mutace: `IllegalArgumentException` spadne do catch-all na ř. 468 → **500 INTERNAL_ERROR**
místo 400, plus ERROR záznam v logu při každém prázdném identifikátoru z klienta.

**Scénář selhání:** Frontend pošle `null` v těle tam, kde service čeká id. Místo 400 s hláškou přijde 500,
FE zobrazí „chyba serveru" a log se plní ERROR záznamy, které maskují skutečné pády. Přesně situace,
kvůli které TD-20/TD-52 vznikly.

**Proč to vadí:** Falešná jistota — test je pojmenovaný jako regresní test uzavřeného dluhu TD-20
a v suitě vypadá jako splněná domácí úloha.

**Návrh řešení:** Nahradit tělo voláním, které `IllegalArgumentException` skutečně vyvolá skrz MVC
(např. endpoint, kde service dostane `null` z volitelného parametru), a tvrdit
`status().isBadRequest()` + `$.errors[0].code == "INVALID_ARGUMENT"` + `Content-Type` `application/problem+json`.
Pokud takový endpoint neexistuje, test smazat a hole místo přiznat — planý test je horší než žádný.

---

### [T-4] Dashboard: tržby a částka po splatnosti se ověřují jen `isNotNull()`, fixtury je drží na nule
**Severita:** 🟠 STŘEDNÍ
**Jistota:** OVĚŘENO
**Kde:** `src/test/java/cz/palo/autoservis/service/DashboardServiceTest.java:149`, `:194`,
`:208-210`, `:239`; fixtura `invoice(...)` na `:87-96` a `issuedInvoice(...)` na `:81-84`
(produkční dotazy `src/main/resources/mapper/DashboardMapper.xml:104` `sumOverdueInvoices`,
`:140` `sumRevenue`, `:206` `findMonthlyStats` — `rev` CTE, `:267` `sumStockValue`)

**Co je špatně:** Dvě z pěti peněžních čísel na přehledu — **tržby tento/minulý měsíc** a **částka
faktur po splatnosti** — nemají v celé suitě jedinou hodnotovou aserci:

```java
assertThat(invoices.getOverdueTotal()).isNotNull();          // :149
assertThat(summary.getRevenue().getCurrentMonth()).isNotNull();   // :209
assertThat(summary.getRevenue().getPreviousMonth()).isNotNull();  // :210
assertThat(row.getRevenue()).isNotNull();                    // :239 (měsíční statistika)
assertThat(warehouse.getStockValue()).isNotNull();           // :194
```

Horší je, jak jsou postavená data: fixtury `invoice(...)` i `issuedInvoice(...)` zakládají **jen hlavičku
faktury, nikdy položky** (`billing.invoice_items`). Všechny tři dotčené dotazy přitom berou částku
z `LEFT JOIN billing.v_invoice_price_totals` (view V32 počítá z `invoice_items`). Bez položek view řádek
nevrátí, `COALESCE(...,0)` dá **0** — takže testovaná větev „sečti tržby" se fakticky nikdy nevykoná.
`marginSection` (ř. 249–277) je naproti tomu vzorně přesná (marže z položek zakázky se tvrdí na haléř),
takže rozdíl je vidět.

**Jakou mutaci by přežil (`DashboardMapper.xml:140-153`, `sumRevenue`):**
- náhrada `SUM(t.total_gross)` za `0` nebo za `SUM(t.total_net)` — projde;
- prohození obou `FILTER (WHERE date_trunc('month', i.issue_date) = ...)` větví, tedy záměna
  „tento měsíc" ↔ „minulý měsíc" — projde (obě jsou 0);
- odstranění `WHERE i.status IN ('ISSUED','PAID')`, čímž by se do tržeb započítaly i DRAFT a CANCELLED
  faktury — projde.
Totéž pro `sumOverdueInvoices` (`:104-116`): `COALESCE(SUM(t.total_gross), 0)` → `0` projde.

**Scénář selhání:** Někdo optimalizuje `sumRevenue` a vymění `total_gross` za `total_net`. Majitel na
přehledu vidí tržby o 21 % nižší než ve skutečnosti a rozhoduje se podle nich. Suita zelená, JaCoCo
větvové pokrytí beze změny (SQL v XML JaCoCo nevidí a PIT ho nemutuje).

**Proč to vadí:** Přehled je nová funkce (commit `f82d7c1`) a majitel podle něj čte peníze. Čísla, která
nikdo neověřuje hodnotou, jsou u peněz to nejnebezpečnější — vypadají důvěryhodně.

**Návrh řešení:** Rozšířit fixturu o položky faktury (`billing.invoice_items`) tak, aby view V32 vracelo
známý součet, a nahradit `isNotNull()` konkrétními hodnotami:
- `invoicesSection` → `overdueTotal` = součet `total_gross` obou vložených faktur po splatnosti;
- nový test `revenueSection` → faktura tento měsíc (ISSUED) + faktura minulý měsíc (PAID)
  + DRAFT faktura, která se **nesmí** započítat, + CANCELLED, která se také nesmí započítat;
- `statisticsSection:239` → `row.getRevenue()` na konkrétní částku.
`stockValue` (`:194`) po `isolateSeed()` musí být `isEqualByComparingTo("0")`, ne `isNotNull()`.

---

### [T-5] Testy rolové autorizace míjejí 8 z 16 vyhrazených míst, včetně změny IBAN na fakturách
**Severita:** 🟠 STŘEDNÍ
**Jistota:** OVĚŘENO
**Kde:** `src/test/java/cz/palo/autoservis/web/RoleAuthorizationTest.java:66-118` (pokrývá 7 endpointů)
a `:33-34` (javadoc: *„Company-profile PUT nese stejnou anotaci a je pokrytý tímtéž mechanismem."*)

**Co je špatně:** V produkci je 19 anotací `@PreAuthorize`; tři z nich
(`GoodsReceiptImportController.java:45,81`, `GoodsReceiptReviewController.java:33`) povolují všechny tři
pracovní role, takže se od baseline neliší — **skutečně vyhrazených míst je 16**. `RoleAuthorizationTest`
ověřuje 7 z nich (issue/pay/cancel faktury, dobropis, deaktivace zákazníka, deaktivace vozidla, uzavření
inventury), osmé (`UserController`, ADMIN-only) je pokryté v `ProblemDetailContractTest:328`.
**Nepokrytých zůstává 8:**

| Vyhrazené místo | Kde | Test |
|---|---|---|
| `CashReceiptController` — celá třída, 4 endpointy (pokladna) | `CashReceiptController.java:29` | žádný |
| `CompanyProfileController.update` — PUT (název firmy, DIČ, **IBAN a číslo účtu**) | `CompanyProfileController.java:38` | žádný |
| `EmployeeController` — create/update/deactivate/activate (mj. hodinové sazby) | `EmployeeController.java:65,86,101,113` | žádný |
| `CustomerController.activate` | `CustomerController.java:137` | žádný |
| `VehicleController.activate` | `VehicleController.java:133` | žádný |

Javadoc testu na ř. 33–34 přitom **explicitně tvrdí**, že company-profile PUT je „pokrytý tímtéž
mechanismem". To není pokrytí, to je předpoklad — a je to jediné místo, kde se v testech mluví
o autorizaci profilu firmy.

**Scénář selhání:** Při refaktoringu (nebo při přidání dalšího endpointu s copy-paste hlavičkou) zmizí
`@PreAuthorize` z `CompanyProfileController.java:38`. Mechanik (baseline role) přepíše IBAN a číslo účtu
v profilu firmy. Profil se od té chvíle zmrazuje na **každou nově vystavenou fakturu**
(`InvoiceLifecycleTest.createFromOrder_freezesSupplierPartyFromCompanyProfile` to potvrzuje) a vejde
i do QR platby (SPAYD). Zákazníci platí na cizí účet. Suita zelená.

**Proč to vadí:** Bezpečnost + peníze. `@PreAuthorize` je jednořádková anotace, kterou nic nedrží
kromě testu; u profilu firmy je dopad přímo finanční.

**Návrh řešení:** Doplnit do `RoleAuthorizationTest` MECHANIC → 403 pro: `PUT /company-profile`,
`POST /cash-receipts` + `GET /cash-receipts/{id}`, `POST/PUT/DELETE /employees` a `/employees/{id}/activate`,
`POST /customers/{id}/activate`, `POST /vehicles/{id}/activate`. U endpointů s tělem stačí poslat
libovolné (i nevalidní) tělo — `@PreAuthorize` se v Springu vyhodnocuje **před** vstupem do metody, takže
403 přijde dřív než 400; obava v javadocu na ř. 32–34 je zbytečná a lze ji ověřit jedním testem.
Případně doplnit protiváhu „ADMIN projde (404/400, ne 403)" jako u stávajících.

---

### [T-6] Zakázka nemá stavový automat ani jediný test přechodů
**Severita:** 🟠 STŘEDNÍ
**Jistota:** OVĚŘENO
**Kde:** `src/main/java/cz/palo/autoservis/model/enums/OrderStatus.java` (holý enum bez `canTransitionTo`),
`src/main/java/cz/palo/autoservis/service/impl/OrderServiceImpl.java:111-121` (`update` nevaliduje přechod),
`src/test/java/cz/palo/autoservis/service/OrderCrudServiceTest.java:110-129` (`update` nastaví
`IN_PROGRESS` bez jakékoli kontroly)

**Co je špatně:** `InvoiceStatus` má `canTransitionTo()` a plnou matici 4×4 v `InvoiceStatusTest`;
inventura má `StockTakeStateMachineTest` (obě terminální větve); review příjemky má obě větve
v `ReceiptReviewServiceTest`. **Zakázka jako jediná z těch čtyř nemá ani automat, ani test.**
`OrderServiceImpl.update` (ř. 111–121) jen zavolá `orderConverter.applyUpdate` a `orderMapper.update`;
`grep canTransitionTo` v `OrderServiceImpl` nevrací nic. Jediné testy dotýkající se `OrderStatus`
(`OrderCrudServiceTest.countOpen_*`, `OrderSearchTest.statusFilter_*`) ověřují **filtrování a počítání**,
ne přípustnost přechodu.

**Scénář selhání:** `PUT /api/v1/orders/{id}` s `status: RECEIVED` na zakázce ve stavu `CANCELLED`
(nebo `COMPLETED`) projde. Stornovanou zakázku lze vrátit do práce; zakázka `COMPLETED`, ke které
už existuje vystavená faktura, se vrátí na `IN_PROGRESS` a v přehledu „rozpracované" i v dlaždici
„k vyfakturování" se objeví znovu. Fakturační guard `ORDER_NOT_INVOICEABLE`
(`InvoiceLifecycleTest:493`) se přitom dívá jen na aktuální stav, takže po „oživení" stornované
zakázky ji lze vyfakturovat.

**Proč to vadí:** Zakázka je doklad, na který odkazují faktury i skladové pohyby. Chybějící guard
je věc kódu, ne testů — ale **odhalila ho absence testu**: kdyby existoval `OrderStatusTest` po vzoru
`InvoiceStatusTest`, mezera by byla vidět. Uvádím to sem, protože brief výslovně žádá „obě větve každého
přechodu" i pro zakázku. *(Vlastní oprava guardu patří do průchodu o business logice; tady hlásím,
že testovací síť tenhle celý automat nepokrývá a ani pokrýt nemůže.)*

**Návrh řešení:** Rozhodnutí uživatele, které přechody povolit (viz Otevřené otázky). Poté:
`OrderStatus.canTransitionTo()` + `OrderStatusTest` s plnou maticí 7×7 (vzor `InvoiceStatusTest`)
+ integrační test v `OrderCrudServiceTest`, že `OrderServiceImpl.update` matici vynucuje proti DB
(povolený přechod projde, zakázaný → 422 `INVALID_STATUS_TRANSITION`).

---

### [T-7] `docs/backend.md` §7 uvádí o ~14 % nižší počet testů a chybí v něm 15 testovacích tříd
**Severita:** 🟡 NÍZKÝ
**Jistota:** OVĚŘENO
**Kde:** `docs/backend.md:130` („**742 testů**", stav 2026-07-24), `docs/backend.md:141` („17 konvertorů"),
`docs/backend.md:139-146` (§7.2 Organizace testů), `docs/tech-dluhy.md:101` („75 test tříd")

**Co je špatně:** Skutečný stav ke dni auditu:

| Údaj | Dokumentace | Skutečnost |
|---|---|---|
| Testovací třídy | 75 (`tech-dluhy.md:101`) | **83** (84 souborů − abstraktní `AbstractIntegrationTest`) |
| Počet testů | 742 (`backend.md:130`) | **790 metod** → **~859 spuštění** (782 `@Test` + 77 běhů z 8 `@ParameterizedTest`) |
| Testy konvertorů | 17 (`backend.md:141`) | **16** (a v `main` je konvertorů **20**, tj. 4 bez unit testu: `CashReceiptConverter`, `CreditNoteConverter`, `EmployeeConverter`, `GoodsReceiptItemConverter`) |

V §7.2 (`backend.md:139-146`) chybí mj.: `CreditNoteServiceTest`, `DashboardServiceTest`,
`EmployeeServiceTest`, `InvoiceDocumentServiceTest`, `LaborCostSnapshotTest`, `OrderItemServiceTest`,
`OrderInvoiceStatusProjectionTest`, `SpaydBuilderTest`, `AmountInWordsTest`, `PagedResponseTest`,
`RoleAuthorizationTest`, `CorsConfigTest`, `CorsPropertiesBindingTest`, `ProdSeedIntegrationTest`,
`GeneratePasswordHashTest`. Sekce „Web / kontrakt" (ř. 144) uvádí jediný test, ve skutečnosti jsou tam čtyři.

**Scénář selhání:** Vývojář (nebo AI agent) čte `backend.md` §7 jako mapu pokrytí — CLAUDE.md ho k tomu
posílá — a usoudí, že pokladní doklady, dobropis nebo rolová autorizace jsou pokryté „někde v těch 742
testech". Skutečné mezery (T-1, T-5) zůstanou neviditelné.

**Proč to vadí:** Dokumentace slouží jako mapa pokrytí; pokud čísla neodpovídají, mapa se přestane
používat. Podhodnocení samo o sobě neškodí, ale **neúplný seznam tříd** aktivně skrývá díry.

**Návrh řešení:** Aktualizovat `backend.md:130` a §7.2 (nejlépe generovat počty z běhu, ne psát ručně)
a `tech-dluhy.md:101`. U §7.2 raději uvést strukturu po balíčcích než výčet, který zastarává s každou
novou třídou.

---

### [T-8] Tvrzení o mutačním pokrytí konvertorů a enumů neodpovídá konfiguraci PIT
**Severita:** 🟡 NÍZKÝ
**Jistota:** PRAVDĚPODOBNÝ
**Kde:** `docs/tech-dluhy.md:360-362` („sweep `service.*` + `security.service.*` … **Klíčové balíčky
výš: konvertory a enumy 100 %**") vs. `pom.xml:323-326`

**Co je špatně:** PIT má v `pom.xml:323-326` `targetClasses` omezené na
`cz.palo.autoservis.service.*` a `cz.palo.autoservis.security.service.*`. Balíčky
`cz.palo.autoservis.model.converter` a `cz.palo.autoservis.model.enums` v cíli **nejsou**, takže je
konfigurovaný sweep vůbec nemutuje a 100% mutační skóre pro ně z něj vzejít nemohlo. (JaCoCo rule
v `pom.xml:285-300` `model.converter` naopak zahrnuje — pravděpodobně došlo k záměně obou konfigurací.)

**Nejistota:** Sweep mohl být spuštěn ad hoc s ručně rozšířeným `targetClasses` z příkazové řádky;
z repozitáře to nelze rozhodnout, PIT report v `target/` není verzovaný.

**Scénář selhání:** Někdo se rozhodne, že konvertory jsou „mutačně doložené na 100 %", a přestane
u nich hlídat kvalitu asercí. T-2 (`CreditNoteConverter` bez testu, prohození stran a částek přežije)
je přesně ten případ.

**Proč to vadí:** Mutační skóre je v tomto projektu hlavní argument pro tvrzení „testy nejsou plané".
Když se argument opře o balíček, který PIT nemutoval, tvrzení nedrží.

**Návrh řešení:** Buď rozšířit `pom.xml:323-326` o `cz.palo.autoservis.model.converter.*` a
`cz.palo.autoservis.model.enums.*` (jsou to čisté unit testy, běh bude rychlý), nebo formulaci
v `tech-dluhy.md:361` opravit na to, co sweep skutečně pokryl.

---

### [T-9] Poznámka v `pom.xml` uvádí jiná čísla pokrytí než `tech-dluhy.md` k témuž datu
**Severita:** 🟡 NÍZKÝ
**Jistota:** OVĚŘENO
**Kde:** `pom.xml:240` („2026-07-23: instrukce 84,5 %, větve 73,9 %") vs.
`docs/tech-dluhy.md:352-353` („Větvové pokrytí **75,3 %**, instrukční **86,8 %**", stav 2026-07-23)
a `docs/plan-testy.md:363` (75,3 %)

**Co je špatně:** Dvě různé dvojice čísel pro tentýž den. Komentář v `pom.xml` zdůvodňuje výši prahů
(BUNDLE instrukce ≥ 0,80, větve ≥ 0,68) — pokud čísla nesedí, nejde posoudit, jakou rezervu prahy mají.

**Scénář selhání:** Někdo se rozhodne prahy „dotáhnout na reálnou hodnotu" podle špatného čísla,
nastaví větve na 0,74 a build začne padat na kolísání.

**Proč to vadí:** Kosmetika, ale právě u prahů se čísly argumentuje.

**Návrh řešení:** Sjednotit; ideálně v komentáři neuvádět hodnotu, jen datum posledního zvýšení prahu,
a aktuální čísla nechat v jednom dokumentu (`backend.md` §7.1).

---

### [T-10] `StockTakeStateMachineTest`: dvojí uzavření inventury se ověřuje jen jako `RuntimeException`
**Severita:** 🟡 NÍZKÝ
**Jistota:** OVĚŘENO
**Kde:** `src/test/java/cz/palo/autoservis/service/StockTakeStateMachineTest.java:134-135` a `:157-158`

**Co je špatně:** Ve dvou testech terminálních stavů se u `close()` použije nejobecnější možná aserce:

```java
assertThatThrownBy(() -> stockTakeService.close(id, "znovu", USER_ID))
        .isInstanceOf(RuntimeException.class);
```

Ostatní operace v týchž testech přitom používají přesný helper `assertNotEditable` (`:294-299`,
tvrdí `BusinessRuleException` s kódem `STOCK_TAKE_NOT_EDITABLE`) — a produkční kód vyhazuje právě ten
(`StockTakeServiceImpl.java:328-331`, `requireOpen`). Aserce projde i pro `NullPointerException`,
`IllegalStateException` nebo `ResourceNotFoundException`, tedy i pro pád ve zcela jiné části metody.

**Jakou mutaci by přežil:** Přesun `requireOpen(stockTake)` (`StockTakeServiceImpl.java:170`) pod
kód, který na uzavřené inventuře stejně spadne jinou výjimkou — test to nerozliší. Kód chyby, na který
reaguje frontend, tady ověřený není.

**Proč to vadí:** Mírně — správnou aserci má `StockTakeTest.onlyOneOpenAndSingleClose:304-306`
(HTTP úroveň, 422 `STOCK_TAKE_NOT_EDITABLE`), takže díra je krytá jinde. Jde o nekonzistenci
uvnitř třídy, která jinak drží vysokou laťku.

**Návrh řešení:** Nahradit obě místa voláním `assertNotEditable(...)`.

---

### [T-11] Přetečení pěti číselných řad nemá test
**Severita:** 🟡 NÍZKÝ
**Jistota:** OVĚŘENO
**Kde:** `src/main/resources/db/migration/V49__invoice_number_on_issue.sql:44-45`,
`V55__init_credit_notes.sql:67-68`, `V56__reset_order_number_per_year.sql:35-36`,
`V57__init_cash_receipts.sql:73-74`, `V61__add_stock_take_number.sql:40-41`
(negativní důkaz: `grep -rn "overflow" src/test/java` → 0 výskytů)

**Co je špatně:** Všech pět generátorů čísel dokladů má guard `RAISE EXCEPTION … overflow`
(faktura/dobropis/PPD při >999 za měsíc, zakázka/inventura při >9999 za rok). Žádný z nich není
pokrytý. `DatabaseTriggerTest` testuje formát, inkrement a per-rok reset (ř. 109–186), přetečení ne.

**Scénář selhání:** Reset řady se rozbije (např. někdo změní `EXTRACT(YEAR …)` na konstantu) a guard
místo chybějícího resetu začne v prosinci hlásit `overflow` — nebo naopak guard při refaktoringu vypadne
a `LPAD` tiše vyrobí pětimístné číslo, které rozbije formát dokladu. Ani jedno nikdo nezachytí.

**Proč to vadí:** Nízké riziko (999 faktur/měsíc v malém servisu nehrozí), ale guard se dá otestovat
třemi řádky a je to zároveň nejlevnější test správnosti resetu.

**Návrh řešení:** V `DatabaseTriggerTest` doplnit: vložit řádek s číslem `…999` (resp. `…9999`) v aktuálním
období přímo přes `JdbcTemplate` (obchází trigger — vzor `insertOrderWithNumber:466-471`) a ověřit,
že další vystavení skončí `DataAccessException`. Stačí u faktury a u zakázky, ostatní tři jsou kopie
téhož vzoru.

---

### [T-12] `GeneratePasswordHashTest` je provozní skript v testovací suitě
**Severita:** 🟡 NÍZKÝ
**Jistota:** OVĚŘENO
**Kde:** `src/test/java/cz/palo/autoservis/tool/GeneratePasswordHashTest.java:30-48`
(javadoc ř. 12–13 to sám přiznává: *„Nejde o skutečný test; `@Test` je jen proto, aby šel spustit
přes Maven Surefire"*)

**Co je špatně:** Třída nic netestuje — bez `-Dadmin.password` se přeskočí (`Assumptions`, ř. 33),
s ním vygeneruje BCrypt hash, zapíše ho do `target/admin_password_hash.txt` (ř. 39–41) a vypíše
na stdout. Nemá jedinou aserci. V suitě figuruje jako „přeskočený test", takže do počtů v dokumentaci
(T-7) vstupuje jako test.

**Scénář selhání:** Nastavení `-Dadmin.password` v CI (např. omylem v profilu) způsobí, že se
produkční heslo objeví v Surefire reportu a v `target/`. Zároveň komentář na ř. 24–26 varuje, že
cost encoderu se musí ručně držet v souladu se `SecurityConfig.passwordEncoder()` — což nic nehlídá.

**Proč to vadí:** Kosmetika a hygiena; zkresluje počty testů a míchá provozní nástroj do testovací suity.

**Návrh řešení:** Buď přesunout do samostatné `main` třídy pod `tools/` a spouštět přes `exec:java`,
nebo aspoň přejmenovat mimo vzor `*Test` (Surefire ji pak neposbírá) a doplnit jednu skutečnou aserci
(hash prochází `passwordEncoder.matches`). *(Doporučené řešení je věcí volby — viz Otevřené otázky.)*

---

## Co bylo ověřeno jako v pořádku

**Infrastruktura**
- `AbstractIntegrationTest` — singleton container pattern je implementován správně (statický blok, žádné
  `@Testcontainers`/`@Container`, `@DynamicPropertySource`); komentář vysvětluje proč. Ryuk uklidí kontejner.
- `application-test.yaml` — suita opravdu nepotřebuje env proměnné (`jwt.secret` i dummy AI klíč jsou v profilu);
  tvrzení `backend.md:128` sedí.
- `ProdSeedIntegrationTest` záměrně nedědí z `AbstractIntegrationTest` a startuje vlastní čerstvý kontejner
  s produkčními Flyway locations — jediný způsob, jak produkční seed otestovat. Ověřuje i `setval` sekvence
  zákazníků (ř. 89–91), což je R-05.
- **Izolace:** ze 47 tříd dědících z `AbstractIntegrationTest` je 42 `@Transactional` (rollback po každém testu),
  5 nikoli. Ty jsou buď nutně netransakční (`LoginLockoutTest`, `ChangePasswordTest` — `LoginAttemptService`
  běží v `REQUIRES_NEW` a transakce testu by na řádku `security.users` držela zámek), nebo nezapisují
  (`JwtServiceTest`, `CorsConfigTest`, `AutoservisApplicationTests`); obě zapisující uklízejí v `@AfterEach`.
  Žádné `@DirtiesContext`, žádné `@TestMethodOrder`, žádná závislost na pořadí, kterou by šlo doložit.
- **Reprodukovatelnost:** žádná náhodná data (jediný `UUID.randomUUID()` je v refresh-token fixtuře, kde je
  správný); relativní časy (`LocalDate.now().plusDays(...)`) jsou používané konzistentně a bez hraničních
  případů, které by praskly o půlnoci nebo na přelomu roku. Fixní `Clock` sice není zaveden, ale žádný
  test na absolutním „dnes" nestojí.

**Kvalita asercí (kontrolováno cíleně proti antivzorům z briefu)**
- 0× `try/catch` polykající chybu, 0× `assertDoesNotThrow`/`doesNotThrowAnyException` v celé suitě.
- `verify()` na mocku se používá 2×, obojí smysluplně (`verifyNoInteractions` a `never()` — důkaz, že se
  do DB vůbec nesáhlo).
- 59 výskytů `isNotNull()` prošlo ručně; drtivá většina jsou předpoklady bezprostředně před hodnotovou
  asercí. Skutečně planých je 5 a všechny jsou v T-2 a T-4.
- Past „ověření zápisu přes MyBatis lokální cache" je projektu známá a v kritických místech obejitá přímým
  `JdbcTemplate` dotazem (`CompanyProfileServiceTest:29-36`, `DatabaseTriggerTest:32-38`, všechny skladové
  testy). **Adversariální kontrola:** u `Supplier`/`Vehicle`/`Employee` update tento problém nehrozí —
  guard `if (affectedRows == 0) throw IllegalStateException` mutant „removed call to mapper::update"
  zabije dřív, než se k re-readu dojde. Původní podezření se nepotvrdilo.

**Stavové automaty**
- Faktura: `InvoiceStatusTest` testuje **celou matici 4×4** (4 povolené + 12 zakázaných + `EnumSource`
  kontrola přesné množiny cílů) a `InvoiceLifecycleTest` (43 testů) dokazuje, že ji service vynucuje proti DB,
  včetně obou terminálních stavů a guardovaného UPDATE (409 `INVOICE_STATE_CHANGED`).
- Inventura: obě terminální větve (CLOSED, CANCELLED) + invariant „nejvýš jedna otevřená" včetně obou
  cest jeho uvolnění.
- Review příjemky: PENDING_REVIEW → CONFIRMED/REJECTED/CANCELLED i zakázané kombinace
  (`RECEIPT_NOT_EDITABLE`, `RECEIPT_NOT_CANCELLABLE`, `RECEIPT_ALREADY_USED`).
- Editační zámky faktury (TD-58) jsou testované na **mapper úrovni** s vysvětlením proč (souběh nejde
  deterministicky nasimulovat) — poctivý přístup, ne obcházení.

**Peníze a zaokrouhlování**
- `DraftVerificationSumsTest` (30 testů) je nejlepší třída suity: každá kontrola se testuje ve shodě
  i v rozporu, drafty jsou schválně poskládané tak, aby selhala **právě jedna** kontrola (jinak by mutant
  „return true" přežil), tolerance se testuje na hranici (0.04 / 0.05 / 0.06), DPH po sazbách i s testem
  na prohození základů mezi sazbami.
- `SpaydBuilderTest` vznikl přesně proto, že PDF test kontroluje jen `%PDF-` — a tvrdí celý SPAYD řetězec
  včetně částky na dvě desetinná místa.
- `StockValuationTest` testuje FIFO ocenění na dvou šaržích s různými cenami a částečném výdeji, s explicitním
  komentářem, že průměr by dal jinou hodnotu.
- `OrderItemServiceTest.summary_*` ověřuje rozpad ceny po typech i náklad (podklad marže) na konkrétních číslech.
- `DashboardServiceTest.marginSection` ověřuje marži práce i materiálu, tento vs. minulý měsíc, a že položka
  bez známého nákladu se vynechá.

**Skladový ledger**
- Append-only trigger ověřen skutečným pokusem o `DELETE` (`ReceiptReviewServiceTest:203-213`).
- Storno příjemky: kompenzační pohyb, nikoli smazání (`:672-700`); čerpaná příjemka → 422 (`:702-722`).
- Znaménka a hranice: `QUANTITY_EXCEEDS_REMAINING` u výdeje, odpisu, vratky i inventurního manka;
  agregace více požadavků na tutéž šarži (K6) v obou větvích (3+3 při remaining 4 → 422; 2+2 → projde).
- Kompenzační `ISSUE_RETURN` při smazání skladové položky ze zakázky včetně počtu vzniklých pohybů.
- Inventura: FIFO manko po šaržích, přebytek přes pseudo-příjemku bez DPH (ČÚS 007), rozdíl proti
  **aktuálnímu** stavu (ne snapshotu), „nepočítáno ≠ nula".

**DB triggery a číselné řady**
- Čtou se přímo `JdbcTemplate` dotazem, ne přes MyBatis (a je vysvětleno proč).
- U generovaných čísel se netvrdí jen formát, ale i **inkrement dvou po sobě jdoucích záznamů** —
  formát by prošel i triggeru vracejícímu pořád totéž.
- Per-rok reset řady ZAK (TD-57) v obou směrech (MAX+1 navazuje; rok 2099 letošní pořadí neovlivní).
- Číslo faktury podle `issue_date`, ne podle „dneška" (K-3).
- Časy/zóny: round-trip okamžiku, tři uložení bez driftu (regrese TD-47), ekvivalence 14:00Z a 16:00+02:00.

**Security**
- Rotace refresh tokenů včetně detekce reuse a preventivního odvolání **všech** sessions; expirovaný
  token naopak ostatní sessions neruší (obě větve).
- Podpis JWT se ověřuje cizím klíčem, expirace tokenem podepsaným **aplikačním** klíčem (jinak by se
  testovala zase jen signatura).
- `TokenHasherTest` používá referenční vektor SHA-256("abc") z FIPS 180-4 — nezávislý na implementaci —
  a hlídá UTF-8 kódování konkrétním digestem.
- Lockout po 10 pokusech včetně toho, že správné heslo pak dá `LockedException`, a že admin reset odemyká.
- `JwtAuthFlowTest` jde reálnou cestou přes cookies (ne `.with(user(...))`), takže filtr se neobchází.

**Web kontrakt a ostatní**
- `ProblemDetailContractTest` tvrdí u každé odpovědi status, `Content-Type`, `title`, `detail` i kód
  v `errors[]` — ne jen „vrátilo to chybu". Pokrývá 400/401/403/404/409/422/503.
- `CorsPropertiesBindingTest` dokazuje **příčinu** regrese E7 (plochá property je `null`) — vzácně dobrý
  regresní test.
- `ListSortingTest` pokrývá všech 8 stránkovaných seznamů a zdůvodňuje, proč si zakládá fixtury
  (nad prázdným seznamem je „je seřazeno" triviálně pravda — na to první verze testu narazila).
- `PagedResponseTest` pokrývá matici 1-based stránkování včetně jádra TD-50.
- **JaCoCo `check`** je bound na fázi `test`, takže `./mvnw test` prahy **skutečně vynutí** (BUNDLE
  instrukce ≥ 0,80, větve ≥ 0,68; PACKAGE `service`/`service.impl`/`security.service`/`model.converter`
  větve ≥ 0,65). Prahy odpovídají popisu v `backend.md:136`.
- **PIT** je konfigurován korektně (JUnit5 plugin, manuální PDF test vyloučen, běh jen ručně) — s výhradou
  rozsahu, viz T-8.

---

## Otevřené otázky pro uživatele

1. **Přípustné přechody stavu zakázky (T-6).** Které přechody `OrderStatus` mají být povolené? Varianty:
   - **(a) volný pohyb dopředu i zpět mezi provozními stavy** (`RECEIVED` ↔ `DIAGNOSIS` ↔ `WAITING_FOR_PARTS`
     ↔ `IN_PROGRESS` ↔ `READY_FOR_PICKUP`), ale `COMPLETED` a `CANCELLED` terminální — *doporučuji*;
     odpovídá reálnému provozu (mechanik se běžně vrací z „čeká na díly" zpět do práce) a zároveň chrání
     doklad, na který visí faktura a skladové pohyby;
   - **(b) přísně dopředu**, návrat jen přes storno a novou zakázku — bezpečnější, ale v dílně otravné;
   - **(c) ponechat volné** (dnešní stav) a spolehnout se na kázeň obsluhy.
   *Rozhodnutí uživatele* — teprve podle něj má smysl psát matici testů.

2. **Priorita doplnění testů PPD (T-1).** Má se testovací síť pro pokladní doklad doplnit teď, nebo až
   po rozhodnutí o rozšíření evidence úhrad (TD-62, částečné úhrady)? Pokud se PPD bude měnit, dává smysl
   psát testy až na cílové chování — jen prosím ne „až potom", protože modul už je v provozu.

3. **`GeneratePasswordHashTest` (T-12).** Vyhovuje současný stav (nástroj v testech, skipnutý), nebo ho
   přesunout mimo suitu? Je to čistě věc vkusu a provozního zvyku — pokud ho spouštíte přes
   `./mvnw test -Dtest=GeneratePasswordHashTest`, je dnešní řešení nejpohodlnější.

4. **Rozšíření PIT o konvertory a enumy (T-8).** Stojí za to přidat `model.converter.*` a `model.enums.*`
   do `targetClasses`? Jsou to čisté unit testy, takže běh bude rychlý a T-2 by se tím odhalilo automaticky.
   Alternativa: nechat PIT jak je a jen opravit formulaci v dokumentaci.
