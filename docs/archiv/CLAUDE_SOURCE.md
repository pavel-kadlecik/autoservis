# CLAUDE_SOURCE.md — Autoservis

> **Primárný zdroj pravdy pro AI asistenty.** Čti vždy na začátku chatu.
> Při konfliktu s `docs/*.md` má tento soubor přednost.
> Aktualizuj při každé zásadní změně architektury nebo stavu projektu.
> Poslední aktualizace: **CRUD dodavatelů (RUD) + normalizace IČO hotové end-to-end.** Backend + frontend modul `/suppliers` kompletní (viz sekce 8). Normalizace registračního čísla proti duplicitám: `SupplierNormalizer` (`@Component`, trim + všechny mezery vč. `\u00A0`, prázdné→null) zapojený na obě brány — import (`resolveSupplier`, navíc guard clause místo „Neznámého dodavatele“) i ruční editace (`SupplierServiceImpl.update`). Bez ručního Create — dodavatel vzniká jen importem faktury.

---

## 1. PRAVIDLA — Skenuj první, vždy dodržuj

### Co VŽDY dělat

| # | Pravidlo |
|---|---|
| R-01 | SQL **výhradně v XML souborech** — anotace `@Select` / `@Insert` se nepoužívají |
| R-02 | Tabulky v XML vždy **plně kvalifikované**: `security.users`, `customer.customers` |
| R-03 | Service metoda po INSERT/UPDATE **vždy vrátí objekt znovu načtený z DB** |
| R-04 | Auditní pole (`created_by`, `handled_by`) **doplní server** z `@AuthenticationPrincipal` |
| R-05 | Po seed datech s explicitními ID vždy volat `setval()` pro synchronizaci sekvence |
| R-06 | Záznamy se **nemažou** — soft-delete přes `is_active = false` |
| R-07 | `updated_at` aktualizuje **databázový trigger**, ne aplikace |
| R-08 | `if` bloky mají **vždy složené závorky**, i jednořádkové |
| R-09 | Hotová migrace se **nikdy nemění** — změna = nový soubor s vyšším číslem |
| R-10 | Nové moduly: **strict** `findById` (`WHERE id = ? AND is_active = TRUE`) |
| R-11 | Mapování domain↔DTO přes ruční `@Component` konvertory (`model/converter/`) — MapStruct se nepoužívá |
| R-12 | Dead code **smazat** — "možná se bude hodit" není důvod ponechat |
| R-13 | Business validaci (FK, unique) provádět v **service vrstvě** → čistá `BusinessRuleException` |
| R-14 | `id` patří do **URL** (path variable), ne do těla requestu — detail viz `CODE_CONVENTIONS.md` sekce 15 |
| R-15 | **Soubory projektu se čtou/editují VÝHRADNĚ přes filesystem konektor.** Bash prostředí soubory projektu **nevidí** (jiný mount) — `grep`/`ls`/`cat`/`find` přes bash vrací prázdno nebo "No such file" a **nesmí** se použít k ověření existence/obsahu. Hledání v obsahu (např. všechny výskyty `getIco`) řeš čtením souborů přes konektor, ne bashem. |

### Co NIKDY nedělat

| # | Zákaz |
|---|---|
| N-01 | **Nikdy `null`** ze service — buď `Optional<X>`, nebo `ResourceNotFoundException` |
| N-02 | **Nikdy UUID** jako PK — projekt používá `BIGSERIAL` / `Long` |
| N-03 | **Nikdy `hikari.connection-init-sql`** — způsobuje selhání startu s Flyway |
| N-04 | **Nikdy `flyway.schemas`** v konfiguraci — schémata spravují migrace přes `CREATE SCHEMA` |
| N-05 | **Nikdy SQL v Java anotacích** (`@Select` atp.) |
| N-06 | **Nikdy `createdBy` přes DTO** — výhradně server-side ze SecurityContext |
| N-07 | **Nikdy query parametr** jako primární identifikátor resource — správně: path variable |
| N-08 | **Nikdy JPA/Hibernate** — persistence výhradně přes MyBatis |
| N-09 | **Nikdy plaintext heslo** v DB — vždy BCrypt hash |
| N-10 | **Nikdy měnit** verzovanou Flyway migraci úspěšně aplikovanou do DB |
| N-11 | **Nikdy `formData.id`** v URL PUT callu — používej `id` z `useParams()` |

> 📖 **Detailní příklady kódu** ke všem pravidlům: `docs/CODE_CONVENTIONS.md`

---

## 2. Projekt

**Autoservis** — webová aplikace pro správu autoservisu (zákazníci, vozidla, zakázky, fakturace).
JWT autentizace s rolemi a refresh token rotací.
**Výuková aplikace** — každý krok se zavede správně nebo vůbec.

---

## 3. Technologický stack

| Vrstva | Technologie | Verze |
|---|---|---|
| Java | Java + Spring Boot | 21 + 4.0.3 |
| Persistence | MyBatis (XML only) | mybatis-spring-boot-starter 4.0.1 |
| Databáze | PostgreSQL | 16+ |
| Migrace | Flyway | 11+ |
| Bezpečnost | Spring Security + jjwt | 7.0.3 + 0.13.0 |
| Lombok | Lombok | 1.18+ |
| Frontend | React + Vite + Bootstrap | 19 + 8 + 5.3 |

---

## 4. Databáze

```
public     → pouze flyway_schema_history
security   → users, roles, user_roles, refresh_tokens, token_blacklist
customer   → customers, addresses, contact_persons, customer_communications
vehicle    → vehicles, mileage_history
order      → orders, order_items
billing    → invoices, invoice_items
warehouse  → goods_receipts, goods_receipt_items, products, stock_movements
```

| Rozhodnutí | Pravidlo |
|---|---|
| Primární klíče | `BIGSERIAL` v DB, `Long` v Javě. UUID se nepoužívá |
| Cizí klíče | typ `BIGINT` |
| Časová razítka | `TIMESTAMPTZ` v DB, `OffsetDateTime` v Javě |
| Cross-schema FK | záměrné — `customer.customers.created_by → security.users(id)` |
| ON DELETE vlastnictví | `CASCADE` — customer → addresses |
| ON DELETE business vazba | `RESTRICT` — customer → vehicles |
| ON DELETE auditní pole | `SET NULL` — users → vehicles.created_by |
| PostgreSQL ENUM | vlastní `PgEnumTypeHandler`, zápis: `setObject(i, value, Types.OTHER)` |

> 📖 **Detailní schéma všech tabulek**: `docs/DATABAZOVA_STRUKTURA.md` (archivní)

---

## 5. Struktura projektu

```
autoservis/
├── CLAUDE.md
├── README.md
├── docs/
│   ├── CLAUDE_SOURCE.md             ← tento soubor (autoritativní pro AI)
│   ├── CODE_CONVENTIONS.md          ← detailní konvence kódu s příklady
│   ├── TECH-DLUHY.md                ← technické dluhy a otevřené úkoly
│   ├── ROZVOJOVA-MAPA.md            ← roadmap a otevřená rozhodnutí
│   ├── DATABAZOVA_STRUKTURA.md      ← archivní DB dokumentace
│   └── ROZHODNUTI_A_KONVENCE.md     ← archivní rozhodnutí
└── src/main/java/cz/palo/autoservis/
    ├── config/mybatis/              ← PgEnumTypeHandler
    ├── config/security/             ← SecurityConfig
    ├── controller/                  ← CustomerController, VehicleController, MileageController, OrderController,
    │                                   OrderItemController, InvoiceController, WarehouseProductController,
    │                                   GoodsReceiptImportController
    ├── exception/                   ← ResourceNotFoundException, BusinessRuleException, GlobalExceptionHandler
    ├── mapper/                      ← MyBatis mapper interfacy
    ├── model/converter/             ← @Component konvertory (domain ↔ DTO)
    ├── model/domain/                ← čisté POJO (bez JPA)
    ├── model/dto/                   ← API kontrakty (namespace pattern)
    ├── model/enums/                 ← FuelType, TransmissionType, OrderStatus, InvoiceStatus, MovementType, MileageSource, ...
    ├── security/                    ← JWT filtr, AppUserDetails, AuthController, JwtService
    └── service/                     ← CustomerService, VehicleService, MileageService, OrderService,
                                        OrderItemService, InvoiceService, ProductService,
                                        WarehouseImportService
```

Vrstvení: `Controller → Service → Mapper interface → XML mapper → PostgreSQL`
SQL je výhradně v `src/main/resources/mapper/**/*.xml`.

---

## 6. Autentizace

- **Access token** — 8h dev / 15 min prod, HTTP-only cookie. **Refresh token** — 7 dní, uložen v DB tabulce `security.refresh_tokens`.
- `AppUserDetails implements UserDetails` — nese `userId: Long` pro `@AuthenticationPrincipal`.
- `AppUserDetailsService` načítá uživatele jedním JOIN dotazem (users + roles). Žádný N+1.
- Token blacklist: `security.token_blacklist`, periodický cleanup přes `BlacklistCleanupService`.

---

## 7. Flyway migrace

| Soubor | Obsah | Stav |
|---|---|---|
| `V1__init_security_schema.sql` | security: users, roles, user_roles, refresh_tokens, token_blacklist | ✅ |
| `V2__init_customer_schema.sql` | customer: customers, addresses, contact_persons, communications | ✅ |
| `V3__seed_initial_data.sql` | Seed: role, testovací zákazníci a uživatelé | ✅ |
| `V4__add_customer_number_sequence.sql` | Sekvence `customer_number_seq` | ✅ |
| `V5__init_vehicle_schema.sql` | vehicle: tabulka vehicles, ENUMy fuel_type + transmission_type, trigger | ✅ |
| `V6__init_order_schema.sql` | order: schéma + tabulka orders + ENUM order_status + trigger | ✅ |
| `V7__add_vehicle_year_constraint.sql` | CHECK: rok výroby <= rok první registrace | ✅ |
| `V8__seed_vehicles_and_orders.sql` | Seed: 20 vozidel + 10 zakázek | ✅ |
| `V9__customer_number_trigger.sql` | Trigger fn_generate_customer_number() — prefix ZNK- | ✅ |
| `V10__change_customer_number_seq_cache.sql` | ALTER SEQUENCE customer_number_seq CACHE 1 | ✅ |
| `V11__order_number_trigger.sql` | Sekvence + trigger fn_generate_order_number() — prefix ZAK- | ✅ |
| `V12__init_order_item_schema.sql` | order: tabulka order_items, ENUM order_item_type | ✅ |
| `V13__seed_order_items.sql` | Seed: položky zakázek | ✅ |
| `V14__init_billing_schema.sql` | billing: schéma, invoices, invoice_items, ENUMy | ✅ |
| `V15__invoice_number_trigger.sql` | Trigger fn_generate_invoice_number() — advisory lock | ✅ |
| `V16__seed_invoices.sql` | Seed: faktury pro zakázky 1–3 | ✅ |
| `V17__add_draft_status_to_invoice.sql` | Přidání DRAFT do invoice_status ENUM | ✅ |
| `V18__init_warehouse_schema.sql` | warehouse: schéma, goods_receipts, goods_receipt_items, products, stock_movements, ENUMy (receipt_status, movement_type, return_reason), trigger | ✅ |
| `V19__add_vehicle_engine_code.sql` | vehicle: sloupec `engine_code` | ✅ |
| `V20__init_vehicle_mileage_history.sql` | vehicle: `mileage_history`, ENUM `mileage_source`, trigger cache km | ✅ |
| `V21__add_product_catalogue_fields.sql` | warehouse: katalogová pole produktů (manufacturer, variant, note, sale_price, min_stock_level) | ✅ |
| `V22__change_order_item_name_type.sql` | order: rozšíření `order_items.name` na VARCHAR(500) (aby se vešel `name_snapshot` šarže) | ✅ |
| `V23__change_order_item_position_default_value.sql` | order: default `order_items.position` sjednocen na 1-based | ✅ |
| `V24__reduce_order_item_type_enum.sql` | order: redukce ENUM `order_item_type` | ✅ |
| `V25__order_item_price_views.sql` | order: pohledy pro cenové souhrny položek | ✅ |
| `V26__rename_summary_service_columns.sql` | order: přejmenování sloupců v summary pohledu | ✅ |
| `V27__add_goods_receipt_item_id_to_order_item.sql` | order: FK `order_items.goods_receipt_item_id → warehouse.goods_receipt_items` (ON DELETE RESTRICT) — vazba položky na šarži | ✅ |
| `V28__add_issue_return_movement_type.sql` | warehouse: přidání hodnoty `ISSUE_RETURN` do `movement_type` (návrat ze zakázky na sklad, kladný) | ✅ |
| `V29__allow_issue_return_in_movement_sign_check.sql` | warehouse: úprava `chk_movement_sign` — `ISSUE_RETURN` na kladné straně | ✅ |
| `V30__rename_supplier_identifier_columns.sql` | warehouse: `suppliers` — `ico`→`registration_number` VARCHAR(30), `dic`→`vat_id` VARCHAR(20), rename constraintu, komentáře | ✅ |

---

## 8. Aktuální stav projektu

| Modul | DB migrace | Java vrstva | Frontend | Status |
|---|---|---|---|---|
| Autentizace (JWT) | ✅ V1 | ✅ | ✅ LoginPage | Funkční end-to-end |
| Zákazníci | ✅ V2–V4, V9, V10 | ✅ | ✅ List, Detail, Create, Edit | Funkční end-to-end |
| Vozidla (+ historie km) | ✅ V5, V7, V8, V19, V20 | ✅ | ✅ List, Detail, Create, Edit, km | Funkční end-to-end |
| Zakázky | ✅ V6, V8, V11 | ✅ | ✅ List, Create, Edit + položky | Funkční end-to-end |
| Položky zakázek | ✅ V12, V13, V22–V27 | ✅ | ✅ modal (přidat/edit) + import ze skladu | Funkční; import z faktury, mazání i editace se skladovými pohyby |
| Fakturace | ✅ V14–V17 | ✅ | ❌ plánováno | Backend hotový, frontend chybí |
| Sklad | ✅ V18, V21, V28–V30 | ✅ produkty + import na zakázku | 🔄 přehled, detail, CRUD, import faktury (PDF) | Produkty + import faktury na zakázku i upload PDF faktury dodavatele hotové |
| Dodavatelé | ✅ V18, V30 | ✅ RUD (bez Create) | ✅ List, Detail, Edit | Funkční end-to-end; dodavatel vzniká jen importem faktury, RUD pro správu; navigace jako podpoložka pod Skladem |
| Dashboard | — | — | ❌ plánováno | Prázdná stránka |

### Nejbližší krok

Sklad — dodavatelé a workflow příjemek (import na zakázku i upload PDF faktury je hotový):
1. ✅ **(hotovo) CRUD dodavatelů — RUD (Read, Update, Deactivate), BEZ ručního Create.** Import PDF faktury je jediná brána, jak dodavatel vznikne (`WarehouseImportServiceImpl`: najdi podle registračního čísla, nebo založ). Backend: `SupplierMapper`+XML (findById lenient, search, countSearch, update s dynamickým `<set>`, existsByRegistrationNumber s `id != #{id}`, deactivate, activate), `SupplierDto` (DetailResponse/UpdateRequest/ListResponse + Bean Validation), `SupplierConverter`, `SupplierService`+impl (getPage, getById, update s kontrolou duplicity `DUPLICATE_REGISTRATION_NUMBER`, deactivate, activate; vše `@Transactional` na mutacích), `SupplierController` (`/api/v1/warehouse/suppliers`, DELETE=deactivate, POST /{id}/activate). Frontend modul `/suppliers`: `SuppliersPage` (list, debounce filtr, `activeOnly`), `SuppliersPageDetail`, `SuppliersPageEdit`, `SupplierTable`, `SupplierForm`, `useSupplierRowActions`; routy v `App.jsx`, navigace jako podpoložka pod „Sklad“ v `Sidebar.jsx`. `deactivate`/`activate` implementováno na všech vrstvách (využití v UI k archivaci starých dodavatelů zatím otevřené rozhodnutí).
2. ✅ **(hotovo) Normalizace registračního čísla (IČO) proti duplicitám z importu.** `SupplierNormalizer` (`@Component` v `service/`, metoda `normalizeRegistrationNumber`): trim, odstranění všech mezer včetně nezlomitelné `\u00A0` (`replaceAll("[\\s\\u00A0]", "")`), prázdný string → null. Prefix CZ/SK se NEusekává. Zapojeno na OBě brány: (a) import — `WarehouseImportServiceImpl.resolveSupplier` normalizuje do proměnné `rn` na začátku, hledá (`findSupplierIdByIco(rn)`) i ukládá (`.registrationNumber(rn)`) stejnou hodnotu; (b) ruční editace — `SupplierServiceImpl.update` normalizuje `updateRequest.getRegistrationNumber()` na vstupu (přepsáno zpět do requestu), pak čistá hodnota v kontrole duplicity i `applyUpdate`. **Navíc:** `resolveSupplier` dostal guard clause (`s == null || s.name() == null` → `BusinessRuleException` "SUPPLIER_NOT_EXTRACTED") — import se zastaví s rollbackem místo tichého zakládání „Neznámého dodavatele“ (ten fallback odstraněn i z `buildReceipt`). Kontroluje se `name` (NOT NULL v DB), ne `ico` (nullable). Známé omezení: Java normalizace nechrání zápisy mimo aplikaci (SQL/psql) — pro současný stav OK. **Zvažováno a odloženo:** interaktivní import (uživatel potvrzuje/vybírá dodavatele) a ruční zakládání dodavatele — naráží na `goods_receipts.supplier_id NOT NULL`, velká přestavba kvůli okrajovému problému; k tomu se vrátíme jen pokud normalizace nebude stačit.
3. ⏳ **TODO — typ dokladu (faktura vs. dodací list) + dopočet DPH u dodacího listu.** Problém: dodací list (např. LKQ, „Není daňový doklad“) nemá rozpis DPH — má jen „Celkem bez DPH“. Import spadne na `goods_receipts.vat_amount NOT NULL` (a `goods_receipt_items.vat_rate NOT NULL`). **Rozhodnuto:** (a) typ dokladu určí **uživatel při uploadu** (faktura / dodací list) — ne AI, kvůli spolehlivosti u financí; (b) u dodacího listu **dopočítat DPH 21 % v kódu** (ne v promptu, ne AI — „AI čte, kód počítá“); dopočet **per položka** (kvůli haléřovému zaokrouhlení), hlavičkové součty sečíst z položek; (c) prompt: AI vrací sazbu přečtenou z dokladu, null když není — default 21 % dosadí kód (u FAKTURY je null u DPH podezřelé → flag k revizi; u DODACÍHO LISTU je null očekávané → dosaď default). **K postavení (odspodu):** V31 = ENUM `warehouse.document_type` (INVOICE/DELIVERY_NOTE, jako `receipt_status`) nebo VARCHAR+CHECK (nerozhodnuto) + sloupec `document_type` v `goods_receipts`; doména + extrakce (typ do `importFromPdf`); dopočet DPH v service; `GoodsReceiptImportController` přijme typ z multipartu; frontend — přepínač faktura/dodací list v `GoodsReceiptImportModal`.
4. Workflow příjemek (potvrzení/zamítnutí naimportované faktury — doklad je po importu ve stavu `PENDING_REVIEW`)
5. ✅ (hotovo) Import položek faktury na zakázku — řádek MATERIAL + `ISSUE` pohyb; mazání a editace se skladovými pohyby (`ISSUE_RETURN`), vazba `order_items.goods_receipt_item_id`
6. ✅ (hotovo) Upload PDF faktury dodavatele — `api.upload` + `GoodsReceiptImportModal` na `WarehousePage`

---

## 9. Specifické konvence projektu

| Věc | Pravidlo |
|---|---|
| Číslo zákazníka | `ZNK-{rok}-{4ciferné seq}`, trigger V9, sekvence `customer.customer_number_seq` (CACHE 1) |
| Číslo zakázky | `ZAK-{rok}-{4ciferné seq}`, trigger V11, sekvence `order.order_number_seq` (CACHE 1) |
| Číslo faktury | `YYYYMM + 3ciferné seq`, trigger V15, advisory lock pro konkurenci |
| Faktura ↔ zakázka | **1:1** — jedna zakázka = max. jedna faktura (`uq_invoices_order_id`). Vědomé rozhodnutí (ne omezení k odstranění): dělené fakturování se řeší založením více zakázek. `createFromOrder` to hlídá přes `ORDER_ALREADY_INVOICED`. |
| VIN validace | `^[A-HJ-NPR-Z0-9]{17}$` — v DB CHECK i DTO `@Pattern` |
| Full-text search | `customer.czech_simple` s `unaccent` → GIN index, `Novak` najde `Novák` |
| Stránkování | `PagedResponse<T>` + `*SearchParams` jako `@Param("params")` |
| Verze API | `/api/v1/...` |
| CORS | `localhost:5173` a `127.0.0.1:5173` |
| Seed hesla | `Password1!` — **změnit před produkčním nasazením** |
| Seed účty | `admin` (ADMIN) · `manager` (MANAGER) · `mechanic` (MECHANIC) |

---

## 10. Spuštění

```bash
# Backend
./mvnw spring-boot:run -Dspring-boot.run.profiles=local

# Frontend
cd frontend/autoservis-frontend && npm install && npm run dev
```

Lokální konfigurace: `src/main/resources/application-local.yaml` (viz `application-local.yaml.example`).
PostgreSQL: databáze `autoservis` na `localhost:5432`.

### Poznámka k prostředí AI asistenta (důležité, viz R-15)

Bash a filesystem konektor míří na **jiný mount** — bash soubory projektu **nevidí**. Důsledky pro AI asistenta:
- Existenci/obsah souboru ověřuj **vždy čtením přes filesystem konektor**, nikdy ne přes bash `ls`/`cat`/`test`.
- `search_files` konektoru hledá podle **názvu souboru**, ne obsahu. Pro hledání v obsahu (všechny výskyty symbolu, např. při přejmenování sloupce/pole) je nutné **projít relevantní soubory čtením** (mapper interface → XML → doména → service → converter → controller → frontend).
- Při přejmenování DB sloupce projdi celý řetězec: migrace → doménový objekt → `*Mapper.xml` (resultMap i INSERT/UPDATE/WHERE) → service (builder/settery) → DTO/converter → frontend. Vynechání XML nebo service se projeví až chybou za běhu, ne při kompilaci XML.
