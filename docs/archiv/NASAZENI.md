# NASAZENI.md — Produkční nasazení na serveru

> Popisuje deploy infrastrukturu v `/opt/autoservis/deploy/` a skript `deploy.sh`.
> Cílový server: `dilna-server` (Ubuntu, `/opt/autoservis`).

---

## 1. Proč to existuje

Backend se původně spouštěl ručně příkazem `java -jar target/autoservis-0.0.1-SNAPSHOT.jar --spring.profiles.active=prod` přímo v terminálové session (tty). To má tři problémy:

- **Nepřežije zavření terminálu** — proces byl vázaný na konkrétní tty session, ne na systém.
- **Nepřežije reboot** — nic ho po startu serveru znovu nespustí.
- **Žádný automatický restart** po pádu, žádné strukturované logování.

Cíl: appka běží jako standardní systemová služba (`systemd`), a aktualizace (nová verze z GitLabu) jde spustit jedním příkazem — bez nutnosti ručně zabíjet starý proces, přepisovat conf soubory nebo zadávat sudo heslo pokaždé znovu.

---

## 2. Jak to funguje — architektura

```
GitLab (origin/master)
        │  git pull
        ▼
/opt/autoservis  (checked out na branch "master")
        │
        ├─ mvnw package  ──────────► target/autoservis-0.0.1-SNAPSHOT.jar
        │                                   │
        │                          systemd: autoservis-backend.service
        │                                   │  (port 8080, čte secrets z .env)
        │                                   ▼
        └─ npm run build ──────────► frontend/autoservis-frontend/dist/
                                            │
                                            ▼
                                    nginx (port 80)
                                    ├─ / .......... servíruje dist/ staticky
                                    └─ /api/ ...... reverse proxy → localhost:8080
```

- **Backend** běží jako `systemd` služba `autoservis-backend`, ne jako ruční proces. `systemd` ho nastartuje po rebootu a restartuje při pádu (`Restart=on-failure`).
- **Secrets** (`DB_PASSWORD`, `JWT_SECRET`, `ANTHROPIC_API_KEY`) žijí v `/opt/autoservis/.env` (mimo git, `chmod 600`), službě je předává `EnvironmentFile=` v unit souboru.
- **Frontend** se buildí do statických souborů (`dist/`), které nginx servíruje přímo z disku — po každém `npm run build` je nová verze okamžitě dostupná, nginx se restartovat nemusí.
- **Sudo bez hesla** — protože `sudo` vyžaduje interaktivní terminál (tty) na zadání hesla, a deploy má jít spustit i automatizovaně, existuje úzké `NOPASSWD` pravidlo v `/etc/sudoers.d/autoservis-deploy`, které povoluje **výhradně** `systemctl {restart,start,stop,status} autoservis-backend` — nic jiného.

---

## 3. Soubory

| Soubor | Účel | V gitu? |
|---|---|---|
| `deploy/autoservis-backend.service` | Šablona systemd unit souboru | ✅ |
| `deploy/autoservis-sudoers` | Šablona sudoers pravidla (NOPASSWD jen pro restart/start/stop/status této služby) | ✅ |
| `deploy/setup.sh` | **Jednorázový** instalační skript (viz níže) | ✅ |
| `deploy.sh` | **Ostrý deploy skript** — spouští se při každé aktualizaci | ✅ |
| `.env` | Produkční secrets (DB heslo, JWT secret, Anthropic API klíč) | ❌ (`.gitignore`) |

---

## 4. Instalace (jednorázově)

Provádí se **jen jednou** na novém serveru, nebo pokud se mění systemd unit / sudoers pravidlo.

### Předpoklady

- Java 21, Node.js (přes `nvm`), PostgreSQL — nainstalované a nakonfigurované.
- `/opt/autoservis/.env` existuje a obsahuje:
  ```
  SPRING_PROFILES_ACTIVE=prod
  DB_PASSWORD=...
  JWT_SECRET=...
  ANTHROPIC_API_KEY=...
  ```
- nginx nakonfigurovaný (`/etc/nginx/sites-available/autoservis`) — servíruje `frontend/autoservis-frontend/dist` a proxuje `/api/` na `localhost:8080`.

### Spuštění

```bash
sudo /opt/autoservis/deploy/setup.sh
```

Co skript udělá:

1. Zkopíruje `autoservis-backend.service` do `/etc/systemd/system/` a spustí `daemon-reload`.
2. Zkopíruje `autoservis-sudoers` do `/etc/sudoers.d/autoservis-deploy` (`chmod 440`) a ověří syntaxi (`visudo -c`) — chybná syntaxe by mohla rozbít `sudo` **pro celý systém**, proto se vždy validuje před uložením.
3. Ukončí případný ručně spuštěný `java -jar ...` proces (`pkill -f`).
4. Povolí (`enable`) a nastartuje (`start`) službu `autoservis-backend`.
5. Vypíše `systemctl status` pro vizuální kontrolu.

> **Spouští se přes `sudo`, protože mění systémové soubory (`/etc/systemd`, `/etc/sudoers.d`) — vyžádá si heslo. Po instalaci ho spouštět znovu netřeba.**

---

## 5. Aktualizace (běžný provoz)

Pro nasazení nové verze po pushnutí změn na GitLab (branch `master`):

```bash
bash /opt/autoservis/deploy.sh
```

Co skript udělá, v pořadí:

1. `git checkout master && git pull origin master`
2. `./mvnw -q clean package -DskipTests` — build backendu (bez testů, kvůli rychlosti)
3. `npm ci && npm run build` ve `frontend/autoservis-frontend` — build frontendu
4. `sudo systemctl restart autoservis-backend` — restart (díky sudoers pravidlu z kroku 4.2 **bez hesla**)
5. Ověří `systemctl is-active` — pokud služba neběží, skript skončí chybou (`exit 1`)

Frontend se nijak "nasazovat" nemusí zvlášť — nginx čte `dist/` přímo z disku, takže po přebuildování je nová verze hned dostupná (jen je vhodné v prohlížeči udělat hard refresh kvůli cache).

> **Nezaměňovat `setup.sh` a `deploy.sh`.** `setup.sh` = jednorázová instalace služby, nedělá `git pull` ani build. `deploy.sh` = běžná aktualizace, tohle se spouští pokaždé.

---

## 6. Diagnostika

```bash
# Stav služby
systemctl status autoservis-backend

# Live logy
journalctl -u autoservis-backend -f

# Posledních 50 řádků logu
journalctl -u autoservis-backend -n 50

# Běží backend na portu 8080?
ss -tlnp | grep 8080

# Jaký commit je aktuálně nasazený?
cd /opt/autoservis && git log -1

# Je na GitLabu něco nového k pullnutí?
cd /opt/autoservis && git fetch && git log --oneline HEAD..origin/master
```

**Appka se po deployi netváří jako aktualizovaná?** Nejčastější příčiny:
- Spustil se `setup.sh` místo `deploy.sh` (setup nepullu­je ani nebuilduje).
- Změna byla commitnutá, ale nepushnutá na GitLab (`git log HEAD..origin/master` je prázdné).
- Frontend změna je v prohlížeči zacachovaná — hard refresh (`Ctrl+Shift+R`).

---

## 7. Bezpečnostní poznámky

- `.env` má `chmod 600`, majitel `pka` — nečíst si ho vypisovat do sdílených logů/konzolí.
- Sudoers pravidlo je záměrně **úzké** (jmenovitě vyjmenované příkazy, ne `ALL`) — kompromitovaný deploy proces nemůže spustit libovolný root příkaz, jen restartovat tuhle jednu službu.
- Pokud dojde k úniku některého ze secrets (např. omylem vypsaný do logu/konzole), je nutné ho **rotovat** — vygenerovat nový a přepsat v `.env`, poté `sudo systemctl restart autoservis-backend`.
