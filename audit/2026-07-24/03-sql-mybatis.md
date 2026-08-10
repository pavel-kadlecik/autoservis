# Audit 3/9 — SQL vrstva (MyBatis + PostgreSQL)

> Součást hloubkového auditu 2026-07-24 (commit `409d3ad`, větev `audit-one`).
> Přehled celého auditu: [00-prehled.md](00-prehled.md).
>
> **Verifikace hlavního auditora:** nálezy №1–2 (chybějící `gdpr_consent`, `<if>` na primitivním
> `marketingConsent` → `NOW()` vždy) a №6 (`CustomerEmbeddedResultMap` mapuje `is_active`/`created_at`
> bez prefixu, `customerColumns` je hned za `vehicleColumns`) ověřeny přímo — potvrzeno v
> `CustomerMapper.xml:59-72,294-317`, `Customer.java:58-60`.

Rozsah: všech 21 XML mapperů, 22 mapper rozhraní, doménové třídy, PgEnumTypeHandler, migrace V1–V44 i volající services/konvertory. Každý nález ověřen proti druhému zdroji (migrace + doména + XML + volající kód). `${…}` se v mapperech **nevyskytuje** — SQL injection přes string substituci: 0 nálezů.

---

## Ověření oprav známých dluhů

| Dluh | Stav |
|---|---|
| **TD-51** (nečtený `is_active` zakázek) | ✅ úplně — `OrderMapper.xml:46` + result mapa |
| **TD-53** (logout neidempotentní) | ✅ úplně — `BlacklistMapper.java:34` `ON CONFLICT … DO NOTHING` |
| **TD-54** (PATCH sémantika update dodavatele) | ✅ jen pro dodavatele — **tentýž vzor zůstává v Customer/Order/Invoice** (viz №3–5) |
| **TD-46** (řazení seznamů) | ✅ v mapperech dotaženo nad rámec textu dluhu — whitelist vč. směru i pro zakázky a příjemky, `sortDesc` respektuje i `<otherwise>` ve všech 8 seznamech. Text dluhu je zastaralý. |

---

## Nálezy

### VYSOKÁ

**№1 — Změnu GDPR souhlasu přes `PUT /customers/{id}` SQL tiše zahodí** (`CustomerMapper.xml:294-317`). `<set>` neobsahuje `gdpr_consent`, ač DTO i konvertor pole mají. 200 OK, hodnota v DB beze změny. Confidence: jistý. *(Duplicitně s [01-backend-jadro.md](01-backend-jadro.md) V-1 → klíčový nález K-4.)*

**№2 — `marketing_consent_at` se přepisuje na `NOW()` při KAŽDÉM update** (`CustomerMapper.xml:306-309`). `<if test="marketingConsent != null">` na primitivním `boolean` (`Customer.java:58`) je vždy true → auditní timestamp GDPR se ztrácí. Confidence: jistý.

**№3 — PATCH (SQL) × full-replace (konvertor): nullable pole zákazníka nejde vymazat** (`CustomerMapper.xml:294-317` × `CustomerConverter.applyUpdate`). `dic`, `legalForm`, `birthDate`, `primaryEmail`, `primaryPhone`, `internalNote` nelze přes PUT vynulovat — chyba TD-54 opravená jen u dodavatelů; komentář v service tvrdí opak. Oprava: full-replace jako `SupplierMapper`. Confidence: jistý.

**№4 — Totéž u zakázek** (`OrderMapper.xml:114-128`): `completed_at`, `final_price`, `estimated_*`, `internal_note` nejde vymazat. Zakázka vrácená z COMPLETED do IN_PROGRESS si nechá `completed_at`. Confidence: jistý.

**№5 — Totéž u faktur** (`InvoiceMapper.xml:151-166`): `constant_symbol`, `specific_symbol`, `note` (tiskne se na doklad) nejde vymazat. Confidence: jistý.

> №1–5 jsou jedna rodina: **UPDATE statement vs. full-replace konvertor**. Nejrychlejší plošná náprava vysokých nálezů: sjednotit UPDATE zákazníka/zakázky/faktury na full-replace podle vzoru `SupplierMapper` a doplnit `gdpr_consent`.

### STŘEDNÍ

**№6 — Duplicitní labely: vnořený `Customer` u vozidla čte `is_active`/`created_at` VOZIDLA** (`VehicleMapper.xml:134-141,178-187` + `CustomerMapper.xml:59-72`, `78-88`). SELECT nese `v.is_active` i `c.is_active` pod stejným labelem → PgJDBC vrací první (vozidlo). V dotazech **bez JOINu** (`findByIdIncludingInactive`, `findByVin`, `findByCustomerId`) vzniká „duchový zákazník" (id správně, jméno prázdné, active/createdAt z vozidla), který odchází v odpovědích PUT/DELETE. Oprava: prefix `c.is_active AS cust_is_active` + `columnPrefix`. Confidence: jistý. *(Shodně [01-backend-jadro.md](01-backend-jadro.md) S-13.)*

**№7 — Vozidla v detailu zákazníka mají `stkValidUntil` vždy NULL** (`CustomerMapper.xml:114-134` `vehicleColumns` — chybí `v.stk_valid_until AS v_stk_valid_until` a `v.engine_code`, ač `VehicleResultMap` je mapuje). Vzor TD-51. FE STK v tabulce vozidel zákazníka nezobrazuje → latentní. Confidence: jistý.

**№8 — Autocomplete zákazníků nabízí i deaktivované** (`CustomerMapper.xml:347-364` — bez `is_active = TRUE`). Confidence: jistý. *(Shodně [01-backend-jadro.md](01-backend-jadro.md) S-8.)*

**№9 — `GET /products?goodsReceiptId=` vrací `active: null` u všech produktů** (`WarehouseMapper.xml:201-210` `findByGoodsReceiptId` používá `resultType`, ne `ProductResultMap`). `map-underscore-to-camel-case` převede `is_active`→`isActive`, ale property je `active` → auto-mapping sloupec zahodí → `Boolean active = null`. **Pozn.:** komentář v `konvence.md:267` („is_active → active") je nepřesný — funguje to jen díky explicitním resultMapám. Oprava: `resultMap="ProductResultMap"`. Confidence: jistý.

**№10 — Odpověď POST/PUT položky faktury nemá spočtené `net/vat/gross`** (`InvoiceItemMapper.xml:65-69` `findById` bez počítaných sloupců). Confidence: jistý. *(Shodně [01-backend-jadro.md](01-backend-jadro.md) S-7.)*

**№11 — Párování dílů: prázdný seznam čísel → `IN ()` → SQL syntax error (500)** (`ProductMatchingMapper.xml:20-33` `<foreach>` bez guardu). Katalogové číslo složené jen ze separátorů (`"-"`, běžný zástupný znak „bez čísla") → prázdný list → `PSQLException` místo degradace na párování podle názvu. Oprava: guard v service (`if (!numbers.isEmpty())`). Confidence: pravděpodobný.

**№12 — Dedup dodacích listů matchuje i STORNOVANÉ příjemky** (`WarehouseImportMapper.xml:73-87` `findDeliveryNoteReceiptId` — jen `status != 'REJECTED'`; `existsActiveDocument:22-32` vylučuje REJECTED **i** CANCELLED — nekonzistence z V43). Scénář: DL příjemka se stornuje → souhrnná faktura s referencí na tentýž DL se spáruje se stornovaným dokladem → `LINKED` → řádky se nematerializují → **zboží nenaskladněno vůbec**. Oprava: `AND gr.status NOT IN ('REJECTED','CANCELLED')`. Confidence: pravděpodobný.

**№13 — Pseudo-příjemky inventury mají v autocomplete `description = NULL`** (`GoodsReceiptMapper.xml:54-57`, `TO_CHAR(NULL)` zNULLuje celý řetězec `||`). Confidence: jistý.

### NÍZKÁ

- **№14 — Mrtvé mapper metody (R-12), dvě latentně rozbité:** `UserMapper.save()` (`@Insert` **nevkládá `email`** NOT NULL → spadl by; nikde nevoláno po K1); `AddressMapper.clearDefault` (nekvalifikovaný cast `::address_type` místo `customer.address_type` → selhal by; nevoláno); dále nevolané `AddressMapper.findByCustomerId/update/delete`, `VehicleMapper.hardDelete` (proti R-06), `findByVin`, `findAllActive`, `countByCustomerId`, `CustomerMapper.existsByCustomerNumber`.
- **№15 — `StockTakeMapper.updateItem` bez SQL guardu na stav OPEN** (`:116-122`; service dělá check-then-act). Souběh `updateItems` × `close` může zapsat do uzavírané inventury. `AND EXISTS (… status='OPEN')`. Confidence: pravděpodobný.
- **№16 — `"order".orders` nemá žádný index na FK/status** (V6 nevytváří žádný `CREATE INDEX`). Dotazy `countOpenByCustomerId/VehicleId` (volané při každé deaktivaci) + JOINy jedou sekvenčně. Jediná tabulka s FK bez indexů. Confidence: jistý, dopad na dnešních datech nízký. *(Shodně [04-databaze.md](04-databaze.md) N-7.)*
- **№17 — Nekonzistentní fulltext:** `unaccent`+tokeny mají jen Customer/Order; Vehicle/Invoice/Supplier/User/ReceiptReview používají jednofrázové `LIKE` bez `unaccent` (Novak nenajde Novák). Žádný search neescapuje `%`/`_`. GIN `idx_customers_fts` (V2) zůstává nevyužitý. Confidence: jistý.
- **№18 — Drobnosti:** `CustomerMapper.findById` JOINované kolekce bez `ORDER BY` (pořadí adres/vozidel nedeterministické); `uq_customers_ico` plné UNIQUE + `""` v UpdateRequest → dva zákazníci s „vymazaným" IČO kolidují (ukládat NULL); `GoodsReceiptMapper.autocomplete` bez id tie-breakeru; prázdný `<set>` teoreticky syntax error (kryto `@NotNull` status — křehké, №4/№5 to řeší); `OrderMapper.findById` permissive bez vysvětlujícího komentáře (na rozdíl od `CustomerMapper` „INTENTIONALLY lenient").

---

## Systematická kontrola (mapper → výsledek)

| Mapper | Výsledek |
|---|---|
| CustomerMapper | ⛔ №1, №2, №3, №6, №7, №8, №18; fragmenty jinak úplné, whitelist OK |
| AddressMapper | ⚠️ №14 (4 mrtvé, `clearDefault` vadný cast); aktivní části OK |
| ContactPersonMapper | ✅ OK |
| VehicleMapper | ⛔ №6; ⚠️ №14, №17; strict findById správně a zdokumentovaně |
| MileageHistoryMapper | ✅ OK (full-replace, ENUM handler, řazení = index V20) |
| RegistrySnapshotMapper | ✅ OK (JSONB cast, append-only) |
| OrderMapper | ⛔ №4; ⚠️ №16, №18; TD-51 fix potvrzen; fulltext tokeny+unaccent OK |
| OrderItemMapper | ✅ OK (reorder s guardem, view V25/V26 sedí; smíšený PATCH = záměr zamčených polí šarží) |
| InvoiceMapper | ⛔ №5; ⚠️ №10, №17; guardovaný updateStatus (K5) ✅; count=search JOINy identické |
| InvoiceItemMapper | ⛔ №10; net/vat/gross = stejné zaokrouhlení jako views ✅ |
| InvoicePartyMapper | ✅ OK (immutable snapshot, žádný UPDATE) |
| CompanyProfileMapper | ✅ OK |
| UserMapper (XML) | ✅ OK — vzorové stránkování nad kolekcí rolí (subquery s LIMIT, vnější ORDER BY) |
| RoleMapper | ✅ OK |
| warehouse/WarehouseMapper | ⛔ №9; LATERAL u low-stock OK; view V42 sedí; (V-1 inventurní šarže viz sklad audit) |
| warehouse/SupplierMapper | ✅ OK — TD-54 fix potvrzen |
| warehouse/GoodsReceiptMapper | ⚠️ №13, №18; findByIdsForUpdate (K6) čistý |
| warehouse/WarehouseImportMapper | ⚠️ №12; idempotence importu správně |
| warehouse/ReceiptReviewMapper | ✅ OK — guardované přechody, BYTEA/JSONB oddělené selecty, FOR UPDATE u storna |
| warehouse/StockTakeMapper | ⚠️ №15; FIFO manko s FOR UPDATE OF gri ✅ |
| warehouse/ProductMatchingMapper | ⚠️ №11; normalizace zrcadlí generovaný sloupec V40 |
| security/BlacklistMapper | ✅ OK — TD-53 potvrzen |
| security/RefreshTokenMapper | ✅ OK (revoke-not-delete) |
| security/UserMapper.java | ⚠️ №14 (`save` bez emailu, mrtvý) |

**Interface × XML:** každá statement má metodu a naopak — jediné disproporce jsou mrtvé metody z №14. Typy parametrů sedí.

---

## Pozitiva

- **Žádné `${}`** v žádném z 21 XML; ORDER BY whitelisty se směrem uvnitř každé větve vč. `<otherwise>` a id tie-breakerem.
- **Souběh systematicky a správně:** guardovaný `updateStatus` faktury (K5), guardované `confirm/reject/cancel/close`, `FOR UPDATE` na šaržích (K6), stornu i rozpouštění manka.
- **`UserMapper.search` je učebnicové řešení pasti „LIMIT × kolekce"** — stránkuje ID v poddotazu, JOIN rolí vně, duplikovaný ORDER BY; žádný jiný mapper do pasti nespadl.
- **Views V25/V26/V32/V37/V42 přesně sedí na resultMapy**, jednotné zaokrouhlování po řádku; `<bind>`-ve-`<foreach>` past vyřešená a zdokumentovaná v XML.
- Plná kvalifikace tabulek (vč. `"order"`) všude; ENUMy konzistentně přes `PgEnumTypeHandler`/`jdbcType=OTHER`.

**Souhrn:** 0 kritických, 5 vysokých (jedna rodina UPDATE vs. konvertor), 8 středních, 5 nízkých.
