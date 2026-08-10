# lokalni-databaze.md — Lokální vývojová PostgreSQL

> Jak je na vývojovém stroji (Windows 11, bez admin práv) provozovaná PostgreSQL pro autoservis:
> co běží, jak se to spouští, k čemu ještě slouží původní bat skript a jak DB spravovat.
> Produkční DB řeší `nasazeni.md`.

## 1. Co máme: přenosná (portable) instalace

PostgreSQL **není nainstalovaná jako Windows služba** — je to rozbalený archiv:

```
D:\Tools\PostgreSQL\pgsql\
├── bin\        ← programy: postgres.exe, pg_ctl.exe, psql.exe, pg_dump.exe, …
├── data\       ← CELÁ databáze (datový adresář, "cluster")
└── logfile     ← log serveru
```

Proč takhle: instalátor PostgreSQL vyžaduje admin práva (registruje službu). Přenosná verze běží čistě pod tvým uživatelem — žádný admin není potřeba na instalaci, spouštění ani upgrade.

Důsledky, které je dobré chápat:

- **Celá DB = složka `data\`.** Kdo má tuhle složku, má databázi. Záloha je kopie složky (viz §6).
- **Nic se nespouští samo od systému.** Server běží, jen když ho něco spustí (`pg_ctl start`). Proto existoval bat skript a proto je teď nastavený autostart (§3).
- Server po startu běží **na pozadí jako obyčejný proces** (`postgres.exe`) tvého uživatele — nezávisle na terminálu, ze kterého byl spuštěn.

## 2. Připojení

| Co | Hodnota |
|---|---|
| Host / port | `localhost:5433` |
| Databáze / uživatel | `autoservis` / `postgres` |
| Aplikace | `application-local.yaml` → `jdbc:postgresql://localhost:5433/autoservis` |
| psql MCP (Claude) | `claude_desktop_config.json` → stejná adresa |

**Proč port 5433, a ne výchozí 5432?** Historicky port 5432 blokovala PostgreSQL 16 ve WSL Ubuntu (startovala automaticky se startem WSL). Ta je od 2026-07-18 trvale zakázaná (`systemctl disable --now postgresql` ve WSL), takže 5432 je volný — ale **není důvod se stěhovat**: musela by se změnit konfigurace aplikace, MCP i zvyky, a nic by se tím nezískalo. Port je jen číslo.

## 3. Spouštění — jak to teď funguje

### Autostart při přihlášení (nastaveno 2026-07-18)

Ve složce „Po spuštění" leží tichý skript:

```
%APPDATA%\Microsoft\Windows\Start Menu\Programs\Startup\start-postgres-autoservis.vbs
```

Při každém přihlášení do Windows spustí `pg_ctl start` **bez okna**. DB je tedy k dispozici vždy, aniž bys na něco myslel. Když už server běží, `pg_ctl` jen tiše ohlásí chybu a skončí — dvojité spuštění nehrozí (server si datový adresář zamyká, viz §4).

- **Vypnout autostart:** smazat ten `.vbs` soubor (nebo ho přesunout jinam).
- **Ověřit, že běží:** `pg_ctl -D D:\Tools\PostgreSQL\pgsql\data status`

### psql v terminálu

`D:\Tools\PostgreSQL\pgsql\bin` je přidaný do **uživatelské** proměnné PATH (bez admina, platí jen pro tvůj účet). V každém **nově otevřeném** terminálu tedy funguje:

```powershell
psql -h localhost -p 5433 -U postgres -d autoservis   # interaktivní konzole
pg_ctl -D D:\Tools\PostgreSQL\pgsql\data status       # stav serveru
```

(Terminály otevřené před změnou PATH ji nevidí — zavřít a otevřít znovu.)

## 4. Původní bat skript — je ještě potřeba?

**Pro každodenní práci už ne** — o start se stará autostart. Skript ale zůstává užitečný jako **ruční ovládací panel**, hlavně pro řízené vypnutí a restart. Co přesně dělá:

| Část skriptu | Co dělá | Poznámka |
|---|---|---|
| `set PG_ROOT/PG_BIN/PG_DATA` | pevné cesty k instalaci | díky tomu je jedno, odkud skript spustíš |
| kontrola `pg_ctl.exe` | ověří, že instalace existuje | |
| `pg_ctl -D data -l logfile start` | **start serveru** | stejný příkaz volá autostart .vbs |
| `pause > nul` + hláška „nezavírejte křížkem" | čeká na ENTER | viz vysvětlení níže |
| `pg_ctl -D data stop` | **řízené vypnutí** po stisku ENTER | |

### Časté otázky ke skriptu

**Je jedno, odkud ho spouštím?** Ano. Skript používá absolutní cesty (`D:\Tools\...`), takže funguje z libovolného místa i umístění — můžeš ho mít na ploše, v projektu, kdekoli. Jediné, na čem záleží, je parametr `-D` (datový adresář) — ten určuje, *kterou* databázi ovládáš.

**Musím nechávat okno otevřené?** Ne — to je jen vlastnost skriptu, ne serveru. Server po `pg_ctl start` běží nezávisle na okně. Okno čeká jen proto, aby ti po ENTER udělalo pohodlné `pg_ctl stop`. Když okno zavřeš křížkem, **server běží dál** (hláška ve skriptu je v tomhle trochu zavádějící — křížkem nic nerozbiješ, jen se nespustí závěrečný stop).

**Co když skript spustím a server už běží (autostart)?** Nic zlého — `pg_ctl start` ohlásí, že server běží, a skript pokračuje na čekání. ENTER pak server normálně vypne. Dvě instance nad stejným datovým adresářem spustit nejdou (PostgreSQL drží zámek `postmaster.pid`).

**Musím server vůbec vypínat?** Nemusíš. PostgreSQL je navržená tak, aby přežila i tvrdé ukončení (vypnutí Windows, výpadek proudu) — při dalším startu si data zrekonstruuje z WAL (write-ahead logu). Řízené `pg_ctl stop` je „hezčí" (rychlejší další start, žádné recovery hlášky v logu), ale pro dev databázi je klidně v pořádku nechat ji zabít s vypnutím počítače.

**Doporučení:** skript si nech (např. přejmenovaný na `postgres-rucni.bat`), používej ho jen když chceš DB vědomě vypnout/nastartovat mimo autostart. Alternativně používej `pg_ctl` přímo z terminálu (§5).

## 5. Ovládání z terminálu (náhrada skriptu)

```powershell
pg_ctl -D D:\Tools\PostgreSQL\pgsql\data status    # běží?
pg_ctl -D D:\Tools\PostgreSQL\pgsql\data start -l D:\Tools\PostgreSQL\pgsql\logfile
pg_ctl -D D:\Tools\PostgreSQL\pgsql\data stop      # řízené vypnutí
pg_ctl -D D:\Tools\PostgreSQL\pgsql\data restart -l D:\Tools\PostgreSQL\pgsql\logfile
```

Tip: `-D` lze vynechat, když si nastavíš uživatelskou proměnnou prostředí `PGDATA=D:\Tools\PostgreSQL\pgsql\data`.

## 6. Zálohování

Dvě cesty, obě bez admina:

1. **`pg_dump` za běhu** (doporučeno — konzistentní, přenositelné):
   ```powershell
   pg_dump -h localhost -p 5433 -U postgres -Fc -f autoservis.dump autoservis
   # obnova: pg_restore -h localhost -p 5433 -U postgres -d autoservis -c autoservis.dump
   ```
2. **Kopie složky `data\`** — jen při **zastaveném** serveru (`pg_ctl stop`, zkopírovat, `start`). Kopie za běhu je nekonzistentní a k ničemu.

Pro dev DB navíc platí: skutečným zdrojem obnovy jsou **Flyway migrace** — `./mvnw spring-boot:run` na prázdné DB postaví schéma V1–V37 včetně seed dat. Záloha má smysl hlavně pro ručně vložená testovací data.

## 7. Řešení problémů

| Příznak | Co udělat |
|---|---|
| Aplikace nenaběhne, „connection refused" na 5433 | `pg_ctl … status`; když neběží → `pg_ctl … start` a koukni do `D:\Tools\PostgreSQL\pgsql\logfile` |
| „port already in use" při startu | Něco už na 5433 poslouchá: `Get-NetTCPConnection -LocalPort 5433` — nejspíš už server běží |
| Konflikt na 5432 se vrátil | Ve WSL se postgres zase povolil? `wsl -d Ubuntu-24.04 -u root -- systemctl is-enabled postgresql` musí říkat `disabled` |
| psql MCP v Claude nefunguje | 1) běží DB? 2) restartovala se aplikace Claude po změně configu? Konfigurace je v `%APPDATA%\Claude\claude_desktop_config.json` |
| Recovery hlášky v logu po startu | Normální po tvrdém ukončení — WAL recovery, žádná akce |
| Aplikace nenaběhne, „Migration checksum mismatch" | Migrace se změnila po tom, co už jednou proběhla. Buď změnu vrať, nebo srovnej otisk: `./mvnw flyway:repair` (viz §7a) |

### 7a. Flyway z příkazové řádky

```bash
./mvnw flyway:info      # co je aplikované
./mvnw flyway:migrate   # aplikuj čekající migrace
./mvnw flyway:repair    # srovnej otisk po úpravě migrace
```

**Heslo** bere plugin z vlastnosti `db.password` v `pom.xml`, která se plní z proměnné
`DB_PASSWORD`. Ta ale platí jen v tom shellu, kde ji nastavíš — v novém terminálu ani
v IntelliJi už není. Spolehlivější je `~/.m2/settings.xml` (mimo repozitář, práva `600`),
kde profil `autoservis-local` dodá `db.password` napevno; Maven si settings.xml načítá vždy:

```xml
<profiles>
  <profile>
    <id>autoservis-local</id>
    <properties><db.password>…</db.password></properties>
  </profile>
</profiles>
<activeProfiles><activeProfile>autoservis-local</activeProfile></activeProfiles>
```

Ověření: `./mvnw -q help:evaluate -Dexpression=db.password -DforceStdout` musí vypsat heslo.

**Odkud plugin čte migrace.** Od 2026-08-06 má v `pom.xml` `filesystem:` locations, tedy
čte přímo `src/main/resources/db/…`. Dřív tam bylo `classpath:`, což znamenalo `target/classes` —
a protože `flyway:*` jsou samostatné cíle a `process-resources` před sebou nespouštějí,
pracoval plugin po úpravě migrace se **starou kopií**: `flyway:repair` uložil otisk starého
souboru, aplikace pak zdroje překopírovala a spadla na „checksum mismatch". S `filesystem:`
to odpadá. Běhu aplikace se to netýká — ta si locations bere z `application.yaml`.

## 8. Proč ne PostgreSQL ve WSL (rozhodnutí 2026-07-18)

Ve WSL Ubuntu-24.04 byla nainstalovaná PostgreSQL 16, která automaticky startovala se startem WSL a blokovala port 5432. Služba je trvale zakázaná (data zůstala, jen se nespouští). Srovnání vyznělo pro přenosnou instalaci: žádná admin práva, data viditelně na disku (ne uvnitř `ext4.vhdx`), žádná RAM režie WSL2 VM, žádné vrtochy localhost forwardingu — a pro chování samotné DB (Flyway, ENUMy, triggery, FTS) je platforma úplně jedno. WSL s Dockerem zůstává k dispozici pro budoucí Testcontainers workflow.
