# 05 — Databáze: migrace, schéma, integrita

> Audit 2026-07-30 · rozsah: všech 64 migračních souborů ve třech Flyway locations (`db/migration`,
> `db/demo`, `db/prod`), výsledné schéma, constrainty, triggery, views, číselné řady, seed data
> · metoda: přečteny celé migrace + `docs/databaze.md` (770 řádků), a **ověřeno proti živé dev DB**
> (`localhost:5433/autoservis`, PostgreSQL 18.2) přes `information_schema` / `pg_constraint` /
> `pg_indexes` / `pg_trigger` / `pg_enum`. Read-only, jen `SELECT`.

**Poznámka k dev DB:** MCP nástroj `mcp__psql__query` je nakonfigurovaný na `192.168.1.224:5432`
a spojení vypršelo. Databáze ale běží na `localhost:5433` — připojil jsem se přímo klientem
`D:\Tools\PostgreSQL\pgsql\bin\psql.exe`. **Dev DB je namigrovaná jen do V55**
(`flyway_schema_history` má 55 řádků, poslední `init credit notes` z 2026-07-25). Objekty
z V56–V63 (`fn_generate_order_number` per rok, `billing.cash_receipts`, schéma `employee`,
`order_items.employee_id`, `stock_takes.stock_take_number`, `vehicles.wheels`, `*_cost` sloupce
ve view) jsem proto ověřoval **čtením migrací**, ne dotazem. U každého nálezu je uvedeno, čím
je doložen.

## Co bylo přečteno

**Migrace — všech 64 souborů, celé:**
- `db/migration`: V1, V2, V4, V5, V6, V7, V9, V10, V11, V12, V14, V15, V17, V18, V19, V20, V21,
  V22, V23, V24, V25, V26, V27, V28, V29, V30, V31, V32, V33, V34, V35, V36, V37, V38, V39, V40,
  V41, V42, V43, V44, V45, V48, V49, V50, V51, V52, V53, V54, V55, V56, V57, V59, V61, V62, V63 (55)
- `db/demo`: V3, V8, V13, V16, V46, V47, V58 (7)
- `db/prod`: V58, V60 (2)

**Dokumentace a konfigurace:**
- `CLAUDE.md`, `docs/konvence.md`, `docs/tech-dluhy.md`, `docs/databaze.md` (celý, 770 ř.)
- `src/main/resources/application.yaml`, `application-prod.yaml`, `src/test/resources/application-test.yaml`

**Kód dotčený nálezy (celé soubory nebo relevantní bloky):**
- `src/main/java/cz/palo/autoservis/service/impl/CreditNoteServiceImpl.java` (celý)
- `src/main/java/cz/palo/autoservis/model/converter/CreditNoteConverter.java` (celý)
- `src/main/java/cz/palo/autoservis/model/dto/billing/CreditNoteDto.java` (celý)
- `src/main/java/cz/palo/autoservis/controller/CreditNoteController.java` (hlavička + create/issue)
- `src/main/resources/mapper/CreditNoteMapper.xml` (celý)
- `src/main/resources/mapper/InvoiceMapper.xml`, `OrderItemMapper.xml`, `CustomerMapper.xml` (relevantní bloky)
- `src/main/java/cz/palo/autoservis/service/impl/CashReceiptServiceImpl.java`
- `src/test/java/cz/palo/autoservis/prod/ProdSeedIntegrationTest.java` (celý)
- `src/test/java/cz/palo/autoservis/service/CreditNoteServiceTest.java` (seznam testů)

## Shrnutí

Databázová vrstva je v dobrém stavu: číslování migrací je **souvislé V1–V63 bez děr**, jediné
zdvojené číslo (V58) je záměrné dvojče `db/demo` / `db/prod` a **skutečně nekoliduje** — locations
se nikdy nepřekrývají a produkční cesta má vlastní integrační test. ENUM typy v DB sedí s dokumentací
na hodnotu přesně (18 typů). Cenové views zaokrouhlují jednotně po řádku a jejich součty na sebe
navazují. Číselné řady faktury / dobropisu / PPD / zakázky / inventury mají shodný a **správně
navržený** vzor (advisory lock + MAX+1 + guard proti přetečení) a jsou bezpečné i při souběhu.
Pravidlo R-05 (`setval()` po seedu s explicitními ID) je dodrženo u všech čtyř seedů. V `db/migration`
nejsou žádná demo data (konvence §14 splněna).

Nálezy: **0 kritických, 0 vysokých, 3 střední, 5 nízkých.** Nejzávažnější je chybějící ochrana proti
vystavení druhého plného dobropisu k téže faktuře (chybí unikát na `credit_notes.original_invoice_id`
i guard v service). Zbytek jsou rozdíly mezi `docs/databaze.md` a realitou — v jednom případě chybí
v „autoritativní referenci" celá tabulka (`billing.credit_notes`), v jiném dokumentace tvrdí
o číslování faktury opak toho, co V49 zavedla.

## Nálezy

### [D-1] Dobropis: k jedné faktuře lze vystavit libovolný počet **plných** opravných dokladů
**Severita:** 🟠 STŘEDNÍ
**Jistota:** OVĚŘENO
**Kde:**
- `src/main/resources/db/migration/V55__init_credit_notes.sql:24-39` — tabulka `billing.credit_notes`;
  jediné unikáty jsou `uq_credit_note_number (credit_note_number)` (ř. 36) a PK; na
  `original_invoice_id` je jen **neunikátní** index (ř. 41: `CREATE INDEX idx_credit_notes_original`).
  Ověřeno i v živé DB (`pg_constraint`: `credit_notes` → `uq_credit_note_number`,
  `fk_credit_note_invoice`, `credit_notes_created_by_fkey` — žádný unikát na `original_invoice_id`).
- `src/main/java/cz/palo/autoservis/service/impl/CreditNoteServiceImpl.java:36-65` — `createFromInvoice`
  ověří pouze existenci faktury a stav `ISSUED`/`PAID`; **nekontroluje, zda dobropis k faktuře už existuje**.
- `src/main/resources/mapper/CreditNoteMapper.xml:27-36` — prostý `INSERT`, žádné `WHERE NOT EXISTS`.
- `src/main/java/cz/palo/autoservis/model/converter/CreditNoteConverter.java:68-73` — částky dobropisu
  jsou **vždy celá záporná faktura**: `setTotalGrossDifference(negate(originalSummary.getTotalGross()))`.
  Model nemá pole pro částku dobropisu — každý dobropis je z definice plný (TD-62: částečný dobropis odložen).
- `src/main/java/cz/palo/autoservis/service/impl/CreditNoteServiceImpl.java:67-90` — `issue()` mění
  stav pouze dobropisu; faktura zůstává `ISSUED`/`PAID`, takže guard na stav faktury druhý dobropis nezastaví.

**Co je špatně:** Model dovoluje 1:N mezi fakturou a dobropisem, ale sémantika dobropisu je 1:1
(plná oprava celé faktury). Ani DB, ani service, ani DTO validace duplicitu nebrání.

**Scénář selhání:**
1. Faktura `202607001` na 12 100 Kč (základ 10 000, DPH 2 100) je `ISSUED`.
2. `POST /api/v1/credit-notes {originalInvoiceId: 1, correctionReason: "Reklamace"}` → dobropis A (DRAFT).
3. `POST /api/v1/credit-notes/{A}/issue` → `OD202607001`, rozdíl −12 100 Kč.
4. Obsluha (jiný den, jiný uživatel, nebo po opakovaném požadavku) provede kroky 2–3 znovu →
   `OD202607002`, rozdíl opět −12 100 Kč.
5. V evidenci jsou **dva platné opravné daňové doklady** na tutéž fakturu, dohromady −24 200 Kč
   proti pohledávce 12 100 Kč. Žádná chyba se nezobrazí, `GET /credit-notes?invoiceId=…`
   (`CreditNoteMapper.findByOriginalInvoiceId`, vrací `List`) oba spokojeně zobrazí.

**Proč to vadí:** Peníze a právo. Dva vystavené opravné daňové doklady k jednomu plnění znamenají
dvojnásobný odpočet DPH v kontrolním hlášení a záporný zůstatek pohledávky. Oprava = storno jednoho
dokladu, jenže tabulka `credit_notes` sice má stav `CANCELLED` (dědí `billing.invoice_status`),
ale žádný endpoint na storno dobropisu neexistuje — doklad by šlo umazat jen ručně v DB.

**Proč ne vyšší severita:** funkce zatím **nemá frontend** (grep `dobropis|creditNote` ve
`frontend/autoservis-frontend/src` nachází jen skladové `credit_note_number`, nic o `/credit-notes`),
takže dnes ji lze spustit jen přímým voláním API. Jakmile UI přibude, riziko roste.

**Návrh řešení:**
1. Migrace `V64`: částečný unikátní index
   `CREATE UNIQUE INDEX uq_credit_notes_invoice_active ON billing.credit_notes (original_invoice_id) WHERE status <> 'CANCELLED';`
   (stejný vzor jako `uq_invoices_order_active` z V48 — po stornu jde vystavit nový).
2. Guard v `CreditNoteServiceImpl.createFromInvoice`: existuje-li nestornovaný dobropis →
   `BusinessRuleException("INVOICE_ALREADY_CREDITED", …)` → 422, aby uživatel nedostal 422
   `DATA_INTEGRITY_VIOLATION` z DB.
3. Test v `CreditNoteServiceTest` (dnes tam duplicita testovaná není — testy pokrývají jen
   DRAFT/PAID/CANCELLED/unknown invoice a PDF).

---

### [D-2] `billing.credit_notes` chybí celá v `docs/databaze.md` — „autoritativní reference" tabulku nezná
**Severita:** 🟠 STŘEDNÍ
**Jistota:** OVĚŘENO
**Kde:**
- `docs/databaze.md:292` — hlavička `## 5. Schéma \`billing\` (V14–V17, V31–V37, V48–V51, V55, V57)`
  **V55 uvádí**, ale sekce obsahuje jen podsekce `billing.invoices` (ř. 294), `billing.invoice_items`
  (ř. 327), `billing.invoice_party` (ř. 342), `billing.company_profile` (ř. 364),
  `billing.cash_receipts` (ř. 382). Podsekce `billing.credit_notes` **neexistuje**.
- V celém dokumentu se `credit_note` vyskytuje jen na ř. 564 (jiný sloupec —
  `stock_movements.credit_note_number`), ř. 627, ř. 639 (§8 číselné řady) a ř. 747 (index migrací V55).
- Realita: `src/main/resources/db/migration/V55__init_credit_notes.sql:24-46` + živá DB
  (`information_schema.tables` → `billing.credit_notes (BASE TABLE)`, 10 sloupců, trigger
  `trg_credit_notes_updated_at` + `trg_credit_notes_generate_number`).
- Dokument se přitom na ř. 3 prohlašuje za „Autoritativní reference DB schématu, rekonstruovaná
  z Flyway migrací **V1–V63**".

**Co je špatně:** Chybí popis celé tabulky právního dokladu. Nedozvíš se z něj mimo jiné, že
`credit_notes.status` **recykluje ENUM `billing.invoice_status`** (tedy že dobropis může technicky
nabýt nesmyslného stavu `PAID`), že `credit_note_number` je nullable do vystavení, že na
`original_invoice_id` **není unikát** (viz D-1) a že tabulka na rozdíl od `cash_receipts`
`updated_at` a trigger **má**.

**Scénář selhání:** Vývojář dostane úkol „přidej k dobropisu částku pro částečný dobropis (TD-62)".
Otevře `docs/databaze.md` §5, tabulku tam nenajde, usoudí, že dobropis se ukládá do `invoices`
(sekce 5 jinak vypadá kompletně) a začne navrhovat discriminator sloupec — přesně to, co V55
v hlavičce (ř. 6-7) vědomě zamítla. Zjistí to až při psaní migrace, nebo hůř — až v review.

**Proč to vadí:** Provoz/údržba. Dokumentace, která je v jednom místě neúplná, přestává být
použitelná jako zdroj pravdy — a `CLAUDE.md` na ni odkazuje jako na povinnou četbu před každou
změnou DB.

**Návrh řešení:** Doplnit do §5 podsekci `### billing.credit_notes (V55) — opravný daňový doklad`
se stejnou strukturou jako `cash_receipts` (sloupce, typy, NOT NULL, FK, unikáty, index, trigger)
a explicitně zmínit sdílený ENUM `invoice_status`. Aktualizovat i §7 (ENUM `invoice_status`
používají dvě tabulky).

---

### [D-3] Dokumentace i komentář v mapperu tvrdí, že číslo faktury přiděluje `BEFORE INSERT` trigger — od V49 je to `BEFORE UPDATE`
**Severita:** 🟠 STŘEDNÍ
**Jistota:** OVĚŘENO
**Kde:**
- `docs/databaze.md:323` — „Triggery: `trg_invoices_updated_at`; `trg_invoices_generate_number`
  **(BEFORE INSERT)**."
- `src/main/resources/mapper/InvoiceMapper.xml:143` — `<!-- invoice_number and variable_symbol are
  generated by a DB trigger **on INSERT**. -->`
- Realita: `src/main/resources/db/migration/V49__invoice_number_on_issue.sql:58-66` —
  `DROP TRIGGER trg_invoices_generate_number ON billing.invoices;` a poté
  `CREATE TRIGGER … BEFORE UPDATE … WHEN (NEW.status = 'ISSUED' AND OLD.status <> 'ISSUED' AND NEW.invoice_number IS NULL)`.
- Ověřeno v živé DB: `pg_trigger` → `invoices | trg_invoices_generate_number | timing=BEFORE | events=UPDATE`.
  Na `billing.invoices` **žádný INSERT trigger pro číslování neexistuje**.
- Tentýž dokument si na ř. 298 a 741 protiřečí (tam je V49 popsána správně).

**Co je špatně:** Dvě nezávislá místa (schéma reference + komentář u INSERT SQL) tvrdí opak toho,
co V49 zavedla. Chyba je „inverzní", ne jen neúplná — čtenář z ní odvodí bezpečnostní záruku, která neplatí.

**Scénář selhání:** Přijde požadavek „naimportuj historické faktury z předchozího systému".
Vývojář si přečte `docs/databaze.md:323` i komentář v `InvoiceMapper.xml:143`, napíše migraci nebo
service metodu, která vloží řádky rovnou se `status = 'ISSUED'` a spolehne se, že trigger doplní
číslo. Trigger se nespustí (je jen na UPDATE), `invoice_number` i `variable_symbol` zůstanou `NULL`
— což od V49 projde, protože oba sloupce jsou nullable
(`V49__invoice_number_on_issue.sql:20-21`). `uq_invoice_number` je běžný UNIQUE index bez
`NULLS NOT DISTINCT` (ověřeno: `CREATE UNIQUE INDEX uq_invoice_number ON billing.invoices USING btree (invoice_number)`),
takže **libovolný počet vystavených faktur bez čísla a bez VS** projde do DB bez jediné chyby.
Odhalí se to až u zákazníka, který dostane fakturu bez evidenčního čísla a bez variabilního symbolu.

**Proč to vadí:** Právo a peníze — evidenční číslo je náležitost daňového dokladu (§29 ZDPH),
VS je jediné, podle čeho se páruje platba. Dnešní kód tuto cestu nemá
(`InvoiceMapper.xml:144-159` `status` nevkládá → default `DRAFT`), takže jde o past pro příští změnu,
ne o aktivní chybu.

**Návrh řešení:**
1. Opravit `docs/databaze.md:323` na `(BEFORE UPDATE, WHEN DRAFT→ISSUED a invoice_number IS NULL — V49)`.
2. Opravit komentář `InvoiceMapper.xml:143`.
3. Volitelně (jistější než dokumentace): migrace `V64` s CHECK
   `CHECK (status = 'DRAFT' OR invoice_number IS NOT NULL)` — vystavený doklad bez čísla se pak
   do DB nedostane žádnou cestou. *(Rozhodnutí uživatele — CHECK je navíc, dokumentační oprava je nutná.)*

---

### [D-4] Řada `ZNK` je jediná číselná řada bez ročního resetu, bez advisory locku a bez guardu proti přetečení
**Severita:** 🟡 NÍZKÝ
**Jistota:** OVĚŘENO
**Kde:**
- `src/main/resources/db/migration/V9__customer_number_trigger.sql:10-14` —
  `'ZNK-' || EXTRACT(YEAR FROM NOW())::TEXT || '-' || LPAD(nextval('customer.customer_number_seq')::TEXT, 4, '0')`
  — rok se bere z `NOW()`, ale pořadové číslo z **globální, nikdy neresetované** sekvence.
- `src/main/resources/db/migration/V4__add_customer_number_sequence.sql:14-18` (`START WITH 4`),
  `V10__change_customer_number_seq_cache.sql:6` (`CACHE 1`).
- `src/main/resources/db/demo/V47__fix_customer_number_sequence_start.sql:11-13` — komentář sám
  přiznává: „the LPAD overflow guard (>9999 customers/orders …) is deferred … **customer-number
  trigger V9 keeps LPAD(...,4) for now**".
- Srovnání: `V56` (ZAK), `V49` (faktura), `V55` (OD), `V57` (PPD), `V61` (INV) — všech pět má
  `pg_advisory_xact_lock` + MAX+1 scopnutý na období + `RAISE EXCEPTION` při přetečení.
- Živá DB: `SELECT last_value, is_called FROM customer.customer_number_seq` → `10 | t`;
  nejvyšší existující číslo `ZNK-2025-0010`. Dnes je 2026 → **příští založený zákazník dostane
  `ZNK-2026-0011`**, ne `ZNK-2026-0001`.
- `docs/databaze.md:636` popisuje mechanismus správně (sekvence + trigger V9), tj. nejde o rozpor
  s dokumentací — jde o nekonzistenci návrhu.

**Co je špatně:** Formát `ZNK-{rok}-{4č.}` implikuje řadu per rok, ale čítač je globální.
Přesně tenhle rozpor byl u zakázek uznán jako chyba a opraven v TD-57 / V56; u zákazníků zůstal.
Navíc chybí guard při >9999 (`LPAD` tiše vyrobí pětimístné číslo) a `nextval` je netransakční,
takže rollback vytvoří v řadě díru (`CACHE 1` z V10 díry jen zmenšuje, neodstraňuje).

**Scénář selhání:**
1. 2. ledna 2027 obsluha založí nového zákazníka.
2. Trigger složí `ZNK-2027-0011` (sekvence je za deseti seed zákazníky), místo `ZNK-2027-0001`.
3. Po 9999 zákaznících celkem (ne za rok) vznikne `ZNK-2035-10000` — o dva znaky delší než
   `4č.` formát. Do `VARCHAR(20)` se vejde, unikát nepadne, takže se to nikde neprojeví
   jako chyba, jen se rozbije formát a řazení podle řetězce.

**Proč to vadí:** Provoz a konzistence. Číslo zákazníka není doklad, takže jde o kosmetiku —
ale nekonzistentní s pěti ostatními řadami a s vlastním formátem. `db/prod/V60__prod_seed.sql:54`
navíc explicitně resetuje sekvenci na 1 s odůvodněním „první zákazník = ZNK-{rok}-0001", což
záměr „řada začíná od 0001" potvrzuje.

**Návrh řešení:** *Rozhodnutí uživatele* — buď (a) přepsat `fn_generate_customer_number` vzorem V56
(per-rok MAX+1 + advisory lock + guard >9999) a `customer_number_seq` dropnout, nebo (b) ponechat
globální řadu a opravit dokumentaci i formát na `ZNK-{4č.}` bez roku. Varianta (a) je konzistentní
se zbytkem systému. V obou případech doplnit guard proti přetečení.

---

### [D-5] `docs/databaze.md` — chybějící trigger, chybějící unikát a zastaralé rozsahy migrací v hlavičkách sekcí
**Severita:** 🟡 NÍZKÝ
**Jistota:** OVĚŘENO
**Kde (pět konkrétních rozdílů, každý ověřen proti DB):**

| # | `docs/databaze.md` | Realita | Důkaz |
|---|---|---|---|
| a | ř. 572: `warehouse.stock_movements` — „Trigger: `trg_apply_stock_movement` (AFTER INSERT)" (jediný uvedený) | Tabulka má **dva** triggery — chybí `trg_movements_append_only` (BEFORE UPDATE OR DELETE, zakáže editaci ledgeru) | `V52__stock_ledger_integrity.sql:29-32`; `pg_trigger`: `stock_movements \| trg_movements_append_only \| BEFORE \| DELETE,UPDATE` |
| b | ř. 522: `goods_receipt_items` — „Indexy: `idx_items_receipt`, `idx_items_product`." Žádný unikát neuveden | Tabulka má navíc `uq_items_id_product UNIQUE (id, product_id)` — nosič složeného FK z V52 | `V52__stock_ledger_integrity.sql:35-36`; `pg_constraint`: `warehouse.goods_receipt_items \| uq_items_id_product \| u` |
| c | ř. 503: „CHECK `chk_receipt_confirmed_complete` (V39): CONFIRMED ⇒ supplier_id, invoice_number, subtotal, vat_amount, total_amount NOT NULL." | V44 CHECK **uvolnila** o `OR document_type = 'STOCK_TAKE'` (inventurní přebytek nemá dodavatele ani částky) | `V44__init_stock_takes.sql:27-41`; `pg_constraint` def obsahuje `OR (document_type = 'STOCK_TAKE')`. Zmíněno je to jen jinde (§7, ř. 623), v popisu tabulky ne |
| d | ř. 175: `## 3. Schéma vehicle (V5, V7, V19, V20, V38)` | Schéma mění i **V62** (`vehicles.wheels` + rozšířený sync trigger) — v těle sekce (ř. 196, 238) V62 uvedena je, v hlavičce chybí | `V62__add_vehicle_wheels.sql:16` |
| e | ř. 398: `## 6. Schéma warehouse (V18, V21, V28–V30)` | Schéma mění dalších **devět** migrací: V39, V40, V41, V42, V43, V44, V52, V54, V61 | soubory `db/migration/V39…V61` |

**Co je špatně:** Popisy tabulek neuvádějí úplnou sadu triggerů a unikátů; hlavičky sekcí uvádějí
zastaralé rozsahy migrací.

**Scénář selhání (nejkonkrétnější je bod a):** Vývojář má „opravit chybně zadaný skladový pohyb".
Podle `docs/databaze.md:572` vidí u `stock_movements` jediný trigger (přičítá množství), z čehož
usoudí, že `UPDATE` pohybu je průchodný a jen si musí ručně dorovnat `quantity_on_hand`. Napíše
`WarehouseMapper.updateMovement`, spustí a dostane
`RAISE EXCEPTION 'stock_movements je append-only …'` (`V52:24`) → 500 z catch-all handleru.
Práce zahozená; správné řešení (kompenzační pohyb) je popsané jen v komentáři migrace a v řádku
indexu §11 (ř. 744).

**Proč to vadí:** Provoz. Jednotlivě jde o drobnosti, dohromady snižují důvěryhodnost dokumentu,
který má být zdrojem pravdy pro každou změnu DB.

**Návrh řešení:** Doplnit chybějící trigger a unikát do popisů tabulek, aktualizovat CHECK na
znění po V44 a přepsat rozsahy v hlavičkách §3 a §6 (nebo je nahradit odkazem „viz §11 Index migrací",
aby se nemusely udržovat dvakrát).

---

### [D-6] FK bez podpůrného indexu, které V53 nepokryl
**Severita:** 🟡 NÍZKÝ
**Jistota:** OVĚŘENO
**Kde:** dotaz nad `pg_constraint` × `pg_index` v živé DB (FK, jehož sloupce netvoří prefix žádného indexu):

| Tabulka | FK | `ON DELETE` | Poznámka |
|---|---|---|---|
| `warehouse.stock_take_items` | `fk_stock_take_items_product (product_id)` | **RESTRICT** | jediný index se `stock_take_id, product_id` — `product_id` je až druhý sloupec, prefix nesedí |
| `warehouse.stock_takes` | `fk_stock_takes_surplus_receipt (surplus_receipt_id)` | **RESTRICT** | žádný index |
| `security.user_roles` | `user_roles_role_id_fkey (role_id)` | **RESTRICT** | PK je `(user_id, role_id)` — `role_id` je druhý |
| 17× auditní `*_by` | `created_by` / `handled_by` / `assigned_by` / `confirmed_by` / `rejected_by` / `cancelled_by` / `opened_by` / `closed_by` | SET NULL | `invoices`, `credit_notes`, `customers`, `customer_communications`, `orders`, `order_items`, `vehicles`, `mileage_history`, `registry_snapshots`, `goods_receipts` (4×), `stock_movements`, `stock_takes` (2×), `user_roles` |

`V53__add_missing_fk_indexes.sql:10-13` doplnil `orders(customer_id)`, `orders(vehicle_id)`,
`vehicles(customer_id)`, `invoice_items(order_item_id)` — přesně ty čtyři, které deklaruje.
V rámci svého deklarovaného rozsahu je tedy **úplný**; výše uvedené FK do něj nespadaly.

**Co je špatně:** Chybějící indexy na referencující straně `RESTRICT`/`SET NULL` FK. PostgreSQL
při `DELETE` rodiče kontroluje potomky bez indexu sekvenčním skenem.

**Scénář selhání:** Dnes **žádný**, a proto nízká severita — projekt hard delete nepoužívá (R-06):
produkty i dodavatelé se deaktivují, uživatelé se ruší přes `enabled = FALSE`, `goods_receipts`
mají storno stavem `CANCELLED`. Kontrola FK se tedy nikdy nespustí. Riziko vzniká až ve chvíli,
kdy někdo v rámci GDPR výmazu (čl. 17) smaže uživatele nebo bude čistit historii — pak každý
`DELETE FROM security.users` odstartuje sekvenční sken **čtrnácti tabulek**.

**Proč to vadí:** Provoz, latentně. Zároveň to je připomínka, že GDPR výmaz zaměstnance/uživatele
nemá dnes v aplikaci žádnou cestu.

**Návrh řešení:** Doplnit index nad `warehouse.stock_take_items (product_id)` a
`warehouse.stock_takes (surplus_receipt_id)` (obojí `RESTRICT`, obojí levné). Auditní `*_by`
indexy nechat, dokud neexistuje mazání uživatelů — *rozhodnutí uživatele*, jestli je chce
preventivně, nebo až s funkcí výmazu.

---

### [D-7] `"order".v_order_item_priced` má zamrzlý `oi.*` z V25 — nové sloupce `order_items` ve view nejsou
**Severita:** 🟡 NÍZKÝ
**Jistota:** OVĚŘENO
**Kde:**
- `src/main/resources/db/migration/V25__order_item_price_views.sql:16-21` — `SELECT oi.*, ROUND(...)`.
  PostgreSQL hvězdičku rozvine v okamžiku `CREATE VIEW`; pozdější `ALTER TABLE … ADD COLUMN`
  se do view nepromítne.
- Ověřeno v živé DB — sloupce view:
  `id, order_id, item_type, name, quantity, unit, purchase_price, unit_price, vat_rate, position, note, created_at, updated_at, created_by, line_net, line_vat, line_gross`.
  **Chybí `goods_receipt_item_id`** (přidán `V27__add_goods_receipt_item_id_to_order_item.sql:6-7`)
  **a `employee_id`** (přidán `V59__add_order_item_employee.sql:10-11`).

**Co je špatně:** View vypadá jako „order_items + dopočty", ale je to zmražený řez tabulky
ke stavu V25. Rozdíl není nikde zdokumentovaný (`docs/databaze.md:652` popisuje view jen jako
„dopočet cen řádku").

**Scénář selhání:** Přidá se `order_items.discount_pct` (migrace V64) a hned nato se v souhrnu
zakázky chce zobrazit sleva — stejným způsobem, jakým V63 přidala `*_cost`
(`V63__order_item_summary_cost.sql:33-36` čte `quantity` a `purchase_price` **z `v_order_item_priced`**).
`CREATE OR REPLACE VIEW "order".v_order_item_summary … SUM(discount_pct) FROM "order".v_order_item_priced`
skončí `ERROR: column "discount_pct" does not exist` — a příčina (že to je vinou `oi.*` z roku V25,
ne překlepem) není zjevná. V63 prošla jen náhodou: `purchase_price` je z V12, tedy starší než V25.

**Proč to vadí:** Provoz — past pro příští migraci, ne dnešní chyba. Dnes se z view čte jen
`orderItemSummaryColumns` (`OrderItemMapper.xml:135-139`), takže chybějící sloupce nikomu nevadí.

**Návrh řešení:** Buď v `docs/databaze.md` §9 u view doplnit poznámku „sloupcová sada zamrzlá
k V25 (`oi.*`) — nový sloupec `order_items` se ve view neobjeví, je nutné view přegenerovat",
nebo v příští migraci, která se view stejně dotkne, nahradit `oi.*` explicitním výčtem
(pak je zamrznutí vidět v kódu).

---

### [D-8] `customer.customers.gdpr_consent_at` je `NOT NULL DEFAULT NOW()` — zákazník bez souhlasu má „datum souhlasu"
**Severita:** 🟡 NÍZKÝ
**Jistota:** OVĚŘENO
**Kde:**
- `src/main/resources/db/migration/V2__init_customer_schema.sql:55-56` —
  `gdpr_consent BOOLEAN NOT NULL DEFAULT FALSE, gdpr_consent_at TIMESTAMPTZ NOT NULL DEFAULT NOW()`.
  Ověřeno v DB: `customer.customers | gdpr_consent_at | timestamp with time zone | NO | now()`.
- `src/main/resources/mapper/CustomerMapper.xml:277-278` — INSERT zapisuje `#{gdprConsent}` a
  hned pod tím **natvrdo `NOW()`**, bez ohledu na hodnotu souhlasu.
- Pro srovnání `marketing_consent_at` (V2:54) je **nullable** a INSERT ho plní z hodnoty
  (`CustomerMapper.xml:276` `#{marketingConsentAt}`) — obě pole se tedy chovají různě.
- `CustomerMapper.xml:334-336` — UPDATE nastaví `gdpr_consent_at = NOW()`, kdykoli se **hodnota
  změní**, tedy i při **odvolání** souhlasu.

**Co je špatně:** Sloupec pojmenovaný „kdy byl udělen souhlas" ve skutečnosti nese „kdy byl
záznam naposledy dotčen / kdy se stav souhlasu naposledy změnil". Pro `gdpr_consent = FALSE`
nese hodnotu, která nic neznamená.

**Scénář selhání:**
1. Obsluha založí zákazníka a checkbox „souhlas GDPR" nechá nezaškrtnutý →
   řádek `gdpr_consent = FALSE, gdpr_consent_at = 2026-07-30 10:15+02`.
2. Za rok přijde dotaz dozorového úřadu / dotčené osoby „kdy jsem dal souhlas?".
3. Kdo se podívá jen na `gdpr_consent_at` (report, export, ad-hoc SQL), přečte konkrétní datum
   a čas — přestože souhlas nikdy udělen nebyl.
4. Stejně po odvolání: souhlas byl udělen 2025-01-01, odvolán 2026-07-30 → sloupec ukazuje
   2026-07-30 a informace „kdy byl udělen" je nenávratně ztracená.

**Proč to vadí:** Právo, mírně. GDPR čl. 7 odst. 1 vyžaduje schopnost prokázat, že souhlas byl
udělen; pole, které by to mělo doložit, je nespolehlivé. Reálná škoda je malá — booleovská hodnota
je autoritativní a čte se spolu s datem — ale evidence je zavádějící a historie se přepisuje.

**Návrh řešení:** *Rozhodnutí uživatele.* Minimum: `COMMENT ON COLUMN` + doplnění poznámky
v `docs/databaze.md:112`, že sloupec znamená „poslední změna stavu souhlasu". Čistě:
migrace, která `gdpr_consent_at` uvolní na nullable a INSERT/UPDATE upraví tak, aby se plnil
`NOW()` jen při `gdpr_consent = TRUE` a při odvolání se nuloval (nebo se zavedl samostatný
`gdpr_consent_revoked_at`). Pozn.: uvolnění NOT NULL je bezpečné, dopad na kód je v
`Customer.gdprConsentAt` (už je `OffsetDateTime`, tedy nullable).

## Co bylo ověřeno jako v pořádku

**Číslování a struktura migrací**
- Řada **V1–V63 je souvislá, bez jediné díry** (ověřeno výčtem souborů napříč všemi třemi
  locations). Čísla chybějící v `db/migration` (3, 8, 13, 16, 46, 47, 58, 60) jsou právě ta,
  která leží v `db/demo` / `db/prod`.
- **V58 dvojče skutečně nekoliduje.** `application.yaml:` `locations: classpath:db/migration,classpath:db/demo`;
  `application-prod.yaml:` `locations: classpath:db/migration,classpath:db/prod`. Množiny se nikdy
  nepřekrývají, takže Flyway v žádném běhu nevidí dvě V58. Navíc je to pokryté testem
  `ProdSeedIntegrationTest` (vlastní čerstvý kontejner + produkční locations + placeholder),
  který ověřuje, že prod DB má právě jednoho uživatele, 5 rolí, prázdné `employee.employees`
  a `nextval(customer_number_seq) = 1`.
- Pořadí aplikace v prod (V1…V57, V58-prod, V59, V60-prod, V61…V63) je konzistentní — V59
  (FK na `employee.employees`) běží až po produkční V58, která schéma vytvoří.
- Dev DB odpovídá souboru migrací až do V55 (`flyway_schema_history`, všech 55 `success = t`).

**Constrainty a integrita**
- Všechny CHECK, UNIQUE, FK a `ON DELETE` z migrací **existují v DB v očekávané podobě**
  (porovnáno položku po položce přes `pg_constraint`).
- Částečné unikáty fungují dle záměru: `uq_invoices_order_active WHERE status <> 'CANCELLED'` (V48),
  `uq_receipt_supplier_docno WHERE status NOT IN ('REJECTED','CANCELLED')` (V39+V43),
  `uq_stock_take_single_open WHERE status = 'OPEN'` (V44),
  `uq_addresses_default_per_type WHERE is_default` a `uq_contact_persons_primary WHERE is_primary` (V2).
- `chk_movement_sign` po V29 pokrývá všech šest hodnot `movement_type` (RECEIPT>0, ISSUE_RETURN>0,
  ADJUSTMENT≠0, ISSUE/RETURN/WRITE_OFF<0) — sedí s `docs/databaze.md:570`.
- Složený FK `fk_mov_batch_product (batch_id, product_id) → goods_receipt_items(id, product_id)`
  (V52) je v DB; pohyb nemůže ukazovat na šarži cizího produktu.
- `chk_orders_price CHECK (estimated_price >= 0 AND final_price >= 0)` — prověřeno tříhodnotovou
  logikou: záporná hodnota je zachycena i tehdy, když je druhý sloupec NULL (`FALSE AND NULL = FALSE`).

**Číselné řady a souběh**
- Faktura (V49), dobropis (V55), PPD (V57), zakázka (V56) i inventura (V61) používají shodný
  a **správný** vzor: `pg_advisory_xact_lock` per období → `MAX(...)+1` scopnutý na období →
  `RAISE EXCEPTION` při přetečení (>999/měsíc, resp. >9999/rok) → `LPAD`.
- **Souběh je bezpečný i pod READ COMMITTED**: `SELECT MAX(...)` běží *až po* získání advisory
  locku a je to samostatný příkaz uvnitř VOLATILE trigger funkce, takže si bere **čerstvý snapshot**
  — vidí tedy řádek, který právě commitla čekající transakce. Duplicitní číslo nemůže vzniknout.
- **Rollback nedělá díry** — MAX+1 nic nespotřebovává (na rozdíl od `nextval`). Jediná řada
  se sekvencí je ZNK (viz D-4).
- Offsety `SUBSTRING` sedí u všech řad: faktura `FROM 7` (YYYYMM), dobropis `FROM 9` (OD+YYYYMM),
  PPD `FROM 10` (PPD+YYYYMM), ZAK/INV `FROM LENGTH(prefix)+1`.
- Stornované doklady si číslo ponechávají, takže se MAX+1 přes ně nepřepíše.

**ENUM typy**
- Všech **18 ENUM typů v DB odpovídá `docs/databaze.md` §7 hodnotu po hodnotě i v pořadí**
  (`pg_enum`), včetně `DRAFT BEFORE 'ISSUED'` (V17), tří kombinovaných `payment_method` (V31),
  `ISSUE_RETURN` (V28), `CANCELLED` v `receipt_status` (V43), `STOCK_TAKE` v `document_type` (V44).
- Všechny tři migrace, které přidávají hodnotu do existujícího ENUMu a hned ji používají
  (V17, V43, V44), mají v hlavičce `-- flyway:noAutoCommit` + explicitní `COMMIT` — a v dev DB
  jsou aplikované úspěšně. `PgEnumTypeHandler` se používá v mapperech přes
  `typeHandler=…$XxxHandler` + `::schema.typ` cast.

**Views**
- V DB existuje **přesně těch 7 views**, které dokumentace uvádí v §9 — ani jedno navíc, ani jedno chybějící.
- Matematika je konzistentní: `v_order_item_priced`, `v_invoice_price_totals` (V32) i
  `v_invoice_vat_summary` (V37) používají **identický** vzorec
  `line_net = ROUND(qty*price, 2)`, `line_vat = ROUND(qty*price*rate/100, 2)`,
  `gross = net + vat`. Součet rekapitulace DPH proto vždy sedí s celkovými součty faktury.
  `v_stock_valuation` (V42) zaokrouhluje po šarži stejnou filozofií.
- **Chování při 0 řádcích je ošetřené**: `GROUP BY` u souhrnných views nevrátí žádný řádek
  (`COALESCE` uvnitř na to nestačí) a kód s tím počítá — `InvoiceMapper.findSummaryByInvoiceId`
  vrací `Optional`, `InvoiceServiceImpl:490` a `CashReceiptServiceImpl:58` použijí
  `InvoiceSummary.zero(...)`, `OrderItemServiceImpl:93` `OrderItemSummary.zero(...)`.
  Kde se předává `null` (`CreditNoteServiceImpl:106`, `CashReceiptServiceImpl:106`),
  má konvertor null guard (`CashReceiptConverter:57`, `CreditNoteConverter:69`).
- `v_stock_valuation` nemůže rozejít hodnotu se stavem: **všechny** cesty vytvářející pohyb
  nastavují `batchId` (`ProductServiceImpl:256`, `ReceiptReviewServiceImpl:307` a `:407`,
  `StockTakeServiceImpl:206` a `:291`) a `StockMovementDto.CreateRequest.batchId` je `@NotNull`
  — bezšaržový kladný `ADJUSTMENT` (který by zvýšil množství bez hodnoty) tedy vzniknout nemůže.
- `v_batch_provenance` po V54 používá `LEFT JOIN` na dodavatele + `COALESCE` — inventurní šarže
  bez dodavatele z view nemizí.
- `CREATE OR REPLACE VIEW` v V63 je platné: zachovává původních 9 sloupců ve stejném pořadí
  a se stejnými názvy (vč. `service_net`/`service_gross` po přejmenování ve V26) a nové 4 přidává na konec.

**Seed data a prod bootstrap**
- **R-05 splněno u všech seedů s explicitními ID**: `db/demo/V3:251-255` (5× `setval`),
  `db/demo/V8:33` (vehicles) a `:52` (orders), `db/demo/V58:76` (employees). Seedy bez explicitních
  ID (`V13`, `V16`, role ve `V3`/`V60`) `setval` nepotřebují a nemají ho — správně.
- **`db/migration` neobsahuje žádná demo data** (konvence §14) — prošel jsem všech 55 souborů.
  Datové příkazy tam jsou jen backfilly nad reálnými daty (V20, V33–V36, V39, V40, V50, V51, V61, V62),
  které jsou na prázdné DB no-op, a jediný infra INSERT `billing.company_profile` (V35:64-73),
  který je nutný i v produkci.
- `db/prod/V60__prod_seed.sql`: heslo admina jde přes placeholder `${admin_password_hash}`
  z env `ADMIN_PASSWORD_HASH` (`application-prod.yaml`), v gitu není žádný hash. Role i uživatel
  jsou idempotentní (`ON CONFLICT DO NOTHING`), `setval('customer.customer_number_seq', 1, false)`
  je správně `is_called = false`, takže první `nextval` vrátí 1. Ověřeno testem.
- `V47` používá `GREATEST(...)`, takže sekvenci nikdy neposune zpět.

**Idempotence a bezpečnost migrací**
- `CREATE SCHEMA IF NOT EXISTS`, `CREATE EXTENSION IF NOT EXISTS`, `ADD VALUE IF NOT EXISTS`,
  `DROP INDEX IF EXISTS`, `ON CONFLICT DO NOTHING` použity tam, kde dávají smysl.
- Migrace přidávající NOT NULL to dělají **až po backfillu** (V33:17-28, V61:56-71) — na neprázdné
  DB neselžou.
- V35 backfill SUPPLIER řádků má `WHERE NOT EXISTS`, takže respektuje `uq_invoice_party_role`.
- V40 backfill má `ON CONFLICT (supplier_id, supplier_sku) DO NOTHING`.

**Soft-delete (R-06)**
- `is_active` mají: `customer.customers`, `customer.contact_persons`, `vehicle.vehicles`,
  `warehouse.suppliers`, `warehouse.products`, `employee.employees` a (vestigiálně, TD-67)
  `"order".orders`. Doklady místo toho používají stav (`invoices`, `credit_notes`, `goods_receipts`,
  `stock_takes`) nebo jsou append-only (`stock_movements`, `registry_snapshots`, `invoice_party`) —
  to je konzistentní a správné.
- Hard delete existuje jen na pěti místech a všechna jsou obhajitelná: `addresses`
  (full-replace sady, TD-42), `invoice_items` a `order_items` (řádky konceptu/nefakturované zakázky),
  `mileage_history` (editovatelný ledger dle V20), `user_roles` (přiřazení role, ne entita).

## Otevřené otázky pro uživatele

1. **[D-1] Kolik dobropisů smí být k jedné faktuře?** Dnes model dovoluje N a každý je plný.
   Návrh: částečný unikát `WHERE status <> 'CANCELLED'` (1 aktivní dobropis) — ale pokud se plánuje
   částečný dobropis podle TD-62, pak by pravidlo mělo být „součet dobropisů ≤ faktura", což unikát
   neumí a musí to hlídat service. Které z toho chceš teď?

2. **[D-4] Má se řada `ZNK` resetovat každý rok?** Buď přepsat trigger vzorem V56 (konzistentní
   s ostatními pěti řadami, po 1. 1. začne od 0001), nebo přiznat, že řada je průběžná, a upravit
   formát/dokumentaci. Je to volba vedení servisu, ne technická.

3. **[D-3] Chceš navíc DB pojistku `CHECK (status = 'DRAFT' OR invoice_number IS NOT NULL)`?**
   Dokumentační opravu považuji za nutnou v každém případě; CHECK je „opasek a kšandy" —
   zaručí, že vystavená faktura bez čísla nevznikne žádnou cestou, ale znemožní i legitimní
   import historických dokladů bez čísla (pokud by takový někdy byl potřeba).

4. **[D-8] Jak se má chovat `gdpr_consent_at` při odvolání souhlasu?** Dnes se přepíše datem
   odvolání a původní datum udělení je pryč. Pro doložitelnost dle GDPR čl. 7 by bylo lepší
   dvojice `gdpr_consent_at` (jen udělení) + `gdpr_consent_revoked_at`. Je to věc účetní/právní
   praxe servisu.

5. **[D-6] Mají se preventivně doplnit indexy na auditní `*_by` FK?** Dnes zbytečné (nic se
   nemaže). Souvisí s tím širší otázka: **existuje plán na GDPR výmaz** (čl. 17) zákazníka nebo
   zaměstnance? Dnes v aplikaci žádná cesta k výmazu není a `ON DELETE SET NULL` / `RESTRICT`
   FK na to nejsou dimenzované.

6. **Dev DB na `localhost:5433` je namigrovaná jen do V55.** Při příštím startu aplikace na ni
   Flyway dosype V56–V63 (mj. `V61` backfill čísel inventur a `V62` backfill kol). Chceš to udělat
   dřív, aby byl dev stav v souladu s repozitářem? *(Provozní poznámka, ne nález — nic jsem neměnil.)*
