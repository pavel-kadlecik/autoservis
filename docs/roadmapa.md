# roadmapa.md — Směr vývoje a otevřená rozhodnutí

> Strategický dokument — kam projekt směřuje, co nebylo rozhodnuto.
> Aktualizovat po každém uzavřeném rozhodnutí nebo dokončeném milníku.
> Stav k 2026-07-18 (revize při restrukturalizaci dokumentace; migrace V1–V37).

## 1. Kde právě jsme

| Milník | Stav |
|---|---|
| Security (JWT, refresh rotace, blacklist, rolová autorizace) | ✅ hotovo e2e (TD-31 i TD-24 vyřešeny) |
| Customer modul | ✅ hotovo e2e |
| Vehicle modul + historie km (V20) | ✅ hotovo e2e |
| Order modul vč. položek, drag-and-drop řazení, import ze skladu (V27) | ✅ hotovo e2e |
| Vehicle — STK z registru vozidel (dataovozidlech.cz, V38) | ✅ hotovo e2e |
| Billing — faktury vč. frontendu, PDF s QR platbou, snapshoty (V33–V37) | ✅ hotovo e2e |
| Warehouse — produkty, AI import PDF faktur, dodavatelé (RUD) | ✅ hotovo e2e |
| Warehouse — přestavba importu: draft pipeline + kontrola + identita produktu | ✅ implementováno (viz 2.1; čeká na Testcontainers běh + e2e průchod) |
| Sjednocení UI (plán `plan-ui.md`, fáze U0–U8) | ✅ dokončeno 2026-07-22 |
| Dashboard | ✅ hotovo e2e (KPI + fronty z `/dashboard/summary`; `funkce/dashboard.md`) |
| Vehicle Phase 2 (číselník značek/modelů) | ⏳ plánováno |

## 2. Rozpracované záměry

### 2.1 Přestavba importu skladu — schváleno 2026-07-19, probíhá

Nahrazuje dřívější dílčí záměry „typ dokladu + dopočet DPH" a „workflow příjemek" jedním návrhem (detail: plán schválený uživatelem; funkční dokument vznikne ve fázi 2 jako `docs/funkce/import-prijemek.md`):

- **Kanonický draft**: každý kanál (AI PDF, ruční formulář, budoucí ISDOC) vyprodukuje tentýž draft; import ukládá jen hlavičku + JSONB `draft_payload` — produkty/šarže/pohyby vznikají až potvrzením.
- **Tři stavy pole odvozené kódem** (VERIFIED / EXTRACTED / DEFAULTED): model vrací hodnotu + původ, deterministické kontroly povyšují; defaulty v `application.yaml`.
- **Typ dokladu volí uživatel při uploadu** (INVOICE / DELIVERY_NOTE); u dodacího listu dopočet DPH v kódu — „AI čte, kód počítá".
- **Identita produktu**: výrobce + normalizované číslo dílu + převodník `supplier_products` (samoučící se párovací kaskáda) — řeší duplicitní karty stejného dílu od různých dodavatelů.
- **Dedup DL ↔ následná faktura** (LKQ vzor: faktura obsahuje skupinové řádky „Dodací list č. X").

Fáze: 1) migrace V39 draft schéma ✅ · 2) extrakce v2 + draft import ✅ (ověření extrakce nad reálnými PDF: `PdfDocumentExtractionManualTest`, vyžaduje API klíč) · 3) confirm/reject BE ✅ (`GoodsReceiptReviewController`, 6 endpointů) · 4) kontrolní UI ✅ (ReceiptsPage + ReceiptReviewPage; e2e ověření čeká na restart backendu s novým kódem) · 5) identita produktu (V40, supplier_products, kaskáda) ✅ · 6) ruční příjemka ✅ (POST /receipts, prázdný MANUAL draft → táž review obrazovka) · 7) dedup DL↔faktura (V41, receipt_delivery_note_refs, volba provázat/naskladnit) ✅.

**Všech 7 fází implementováno (2026-07-20).** Extrakce ověřena na 4 reálných PDF z `import/` (rekonciliace 4/4). Integrační testy: **celá suite zelená** (67 testů, 0 selhání — Docker Desktop doinstalován 2026-07-20). Zbývá: projít e2e v prohlížeči proti backendu s novým kódem (restart backendu v IDE) a commit.

Pozn.: retence REJECTED draftů (drží PDF v BYTEA) — úklid rozhodnout později.

### 2.2 Rozvoj skladové domény — plán E1–E8 dokončen (2026-07-21)

**Hotovo na větvi `sklad-rozvoj`** (suite 159 testů zelená), dle `docs/plan-sklad.md`:

| Fáze | Obsah | Migrace |
|---|---|---|
| E1 | Blokace ne-CZK dokladů, uzavřený číselník měrných jednotek | — |
| E2 | Ruční skladové pohyby (korekce, odpis) vč. UI a nápovědy | — |
| E3 | Ocenění zásob — hodnota skladu z šarží | V42 |
| E4 | Storno potvrzené příjemky kompenzačními pohyby | V43 |
| E5a | Vratka dodavateli (RETURN s důvodem a číslem dobropisu) | — |
| E6 | Inventura — soupis, manko FIFO, přebytek pseudo-příjemkou | V44 |
| E7 | ISDOC adaptér (český standard e-faktury, bez AI) | — |
| E8 | FIFO předvýběr šarže, foto/scan do AI importu, přehled „pod minimem", výdej mimo zakázku | — |

**Otevřené zůstává jen E5b** — přijatý dobropis jako samostatný `document_type` v draft pipeline
(rozhodnutí **R-G**: odloženo, protože dobropis je opačný doklad než příjemka a *která šarže*
a *proč* z něj vyčíst nejdou; vratka se dnes eviduje ručně). Dále z analýzy vědomě nezařazeno:
objednávkový modul a 3-way match (S-9), rezervace zboží (S-7), tisk inventurního protokolu.

**Zpevnění pro ručně psané doklady (2026-07-26):** import fotky dodacího listu, který uvádí jen ceny s DPH — zpětný přepočet základu i jednotkové ceny (`DraftAssembler.deriveLineAmounts`), vyřazení neskladových řádků (práce, spotřební materiál) přepínačem `ITEM`↔`NOTE`, katalogové číslo povinné jen pro nově zakládaný produkt, a přehlednější značení polí (červeně jen povinné a prázdné; `ABSENT` neutrálně).

Funkční dokumenty: `funkce/import-prijemek.md`, `funkce/sklad-pohyby.md`, `funkce/inventura.md`.

Hloubková analýza skladu (procesy, slepá místa, rešerše ERP/české praxe) je v `docs/analyza-sklad-2026-07.md`; prováděcí plán v `docs/plan-sklad.md`. Analýza obsahuje šest uzavřených rozhodnutí (oceňování = šarže + FIFO předvýběr; kompenzační storno potvrzené příjemky; kladná korekce = inventurní šarže; kanály: ISDOC + dobropis + foto/scan ano, e-mail schránka odložena; ne-CZK doklady blokovat; korekce před plnou inventurou). Jádro (ledger, šarže, draft pipeline, supplier_products) se nemění — přidávají se chybějící procesy: ruční pohyby (ADJUSTMENT/WRITE_OFF), hodnota skladu, storno příjemky, vratka dodavateli, inventura, ISDOC adaptér.

### 2.3 Sjednocení UI — dokončeno 2026-07-22

Plán [plan-ui.md](plan-ui.md) proběhl celý (U0–U8 + vsunutá fáze U3R). Výsledek: každý stavební
prvek aplikace má **právě jeden vzor** — `PageHeader`, `DataTable`, `DetailCard`/`MetricCard`,
`FormSection`/`FormActions`, `Modal`/`ConfirmDialog`/`FormModal`, `StatusBadge`,
`EmptyState`/`LoadingState`/`ErrorState`. Konvence jsou v `frontend.md` §10 a hlídá je
`npm run check` (7 pravidel, která `vite build` nezachytí).

Vedle vzhledu se opravily i tři funkční chyby, které se našly až při ověřování:
- **TD-46** — backend ignoroval `sortDesc`, seznamy tedy nešlo řadit sestupně;
- **TD-47** — čas dokončení zakázky se posouval o velikost časového posunu při **každém** uložení;
- neúspěšné přeskládání položek zakázky se tiše vracelo bez jakékoli hlášky.

**Odloženo (mimo rozsah plánu):** systematický
audit přístupnosti (TD-44), anglický text ze seedu v historii tachometru (TD-45, datová oprava),
případný přechod MUI → Bootstrap (TD-43, rozhodnutí R-1 zní „ponechat"). *(Editace adres zákazníka —
TD-42 — vyřešena 2026-07-25.)*

### 2.4 Automatický refresh STK snapshotů (noční job)
STK data se dnes obnovují jen on-demand (založení vozidla, tlačítko na detailu) — badge a filtr „Končící STK" jsou proto jen tak čerstvé jako poslední snapshot; u dlouho neotevřeného vozidla mohou lhát oběma směry. Než se filtr začne používat pro aktivní obvolávání zákazníků, doplnit scheduled job (`@EnableScheduling` už běží — vzor `BlacklistCleanupService`): v noci projít aktivní vozidla s nejstarším snapshotem a obnovit je s throttlingem pod limitem API (27 dotazů/min). Rozhodnout: interval stáří snapshotu (např. > 30 dní) a denní strop dotazů.

### 2.5 Plánovací (objednávkový) kalendář — ✅ hotovo e2e (2026-08-03)
Servis dnes eviduje zakázku až ve chvíli, kdy auto stojí v dílně. Telefonická objednávka na termín nemá kam
spadnout — buď skončí na papíře, nebo se založí prázdná zakázka, která zkreslí fronty na dashboardu.

Nový modul `schedule`: jedna tabulka `appointments` pro objednávky termínů (BOOKING) i blokace dílny
(CLOSURE), s volitelnou vazbou na zakázku a tlačítkem „Založit zakázku z objednávky". Frontend je
**vlastní, bez kalendářové knihovny** — denní karty místo časové osy.

**Rozhodnutí učiněná při návrhu (2026-08-03):**
- *Samostatná entita, ne sloupce na `orders`* — objednávka vzniká dřív než zakázka a část se na zakázku
  nikdy nepromění (zákazník nedorazí).
- *Neplánuje se na zdroj* (mechanika ani stanoviště) — kalendář je zatím čistě časový; `employee_id`
  lze doplnit později bez přestavby.
- *Kolize varují, nezakazují* — překryv objednávek servis často chce. Tvrdě (422) se odmítne jen termín
  spadající do blokace.
- *Bez číselné řady* — objednávka není doklad. Až přibude objednávání přes web, přidá se náhodný
  ověřovací kód, ne souvislá řada.

**Stav: hotovo e2e (2026-08-03).** V72 + V73, doména/DTO/converter, MyBatis mapper, service s validacemi
a stavovým automatem, 10 endpointů, kalendář jako denní karty (týden) + měsíční přehled, převod na
zakázku a zpětný odkaz. Časová osa i FullCalendar byly vyzkoušeny a opuštěny — viz funkční dokument. 65 testů (34 integračních, 13 kontraktních, 18 unit). Funkční dokument
`docs/funkce/planovaci-kalendar.md`, nápověda `help/planovani.md`.

**Odloženo:** plánování na mechanika a stanoviště, automatický návrh termínu, SMS/e-mail připomínky,
objednávání zákazníkem přes web, opakované události.

## 3. Otevřená rozhodnutí

### ROZH-002 — Zákaznický portál vs. jen admin rozhraní?
Aplikace je pro zaměstnance. Method-level security už existuje (TD-24 vyřešeno E7); portál by ještě vyžadoval: route `/portal/*`, roli/scope pro zákazníka a DTO bez citlivých polí (TD-11, TD-22). **K zamyšlení, neblokuje.**

### ROZH-004 — `internal_note` u zakázek
`description` (pohled zákazníka) vs. `internal_note` (mechanik) — nechat obě? PRO: oddělení pohledů, příprava na portál. PROTI: zaměstnanci neví, kam psát co. **Rozhodnout před portálem.**

### ROZH-005 — `order_type` na zakázce
Přidat sloupec `order_type` (SERVIS, PNEUSERVIS, REKLAMACE, PRAVIDELNY_SERVIS, OPRAVA, DIAGNOSTIKA, JINE). Vyžaduje novou migraci. **Plánováno, nezapočato.**

### ROZH-006 — Audit log změn v DB
Zejména pro `order.order_items` (spor o cenu → historie změn položek). Varianty: (A) tabulka audit_log, (B) DB triggery, (C) jen `updated_by`. **Řešit před produkcí.**

### ROZH-007 — Interaktivní import dodavatele
Zvažováno a **odloženo**: uživatel potvrzuje/vybírá dodavatele při importu; ruční zakládání dodavatele. Naráží na `goods_receipts.supplier_id NOT NULL` — velká přestavba kvůli okrajovému problému. Vrátit se, jen pokud normalizace registračního čísla nebude stačit.

### ROZH-008 — Využití deactivate/activate dodavatelů v UI
Backend i FE akce existují; kdy dodavatele archivovat je otevřené.

## 4. Roadmapy modulů

### Vehicle
| Phase | Obsah | Stav |
|---|---|---|
| 1 | vehicles, ENUMy, trigger, e2e (V5, V7, V8, V19) | ✅ |
| 2 | Číselník `brands`+`models`, migrace ze stringů na FK | ⏳ |
| 3 | Historie km (V20) | ✅ |
| 4a | STK z registru vozidel — dataovozidlech.cz, snapshoty + prefill formuláře (V38) | ✅ |
| 4b | Doklady — EK, pojištění (ruční evidence, `vehicle_inspections`) | ⏳ |
| 4c | Automatický noční refresh STK snapshotů (2.2) | ⏳ |
| 5 | Historie vlastnictví (`ownership_history`) | ⏳ |

### Order
| Phase | Obsah | Stav |
|---|---|---|
| 1 | orders + order_items + frontend (V6, V8, V11–V13, V22–V27) | ✅ |
| 2 | Stavové přechody s validací (enum state machine jako u faktur) | ⏳ |
| 2 | `order_type` (ROZH-005) | ⏳ |

### Billing
| Phase | Obsah | Stav |
|---|---|---|
| 1 | invoices + invoice_items (V14–V17) | ✅ |
| 2 | Frontend faktur, stavový automat, company profile (V31–V36) | ✅ |
| 3 | PDF faktury s QR platbou (openhtmltopdf + ZXing; iText z původního plánu opuštěn) | ✅ |
| 4 | Rekapitulace DPH (V37) | ✅ |

### Warehouse
| Phase | Obsah | Stav |
|---|---|---|
| 1 | DB + produkty + přehled (V18, V21) | ✅ |
| 2 | Dodavatelé RUD + normalizace (V30) | ✅ |
| 3 | AI import PDF + import položek na zakázku (V27–V29) | ✅ |
| 4 | Přestavba importu (2.1): V39 draft schéma | ✅ |
| 4 | Přestavba importu (2.1): extrakce v2, workflow, UI, identita produktu, ruční příjemka, dedup DL | ✅ |
| 5 | Rozvoj skladu E1–E4 (2.2): jednotky a měna, ruční pohyby, ocenění zásob (V42), storno příjemky (V43) | ✅ |
| 6 | Rozvoj skladu E5a–E8 (2.2): vratka dodavateli, inventura (V44), ISDOC adaptér, komfortní funkce | ✅ |
| 7 | E5b: přijatý dobropis jako typ dokladu v draft pipeline (R-G) | ⏳ odloženo |

### Employee (nový modul) — ✅ hotovo (větev `feature/employee`)
Evidence mechaniků s hodinovou sazbou (náklad práce), datem nástupu/odchodu, soft-delete.
Realizace se **odklonila od původního náčrtu** `orders.mechanic_id`: mechanik se přiřazuje
k **položce** zakázky typu LABOR (`order_items.employee_id`, D-1) — na jednom autě dělá víc lidí.
Sazba se při přiřazení **snímkuje** do `order_items.purchase_price` (D-3, historická přesnost);
`user_id` je nullable (zaměstnanec ≠ login, D-5). Struktura: `employee.employees` (V58) +
`order_items.employee_id` + CHECK jen u LABOR (V59). Správa `/employees` jen ADMIN/MANAGER (D-7).
Detail: [docs/funkce/zamestnanci.md](funkce/zamestnanci.md), plán [docs/plan-employee.md](plan-employee.md).
**Odemklo marži práce na dashboardu** (materiál + práce, tento vs. minulý měsíc).

## 5. Výukové milníky

| Milník | Nové koncepty |
|---|---|
| Order Phase 2 (stavové přechody) | enum state machine (vzor: `InvoiceStatus.canTransitionTo`) |
| Vehicle Phase 2 (číselník) | `<association>` v MyBatis, data migration strategie |
| Testy (TD-14) | rozšíření Testcontainers pokrytí, Mockito unit testy |
| Zákaznický portál | method-level security, ownership checks, DTO per role |

## 6. Archiv uzavřených rozhodnutí

| Rozhodnutí | Výsledek | Kdy |
|---|---|---|
| UUID vs BIGSERIAL | BIGSERIAL — výkon a jednoduchost | early |
| JPA vs MyBatis | MyBatis — plná kontrola nad SQL (výukový cíl) | early |
| Jedno schéma vs multi-schema | multi-schema — modul = schéma | early |
| Thymeleaf vs React | React SPA | 2026-04 |
| Access token: header vs cookie | HTTP-only cookie (XSS odolnost) | 2026-02 |
| VIN validace | regex `^[A-HJ-NPR-Z0-9]{17}$` | V5 |
| SPZ unikátní? | ne — přenosy značek, NULL povoleno | V5 |
| Brand/model FK hned vs text | text Phase 1, FK Phase 2 | V5 |
| `is_active` primitive vs wrapper | wrapper `Boolean` (COALESCE v XML nefunguje s false) | Vehicle Phase 1 |
| Faktura ↔ zakázka | 1:1; dělená fakturace = více zakázek | billing |
| PDF knihovna | openhtmltopdf + Thymeleaf (původně zvažován iText) | billing Phase 3 |
| Dodavatel: ruční Create? | ne — vzniká jen importem faktury (jediná brána) | 2026-07 |
| Extrakce vs výpočet u AI importu | „AI čte, kód počítá" | 2026-07 |
| Redukce order_item_type | 6 → 3 hodnoty (LABOR/MATERIAL/OTHER_SERVICES) | V24 |
