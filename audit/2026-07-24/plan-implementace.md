# Implementační plán — audit 2026-07-24

> **Pracovní dokument, podle kterého postupujeme.** Zapracovává rozhodnutí R-1…R-6
> ([rozhodnuti.md](rozhodnuti.md)) do seřazených etap. Úplný katalog nálezů je v
> [plan-oprav.md](plan-oprav.md), zdůvodnění nálezů v dílčích reportech 01–09, přehled v
> [00-prehled.md](00-prehled.md). Kde se pořadí liší od plan-oprav.md, platí tento dokument.
>
> **Pravidla provedení:**
> - Pravidlo č. 1 — u každé položky nejdřív ukázat CO a PROČ, provedení schvaluje uživatel.
> - Hotové migrace se nemění — DB změny jdou do nové migrace **V45+**, každá + aktualizace `databaze.md`.
> - Každá oprava dostane regresní test (mnoho nálezů vzniklo tam, kde test chyběl).
> - Po změně měnící fakta v dokumentaci aktualizovat příslušný `docs/*`.
> - Odhady: **S** ≤ půl dne · **M** 1–2 dny · **L** 3+ dní.
>
> **Legenda stavu:** `[ ]` neuděláno · `[~]` rozpracováno · `[x]` hotovo.

---

## Pořadí etap a proč

```
E0  Rychlé a nezávislé opravy (bugy + security quick-win)   ← lze hned, nízké riziko
E1  Fakturační jádro                                         ← nejvyšší věcné riziko, vnitřní návaznost
E2  Evidence úhrad (R-3, var. a)                             ← staví na stavu faktury z E1
E3  Integrita dat (DB vynucení + vazby)                      ← nezávislé na E1/E2, může běžet paralelně
E4  Testovací základ (reprodukovatelnost + JWT e2e)          ← ať další práci chrání test
E5  Dobropis (R-2)                                           ← staví na srovnaném jádru E1 + úhradách E2
E6  Robustnost (chyby, validace, hardening)                  ← před produkcí
E7  Produkční příprava (plné TD-24, hlavičky, secrets)       ← poslední před nasazením
E8  Dokumentace a úklid                                      ← průběžně
```

Kritická cesta: **E0 → E1 → E2 → E5**. E3 a E4 lze prokládat. E6–E8 před nasazením.

---

## E0 — Rychlé a nezávislé opravy

Zjevné bugy a levné security pojistky. Žádná nevyžaduje architektonické rozhodnutí; lze dělat okamžitě a nezávisle na sobě.

- [x] **E0.1** Frontend — `addAlert` chybí v `OrderItemsWrapper` *(F2, K-18)* · **S**
  Doplnit `import { useAlert }` + `const { addAlert } = useAlert();` (`OrderItemsWrapper.jsx`). Jinak catch v `handleReorder:205` sám vyhodí `ReferenceError`. Akceptace: vynucené selhání reorderu ukáže toast, ne pád.
- [x] **E0.2** Frontend — `OrderItemsSummary` vrací `undefined` *(F3)* · **S**
  `OrderItemsSummary.jsx:7` `return` → `return null;`. Akceptace: selhání načtení souhrnu neshodí formulář do ErrorBoundary.
- [x] **E0.3** Frontend — fokus v modalech *(F1, K-17)* · **S–M**
  `Modal.jsx` — oddělit autofocus (efekt jen `[show]`) od keydown/focus-trap; `onClose`/`closable` přes `useRef`. Akceptace (manuální): napsání 8znakového hesla do 2. pole „Změna hesla" bez úniku fokusu; ověřit i OrderItemFormModal, ManualReceiptModal.
- [x] **E0.4** Security — invalidace sessions při změně/resetu hesla *(K-6)* · **S**
  Do `AuthenticationService.changePassword` a `UserServiceImpl.resetPassword` přidat `refreshTokenMapper.revokeAllByUserId(userId)`. Akceptace: po změně hesla starý refresh token → 401.
- [x] **E0.5** Security — hash refresh tokenů *(K-7)* · **M**
  Hashování SHA-256 zavedeno v `AuthenticationService` (jako blacklist, `TokenHasher`); mapper zůstává verbatim. Vyžádalo si migraci **V45** (rozšíření `refresh_tokens.token` na VARCHAR(64) pro hash). Staré syrové tokeny přestanou po nasazení matchovat (uživatel se přihlásí znovu). Řešeno spolu s E0.4. Akceptace: v DB není holé UUID; rotace/reuse dál funguje. **Splněno** (test `refreshToken_isStoredHashedNotRaw`).
- [x] **E0.6** Backend — last-admin guard v `UserServiceImpl.update` *(K-2, S-9/N2)* · **S**
  Před `deleteRoles` (ř. ~114): je-li uživatel admin a nové `roleIds` ADMIN neobsahují → `countEnabledByRoleExcluding(ROLE_ADMIN, id) > 0`, jinak 422 `CANNOT_REMOVE_LAST_ADMIN`. Akceptace: test odebrání ADMIN poslednímu adminovi selže.
- [x] **E0.7** Backend — seznam příjemek posílá `page-1` *(sklad S-1)* · **S**
  `ReceiptReviewServiceImpl.java:56` odstranit `- 1`. Akceptace: `first`/`last` v odpovědi sedí s 1-based kontraktem.
- [x] **E0.8** Security — R-4 okamžitá pojistka *(K-10, R-4 část 1)* · **S**
  Provedeno migrací **V46**: odebrání role ROLE_CUSTOMER + deaktivace (`enabled=false`) seed účtů `jan.novak` (10) / `firma.logistika` (11) — účty se nemažou (FK zákazníků, no-delete filozofie), jen se jim vezme přístup. `SecurityConfig` — `ROLE_CUSTOMER` odříznut od `/api/**` (`hasAnyRole('ADMIN','MANAGER','MECHANIC')`, účtové `/api/*/auth/**` zůstávají `authenticated()`). `databaze.md` + `konvence.md §19` aktualizovány.
- [x] **E0.9** DB — číselné bugy *(R-5 bugy: N-11, A9)* · **S**
  Provedeno migrací **V47**: `setval` sekvence zákazníků nad seed (GREATEST(10, current) — nikdy zpět). **Rozsah zúžen (schváleno v reportu):** LPAD overflow guard (A9, NÍZKÝ, u malého servisu nedosažitelný) **odložen** do přepisu příslušných triggerů — faktura v E1.3, zakázka v E3.8; trigger V9 (ZNK) zůstává `LPAD(...,4)`. Důvod: nepsat guard do triggerů, které se za dvě etapy stejně přepisují.
- [x] **E0.10** Dokumentace — api.md sekce Cookies *(K-16, V1)* · **S**
  Přepsat tabulku Cookies na hodnoty z konfigurace, odstranit blok „⚠️ Známé nesoulady" (TD-31 vyřešeno). Sladit backend.md ř. 75 a tech-dluhy.md.

---

## E1 — Fakturační jádro

Nejvyšší věcné riziko; položky mají vnitřní návaznost. Doporučeno dělat v uvedeném pořadí.

> **Stav E1 (2026-07-24): dokončeno E1.1–E1.7** (celá suita zelená, 735 testů). Migrace V48–V50.
> Jediná výjimka: **S-4 (TOCTOU guarded-write editace DRAFTu)** přesunut do E6 (hardening) — vyžaduje
> souběh, malé okno; E1.3 přepsal jen číslovací trigger, ne guarded editaci hlavičky/položek.

- [x] **E1.1** UPDATE mappery na full-replace + `gdpr_consent` *(K-4, K-11; R-1 nezávislé)* · **M**
  Sjednotit `CustomerMapper`, `OrderMapper`, `InvoiceMapper`, `InvoiceItemMapper`, `AddressMapper` na statický full-replace (vzor `SupplierMapper`; NOT NULL přes `COALESCE`). Do UPDATE zákazníka doplnit `gdpr_consent`/`gdpr_consent_at`; `marketing_consent_at` posouvat jen při skutečné změně (`CASE WHEN … IS DISTINCT FROM …`). Akceptace: testy „vymazání nullable pole" pro každý modul + `PUT gdprConsent:false` → v DB false + editace zákazníka nezmění `marketing_consent_at`.
- [x] **E1.2** Storno → přefakturace *(R-1, K-1, P1.1)* · **M**
  Migrace V45: `DROP CONSTRAINT uq_invoices_order_id` → `CREATE UNIQUE INDEX uq_invoices_order_active ON billing.invoices (order_id) WHERE status <> 'CANCELLED'`. `InvoiceServiceImpl.createFromOrder:74` — kontrolu `findByOrderId` filtrovat na ne-CANCELLED. Akceptace: storno → nová faktura projde; dvě aktivní faktury k zakázce ne. Aktualizovat `databaze.md`.
- [x] **E1.3** Číslování faktury na DRAFT→ISSUED *(R-6, K-3, P1.3)* · **M**
  Migrace V45: `invoice_number` + `variable_symbol` nullable; trigger V15 přepnout z `BEFORE INSERT` na podmíněný `BEFORE UPDATE` při přechodu na ISSUED, prefix z `issue_date` (ne `CURRENT_DATE`), advisory lock zachovat. `issue()` orazítkuje `issue_date`; `InvoiceDto.UpdateRequest` doplnit `issueDate`/`taxableSupplyDate` (editovatelné v DRAFTu, s validací). Akceptace: DRAFT nemá číslo; po ISSUED má číslo dle issue_date; vystavená řada souvislá.
- [x] **E1.4** Snapshot vozidla na faktuře *(K-5, N-2, P1.4)* · **M**
  Migrace V45: `vehicle_vin_snapshot`, `vehicle_brand_snapshot`, `vehicle_model_snapshot` (backfill z aktuálních dat). `createFromOrder` plnit; `InvoiceMapper` číst; `invoice.html` přepnout na snapshoty. Akceptace: editace vozidla po vystavení faktury nezmění doklad.
- [~] **E1.5** Guardy fakturace *(P1.5: S-2, S-3, S-4, S-12/A6/A7/N-6)* · **M**
  **Hotovo (3 business guardy, s testy):** `issue()` neprázdné položky → `INVOICE_HAS_NO_ITEMS`; `createFromOrder` `order.status == CANCELLED` → `ORDER_NOT_INVOICEABLE`; `addItem` `orderItem.orderId == invoice.orderId` → `ITEM_NOT_OF_INVOICED_ORDER`.
  **Odloženo do E6 (S-4, TOCTOU):** guardovaný UPDATE hlavičky/položek `WHERE … AND status='DRAFT'` (0 řádků → 409). Vyžaduje souběh, malé okno; udělá se v E6 (hardening) — E1.3 přepsal jen číslovací trigger, ne guarded-write editace.
- [x] **E1.6** Odpověď položky faktury s `net/vat/gross` *(S-7/№10, P1.7)* · **S**
  `InvoiceItemMapper.findById` doplnit počítané `ROUND(...)` výrazy (sdílený `<sql>` fragment s `findByInvoiceId`). Akceptace: POST/PUT položky vrací spočtené součty.
- [x] **E1.7** PDF konceptu/storna *(A3/S-11, P1.6)* · **S**
  `invoice.html` vodoznak „NÁVRH"/„STORNOVÁNO" dle `invoice.status`; zvážit 422 v `renderPdf` pro CANCELLED. Akceptace: test kontroléru (stav → obsah/kód). *(E1.3 už dělá koncept bez čísla, což A3 posiluje.)*

---

## E2 — Evidence úhrad (varianta a)

Staví na stavu faktury z E1. *(R-3)*

- [x] **E2.1** Data úhrady u faktury · **M**
  Migrace **V51**: `billing.invoices` +`paid_at`, `paid_amount`, `paid_method` (nullable) + backfill PAID. `markPaid` po přechodu na PAID volá `recordPayment` (datum = DB NOW(), částka = celková částka dokladu, způsob = předepsaný payment_method). DTO/konvertor/dom/mapper doplněny. Test `markPaid_recordsPaymentDetails`.
- [~] **E2.2** Přehled po splatnosti · **S**
  **Backend hotový:** `InvoiceSearchParams.overdue` + filtr v `searchWhere` (`status = ISSUED AND due_date < CURRENT_DATE`); endpoint `GET /invoices?overdue=true` je připraven. Test `getPage_overdueFilter_returnsOnlyOverdueIssued`. **FE seznam/přepínač** (spotřebování endpointu) zbývá — malý UI úkol, flagován v reportu.

> Plná evidence úhrad 1:N (částečné úhrady, přeplatky) = budoucí TD, ne teď.

---

## E3 — Integrita dat

Nezávislé na E1/E2 — může běžet paralelně. DB vynucení + vazby.

- [x] **E3.1** Uzavřít ledger na úrovni DB *(K-13: N-3, N-4, P2.1)* · **S**
  Migrace V45: `BEFORE UPDATE OR DELETE` trigger na `stock_movements` (`RAISE EXCEPTION` — append-only); `UNIQUE (id, product_id)` na `goods_receipt_items` + složený FK `(batch_id, product_id)` na `stock_movements`. Akceptace: přímý UPDATE/DELETE pohybu selže; pohyb na šarži cizího produktu selže. Aktualizovat `databaze.md`.
- [x] **E3.2** Vazba zakázka↔vozidlo↔zákazník *(K-12: V-3/A5, P2.2)* · **M**
  `OrderServiceImpl.create`: načíst vozidlo, ověřit existenci (404), aktivitu (422), `vehicle.customerId == order.customerId` → `VEHICLE_NOT_OWNED_BY_CUSTOMER`. `VehicleServiceImpl.update`: při změně `customerId` ověřit otevřené zakázky. Akceptace: zakázka na cizí vozidlo → 422; testy.
- [x] **E3.3** Autocomplete zákazníků filtruje `is_active` *(S-8/№8, P2.3)* · **S**
  `CustomerMapper.autocomplete` → `WHERE is_active = TRUE`; kontrola aktivity ve `create` cestách. Akceptace: deaktivovaný zákazník se nenabídne.
- [x] **E3.4** Inventurní přebytkové šarže viditelné *(K-14: sklad V-1/N-18, P2.4)* · **S**
  `WarehouseMapper.findBatchesByProductId`: `JOIN suppliers` → `LEFT JOIN` + `COALESCE(s.name, gr.supplier_name_snapshot)`; totéž `v_batch_provenance` (migrace V45). Akceptace: přebytková šarže je na kartě vidět a jde ručně korigovat.
- [ ] **E3.5** Embedded resultMap zákazníka u vozidla *(S-13/№6, +№7, №9, P2.5)* · **M**
  `CustomerMapper` embedded fragment aliasovat prefixem + `columnPrefix`; doplnit `stk_valid_until`/`engine_code` do `vehicleColumns` detailu zákazníka (№7); `WarehouseMapper.findByGoodsReceiptId` → `resultMap` (№9). Akceptace: test s deaktivovaným zákazníkem + aktivním vozidlem (`customer.active` správně).
- [x] **E3.6** Chybějící indexy *(DB N-7/№16, P2.6)* · **S**
  Migrace V45: `idx_orders_customer_id`, `idx_orders_vehicle_id`, `idx_vehicles_customer_id`, `idx_invoice_items_order_item_id`. Aktualizovat `databaze.md`.
- [x] **E3.7** Dedup DL vylučuje stornované *(sklad/№12, P2.7)* · **S**
  `WarehouseImportMapper.findDeliveryNoteReceiptId`: `AND gr.status NOT IN ('REJECTED','CANCELLED')`. Akceptace: test storno DL → následná faktura naskladní.
- [ ] **E3.8** Zakázka: reset per rok + sjednocení mechanismu *(R-5)* · **M**
  Migrace V45: `ZAK` číslování převést na vzor faktury (MAX+1 za rok + advisory lock), reset per rok. Zákaznické číslo (ZNK) nechat jako celoživotní sekvenci + zdokumentovat v `databaze.md`, že rok = rok registrace. Akceptace: zakázky 1.1. dalšího roku začínají od 0001; test.

---

## E4 — Testovací základ

Ať další práci chrání testy. *(Vlna 4.1+4.2)*

- [x] **E4.1** Reprodukovatelnost běhu *(testy V1)* · **S**
  `src/test/resources/application-test.yaml` — testovací `jwt.secret` + dummy `spring.ai.anthropic.api-key`. Akceptace: suita běží na čistém clone bez gitignorovaných secrets a bez reálného AI klíče v kontextu.
- [x] **E4.2** JWT filtr + auth cookie e2e *(K-15: testy V2)* · **M**
  MockMvc „plný kruh": login → cookie → chráněný GET (200) → logout → 401 `TOKEN_BLACKLISTED`; expirovaný/cizí token → `TOKEN_EXPIRED`/`TOKEN_INVALID`. Fixuje i správné hodnoty cookies (regrese TD-31). Akceptace: testy zelené.

---

## E5 — Dobropis (opravný daňový doklad)

Staví na srovnaném jádru E1 a úhradách E2. Architektura a §45 náležitosti viz **R-7** (grounded ve výzkumu §45 ZDPH). *(R-2, K-8)*

- [x] **E5.1** Model + vytvoření/vystavení + §45 detail · **M**
  Migrace **V55** `billing.credit_notes` (FK na původní fakturu, `correction_reason`, vlastní řada `OD{YYYYMM}###` přidělovaná triggerem při vystavení). Doména/mapper/DTO/konvertor/service/controller. `createFromInvoice` ověří, že faktura je ISSUED/PAID (jinak `INVOICE_NOT_CORRECTABLE`); `issue` DRAFT→ISSUED s guardovaným přechodem. §45 rozdíly (záporné souhrny) a strany se odvozují z původní faktury. Endpointy `POST /credit-notes`, `POST /{id}/issue`, `GET /{id}`. **5 testů** (`CreditNoteServiceTest`), suita zelená (747).
- [x] **E5.2** PDF opravného dokladu · **M**
  Thymeleaf šablona opravného dokladu (označení „opravný daňový doklad", evidenční čísla obou dokladů, důvod, rozdíly po sazbách, strany) + endpoint `GET /credit-notes/{id}/pdf` (vzor `InvoiceDocumentController`). MVP = plný dobropis; částečný dobropis (podmnožina/vlastní částky) = pozdější rozšíření.

> **Pozn.:** volbu „vlastní řada vs. řada faktur" a rozpad rozdílů po sazbách při ostrém nasazení potvrdit s účetním (R-7) — zvolený model je legislativně obhajitelný, ne závazný výklad.

---

## E6 — Robustnost (před produkcí)

- [~] **E6.1** `GlobalExceptionHandler` — chybějící web výjimky *(backend S-5, P3.1)* · **M**
  **Hotovo:** `HttpMessageNotReadableException` → 400 `MALFORMED_REQUEST`, `HandlerMethodValidationException` → 400, `HttpRequestMethodNotSupportedException` → 405. Test `malformedJsonBody_returnsBadRequest`.
  **Odloženo:** `NoResourceFoundException` (404 pro neznámou cestu) — kvůli možné interakci se SPA fallbackem (deep-linky) nejdřív ověřit routing config; jinak by 404 JSON mohlo rozbít obnovení stránky ve frontend routeru.
- [x] **E6.2** Validace stránkování *(S-6, P3.2)* · **S**
  `BaseParams` explicitní settery s clampem `page ≥ 1`, `pageSize` 1..100 (MAX_PAGE_SIZE). `page=0` → ořízne na 1 → 200, ne 500 (graceful místo 400). Test `pageZero_isClampedNotServerError`.
- [x] **E6.3** Chyby AI extrakce *(sklad S-2, P3.3)* · **S**
  `PdfDocumentExtractionService` obalit → 422/503 `EXTRACTION_FAILED`. Test s mockem selhání.
- [ ] **E6.4** Draft příjemky — validace vstupu + reset stavů *(sklad S-6, S-7, P3.4)* · **M**
  `PUT /receipts/{id}/draft`: non-null header/lines (400 místo NPE→500), příchozí `VERIFIED` downgrade, `documentType`/`sourceChannel` nepřebírat; sladit `verifySupplier`. Testy.
- [~] **E6.5** Completeness gate — ceny + jednotky *(sklad S-3, S-4, P3.5)* · **S**
  `validateCompleteness`: `unitPriceExclVat ≥ 0`, `totalInclVat ≥ 0`, jednotka řádku vs. `products.unit`. Testy.
- [x] **E6.6** Párování ignoruje `is_active` + `IN ()` guard *(sklad S-5/№11, P3.6)* · **S**
  Filtr `is_active` v párovací kaskádě/SKU fallbacku; guard prázdného seznamu čísel dílu. Testy.
- [ ] **E6.7** Frontend — chybové stavy a double-submit *(F4, F5, F6, P3.9)* · **M**
  F4: create/edit stránky `catch (err) { addAlert(err.problem?.detail ?? "…") }`. F5: `saving` guard přes `FormActions` ve všech CRUD formulářích. F6: try/catch → `ErrorState`/toast na detailech a seznamech. Akceptace: manuální + kde lze test.
- [~] **E6.8** Doplnit testy *(testy S3, S4, S6, S7, S5, S1, S2, N2, N4)* · **M**
  SPAYD/QR obsah (přesný řetězec), GlobalExceptionHandler větve, upload 400/415, jeden souběhový K6 test, konvertory + `exception.*` do PIT scope; smazat planý `nullIdentifierFromService...`, centralizovat seed konstanty.

---

## E7 — Produkční příprava

Jeden propojený balík „než to pustíme ven". *(R-4 část 2, TD-33)*

- [x] **E7.1** Plná rolová autorizace TD-24 *(R-4, doména B5)* · **M–L**
  Matice role × operace odsouhlasena uživatelem 2026-07-24 (viz rozhodnuti R-9). Vedení-only operace
  (vystavení/úhrada/storno faktury, dobropis, (de)aktivace zákazníka/vozidla, profil firmy, uzavření
  inventury) → `@PreAuthorize("hasAnyRole('ADMIN','MANAGER')")` inline na controllerech; správa
  uživatelů zůstává ADMIN-only. Baseline `/api/**` = všechny pracovní role. Test `RoleAuthorizationTest`
  (10 testů: MECHANIC→403, baseline→200, vedení→404 na neexistující = branou prošlo).
- [x] **E7.2** Security hlavičky *(security N5, P3.8)* · **M**
  `frameOptions(DENY)`, `nosniff`, `referrerPolicy(SAME_ORIGIN)`, HSTS (1 rok, includeSubDomains), CSP
  (`default-src 'self'`, `'unsafe-inline'` u stylů/skriptů kvůli Bootstrap/MUI/Vite). V `SecurityConfig`.
- [x] **E7.3** CORS z konfigurace *(security N4)* · **S**
  Originy do `cors.allowed-origins` (dev default v `application.yaml`, prod přes env `CORS_ALLOWED_ORIGINS`
  v `application-prod.yaml`); `SecurityConfig` čte přes `@Value`.
- [~] **E7.4** Produkční checklist *(TD-33)* · **S** — zbývá jako TD-63 (netestovatelné z aplikace):
  seed hesla `Password1!` změnit; company_profile placeholder doplnit; ověřit `SPRING_PROFILES_ACTIVE=prod`.

---

## E8 — Dokumentace a úklid (průběžně)

- [ ] **E8.1** Souhrnná čísla v docs *(dokumentace S1–S13, N1–N16, P5.1)* · **M**
  Opravit počty (controllery 19, endpointy 96, mappery 21, konvertory 17, handlery 14, testy 70, migrace); doplnit chybějící routy/soubory/služby; README na V1–V44 + nové moduly. Zvážit skript kontrolující počty.
- [x] **E8.2** Aktualizovat `tech-dluhy.md` *(P5.2)* · **S**
  Uzavřít TD-46; přidat nové TD: stavový automat zakázky (A6), plná evidence úhrad 1:N, mrtvé schéma `customer_communications`/`loyalty_points` (B6), rozpor `vin` immutable docs×kód (N-6), konfigurovatelnost číselných řad (R-5).
- [x] **E8.3** Mrtvý kód *(backend N-5, sklad N-7, SQL №14, P5.3)* · **S**
  Smazáno po ověření grepem (nula volajících i testů): `VehicleMapper` `hardDelete`/`findByVin`/`findAllActive`/`countByCustomerId`, `InvoiceItemMapper.deleteByInvoiceId`, `CustomerMapper.existsByCustomerNumber`, `AddressMapper` `findByCustomerId`/`update`/`delete`/`clearDefault` (vadný cast), `UserMapper.save` (rozbité, bez emailu), `OrderService.findAllActive` (+ mapper + XML; test převeden na `getPage`), nepoužité importy v `OrderController`/`OrderItemController`. Suita 768 zelená.
  **Ponecháno (zapsáno do TD-64):** `InvoiceConverter` overloady (1-arg/3-arg) a `GET /warehouse/products/import/{id}` — nemají produkčního volajícího, ale **mají testové pokrytí**; mazat je znamená rušit i testy → diskutabilní, nedělám unilaterálně na konci auditu.

---

## Otevřené vstupy od uživatele (mimo E-etapy)

Nejsou to úkoly kódu, ale informace/potvrzení, které ovlivní provedení:

1. **Účetní potvrzení** před E5 (dobropis) a E1.3 (souvislost řady, zpětné datování).
2. **Více fakturačních řad?** — pokud servis dnes odděluje hotovost/převod, konfigurovatelnost řad (dnes odložená, R-5) se posune do plánu.
3. **Matice role × operace** pro E7.1 (TD-24) — odsouhlasit před implementací.
