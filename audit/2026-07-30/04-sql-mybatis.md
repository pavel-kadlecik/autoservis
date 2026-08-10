# 04 — SQL a MyBatis (průřezově)

> Audit 2026-07-30 · rozsah: všech 25 XML mapperů + 26 Java `@Mapper` rozhraní, resultMapy vs. doména,
> JOINy, dynamické `WHERE`, stránkování/řazení, `${}`/injection, plná kvalifikace tabulek, konzistence
> `is_active`, sémantika UPDATE, indexy vs. reálné dotazy, mrtvé/nepárované metody ·
> metoda: každý XML i Java soubor přečten celý, nálezy ověřeny druhým čtením proti službám, DTO,
> konvertorům, migracím a testům; skriptem porovnány `id` v XML ↔ metody v rozhraních ↔ volající.
> Testy nespouštěny (vyžadují Docker).

## Co bylo přečteno

**XML mappery (25/25, celé):**
`AddressMapper.xml`, `CashReceiptMapper.xml`, `CompanyProfileMapper.xml`, `ContactPersonMapper.xml`,
`CreditNoteMapper.xml`, `CustomerMapper.xml`, `DashboardMapper.xml`, `EmployeeMapper.xml`,
`InvoiceItemMapper.xml`, `InvoiceMapper.xml`, `InvoicePartyMapper.xml`, `MileageHistoryMapper.xml`,
`OrderItemMapper.xml`, `OrderMapper.xml`, `RegistrySnapshotMapper.xml`, `RoleMapper.xml`,
`UserMapper.xml`, `VehicleMapper.xml`, `warehouse/GoodsReceiptMapper.xml`,
`warehouse/ProductMatchingMapper.xml`, `warehouse/ReceiptReviewMapper.xml`,
`warehouse/StockTakeMapper.xml`, `warehouse/SupplierMapper.xml`, `warehouse/WarehouseImportMapper.xml`,
`warehouse/WarehouseMapper.xml`

**Java `@Mapper` rozhraní (26, celá):** všech 22 v `mapper/` + `security/mapper/`
(`BlacklistMapper`, `RefreshTokenMapper`, `RoleMapper`, `UserMapper`)

**Doprovodné soubory (relevantní části nebo celé):**
`config/mybatis/PgEnumTypeHandler.java`, `application.yaml` (bloky flyway/mybatis),
`AutoservisApplication.java` (`@MapperScan`),
`service/impl/OrderItemServiceImpl.java`, `StockTakeServiceImpl.java`, `SupplierServiceImpl.java`,
`VehicleServiceImpl.java`, `CustomerServiceImpl.java`, `ProductServiceImpl.java` (části),
`InvoiceServiceImpl.java` (části), `ReceiptReviewServiceImpl.java` (části),
`service/ProductMatchingService.java`, `service/DraftVerificationService.java` (části),
`model/converter/{VehicleConverter, WarehouseProductConverter, OrderItemConverter, CustomerConverter}.java`,
`model/domain/warehouse/Product.java`, `model/dto/{OrderDto, OrderItemDto, ProductDto, VehicleDto,
GoodsReceiptItemDto, UserDto, InvoiceSearchParams, VehicleSearchParams, BaseParams, SearchParams,
CustomerAutocompleteParams, VehicleAutocompleteParams}`,
`controller/OrderItemController.java`, `controller/warehouse/ProductController.java`,
`exception/GlobalExceptionHandler.java` (mapování výjimek),
migrace `V1, V2, V5, V6, V12, V14, V18, V30, V32, V39, V42, V43, V48, V49, V53, V54, V63`,
`docs/konvence.md`, `docs/tech-dluhy.md`, `CLAUDE.md`.

## Shrnutí

Vrstva MyBatis je na výukový projekt překvapivě zdravá. **Ani jeden výskyt `${}`** v celém repu
(ani v XML, ani v anotacích) — SQL injection přes interpolaci není. Všechny tabulky jsou plně
kvalifikované včetně `"order"` v uvozovkách (R-02 dodržen bez výjimky). Whitelisty `sortBy` mají
všechny stránkované seznamy, směr `sortDesc` je uvnitř každé větve včetně `<otherwise>`, a **každá**
stránkovaná větev má tie-breaker `id` — stránkování je stabilní. `search` a `countSearch` mají u všech
osmi stránkovaných mapperů **identickou `WHERE` část** (ověřeno kus po kuse). Skriptová kontrola
nenašla ani jednu metodu rozhraní bez `id` v XML (runtime chyba), ani jedno `id` v XML bez metody.
Dynamické `<where>` bloky jsou správně stavěné — žádná kombinace `<if>` nevyrobí visící `AND`
ani prázdné `WHERE`.

Nálezy jsou proto spíš úzké: **1× STŘEDNÍ** (deaktivovaná skladová karta / dodavatel zablokuje
potvrzení příjemky kolizí na UNIQUE, s neinformativní hláškou) a **11× NÍZKÝ** — dvě z nich jsou
tiché „prázdné pole v odpovědi" (`active` u importních produktů, `stkValidUntil` u vozidel v detailu
zákazníka), zbytek je 500 místo 400 na nesmyslném vstupu, nestabilní pořadí řádků faktury, mrtvý SQL
kód a výkonové vzory (kartézský součin + tři N+1 smyčky). Nic z toho neztrácí data ani peníze.

## Nálezy

### [S-1] Deaktivovaná karta dílu (nebo dodavatele) zablokuje potvrzení příjemky kolizí na UNIQUE
**Severita:** 🟠 STŘEDNÍ
**Jistota:** OVĚŘENO
**Kde:** `src/main/resources/mapper/warehouse/WarehouseImportMapper.xml:54` a `:8`;
`src/main/resources/mapper/warehouse/ProductMatchingMapper.xml:15`, `:25`, `:41`;
`src/main/java/cz/palo/autoservis/service/impl/ReceiptReviewServiceImpl.java:656-679` (`resolveProduct`)
a `:631-649` (`resolveSupplier`);
`src/main/resources/db/migration/V18__init_warehouse_schema.sql:114` (`uq_products_sku UNIQUE (sku)`);
`src/main/resources/db/migration/V30__rename_supplier_identifier_columns.sql:25`
(`uq_suppliers_registration_number`)

**Co je špatně:** Celá párovací kaskáda i obě „pojistky proti duplicitě" hledají **jen aktivní**
záznamy, ale unikátní constrainty v DB platí **bez ohledu na `is_active`**:

```xml
<!-- WarehouseImportMapper.xml:53-55 -->
<select id="findProductIdBySku" resultType="long">
    SELECT id FROM warehouse.products WHERE sku = #{sku} AND is_active = TRUE
</select>
```

```java
// ReceiptReviewServiceImpl.java:663-667
// pojistka proti duplicitě sku (UNIQUE) — stejný kód už kartu má
var existing = importMapper.findProductIdBySku(catalogNumber);
if (existing.isPresent()) { return existing.get(); }
```

Komentář sám říká, že to má být pojistka proti UNIQUE — jenže filtr `AND is_active = TRUE` ji
vypíná právě v tom jediném případě, kdy by byla potřeba. Stejný vzorec je u dodavatele
(`findSupplierIdByIco`, XML:8) proti `uq_suppliers_registration_number`.
Gate úplnosti (`validateCompleteness`, `ReceiptReviewServiceImpl.java:548-552`, `:593-595`) přitom
stav `NONE` propouští — blokuje jen `SUGGESTED`, takže se nový záznam skutečně zakládá.

**Scénář selhání:**
1. Díl „BOSCH 0451103316" má nulovou zásobu (vše vydáno), obsluha ho deaktivuje
   (`DELETE /warehouse/products/{id}` — `ProductServiceImpl.deactivate` to při nulové zásobě povolí,
   TD-28).
2. Přijde nová faktura od dodavatele, ve které ten díl je. AI ho přečte, kaskáda párování ho
   **nenajde** (všechny tři kroky mají `p.is_active = TRUE`) → `ProductMatch.state = NONE`.
3. Obsluha stiskne „Potvrdit". `resolveProduct` → `findProductIdBySku` (jen aktivní) → prázdno →
   `insertProduct` s týmž `sku` → `uq_products_sku` → `DuplicateKeyException`.
4. `GlobalExceptionHandler.handleDataIntegrity` (`GlobalExceptionHandler.java:370-379`) vrátí
   **422 „Zadaná data porušují databázové omezení / Data se nepodařilo uložit"**.
   Celá `@Transactional` metoda `confirm` (`ReceiptReviewServiceImpl.java:238-240`) se odroluje,
   příjemka zůstane `PENDING_REVIEW` a **každý další pokus skončí stejně**.
5. Obsluha z hlášky nemá jak zjistit, že problém je deaktivovaná karta.

Analogicky u dodavatele: deaktivovaný dodavatel + nová faktura od něj → `insertSupplier` →
kolize na `registration_number` → stejná neinformativní 422.

**Proč to vadí:** provoz — příjem zboží se zastaví na chybě, kterou nelze podle hlášky diagnostikovat.
Řešitelné jen tím, že někdo uhodne příčinu a kartu ručně reaktivuje. Testy tuto cestu nepokrývají
(`ProductDeactivationTest` má jen dva testy: zásoba > 0 → 422, zásoba = 0 → OK).

**Návrh řešení:** rozhodnutí uživatele mezi dvěma variantami, obě malé:
- **(a) preferovaná** — z obou „pojistek" (`findProductIdBySku`, `findSupplierIdByIco`) filtr
  `AND is_active = TRUE` **odstranit** (kaskáda párování ať ho dál má — neaktivní kartu nemá smysl
  nabízet, ale nesmí ji ani obcházet), a v `resolveProduct`/`resolveSupplier` nalezenou neaktivní kartu
  buď reaktivovat, nebo vyhodit čitelnou `BusinessRuleException`
  (`PRODUCT_INACTIVE` / `SUPPLIER_INACTIVE`) s SKU/IČO v `params`.
- **(b)** ponechat filtry a před `insertProduct`/`insertSupplier` doplnit kontrolu na existenci
  napříč `is_active` s toutéž čitelnou výjimkou.

---

### [S-2] Prázdné pole v `import-from-receipt` vyrobí `IN` bez seznamu → SQL syntax error → 500
**Severita:** 🟡 NÍZKÝ
**Jistota:** OVĚŘENO
**Kde:** `src/main/java/cz/palo/autoservis/controller/OrderItemController.java:90-99`;
`src/main/java/cz/palo/autoservis/service/impl/OrderItemServiceImpl.java:136-143` a `:183`;
`src/main/resources/mapper/warehouse/GoodsReceiptMapper.xml:115-124`;
`src/main/resources/mapper/warehouse/WarehouseMapper.xml:265-273`

**Co je špatně:** endpoint bere `@Valid @RequestBody List<GoodsReceiptItemDto.ImportRequest>` **bez
`@NotEmpty`/`@Size(min=1)`** (kontroluje se jen obsah prvků — `GoodsReceiptItemDto.java:36-45`).
Prázdný seznam projde až do mapperu:

```xml
<!-- GoodsReceiptMapper.xml:119-123 -->
WHERE gri.id IN
<foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
FOR UPDATE
```

MyBatis 3.5.x (starter 4.0.1, `pom.xml:70-72`) při prázdné kolekci `<foreach>` **celý přeskočí
včetně `open`/`close`** → výsledné SQL je `WHERE gri.id IN FOR UPDATE`. Ať už by se `()` vygenerovaly,
nebo ne, PostgreSQL to odmítne.

Ostatní `<foreach>` v repu **jsou** ošetřené — `reorder` (`OrderItemServiceImpl.java:299-301`),
`insertRoles` (`UserDto` má `@NotEmpty` na `roleIds`), `insertBatch`
(`InvoiceServiceImpl.java:161-166` — `ORDER_HAS_NO_ITEMS`), `findByNormalizedNumbers`
(`ProductMatchingService.java:75-79`, explicitní guard s komentářem). Tahle jediná chybí.

**Scénář selhání:** `POST /api/v1/orders/12/items/import-from-receipt` s tělem `[]` →
`ids` je prázdný → `goodsReceiptMapper.findByIdsForUpdate(List.of())` → `BadSqlGrammarException`
→ catch-all `@ExceptionHandler(Exception.class)` (`GlobalExceptionHandler.java:468`) → **500
`INTERNAL_ERROR`** a ERROR v logu. Správně má být 400. (Stejný pád nastane o kus dál na
`warehouseMapper.findByIds(productIds)`, `WarehouseMapper.xml:269-272`, kdyby první dotaz prošel.)

**Proč to vadí:** stejná třída problému jako vyřešený TD-52 — nesmyslný vstup se tváří jako pád
serveru a zaplevelí log úrovní ERROR, což maskuje skutečné chyby. Frontend to dnes neposílá
(`OrderItemsWrapper` posílá vždy aspoň jednu položku), takže jde o díru v kontraktu API, ne o
běžný provoz.

**Návrh řešení:** `@NotEmpty(message = "Vyberte alespoň jednu položku příjemky")` na parametr
v `OrderItemController.createFromReceipt` (controller už má `@Validated`, takže se to promítne do
400 přes `HandlerMethodValidationException`), případně navíc `if (ids.isEmpty()) return List.of();`
v service jako pojistka.

---

### [S-3] `findByGoodsReceiptId` mapuje přes `resultType` — `is_active` se nenamapuje, `active` je vždy `null`
**Severita:** 🟡 NÍZKÝ
**Jistota:** OVĚŘENO
**Kde:** `src/main/resources/mapper/warehouse/WarehouseMapper.xml:201-210`;
`src/main/java/cz/palo/autoservis/model/domain/warehouse/Product.java:28` (`private Boolean active;`);
`src/main/java/cz/palo/autoservis/model/converter/WarehouseProductConverter.java:38`;
`src/main/java/cz/palo/autoservis/controller/warehouse/ProductController.java:71-73`

**Co je špatně:** jediný dotaz nad produkty, který nepoužívá `ProductResultMap`:

```xml
<select id="findByGoodsReceiptId" resultType="cz.palo.autoservis.model.domain.warehouse.Product">
    SELECT <include refid="productColumns"/> ...
```

Spoléhá na `map-underscore-to-camel-case`. Ten ale u sloupců s prefixem `is_` **nefunguje**:
MyBatis z `is_active` udělá `isactive`, `Reflector` hledá vlastnost `ISACTIVE`, a Lombok pro
`Boolean active` generuje `getActive()/setActive()`, takže registrovaná vlastnost je `ACTIVE`.
Shoda nenastane, sloupec zůstane nenamapovaný. Všechny ostatní sloupce (`part_number_normalized`,
`quantity_on_hand`, `created_at` …) se namapují správně — problém je jen u `is_*`.
Ostatní selecty produktů (`search`, `findById`, `findByIds`) mají explicitní `ProductResultMap`
s `<result property="active" column="is_active"/>` (XML:32), takže tam je to v pořádku.

**Scénář selhání:** `GET /api/v1/warehouse/products/import/{goodsReceiptId}` → service předá
`Product.active = null` konvertoru → `WarehouseProductConverter.toListResponse` (řádek 38)
vrátí `"active": null` u **každého** produktu, i když je karta aktivní. Jakýkoli klient, který by
podle `active` rozhodoval (badge „Neaktivní", nabídka akce Aktivovat/Deaktivovat), se rozhodne
špatně. Stejná třída chyby jako vyřešené TD-51.

**Proč to vadí:** dnes malý dopad — tenhle endpoint podle `tech-dluhy.md` TD-64 nemá volajícího na
frontendu. Je to ale tichá past: až ho někdo použije, chyba se neprojeví ani při kompilaci, ani
v testech (`WarehouseProductConverterTest` testuje konvertor, ne mapper).

**Návrh řešení:** `resultType` → `resultMap="ProductResultMap"` (fragment `productColumns` už
vybírá přesně ty sloupce, které resultMap mapuje). Alternativně endpoint zrušit, pokud je opravdu
mrtvý (viz TD-64).

---

### [S-4] Vozidla v detailu zákazníka nemají `stkValidUntil` — chybí sloupec ve fragmentu
**Severita:** 🟡 NÍZKÝ
**Jistota:** OVĚŘENO
**Kde:** `src/main/resources/mapper/CustomerMapper.xml:135-155` (fragment `vehicleColumns`)
vs. `src/main/resources/mapper/VehicleMapper.xml:57-65` (plný fragment);
`src/main/java/cz/palo/autoservis/model/converter/CustomerConverter.java:70`;
`src/main/java/cz/palo/autoservis/model/dto/vehicle/VehicleDto.java:241`

**Co je špatně:** `CustomerMapper.findById` vnořuje vozidla přes `VehicleResultMap`
(`CustomerMapper.xml:53-55`), ale jeho vlastní fragment `vehicleColumns` (řádky 135–155) je proti
`VehicleMapper.vehicleColumns` **o tři sloupce kratší** — chybí `v.engine_code`,
`v.stk_valid_until` a `v.wheels`. Vlastnosti `engineCode`, `stkValidUntil` a `wheels` proto zůstanou
`null`, přestože `VehicleResultMap` je mapuje (`VehicleMapper.xml:33`, `:38`, `:39`).

Konvertor přitom `stkValidUntil` do odpovědi vyplňuje (`VehicleConverter.toListResponse`) a
`VehicleDto.ListResponse.stkValidUntil` (řádek 241) je součást `CustomerDto.DetailResponse.vehicles`
(`CustomerDto.java:172`).

**Scénář selhání:** `GET /api/v1/customers/25` vrátí u každého vozidla `"stkValidUntil": null`,
zatímco `GET /api/v1/vehicles?search=…` u téhož vozidla vrátí skutečné datum. Dnes to nikdo
nezobrazuje (`CustomerVehiclesTable.jsx:28-46` sloupec STK nemá), takže je to tichý rozpor —
jakmile někdo sloupec „STK do" do té tabulky přidá, bude prázdný a příčina bude v SQL, ne v UI.

**Proč to vadí:** dvě definice „sloupců vozidla" na dvou místech se rozejdou při každém přidání
sloupce (V38 přidala `stk_valid_until`, V62 `wheels`, V19 `engine_code` — ani jedna nedoplnila
fragment v `CustomerMapper.xml`). To je přesně varování z `konvence.md §15` („při přejmenování
sloupce projít celý řetězec").

**Návrh řešení:** doplnit tři chybějící sloupce do `CustomerMapper.xml` fragmentu `vehicleColumns`
(s prefixem `v_`), nebo — čistěji — přesunout aliasovanou variantu do `VehicleMapper.xml` jako
sdílený fragment `vehicleColumnsForCustomer` (vzor `customerColumnsForVehicle`, TD-56), aby
existovala jediná definice.

---

### [S-5] Řádky faktury i zakázky se řadí podle `position` bez tie-breakeru, přičemž pozice se mohou opakovat
**Severita:** 🟡 NÍZKÝ
**Jistota:** OVĚŘENO
**Kde:** `src/main/resources/mapper/InvoiceItemMapper.xml:98` (`ORDER BY ii.position`);
`src/main/resources/mapper/OrderItemMapper.xml:118` (`ORDER BY oi.position`);
`src/main/resources/db/migration/V12__init_order_item_schema.sql:21`
(`position SMALLINT NOT NULL DEFAULT 0`, bez UNIQUE);
`src/main/resources/db/migration/V14__init_billing_schema.sql:65` (totéž pro `invoice_items`);
`frontend/autoservis-frontend/src/components/OrderItemsWrapper.jsx:196`

**Co je špatně:** ani `(order_id, position)`, ani `(invoice_id, position)` nemá unikátní index —
duplicitní pozice jsou tedy povolený stav. Frontend je umí vyrobit: nová položka dostane
`position = items.length + 1` (`OrderItemsWrapper.jsx:196`), takže po smazání prostřední položky
(1, 2, 3 → smaž 2 → zůstane 1, 3) dostane nová položka opět **3**. Backend pozici při `create`
nepřečísluje (`OrderItemServiceImpl.create`, řádky 105-117, pozici bere z requestu tak, jak přišla).

Při shodné pozici je pořadí v `ORDER BY position` bez druhého klíče nedeterministické — PostgreSQL
je nezaručuje mezi jednotlivými spuštěními.

**Scénář selhání:** zakázka má položky s pozicemi 1, 3, 3. Vystaví se faktura
(`InvoiceItemMapper.insertBatch` pozice zkopíruje). Faktura se vytiskne dnes a znovu za měsíc —
`findByInvoiceId` (`InvoiceItemMapper.xml:90-99`) může obě položky s pozicí 3 vrátit v opačném
pořadí, takže **dvě PDF téhož daňového dokladu mají řádky v jiném pořadí**. Součty i obsah jsou
totožné, ale doklad není bit-shodný, což u archivovaného dokladu vypadá jako zmanipulovaný.

**Proč to vadí:** právní doklad by měl být reprodukovatelný. Riziko je nízké (týká se jen faktur
s duplicitními pozicemi), oprava je jednořádková.

**Návrh řešení:** `ORDER BY ii.position, ii.id` a `ORDER BY oi.position, oi.id`. Volitelně navíc
v `OrderItemServiceImpl.create` používat `findMaxPositionByOrderId() + 1` (metoda už existuje a
`importFromReceipt` ji na řádku 187 používá) místo hodnoty z klienta.

---

### [S-6] `position` a `id` položek zakázky nemají `@NotNull` — DB chybu dostane uživatel jako neinformativní 422, u `reorder` jako tichý no-op
**Severita:** 🟡 NÍZKÝ
**Jistota:** OVĚŘENO
**Kde:** `src/main/java/cz/palo/autoservis/model/dto/order/OrderItemDto.java:68-69` (`CreateRequest.position`)
a `:121-126` (`ReorderRequest`);
`src/main/java/cz/palo/autoservis/model/converter/OrderItemConverter.java:68`;
`src/main/resources/mapper/OrderItemMapper.xml:61-73` (insert) a `:94-106` (reorder);
`src/main/resources/db/migration/V12__init_order_item_schema.sql:21`

**Co je špatně (a):** `CreateRequest.position` má jen `@PositiveOrZero`, ne `@NotNull`. Konvertor ji
předá tak, jak přišla (`OrderItemConverter.java:68`), a `insert` ji zapisuje **bezpodmínečně**
(`#{position}` na XML:71). Sloupec je `NOT NULL DEFAULT 0` — explicitní `NULL` default nespustí,
takže INSERT skončí porušením NOT NULL. (`update` má tentýž problém ošetřený `<if test="position != null">`
na XML:87 — obě cesty se tedy chovají různě.)

**Co je špatně (b):** `ReorderRequest.id` nemá žádnou validaci. V `reorder`:
```sql
SET position = CASE id <foreach>WHEN #{item.id} THEN #{item.position}</foreach> END
WHERE id IN (<foreach>#{item.id}</foreach>) AND order_id = #{orderId}
```
`WHEN NULL` nikdy nesedne a `id IN (NULL)` neodpovídá ničemu.

**Scénář selhání:**
(a) `POST /api/v1/orders/12/items` s tělem bez klíče `position` → INSERT s `position = NULL` →
`DataIntegrityViolationException` → **422 „Zadaná data porušují databázové omezení"**, ačkoli jde
o chybu klienta, kterou má odhalit `@Valid` a vrátit 400 se jménem pole.
(b) `PUT /api/v1/orders/12/items/reorder` s tělem `[{"id": null, "position": 1}]` → UPDATE dotkne
0 řádků, endpoint vrátí **204 No Content**, tedy „hotovo" — přestože se nezměnilo nic.

**Proč to vadí:** frontend obě situace dnes nevyrábí (`OrderItemsWrapper.jsx:196`, `:242` posílají
pozici i id vždy), takže jde o kontrakt API, ne o denní provoz. Chybová hláška u (a) přesto
odporuje pravidlu R-13 (business/validační chybu má vracet aplikace, ne DB) a (b) je tichá lež
o výsledku operace.

**Návrh řešení:** `@NotNull` na `CreateRequest.position` (nebo v service dopočítat
`findMaxPositionByOrderId() + 1`, když pozice nedorazí — konzistentní s `importFromReceipt`)
a `@NotNull` na `ReorderRequest.id` i `ReorderRequest.position`.

---

### [S-7] Mrtvý SQL kód: nepoužitý GIN full-textový index, dvě neexistujícím konzumentem volaná views, dvě nevolané mapper metody — a rozpor s dokumentací
**Severita:** 🟡 NÍZKÝ
**Jistota:** OVĚŘENO
**Kde:**
- `src/main/resources/db/migration/V2__init_customer_schema.sql:186-193` (`idx_customers_fts`)
  vs. `src/main/resources/mapper/CustomerMapper.xml:170-177` (hledání přes `LIKE`)
- `src/main/resources/db/migration/V18__init_warehouse_schema.sql:309-316` (`v_stock_on_hand`)
- `src/main/resources/db/migration/V54__batch_provenance_left_join_supplier.sql:19-32` (`v_batch_provenance`)
- `src/main/java/cz/palo/autoservis/mapper/CreditNoteMapper.java:20`
  + `src/main/resources/mapper/CreditNoteMapper.xml:43-48` (`findByOriginalInvoiceId`)
- `src/main/java/cz/palo/autoservis/security/mapper/UserMapper.java:53`
  + `src/main/resources/mapper/UserMapper.xml:104-108` (`resetFailedAttempts`)
- `docs/konvence.md:338`, `docs/databaze.md:91`

**Co je špatně:**
1. `idx_customers_fts` je GIN index nad `to_tsvector('customer.czech_simple', …)`. Grep přes celý
   `src/` nenajde **ani jeden** výskyt `to_tsvector`, `plainto_tsquery` ani operátoru `@@` mimo tuto
   migraci — vyhledávání zákazníků se od opravy TD-18 dělá přes
   `LOWER(public.unaccent(c.first_name)) LIKE CONCAT('%', …, '%')` (`CustomerMapper.xml:173-175`),
   což GIN index použít nemůže (a kvůli úvodnímu `%` ani jiný b-tree index ne).
2. `warehouse.v_stock_on_hand` a `warehouse.v_batch_provenance` nemá žádný mapper ani Java kód —
   ověřeno grepem přes `src/` mimo `db/migration`. V54 dokonce jedno z nich opravovala (INNER →
   LEFT JOIN dodavatele), přestože ho nikdo nečte; skutečnou opravu nese `WarehouseMapper.xml:130-133`.
3. `CreditNoteMapper.findByOriginalInvoiceId` a `UserMapper.resetFailedAttempts` — skript
   porovnávající `id` v XML s volajícími v `src/main` i `src/test` u obou nenašel **žádného volajícího**
   (ověřeno i grepem). `UserMapper.resetFailedAttempts` je navíc funkčně nadbytečný —
   `updateLastLogin` (`UserMapper.xml:110-115`) čítač resetuje sám.
4. Dokumentace tvrdí něco jiného, než co kód dělá: `konvence.md:338` uvádí
   „Full-text search | `customer.czech_simple` s `unaccent`", `databaze.md:91` popisuje
   „konfigurace `customer.czech_simple` (simple + unaccent)". Konfigurace i index existují, ale
   vyhledávání je `LIKE` + `unaccent()` — full-text se nepoužívá.

**Scénář selhání:** není pád, je to trvalý náklad a matoucí mapa terénu:
každý `INSERT`/`UPDATE` zákazníka platí údržbu GIN indexu, který žádný dotaz nepoužije; nový vývojář
podle `konvence.md:338` uvěří, že hledání je full-textové, a bude ladit tsvector konfiguraci místo
`LIKE`; údržba (V54) se investuje do view, které nikdo nečte.

**Proč to vadí:** R-12 („dead code smazat") a pravidlo z `CLAUDE.md` o synchronizaci dokumentace.
Provozně zanedbatelné, ale je to přesně ten typ šumu, který sráží důvěru v ostatní dokumentaci.

**Návrh řešení:** *rozhodnutí uživatele* mezi dvěma směry:
- **buď** smazat (migrace `V{n+1}`: `DROP INDEX customer.idx_customers_fts`,
  `DROP VIEW warehouse.v_stock_on_hand`, `DROP VIEW warehouse.v_batch_provenance`) + smazat obě
  mrtvé mapper metody + opravit `konvence.md:338` a `databaze.md:91` na „`LIKE` + `unaccent`,
  víceslovné tokeny";
- **nebo** hledání zákazníků skutečně převést na FTS (`to_tsvector(...) @@ plainto_tsquery(...)`),
  čímž index začne dávat smysl — ale to je větší zásah a přijde o hledání podprovázků
  („nov" už nenajde „Novák"), takže bych to nedoporučoval.

---

### [S-8] Odpovědi mutačních operací nad vozidlem nemají vnořeného `customer` — `findByIdIncludingInactive` zákazníka nejoinuje
**Severita:** 🟡 NÍZKÝ
**Jistota:** OVĚŘENO
**Kde:** `src/main/resources/mapper/VehicleMapper.xml:149-153` (bez JOIN) vs. `:139-146` (s JOIN);
`src/main/java/cz/palo/autoservis/service/impl/VehicleServiceImpl.java:272-277` (`fetchOrFail`);
`src/main/java/cz/palo/autoservis/model/converter/VehicleConverter.java:36-39`

**Co je špatně:** `findById` (strict) sloupce zákazníka joinuje a aliasuje (`cust_*`, TD-56),
`findByIdIncludingInactive` ne:

```xml
<select id="findByIdIncludingInactive" resultMap="VehicleResultMap">
    SELECT <include refid="vehicleColumns"/>
    FROM vehicle.vehicles v
    WHERE v.id = #{id}
</select>
```

`VehicleResultMap` má ale `<association property="customer" columnPrefix="cust_">`
(`VehicleMapper.xml:48-50`), takže `Vehicle.customer` zůstane `null`. A právě tuhle metodu používá
`fetchOrFail`, kterým procházejí **všechny** odpovědi po zápisu (`update`, `deactivate`, `activate`,
řádky 165, 192, 220). Konvertor má od TD-55 null guard, takže nespadne — jen pole vynechá.

**Scénář selhání:** `GET /api/v1/vehicles/7` vrátí `{"customerId": 3, "customer": {…}, "customerDisplayName": "Novák Jan", …}`;
`PUT /api/v1/vehicles/7` (i `DELETE` a `POST /activate`) vrátí **tentýž resource bez `customer`
a bez `customerDisplayName`**. Klient, který by po uložení překreslil detail z odpovědi PUT
(místo nového GETu), by majitele ztratil. Dnešní frontend odpověď PUT zahazuje
(`VehiclesPageEdit.jsx:50` jen `await api.put(...)`), takže není vidět.

**Proč to vadí:** stejný endpoint (`/vehicles/{id}`) vrací pro GET a PUT různě tvarovaný objekt.
Je to latentní past a zároveň tichý rozpor s `api.md`, které pro obě operace uvádí `DetailResponse`.

**Návrh řešení:** doplnit do `findByIdIncludingInactive` tentýž `LEFT JOIN customer.customers c`
+ `<include refid="cz.palo.autoservis.mapper.CustomerMapper.customerColumnsForVehicle"/>` jako má
`findById` (řádky 140-144). Pozor: `VehicleServiceImpl.update:159` čte majitele záměrně z
`getCustomerId()`, ne z `getCustomer()` (TD-56) — ten řádek se měnit nesmí ani nemusí.

---

### [S-9] Výkon: detail zákazníka je trojitý kartézský součin, tři místa dělají N+1
**Severita:** 🟡 NÍZKÝ
**Jistota:** OVĚŘENO
**Kde:** `src/main/resources/mapper/CustomerMapper.xml:190-201` (`findById`);
`src/main/java/cz/palo/autoservis/service/impl/OrderItemServiceImpl.java:225`;
`src/main/java/cz/palo/autoservis/service/impl/StockTakeServiceImpl.java:141-147` a `:197-199`

**Co je špatně:**
1. `CustomerMapper.findById` joinuje **tři nezávislé kolekce naráz** (adresy, kontaktní osoby,
   vozidla — řádky 197-199). Databáze vrátí jejich kartézský součin; MyBatis řádky správně
   deduplikuje podle `<id>` v každé vnořené mapě (ověřeno — `AddressResultMap`,
   `ContactPersonResultMap` i `VehicleResultMap` `<id>` mají), takže **výsledek je správný**, jen
   objem přenesených řádků roste násobně.
2. `OrderItemServiceImpl.importFromReceipt:225` —
   `return newItems.stream().map(item -> getById(item.getId())).toList();` provede jeden
   `SELECT … LEFT JOIN employee.employees` **na každou** importovanou položku, hned po jejím INSERTu.
3. `StockTakeServiceImpl.updateItems:141-147` — jeden `UPDATE` na každý řádek soupisu.
4. `StockTakeServiceImpl.applyShortages:197-199` — jeden `SELECT … FOR UPDATE` na každý díl s mankem.

**Scénář selhání:** flotilový zákazník s 8 adresami, 6 kontaktními osobami a 40 vozidly →
`GET /customers/{id}` přenese 8 × 6 × 40 = **1 920 řádků** místo 54, každý se všemi sloupci
zákazníka (včetně `internal_note`). Inventura nad 600 aktivními díly
(`snapshotActiveProducts` nasnapshotuje všechny, `StockTakeMapper.xml:107-120`) → uložení soupisu
je 600 samostatných round-tripů v jedné transakci; uzavření s 50 manky dalších 50 dotazů.

**Proč to vadí:** dnes žádná z těch čísel neexistuje (výuková DB), takže to nic nebrzdí — ale jsou to
vzory, které se s daty zhorší kvadraticky, ne lineárně. Hlásím to jako připomínku, ne jako
naléhavou opravu.

**Návrh řešení:** *návrhové, nízká priorita.*
- `findById`: rozdělit na 1 + 3 dotazy (hlavička + tři `findByCustomerId`), sestavit v service.
  `VehicleMapper.findByCustomerId` už existuje (`VehicleMapper.xml:159-164`),
  `AddressMapper`/`ContactPersonMapper` by ho potřebovaly doplnit.
- `importFromReceipt`: místo N× `getById` načíst vložené položky jedním
  `findByOrderId` a vyfiltrovat nové (id už jsou známá z `useGeneratedKeys`).
- `updateItems`: jeden `UPDATE … FROM (VALUES …)` přes `<foreach>` (pozor na guard prázdné kolekce,
  viz S-2).

---

### [S-10] Marže se počítá z položek zakázky, tržba z položek faktury — po editaci konceptu faktury čísla nesedí
**Severita:** 🟡 NÍZKÝ
**Jistota:** OVĚŘENO
**Kde:** `src/main/resources/mapper/DashboardMapper.xml:163-193` (`sumMargin`) a `:206-245`
(`findMonthlyStats`, CTE `mar`) vs. `:140-153` (`sumRevenue`) a CTE `rev`;
`src/main/resources/db/migration/V32__v_invoice_price_totals.sql:8-21`

**Co je špatně:** tržba se sčítá z `billing.v_invoice_price_totals`, tedy z **položek faktury**;
marže se počítá jako `SUM((oi.unit_price - oi.purchase_price) * oi.quantity)` nad
`"order".order_items`, tedy z **položek zakázky**. Ty dvě množiny se mohou lišit: položky zakázky se
po vystavení faktury zamykají (`requireOrderNotInvoiced`, `OrderItemServiceImpl.java:324-333`),
ale položky faktury jdou v konceptu volně přidávat, měnit i mazat
(`InvoiceItemMapper.xml:35-47`, `:61-76`, `:102-110` — guard je jen na stav `DRAFT`, ne na shodu
se zakázkou).

**Scénář selhání:**
1. Zakázka má jednu položku: prodej 1 000 Kč, nákup 600 Kč.
2. Vystaví se koncept faktury (položka se zkopíruje), účetní v konceptu cenu opraví na 1 300 Kč
   (doúčtování) a fakturu vystaví.
3. Přehled („Statistika"/dlaždice) pak ukáže **tržbu 1 300 Kč** (z faktury), ale **marži 400 Kč**
   (z položek zakázky, tedy 1 000 − 600) místo 700 Kč. Marže v procentech vyjde 31 % místo 54 %.
   Analogicky smazaný řádek faktury sníží tržbu, ale marži ne — marže pak může být i **vyšší než
   tržba**.

**Proč to vadí:** peníze v manažerském přehledu. Není to účetní doklad (faktura je správně), ale
majitel podle marže rozhoduje o cenách. Rozsah je omezený — projeví se jen u faktur, kde se koncept
po vytvoření editoval.

**Návrh řešení:** *rozhodnutí uživatele*, dvě čisté varianty:
- **(a)** počítat marži ze stejného základu jako tržbu — tj. z `billing.invoice_items`, spárovaných
  přes `order_item_id` (sloupec existuje, `V14:59`, a má index z V53) na `oi.purchase_price`;
- **(b)** ponechat dnešní stav a v UI/nápovědě explicitně napsat, že marže vychází z kalkulace
  zakázky, ne z vystaveného dokladu.
Varianta (a) je konzistentnější, ale je to změna významu ukazatele — proto rozhodnutí uživatele.

---

### [S-11] Neaktivní produkt se zásobou zmizí z ocenění skladu i z inventury
**Severita:** 🟡 NÍZKÝ
**Jistota:** OVĚŘENO
**Kde:** `src/main/resources/db/migration/V42__v_stock_valuation.sql:31` (`WHERE p.is_active = TRUE`);
`src/main/resources/mapper/warehouse/StockTakeMapper.xml:119` (snapshot `WHERE p.is_active = TRUE`);
`src/main/resources/mapper/warehouse/WarehouseMapper.xml:241` (`findLowStock`);
`src/main/resources/db/migration/V18__init_warehouse_schema.sql:283-302`
(trigger `fn_apply_stock_movement` bez kontroly `is_active`);
`src/main/java/cz/palo/autoservis/service/impl/OrderItemServiceImpl.java:269-283` (`ISSUE_RETURN`)

**Co je špatně:** tři agregace nad skladem filtrují `p.is_active = TRUE`
(ocenění V42, přehled pod minimem, snapshot inventury), ale **nic negarantuje, že neaktivní karta má
nulovou zásobu**. `ProductServiceImpl.deactivate` sice deaktivaci při `quantityOnHand > 0` odmítne
(TD-28), jenže zásoba se může vrátit **po** deaktivaci: trigger `fn_apply_stock_movement` přičte
množství k `products.quantity_on_hand` bez ohledu na `is_active`, a `OrderItemServiceImpl.delete`
při smazání skladové položky zakázky vloží kladný pohyb `ISSUE_RETURN` (řádky 273-281) bez jakékoli
kontroly stavu karty.

**Scénář selhání:**
1. Díl je celý vydán na otevřenou zakázku → `quantity_on_hand = 0`.
2. Obsluha kartu deaktivuje (projde — zásoba je nula).
3. Zakázka se ještě nefakturovala, mechanik položku smaže → `ISSUE_RETURN` vrátí 4 ks
   na sklad i do šarže (trigger, V18:286-294). Karta je pořád `is_active = FALSE`.
4. Od té chvíle: `GET /warehouse/valuation` i dlaždice „Hodnota skladu"
   (`DashboardMapper.xml:267-270`) hodnotu těch 4 ks **nezapočítají**; přehled „pod minimem" je
   nevidí; a nově otevřená inventura je do soupisu vůbec nenasnapshotuje
   (`StockTakeMapper.xml:107-120`), takže fyzicky přítomné zboží se nikdy nedostane do inventurního
   rozdílu.

**Proč to vadí:** peníze a evidence — hodnota skladu je podhodnocená o zboží, které na regálu leží,
a inventura ho nemá jak najít. Pravděpodobnost je nízká (vyžaduje deaktivaci mezi výdejem a
storno-vrácením), ale rozpor je systémový: `quantity_on_hand` a `is_active` nejsou vázané ani
constraintem, ani kódem.

**Návrh řešení:** dvě malé, nezávislé pojistky:
- v `OrderItemServiceImpl.delete` (a obecně před každým **kladným** pohybem) načíst produkt a
  neaktivní kartu buď automaticky reaktivovat, nebo odmítnout čitelnou `BusinessRuleException`;
- alternativně/navíc DB CHECK nebo trigger `warehouse.products`: `is_active = FALSE` ⇒
  `quantity_on_hand = 0` (nová migrace `V{n+1}`).

---

### [S-12] Aktivace zákazníka nevrátí jeho vozidla a detail zákazníka je navíc skryje
**Severita:** 🟡 NÍZKÝ
**Jistota:** OVĚŘENO
**Kde:** `src/main/resources/mapper/CustomerMapper.xml:199`
(`LEFT JOIN vehicle.vehicles v ON v.customer_id = c.id AND v.is_active = TRUE`);
`src/main/java/cz/palo/autoservis/service/impl/CustomerServiceImpl.java:177`
(`vehicleService.deactivateByCustomerId(id)`) vs. `:188-192` (`activate` bez protějšku);
`src/main/resources/mapper/VehicleMapper.xml:145` (`findById` je strict)

**Co je špatně:** deaktivace zákazníka kaskádně deaktivuje všechna jeho vozidla
(`CustomerMapper` má k tomu `deactivateByCustomerId`, `VehicleMapper.xml:267-271`), ale
`activate` (`CustomerServiceImpl.java:188-192`) je jen `customerMapper.activate(id)` — vozidla
zůstanou `is_active = FALSE`. Detail zákazníka přitom neaktivní vozidla **nezobrazí**
(`CustomerMapper.xml:199`), a `GET /vehicles/{id}` na ně vrací 404 (strict `findById`,
`VehicleMapper.xml:145`).

**Scénář selhání:**
1. `DELETE /api/v1/customers/25` → zákazník i jeho 3 vozidla neaktivní.
2. Omyl, hned `POST /api/v1/customers/25/activate` → zákazník je zpět, ale vozidla ne.
3. Detail zákazníka ukazuje **prázdný seznam vozidel** a nenabízí žádnou cestu, jak je vrátit —
   sekce vozidel zobrazuje jen aktivní. Odkaz na detail vozidla vrátí 404.
4. Zotavení existuje, ale není zřejmé: obsluha musí jít do seznamu vozidel, vypnout filtr „aktivní"
   (`VehiclesPage.jsx:33` posílá `activeOnly`) a každé vozidlo aktivovat zvlášť.

**Proč to vadí:** provoz — nesymetrická kaskáda vypadá jako ztráta dat. Data ztracená nejsou,
ale běžná oprava překlepu vyžaduje znalost obchvatu.

**Návrh řešení:** *rozhodnutí uživatele*, protože jde o zvolenou sémantiku:
- **(a)** `CustomerServiceImpl.activate` udělat `@Transactional` a doplnit reaktivaci vozidel
  (potřeba nová metoda `VehicleMapper.activateByCustomerId(customerId)`; pozor: reaktivuje i
  vozidla deaktivovaná samostatně, před deaktivací zákazníka — proto varianta (b));
- **(b)** kaskádu při deaktivaci vůbec nedělat a místo toho v detailu zákazníka
  neaktivní vozidla zobrazovat (zrušit `AND v.is_active = TRUE` na `CustomerMapper.xml:199`
  a v UI je odlišit) — pak je co aktivovat ručně a nic se neztratí z dohledu.

## Co bylo ověřeno jako v pořádku

- **`${}` / SQL injection** — v `src/main/resources/mapper/**` ani v anotacích
  `security/mapper/*` **není jediný výskyt** `${`. Všechny identifikátory řazení jdou přes whitelist
  v `<choose>`, hodnoty výhradně přes `#{}`.
- **Plná kvalifikace tabulek (R-02)** — zkontrolována každá `FROM`/`JOIN`/`INSERT INTO`/`UPDATE`
  klauzule ve všech 25 XML. Schéma `"order"` je všude v uvozovkách
  (`OrderMapper.xml`, `OrderItemMapper.xml`, `DashboardMapper.xml:25,55,73,…`,
  `WarehouseMapper.xml:152`, `ReceiptReviewMapper.xml:240`). Bez výjimky.
- **Párování XML ↔ Java rozhraní** — skriptem porovnáno všech 25 namespace: **žádné `id` v XML bez
  metody** (runtime chyba) a **žádná metoda bez `id` nebo inline anotace**. Jediný namespace bez
  Java rozhraní je `ContactPersonMapper.xml` — obsahuje pouze `resultMap` referencovaný
  z `CustomerMapper.xml:51`, což je záměr (TD-32).
- **`search` vs. `count*` — identická `WHERE`** — ověřeno u všech osmi stránkovaných dvojic
  (Customer, Vehicle, Order, Invoice, Warehouse, Supplier, ReceiptReview, StockTake): všechny sdílejí
  týž `<sql>` fragment; `search` navíc joinuje jen 1:1 tabulky/views bez filtru
  (`OrderMapper.xml:158` faktura, `InvoiceMapper.xml:267` cenové view), takže počet řádků neovlivní.
  `UserMapper.search` (XML:184-189) stránkuje ID v poddotazu a používá týž fragment jako `countSearch`.
- **Stabilita stránkování** — každá větev každého whitelistu končí tie-breakerem `id`
  (`customerSortOrder`, `vehicleSortOrder`, `orderSortOrder`, `invoiceSortOrder`, `productSortOrder`,
  `supplierSortOrder`, `receiptSortOrder`, `stockTakeSortOrder`, `userSortOrder`). Směr `sortDesc`
  je uvnitř každé větve včetně `<otherwise>`, nullable sloupce mají explicitní `NULLS LAST`.
  `UserMapper.search` řadí i ve vnějším dotazu (XML:191) — JOIN na role by pořadí z poddotazu neudržel.
- **Dynamické `WHERE`** — projity všechny `<where>`/`<trim>` bloky. Žádná kombinace `<if>` nevyrobí
  visící `AND`/`OR` ani prázdné `WHERE`: buď blok začíná bezpodmínečnou podmínkou
  (`OrderMapper.xml:70`, `CustomerMapper.xml:380`, `VehicleMapper.xml:220`,
  `GoodsReceiptMapper.xml:60`), nebo je celý v `<where>`, které první `AND` odstraní
  (ověřeno i pro kombinaci „jen `stkExpiring`" a „jen `lowStockOnly`").
- **`<foreach>` s prázdnou kolekcí** — všechny výskyty prověřeny; ošetřené jsou
  `reorder` (`OrderItemServiceImpl.java:299-301`), `insertBatch` (`InvoiceServiceImpl.java:161-166`),
  `insertRoles` (`@NotEmpty` na `UserDto.CreateRequest/UpdateRequest.roleIds`),
  `findByNormalizedNumbers` (`ProductMatchingService.java:75-79`). Neošetřené jen dvě — viz S-2.
- **`INNER` vs. `LEFT JOIN`** — položka zakázky bez mechanika se nezahodí
  (`OrderItemMapper.xml:116,125` LEFT JOIN `employee.employees`); šarže z inventurního přebytku bez
  dodavatele se nezahodí (`WarehouseMapper.xml:133` LEFT JOIN + `COALESCE`, V54);
  vozidlo bez zákazníka, zakázka bez vozidla, faktura bez zakázky — všude LEFT
  (`VehicleMapper.xml:144,171`, `OrderMapper.xml:152-153`, `InvoiceMapper.xml:103-105`);
  faktura bez položek dostane `COALESCE(...,0)` z LEFT JOIN na cenové view
  (`InvoiceMapper.xml:242-244,263-265`). INNER JOINy jsou jen tam, kde je FK `NOT NULL`
  nebo jde o existenční dotaz (`WarehouseMapper.xml:132`, `StockTakeMapper.xml:137,159`,
  `ReceiptReviewMapper.xml:241`).
- **Násobení řádků LEFT JOINem** — `OrderMapper.search:158` joinuje `billing.invoices` s
  `bi.status <> 'CANCELLED'`; partial unique index `uq_invoices_order_active`
  (`V48__invoice_order_partial_unique.sql:20-22`) zaručuje nejvýš jednu takovou fakturu, takže
  komentář v XML sedí a `GROUP BY` opravdu není potřeba. Totéž `sumMargin`
  (`DashboardMapper.xml:190`) — žádné dvojí započtení.
- **Zaokrouhlování** — `InvoiceItemMapper.xml:82-85,92-95` počítá `net`/`vat`/`gross` **přesně
  stejným výrazem** jako view `billing.v_invoice_price_totals`
  (`V32__v_invoice_price_totals.sql:17-18`) — zaokrouhlení po řádku, součet až potom.
  Řádky faktury a součet faktury tedy sedí na haléř. Stejnou filozofii drží V42 i V63.
- **Guardované zápisy (TOCTOU)** — ověřeny všechny: `InvoiceMapper.update` (`AND status='DRAFT'`,
  XML:180), `updateStatus` (`AND status = expectedStatus`, XML:193),
  `InvoiceItemMapper.insert/update/deleteById` (`WHERE EXISTS` na DRAFT fakturu, XML:43-46,71-75,105-109),
  `ReceiptReviewMapper.updateDraft/confirm/reject` (`AND status='PENDING_REVIEW'`),
  `cancel` (`AND status='CONFIRMED'`), `StockTakeMapper.close/cancel` (`AND status='OPEN'`),
  `CreditNoteMapper.updateStatus`. `SELECT … FOR UPDATE` je všude nad jednou tabulkou nebo
  s explicitním `OF gri` (`StockTakeMapper.xml:163`), takže zamyká to, co má.
- **Sémantika UPDATE (full-replace vs. PATCH)** — `CustomerMapper.update`, `VehicleMapper.update`,
  `OrderMapper.update`, `SupplierMapper.update`, `WarehouseMapper.update`, `EmployeeMapper.update`,
  `InvoiceItemMapper.update`, `CompanyProfileMapper.update` jsou full-replace, takže vyplněné pole
  jde vymazat (TD-54 dotažen). `COALESCE` zůstal jen u `NOT NULL` sloupců, které vymazat nejde
  (`suppliers.country_code`, `invoice_items.position`, `invoices.due_date/payment_method/status`,
  `stock_takes.note`) — zdůvodněno komentářem u každého. Jediný dynamický `<set>` je
  `OrderItemMapper.update` (XML:77-90), ale všechna `<if>`-chráněná pole jsou v
  `OrderItemDto.UpdateRequest` `@NotNull`/`@NotBlank`, a mazatelná pole
  (`purchase_price`, `note`, `employee_id`) se zapisují bezpodmínečně — chování je tedy
  ekvivalentní full-replace.
- **`GDPR`/`marketing` časová razítka** — `CustomerMapper.xml:335-339` posune `*_consent_at` jen při
  skutečné změně (`IS DISTINCT FROM` proti staré hodnotě sloupce, což v jednom UPDATE odkazuje na
  hodnotu **před** zápisem). Správně.
- **Duchový zákazník (TD-56)** — `VehicleResultMap` má `columnPrefix="cust_"`
  (`VehicleMapper.xml:48-50`) a fragment `customerColumnsForVehicle`
  (`CustomerMapper.xml:97-109`) aliasuje **jen** sloupce, které `CustomerEmbeddedResultMap`
  skutečně mapuje. Kolize `is_active`/`created_at` mezi vozidlem a zákazníkem je vyřešená.
  Vnořené kolekce v `CustomerFullResultMap` mají prefixy `addr_`/`cp_`/`v_` a všechny cílové
  resultMapy mají `<id>`, takže deduplikace funguje.
- **Enum handling** — `PgEnumTypeHandler` posílá `setObject(..., Types.OTHER)`, což PostgreSQL
  driveru stačí i bez explicitního `::typ` v SQL; čtení jde přes `getString` + `valueOf`.
  Nekonzistence v tom, kde je handler uveden explicitně a kde se spoléhá na registraci přes
  `@MappedTypes` + `type-handlers-package`, je kosmetická — obojí funguje.
- **Records jako parametry** — `#{params.effectiveLimit}` nad `CustomerAutocompleteParams`
  (record) funguje: MyBatis 3.5.11+ pro recordy registruje všechny bezparametrové metody jako
  gettery. `params.normalizedQuery()` a `params.importType.name()` jdou přes OGNL, které volání
  metod i čtení privátních polí zvládá.
- **Stránkovací parametry** — `BaseParams` clampuje `page ≥ 1` a `1 ≤ pageSize ≤ 100`
  (`BaseParams.java:22-28`), takže `LIMIT`/`OFFSET` nemůže dostat zápornou ani obří hodnotu.
- **`Optional` vs. `TooManyResultsException`** — každá metoda vracející `Optional` má v DB
  garanci jedinečnosti: `findOpenId` (partial unique `uq_stock_take_single_open`, V44:68),
  `findByOrderId` (`uq_invoices_order_active`, V48:20), `findProductIdBySku` (`uq_products_sku`),
  `findSupplierIdByIco` (`uq_suppliers_registration_number`),
  `findProductIdBySupplierSku` (`uq_supplier_products`), `find()` profilu firmy (`WHERE id = 1`),
  `findDeliveryNoteReceiptId` (`LIMIT 1`).
- **Indexy vs. reálné dotazy** — porovnány `WHERE`/`JOIN`/`ORDER BY` sloupce všech mapperů s indexy
  z V1/V2/V5/V6/V12/V14/V18/V20/V21/V27/V34/V38/V39/V40/V41/V43/V44/V48/V53/V55/V57/V59/V61.
  **Konkrétní chybějící index jsem nenašel**: FK sloupce, na které se filtruje, index mají
  (V53 dorovnala `orders.customer_id/vehicle_id`, `vehicles.customer_id`,
  `invoice_items.order_item_id`); `invoices.order_id` sice po V48 nemá plný unique, ale všechny
  dotazy nad ním nesou i predikát `status <> 'CANCELLED'` / `IN ('ISSUED','PAID')`, takže partial
  unique index použitelný je. Neindexované jsou jen `LIKE '%…%'` fulltexty (viz S-7) a
  `token_blacklist.invalidated_at` (mazací job nad malou tabulkou) — ani jedno nehlásím jako nález.
- **SQL v anotacích (N-05)** — zůstává na 5 místech: `BlacklistMapper` (3× `@Insert/@Select/@Delete`),
  `RefreshTokenMapper` (4×), `UserMapper.existsByUsername` (1×). To je dokumentovaná historická
  výjimka R-01 + `tech-dluhy.md`, ne nález. Reálný dopad je malý: jsou to jednotabulkové dotazy bez
  dynamiky, parametrizované přes `#{}`, takže nehrozí ani injection, ani neviditelná změna schématu
  — jen se hledají jinde než všechny ostatní.
- **Prázdné pole v odpovědi bez dopadu** — `OrderResultMap` mapuje `invoice_status`
  (`OrderMapper.xml:26`), ale `findById` (XML:218-227) ten sloupec nevybírá. Není to chyba:
  `invoiceStatus` je jen v `OrderDto.ListResponse` (`OrderDto.java:30`), kterou plní `search`
  (XML:150). `DetailResponse` pole nemá.
- **`is_active` u zakázek** — `WhereClause` (`OrderMapper.xml:70`) a `countOpenBy*`
  (XML:233,241) drží `is_active = TRUE` jako neškodný no-op; to je vědomě odložený TD-67, nehlásím.
- **`CustomerMapper.findById` bez filtru `is_active`** — v XML je komentář „INTENTIONALLY lenient"
  (řádky 185-189) a je to překlasifikovaný TD-08. Nehlásím.

## Otevřené otázky pro uživatele

1. **S-1 — jak se má chovat import, když karta dílu / dodavatele existuje, ale je deaktivovaná?**
   (a) automaticky reaktivovat a naskladnit, nebo (b) odmítnout s hláškou „Díl {sku} má deaktivovanou
   kartu — nejdřív ji aktivujte"? Varianta (a) je pro obsluhu pohodlnější, (b) zachovává záměr
   deaktivace. V obou případech je nutné z „pojistek" `findProductIdBySku` / `findSupplierIdByIco`
   odstranit filtr `AND is_active = TRUE`, jinak kolize na UNIQUE zůstane.

2. **S-10 — z čeho se má počítat marže?** Z položek zakázky (dnešní stav — „kolik jsme si spočítali")
   nebo z položek vystavené faktury (— „kolik jsme skutečně vyfakturovali")? Ovlivňuje to význam
   čísla na přehledu, ne správnost dokladu, takže je to volba majitele. Pokud zůstane dnešní stav,
   patří to do nápovědy.

3. **S-12 — má aktivace zákazníka vracet i jeho vozidla?** Automatická reaktivace vrátí i vozidla,
   která byla deaktivovaná samostatně **před** deaktivací zákazníka (např. prodané auto). Alternativa
   je kaskádu při deaktivaci vůbec nedělat a v detailu zákazníka neaktivní vozidla ukazovat
   (odlišená), aby bylo co aktivovat ručně.

4. **S-7 — full-text search: dotáhnout, nebo zrušit?** Konfigurace `customer.czech_simple` i GIN
   index existují a nikdo je nepoužívá. Buď je zahodit (a opravit `konvence.md`/`databaze.md`),
   nebo hledání zákazníků na FTS převést. Pozor: FTS hledá celá slova, takže „nov" přestane najít
   „Novák" — dnešní `LIKE '%nov%'` to umí. Doporučuji zahodit index a opravit dokumentaci.

5. **S-9 — kolik zákazníků/dílů se reálně čeká?** Kartézský součin v detailu zákazníka a N+1
   v inventuře jsou dnes neškodné. Jestli se počítá s flotilovými zákazníky (desítky vozidel) nebo
   se skladem v řádu tisíců karet, má smysl je opravit dřív; jinak je to bezpečně odložitelné.
