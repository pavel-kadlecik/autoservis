# Rozhodnutí k auditu 2026-07-24

> Záznam rozhodnutí uživatele k otevřeným otázkám z [plan-oprav.md](plan-oprav.md)
> (sekce „Věci k rozhodnutí uživatele"). Každé rozhodnutí je zaznamenáno i s odůvodněním.
> Průběžně doplňováno, jak procházíme otázky jednu po druhé.

---

## R-1 · Sémantika storna faktury (P1.0, klíčový nález K-1)

**Otázka:** Po stornu faktury povolit vystavení nové faktury k téže zakázce (varianta a), nebo nechat storno terminálním koncem a položky po stornu neodemykat (varianta b)?

**Rozhodnutí:** **Varianta (a)** — povolit novou fakturu, existuje-li k zakázce jen stornovaná (CANCELLED) faktura.

**Odůvodnění:**
1. Odpovídá už existujícímu záměru kódu — oprava V2 záměrně odemyká položky zakázky po stornu faktury, což je jednoznačný signál, že návrh počítal s přefakturací. Varianta (a) myšlenku dotáhne; (b) by zahazovala napsanou práci.
2. Odpovídá reálnému provozu — oprava chybného draftu / nevystavené faktury je běžná a nemá jinou rozumnou cestu (dnešní jediné východisko = duplikace zakázky rozbije vazbu položek na skladové šarže).
3. Malý ohraničený zásah — jedna migrace V45 (výměna `uq_invoices_order_id` za částečný unikátní index `WHERE status <> 'CANCELLED'`) + změna podmínky v `InvoiceServiceImpl.createFromOrder` (ignorovat CANCELLED) + regresní test. Žádná přestavba.
4. Nekoliduje s legislativou — skutečné „po odeslání zákazníkovi" případy pokryje opravný daňový doklad (viz R-2/dobropis). Storno tak zůstane vyhrazené pro fakturu, která nikdy neopustila firmu (DRAFT nebo neodeslaná ISSUED).

**Dopad do plánu:** potvrzuje P1.1 ve variantě (a).

**Rozhodnuto:** 2026-07-24.

---

## R-2 · Opravný daňový doklad (dobropis) (P1.8, klíčový nález K-8)

**Otázka:** Kdy zařadit dobropis a jak velký rozsah hned pokrýt? (Že se dělat musí, je dané — bez něj není fakturace legislativně použitelná pro ostrý provoz.)

**Rozhodnutí:** Zařadit jako **samostatnou etapu hned po opravné Vlně 1 a Vlně 2**, rozdělenou na dva kroky:
1. Nejdřív odblokovat `PAID`/`ISSUED` směrem k opravě — rozšířit stavový automat o možnost navázat na fakturu opravný doklad (odemkne nejčastější reálný případ: reklamace po zaplacení).
2. Plný dobropis jako doklad (vlastní číselná řada / řada faktur, PDF, záporné částky, odkaz na původní fakturu, snapshoty stran) postavit vzorem podle stávající faktury (znovupoužít `invoice_party` snapshoty a číselné triggery).

**Odůvodnění:**
1. Legislativní blocker pro ostrý provoz (§42/§45 ZDPH — vystavený a odeslaný doklad nelze „zrušit a přepsat", jen opravit dobropisem), ne kosmetika → patří před „hezké mít" věci (dashboard, číselník značek).
2. Závislý na Vlně 1 — nemá smysl stavět dobropis nad workflow, které má ještě slepou uličku po stornu (R-1) a děravé snapshoty (K-5). Nejdřív srovnat základ, pak přistavět opravný doklad.
3. Rozdělení na dva kroky dodá hodnotu dřív (odemčení opravy zaplacené faktury je užitečné i před dokonalým dobropisovým PDF).

**Upozornění:** náležitosti opravného dokladu a volbu číslování (vlastní řada vs. řada faktur) ověřit s účetním — auditor není daňový poradce, legislativa je popsána rámcově.

**Dopad do plánu:** potvrzuje P1.8, povyšuje na „Billing Phase 6" v roadmapě; časově za Vlnu 1 a 2.

**Rozhodnuto:** 2026-07-24.

---

## R-3 · Evidence úhrad (klíčový nález K-9, doména B1)

**Otázka:** Jaký rozsah evidence úhrad a kdy? Dnes je „platba" jen bit `PAID` — bez data, částky, způsobu, bez přehledu po splatnosti.

**Rozhodnutí:** **Varianta (a) — minimální**, zařazená **hned po fakturačním workflow (Vlna 1), před plným dobropisem (R-2)**. Ke stavu `PAID` doplnit `paid_at`, skutečnou částku a způsob úhrady; přidat seznam/filtr „faktury po splatnosti" (porovnání `dueDate` s dneškem u nezaplacených). Plná evidence úhrad jako samostatná tabulka 1:N (varianta b — částečné úhrady, přeplatky) odložena jako pozdější rozšíření.

**Odůvodnění:**
1. Nejlepší poměr přínos/náklad z celého plánu — tři sloupce k faktuře + jeden filtrovaný seznam (řádově dny) okamžitě odemknou „kdo mi dluží", což je denní provozní potřeba.
2. Nedělat rovnou (b) je vědomá skromnost — u malého servisu je většina faktur „zaplaceno naráz"; plná 1:N evidence je předčasná složitost. Varianta (a) není slepá ulička: `paid_at` + částka se dají později povýšit na tabulku plateb bez zahození.
3. Časově za Vlnu 1 (stojí na stavu faktury), před dobropisem — je jednodušší, levnější a provozně naléhavější (obsluha to bude klikat denně; dobropis je nutnost méně častá).

**Souvislost:** platby + dobropis (R-2) dohromady dávají úplný obraz „co se s fakturou děje po vystavení" (vystaveno → dobropis → zaplaceno → vyrovnáno).

**Dopad do plánu:** nová položka za Vlnu 1, před R-2; varianta (b) jako budoucí TD.

**Rozhodnuto:** 2026-07-24.

---

## R-4 · Autorizace a seed účty (klíčový nález K-10, doména B5, TD-24)

**Otázka:** Co se seed zákaznickými účty a jestli teď povyšovat celé TD-24? Dnes je `/api/**` plošně `authenticated()` (každý přihlášený smí vše) a seed V3 zakládá účty `jan.novak`/`firma.logistika` s rolí ROLE_CUSTOMER a sdíleným heslem `Password1!` — přes plochou autorizaci by „zákazník" viděl a editoval celou firmu.

**Rozhodnutí:** **Rozdělit na okamžité minimum a plánovanou nadstavbu.**

Okamžitě (levné, uzavře riziko):
- Odstranit zákaznické seed účty `jan.novak` a `firma.logistika` (migrace V45) — portál je jen úvaha v roadmapě, účty pro neexistující funkci v datech nemají co dělat a jsou jediné s rolí, na kterou systém neumí reagovat.
- Backendová pojistka: dokud portál neexistuje, `SecurityConfig` odříznout `ROLE_CUSTOMER` od `/api/**` (`hasAnyRole('ADMIN','MANAGER','MECHANIC')` na úrovni celého API).
- Seed hesla `Password1!` → tvrdý bod produkčního checklistu (TD-33).

Plánovaně (samostatná etapa, před produkcí, ne před Vlnou 1):
- Granulární rolová autorizace TD-24 — vyhradit citlivé operace (vystavení/storno faktury, mazání, změna company profile) rolím ADMIN/MANAGER; projít endpoint po endpointu; zvážit chybějící role (recepce, účetní — B5).

**Odůvodnění:**
1. Okamžitá část je malá a odstraní skutečné riziko (dva řádky dat + jedno pravidlo konfigurace) — nemá smysl ji odkládat na „až celé TD-24".
2. Celé TD-24 je návrhová práce (matice role × operace), ne bug — míchat ji do rychlých oprav by ji zdrželo.
3. Jediný nález blokující *před* nasazením (lokálně nehrozí nic; na serveru s reálnými účty je ROLE_CUSTOMER se sdíleným heslem otevřené dveře).

**Poznámka:** produkční checklist (seed hesla, CORS, security hlavičky — R-6 dále) brát jako jeden propojený balík „než to pustíme ven", ne roztroušené drobnosti.

**Dopad do plánu:** okamžitá pojistka do Vlny 0/2 (nezávislá); plné TD-24 jako etapa přípravy na produkci po Vlně 1.

**Rozhodnuto:** 2026-07-24.

---

## R-5 · Číselné řady — reset po roce a konfigurovatelnost (doména B11, DB N-11, A9)

**Otázka:** Mají se ZNK/ZAK resetovat po roce (formát to slibuje, sekvence to nedělá)? Stavět teď konfigurovatelnost (vlastní formáty, více řad, offset)?

**Rozhodnutí:**

Bugy (opravit vždy, nezávisí na preferenci):
- Sladit `START WITH` sekvence zákazníků nad seed (N-11).
- Ošetřit přetečení `LPAD` u všech tří řad (A9) — dnes tichá kolize / 500 při překročení počtu míst.

Reset po roce — rozlišit dle povahy dokladu:
- **Faktura:** ponechat per-měsíc reset (správně); jen R-6 opraví, podle jakého data.
- **Zakázka (ZAK):** sjednotit na mechanismus faktury (MAX+1 za období + advisory lock), resetovat **per rok**. Řeší klamavý formát i odstraní třetí variantu mechanismu (2 ze 3 řad stejný kód).
- **Zákazník (ZNK):** nechat jako **celoživotní sekvenci** (neresetovat) a **zdokumentovat**, že rok = rok registrace, ne pořadí v roce. Zákaznické číslo je trvalé master-data ID.

Konfigurovatelnost (bod B): **teď nestavět.** Pro jeden servis předčasná složitost. Jedinou reálnou potřebu — offset při migraci z jiného systému — řešit jednorázově `setval` / počátečním záznamem při nasazení. Zaznamenat jako budoucí TD.

**Odůvodnění:**
1. Bugy jsou levné a nezávislé na preferenci.
2. Reset ZAK per rok + sjednocení mechanismu řeší dva nálezy jedním zásahem.
3. ZNK jako trvalé ID je věcně správnější než reset (zákaznická čísla se v praxi neresetují); stačí dokumentace.
4. Konfigurovatelnost = YAGNI pro jeden servis; offset jde bez ní.

**Podmínka:** pokud cílový servis **už dnes** provozuje více fakturačních řad (hotovost vs. převod), bod B se posouvá z „nestavět" na „naplánovat" — informaci má uživatel. Při jedné řadě platí doporučení.

**Dopad do plánu:** bugy do Vlny 0/2; ZAK reset + sjednocení jako součást billing prací; konfigurovatelnost jako budoucí TD.

**Rozhodnuto:** 2026-07-24.

---

## R-6 · Číslování faktury — podle jakého data a v jakém okamžiku (P1.3, klíčový nález K-3, A3/A4)

**Otázka:** Jen opravit prefix (`CURRENT_DATE` → `issue_date`), nebo přesunout číslování i razítko data na přechod DRAFT→ISSUED?

**Rozhodnutí:** **Varianta (b)** — přesunout číslování a razítko data vystavení na přechod DRAFT→ISSUED.
- `invoice_number` (a variabilní symbol) → **nullable**; koncept ho nemá.
- Přidělit při vystavení. Zásadu „číslování v DB" zachovat: přepnout trigger V15 z `BEFORE INSERT` na **podmíněný `BEFORE UPDATE` spouštěný při přechodu na ISSUED** (advisory lock zůstává). Nestěhovat do Javy.
- `issue_date` při vystavení orazítkovat aktuálním datem; v DRAFTu ponechat editovatelný pro vědomě zpětně datovanou fakturu (s validací).

**Odůvodnění:**
1. Účetně správný výsledek — vystavená řada souvislá (koncepty ji nespotřebovávají), číslo i datum sedí z principu (přidělují se současně). Řeší tři neshody najednou: (1) číslo dle správného data, (2) datum nezamrazené příliš brzy, (3) DRAFTy nedělají díry v řadě.
2. Podpoří opravu A3 — koncept bez čísla je zjevně „návrh", ne doklad; nesplete se s ostrou fakturou.
3. Neporušuje „číslování v DB" — jen změna spouštěče triggeru (INSERT → podmíněný UPDATE).
4. Varianta (a) je poloviční — opravila by jen měsíc, nechala DRAFTy spotřebovávat čísla a datum zamrazené brzy.

**Cena:** větší než (a) — mění schéma (nullable sloupec, migrace V45), přepisuje trigger, dotýká se UI/PDF konceptu. Ohraničená práce (dny), patří do Vlny 1 (stojí na stavovém automatu faktury).

**Upozornění:** požadavek na souvislost řady a zpětné datování potvrdit s účetním — obecná praxe, ne závazný výklad.

**Dopad do plánu:** upřesňuje P1.3 na variantu (b).

**Rozhodnuto:** 2026-07-24.

---

## Shrnutí dopadu rozhodnutí na plán

Všech 6 otevřených otázek rozhodnuto. Dopad na [plan-oprav.md](plan-oprav.md):

| Rozhodnutí | Dopad na plán |
|---|---|
| R-1 | P1.1 = varianta (a), potvrzeno |
| R-2 | P1.8 = „Billing Phase 6", 2 kroky, za Vlnu 1+2 |
| R-3 | nová položka „evidence úhrad var. (a)" za Vlnu 1, před R-2; var. (b) = budoucí TD |
| R-4 | okamžitá pojistka (smazat seed účty + odříznout ROLE_CUSTOMER) do Vlny 0/2; plné TD-24 před produkcí |
| R-5 | bugy (START WITH, LPAD) do Vlny 0/2; ZAK per-rok + sjednocení mechanismu do billing prací; konfigurovatelnost = budoucí TD |
| R-6 | P1.3 = varianta (b) |

Doporučené pořadí prací zůstává: Vlna 0 → Vlna 1 (fakturace, vč. R-1, R-6) → evidence úhrad (R-3) → Vlna 2 (integrita, vč. R-4 pojistky) → dobropis (R-2) → testy → produkční příprava (plné TD-24, security hlavičky, CORS, seed hesla).

---

## R-7 · Architektura dobropisu (opravného daňového dokladu) — E5 (navazuje na R-2)

**Kontext:** R-2 podmiňoval E5 potvrzením náležitostí a číslování „s účetním". Uživatel místo toho zadal
najít informace ze zdrojů. **Náležitosti §45 ZDPH ověřeny** (stormware.cz, superfaktura.cz, kurzy.cz —
zákon č. 235/2004 Sb.): opravný daňový doklad musí obsahovat označení „opravný daňový doklad", identifikaci
obou stran vč. DIČ, **evidenční číslo původního i opravného dokladu**, **důvod opravy**, **rozdíl základu
daně**, **rozdíl daně**, **rozdíl celkové částky**, datum vystavení + obecné náležitosti §29. Lhůta vystavení
15 dní od zjištění důvodu; opravu nelze po 3 letech.

**Rozhodnutí (architektura, inspirováno iDoklad/FakturaOnline + znovupoužitím vzorů projektu):**
1. **Samostatná tabulka `billing.credit_notes`** (ne discriminator na `invoices`) — izoluje dobropis od
   solidního fakturačního toku, nemíchá se do `uq_invoices_order_active`, stavového automatu ani number
   triggeru faktur. Nižší riziko regrese.
2. **Vlastní číselná řada** s prefixem `OD` (`OD{YYYYMM}###`) — nezaměnitelná s fakturami, bez kolizí;
   přiděluje se **až při vystavení** (DRAFT→ISSUED), stejný vzor jako faktura po E1.3.
3. **Strany i rozdílové částky se odvozují z původní faktury** — dobropis drží `original_invoice_id`
   (§45 evidenční číslo původního dokladu) + `correction_reason` (§45 důvod). Identifikace stran = snapshoty
   původní faktury (`invoice_party`, právně správné — strany k datu původního dokladu). Rozdíly (základ/daň/
   částka, rozpad po sazbách) = **záporné hodnoty souhrnů původní faktury** (z views). Žádné duplicitní
   snapshoty ani položky.
4. **MVP = plný dobropis** (storno-refund celé faktury — nejčastější reklamační případ). Částečný dobropis
   (podmnožina/vlastní částky) = pozdější rozšíření.
5. **Rozdělení dle R-2:** **E5.1** = model + vytvoření/vystavení + §45 detail + testy (backend). **E5.2** =
   PDF opravného dokladu (šablona s označením a rozdíly). E5.1 teď, E5.2 další cyklus.

**Přiznaná nejistota:** volbu „vlastní řada vs. řada faktur" a přesný rozpad rozdílů po sazbách doporučuji
při ostrém nasazení nechat potvrdit účetním — zvolený model (vlastní řada OD, rozdíly = záporné souhrny) je
legislativně obhajitelný a odpovídá běžné praxi ERP, ne však závazný výklad.

**Rozhodnuto:** 2026-07-24.

---

## R-8 · Strategie dokončení (rozhodnuto auditorem na vyzvání „zvol další postup")

Po dokončení kritické cesty (E0–E5) + E3/E4 a části E6 jsem zvážil nejvhodnější cestu k závěru.
Ne všechno zbývající má stejnou hodnotu; volím podle poměru přínos/riziko a podle toho, co lze
udělat správně a otestovaně bez externího vstupu.

**Dokončím autonomně (backend, testovatelné, bez rozhodnutí uživatele):**
- E6.5 completeness gate (záporné ceny, jednotka vs. karta) — chrání ocenění skladu.
- E6.4 validace draftu příjemky (NPE→400, reset padělatelných stavů polí).
- S-4 TOCTOU guarded-write editace DRAFT faktury (přesunuto z E1).
- **E3.5** (embedded resultMap „duchový zákazník") — dořeším odložený korektnostní nález.
- E6.8 nejcennější durabilita: **test obsahu SPAYD/QR** (audit varoval, že chybná částka v QR
  by prošla celou suitou — reálné riziko peněz) — extrakce SPAYD do testovatelné metody.
- E8 dokumentace + úklid: `tech-dluhy.md` (uzavřít vyřešené TD, přidat odložené), refresh
  přehledu auditu, smazání mrtvého kódu.

**Stop pro rozhodnutí uživatele:**
- **E7 produkční příprava** — matice role × operace (kdo smí vystavit/stornovat fakturu, mazat,
  měnit profil firmy). Nehádám oprávnění; vyžádám si matici.
- Potvrzení dobropisu s účetním (R-7) před ostrým provozem.

**Doporučuji jako samostatné soustředěné follow-upy (necpat teď):**
- E3.8 ZAK trigger rewrite (invazivní jako E1.3 — seed/test blast radius).
- E2.2 FE seznam „po splatnosti" + E6.7 FE chybové stavy (FE nemá testovací síť — nejlépe
  v dedikované FE etapě s ověřením v prohlížeči).
- E6.1 NoResourceFound → 404 (nejdřív ověřit routing/SPA fallback).

**Rozhodnuto:** 2026-07-24.

---

## R-9 · Matice rolí (E7, autorizace) — rozhodnuto uživatelem 2026-07-24

**Kontext:** E7 (TD-24) dělí operace mezi role ADMIN/MANAGER/MECHANIC. Kdo smí co, není technická,
ale provozní otázka → auditor nehádá, předložil výchozí matici z doménové analýzy (B5) k odsouhlasení.
Uživatel zvolil „implementuj navrženou matici".

**Schválená matice (✅ smí / ❌ nesmí):**

| Operace | ADMIN | MANAGER | MECHANIC |
|---|---|---|---|
| Zákazníci/vozidla/zakázky — vytvořit, editovat | ✅ | ✅ | ✅ |
| Deaktivace/reaktivace zákazníků, vozidel | ✅ | ✅ | ❌ |
| Faktura — vystavit, úhrada, stornovat | ✅ | ✅ | ❌ |
| Dobropis (celý) | ✅ | ✅ | ❌ |
| Profil firmy — editace | ✅ | ✅ | ❌ |
| Sklad — import/review příjemek, ruční pohyby, otevření+sčítání inventury | ✅ | ✅ | ✅ |
| Inventura — uzavření (materializace korekcí) | ✅ | ✅ | ❌ |
| Správa uživatelů a rolí | ✅ | ❌ | ❌ |

**Provedení:** inline `@PreAuthorize` na controllerech (konzistentní se stávajícím UserController /
warehouse), baseline `/api/**` = pracovní role v `SecurityConfig`. Součástí E7 i bezpečnostní hlavičky
a CORS z konfigurace (E7.2/E7.3).

**Vědomě mimo teď:** nové role (recepce, účetní) = samostatné rozšíření; příprava produkce (seed hesla,
checklist) zůstává jako TD-63.

**Rozhodnuto:** 2026-07-24.
