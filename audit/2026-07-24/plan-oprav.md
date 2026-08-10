# Plán oprav — audit 2026-07-24

> Návrh, **co a jak** změnit, seřazený dle priority. Provedení schvaluje uživatel (Pravidlo č. 1).
> Odkazy na nálezy: [00-prehled.md](00-prehled.md) (klíčové K-xx) a dílčí reporty 01–09.
> Odhady náročnosti: **S** = do půl dne, **M** = 1–2 dny, **L** = 3+ dní.
>
> **Zásady:** hotové migrace se nemění — DB opravy jdou do nové migrace **V45+**. Každá oprava
> dostane regresní test (řada nálezů vznikla přesně tam, kde test chyběl). Před větší změnou
> ukázat CO a PROČ.

---

## Vlna 0 — Rychlé a jednoznačné opravy (řádově hodiny, nízké riziko)

Malé změny s velkým dopadem; žádná nevyžaduje architektonické rozhodnutí.

### P0.1 — Frontend: `addAlert` chybí v `OrderItemsWrapper` (K-18, F2) · **S**
`import { useAlert } from "../context/AlertContext.jsx"` + `const { addAlert } = useAlert();` v komponentě. Bez toho catch v `handleReorder` (ř. 205) sám vyhodí `ReferenceError`.

### P0.2 — Frontend: `OrderItemsSummary` vrací `undefined` (F3) · **S**
`OrderItemsSummary.jsx:7` `return` → `return null;`. Jinak při selhání načtení souhrnu spadne celý formulář zakázky do ErrorBoundary.

### P0.3 — Frontend: fokus v modalech (K-17, F1) · **S–M**
`Modal.jsx` — oddělit autofocus (efekt jen `[show]`) od keydown/focus-trap listeneru; `onClose`/`closable` číst přes `useRef`. Ověřit ručně na „Změna hesla" (napsat 8znakové heslo do druhého pole). Regresní obtížné (runtime) — aspoň manuální checklist.

### P0.4 — Security: invalidace sessions při změně/resetu hesla (K-6, security N1) · **S**
`AuthenticationService.changePassword` a `UserServiceImpl.resetPassword` → přidat `refreshTokenMapper.revokeAllByUserId(userId)` (mapper i dotaz existují). Test: po změně hesla starý refresh token → 401.

### P0.5 — Backend: last-admin guard v `UserServiceImpl.update` (K-2, backend S-9 / security N2) · **S**
Před `deleteRoles` ověřit: je-li uživatel admin a nové `roleIds` roli ADMIN neobsahují, pak `countEnabledByRoleExcluding(ROLE_ADMIN, id) > 0`, jinak 422 `CANNOT_REMOVE_LAST_ADMIN`. Zvážit i „ne sám sobě". Test v `UserServiceTest`.

### P0.6 — Backend: `gdpr_consent` doplnit do UPDATE zákazníka (K-4, backend V-1 / SQL №1) · **S**
`CustomerMapper.xml` `<update id="update">` doplnit `gdpr_consent`/`gdpr_consent_at`. Řeší se spolu s P1.1 (full-replace). Test: `PUT` s `gdprConsent:false` → v DB false.

### P0.7 — Backend: `marketing_consent_at` se nepřepisuje při každém uložení (K-4, SQL №2) · **S**
Timestamp posunout jen při skutečné změně — `CASE WHEN marketing_consent IS DISTINCT FROM #{marketingConsent} THEN NOW() ELSE marketing_consent_at END` (nebo příznak ze service). Řeší se spolu s P1.1.

### P0.8 — Backend: seznam příjemek posílá `page-1` (sklad S-1 / SQL) · **S**
`ReceiptReviewServiceImpl.java:56` odstranit `- 1` (`PagedResponse.of` je 1-based). Test: `first`/`last` v odpovědi.

### P0.9 — Dokumentace: api.md sekce Cookies (K-16, dokumentace V1) · **S**
Přepsat tabulku Cookies na hodnoty z konfigurace a odstranit blok „⚠️ Známé nesoulady" (TD-31 je vyřešené). Sladit backend.md ř. 75 a tech-dluhy.md.

---

## Vlna 1 — Fakturační workflow (nejvyšší věcné riziko)

Jádro provozu; nálezy se řetězí. Doporučeno řešit jako celek „Billing Phase 5".

### P1.0 — ROZHODNUTÍ: sémantika storna faktury (K-1) · rozhodnutí uživatele
Před implementací rozhodnout: (a) po stornu jde vystavit novou fakturu k zakázce, nebo (b) storno je terminální a položky se neodemykají. Audit doporučuje **(a)** — odpovídá záměru opravy V2 (odemykání položek po stornu). Zbytek P1.1 předpokládá (a).

### P1.1 — Stornovaná faktura blokuje fakturaci zakázky (K-1, backend V-2 / DB N-1 / doména A1) · **M**
- Migrace **V45**: `DROP CONSTRAINT uq_invoices_order_id` → `CREATE UNIQUE INDEX uq_invoices_order_active ON billing.invoices (order_id) WHERE status <> 'CANCELLED'`.
- `InvoiceServiceImpl.createFromOrder`: `findByOrderId` → filtr na ne-CANCELLED fakturu.
- Test: storno → nová faktura k téže zakázce projde; dvě aktivní faktury k zakázce ne.
- Aktualizovat `databaze.md`.

### P1.2 — Sjednotit UPDATE mappery na full-replace (K-11, backend S-1 / SQL №3–5) · **M**
`CustomerMapper`, `OrderMapper`, `InvoiceMapper`, `InvoiceItemMapper`, `AddressMapper` sladit se vzorem `SupplierMapper` (statický `SET`; NOT NULL sloupce přes `COALESCE`). Sjednotit s P0.6/P0.7. Testy „vymazání nullable pole" pro každý modul (vzor `SupplierServiceTest`).

### P1.3 — Číslo a datum faktury (K-3, DB N-5 / doména A4 / backend N-4) · **M**
- Migrace **V45**: `fn_generate_invoice_number` `COALESCE(NEW.issue_date, CURRENT_DATE)` místo `CURRENT_DATE`; ošetřit prázdný string (sjednotit s V9/V11).
- Koncepčně (rozhodnutí): přesunout číslování + `issue_date` razítko na přechod DRAFT→ISSUED (service, advisory lock). Draft by nespotřebovával čísla řady.
- `InvoiceDto.UpdateRequest` doplnit `issueDate`/`taxableSupplyDate` (editovatelné v DRAFTu).
- Test: zpětně datovaná faktura má číslo dle issue_date; DRAFT→ISSUED razítkuje datum.

### P1.4 — Snapshot vozidla na faktuře (K-5, DB N-2) · **M**
- Migrace **V45**: přidat `vehicle_vin_snapshot`, `vehicle_brand_snapshot`, `vehicle_model_snapshot` (backfill z aktuálních dat jako V33/V36).
- `createFromOrder` plnit; `invoice.html` přepnout na snapshoty; `InvoiceMapper` číst.
- Test: editace vozidla po vystavení faktury nezmění doklad.

### P1.5 — Guardy fakturace (backend S-2, S-3, S-4, S-12 / doména A6, A7 / DB N-6) · **M**
- `issue()`: neprázdné položky → `INVOICE_HAS_NO_ITEMS` (S-2).
- `createFromOrder`: `order.status == CANCELLED` → `ORDER_NOT_INVOICEABLE` (S-12/A6/N-6).
- `addItem`: `orderItem.orderId == invoice.orderId` → `ITEM_NOT_OF_INVOICED_ORDER` (S-3).
- editace DRAFT faktury/položek: guardovaný UPDATE `WHERE … AND status='DRAFT'` (S-4, vzor K5).
- Testy pro každý guard.

### P1.6 — PDF pro DRAFT/CANCELLED (backend S-11 / doména A3) · **S**
`invoice.html` vodoznak „NÁVRH" / „STORNOVÁNO" dle `invoice.status`; zvážit 422 pro CANCELLED v `renderPdf`. Test kontroléru (stav → obsah).

### P1.7 — `POST/PUT` položky faktury vrací net/vat/gross (backend S-7 / SQL №10) · **S**
`InvoiceItemMapper.findById` doplnit počítané `ROUND(...)` výrazy (sdílet `<sql>` fragment s `findByInvoiceId`). Test.

### P1.8 — DISKUSE: opravný daňový doklad (dobropis) (K-8, doména A2) · **L**, návrh
Legislativně nutné pro ostrý provoz. Návrh: nový typ dokladu / stav automatu „opravný daňový doklad" vázaný na PAID/ISSUED fakturu, s vlastní číselnou řadou a snapshotem. Rozsah velký — samostatná etapa, ne součást této vlny. Vést jako nový TD + řádek v roadmapě „Billing Phase 6".

---

## Vlna 2 — Integrita dat (DB vynucení + vazby)

### P2.1 — Uzavřít ledger na úrovni DB (K-13, DB N-3 + N-4) · **S** (jedna migrace)
Migrace **V45**: `BEFORE UPDATE OR DELETE` trigger na `stock_movements` s `RAISE EXCEPTION` (append-only); `UNIQUE (id, product_id)` na `goods_receipt_items` + složený FK `(batch_id, product_id)` na `stock_movements`. Aktualizovat `databaze.md`.

### P2.2 — Vazba zakázka↔vozidlo↔zákazník (K-12, backend V-3 / doména A5) · **M**
`OrderServiceImpl.create`: načíst vozidlo, ověřit existenci (404), aktivitu (422) a `vehicle.customerId == order.customerId` → `VEHICLE_NOT_OWNED_BY_CUSTOMER`. `VehicleServiceImpl.update`: při změně `customerId` ověřit otevřené zakázky. Testy.

### P2.3 — Autocomplete zákazníků filtruje `is_active` (backend S-8 / SQL №8) · **S**
`CustomerMapper.autocomplete` → `WHERE is_active = TRUE`; kontrola aktivity v `create` cestách. Test.

### P2.4 — Inventurní přebytkové šarže viditelné (K-14, sklad V-1 / DB N-18) · **S**
`WarehouseMapper.findBatchesByProductId`: `JOIN suppliers` → `LEFT JOIN` + `COALESCE(s.name, gr.supplier_name_snapshot)`. Totéž `v_batch_provenance` (migrace V45). Test s inventurním přebytkem.

### P2.5 — Embedded resultMap zákazníka u vozidla (backend S-13 / SQL №6) · **M**
`CustomerMapper` embedded fragment aliasovat prefixem (`c.is_active AS cust_is_active`, …) + `columnPrefix` v asociaci. Test s deaktivovaným zákazníkem + aktivním vozidlem (ověřit `customer.active`). Souvisí: №7 (`stkValidUntil` v `vehicleColumns` detailu zákazníka), №9 (`WarehouseMapper.findByGoodsReceiptId` → `resultMap`).

### P2.6 — Chybějící indexy (DB N-7 / SQL №16) · **S**
Migrace **V45**: `idx_orders_customer_id`, `idx_orders_vehicle_id`, `idx_vehicles_customer_id`, `idx_invoice_items_order_item_id`. Aktualizovat `databaze.md`.

### P2.7 — Dedup DL matchuje stornované příjemky (sklad, SQL №12) · **S**
`WarehouseImportMapper.findDeliveryNoteReceiptId`: `AND gr.status NOT IN ('REJECTED','CANCELLED')` (sladit s `existsActiveDocument`). Test scénáře storno DL → následná faktura.

---

## Vlna 3 — Robustnost a bezpečnost (bez nasazení nezralé)

### P3.1 — GlobalExceptionHandler: chybějící web výjimky (backend S-5) · **M**
Doplnit `HttpMessageNotReadableException` (vadný JSON, neplatný enum), `HandlerMethodValidationException`, `HttpRequestMethodNotSupportedException` (405), `NoResourceFoundException` (404) → ProblemDetail formát. Testy v `ProblemDetailContractTest`.

### P3.2 — Validace stránkovacích parametrů (backend S-6) · **S**
`BaseParams` clamp `page >= 1`, `pageSize` 1..100 (nebo Bean Validation). Test: `page=0` → 400, ne 500.

### P3.3 — Chyby AI extrakce → 422/503 (sklad S-2) · **S**
`PdfDocumentExtractionService` obalit, mapovat na `EXTRACTION_FAILED` s hláškou. Test s mockem selhání.

### P3.4 — Draft příjemky: validace vstupu + reset ne-editovatelných stavů (sklad S-6, S-7) · **M**
`PUT /receipts/{id}/draft`: non-null header/lines (400 místo NPE→500), příchozí `VERIFIED` downgrade, `documentType`/`sourceChannel` nepřebírat z payloadu. Sladit `verifySupplier` (nechat napárováno, čistit id při NONE). Testy.

### P3.5 — Completeness gate: záporné ceny + jednotka vs. karta (sklad S-3, S-4) · **S**
`validateCompleteness`: `unitPriceExclVat >= 0`, `totalInclVat >= 0`, porovnat jednotku řádku s `products.unit`. Chybové klíče do `invalidLines`. Testy.

### P3.6 — Párování ignoruje `is_active` + `IN ()` guard (sklad S-5 / SQL №11) · **S**
Filtr `is_active` v párovací kaskádě a SKU fallbacku; guard prázdného seznamu čísel dílu. Testy.

### P3.7 — Hash refresh tokenů (K-7, security N3) · **M**
Hashovat refresh token SHA-256 (jako blacklist) při `save` i `findByToken`. Migrace není nutná (staré tokeny dožijí). Sladit s P0.4. Test.

### P3.8 — Security hlavičky + CORS z konfigurace (security N4, N5) · **M**, produkční checklist
Znovu zapnout `frameOptions(sameOrigin)`, přidat CSP + `X-Content-Type-Options: nosniff`, v prod HSTS (Spring nebo nginx v `deploy/`); CORS originy do konfigurace. Aktualizovat `nasazeni.md`.

### P3.9 — Frontend: chybové stavy a double-submit (F4, F5, F6) · **M**
F4: create/edit stránky `catch (err) { addAlert(err.problem?.detail ?? "…") }`. F5: `saving` guard přes `FormActions` ve všech CRUD formulářích. F6: try/catch → `ErrorState`/toast na detailech a seznamech. (Vše nekonzistence proti již správným stránkám — mechanická sjednocení.)

---

## Vlna 4 — Testy (uzavřít mezery odhalené auditem)

### P4.1 — Reprodukovatelnost: `src/test/resources/application-test.yaml` (K-15 souvis., testy V1) · **S**
Testovací `jwt.secret` + dummy `spring.ai.anthropic.api-key`, aby suita běžela na čistém stroji/CI bez gitignorovaných secrets a bez reálného AI klíče v kontextu.

### P4.2 — JWT filter + auth cookie e2e (K-15, testy V2) · **M**
MockMvc „plný kruh": login → cookie → chráněný GET (200) → logout → 401 `TOKEN_BLACKLISTED`; expirovaný/cizí token → `TOKEN_EXPIRED`/`TOKEN_INVALID`. Fixuje i správné hodnoty cookies (proti regresi TD-31).

### P4.3 — SPAYD/QR obsah (testy S3) · **S**
Extrahovat sestavení SPAYD do testovatelné metody; unit test na přesný řetězec (částka `AM:`, VS, sanitizace MSG). Dnes by chybná částka v QR prošla celou suitou.

### P4.4 — GlobalExceptionHandler + upload validace + souběh (testy S4, S6, S7) · **M**
500/DATA_INTEGRITY/INVALID_REFRESH větve; upload 400/415; jeden ne-transakční souběhový test K6 (`ExecutorService`+`CyclicBarrier`). Přidat konvertory a `exception.*` do PIT scope (S5).

### P4.5 — Odstranit planý test + zpevnit křehké aserce (testy S1, S2, N2, N4) · **S**
Smazat/opravit `nullIdentifierFromService_returnsInvalidArgument`; centralizovat seed konstanty; přesné počty stránek odvodit z `totalElements`; terminální stavy inventury asertovat konkrétní kód.

---

## Vlna 5 — Dokumentace a úklid

### P5.1 — Souhrnná čísla v dokumentaci (dokumentace S1–S13, N1–N16) · **M**
Opravit počty (controllery 19, endpointy 96, mappery 21, konvertory 17, handlery 14, testy 70, migrace V1–V44) a přehledové tabulky; doplnit chybějící routy/soubory/služby (`units.js`, review komponenty, 4 mappery, 3 služby); README na V1–V44 + nové moduly. Doporučeno: skript kontrolující počty (obdoba grep křížové kontroly ze skillu `novy-endpoint`).

### P5.2 — Aktualizovat tech-dluhy.md (napříč audity) · **S**
TD-46 uzavřít (v mapperech dotaženo); přidat nové TD: stavový automat zakázky (doména A6), dobropis (K-8), evidence úhrad (K-9), mrtvé schéma `customer_communications`/`loyalty_points` (doména B6), rozpor `vin` immutable dokumentace × kód (backend N-6).

### P5.3 — Mrtvý kód (backend N-5 / sklad N-7 / SQL №14) · **S**
Smazat po ověření grepem: `VehicleMapper.hardDelete`+ostatní nevolané, `OrderService.findAllActive`, `AddressMapper` nevolané metody, `UserMapper.save` (rozbité), nepoužité overloady/importy, `GET /warehouse/products/import/{id}`. R-12.

---

## Věci k rozhodnutí uživatele (ne opravy, ale směr)

1. **Storno faktury** (P1.0) — varianta (a) vs. (b).
2. **Dobropis** (P1.8, K-8) — kdy zařadit; bez něj není billing legislativně kompletní.
3. **Evidence úhrad** (K-9, doména B1) — priorita vs. ostatní roadmapa; audit ji řadí hned za billing workflow.
4. **Autorizace + seed účty** (K-10, doména B5) — před jakýmkoli ostrým provozem nutné (TD-24 povýšit).
5. **Číselné řady** — per-year reset a konfigurovatelnost (doména B11) — ano/ne.
6. **Číslování faktury** (P1.3) — přesunout na DRAFT→ISSUED, nebo jen opravit prefix?

---

## Doporučené pořadí

**Nejdřív Vlna 0** (hodiny, zjevné bugy) → **Vlna 1** (fakturace, nejvyšší věcné riziko) → **Vlna 2** (integrita) → **Vlna 4.1+4.2** (aby další změny chránil test) → **Vlna 3** (robustnost, před nasazením) → **Vlna 5** (úklid průběžně). Vlny 0, 2, 5 lze dělat nezávisle; Vlna 1 má vnitřní návaznost (P1.0 rozhodnutí → P1.1).
