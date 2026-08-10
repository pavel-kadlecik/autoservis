# Datum vystavení faktury

*Funkční dokument (co + proč). Změněno 2026-08-07 na výslovné rozhodnutí uživatele: na faktuře má být datum, které obsluha zadá — dosavadní razítko dneškem (audit KN-10) se ruší. 2026-08-09 padl i zákaz budoucího data — hodnota je zcela bez omezení.*

## Co funkce dělá

Datum vystavení faktury **volí obsluha** a aplikace ho nepřepisuje:

1. **Při zakládání konceptu** (modal „Vytvoření faktury" v zakázce) se pole předvyplní dneškem a obsluha ho může změnit.
2. **Při vystavení** (dialog „Vystavení faktury") se pole předvyplní datem z konceptu a obsluha ho může upravit naposledy. **S tímto datem doklad odejde.**

Změna data v dialogu vystavení **přenačte návrh čísla faktury**, protože číslo se podle masky skládá z období data (`{RRRR}{MM}{NNN}`). Číslo a datum tak vždy pocházejí z téhož období.

**Hodnota data není ničím omezená** — zpětné datování i budoucí datum jsou povolené (budoucí od 2026-08-09, dřív 422 `ISSUE_DATE_IN_FUTURE` a `max` = dnešek ve formulářích).

## Proč se razítko zrušilo

Do 2026-08-07 platilo pravidlo z auditu KN-10: `POST /invoices/{id}/issue` přepsal `issueDate` dneškem. Mělo to jeden dobrý důvod — aby se **číslo a datum nerozešly o období** (koncept z prosince vystavený v lednu by měl lednové číslo a prosincové datum).

Cena za to ale byla, že **pole „Datum vystavení" v modalu bylo naoko**: obsluha ho vyplnila, formulář ho odeslal, uloženo bylo — a při vystavení se zahodilo. Aplikace tím tiše ignorovala vstup, o který sama požádala.

Původní důvod razítka řeší nově **sladění číselné řady s datem**, ne přepis data:

| | Dřív (razítko KN-10) | Nyní |
|---|---|---|
| Datum na faktuře | dnešek při kliknutí na Vystavit | to, co obsluha potvrdí v dialogu |
| Návrh čísla | `GET /invoices/next-number` bez parametru → dnešek | `?issueDate=<zvolené datum>` |
| Zámek řady | `lockNumberSeriesFor(dnešek)` | `lockNumberSeriesFor(zvolené datum)` |
| Číslo vs. datum | z téhož data (dnešek) | z téhož data (volba obsluhy) |

Nerozejití čísla a data tedy platí dál — drží ho ale společný *zdroj* data, ne přepis.

## Klíčová rozhodnutí a proč

| Rozhodnutí | Proč |
|---|---|
| Datum jde upravit **v dialogu vystavení**, ne v editaci konceptu (`PUT`) | koncept může ležet týdny; kdyby datum šlo zadat jen při zakládání, vystavil by se se starým datem a nešlo by to nikde opravit. Dialog vystavení je poslední okamžik, kdy datum ještě něco znamená, a obsluha ho tam vidí spolu s číslem, které z něj vychází. `UpdateRequest` zůstává bez `issueDate` — jedno pole, jedno místo |
| Budoucí datum povolené (2026-08-09, ruší zákaz z 2026-08-07) | původní obava — doklad vystavený dopředu nese číslo z řady období, které ještě nenastalo, a může se rozejít s pořadím i přiznáním — trvá, ale je to riziko stejného druhu jako u zpětného datování, které povolené bylo od začátku. Rozhodnutí uživatele: obě hranice drží obsluha, ne aplikace |
| Zpětné datování povolené | leželý koncept je legitimní důvod; dřívější zákaz (rozhodnutí 2026-07-30) byl důsledkem razítka, ne samostatné úvahy. Odpovědnost za volbu nese obsluha — aplikace ji na riziko upozorňuje v nápovědě, ale nebrání jí |
| Změna data přenačte návrh čísla (i ručně přepsaný) | nechat u červencového data srpnové číslo je přesně ta rozejitá dvojice, které se celá funkce vyhýbá |
| Splatnost se posouvá dál | když zvolené datum vystavení předběhne splatnost konceptu, posune se splatnost o **původní lhůtu** (např. „14 dní") — jinak by doklad narazil na CHECK `chk_due_date`. Beze změny oproti razítku, jen se počítá od zvoleného data |
| DUZP se nemění | datum zdanitelného plnění je fakt okamžiku plnění (§21 ZDPH), ne vystavení; být dřív než vystavení má u něj legitimní důvod |
| Bez migrace | `issue_date DATE NOT NULL DEFAULT CURRENT_DATE` i CHECK `chk_due_date` platí beze změny — mění se jen to, kdo hodnotu určuje |

## Hranice funkce (zatím ne)

- **Řada nemusí být chronologická vůči pořadí vystavení.** Zpětně datovaný doklad dostane číslo ze svého období, takže v srpnu vystavená červencová faktura má nižší číslo než dřív vystavená srpnová. Cena za volbu data; viz `cislovani-faktur.md`.
- **Aplikace nehlídá už uzavřené účetní období.** Nemá o podaných přiznáních k DPH žádnou informaci; hlídat by to znamenalo evidovat uzávěrky. Zatím je to na obsluze.
- **Datum konceptu nejde změnit mimo dialog vystavení.** Kdo chce jiné datum dřív, smaže koncept a založí ho znovu — nebo ho prostě upraví až při vystavení.

## Mapa implementace

`InvoiceDto.CreateRequest.issueDate` a nově i **`InvoiceDto.IssueRequest.issueDate`** (`@NotNull`, jinak bez validace — `requireIssueDateNotInFuture` zrušena 2026-08-09) → `InvoiceServiceImpl`: `stampNumberAndIssue` bere datum z requestu a předává ho do `lockNumberSeriesFor`, `suggestNextNumber(issueDate)` beze změny → `InvoiceMapper.issueWithNumber` (beze změny) → FE `InvoiceCreateFormModal` (`RequiredMark` u povinných polí; `max` = dnešek odstraněno 2026-08-09), `InvoiceIssueModal` (pole „Datum vystavení", přenačtení návrhu čísla přes `?issueDate=`), `InvoiceTable` a `InvoicesPageDetail` (posílají `issueDate` do `POST /invoices/{id}/issue`). Testy: `InvoiceLifecycleTest` (`issue_keepsIssueDateFromRequest`, `issue_acceptsFutureIssueDate`, `createFromOrder_acceptsFutureIssueDate`, `issue_shiftsOnlyDueDateThatWouldFallIntoThePast`, `invoiceNumber_assignedAtIssue`), helper `InvoiceIssuing`. Nápověda: `help/faktury.md`. Souvislosti: `cislovani-faktur.md`.
