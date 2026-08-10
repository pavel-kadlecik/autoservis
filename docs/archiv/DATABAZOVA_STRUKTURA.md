# Autoservis — Dokumentace databázové struktury

> ⚠️ **ARCHIVNÍ DOKUMENT**
> Tento soubor slouží jako podrobná referenční dokumentace a nemusí odrážet aktuální stav projektu.
> **Autoritativní zdroj pravdy je `CLAUDE_SOURCE.md`** (resp. `CLAUDE.md` v kořeni) — stav migrací, modulů a rozhodnutí čti tam.
> Při konfliktu mezi tímto souborem a `CLAUDE_SOURCE.md` má `CLAUDE_SOURCE.md` přednost.
>
> Stav dokumentace: synchronizováno s migracemi **V1–V21** (security, customer, vehicle, order, billing, warehouse).

---

## Přehled schémat

```
PostgreSQL databáze: autoservis
│
├── schema: public
│   ├── flyway_schema_history     (Flyway — správa verzí migrací)
│   └── unaccent                  (extension — odstranění diakritiky pro FTS)
│
├── schema: security              (Autentizace a autorizace — sdílené)
│   ├── users
│   ├── roles
│   ├── user_roles
│   ├── token_blacklist
│   └── refresh_tokens
│
├── schema: customer              (Modul zákazníci)
│   ├── customers
│   ├── addresses
│   ├── contact_persons
│   └── customer_communications
│
├── schema: vehicle               (Modul vozidla)
│   ├── vehicles
│   └── mileage_history
│
├── schema: "order"               (Modul zakázky — pozor: vyhrazené slovo, vždy v uvozovkách)
│   ├── orders
│   └── order_items
│
├── schema: billing               (Modul fakturace)
│   ├── invoices
│   └── invoice_items
│
└── schema: warehouse             (Modul sklad)
    ├── suppliers
    ├── products
    ├── goods_receipts
    ├── goods_receipt_items
    └── stock_movements
        + pohledy: v_stock_on_hand, v_batch_provenance
```

**Migrace:** `security` (V1), `customer` (V2), `vehicle` (V5), `order` (V6), `billing` (V14), `warehouse` (V18). Rozšíření: `engine_code` (V19), `vehicle.mileage_history` (V20), katalogová pole produktů (V21). Cross-schema FK ze všech business schémat cílí na `security.users(id)`.

---

## Schema: `security`

Sdílené schéma pro autentizaci a autorizaci. Referencováno cross-schema FK ze všech ostatních schémat. Obsahuje výhradně data potřebná pro Spring Security a správu JWT relací — žádná business data.

---

### Tabulka `security.roles`

Definice rolí systému. Hodnota sloupce `name` se mapuje přímo na Spring Security `GrantedAuthority`.

| Sloupec | Typ | Null | Výchozí | Popis |
|---|---|---|---|---|
| `id` | SMALLSERIAL | NOT NULL | auto | PK — Java `Short` |
| `name` | VARCHAR(50) | NOT NULL | — | Název role. Formát: `ROLE_{NÁZEV}`. Unikátní. |
| `description` | VARCHAR(255) | NULL | — | Lidsky čitelný popis role. |

**Seed data:** `ROLE_ADMIN`, `ROLE_MANAGER`, `ROLE_MECHANIC`, `ROLE_CUSTOMER`, `ROLE_READONLY`.

---

### Tabulka `security.users`

Autentizační záznamy. Přímé mapování na Spring Security `UserDetails`. Obsahuje **pouze** data potřebná pro přihlášení a správu účtu — žádná business data zákazníka.

| Sloupec | Typ | Null | Výchozí | Popis |
|---|---|---|---|---|
| `id` | BIGSERIAL | NOT NULL | auto | PK — Java `Long`. Referencován ze všech ostatních schémat. |
| `username` | VARCHAR(100) | NOT NULL | — | Přihlašovací jméno. Unikátní. |
| `email` | VARCHAR(255) | NOT NULL | — | Email. Unikátní. Validován CHECK regexem. |
| `password_hash` | VARCHAR(255) | NOT NULL | — | BCrypt hash hesla. **Nikdy plaintext.** |
| `enabled` | BOOLEAN | NOT NULL | TRUE | Účet je aktivní. |
| `account_non_expired` | BOOLEAN | NOT NULL | TRUE | Platnost účtu nevypršela. |
| `account_non_locked` | BOOLEAN | NOT NULL | TRUE | Účet není zamčen. |
| `credentials_non_expired` | BOOLEAN | NOT NULL | TRUE | Platnost hesla nevypršela. |
| `failed_login_attempts` | SMALLINT | NOT NULL | 0 | Počítadlo neúspěšných přihlášení. |
| `last_login_at` | TIMESTAMPTZ | NULL | — | Poslední úspěšné přihlášení. |
| `password_changed_at` | TIMESTAMPTZ | NOT NULL | NOW() | Datum poslední změny hesla. |
| `created_at` | TIMESTAMPTZ | NOT NULL | NOW() | Datum vytvoření. Neměnné. |
| `updated_at` | TIMESTAMPTZ | NOT NULL | NOW() | Automaticky aktualizováno triggerem. |

**Constraints:** `uq_users_username`, `uq_users_email`, `chk_users_email` (regex), `chk_failed_attempts` (`>= 0`).
**Indexy:** `idx_users_email`, `idx_users_username`, `idx_users_enabled` (partial, pouze aktivní účty).

---

### Tabulka `security.user_roles`

Vazební tabulka M:N mezi uživateli a rolemi.

| Sloupec | Typ | Null | Výchozí | Popis |
|---|---|---|---|---|
| `user_id` | BIGINT | NOT NULL | — | FK → `security.users(id)`. CASCADE DELETE. |
| `role_id` | SMALLINT | NOT NULL | — | FK → `security.roles(id)`. RESTRICT DELETE. |
| `assigned_at` | TIMESTAMPTZ | NOT NULL | NOW() | Kdy byla role přiřazena. |
| `assigned_by` | BIGINT | NULL | — | FK → `security.users(id)`. Kdo roli přiřadil. SET NULL. Auditní stopa. |

**PK:** kompozitní `(user_id, role_id)`.

---

### Tabulka `security.token_blacklist`

Blacklist neplatných (odhlášených) JWT access tokenů do jejich přirozené expirace. Periodicky čištěno `BlacklistCleanupService`.

| Sloupec | Typ | Null | Výchozí | Popis |
|---|---|---|---|---|
| `token` | VARCHAR(512) | NOT NULL | — | PK — samotný JWT token. |
| `invalidated_at` | TIMESTAMP | NULL | CURRENT_TIMESTAMP | Kdy byl token zneplatněn. |

---

### Tabulka `security.refresh_tokens`

Server-side úložiště refresh tokenů — umožňuje okamžité zneplatnění bez čekání na expiraci JWT. Jeden uživatel může mít více aktivních tokenů (prohlížeč + mobil). Tokeny se nemažou, pouze revokují — umožňuje detekci útoku opětovným použitím (token reuse).

| Sloupec | Typ | Null | Výchozí | Popis |
|---|---|---|---|---|
| `id` | VARCHAR(36) | NOT NULL | — | PK — UUID jako string. |
| `token` | VARCHAR(36) | NOT NULL | — | Samotný refresh token. Unikátní. |
| `user_id` | BIGINT | NOT NULL | — | FK → `security.users(id)`. CASCADE DELETE. |
| `expires_at` | TIMESTAMP | NOT NULL | — | Platnost tokenu (7 dní). |
| `revoked` | BOOLEAN | NOT NULL | FALSE | TRUE = zneplatněn. |
| `created_at` | TIMESTAMP | NOT NULL | CURRENT_TIMESTAMP | Datum vytvoření. |

**Indexy:** `idx_refresh_tokens_user_id`, `idx_refresh_tokens_revoked` (partial, pouze `revoked = FALSE`).

---

## Schema: `customer`

Business data zákazníků. Zákazník může existovat bez vazby na `security.users` (zákazník bez portálového účtu). Cross-schema FK cílí na `security.users(id)`.

---

### Tabulka `customer.customers`

Ústřední tabulka modulu. Pokrývá dva typy zákazníků přes diskriminační sloupec `customer_type`.

| Sloupec | Typ | Null | Výchozí | Popis |
|---|---|---|---|---|
| `id` | BIGSERIAL | NOT NULL | auto | PK — Java `Long`. |
| `user_id` | BIGINT | NULL | — | FK → `security.users(id)`, SET NULL. UNIQUE. NULL = bez portálového účtu. |
| `customer_type` | ENUM | NOT NULL | INDIVIDUAL | Diskriminátor: `INDIVIDUAL` / `COMPANY`. |
| `customer_number` | VARCHAR(20) | NOT NULL | trigger | Interní číslo zákazníka. Formát `ZNK-{rok}-{4cif}`. Generuje **trigger V9**. Unikátní. |
| `first_name` | VARCHAR(100) | NULL | — | Jméno. Povinné pro `INDIVIDUAL`. |
| `last_name` | VARCHAR(100) | NULL | — | Příjmení. Povinné pro `INDIVIDUAL`. |
| `birth_date` | DATE | NULL | — | Datum narození. Pouze `INDIVIDUAL`. |
| `company_name` | VARCHAR(255) | NULL | — | Název firmy. Povinný pro `COMPANY`. |
| `ico` | VARCHAR(15) | NULL | — | IČO. Unikátní. |
| `dic` | VARCHAR(15) | NULL | — | DIČ. |
| `legal_form` | VARCHAR(100) | NULL | — | Právní forma. |
| `primary_email` | VARCHAR(255) | NULL | — | Primární email. Denormalizace. Validován regexem. |
| `primary_phone` | VARCHAR(30) | NULL | — | Primární telefon. |
| `marketing_consent` | BOOLEAN | NOT NULL | FALSE | Marketingový souhlas (GDPR). |
| `marketing_consent_at` | TIMESTAMPTZ | NULL | — | Kdy udělen/odvolán. |
| `gdpr_consent` | BOOLEAN | NOT NULL | FALSE | GDPR souhlas. |
| `gdpr_consent_at` | TIMESTAMPTZ | NOT NULL | NOW() | Kdy udělen. |
| `preferred_contact_channel` | ENUM | NULL | EMAIL | `EMAIL` / `PHONE` / `SMS` / `PORTAL`. |
| `internal_note` | TEXT | NULL | — | Interní poznámka. Pouze zaměstnanci. |
| `loyalty_points` | INTEGER | NOT NULL | 0 | Body věrnosti. `>= 0`. |
| `is_active` | BOOLEAN | NOT NULL | TRUE | Soft delete. |
| `created_at` | TIMESTAMPTZ | NOT NULL | NOW() | Datum vytvoření. |
| `updated_at` | TIMESTAMPTZ | NOT NULL | NOW() | Trigger. |
| `created_by` | BIGINT | NULL | — | FK → `security.users(id)`, SET NULL. |

**Constraints:** `uq_customers_number`, `uq_customers_ico`, `chk_individual_required`, `chk_company_required`, `chk_customers_email`, `chk_loyalty_points`.
**Indexy:** `idx_customers_user_id`, `idx_customers_type`, `idx_customers_last_name` (partial INDIVIDUAL), `idx_customers_company_name` (partial COMPANY), `idx_customers_ico` (partial), `idx_customers_email` (partial), `idx_customers_active`, `idx_customers_fts` (GIN, konfigurace `customer.czech_simple`).

---

### Tabulka `customer.addresses`

Adresy zákazníka. Více adres různých typů na zákazníka.

| Sloupec | Typ | Null | Výchozí | Popis |
|---|---|---|---|---|
| `id` | BIGSERIAL | NOT NULL | auto | PK. |
| `customer_id` | BIGINT | NOT NULL | — | FK → `customer.customers(id)`. CASCADE DELETE. |
| `address_type` | ENUM | NOT NULL | CONTACT | `BILLING` / `CONTACT` / `HEADQUARTERS`. |
| `is_default` | BOOLEAN | NOT NULL | FALSE | Výchozí adresa typu. Vynuceno partial unique indexem. |
| `street` | VARCHAR(255) | NOT NULL | — | Ulice. |
| `street_number` | VARCHAR(20) | NOT NULL | — | Číslo popisné/orientační. |
| `city` | VARCHAR(100) | NOT NULL | — | Město. |
| `postal_code` | VARCHAR(10) | NOT NULL | — | PSČ. Pro `CZ` validováno `NNN NN`. |
| `country_code` | CHAR(2) | NOT NULL | CZ | ISO 3166-1 alpha-2. |
| `created_at` | TIMESTAMPTZ | NOT NULL | NOW() | — |
| `updated_at` | TIMESTAMPTZ | NOT NULL | NOW() | Trigger. |

**Constraints:** `chk_postal_code` (pro `CZ`).
**Speciální index:** `uq_addresses_default_per_type` — partial unique `(customer_id, address_type) WHERE is_default = TRUE`.

---

### Tabulka `customer.contact_persons`

Kontaktní osoby — primárně firemní zákazníci.

| Sloupec | Typ | Null | Výchozí | Popis |
|---|---|---|---|---|
| `id` | BIGSERIAL | NOT NULL | auto | PK. |
| `customer_id` | BIGINT | NOT NULL | — | FK → `customer.customers(id)`. CASCADE DELETE. |
| `user_id` | BIGINT | NULL | — | FK → `security.users(id)`, SET NULL. UNIQUE. |
| `first_name` | VARCHAR(100) | NOT NULL | — | Jméno. |
| `last_name` | VARCHAR(100) | NOT NULL | — | Příjmení. |
| `position` | VARCHAR(100) | NULL | — | Pracovní pozice. |
| `email` | VARCHAR(255) | NULL | — | Email. Validován regexem. |
| `phone` | VARCHAR(30) | NULL | — | Telefon. |
| `is_primary` | BOOLEAN | NOT NULL | FALSE | Primární osoba. Partial unique index. |
| `is_active` | BOOLEAN | NOT NULL | TRUE | Soft delete. |
| `note` | TEXT | NULL | — | Poznámka. |
| `created_at` | TIMESTAMPTZ | NOT NULL | NOW() | — |
| `updated_at` | TIMESTAMPTZ | NOT NULL | NOW() | Trigger. |

**Speciální index:** `uq_contact_persons_primary` — partial unique `(customer_id) WHERE is_primary = TRUE`.

---

### Tabulka `customer.customer_communications`

Auditní log komunikace se zákazníkem.

| Sloupec | Typ | Null | Výchozí | Popis |
|---|---|---|---|---|
| `id` | BIGSERIAL | NOT NULL | auto | PK. |
| `customer_id` | BIGINT | NOT NULL | — | FK → `customer.customers(id)`. CASCADE DELETE. |
| `channel` | ENUM | NOT NULL | — | `EMAIL` / `PHONE` / `SMS` / `PORTAL`. |
| `direction` | VARCHAR(10) | NOT NULL | — | `INBOUND` / `OUTBOUND` (CHECK). |
| `subject` | VARCHAR(255) | NULL | — | Předmět. |
| `body` | TEXT | NULL | — | Obsah. |
| `handled_by` | BIGINT | NULL | — | FK → `security.users(id)`, SET NULL. |
| `communicated_at` | TIMESTAMPTZ | NOT NULL | NOW() | Kdy proběhla. |
| `created_at` | TIMESTAMPTZ | NOT NULL | NOW() | Kdy zapsáno. |

**Indexy:** `idx_comm_customer_id`, `idx_comm_date` (`communicated_at DESC`).

---

## Schema: `vehicle`

Modul evidence vozidel. Vozidlo má povinnou vazbu na zákazníka (1:N). Phase 1: značka/model jako volný text (číselník plánován v dalších fázích).

---

### Tabulka `vehicle.vehicles`

| Sloupec | Typ | Null | Výchozí | Popis |
|---|---|---|---|---|
| `id` | BIGSERIAL | NOT NULL | auto | PK — Java `Long`. |
| `customer_id` | BIGINT | NOT NULL | — | FK → `customer.customers(id)`. **RESTRICT DELETE** — nelze smazat zákazníka s vozidly. |
| `vin` | VARCHAR(17) | NOT NULL | — | VIN. Unikátní. Validován regexem. |
| `license_plate` | VARCHAR(15) | NULL | — | SPZ/RZ. **Není unikátní** (mění se v čase). |
| `brand` | VARCHAR(100) | NOT NULL | — | Značka. Phase 1: volný text. |
| `model` | VARCHAR(100) | NOT NULL | — | Model. Phase 1: volný text. |
| `year_of_manufacture` | SMALLINT | NULL | — | Rok výroby. CHECK 1885 až rok+1. |
| `first_registration_date` | DATE | NULL | — | Datum první registrace (z TP). |
| `fuel_type` | ENUM | NOT NULL | — | Druh paliva / pohonu. |
| `transmission` | ENUM | NULL | — | Typ převodovky. |
| `engine_displacement_ccm` | INTEGER | NULL | — | Objem v cm³. CHECK 50–10000. NULL pro elektro. |
| `engine_power_kw` | SMALLINT | NULL | — | Výkon v kW. CHECK 1–2000. |
| `engine_code` | VARCHAR(30) | NULL | — | Kód motoru (např. `CAXA`, `N47D20`). **V19.** CHECK: ne prázdný string. |
| `color` | VARCHAR(50) | NULL | — | Barva. |
| `current_mileage_km` | INTEGER | NULL | — | Stav tachometru. **Denormalizace.** CHECK `>= 0`. |
| `internal_note` | TEXT | NULL | — | Interní poznámka. |
| `is_active` | BOOLEAN | NOT NULL | TRUE | Soft delete. |
| `created_at` | TIMESTAMPTZ | NOT NULL | NOW() | — |
| `updated_at` | TIMESTAMPTZ | NOT NULL | NOW() | Trigger. |
| `created_by` | BIGINT | NULL | — | FK → `security.users(id)`, SET NULL. |

**Constraints:** `uq_vehicles_vin`, `chk_vehicles_vin_format` (`^[A-HJ-NPR-Z0-9]{17}$`), `chk_vehicles_year` (1885 – rok+1), `chk_vehicles_displacement` (50–10000), `chk_vehicles_power` (1–2000), `chk_vehicles_mileage` (`>= 0`), `chk_vehicles_year_registration` (**V7** — `year_of_manufacture <= EXTRACT(YEAR FROM first_registration_date)`).
**Indexy:** `idx_vehicles_customer_id`, `idx_vehicles_license_plate` (partial), `idx_vehicles_brand_model` (partial), `idx_vehicles_active` (partial).

---

### Tabulka `vehicle.mileage_history`

Editovatelná kniha stavů tachometru (Phase 3, **V20**). `vehicles.current_mileage_km` je denormalizovaná cache posledního záznamu, udržovaná triggerem.

| Sloupec | Typ | Null | Výchozí | Popis |
|---|---|---|---|---|
| `id` | BIGSERIAL | NOT NULL | auto | PK. |
| `vehicle_id` | BIGINT | NOT NULL | — | FK → `vehicle.vehicles(id)`. CASCADE DELETE. |
| `mileage_km` | INTEGER | NOT NULL | — | Stav tachometru. CHECK 0–9999999. |
| `recorded_date` | DATE | NOT NULL | CURRENT_DATE | Datum odečtu. CHECK ≤ dnes. |
| `source` | ENUM | NOT NULL | OTHER | Zdroj údaje (viz `vehicle.mileage_source`). |
| `note` | TEXT | NULL | — | Poznámka. |
| `created_at` | TIMESTAMPTZ | NOT NULL | NOW() | — |
| `created_by` | BIGINT | NULL | — | FK → `security.users(id)`, SET NULL. |

**Index:** `idx_mileage_history_latest` `(vehicle_id, recorded_date DESC, id DESC)`.
**Trigger:** `trg_mileage_history_sync_current` (AFTER INSERT/UPDATE/DELETE) — přepočítá `vehicles.current_mileage_km` na poslední záznam.

---

## Schema: `"order"`

Modul zakázek (servisních příkazů). **Pozor:** `order` je vyhrazené SQL slovo — název schématu se v SQL **vždy** uvádí v uvozovkách: `"order".orders`.

---

### Tabulka `"order".orders`

Hlavička zakázky — propojuje zákazníka a jeho vozidlo s prováděnou prací.

| Sloupec | Typ | Null | Výchozí | Popis |
|---|---|---|---|---|
| `id` | BIGSERIAL | NOT NULL | auto | PK. |
| `order_number` | VARCHAR | NOT NULL | trigger | Číslo zakázky. Formát `ZAK-{rok}-{4cif}`. Generuje **trigger V11**. Unikátní. |
| `customer_id` | BIGINT | NOT NULL | — | FK → `customer.customers(id)`. |
| `vehicle_id` | BIGINT | NOT NULL | — | FK → `vehicle.vehicles(id)`. |
| `status` | ENUM | NOT NULL | RECEIVED | Stav zakázky (viz ENUM `order_status`). |
| `description` | TEXT | NOT NULL | — | Popis požadované práce / závady. |
| `internal_note` | TEXT | NULL | — | Interní poznámka. |
| `estimated_completion_at` | TIMESTAMPTZ | NULL | — | Odhad dokončení. |
| `completed_at` | TIMESTAMPTZ | NULL | — | Skutečné dokončení. |
| `estimated_price` | NUMERIC | NULL | — | Odhad ceny. CHECK `>= 0`. |
| `final_price` | NUMERIC | NULL | — | Finální cena. CHECK `>= 0`. |
| `is_active` | BOOLEAN | NOT NULL | TRUE | Soft delete. |
| `created_at` | TIMESTAMPTZ | NOT NULL | NOW() | — |
| `updated_at` | TIMESTAMPTZ | NOT NULL | NOW() | Trigger. |
| `created_by` | BIGINT | NULL | — | FK → `security.users(id)`, SET NULL. |

**Constraints:** `uq_orders_number`, `orders_customer_id_fkey`, `orders_vehicle_id_fkey`, `orders_created_by_fkey`, `chk_orders_price`.

---

### Tabulka `"order".order_items`

Položky zakázky — práce, materiál, vedlejší úkony. Tvoří podklad pro fakturu.

| Sloupec | Typ | Null | Výchozí | Popis |
|---|---|---|---|---|
| `id` | BIGSERIAL | NOT NULL | auto | PK. |
| `order_id` | BIGINT | NOT NULL | — | FK → `"order".orders(id)`. CASCADE DELETE. |
| `item_type` | ENUM | NOT NULL | — | Druh položky (viz ENUM `order_item_type`). |
| `name` | VARCHAR(255) | NOT NULL | — | Název položky. |
| `quantity` | NUMERIC(10,2) | NOT NULL | — | Množství. CHECK `> 0`. |
| `unit` | VARCHAR(20) | NOT NULL | — | Jednotka (ks, hod, l…). |
| `purchase_price` | NUMERIC(10,2) | NULL | — | Nákupní cena. CHECK `>= 0`. |
| `unit_price` | NUMERIC(10,2) | NOT NULL | — | Prodejní cena za jednotku. CHECK `>= 0`. |
| `vat_rate` | SMALLINT | NOT NULL | 21 | Sazba DPH v %. CHECK 0–100. |
| `position` | SMALLINT | NOT NULL | 0 | Pořadí na zakázce. CHECK `>= 0`. |
| `note` | TEXT | NULL | — | Poznámka. |
| `created_at` | TIMESTAMPTZ | NOT NULL | NOW() | — |
| `updated_at` | TIMESTAMPTZ | NOT NULL | NOW() | Trigger. |
| `created_by` | BIGINT | NULL | — | FK → `security.users(id)`, SET NULL. |

**Index:** `idx_order_items_order_id`.

---

## Schema: `billing`

Modul fakturace. Z dokončené zakázky vzniká faktura; její položky odkazují zpět na položky zakázky.

---

### Tabulka `billing.invoices`

Hlavička faktury. Vztah k zakázce je 1:1 (`uq_invoices_order_id`).

| Sloupec | Typ | Null | Výchozí | Popis |
|---|---|---|---|---|
| `id` | BIGSERIAL | NOT NULL | auto | PK. |
| `invoice_number` | VARCHAR | NOT NULL | trigger | Číslo faktury. Formát `YYYYMM + 3cif` (např. `202506001`). Generuje **trigger V15** (advisory lock). Unikátní. |
| `order_id` | BIGINT | NOT NULL | — | FK → `"order".orders(id)`. UNIQUE — jedna faktura na zakázku. |
| `customer_id` | BIGINT | NOT NULL | — | FK → `customer.customers(id)`. |
| `issue_date` | DATE | NOT NULL | CURRENT_DATE | Datum vystavení. |
| `due_date` | DATE | NOT NULL | — | Datum splatnosti. CHECK `>= issue_date`. |
| `taxable_supply_date` | DATE | NOT NULL | — | DUZP (datum zdanitelného plnění). |
| `variable_symbol` | VARCHAR | NOT NULL | trigger | VS. Default = `invoice_number` (doplní trigger V15). |
| `constant_symbol` | VARCHAR | NULL | — | KS. |
| `specific_symbol` | VARCHAR | NULL | — | SS. |
| `payment_method` | ENUM | NOT NULL | CASH | `CARD` / `CASH` / `TRANSFER`. |
| `status` | ENUM | NOT NULL | DRAFT | Stav faktury (DRAFT default od **V17**). |
| `note` | TEXT | NULL | — | Poznámka. |
| `created_at` | TIMESTAMPTZ | NOT NULL | NOW() | — |
| `updated_at` | TIMESTAMPTZ | NOT NULL | NOW() | Trigger. |
| `created_by` | BIGINT | NULL | — | FK → `security.users(id)`, SET NULL. |

**Constraints:** `uq_invoices_order_id`, `uq_invoice_number`, `invoices_order_id_fkey`, `invoices_customer_id_fkey`, `chk_due_date`.
**Indexy:** `idx_invoices_customer_id`, `idx_invoices_status`.

---

### Tabulka `billing.invoice_items`

Položky faktury. Každá odkazuje na konkrétní položku zakázky (`order_item_id`) — zamražený snímek pro neměnnost daňového dokladu.

| Sloupec | Typ | Null | Výchozí | Popis |
|---|---|---|---|---|
| `id` | BIGSERIAL | NOT NULL | auto | PK. |
| `invoice_id` | BIGINT | NOT NULL | — | FK → `billing.invoices(id)`. CASCADE DELETE. |
| `order_item_id` | BIGINT | NOT NULL | — | FK → `"order".order_items(id)`. **RESTRICT DELETE.** |
| `name` | VARCHAR | NOT NULL | — | Název položky (snímek). |
| `quantity` | NUMERIC(10,2) | NOT NULL | — | Množství. CHECK `> 0`. |
| `unit` | VARCHAR(20) | NOT NULL | — | Jednotka. |
| `unit_price` | NUMERIC(10,2) | NOT NULL | — | Cena za jednotku. CHECK `>= 0`. |
| `vat_rate` | SMALLINT | NOT NULL | 21 | Sazba DPH. CHECK 0–100. |
| `position` | SMALLINT | NOT NULL | 0 | Pořadí. CHECK `>= 0`. |

**Index:** `idx_invoice_items_invoice_id`.

---

## Schema: `warehouse`

Modul skladu autodílů. Zásoby vznikají z faktur dodavatelů (typicky importovaných z PDF přes AI). Model má tři vrstvy: **doklad** (příjemka), **produkt** (skladová karta) a **pohyb** (kniha příjmů/výdejů = zdroj pravdy o stavu). Zachovává úplnou dohledatelnost každého kusu zpět na číslo faktury a objednávky.

---

### Tabulka `warehouse.suppliers`

Číselník dodavatelů. Deduplikace podle IČO. Cíl vratek a reklamací.

| Sloupec | Typ | Null | Výchozí | Popis |
|---|---|---|---|---|
| `id` | BIGSERIAL | NOT NULL | auto | PK. |
| `name` | VARCHAR(255) | NOT NULL | — | Název dodavatele. |
| `ico` | VARCHAR(15) | NULL | — | IČO. Unikátní. |
| `dic` | VARCHAR(15) | NULL | — | DIČ. |
| `street` / `city` / `postal_code` | VARCHAR | NULL | — | Adresa. |
| `country_code` | CHAR(2) | NOT NULL | CZ | Stát. |
| `bank_account` / `iban` / `swift` | VARCHAR | NULL | — | Bankovní spojení. |
| `email` / `phone` | VARCHAR | NULL | — | Kontakt. |
| `is_active` | BOOLEAN | NOT NULL | TRUE | Soft delete. |
| `created_at` | TIMESTAMPTZ | NOT NULL | NOW() | — |
| `updated_at` | TIMESTAMPTZ | NOT NULL | NOW() | Trigger. |

**Constraints:** `uq_suppliers_ico`. **Index:** `idx_suppliers_name`.

---

### Tabulka `warehouse.products`

Skladová karta = typ dílu. Produkt existuje jednou i při opakovaném naskladnění. Identifikace přes SKU (katalogové číslo dodavatele).

| Sloupec | Typ | Null | Výchozí | Popis |
|---|---|---|---|---|
| `id` | BIGSERIAL | NOT NULL | auto | PK. |
| `sku` | VARCHAR(100) | NOT NULL | — | Katalogové číslo. Unikátní. |
| `name` | VARCHAR(500) | NOT NULL | — | Název dílu. |
| `manufacturer` | VARCHAR(255) | NULL | — | Výrobce / značka. **V21.** |
| `variant` | VARCHAR(255) | NULL | — | Varianta / aplikace (např. „2.0 TDI 2013-2016"). **V21.** |
| `note` | VARCHAR(500) | NULL | — | Poznámka. **V21.** |
| `unit` | VARCHAR(20) | NOT NULL | ks | Skladová jednotka. |
| `default_vat_rate` | SMALLINT | NULL | — | Výchozí sazba DPH. CHECK 0–100. |
| `sale_price` | NUMERIC(12,2) | NULL | — | Prodejní cena bez DPH. Nákupní cena zůstává odvozená ze šarží. **V21.** CHECK `>= 0`. |
| `min_stock_level` | NUMERIC(12,3) | NULL | — | Volitelný práh hlídání. NULL = nehlídá se; nízká zásoba když `quantity_on_hand < min_stock_level`. **V21.** CHECK `>= 0`. |
| `quantity_on_hand` | NUMERIC(12,3) | NOT NULL | 0 | **Denormalizovaný** stav. Pravda = `SUM(stock_movements)`. Udržuje trigger. CHECK `>= 0`. |
| `is_active` | BOOLEAN | NOT NULL | TRUE | Soft delete. |
| `created_at` | TIMESTAMPTZ | NOT NULL | NOW() | — |
| `updated_at` | TIMESTAMPTZ | NOT NULL | NOW() | Trigger. |

**Constraints:** `uq_products_sku`, `chk_products_qty`, `chk_products_vat`, `chk_products_sale_price`, `chk_products_min_stock`. **Indexy:** `idx_products_name`, `idx_products_manufacturer`.

---

### Tabulka `warehouse.goods_receipts`

Příjemka = hlavička jedné PDF faktury dodavatele. Nese číslo faktury i objednávky (klíč k dohledatelnosti a účetnictví).

| Sloupec | Typ | Null | Výchozí | Popis |
|---|---|---|---|---|
| `id` | BIGSERIAL | NOT NULL | auto | PK. |
| `supplier_id` | BIGINT | NOT NULL | — | FK → `warehouse.suppliers(id)`. RESTRICT DELETE. |
| `supplier_name_snapshot` | VARCHAR(255) | NOT NULL | — | Zamražený název dodavatele k datu faktury. |
| `invoice_number` | VARCHAR(50) | NOT NULL | — | Číslo faktury z PDF. |
| `order_number` | VARCHAR(50) | NULL | — | Číslo objednávky z PDF. |
| `original_order_number` | VARCHAR(50) | NULL | — | „Původní číslo obj." z PDF. |
| `issue_date` / `due_date` / `taxable_supply_date` | DATE | NULL | — | Data dokladu (DUZP). |
| `subtotal` | NUMERIC(12,2) | NOT NULL | — | Základ daně. CHECK `>= 0`. |
| `vat_amount` | NUMERIC(12,2) | NOT NULL | — | DPH celkem. CHECK `>= 0`. |
| `total_amount` | NUMERIC(12,2) | NOT NULL | — | K úhradě. CHECK `>= 0`. |
| `currency` | CHAR(3) | NOT NULL | CZK | Měna. |
| `status` | ENUM | NOT NULL | PENDING_REVIEW | Stav AI importu (viz `receipt_status`). |
| `reconciliation_ok` | BOOLEAN | NOT NULL | FALSE | Sedí součet řádků na total? FALSE = ruční kontrola. |
| `extraction_model` | VARCHAR(100) | NULL | — | Jaký AI model extrahoval. |
| `source_filename` | VARCHAR(255) | NULL | — | Název zdrojového souboru. |
| `source_pdf` | BYTEA | NULL | — | Originální PDF pro daňovou archivaci. |
| `created_at` / `updated_at` | TIMESTAMPTZ | NOT NULL | NOW() | `updated_at` trigger. |
| `created_by` | BIGINT | NULL | — | FK → `security.users(id)`, SET NULL. |

**Constraints:** `uq_receipt_invoice` (`(supplier_id, invoice_number)` — idempotence importu), `chk_receipt_totals`, FK na supplier a created_by.
**Indexy:** `idx_receipts_supplier`, `idx_receipts_issue_date`, `idx_receipts_status`, `idx_receipts_invoice_no`.

---

### Tabulka `warehouse.goods_receipt_items`

Řádky příjemky = **šarže** (dávky). Každý řádek je samostatná šarže produktu s vlastní nákupní cenou a vlastním zbývajícím množstvím. Nositel dohledatelnosti (šarže → příjemka → faktura/objednávka) a historie nákupních cen.

| Sloupec | Typ | Null | Výchozí | Popis |
|---|---|---|---|---|
| `id` | BIGSERIAL | NOT NULL | auto | PK. |
| `goods_receipt_id` | BIGINT | NOT NULL | — | FK → `warehouse.goods_receipts(id)`. CASCADE DELETE. |
| `product_id` | BIGINT | NOT NULL | — | FK → `warehouse.products(id)`. RESTRICT DELETE. |
| `position` | SMALLINT | NOT NULL | 0 | Pořadí z PDF. |
| `name_snapshot` | VARCHAR(500) | NOT NULL | — | Název doslova z faktury. |
| `quantity_received` | NUMERIC(12,3) | NOT NULL | — | Kolik přišlo (neměnné). CHECK `> 0`. |
| `quantity_remaining` | NUMERIC(12,3) | NOT NULL | — | Kolik ze šarže zbývá. CHECK `>= 0`. Udržuje trigger. |
| `unit_price_excl_vat` | NUMERIC(12,2) | NOT NULL | — | Nákupní cena této šarže. |
| `vat_rate` | SMALLINT | NOT NULL | — | Sazba DPH. CHECK 0–100. |
| `total_incl_vat` | NUMERIC(12,2) | NOT NULL | — | Celkem s DPH. |
| `created_at` | TIMESTAMPTZ | NOT NULL | NOW() | — |

**Indexy:** `idx_items_receipt`, `idx_items_product`.

---

### Tabulka `warehouse.stock_movements`

Append-only kniha skladových pohybů — **zdroj pravdy o stavu**. Každý příjem, výdej, vratka a korekce je samostatný řádek; needituje se. Pravdivý stav = součet pohybů.

| Sloupec | Typ | Null | Výchozí | Popis |
|---|---|---|---|---|
| `id` | BIGSERIAL | NOT NULL | auto | PK. |
| `product_id` | BIGINT | NOT NULL | — | FK → `warehouse.products(id)`. RESTRICT DELETE. |
| `batch_id` | BIGINT | NULL | — | FK → `warehouse.goods_receipt_items(id)`. RESTRICT. NULL u obecných korekcí. |
| `movement_type` | ENUM | NOT NULL | — | Druh pohybu (viz `movement_type`). |
| `quantity` | NUMERIC(12,3) | NOT NULL | — | Znaménkové množství: + příjem, − výdej. Vázáno na typ přes CHECK. |
| `order_id` | BIGINT | NULL | — | FK → `"order".orders(id)`. RESTRICT. Výdej do zakázky. |
| `return_reason` | ENUM | NULL | — | Důvod vratky — jen a právě u `RETURN`. |
| `credit_note_number` | VARCHAR(50) | NULL | — | Číslo dobropisu. |
| `note` | VARCHAR(500) | NULL | — | Poznámka. |
| `moved_at` | TIMESTAMPTZ | NOT NULL | NOW() | Datum pohybu. |
| `created_by` | BIGINT | NULL | — | FK → `security.users(id)`, SET NULL. |
| `created_at` | TIMESTAMPTZ | NOT NULL | NOW() | — |

**Constraints:** `chk_movement_sign` (znaménko dle typu: RECEIPT > 0, ADJUSTMENT ≠ 0, ISSUE/RETURN/WRITE_OFF < 0), `chk_return_reason` (reason ⇔ typ RETURN), FK na product/batch/order/created_by.
**Indexy:** `idx_mov_product`, `idx_mov_batch`, `idx_mov_order`, `idx_mov_type`.
**Trigger:** `trg_apply_stock_movement` (AFTER INSERT) — automaticky upraví `products.quantity_on_hand` a `goods_receipt_items.quantity_remaining`.

---

### Pohledy (views) schématu `warehouse`

| Pohled | Popis |
|---|---|
| `v_stock_on_hand` | Aktuální stav skladu po produktech (jen aktivní). |
| `v_batch_provenance` | Dohledatelnost: každá šarže se zbývajícím množstvím + původní faktura, objednávka a dodavatel. |

---

## Vztahy mezi tabulkami

```
security.users ──< security.user_roles >── security.roles
      │  │  │
      │  │  └──< security.refresh_tokens
      │  │
      │  └─────── (audit: created_by / handled_by / assigned_by napříč schématy)
      │
      ▼ (user_id 1:1 volitelně)
customer.customers ──┬──< customer.addresses
      │              ├──< customer.contact_persons ──> security.users (user_id)
      │              └──< customer.customer_communications
      │
      └──< vehicle.vehicles                    (RESTRICT — zákazník s vozidly nelze smazat)
                 │
   customer.customers ──┐    ┌── vehicle.vehicles
                        ▼    ▼
                   "order".orders ──< "order".order_items
                        │                    │
                        ▼                    │ (order_item_id, RESTRICT)
                billing.invoices ──< billing.invoice_items
                        ▲
                        │ (1:1, order_id UNIQUE)

warehouse.suppliers ──< warehouse.goods_receipts ──< warehouse.goods_receipt_items >── warehouse.products
                                                              │ (batch_id)                  ▲
                                                              ▼                             │
                                          warehouse.stock_movements ──> "order".orders (order_id)
                                                              └──────────> warehouse.products (product_id)
```

### Klíčová pravidla ON DELETE

| Vztah | Pravidlo | Důvod |
|---|---|---|
| customer → addresses / contact_persons / communications | CASCADE | vlastnictví |
| customer → vehicles | RESTRICT | business vazba — nelze ztratit historii |
| order → order_items | CASCADE | vlastnictví |
| order_items → invoice_items | RESTRICT | daňový doklad nesmí ztratit zdroj |
| invoices → invoice_items | CASCADE | vlastnictví |
| supplier → goods_receipts | RESTRICT | účetní doklad |
| goods_receipts → goods_receipt_items | CASCADE | vlastnictví |
| products / batch / order → stock_movements | RESTRICT | kniha pohybů je neměnná |
| `*.created_by` / `handled_by` → users | SET NULL | auditní pole |

---

## ENUM typy

| ENUM | Hodnoty |
|---|---|
| `customer.customer_type` | INDIVIDUAL, COMPANY |
| `customer.address_type` | BILLING, CONTACT, HEADQUARTERS |
| `customer.contact_channel` | EMAIL, PHONE, SMS, PORTAL |
| `vehicle.fuel_type` | PETROL, DIESEL, LPG, CNG, ELECTRIC, HYBRID_PETROL, HYBRID_DIESEL, HYDROGEN, OTHER |
| `vehicle.transmission_type` | MANUAL, AUTOMATIC, SEMI_AUTOMATIC, CVT, DCT |
| `vehicle.mileage_source` | SERVICE, CUSTOMER, INITIAL, OTHER |
| `"order".order_status` | RECEIVED, DIAGNOSIS, WAITING_FOR_PARTS, IN_PROGRESS, READY_FOR_PICKUP, COMPLETED, CANCELLED |
| `"order".order_item_type` | LABOR, MATERIAL, DIAGNOSTIC, TOWING, RENTAL, OTHER |
| `billing.invoice_status` | DRAFT, ISSUED, PAID, CANCELLED |
| `billing.payment_method` | CARD, CASH, TRANSFER |
| `warehouse.receipt_status` | PENDING_REVIEW, CONFIRMED, REJECTED |
| `warehouse.movement_type` | RECEIPT, ISSUE, ADJUSTMENT, RETURN, WRITE_OFF |
| `warehouse.return_reason` | DEFECTIVE, WRONG_PART, DAMAGED_TRANSPORT, SURPLUS, OTHER |

> Mapování PostgreSQL ENUM ↔ Java přes vlastní `PgEnumTypeHandler` (`setObject(i, value, Types.OTHER)`).

---

## Triggery

Každé schéma má vlastní funkci `fn_set_updated_at()`, která nastavuje `updated_at = NOW()` před UPDATE.

### `updated_at` triggery

| Trigger | Tabulka |
|---|---|
| `trg_users_updated_at` | `security.users` |
| `trg_customers_updated_at` | `customer.customers` |
| `trg_addresses_updated_at` | `customer.addresses` |
| `trg_contact_persons_updated_at` | `customer.contact_persons` |
| `trg_vehicles_updated_at` | `vehicle.vehicles` |
| `trg_orders_updated_at` | `"order".orders` |
| `trg_order_items_updated_at` | `"order".order_items` |
| `trg_invoices_updated_at` | `billing.invoices` |
| `trg_suppliers_updated_at` | `warehouse.suppliers` |
| `trg_products_updated_at` | `warehouse.products` |
| `trg_receipts_updated_at` | `warehouse.goods_receipts` |

### Generátory čísel (BEFORE INSERT)

| Trigger | Tabulka | Funkce | Formát |
|---|---|---|---|
| `trg_generate_customer_number` | `customer.customers` | `fn_generate_customer_number` | `ZNK-{rok}-{4cif}` |
| `trg_generate_order_number` | `"order".orders` | `fn_generate_order_number` | `ZAK-{rok}-{4cif}` |
| `trg_invoices_generate_number` | `billing.invoices` | `fn_generate_invoice_number` | `YYYYMM+3cif`, advisory lock |

### Skladové pohyby (AFTER INSERT)

| Trigger | Tabulka | Funkce | Efekt |
|---|---|---|---|
| `trg_apply_stock_movement` | `warehouse.stock_movements` | `fn_apply_stock_movement` | Aktualizuje `products.quantity_on_hand` a `goods_receipt_items.quantity_remaining` |

### Cache stavu km (AFTER INSERT/UPDATE/DELETE)

| Trigger | Tabulka | Funkce | Efekt |
|---|---|---|---|
| `trg_mileage_history_sync_current` | `vehicle.mileage_history` | `fn_sync_current_mileage` | Přepočítá `vehicles.current_mileage_km` na poslední záznam |

---

## Sekvence

Každá tabulka s `BIGSERIAL`/`SMALLSERIAL` PK má vlastní automatickou sekvenci (`*_id_seq`). Navíc:

| Sekvence | Schéma | Cache | Použití |
|---|---|---|---|
| `customer_number_seq` | customer | 1 | Číslo zákazníka `ZNK-` (vytvořeno V4, CACHE sníženo na 1 v **V10** kvůli mezerám). |
| `order_number_seq` | "order" | 1 | Číslo zakázky `ZAK-` (vytvořeno V11, START 11). |

> Čísla **faktur** nepoužívají sekvenci — generují se přes `MAX(...) + 1` v rámci měsíce, chráněné advisory lockem (V15).

---

## Migrační historie

| Verze | Soubor | Popis |
|---|---|---|
| V1 | `V1__init_security_schema.sql` | security: users, roles, user_roles, token_blacklist, refresh_tokens |
| V2 | `V2__init_customer_schema.sql` | customer: customers, addresses, contact_persons, communications + FTS |
| V3 | `V3__seed_initial_data.sql` | Seed: role, uživatelé, ukázkoví zákazníci |
| V4 | `V4__add_customer_number_sequence.sql` | Sekvence `customer_number_seq` |
| V5 | `V5__init_vehicle_schema.sql` | vehicle: vehicles, ENUMy fuel_type + transmission_type, trigger |
| V6 | `V6__init_order_schema.sql` | order: schéma, orders, ENUM order_status, trigger |
| V7 | `V7__add_vehicle_year_constraint.sql` | CHECK: rok výroby ≤ rok první registrace |
| V8 | `V8__seed_vehicles_and_orders.sql` | Seed: 20 vozidel + 10 zakázek |
| V9 | `V9__customer_number_trigger.sql` | Trigger `fn_generate_customer_number()` — prefix `ZNK-` |
| V10 | `V10__change_customer_number_seq_cache.sql` | ALTER SEQUENCE customer_number_seq CACHE 1 |
| V11 | `V11__order_number_trigger.sql` | Sekvence + trigger `fn_generate_order_number()` — prefix `ZAK-` |
| V12 | `V12__init_order_item_schema.sql` | order: order_items, ENUM order_item_type |
| V13 | `V13__seed_order_items.sql` | Seed: položky zakázek |
| V14 | `V14__init_billing_schema.sql` | billing: invoices, invoice_items, ENUMy |
| V15 | `V15__invoice_number_trigger.sql` | Trigger `fn_generate_invoice_number()` — advisory lock |
| V16 | `V16__seed_invoices.sql` | Seed: faktury pro zakázky 1–3 |
| V17 | `V17__add_draft_status_to_invoice.sql` | Přidání `DRAFT` do invoice_status, nový default |
| V18 | `V18__init_warehouse_schema.sql` | warehouse: suppliers, products, goods_receipts, goods_receipt_items, stock_movements, 3 ENUMy, 2 views, trigger pohybů |
| V19 | `V19__add_vehicle_engine_code.sql` | vehicle: sloupec `engine_code` |
| V20 | `V20__init_vehicle_mileage_history.sql` | vehicle: `mileage_history`, ENUM `mileage_source`, trigger cache km |
| V21 | `V21__add_product_catalogue_fields.sql` | warehouse: katalogová pole produktů (manufacturer, variant, note, sale_price, min_stock_level) |
