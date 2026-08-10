# 10 — Dokumentace: Javadoc/JSDoc, docs/, nápověda v aplikaci

> Audit 2026-07-30 · rozsah: (A) Javadoc/JSDoc v `src/main/java` a `frontend/…/src`,
> (B) uživatelská nápověda `frontend/autoservis-frontend/src/help/`,
> (C) `docs/` (api, architektura, backend, frontend, konvence, funkce, tech-dluhy; `databaze.md` jen povrchově).
> Metoda: grep na nalezení místa → přečtení celého souboru → ověření tvrzení proti kódu/SQL z obou stran.
> Testy nespouštěny. Neauditováno: `docs/archiv/`, `audit/2026-07-24/`.

## Co bylo přečteno

**Pravidla a mapa:** `CLAUDE.md`, `docs/konvence.md`, `docs/tech-dluhy.md`.

**docs/ (celé):** `api.md`, `architektura.md`, `backend.md`, `frontend.md`, `funkce/dashboard.md`,
`funkce/sklad-pohyby.md`; částečně `databaze.md` (hlavička, §4–§6b, §10, §11), `plan-ui.md`,
`plan-oprav.md`, `plan-sklad.md`.

**Java (celé soubory):** `config/security/SecurityConfig`, `security/controller/AuthController`,
`security/filter/JwtAuthenticationFilter`, `security/service/AuthenticationService`,
`security/mapper/UserMapper` (+ `UserMapper.xml`), `controller/{Customer,Vehicle,Order,OrderItem,
Mileage,User,Employee,Invoice,CashReceipt,CreditNote,Dashboard,CodeList,CompanyProfile}Controller`,
`controller/warehouse/ProductController`, `service/{Customer,Vehicle,Product,OrderItem,User,Invoice,
StockTake}Service`, `service/impl/{Customer,Vehicle,Product,OrderItem,User,Invoice,StockTake,
ReceiptReview}ServiceImpl`, `model/converter/{Invoice,InvoiceItem,Vehicle}Converter`,
`model/dto/customer/{CustomerDto,CustomerSearchParams}`, `model/dto/warehouse/StockMovementDto`,
`model/enums/{InvoiceStatus,OrderStatus}`.

**SQL:** `mapper/CustomerMapper.xml`, `mapper/UserMapper.xml`, sort-whitelisty ve všech 8 mapperech,
`db/migration/V49__invoice_number_on_issue.sql`, `V63__order_item_summary_cost.sql`,
`db/prod/V60__prod_seed.sql`, `db/demo/V3__seed_initial_data.sql`.

**Frontend:** všech **15 článků nápovědy** (`src/help/*.md`), `src/help/index.js`, `pages/HelpPage.jsx`,
`App.jsx`, `components/navigation.js`, `components/{StockMovementModal,OrderItemsToolbar,
TableRowActionMenu}.jsx`, `pages/{CustomersPage,CustomersPageDetail,VehiclesPage,InvoicesPageDetail,
DashboardPage,ReceiptsPage}.jsx` (relevantní části), `scripts/check-ui.mjs` (pravidla).

## Shrnutí

Dokumentace projektu je nadprůměrně bohatá a v jádru poctivá — `docs/funkce/`, `docs/databaze.md`
a většina Javadoců drží krok s kódem. Problém je **jednosměrný a systematický**: když se funkce
rozšíří, aktualizuje se ten dokument, který se právě píše, a ostatní tři popisy téže věci zůstanou
v původním stavu. Nejvýrazněji je to vidět u ručních skladových pohybů (rozšířeny ze 2 na 4 typy —
DTO a `api.md` aktualizovány, `ProductService`, `ProductController`, `StockMovementModal` a článek
nápovědy ne) a u bezpečnostní vrstvy (E7 přidal rolovou autorizaci na 10 controllerů, `konvence.md`
a `api.md` to zaznamenaly, `backend.md` §3 popisuje stav před E7 včetně dávno smazaného
`/auth/register`).

Celkem **1 vysoký, 9 středních a 13 nízkých** nálezů. Nejzávažnější je `api.md`, které slibuje, že
admin reset hesla odvolá uživatelovy refresh tokeny — kód to nedělá, takže dokumentovaná
bezpečnostní záruka neplatí. Nejcennější oblastí k nápravě je **nápověda**: je psaná dobrým jazykem
obsluhy a řeší reálné otázky, ale slibuje tři funkce, které v aplikaci nejsou (kontaktní osoby,
zakázky na detailu zákazníka, hledání podle čísla/e-mailu/telefonu), radí přiřadit roli, po které
uživatel dostane 403 na každé obrazovce, a na pěti místech pojmenovává tlačítka jinak, než jsou
v UI. Provázanost nápovědy je slabá — je dostupná jen jednou položkou v sidebaru, nemá vyhledávání
ani jediný kontextový odkaz ze stránky.

Pokrytí Javadocem/JSDocem je dobré (jen 8 z 224 Java souborů nemá ani jeden doc blok, 60 z 66 FE
komponent má JSDoc), takže **věcná správnost je tady mnohem větší téma než pokrytí**.

---

## Nálezy

### [A-1] `api.md` slibuje odvolání sessions při admin resetu hesla — kód ho nedělá
**Severita:** 🔴 VYSOKÝ
**Jistota:** OVĚŘENO
**Kde:** `docs/api.md:47` vs. `src/main/java/cz/palo/autoservis/service/impl/UserServiceImpl.java:218-227`
(+ `src/main/java/cz/palo/autoservis/security/service/AuthenticationService.java:201`)

**Co je špatně:**
`api.md:47` tvrdí: *„Změna/**reset** hesla rovněž odvolá všechny refresh tokeny uživatele (K-6)."*

Odvolání (`refreshTokenMapper.revokeAllByUserId`) se v celém `src/main/java` volá **jen dvakrát**
(ověřeno gripem): `AuthenticationService.java:128` (detekce reuse při refreshi) a
`AuthenticationService.java:201` (self-service `changePassword`). `UserServiceImpl.resetPassword`
dělá pouze:

```java
int affectedRows = userMapper.updatePasswordHash(id, passwordEncoder.encode(request.getNewPassword()));
userMapper.unlockAccount(id);
```

Žádný `revokeAllByUserId`, žádný zápis do blacklistu.

**Scénář selhání:** Zaměstnanci se dostane do rukou přihlášený notebook / odejde ze servisu ve zlém.
Admin otevře **Uživatelé → Resetovat heslo** a nastaví nové heslo v přesvědčení (podepřeném
dokumentací), že tím ukončil útočníkovy sessions. Útočníkův prohlížeč má ale pořád platnou cookie
`jwt_refresh` (maxAge `jwt.refresh-expiration` = **7 dní**) a při každém 401 si přes
`POST /auth/refresh` vyrobí nový access token. Přístup do celé aplikace mu zůstane až 7 dní po resetu.

**Proč to vadí:** bezpečnost. Reset hesla je v podnikových aplikacích standardní „odstřihávací"
akce; dokumentace tu záruku výslovně dává, a proto ji admin nebude ověřovat. Sebeobslužná změna
hesla (`changePassword`) session odvolá — nekonzistence mezi dvěma cestami ke stejnému cíli je navíc
nečekaná.

**Návrh řešení:** doplnit `refreshTokenMapper.revokeAllByUserId(id)` do
`UserServiceImpl.resetPassword` (jeden řádek, vzor `AuthenticationService.changePassword:201`)
+ test analogický `ChangePasswordTest`. Teprve pak tvrzení v `api.md:47` platí. Opravovat opačným
směrem (přepsat `api.md`) nedoporučuji — chybí tím reálná bezpečnostní funkce.
*Poznámka: hlubší posouzení patří bezpečnostnímu průchodu; tady je to hlášeno jako dokumentovaná
záruka, kterou kód neplní.*

---

### [B-1] Nápověda radí přiřadit roli „zákaznický portál" — takový účet dostane 403 na každé obrazovce
**Severita:** 🟠 STŘEDNÍ
**Jistota:** OVĚŘENO
**Kde:** `frontend/autoservis-frontend/src/help/sprava-uzivatelu.md:13`
vs. `src/main/java/cz/palo/autoservis/config/security/SecurityConfig.java:93`
(+ `src/main/resources/mapper/RoleMapper.xml` `getAll`, `frontend/autoservis-frontend/src/components/UserForm.jsx:26,92`)

**Co je špatně:**
Nápověda: *„Zaškrtněte alespoň jednu roli — podle toho, co bude uživatel v aplikaci dělat
(administrátor, vedoucí servisu, mechanik, **zákaznický portál**…)."*

`SecurityConfig.java:93` má `.requestMatchers("/api/**").hasAnyRole("ADMIN", "MANAGER", "MECHANIC")`
s komentářem, že `ROLE_CUSTOMER` je odsud odříznuta záměrně (audit K-10). `konvence.md §19` totéž
potvrzuje: *„Role `ROLE_CUSTOMER` (zákaznický portál, zatím neexistuje) je tím od API odříznuta."*

Přesto `GET /code-lists/roles` (`RoleMapper.xml` `getAll` = `SELECT id, name, description FROM
security.roles` bez filtru) vrací **všech pět** rolí ze seedu (`db/prod/V60__prod_seed.sql:24-28`
i `db/demo/V3:19-23`), včetně `ROLE_CUSTOMER` a `ROLE_READONLY`, a `UserForm.jsx:26` je bez
filtrování všechny vykreslí jako zaškrtávátka.

**Scénář selhání:** Admin založí podle nápovědy účet a zaškrtne jen „Zákazník — přístup do
zákaznického portálu". Uživatel se **úspěšně přihlásí** (`/api/*/auth/**` stačí `authenticated()`),
`RequireAuth` projde přes `GET /auth/me`, aplikace ho pustí na `/dashboard` — a tam i všude jinde
dostane 403 z `/dashboard/summary`. Vypadá to jako rozbitá aplikace, ne jako špatně zvolená role.
Totéž platí pro `ROLE_READONLY` (backend.md:73: *„jen v DB seedu, kód ji nevyužívá"*).

**Proč to vadí:** provoz — nápověda aktivně navádí k vytvoření účtu, který nemůže fungovat, a chyba
se projeví až u koncového uživatele, ne při zakládání.

**Návrh řešení:** dvě části.
1. **Nápověda:** vypustit „zákaznický portál" z výčtu a doplnit větu, že použitelné jsou dnes jen
   role Administrátor / Vedoucí servisu / Mechanik.
2. **Kód (doporučeno, ale je to *rozhodnutí uživatele*):** buď filtrovat `RoleMapper.getAll` na
   přiřaditelné role, nebo do `RoleDto` doplnit příznak „assignable" a v `UserForm` nepřiřaditelné
   role zašedit. Bez toho zůstane past v UI, i když nápověda mlčí.

---

### [B-2] `zakaznici.md` popisuje tři funkce, které aplikace nemá
**Severita:** 🟠 STŘEDNÍ
**Jistota:** OVĚŘENO
**Kde:** `frontend/autoservis-frontend/src/help/zakaznici.md:12`, `:19`, `:30`

**Co je špatně:** tři samostatná nepravdivá tvrzení v jednom článku.

1. **`zakaznici.md:12`** — *„U firmy lze přidat **kontaktní osoby**."*
   `CustomerDto.CreateRequest` (`src/main/java/…/model/dto/customer/CustomerDto.java:23-70`)
   ani `UpdateRequest` (`:74-122`) pole `contactPersons` **nemají**; `CustomerForm.jsx`
   a `src/api/customerPayload.js` neobsahují jediné pole kontaktní osoby (ověřeno gripem
   `contactPerson` — jediný výskyt na FE je zobrazení v `CustomersPageDetail.jsx:173`);
   controller pro kontaktní osoby v projektu neexistuje. Kontaktní osoby se dají jen **zobrazit**
   (pocházejí ze seedu), přidat je nelze nikde v aplikaci ani přes API.

2. **`zakaznici.md:19`** — *„Na detailu vidíte jeho **vozidla** a **otevřené zakázky**."*
   `CustomersPageDetail.jsx` má karty Kontakt (`:118`), Firemní/Osobní údaje (`:136`), Adresy
   (`:149`), Kontaktní osoby (`:173`), Souhlasy (`:181`), Interní poznámka (`:197`), Metadata
   (`:205`) a **Vozidla zákazníka** (`:217`). Karta se zakázkami tam **není** (grep `orders`
   v souboru nevrací nic).

3. **`zakaznici.md:30`** — *„Pole **Hledat** najde zákazníka podle jména i firmy, **čísla, e-mailu
   nebo telefonu**."*
   `CustomerMapper.xml:158-179` (`searchWhere`, sdílený `search` i `countSearch`) porovnává
   výhradně `c.first_name`, `c.last_name`, `c.company_name` (řádky 173–175). `customer_number`,
   `primary_email` ani `primary_phone` v podmínce nejsou. Placeholder v UI je ostatně správně:
   `CustomersPage.jsx:83` = „Jméno, příjmení nebo název firmy".

**Scénář selhání:** Obsluha má na papíře číslo zákazníka `ZNK-2026-0042` nebo jeho telefon, vloží
ho do pole Hledat podle návodu — dostane prázdný seznam a usoudí, že zákazník v systému není.
Založí duplicitu. U kontaktních osob strávil hledáním neexistujícího tlačítka čas a skončí
u telefonátu na podporu.

**Proč to vadí:** provoz + kvalita dat (duplicitní zákazníci). Nápověda, která slíbí neexistující
funkci, si u obsluhy vypálí důvěru — pak nevěří ani tomu, co je v ní správně.

**Návrh řešení:** opravit článek na skutečnost (hledání: jméno / příjmení / název firmy; detail:
vozidla; kontaktní osoby: jen zobrazení dat, přidávat je zatím nelze). Rozšíření hledání
o `customer_number`/`primary_email`/`primary_phone` je levné (tři OR větve v `searchWhere`) a dává
provozní smysl — *rozhodnutí uživatele*, jestli opravit dokument, nebo kód.

---

### [B-3] „Jen korekce a odpis" — jedna stará věta na čtyřech místech, modal nabízí čtyři typy pohybu
**Severita:** 🟠 STŘEDNÍ
**Jistota:** OVĚŘENO
**Kde:**
`frontend/autoservis-frontend/src/help/sklad-pohyby.md:5` a `:22`;
`frontend/autoservis-frontend/src/components/StockMovementModal.jsx:8`;
`src/main/java/cz/palo/autoservis/service/ProductService.java:74-76`;
`src/main/java/cz/palo/autoservis/controller/warehouse/ProductController.java:127-129`
— proti `StockMovementModal.jsx:139-142` a `model/dto/warehouse/StockMovementDto.java:61-70`

**Co je špatně:** ruční skladový pohyb byl rozšířen ze dvou typů na čtyři (`ADJUSTMENT`,
`WRITE_OFF`, `RETURN`, `ISSUE`). Aktualizovaly se `StockMovementDto` (javadoc `:17-24` uvádí
správně všechny čtyři), `docs/api.md:219` i `docs/funkce/sklad-pohyby.md` (správně „Všechny čtyři").
Neaktualizovaly se čtyři jiné popisy téhož:

| Místo | Co tvrdí |
|---|---|
| `help/sklad-pohyby.md:5` | „Ručně se zadávají jen **dvě** situace — **manko** a **odpis**." |
| `help/sklad-pohyby.md:22` | krok návodu: „Vyberte **typ** (korekce nebo odpis)." |
| `StockMovementModal.jsx:8` | JSDoc: „záporná korekce **nebo odpis** proti konkrétní šarži" |
| `ProductService.java:74` | „Zaznamená ruční záporný skladový pohyb (**korekce dolů nebo odpis**)" |
| `ProductController.java:127` | „manual negative stock movement (**downward correction or write-off**)" |

Realita (`StockMovementModal.jsx:139-142`, vynucená `StockMovementDto.isManualMovementType()`):
Korekce −, Odpis, **Vratka dodavateli**, **Spotřeba bez zakázky**.

Článek si navíc **protiřečí sám**: `:9-12` vyjmenovává čtyři typy a `:14` začíná „Všechny čtyři
zásobu snižují", ale úvod `:5` i krok návodu `:22` mluví o dvou.

**Scénář selhání:** Mechanik posílá vadný díl zpět dodavateli. Podle nápovědy („zadávají se jen
manko a odpis") ho **odepíše** jako `WRITE_OFF`. Ledger pak tvrdí „zboží je pryč a nikdo nám ho
neproplatí", ačkoli servis čeká dobropis; ztratí se `return_reason` i vazba na číslo dobropisu,
takže při reklamačním sporu není z čeho doložit, co a proč odešlo. Náprava je nemožná — ledger je
append-only (`V52`), takže jde jen připsat další pohyb.

**Proč to vadí:** data (nesprávná klasifikace pohybu je nevratná) a peníze (nedohledaná reklamace).
`docs/funkce/sklad-pohyby.md:40-42` přitom rozdíl mezi typy popisuje přesně — ta znalost do
nápovědy jen nedoputovala.

**Návrh řešení:** sjednotit všech pět míst na čtyři typy podle `StockMovementDto:17-24`.
V `help/sklad-pohyby.md` opravit `:5` („čtyři situace") a `:22` (vyjmenovat všechny čtyři).

---

### [A-2] Javadoc `OrderItemService` garantuje, že položky lze měnit kdykoli — zámek fakturou to popírá
**Severita:** 🟠 STŘEDNÍ
**Jistota:** OVĚŘENO
**Kde:** `src/main/java/cz/palo/autoservis/service/OrderItemService.java:15-16`
vs. `src/main/java/cz/palo/autoservis/service/impl/OrderItemServiceImpl.java:109,131,244,267,324-333`

**Co je špatně:** Javadoc rozhraní:

```java
 * <p>Provides CRUD operations for the {@code order.order_items} table.
 * Order items can be freely added, updated, or deleted at any point during the order lifecycle.
```

Implementace volá `requireOrderNotInvoiced(orderId)` na začátku `create` (`:109`),
`importFromReceipt` (`:131`), `update` (`:244`) i `delete` (`:267`); helper (`:324-333`) vyhodí
422 `ORDER_LOCKED_BY_INVOICE`, existuje-li k zakázce faktura ve stavu ≠ `CANCELLED`. Jde
o dokumentovanou opravu V2 z `analyza-2026-07`, kterou `api.md:129` popisuje správně — jen
Javadoc rozhraní zůstal ve stavu před opravou. Přesně opačným směrem než realita: doc slibuje
**víc** volnosti, než kód dává.

**Scénář selhání:** Vývojář implementuje hromadnou úpravu položek (např. přecenění) a podle
Javadocu rozhraní nepočítá s tím, že by mohla selhat. Na první zakázce s fakturou dostane 422
uprostřed dávky; bez `@Transactional` kolem dávky zůstane část zakázek přepsaná a část ne.

**Proč to vadí:** zastaralý komentář je horší než žádný — čtenář ho použije jako kontrakt.
Na tomtéž rozhraní chybí `@param userId` u `delete` (`:60`) a doc nezmiňuje vedlejší efekt
`delete` (vrácení dílu na sklad pohybem `ISSUE_RETURN`, `OrderItemServiceImpl.java:269-283`), což
je pro volajícího podstatnější než samotné smazání řádku. Soubor navíc drží nepoužitý import
`AppUserDetails` (`OrderItemService.java:7`) — R-12.

**Návrh řešení:** přepsat větu na „Položky lze měnit jen dokud k zakázce neexistuje faktura ve stavu
≠ CANCELLED (jinak 422 `ORDER_LOCKED_BY_INVOICE`)", doplnit `@param userId` a poznámku
o `ISSUE_RETURN` u `delete`, smazat nepoužitý import.

---

### [C-1] `backend.md` §3 popisuje bezpečnostní vrstvu ve stavu před E7 (a před smazáním `/auth/register`)
**Severita:** 🟠 STŘEDNÍ
**Jistota:** OVĚŘENO
**Kde:** `docs/backend.md:23`, `:66`, `:67`, `:73`
vs. `src/main/java/cz/palo/autoservis/config/security/SecurityConfig.java:83-94,160-175`,
`src/main/java/cz/palo/autoservis/security/filter/JwtAuthenticationFilter.java:56`

**Co je špatně:** čtyři tvrzení v §3, každé neplatné:

| `backend.md` | Tvrzení | Realita |
|---|---|---|
| `:23` | „AuthenticationService (login/**register**/refresh/logout…)" | metoda `register` byla odstraněna (K1); `AuthenticationService.java` má login/refresh/logout/changePassword |
| `:66` | „CORS jen `localhost:5173` / `127.0.0.1:5173` (**natvrdo** — před produkcí změnit)" | `SecurityConfig.java:162`: `configuration.setAllowedOrigins(corsProperties.getAllowedOrigins())` — z konfigurace přes `@ConfigurationProperties` (TD-33, opraveno 2026-07-25) |
| `:66` | „`/api/*/auth/{login,**register**,refresh}` permitAll → … → `/api/**` **authenticated**" | `SecurityConfig.java:83-93`: permitAll jen `login` + `refresh`; `/api/*/auth/**` `authenticated()`; **`/api/**` `hasAnyRole("ADMIN","MANAGER","MECHANIC")`**, ne `authenticated()` |
| `:67` | „`shouldNotFilter` přeskakuje login/**register**/refresh" | `JwtAuthenticationFilter.java:56`: `^/api/[^/]+/auth/(login\|refresh)$` |
| `:73` | „Rolová autorizace (`@PreAuthorize`) se používá **na dvou místech** … Granulární role-based přístup pro ostatní moduly je **odložený dluh (TD-24)**" | `@PreAuthorize` je na **21 místech v 10 controllerech** (grep): CashReceipt, CreditNote (celé třídy), CompanyProfile, Customer 2×, Employee 4×, Invoice 3×, User (celá třída), Vehicle 2×, GoodsReceiptImport 2×, GoodsReceiptReview (celá třída), StockTake 1×. **TD-24 je v `tech-dluhy.md:116` označen jako vyřešený E7.** |

`backend.md:66` navíc vůbec nezmiňuje bezpečnostní hlavičky (`SecurityConfig.java:98-122`:
frame-options DENY, nosniff, referrer, HSTS, CSP), které §3 popisuje jako chybějící stav.

**Scénář selhání:** Vývojář (nebo AI agent) přidává nový modul a podle `CLAUDE.md` mapy si přečte
`backend.md`. Vyvodí, že (a) autorizace je jen `authenticated()`, takže `@PreAuthorize` nemá řešit,
(b) rolová granularita je odložený dluh, (c) CORS je natvrdo a před produkcí ho někdo změní.
Výsledný endpoint nedostane žádné rolové omezení, ačkoli `konvence.md §19` a `api.md:10` popisují
opačné pravidlo. Zároveň dokument stále odkazuje na endpoint `/auth/register`, který v projektu
neexistuje — kdo ho bude hledat, nenajde nic a ztratí čas.

**Proč to vadí:** je to jeden ze čtyř dokumentů, na které `CLAUDE.md:37` posílá při práci na Java
vrstvě, a jeho §3 je jediný souvislý popis security v celém `docs/`. Odporuje `konvence.md §19`
i `api.md:10`, které jsou správně — takže čtenář dostane tři různé pravdy.

**Návrh řešení:** přepsat `backend.md` §3 podle skutečného `SecurityConfig` (pořadí pravidel,
role baseline, hlavičky, CORS z `CorsProperties`), odstranit všechny tři zmínky o `register`
a nahradit odstavec o „dvou místech" odkazem na `konvence.md §19` (jediný zdroj pravdy pro
autorizační matici), ať se to znovu nerozejde.

---

### [C-2] `api.md` i `frontend.md` tvrdí, že `CustomerDto.UpdateRequest` nemá `addresses` — má
**Severita:** 🟠 STŘEDNÍ
**Jistota:** OVĚŘENO
**Kde:** `docs/api.md:60`, `docs/frontend.md:164`, `docs/frontend.md:279`
vs. `src/main/java/cz/palo/autoservis/model/dto/customer/CustomerDto.java:115-121`
a `src/main/java/cz/palo/autoservis/service/impl/CustomerServiceImpl.java:140-153`

**Co je špatně:**
- `api.md:60`: „`PUT /customers/{id}` | `CustomerDto.UpdateRequest` (**bez customerType a addresses**)"
- `frontend.md:164`: „**adresy jen v create režimu** — `CustomerDto.UpdateRequest` pole `addresses`
  nemá, takže PUT je nepřijímá"
- `frontend.md:279` (tabulka „Co zůstalo otevřené"): „Adresu existujícího zákazníka nelze změnit | TD-42"

Kód: `CustomerDto.java:119-121` má `@Valid @Size(max = 2) private List<AddressDto.CreateRequest>
addresses;` s komentářem „TD-42: adresy jdou editovat i u existujícího zákazníka";
`CustomerServiceImpl.java:144-153` je implementuje (null = neměnit, neprázdný seznam = full-replace
s `AddressSetValidator` a `deleteByCustomerId`). `tech-dluhy.md:178` uvádí TD-42 jako
**VYŘEŠENO 2026-07-25** včetně toho, že `CustomerForm` adresní sekci v editu zase ukazuje.

**Scénář selhání:** Zákazník se přestěhuje. Obsluha nahlásí, že adresu nejde změnit; podpora
ověří v `frontend.md`/`api.md`, potvrdí „to opravdu nejde, je to evidovaný dluh TD-42" a pošle
zákazníkovi fakturu na starou adresu. Funkce přitom existuje a je v UI dostupná.
Druhý scénář: vývojář podle `api.md:60` napíše klienta, který `addresses` v PUT vůbec neposílá —
což je sice bezpečné (null = neměnit), ale nikdy adresu neopraví.

**Proč to vadí:** fakturační adresa jde na daňový doklad. Dokumentace tvrdí, že chyba nemá řešení,
ačkoli řešení je hotové pět dní.

**Návrh řešení:** `api.md:60` → „(bez `customerType`; `addresses` volitelné — `null` = neměnit,
neprázdný seznam = full-replace sady, TD-42)"; `frontend.md:164` přepsat na skutečné chování
`CustomerForm` v edit režimu; z tabulky `frontend.md:275-283` vyřadit řádky TD-42 a TD-46 (viz C-7).

---

### [C-3] Souhrn endpointů v `api.md` neuvádí dva controllery a všechna tři čísla jsou špatně
**Severita:** 🟠 STŘEDNÍ
**Jistota:** OVĚŘENO
**Kde:** `docs/api.md:3` a `docs/api.md:419-444`
vs. `src/main/java/cz/palo/autoservis/controller/CreditNoteController.java`,
`…/CashReceiptController.java`, `…/DashboardController.java:31,43`

**Co je špatně:** `api.md:3` v hlavičce: *„odvozený z kódu controllerů (**22 tříd, 107
endpointů**; stav 2026-07-25)"*. Tabulka „Souhrn počtů (pro křížovou kontrolu)" (`:421-444`)
vyjmenovává **21 controllerů** a končí `| **Celkem** | **103** |`.

Skutečnost (spočítáno přes `find … -name "*Controller.java"` a `grep -cE
'@(Get|Post|Put|Delete|Patch)Mapping'`): **23 controllerů, 112 endpointů**. Rozdíly:

| Controller | Skutečně | V tabulce `api.md` |
|---|---|---|
| `CashReceiptController` | 4 | **chybí** (přitom je popsán v těle `api.md:183-192`) |
| `CreditNoteController` | 4 | **chybí** (přitom je popsán v těle `api.md:172-181`) |
| `DashboardController` | 2 (`/summary` `:31`, `/statistics` `:43`) | 1 |

Ostatních 20 řádků tabulky sedí. Tabulka, jejíž jediný účel je „křížová kontrola", tedy křížovou
kontrolu neprojde a hlavička s ní nesouhlasí ani navzájem (107 × 103).

**Scénář selhání:** Někdo (nebo AI agent podle skillu `novy-endpoint`) ověří kompletnost `api.md`
podle souhrnné tabulky, vyjde mu, že „všechno je zdokumentované", a přehlédne, že dva doklady
s právním dopadem (opravný daňový doklad, příjmový pokladní doklad) v přehledu chybí. Při
inventarizaci API pro produkci se na ně zapomene — třeba při nastavování rolí nebo reverzní proxy.

**Proč to vadí:** kontrolní součet, kterému nejde věřit, je horší než žádný — dává falešnou jistotu.
Navíc `docs/tech-dluhy.md:101` nese třetí, ještě starší variantu týchž čísel (viz C-5).

**Návrh řešení:** doplnit `CreditNoteController` (4) a `CashReceiptController` (4) do tabulky,
opravit `DashboardController` na 2, přepočítat celkem na **112** a sjednotit hlavičku `:3`
(**23 tříd, 112 endpointů**). Uvést v hlavičce příkaz, kterým se čísla přepočítají, ať se to
příště ověří strojově.

---

### [C-4] `api.md` dokumentuje filtry `customerType` a `city` u `/customers` — mapper je ignoruje
**Severita:** 🟠 STŘEDNÍ
**Jistota:** OVĚŘENO
**Kde:** `docs/api.md:55` vs. `src/main/resources/mapper/CustomerMapper.xml:158-179`
a `src/main/java/cz/palo/autoservis/model/dto/customer/CustomerSearchParams.java:31-32`

**Co je špatně:** `api.md:55` popisuje `GET /customers` jako
`CustomerSearchParams (search, **customerType, city**, activeOnly, sortBy)`.

`CustomerSearchParams.java:31-32` obě pole opravdu má:
```java
private CustomerType customerType;
private String city;
```
ale sdílený `searchWhere` v `CustomerMapper.xml:158-179` (jediné místo, kudy jdou `search`
i `countSearch`, řádky `:214` a `:254`) zná pouze `params.activeOnly` a `params.searchTokens`.
Grep `params.customerType` / `params.city` v `CustomerMapper.xml` nevrací **žádný** výskyt
(`customerType` se v souboru objevuje jen v resultMapech `:19`, `:61` a v INSERTu `:272`).

**Scénář selhání:** Někdo (FE, integrace, reporting skript) zavolá
`GET /api/v1/customers?customerType=COMPANY&pageSize=100`. Server vrátí **200 se všemi zákazníky
včetně fyzických osob** — žádnou chybu, žádné varování. Kdo výstup použije jako „seznam firem"
(např. pro hromadnou korespondenci nebo pro kontrolu DIČ), pracuje s tichým nesmyslem. Totéž
`?city=Brno`.

**Proč to vadí:** tichý nesprávný výsledek je horší než 400. Zároveň jsou to dvě mrtvá pole DTO
(R-12 „dead code smazat"), která svou existencí podpírají nepravdivou dokumentaci.

**Návrh řešení:** *rozhodnutí uživatele* mezi dvěma variantami:
(a) filtry **doimplementovat** v `searchWhere` (`AND c.customer_type = #{params.customerType,
jdbcType=OTHER}`, `AND LOWER(unaccent(a.city)) = …` — pozor, `city` je v `customer.addresses`,
vyžádá si JOIN), nebo
(b) obě pole z `CustomerSearchParams` **smazat** a vyškrtnout je z `api.md:55`.
Doporučuji (b) — dnes je nikdo nevolá a filtr podle města přes JOIN by komplikoval `countSearch`.

---

### [B-4] Nápověda ani jinde nepopisuje uzamčení účtu po 10 neúspěšných přihlášeních
**Severita:** 🟠 STŘEDNÍ
**Jistota:** OVĚŘENO
**Kde:** `frontend/autoservis-frontend/src/help/sprava-uzivatelu.md` (celý článek, zejm. FAQ `:41`)
vs. `src/main/java/cz/palo/autoservis/security/service/LoginAttemptService.java`
(+ `docs/api.md:34,392`, `docs/backend.md:71`)

**Co je špatně:** po 10 neúspěšných přihlášeních se účet zamkne (`MAX_FAILED_ATTEMPTS`,
`LoginAttemptService`), uživatel dostane 401 `ACCOUNT_LOCKED` s hláškou „Účet je uzamčen po
opakovaných neúspěšných přihlášeních." a odemkne ho **jedině admin reset hesla**
(`UserServiceImpl.java:225` `userMapper.unlockAccount(id)`).

V nápovědě o tom není ani slovo. Článek „Správa uživatelů a hesel" má sekci Časté dotazy
(`:39-45`) a v ní přesně tu otázku, kde by to patřilo — *„Zapomněl jsem heslo, co teď?"* (`:41`) —
a odpovídá jen „požádejte administrátora o reset". Detail účtu přitom ukazuje `failedLoginAttempts`
(`api.md:364`), takže admin ten stav vidí, ale nikde se nedozví, co znamená ani jak ho vyčistit.

**Scénář selhání:** Zaměstnanec si třikrát splete heslo, pak zkouší varianty, po desátém pokusu se
mu hláška změní z „Neplatné jméno nebo heslo" na „Účet je uzamčen…". Zavolá adminovi. Admin hledá
v nápovědě „uzamčen" — nic. V UI není žádné tlačítko „Odemknout" (samostatná admin akce pro
odemčení bez resetu hesla podle `backend.md:71` neexistuje). Admin buď náhodou trefí reset hesla,
nebo eskaluje na vývojáře.

**Proč to vadí:** je to jediná situace v aplikaci, kdy se uživatel **sám nedostane dovnitř**, a je
plně automatická — přijde dřív nebo později. Nápověda má v tomhle článku deklarovaný cíl řešit
„co dělám, když…", a tuhle otázku vynechává.

**Návrh řešení:** doplnit do `sprava-uzivatelu.md` odstavec „Účet je uzamčen": po 10 neúspěšných
pokusech se účet zamkne, odemkne ho **Resetovat heslo** od administrátora, počítadlo pokusů je
vidět na detailu uživatele. Zvážit i samostatnou akci „Odemknout účet" v UI (*rozhodnutí
uživatele* — dnes je odemčení svázané se změnou hesla, což je bezpečnější, ale méně pohodlné).

---

### [B-5] Nápověda pojmenovává pět tlačítek a jeden filtr jinak, než jsou v aplikaci
**Severita:** 🟡 NÍZKÝ
**Jistota:** OVĚŘENO
**Kde:** viz tabulka

| Nápověda | Skutečný text v UI |
|---|---|
| `help/sprava-uzivatelu.md:11` „Uživatelé → **Vytvořit uživatele**" | `pages/UsersPage.jsx:72` — **„Nový uživatel"** |
| `help/stk-registr.md:7` „Vozidla → **Vytvořit vozidlo**" | `pages/VehiclesPage.jsx:74` — **„Nové vozidlo"** (a `help/vozidla.md:7` to má správně) |
| `help/prijem-zbozi.md:47` a `help/sklad-pohyby.md:47` „Sklad → Příjemky → **Ruční příjemka**" | `pages/ReceiptsPage.jsx:139` — **„Nová ručně"** |
| `help/zakazky.md:17`, `help/prijem-zbozi.md:53`, `help/sklad-pohyby.md:16` „**Import položek**" | `components/OrderItemsToolbar.jsx:18` — **„+ Importovat položky"** |
| `help/zakaznici.md:26` a `help/vozidla.md:25` „najdete přepnutím filtru **Stav**" | `pages/CustomersPage.jsx:88` / `pages/VehiclesPage.jsx:90` — zaškrtávátko **„Jen aktivní"**; filtr jménem „Stav" existuje jen u faktur (`InvoicesPage.jsx:80`) a „Stav zakázky" u zakázek (`OrdersPage.jsx:92`) |

**Scénář selhání:** Obsluha chce najít deaktivovaného zákazníka. Podle nápovědy hledá filtr „Stav",
ten na stránce Zákazníci není (u faktur ho přitom viděla, takže si je jistá, že existuje) a odejde
s tím, že zákazník je nenávratně pryč. U „Ruční příjemky" hledá tlačítko toho jména, vidí jen
„Nová ručně" a „Import dokladu (PDF)" a neví, které z nich to je.

**Proč to vadí:** samo o sobě kosmetika, ale u návodu psaného pro obsluhu je název tlačítka to
jediné, čeho se drží. Šest neshod v patnácti článcích znamená, že se texty UI mění bez průchodu
nápovědou.

**Návrh řešení:** srovnat texty v článcích s UI (levnější než přejmenovávat tlačítka).
U filtru „Stav" nahradit větou „Zrušte zaškrtnutí **Jen aktivní**". Zvážit do `npm run check`
jednoduché pravidlo: každý text v `**…**` v `src/help/*.md`, který vypadá jako popisek tlačítka,
musí mít výskyt v `src/**/*.jsx` — hrubé, ale zachytilo by přesně tuhle třídu chyb.
*(Přínos/náklad je věcí volby — *rozhodnutí uživatele*.)*

---

### [B-6] Nápověda mluví o „Dashboardu", aplikace o „Přehledu"; chybí dlaždice Marže
**Severita:** 🟡 NÍZKÝ
**Jistota:** OVĚŘENO
**Kde:** `frontend/autoservis-frontend/src/help/dashboard.md:1` a `:24-32`,
`src/help/index.js:29`, `help/vozidla.md:21`
vs. `pages/DashboardPage.jsx:45,67`, `components/navigation.js:16`, `pages/DashboardPage.jsx:260-281`

**Co je špatně:** dvě věci ze stejného zdroje — stránka se nedávno přejmenovala (commit f82d7c1
„Dashboard: statistika, přejmenování na přehled") a přibyla dlaždice marže.

1. **Název.** `navigation.js:16` = `label: "Přehled"`, `DashboardPage.jsx:45` a `:67` =
   `<PageHeader title="Přehled" …>`. Slovo „Dashboard" se v UI nevyskytuje **nikde**. Nápověda
   ho používá jako nadpis článku (`dashboard.md:1` „# Dashboard"), v položce seznamu
   (`index.js:29` „Dashboard (úvodní přehled)") i v odkazech z jiných článků
   (`vozidla.md:21` „hlídá ji na **dashboardu**").

2. **Chybějící dlaždice.** `dashboard.md:24-32` (sekce „Provoz") vyjmenovává čtyři dlaždice:
   Rozpracované dle stavu, Připraveno k vyzvednutí, Sklad — úkoly, Tržby tento měsíc.
   Na stránce je jich **pět** — `DashboardPage.jsx:260-281` vykresluje „**Marže tento měsíc
   (bez DPH)**" s rozpadem materiál/práce a srovnáním s minulým měsícem. `docs/funkce/dashboard.md:19`
   ji uvádí správně, uživatelský článek ne.

**Scénář selhání:** Majitel servisu si otevře nápovědu, aby pochopil, jak se počítá číslo v dlaždici
Marže (klíčová otázka — zahrnuje DPH? co položky bez nákupní ceny?). V článku dlaždice není, takže
odpověď („z vyfakturovaných zakázek, položky bez známého nákladu se nezapočítají" — poznámka pod
dlaždicí, `DashboardPage.jsx:279`) si musí odvodit sám. Zároveň hledá v levém menu „Dashboard“
a najde „Přehled".

**Proč to vadí:** marže je číslo, podle kterého se rozhoduje o cenách — nedokumentovaná metodika
výpočtu vede k chybným závěrům. Rozdíl v názvu je drobnost, ale je to první článek nápovědy, který
uživatel uvidí (`HelpPage.jsx:37` na něj bez slugu přesměrovává).

**Návrh řešení:** přejmenovat článek na „Přehled (úvodní stránka)", projít výskyty slova
„dashboard" v ostatních článcích a doplnit odstavec o dlaždici Marže (text lze převzít
z `docs/funkce/dashboard.md:38-40`).

---

### [B-7] Nápověda je dostupná jen jednou položkou v sidebaru — bez hledání a bez kontextových odkazů
**Severita:** 🟡 NÍZKÝ
**Jistota:** OVĚŘENO
**Kde:** `frontend/autoservis-frontend/src/App.jsx:95-96`,
`frontend/autoservis-frontend/src/components/navigation.js:41`,
`frontend/autoservis-frontend/src/pages/HelpPage.jsx:32-73`

**Co je špatně:** grep `"/help"` přes celý `src/` vrací **jen tři** místa mimo samotnou nápovědu:
dvě routy (`App.jsx:95-96`) a jednu položku sidebaru (`navigation.js:41`). Žádná stránka, žádný
formulář ani modal na nápovědu neodkazuje. `HelpPage.jsx` nemá vyhledávací pole — obsah se prochází
výhradně proklikáním seznamu 15 článků (`:48-54`), uvnitř článku pak Ctrl+F prohlížeče.

Nápověda přitom obsahuje odpovědi přesně na místa, kde uživatel tápe: kontrolní obrazovka příjemky
(barevné stavy polí, „Vyřadit z naskladnění"), inventura („prázdné pole není nula"), skladový pohyb
(proč se vybírá šarže), STK (proč nejde datum přepsat).

**Scénář selhání:** Obsluha poprvé otevře kontrolní obrazovku příjemky, vidí u polí barevné ikonky
a netuší, co znamenají. Vysvětlení je v `prijem-zbozi.md:21-29`, ale nic na obrazovce k němu
nevede — a i kdyby si vzpomněla na Nápovědu, musí uhodnout, že to je v článku „Příjem zboží na
sklad" a ne v „Přehled skladu a karta dílu". Pravděpodobný výsledek: potvrdí příjemku bez kontroly
dopočtených hodnot.

**Proč to vadí:** obsahově je nápověda dobrá (viz „Co bylo ověřeno jako v pořádku"), ale bez
provázanosti se k ní uživatel dostane jen tehdy, když už ví, že hledá. Náklad na napsání článků se
tím z velké části nevyužije.

**Návrh řešení:** *rozhodnutí uživatele* podle chuti investovat:
1. **Levné:** do `PageHeader` volitelný prop `helpSlug` → ikonka „?" vedle nadpisu, `Link` na
   `/help/{slug}`. Nasadit na 6–8 nejsložitějších obrazovek (kontrola příjemky, detail inventury,
   karta dílu, detail faktury, editace zakázky, nastavení firmy).
2. **Střední:** vyhledávací pole nad seznamem článků v `HelpPage` — filtrování `HELP_ARTICLES`
   podle `title` + `content` (obsah je už v paměti, žádný backend není potřeba).

Zároveň chybí článek o **opravném daňovém dokladu (dobropisu)** — backend má plný
`CreditNoteController` (4 endpointy včetně PDF), ale grep `credit-notes` ve `frontend/…/src`
nevrací **nic**, takže funkce není z UI dostupná. Článek proto zatím nemá co popisovat; skutečná
mezera je na straně frontendu (mimo rozsah tohoto průchodu, hlášeno pro úplnost).

---

### [A-3] Javadocy zkopírované od sousední metody popisují něco jiného, než metoda dělá
**Severita:** 🟡 NÍZKÝ
**Jistota:** OVĚŘENO
**Kde:**

| Místo | Co tvrdí | Co metoda dělá |
|---|---|---|
| `controller/warehouse/ProductController.java:65-66` | „Returns the full stock card of a product (header, batches, movement history)." | `getByGoodsReceipt` vrací `List<ProductDto.ListResponse>` — produkty jedné příjemky (kopie doc z `getById` o 17 řádků výš). `@param`/`@return` v témže bloku už jsou správně. |
| `controller/CustomerController.java:48` | „Returns the full detail of an active vehicle by ID." | `getCustomerVehiclesId` vrací seznam vozidel zákazníka. Táž věta jako v `VehicleController.java:35`. |
| `service/impl/VehicleServiceImpl.java:70` | `@throws ResourceNotFoundException if no active vehicle with the given ID exists` | `findByCustomerId` (`:73-79`) žádnou výjimku nevyhazuje — vrací seznam, pro zákazníka bez vozidel prázdný. Zkopírováno z `getById` (`:54`). |
| `service/impl/InvoiceServiceImpl.java:61-62` | `@throws BusinessRuleException if … **the company profile is not configured** …` | při chybějícím profilu firmy vyletí `IllegalStateException` (`:137-138`), kterou `GlobalExceptionHandler` mapuje na **500**, ne na 422. Doc navíc nezmiňuje `ORDER_NOT_INVOICEABLE` (`:76-81`). |
| `service/impl/ProductServiceImpl.java:199-203` | osiřelý javadoc blok o validaci jednotky (Z-4) stojí těsně **před** javadocem `registerManualMovement` (`:204-213`) | Java váže na metodu jen poslední blok; popis `requireValidUnit` tak visí nad ruční skladovou operací, se kterou nemá nic společného. Vlastní `requireValidUnit` (`:269`) je bez dokumentace. |

**Scénář selhání:** Vývojář hledá, proč `GET /customers/{id}/vehicles` u zákazníka bez vozidel
nevrací 404, a podle Javadocu `VehicleServiceImpl.java:70` čeká, že vrátit má. Napíše v klientovi
větev pro 404, která se nikdy nevykoná, a chybějící ošetření prázdného pole se projeví až v UI.
U `InvoiceServiceImpl` je dopad hmatatelnější: kdo se opře o dokumentované 422, nepočítá s 500
a nezaloguje ho jako incident.

**Proč to vadí:** brief to říká přesně — zastaralý komentář je horší než žádný. Tři z pěti případů
jsou čistá copy-paste chyba, kterou by odhalilo přečtení vlastního textu.

**Návrh řešení:** opravit pět míst (5 minut práce). U `InvoiceServiceImpl` buď opravit doc na
`@throws IllegalStateException`, nebo — lépe — změnit vyhození na `BusinessRuleException`
`COMPANY_PROFILE_MISSING` (422 s pochopitelnou hláškou je pro chybějící nastavení správnější než
500) — *rozhodnutí uživatele*. U `ProductServiceImpl` přesunout blok `:199-203` nad
`requireValidUnit`.

---

### [A-4] Dva javadoc odkazy míří na neexistující symboly
**Severita:** 🟡 NÍZKÝ
**Jistota:** OVĚŘENO
**Kde:** `src/main/java/cz/palo/autoservis/security/mapper/UserMapper.java:116`;
`src/main/java/cz/palo/autoservis/model/converter/VehicleConverter.java:11,17,67,100,132,170`

**Co je špatně:**
1. `UserMapper.java:116`: „Creates a new user account (admin CRUD, distinct from self-registration
   in **{@link #save}**)." Metoda `save` v rozhraní **není** (přečteno celé, `:20-185`) — zmizela
   se zrušením veřejné registrace (K1). Odkaz je nerozřešitelný.
2. `VehicleConverter.java` používá **6×** `{@link vehicle}` s malým „v" (`:11`, `:17`, `:67`,
   `:100`, `:132`, `:170`) — `vehicle` není typ ani parametr, správně je `{@link Vehicle}`.

**Scénář selhání:** `mvn javadoc:javadoc` (nebo náhled v IDE) na obou místech hlásí
„reference not found" — u `-Xdoclint` nastaveného na chybu build spadne. Praktičtější dopad:
`{@link #save}` posílá čtenáře hledat metodu registrace, která byla záměrně odstraněna z
bezpečnostních důvodů (K1) — tedy přesně tam, kam ho posílat nechceme.

**Proč to vadí:** drobnost, ale je to jediné místo v celém `security/mapper`, kde dokumentace
odkazuje na zrušenou funkci; při čtení působí, jako by registrace pořád existovala.

**Návrh řešení:** v `UserMapper.java:116` větu zkrátit na „Creates a new user account (admin CRUD)."
V `VehicleConverter` nahradit `{@link vehicle}` → `{@link Vehicle}` (6×).

---

### [A-5] Javadoc uvádí formát čísla faktury `FAK-2025-0001` — aplikace generuje `202607001`
**Severita:** 🟡 NÍZKÝ
**Jistota:** OVĚŘENO
**Kde:** `src/main/java/cz/palo/autoservis/service/InvoiceService.java:95`
a `src/main/java/cz/palo/autoservis/controller/InvoiceController.java:155`
vs. `src/main/resources/db/migration/V49__invoice_number_on_issue.sql`

**Co je špatně:** obě metody `getByInvoiceNumber` mají
`@param invoiceNumber invoice number (e.g. {@code FAK-2025-0001})`.

Číslo generuje trigger `billing.fn_generate_invoice_number` (V49):
```sql
v_year_month := TO_CHAR(COALESCE(NEW.issue_date, CURRENT_DATE), 'YYYYMM');
...
NEW.invoice_number := v_year_month || LPAD(v_next_seq::TEXT, 3, '0');
```
tedy `YYYYMM` + 3 číslice → `202607001`. Totéž má `konvence.md §18` („Číslo faktury `YYYYMM{3č.}`")
i nápověda (`help/prijmovy-pokladni-doklad.md:18` používá reálné „č. 202607001"). Prefix `FAK-`
se v projektu nevyskytuje nikde jinde — je to relikt návrhu, který se nikdy neimplementoval.

**Scénář selhání:** Integrace (nebo test) postavená podle Javadocu volá
`GET /api/v1/invoices/number/FAK-2025-0001` → `ResourceNotFoundException` → 404. Ladění pak vede
přes „chybí data?" místo „špatný formát".

**Proč to vadí:** je to jediný příklad hodnoty u tohoto endpointu, takže ho čtenář vezme
za předlohu. Malá věc s jasným následkem.

**Návrh řešení:** změnit oba příklady na `{@code 202607001}` a doplnit „(formát `YYYYMM` +
pořadové číslo, viz `konvence.md §18`)".

---

### [A-6] Kód i `api.md` tvrdí, že `UserController` je „druhý endpoint s rolovou autorizací"
**Severita:** 🟡 NÍZKÝ
**Jistota:** OVĚŘENO
**Kde:** `src/main/java/cz/palo/autoservis/controller/UserController.java:23-25`
a `docs/api.md:359`

**Co je špatně:** Javadoc třídy: *„Restricted to administrators — this is the **second endpoint in
the API** (after `/warehouse/receipts/import`) to use role-based authorization."*
`api.md:359` totéž česky: *„druhý endpoint v API s rolovou autorizací (po `GoodsReceiptImportController`)"*.

Po E7 je `@PreAuthorize` na **21 místech v 10 controllerech** (grep `@PreAuthorize` v `src/main/java`).
Tvrzení bylo pravdivé před 2026-07-24; `api.md:10` a `konvence.md §19` popisují aktuální stav
správně, takže si `api.md` protiřečí samo se sebou (`:10` × `:359`).

**Scénář selhání:** Čtenář `api.md` narazí nejdřív na `:10` (matice vedení-only operací), pak na
`:359` („druhý endpoint") a nemá jak poznat, který odstavec platí. Když si vybere ten druhý,
usoudí, že fakturu smí vystavit kdokoli, a nebude testovat 403 pro mechanika.

**Proč to vadí:** rozpor uvnitř jednoho dokumentu je horší než zastaralost — čtenář ztratí důvěru
v celý soubor.

**Návrh řešení:** v `UserController.java:23-25` nahradit větou „Celý controller je vyhrazen roli
ADMIN — správa účtů a rolí nesmí být dostupná běžné obsluze (viz `konvence.md §19`)".
V `api.md:359` škrtnout „druhý endpoint v API s rolovou autorizací" a nechat jen odkaz na §Autorizace.

---

### [A-7] Rozpor `frontend.md` × `plan-ui.md` o JSDoc — a co má platit
**Severita:** 🟡 NÍZKÝ
**Jistota:** OVĚŘENO (rozpor) / NÁVRHOVÉ (doporučený verdikt)
**Kde:** `docs/frontend.md:6` vs. `docs/plan-ui.md:55`, `:273`, `:281`, `:438`, `:601`, `:1210`, `:1325`

**Co je špatně:** dokumenty si odporují.

- `frontend.md:6`: „Jazyk UI: čeština. Žádný TypeScript, **žádné JSDoc typy** — `src/api/typeDefs/`
  byl neimportovaný mrtvý kód, smazán 2026-07-20 (TD-39)."
- `plan-ui.md:55` (pravidlo pro každý úkol plánu): „Styl okolního kódu: FE komentáře česky,
  **JSDoc u sdílených komponent** (vzor: `AutocompletePair.jsx`)." — a je to i **akceptační
  kritérium** čtyř úkolů: `:281` („`PageHeader` existuje s JSDoc a všemi pěti props"),
  `:438` („čtyři komponenty existují s JSDoc"), `:601` („`DataTable` má JSDoc"), `:1325`
  („do `format.js` přidat … s JSDoc").

**Co skutečně platí (změřeno):** JSDoc blok má **60 z 66** komponent v `src/components`.
Vzorová `AutocompletePair.jsx:37-51` obsahuje plně **otypovaný** JSDoc
(`@param {string} endpoint`, `@param {number} [limit=10]`, …), tedy přesně to, co `frontend.md:6`
označuje za neexistující. Bez JSDocu jsou: `ContactPersonCard`, `ImportProductFormModal`, `Layout`,
`OrderItemTable`, `OrderItemsWrapper`, `TableRowActionMenu`.

**Scénář selhání:** Vývojář (nebo agent) přidává sdílenou komponentu, přečte `frontend.md`
(dokument, na který ho posílá `CLAUDE.md:38`) a JSDoc vynechá — s odkazem na „žádné JSDoc typy".
Tím poruší závazný vzor, který drží zbytek `src/components`, a rozdrolí konzistenci, kterou plán UI
tři týdny budoval.

**Proč to vadí:** `plan-ui.md` je označen jako dokončený (`frontend.md:271`), takže se dá číst jako
historie; `frontend.md` je naopak živý průvodce. Vítězí tedy ten dokument, který je fakticky špatně.

**Verdikt — co má platit (doporučení):** platí **`plan-ui.md`**, tedy *JSDoc u sdílených komponent
je povinný*. Důvody: (1) 60/66 komponent ho už má, takže pravidlo popisuje realitu, ne přání;
(2) projekt nemá TypeScript ani FE testy (`frontend.md:18`), takže JSDoc je jediný strojově čitelný
popis kontraktu props; (3) `frontend.md:6` je ve skutečnosti *o něčem jiném* — mluví o smazaném
adresáři `src/api/typeDefs/` s `@typedef` definicemi domény, ne o JSDocu komponent, ale formulace
„žádné JSDoc typy" to smazává.

**Návrh řešení:** přepsat `frontend.md:6` na: „Žádný TypeScript ani centrální `@typedef` katalog
(`src/api/typeDefs/` byl neimportovaný mrtvý kód, smazán 2026-07-20 — TD-39). **Sdílené komponenty
ale mají JSDoc s typy props** — vzor `AutocompletePair.jsx`." a totéž pravidlo přidat do
`frontend.md §8 Konvence`, aby nežilo jen v dokončeném plánu.

---

### [A-8] Pokrytí Javadocem/JSDocem — statistika a místa, kde chybějící dokumentace nejvíc bolí
**Severita:** 🟡 NÍZKÝ
**Jistota:** OVĚŘENO
**Kde:** viz níže

**Stav (změřeno gripem `^\s*/\*\*` přes 224 souborů `src/main/java` a 66 souborů `src/components`):**

| Balíček | Souborů | Bez jediného doc bloku |
|---|---|---|
| `controller/` (+ `controller/warehouse/`) | 22 | **0** |
| `service/` (rozhraní) | 36 | 1 — `SupplierNormalizer` |
| `service/impl/` | 24 | 2 — `GoodsReceiptServiceImpl`, `ReceiptReviewServiceImpl` (bez class-level doc) |
| `mapper/` | 22 | 0 |
| `model/converter/` | 20 | 3 — `GoodsReceiptItemConverter`, `OrderItemSummaryConverter`, `SupplierConverter` |
| `security/**` | 20 | 0 |
| `model/dto/**` | ~50 | 2 — `OrderItemSummaryDto`, `SupplierDto` |
| FE `src/components/**` | 66 | 6 (viz níže) |

**Kde to bolí nejvíc** (netriviální logika / peníze / stavový automat bez popisu „proč"):

1. **`service/impl/ReceiptReviewServiceImpl.java`** (716 řádků, nejdelší třída projektu) — **nemá
   class-level Javadoc**. Přitom drží celý stavový automat příjemky, completeness gate
   (`validateCompleteness`, `:531-621`, 8 druhů kontrol), materializaci skladu a guardované
   přechody. Jednotlivé metody dokumentované jsou, ale chybí věta, která celek zarámuje.
2. **`service/impl/GoodsReceiptServiceImpl.java`** — 0 doc bloků na 52 řádcích.
3. **FE `components/OrderItemsWrapper.jsx`** — bez JSDocu, přitom `frontend.md:213` ho označuje za
   „nejsložitější celek" (stav položek, CRUD, drag-and-drop s optimistickým updatem a rollbackem,
   předvyplnění sazby mechanika). `OrderItemTable.jsx` (dnd-kit) rovněž bez JSDocu.
4. **FE `components/TableRowActionMenu.jsx`** — bez JSDocu, přitom je to jedna ze dvou MUI
   komponent (rozhodnutí R-1) a používají ji **všechny** seznamy; kontrakt `actions[]`
   (`{id, label, icon, color}`) se dá zjistit jen čtením volajících.
5. Dále bez JSDocu: `Layout.jsx`, `ContactPersonCard.jsx`, `ImportProductFormModal.jsx`.

**Užitečnost — kde dokumentace jen opakuje název:** převažující vzor v `controller/` je
`/** Returns the full detail of X by ID. @param id X ID @return 200 OK with X detail */`, tedy
přepis signatury. Není to škodlivé, ale u endpointů s netriviálním chováním chybí *proč*: `POST
/orders/{orderId}/items` nezmiňuje zámek fakturou (viz A-2), `POST /users/{id}/reset-password`
(`UserController.java:118-124`) nezmiňuje, že zároveň odemyká zamčený účet, `DELETE
/orders/{orderId}/items/{id}` (`OrderItemController.java:130-135`) nezmiňuje vrácení dílu na sklad.
Naopak vzorově dělané jsou `OrderItemServiceImpl.applyLaborEmployee` (`:335-348`),
`UserServiceImpl.requireAdminRoleNotRemovedFromLastAdmin` (`:125-135`) a
`ReceiptReviewServiceImpl.requireNotUsed` (`:419-428`) — všechny vysvětlují *proč*, ne *co*.

**Scénář selhání:** Nový vývojář dostane úkol „přidat sloupec do tabulky položek zakázky".
`OrderItemsWrapper` (koordinátor) nemá jediný komentář, takže netuší, že reorder je optimistický
s rollbackem a že mechanik se předvyplňuje ze sazby — jeho změna jeden z těch mechanismů rozbije
a FE nemá testy (`frontend.md:18`), takže to nic nezachytí.

**Proč to vadí:** je to výuková aplikace; onboarding stojí na komentářích, protože testovací síť na
FE není žádná.

**Návrh řešení:** doplnit class-level Javadoc u `ReceiptReviewServiceImpl` a
`GoodsReceiptServiceImpl` a JSDoc u `OrderItemsWrapper` + `TableRowActionMenu` (čtyři soubory,
vysoký poměr užitku k práci). Zbytek řešit průběžně při dotyku se souborem — plošné doplňování
by vygenerovalo právě tu dokumentaci, která jen opakuje název.

---

### [C-5] Souhrnná čísla o velikosti projektu jsou zastaralá ve třech dokumentech
**Severita:** 🟡 NÍZKÝ
**Jistota:** OVĚŘENO
**Kde:** `docs/architektura.md:17,91,99,100`, `docs/backend.md:30,39,130`, `docs/tech-dluhy.md:101`

**Co je špatně:**

| Dokument | Tvrzení | Skutečnost (změřeno) |
|---|---|---|
| `architektura.md:17` | „Flyway (aktuálně **V1–V44**)" | **V1–V63** (`databaze.md:3` to má správně) |
| `architektura.md:100` | „`db/migration/` ← Flyway **V1–V44**" | 55 souborů ve `db/migration`, + `db/demo` (7) a `db/prod` (2) — model tří locations (`konvence.md §14`) se v mapě repozitáře vůbec neobjevuje |
| `architektura.md:91` | „`controller/` ← **13 REST controllerů** (+ warehouse/)" | 15 v `controller/` + 7 v `controller/warehouse/` + 1 v `security/controller/` = **23** |
| `architektura.md:99` | „`mapper/**/*.xml` ← veškeré SQL (**13 + 4 warehouse XML**)" | **18 + 7 = 25** XML |
| `architektura.md:47-55` | tabulka modulů | chybí modul **Zaměstnanci** (schéma `employee`, V58/V59), který má vlastní controller, service, mapper, FE stránky i článek nápovědy |
| `backend.md:30` | „`converter/` — **17** ručních `@Component` konvertorů" | **20** |
| `backend.md:39` | „XML mappery (`src/main/resources/mapper/`, **14 + 4 warehouse**)" | **18 + 7 = 25** |
| `backend.md:130` | „Stav 2026-07-24: **742 testů**" | **784** `@Test` metod v **83** testovacích třídách |
| `tech-dluhy.md:101` | „(21 controllerů, 101 endpointů, 24 mapperů, 18 konvertorů, **18 handlerů**, 55 migrací, 75 test tříd)" | 23 / 112 / 25 XML / 20 / **19** `@ExceptionHandler` / 63 verzí migrací / 83 test tříd |

**Scénář selhání:** Před přidáním migrace si někdo ověří rozsah v `architektura.md:17` („V1–V44"),
založí `V45__…sql` a Flyway ho odmítne jako out-of-order (`out-of-order: false`,
`konvence.md §14`) — V45 už existuje. Skill `nova-migrace` sice čísluje sám, ale dokument ho
aktivně navádí špatně. U mapy repozitáře je následek mírnější: hledání „13 controllerů" v adresáři
s 23 soubory vyvolá dojem, že se dívá do špatného projektu.

**Proč to vadí:** samotná čísla nikoho nezabijí, ale `architektura.md` je podle `CLAUDE.md:39`
vstupní bod pro „celkový obraz systému" — a nese rozsah migrací starý 19 verzí. Čtenář, který
jednou přistihne dokument při hrubě špatném čísle, přestane věřit i jeho architektonické části,
která je jinak dobrá.

**Návrh řešení:** aktualizovat čísla; u `architektura.md:100` doplnit `db/demo` a `db/prod`
do mapy repozitáře a modul Zaměstnanci do tabulky modulů. Do budoucna raději rozsah nečíslovat
(„Flyway, aktuální rozsah viz `databaze.md §11`") — číslo, které se mění každý týden, do
přehledového dokumentu nepatří.

---

### [C-6] `frontend.md` §9 vydává dva vyřešené dluhy za otevřené a §5 jmenuje neexistující hook
**Severita:** 🟡 NÍZKÝ
**Jistota:** OVĚŘENO
**Kde:** `docs/frontend.md:279`, `:282`, `:153`
vs. `docs/tech-dluhy.md:178` (TD-42), `docs/tech-dluhy.md:233` (TD-46),
`frontend/autoservis-frontend/src/hooks/` (výpis adresáře)

**Co je špatně:**
1. Tabulka „Co zůstalo otevřené a **proč to plán neřešil**" (`frontend.md:277-283`) obsahuje řádky
   „Adresu existujícího zákazníka nelze změnit | TD-42" (`:279`) a „Řazení zbývajících sloupců bez
   whitelistu | TD-46" (`:282`). **Oba dluhy jsou v `tech-dluhy.md` označené jako vyřešené**
   (TD-42 ✅ 2026-07-25, TD-46 ✅ 2026-07-25 — „`sortDesc` se používá ve všech 8 mapperech").
   Ověřeno i přímo: sort whitelisty existují ve všech osmi mapperech (Customer, User, Vehicle,
   Invoice, Order, Warehouse, Supplier, ReceiptReview) + StockTake.
2. `frontend.md:153` vyjmenovává entitní row-action hooky jako „(`useCustomerRowActions`,
   `useVehicleRowActions`, **`useOrderRowActions`**, `useSupplierRowActions`,
   `useWarehouseRowActions`)". `useOrderRowActions` v `src/hooks/` **neexistuje** (adresář obsahuje
   `useRowActions`, `useCustomerRowActions`, `useEmployeeRowActions`, `useSupplierRowActions`,
   `useUserRowActions`, `useVehicleRowActions`, `useWarehouseRowActions`) — a naopak chybí
   `useEmployeeRowActions`, který existuje. `frontend.md:32` přitom správně píše „6× tenký wrapper".

**Scénář selhání:** Vývojář má za úkol přidat řádkové akce k nové entitě, vezme si za vzor
`useOrderRowActions` — a nenajde ho. Menší dopad: podpora znovu potvrdí uživateli, že adresa nejde
změnit (viz C-2), protože i druhý dokument to potvrzuje.

**Proč to vadí:** `frontend.md` je jediný průvodce frontendem a v obou případech odkazuje mimo
realitu. Sekce „co zůstalo otevřené" má navíc charakter závazku — když v ní zůstávají hotové věci,
nedá se použít jako seznam k práci.

**Návrh řešení:** vyškrtnout řádky TD-42 a TD-46 z tabulky `:277-283`, opravit výčet hooků na
`:153` (nahradit `useOrderRowActions` za `useEmployeeRowActions`).

---

### [C-7] `api.md` §Řazení nezná tři whitelisty a dva klíče; chybí chybový kód `CANNOT_REMOVE_LAST_ADMIN`
**Severita:** 🟡 NÍZKÝ
**Jistota:** OVĚŘENO
**Kde:** `docs/api.md:12-21`, `docs/api.md:366`, `docs/api.md:398`
vs. sort whitelisty v mapperech a `src/main/java/cz/palo/autoservis/service/impl/UserServiceImpl.java:152-156`

**Co je špatně:**

*(a) Neúplný výčet seřaditelných klíčů.* `api.md:15` slibuje „Podporované klíče" a vyjmenovává
šest endpointů. Proti mapperům:

| Endpoint | `api.md:16-21` | Whitelist v XML |
|---|---|---|
| `/invoices` | invoiceNumber, customerName, issueDate, dueDate, totalGross | + **`status`** (`InvoiceMapper.xml:294`, řadí podle pořadí ve stavovém automatu, ne abecedně) |
| `/warehouse/products` | sku, quantityOnHand, salePrice | + **`unit`** (`WarehouseMapper.xml:91`) |
| `/warehouse/suppliers` | registrationNumber, city | + **`vatId`** (`SupplierMapper.xml:100`) |
| `/orders` | **není uveden vůbec** | `OrderMapper.xml:179-198`: orderNumber, status, customerName, licensePlate, brand, estimatedCompletionAt |
| `/warehouse/receipts` | **není uveden vůbec** | `ReceiptReviewMapper.xml:107-122`: documentNumber, supplierName, issueDate, totalAmount, documentType, status |

`api.md:110` se přitom na existenci whitelistu zakázek odvolává („není v whitelistu
`orderSortOrder`"), aniž by ho §Řazení uvedl.

*(b) Nedokumentovaný chybový kód.* `UserServiceImpl.java:152-156` vyhazuje
`BusinessRuleException("CANNOT_REMOVE_LAST_ADMIN")` z `PUT /users/{id}`. Tabulka endpointů
`api.md:366` u `PUT /users/{id}` uvádí jen „200; 422 `DUPLICATE_EMAIL`" a souhrnná tabulka kódů
`api.md:398` vyjmenovává `CANNOT_DEACTIVATE_SELF` a `CANNOT_DEACTIVATE_LAST_ADMIN`, ale
`CANNOT_REMOVE_LAST_ADMIN` **nikde v `docs/` není** (grep).

**Scénář selhání:** (a) `frontend.md:134` říká „seřaditelné jsou jen sloupce, které má backend
ve whitelistu (**viz `api.md`**)" — kdo se toho drží, neudělá klikatelnou hlavičku „Stav" u faktur
ani žádné řazení u zakázek, ačkoli backend obojí umí. (b) FE po pokusu odebrat roli poslednímu
adminovi dostane 422 s kódem, který nezná; podle `frontend.md:100` sice zobrazí `err.problem.detail`
(česky, srozumitelně), ale nikdo nemá jak dopředu vědět, že tenhle stav existuje a je potřeba ho
otestovat.

**Proč to vadí:** §Řazení je jediný zdroj, ze kterého FE zjistí, co smí nabídnout; neúplnost
z něj dělá brzdu funkcí, které jsou hotové. Nedokumentovaný guard je horší — je bezpečnostně
relevantní (brání zamčení aplikace) a nikde není zaznamenaný.

**Návrh řešení:** doplnit `/orders` a `/warehouse/receipts` do `api.md:16-21`, přidat chybějící
klíče (`status`, `unit`, `vatId`) a `CANNOT_REMOVE_LAST_ADMIN` do `api.md:366` i do výčtu
`BusinessRuleException` na `:398`.

---

### [C-8] Dvě pravidla z `konvence.md`/`frontend.md`, která kód soustavně porušuje
**Severita:** 🟡 NÍZKÝ
**Jistota:** OVĚŘENO (rozsah porušení) / NÁVRHOVÉ (co s tím)
**Kde:** `docs/konvence.md` R-08 (řádek 17, detail §8 na `:142-153`);
`docs/frontend.md:538` („`btn-outline-primary` neexistuje")

**Co je špatně:**

*(a) R-08 „`if` bloky mají vždy složené závorky, i jednořádkové".*
Změřeno regexem přes `src/main/java`: **73** jednořádkových `if` bez závorek. Nejsou to výjimky
v okrajovém kódu — jsou v `security/controller/AuthController.java:233,235`,
`security/filter/JwtAuthenticationFilter.java:123,125`, `service/DraftAssembler.java`
(7×), `service/DraftVerificationService.java`, `service/impl/ReceiptReviewServiceImpl.java`
(`:333, :451, :535-546, :552, :558, :613-615, :689, :700`),
`model/converter/RegistryConverter.java` (9× za sebou, `:52-59`).

*(b) `frontend.md:538` „`btn-outline-primary` neexistuje — neutrální akce je vždy
`btn-outline-secondary`".* Grep vrací **5** výskytů:
`components/OrderItemsToolbar.jsx:12,16,20` (Přidat položku / Importovat položky / Vytvořit
fakturu) a `pages/VehiclesPageDetail.jsx:273,319`. `npm run check` (`scripts/check-ui.mjs`) tohle
pravidlo **nehlídá** — z barev kontroluje jen `text-bg-*`.

**Scénář selhání:** Pravidlo, které se porušuje na 73 místech, přestává být pravidlem — nový kód se
podle něj neřídí a v code review se nedá vymáhat („vždyť to takhle má půlka projektu"). Konkrétně
u (b): `frontend.md §10.8` zároveň říká „nejvýš jedno plné tlačítko na obrazovku" a přiděluje
barvám význam; tři modré obrysové knoflíky v jedné liště ten systém nabourávají, protože modrá tam
podle tabulky patří jen plnému hlavnímu tlačítku.

**Proč to vadí:** brief se ptá přímo — pravidlo, které kód systematicky porušuje, může být špatné
pravidlo. Tady jsou to dva různé případy a zaslouží si dvě různá rozhodnutí.

**Návrh řešení** *(obojí je rozhodnutí uživatele)*:
- **R-08:** zdůvodnění v `konvence.md §8` odkazuje na Apple goto-fail (2014). Ten bug ale vznikl
  z **dvou příkazů** pod bezzávorkovým `if` na dalších řádcích — ne z jednořádkové guard klauzule
  `if (x == null) return null;` na téže řádce, kde k záměně dojít nemůže. Doporučuji pravidlo
  **zpřesnit**: „vždy závorky, **s jedinou výjimkou guard klauzule na téže řádce**
  (`if (podmínka) return/throw/continue …;`)" a vynutit ho Checkstyle pravidlem
  `NeedBraces` s `allowSingleLineStatement=true`. Alternativa (doplnit 73× závorky) je čistší,
  ale je to 73 dotyků v kódu bez funkční změny.
- **`btn-outline-primary`:** pravidlo je smysluplné a porušení je jen 5×, takže tady doporučuji
  **opravit kód** (3× v `OrderItemsToolbar`, 2× ve `VehiclesPageDetail` → `btn-outline-secondary`)
  a přidat do `check-ui.mjs` deváté pravidlo, které `btn-outline-primary` zakáže — jinak se to
  vrátí.

---

## Co bylo ověřeno jako v pořádku

**Nápověda (obsahově):**
- Jazyk je důsledně jazykem obsluhy servisu, ne vývojáře — žádné názvy tříd, endpointů ani
  databázových sloupců v žádném z 15 článků. Sekce „Časté situace" / „Časté dotazy" mají 6 z 15
  článků a řeší reálné „co dělám, když…" (`prijem-zbozi.md:48-53`, `sklad-pohyby.md:49-53`,
  `inventura.md:29-33`, `stk-registr.md:38-43`).
- Nejlépe napsané články: `inventura.md` (vysvětluje *proč* prázdné pole není nula),
  `sklad-pohyby.md §Proč se vybírá šarže`, `stk-registr.md §Proč nejde datum STK přepsat ručně` —
  vysvětlují záměr, ne jen ovládání.
- Ověřeno proti kódu a **sedí**: `prijmovy-pokladni-doklad.md` celý (tlačítko „Pokladní doklad",
  viditelné jen u ISSUED/PAID — `InvoicesPageDetail.jsx:112-116`; zaokrouhlení na celé Kč; číslo
  řady `PPD…`; víc dokladů k jedné faktuře); `inventura.md` celý (tlačítka „Zahájit inventuru"
  `StockTakesPage.jsx:123`, „Uložit soupis"/„Uzavřít inventuru"/„Zrušit inventuru"
  `StockTakePageDetail.jsx:214,217,220`; jen jedna otevřená; rozdíl proti aktuálnímu stavu; manko
  FIFO); `zakazky.md` (stavy odpovídají `OrderStatus`; zámek fakturou a odemčení stornem; marže
  „u položky bez nákupní ceny je náklad 0 → 100 %" přesně odpovídá `V63__order_item_summary_cost.sql`);
  `faktury.md` (stavy, číslo až při vystavení, QR jen s IBANem, filtry „Stav"/„Splatnost");
  `sklad-pohyby.md §Proč se vybírá šarže` (nejstarší šarže předvybraná —
  `StockMovementModal.jsx:44-49`) a §Přebytek; `nastaveni-firmy.md` (vedení-only odpovídá
  `@PreAuthorize` na `CompanyProfileController.java:38`); `dodavatele.md`; `zamestnanci.md`
  (snímek sazby, „mimo číselník", jen k typu Práce); `stk-registr.md` (barvy štítku, chybové hlášky,
  „rok výroby registr neposkytuje").

**Javadoc — věcně správné i u netriviální logiky:**
- `config/security/SecurityConfig` class-level doc (`:28-52`) přesně odpovídá kódu včetně
  baseline rolí, hlaviček i CORS z konfigurace.
- `security/controller/AuthController` (`:21-36`) — „tokens are transmitted exclusively via
  HTTP-only cookies, never in response bodies" dnes **platí**; endpoint, který to porušoval
  (`/auth/register`, K2), byl odstraněn.
- `security/filter/JwtAuthenticationFilter` (`:25-56`) — pořadí validace i regex veřejných cest.
- `security/service/AuthenticationService` (`:29-48`, `:62-82`, `:106-121`, `:171-186`) — rotace,
  detekce reuse, lockout i revokace sessions při self-service změně hesla.
- `service/impl/InvoiceServiceImpl` — komentáře k TD-49 (`:194-197`), TD-58 (`:201-203`)
  a guardovanému UPDATE (`:443-445`) odpovídají kódu do posledního detailu.
- `service/impl/StockTakeServiceImpl` + `service/StockTakeService` — celé, včetně účetního
  zdůvodnění nulového DPH u přebytku (`:243-247`).
- `service/impl/ReceiptReviewServiceImpl` — TD-59 (`:185-194`, `:458-475`), `requireNotUsed`
  (`:419-428`), `resolveProduct` (`:651-655`).
- `model/enums/InvoiceStatus` — dokumentovaná matice přechodů (`:14-22`) přesně odpovídá
  `ALLOWED_TRANSITIONS` (`:23-28`).
- `model/dto/warehouse/StockMovementDto` (`:17-24`) — jediné ze čtyř míst, které o typech pohybu
  nelže.

**docs/ — ověřeno a v pořádku:**
- `docs/databaze.md` (povrchová kontrola): hlavička uvádí V1–V63, index migrací §11 pokrývá
  V62/V63, schéma `employee` má vlastní sekci §6b, dvojče V58 demo/prod je popsané správně.
  Detailní ověření patří jinému průchodu.
- `docs/konvence.md §19` (rolová autorizace) — odpovídá 21 `@PreAuthorize` v kódu.
- `docs/api.md §Autorizace` (`:10`) — matice vedení-only operací sedí endpoint po endpointu
  (faktura issue/pay/cancel, credit-notes, cash-receipts, (de)aktivace zákazníka a vozidla,
  PUT company-profile, stock-takes close, `/users` ADMIN-only).
- `docs/api.md §Chybové odpovědi` — všech 19 `@ExceptionHandler` v `GlobalExceptionHandler` má
  v tabulce protějšek (s výjimkou chybějícího kódu `CANNOT_REMOVE_LAST_ADMIN`, viz C-7).
- `docs/funkce/dashboard.md` a `docs/funkce/sklad-pohyby.md` — přečteny celé, **žádný rozpor
  s kódem**; obě obsahují i nejnovější změny (Statistika z 2026-07-30, čtyři typy pohybu).
  Funkční dokumenty jsou zjevně nejlépe udržovanou vrstvou dokumentace.
- `docs/frontend.md §10` (UI konvence) — vzory `PageHeader`/`FormSection`/`Modal`/`StatusBadge`
  odpovídají komponentám; osm pravidel v `check-ui.mjs` existuje a jsou popsaná správně.
- Endpointy `api.md` proti controllerům: cesty, HTTP metody a DTO **sedí u všech 112 endpointů**;
  žádný endpoint neexistuje v kódu bez záznamu v těle `api.md` (chyby jsou jen v souhrnné tabulce,
  viz C-3).

## Otevřené otázky pro uživatele

1. **[A-1] Má admin reset hesla ukončovat sessions?** Doporučuji ano (jeden řádek v
   `UserServiceImpl.resetPassword`). Pokud ne, je nutné opravit `api.md:47` a někam zapsat, že
   „odstřihnutí" uživatele vyžaduje deaktivaci účtu, ne reset hesla.

2. **[B-1] Co s rolemi `ROLE_CUSTOMER` a `ROLE_READONLY` v zakládání účtu?** Nechat je nabízené
   (a jen o nich mlčet v nápovědě), nebo je z `GET /code-lists/roles` odfiltrovat / v UI zašedit,
   dokud zákaznický portál nevznikne?

3. **[B-2] Rozšířit hledání zákazníků o číslo, e-mail a telefon** (tři OR větve v
   `CustomerMapper.xml searchWhere`), nebo opravit nápovědu na dnešní rozsah? Provozně to vypadá
   jako užitečná funkce, ale je to vaše volba.

4. **[C-4] Filtry `customerType` a `city` u `/customers`** — doimplementovat, nebo smazat mrtvá
   pole DTO a vyškrtnout je z `api.md`? (Doporučuji smazat.)

5. **[C-8] Pravidlo R-08** — zpřesnit na „závorky vždy, kromě jednořádkové guard klauzule"
   a vynutit Checkstyle, nebo dorovnat 73 míst v kódu?

6. **[B-7] Provázanost nápovědy** — stojí za to investovat do `helpSlug` v `PageHeader`
   a vyhledávání v `HelpPage`? Bez toho se k článkům dostane jen ten, kdo je aktivně hledá.

7. **[B-7] Dobropis (opravný daňový doklad)** má hotový backend včetně PDF, ale žádné FE ani
   článek nápovědy. Je to záměrné odložení (a má se to zapsat do `tech-dluhy.md`), nebo se na
   napojení zapomnělo?
