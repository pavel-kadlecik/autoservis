# databaze.md — Databázová reference

> Autoritativní reference DB schématu, rekonstruovaná z Flyway migrací **V1–V70**.
> Při změně DB: nová migrace `V{n+1}__*.sql` + aktualizace tohoto dokumentu.
> Existující migrace se **nikdy nemění** (viz `konvence.md`).

PostgreSQL, multi-schema (modul = schéma). Konvence napříč celou DB:

| Rozhodnutí | Pravidlo |
|---|---|
| Primární klíče | `BIGSERIAL` v DB, `Long` v Javě (UUID se nepoužívá) |
| Cizí klíče | `BIGINT`; cross-schema FK jsou záměrné (např. `→ security.users(id)`) |
| Časová razítka | `TIMESTAMPTZ` v DB, `OffsetDateTime` v Javě (výjimka: security tokeny, viz Zvláštnosti) |
| `updated_at` | udržuje DB trigger `fn_set_updated_at()` (v každém schématu), ne aplikace |
| Mazání | soft-delete přes `is_active = FALSE`; hard delete se nepoužívá |
| ON DELETE — vlastnictví | `CASCADE` (customer → addresses) |
| ON DELETE — business vazba | `RESTRICT` (customer → vehicles, product → movements) |
| ON DELETE — auditní pole | `SET NULL` (users → *.created_by) |
| PostgreSQL ENUM | vlastní typy per schéma; v Javě přes `PgEnumTypeHandler` (`jdbcType=OTHER`) |

Schémata: `security`, `customer`, `vehicle`, `"order"` (rezervované slovo — v SQL vždy v uvozovkách), `billing`, `warehouse`, `employee`, `schedule`. Ve `public` je jen `flyway_schema_history`.

---

## 1. Schéma `security` (V1)

### security.roles
| Sloupec | Typ | NOT NULL | Default |
|---|---|---|---|
| id | SMALLSERIAL | PK | — |
| name | VARCHAR(50) | ANO | — |
| description | VARCHAR(255) | NE | — |

UNIQUE: `uq_roles_name (name)`.

### security.users
| Sloupec | Typ | NOT NULL | Default |
|---|---|---|---|
| id | BIGSERIAL | PK | — |
| username | VARCHAR(100) | ANO | — |
| email | VARCHAR(255) | ANO | — |
| password_hash | VARCHAR(255) | ANO | — (BCrypt) |
| enabled | BOOLEAN | ANO | TRUE |
| account_non_expired | BOOLEAN | ANO | TRUE |
| account_non_locked | BOOLEAN | ANO | TRUE |
| credentials_non_expired | BOOLEAN | ANO | TRUE |
| failed_login_attempts | SMALLINT | ANO | 0 |
| locked_at | TIMESTAMPTZ | NE | — |
| last_login_at | TIMESTAMPTZ | NE | — |
| password_changed_at | TIMESTAMPTZ | ANO | NOW() |
| created_at | TIMESTAMPTZ | ANO | NOW() |
| updated_at | TIMESTAMPTZ | ANO | NOW() |

UNIQUE: `uq_users_username`, `uq_users_email`. CHECK: `chk_users_email` (regex), `chk_failed_attempts` (≥ 0).
Indexy: `idx_users_email`, `idx_users_username`, `idx_users_enabled` (partial `WHERE enabled = TRUE`).
Trigger: `trg_users_updated_at`.

**Uzamčení účtu (V64).** Po 10 neúspěšných přihlášeních nastaví `LoginAttemptService`
`account_non_locked = FALSE` a orazítkuje `locked_at`. Zámek **není trvalý**: před autentizací
se volá `UserMapper.unlockIfLockExpired`, guardovaný `UPDATE`, který zámek uvolní, pokud
`locked_at + lockout.duration <= NOW()` (lhůta z `application.yaml`, výchozí 15 min). Porovnání
běží **výhradně v DB**, aby se nemíchaly hodiny aplikace a databáze. Admin reset hesla
(`unlockAccount`) odemyká okamžitě a `locked_at` nuluje. Do V64 byl zámek trvalý — audit KN-5.

### security.user_roles
| Sloupec | Typ | NOT NULL | FK |
|---|---|---|---|
| user_id | BIGINT | ANO | → security.users(id) ON DELETE CASCADE |
| role_id | SMALLINT | ANO | → security.roles(id) ON DELETE RESTRICT |
| assigned_at | TIMESTAMPTZ | ANO (NOW()) | |
| assigned_by | BIGINT | NE | → security.users(id) ON DELETE SET NULL |

PK: `(user_id, role_id)`.

### security.token_blacklist
| Sloupec | Typ | NOT NULL | Default |
|---|---|---|---|
| token | VARCHAR(512) | PK | — |
| invalidated_at | TIMESTAMP (bez TZ) | NE | CURRENT_TIMESTAMP |

Sloupec `token` ukládá od V4 (analyza-2026-07, `TokenHasher.sha256Hex`) SHA-256 hex otisk (64 znaků) přístupového JWT, ne raw token — únik zálohy DB tak nedává použitelný bearer token. Sloupec je stále `VARCHAR(512)` (dimenzovaný na celé JWT), migrace nebyla potřeba. Starší plaintextové záznamy (z doby před nasazením) přestaly matchovat a dožijí do své expirace.

### security.refresh_tokens
| Sloupec | Typ | NOT NULL | Default | FK |
|---|---|---|---|---|
| id | VARCHAR(36) | PK | — | |
| token | VARCHAR(64) | ANO, UNIQUE | — | SHA-256 hash tokenu (V45/K-7), ne syrové UUID |
| user_id | BIGINT | ANO | — | → users(id) ON DELETE CASCADE |
| expires_at | TIMESTAMP (bez TZ) | ANO | — | |
| revoked | BOOLEAN | ANO | FALSE | |
| created_at | TIMESTAMP (bez TZ) | ANO | CURRENT_TIMESTAMP | |

Indexy: `idx_refresh_tokens_user_id`, `idx_refresh_tokens_revoked` (partial `WHERE revoked = FALSE`).

---

## 2. Schéma `customer` (V2, V4, V9, V10)

Full-text: rozšíření `unaccent` (ve `public`) + konfigurace `customer.czech_simple` (simple + unaccent) — vyhledávání bez diakritiky (`Novak` najde `Novák`).

### customer.customers
| Sloupec | Typ | NOT NULL | Default | FK / pozn. |
|---|---|---|---|---|
| id | BIGSERIAL | PK | — | |
| user_id | BIGINT | NE, UNIQUE | — | → security.users(id) ON DELETE SET NULL |
| customer_type | customer_type ENUM | ANO | 'INDIVIDUAL' | |
| customer_number | VARCHAR(20) | ANO | — | trigger V9: `ZNK-{rok}-{4č.seq}` |
| first_name | VARCHAR(100) | NE | — | |
| last_name | VARCHAR(100) | NE | — | |
| birth_date | DATE | NE | — | |
| company_name | VARCHAR(255) | NE | — | |
| ico | VARCHAR(15) | NE, UNIQUE | — | |
| dic | VARCHAR(15) | NE | — | |
| legal_form | VARCHAR(100) | NE | — | |
| primary_email | VARCHAR(255) | NE | — | |
| primary_phone | VARCHAR(30) | NE | — | |
| marketing_consent | BOOLEAN | ANO | FALSE | |
| marketing_consent_at | TIMESTAMPTZ | NE | — | |
| gdpr_consent | BOOLEAN | ANO | FALSE | |
| gdpr_consent_at | TIMESTAMPTZ | ANO | NOW() | |
| preferred_contact_channel | contact_channel ENUM | NE | 'EMAIL' | |
| internal_note | TEXT | NE | — | |
| loyalty_points | INTEGER | ANO | 0 | |
| is_active | BOOLEAN | ANO | TRUE | |
| created_at / updated_at | TIMESTAMPTZ | ANO | NOW() | |
| created_by | BIGINT | NE | — | → security.users(id) ON DELETE SET NULL |

UNIQUE: `uq_customers_number`, `uq_customers_ico`.
CHECK: `chk_individual_required` (INDIVIDUAL → first+last name), `chk_company_required` (COMPANY → company_name), `chk_customers_email`, `chk_loyalty_points` (≥ 0).

> **Nevyplněné textové údaje = NULL, nikdy `''`** (V80). `uq_customers_ico` povoluje libovolně mnoho NULL, ale prázdný řetězec jen jednou — jeden řádek s `ico = ''` pak blokuje uložení všech ostatních zákazníků bez IČO; `chk_customers_email` prázdný řetězec rovnou odmítá. Normalizaci blank → NULL provádí `CustomerConverter`.
Indexy: `idx_customers_user_id`, `idx_customers_type`, `idx_customers_last_name` (partial INDIVIDUAL), `idx_customers_company_name` (partial COMPANY), `idx_customers_ico` (partial), `idx_customers_email` (partial), `idx_customers_active`, `idx_customers_fts` (GIN tsvector nad jménem/firmou/IČO).
Triggery: `trg_customers_updated_at`; `trg_generate_customer_number` (BEFORE INSERT, WHEN customer_number NULL/'').

### customer.addresses
| Sloupec | Typ | NOT NULL | Default | FK |
|---|---|---|---|---|
| id | BIGSERIAL | PK | — | |
| customer_id | BIGINT | ANO | — | → customers(id) ON DELETE CASCADE |
| address_type | address_type ENUM | ANO | 'CONTACT' | |
| is_default | BOOLEAN | ANO | FALSE | |
| street | VARCHAR(255) | ANO | — | |
| street_number | VARCHAR(20) | ANO | — | |
| city | VARCHAR(100) | ANO | — | |
| postal_code | VARCHAR(10) | ANO | — | |
| country_code | CHAR(2) | ANO | 'CZ' | |
| created_at / updated_at | TIMESTAMPTZ | ANO | NOW() | |

CHECK: `chk_postal_code` (CZ formát `^\d{3}\s?\d{2}$`). Partial UNIQUE: `uq_addresses_default_per_type (customer_id, address_type) WHERE is_default = TRUE`. Index: `idx_addresses_customer_id`. Trigger: `trg_addresses_updated_at`.

### customer.contact_persons
| Sloupec | Typ | NOT NULL | Default | FK |
|---|---|---|---|---|
| id | BIGSERIAL | PK | — | |
| customer_id | BIGINT | ANO | — | → customers(id) ON DELETE CASCADE |
| user_id | BIGINT | NE, UNIQUE | — | → security.users(id) ON DELETE SET NULL |
| first_name / last_name | VARCHAR(100) | ANO | — | |
| position | VARCHAR(100) | NE | — | |
| email | VARCHAR(255) | NE | — | |
| phone | VARCHAR(30) | NE | — | |
| is_primary | BOOLEAN | ANO | FALSE | |
| is_active | BOOLEAN | ANO | TRUE | |
| note | TEXT | NE | — | |
| created_at / updated_at | TIMESTAMPTZ | ANO | NOW() | |

CHECK: `chk_cp_email`. Partial UNIQUE: `uq_contact_persons_primary (customer_id) WHERE is_primary = TRUE`. Index: `idx_contact_persons_customer`. Trigger: `trg_contact_persons_updated_at`.

### customer.customer_communications
| Sloupec | Typ | NOT NULL | Default | FK |
|---|---|---|---|---|
| id | BIGSERIAL | PK | — | |
| customer_id | BIGINT | ANO | — | → customers(id) ON DELETE CASCADE |
| channel | contact_channel ENUM | ANO | — | |
| direction | VARCHAR(10) | ANO | — | CHECK: 'INBOUND'/'OUTBOUND' |
| subject | VARCHAR(255) | NE | — | |
| body | TEXT | NE | — | |
| handled_by | BIGINT | NE | — | → security.users(id) ON DELETE SET NULL |
| communicated_at | TIMESTAMPTZ | ANO | NOW() | |
| created_at | TIMESTAMPTZ | ANO | NOW() | |

Indexy: `idx_comm_customer_id`, `idx_comm_date` (communicated_at DESC).

---

## 3. Schéma `vehicle` (V5, V7, V19, V20, V38)

### vehicle.vehicles
| Sloupec | Typ | NOT NULL | Default | FK / pozn. |
|---|---|---|---|---|
| id | BIGSERIAL | PK | — | |
| customer_id | BIGINT | ANO | — | → customers(id) ON UPDATE CASCADE ON DELETE RESTRICT |
| vin | VARCHAR(17) | NE (V90), UNIQUE | — | stroj bez VIN (zahradní traktor) = NULL; UNIQUE považuje NULLy za různé, formátový CHECK na NULL projde |
| machine_serial_number | VARCHAR(50) | NE | — | V90; výrobní číslo stroje bez VIN — volný text, bez unikátnosti |
| license_plate | VARCHAR(15) | NE | — | není unikátní (přenosy značek) |
| brand | VARCHAR(100) | ANO | — | |
| model | VARCHAR(100) | ANO | — | |
| year_of_manufacture | SMALLINT | NE | — | |
| first_registration_date | DATE | NE | — | |
| fuel_type | vehicle.fuel_type ENUM | NE | — | V86; přívěsný vozík nemá motor. NULL = nevyplněno, `OTHER` = jiné než uvedené |
| transmission | vehicle.transmission_type ENUM | NE | — | |
| engine_displacement_ccm | INTEGER | NE | — | |
| engine_power_kw | SMALLINT | NE | — | |
| engine_code | VARCHAR(30) | NE | — | V19; CHECK not-blank |
| color | VARCHAR(50) | NE | — | |
| current_mileage_km | INTEGER | NE | — | denormalizovaná cache — drží trigger V20 |
| stk_valid_until | DATE | NE | — | V38; denormalizovaná cache STK z posledního registry snapshotu — drží trigger, aplikace nezapisuje |
| wheels | TEXT | NE | — | V62; pneu/ráfky per náprava (`raw_response->>NapravyPneuRafky`) — denormalizovaná cache z posledního snapshotu, drží týž trigger, aplikace nezapisuje, jen k zobrazení |
| internal_note | TEXT | NE | — | |
| is_active | BOOLEAN | ANO | TRUE | |
| created_at / updated_at | TIMESTAMPTZ | ANO | NOW() | |
| created_by | BIGINT | NE | — | → security.users(id) ON UPDATE CASCADE ON DELETE SET NULL |

UNIQUE: `uq_vehicles_vin` — od V90 nad nullable sloupcem: libovolně mnoho strojů bez VIN (NULLy jsou si navzájem „různé"), vyplněné VINy zůstávají unikátní.
CHECK: `chk_vehicles_vin_format` (17 znaků, bez I/O/Q), `chk_vehicles_year` (1885 ≤ rok ≤ aktuální+1), `chk_vehicles_year_registration` (V7: rok výroby ≤ rok 1. registrace), `chk_vehicles_displacement` (50–10000), `chk_vehicles_power` (1–2000), `chk_vehicles_mileage` (≥ 0), `chk_vehicles_engine_code_not_blank`.

> **Nevyplněné volitelné texty = NULL, nikdy `''`** (V81, stejné pravidlo jako u `customer.customers`/V80). `chk_vehicles_engine_code_not_blank` prázdný řetězec odmítá — FE ale nevyplněné pole posílá jako `''`, takže bez normalizace padalo založení/editace vozidla bez kódu motoru na 422. Normalizaci blank → NULL provádí `VehicleConverter`. U `vin` (nepovinný od V90) je navíc `@Pattern` v DTO záměrně tvaru `^$|^…{17}$` — prázdný řetězec z FE musí projít validací (běží před konvertorem), na NULL ho převede až konvertor.
>
> Totéž platí pro **volitelné ENUMy** (`fuel_type` od V86, `transmission` od V5), jen se řeší o vrstvu výš: `''` na enum nepřevede Jackson a request padá na `HttpMessageNotReadableException` dřív, než se ke slovu dostane konvertor i validace. Globální pravidlo `EmptyString → AsNull` pro všechny enumy zavádí `JacksonConfig` — viz [funkce/palivo-nepovinne.md](funkce/palivo-nepovinne.md).
Trigger: `trg_vehicles_updated_at`.
Index: `idx_vehicles_stk_valid_until (stk_valid_until) WHERE is_active = TRUE` (V38 — filtr „končící STK").

### vehicle.mileage_history (V20)
| Sloupec | Typ | NOT NULL | Default | FK |
|---|---|---|---|---|
| id | BIGSERIAL | PK | — | |
| vehicle_id | BIGINT | ANO | — | → vehicles(id) ON UPDATE CASCADE ON DELETE CASCADE |
| mileage_km | INTEGER | ANO | — | CHECK 0–9 999 999 |
| recorded_date | DATE | ANO | CURRENT_DATE | CHECK ≤ CURRENT_DATE |
| source | vehicle.mileage_source ENUM | ANO | 'OTHER' | |
| note | TEXT | NE | — | |
| created_at | TIMESTAMPTZ | ANO | NOW() | |
| created_by | BIGINT | NE | — | → security.users(id) ON UPDATE CASCADE ON DELETE SET NULL |

Index: `idx_mileage_history_latest (vehicle_id, recorded_date DESC, id DESC)`.
Trigger: `trg_mileage_history_sync_current` (AFTER INSERT/UPDATE/DELETE) → přepočítá `vehicles.current_mileage_km` na poslední čtení (plný recompute — léčí i UPDATE/DELETE).

### vehicle.registry_snapshots (V38)

Snapshoty z registru vozidel (dataovozidlech.cz — Datová kostka RSV). Append-only: každé úspěšné volání API = nový řádek. `registry_status` je záměrně VARCHAR (množinu hodnot řídí Ministerstvo dopravy — neznámá hodnota nesmí shodit INSERT); `raw_response` uchovává kompletní `Data` objekt z API (limit 27 dotazů/min — surová data šetří opakovaná volání).

| Sloupec | Typ | NOT NULL | Default | FK / pozn. |
|---|---|---|---|---|
| id | BIGSERIAL | PK | — | |
| vehicle_id | BIGINT | ANO | — | → vehicles(id) ON UPDATE CASCADE ON DELETE CASCADE |
| stk_valid_until | DATE | NE | — | `PravidelnaTechnickaProhlidkaDo` |
| last_inspection_date | DATE | NE | — | `EvidencniProhlidkaDne` |
| registry_status | VARCHAR(100) | NE | — | `StatusNazev` (např. PROVOZOVANÉ) |
| raw_response | JSONB | ANO | — | kompletní odpověď registru |
| fetched_at | TIMESTAMPTZ | ANO | NOW() | |
| created_by | BIGINT | NE | — | → security.users(id) ON UPDATE CASCADE ON DELETE SET NULL |

Index: `idx_registry_snapshots_latest (vehicle_id, fetched_at DESC, id DESC)`.
Trigger: `trg_registry_snapshots_sync_stk` (AFTER INSERT/UPDATE/DELETE) → přepočítá `vehicles.stk_valid_until` **i `vehicles.wheels`** (V62) z posledního snapshotu (plný recompute, vzor V20).

---

## 4. Schéma `"order"` (V6, V11, V12, V22–V27, V56, V59, V63, V70)

> Název schématu je rezervované slovo SQL — v XML mapperech vždy `"order".orders`. (Nevhodnost názvu je evidovaná jako tech dluh TD-16.)

### "order".orders
| Sloupec | Typ | NOT NULL | Default | FK / pozn. |
|---|---|---|---|---|
| id | BIGSERIAL | PK | — | |
| order_number | VARCHAR | ANO, UNIQUE | — | trigger `ZAK-{rok}-{4č.}`; V56: per-rok MAX+1 + advisory lock (reset per rok, TD-57) |
| customer_id | BIGINT | ANO | — | → customers(id) (default NO ACTION) |
| vehicle_id | BIGINT | ANO | — | → vehicles(id) (default NO ACTION) |
| status | "order".order_status ENUM | ANO | 'RECEIVED' | |
| description | TEXT | ANO | — | popis pro zákazníka |
| internal_note | TEXT | NE | — | interní poznámka mechanika |
| estimated_completion_at | TIMESTAMPTZ | NE | — | |
| completed_at | TIMESTAMPTZ | NE | — | |
| estimated_price | NUMERIC | NE | — | ruční orientační odhad |
| final_price | NUMERIC | NE | — | |
| mileage_km_at_intake | INTEGER | NE | — | V70; stav tachometru při příjmu — **snímek pro zakázkový list**, odometr vozu vede `vehicle.mileage_history` |
| received_at | DATE | ANO | — | V94; obchodní **datum přijetí vozidla** — zadává uživatel, tiskne se na zakázkovém listu; `created_at` zůstává auditní |
| is_active | BOOLEAN | ANO | TRUE | |
| created_at / updated_at | TIMESTAMPTZ | ANO | NOW() | |
| created_by | BIGINT | NE | — | → security.users(id) ON DELETE SET NULL |

UNIQUE: `uq_orders_number`. CHECK: `chk_orders_price` (≥ 0; při NULL prochází), `chk_orders_mileage_at_intake` (0–9 999 999; při NULL prochází — V70).
Triggery: `trg_orders_updated_at`; `trg_generate_order_number` (BEFORE INSERT, WHEN order_number NULL/'').

### "order".order_items (V12, V22–V24, V27, V59)
| Sloupec | Typ | NOT NULL | Default | FK / pozn. |
|---|---|---|---|---|
| id | BIGSERIAL | PK | — | |
| order_id | BIGINT | ANO | — | → orders(id) ON DELETE CASCADE |
| item_type | "order".order_item_type ENUM | ANO | — | 3 hodnoty (po redukci V24) |
| name | VARCHAR(500) | ANO | — | rozšířeno z 255 (V22) kvůli name_snapshot šarže |
| quantity | NUMERIC(10,2) | ANO | — | CHECK > 0 |
| unit | VARCHAR(20) | ANO | — | |
| purchase_price | NUMERIC(10,2) | NE | — | interní nákupní cena; CHECK ≥ 0 |
| unit_price | NUMERIC(10,2) | ANO | — | prodejní cena; CHECK ≥ 0 |
| vat_rate | SMALLINT | ANO | 21 | CHECK 0–100 |
| position | SMALLINT | ANO | 1 (V23, dříve 0) | CHECK ≥ 0 |
| note | TEXT | NE | — | |
| created_at / updated_at | TIMESTAMPTZ | ANO | NOW() | |
| created_by | BIGINT | NE | — | → security.users(id) ON DELETE SET NULL |
| goods_receipt_item_id | BIGINT | NE | — | V27 → warehouse.goods_receipt_items(id) ON DELETE RESTRICT — vazba položky na šarži |
| employee_id | BIGINT | NE | — | V59 → employee.employees(id) ON UPDATE CASCADE ON DELETE RESTRICT — mechanik, který práci odvedl (D-1) |

Indexy: `idx_order_items_order_id`, `idx_order_items_goods_receipt_item`, `idx_order_items_employee`.
CHECK: `chk_order_items_employee_labor` (V59) — `employee_id` lze nastavit **jen** u položky `item_type = 'LABOR'` (D-2). Sazba mechanika se při přiřazení snímkuje do `purchase_price` (D-3).
Trigger: `trg_order_items_updated_at`.

---

## 5. Schéma `billing` (V14–V17, V31–V37, V48–V51, V55, V57, V66–V69)

### billing.invoices
| Sloupec | Typ | NOT NULL | Default | FK / pozn. |
|---|---|---|---|---|
| id | BIGSERIAL | PK | — | |
| invoice_number | VARCHAR(20) | NE, UNIQUE | — | od **V71** vkládá aplikace při **založení**: maska z `company_profile` jen generuje návrh, uložit lze libovolné neprázdné číslo. CHECK neprázdnosti; ISSUED/PAID ho musí mít; po vystavení **neměnné** (trigger V71). Historické koncepty V71 dorovnala starým formátem |
| order_id | BIGINT | ANO | — | → "order".orders(id); **1:1 pro aktivní fakturu** (částečný unikát V48) |
| customer_id | BIGINT | ANO | — | → customers(id) |
| issue_date | DATE | ANO | CURRENT_DATE | zadá obsluha při zakládání konceptu a naposledy potvrdí v dialogu vystavení — server ho **nepřepisuje** (rozhodnutí uživatele 2026-08-07; dřív razítko dneškem, KN-10) ani neomezuje (2026-08-09 zrušen zákaz budoucího data `ISSUE_DATE_IN_FUTURE`) |
| due_date | DATE | ANO | — | CHECK ≥ issue_date; při vystavení se posune o původní lhůtu, kdyby ji zvolené datum vystavení předběhlo |
| taxable_supply_date | DATE | ANO | — | DUZP |
| variable_symbol | VARCHAR(10) | NE | — | od **V71** se negeneruje — vyplňuje uživatel v dialogu; CHECK jen číslice (1–10) |
| constant_symbol | VARCHAR | NE | — | |
| specific_symbol | VARCHAR | NE | — | |
| payment_method | billing.payment_method ENUM | ANO | 'CASH' | |
| status | billing.invoice_status ENUM | ANO | 'DRAFT' (V17) | |
| note | TEXT | NE | — | |
| created_at / updated_at | TIMESTAMPTZ | ANO | NOW() | |
| created_by | BIGINT | NE | — | → security.users(id) ON DELETE SET NULL |
| customer_name_snapshot | VARCHAR(255) | ANO | — | V33; denormalizovaná kopie pro levný výpis — zdroj pravdy je invoice_party |
| order_number_snapshot | VARCHAR | ANO | — | V33 |
| vehicle_license_plate_snapshot | VARCHAR(20) | NE | — | V36; zmražená SPZ vozidla |
| vehicle_vin_snapshot | VARCHAR(17) | NE | — | V50; zmražený VIN (K-5 — dřív se četl živě) |
| vehicle_brand_snapshot | VARCHAR(100) | NE | — | V50; zmražená značka |
| vehicle_model_snapshot | VARCHAR(100) | NE | — | V50; zmražený model |
| paid_at | TIMESTAMPTZ | NE | — | V51; kdy zaplaceno (NULL = nezaplaceno) |
| paid_amount | NUMERIC(12,2) | NE | — | V51; zaplacená částka (plná úhrada = celková částka) |
| paid_method | payment_method ENUM | NE | — | V51; skutečný způsob úhrady |
| credited_at | TIMESTAMPTZ | NE | — | V69; kdy byl k faktuře **vystaven** dobropis. NULL = nedobropisovaná |
| purchase_order_number | VARCHAR(100) | NE | — | V91; číslo objednávky zákazníka (nákupní objednávka / PO) — volný text bez formátového omezení, zadává obsluha ručně, NULL = neuvedeno. Tiskne se na fakturu kvůli párování u odběratele |

UNIQUE: `uq_invoices_order_active` (částečný, `WHERE status <> 'CANCELLED' AND credited_at IS NULL` — V48 + V69), `uq_invoice_number`. CHECK (V71): `chk_invoice_number_not_blank`, `chk_invoice_issued_has_number` (ISSUED/PAID ⇒ číslo NOT NULL), `chk_invoice_variable_symbol_digits` (`^[0-9]{1,10}$`). Indexy: `idx_invoices_customer_id`, `idx_invoices_status`.

FK z `invoice_items` a `invoice_party` mají **ON DELETE CASCADE**, což od 2026-08-02 nese mazání konceptů (`InvoiceMapper.deleteDraft`): položky a strany odejdou s konceptem. `credit_notes` a `cash_receipts` míří na fakturu s NO ACTION, ale ke konceptu vzniknout nemohou (guardy `INVOICE_NOT_CORRECTABLE` / `INVOICE_NOT_ISSUED`), takže osiřelý odkaz nehrozí.

`chk_invoice_issued_has_number` je **nosné pravidlo modelu číslování**, ne jen pojistka: `invoice_number` je u konceptu vždy `NULL` a vyplní se až při vystavení, aby zrušený koncept nespálil číslo řady. Přesun číslování ze založení na vystavení (2026-08-02) proto **nepotřeboval migraci** — DB tenhle stav dovolovala už od V71.

**Aktivní faktura zakázky** = nestornovaná **a nedobropisovaná**. Dobropisovaná faktura zůstává platným dokladem ve stavu ISSUED/PAID, ale zakázku už neblokuje — jinak by ji chybná faktura zamkla navždy (storno vystaveného dokladu je od KN-1 zakázané, dobropis stav faktury nemění). Predikát indexu musí zůstat shodný s `InvoiceMapper.findByOrderId` (vrací `Optional`) a s LEFT JOINem v `OrderMapper.search` (jinak by refakturovaná zakázka byla v seznamu dvakrát).
Triggery: `trg_invoices_updated_at`; `trg_invoices_number_immutable` (V71) — po vystavení (OLD.status ≠ DRAFT) odmítne jakoukoli změnu `invoice_number` přímo v DB. Generátor čísla (`trg_invoices_generate_number`, V15→V49) je od **V71 zrušen**: číslo skládá aplikace podle masky z `company_profile` **při vystavení** (`DocumentNumberMask` — do V92 `InvoiceNumberMask`, MAX+1 přes `regexp_match` pod `pg_advisory_xact_lock` nad řadou), unikátnost dál jistí `uq_invoice_number`.

> **1:1 faktura ↔ zakázka je vědomé rozhodnutí** (ne omezení k odstranění): dělené fakturování se řeší založením více zakázek. Service hlídá přes `ORDER_ALREADY_INVOICED`. Od V48 (audit K-1/R-1) platí unikátnost jen pro **aktivní** fakturu — stornovaná (CANCELLED) zakázku neblokuje, takže po chybné faktuře lze vystavit novou. Od auditu KN-1 přitom stornovat lze **jen koncept**: vystavený doklad se opravuje dobropisem (`billing.credit_notes`), takže CANCELLED faktura vzniká výhradně ze zrušeného DRAFTu.

### billing.invoice_items
| Sloupec | Typ | NOT NULL | Default | FK |
|---|---|---|---|---|
| id | BIGSERIAL | PK | — | |
| invoice_id | BIGINT | ANO | — | → invoices(id) ON DELETE CASCADE |
| order_item_id | BIGINT | ANO | — | → "order".order_items(id) ON DELETE RESTRICT |
| name | VARCHAR | ANO | — | |
| quantity | NUMERIC(10,2) | ANO | — | CHECK > 0 |
| unit | VARCHAR(20) | ANO | — | |
| unit_price | NUMERIC(10,2) | ANO | — | CHECK ≥ 0 |
| vat_rate | SMALLINT | ANO | 21 | CHECK 0–100 |
| position | SMALLINT | ANO | 0 | CHECK ≥ 0 |

Index: `idx_invoice_items_invoice_id`.

### billing.invoice_party (V34, V35) — immutable snapshot stran faktury
| Sloupec | Typ | NOT NULL | Default | FK |
|---|---|---|---|---|
| id | BIGSERIAL | PK | — | |
| invoice_id | BIGINT | ANO | — | → invoices(id) ON DELETE CASCADE |
| role | billing.invoice_party_role ENUM | ANO | — | SUPPLIER / CUSTOMER |
| name | VARCHAR(255) | ANO | — | |
| ico | VARCHAR(15) | NE | — | |
| dic | VARCHAR(15) | NE | — | |
| street | VARCHAR(255) | NE | — | |
| street_number | VARCHAR(20) | NE | — | |
| city | VARCHAR(100) | NE | — | |
| postal_code | VARCHAR(10) | NE | — | |
| country_code | CHAR(2) | ANO | 'CZ' | |
| bank_account | VARCHAR(34) | NE | — | V35; plní jen SUPPLIER |
| iban | VARCHAR(34) | NE | — | V35 |
| swift | VARCHAR(11) | NE | — | V35 |
| created_at | TIMESTAMPTZ | ANO | NOW() | |

UNIQUE: `uq_invoice_party_role (invoice_id, role)`. Index: `idx_invoice_party_invoice_id`.
Bez `updated_at` a bez triggeru — **immutable snapshot** (faktura je právní doklad).

### billing.company_profile (V35) — jednořádková tabulka
| Sloupec | Typ | NOT NULL | Default |
|---|---|---|---|
| id | INTEGER | PK | 1 — CHECK `(id = 1)` vynucuje jediný řádek |
| name | VARCHAR(255) | ANO | — |
| ico / dic | VARCHAR(15) | NE | — |
| street | VARCHAR(255) | NE | — |
| street_number | VARCHAR(20) | NE | — |
| city | VARCHAR(100) | NE | — |
| postal_code | VARCHAR(10) | NE | — |
| country_code | CHAR(2) | ANO | 'CZ' |
| bank_account / iban | VARCHAR(34) | NE | — |
| swift | VARCHAR(11) | NE | — |
| invoice_number_auto | BOOLEAN | ANO | TRUE — V71; zapíná skládání čísla faktury dle masky + předvyplnění v dialogu |
| invoice_number_mask | VARCHAR(40) | ANO | `'{RRRR}{MM}{NNN}'` — V71; maska řady, tokeny `{RRRR}` `{RR}` `{MM}` `{N…}` (validuje `DocumentNumberMask`) |
| cash_receipt_number_source | billing.cash_receipt_number_source | ANO | `'MASK'` — V93 (nahradil boolean `cash_receipt_number_auto` z V92); zdroj čísla PPD v dialogu: `MASK` = návrh dle masky, `INVOICE` = číslo hrazené faktury, `MANUAL` = prázdné pole |
| cash_receipt_number_mask | VARCHAR(40) | ANO | `'PPD{RRRR}{MM}{NNN}'` — V92; maska řady PPD, default = historický formát `PPD{YYYYMM}###` |
| cash_receipt_gap_check_enabled | BOOLEAN | ANO | FALSE — V92; hlídat mezery v řadě PPD (zrcadlo V89); v režimu `INVOICE` kontrolu deaktivuje aplikace (V93) |
| cash_receipt_gap_check_from | VARCHAR(20) | NE | — V92; číslo PPD, od kterého se hlídá; NULL = celé aktuální období |
| created_at / updated_at | TIMESTAMPTZ | ANO | NOW() |

Trigger: `trg_company_profile_updated_at`.
⚠️ Seed V35 vkládá **zástupná data** („DOPLŇTE NÁZEV FIRMY") — nutno nahradit před produkčním vystavováním faktur. Nové faktury berou SUPPLIER stranu z aktuálního stavu této tabulky. Default masky (V71) odpovídá historickému formátu `YYYYMM###`, takže po migraci řada plynule navazuje.

### billing.cash_receipts (V57, V68, V92) — příjmový pokladní doklad (PPD)
| Sloupec | Typ | NOT NULL | Default | Poznámka |
|---|---|---|---|---|
| id | BIGSERIAL | PK | — | |
| receipt_number | VARCHAR(20) | ANO | — | od **V92** vkládá aplikace při vystavení: maska z `company_profile` jen generuje návrh, uložit lze libovolné neprázdné číslo. UNIQUE; CHECK neprázdnosti; **neměnné** (trigger V92) |
| invoice_id | BIGINT | ANO | — | FK → billing.invoices; **nejvýš jeden nestornovaný doklad na fakturu** (V68) |
| issue_date | DATE | ANO | CURRENT_DATE | |
| amount | NUMERIC(12,2) | ANO | — | přijatá částka (snapshot `total_to_pay` faktury); CHECK `>= 0` |
| purpose | VARCHAR(255) | NE | — | účel platby („Úhrada faktury č. …, VS …"), skládá aplikace |
| status | billing.cash_receipt_status | ANO | 'ISSUED' | V68 — `ISSUED` / `CANCELLED` |
| cancelled_at | TIMESTAMPTZ | NE | — | V68 |
| cancelled_by | BIGINT | NE | — | V68 — FK → security.users (ON DELETE SET NULL) |
| cancellation_reason | VARCHAR(255) | NE | — | V68 — povinný při stornu (CHECK) |
| created_at | TIMESTAMPTZ | ANO | NOW() | |
| created_by | BIGINT | NE | — | FK → security.users (ON DELETE SET NULL) |

Indexy: `idx_cash_receipts_invoice`, částečný unikát `uq_cash_receipts_invoice_active` (`WHERE status <> 'CANCELLED'`). Trigger: `trg_cash_receipts_number_immutable` (V92) — odmítne jakoukoli změnu `receipt_number` přímo v DB (storno mění jen status). Generátor čísla (`trg_cash_receipts_generate_number`, V57) je od **V92 zrušen**: číslo skládá aplikace podle masky z `company_profile` (`MAX+1` přes `regexp_match` pod `pg_advisory_xact_lock` nad řadou — týž vzor jako faktury od V71), unikátnost dál jistí `uq_cash_receipt_number`. CHECK `chk_cash_receipt_cancellation` váže stav na `cancelled_at`/`cancellation_reason` — polostornovaný doklad neexistuje.

**Obsah** dokladu je neměnný (bez `updated_at`); povolené operace jsou **storno** (V68 — doklad zůstává v číselné řadě s důvodem a jen přestane platit) a **smazání** (V92, rozhodnutí uživatele 2026-08-09 — tvrdé DELETE, číslo se uvolní a díru v řadě zavírá ruční zápis čísla; mazat jde i stornovaný doklad). Obojí uvolní fakturu pro nový doklad. Účastníci a rozpis DPH se neukládají — odvozují se z faktury (strany z `invoice_party`, DPH z `v_invoice_vat_summary`).

### billing.credit_notes (V55, V66) — opravný daňový doklad (dobropis, §45 ZDPH)
| Sloupec | Typ | NOT NULL | Default | Poznámka |
|---|---|---|---|---|
| id | BIGSERIAL | PK | — | |
| credit_note_number | VARCHAR | NE | — | řada `OD{YYYYMM}###`, přiděluje trigger až **při vystavení**; UNIQUE `uq_credit_note_number`. Koncept číslo nemá (vzor faktury po V49) |
| original_invoice_id | BIGINT | ANO | — | FK → billing.invoices — §45 evidenční číslo původního dokladu |
| correction_reason | VARCHAR(500) | ANO | — | §45 důvod opravy |
| issue_date | DATE | ANO | CURRENT_DATE | |
| taxable_supply_date | DATE | ANO | — | |
| status | billing.invoice_status ENUM | ANO | DRAFT | **recykluje ENUM faktury** — samostatný typ se nezaváděl |
| created_at / updated_at | TIMESTAMPTZ | ANO | NOW() | |
| created_by | BIGINT | NE | — | FK → security.users (ON DELETE SET NULL) |

**Samostatná tabulka, ne discriminator na `invoices`** (rozhodnutí R-7): izoluje dobropis od
fakturačního toku a nemíchá se do `uq_invoices_order_active`, stavového automatu ani number
triggeru faktur.

Index: `idx_credit_notes_original`. Triggery: `trg_credit_notes_updated_at`;
`trg_credit_notes_generate_number` (BEFORE UPDATE při přechodu DRAFT→ISSUED, advisory lock
per měsíc, overflow guard >999).

**UNIQUE `uq_credit_notes_original_active` (V66):** částečný index na `original_invoice_id`
`WHERE status <> 'CANCELLED'` — **jeden aktivní opravný doklad na fakturu** (audit KN-8). Do V66
chyběl a service existenci dřívějšího dobropisu neověřoval; každý dobropis přitom nese celou
zápornou fakturu (MVP = plný dobropis), takže dva znamenaly dvojnásobné snížení daně na výstupu.
Stornovaný doklad neblokuje — po stornu lze vystavit nový (storno dobropisu zatím endpoint nemá).
Až přibude **částečný** dobropis (TD-62), pravidlo se změní na „součet ≤ faktura" a index bude
potřeba nahradit.

Rozdílové částky a strany se **neukládají** — odvozují se z původní faktury (strany
z `invoice_party`, rozdíly jako záporné souhrny z `v_invoice_price_totals` /
`v_invoice_vat_summary`). Žádné duplicitní snapshoty ani položky.

---

## 6. Schéma `warehouse` (V18, V21, V28–V30)

### warehouse.suppliers
| Sloupec | Typ | NOT NULL | Default | Pozn. |
|---|---|---|---|---|
| id | BIGSERIAL | PK | — | |
| name | VARCHAR(255) | ANO | — | |
| registration_number | VARCHAR(30) | NE, UNIQUE | — | V30: přejmenováno z `ico` VARCHAR(15) |
| vat_id | VARCHAR(20) | NE | — | V30: přejmenováno z `dic` VARCHAR(15) |
| street | VARCHAR(255) | NE | — | |
| city | VARCHAR(100) | NE | — | |
| postal_code | VARCHAR(10) | NE | — | |
| country_code | CHAR(2) | ANO | 'CZ' | |
| bank_account | VARCHAR(50) | NE | — | |
| iban | VARCHAR(34) | NE | — | |
| swift | VARCHAR(11) | NE | — | |
| email | VARCHAR(255) | NE | — | |
| phone | VARCHAR(30) | NE | — | |
| is_active | BOOLEAN | ANO | TRUE | |
| created_at / updated_at | TIMESTAMPTZ | ANO | NOW() | |

UNIQUE: `uq_suppliers_registration_number` (V30, dříve `uq_suppliers_ico`). Index: `idx_suppliers_name`. Trigger: `trg_suppliers_updated_at`.

### warehouse.products
| Sloupec | Typ | NOT NULL | Default | Pozn. |
|---|---|---|---|---|
| id | BIGSERIAL | PK | — | |
| sku | VARCHAR(100) | ANO, UNIQUE | — | lidsky čitelné „hlavní katalogové číslo" (od V40 už NENÍ párovací identita — viz supplier_products) |
| manufacturer_part_number | VARCHAR(100) | NE | — | V40; číslo dílu dle výrobce — párovací identita spolu s manufacturer |
| part_number_normalized | VARCHAR(100) | NE (GENERATED) | — | V40; upper bez mezer/teček/pomlček; index |
| name | VARCHAR(500) | ANO | — | |
| unit | VARCHAR(20) | ANO | 'ks' | |
| default_vat_rate | SMALLINT | NE | — | CHECK NULL nebo 0–100 |
| quantity_on_hand | NUMERIC(12,3) | ANO | 0 | denormalizace — drží trigger pohybů; CHECK ≥ 0 |
| manufacturer | VARCHAR(255) | NE | — | V21 |
| variant | VARCHAR(255) | NE | — | V21 |
| note | VARCHAR(500) | NE | — | V21 |
| sale_price | NUMERIC(12,2) | NE | — | V21; CHECK NULL nebo ≥ 0 |
| min_stock_level | NUMERIC(12,3) | NE | — | V21; opt-in práh „nízký stav"; CHECK NULL nebo ≥ 0 |
| is_active | BOOLEAN | ANO | TRUE | |
| created_at / updated_at | TIMESTAMPTZ | ANO | NOW() | |

UNIQUE: `uq_products_sku`. Indexy: `idx_products_name`, `idx_products_manufacturer`, `idx_products_part_number_norm` (V40), `idx_products_name_trgm` (V40, GIN pg_trgm — podobnost názvů pro párovací kaskádu). Trigger: `trg_products_updated_at`.

### warehouse.supplier_products — převodník kódů dodavatelů (V40)
Standardní ERP vzor (supplier item cross-reference): (dodavatel, jeho katalogové číslo) → skladová karta. **Samoučící se** — potvrzené párování v review workflow se sem upsertuje; příští import stejného kódu se napáruje automaticky.

| Sloupec | Typ | NOT NULL | Default | FK / pozn. |
|---|---|---|---|---|
| id | BIGSERIAL | PK | — | |
| supplier_id | BIGINT | ANO | — | → suppliers(id) ON DELETE CASCADE |
| supplier_sku | VARCHAR(100) | ANO | — | katalogové číslo dodavatele |
| product_id | BIGINT | ANO | — | → products(id) ON DELETE CASCADE |
| name_snapshot | VARCHAR(500) | NE | — | název z posledního dokladu |
| last_unit_price_excl_vat | NUMERIC(12,2) | NE | — | poslední nákupní cena |
| last_seen_at | TIMESTAMPTZ | ANO | NOW() | |
| created_at / updated_at | TIMESTAMPTZ | ANO | NOW() | |

UNIQUE: `uq_supplier_products (supplier_id, supplier_sku)`. Index: `idx_supplier_products_product`. Trigger: `trg_supplier_products_updated_at`. Backfill V40: sku existujících produktů → převodník dodavatele dle provenience nejstarší šarže.

### warehouse.receipt_delivery_note_refs — DL reference faktur (V41)
Dedup DL ↔ faktura (LKQ vzor: souhrnná faktura opakuje položky již přijatého dodacího listu pod skupinovým řádkem „Dodací list č. X"). Rozhodnutí kontrolora: `LINKED` = jen provázat (řádky krytého DL se při potvrzení faktury NEmaterializují), `RESTOCKED` = naskladnit i podruhé.

| Sloupec | Typ | NOT NULL | Default | FK / pozn. |
|---|---|---|---|---|
| id | BIGSERIAL | PK | — | |
| goods_receipt_id | BIGINT | ANO | — | → goods_receipts(id) ON DELETE CASCADE (faktura) |
| delivery_note_number | VARCHAR(50) | ANO | — | |
| matched_receipt_id | BIGINT | NE | — | → goods_receipts(id) ON DELETE SET NULL (přijatý DL) |
| resolution | warehouse.dn_ref_resolution ENUM | NE | — | LINKED / RESTOCKED; NULL = nerozhodnuto (blokuje confirm) |
| created_at | TIMESTAMPTZ | ANO | NOW() | |

UNIQUE: `uq_dn_ref (goods_receipt_id, delivery_note_number)`. Index: `idx_dn_refs_matched`.

### warehouse.goods_receipts — doklad (příjemka: faktura / dodací list)
Od V39 nese příjemka i **draft workflow**: import uloží jen hlavičku + kanonický JSONB draft (`draft_payload`); produkty, šarže a pohyby vznikají až potvrzením (CONFIRMED). Hlavičková pole jsou proto do potvrzení nullable — projekce draftu.

| Sloupec | Typ | NOT NULL | Default | FK / pozn. |
|---|---|---|---|---|
| id | BIGSERIAL | PK | — | |
| supplier_id | BIGINT | NE (V39) | — | → suppliers(id) ON DELETE RESTRICT |
| supplier_name_snapshot | VARCHAR(255) | NE (V39) | — | |
| invoice_number | VARCHAR(50) | NE (V39) | — | číslo dokladu: faktury u INVOICE, dodacího listu u DELIVERY_NOTE |
| order_number | VARCHAR(50) | NE | — | |
| original_order_number | VARCHAR(50) | NE | — | |
| issue_date / due_date / taxable_supply_date | DATE | NE | — | DUZP |
| subtotal / vat_amount / total_amount | NUMERIC(12,2) | NE (V39) | — | CHECK: NULL nebo ≥ 0 |
| currency | CHAR(3) | ANO | 'CZK' | |
| document_type | warehouse.document_type ENUM | ANO | 'INVOICE' | V39; volí uživatel při uploadu |
| source_channel | warehouse.receipt_source ENUM | ANO | 'AI_PDF' | V39; kanál vzniku draftu |
| draft_payload | JSONB | NE | — | V39; kanonický draft (řádky, stavy polí, návrhy párování); závazný během PENDING_REVIEW |
| status | warehouse.receipt_status ENUM | ANO | 'PENDING_REVIEW' | |
| reconciliation_ok | BOOLEAN | ANO | FALSE | |
| extraction_model | VARCHAR(100) | NE | — | který AI model extrahoval |
| source_filename | VARCHAR(255) | NE | — | |
| source_pdf | BYTEA | NE | — | originál PDF pro archivaci |
| confirmed_at / rejected_at | TIMESTAMPTZ | NE | — | V39 |
| confirmed_by / rejected_by | BIGINT | NE | — | V39; → security.users(id) ON DELETE SET NULL |
| rejection_note | VARCHAR(500) | NE | — | V39; jen pro REJECTED |
| cancelled_at | TIMESTAMPTZ | NE | — | V43; storno potvrzené příjemky |
| cancelled_by | BIGINT | NE | — | V43; → security.users(id) ON DELETE SET NULL |
| cancellation_note | VARCHAR(500) | NE | — | V43; důvod storna (povinný při stornu) |
| created_at / updated_at | TIMESTAMPTZ | ANO | NOW() | |
| created_by | BIGINT | NE | — | → security.users(id) ON DELETE SET NULL |

CHECK `chk_receipt_confirmed_complete` (V39): CONFIRMED ⇒ supplier_id, invoice_number, subtotal, vat_amount, total_amount NOT NULL.
UNIQUE: od V39 **partial index** `uq_receipt_supplier_docno (supplier_id, invoice_number)`, od V43 s predikátem `WHERE status NOT IN ('REJECTED', 'CANCELLED')` — idempotence importu; zamítnutý i stornovaný doklad uvolní číslo pro opravný import (nahrazuje `uq_receipt_invoice`).
Indexy: `idx_receipts_supplier`, `idx_receipts_issue_date`, `idx_receipts_status`, `idx_receipts_invoice_no`. Trigger: `trg_receipts_updated_at`.

### warehouse.goods_receipt_items — šarže (batch)
| Sloupec | Typ | NOT NULL | Default | FK / pozn. |
|---|---|---|---|---|
| id | BIGSERIAL | PK | — | |
| goods_receipt_id | BIGINT | ANO | — | → goods_receipts(id) ON DELETE CASCADE |
| product_id | BIGINT | ANO | — | → products(id) ON DELETE RESTRICT |
| position | SMALLINT | ANO | 0 | |
| name_snapshot | VARCHAR(500) | ANO | — | |
| quantity_received | NUMERIC(12,3) | ANO | — | CHECK > 0 |
| quantity_remaining | NUMERIC(12,3) | ANO | — | zbytek šarže — drží trigger; CHECK ≥ 0 |
| unit_price_excl_vat | NUMERIC(12,2) | ANO | — | nákupní cena šarže |
| vat_rate | SMALLINT | ANO | — | CHECK 0–100 |
| total_incl_vat | NUMERIC(12,2) | ANO | — | |
| created_at | TIMESTAMPTZ | ANO | NOW() | |

Indexy: `idx_items_receipt`, `idx_items_product`.

### warehouse.stock_takes — inventura (V44, V61)
Soupis k datu; uzavření vygeneruje korekční pohyby. Rozhodnutí R-H (analyza-sklad-2026-07).

| Sloupec | Typ | NOT NULL | Default | FK / pozn. |
|---|---|---|---|---|
| id | BIGSERIAL | PK | — | |
| stock_take_number | VARCHAR(20) | ANO | trigger | V61 — číslo dokladu `INV-{rok}-{4 číslice}`, per rok; UNIQUE `uq_stock_takes_number` |
| status | warehouse.stock_take_status ENUM | ANO | 'OPEN' | |
| note | VARCHAR(500) | NE | — | |
| opened_at | TIMESTAMPTZ | ANO | NOW() | |
| opened_by | BIGINT | NE | — | → security.users(id) ON DELETE SET NULL |
| closed_at / closed_by | TIMESTAMPTZ / BIGINT | NE | — | plní uzavření i zrušení |
| surplus_receipt_id | BIGINT | NE | — | → goods_receipts(id) ON DELETE RESTRICT; pseudo-příjemka přebytků |
| created_at / updated_at | TIMESTAMPTZ | ANO | NOW() | |

Partial UNIQUE: `uq_stock_take_single_open ((status)) WHERE status = 'OPEN'` — **jen jedna otevřená inventura**. Index: `idx_stock_takes_status`. Triggery: `trg_stock_takes_updated_at`; `trg_generate_stock_take_number` (BEFORE INSERT, V61) → `fn_generate_stock_take_number` přidělí `INV-{rok}-{4 číslice}` per rok (advisory lock + MAX+1, vzor V56/V49).

### warehouse.stock_take_items — položky soupisu (V44)
| Sloupec | Typ | NOT NULL | Default | FK / pozn. |
|---|---|---|---|---|
| id | BIGSERIAL | PK | — | |
| stock_take_id | BIGINT | ANO | — | → stock_takes(id) ON DELETE CASCADE |
| product_id | BIGINT | ANO | — | → products(id) ON DELETE RESTRICT |
| expected_quantity | NUMERIC(12,3) | ANO | — | snapshot při otevření — **jen informativní**; rozdíl se počítá proti aktuálnímu stavu |
| counted_quantity | NUMERIC(12,3) | NE | — | **NULL = nepočítáno** (negeneruje korekci), není to nula |
| surplus_unit_price | NUMERIC(12,2) | NE | — | cena pro přebytek; předvyplněna z nejnovější šarže |
| closed_expected_quantity | NUMERIC(12,3) | NE | — | V65 — stav skladu v okamžiku **uzavření**, proti kterému byl rozdíl počítán (NULL = neuzavřeno) |
| closed_difference | NUMERIC(12,3) | NE | — | V65 — zmrazený rozdíl (napočítáno − stav); záporný = manko, kladný = přebytek |
| created_at / updated_at | TIMESTAMPTZ | ANO | NOW() | |

UNIQUE: `uq_stock_take_product (stock_take_id, product_id)`. CHECK: nezápornost všech tří množstevních/cenových sloupců. Index: `idx_stock_take_items_take`. Trigger: `trg_stock_take_items_updated_at`.

**Zmrazení rozdílů při uzavření (V65, audit KN-2).** Rozdíl se u **otevřené** inventury počítá
proti aktuálnímu stavu skladu, ne proti `expected_quantity` — výdej během počítání se nesmí
přepsat (R-H). Jenže `close()` zaúčtuje korekce, které stav srovnají na napočítané množství,
takže **po uzavření** by živý výpočet dal u každého řádku 0 a doklad by nedoložil ani jedno manko.
`StockTakeMapper.materializeDifferences` proto rozdíly zmrazí do `closed_*` **před** zápisem
korekcí (jedna transakce s `close()`); `findItems` pak u uzavřené inventury čte je.
Řádky s `counted_quantity IS NULL` se přeskakují — „nepočítáno" nesmí zvěcnět na doloženou nulu.
Inventury uzavřené před V65 mají `closed_*` NULL a spadnou zpět na původní výpočet
(rekonstrukce nebyla možná — manka jsou v ledgeru jen jako text v poznámce pohybu).

### warehouse.stock_movements — pohybový ledger
| Sloupec | Typ | NOT NULL | Default | FK / pozn. |
|---|---|---|---|---|
| id | BIGSERIAL | PK | — | |
| product_id | BIGINT | ANO | — | → products(id) ON DELETE RESTRICT |
| batch_id | BIGINT | NE | — | → goods_receipt_items(id) ON DELETE RESTRICT |
| movement_type | warehouse.movement_type ENUM | ANO | — | |
| quantity | NUMERIC(12,3) | ANO | — | znaménkové: + příjem / − výdej |
| order_id | BIGINT | NE | — | **bez FK od V87** — zakázku lze smazat a pohyb v append-only ledgeru zůstává; ID se nerecykluje, odkaz je proto jednoznačný i po smazání |
| order_item_id | BIGINT | NE | — | **bez FK** (V83) — položka zakázky, které se pohyb týká |
| return_reason | warehouse.return_reason ENUM | NE | — | CHECK: NOT NULL právě a jen pro RETURN |
| credit_note_number | VARCHAR(50) | NE | — | |
| note | VARCHAR(500) | NE | — | |
| moved_at | TIMESTAMPTZ | ANO | NOW() | |
| created_by | BIGINT | NE | — | → security.users(id) ON DELETE SET NULL |
| created_at | TIMESTAMPTZ | ANO | NOW() | |

CHECK `chk_movement_sign` (finální po V29): RECEIPT > 0 · ISSUE_RETURN > 0 · ADJUSTMENT ≠ 0 · ISSUE, RETURN, WRITE_OFF < 0.
CHECK `chk_movement_order_item` (V83): `order_item_id` smí být vyplněné jen spolu s `order_id`.
Indexy: `idx_mov_product`, `idx_mov_batch`, `idx_mov_order`, `idx_mov_type`, `idx_mov_order_item`.

**`order_item_id` záměrně bez cizího klíče (V83).** Rozlišuje **vydané** položky od pouze
**rezervovaných** — rezervace se z toho odvozuje (položka s vazbou na šarži, na neuzavřené
zakázce, bez výdejového pohybu) a nikam se neukládá, aby vedle ledgeru nevznikl druhý záznam
téhož faktu. FK by musel mít `ON DELETE`, a žádná varianta neprojde: `CASCADE` maže řádky
ledgeru, `SET NULL` vyvolá UPDATE, který shodí `trg_movements_append_only` (V52) a tím rozbije
dnes fungující mazání položky zakázky, `RESTRICT` to mazání zablokuje rovnou. Sloupec proto
nese id jako **údaj**, ne jako hlídaný odkaz — týž princip jako snímky na faktuře (V50).
Sekvence id se v PostgreSQL nerecykluje, odkaz tedy zůstává jednoznačný i po smazání položky.
Celý model popisuje [funkce/rezervace-skladu.md](funkce/rezervace-skladu.md).
Trigger: `trg_apply_stock_movement` (AFTER INSERT) → přičte quantity k `products.quantity_on_hand`; pokud `batch_id` NOT NULL a typ ≠ RECEIPT, přičte quantity i k `goods_receipt_items.quantity_remaining`. Kvantita se odvozuje výhradně z ledgeru.

---

## 6b. Schéma `employee` (V58, V59)

Evidence zaměstnanců servisu (kdo odvedl práci) s hodinovou sazbou = nákladem práce.
Mechanik se přiřazuje k **položce** zakázky typu LABOR (`"order".order_items.employee_id`, V59),
ne k celé zakázce (D-1) — na jednom autě jich může dělat víc, každý svoje hodiny a sazbu.
Sazba se při přiřazení **snímkuje** do `order_items.purchase_price` (D-3, historická přesnost);
`hourly_rate` zde slouží jen k předvyplnění. Zaměstnanec se **nikdy nemaže**, jen deaktivuje
(`is_active = FALSE`, volitelně `left_at`) — položky ho drží přes FK navždy (D-4, R-06).

### employee.employees
| Sloupec | Typ | NOT NULL | Default | FK / pozn. |
|---|---|---|---|---|
| id | BIGSERIAL | PK | — | |
| user_id | BIGINT | NE, UNIQUE | — | → security.users(id) ON UPDATE CASCADE ON DELETE SET NULL; nullable (D-5): zaměstnanec ≠ login |
| first_name | VARCHAR(100) | ANO | — | |
| last_name | VARCHAR(100) | ANO | — | |
| position | VARCHAR(100) | NE | — | pracovní pozice (Automechanik, Diagnostik…) |
| hourly_rate | NUMERIC(10,2) | NE | — | náklad práce; CHECK NULL nebo ≥ 0 |
| hired_at | DATE | ANO | — | datum nástupu |
| left_at | DATE | NE | — | NULL = stále zaměstnán |
| is_active | BOOLEAN | ANO | TRUE | soft-delete (D-4) |
| created_at / updated_at | TIMESTAMPTZ | ANO | NOW() | |
| created_by | BIGINT | NE | — | → security.users(id) ON UPDATE CASCADE ON DELETE SET NULL |

UNIQUE: `uq_employees_user (user_id)` — jeden login = nejvýš jeden zaměstnanec.
CHECK: `chk_employees_hourly_rate` (NULL nebo ≥ 0), `chk_employees_dates` (`left_at` NULL nebo ≥ `hired_at`).
Index: `idx_employees_active (is_active)`. Trigger: `trg_employees_updated_at`.
Seed V58: 4 zaměstnanci (1 napojený na login `mechanic`, 1 odešlý — `is_active = FALSE`).

---

## 6c. Schéma `schedule` (V72, V73)

Plánovací (objednávkový) kalendář. **Objednávka termínu vzniká dřív než zakázka** — zákazník volá
v pondělí, přijede v úterý — a část objednávek se na zakázku nikdy nepromění (nedorazí). Proto
samostatná tabulka, ne sloupce na `"order".orders`: ty by nutily zakládat prázdné zakázky, které
by zkreslily fronty na dashboardu i statistiky.

Jedna tabulka nese **tři druhy událostí** (`entry_type`): objednávku zákazníka (`BOOKING`), blokaci
dílny (`CLOSURE` — svátek, dovolená, revize zvedáku) a událost (`EVENT` — školení, dovolená
zaměstnance; V82). Blokace ani událost nemá zákazníka, vozidlo, kontakt ani zakázku.
Rozdíl vynucují CHECK constrainty, ne aplikační kód.

**Objednávka nemusí mít nikoho** (V85): zákazník i vozidlo jsou volitelné, protože termín se domlouvá
po telefonu dřív, než servis zákazníka i auto zná. Kontakt na zákazníka mimo evidenci nese
`contact_note`. Vybere-li obsluha jen vozidlo, zákazníka **dopočítá service** z jeho majitele
(`vehicles.customer_id` je NOT NULL).

**Neplánuje se na zdroj** (mechanika ani stanoviště) — rozhodnutí uživatele 2026-08-03. Kalendář je
čistě časový; `employee_id` lze doplnit později bez přestavby.

### schedule.appointments
| Sloupec | Typ | NOT NULL | Default | FK / pozn. |
|---|---|---|---|---|
| id | BIGSERIAL | PK | — | |
| entry_type | schedule.appointment_type ENUM | ANO | — | BOOKING / CLOSURE / EVENT (V82) |
| title | VARCHAR(200) | ANO | — | text události v kalendáři |
| note | TEXT | NE | — | |
| starts_at | TIMESTAMPTZ | ANO | — | |
| ends_at | TIMESTAMPTZ | **NE** (V74) | — | NULL = „zákazník nechá auto, konec neznámý“. U CLOSURE i EVENT povinný |
| customer_id | BIGINT | NE | — | → customer.customers(id) ON DELETE RESTRICT; **volitelný i u BOOKING (V85)**; NULL u CLOSURE a EVENT |
| contact_note | VARCHAR(200) | NE | — | V85: jméno a telefon zákazníka mimo evidenci (volný text); jen u BOOKING |
| vehicle_id | BIGINT | NE | — | → vehicle.vehicles(id) ON DELETE RESTRICT; **volitelný i u BOOKING (V85)**; NULL u CLOSURE a EVENT |
| order_id | BIGINT | NE | — | → "order".orders(id) ON DELETE **SET NULL** — objednávka je na zakázce nezávislá a přežije ji |
| employee_id | BIGINT | NE | — | V82: → employee.employees(id) ON DELETE RESTRICT; jen u EVENT (dovolená apod.) |
| status | schedule.appointment_status ENUM | ANO | 'PLANNED' | u CLOSURE a EVENT jen PLANNED/CANCELLED |
| created_at / updated_at | TIMESTAMPTZ | ANO | NOW() | |
| created_by | BIGINT | NE | — | → security.users(id) ON DELETE SET NULL |

CHECK: `chk_appointments_time_range` (V74: `ends_at IS NULL OR ends_at > starts_at`);
`chk_appointments_closure_has_end` (V74: CLOSURE ⟹ `ends_at` NOT NULL);
`chk_appointments_closure_empty` (CLOSURE ⟹ `customer_id`,
`vehicle_id` i `order_id` jsou NULL); `chk_appointments_converted_order` (`status = CONVERTED` ⟹
`order_id` NOT NULL); `chk_appointments_event_empty` (V82: EVENT ⟹ `customer_id`, `vehicle_id` i `order_id`
jsou NULL); `chk_appointments_event_has_end` (V82: EVENT ⟹ `ends_at` NOT NULL);
`chk_appointments_employee_event_only` (V82: `employee_id` NOT NULL ⟹ EVENT);
`chk_appointments_contact_booking_only` (V85: `contact_note` NOT NULL ⟹ BOOKING).
**V85 zrušila** `chk_appointments_booking_customer` (V72) i `chk_appointments_booking_vehicle` (V78) —
objednávka nemusí mít zákazníka ani vozidlo, protože termín se domlouvá dřív, než servis obojí zná.
Čitelná zůstává díky `title`, který je povinný vždy.
UNIQUE: `uq_appointments_order (order_id) WHERE order_id IS NOT NULL` — z jedné objednávky nejvýš jedna zakázka.
Indexy: `idx_appointments_range (starts_at, ends_at)` (hlavní dotaz kalendáře „co je v týdnu od–do"; V76 zrušil predikát `WHERE is_active`),
`idx_appointments_customer`, `idx_appointments_vehicle`.
Trigger: `trg_appointments_updated_at`.
Seed V73 (`db/demo`): 4 objednávky + 1 blokace, **datumy relativní k `NOW()`** — demo nezestárne
a kalendář se po seedu otevře na aktuálním týdnu s daty.

#### schedule.opening_hours (V79)

| sloupec | typ | NOT NULL | default | poznámka |
|---|---|---|---|---|
| day_of_week | SMALLINT | ANO | — | PK; 1 = pondělí … 7 = neděle (ISO-8601) |
| opens_at | TIME | NE | — | NULL + closes_at NULL = zavřeno celý den |
| closes_at | TIME | NE | — | |
| updated_at | TIMESTAMPTZ | ANO | NOW() | trigger `trg_opening_hours_updated_at` |

CHECK: `chk_opening_hours_day` (1–7); `chk_opening_hours_pair` (`(opens_at IS NULL) = (closes_at IS NULL)`
— „otevřeno od sedmi do neznáma" nesmí vzniknout); `chk_opening_hours_range` (`closes_at > opens_at`,
přes půlnoc se neotevírá).
Migrace naseeduje 7 řádků: po–pá 7:00–17:00, víkend zavřeno. **Řádky se jen přepisují**, nikdy
nezakládají ani nemažou — sedm dnů týdne je konstanta.

#### schedule.schedule_settings (V79)

Singleton (`CHECK (id = 1)`, týž vzor jako `billing.company_profile`). Sloupec
`opening_hours_enabled BOOLEAN NOT NULL DEFAULT FALSE` zapíná ohled na otevírací dobu —
dnes znamená „upozorňuj na termín mimo dobu" (uložit lze i tak) a ztlumení zavřených dnů v kalendáři.
**Výchozí vypnuto**, aby migrace nezačala varovat u dat, která nikdo nezkontroloval.

**Dva tvary objednávky, jeden sloupec (V74).** Vyplněný `ends_at` = zákazník počká (od–do);
prázdný = nechá auto a délku opravy nelze před diagnostikou odhadnout. Žádný příznak navíc —
tvar se pozná z jediného údaje, takže si příznak a data nemohou odporovat. Třetí varianta
(„přiveze to ve středu, čas neřešíme“) se vědomě **nezavádí**: čas příjezdu servis vždycky zná,
protože ho musí zákazníkovi sdělit (rozhodnutí uživatele 2026-08-03).

Dotazy na časové okno používají `COALESCE(ends_at, starts_at + INTERVAL '1 second')` — bez toho
by `#{from} < NULL` vrátilo NULL a objednávka bez konce by z kalendáře úplně zmizela.

**Bez soft-delete (V76).** Objednávka má dva konce a nic mezi tím: `status = CANCELLED` (zákazník
nepřijede — zůstává v historii a počítá se do statistik) nebo `DELETE` (záznam vznikl omylem — mizí
úplně). Sloupec `is_active` zrušen: objednávka není doklad, nikdo se na ni zpětně neodvolává a
neodkazuje na ni žádná tabulka, takže deaktivovaný záznam by tu jen ležel. Týž princip jako
u konceptu faktury a dobropisu (§18). Převedenou objednávku (`status = CONVERTED`) smazat nelze —
hlídá to service (422 `APPOINTMENT_CONVERTED_CANNOT_DELETE`), protože jde o význam záznamu,
ne o integritu.

**Číslo dokladu nemá záměrně.** Číselnou řadu dostávají doklady (zákazník, zakázka, faktura, PPD,
dobropis, inventura), ne evidenční záznamy. Objednávka je záměr, který se běžně ruší a maže, a nikdo
se na ni zpětně neodvolává. Až přibude objednávání zákazníkem přes web, přidá se **náhodný
ověřovací kód** (ne souvislá řada — ta by prozradila počet zákazníků).

---

## 7. ENUM typy (finální hodnoty)

| ENUM | Schéma | Hodnoty | Pozn. |
|---|---|---|---|
| customer_type | customer | INDIVIDUAL, COMPANY | |
| address_type | customer | BILLING, CONTACT, HEADQUARTERS | |
| contact_channel | customer | EMAIL, PHONE, SMS, PORTAL | |
| fuel_type | vehicle | PETROL, DIESEL, LPG, CNG, ELECTRIC, HYBRID_PETROL, HYBRID_DIESEL, HYDROGEN, OTHER | Sloupec je od V86 nullable — „bez pohonu" (přívěs) se zapisuje jako NULL, ne jako hodnota ENUMu |
| transmission_type | vehicle | MANUAL, AUTOMATIC, SEMI_AUTOMATIC, CVT, DCT | |
| mileage_source | vehicle | SERVICE, CUSTOMER, INITIAL, OTHER | V20 |
| order_status | "order" | RECEIVED, DIAGNOSIS, WAITING_FOR_PARTS, IN_PROGRESS, READY_FOR_PICKUP, COMPLETED, CANCELLED | |
| order_item_type | "order" | LABOR, MATERIAL, OTHER_SERVICES | V24 redukce z 6 hodnot (DIAGNOSTIC→LABOR; TOWING/RENTAL/OTHER→OTHER_SERVICES) |
| invoice_status | billing | DRAFT, ISSUED, PAID, CANCELLED | DRAFT přidán V17 |
| payment_method | billing | CARD, CASH, TRANSFER, CASH_OR_TRANSFER, CASH_OR_CARD, CARD_OR_TRANSFER | kombinované hodnoty přidány V31 |
| invoice_party_role | billing | SUPPLIER, CUSTOMER | V34 |
| cash_receipt_status | billing | ISSUED, CANCELLED | V68; PPD nemá koncept ani „zaplaceno" — proto vlastní typ, ne recyklovaný `invoice_status` (na rozdíl od `credit_notes`, které fakturační cyklus sdílí) |
| cash_receipt_number_source | billing | MASK, INVOICE, MANUAL | V93; zdroj čísla PPD v dialogu vystavení — řídí jen předvyplnění, zapsat lze vždy libovolné unikátní číslo |
| receipt_status | warehouse | PENDING_REVIEW, CONFIRMED, REJECTED, CANCELLED | CANCELLED přidán V43 — storno potvrzené příjemky kompenzačními pohyby (R-C) |
| document_type | warehouse | INVOICE, DELIVERY_NOTE, STOCK_TAKE | V39; druh dokladu příjemky. STOCK_TAKE přidán V44 — pseudo-příjemka inventurních přebytků (bez dodavatele a částek, CHECK pro ni uvolněn) |
| stock_take_status | warehouse | OPEN, CLOSED, CANCELLED | V44; stav inventury, jen jedna OPEN naráz |
| receipt_source | warehouse | AI_PDF, MANUAL, ISDOC | V39; kanál vzniku draftu. ISDOC zprovozněn v E7 (parser XML → tentýž draft, bez AI) |
| dn_ref_resolution | warehouse | LINKED, RESTOCKED | V41; rozhodnutí u DL reference faktury |
| movement_type | warehouse | RECEIPT, ISSUE, ADJUSTMENT, RETURN, WRITE_OFF, ISSUE_RETURN | ISSUE_RETURN přidán V28 (vratka výdejky, kladný). Kód vytváří: RECEIPT (potvrzení příjemky), ISSUE (výdej do zakázky), ISSUE_RETURN (smazání položky), ADJUSTMENT, WRITE_OFF a RETURN (ruční pohyb, jen záporné — E2.1, vratka E5a). Vratka nese `return_reason` (povinný, CHECK) a volitelné `credit_note_number`; dobropis jako samostatný doklad zatím neexistuje (R-G, fáze E5b) |
| return_reason | warehouse | DEFECTIVE, WRONG_PART, DAMAGED_TRANSPORT, SURPLUS, OTHER | |
| appointment_type | schedule | BOOKING, CLOSURE, EVENT | V72, EVENT od V82; BOOKING = objednávka zákazníka, CLOSURE = zavřená dílna (svátek, revize), EVENT = obecná událost (školení, dovolená zaměstnance) — na rozdíl od CLOSURE neblokuje objednávky |
| appointment_status | schedule | PLANNED, CONVERTED, NO_SHOW, CANCELLED | V72; **V77 redukce z 5 hodnot** — CONFIRMED zrušen (objednávka vzniká po telefonu se zákazníkem, takže je potvrzená hned; dva stavy znamenaly totéž). CONVERTED = z objednávky vznikla zakázka (`order_id` vyplněné) |

---

## 8. Sekvence a generovaná čísla

| Číslo | Formát | Mechanismus |
|---|---|---|
| Zákazník | `ZNK-{rok}-{4č.}` | sekvence `customer.customer_number_seq` (CACHE 1 od V10 — proti mezerám) + trigger V9 |
| Zakázka | `ZAK-{rok}-{4č.}` | trigger `fn_generate_order_number` — per-rok MAX+1 + advisory lock (V56, TD-57; dřív globální sekvence V11 → nereset per rok) |
| Faktura | `YYYYMM{3č.}` | **bez sekvence** — `MAX(...)+1` v rámci měsíce + advisory lock (V15); trigger doplní i `variable_symbol`. Od V49 se přiděluje až při vystavení (DRAFT→ISSUED) z `issue_date`. Od 2026-08-07 je `issue_date` to, které poslala obsluha z dialogu — číslo i datum letí do DB **týmž UPDATE** jako změna stavu (`issueWithNumber`) a obojí vychází z téhož období |
| Dobropis | `OD{YYYYMM}{3č.}` | **bez sekvence** — `MAX(...)+1` v rámci měsíce + advisory lock (V55); přiděluje se při vystavení (DRAFT→ISSUED) |
| Pokladní doklad (PPD) | `PPD{YYYYMM}{3č.}` | **bez sekvence** — `MAX(...)+1` v rámci měsíce + advisory lock (V57); přiděluje se **při INSERT** (PPD nemá koncept) |

Implicitní `*_id_seq` sekvence pro každé BIGSERIAL PK. Po seedech s explicitními ID se volá `setval()` (V3, V8) — pravidlo pro každý budoucí seed.

---

## 9. Views

| View | Migrace | Účel |
|---|---|---|
| `warehouse.v_stock_on_hand` | V18 | stav zásob aktivních produktů (sku, name, unit, quantity_on_hand) — aplikace nečte; určeno pro ad-hoc SQL/ladění (analyza-sklad-2026-07, Z-3) |
| `warehouse.v_batch_provenance` | V18 | dohledatelnost šarže → faktura + objednávka + dodavatel — aplikace nečte; určeno pro ad-hoc SQL/ladění (analyza-sklad-2026-07, Z-3) |
| `"order".v_order_item_priced` | V25 | dopočet cen řádku: line_net, line_vat, line_gross (ROUND po řádku) |
| `"order".v_order_item_summary` | V25, V26, V63 | souhrn za zakázku podle typu: labor_*, material_*, service_* (V26 přejmenováno ze services_*), total_net/gross; V63 přidán náklad bez DPH per kategorie (`labor_cost`, `material_cost`, `service_cost`, `total_cost` = Σ množství×nákupní cena, NULL→0) — podklad pro marži (marže = net − cost, počítá FE) |
| `billing.v_invoice_price_totals` | V32, V67 | souhrn faktury z položek: total_net, total_vat, total_gross + (V67) `rounding` a `total_to_pay` — zaokrouhlení hotovostní úhrady (`payment_method = 'CASH'`) na celé Kč **mimo základ daně** (§36/5 ZDPH; `total_net`/`total_vat` se nemění). `total_to_pay` je jediný zdroj částky pro PDF, QR platbu, PPD, `paid_amount` i sloupec „Celkem k úhradě" v seznamu |
| `billing.v_invoice_vat_summary` | V37 | rekapitulace DPH po sazbách: vat_rate, base, vat, total — stejné zaokrouhlení jako V32, součty sedí |
| `warehouse.v_stock_valuation` | V42 | ocenění zásob per aktivní produkt: `stock_value` = Σ (zbytek šarže × nákupní cena bez DPH), zaokrouhleno po šarži; produkt bez šarží = 0. Čte endpoint `/warehouse/stock-valuation` |

Všechny cenové views zaokrouhlují **po řádku** (`ROUND(qty·price, 2)`) — haléřová konzistence mezi řádky, souhrny i DPH rekapitulací.

---

## 10. Seed data — tři Flyway locations

Migrace jsou rozdělené podle prostředí (viz `konvence.md §14`):
- `db/migration` — schéma (DDL) + nutná infra; běží **všude** (prod, dev, test).
- `db/demo` — demo/ukázková data; **jen dev/local + test** (produkce je nedostane).
- `db/prod` — produkční seed (jeden admin); **jen prod**.

**Demo (`db/demo`, dev/test):**
- **V3:** 5 rolí (`ROLE_ADMIN`, `ROLE_MANAGER`, `ROLE_MECHANIC`, `ROLE_CUSTOMER`, `ROLE_READONLY`), 5 uživatelů (`admin`, `manager`, `mechanic` + portáloví `jan.novak`, `firma.logistika`), 10 zákazníků (ZNK-2025-0001…0010), adresy, kontakty. Sdílené heslo `Password1!` (BCrypt) — **jen dev/test**.
- **V8:** 20 vozidel, 10 zakázek (6× COMPLETED 2025, 4× rozpracované 2026).
- **V13:** 10 položek zakázek (seedováno s před-redukčními ENUM hodnotami; V24 je přemapovala).
- **V16:** 3 faktury pro dokončené zakázky (položky SELECTem z order_items).
- **V46/V47:** deaktivace demo portálových účtů (10/11); posun `customer_number_seq` za 10 seed zákazníků.
- **V58 (demo varianta):** schéma `employee` (DDL) **+ 4 demo zaměstnanci**. Seed zaměstnance #1 má FK `user_id = 3` (mechanik z V3) — proto tato verze závisí na V3 a běží jen v dev/test.

**Nutná infra (`db/migration`, všude — na prázdné prod DB no-op nebo placeholder):**
- **V20/V33/V34/V36:** backfilly (initial km čtení; snapshoty; invoice_party); na prázdné DB no-op.
- **V35:** singleton `billing.company_profile` s placeholderem „DOPLŇTE NÁZEV FIRMY" (nutné i v produkci — nahradit reálnými údaji firmy).

**Produkční seed (`db/prod`, jen prod):**
- **V58 (produkční varianta):** stejné schéma `employee` jako demo V58, ale **BEZ seedu** zaměstnanců. Dvojče demo V58: obě mají číslo 58, ale locations se nepřekrývají (dev vidí demo, prod tuto), takže nekolidují. Nutné, protože V59 (`db/migration`) přidává FK na `employee.employees` i v produkci. (Entanglement DDL+demo v původní V58 = tech dluh TD-65.)
- **V60 `prod_seed`:** 5 rolí + jeden admin (`admin`, heslo z env `ADMIN_PASSWORD_HASH` přes placeholder `${admin_password_hash}`), reset `customer_number_seq` na 1 (první zákazník = ZNK-{rok}-0001). Viz `docs/nasazeni.md`.

---

## 11. Index migrací V1–V70

> Složky (viz §10 a `konvence.md §14`): `db/demo` = V3, V8, V13, V16, V46, V47, V58 (jen dev/test); `db/prod` = V58 (schema-only dvojče) + V60 (jen prod); ostatní `db/migration` (všude). V58 existuje ve dvou variantách se stejným číslem (demo vs schema-only) — locations se nikdy nepřekrývají, takže nekolidují.

| # | Soubor | Popis |
|---|---|---|
| V1 | init_security_schema | security: roles, users, user_roles, token_blacklist, refresh_tokens |
| V2 | init_customer_schema | customer: FTS konfigurace, ENUMy, customers, addresses, contact_persons, communications |
| V3 | seed_initial_data | seed rolí, uživatelů, zákazníků + setval |
| V4 | add_customer_number_sequence | sekvence customer_number_seq |
| V5 | init_vehicle_schema | vehicle: ENUMy, vehicles, trigger |
| V6 | init_order_schema | "order": ENUM order_status, orders, trigger |
| V7 | add_vehicle_year_constraint | CHECK rok výroby ≤ rok 1. registrace |
| V8 | seed_vehicles_and_orders | seed 20 vozidel + 10 zakázek |
| V9 | customer_number_trigger | fn_generate_customer_number (ZNK-) |
| V10 | change_customer_number_seq_cache | CACHE 20 → 1 |
| V11 | order_number_trigger | sekvence + fn_generate_order_number (ZAK-) |
| V12 | init_order_item_schema | order_items, ENUM order_item_type (tehdy 6 hodnot) |
| V13 | seed_order_items | seed položek zakázek 1–3 |
| V14 | init_billing_schema | billing: ENUMy, invoices, invoice_items |
| V15 | invoice_number_trigger | fn_generate_invoice_number (advisory lock) |
| V16 | seed_invoices | 3 faktury pro zakázky 1–3 |
| V17 | add_draft_status_to_invoice | ENUM +DRAFT; default status DRAFT (noAutoCommit) |
| V18 | init_warehouse_schema | warehouse: ENUMy, suppliers, products, goods_receipts, goods_receipt_items, stock_movements, trigger, 2 views |
| V19 | add_vehicle_engine_code | vehicles.engine_code |
| V20 | init_vehicle_mileage_history | mileage_history, ENUM mileage_source, sync trigger |
| V21 | add_product_catalogue_fields | products: +manufacturer, variant, note, sale_price, min_stock_level |
| V22 | change_order_item_name_type | order_items.name → VARCHAR(500) |
| V23 | change_order_item_position_default_value | position DEFAULT 0 → 1 |
| V24 | reduce_order_item_type_enum | redukce order_item_type 6 → 3 hodnoty |
| V25 | order_item_price_views | views v_order_item_priced, v_order_item_summary |
| V26 | rename_summary_service_columns | services_* → service_* ve view |
| V27 | add_goods_receipt_item_id_to_order_item | FK order_items → goods_receipt_items (šarže) |
| V28 | add_issue_return_movement_type | ENUM movement_type +ISSUE_RETURN |
| V29 | allow_issue_return_in_movement_sign_check | nový chk_movement_sign |
| V30 | rename_supplier_identifier_columns | suppliers: ico→registration_number, dic→vat_id |
| V31 | add_invoice_payment_method_type | ENUM payment_method +3 kombinované hodnoty |
| V32 | v_invoice_price_totals | view souhrnů faktury |
| V33 | invoice_customer_order_snapshot | invoices: +customer_name_snapshot, order_number_snapshot |
| V34 | invoice_party_snapshot | tabulka invoice_party + backfill CUSTOMER |
| V35 | company_profile_and_supplier_backfill | company_profile + bank sloupce invoice_party + backfill SUPPLIER |
| V36 | invoice_vehicle_license_plate_snapshot | invoices.vehicle_license_plate_snapshot |
| V37 | v_invoice_vat_summary | view rekapitulace DPH po sazbách |
| V38 | init_vehicle_registry_snapshots | registry_snapshots (STK z dataovozidlech.cz, JSONB), vehicles.stk_valid_until + sync trigger, partial index |
| V39 | receipt_draft_workflow | ENUMy document_type + receipt_source; goods_receipts: +draft_payload (JSONB), +confirmed_*/rejected_*, hlavička nullable, partial unique index místo uq_receipt_invoice, backfill starých PENDING_REVIEW → CONFIRMED |
| V40 | product_identity_supplier_products | pg_trgm; products: +manufacturer_part_number (+generovaný part_number_normalized, indexy); tabulka supplier_products (převodník kódů dodavatelů) + backfill z provenience šarží |
| V41 | receipt_delivery_note_refs | ENUM dn_ref_resolution (LINKED/RESTOCKED); tabulka receipt_delivery_note_refs — DL reference faktur pro ochranu proti dvojímu naskladnění |
| V42 | v_stock_valuation | view ocenění zásob (hodnota skladu ze zbytků šarží a jejich nákupních cen) |
| V43 | receipt_cancellation | ENUM receipt_status +CANCELLED (noAutoCommit); goods_receipts: +cancelled_at/_by, cancellation_note; partial unique index rozšířen o CANCELLED |
| V44 | init_stock_takes | ENUM stock_take_status; document_type +STOCK_TAKE (noAutoCommit); uvolnění chk_receipt_confirmed_complete pro STOCK_TAKE; tabulky stock_takes + stock_take_items (inventura) |
| V45 | widen_refresh_token_for_hash | refresh_tokens.token VARCHAR(36) → VARCHAR(64); refresh tokeny se nově ukládají jako SHA-256 hash (audit K-7, jako blacklist) |
| V46 | remove_portal_seed_accounts | odebrání role ROLE_CUSTOMER a deaktivace seed účtů jan.novak (10) / firma.logistika (11) — portál neexistuje, plochá autorizace by je pustila na celou firmu (audit K-10) |
| V47 | fix_customer_number_sequence_start | setval customer_number_seq nad seed (10 zákazníků, ne 3) — náprava START WITH 4 z V4 (audit N-11) |
| V48 | invoice_order_partial_unique | billing.invoices: plný `uq_invoices_order_id` → částečný unikátní index `uq_invoices_order_active WHERE status <> 'CANCELLED'` — po stornu lze zakázku vyfakturovat znovu (audit K-1/R-1) |
| V49 | invoice_number_on_issue | billing.invoices: `invoice_number`+`variable_symbol` nullable; trigger číslování přesunut z `BEFORE INSERT` na podmíněný `BEFORE UPDATE` (přechod do ISSUED), prefix z `issue_date` (ne CURRENT_DATE), guard proti přetečení >999/měsíc — číslo se přiděluje až při vystavení a sedí s datem dokladu (audit K-3/R-6) |
| V50 | invoice_vehicle_full_snapshot | billing.invoices: +`vehicle_vin_snapshot`, `vehicle_brand_snapshot`, `vehicle_model_snapshot` + backfill — celé vozidlo zmraženo, doklad se zpětně nemění při editaci vozidla (audit K-5); ruší premisu V36, že VIN/značka/model jsou neměnné |
| V51 | invoice_payment_record | billing.invoices: +`paid_at`, `paid_amount`, `paid_method` (nullable) + backfill PAID — evidence úhrady (kdy/kolik/jak), plní se při přechodu na PAID (audit K-9/R-3 var. a) |
| V52 | stock_ledger_integrity | warehouse.stock_movements: `BEFORE UPDATE/DELETE` trigger (append-only, K-13/N-3) + složený FK `(batch_id, product_id)` na `goods_receipt_items(id, product_id)` — pohyb nesmí ukazovat na šarži cizího produktu (N-4) |
| V53 | add_missing_fk_indexes | indexy na `"order".orders (customer_id, vehicle_id)`, `vehicle.vehicles (customer_id)`, `billing.invoice_items (order_item_id)` (audit N-7) |
| V54 | batch_provenance_left_join_supplier | `v_batch_provenance`: INNER→LEFT JOIN na dodavatele + COALESCE na snapshot jména — inventurní šarže (supplier NULL) už z view nemizí (K-14/N-18) |
| V55 | init_credit_notes | billing.credit_notes (opravný daňový doklad §45/K-8/R-7): FK na původní fakturu, correction_reason, vlastní řada `OD{YYYYMM}###` přidělovaná triggerem při vystavení; §45 rozdíly a strany se odvozují z původní faktury |
| V56 | reset_order_number_per_year | `"order".fn_generate_order_number` přepsán na per-rok MAX+1 + advisory lock (vzor faktury V49) — číslo ZAK se resetuje per rok, navazuje na existující data; dropnuta nepoužitá `order_number_seq` (TD-57) |
| V57 | init_cash_receipts | billing.cash_receipts (příjmový pokladní doklad, §11 ZoÚ): FK na fakturu, přijatá částka, vlastní řada `PPD{YYYYMM}###` přidělovaná triggerem **při INSERT** (PPD nemá koncept); účastníci a DPH se odvozují z faktury |
| V58 | init_employee_schema | schéma `employee`: tabulka `employees` (hodinová sazba, nástup/odchod, soft-delete, nullable `user_id`), trigger `updated_at`, index. **Dvě varianty:** `db/demo` (+ seed 4 zaměstnanců, závisí na V3) a `db/prod` (schema-only) — viz §10 |
| V59 | add_order_item_employee | `"order".order_items`: +`employee_id` (FK → employee.employees, ON DELETE RESTRICT) + CHECK `chk_order_items_employee_labor` (jen u LABOR, D-2) + index — vazba mechanika na položku práce, sazba se snímkuje do `purchase_price` (D-3) |
| V60 | prod_seed | **`db/prod` (jen prod):** 5 rolí + jeden admin (`admin`, heslo z env `${admin_password_hash}`), reset `customer_number_seq` na 1 (prázdné `employee.employees` zajišťuje produkční V58) |
| V61 | add_stock_take_number | `warehouse.stock_takes`: +`stock_take_number` (NOT NULL, UNIQUE) — číslo dokladu inventury `INV-{rok}-{4č.}`, per rok, trigger `fn_generate_stock_take_number` (advisory lock + MAX+1, vzor V56/V49); backfill existujících inventur dle `opened_at` |
| V62 | add_vehicle_wheels | `vehicle.vehicles`: +`wheels` (TEXT) — pneu/ráfky per náprava z registru; rozšířen sync trigger `fn_sync_stk_valid_until` (plní i `wheels` z `raw_response->>NapravyPneuRafky`), backfill vozidel se snapshotem; aplikace sloupec nezapisuje |
| V63 | order_item_summary_cost | `CREATE OR REPLACE VIEW "order".v_order_item_summary` — +`labor_cost`/`material_cost`/`service_cost`/`total_cost` (Σ množství×nákupní cena bez DPH, NULL→0); podklad pro zobrazení marže v editaci zakázky |
| V64 | add_user_lock_expiry | `security.users`: +`locked_at` (TIMESTAMPTZ) — razítko uzamčení účtu, od kterého se počítá expirace zámku (`lockout.duration`, výchozí 15 min); `lockAccount` ho plní, `unlockAccount` nuluje, nový guardovaný `unlockIfLockExpired` uvolní prošlý zámek. Backfill `locked_at = NOW()` pro už zamčené účty (bez něj by je guard nikdy neuvolnil). Do V64 byl zámek **trvalý** — audit 2026-07-30, nález KN-5 |
| V65 | stock_take_closed_difference | `warehouse.stock_take_items`: +`closed_expected_quantity`, +`closed_difference` — rozdíly se při uzavření zmrazí (`materializeDifferences` běží **před** korekcemi, v téže transakci); do V65 vykazovala uzavřená inventura samé nuly, protože korekce srovnaly živý stav, proti kterému se rozdíl počítal. Bez backfillu — historické rozdíly už v datech nejsou (manka jsou v ledgeru jen textem v poznámce). Audit 2026-07-30, nález KN-2 |
| V66 | credit_note_original_invoice_unique | `billing.credit_notes`: částečný unikát `uq_credit_notes_original_active (original_invoice_id) WHERE status <> 'CANCELLED'` — jeden aktivní opravný daňový doklad na fakturu (vzor `uq_invoices_order_active`, V48). Do V66 šlo vystavit N plných dobropisů = dvojnásobné snížení daně na výstupu; guard doplněn i v `CreditNoteServiceImpl` kvůli srozumitelné hlášce. Audit 2026-07-30, nález KN-8 |
| V67 | invoice_totals_cash_rounding | `CREATE OR REPLACE VIEW billing.v_invoice_price_totals` — +`rounding`, +`total_to_pay`: zaokrouhlení hotovostní úhrady (`payment_method = 'CASH'`) na celé Kč **mimo základ daně** (§36/5 ZDPH). `total_net`/`total_vat` a rozpis DPH se nemění. Jedno místo výpočtu pro fakturu, PDF, QR platbu, PPD i `paid_amount` — dřív si zaokrouhloval jen PPD a tři doklady říkaly tři částky. Audit 2026-07-30, nálezy KN-7/L-9 |
| V68 | cash_receipt_cancellation | `billing.cash_receipts`: +ENUM `billing.cash_receipt_status` (ISSUED/CANCELLED), +`status`, +`cancelled_at`, +`cancelled_by`, +`cancellation_reason`, CHECK `chk_cash_receipt_cancellation` (důvod storna povinný) a částečný unikát `uq_cash_receipts_invoice_active (invoice_id) WHERE status <> CANCELLED` — **jeden platný pokladní doklad na fakturu** (rozhodnutí uživatele; dvojklik dřív vystavil dva doklady na tutéž hotovost) a storno dokladu vystaveného omylem (doklad se nemaže, §35 ZoÚ — zůstává v řadě a uvolní fakturu pro nový). Audit 2026-07-30, nález KN-7 |
| V69 | invoice_credited_unlocks_order | `billing.invoices`: +`credited_at` (TIMESTAMPTZ) a přestavěný částečný unikát `uq_invoices_order_active … WHERE status <> CANCELLED AND credited_at IS NULL` — **dobropisovaná faktura uvolní zakázku pro novou**. Vlna 2 zamkla storno vystaveného dokladu (KN-1), ale dobropis stav faktury nemění, takže zakázka zůstávala zamčená navždy a doklad na správnou částku už k ní vystavit nešel. Razítko plní `CreditNoteServiceImpl.issue` (koncept dobropisu nic neuvolňuje); backfill pro faktury s už vystaveným dobropisem. Audit 2026-07-30, díra objevená po Vlně 2 |
| V70 | order_mileage_at_intake | `"order".orders`: +`mileage_km_at_intake` (INTEGER, nullable) + CHECK `chk_orders_mileage_at_intake` (0–9 999 999, zrcadlí `vehicle.mileage_history`) — stav tachometru při příjmu vozu jako **snímek pro zakázkový list**. Doklad, který zákazník podepsal, musí být reprodukovatelný i po dalších odečtech, proto snímek a ne odkaz na `vehicle.current_mileage_km`. Odečet do historie vozidla se zapisuje zároveň (`OrderServiceImpl.create`, zdroj SERVICE) — jen při zakládání zakázky. Bez backfillu (hodnotu nelze zpětně poznat). Audit 2026-07-30, nález KN-28 + 07/P-14 |
| V71 | add_invoice_number_mask_and_constraints | **Číslování faktur dle masky** (rozhodnutí uživatele 2026-08-02): `billing.company_profile` +`invoice_number_auto` (BOOLEAN, TRUE) a +`invoice_number_mask` (VARCHAR(40), default `{RRRR}{MM}{NNN}` = historický formát). Číslo faktury nově skládá **aplikace při založení konceptu** (předvyplněné a editovatelné v dialogu), DB generátor z V49 (`trg_invoices_generate_number` + funkce) zrušen. Integritu přebírají constrainty: `invoice_number` → VARCHAR(20), CHECK neprázdnosti, CHECK „ISSUED/PAID má číslo", trigger `trg_invoices_number_immutable` (po vystavení číslo neměnné); `variable_symbol` → VARCHAR(10) + CHECK jen číslice (negeneruje se, vyplňuje uživatel). Backfill: koncepty bez čísla dostaly číslo starým formátem z období `issue_date` |
| V72 | init_schedule_schema | **Plánovací kalendář** (přání zákazníka 2026-08-03): schéma `schedule`, ENUMy `appointment_type` a `appointment_status`, tabulka `appointments`, 4 CHECK constrainty, partial UNIQUE `uq_appointments_order`, 3 indexy, trigger `updated_at`. Jedna tabulka pro objednávky (BOOKING) i blokace dílny (CLOSURE) — rozdíl vynucují CHECKy, ne kód. `order_id` má **SET NULL**: objednávka vzniká před zakázkou a přežije ji. Neplánuje se na mechanika ani stanoviště (rozhodnutí uživatele) |
| V73 | seed_appointments | **`db/demo` (jen dev/test):** 4 objednávky + 1 blokace dílny. Datumy **relativní k `NOW()`** (`date_trunc('day', NOW()) + INTERVAL …`), aby demo nezestárlo — s pevnými datumy by se kalendář po měsíci otevíral prázdný. Odkazy na zákazníka a vozidla přes podvýběr na `license_plate` (obchodní klíč), ne přes natvrdo psaná `id`. Bez `setval()` — `id` se nezadává |
| V74 | appointment_optional_end | `schedule.appointments`: `ends_at` **nullable** + přestavěný `chk_appointments_time_range` (`NULL OR ends_at > starts_at`) + nový `chk_appointments_closure_has_end`. Servis má dva druhy objednávek — „zákazník počká“ (zná se od–do) a „nechá auto“ (konec neznámý). NOT NULL nutil konec vymyslet a kalendář podle smyšleného čísla kreslil délku, která nikde neplatila. Blokace dílny konec mít musí, jinak by zavřela dílnu natrvalo |
| V75 | seed_appointment_open_end | **`db/demo`:** jedna seedovaná objednávka („Příprava na STK“) dostane `ends_at = NULL` — ukázka druhého tvaru z V74. Novou migrací, protože V73 je aplikovaná a její změna by rozešla Flyway checksum (R-09). Cílí přes `title`, ne přes `id` |
| V76 | appointment_hard_delete | `schedule.appointments`: **zrušen `is_active`** + přestavěný `idx_appointments_range` (bez predikátu). Objednávka založená omylem se maže natvrdo — není doklad, nic na ni neodkazuje (nula cizích klíčů) a deaktivovaný záznam by nikdo nikdy nezobrazil. Výjimka z R-06 stejného druhu jako koncept faktury. Zrušení termínu zůstává stavem `CANCELLED`; převedenou objednávku smazat nelze (422, hlídá service) |
| V77 | reduce_appointment_status | `schedule.appointment_status`: **redukce z 5 hodnot na 4** — zrušen `CONFIRMED`, staré hodnoty přemapovány na `PLANNED`. Objednávka vzniká po telefonu se zákazníkem na lince, takže je potvrzená už v okamžiku založení; „Naplánováno" a „Potvrzeno" znamenaly totéž a obsluha musela přemýšlet, které vybrat. Postup jako V24 (přes TEXT), navíc bylo nutné dočasně odebrat DEFAULT a CHECK `chk_appointments_converted_order`, které jsou na typ vázané. Až přibudou SMS připomínky, hodnota se vrátí — přidat do ENUMu je levnější než odebrat (V17) |
| V78 | appointment_vehicle_required | `schedule.appointments`: nový CHECK `chk_appointments_booking_vehicle` — objednávka (BOOKING) musí mít vozidlo. Servis pracuje na autech: objednávka bez vozidla neřekne, co přijede, a zakázka, která z ní vzniká, ho stejně vyžaduje (`orders.vehicle_id` NOT NULL). Vozidlo navíc nese zákazníka (`vehicle.vehicles.customer_id`), takže je informačně silnější než zákazník sám. Objednávky bez vozidla migrace maže — dopočítat auto odhadem by do evidence vneslo nepotvrzený údaj. CLOSURE se nemění, tomu vozidlo `chk_appointments_closure_empty` naopak zakazuje |
| V79 | init_opening_hours | `schedule.opening_hours` (7 řádků, 1 = pondělí … 7 = neděle) a `schedule.schedule_settings` (singleton s `opening_hours_enabled`). Otevírací doba patří ke kalendáři, ne k firemnímu profilu — není to údaj na fakturu, ale provozní pravidlo. Hlídá se jen **příjezd a vyzvednutí**, ne doba mezi nimi: auto přes noc v zavřené dílně stojí běžně a vícedenní opravy (V74) na tom stojí. Výchozí stav vypnutý |
| V80 | customers_blank_strings_to_null | **Datová oprava** `customer.customers`: prázdné řetězce (`''`) v textových sloupcích (`ico`, `dic`, `legal_form`, `primary_phone`, `internal_note`; `first_name`/`last_name` jen u COMPANY, `company_name` jen u INDIVIDUAL — jinak by UPDATE porušil CHECKy) → NULL. FE posílal nevyplněná pole jako `''` a full-replace UPDATE (E1.1) je zapisoval do DB; na produkci pak řádek s `ico = ''` shazoval editaci všech zákazníků bez IČO na `DUPLICATE_ICO` (`uq_customers_ico` povoluje více NULL, ale `''` jen jednou). Kód nově normalizuje blank → NULL v `CustomerConverter`; migrace srovnává stará data |
| V81 | vehicles_blank_strings_to_null | **Datová oprava** `vehicle.vehicles`: `''` → NULL v `license_plate`, `color`, `internal_note` — stejná třída chyby jako V80. `engine_code` v migraci chybí záměrně: `chk_vehicles_engine_code_not_blank` (V19) `''` odmítá, takže se do DB nikdy nedostal — místo toho padalo založení/editace vozidla bez kódu motoru na 422 (ověřeno testem). Kód nově normalizuje blank → NULL ve `VehicleConverter`; `UserDto` navíc dostal `@NotBlank` na e-mail (dřív `@NotNull` + `@Email` propustily `''` až na DB CHECK) |
| V82 | add_calendar_event_type | **Obecná událost v kalendáři** (přání zákazníka 2026-08-04): hodnota `EVENT` do ENUMu `schedule.appointment_type` (vzor V17 — `noAutoCommit` + `COMMIT` před použitím), sloupec `employee_id` (FK → employee.employees, RESTRICT) a tři CHECKy: událost bez zákazníka/vozidla/zakázky, s povinným koncem, zaměstnanec jen u události. Událost na rozdíl od blokace **neblokuje** plánování objednávek — dovolená jednoho mechanika dílnu nezavírá. Vazba na zaměstnance dělá z dovolené evidovaný údaj (jde dohledat, kdo je pryč), ne jen text v názvu |
| V83 | add_order_item_id_to_stock_movements | **Základ rezervačního modelu** (rozprava 2026-08-05, Etapa 1): sloupec `warehouse.stock_movements.order_item_id` + index + CHECK `chk_movement_order_item` (jen spolu s `order_id`). Rozlišuje **vydané** položky zakázky od pouze **rezervovaných** — dosud pohyb nesl jen `order_id`, takže u zakázky s více řádky z různých šarží to nešlo poznat. **Záměrně bez FK**: ledger je append-only (V52) a žádná varianta `ON DELETE` neprojde, aniž by něco rozbila (podrobně §6). Migrace obsahuje **backfill** ve dvou průchodech (přesná shoda zakázka+šarže+množství, pak volnější jen zakázka+šarže), na jeho dobu vypíná `trg_movements_append_only`; bez backfillu by položky dnešních otevřených zakázek vypadaly jako nevydané a dostupné množství by u nich kleslo dvakrát |
| V84 | add_order_id_to_mileage_history | **Vazba odečtu tachometru na zakázku** (Etapa 2, tvrdé mazání): sloupec `vehicle.mileage_history.order_id` + index, FK na `"order".orders` s **ON DELETE CASCADE**. Založení zakázky zapisuje km z příjmu i do historie vozidla (V70), ale spojoval je jen TEXT poznámky „Příjem vozu — zakázka ZAK-…“ — vazba přes řetězec. Zakázku má nově jít smazat, když ji obsluha založila omylem (typicky na špatném voze); pak musí zmizet i odečet, protože v historii cizího auta je to nesmyslný údaj. CASCADE proto, že odečet z příjmu je vlastnictvím té zakázky. Zrušení zakázky odečet NEMAŽE — zrušená zakázka existovala a vůz jí projel. Migrace obsahuje backfill podle textu poznámky (shoda vozidla + zdroj SERVICE + číslo zakázky na konci poznámky) |
| V85 | appointment_optional_customer | **Objednávka bez zákazníka a vozidla** (rozhodnutí uživatele 2026-08-07): ruší `chk_appointments_booking_customer` (V72) i `chk_appointments_booking_vehicle` (V78), přidává `contact_note VARCHAR(200)` + CHECK `chk_appointments_contact_booking_only`. Vynucená vazba byla svazující: termín se domlouvá po telefonu dřív, než servis zákazníka i auto zná („přijedu ve středu, něco to klepe“), a nutila zakládat zákazníka i vozidlo z odhadu — tedy zapsat do evidence údaj, který nikdo nepotvrdil. Zdůvodnění V78 („zakázka vozidlo stejně vyžaduje“) platí dál, ale míří na špatný okamžik: vozidlo je potřeba, až auto přijede a vzniká zakázka. `contact_note` drží jméno a telefon volajícího mimo evidenci, jinak by termín nešlo s nikým přeložit. **Nic se nemaže** — jen uvolnění dvou omezení a nový sloupec |
| V86 | vehicle_optional_fuel_type | **Palivo u vozidla nepovinné** (rozhodnutí uživatele 2026-08-07): `vehicle.vehicles.fuel_type` DROP NOT NULL. Do evidence patří i **přívěsné vozíky** — vozí se na kontrolu brzd a osvětlení jako auta, ale žádný pohon nemají. Povinné pole nutilo obsluhu vybrat `OTHER`, což znamená „jiné palivo", ne „žádné" — zápis nepravdivého údaje. Hodnota `NONE` do ENUMu **nepřibyla záměrně**: NULL už „nevyplněno" vyjadřuje a dvě prázdné hodnoty vedle sebe by musel rozlišovat každý dotaz. Srovnává `fuel_type` s `transmission`, volitelnou od V5. Doprovodná změna v kódu: `JacksonConfig` globálně převádí `''` → NULL u všech enumů (bez toho request padal na `HttpMessageNotReadableException` — FE posílá nevybraný `<select>` jako `''`, což Jackson na enum sám nepřevede). **Nic se nemaže** |
| V87 | drop_stock_movement_order_fk | **Pohyb skladu přežije smazání zakázky.** `fk_mov_order` měl ON DELETE RESTRICT, takže zakázku, ze které odešel materiál, nešlo smazat vůbec — omylem založená zakázka (překlep, špatné auto) s vydaným dílem zůstala v evidenci navždy, i když se materiál vrátil a sklad byl v pořádku. FK se **zahazuje**, nemění na SET NULL: `stock_movements` je append-only (trigger `trg_movements_append_only`, V52), takže SET NULL by jako UPDATE ten trigger odmítl a mazání by spadlo; kaskáda nepřipadá v úvahu, mazat pohyby = přepisovat skladovou historii. Týž vzor a týž důvod jako `order_item_id` ve V83. Integritu drží aplikace: `OrderServiceImpl.delete` nejdřív vrátí veškerý vydaný materiál, takže po smazané zakázce zbývá jen vyrovnaný pár pohybů s nulovým dopadem na zásobu |
| V90 | vehicle_optional_vin_add_serial_number | **Vozidlo bez VIN + výrobní číslo stroje** (rozhodnutí uživatele 2026-08-08): `vehicle.vehicles.vin` DROP NOT NULL, +`machine_serial_number VARCHAR(50)`. Servis opravuje i techniku bez VIN (zahradní traktory, sekačky) — povinný VIN ji z evidence vylučoval. Kopíruje oborový standard (Shopmonkey, Mitchell 1, Fleetio): VIN je klíč pro externí lookupy (registr vozidel), ne podmínka záznamu — bez VIN se jen zakáže „načíst z registru". `uq_vehicles_vin` ani `chk_vehicles_vin_format` se **nemění**: UNIQUE má NULLy navzájem různé, CHECK na NULL projde. Výrobní číslo je **záměrně samostatný sloupec** — má jiný formát i sémantiku než VIN (vzor: Lightspeed DMS, RepairDesk vedou „VIN nebo serial number" jako dvě pole). Viz [funkce/vozidla-bez-vin.md](funkce/vozidla-bez-vin.md). **Nic se nemaže** |
| V91 | add_invoice_purchase_order_number | **Číslo objednávky zákazníka na faktuře** (požadavek zákazníka 2026-08-08): `billing.invoices.purchase_order_number VARCHAR(100)` NULL. Firemní odběratelé fakturu bez svého čísla objednávky neumí spárovat (účtárna ji vrací) — číslo dodává zákazník, obsluha ho jen opíše, proto volný text bez formátového CHECKu. Pojmenování *purchase_order* (ne *order*) záměrně: „order" je v projektu zakázka autoservisu (`order_number_snapshot` = ZAK-…) a „appointment" termín v kalendáři. **Nic se nemaže** |
| V92 | cash_receipt_number_mask | **Číslování pokladních dokladů dle masky** (rozhodnutí uživatele 2026-08-09): `billing.company_profile` +`cash_receipt_number_auto` (BOOLEAN, TRUE), +`cash_receipt_number_mask` (VARCHAR(40), default `PPD{RRRR}{MM}{NNN}` = historický formát), +`cash_receipt_gap_check_enabled` (BOOLEAN, FALSE), +`cash_receipt_gap_check_from` (VARCHAR(20)) — zrcadlo V71+V89 u faktur. Číslo PPD nově skládá **aplikace při vystavení** (předvyplněné a editovatelné v dialogu), DB generátor z V57 (`trg_cash_receipts_generate_number` + funkce) zrušen. Integritu přebírají constrainty: `receipt_number` → VARCHAR(20), CHECK neprázdnosti, trigger `trg_cash_receipts_number_immutable` (číslo neměnné — storno mění jen status). Motivace: obsluha chce řadu PPD řídit sama (doklad půjde smazat a díru zavřít ručním zápisem čísla — navazující změna v aplikaci). Backfill netřeba, `receipt_number` je NOT NULL od V57 |
| V93 | add_cash_receipt_number_source | **Zdroj čísla PPD — režim „podle čísla faktury"** (rozhodnutí uživatele 2026-08-09): nový ENUM `billing.cash_receipt_number_source` (MASK/INVOICE/MANUAL), `billing.company_profile` +`cash_receipt_number_source` (NOT NULL, `'MASK'`), převod z boolean `cash_receipt_number_auto` (FALSE → `'MANUAL'`) a jeho DROP. Majitel čísluje PPD shodně s hrazenou fakturou (přání jeho účetní — párování platby s fakturou zadarmo); vlastní souvislá řada PPD se nevede: hotově se platí jen některé faktury a úplnost hlídá řada faktur. V režimu INVOICE aplikace deaktivuje hlídání mezer PPD (`findNumberGaps` → enabled=false) — „díry" jsou faktury zaplacené převodem, ne chyba |
| V94 | add_order_received_at | **Datum přijetí vozidla na zakázce** (požadavek uživatele 2026-08-09): `"order".orders` +`received_at` (DATE, NOT NULL). Zakázkový list tiskl auditní `created_at`, jenže vůz mohl přijet jindy, než se zakázka zapisuje — uživatel chce datum volit ručně. FE ho předvyplní dneškem, zapsat lze libovolné (i budoucí — bez omezení, rozhodnutí uživatele). Backfill existujících zakázek `(created_at AT TIME ZONE 'Europe/Prague')::date`. Bez DB defaultu záměrně: hodnotu musí vždy dodat aplikace, default by zamaskoval chybějící mapování. **Nic se nemaže** |
| V95 | ceske_komentare_db | **České popisky DB objektů** (překlad komentářů, 2026-08-10): znovu aplikuje všechny `COMMENT ON` z V1–V94 s českými texty — pro existující databáze (nové je dostanou už z přeložených V1–V94). Vygenerováno strojově z přeložených migrací, cíle plně kvalifikované, přeskočen jediný komentář dropnutého sloupce (`cash_receipt_number_auto`, V93). Nemění data ani strukturu. Součást jednorázové výjimky z R-09 — viz konvence.md §1 a docs/preklad-komentaru.md |
| V96 | fix_demo_order_descriptions | **Oprava demo seedů** (db/demo, 2026-08-10): popisy zakázek ZAK-2025-0002 a ZAK-2025-0003 z V8 neodpovídaly položkám z V13 (posun o jednu při psaní seedů). Srovnány popisy podle položek (fakturační komentáře V16 na položky sedí). Jen demo data, produkce db/demo nenačítá |

> **Audit 2026-07-24:** E0 → V45–V47, E1 → V48–V50, E2 → V51, E3 → V52–V54. Refresh tokeny (V45) i blacklist drží jen SHA-256 hash; `SecurityConfig` navíc odřezává `ROLE_CUSTOMER` od `/api/**`.
>
> **Audit 2026-07-30:** Vlna 0 → V64 (expirace zámku účtu, KN-5); Vlna 1 → V65 (zmrazení inventurních rozdílů, KN-2); Vlna 2 → V66 (jeden aktivní dobropis na fakturu, KN-8), V67 (zaokrouhlení hotovosti na jednom místě, KN-7), V68 (jeden platný pokladní doklad na fakturu + jeho storno, KN-7), V69 (dobropis uvolní zakázku pro novou fakturu); Vlna 3 → V70 (stav tachometru při příjmu pro zakázkový list, KN-28 + 07/P-14).

---

## 12. Zvláštnosti a známé kompromisy

- **Snapshot architektura faktur** (V33–V36): faktura je právní doklad — strany (invoice_party), jméno zákazníka, číslo zakázky a SPZ se zmrazí při vystavení. `invoices.customer_name_snapshot` je vědomá denormalizace pro levný výpis; zdroj pravdy je `invoice_party` (řádek CUSTOMER).
- **V17 vyžadovalo `flyway:noAutoCommit` + explicitní COMMIT** — PostgreSQL neumí přidat ENUM hodnotu a použít ji ve stejné transakci.
- **Nekonzistence timestampů v security:** `token_blacklist` a `refresh_tokens` používají `TIMESTAMP` bez TZ, zbytek DB `TIMESTAMPTZ`.
- **CHECK s NULL sémantikou:** `chk_orders_price` a `chk_vehicles_year_registration` při NULL hodnotách prochází — fungují jen jako mez pro vyplněné hodnoty.
- **Redundantní kontrola roku vozidla:** `chk_vehicles_year` (V5) a `chk_vehicles_year_registration` (V7) se částečně překrývají.
- **`ROLE_READONLY`** existuje v seedu rolí (V3), ale nemá přiřazeného uživatele a kód ji nevyužívá.
- **`order` jako název schématu** je rezervované slovo — evidováno jako TD-16 (viz `tech-dluhy.md`).
