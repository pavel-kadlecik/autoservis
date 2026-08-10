# claude-infrastruktura.md — Hooks, permissions a skills pro Claude Code

> Co je v `.claude/`, jak to funguje, jak to rozšiřovat a jak řešit problémy.
> Zavedeno 2026-07-18 (commit `87d4db7`) po dokončení restrukturalizace dokumentace.

## 1. Proč to existuje

Projekt má dvě pravidla, jejichž porušení je drahé a špatně vratné: **commitnutá Flyway migrace se nikdy nemění** (rozbilo by to checksum validaci proti již zmigrovaným DB) a **`docs/archiv/` je jen ke čtení** (archiv je historický snapshot, ne živá dokumentace). Do 2026-07 stála obě pravidla jen na textu v CLAUDE.md — tedy na tom, že si jich AI všimne a poslechne. Hook je vynucuje **deterministicky**: zakázaný zápis se vůbec neprovede, bez ohledu na to, co si model myslí.

Druhý účel: **skills** zabalují opakované pracovní postupy (nová migrace, nový endpoint) do checklistu, který zaručuje konzistenci s konvencemi a — hlavně — **synchronizaci dokumentace s kódem** (přesně ten drift, kvůli kterému se dělala celá restrukturalizace).

## 2. Co kde je

```
.claude/
├── settings.json                  ← commitnuté, platí pro každého (hook + permissions)
├── settings.local.json            ← osobní přepisy, gitignorováno (neexistuje, dokud ho nevytvoříš)
├── launch.json                    ← jak spustit backend (:8080) a frontend (:5173) z Claude Code
├── hooks/
│   └── guard-immutable.js         ← PreToolUse strážce (Node, bez závislostí)
└── skills/
    ├── nova-migrace/SKILL.md      ← workflow změny DB schématu
    └── novy-endpoint/SKILL.md     ← checklist vrstev nového REST endpointu
```

`launch.json` drží tři konfigurace:

| Název | Spouští | Kdy |
|---|---|---|
| `backend` | `./mvnw spring-boot:run -Dspring-boot.run.profiles=local` | Linux, macOS |
| `backend-windows` | `cmd /c .\mvnw.cmd spring-boot:run …` | Windows |
| `frontend` | `npm run dev` v `frontend/autoservis-frontend` | všude |

**Backend má dvě varianty, protože formát `launch.json` neumí platformní větvení** (nemá klíče
`windows`/`linux` jako VS Code) — a Maven wrapper je na každé platformě jiný soubor. Jedna konfigurace
by tedy vždy jednu půlku týmu vyřadila: `cmd` na Linuxu neexistuje, `./mvnw` na Windows nespustí.
Zavedeno 2026-08-07, kdy `backend` mířil jen na `mvnw.cmd` a na Linuxu ho nešlo spustit vůbec.

Cesty musí být **relativní k repozitáři**: absolutní cesta konkrétního uživatele soubor rozbije
komukoli jinému (opraveno 2026-07-31 — mířil na neexistující `C:\Users\Robert\IdeaProjects\autoservis`)
a spuštění pak selže na „not recognized as an internal or external command". Na Windows je potřeba
`.\mvnw.cmd`, ne `mvnw.cmd`.

**Frontend má `autoPort: false` schválně.** Backend povoluje CORS jen pro `http://localhost:5173`
a `http://127.0.0.1:5173` (`application.yaml` → `cors.allowed-origins`), takže na náhradním portu
sice Vite naběhne, ale přihlášení skončí na 403 a vypadá to jako chyba aplikace. Lepší je, když
spuštění rovnou selže na obsazeném portu — je vidět, že už někde běží jiná instance.

Vše se načítá **při startu session** — po změně čehokoli v `.claude/` je potřeba nová session (nebo restart aplikace Claude), jinak běží stará konfigurace.

## 3. Hook `guard-immutable.js`

**Kdy běží:** před každým použitím nástroje `Edit`, `Write` nebo `NotebookEdit` (matcher v `settings.json`). Dostane na stdin JSON s cestou k souboru; když cesta spadá do chráněné oblasti, skončí exit kódem 2 → **nástroj se neprovede** a Claude dostane česky vysvětlení, co má udělat místo toho.

**Dvě pravidla:**

| Chráněná oblast | Logika | Co hook poradí |
|---|---|---|
| `docs/archiv/**` | blokováno vždy | aktuální dokumentace patří do `docs/` |
| `src/main/resources/db/migration/V*__*.sql` | blokováno, **jen pokud je soubor v gitu** (`git ls-files`) | změnu proveď novou migrací `V{n+1}` + aktualizuj `databaze.md` |

Klíčový detail u migrací: rozhoduje **commitnutí, ne existence souboru**. Nově vytvořenou migraci (např. `V38__...sql` před prvním commitem) lze normálně editovat a ladit; jakmile je commitnutá, hook ji zamkne. To odpovídá realitě — commit = migrace je „vydaná" a někde už mohla proběhnout.

**Co hook NEchrání (vědomě):** změny přes shell (`Bash` nástroj — `sed`, `mv`, přesměrování). Hook není bezpečnostní sandbox, je to zábradlí proti nejčastější chybě (přímá editace). Ruční editace mimo Claude (IDE) samozřejmě také neblokuje.

**Legitimní obejití:** pokud je opravdu potřeba zasáhnout do chráněného souboru (např. oprava rozbitého kódování v archivu), udělej to v IDE ručně, nebo dočasně zakomentuj příslušný blok v hooku / vypni hook v `settings.local.json` (`"disableAllHooks": true`) — a vrať zpět.

**Závislost:** Node.js v PATH (na tomto stroji je — používá ho frontend).

## 4. Permissions v `settings.json`

Omezují, na co se Claude musí/nemusí ptát:

- **allow** — bez dotazu: `./mvnw *`, `npm run/ci/install/test`, čtecí git (`status`, `log`, `diff`, `show`, `branch`, `ls-files`). Zrychluje běžnou práci.
- **deny** — vždy zamítnuto: `git push --force*`, `./mvnw flyway:clean*` (smaže všechna schémata!), `./mvnw flyway:repair*` (přepíše checksumy — maskuje manipulaci s migracemi).
- Vše ostatní (commit, push, rm…) → standardní dotaz na povolení.

**Osobní odchylky** (např. povolit si `git commit`) patří do `settings.local.json`, ne do commitovaného `settings.json` — local vrstva má přednost a nezasahuje ostatním.

## 5. Skills

Vyvolání: napsat `/nova-migrace` resp. `/novy-endpoint`, nebo je Claude použije sám, když úkol odpovídá popisu skillu.

| Skill | Kdy | Co zaručuje |
|---|---|---|
| `/nova-migrace` | jakákoli změna DB schématu | správné číslo V{n+1}, konvence SQL, ENUM pasti (noAutoCommit — vzor V17; redukce — vzor V24), `setval()` po seedech, **aktualizaci `databaze.md`** vč. indexu migrací, ověření proti živé DB |
| `/novy-endpoint` | nový/změněný REST endpoint | pořadí vrstev (XML → mapper → DTO → converter → service → controller → FE), pravidla R/N na každé vrstvě, **aktualizaci `api.md`** vč. křížové kontroly počtu endpointů grepem |

Skills odkazují do `docs/` — jsou to tenké checklisty, fakta žijí v dokumentaci. Když se změní konvence, měň `konvence.md`, ne skill.

## 6. Jak přidat další skill / hook / pravidlo

- **Nový skill:** složka `.claude/skills/{nazev}/SKILL.md` s frontmatter `name` + `description` (podle description se skill nabízí — pište konkrétně kdy použít). Kandidáti do budoucna: `overit-dokumentaci` (drift check počtů endpointů/migrací), `novy-modul` (celý řez od schématu po FE).
- **Nové pravidlo do hooku:** další blok v `guard-immutable.js` (vzor: regex na cestu → `console.error` s radou → `exit 2`). Vždy nejdřív pipe-test: `echo '{"tool_input":{"file_path":"..."}}' | node .claude/hooks/guard-immutable.js; echo $?`.
- **Nové oprávnění:** do `allow`/`deny` v `settings.json` (týmové) nebo `settings.local.json` (osobní). Syntaxe: `"Bash(prefix *)"`.

## 7. Řešení problémů

| Příznak | Příčina / řešení |
|---|---|
| Hook neblokuje, co má | Změny v `.claude/` se načetly? → nová session / restart aplikace. Ověř skript pipe-testem (viz §6). |
| Hook blokuje něco legitimního | Viz „Legitimní obejití" v §3; pokud je to systémové, uprav regex v hooku. |
| „node není rozpoznán" v hook chybě | Node.js zmizel z PATH — hook selže „open" (tool se v takovém případě neblokuje kvůli chybě hooku, jen se zaloguje). |
| Rozbité `settings.json` | Nevalidní JSON potichu vypne VŠECHNA nastavení z toho souboru. Validace: `node -e "require('./.claude/settings.json')"`. |
| Skill se nenabízí | Zkontroluj frontmatter (`name`, `description`) a novou session. Ručně jde vždy: `/nazev-skillu`. |
