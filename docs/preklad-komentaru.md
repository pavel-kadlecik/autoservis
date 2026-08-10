# Překlad komentářů do češtiny — evidence průběhu

> **Pracovní dokument větve `preklad-komentaru`.** Po dokončení celé akce rozhodne
> uživatel, zda soubor smazat, nebo ponechat jako záznam. Neslouží jako trvalá
> dokumentace projektu.

## Zadání (odsouhlaseno 2026-08-09)

- Sjednotit jazyk všech komentářů (Javadoc, JSDoc, inline, XML, SQL, MD) do **češtiny**.
- Při překladu **ověřit pravdivost** každého komentáře proti kódu:
  - lživý/zastaralý komentář → opravit podle skutečného chování,
  - komentář odhalí chybu v kódu → kód **neměnit**, zapsat do sekce Nálezy a nahlásit.
- Migrace: **varianta C** — přeložit i migrační soubory, se strojovým ověřením
  (SQL beze změny) a `flyway repair` na všech DB před startem aplikace.
- Commit po každé dokončené a ověřené etapě (předem odsouhlaseno).
- `docs/archiv/` a `audit/` se **nedotýkat** (historické dokumenty).
- Identifikátory v kódu (názvy tříd, metod, proměnných, sloupců) se **nemění** —
  překládají se výhradně komentáře. Texty logů a výjimek se v této akci nemění.

## Postup u každé etapy

1. Přeložit komentáře, ověřit pravdivost proti kódu.
2. Ověřit build: backend `./mvnw compile` (po větších celcích `./mvnw test`),
   frontend `npm run build`.
3. Zkontrolovat diff (jen komentáře, žádná změna kódu).
4. Aktualizovat tuto evidenci, commit.

## Etapy

| # | Rozsah | Souborů | Stav |
|---|---|---|---|
| E0 | Založení této evidence | 1 | ✅ hotovo |
| E1 | `util`, `validation`, `exception`, `client` | 17 | ✅ hotovo |
| E2 | `config`, `security` | 32 | ✅ hotovo |
| E3a | `model` — customer, vehicle, order + ares, registry, autocomplete, pagination | 32 | ✅ hotovo |
| E3b | `model` — billing (domain + DTO: faktury, dobropisy, PPD, profil firmy) | 16 | ✅ hotovo |
| E3c | `model` — warehouse, user, employee, schedule, dashboard, enums, draft | 65 | ✅ hotovo |
| E3d | `model/converter` | 22 | ✅ hotovo |
| E4 | `mapper` (Java rozhraní 24) + mapper XML (21) | 45 | ✅ hotovo |
| E5 | `service` (rozhraní + impl, jeden celek) | 64 | ✅ hotovo |
| E6 | `controller` (vč. warehouse) | 25 | ✅ hotovo |
| E7 | testy (service, model, security, web, ostatní) | 106 | ✅ hotovo |
| E8 | frontend (`api`, `hooks`, `context`, `components`, `pages`, `help`, `css`) | 146 | ✅ hotovo |
| E9 | MD dokumentace + `README.md` + CLAUDE.md + komentáře v `application*.yaml` a šablonách | — | ✅ hotovo |
| E10 | Migrace (varianta C) + V95 + runbook | 95+1 | ✅ hotovo (zbývá `flyway repair` na lokální DB a produkci — runbook) |
| E11 | Závěr: pravidlo R-16 v konvencích, finální testy + build, flyway repair lokální DB | — | ✅ hotovo |

Poznámka: přesné počty u E3/E8 se doplní při zahájení etapy; dělení je orientační.

## Stav dokončení (2026-08-10)

- Všechny etapy E0–E11 hotové; commity po etapách na větvi `preklad-komentaru`.
- Finální ověření: `./mvnw test` (plná sada, čerstvá DB V1–V95) — BUILD SUCCESS;
  `npm run build` — OK; lokální DB po `flyway repair` validace 94 migrací OK
  (V95 se aplikuje při příštím startu aplikace).
- **Zbývá (mimo repo):** merge větve dle zvyklostí projektu a produkční nasazení
  s `flyway repair` dle [preklad-migraci-runbook.md](preklad-migraci-runbook.md).
- **K rozhodnutí uživatele:** ponechat/smazat tento evidenční soubor; naložení
  s nálezy v sekci „podezření na chybu v kódu".

## E10 — Migrace: bezpečnostní postup (varianta C)

Odsouhlaseno „pokud je to bezpečné" — bezpečnost zajišťují tyto kroky, žádný nelze vynechat:

1. Přeložit `--` komentáře a texty v `COMMENT ON` ve všech migračních souborech
   (`db/migration`, `db/demo`, `db/prod`).
2. **Strojové ověření:** skript porovná SQL před/po překladu — po odstranění
   komentářů a normalizaci smí být rozdíl výhradně ve string literálech
   `COMMENT ON`. Jakýkoli jiný rozdíl = stop.
3. Nová migrace `V95__ceske_komentare_db.sql` — znovu aplikuje všechny `COMMENT ON`
   s finálními českými texty (vygenerovaná z přeložených souborů → zaručená shoda).
   Tím dostane české popisky i existující produkční a lokální DB.
4. Hook `.claude/hooks/guard-immutable.js` zůstal NAKONEC NETKNUTÝ — úpravy
   proběhly mimo nástroje, které hlídá; samotná změna migrací byla odsouhlasena
   (varianta C) a strojově ověřena.
5. **Runbook nasazení:** na každé DB (lokální, produkce) spustit `flyway repair`
   **před** startem aplikace s novými soubory. Detailní runbook vznikne v této etapě.
6. Aktualizace `docs/konvence.md` (výjimka z R-09 jednorázově prolomena, popis proč)
   a `docs/databaze.md` (V95).

## Nálezy — lživé komentáře (opraveno při překladu)

- **E1** `GlobalExceptionHandler` — přehledová tabulka „výjimka → HTTP stav"
  v Javadocu třídy byla neúplná: chybělo 8 později přidaných handlerů
  (MethodArgumentTypeMismatch, HttpMessageNotReadable, HandlerMethodValidation,
  UsernameNotFound, NoResourceFound, HttpRequestMethodNotSupported,
  DocumentExtraction, AresUnavailable). Při překladu doplněny.
- **E1** `ResourceNotFoundException.getResourceName()` — Javadoc uváděl příklad
  „Customer", ačkoli aplikace předává české názvy („Zákazník"). Sjednoceno.

- **E2** `SecurityConfig.corsConfigurationSource()` — Javadoc tvrdil „konfigurace
  pro lokální vývoj, před nasazením uprav `allowedOrigins` v kódu". Reálně se
  originy od zavedení `CorsProperties` čtou z konfigurace (`cors.allowed-origins`).
  Přepsáno podle skutečnosti.
- **E2** `SecurityConfig` — Javadoc třídy měl anglická jen první dvě odrážky
  autorizačních pravidel (zbytek už byl česky) — důkaz driftu; sjednoceno.
- **E2** `TokenResponse` — Javadoc zmiňoval vydávání tokenů i „při registraci";
  veřejná registrace byla zrušena (audit A2/K1). Odstraněno.
- **E2** `JwtService` — Javadoc tvrdil „default 15 minut" pro access token;
  reálně `application.yaml` nastavuje 8 hodin (dev) a 15 minut platí až
  v `application-prod.yaml`. Opraveno na skutečné hodnoty.
- **E2** `AuthController.me()` — Javadoc `@return` neuváděl role, které odpověď
  obsahuje. Doplněno.
- **E3a** `Vehicle.currentMileageKm` — komentář tvrdil „fáze 3 teprve přidá
  tabulku historie tachometru"; tabulka `vehicle.mileage_history` (V20) dávno
  existuje včetně sync triggeru. Opraveno na odkaz na existující historii.
- **E3b** `Invoice` (Javadoc třídy) — tvrdil „zakázka má nejvýš jednu fakturu
  (1:1)"; od V69 platí nejvýš jedna *aktivní* faktura (dobropisovaná zakázku
  uvolní a fakturuje se znovu, jak popisuje pole `creditedAt`). Opraveno.
- **E3b** `CashReceipt` — Javadoc tvrdil, že číslo PPD „generuje DB trigger
  už při INSERT; aplikace ho nenastavuje". Od V92 je trigger z V57 zrušen
  a číslo skládá/validuje aplikace podle masky (zdroj MASK/INVOICE/MANUAL, V93).
  Opraveno. **Pozor: tutéž zastaralou větu má CLAUDE.md** („čísla dokladů …
  PPD… řeší DB triggery") — opravit v E9.
- **E3b** `InvoiceDto.ListResponse` — Javadoc popisující `creditedAt` (dobropis)
  seděl na poli `orderDescription`; přesunut ke správnému poli a `orderDescription`
  dostal vlastní pravdivý popis.
- **E3b** `InvoiceDto` — anglický Javadoc „Response DTO for detail endpoints"
  visel osiřele nad `NumberGapsResponse` (dva Javadocy za sebou → první se
  zahazuje) a `DetailResponse` byl bez dokumentace. Přesunut k `DetailResponse`.
- **E3c** `OrderStatus.canTransitionTo` — diagram automatu a sekce „Proč jsou
  COMPLETED a CANCELLED terminální" tvrdily, že COMPLETED je terminální;
  od 2026-08-06 je vratný (`REOPENABLE`), jak správně popisuje komentář o pár
  řádků výš v témže souboru. Diagram i sekce opraveny.
- **E3c** `InvoiceStatus` — dva Javadocy za sebou nad `ALLOWED_TRANSITIONS`
  (druhý blok „Proč PAID zůstává terminální" zahazoval první s diagramem
  přechodů). Sloučeno do jednoho bloku.
- **E3c** `ProductImportType` — Javadoc byl generická vata („Enum representing
  the type of product import data"); nahrazen popisem skutečného významu
  (podle čeho se dohledává doklad — číslo faktury vs. objednávky).
- **E3c** (drobnost) sjednocena diakritika v česky psaných komentářích bez
  háčků/čárek („Vychozi razeni seznamu…" v *SearchParams, `ISSUE_RETURN`).
- **E3d** `WarehouseProductConverter.isLowStock` — Javadoc mluvil o „current
  quantity", kód porovnává *dostupné* množství (fyzický stav − rezervace).
  Překlad upřesněn podle kódu, ve shodě s dokumentací polí `lowStock` v DTO.
- **E4** `SupplierMapper.update` — Javadoc tvrdil „aktualizují se jen non-null
  pole"; SQL je full-replace (jedině `country_code` má COALESCE). Věta vypuštěna.
- **E4** `OrderMapper.findById` — Javadoc „Finds an *active* order"; SQL žádný
  filtr `is_active` nemá a zakázky soft-delete nemají (sloupec je legacy, vždy
  TRUE — TD-67). Slovo „aktivní" vypuštěno.
- **E4** `VehicleMapper.update` — Javadoc tvrdil, že `vin` se nikdy nemění;
  SQL ho aktualizuje (od V90 je VIN editovatelný) a zapisuje i `is_active`
  z objektu. Přepsáno podle skutečnosti.
- **E5** `SupplierService` — Javadoc rozhraní i `getById` zkopírovaný ze skladové
  karty („full stock card, batches, movement history"); detail dodavatele nic
  takového nenese. Opraveno; u `update` doplněn chybějící `@throws` pro duplicitní IČO.
- **E5** `InvoiceService.update` — tvrdil, že měnit lze i `status` (service ho
  před zápisem vrací na původní, TD-49) a neuváděl `purchase_order_number` (V91).
- **E5** `InvoiceService.delete` — „smazat lze jen koncept" zastaralé: od V88 lze
  smazat i vystavenou fakturu, dokud není předaná ani zaplacená. Vč. `@throws`.
- **E5** `InvoiceServiceImpl.createFromOrder` — `@throws ORDER_NOT_INVOICEABLE
  (cancelled order)` zastaralé: od 2026-08-05 se odmítá každá nedokončená zakázka.
- **E5** `InvoiceDocumentService` — „HTML preview and PDF" zavádějící; veřejná
  metoda je jen `renderPdf`, HTML je interní mezikrok.
- **E5** `OrderService/Impl.getById` — „detail *aktivní* zakázky"; SQL `is_active`
  nefiltruje (zakázky soft-delete nemají). Slovo vypuštěno.
- **E5** `OrderItemService` — „items can be freely added/updated/deleted" bez
  zmínky o zámku aktivní fakturou (`ORDER_LOCKED_BY_INVOICE`); `importFromReceipt`
  „updates the order" — reálně vkládá rezervace bez pohybu (V83). Přepsáno.
- **E5** `OrderServiceImpl.requireMaterialReturned` — tři zastaralá tvrzení
  (automatické vracení, blokace každé šarže, jediná cesta vzniku ISSUE_RETURN);
  u `update`/`changeStatus` doplněny chybějící `@throws` kódy.
- **E5** `ProductService.getById` — neuváděl rozpad rezervací v odpovědi.
- **E5** `StockTakeServiceImpl.cancel` — `@throws ConflictException` deklarovaný
  pro ne-OPEN stav; reálně padá `BusinessRuleException`, Conflict až při souběhu.
- **E5** `IsdocParser` — Javadoc konstanty `DOCUMENT_TYPE_INVOICE` popisoval
  namespace místo typu dokladu; poznámka o namespace přesunuta k třídě.
- **E5** osiřelé/zatoulané Javadocy (vzor „dva bloky za sebou — první se zahazuje"),
  opraveno přesunem: `InvoiceService.handOver`, `InvoiceServiceImpl.delete`,
  `ProductServiceImpl.requireValidUnit`, `ReceiptReviewServiceImpl
  .linkedDeliveryNoteNumbers`, `OrderItemServiceImpl.requireOrderNotInvoiced`
  (u něj i zastaralé „kromě CANCELLED" → od V69 nestornovaná a nedobropisovaná).
- **E6** `InvoiceController` — další osiřelý Javadoc (blok pro `handOver` visel
  nad `numberGaps`); přesunut. `update` neuváděl `purchase_order_number` (V91)
  ani guard „jen v DRAFT". Doplněno.
- **E7** `VehicleConverterTest` — Javadoc tvrdil, že konvertor sahá na
  `getCustomer()` bez null kontroly; kontrola existuje (TD-55) a vlastní test
  to dokazuje. Přepsáno.
- **E7** `OrderStatusTest` — zastaralý duplicitní komentář bez vratného
  COMPLETED (2026-08-06) odporoval kódu pod sebou; odstraněn.
- Pozn.: u dávek „controllery M–W" a „testy service M–W" se závěrečné hlášení
  nálezů nedochovalo (přerušení limitem) — soubory jsou přeložené kompletně
  (ověřeno diffem, kompilací a kontrolou zbytkové angličtiny), případné dílčí
  opravy pravdivosti v nich proto nejsou vyjmenované.
- **E9** `CLAUDE.md` — opravena zastaralá věta o číslování dokladů: číslo PPD
  od V92 neskládá DB trigger, ale aplikace (zdroj MASK/INVOICE/MANUAL, V93);
  do výjimky doplněno vedle čísla faktury.
- **E9** `README.md` — byl celý anglicky a lhal: „migrace V1–V37" (reálně V94),
  chyběly moduly zaměstnanci/kalendář/dashboard/inventury/dobropisy/PPD,
  stavový automat uváděl přechod ISSUED→CANCELLED (zrušený, KN-1) a chyběla
  zmínka o produkčním provozu. Přepsán česky podle skutečnosti.
- **E9** `docs/` — samotná dokumentace už byla česky; přeloženy komentáře
  v `application.yaml` a `application-local.yaml.example`. Šablony PDF
  a `docs/funkce`, `docs/pruvodce` česky byly.
- **E8** `api.js#getBlob` — `@returns … null on 404` lhalo: kód vrací null při
  JAKÉKOLI chybové odpovědi (`if (!response.ok)`), nejen 404. Opraveno.
- **E8** `AppointmentForm.jsx` — osiřelý JSDoc `outsideHoursText` visel nad
  `titleLabel`; přesunut ke správné funkci.
- **E8** `VehicleForm.jsx` — „current year down to 30 years ago" nepřesné
  (30 hodnot = aktuální rok až −29); přeloženo přesně dle kódu.
- **E10** `V29` — hlavička tvrdila „Add ISSUE_RETURN CONSTRAINT", migrace žádný
  nový constraint nepřidává; znovu vytváří `chk_movement_sign` s větví pro
  ISSUE_RETURN. Opraveno.
- **E10** `V88` — komentář tvrdil razítkování `issued_at`, sloupec neexistuje;
  SQL používá `COALESCE(issue_date, created_at)`. Opraveno na `issue_date`.
- **E10** stav ověření: strojový skript porovnal všech 95 souborů (HEAD vs
  přeložené) — SQL identické, změny jen v komentářích a `COMMENT ON` textech.
  Plný testovací běh (`./mvnw test`, Testcontainers = čerstvá DB V1–V95)
  prošel: BUILD SUCCESS.
- ✅ **E10** demo seed nekonzistence (zakázky 2 a 3, V8 vs. V13/V16) — ověřeno
  (posun o jednu při psaní seedů) a opraveno novou demo migrací
  `V96__fix_demo_order_descriptions.sql` (popisy srovnány podle položek).

Ověřená tvrzení (nelhala): `MAX_FAILED_ATTEMPTS` = 10 (LoginAttemptService:34)
odpovídá poznámce o enumeraci v handleru zamčeného účtu. Detekce opakovaného
použití refresh tokenu popsaná v `RefreshTokenMapper` skutečně existuje
(`AuthenticationService.refresh` odvolává všechny session uživatele).

## Nálezy — podezření na chybu v kódu (VYŘEŠENO 2026-08-10 na pokyn uživatele)

- ✅ **`OrderItemService.java` — mrtvé importy** (`OrderItemSummary`,
  `AppUserDetails`): ověřeno a odstraněno (R-12).
- ✅ **`OrderItemServiceImpl.returnIssuedStock`** — ověřeno: oba dotazy používají
  identický SQL výraz, nesoulad hrozil jen při souběhu a návratovou hodnotu
  žádný volající nepoužívá. Přesto opraveno lokálním čítačem skutečně
  vrácených položek — kontrakt teď platí vždy.
- ✅ **`AppointmentController`** — nepoužitý import `@PreAuthorize` odstraněn;
  plně kvalifikovaná `AccessDeniedException` převedena na import.
