# Audit 1/9 — Backend jádro (customer, vehicle, order, billing + průřez)

> Součást hloubkového auditu 2026-07-24 (commit `409d3ad`, větev `audit-one`).
> Přehled celého auditu: [00-prehled.md](00-prehled.md).
>
> **Verifikace hlavního auditora:** nálezy V-1 (chybějící `gdpr_consent` v UPDATE + primitivní
> `marketingConsent` → `NOW()` vždy) a V-2 (plný `uq_invoices_order_id`, `findByOrderId` bez filtru
> stavu) ověřeny druhým čtením přímo v `CustomerMapper.xml:294-317`, `Customer.java:58-60`,
> `InvoiceServiceImpl.java:74`, `V14:40`. Potvrzeno.

Rozsah: všechny service impl třídy v zadání přečteny celé; nálezy ověřeny proti mapper XML, migracím (V2, V6, V12, V14, V15, V17, V25, V26, V32, V37), DTO, konvertorům a `GlobalExceptionHandler`. Známé dluhy (TD-11, 13, 16, 22, 24, 33, 40–46) nereportovány. Opravy TD-49…TD-55 namátkou ověřeny — **všechny jsou v kódu skutečně přítomné a úplné** (detail v sekci Pozitiva).

---

## Nálezy

### VYSOKÝ

#### V-1: Revokace GDPR souhlasu se při PUT zákazníka tiše zahodí — `gdpr_consent` chybí v UPDATE
- **Soubor:** `src/main/resources/mapper/CustomerMapper.xml:294-317` (blok `<set>`), v kombinaci s `src/main/java/cz/palo/autoservis/model/converter/CustomerConverter.java:132-134`
- **Popis:** TD-23 zavedl `Boolean gdprConsent` v `UpdateRequest` a `CustomerConverter.applyUpdate` ho aplikuje na doménu. Jenže `CustomerMapper.update` sloupec **`gdpr_consent` vůbec neobsahuje** — v `<set>` je `marketing_consent`, ale `gdpr_consent` ne.
- **Dopad:** `PUT /customers/{id}` s `gdprConsent: false` vrátí 200 a v odpovědi (verify-and-fetch z DB) zůstane `gdprConsent: true`. Odvolání souhlasu GDPR — právně významný úkon — je přes API nemožné a klient se o tom nedozví.
- **Vedlejší nález tamtéž:** `<if test="marketingConsent != null">` je na **primitivním** `boolean` domény (`Customer.marketingConsent`) — OGNL ho zabalí, podmínka je vždy true, takže `marketing_consent_at = NOW()` se **přepisuje při každé editaci zákazníka**, i když se souhlas nezměnil. Původní datum udělení souhlasu se ztrácí.
- **Oprava:** doplnit do `<set>` větev pro `gdpr_consent` (+`gdpr_consent_at`); timestampy nastavovat jen při skutečné změně (`marketing_consent_at = CASE WHEN marketing_consent IS DISTINCT FROM #{marketingConsent} THEN NOW() ELSE marketing_consent_at END`).
- **Confidence:** jistý (u zahazování gdprConsent), jistý (u přepisu marketing_consent_at).

#### V-2: Stornovaná faktura navždy zablokuje fakturaci zakázky — mrtvý konec workflow
- **Soubor:** `InvoiceServiceImpl.java:74-80` + `V14__init_billing_schema.sql:40` (`uq_invoices_order_id UNIQUE (order_id)`)
- **Popis:** `createFromOrder` odmítne zakázku, pokud k ní **jakákoli** faktura existuje: `if (invoiceMapper.findByOrderId(...).isPresent())` — bez filtru na stav. DB navíc drží plný `UNIQUE (order_id)`. Přitom oprava V2 (`OrderItemServiceImpl.requireOrderNotInvoiced:319-328`) po stornu faktury položky zakázky záměrně **odemyká** (`filter(inv -> inv.getStatus() != CANCELLED)`) — zjevný záměr byl „storno → oprav položky → vystav novou fakturu".
- **Dopad:** jediný reálný scénář stornování končí slepou uličkou: položky jdou opravit, ale novou fakturu už k zakázce nikdy vystavit nelze. Zakázka je trvale nefakturovatelná; odemykání položek po stornu je tím pádem k ničemu.
- **Oprava:** buď (a) povolit novou fakturu, existuje-li k zakázce jen CANCELLED faktura — filtr v service + nová migrace nahrazující unique **částečným unikátním indexem** `WHERE status <> 'CANCELLED'`, nebo (b) storno zakázat jako terminální řešení a položky po stornu neodemykat. Varianta (a) odpovídá záměru V2.
- **Confidence:** jistý (chování), pravděpodobný (že jde o rozpor se záměrem). **Pozn.:** tentýž nález nezávisle nahlásily i audity DB (N-1), SQL a domény (A1) — konsolidováno jako **klíčový nález K-1**.

#### V-3: Vazba zákazník ↔ vozidlo na zakázce není nikde vynucená
- **Soubory:** `OrderServiceImpl.java:86-91` (create), `VehicleServiceImpl.java:138-164` (update/přeřazení vozidla), `V6__init_order_schema.sql` (jen samostatné FK)
- **Popis:** Zakázka nese redundantní pár `customer_id` + `vehicle_id`, ale:
  1. `OrderServiceImpl.create` nevaliduje **nic** — nekontroluje existenci zákazníka/vozidla (spadne až na FK → generické 422 `DATA_INTEGRITY_VIOLATION`, porušení R-13), nekontroluje `is_active` ani `vehicle.customer_id == order.customer_id`. Zakázku lze založit na zákazníka A s vozidlem zákazníka B.
  2. `VehicleServiceImpl.update` umožňuje přeřadit vozidlo jinému zákazníkovi (`applyUpdate` přepíše `customerId`) **bez kontroly otevřených zakázek** — existující zakázky pak ukazují na zákazníka, kterému vozidlo už nepatří; `deactivate` přitom otevřené zakázky kontroluje.
- **Dopad:** rozjeté jádro datového modelu; nesmysl se propíše až do faktury. FE tomu dnes brání jen tím, že autocomplete vozidel filtruje `customerId` — backend je ale autoritativní.
- **Oprava:** v `OrderServiceImpl.create` načíst vozidlo, ověřit existenci (404), aktivitu obou stran (422) a `vehicle.getCustomerId().equals(createRequest.getCustomerId())` → `BusinessRuleException VEHICLE_NOT_OWNED_BY_CUSTOMER`. Ve `VehicleServiceImpl.update` při změně `customerId` ověřit otevřené zakázky.
- **Confidence:** jistý.

### STŘEDNÍ

#### S-1: Update mappery mají PATCH sémantiku — vyplněná pole nejdou vymazat (třída chyby jako opravený TD-54)
- **Soubory:** `CustomerMapper.xml:294-317`, `OrderMapper.xml:114-128`, `InvoiceMapper.xml:151-166`, `InvoiceItemMapper.xml:52-63`, `AddressMapper.xml:61-72`, `OrderItemMapper.xml:68-84` (smíšené)
- **Popis:** TD-54 sjednotil `SupplierMapper.update` na full-replace s odůvodněním „odlišné od ostatních modulů (full-replace)". Realita je opačná: full-replace mají jen Vehicle, Supplier, CompanyProfile a MileageHistory; Customer, Order, Invoice, InvoiceItem a Address skládají `<set>` z `<if test="x != null">`. Komentář v `CustomerServiceImpl.update:119-121` dokonce tvrdí *„applyUpdate() below is a full replace"* — pro Java objekt platí, ale mapper null hodnoty zahodí.
- **Dopad:** zákazník — `birthDate`, `ico`, `dic`, `legalForm`, `primaryEmail`, `primaryPhone`, `internalNote`, `preferredContactChannel` nejdou nikdy vynulovat; zakázka — `estimatedCompletionAt`, `estimatedPrice`, `finalPrice`, `completedAt` (znovuotevření omylem dokončené zakázky nemá jak smazat `completedAt`); faktura — `note`, `constantSymbol`, `specificSymbol`. Klient dostane 200 a verify-and-fetch mu vrátí starou hodnotu, kterou „smazal".
- **Oprava:** sladit na full-replace jako u TD-54, případně vědomě zdokumentovat PATCH — ale jednotně napříč moduly.
- **Confidence:** jistý.

#### S-2: Lze vystavit fakturu bez položek (0 Kč) — `issue()` nekontroluje položky
- **Soubor:** `InvoiceServiceImpl.java:380-401` (`transitionTo`), `365-374` (`deleteItem`)
- **Popis:** `createFromOrder` prázdnou fakturu odmítne (`ORDER_HAS_NO_ITEMS`), ale v DRAFT lze všechny položky smazat (`deleteItem` má jen `requireEditable`) a poté `POST /{id}/issue` projde — `transitionTo` kontroluje jen stavový přechod.
- **Dopad:** vystavená (nezměnitelná) faktura s nulovými součty — právně nesmyslný doklad; QR platba na 0.00.
- **Oprava:** v `transitionTo` při cíli ISSUED ověřit neprázdnost položek → `INVOICE_HAS_NO_ITEMS`.
- **Confidence:** jistý.

#### S-3: `addItem` přijme cizí `orderItemId` bez kontroly příslušnosti
- **Soubor:** `InvoiceServiceImpl.java:313-328` + `InvoiceItemDto.java:32-58` + `V14:59`
- **Popis:** Klient v těle `POST /invoices/{invoiceId}/items` posílá `orderItemId` a nikdo neověří, že položka patří k zakázce této faktury (ani že už na faktuře není). FK chytí jen neexistenci.
- **Dopad:** položka faktury může odkazovat na položku jiné zakázky (rozbitá auditní stopa), tutéž položku lze navěsit vícekrát; `ON DELETE RESTRICT` pak cizí zakázce zablokuje smazání položky (záhadné 422).
- **Oprava:** ověřit `orderItem.getOrderId().equals(invoice.getOrderId())` → `ITEM_NOT_OF_INVOICED_ORDER`; zvážit unikát `(invoice_id, order_item_id)`.
- **Confidence:** jistý.

#### S-4: TOCTOU na editaci DRAFT faktury — `update`/`addItem`/`updateItem`/`deleteItem` nemají status guard v SQL
- **Soubor:** `InvoiceServiceImpl.java:170-188, 313-374` + `InvoiceMapper.xml:151-166`
- **Popis:** Oprava K5 zavedla guardovaný UPDATE jen pro **přechody stavů**. Editace hlavičky a položek dělají check-then-act. Souběh s `issue()` zmutuje už vystavenou fakturu.
- **Dopad:** porušení invariantu „ISSUED je nezměnitelný právní doklad". Okno je malé, zápis tichý a nevratný.
- **Oprava:** stejný vzor jako K5 — `WHERE id = #{id} AND status = 'DRAFT'`, 0 řádků → 409.
- **Confidence:** jistý (mechanismus), scénář vyžaduje souběh.

#### S-5: `GlobalExceptionHandler` nemapuje běžné web výjimky — nevalidní JSON/enum, špatný typ query, špatná metoda → 500
- **Soubor:** `GlobalExceptionHandler.java` (celý — jediná advice v projektu)
- **Popis:** TD-52 přidal handler jen pro `MethodArgumentTypeMismatchException`. Chybí `HttpMessageNotReadableException` (vadný JSON, **neplatná enum hodnota v těle** — `"paymentMethod": "FOO"`), `HandlerMethodValidationException`, `HttpRequestMethodNotSupportedException` (405), `NoResourceFoundException` (404) — vše spadne do catch-all `Exception` → 500 `INTERNAL_ERROR` + ERROR log.
- **Dopad:** klientské překlepy vypadají jako pády serveru a plní log ERRORem — přesně to, co TD-52 řešil, jen pro ostatní vstupní cesty.
- **Oprava:** doplnit handlery (nebo podědit `ResponseEntityExceptionHandler`); pokrýt testem.
- **Confidence:** jistý u `HttpMessageNotReadableException`, pravděpodobný u přesných typů (závisí na verzi Springu; výsledek „500 z catch-all" platí).

#### S-6: Stránkovací parametry bez validace — `page=0` shodí každý seznam na 500, `pageSize` bez stropu
- **Soubor:** `model/dto/pagination/BaseParams.java:14-33`
- **Popis:** `page`/`pageSize` nemají `@Min`/`@Max` ani clamp. `getOffset() = (page-1)*pageSize` → `page=0` → OFFSET −20 → `OFFSET must not be negative` → 500. `pageSize=0` → dělení nulou v `PagedResponse.of`; `pageSize=100000` → dump celé tabulky.
- **Dopad:** každý stránkovaný endpoint jde poslat do 500 jedním query parametrem; chybí ochrana proti přetížení.
- **Oprava:** clamp v setterech nebo Bean Validation + `@Valid`.
- **Confidence:** jistý.

#### S-7: `POST/PUT` položky faktury vrací `net`/`vat`/`gross` = null — `findById` nepočítá rozpad ceny
- **Soubor:** `InvoiceItemMapper.xml:65-69` (`findById` bez počítaných sloupců) vs. `71-80` (`findByInvoiceId` s nimi); `InvoiceServiceImpl.fetchItemOrFail:455-460`
- **Dopad:** klient po přidání/úpravě řádku nemá součty řádku (UI musí přenačíst celý detail) — nekonzistentní kontrakt téhož DTO.
- **Oprava:** doplnit do `findById` stejné `ROUND(...)` výrazy (sdílet přes `<sql>` fragment).
- **Confidence:** jistý.

#### S-8: Autocomplete zákazníků nabízí i deaktivované — chybí `is_active` filtr
- **Soubor:** `CustomerMapper.xml:347-364` vs. `VehicleMapper.xml:228-246` (má `v.is_active = TRUE`)
- **Dopad:** deaktivovaný zákazník se nabízí; v kombinaci s V-3 lze bez varování založit zakázku na deaktivovaného zákazníka.
- **Oprava:** `WHERE is_active = TRUE` + kontrola aktivity v `OrderServiceImpl.create` / `VehicleServiceImpl.create`.
- **Confidence:** jistý.

#### S-9: Poslednímu adminovi lze přes `PUT /users/{id}` odebrat roli ADMIN — guard existuje jen pro deaktivaci
- **Soubor:** `UserServiceImpl.java:92-118` (update bez guardu) vs. `130-154` (deactivate s `CANNOT_DEACTIVATE_LAST_ADMIN`)
- **Dopad:** systém bez administrátora; celý `UserController` je `hasRole('ADMIN')` → správa účtů nedostupná, oprava jen zásahem do DB.
- **Oprava:** v `update` před `deleteRoles` ověřit, že po změně zůstane aspoň jeden enabled admin.
- **Confidence:** jistý. **Pozn.:** shodně nahlásil security audit (N2) — konsolidováno jako **K-2**.

#### S-10: Souběžné dvojité smazání položky zakázky vrátí zboží na sklad dvakrát
- **Soubor:** `OrderItemServiceImpl.java:255-281`
- **Popis:** `delete` je check-then-act: `findById` → INSERT `ISSUE_RETURN` → `delete(id)`, jehož návratová hodnota se **nekontroluje**. Dvě souběžné transakce vloží vratku obě, druhá smaže 0 řádků a tiše commitne → `quantity_remaining` naroste dvojnásobně.
- **Oprava:** nejdřív `int affected = delete(id)`, při 0 skončit; vratku vložit až po úspěšném DELETE.
- **Confidence:** jistý (mechanismus; vyžaduje souběh).

#### S-11: PDF faktury se vygeneruje pro DRAFT i CANCELLED, k nerozeznání od ostré faktury
- **Soubor:** `InvoiceDocumentController.java:31-39`; šablona `templates/pdf/invoice.html` slovo „status" neobsahuje.
- **Dopad:** koncept i **stornovaná** faktura se vyrenderují jako plnohodnotný doklad s QR platbou — lze omylem vydat zákazníkovi.
- **Oprava:** vodoznak „KONCEPT"/„STORNOVÁNO" podle `invoice.status`; přísněji 422 pro CANCELLED.
- **Confidence:** jistý (chování). **Pozn.:** shodně doména A3.

#### S-12: Fakturovat lze zakázku v libovolném stavu — i CANCELLED
- **Soubor:** `InvoiceServiceImpl.createFromOrder:65-158` — `order.getStatus()` se nikde nekontroluje.
- **Oprava:** guard `order.getStatus() == CANCELLED` → 422; zvážit whitelist COMPLETED/READY_FOR_PICKUP.
- **Confidence:** jistý (kód). **Pozn.:** shodně doména A6.

#### S-13: Vnořený zákazník u vozidla čte `is_active`/`created_at` z kolidujících sloupců vozidla
- **Soubor:** `VehicleMapper.xml:134-141` (`findById`), `178-187` (`search`) + `CustomerMapper.xml:59-72` (`CustomerEmbeddedResultMap` mapuje `is_active`, `created_at` bez prefixu)
- **Popis:** SELECT skládá `vehicleColumns` (obsahuje `v.is_active`, `v.created_at`) a hned za ně nealiasované `customerColumns` (`c.is_active`, `c.created_at`). Embedded resultMap zákazníka čte podle jména — JDBC vrátí **první výskyt**, tedy hodnoty **vozidla**.
- **Dopad:** `VehicleDto.DetailResponse.customer.active` a `customer.createdAt` ukazují stav vozidla, ne majitele.
- **Oprava:** aliasovat sloupce zákazníka (`c.is_active AS cust_is_active`) a použít `columnPrefix`.
- **Confidence:** pravděpodobný (plyne z chování JDBC při duplicitních labelech; potvrdit testem). **Pozn.:** nezávisle nahlásil i SQL audit (№6).

### NÍZKÝ

- **N-1:** `InvoiceDto.UpdateRequest.status` je po TD-49 mrtvé pole (`applyUpdate` řádek `setStatus` je mrtvý), javadoc controlleru dál tvrdí „status may be changed". Odstranit pole i řádek (R-12).
- **N-2:** Item endpointy ignorují rodičovské ID v cestě (`PUT /invoices/999/items/5` upraví položku 5 bez ohledu na 999); `MileageServiceImpl.requireReadingOfVehicle` tentýž vzor správně kontroluje. `GET /orders/{id}/items` pro neexistující zakázku vrací 200 `[]` místo 404.
- **N-3:** Kotvu INITIAL v historii tachometru lze přeznačkovat na SERVICE (`MileageServiceImpl.updateReading:111-118`) — relabel → delete obchází nesmazatelnost INITIAL.
- **N-4:** Číslo faktury z `CURRENT_DATE`, ale `issue_date` posílá klient → zpětně datovaná faktura má číslo z jiného měsíce (viz K-3).
- **N-5:** Mrtvý kód (R-12): `OrderService.findAllActive`, `VehicleMapper` (`findAllActive`, `findByVin`, `hardDelete`, `countByCustomerId`), `InvoiceItemMapper.deleteByInvoiceId`, `AddressMapper.clearDefault/update/delete`, nepoužité overloady `InvoiceConverter.toDetailResponse`, nepoužité importy v `OrderController`/`OrderItemController`.
- **N-6:** Konvence: `OrderDto`/`AddressDto` mají `private boolean isActive`/`isDefault` (§3 zakazuje prefix is); `Customer.toSummaryResponse()` je konverze v doméně (R-11); `VehicleDto.UpdateRequest` obsahuje `vin` a update ho mění — `konvence.md §12` přitom na vehicle příkladu deklaruje „vin: NENÍ — immutable" (rozpor kód×dokumentace); `Customer.getDisplayName()` vrací úvodní mezeru / null.
- **N-7:** `CustomerDto.CreateRequest.gdprConsent` je `@NotNull boolean` (na primitivu nikdy nevystřelí); `CustomerServiceImpl.create` nekontroluje duplicitní IČO (na rozdíl od update → generické 422); `handleValidation` iteruje jen `getFieldErrors()` (class-level violation by vypadla, dnes latentní); `activate` nevrací kaskádně vozidla po reaktivaci zákazníka; `activate/deactivate` nemají null-guard `id`.

---

## Pozitiva / poznámky ke konzistenci

**Ověření oprav TD-49…TD-55 (vše na místě a úplné):**
- TD-49: `InvoiceServiceImpl.update:183-185` — `originalStatus` se ukládá před `applyUpdate` a vrací zpět.
- TD-50: `PagedResponse.of:48-49` — `first(page <= 1)`, `last(page >= totalPages)`, 1-based.
- TD-51: `OrderMapper.xml` — `o.is_active` v `orderColumns:46` i result mapě.
- TD-52: `GlobalExceptionHandler:175-185` — handler → 400 `INVALID_ARGUMENT`.
- TD-53: `BlacklistMapper:33` — `ON CONFLICT (token) DO NOTHING`.
- TD-54: `SupplierMapper.xml:58-75` — statický full-replace, `country_code` přes `COALESCE`.
- TD-55: `VehicleConverter:37-39, 160-163` — null-guard na `getCustomer()`.

**Peníze a DPH — konzistentní a správně:** jediný vzor zaokrouhlení „ROUND po řádku (2 des.), pak SUM" je identický ve všech třech views (V25 order, V32 totals, V37 rekapitulace DPH) i v `InvoiceItemMapper.findByInvoiceId`; `gross = net + vat` sedí na haléř na řádku, v rekapitulaci i v součtu. Java nikde částky nepočítá (kromě prezentačního `setScale(2, HALF_UP)` v SPAYD QR).

**Stavový automat faktury:** přechodová tabulka v `InvoiceStatus` je úplná a uzavřená; `transitionTo` kombinuje 422 pro běžný případ s guardovaným UPDATE (K5) pro souběh. Díry nejsou v přechodech, ale okolo nich (S-2, S-4, V-2).

**Další silné stránky:** verify-and-fetch (R-03) důsledně všude vč. rozlišení 404 vs. 500; audit `created_by` výhradně z `@AuthenticationPrincipal`; `id` výhradně v URL; `OrderItemConverter.applyUpdate` zamyká skladová pole u položek z příjemky; K6 vzor v `importFromReceipt` korektní; ProblemDetail formát jednotný; `InvoiceParty` snapshot správný návrh.

**Poznámka:** zakázka nemá stavový automat (`OrderStatus` je holý enum, `UpdateRequest.status` se mění libovolně, `completedAt` se nesynchronizuje se stavem) — kód to sám přiznává v komentáři. Doporučeno vést jako explicitní TD; S-12 a V-2 na to narážejí.
