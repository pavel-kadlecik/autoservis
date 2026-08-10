# Číslování faktur podle masky

*Funkční dokument (co + proč). Zavedeno 2026-08-02 (migrace V71), téhož dne přepracováno: číslo se přiděluje až při **vystavení**, aby v řadě nevznikaly mezery (rozhodnutí uživatele — „číslování nemá mít díru, a to ani v případě zrušení faktury").*

## Co funkce dělá

Číslo faktury vzniká **při vystavení dokladu**; koncept číslo nemá. Obsluha ho vidí a může upravit v dialogu „Vystavení faktury". Jak se skládá, řídí **Fakturační údaje** (menu Nastavení):

- **Přepínač „Generovat číslo faktury podle masky"** (`company_profile.invoice_number_auto`) řídí **předvyplnění pole**:
  - **zapnuto** — dialog předvyplní návrh dalšího čísla řady (`GET /invoices/next-number`),
  - **vypnuto** — pole zůstane prázdné.

  V obou režimech lze vystavit s **libovolným** číslem: maska je jen předpis pro generování návrhu, ne validační pravidlo (rozhodnutí uživatele 2026-08-02, zpřísnění à la Fakturoid odmítnuto). Hlídá se neprázdnost, unikátnost a délka ≤ 20.
- **Maska** (`invoice_number_mask`, tokeny `{RRRR}` `{RR}` `{MM}` `{N…}`, zbytek literály) — s legendou a živým náhledem přímo ve formuláři. Default `{RRRR}{MM}{NNN}` odpovídá historickému formátu `YYYYMM###`, takže po migraci řada plynule navazuje.
- **Variabilní symbol** se zadává v témže dialogu (jen číslice, max. 10; jde do QR platby jako `X-VS`). Dialog ho předvyplní číslicemi z čísla (`163/26` → `16326`, česká konvence „VS = číslo faktury"; nad 10 číslic nechá pole prázdné, protože zkrácený symbol by nedosedl na žádnou platbu). Server sám nedosazuje nic — prázdné pole znamená doklad bez VS, což u hotovostní faktury dává smysl.

## Proč až při vystavení

Původní verze V71 dávala číslo už konceptu. Tři důsledky, kvůli kterým se to týž den obrátilo:

1. **Zrušený koncept spálil číslo.** `uq_invoice_number` je plný unikát, takže stornovaný koncept si číslo držel navždy a v řadě zůstala mezera, kterou nešlo nijak vysvětlit (doklad s tím číslem nikdy neexistoval). Koncept bez čísla nemá co spálit.
2. **Řada neběžela podle data vystavení.** Číslo se přidělovalo v pořadí *zakládání* konceptů, ale právně má řada běžet v pořadí *vystavení*. Dva souběžné koncepty vystavené v opačném pořadí daly vzestupná čísla s klesajícími daty.
3. **Číslo a datum se mohly rozejít o období.** Číslo se skládalo z data zadaného při založení, jenže vystavení `issue_date` tehdy přerazítkovávalo na dnešek (KN-10). Koncept z prosince vystavený v lednu tak nesl číslo loňské řady. Přidělením při vystavení vychází obojí z téhož data — od 2026-08-07, kdy razítko padlo, je tím datem to, které obsluha potvrdí v dialogu vystavení (viz `datum-vystaveni-faktury.md`).

Zákon souvislost řady nepředepisuje, ale mezera je pro správce daně signál k dotazu — a když ji lze konstrukcí vyloučit, není důvod ji vyrábět.

## Klíčová rozhodnutí a proč

| Rozhodnutí | Proč |
|---|---|
| Generátor v aplikaci (`DocumentNumberMask`, do V92 `InvoiceNumberMask`), ne v DB triggeru | masku nelze rozumně parsovat v plpgsql a editovatelné předvyplnění stejně vyžaduje aplikaci; **výjimka** z konvence „čísla dokladů řeší DB triggery" (od V92 sdílí i řada PPD; řady ZNK/ZAK/OD na triggerech zůstávají) |
| Číslo až při vystavení | viz sekce výše — jediný stav, ve kterém číslo nemůže zaniknout ani se rozejít s datem dokladu |
| Pořadí = `MAX+1` přes regex z masky, žádný čítač ani sekvence | samoopravné: ruční číslo v mezích masky řadu posune (vystavím `18/26`, další návrh je `19/26`); čísla mimo masku řadu neovlivní — proto je bezpečné masku nevynucovat. DB sekvence by naopak při každém rollbacku číslo spálila |
| Poradní zámek nad řadou při vystavení | `pg_advisory_xact_lock` nad regexem masky pro dané období — dva souběžně vystavované doklady nedostanou totéž pořadí. Vzor: `fn_generate_credit_note_number` (V55), `fn_generate_cash_receipt_number` (V57) |
| **Přepínač řídí předvyplnění, ne to, jestli se aplikace ptá** | pole s číslem je v dialogu vystavení vždy a vždy editovatelné; „automat" znamená „předvypiš návrh", ne „rozhodni za obsluhu". Mezikrok, ve kterém zapnutý automat číslo dosadil mlčky a ruční zápis se přesunul za druhé tlačítko („Vystavit s vlastním číslem…"), byl vrácen (rozhodnutí uživatele 2026-08-02): přepsal význam nastavení, které už mělo ustálený smysl, a z jednoho pole udělal dvě obrazovky |
| Číslo je editovatelné i při zapnutém automatu | cena je vědomá — ruční číslo mimo řadu je po přesunu číslování na vystavení **jediný** zbylý způsob, jak mezeru vyrobit |
| Reset řady se odvozuje z masky | `{MM}` → měsíční, jinak rok → roční, bez data → nekonečná; žádné další nastavení (vzor Fakturoid) |
| Návrh čísla nic nerezervuje | souběh dvou uživatelů řeší zámek řady a v krajním případě `uq_invoice_number` → `DUPLICATE_INVOICE_NUMBER` a dialog si vyžádá čerstvý návrh |
| Integrita v DB, ne jen v aplikaci | `VARCHAR(20)`, CHECK neprázdnosti, CHECK „ISSUED/PAID má číslo", trigger neměnnosti po vystavení, VS `VARCHAR(10)` + CHECK číslic — garance zůstávají i po zrušení generátoru (viz `databaze.md` §5, V71). CHECK `chk_invoice_issued_has_number` je přesně pravidlo této funkce, takže změna nepotřebovala migraci |
| QR platba se rozhoduje podle stavu, ne podle čísla | `SpaydBuilder` blokuje QR pro `DRAFT` explicitně — návrh dokladu nemá vybízet k platbě |

## Hranice funkce (zatím ne)

- **Mezera po ručním čísle mimo řadu.** Vystavím-li `999/26`, řada pokračuje od `1000/26`. Vědomě přijaté: číslo zůstává editovatelné.
- **Mezera po zrušeném konceptu, který není poslední v pořadí.** Alokace `MAX+1` ji zavřít neumí; vyplňování mezer by rozbilo chronologii, což je horší.
- **Mezera po smazané faktuře** (V88) se nezavře sama ze stejného důvodu. Od V89 na ni ale upozorní hláška nad seznamem faktur s přesnými čísly — viz `api.md` §Faktury. Zavírá se ručně: číslo je při vystavení editovatelné.
- Jedna číselná řada (jedna maska) — víc souběžných řad à la POHODA by znamenalo tabulku řad; koncept se na to dá povýšit beze změny principu.
- Zpětně datovaný doklad dostane číslo z řady **svého** období, ne z aktuální — vystavím-li v srpnu doklad k červenci, číslo bude červencové. Řada tím může přestat být chronologická vůči pořadí vystavení; je to cena za to, že datum volí obsluha (rozhodnutí uživatele 2026-08-07, viz `datum-vystaveni-faktury.md`).
- Dobropisy a PPD mají své řady na DB triggerech beze změny.

## Mapa implementace

DB `V71__add_invoice_number_mask_and_constraints.sql` (beze změny — nové chování migraci nepotřebuje) → doména `CompanyProfile`, `Invoice` → `DocumentNumberMask` (parser/format/regex, čistá třída; od V92 sdílená s pokladními doklady) → `InvoiceServiceImpl` (`suggestNextNumber`, `stampNumberAndIssue`, `lockNumberSeriesFor`, `requireUsableInvoiceNumber`) → `InvoiceMapper.issueWithNumber` + `lockNumberSeries` → `InvoiceController` (`GET /invoices/next-number`, `POST /invoices/{id}/issue` s tělem) → FE `CompanyProfilePage` (přepínač + maska + legenda + náhled), `InvoiceTable` a `InvoicesPageDetail` (akce „Vystavit" otevře dialog), `InvoiceIssueModal` (návrh čísla podle zvoleného data vystavení, odvození VS). Testy: `DocumentNumberMaskTest`, `DatabaseTriggerTest` (constrainty V71), `CompanyProfileServiceTest`, `InvoiceLifecycleTest` (`invoiceNumber_assignedAtIssue`, `cancelledDraft_doesNotConsumeNumber`, `issue_storesVariableSymbolFromRequest`, `issue_withoutNumber_isRejected`, `issue_acceptsNumberOutsideMask`, `issue_rejectsDuplicateNumber`). Nápověda: `help/nastaveni-firmy.md`, `help/faktury.md`. Datum, ze kterého se číslo skládá: `datum-vystaveni-faktury.md`.
