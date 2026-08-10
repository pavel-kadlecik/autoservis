# ROZVOJOVA-MAPA.md — Autoservis: Směr vývoje a otevřená rozhodnutí

> Strategický dokument — kam projekt směřuje, co nebylo rozhodnuto, co si zaslouží zamyšlení.
> Aktualizovat po každém uzavřeném rozhodnutí nebo dokončeném milníku.
> Poslední aktualizace: Warehouse modul — přehled skladu + CRUD produktů hotové; vozidla mají historii km.

---

## 1. Kde právě jsme

| Milník | Stav |
|---|---|
| Security (JWT, refresh tokeny, blacklist) | ✅ hotovo end-to-end |
| Customer modul (DB + Java + frontend) | ✅ hotovo, několik otevřených TD |
| Vehicle modul Phase 1 (DB + Java + frontend) | ✅ hotovo end-to-end |
| Vehicle — historie km (Phase 3) | ✅ hotovo end-to-end (V20) |
| Order modul Phase 1 (vč. položek zakázky) | ✅ DB + Java + frontend |
| Warehouse — přehled skladu + CRUD produktů | ✅ hotovo end-to-end (V18, V21) |
| Warehouse — dodavatelé, příjemky, PDF import | 🔄 rozpracováno |
| Vehicle Phase 2 (číselník značek/modelů) | ⏳ plánováno |

---

## 2. Otevřená rozhodnutí — čekají na odpověď

### ROZH-001 ✅ — Co dál po opravě frontend bugů?
**Rozhodnuto:** Order modul. Zakázky se rozpracovávají.

---

### ROZH-002 — Zákaznický portál vs. jen admin rozhraní?

Aktuálně: aplikace je čistě pro zaměstnance servisu. Pokud přibude zákaznický přístup — nové route `/portal/*`, method-level security, dvě DTO (zákazník nesmí vidět `internal_note`).

**→ Status: K zamyšlení. Neblokuje aktuální vývoj.**

---

### ROZH-003 ✅ — Čísla zakázek: formát
**Rozhodnuto:** Prefix `ZAK-`, trigger V11, sekvence `order.order_number_seq` (CACHE 1). Formát: `ZAK-{rok}-{4ciferné seq}`.

---

### ROZH-004 — Zvážit použití `internal_note` u zakázek

Tabulka `order.orders` má dva textové sloupce:
- `description` — popis zakázky (co zákazník požaduje)
- `internal_note` — interní poznámka mechanika

Otázka: je `internal_note` potřebná když už existuje `description`?

**Argumenty PRO zachování obou:**
- Jasné oddělení pohledu zákazníka vs. mechanika
- Zákaznický portál — zákazník vidí `description`, ne `internal_note`

**Argumenty PROTI:**
- Dva textové sloupce mohou být matoucí
- Zaměstnanci neví kam psát co

**→ Status: K zamyšlení. Rozhodnout před implementací vytváření zakázky.**

---

### ROZH-006 — Audit log změn v databázi

Zamyslet se nad kompletním audit logem pro celou databázi — zejména pro `order.order_items`.

Příklad use case: zákazník se přijde hádat o cenu — servis dokáže ukázat historii změn položek.

**Možné přístupy:**
- Varianta A: dedikovaná tabulka `audit_log` (kdy, kdo, který sloupec, stará/nová hodnota)
- Varianta B: PostgreSQL triggery které automaticky zapisují změny
- Varianta C: pouze `updated_by` — víme kdo změnil, ale ne co

**→ Status: K zamyšlení. Neblokuje aktuální vývoj. Řešit před nasazením do produkce.**

---

Přidat sloupec `order_type` s hodnotami: `SERVIS`, `PNEUSERVIS`, `REKLAMACE`, `PRAVIDELNY_SERVIS`, `OPRAVA`, `DIAGNOSTIKA`, `JINE`. Nahradí zkrácený `description` v tabulce. Vyžaduje novou migraci.

**→ Status: Plánováno. Provést po dokončení Order Phase 1.**

---

## 3. Roadmap Vehicle modulu

| Phase | Obsah | Migrace | Stav |
|---|---|---|---|
| 1 | Tabulka vehicles, ENUMy, trigger, end-to-end Java + frontend | V5, V7, V8 | ✅ |
| 2 | Číselník `brands` + `models`, data migration ze stringů na FK | V? | ⏳ |
| 3 | Historie km — `mileage_history` | V20 | ✅ |
| 4 | Doklady — STK, EK, pojištění (`vehicle_inspections`) | V? | ⏳ |
| 5 | Historie vlastnictví (`ownership_history`) | V? | ⏳ |

---

## 4. Roadmap Order modulu

| Phase | Obsah | Migrace | Stav |
|---|---|---|---|
| 1 | Schema + tabulka `orders`, ENUM `order_status`, trigger | V6 | ✅ |
| 1 | Seed data — vozidla + zakázky | V8 | ✅ |
| 1 | Java vrstva: Mapper, Service, Controller, DTO | — | ✅ |
| 1 | Frontend: seznam zakázek | — | 🔄 rozpracováno |
| 1 | Frontend: detail, vytvoření zakázky | — | ⏳ |
| 2 | Stavové přechody (RECEIVED → COMPLETED) s validací | V? | ⏳ |
| 2 | ENUM `order_type` — typ zakázky | V? | ⏳ |
| 3 | Položky zakázky (`order_items`) — práce + materiál | V12 | ⏳ |
| 4 | Modul fakturace — faktury zákazníkovi (`billing.invoices`) | V13 | ⏳ |
| 5 | Import faktur od dodavatelů (PDF + Claude API) | V? | ⏳ |

---

## 5. Roadmap modulu BILLING (Fakturace)

### Workflow fakturace v autoservisu

```
1. Přijde zakázka
2. Objedná se materiál / díly od dodavatele (faktura dodavatele)
3. Mechanik pracuje na vozidle
4. Dokončení opravy
5. Sestavení faktury zákazníkovi:
       - položky práce  (hodiny × sazba)
       - položky materiálu (díly + marže)
6. Vystavení faktury zákazníkovi (PDF)
```

### Navrhovaná databázová struktura

```
order.orders
    └── order.order_items            ← položky zakázky
            ├── typ: LABOR               ← práce (hodiny × sazba)
            └── typ: MATERIAL            ← díl/materiál (nákupní cena interní)

billing.invoices                     ← faktura zákazníkovi
    └── billing.invoice_items        ← řádky faktury (prodejní cena)

billing.supplier_invoices            ← faktury od dodavatelů
    └── billing.supplier_invoice_items  ← importované díly
```

### Klíčová rozhodnutí

- `order_items` = zdrojová pravda (co bylo uděláno a použito)
- `invoice_items` = co se fakturuje zákazníkovi
- Nákupní cena dílu je **interní** — zákazník vidí pouze prodejní cenu
- `final_price` na zakázce = automaticky `SUM(order_items)` × prodejní ceny
- `estimated_price` zůstane jako ruční orientační odhad

### Způsoby zadání položek

| Způsob | Popis |
|---|---|
| Manuální | Mechanik zadá práci a materiál ručně |
| Import PDF | PDF faktura dodavatele → Claude API extrakce → položky |

### PDF generování faktur zákazníkovi

- Knihovna: **iText** (AGPL — firma používá interně = zdarma)
- Faktura obsahuje: hlavičku servisu, zákazníka, položky, DPH, QR kód pro platbu

### Import faktur od dodavatelů

- Claude API s few-shot promptingem (vzorové faktury jednotlivých dodavatelů)
- Každý dodavatel má uloženou vzorovou fakturu pro vyšší přesnost extrakce
- Mechanik zkontroluje a potvrdí extrahované položky
- Síťový přístup: pouze `api.anthropic.com:443` — vše ostatní blokuje firewall

### Roadmap

| Phase | Obsah | Migrace | Stav |
|---|---|---|---|
| 1 | Schema `order`, tabulka `order_items` (LABOR + MATERIAL) | V12 | ⏳ |
| 2 | Schema `billing`, tabulky `invoices` + `invoice_items` | V13 | ⏳ |
| 3 | PDF generování faktur zákazníkovi (iText) | — | ⏳ |
| 4 | Import faktur od dodavatelů (Claude API) | — | ⏳ |
| 5 | Vzorové faktury dodavatelů — few-shot prompting | — | ⏳ |

---

## 6. Roadmap modulu WAREHOUSE (sklad)

| Phase | Obsah | Migrace | Stav |
|---|---|---|---|
| 1 | DB: suppliers, products, goods_receipts, goods_receipt_items, stock_movements, trigger pohybů | V18 | ✅ |
| 1 | Přehled skladu + detail produktu (Java + frontend) | — | ✅ |
| 1 | Katalogová pole produktu + CRUD + hlídání nízké zásoby | V21 | ✅ |
| 2 | Dodavatelé — CRUD | V? | ⏳ |
| 2 | Příjemky — workflow potvrzení/zamítnutí | — | ⏳ |
| 3 | Import faktur dodavatele z PDF (Spring AI / Claude) | — | 🔄 |
| 3 | Import položek faktury na zakázku (řádek + ISSUE pohyb) | — | ⏳ |

---

## 8. Roadmap modulu EMPLOYEE (Zaměstnanci)

### Důvod vzniku

Mechanik má hodinovou sazbu uloženou v systému. Při přiřazení mechanika k zakázce se jeho sazba automaticky předvyplní jako `purchase_price` u `LABOR` položek.

### Navrhovaná struktura

```
employee.employees
    id, user_id (FK → security.users)
    hourly_rate         ← hodinová sazba mechanika (interní náklad)
    position            ← pozice: MECHANIC, SENIOR_MECHANIC...
    is_active

order.orders
    mechanic_id         ← FK → employee.employees (který mechanik dělá na vozidle)

order.order_items (LABOR)
    purchase_price      ← automaticky předvyplněno z mechanic.hourly_rate
```

### Dopad na stávající systém

- Nová tabulka `employee.employees`
- Nový sloupec `mechanic_id` v `order.orders` (migrace)
- Service logika: při vytvoření `LABOR` položky načíst sazbu mechanika zakázky

### Roadmap

| Phase | Obsah | Migrace | Stav |
|---|---|---|---|
| 1 | Schema `employee`, tabulka `employees`, hodinová sazba | V? | ⏳ |
| 2 | Sloupec `mechanic_id` v `order.orders` | V? | ⏳ |
| 3 | Automatické předvyplnění `purchase_price` z hodinové sazby | — | ⏳ |

**→ Status: Plánováno. Řešit po dokončení modulu fakturace.**

---

## 9. Výukové milníky — co se naučíme kdy

| Milník | Nové koncepty |
|---|---|
| Order Phase 1 frontend | Stránkování, filtrování, stavové barvy |
| Order Phase 2 (stavové přechody) | Enum state machine, validace povolených přechodů |
| Customer Phase 2 (PUT, validace) | MapStruct pro update, business validace v service |
| Vehicle Phase 2 (číselník) | `<association>` v MyBatis, data migration strategie |
| Testy | Spring Boot Test, Testcontainers, Flyway v testech |
| Zákaznický portál | Method-level security, ownership checks, rozdílná DTO per role |

---

## 10. Archiv uzavřených rozhodnutí

| Rozhodnutí | Výsledek | Kdy |
|---|---|---|
| UUID vs BIGSERIAL | BIGSERIAL — výkon a jednoduchost | Early |
| JPA vs MyBatis | MyBatis — výukový cíl: plná kontrola nad SQL | Early |
| Jedno schema vs multi-schema | Multi-schema — modul = schema | Early |
| Thymeleaf vs React | React — uživatel preferuje SPA | Duben 2026 |
| VIN validace: délka vs regex | Regex `^[A-HJ-NPR-Z0-9]{17}$` — produkční standard | V5 |
| SPZ unikátní? | Není — přenosy značek, NULL pro nezaregistrovaná | V5 |
| Brand/model jako FK hned vs volný text | Volný text Phase 1, FK Phase 2 | V5 |
| Access token: header vs cookie | HTTP-only cookie — bezpečnější (XSS odolné) | Únor 2026 |
| `is_active` primitive vs wrapper | Wrapper `Boolean` — COALESCE v XML nefunguje s false | Vehicle Phase 1 |
| Co dál po opravě bugů | Order modul (Varianta A) | Dnešní session |
