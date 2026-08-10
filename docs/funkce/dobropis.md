# Funkce: Opravný daňový doklad (dobropis)

> Funkční dokument — **co** funkce dělá a **proč** je postavená takhle.
> Schéma: [databaze.md §5](../databaze.md) (`billing.credit_notes`, V55 + V66) ·
> endpointy: [api.md](../api.md) · PDF: `templates/pdf/credit-note.html`.
> Uživatelská nápověda: v aplikaci článek **Opravný daňový doklad** (`frontend/…/src/help/dobropis.md`).

## Co funkce dělá

Umožňuje opravit **vystavenou** fakturu, aniž by se rušila. K faktuře ve stavu `ISSUED` nebo `PAID`
se založí koncept opravného dokladu, obsluha doplní důvod opravy, zkontroluje rozdílové částky
a doklad vystaví — tím dostane evidenční číslo řady `OD` a stává se neměnným.

Rozsah je **plný dobropis** (vrací celou fakturu). Částečný je odložený jako TD-62.

## Proč vůbec — a proč ne storno

Fakturu, kterou zákazník fyzicky dostal a která je vykázaná v přiznání k DPH, nelze zrušit
a vystavit znovu. §42 a §45 zákona o DPH na opravu předepisují opravný daňový doklad. Storno
proto patří jen ke **konceptu**, který nikam neodešel a nemá číslo.

**Tohle byla dlouho jen teorie.** Backend, PDF i testy vznikly už v auditu 2026-07-24 (E5, R-7),
ale nikdy nedostaly frontend — a nápověda mezitím obsluze radila přesný opak: „vystavenou fakturu
nelze opravit přímo, stornujte ji a vystavte znovu". Funkce tedy existovala v kódu a neexistovala
pro uživatele. Napravil to audit 2026-07-30 (nález KN-1).

## Klíčová rozhodnutí a proč

| Rozhodnutí | Proč |
|---|---|
| **Samostatná tabulka `credit_notes`**, ne discriminator na `invoices` (R-7) | izoluje dobropis od fakturačního toku — nemíchá se do `uq_invoices_order_active`, stavového automatu ani number triggeru faktur; nižší riziko regrese |
| **Vlastní řada `OD{YYYYMM}###`**, přidělená až při vystavení | nezaměnitelná s fakturami; koncept číslo nemá, takže se nedá splést s platným dokladem (vzor faktury po V49) |
| **Rozdíly a strany se neukládají** — odvozují se z původní faktury | strany z `invoice_party` jsou právně správné (stav k datu původního dokladu), rozdíly = záporné souhrny z views. Žádné duplicitní snapshoty, které by se mohly rozejít |
| **Nejvýš jeden aktivní dobropis na fakturu** (V66, audit KN-8) | každý nese celou zápornou fakturu, takže druhý = dvojnásobné snížení daně na výstupu. Vynucuje částečný unikát + guard v service kvůli srozumitelné hlášce |
| **Důvod opravy se vyžaduje dialogem, nedosazuje se** | §45 ho předepisuje jako náležitost a tiskne se na doklad; předvyplněný text by byl formálně vyplněná, věcně prázdná položka |
| **Dobropis nemá vlastní seznam** — chodí se na něj z faktury | vždy patří k jedné faktuře; samostatný seznam by byl pátý způsob, jak najít totéž. Detail faktury proto načítá `GET /credit-notes?invoiceId=` a tlačítko vede buď na založení, nebo na existující doklad |
| **Zakládá se koncept, nevystavuje se rovnou** | vystavení je nevratné (přidělí číslo) — obsluha musí mít možnost zkontrolovat rozdíly dřív |
| **Koncept lze smazat** (2026-08-02) | bez toho byla omylem založená oprava slepou uličkou: vystavit ji obsluha nechce (byl by to platný doklad se špatným důvodem), zahodit nemohla, a nový dobropis k téže faktuře už založit nešlo — `INVOICE_ALREADY_CREDITED` počítá i s koncepty. Východiskem byl jen ruční zásah do DB. Mazání je výjimka z R-06 se stejným zdůvodněním jako u konceptu faktury: doklad bez čísla, který nikam neodešel (`konvence.md` §18) |
| **Vystavený dobropis uvolní zakázku pro novou fakturu** (V69) | Bez toho byl dobropis slepá ulička: Vlna 2 zamkla storno vystavené faktury (§42/§45), ale dobropis stav faktury nemění, takže zakázka zůstala zamčená **navždy** — ekonomicky ji dobropis vynuloval, ale doklad na správnou částku už k ní vystavit nešel. Řeší sloupec `invoices.credited_at` a částečný unikát, který dobropisované faktury nepočítá. Uvolní se i editace položek zakázky — oprava položek je typicky důvod, proč se dobropisuje. |

## Chování při chybách

- Faktura ve stavu `DRAFT` nebo `CANCELLED` → **422** `INVOICE_NOT_CORRECTABLE`.
- K faktuře už aktivní dobropis existuje → **422** `INVOICE_ALREADY_CREDITED` (hláška uvádí jeho číslo,
  nebo že jde zatím o koncept).
- Vystavení dokladu, který už není koncept → **422** `INVALID_STATUS_TRANSITION`;
  souběžná změna → **409** `CREDIT_NOTE_STATE_CHANGED`.
- Celý modul je vyhrazen vedení (`ADMIN`/`MANAGER`) — mechanik dostane **403**.

## Co funkce zatím neumí

- **Částečný dobropis** (podmnožina položek nebo vlastní částky) — TD-62.
- **Storno vystaveného dobropisu** — endpoint neexistuje. Částečný unikát (V66) je na něj
  připravený: stornovaný doklad by neblokoval vystavení nového. (Koncept se od 2026-08-02
  **maže** — `DELETE /credit-notes/{id}`, viz níže.)
- **Vrácení peněz** — dobropis je jen doklad; pohyb peněz aplikace neeviduje.

## Kde je dobropis vidět

Faktura si po dobropisování ponechá stav `ISSUED`/`PAID` (je pořád platným dokladem), takže by
v seznamech splynula s běžnou pohledávkou. Rozlišuje ji proto **druhý odznak „Dobropisována"**
vedle stavu — v seznamu faktur i ve fakturách zákazníka, s datem vystavení opravného dokladu
v tooltipu. Tón je `secondary`, ne `danger`: dobropisovaná faktura není stornovaná ani chybná
a splývat se stornem by byla přesně ta záměna, které se vyhýbáme. Na detailu faktury zůstává
vysvětlující hláška a odkaz na doklad.

## Promítnutí do přehledu (audit KN-20)

**Vystavený** dobropis se projeví na dashboardu:

- faktura **zmizí z pohledávek po splatnosti** — jinak by obsluha urgovala zákazníka, kterému
  už peníze vrátila,
- **tržba měsíce se sníží** o celou částku dobropisované faktury, a to v měsíci **vystavení
  dobropisu** (rozhodnutí uživatele 2026-07-30) — neupravuje se tím zpětně už uzavřený měsíc.
  Platí to i pro měsíční řadu v modalu **Statistika** — do V69 ji dobropis míjel a modal tvrdil
  za týž měsíc jiné číslo než dlaždice,
- **marže dobropisované zakázky se nepočítá** — nese ji až nová faktura. Bez toho by se po
  refakturaci započítaly položky zakázky dvakrát (zakázka má nově dvě nestornované faktury).

**Koncept se nepočítá.** Nemá evidenční číslo a není daňovým dokladem, takže pohledávka do
vystavení právně trvá. (Pozor na záměnu: unikát `uq_credit_notes_original_active` zahrnuje
i koncepty, ale řeší něco jiného — brání vzniku druhého dobropisu.)

Částka se bere z původní faktury, protože MVP je plný dobropis. Až přibude **částečný**
(TD-62), bude potřeba doplnit jeho vlastní částku i sem.
