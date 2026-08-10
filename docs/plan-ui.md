# plan-ui.md — Prováděcí plán sjednocení UI (červenec 2026)

> Zdroj: [analyza-ui-2026-07.md](analyza-ui-2026-07.md) — nálezy S-01…S-28, rozhodnutí R-1…R-7.
> Vzor a pravidla převzata z [plan-sklad.md](plan-sklad.md) a [plan-oprav.md](plan-oprav.md):
> úkoly jsou malé, mají přesné soubory, postup, akceptační kritéria a pole „Co NEDĚLAT",
> aby je mohl samostatně provést i slabší model.
>
> ## ✅ PLÁN DOKONČEN 22. 7. 2026
>
> Všech 35 úkolů (U0–U8 + vsunutá fáze **U3R**) je hotových a ověřených v prohlížeči.
> Dokument je od teď **historický záznam** — závazný vzor pro nový kód je `frontend.md` §10
> a hlídá ho `npm run check`. Nové nálezy patří do `tech-dluhy.md`, ne sem.
>
> **Co se našlo navíc, mimo zadání plánu** (vždy až při klikání, ne při čtení kódu):
> | Nález | Kde |
> |---|---|
> | Backend ignoroval `sortDesc` — seznamy nešly řadit sestupně | TD-46, fáze U3R |
> | Čas dokončení zakázky se posouval při **každém** uložení | TD-47 |
> | Menu na mobilu nechalo obsahu 135 z 375 px | commit „menu pod 768 px překrývá obsah" |
> | Neúspěšné přeskládání položek se tiše vrátilo bez hlášky | U6.3 |
> | `NotFoundPage` a nápověda: chybějící/dvojitý `h1` | U5.1, U7.1 |
>
> **Poučení k ověřování:** třikrát se stalo, že „zelená kontrola" nic nedokazovala — build
> nezachytí neznámý identifikátor, test řazení nad prázdným seznamem projde vždy a sken
> nepoužitých importů s chybou v regexu hlásí OK, protože nekontroluje nic. Proto se
> u každého kritéria uvádí **naměřená hodnota nebo počet**, ne jen „ověřeno".
>
> **Stav rozhodnutí k 22. 7. 2026** (§7 analýzy): R-1…R-5 a R-7 rozhodnuty, **R-4 = b**
> (`remark-gfm`, doplněno v U7.2). R-6 se vyřešilo praxí — postupovalo se **postupně** (varianta a).
>
> Původní stav k 21. 7. 2026:
> **R-1 = a** (MUI zůstává v dnešním rozsahu, sjednotí se jen ikony v menu),
> **R-2 = a** (responsivita od 768 px, offcanvas sidebar pod 992 px),
> **R-3 = a** (jednotný jemný pill badge `bg-*-subtle text-*-emphasis rounded-pill`),
> **R-5 = a** (řazení klikem na hlavičku doplnit do `DataTable`),
> **R-7 = b** (čtyřtónová sémantika tlačítek — závazná specifikace v **§7.1 analýzy**).
> **Otevřené:** R-4 (GFM v nápovědě → blokuje jen U7.2), R-6 (postupně vs. naráz — plán předpokládá
> postupně). Fáze U0–U6 lze spustit ihned.

---

## 1. Pravidla pro vykonavatele (platí pro KAŽDÝ úkol)

1. Pracuj **výhradně na zadaném úkolu**. Nic „při té příležitosti" navíc.
2. Před prací přečti `CLAUDE.md` a všechny dokumenty z pole *Čti* daného úkolu.
3. Po dokončení ověř **všechna** akceptační kritéria a u každého napiš ✓/✗ s důkazem
   (výstup příkazu, číslo řádku, screenshot z prohlížeče).
4. **Vizuální ověření je povinné** u každého úkolu, který mění vzhled: spusť frontend
   (`.claude/launch.json` → „frontend"), projdi dotčené stránky v Browser panelu a přilož screenshot
   před/po. Backend musí běžet; přihlášení provádí uživatel.
5. **Regresní průchod:** po každém úkolu, který mění sdílenou komponentu, projdi *všechny* stránky,
   které ji používají (seznam je v zadání úkolu), a ověř, že se nic nerozbilo.
6. Aktualizuj dokumentaci uvedenou v poli **Dokumentace**, zaškrtni checkbox v §3 a **necommituj**.
7. Při nejistotě se **zastav a zeptej** — platí Pravidlo č. 1 z `CLAUDE.md`.
8. Styl okolního kódu: FE komentáře česky, JSDoc u sdílených komponent (vzor: `AutocompletePair.jsx`).
9. **Žádné změny backendu.** Jedinou výjimkou je U5.2 (řazení), a i tam jen pokud se ukáže, že
   endpoint parametr nepodporuje — pak se úkol zastaví a nahlásí.

## 2. Jak úkoly zadávat (pokyny pro uživatele)

Každý úkol v nové session, vlastní větev (`ui/U1-1-page-header` apod.):

```
Přečti si docs/plan-ui.md — nejdřív celou sekci §1 (pravidla), pak úkol <ID>.
Proveď úkol <ID> přesně podle zadání — provedení schvaluji („udělej to ty").
Pracuj výhradně na tomto úkolu. Po dokončení:
- vypiš akceptační kritéria a u každého ✓/✗ s důkazem,
- přilož screenshoty dotčených stránek (před/po),
- aktualizuj dokumentaci dle zadání a zaškrtni checkbox v §3.
Necommituj.
```

**Pořadí a závislosti** — základ musí předcházet plošné aplikaci:

```
U0 (opravy P0, nezávislé)
      ↓
U1 (sdílené komponenty a konvence)  →  U2 (navigace)  →  U3 (seznamy)
      ↓                                                        ↓
U4 (detaily)  →  U5 (formuláře)  →  U6 (modaly)  →  U7 (nápověda)  →  U8 (úklid + dokumentace)
```

> **Změna pořadí oproti původnímu plánu (21. 7. 2026).** Fáze U1 měla `PageHeader` nasadit jen
> pilotně na tři stránky. To se ukázalo jako chyba: mezi migrovanými a nemigrovanými stránkami
> vznikl **různý odstup pod nadpisem** (nadpisy nemají marginy, odstup dělala až náhodná třída
> prvku pod nimi) — tedy přesně ta nejednotnost, kterou má plán odstranit. `PageHeader` je proto
> nasazený na **všech** stránkách včetně detailů a formulářů, což předtahuje hlavičkovou část
> úkolů U3.2, U3.3, U4.2 a U5.1. Ze stejného důvodu byl předtažen **celý U6.1** — statická
> kontrola konvencí jinak nemohla být zelená. Zbytek těchto úkolů (tabulky, filtry, sekce
> formulářů) zůstává otevřený.

Tvrdé závislosti:
- **U1.1 před U3.x/U4.x/U5.x** (všechny používají `PageHeader`),
- **U1.2 před U3.2 a U4.2** (`StatusBadge`),
- **U1.4 před U6.x** (`Modal`),
- **U2.1 před U2.2** (struktura menu před chováním skupin),
- **U3.1 před U3.2–U3.4** (`DataTable` před přepisem tabulek).

Nikdy dva úkoly současně — U3 a U4 sahají do týchž souborů detailů.

---

## 3. Přehled úkolů a stav

| | ID | Název | Zdroj | Velikost | Riziko |
|---|---|---|---|---|---|
| [x] | U0.1 | Oprava pádu editace zákazníka | S-09 | XS | nízké |
| [x] | U0.2 | Error boundary kolem layoutu | S-09 | S | nízké |
| [x] | U0.3 | Nouzová oprava responsivity hlaviček a tabulek | S-01 | S | nízké |
| [x] | U1.1 | Komponenta `PageHeader` | S-02, S-03, S-04 | M | střední |
| [x] | U1.2 | `StatusBadge` + sjednocení badge v `format.js` | S-14, S-15, R-3 | M | střední |
| [x] | U1.3 | `EmptyState`, `LoadingState`, `ErrorState` | S-20, S-22 | S | nízké |
| [x] | U1.4 | Komponenta `Modal` (Esc, backdrop, focus, scroll lock) | S-12, S-13 | M | střední |
| [x] | U1.5 | `FormSection`, `FormActions`, `RequiredMark` + CSS validace | S-10, §4.4 | S | nízké |
| [x] | U1.6 | Filtry: `SearchFilter`, `SelectFilter`, `ToggleFilter` | S-06, S-07 | S | nízké |
| [x] | U1.7 | Zápis „UI konvencí" do `docs/frontend.md` | §4 analýzy | S | žádné |
| [x] | U2.1 | Přestavba menu: skupiny, „Nastavení firmy" na hlavní úroveň | S-23, zadání | M | střední |
| [x] | U2.2 | Rozbalovací skupiny s perzistencí + aktivní stav | S-24, S-25, R-x | M | střední |
| [x] | U2.3 | Offcanvas sidebar pod 992 px | S-01, R-2 | M | střední |
| [x] | U2.4 | Catch-all routa 404 (neznámá adresa vykreslila prázdno) | nález při U1 | XS | nízké |
| [x] | U3.1 | Sdílená `DataTable` (+ řazení dle R-5) | S-05, S-28, R-5 | L | **vysoké** |
| [x] | U3.2 | Převod seznamů zákazníci/vozidla/zakázky/dodavatelé/uživatelé | S-02…S-08 | L | střední |
| [x] | **U3R.1** | **BE: sjednotit řazení — pojmenovaný default místo `<otherwise>`** | TD-46, R-5 | M | střední |
| [x] | **U3R.2** | **BE: whitelist řazení pro zakázky a příjemky** | TD-46, R-5 | M | střední |
| [x] | **U3R.3** | **FE: doplnit seřaditelné sloupce na hotových seznamech** | R-5 | S | nízké |
| [x] | **U3R.4** | **FE: klientské řazení pro nestránkované seznamy** | R-5 | M | nízké |
| [x] | **U3R.5** | **Testy řazení pro všechny seznamy + dokumentace** | TD-46 | M | nízké |
| [x] | U3.3 | Převod seznamů skladu (sklad, příjemky, pod minimem, inventury) | S-02…S-08 | M | střední |
| [x] | U3.4 | Převod seznamu faktur (stavové akce do menu) | S-05 | M | střední |
| [x] | U4.1 | `MetricCard`/`MetricRow`/`DetailCard` a jejich nasazení | §4.3 | M | nízké |
| [x] | U4.2 | Sjednocení hlaviček, akcí a barev na detailech (vč. procesních obrazovek) | S-03, S-04, R-7 | L | střední |
| [x] | U5.1 | Převod formulářů na kartový vzor | S-09, §4.4 | L | střední |
| [x] | U5.2 | Povinná pole, chování validace, skok na první chybu | S-10 | M | nízké |
| [x] | U6.1 | Převod 9 modalů na komponentu `Modal` | S-12, S-13 | L | střední |
| [x] | U6.2 | `FormModal` pro dialogy s povinným polem | S-13 | M | nízké |
| [x] | U6.3 | Sjednocení alertů a z-indexu toastů | S-17, S-18, S-19 | M | nízké |
| [x] | U7.1 | Typografie nápovědy (`help.css`) | S — §6 | S | nízké |
| [x] | U7.2 | GFM v nápovědě (dle R-4) | R-4 | XS | nízké |
| [x] | U8.1 | Sjednocení formátovačů v `format.js` | S-27 | M | nízké |
| [x] | U8.2 | Úklid mrtvého kódu a textů | S-16, S-19, S-20, S-21, S-26 | S | nízké |
| [x] | U8.3 | Závěrečná aktualizace dokumentace | — | S | žádné |

---

## 4. Úkoly

### FÁZE U0 — Opravy P0 (nezávislé na rozhodnutích, lze spustit ihned)

---

#### U0.1 · Oprava pádu editace zákazníka (S-09)

**Proč:** `/customers/{id}/edit` vyrenderuje bílou stránku. `CustomersPageEdit` nepředává adresní
pole, která `CustomerForm` čte bez ochrany. Funkce „upravit zákazníka" je dnes nepoužitelná.

*Čti:* `frontend/autoservis-frontend/src/pages/CustomersPageEdit.jsx`,
`frontend/autoservis-frontend/src/components/CustomerForm.jsx`,
`frontend/autoservis-frontend/src/pages/CustomersPageCreate.jsx` (vzor `toApiPayload`), `docs/api.md` (customers).

**Postup:**
1. V `CustomersPageEdit.jsx` do `setCustomer({…})` doplnit stejný tvar adresního stavu, jaký staví
   `CustomersPageCreate.jsx:14-44`: `billingAddress`, `contactAddress`, `hasSeparateContact`.
   Naplnit je z `data.addresses` — adresa s `addressType === "BILLING"` do `billingAddress`,
   `"CONTACT"` do `contactAddress`, `hasSeparateContact = existuje CONTACT adresa`.
   Chybějící adresu nahradit prázdným tvarem (`countryCode: "CZ"`).
2. V `onSave` použít **stejný** převod na API tvar jako create (`toApiPayload`) — vytáhnout ho do
   sdíleného modulu (např. `src/api/customerPayload.js`) a importovat v obou stránkách,
   ať se logika nerozejde.
3. V `CustomerForm.jsx` navíc zpevnit čtení: na začátku komponenty normalizovat
   `initialData` (`billingAddress ?? EMPTY_ADDRESS`) — obrana proti stejné chybě v budoucnu.
4. Ověřit v prohlížeči: otevřít `/customers/1/edit`, změnit ulici, uložit, zkontrolovat na detailu.

**Co NEDĚLAT:** neměnit backend ani tvar API; neměnit `CustomerForm` layout (to řeší U5.1);
nepředělávat `CustomersPageCreate` nad rámec vytažení `toApiPayload`.

**Akceptační kritéria:**
- [ ] `/customers/{id}/edit` se vyrenderuje u firemního i fyzického zákazníka (screenshot obou);
- [ ] fakturační adresa je předvyplněná hodnotami z API;
- [ ] u zákazníka s kontaktní adresou je checkbox „Kontaktní adresa je jiná" zaškrtnutý a pole vyplněná;
- [ ] uložení změny se projeví na detailu; adresy nezmizí ani nezduplikují;
- [ ] konzole prohlížeče bez chyb a bez varování „An error occurred in the <CustomerForm> component".

**Dokumentace:** `docs/frontend.md` §5 — jedna věta k `CustomerForm` (sdílený tvar adresního stavu
pro create i edit).

> **Provedeno 21. 7. 2026 — odchylka od zadání.** Bod 1–2 zadání předpokládal, že se adresy do edit
> formuláře doplní a budou se ukládat. **`CustomerDto.UpdateRequest` ale pole `addresses` nemá**
> ([CustomerDto.java:74-113](../src/main/java/cz/palo/autoservis/model/dto/customer/CustomerDto.java)),
> takže PUT je nepřijímá. Posílat je by buď skončilo chybou, nebo by uživateli tiše lhalo, že se
> adresa uložila. Řešení: v edit režimu se adresní sekce **nezobrazuje** a nahrazuje ji poznámka
> „Adresy se zadávají při zakládání zákazníka a tímto formulářem se nemění."; `toUpdatePayload`
> adresní klíče z těla vyjímá. Editace adres u existujícího zákazníka je tím pádem **chybějící
> funkce** (backend + FE) — zapsat do `tech-dluhy.md` jako samostatný úkol, viz U8.2.

---

#### U0.2 · Error boundary kolem layoutu (S-09)

**Proč:** Když komponenta spadne, uživatel vidí bílou stránku bez jakéhokoli vysvětlení a bez cesty
zpět. Jakákoli budoucí chyba se projeví stejně.

*Čti:* `frontend/autoservis-frontend/src/components/Layout.jsx`, `src/App.jsx`, `docs/frontend.md` §3.

**Postup:**
1. Nová komponenta `src/components/ErrorBoundary.jsx` (class komponenta s `componentDidCatch`)
   zobrazující kartu: nadpis „Něco se pokazilo", text „Stránku se nepodařilo zobrazit.", tlačítka
   „Zkusit znovu" (reset stavu) a „Zpět na Dashboard". V dev režimu (`import.meta.env.DEV`)
   navíc `<pre>` s `error.message`.
2. Obalit `<Outlet />` v `Layout.jsx` — sidebar tak zůstane funkční i při pádu obsahu.
3. Ověřit dočasným vyhozením chyby v `DashboardPage` (po ověření vrátit zpět).

**Co NEDĚLAT:** neobalovat každou stránku zvlášť; neposílat chyby nikam ven (žádná telemetrie);
neskrývat chybu v dev režimu.

**Akceptační kritéria:**
- [ ] uměle vyvolaná chyba ve stránce zobrazí kartu s hláškou, sidebar zůstane funkční (screenshot);
- [ ] „Zpět na Dashboard" funguje, „Zkusit znovu" překreslí stránku;
- [ ] v produkčním buildu se nezobrazuje `error.message`.

**Dokumentace:** `docs/frontend.md` §3 — odstavec o error boundary.

---

#### U0.3 · Nouzová oprava responsivity hlaviček a tabulek (S-01)

**Proč:** Při šířce okna pod ~1250 px se akční tlačítka zúží na 26 px a sloupec „Akce" zmizí mimo
obraz bez možnosti scrollu. Než přijde plná přestavba (U2.3, U3.x), je potřeba stav odblokovat.

*Čti:* `frontend/autoservis-frontend/src/index.css`, `src/pages/WarehousePage.jsx`,
`src/pages/CustomersPage.jsx`, `src/pages/VehiclesPage.jsx`, `src/pages/UsersPage.jsx`,
`src/pages/SuppliersPage.jsx`, `src/pages/ReceiptsPage.jsx`, `analyza-ui-2026-07.md` §2.1.

**Postup:**
1. V `index.css:25` **odstranit `overflow-x: hidden`** z `#main-content`.
2. Ve všech seznamech obalit tabulku `<div className="table-responsive">` (tabulky uvnitř
   `CustomerTable`, `VehicleTable`, `OrderTable`, `SupplierTable`, `UserTable`, `ProductTable`,
   `InvoiceTable` a inline tabulky v `ReceiptsPage`, `StockTakesPage`).
3. V hlavičkových řádcích seznamů nahradit `flex-nowrap` za `flex-wrap` a **odstranit `text-truncate`
   z tlačítek**; sloupce `col-8`/`col-4` nahradit `col-12 col-xl-7` / `col-12 col-xl-5` (přesné
   hodnoty ověřit v prohlížeči).
4. Ověřit při šířkách 1440, 1200, 992 a 760 px na `/warehouse`, `/customers`, `/vehicles`, `/users`.

**Co NEDĚLAT:** nezavádět zatím `PageHeader` ani nové filtrové komponenty (U1.1, U1.6);
neměnit sidebar (U2.3); needitovat obsah tabulek.

**Akceptační kritéria:**
- [ ] na `/warehouse` při 1200 px mají obě tlačítka plný text (změřit
      `document.querySelectorAll('.row.mb-3 button')` → šířka > 100 px, doložit výstupem);
- [ ] při 760 px jde ve všech seznamech doscrollovat na sloupec „Akce" a otevřít menu (screenshot);
- [ ] žádná stránka nemá vodorovný scroll celého `body` (jen uvnitř `.table-responsive`);
- [ ] hlavičky se lámou do dvou řádků, nic se nezkracuje na „…".

**Dokumentace:** `docs/frontend.md` §5 — poznámka, že tabulky patří do `table-responsive`.

---

### FÁZE U1 — Sdílené komponenty a konvence

> **R-3 rozhodnuto** (jemný pill) — fáze je celá odblokovaná.

---

#### U1.1 · Komponenta `PageHeader` (S-02, S-03, S-04)

**Proč:** Dnes existuje 5 variant nadpisu a 4 varianty hlavičky; „Zpět" je nahodilé a znamená
dvě různé věci. Jedna komponenta to uzavře pro celou aplikaci.

*Čti:* `analyza-ui-2026-07.md` §2.2 a §4.1, `src/pages/CustomersPage.jsx`,
`src/pages/CustomersPageDetail.jsx`, `src/pages/StockTakesPage.jsx`, `src/pages/CompanyProfilePage.jsx`.

**Postup:**
1. Vytvořit `src/components/PageHeader.jsx` přesně podle snippetu v `analyza-ui-2026-07.md` §4.1
   (props: `title`, `subtitle`, `badges`, `backTo`, `actions`), s JSDoc česky.
2. Nasadit **pouze na tři stránky** jako pilot: `CustomersPage`, `CustomersPageDetail`, `StockTakesPage`.
3. Ověřit, že se hlavička láme při 760 px a nadpis má všude stejnou velikost (`h1.h3`).

**Co NEDĚLAT:** neměnit zbývající stránky (to dělají U3.x/U4.x); nepřidávat drobečkovou navigaci;
neměnit texty tlačítek nad rámec „Zpět" → ikonové tlačítko.

**Akceptační kritéria:**
- [ ] `PageHeader` existuje s JSDoc a všemi pěti props;
- [ ] tři pilotní stránky ho používají a vypadají shodně (screenshoty vedle sebe);
- [ ] nadpisy mají stejnou vypočtenou `font-size` (doložit `getComputedStyle`);
- [ ] při 760 px se akce zalomí pod nadpis, nic se nezkracuje.

**Dokumentace:** `docs/frontend.md` — nová §10.1 „Hlavička stránky" se snippetem.

---

#### U1.2 · `StatusBadge` a sjednocení badge (S-14, S-15, R-3)

**Proč:** 5 vizuálních stylů badge, „aktivní/neaktivní" ve 4 zněních, `text-bg-light` je neviditelný.

*Čti:* `analyza-ui-2026-07.md` §2.7 a §4.6, `src/api/format.js`, `src/index.css:33-35`.
**R-3 = a:** jednotný styl je `bg-*-subtle text-*-emphasis rounded-pill` pro všechny badge.

**Postup:**
1. `src/components/StatusBadge.jsx` podle §4.6 (prop `tone`, `children`).
2. V `format.js` přepsat všechny badge mapy na `tone` (`success`/`warning`/`danger`/`info`/
   `secondary`/`primary`) podle sémantické tabulky v §4.6; **zrušit `text-bg-light`**
   (`ORDER_STATUS_BADGE_CLASSES.DIAGNOSIS` → `info`, `MILEAGE_SOURCE_BADGE_CLASSES.OTHER` → `secondary`).
3. Přesunout do `format.js` duplicitní mapu stavů inventury (`StockTakesPage.jsx:8-12`,
   `StockTakePageDetail.jsx:126-129`) jako `getStockTakeStatusLabel`/`…Tone`.
4. Přidat `format.js` helper `getActiveTone(active)` + jednotné texty „Aktivní" / „Neaktivní".
5. Odstranit `.badge-individual` a `.badge-company` z `index.css` a `CustomerTable.jsx:40`
   přepnout na `StatusBadge`.
6. Nasadit `StatusBadge` **všude**, kde se dnes badge renderuje (seznam v analýze §2.7) — včetně
   smazání šesti kopií lokálního `StatusBadge` v detailech.

**Co NEDĚLAT:** neměnit význam stavů ani jejich texty v `*_LABELS`; neměnit `FIELD_STATE_META`
(ikony stavů polí příjemky mají jinou roli); nepřebarvovat STK badge nad rámec převodu na `tone`.

**Akceptační kritéria:**
- [ ] grep `text-bg-` ve `src/` nevrací žádný výskyt mimo případné odůvodněné výjimky (doložit výstup);
- [ ] grep `badge-individual|badge-company` nevrací nic;
- [ ] stav zakázky „Diagnostika" je čitelný (screenshot `/orders`);
- [ ] „Aktivní"/„Neaktivní" má stejné znění i vzhled na detailech, ve skladu i u uživatelů;
- [ ] mapa stavů inventury je jen na jednom místě (`format.js`).

**Dokumentace:** `docs/frontend.md` §10.5 „Badge a stavové barvy" + tabulka sémantiky barev.

---

#### U1.3 · `EmptyState`, `LoadingState`, `ErrorState` (S-20, S-22)

**Proč:** 4 varianty prázdného stavu, 4 varianty načítání, chybový stav bez cesty ven.
`.empty-state` v CSS je přitom mrtvý.

*Čti:* `analyza-ui-2026-07.md` §2.10 a §4.6, `src/index.css:44-46`, `src/pages/ReceiptReviewPage.jsx:245-246`.

**Postup:**
1. `src/components/EmptyState.jsx` podle §4.6 (props `icon`, `title`, `hint`, `action`).
2. `src/components/LoadingState.jsx` — vycentrovaný `spinner-border` + text „Načítám…"
   (jednotně s výpustkou), prop `inline` pro použití uvnitř karty.
3. `src/components/ErrorState.jsx` — `EmptyState` s ikonou `exclamation-triangle`, textem chyby
   a povinným tlačítkem zpět (prop `backTo`, `backLabel`).
4. Nasadit `LoadingState` a `ErrorState` na **všech 11 míst** s „Načítám" a na obě stránky
   s holým chybovým alertem (`ReceiptReviewPage.jsx:246`, `StockTakePageDetail.jsx:44`).
   `EmptyState` nasadit až v U3.x (potřebuje `DataTable`).

**Co NEDĚLAT:** nepřidávat skeletony; neměnit `RequireAuth` (jeho spinner je záměrně celostránkový);
neměnit logiku načítání dat.

**Akceptační kritéria:**
- [ ] grep `Načítám` vrací už jen `LoadingState.jsx` a `Sidebar.jsx` (uživatelské jméno);
- [ ] `/warehouse/receipts/999999/review` zobrazí `ErrorState` s tlačítkem „Zpět na příjemky" (screenshot);
- [ ] `.empty-state` CSS je použité komponentou `EmptyState`.

**Dokumentace:** `docs/frontend.md` §10.6 „Prázdný, načítací a chybový stav".

---

#### U1.4 · Komponenta `Modal` (S-12, S-13)

**Proč:** 10 ručně psaných modalů, žádný nereaguje na Esc, nemá backdrop element, focus trap ani
zámek scrollu; liší se křížkem i pořadím tlačítek.

*Čti:* `analyza-ui-2026-07.md` §2.6 a §4.5, `src/components/ConfirmDialog.jsx`,
`src/components/GoodsReceiptImportModal.jsx`.

**Postup:**
1. `src/components/Modal.jsx` přesně podle snippetu §4.5: `modal-backdrop` element,
   `modal-dialog-centered`, `aria-modal`, Esc, klik na pozadí, `body.modal-open`.
2. Doplnit **focus trap**: při otevření zaostřit první fokusovatelný prvek dialogu, `Tab`/`Shift+Tab`
   cyklovat uvnitř, při zavření vrátit fokus na spouštěč.
3. Prop `closable` (default `true`) — při `saving` se předává `false`, aby se dialog nezavřel
   uprostřed požadavku.
4. **Přepsat na ni jen `ConfirmDialog`** (zůstane stejné API: `title`, `message`, `show`,
   `onConfirm`, `onCancel`, `yesLabel`, `noLabel`) — ostatní modaly řeší U6.1.
5. Ověřit na `/customers` → menu řádku → Deaktivovat.

**Co NEDĚLAT:** nepoužívat Bootstrap JS API (`bootstrap.Modal`) — projekt ho pro modaly záměrně
nepoužívá; neměnit texty dialogů; nepřepisovat ostatní modaly.

**Akceptační kritéria:**
- [ ] Esc zavře `ConfirmDialog`; klik na pozadí zavře; křížek je přítomen;
- [ ] `document.body` má při otevřeném modalu zámek scrollu (doložit `getComputedStyle`);
- [ ] existuje element `.modal-backdrop` (doložit selektorem);
- [ ] `Tab` necyklí mimo dialog; po zavření je fokus zpět na spouštěči;
- [ ] všech 7 tabulek s `ConfirmDialog` funguje beze změny chování.

**Dokumentace:** `docs/frontend.md` §10.4 „Modaly" + aktualizace §5 (dnes popisuje ruční `.modal show d-block`).

---

#### U1.5 · `FormSection`, `FormActions`, `RequiredMark` + CSS validace (S-10)

**Proč:** Dva vzory formulářů, tři různé popisky tlačítka uložení, povinná pole označená jen někde,
a `was-validated` maluje zelené fajfky do prázdných nepovinných polí.

*Čti:* `analyza-ui-2026-07.md` §2.5 a §4.4, `src/components/CustomerForm.jsx`,
`src/pages/CompanyProfilePage.jsx`, `src/index.css`.

**Postup:**
1. `src/components/FormSection.jsx` = `section.card.border-0.shadow-sm.mb-3` + `h2.h6.text-uppercase.text-muted`.
2. `src/components/FormActions.jsx` = `d-flex justify-content-end gap-2 border-top pt-3`,
   props `onCancel`, `saving`, `submitLabel` (default „Uložit"), `cancelLabel` (default „Zrušit").
3. `src/components/RequiredMark.jsx` = `<span className="text-danger" aria-hidden="true">*</span>`.
4. Do `index.css` přidat blok potlačující zelenou validační zpětnou vazbu (snippet v §4.4).
5. Nasadit **pouze na `CompanyProfilePage`** jako pilot (je nejblíž cílovému vzoru) — zbytek řeší U5.1.

**Co NEDĚLAT:** nepřepisovat zatím ostatní formuláře; neměnit validační pravidla ani `required`
atributy; neodstraňovat `needs-validation`.

**Akceptační kritéria:**
- [ ] po odeslání neplatného formuláře nemá žádné prázdné nepovinné pole zelený rámeček ani ikonu
      (screenshot `/customers/new` po kliknutí na Uložit — chování se projeví globálně díky CSS);
- [ ] chybná pole mají červený rámeček a `invalid-feedback` beze změny;
- [ ] `CompanyProfilePage` používá `FormSection` + `FormActions` a má tlačítka „Zrušit" i „Uložit".

**Dokumentace:** `docs/frontend.md` §10.3 „Formulář" se snippetem.

---

#### U1.6 · Filtrové komponenty (S-06, S-07)

**Proč:** `InputFilter` má hardcodované `id`, `CheckBox` je hardcodovaný text „Jen aktivní"
s nesmyslným `col-4`, dva vizuálně stejné checkboxy mají dvě implementace, labely jsou jen někde.

*Čti:* `analyza-ui-2026-07.md` §2.4 a §4.2, `src/components/InputFilter.jsx`, `src/components/CheckBox.jsx`.

**Postup:**
1. `src/components/filters/SearchFilter.jsx` — props `id`, `label`, `placeholder`, `value`, `onChange`;
   viditelný `<label className="form-label">`, `input-group` s lupou.
2. `src/components/filters/SelectFilter.jsx` — props `id`, `label`, `value`, `onChange`, `options`,
   `emptyLabel` (např. „Všechny stavy").
3. `src/components/filters/ToggleFilter.jsx` — props `id`, `label`, `checked`, `onChange`.
4. `src/components/filters/ListToolbar.jsx` — `row g-2 align-items-end mb-3` obal, který se láme.
5. Sjednotit tvar placeholderů podle §2.4: velké počáteční písmeno, výčet oddělený čárkami,
   poslední „nebo".
6. `InputFilter.jsx` a `CheckBox.jsx` **ponechat zatím beze změny** — smažou se v U3.2/U3.3,
   až je nikdo nebude importovat.

**Co NEDĚLAT:** neměnit debounce ani logiku načítání; nepřidávat nové filtry; nesahat na
`AutocompletePair`.

**Akceptační kritéria:**
- [ ] čtyři komponenty existují s JSDoc;
- [ ] `id` se předává zvenčí (žádné hardcodované `filter-search` / `onlyActiveCheck`);
- [ ] pilotní nasazení na `InvoicesPage` vypadá shodně s dnešním, jen má label i nad hledáním (screenshot).

**Dokumentace:** `docs/frontend.md` §10.2 „Seznamová stránka".

---

#### U1.7 · Zápis „UI konvencí" do `docs/frontend.md`

**Proč:** Bez zapsaného pravidla se varianty vrátí. Konvence musí být v dokumentaci dřív, než se
začnou plošně aplikovat.

*Čti:* `analyza-ui-2026-07.md` §4 celá, `docs/frontend.md`, `docs/konvence.md` §17.

**Postup:**
1. Do `docs/frontend.md` přidat kapitolu **§10 UI konvence** s podkapitolami 10.1–10.8
   (hlavička, seznam, formulář, modal, badge, prázdný/chybový stav, formátování hodnot
   a **10.8 barevná sémantika tlačítek**), každou se snippetem podle §4 analýzy;
   do 10.8 přenést celou tabulku a pět pravidel z **§7.1 analýzy**.
2. Aktualizovat `docs/frontend.md` §5 (Vzory komponent) — odkázat na §10 místo popisu dnešního stavu.
3. Do `docs/konvence.md` §17 přidat tři odrážky: jeden vzor hlavičky/tabulky/formuláře,
   badge jen přes `StatusBadge`, modaly jen přes `Modal`.
4. Do `docs/tech-dluhy.md` zapsat nálezy, které tento plán **neřeší** (Dashboard, a11y audit,
   případně MUI→Bootstrap dle R-1).

**Co NEDĚLAT:** neměnit kód; nepsat do `docs/archiv/`.

**Akceptační kritéria:**
- [ ] `docs/frontend.md` §10 obsahuje všech 8 podkapitol se snippety (včetně 10.8 barvy tlačítek);
- [ ] `docs/konvence.md` §17 rozšířeno;
- [ ] `docs/tech-dluhy.md` obsahuje nové položky s odkazem na `analyza-ui-2026-07.md`.

---

### FÁZE U2 — Navigace

> **R-2 rozhodnuto** (od 768 px, offcanvas pod 992 px). Chování skupin: varianta **A** —
> accordion s perzistencí v `localStorage` a auto-rozbalením aktivní skupiny (§5.2 analýzy).

---

#### U2.1 · Přestavba struktury menu (S-23, zadání uživatele)

**Proč:** Odsazení `ps-5` předstírá hierarchii, kterou routy nemají; „Nastavení firmy" má být
na hlavní úrovni jako profil.

*Čti:* `analyza-ui-2026-07.md` §5.1, `src/components/Sidebar.jsx`, `src/App.jsx`, `docs/frontend.md` §3.

**Postup:**
1. Přepsat `Sidebar.jsx` na datovou strukturu (pole položek a skupin) místo ručně psaného seznamu:
   `[{type:'item'|'group', label, icon, to, children, adminOnly}]`.
2. Struktura podle §5.1: blok Provoz (Dashboard, Zákazníci, Vozidla, Zakázky, Faktury),
   skupina **Sklad** (Přehled skladu, Příjemky, Pod minimem, Inventury, Dodavatelé),
   spodní blok (Nastavení firmy, Uživatelé, Nápověda) oddělený `border-top`.
3. Zrušit **veškeré `ps-5`**; odsazení dětí skupiny řešit jedinou CSS třídou (`.nav-sub`).
4. V `App.jsx` přidat routu `/settings/company` → `CompanyProfilePage` a ponechat
   `/invoices/settings` jako `<Navigate to="/settings/company" replace />`.
5. Opravit `Sidebar.jsx:108` — `logout` dostane `preventDefault` (S-26).

**Co NEDĚLAT:** nezavádět zatím rozbalování (U2.2) — skupina je v tomto úkolu trvale rozbalená;
neměnit vzhled aktivní položky; nemazat starou routu.

**Akceptační kritéria:**
- [ ] menu odpovídá struktuře §5.1 (screenshot);
- [ ] „Nastavení firmy" je ve spodním bloku, `/settings/company` funguje a `/invoices/settings` na ni přesměruje;
- [ ] „Přehled skladu" vede na `/warehouse`, hlavička „Sklad" už není odkaz;
- [ ] grep `ps-5` v `src/` nevrací nic;
- [ ] odhlášení nezanechá `#` v URL.

**Dokumentace:** `docs/frontend.md` §3 (tabulka rout) + `docs/api.md`, pokud se v něm cesta uvádí.

---

#### U2.2 · Rozbalovací skupiny a aktivní stav (S-24, S-25)

**Proč:** Dvě položky svítí jako aktivní zároveň; sidebar nejde zkrátit.

*Čti:* `analyza-ui-2026-07.md` §5.2 a §5.3, `src/components/Sidebar.jsx` (po U2.1).

**Postup:**
1. Hlavička skupiny = `<button>` s `aria-expanded`, ikonou a šipkou `bi-chevron-down`/`bi-chevron-right`.
2. Stav rozbalení v `useState`, inicializovaný z `localStorage` (klíč `sidebar.groups`), ukládaný
   při každé změně. Výchozí: rozbaleno.
3. **Auto-rozbalení má přednost:** je-li aktivní některá položka skupiny, skupina je rozbalená
   bez ohledu na uložený stav.
4. Doplnit `end` na `NavLink` u položek, jejichž cesta je prefixem jiné (`/warehouse`) —
   ověřit, že na `/warehouse/1/detail` zůstane aktivní „Přehled skladu".
5. Sbalená skupina s aktivním dítětem: hlavička dostane `.nav-group-active` (bílý text + levý pruh),
   ne plné modré pozadí.

**Co NEDĚLAT:** nezavádět auto-accordion (sbalování ostatních skupin); nepřidávat mini/ikonový režim;
neukládat do `localStorage` nic jiného než stav skupin.

**Akceptační kritéria:**
- [ ] na `/warehouse/receipts` je aktivní **jen** „Příjemky" (screenshot);
- [ ] na `/settings/company` je aktivní jen „Nastavení firmy";
- [ ] na `/suppliers` je skupina Sklad rozbalená a „Dodavatelé" aktivní;
- [ ] sbalení skupiny přežije refresh stránky;
- [ ] hlavička skupiny je ovladatelná klávesnicí (Enter/Space) a hlásí `aria-expanded`.

**Dokumentace:** `docs/frontend.md` §3 — odstavec o chování sidebaru.

---

#### U2.3 · Offcanvas sidebar pod 992 px (S-01, R-2)

**Proč:** Sidebar ukusuje 240 px z každé šířky; pod 992 px zbývá na obsah příliš málo.

*Čti:* `analyza-ui-2026-07.md` §5.4, `src/components/Layout.jsx`, `src/index.css:4-25`.

**Postup:**
1. V `Layout.jsx` přidat stav `sidebarOpen` a v obsahu hlavičku viditelnou jen pod 992 px
   (`d-lg-none`) s tlačítkem ☰ (`bi-list`) a názvem aplikace.
2. CSS: `≥992px` beze změny; `<992px` sidebar `position: fixed; transform: translateX(-100%)`,
   při otevření `translateX(0)` + poloprůhledný podklad přes obsah.
3. Zavírat klikem na položku menu, na podklad a klávesou Esc; při otevření zamknout scroll body.
4. Ověřit při 1440, 1200, 991, 768 a 576 px na seznamu i detailu.

**Co NEDĚLAT:** neměnit strukturu menu (U2.1/U2.2); nepřidávat mini/ikonový režim; neřešit
kartové zobrazení tabulek (mimo rozsah dle R-2).

**Akceptační kritéria:**
- [ ] při ≥992 px je sidebar staticky a chování je beze změny;
- [ ] při <992 px je sidebar skrytý, ☰ ho otevře, klik na položku i Esc ho zavře (screenshoty);
- [ ] obsah při 768 px využívá plnou šířku okna;
- [ ] žádná stránka nemá vodorovný scroll `body`.

**Dokumentace:** `docs/frontend.md` §10.7 „Responsivita" — breakpointy a pravidla.

---

### FÁZE U3 — Seznamy

---

#### U3.1 · Sdílená `DataTable` (S-05, S-28, R-5)

**Proč:** Čtyři způsoby řádkových akcí, `cursor:pointer` na neklikatelných řádcích, chybějící
`scope`/`align-middle`/`table-responsive` a mrtvý stav řazení. **Nejrizikovější úkol plánu** —
dotkne se všech seznamů.

*Čti:* `analyza-ui-2026-07.md` §2.3 a §4.2 (**R-5 = a**: řazení klikem na hlavičku se doplňuje);
`src/components/CustomerTable.jsx`,
`src/components/ProductTable.jsx`, `src/components/InvoiceTable.jsx`, `src/hooks/useRowActions.js`.

**Postup:**
1. `src/components/DataTable.jsx` — props:
   `columns` (`{key, header, align, sortable, render(row)}`), `rows`, `rowKey`,
   `rowActions(row)` (pole akcí pro `TableRowActionMenu`), `onRowClick` (volitelné),
   `emptyState` (uzel), `sort`/`onSortChange` (dle R-5), `dense`.
2. Vždy renderovat: `div.table-responsive > table.table.table-hover.align-middle`,
   `th scope="col"`, sloupec akcí `text-end` s hlavičkou „Akce".
3. `cursor: pointer` nastavit **jen** když je `onRowClick` — a z `index.css:28` odstranit
   globální pravidlo `.table-hover tbody tr { cursor: pointer }`.
4. Řazení (R-5 = a): hlavička sloupce se `sortable` je `<button>` s šipkou a `aria-sort`;
   kliknutí přepíná `sortBy`/`sortDesc`, které stránka posílá do API (dnes jsou zamrzlé — S-28).
5. Nasadit **pouze na `CustomerTable`** jako pilot; ostatní tabulky v U3.2–U3.4.

**Co NEDĚLAT:** neměnit `useRowActions` ani texty potvrzovacích dialogů; nepřevádět zbylé tabulky;
nezavádět virtualizaci ani výběr řádků.

**Akceptační kritéria:**
- [ ] `DataTable` má JSDoc a všechny uvedené props;
- [ ] `/customers` funguje beze změny chování (detail, editace, deaktivace) — screenshot + průchod;
- [ ] kurzor nad řádkem zákazníků je výchozí (řádek není klikatelný), nad klikatelným řádkem `pointer`;
- [ ] tabulka je vodorovně scrollovatelná při 760 px;
- [ ] kliknutí na hlavičku „Jméno" změní řazení a odešle `sortBy`/`sortDesc` — doložit
      záznamem síťového požadavku; opakovaný klik otočí směr a `aria-sort` to hlásí.

**Dokumentace:** `docs/frontend.md` §10.2 — doplnit `DataTable`.

> **Zastaveno 21. 7. 2026 podle §1 pravidla 9.** FE část hotová a ověřená (sloupce, akce, prázdný
> stav, `aria-sort`, odesílání `sortBy`/`sortDesc`). **Backend ale řazení nepodporuje** —
> `sortDesc` není v žádném mapperu a `sortBy` respektují jen zákazníci a uživatelé; viz
> **TD-46** v `tech-dluhy.md`. Rozhodnutí R-5 tím pádem nejde dodat bez zásahu do SQL.
> Do rozhodnutí uživatele zůstávají hlavičky seřaditelné jen u zákazníků (kde `sortBy` funguje),
> ale **druhý klik nezmění směr** — což je matoucí, proto se čeká na volbu, jestli doplnit backend,
> nebo řazení z UI stáhnout.

---

#### U3.2 · Převod seznamů: zákazníci, vozidla, zakázky, dodavatelé, uživatelé

**Proč:** Sjednotit hlavičku, filtry, tabulku, prázdný stav a texty na pěti seznamech.

*Čti:* `analyza-ui-2026-07.md` §2.2–§2.4, §2.11; `docs/frontend.md` §10 (po U1.7).

**Postup:** Pro každou z pěti stránek (`CustomersPage`, `VehiclesPage`, `OrdersPage`,
`SuppliersPage`, `UsersPage`) a jejich tabulky:
1. Hlavička → `PageHeader` s akcí vytvoření; sjednotit texty tlačítek na tvar **„Nový X"**
   („Nový zákazník", „Nové vozidlo", „Nová zakázka", „Nový uživatel").
2. Filtry → `ListToolbar` + `SearchFilter`/`ToggleFilter`; sjednotit placeholdery dle §2.4.
3. Tabulka → `DataTable` s `emptyState` (`EmptyState`) a `rowActions` z existujících hooků.
4. Sloupec „Stav" (Aktivní/Neaktivní) přidat tam, kde chybí, přes `StatusBadge`.
5. Opravit texty: „Status" → „Stav", „e-mail" → „Email", role přes nový label z `format.js` (S-16).
6. Smazat `InputFilter.jsx` a `CheckBox.jsx`, jakmile je nikdo neimportuje.

**Co NEDĚLAT:** neměnit API volání, debounce ani stránkování; nepřidávat nové filtry;
neměnit `OrdersPage` nápovědný text pod hledáním (jen ho přesunout do `SearchFilter` jako `hint`).

**Akceptační kritéria:**
- [ ] pět seznamů má shodnou hlavičku, lištu filtrů i tabulku (screenshoty všech pěti);
- [ ] prázdný výsledek filtru zobrazí `EmptyState` (ověřit hledáním nesmyslného řetězce);
- [ ] grep `InputFilter|CheckBox\.jsx` nevrací nic a soubory jsou smazané;
- [ ] grep `>Status<` nevrací nic;
- [ ] role uživatelů se zobrazují česky, ne jako `ROLE_ADMIN`;
- [ ] chování akcí (detail/editace/deaktivace/reset hesla) je beze změny.

**Dokumentace:** `docs/frontend.md` §5 — aktualizovat popis seznamových stránek.

---

#### FÁZE U3R — Dokončení řazení (provést PŘED U3.3 a U3.4)

> **Proč vznikla.** Úkol U3.1 měl podle rozhodnutí R-5 dodat řazení seznamů. Backend ho ale
> vůbec nepodporoval (TD-46), a oprava, která na to navázala, zůstala **na půl cesty**: whitelist
> dostalo 6 z 8 stránkovaných seznamů, u zakázek jsem chybějící podporu prohlásil za vlastnost
> („sortable se nenastavuje") místo abych ji doplnil, a `<otherwise>` větve se chovají nejednotně —
> někde respektují směr, jinde ne. Tahle fáze to dotahuje do konce.
>
> **Zjištěný stav k 22. 7. 2026** (úplná inventura, ne vzorek):
>
> *Stránkované seznamy — řazení musí být na serveru:*
>
> | Seznam | Endpoint | Whitelist | Stav |
> |---|---|---|---|
> | Zákazníci | `/customers` | lastName, companyName, customerNumber, primaryEmail | ✅ |
> | Vozidla | `/vehicles` | vin, licensePlate, brand, yearOfManufacture, stkValidUntil | ✅ |
> | Uživatelé | `/users` | username, email, lastLoginAt | ✅ |
> | Faktury | `/invoices` | invoiceNumber, customerName, issueDate, dueDate, totalGross | ✅ |
> | Sklad | `/warehouse/products` | sku, quantityOnHand, salePrice | ✅ |
> | Dodavatelé | `/warehouse/suppliers` | registrationNumber, city | ✅ |
> | **Zakázky** | `/orders` | — | ❌ `OrderMapper.xml:154` pevné `ORDER BY` |
> | **Příjemky** | `/warehouse/receipts` | — | ❌ `ReceiptReviewMapper.xml:91` pevné `ORDER BY` |
>
> *Nestránkované seznamy — vrací celé pole, řazení patří na klienta:*
> Inventury (`StockTakeMapper.xml:45`), Pod minimem (`WarehouseMapper.xml:228`).
>
> *Vnořené tabulky na detailech* (šarže, pohyby, tachometr, vozidla zákazníka) — malé seznamy,
> klientské řazení volitelné. **Položky zakázky, faktury a řádky draftu příjemky se řadit
> NESMĚJÍ** — jejich pořadí je ruční (`position`, u zakázky drag-and-drop) a řazení by ho skrylo.

---

#### U3R.1 · BE: pojmenovaný default místo `<otherwise>`

**Proč:** dnes je výchozí řazení schované v `<otherwise>` jako pevný fragment. Důsledek: u zákazníků,
uživatelů, vozidel a faktur **výchozí řazení ignoruje `sortDesc`**, zatímco u skladu a dodavatelů
ho respektuje — dvojí chování téhož parametru. Uživatelův postřeh: pevné `ORDER BY` je vlastně
jen *výchozí hodnota* `sortBy`, ne výjimka z pravidla.

*Čti:* `docs/api.md` (§ Řazení), `src/main/resources/mapper/CustomerMapper.xml`,
`UserMapper.xml` (vzor `<sql id="userSortOrder">`), `src/main/java/…/pagination/BaseParams.java`.

**Postup:**
1. V každém mapperu se seznamem vytáhnout řazení do vlastního `<sql id="xxxSortOrder">`
   (vzor už je v `UserMapper.xml`) — jedna definice, i když ji dotaz používá dvakrát.
2. Výchozí pořadí přestat psát do `<otherwise>` jako pevný fragment; místo toho ho pojmenovat
   jako běžný klíč whitelistu (např. `createdAt`, `name`, `issueDate`) **včetně `<if sortDesc>`**.
   `<otherwise>` pak jen deleguje na tentýž klíč, aby neznámá hodnota spadla na definované chování.
3. Do každé `*SearchParams` nastavit `sortBy` na výchozí klíč daného seznamu, ať je default
   viditelný v Javě a ne zahrabaný v SQL.
4. Sekundární klíč řazení (tie-breaker, typicky `id`) ponechat **bez** směru — jde jen o stabilitu
   stránkování, ne o uživatelskou volbu.

**Co NEDĚLAT:** neměnit, co je výchozí řazení jednotlivých seznamů (faktury zůstávají
nejnovější první); nezavádět `${}` interpolaci; nesahat na `countSearch` (ORDER BY tam nepatří).

**Akceptační kritéria:**
- [ ] žádný mapper nemá `<otherwise>` s pevným směrem, který ignoruje `sortDesc`;
- [ ] výchozí řazení každého seznamu jde obrátit přes `sortDesc=true` (doložit testem);
- [ ] výchozí pořadí bez parametrů je stejné jako dnes (regresní test na prvních 3 řádcích).

**Dokumentace:** `docs/api.md` — u každého endpointu uvést i **výchozí** klíč řazení.

---

#### U3R.2 · BE: whitelist řazení pro zakázky a příjemky

**Proč:** jediné dva stránkované seznamy bez řazení. U zakázek je to nejnápadnější — je to
nejpoužívanější seznam aplikace.

*Čti:* `src/main/resources/mapper/OrderMapper.xml` (`search`, sdílený `WhereClause`),
`src/main/resources/mapper/warehouse/ReceiptReviewMapper.xml` (`search`, `searchWhere`).

**Postup:**
1. **Zakázky** (`OrderMapper.xml:145-156`) — whitelist: `orderNumber`, `status`,
   `customerName` (řadí přes `COALESCE(c.company_name, c.last_name)` — zobrazený `displayName`
   se skládá z obou sloupců), `licensePlate` (`v.license_plate NULLS LAST`), `brand`,
   `estimatedCompletionAt` (`NULLS LAST`), `createdAt` = **výchozí**.
   JOINy na `customer` a `vehicle` už v dotazu jsou, není potřeba nic přidávat.
2. **Příjemky** (`ReceiptReviewMapper.xml:87-93`) — whitelist: `documentNumber`
   (`gr.invoice_number`), `supplierName` (`gr.supplier_name_snapshot`), `issueDate` (`NULLS LAST`),
   `totalAmount`, `status`, `createdAt` = **výchozí**.
3. Obojí přes `<sql id="…SortOrder">` podle vzoru z U3R.1.

**Co NEDĚLAT:** neřadit podle sloupce „Kontroly" (odvozený příznak `reconciliationOk`, není v DB
jako sloupec); neměnit `WhereClause`/`searchWhere`; nesahat na `findAllActive`.

**Akceptační kritéria:**
- [ ] `/orders?sortBy=orderNumber&sortDesc=true` vrátí opačné pořadí než `sortDesc=false`;
- [ ] totéž pro `customerName` a `estimatedCompletionAt` (prázdné termíny zůstanou na konci);
- [ ] `/warehouse/receipts?sortBy=totalAmount` řadí podle částky, ne podle data;
- [ ] výchozí pořadí obou seznamů beze změny.

**Dokumentace:** `docs/api.md` — doplnit oba endpointy do seznamu klíčů řazení.

---

#### U3R.3 · FE: doplnit seřaditelné sloupce na hotových seznamech

**Proč:** i tam, kde backend řadit umí, FE nabízí jen část sloupců — u zákazníků chybí Email
(backend `primaryEmail` má), u dodavatelů Název (výchozí klíč), u vozidel Model. Uživatel neví,
proč jde kliknout na jednu hlavičku a na sousední ne.

*Čti:* `docs/api.md` (§ Řazení — zdroj pravdy, co backend umí), `src/components/DataTable.jsx`,
tabulky převedené v U3.2.

**Postup:**
1. Projít **každý** sloupec každého převedeného seznamu a nastavit `sortable` všude, kde
   odpovídající klíč existuje ve whitelistu (`sortKey` použít tam, kde se jméno sloupce
   v UI liší od klíče API).
2. Sloupce, které řadit nelze, ponechat bez `sortable` a **napsat proč** do komentáře
   u definice sloupce (typicky: hodnota se skládá na klientovi, nebo není v DB).
3. Zakázky a příjemky napojit na nové whitelisty z U3R.2 (do té doby zůstávají bez řazení).

**Co NEDĚLAT:** nepřidávat `sortable` na sloupec, který backend neumí — klikatelná hlavička,
která nic neudělá, je horší než neklikatelná.

**Akceptační kritéria:**
- [ ] pro každý seznam sedí množina seřaditelných sloupců s whitelistem v `api.md` (projít oboje);
- [ ] u každého neseřaditelného sloupce je v kódu důvod;
- [ ] klik na libovolnou seřaditelnou hlavičku mění pořadí (ověřit v prohlížeči, ne jen požadavek).

---

#### U3R.4 · FE: klientské řazení pro nestránkované seznamy

**Proč:** Inventury a Pod minimem vracejí celé pole, takže posílat řazení na server by byla
zbytečná režie — ale bez řazení zůstanou jediné dvě stránky, kde hlavičky nefungují.

*Čti:* `src/pages/StockTakesPage.jsx`, `src/pages/LowStockPage.jsx`, `src/components/DataTable.jsx`.

**Postup:**
1. Do `DataTable` přidat režim **`clientSort`**: když je zapnutý, komponenta si řadí `rows` sama
   (podle `column.sortValue(row)`, default `column.render` hodnota) a `onSortChange` neposílá ven.
2. Řadit stabilně a s rozumným porovnáním: čísla numericky, datumy jako datumy, texty přes
   `localeCompare('cs')` — jinak by „Šimánek" skončil za „Zeman".
3. Prázdné hodnoty vždy na konec bez ohledu na směr (stejně jako `NULLS LAST` na serveru).
4. Nasadit na Inventury a Pod minimem (po jejich převodu na `DataTable` v U3.3).

**Co NEDĚLAT:** nezavádět klientské řazení na stránkovaných seznamech — seřadilo by jen aktuální
stránku a tvářilo se, že seřadilo vše.

**Akceptační kritéria:**
- [ ] Inventury i Pod minimem jdou řadit klikem, oba směry, bez síťového požadavku (doložit);
- [ ] české řazení: „Č" před „D", „Š" mezi „S" a „T";
- [ ] prázdné hodnoty jsou na konci v obou směrech.

---

#### U3R.5 · Testy řazení pro všechny seznamy + dokumentace

**Proč:** `ListSortingTest` pokrývá 5 seznamů; po U3R.1–U3R.2 jich je 8 a přibyl pojmenovaný default.
Bez testu na každý whitelist se stane přesně to, co u TD-46 — parametr se tiše přestane používat.

*Čti:* `src/test/java/…/service/ListSortingTest.java`, `docs/api.md`.

**Postup:**
1. Rozšířit `ListSortingTest` o zakázky a příjemky a o **výchozí řazení** každého seznamu
   (že bez parametrů vrátí očekávané pořadí a že `sortDesc=true` ho obrátí).
2. Přidat test, že **neznámý `sortBy` nespadne** a chová se jako výchozí — u všech osmi.
3. Doplnit `docs/api.md` o kompletní tabulku klíčů včetně výchozích a `docs/frontend.md` §10.2
   o pravidlo „seřaditelné je jen to, co je v `api.md`".

**Co NEDĚLAT:** netestovat konkrétní seed data hodnotami natvrdo (křehké) — ověřovat
uspořádanost a obrácení pořadí.

**Akceptační kritéria:**
- [ ] test pro každý z 8 stránkovaných seznamů, oba směry + výchozí + neznámý klíč;
- [ ] celá suite zelená;
- [ ] `api.md` a `frontend.md` odpovídají skutečnosti (projít proti kódu).

---

### U3.3 · Převod seznamů skladu (sklad, příjemky, pod minimem, inventury)

**Proč:** Tytéž vzory pro skladové seznamy; `ReceiptsPage`, `LowStockPage` a `StockTakesPage` mají
tabulku psanou přímo ve stránce a řádkové akce klikem na řádek.

*Čti:* jako U3.2 + `src/pages/ReceiptsPage.jsx`, `src/pages/LowStockPage.jsx`,
`src/pages/StockTakesPage.jsx`, `src/pages/WarehousePage.jsx`.

**Postup:**
1. `WarehousePage`: `PageHeader` s akcemi „Import dokladu (PDF)" (outline) a **„Nová skladová
   položka"** (primary — sjednotit s „Přidat nový záznam"); karta hodnoty zásob zůstává.
2. `ReceiptsPage`: `PageHeader` s „Import dokladu (PDF)" + „Nová příjemka ručně"; filtry přes
   `SelectFilter` **s labely**; tabulku vytáhnout do `ReceiptTable.jsx` nad `DataTable`
   s `onRowClick` (řádek zde klikatelný zůstává, protože nemá jinou akci).
3. `LowStockPage` a `StockTakesPage`: `PageHeader` (`backTo` u „Pod minimem" zrušit — je to
   položka menu, ne podstránka), `DataTable`, `EmptyState`.
4. Sjednotit texty importních tlačítek na **„Import dokladu (PDF)"** na obou místech.

**Co NEDĚLAT:** neměnit workflow příjemek ani `ReceiptReviewPage` (jiný úkol); neměnit
`GoodsReceiptImportModal` (U6.1); nesahat na výpočet hodnoty zásob.

**Akceptační kritéria:**
- [x] čtyři skladové seznamy mají shodnou hlavičku i tabulku jako U3.2;
- [x] na `/warehouse` při 1200 px jsou obě tlačítka plně čitelná;
- [x] filtry na `/warehouse/receipts` mají viditelné labely;
- [x] import PDF i ruční založení příjemky fungují beze změny (tlačítka i routy prošly);
- [x] prázdné inventury i prázdný „pod minimem" používají `EmptyState`.

**Dokumentace:** `docs/frontend.md` §3 tabulka rout (pokud se změní názvy stránek).

**Odchylky:**
- `WarehousePage`: sloupce **Hodnota** a **Stav** nejsou seřaditelné — obojí je dopočítané
  v renderu (hodnota = množství × cena, stav = porovnání s minimem), server pro ně whitelist
  nemá a klientské řazení jen aktuální stránky by řadilo jen 10 viditelných řádků. Důvod je
  napsaný u sloupců v kódu.
- `ReceiptTable`: sloupec **Kontroly** neseřaditelný — `reconciliationOk` je odvozený příznak
  z kontrol draftu, ne sloupec v tabulce.
- Ověření klientského řazení: inventury mají v ostrých datech 2 identické řádky a „Pod minimem"
  je prázdné, takže na živých datech nešlo pořadí pozorovat. Ověřeno proto přes reálnou
  komponentu se **stubnutou odpovědí API** (bez zásahu do dat i do kódu): české řazení
  (`Auto` < `Čedok`), čísla numericky, prázdné hodnoty poslední v obou směrech, a **nula
  síťových požadavků** při klikání na hlavičku.

---

#### U3.4 · Seznam faktur — stavové akce do menu (S-05)

**Proč:** `InvoiceTable` je jediná tabulka s `btn-group` textových tlačítek; při užším okně se
nevejde a vybočuje ze vzoru.

*Čti:* `analyza-ui-2026-07.md` §2.3, `src/components/InvoiceTable.jsx`, `src/pages/InvoicesPage.jsx`,
`docs/funkce/` (fakturace — ověřit, že se nemění workflow).

**Postup:**
1. `InvoicesPage` → `PageHeader` + `ListToolbar` (`SearchFilter` + `SelectFilter` „Stav").
2. `InvoiceTable` → `DataTable`; akce podle stavu do `rowActions`:
   DRAFT → Detail, Vystavit, Stornovat; ISSUED → Detail, Označit zaplaceno, Stornovat;
   PAID/CANCELLED → Detail. Destruktivní akce `color: "error.main"` jako jinde.
3. Potvrzení ponechat přes `ConfirmDialog` se stávajícími texty.

**Co NEDĚLAT:** neměnit stavový automat ani endpointy; neměnit texty potvrzení;
nepřidávat hromadné akce.

**Akceptační kritéria:**
- [x] stavy nabízejí správné akce — ověřeno na ostrých datech: ISSUED → Detail / Označit
      zaplaceno / Stornovat, PAID → jen Detail;
- [x] storno otevře `ConfirmDialog` se správným textem a bez potvrzení neodešle žádný zápis;
- [x] tabulka se vejde bez vodorovného scrollu při 1200 px (na mobilu scrolluje uvnitř
      `.table-responsive`, stránka ne).

**Dokumentace:** `docs/frontend.md` §5 — aktualizovat větu o `InvoiceTable` (dnes popisuje `btn-group`).

**Odchylka:** stavy DRAFT a CANCELLED v ostrých datech nejsou, takže jejich menu se ověřilo jen
proti kódu `rowActions`, ne klikem. Samotné přechody (Vystavit / Označit zaplaceno / Stornovat)
**nebyly spuštěny** — jsou nevratné a měnily by ostrá data; ověřen byl dialog až po krok „Ano".

---

### FÁZE U4 — Detaily

---

#### U4.1 · `MetricCard`, `MetricRow`, `DetailCard`

**Proč:** `MetricCard` je zkopírovaný 4×, sekce detailů se opakují ručně, tabulka vozidel na detailu
zákazníka nemá nadpis ani kartu.

*Čti:* `analyza-ui-2026-07.md` §4.3, `src/pages/CustomersPageDetail.jsx:287-300`,
`src/pages/WarehousePageDetail.jsx:244-257`.

**Postup:**
1. `src/components/MetricCard.jsx` (props `label`, `value`, `unit`, `tone`) — sjednotit variantu
   z `WarehousePageDetail` (má navíc `danger`) jako výchozí, `tone` místo boolean `danger`.
2. `src/components/MetricRow.jsx` = `div.row.g-2.mb-4`.
3. `src/components/DetailCard.jsx` = `section.card.border-0.shadow-sm.mb-3` + `h2.h6.text-uppercase.text-muted`,
   prop `title`, `action` (tlačítko vpravo v hlavičce karty — používá to detail vozidla a skladu).
4. Nasadit na všech 6 detailů; smazat 4 lokální kopie `MetricCard`.
5. Tabulku vozidel na detailu zákazníka obalit `DetailCard title="Vozidla zákazníka"`.

**Co NEDĚLAT:** neměnit obsah ani pořadí sekcí; nepřevádět zatím hlavičky detailů (U4.2);
neměnit `dl`/`dt`/`dd` strukturu.

**Akceptační kritéria:**
- [x] grep `function MetricCard` vrací jediný výskyt (`MetricCard.jsx`);
- [x] šest detailů se vykreslí bez chyby v konzoli, karty mají shodné odsazení
      (naměřeno `margin-bottom: 16px` na všech, dřív se míchalo `mb-3` a `mb-4`);
- [x] tabulka vozidel na detailu zákazníka má nadpis a kartu, řadí se klientsky
      a prázdnému zákazníkovi ukáže `EmptyState`;
- [x] „nízká zásoba" na detailu skladu je stále červená (`tone="danger"`).

**Odchylky:**
- Nadpisy sekcí `h3` → `h2`. Stránka má jediné `h1` v `PageHeader`, takže `h3` přeskakoval
  úroveň. Vizuálně beze změny (nese to třída `h6`).
- Karta bez nadpisu je legitimní stav (`title` je volitelný) — používá ji karta součtů faktury.
- Ověření červené hodnoty: žádný díl teď není pod minimem, takže `tone="danger"` bylo ověřeno
  přes stubnutou odpověď API (`lowStock: true` → `text-danger`, `rgb(220,53,69)`; kontrola bez
  stubu → výchozí barva).

**Dokumentace:** `docs/frontend.md` §10 — doplnit `DetailCard`/`MetricRow`.

---

#### U4.2 · Sjednocení hlaviček a akcí na detailech (S-03, S-04)

**Proč:** „Zpět" má jen polovina detailů, čtyři tlačítka skladu jsou všechna šedá, faktura má akce
v jiném pořadí i barvě.

*Čti:* `analyza-ui-2026-07.md` §2.2, §4.1, §4.3 a **§7.1 (barevná sémantika tlačítek — závazná)**.

**Postup:**
1. Všech 6 detailů → `PageHeader` s `backTo` na příslušný seznam, `badges` = `StatusBadge`(y),
   `subtitle` = číslo/VIN/SKU.
2. Sjednotit sadu, pořadí a **barvy** akcí podle §7.1: **neutrální (outline-secondary) →
   hlavní (jedno plné: modré `btn-primary` u vratných, zelené `btn-success` u nevratného posunu
   procesu) → destruktivní (outline-danger, vždy poslední s `ms-auto`)**.
   - zákazník/vozidlo/zakázka/dodavatel: Editovat (outline-secondary), Deaktivovat (outline-danger);
   - sklad: Editovat (outline-secondary), **Skladový pohyb** (`btn-primary`), Deaktivovat (outline-danger);
   - faktura: PDF (outline-secondary), **Vystavit / Označit zaplaceno** (`btn-success` — nevratný
     posun dokladu), Stornovat (outline-danger).
3. Ikona entity: nahradit emoji 🚗/📦 kolečkem s iniciálami (`getInitials`) jako u ostatních entit.
4. Vnořené tabulky (šarže, pohyby, tachometr) do `DataTable` s `dense` a `EmptyState`.
5. **Procesní obrazovky** `ReceiptReviewPage` a `StockTakePageDetail`: převést hlavičku na
   `PageHeader` a srovnat barvy podle §7.1 — průběžné uložení klesá na `btn-outline-secondary`
   („Uložit koncept", „Uložit soupis"), vrcholná nevratná akce je plná zelená
   („Potvrdit a naskladnit" už jí je; **„Uzavřít inventuru" se mění z `btn-primary`
   na `btn-success`**, `StockTakePageDetail.jsx:207`), rušící akce `btn-outline-danger` s `ms-auto`.

**Co NEDĚLAT:** neměnit chování akcí ani potvrzovací texty; nepřidávat nové akce; u procesních
obrazovek nesahat na logiku draftu, kontrol ani na výpočet rozdílů inventury — jen hlavička a barvy.

**Akceptační kritéria:**
- [x] všech 6 detailů má „Zpět" na stejném místě (vlevo u nadpisu);
- [x] na každém detailu je **nejvýš jedno plné** tlačítko — proměřeno v DOM na 6 detailech
      i 3 seznamech, nikde víc než jedno;
- [x] barvy odpovídají tabulce §10.8 — vypsané třídy všech tlačítek v hlavičkách;
- [x] destruktivní akce je vždy poslední a oddělená;
- [x] emoji ikony jsou pryč (zbyla jen zmínka v komentáři `EntityAvatar.jsx`, který popisuje,
      co nahradil);
- [x] vnořené tabulky (tachometr, šarže, pohyby, položky zakázky i faktury) běží na `DataTable`
      s `dense`, `clientSort` a `EmptyState`.

**Dokumentace:** `docs/frontend.md` §10.1 — doplnit pravidlo pořadí akcí.

**Odchylky:**
- Body 1 a 3 (`PageHeader` s `backTo`/`badges`/`subtitle`, konec emoji ikon) byly hotové už
  z fází U1 a U4.1 — zbylo jen doladit barvy, procesní obrazovky a vnořené tabulky.
- `ms-auto` u destruktivní akce **se do `PageHeader` nepřidávalo**: blok akcí je tam už zarovnaný
  doprava, takže by nic neudělalo. Má smysl jen v lištách přes celou šířku, kde už je.
- Nový tón `btn-outline-success` („Aktivovat") **byl doplněn do §10.8** místo přebarvení. Je to
  tentýž knoflík jako „Deaktivovat", jen u neaktivního záznamu; přebarvit ho na modrý plný by
  udělalo z návratu do provozu hlavní akci stránky.
- `btn-outline-primary` zmizel i z `VehicleForm` („Načíst z registru") — tón, který §10.8 nezná.
- Součet položek zakázky se přesunul z `tfoot` **pod tabulku** (jako u faktury). `DataTable` je
  jedna pro celou aplikaci a patičku nemá; souhrn patří vedle tabulky, ne do ní.
- `MileageHistoryTable` ztratila podbarvení nejnovějšího řádku (`table-active`) — tutéž informaci
  nese odznak „aktuální" a žádná jiná tabulka řádky nepodbarvuje.
- **Ponecháno vědomě:** „Import dokladu (PDF)" je na `/warehouse/receipts` plné modré, kdežto na
  `/warehouse` šedé. Není to nedůslednost — na obrazovce příjemek je import hlavní cestou, jak
  doklad založit, na skladu je hlavní akcí nová položka. Jedno plné tlačítko na obrazovku platí.
- Ověření inventury: obě existující jsou zrušené, takže se lišta tlačítek nevykreslí. Zelené
  „Uzavřít inventuru" ověřeno přes stubnutý stav `OPEN` (bez zásahu do dat).

---

### FÁZE U5 — Formuláře

---

#### U5.1 · Převod formulářů na kartový vzor

**Proč:** Dva vzory rozvržení, dvě šířky stránky (`container` vs. plná), tři popisky uložení.

*Čti:* `analyza-ui-2026-07.md` §2.5 a §4.4, `docs/frontend.md` §10.3 (po U1.7),
všech 6 formulářů v `src/components/*Form.jsx`.

**Postup:** Pro `CustomerForm`, `VehicleForm`, `SupplierForm`, `WarehouseForm`, `UserForm`, `OrderForm`:
1. Nadpis vytáhnout ze `*Form` do stránky jako `PageHeader` (props `title` už formuláře dostávají —
   předá se rovnou do `PageHeader`), z formuláře `<h2>` odstranit.
2. Sekce `h5.text-primary.border-bottom` → `FormSection`.
3. Odstranit obal `container mt-4` (`VehicleForm.jsx:111`, `WarehouseForm.jsx:40`, `OrderForm.jsx:170`) —
   šířku drží layout.
4. Patičku nahradit `FormActions` (jednotně „Zrušit" + „Uložit"; `OrderForm` v create režimu
   „Vytvořit zakázku" přes prop `submitLabel`).
5. `VehicleForm`: opravit blok zákazníka (S-11) — `AutocompletePair` dostane `label="Zákazník"`,
   zrušit `marginTop: -1em` i `invisible` placeholder odstavec.

**Co NEDĚLAT:** neměnit validační pravidla, `required`, ani tvar odesílaných dat;
nepřepisovat `ReceiptDraftHeaderForm` (je to editor draftu, ne běžný formulář);
neměnit chování registru vozidel ani autocomplete.

**Akceptační kritéria:**
- [x] šest formulářů má shodné rozvržení, šířku i patičku — proměřeno na 11 routách
      (create i edit), patička všude „Zrušit" `btn-outline-secondary` + „Uložit" `btn-primary`;
- [x] grep `text-primary border-bottom` nevrací nic;
- [x] grep `container mt-4` nevrací nic (bylo odstraněno už dřív);
- [x] odeslání každé entity funguje — **bez zápisu do dat**: požadavek odchycen těsně před
      odesláním, ověřen endpoint, metoda i tělo (5× PUT, 2× POST); validace prázdné povinné
      pole nepustí a ukáže hlášku;
- [x] blok „Zákazník" ve `VehicleForm` má label a rovné zarovnání (popisek 51 px od okraje
      karty, stejně jako „Značka" v další sekci).

**Dokumentace:** `docs/frontend.md` §5 (Formuláře) — přepsat podle nového vzoru.

**Odchylky:**
- Krok 1 (nadpis vytáhnout do stránky) **neproveden**: formuláře už `PageHeader` používají
  a `title` dostávají propem, takže výsledek je stejný. Přesun by znamenal sáhnout na 11
  stránek bez viditelné změny.
- `OrderForm` žádné sekce neměl — dostal dvě: „Zákazník a vozidlo" a „Zakázka".
- Přibyla oprava mimo zadání: `NotFoundPage` neměla `h1`. Byla to jediná stránka aplikace
  bez nadpisu, což §10.1 zakazuje a pro čtečku obrazovky je to stránka bez názvu.
- `/suppliers/new` neexistuje — dodavatelé vznikají z příjemek, ne ručně. Není to chyba
  převodu, jen se ověřovalo o jednu routu míň (5 create + 6 edit).
- Skutečný průchod uložením proběhl **jen u editací beze změny hodnot** (rozhodnutí uživatele) —
  zakládání by nechalo v ostrých datech testovací záznamy, které nejdou smazat, jen deaktivovat.
  Všech 5 uložení vrátilo 200 a přesměrovalo na seznam.
- Ten průchod **odhalil chybu mimo zadání**: čas u zakázky se při každém uložení posouval
  o velikost časového posunu (viz **TD-47**, opraveno hned). Sám o sobě je to argument pro to,
  aby se ověřovalo skutečným uložením — odchycený požadavek by to neukázal.

---

#### U5.2 · Povinná pole a chování validace (S-10)

**Proč:** Povinnost pole se dnes ve většině formulářů pozná až po odeslání; po neúspěšné validaci
uživatel nevidí, kam se dívat.

*Čti:* `analyza-ui-2026-07.md` §2.5 a §4.4, všech 6 formulářů + modaly s formulářem.

**Postup:**
1. Ke každému `required` poli doplnit `<RequiredMark />` do labelu; nad první sekci formuláře
   přidat větu „Pole označená * jsou povinná." (`text-muted small`).
2. Do `handleSave` každého formuláře doplnit po neúspěšné `checkValidity()`:
   skok na první `:invalid` prvek (`scrollIntoView({block:'center'})` + `focus()`).
   Vytáhnout jako helper `src/api/formUtils.js#focusFirstInvalid(formRef)`.
3. Ověřit, že se CSS z U1.5 (potlačení zelené) projevuje ve všech formulářích i modalech.

**Co NEDĚLAT:** neměnit, která pole jsou povinná; nezavádět knihovnu pro formuláře;
nepřidávat souhrnný seznam chyb nahoře (zbytečné, když se skáče na pole).

**Akceptační kritéria:**
- [x] všechna `required` pole mají `*` — ověřeno **proti DOM** na 8 stavech formulářů
      (zákazník fyzická osoba i firma, s kontaktní adresou i bez, zakázka create i edit,
      vozidlo, dodavatel, sklad, uživatel): 0 polí bez značky a 0 značek u nepovinného pole;
- [x] odeslání neplatného formuláře odscrolluje na první chybu a zaostří ji — ověřeno na všech
      6 formulářích (např. zákazník: scroll 1090 → 33 px, fokus na `firstName`, pole v obraze);
- [x] prázdná nepovinná pole nemají zelenou zpětnou vazbu (7 nepovinných polí skladu po
      neúspěšném odeslání bez zeleného rámečku, chybná pole červeně `rgb(220,53,69)`).

**Dokumentace:** `docs/frontend.md` §10.3 — doplnit pravidlo povinných polí a skoku na chybu.

**Odchylky:**
- `AutocompletePair` dostal prop `required` + `aria-required`. Hvězdička se do něj dřív psala
  do textu popisku (`label="Zákazník *"`), takže ji čtečka četla jako součást názvu pole.
  HTML `required` tam nejde — hodnotu drží skrytý input a prohlížeč by validoval napsaný text,
  ne provedený výběr.
- U zakázky se na chybějícího zákazníka/vozidlo skáče ručně (ta pole nejsou `:invalid`).
- Poučení z průběhu: regulární výraz nad JSX minul povinná adresní pole, protože v atributech
  jsou šipkové funkce (`=>` obsahuje `>`). Nález odhalila až kontrola proti DOM, ne kontrola
  nad zdrojákem — proto je akceptační kritérium formulované přes DOM.

---

### FÁZE U6 — Modaly a hlášky

---

#### U6.1 · Převod modalů na komponentu `Modal` (S-12, S-13)

**Proč:** Devět modalů plus jeden inline mají vlastní kopii `.modal show d-block`, různé pořadí
tlačítek a chybějící křížky.

*Čti:* `analyza-ui-2026-07.md` §2.6 a §4.5, `src/components/Modal.jsx` (z U1.4).

**Postup:** Převést v tomto pořadí (od nejjednoduššího):
`ResetPasswordModal`, `ChangePasswordModal`, `StockMovementModal`, `MileageFormModal`,
`OrderItemFormModal`, `InvoiceCreateFormModal`, `GoodsReceiptImportModal`, `ImportProductFormModal`,
a inline modal v `ReceiptsPage.jsx:230-287` (vytáhnout do `ManualReceiptModal.jsx`).
Pro každý:
1. Nahradit vlastní obal komponentou `Modal` (`title`, `size`, `onClose`, `footer`, `closable={!saving}`).
2. Sjednotit patičku na **`Zrušit` → hlavní akce** (opravit obrácené pořadí v `ImportProductFormModal`).
3. Chyby vždy `alert alert-danger py-2` jako první prvek těla (`ResetPasswordModal` přejde
   z `is-invalid` na tento tvar, ale `invalid-feedback` u pole ponechá).

**Co NEDĚLAT:** neměnit obsah formulářů uvnitř modalů, jejich validace ani API volání;
nesjednocovat texty tlačítek hlavní akce (jsou záměrně konkrétní: „Nahrát a zpracovat", „Uložit pohyb"…).

**Akceptační kritéria:**
- [x] grep `modal show d-block` vrací jediný výskyt (`Modal.jsx`);
- [x] všech 10 dialogů má křížek, backdrop, zámek scrollu, fokus uvnitř a reaguje na Esc
      i klik na pozadí — proměřeno v DOM u každého zvlášť;
- [x] během ukládání (`saving`) dialog zavřít nejde — ověřeno zabrzděným požadavkem
      (křížek zmizí, Esc ani klik na pozadí nezabírá);
- [x] pořadí tlačítek je všude `Zrušit` vlevo, hlavní akce vpravo;
- [x] všech 10 dialogů otevřeno klikáním v aplikaci.

**Dokumentace:** `docs/frontend.md` §5 (Modály) — přepsat podle `Modal`.

**Odchylky:**
- Převod na `Modal` proběhl už dřív (U6.1 byl předtažen kvůli statické kontrole). Zbylo
  sjednotit patičky a ověřit chování.
- Zrušit/Ne přešlo z **plného** `btn-secondary` na `btn-outline-secondary` — §10.8 zná jen
  šedý obrys a formuláře (`FormActions`) ho už používaly, takže dialogy vybočovaly.
- `GoodsReceiptImportModal` má po úspěšném importu jednotlačítkovou patičku (jen „Zavřít"),
  takže se na něj pravidlo pořadí nevztahuje.
- `ImportProductFormModal` se otevírá z editace zakázky („+ Importovat položky"), ne
  z kontroly příjemky, jak plán předpokládal.

---

#### U6.2 · `FormModal` pro dialogy s povinným polem (S-13)

**Proč:** `ConfirmDialog` se zneužívá jako formulář — `ReceiptReviewPage` do něj vkládá `<textarea>`
pro důvod zamítnutí/storna, `StockTakePageDetail` pro poznámku. Chybí `required`, focus i submit.

*Čti:* `src/pages/ReceiptReviewPage.jsx:395-434`, `src/pages/StockTakePageDetail.jsx:216-232`,
`src/components/Modal.jsx`.

**Postup:**
1. `src/components/FormModal.jsx` nad `Modal` — props `title`, `intro`, `fields`
   (`{name, label, type, required, maxLength, hint}`), `submitLabel`, `onSubmit(values)`, `saving`.
2. Zapnout `required` validaci (`needs-validation`), fokus na první pole při otevření, submit na Enter.
3. Převést tři dialogy: zamítnutí příjemky (důvod volitelný), storno příjemky (důvod **povinný** —
   dnes se validuje ručně v `cancelReceipt` a chybová hláška se ukáže mimo dialog),
   uzavření inventury (poznámka volitelná).

**Co NEDĚLAT:** neměnit `ConfirmDialog` (zůstává pro čisté ano/ne); neměnit business pravidla
(povinnost důvodu storna zůstává); neměnit API payloady.

**Akceptační kritéria:**
- [x] storno příjemky bez důvodu nejde odeslat a chyba je **uvnitř** dialogu
      („Důvod storna je povinný.", `display: block`); na stránce pod dialogem nic;
- [x] po zadání důvodu se sestaví správný požadavek
      (`POST /warehouse/receipts/12/cancel` s `{"note": …}`) — **odeslání zablokováno**,
      aby se doklad opravdu nestornoval;
- [x] zamítnutí bez důvodu projde (`{"note": null}`);
- [x] uzavření inventury s prázdnou poznámkou projde (`{"note": null}`);
- [x] `ConfirmDialog` už nikde neobsahuje `<textarea>`.

**Dokumentace:** `docs/frontend.md` §10.4 — doplnit `FormModal`.

**Odchylky a nálezy:**
- Uzavření inventury poznámku dosud **vůbec neposílalo** (`{note: null}` natvrdo), přestože ji
  `StockTakeController#close` přijímá. Není to nová funkce, jen zpřístupnění existujícího pole.
- `Modal` fokusoval po otevření **křížek v hlavičce** místo prvního pole — hledal první ovládací
  prvek v celém dialogu a ten je v pořadí křížek. Opraveno pro všechny dialogy.
- Při odstranění stavu `rejectNote` zůstalo v obsluze tlačítka volání `setRejectNote("")`
  a „Zamítnout" přestalo otevírat dialog. Build ani stávající pravidla to nechytly, protože
  nejde o import ani JSX značku. Proto **nové pravidlo 1c** v `check-ui.mjs`: `setXxx()` volané,
  ale nikde v souboru nezavedené. Ověřeno dočasným vrácením chyby — kontrolér ji nahlásí.

---

#### U6.3 · Sjednocení alertů a z-indexu toastů (S-17, S-18, S-19)

**Proč:** Není pravidlo, kdy toast a kdy inline alert; toast se schová pod modal; chybové texty
mají tři různé tvary.

*Čti:* `analyza-ui-2026-07.md` §2.8 a §4.6, `src/components/AlertContainer.jsx`,
`src/components/Alert.jsx`, `src/context/AlertContext.jsx`.

**Postup:**
1. `AlertContainer` → `z-index: 1080` (nad modal 1055); z `Alert.jsx:18` odstranit `zIndex: 11`.
2. Aplikovat pravidlo z §4.6: výsledek akce = toast, trvalý stav obrazovky = inline alert.
   Konkrétně převést `CompanyProfilePage.jsx:70` (uložení) z inline alertu na toast.
3. Sjednotit tvar chybových hlášek na **„<Předmět> se nepodařilo <sloveso>."** — projít všechny
   výskyty `addAlert(..., "danger")` a `setError(...)`; odstranit „Zkuste to znovu", doplnit tečky.
4. Doplnit `info` mezi podporované typy v JSDoc `AlertContext` a v `docs/frontend.md` §6.

**Co NEDĚLAT:** neměnit délku auto-hide (15 s); nepřidávat knihovnu toastů; neměnit hlášky,
které přicházejí z backendu (`err.problem.detail`).

**Akceptační kritéria:**
- [x] toast je nad modalem — naměřeno `z-index` 1080 (kontejner) vs. 1055 (dialog) vs. 1050
      (backdrop) a ověřeno `elementFromPoint` nad plochou otevřeného dialogu;
- [x] uložení profilu firmy hlásí toastem i při neúspěchu (ověřeno zablokovaným požadavkem —
      `alert-danger` toast, na stránce žádný inline alert);
- [x] grep `Zkuste to znovu` nevrací nic;
- [x] `docs/frontend.md` §6 uvádí i `info`.

**Dokumentace:** `docs/frontend.md` §6 a §10.6.

**Odchylky a nálezy:**
- `CompanyProfilePage` už úspěch toastovala z dřívější fáze; převáděl se **neúspěch** uložení,
  který visel jako inline alert. Stav `error` tam tím pádem zanikl celý.
- Generický fallback „Operace se nezdařila." (detail faktury i řádkové menu) nesplňoval tvar —
  nahrazen „Stav faktury se nepodařilo změnit.".
- **Nález mimo zadání:** neúspěšné přeskládání položek zakázky se jen zalogovalo do konzole
  a pořadí se tiše vrátilo zpátky. Uživateli to připadalo jako chyba přetahování, ne jako
  neuložená změna. Doplněn toast.
- Chyba uvnitř dialogu (`MileageFormModal`, `FormModal`) **zůstává v dialogu** — netýká se
  výsledku akce, ale hodnot, na které se uživatel dívá. Zapsáno do §10.6 jako třetí kategorie.

---

### FÁZE U7 — Nápověda

---

#### U7.1 · Typografie nápovědy (`help.css`)

**Proč:** Třída `help-article` je v JSX, ale žádné CSS pro ni neexistuje; `reset.css` zároveň maže
marginy nadpisům i odstavcům, takže článek je slitý blok bez rytmu.

*Čti:* `analyza-ui-2026-07.md` §6, `src/pages/HelpPage.jsx`, `src/css/reset.css:11-18`, `src/main.jsx`.

**Postup:**
1. Vytvořit `src/css/help.css` přesně podle snippetu v §6 analýzy a importovat v `main.jsx`
   (za `index.css`).
2. `HelpPage`: nadpis stránky přes `PageHeader` (`title="Nápověda"`, `subtitle={article.title}`).
3. Seznamu článků vlevo přidat `position: sticky; top: 1.5rem` (jen ≥992 px).
4. Projít **všech 5 článků** (`stk-registr`, `sprava-uzivatelu`, `prijem-zbozi`, `sklad-pohyby`,
   `inventura`) a ověřit odstupy, seznamy, `code`, tučné pasáže i vnořené seznamy.

**Co NEDĚLAT:** neměnit obsah článků; nepřidávat vyhledávání v nápovědě; neměnit `help/index.js`.

**Akceptační kritéria:**
- [x] nadpis sekce má `margin-top: 36px` (2,25 rem ≥ 2 rem) a odstavce `margin-bottom: 16px`
      (1 rem) — naměřeno `getComputedStyle` na všech 5 článcích;
- [x] žádný nadpis se nedotýká sousedního textu — nejmenší mezera kolem nadpisu je **12 px**
      (proměřeno mezi všemi sousedními prvky v každém článku);
- [x] šířka textu je omezená (`max-width: 586.5px` = 68ch);
- [x] seznam článků při dlouhém článku neodjíždí — při scrollu na 500 px zůstal na 24 px
      od horního okraje (`position: sticky`).

**Dokumentace:** `docs/frontend.md` §5 (Uživatelská nápověda) — zmínit `help.css`.

**Odchylky a nálezy:**
- Sticky je od **768 px** (`col-md-*`), ne od 992 px jak plán psal — pod tím se sloupce stohují
  a nabídka je nad článkem, kde by lepení k hornímu okraji jen ukrojilo výšku textu.
- **Nález mimo zadání:** stránka měla **dva `h1`** — „Nápověda" z `PageHeader` a nadpis článku
  z markdownu. Analýza to řešila jen zmenšením v CSS, takže vizuálně to sedělo, ale čtečka
  obrazovky hlásila dva názvy stránky. Nadpisy se proto posouvají o úroveň níž a velikost řídí
  třída (`help-h1`…`help-h3`), ne úroveň značky — vzhled beze změny, markdown zůstal přenositelný.

---

#### U7.2 · GFM v nápovědě (dle R-4)

**Proč:** Články dnes nesmí používat tabulky (`help/index.js:13`), což u skladu a fakturace vadí.
✅ **R-4 rozhodnuto 22. 7. 2026 uživatelem: přidat `remark-gfm`.**

*Čti:* `src/pages/HelpPage.jsx`, `src/help/index.js`, `package.json`.

**Postup:**
1. `npm i remark-gfm`, v `HelpPage` `<Markdown remarkPlugins={[remarkGfm]}>`.
2. Do `help.css` doplnit styl tabulky (`table`, `th`, `td`, tenké linky, `overflow-x: auto` obal).
3. Aktualizovat komentář v `help/index.js` (dnes zakazuje tabulky).

**Co NEDĚLAT:** nepřepisovat existující články na tabulky (samostatná obsahová práce);
nepovolovat `rehype-raw` (HTML v článcích zůstává zakázané).

**Akceptační kritéria:**
- [x] testovací tabulka se vyrenderovala a je stylovaná (3 sloupce, `padding: 8px 12px`,
      zvýrazněná hlavička, `---:` zarovnalo poslední sloupec doprava); ověřen i přeškrtnutý text,
      automatický odkaz a zaškrtávací seznam. **Testovací obsah vrácen přes `git checkout`**, ne
      ručně — a znovu ověřeno, že ve všech 5 článcích není;
- [x] tabulka se na úzkém okně scrolluje uvnitř `.help-table` (375 px: obal 279 px, tabulka
      321 px) a stránka nepřetéká;
- [x] komentář v `help/index.js` odpovídá skutečnosti.

**Dokumentace:** `docs/frontend.md` §5 + `CLAUDE.md` (pokud zmiňuje zákaz tabulek).

**Odchylky:**
- `CLAUDE.md` zákaz tabulek nikdy nezmiňoval — upravovat se nemusel.
- Tabulka se obaluje `.help-table` přes `components` u `<Markdown>`; markdown sám třídu nést
  nemůže, a bez obalu by široká tabulka roztáhla celou stránku.

---

### FÁZE U8 — Úklid a uzavření

---

#### U8.1 · Sjednocení formátovačů (S-27)

**Proč:** `formatQuantity` existuje 4×, `formatMoney` 2× a dává jiný výstup než `formatCurrency`;
čísla se formátují i inline. V tabulce položek zakázky nejsou ceny formátované vůbec.

*Čti:* `analyza-ui-2026-07.md` §3 (S-27 detailně), `src/api/format.js`.

**Postup:**
1. Do `format.js` přidat `formatQuantity(value, unit)` a `formatNumber(value)` (s JSDoc, `EMPTY_VALUE`
   pro `null`).
2. Smazat lokální kopie: `ProductTable.jsx:11-20`, `WarehousePageDetail.jsx:10-18`,
   `StockTakePageDetail.jsx:9-12`, `LowStockPage.jsx:7-10`.
3. Nahradit `formatMoney` za `formatCurrency` (pozor: mění zápis na `Intl` — ověřit vzhled tabulek).
4. Nahradit inline `toLocaleString` v `VehiclesPageDetail.jsx:179`, `MileageHistoryTable.jsx:47`,
   `StockMovementModal.jsx:178-182`.
5. `OrderItemTable.jsx:23-25` a `OrdersPageDetail.jsx` — ceny přes `formatCurrency`,
   ze záhlaví pak odstranit `[Kč]`.

**Co NEDĚLAT:** neměnit `formatCurrency` samotný; neměnit počty desetinných míst u množství
(množství má proměnný počet, cena vždy 2).

**Akceptační kritéria:**
- [x] grep `function formatQuantity|function formatMoney|toLocaleString` vrací už jen `format.js`;
- [x] ceny v položkách zakázky jsou formátované stejně jako v souhrnu — v editaci i na detailu
      shodně `500,00 Kč`, `750,00 Kč`, `420,00 Kč`; ze záhlaví zmizelo `[Kč]`;
- [x] hodnoty ve skladu i na vozidle jsou nezměněné až na jednotný zápis (`198 400 km`).

**Dokumentace:** `docs/frontend.md` §4 (format.js) — doplnit nové helpery.

---

#### U8.2 · Úklid mrtvého kódu a textů (S-16, S-19, S-20, S-21, S-26)

**Proč:** Pravidlo R-12 z `konvence.md` — dead code se maže.

*Čti:* `analyza-ui-2026-07.md` §2.9 a §3.

**Postup:**
1. Smazat nepoužité CSS: `#loading-overlay` (`index.css:38-42`); `.empty-state` **ponechat**
   (po U1.3 je používané).
2. Smazat mrtvé importy `CustomersPageDetail.jsx:14,16` a proměnnou `ImportProductFormModal.jsx:27`.
3. Opravit `PaginatorRounded.jsx:10` (`card-footer` → vlastní třída `.list-footer`) a `:14`
   (`aria-label="Default select example"` → „Počet záznamů na stránku"); sjednotit výchozí
   velikost stránky na **10** ve všech seznamech.
4. Sjednotit „Načítám..." → „Načítám…" (po U1.3 už jen v `Sidebar.jsx`).
5. Do `docs/tech-dluhy.md` zapsat **chybějící editaci adres zákazníka** (zjištěno v U0.1:
   `CustomerDto.UpdateRequest` nemá `addresses`, adresu u existujícího zákazníka nelze změnit) —
   vyžaduje zásah do backendu, proto mimo tento plán.
6. Zkontrolovat, že po fázích U3–U6 nezůstaly osiřelé komponenty (`InputFilter`, `CheckBox`,
   lokální `StatusBadge`/`MetricCard`) — pokud ano, smazat.

**Co NEDĚLAT:** nemazat `.empty-state`; neměnit `MILEAGE_SOURCE_OPTIONS` ani jiné funkční číselníky;
nemazat seed poznámku „Initial reading migrated…" z databáze (to je datová otázka, ne UI —
zapsat do `tech-dluhy.md`).

**Akceptační kritéria:**
- [x] grep `loading-overlay|Default select example|canImport` nevrací nic;
- [x] všech **8 seznamů** startuje s velikostí stránky 10 (proměřeno v DOM);
- [x] žádný nepoužitý import — `vite build` je nehlásí, proto proveden vlastní scan
      (741 jmen, 4 skutečné nálezy odstraněny);
- [x] osiřelé komponenty žádné nezbyly — `InputFilter` i `CheckBox` padly už dřív;
      ověřeno protažením všech souborů v `src/components/`.

**Dokumentace:** `docs/tech-dluhy.md` — uzavřít odpovídající položky, přidat poznámku o seed textu.

---

#### U8.3 · Závěrečná aktualizace dokumentace

**Proč:** Po dokončení musí `frontend.md` popisovat skutečnost, ne historii.

*Čti:* `docs/frontend.md` celý, `docs/architektura.md`, `docs/roadmapa.md`, `analyza-ui-2026-07.md`.

**Postup:**
1. Projít `docs/frontend.md` §1–§9 a opravit vše, co po U0–U8 neplatí (zejména §5 Vzory komponent,
   §6 AlertContext, §9 Známé nekonzistence — ta se má vyprázdnit nebo přepsat).
2. `docs/architektura.md` — řádek o UI stacku doplnit o rozhodnutí R-1 (MUI ano/ne).
3. `docs/roadmapa.md` — zaznamenat dokončení sjednocení UI a přesunout otevřené věci
   (Dashboard, a11y audit, případný přechod MUI→Bootstrap).
4. V `plan-ui.md` §3 zaškrtnout všechny hotové úkoly.

**Akceptační kritéria:**
- [x] `docs/frontend.md` §9 už není seznam nekonzistencí — je z něj **stav sjednocení**
      s tabulkou toho, co zůstalo otevřené a proč to plán neřešil;
- [x] `docs/roadmapa.md` má záznam o dokončení (§2.3) i seznam odložených položek;
- [x] všechny checkboxy v §3 odpovídají skutečnosti — fáze **U3R zůstala nezaškrtnutá**,
      přestože byla hotová; doplněno.

**Co se cestou opravilo v `frontend.md`:** §2 (struktura `src/` — počty souborů, `help.css`),
§5 (seznamové stránky stále popisovaly smazané `InputFilter`/`CheckBox`), §9 (přepsáno).

---

## 5. Ověření po každé fázi

> **Poučení z fáze U1 (21. 7. 2026):** `npm run build` **není důkaz funkčnosti**. Komponenta použitá
> bez importu je v JS legální zápis a spadne až za běhu — takhle proklouzl `StatusBadge` do
> `ReceiptDraftHeaderForm` a shodil detail příjemky, přestože build byl zelený a „ověření" prošlo.
> Ověřování v prohlížeči navíc nesmí končit u kontroly, že se vyrenderoval nadpis: **každá routa
> včetně detailů se skutečnými ID** musí být otevřená a konzole po ní přečtená.

Po dokončení každé fáze (U0…U8) projít tento průchod a přiložit výsledek:

0. `npm run check` — statická kontrola UI konvencí (chybějící importy, vlastní `<h1>`, ručně psaný
   modal, `text-bg-*`, `text-truncate` na tlačítku). Musí být bez nálezu.
1. `cd frontend/autoservis-frontend && npm run build` — musí projít bez chyb.
2. Spustit backend i frontend, přihlásit se (uživatel) a projít **všechny routy z `App.jsx`** —
   tedy i detaily, formuláře create/edit a kontrolní obrazovky, **se skutečnými ID z databáze**,
   ne jen položky ze sidebaru. Šířky **1440 px** a **992 px**; od U2.3 navíc **768 px**.
3. **Po každé routě** přečíst konzoli prohlížeče — žádné chyby ani React varování. Nestačí zjistit,
   že se vyrenderoval nadpis: stránka může spadnout až v podřízené komponentě.
3b. Otevřít **každý modal** dotčený fází a ověřit Esc, klik na pozadí a zámek scrollu.
4. Průchod hlavními scénáři: založení a editace zákazníka → vozidla → zakázky → položek →
   faktury; import příjemky → kontrola → potvrzení; ruční skladový pohyb; inventura.
5. Screenshoty dotčených obrazovek přiložit k úkolu.

## 6. Mimo rozsah tohoto plánu

- **Obsah Dashboardu** — produktové zadání, patří do `roadmapa.md`.
- **Systematický audit přístupnosti** (kontrasty, čtečky, kompletní klávesová obsluha) —
  tento plán řeší jen to, co vyplynulo ze sjednocení (`aria-modal`, focus trap, labely, `scope`).
- **Přechod MUI → čistý Bootstrap** — dle R-1 (rozhodnuto: MUI zůstává) samostatný pozdější úkol,
  zapsat do `tech-dluhy.md` v rámci U1.7.
- **Kartové zobrazení tabulek na telefonu** — dle R-2 mimo cílovou responsivitu.
- **Změny backendu** — žádný nález je nevyžaduje.
