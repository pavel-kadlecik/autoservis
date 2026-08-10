# oprava-chybove-stavy-2026-07.md — Oprava a sjednocení chybových stavů (červenec 2026)

> Zpětná vazba k session „chybové stavy do produkce" (2026-07-26): **co** a **proč** se měnilo, včetně
> zdůvodnění klíčových rozhodnutí. Souvisí: [tech-dluhy.md](tech-dluhy.md) TD-60 (vyřešeno),
> TD-66 (návazný i18n). Změněné dokumenty: [api.md](api.md), [konvence.md](konvence.md).

---

## 1. Spouštěč

Při změně hesla se **i po úspěšné změně** na frontendu zobrazila chyba „Heslo se nepodařilo změnit."
Protože aplikace jde na produkci, zadání se rozšířilo: projít **všechny chybové stavy** vracené z backendu
na frontend, sjednotit je a zajistit, že jsou **česky, konkrétní a srozumitelné** (uživatel hned ví, co je špatně).

## 2. Kořenová příčina bugu se změnou hesla

`AuthController.changePassword` vracel `ResponseEntity<String>`. Pro návratový typ `String` použije Spring
`StringHttpMessageConverter` → odešle `Content-Type: text/plain` a tělo jako **nekótovaný** řetězec
`Heslo bylo úspěšně změněno.`

Na frontendu `api.js` na úspěšné 2xx volá `JSON.parse(text)` → `JSON.parse("Heslo bylo úspěšně změněno.")`
vyhodí **`SyntaxError`** (není validní JSON). Ta výjimka **není `ApiError`**, takže `err.problem` je
`undefined` → komponenta zobrazí generický fallback. **Úspěšná akce vypadala jako selhání.**

## 3. Co se změnilo (po kategoriích)

### A. Backend — odpovědi (kořen bugu)
- `AuthController.changePassword` a `logout`: `ResponseEntity<String>` → `ResponseEntity<Void>` +
  **204 No Content**. FE 204 zpracuje jako `null` → úspěch se zobrazí správně. (`api.md` aktualizováno.)

### B. Backend — angličtina → čeština v uživatelských hláškách
Projekt už měl pravidlo „uživatelské hlášky česky" (konvence §10, plan-oprav §1.6), ale na řadě míst bylo porušené:
- **`GlobalExceptionHandler`** — 9 `detail`/`errors[]` řetězců (500, validace, data integrity, malformed body,
  method not allowed, resource not found, extraction) → česky. `title` (RFC 9457 reason phrase, např.
  „Bad Request") zůstává anglicky — je to standard a FE ho nezobrazuje.
- **`ResourceNotFoundException`** — šablona „nebyl nalezen" → **„neexistuje"** (rodově neutrální); ~60 volání
  s anglickým názvem zdroje (`Customer`→`Zákazník`, `Vehicle`→`Vozidlo`, `Invoice`→`Faktura`, `Order`→`Zakázka`…) → česky.
- **Business pravidla** (Invoice/Order/Vehicle/Customer/Product/Supplier/Mileage/OrderItem/Address service) —
  „already exists", „open order(s)", „does not belong", „Source INITIAL", „Year of manufacture"… → česky.
- **Interní `IllegalState`/`IllegalArgument`** (invarianty „disappeared…", „is not configured",
  „SHA-256 unavailable", ID guardy) → česky. Do uživatele nejdou (500 vrací generickou hlášku), sjednoceny kvůli konzistenci logů.
- **`VehicleDto`** „Customer ID je povinný" → „ID zákazníka je povinné"; uživatelsky viditelná poznámka
  počátečního stavu tachometru → česky.

### C. Frontend — mutace ukazují konkrétní důvod
~13 catch bloků u **ukládání/vytváření/editace/mazání/akcí** přešlo z natvrdo fallbacku na
`err.problem?.detail ?? "<fallback>"` → uživatel vidí konkrétní důvod (např. „Zakázka už má fakturu.",
„Vozidlo s VIN … už existuje.", „Současné heslo není správné").

### D. Frontend — načítání ukazují konkrétní důvod
~13 catch bloků u **načítání (GET)** rovněž doplněno o `err.problem?.detail ?? "<fallback>"`
(vč. `DashboardPage`, kde se detail ukládá do stavu a zobrazuje v `hint`).

### E. Frontend — ochrana proti dvojkliku (double-submit)
`FormActions` nově interně blokuje tlačítka po prvním kliknutí, dokud `onSubmit` (promise z formulářového
`handleSave` → `onSave`) neproběhne — jednou změnou pokrývá všechny CRUD formuláře. Formuláře jen vrací
promise z `handleSave`; externí `saving` prop (řízený stránkou, dnes `EmployeesPage`) zůstává jako override.

### F. Frontend — seznam „faktury po splatnosti"
`InvoicesPage` má filtr „Splatnost → Po splatnosti" (deep-link `?overdue=true`, čte se z URL); dashboard
dlaždice „Faktury po splatnosti" míří na filtrovaný seznam. Backend `InvoiceSearchParams.overdue` už existoval.

## 4. Klíčová rozhodnutí a proč

- **204 místo JSON těla u change-password.** Příčina je v backendu (holý `String`), ne v klientovi. FE
  úspěšnou hlášku stejně hardcoduje, tělo nepotřebuje → 204 je sémanticky správné a nejmenší zásah.
- **`api.js` se NEzpevňoval.** Zvažováno obalení `JSON.parse` do `try/catch` jako pojistka proti ne-JSON
  2xx tělu. Zamítnuto — příčina patří do backendu (2xx vrací vždy JSON nebo 204), ne maskovat v klientovi.
- **„neexistuje" místo „nebyl nalezen".** Rodově neutrální — funguje pro mužský i ženský/střední rod
  („Faktura … neexistuje" vs. gramaticky špatné „Faktura … nebyl nalezen").
- **`resourceName` přeložen na česká slova, ne centralizován na obecnou hlášku.** FE `params.resourceName`
  jako token nikde nečte (ověřeno); jeho jediná cesta k uživateli je věta „<X> s ID <n> neexistuje". Obecné
  „záznam nebyl nalezen" by ztratilo informaci, **co** nebylo nalezeno → rozhodnutí zachovat konkrétnost.
- **Žádný nový FE helper.** Použit existující idiom `err.problem?.detail ?? fallback`, který už půlka
  komponent používá — bez nové abstrakce (R-12 „žádný kód navíc bez funkčního přínosu").

## 5. Rozsah a ověření

- **Backend:** kompilace OK, **celá suita 770 testů zelená** (0 selhání). Upraveny testy `JwtAuthFlowTest`
  (logout → 204), `ProblemDetailContractTest` (české `detail` + `params.resourceName`).
- **Frontend:** `npm run build` OK.
- **Finální grep** napříč `src/main/java`: žádná anglická uživatelská ani výjimková hláška nezůstala.
- Zbývají **dvě záměrná tichá pomocná načítání** (souhrn zakázky, dropdown mechaniků v `OrderItemsWrapper`) —
  selžou-li, jen se nezobrazí vedlejší data; snesitelný UX kompromis, ne chybějící hláška.

## 6. Návazný tech-dluh

- **TD-66** — plná i18n: uživatelské hlášky přesunout do `messages.properties` po kódech (`MessageSource`),
  jak už projekt dělá u custom validátorů. Dnes jsou české texty inline — pragmatické a správné pro
  jednojazyčnou aplikaci; katalog = čistší oddělení textu od logiky + snadné přidání jazyka.
- **TD-60 plně vyřešen 2026-07-26:** kategorie C–F výše pokryly i zbývající body (ochrana proti dvojkliku, seznam faktur po splatnosti).
