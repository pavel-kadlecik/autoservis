# nasazeni.md — Produkční nasazení a správa secrets

> Deploy infrastruktura v `deploy/` + skript `deploy.sh`; správa API klíčů a secrets.
> Cílový server: `dilna-server` (Ubuntu, `/opt/autoservis`).

## 1. Proč to existuje

Backend se původně spouštěl ručně (`java -jar … --spring.profiles.active=prod` v tty). Problémy: nepřežije zavření terminálu ani reboot, žádný restart po pádu, žádné strukturované logování. Cíl: aplikace jako standardní `systemd` služba, aktualizace jedním příkazem bez interaktivního sudo hesla.

## 2. Architektura

```
GitLab (origin/master)
        │  git pull
        ▼
/opt/autoservis  (branch "master")
        │
        ├─ mvnw package  ──────────► target/autoservis-0.0.1-SNAPSHOT.jar
        │                                   │
        │                          systemd: autoservis-backend.service
        │                                   │  (port 8080, secrets z .env)
        │                                   ▼
        └─ npm run build ──────────► frontend/autoservis-frontend/dist/
                                            │
                                            ▼
                                    nginx (port 80)
                                    ├─ / .......... servíruje dist/ staticky
                                    └─ /api/ ...... reverse proxy → localhost:8080
```

- **Backend** = systemd služba `autoservis-backend` (`Restart=on-failure`, start po rebootu, user `pka`, `WorkingDirectory=/opt/autoservis`).
- **Secrets** (`DB_PASSWORD`, `JWT_SECRET`, `ANTHROPIC_API_KEY`, `DATAOVOZIDLECH_API_KEY`, `ADMIN_PASSWORD_HASH`, `SPRING_PROFILES_ACTIVE=prod`; volitelně `CORS_ALLOWED_ORIGINS` = doména FE a `MAIL_USERNAME`/`MAIL_PASSWORD` = účet Seznam Emailu pro odesílání faktur (celá adresa + **„aplikační heslo"** ze Zabezpečení Seznam účtu, ne přihlašovací heslo — vyžaduje dvoufázové ověření, přežije změnu hesla majitelem a jde samostatně zneplatnit) — bez nich aplikace běží, jen odeslání e-mailu vrací `EMAIL_NOT_CONFIGURED`) žijí v `/opt/autoservis/.env` (mimo git, `chmod 600`, majitel `pka`); službě je předává `EnvironmentFile=` v unit souboru. `ADMIN_PASSWORD_HASH` viz sekce 7b.
- **Frontend** je statický build v `dist/` — nginx ho čte přímo z disku, po `npm run build` je nová verze hned dostupná (bez restartu nginx; v prohlížeči hard refresh).
- **Sudo bez hesla** — úzké `NOPASSWD` pravidlo v `/etc/sudoers.d/autoservis-deploy` povoluje **výhradně** `systemctl {restart,start,stop,status} autoservis-backend`.

## 3. Soubory

| Soubor | Účel | V gitu? |
|---|---|---|
| `deploy/autoservis-backend.service` | šablona systemd unit | ✅ |
| `deploy/autoservis-sudoers` | šablona NOPASSWD pravidla | ✅ |
| `deploy/setup.sh` | **jednorázová** instalace služby | ✅ |
| `deploy.sh` (kořen) | **ostrý deploy** — každá aktualizace | ✅ |
| `.env` | produkční secrets | ❌ (`.gitignore`) |

## 4. Instalace (jednorázově)

Jen na novém serveru, nebo při změně unit/sudoers souboru.

Předpoklady: Java 21, Node.js (nvm), PostgreSQL; `/opt/autoservis/.env` existuje; nginx nakonfigurován (servíruje `dist/`, proxy `/api/` → `localhost:8080`).

```bash
sudo /opt/autoservis/deploy/setup.sh
```

Skript: nakopíruje unit do `/etc/systemd/system/` + `daemon-reload` → nakopíruje sudoers do `/etc/sudoers.d/` (`chmod 440`) s validací `visudo -c` (chybná syntaxe by rozbila sudo celému systému) → ukončí případný ruční `java -jar` proces → `enable` + `start` služby → vypíše status.

## 5. Aktualizace (běžný provoz)

Po pushnutí na GitLab (branch `master`):

```bash
bash /opt/autoservis/deploy.sh
```

Kroky: `git checkout master && git pull` → `./mvnw -q clean package -DskipTests` → `npm ci && npm run build` ve frontend/autoservis-frontend → `sudo systemctl restart autoservis-backend` (bez hesla díky sudoers) → kontrola `systemctl is-active` (neběží → exit 1).

> **Nezaměňovat:** `setup.sh` = jednorázová instalace (nedělá pull ani build). `deploy.sh` = běžná aktualizace.

## 6. Diagnostika

```bash
systemctl status autoservis-backend          # stav služby
journalctl -u autoservis-backend -f          # live logy
journalctl -u autoservis-backend -n 50       # posledních 50 řádků
ss -tlnp | grep 8080                         # běží na portu?
cd /opt/autoservis && git log -1             # nasazený commit
git fetch && git log --oneline HEAD..origin/master   # co je nového k pullnutí
```

Appka se netváří aktualizovaná? Nejčastěji: spuštěn `setup.sh` místo `deploy.sh` · změna nepushnutá na GitLab · frontend v cache prohlížeče (Ctrl+Shift+R).

## 7. API klíče a secrets

Zásada: klíč **nikdy** do `application.yaml` ani do gitu — konfigurace má jen placeholdery (`${ANTHROPIC_API_KEY}`, `${DATAOVOZIDLECH_API_KEY:}`), Spring je čte z prostředí. Klíč pro registr vozidel se získává registrací na https://dataovozidlech.cz/registraceapi (zdarma, přijde e-mailem); má prázdný default — bez něj aplikace nastartuje a volání registru vrací 503 `REGISTRY_AUTH_FAILED`. ARES (lookup firmy dle IČO) žádný klíč nepotřebuje — veřejné API MF ČR.

### Vývoj (lokální stroj)

Samostatný soubor s omezenými právy (ne přímo `.bashrc`):

```bash
mkdir -p ~/.config/autoservis
echo 'ANTHROPIC_API_KEY=sk-ant-…' > ~/.config/autoservis/env
echo 'DATAOVOZIDLECH_API_KEY=…' >> ~/.config/autoservis/env
chmod 600 ~/.config/autoservis/env
# načtení před spuštěním (nebo trvale v ~/.bashrc):
set -a; source ~/.config/autoservis/env; set +a
./mvnw spring-boot:run
```

IntelliJ: Run Configuration → Environment variables. Pozor — uloží se do `.idea/workspace.xml`; ověř, že `.idea/` je v `.gitignore`.

Lokální přepisy konfigurace: `application-local.yaml` (gitignorováno; šablona `application-local.yaml.example`).

### Produkce

Secrets jsou v `/opt/autoservis/.env` (viz sekce 2) — `KLIC=hodnota` bez `export`, `chmod 600`. Po změně `sudo systemctl restart autoservis-backend`.

### Tři pravidla

1. Klíč nikdy necommitovat; pro tajemství vždy env proměnná.
2. Práva souboru: 600 (dev) / 600 majitel služby (produkce). Env proměnné běžícího procesu jsou čitelné přes `/proc/PID/environ` vlastníkovi a rootovi — proto vyhrazený uživatel.
3. Únik klíče (i jen do git historie) = klíč je kompromitovaný → okamžitě zneplatnit a rotovat (nový vygenerovat v Anthropic Console, přepsat v `.env`, restart služby).

## 7b. Prázdná produkční DB + admin účet

Produkce běží s profilem `prod`, který nastavuje `spring.flyway.locations = classpath:db/migration,classpath:db/prod` (viz `application-prod.yaml`). Demo data (`db/demo`) se tedy do produkce **nedostanou** — čerstvá DB je prázdná až na:
- 5 rolí (`ROLE_ADMIN`, `ROLE_MANAGER`, `ROLE_MECHANIC`, `ROLE_CUSTOMER`, `ROLE_READONLY`),
- jednoho uživatele **`admin`** s rolí `ROLE_ADMIN`.

Migrace `db/prod/V60__prod_seed.sql` heslo admina nebere z gitu — čte ho z env `ADMIN_PASSWORD_HASH` (BCrypt hash) přes Flyway placeholder `${admin_password_hash}`.

**Postup před prvním nasazením:**

0. **Promotni změny do `master`.** `deploy.sh` staví z větve `master` (`git checkout master && git pull`). Produkční chování (`db/prod`, override `locations` v `application-prod.yaml`) i pomůcka na hash žijí na `devel` — bez merge do `master` je server **nemá**.
1. **Vygeneruj BCrypt hash na svém dev stroji** (ne na serveru — `deploy.sh` běží s `-DskipTests`, na serveru se testy nepouští; hash je navíc jen řetězec, který stačí zkopírovat). Test nepotřebuje Docker. `-Djacoco.skip=true` je nutné — bez něj Maven po vypsání hashe skončí `BUILD FAILURE` na coverage gate (1 test = 0 % pokrytí; hash se ale i tak vygeneruje):
   ```bash
   ./mvnw test -Dtest=GeneratePasswordHashTest -Dsurefire.failIfNoSpecifiedTests=false -Djacoco.skip=true -Dadmin.password='ZvolenéSilnéHeslo'
   # hash vypíše do konzole a do target/admin_password_hash.txt
   ```
   Použij **silné** heslo (ne `admin`) — po prvním přihlášení ho stejně změň v UI (krok 5).
2. Vlož hash do `/opt/autoservis/.env`. **Pozor na shell:** hash obsahuje `$` (`$2a$10$…`), takže v **dvojitých** uvozovkách ho bash rozbije — `$2a`, `$10` se berou jako proměnné, `$$` jako PID. Použij **jednoduché** uvozovky (žádná expanze):
   ```bash
   sudo sed -i '/^ADMIN_PASSWORD_HASH=/d' /opt/autoservis/.env   # smaž případné chybné pokusy
   echo 'ADMIN_PASSWORD_HASH=$2a$10$....' | sudo tee -a /opt/autoservis/.env
   grep '^ADMIN_PASSWORD_HASH=' /opt/autoservis/.env             # ověř: 1 řádek, celý hash
   ```
   (Alternativně `.env` otevři v editoru a řádek vlož přímo — bez shellu žádná expanze.) `EnvironmentFile=` v systemd hodnotu nebere jako shell → v běžící službě jsou `$` bezpečné (literál).
3. **Produkční DB musí být prázdná.** Když je server čerstvý (DB ještě nikdy neběžela) → přeskoč. Pokud už byl nasazen **starým `master`** (kde demo migrace V3/V8/V13/V16/V46/V47/V58 ležely v `db/migration` a spustily se), pak DB (a) není prázdná a (b) Flyway by při startu **spadl na validaci** — tyto verze jsou v `flyway_schema_history` jako aplikované, ale v produkčních locations (`db/migration,db/prod`) už nejsou (`applied migration not resolved`). Pro čistý start proto **dropni a znovu vytvoř produkční databázi** (stejně ji chceš prázdnou):
   ```bash
   sudo systemctl stop autoservis-backend
   sudo -u postgres psql -c "DROP DATABASE autoservis;" -c "CREATE DATABASE autoservis OWNER autoservis_app;"
   ```
   (Když DROP hlásí aktivní spojení, nejdřív je ukonči: `sudo -u postgres psql -c "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname='autoservis';"`.)
4. Spusť `deploy.sh` → Flyway vytvoří schéma od nuly + vloží admina. Přihlas se jako `admin`.
5. **Ihned po přihlášení změň heslo** v UI (Správa uživatelů a hesel). (Vynucení změny hesla je evidováno jako možné budoucí vylepšení.)
6. Doplň reálné údaje firmy do `billing.company_profile` (placeholder „DOPLŇTE NÁZEV FIRMY", viz níže) a založ reálné uživatele/zákazníky.
7. **Založ druhý účet s rolí ADMIN** (jiné uživatelské jméno než `admin`) — pojistka proti tomu,
   aby servis zůstal bez správce. Odemknout zamčený účet umí jen jiný administrátor a `admin` je
   navíc uhodnutelné jméno, na které míří útoky hrubou silou. Od V64 sice zámek po 15 minutách
   vyprší (`lockout.duration`), takže trvalé vyřazení už nehrozí (audit KN-5), ale útočník, který
   požadavky opakuje, dokáže jediný admin účet držet nepoužitelný trvale.

## 8. Produkční checklist (otevřené položky)

Před ostrým provozem vyřešit (detaily v `tech-dluhy.md`):
- ~~TD-31: `secure=true` na cookies za HTTPS, sladit maxAge s expirací tokenů~~ — vyřešeno 2026-07-20: `AuthController` čte `jwt.cookie-secure`/`jwt.expiration`/`jwt.refresh-expiration` z konfigurace. **Zkontrolovat, že `application-prod.yaml` má `jwt.cookie-secure: true`** (nastaveno, ověřit při nasazení, že se profil `prod` skutečně aktivuje).
- **Nastavit `ADMIN_PASSWORD_HASH`** v `.env` (viz sekce 7b) a po prvním přihlášení změnit heslo admina. (Demo heslo `Password1!` se do produkce nedostane — je jen v `db/demo`.)
- TD-63 (zbytek TD-33): doplnit reálné údaje do `billing.company_profile` (placeholder „DOPLŇTE NÁZEV FIRMY"). **Nastavit `CORS_ALLOWED_ORIGINS`** v `.env` na skutečnou doménu FE (víc originů čárkou; prázdné = žádný cross-origin, viz `application-prod.yaml`).
- ~~TD-33 CORS originy natvrdo~~ — vyřešeno E7 (2026-07-24): `cors.allowed-origins` z konfigurace.
- ~~TD-24: rolová autorizace endpointů~~ — vyřešeno E7 (2026-07-24): `@PreAuthorize` dle matice rolí (R-9). Bezpečnostní hlavičky (CSP/HSTS/nosniff/frameOptions) doplněny v `SecurityConfig`.
