# Funkce: Dashboard (úvodní přehled)

> Funkční dokumentace — **co** funkce dělá a **proč** je postavená takhle.
> Technické detaily vrstev: [api.md](../api.md) (sekce Dashboard) · [backend.md](../backend.md) · [frontend.md §5](../frontend.md).
> Uživatelská nápověda: v aplikaci záložka **Nápověda** (`frontend/…/src/help/dashboard.md`).

## Co funkce dělá

Úvodní stránka (`/dashboard`) je **rozcestník k akci** — na jednom místě ukáže, co dnes potřebuje
pozornost, a z každé dlaždice proklikne na příslušný seznam nebo detail. Nic needituje.

- **KPI řádek**: rozpracované zakázky, k vyfakturování, pohledávky po splatnosti (částka), hodnota skladu.
- **Vyžaduje pozornost** (výstražné dlaždice, každá s počtem a max 5 řádky):
  - **zakázky po termínu** — odhad dokončení (`estimated_completion_at`) je v minulosti a zakázka není dokončená/zrušená;
  - **faktury po splatnosti** — vystavené (ISSUED) faktury po `due_date`, včetně součtu dlužné částky; **faktura s vystaveným dobropisem se nepočítá** (audit KN-20) — koncept dobropisu ano, pohledávka do jeho vystavení trvá;
  - **sklad pod minimem** — díly pod hlídaným minimem i s doporučeným dodavatelem (reuse přehledu „Pod minimem");
  - **končící / propadlá STK** — vozidla se STK do 30 dnů nebo propadlou.
- **Provoz**: rozpad rozpracovaných zakázek po stavech, fronta k vyzvednutí, příjemky ke kontrole
  a otevřená inventura, tržby tento vs. minulý měsíc, **marže** (materiál + práce) tento vs. minulý měsíc.
- **Statistika** (tlačítko vpravo nahoře): modal s **měsíční řadou** zvoleného roku — počet zakázek
  (dle data založení), počet faktur, tržby (s DPH) a marže (bez DPH) po měsících + roční součet.
  Tržby i marže počítají stejně jako dlaždice, včetně odečtu vystavených dobropisů (do V69 je
  měsíční řada míjela a modal tvrdil za týž měsíc jiné číslo než dlaždice).
  Filtr roku (nabízejí se jen roky s daty); vlastní volání `GET /dashboard/statistics?year=`.

Data přehledu přijdou **jedním voláním** `GET /dashboard/summary`.

## Klíčová rozhodnutí a proč

| Rozhodnutí | Proč |
|---|---|
| **Jeden agregační endpoint** (`/dashboard/summary`, BFF vzor) místo skládání z mnoha seznamů | dashboard se načte na jedno volání; 6+ requestů na dlaždici by bylo pomalé a upovídané |
| **Dlaždice jen navigují, needitují** | přehled má nasměrovat k akci, ne být další editační obrazovkou; každý proklik vede na existující seznam/detail |
| **Preview omezené na 5 řádků, počty úplné** | dlaždice nesmí lhát číslem, ale ani zahltit — detail je od toho seznam za proklikem |
| **Řazení podle naléhavosti, ne podle modulu** | „vyžaduje pozornost" nahoře (červená/oranžová), běžný provoz níž — uživatel vidí problémy dřív než statistiku |
| **Read-only, bez konvertoru; datové výpočty v DB** | projekce se mapují rovnou z SQL (vzor `StockValuation`/`LowStock`); `daysOverdue` i `expired` počítá DB deterministicky jako STK filtr a cenové views |
| **„Zakázky po termínu" z `estimated_completion_at`, ne z tvrdého deadline** | tvrdý termín v modelu není; počítá se jen ze zakázek s vyplněným odhadem (nepovinné pole) — u nevyplněných by výstraha nedávala smysl |
| **„K vyfakturování" = COMPLETED bez nestornované faktury** | ctí pravidlo 1:1 faktura↔zakázka; koncepty faktur (DRAFT) jsou samostatná informace |
| **Tržby podle `issue_date` (ISSUED+PAID), ne podle úhrady** | datum zaplacení faktury se needviduje; vystavená faktura je tržba. **Vystavené dobropisy se odečítají** — v měsíci vystavení *dobropisu*, ne původní faktury, aby se zpětně neměnil už uzavřený měsíc (audit KN-20) |
| **Marže z položek vyfakturovaných zakázek, ne z faktury** | náklad (`purchase_price`) nese jen `order_items`, ne `invoice_items`; marži proto počítáme z položek zakázky joinnutých na její vystavenou fakturu. Období podle `issue_date` zrcadlí tržby, aby čísla seděla vedle sebe. Join bere jen **aktivní** fakturu zakázky (V69): po dobropisu a refakturaci má zakázka dvě nestornované faktury a bez toho by se položky započítaly dvakrát; dobropisovaná faktura marži nenese, protože tržbu z ní zrušil dobropis |
| **Marži práce odemyká teprve modul Zaměstnanci** | dřív se náklad práce nikde nevedl, takže šla spočítat jen materiálová marže — proto marže na dashboardu vznikla až s LABOR snímkem sazby (D-3), rovnou jako materiál + práce |
| **Položky bez známého nákladu se do marže nezapočítají** | prázdná `purchase_price` = neznámý náklad; kdyby se brala jako nula, položka by se tvářila jako 100% marže a číslo by nafoukla |
| **Přehled „pod minimem" a hodnotu skladu bere service z `WarehouseMapper`** | skladová logika se neduplikuje — dashboard jen přebírá hotové dotazy |
| **Celé čtení v jedné read-only transakci** | všechna čísla pocházejí z téhož okamžiku (konzistentní snímek) |
| **Statistika se počítá živě, měsíční agregáty se neukládají** | tržby i marže jsou kdykoli zpětně spočitatelné z faktur (`issue_date`) a položek zakázek (snapshot `purchase_price`); uložený součet by byl druhý zdroj pravdy a rozešel by se např. při stornu faktury za starší měsíc |
| **Ve statistice filtr roku místo stránkování** | rok má nejvýš 12 řádků — stránkovat není co; „ukaž mi rok 2025" je přirozenější dotaz než „ukaž stránku 3" |
| **Počet zakázek dle `created_at`, ne dle vyfakturování** | počet vyfakturovaných zakázek by kvůli pravidlu 1:1 faktura↔zakázka jen kopíroval počet faktur; založené zakázky ukazují vytížení dílny nezávisle na fakturaci |

## Čerstvost dat

Většina dlaždic je živá (počítá se při každém načtení). **Výjimka je STK**: badge a počet jsou jen
tak čerstvé jako poslední snapshot z registru vozidel — data se dnes obnovují jen on-demand (založení
vozidla, tlačítko na detailu). Dlaždice to přiznává poznámkou „čerstvost dle posledního načtení".
Automatický noční refresh je [roadmapa.md](../roadmapa.md) §2.4 (Vehicle Phase 4c).

## Mapa implementace

- **Backend:** `mapper/DashboardMapper(.xml)` (agregační dotazy — count/sum/group by/preview LIMIT 5;
  pro statistiku `findMonthlyStats` + `findStatsYears`), `service/DashboardService(Impl)` (skládá
  `Summary` a `Statistics`, reuse `WarehouseMapper` pro sklad), `controller/DashboardController`
  (`GET /dashboard/summary`, `GET /dashboard/statistics`), DTO `model/dto/dashboard/DashboardDto`.
- **Frontend:** `pages/DashboardPage.jsx` — KPI (`MetricRow`/`MetricCard`), dlaždice front z hotových
  vzorů (`StatusBadge`, prokliky přes React Router); volání `api.get('/dashboard/summary')`.
  Modal statistiky: `components/DashboardStatisticsModal.jsx` (sdílený `Modal`, filtr roku, roční součet).
- **Testy:** `service/DashboardServiceTest` (izolace seedu + řízená data: rozpad po stavech, po termínu,
  k vyfakturování, faktury po splatnosti, STK, omezení preview na 5, prázdné sekce).

## Historie

- 2026-07-24: navrženo a implementováno (větev `dashboard`); ověřeno e2e proti seedu (`/dashboard/summary`
  → 200, KPI i obě sekce se vykreslují, prokliky vedou na detaily/seznamy).
- 2026-07-30: modal **Statistika** — měsíční řada (zakázky, faktury, tržby, marže) za zvolený rok,
  `GET /dashboard/statistics?year=`, komponenta `DashboardStatisticsModal`. Počítá se živě, nic se neukládá.
