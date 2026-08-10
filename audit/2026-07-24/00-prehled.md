# Hloubkový audit projektu Autoservis — 2026-07-24

> **Auditovaný stav:** commit `409d3ad` (větev `audit-one`), Flyway V1–V44.
> **Rozsah:** celý backend (Java 21 / Spring Boot 4 / MyBatis / PostgreSQL), frontend (React 19),
> SQL vrstva, DB schéma, security, testy, dokumentace, věcná/procesní správnost domény.
> **Metoda:** 9 nezávislých hloubkových průchodů; každý četl dotčené soubory celé, ne jen grep.
> Klíčové nálezy hlavní auditor **ověřil druhým čtením přímo v kódu** (viz poznámky u nálezů).
> Historie se nemaže — audity jsou datované, tento je první.

## Jak číst tento audit

| Soubor | Oblast |
|---|---|
| [01-backend-jadro.md](01-backend-jadro.md) | Service/controller vrstva customer, vehicle, order, billing + průřez |
| [02-sklad.md](02-sklad.md) | Sklad, ledger, draft workflow, AI/ISDOC import |
| [03-sql-mybatis.md](03-sql-mybatis.md) | 21 XML mapperů, resultMapy, dynamické SQL |
| [04-databaze.md](04-databaze.md) | 44 migrací, constrainty, triggery, views, doménový návrh |
| [05-security.md](05-security.md) | JWT, cookies, autentizace/autorizace, konfigurace, secrets |
| [06-domena-procesy.md](06-domena-procesy.md) | Věcná a procesní správnost (provoz servisu, legislativa ČR) |
| [07-testy.md](07-testy.md) | Testovací suita, pokrytí, mezery |
| [08-dokumentace.md](08-dokumentace.md) | Soulad docs/ s kódem |
| [09-frontend.md](09-frontend.md) | React SPA — správnost, vzory, bezpečnost |
| [plan-oprav.md](plan-oprav.md) | **Detailní návrh oprav — co, jak, v jakém pořadí** |

## Celkové hodnocení

Projekt je na deklarovaný výukový cíl **výrazně nadprůměrný**: čistá vrstvená architektura, disciplinovaná konvence, mutačně doložená testovací suita (PIT 84 % killed), dokumentace v pozoruhodné shodě s kódem. Řemeslná úroveň jádra (stavový automat faktury, skladový ledger, souběhové guardy K5/K6, „AI čte, kód počítá") je solidní.

**Těžiště nálezů není v tom, jak je kód napsaný, ale v tom, co dělá na hranách procesů.** Nejzávažnější rizika:

1. **Fakturační workflow má slepou uličku a chybí mu opravný doklad** — storno faktury trvale zazdí zakázku (K-1), dobropis neexistuje (K-8), datum a číslo dokladu se fixují špatně (K-3).
2. **UPDATE zákazníka tiše zahazuje GDPR souhlas a přepisuje auditní timestamp** (K-4) — celá rodina „PATCH mapper vs. full-replace konvertor".
3. **Snapshot faktury je děravý** — VIN/značka/model se čtou živě, právní doklad se zpětně mění (K-5).
4. **Autorizace je plochá** a seed obsahuje zákaznické účty se sdíleným heslem, které vidí celou firmu (K-10); změna hesla neodhlásí útočníka (K-6).
5. **Integrita skladu i vazba zakázka–vozidlo stojí jen na aplikační vrstvě** — DB je nevynucuje (N-3, N-4, V-3).

Žádný nález nevyžaduje zásah do hotových migrací ani přestavbu — vše je řešitelné migrací V45+, doplněním guardů v service a sjednocením UPDATE mapperů.

## Statistika nálezů

| Audit | KRIT/VYS | STŘ | NÍZ |
|---|---|---|---|
| Backend jádro | 3 | 13 | 7 |
| Sklad | 2 | 10 | 11 |
| SQL/MyBatis | 5 | 8 | 5 |
| Databáze | 5 | 8 | 12 |
| Security | 2 | 3 | 3 |
| Doména/procesy | 2 (věcné) + B5 | 5 věcných + mezery | — |
| Testy | 2 | 8 | 6 |
| Dokumentace | 1 | 14 | 16 |
| Frontend | 2 | 3 | 6 |

Řada nálezů se nezávisle objevila ve více auditech (různé pohledy na tutéž příčinu) — to je signál spolehlivosti, ne duplicita. Konsolidované do klíčových nálezů níže.

## Klíčové nálezy (konsolidace napříč audity)

| # | Nález | Severita | Nahlásily audity | Ověřeno |
|---|---|---|---|---|
| **K-1** | Stornovaná faktura trvale zablokuje fakturaci zakázky (plný `uq_invoices_order_id` + `findByOrderId` bez filtru stavu) | 🔴 VYSOKÝ | backend V-2, DB N-1, doména A1 | ✅ přímo |
| **K-2** | Poslednímu adminovi lze přes PUT odebrat roli ADMIN (guard jen v deactivate) | 🔴 VYSOKÝ | backend S-9, security N2 | ✅ přímo |
| **K-3** | Číslo + datum faktury se fixují podle `CURRENT_DATE` při INSERTu draftu | 🟠 VYSOKÝ | DB N-5, doména A4, backend N-4 | ✅ přímo |
| **K-4** | UPDATE zákazníka zahazuje `gdpr_consent`; `marketing_consent_at` přepsán při každém uložení | 🔴 VYSOKÝ | backend V-1, SQL №1+№2 | ✅ přímo |
| **K-5** | Snapshot faktury děravý — VIN/značka/model čteny živě, doklad se zpětně mění | 🟠 VYSOKÝ | DB N-2 | ✅ přímo |
| **K-6** | Změna/reset hesla neruší aktivní refresh tokeny (útočník zůstane přihlášen 7 dní) | 🔴 VYSOKÝ | security N1 | ✅ přímo |
| **K-7** | Refresh tokeny v DB v plaintextu (únik zálohy = použitelné tokeny) | 🟠 STŘEDNÍ | security N3 | ✅ přímo |
| **K-8** | Chybí opravný daňový doklad (dobropis); PAID je terminální — reklamaci po zaplacení nelze řešit | 🔴 VYSOKÝ (legislativa) | doména A2 | — návrhové |
| **K-9** | Evidence úhrad neexistuje (jen bit `PAID`) — žádné datum, částka, částečná úhrada, upomínky | 🔴 VYSOKÝ (proces) | doména B1 | ✅ přímo |
| **K-10** | Plochá autorizace + seed ROLE_CUSTOMER účty se sdíleným heslem vidí celou firmu | 🔴 VYSOKÝ (před produkcí) | doména B5, security TD-24 | ✅ přímo |
| **K-11** | Rodina „PATCH mapper vs. full-replace konvertor" — nullable pole zákazníka/zakázky/faktury nejdou vymazat | 🟠 STŘEDNÍ | backend S-1, SQL №3–5 | ✅ přímo |
| **K-12** | Vazba zakázka↔vozidlo↔zákazník není vynucená (lze fakturovat cizí vozidlo) | 🟠 STŘEDNÍ | backend V-3, doména A5 | ✅ přímo |
| **K-13** | Ledger append-only jen konvencí; vazba pohyb↔šarže↔produkt bez složeného FK | 🟠 STŘEDNÍ | DB N-3, N-4 | ✅ přímo |
| **K-14** | Inventurní přebytkové šarže neviditelné na kartě dílu (INNER JOIN na dodavatele) | 🟠 VYSOKÝ | sklad V-1, DB N-18 | ✅ přímo |
| **K-15** | JWT filtr a auth cookie flow nemají žádný e2e test | 🟠 VYSOKÝ (test) | testy V2 | ✅ přímo |
| **K-16** | api.md sekce Cookies hlásí neexistující bezpečnostní dluhy (kód opraven, docs ne) | 🟠 STŘEDNÍ (docs) | dokumentace V1 | ✅ přímo |
| **K-17** | Modaly přeskakují fokus při psaní do druhého+ pole (nestabilní `onClose` v deps efektu) | 🟠 VYSOKÝ (FE) | frontend F1 | ✅ přímo |
| **K-18** | `addAlert` bez importu v `OrderItemsWrapper` → ReferenceError při selhání reorderu | 🟠 VYSOKÝ (FE) | frontend F2 | ✅ přímo |

## Poznámka k metodě a spolehlivosti

- Audity běžely paralelně a nezávisle; shoda více auditorů na tomtéž nálezu z různých úhlů (kód × SQL × migrace × doména) zvyšuje důvěru.
- Hlavní auditor ověřil druhým čtením v kódu všechny nálezy severity VYSOKÝ+ a reprezentativní vzorek středních — sloupec „Ověřeno" výše. Nálezy K-8 a části domény jsou návrhové/legislativní (nejde je „ověřit v kódu", jen posoudit) — u nich je přiznána míra nejistoty přímo v [06-domena-procesy.md](06-domena-procesy.md).
- Dva agenti (backend jádro, sklad) běželi bez dostupného bezpečnostního klasifikátoru (`claude-sonnet-5[1m]` nedostupný) — jejich nálezy byly proto ověřeny obzvlášť pečlivě; žádný se neukázal jako nepodložený.
