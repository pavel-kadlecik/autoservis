# Audit 4/9 — Databázová vrstva a doménový návrh (V1–V44)

> Součást hloubkového auditu 2026-07-24 (commit `409d3ad`, větev `audit-one`).
> Přehled celého auditu: [00-prehled.md](00-prehled.md).
>
> **Verifikace hlavního auditora:** N-1 (`uq_invoices_order_id` je plný UNIQUE, ne partial),
> N-2 (V36 snapshotuje jen SPZ; `InvoiceMapper` nese jen `vehicle_license_plate_snapshot`; PDF
> šablona čte `invoice.vehicleVin/Brand/Model` živě přes order→vehicle) a N-5 (`V15:18`
> `TO_CHAR(CURRENT_DATE,'YYYYMM')`) ověřeny přímo. Potvrzeno.

Rozsah: všech 44 migrací, křížově ověřeno proti `src/main/java/**` a `src/main/resources/mapper/**`. Známé dluhy TD-16, TD-40, TD-41, TD-13 a položky v `databaze.md` §12 (TIMESTAMP bez TZ, NULL sémantika CHECKů, chk_vehicles_year, ROLE_READONLY, placeholder company_profile / seed hesla = TD-33) nereportovány.

**Oprava se NIKDY nedělá do hotové migrace — vše řešitelné migrací V45+ a úpravou service vrstvy.**

---

## Nálezy

### VYSOKÁ

**N-1 · Zakázka po stornované faktuře je navždy nevyfakturovatelná** — `V14:40` (`uq_invoices_order_id UNIQUE (order_id)` plný, ne partial) + `InvoiceServiceImpl.createFromOrder` (`findByOrderId(...).isPresent()` bez filtru stavu). Storno faktury → zakázku už nikdy nevyfakturuji. V2-fix jinde s CANCELLED jako „neexistující" fakturou počítá — chování je vnitřně nekonzistentní. Oprava: V45 partial unique `WHERE status <> 'CANCELLED'` + filtr v service. Confidence: jistý. *(Klíčový nález K-1 — shodně backend V-2, doména A1.)*

**N-2 · „VIN/značka/model jsou neměnné" neplatí — právní doklad se zpětně mění** — `V36` (premisa) vs. `VehicleMapper.xml:253-271` (`update` přepisuje `vin`, `brand`, `model`, `customer_id`) a `invoice.html:227-231` (PDF čte `vehicleVin/Brand/Model` živě přes order→vehicle). V36 snapshotuje jen SPZ s odůvodněním, že zbytek je immutable; DB immutabilitu nedělá a `PUT /vehicles/{id}` je běžně mění. Vystavená faktura pak vytiskne jiné VIN/značku/model než v den vystavení. Oprava: V45 doplnit `vehicle_vin/brand/model_snapshot`, plnit v `createFromOrder`, PDF na snapshoty. Confidence: jistý. *(Klíčový nález K-5.)*

**N-3 · Ledger je append-only jen konvencí — UPDATE/DELETE pohybu tiše rozbije stav** — `V18:300-302` `trg_apply_stock_movement` je jen `AFTER INSERT`. `UPDATE`/`DELETE` na `stock_movements` projde bez zásahu do `quantity_on_hand`/`quantity_remaining`; denormalizace se rozjede a nic to nedetekuje. Projekt „stav se nikdy nepřepisuje" deklaruje jako architektonické rozhodnutí — DB ho nevynucuje. Oprava: V45 `BEFORE UPDATE OR DELETE` trigger s `RAISE EXCEPTION`. Confidence: jistý.

**N-4 · Pohyb může ukazovat na šarži cizího produktu — chybí složený FK** — `V18:244-245` `fk_mov_batch` jen na `goods_receipt_items(id)`. Pohyb s `batch_id` šarže jiného produktu projde; trigger odečte produkt X a zůstatek šarže Y. Aplikace to hlídá (`ProductServiceImpl:232`), DB ne. Oprava: V45 `UNIQUE (id, product_id)` na items + složený FK `(batch_id, product_id)`. Confidence: jistý (DB), dopad pravděpodobný.

**N-5 · Číslo faktury podle CURRENT_DATE, ne issue_date, a už ve stavu DRAFT** — `V15:18` (`TO_CHAR(CURRENT_DATE,'YYYYMM')`), trigger `BEFORE INSERT`. (a) faktura s `issueDate=30.6.` založená 24.7. dostane `202607xxx`; (b) DRAFT z konce měsíce vystavený příští měsíc nese číslo měsíce založení; (c) seed V16 to demonstruje na sobě. Účetně nekonzistentní řada. Oprava: V45 `COALESCE(NEW.issue_date, CURRENT_DATE)`; koncepčně přesun číslování až na DRAFT→ISSUED. Confidence: jistý. *(Klíčový nález K-3 — shodně doména A4, backend N-4.)*

### STŘEDNÍ

**N-6 · Fakturovat lze CANCELLED / čerstvě přijatou zakázku** — `createFromOrder` stav zakázky nekontroluje, DB rovněž ne. Oprava: business guard `ORDER_NOT_INVOICEABLE`. Confidence: jistý (chybějící guard). *(Shodně backend S-12, doména A6.)*

**N-7 · Chybějící indexy: `orders` nemá kromě UNIQUE čísla žádný index** — `V6` (žádný CREATE INDEX), `V5` (jen uq_vin), `V14` (invoice_items bez indexu na `order_item_id`). Nejfrekventovanější dotazy (detail zákazníka → vozidla/zakázky) + RESTRICT kontroly při mazání jedou plným skenem. Oprava: V45 `idx_orders_customer_id`, `idx_orders_vehicle_id`, `idx_vehicles_customer_id`, `idx_invoice_items_order_item_id`. Confidence: jistý.

**N-8 · Sync triggery V20/V38: změna `vehicle_id` řádku nechá starému vozidlu zastaralou cache** — `COALESCE(NEW.vehicle_id, OLD.vehicle_id)` přepočítá jen nové vozidlo. Aplikace dnes `vehicle_id` nemění → díra vůči přímému SQL / budoucí funkci. Oprava: při `NEW.vehicle_id <> OLD.vehicle_id` přepočítat obě. Confidence: jistý.

**N-9 · GIN fulltext `idx_customers_fts` je mrtvý** — `V2:186-193` vs. `CustomerMapper.xml:151-155` (`LOWER(unaccent(...)) LIKE '%…%'`). Žádný mapper `to_tsvector`/`@@` nepoužívá; oboustranný wildcard index nevyužije. Údržba GIN bez čtení. Oprava: buď index dropnout a nasadit `pg_trgm` (extension od V40 existuje) nad immutable-unaccent wrapperem, nebo přepsat na tsquery. Confidence: jistý.

**N-10 · `refresh_tokens` rostou donekonečna** — `V1:104-119` „never deleted"; úklid jen pro `token_blacklist`. Každé přihlášení + rotace = nový řádek navždy; chybí index na `expires_at`. Oprava: scheduled job + V45 index. Confidence: jistý.

**N-11 · `customer_number_seq START WITH 4` koliduje se seedem (0001–0010)** — `V4:15` komentář tvrdí „0001–0003", ale V3 seeduje **deset** zákazníků. Fresh instalace v roce 2025 by 4. zákazníkem vygenerovala `ZNK-2025-0004` → kolize. V 2026+ maskuje rok. Řady se navíc neresetují per rok a nejsou gapless. Oprava: V45 `setval` nad seed + rozhodnout per-year reset. Confidence: jistý (nesoulad), dopad podmíněný rokem.

**N-12 · Párovací identita produktu (manufacturer, part_number_normalized) není unikátní** — `V40:37` jen obyčejný index. Dvě karty se stejným výrobcem + číslem dílu DB dovolí → kaskáda párování najde dva kandidáty. Oprava: partial unique po deduplikaci. Confidence: jistý (chybějící constraint).

**N-13 · Měna: faktury žádnou nemají, příjemky ji mají bez omezení** — `V14` (invoices bez `currency`), `V18:150` (`currency CHAR(3) DEFAULT 'CZK'` bez CHECK). Vydaná faktura je implicitně CZK (nedeklarovaný invariant); `goods_receipts.currency` přijme „XXX"/„czk". Oprava: CHECK na receipts + invariant „CZK only" do dokumentace, nebo sloupec na invoices. Confidence: jistý, dopad nízký-střední.

### NÍZKÁ

- **N-14 — Duplicitní/nadbytečné indexy:** `V1:72-73` `idx_users_email`/`idx_users_username` duplikují unique; `V2:182` `idx_customers_ico` podmnožina unique; `idx_customers_active` (boolean) a `idx_mov_type` (6 hodnot) mizivá selektivita. V45 DROP.
- **N-15 — V15 neošetřuje prázdný string** (na rozdíl od V9/V11): `invoice_number = ''` obejde generátor. Sjednotit podmínku.
- **N-16 — `gdpr_consent_at NOT NULL DEFAULT NOW()` i při `gdpr_consent = FALSE`** (`V2:55-56`). CHECK párující flag a timestamp. *(Shodně doména A10.)*
- **N-17 — `VehicleMapper.hardDelete` mrtvý kód proti R-06** (`VehicleMapper.xml:295-297`). Smazat.
- **N-18 — `v_batch_provenance` ztrácí šarže z inventurních přebytků** (`V18:319-332` INNER JOIN suppliers; od V44 STOCK_TAKE příjemky mají supplier_id NULL). LEFT JOIN. *(Souvisí se sklad V-1.)*
- **N-19 — Pozice položek bez UNIQUE, rozdílné defaulty** (`order_items.position` DEFAULT 1, `invoice_items` DEFAULT 0; žádná `UNIQUE (parent_id, position)`).
- **N-20 — Netypované NUMERIC/VARCHAR** v `orders` (estimated/final_price, order_number) a billing hlavičkách — jediná místa bez precision/délky.
- **N-21 — `stock_movements.moved_at` je vždy = `created_at`** (backdating nepodporováno). Buď plnit (inventura k datu), nebo odstranit.
- **N-22 — `token_blacklist.invalidated_at` nullable** — NULL řádek by cleanup nikdy nesmazal (kryto DEFAULT NOW()).
- **N-23 — Storno příjemky check-then-act bez zámku šarží** (`ReceiptReviewServiceImpl.cancel` — `requireNotUsed` bez FOR UPDATE); souběžný výdej → 500/DATA_INTEGRITY místo čisté 422. `findByIdsForUpdate` i zde.
- **N-24 — Fakturační adresa bez kontroly typu** (`createFromOrder` ověřuje jen vlastnictví, ne `address_type` — CONTACT adresa jde zmrazit jako fakturační).
- **N-25 — `user_roles.role_id` bez indexu** (RESTRICT FK skenuje při mazání role). Formální.

---

## Posouzení doménového návrhu

**Jádro modelu (zákazník → vozidlo → zakázka → faktura) je navrženo správně a nadprůměrně poctivě.** Oddělení identity (security) od business profilu (customer), polymorfní zákazník s CHECK constrainty místo dědičnosti, VIN unikát + vědomě neunikátní SPZ, zakázka s redukovaným enumem položek. Disciplína, s jakou se business pravidla dostávají do DB (CHECK na znaménka pohybů, partial unique na „jedna výchozí adresa per typ", „jedna otevřená inventura", advisory lock na číslování faktur), je nadstandardní.

**Fakturace: snapshot architektura je koncepčně správná, ale hranice zmrazení je děravá a špatně načasovaná.** Zmrazení není úplné (SPZ ano, VIN/značka/model živě — N-2). Snapshoty se pořizují při **založení DRAFTu**, ne při ISSUED — mění-li se zákazník nebo company_profile mezi draftem a vystavením, faktura ponese data z doby draftu (DRAFT před doplněním firmy ponese „DOPLŇTE NÁZEV FIRMY"). Číslování má stejný časový posun (N-5). Storno je jen CANCELLED — bez dobropisu a kvůli plnému UNIQUE(order_id) zazdí zakázku (N-1). `invoice_party` je jinak vzorový. Z právních náležitostí chybí údaj o zápisu v obchodním/živnostenském rejstříku (§435 NOZ).

**Sklad je nejsilnější část návrhu.** Ledger + šarže + denormalizace triggerem, oceňování skutečnými cenami, draft workflow s JSONB, kompenzační storno, inventura generující korekce. Kritika: append-only vynucené jen aplikačně (N-3), vazba pohyb↔šarže↔produkt není v DB uzavřená (N-4) — obojí levná definitivní oprava. Záporná zásoba rozbít nejde (CHECK ≥ 0 + FOR UPDATE). Systémová slabina: `quantity_on_hand` míchá pohyby se šarží i bez ní, invariant „Σ quantity_remaining = quantity_on_hand" neplatí z definice a nikdo ho nekontroluje — stálo by za ladicí view.

**Soft-delete kaskáda je čistě aplikační a asymetrická.** Deaktivace kaskáduje (service), přímý `UPDATE` ne; reaktivace kaskádu nemá. Pro jediného zapisujícího klienta přijatelný a zdokumentovaný kompromis.

**DPH a měny:** sazby jako SMALLINT s CHECK 0–100 je správná volba (enum by byl past). Zaokrouhlování „po řádku, všude stejně" konzistentní napříč souhrny. Absence měny na faktuře (N-13) je nedeklarovaný invariant.

**Číselné řady:** tři různé mechanismy pro tři řady; každý sám o sobě korektní, ale sekvenční se neresetují per rok a nejsou gapless. Doporučeno sjednotit na vzor V15, má-li „rok" ve formátu něco znamenat.

**ENUMy:** Java ↔ DB 1:1 ve všech 18 typech; jediný nesoulad je pořadí `ISSUE_RETURN` (bez dopadu).

---

## Pozitiva

1. **V17/V43/V44 — ENUM-add-and-use past pochopená a řešená správně** (`flyway:noAutoCommit` + COMMIT).
2. **V24 — přestavba enumu za běhu** (nový typ → TEXT → remap → přepnutí → drop+rename) učebnicově.
3. **Advisory lock ve V15** řeší race na MAX+1 korektně.
4. **Snapshot vrstvy V33–V36** s poctivými komentáři o limitech backfillu.
5. **Setval po seedech** důsledný (V3, V8); kde ID nejsou explicitní, se nevolá.
6. **Idempotence** kde má smysl (`ON CONFLICT DO NOTHING`, `NOT EXISTS`, `IF NOT EXISTS`, `DISTINCT ON`).
7. **Partial unique indexy jako nosič business pravidel.**
8. **Trigger V20/V38 jako plný recompute** (sebeozdravný vůči UPDATE/DELETE řádků).
9. **CHECK constrainty s doménovým smyslem** (VIN bez I/O/Q, PSČ jen CZ, znaménka pohybů, `chk_receipt_confirmed_complete` s uvolněním pro STOCK_TAKE).
10. **Generovaný sloupec `part_number_normalized` (V40)** — normalizace v DB, s indexem, NULLIF.
11. **`databaze.md` je s realitou migrací v pozoruhodné shodě.**

**Souhrn: 5× VYSOKÁ (N-1..N-5), 8× STŘEDNÍ (N-6..N-13), 12× NÍZKÁ (N-14..N-25).** Nejvyšší priorita: N-1 (workflow dead-end) a N-2 (integrita právního dokladu), pak dvojice N-3+N-4 (jedna malá migrace, definitivně uzavře ledger).
