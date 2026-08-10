Klíč se nikdy nedává do `application.yml` ani do gitu — proto v konfiguraci je jen placeholder `${ANTHROPIC_API_KEY}`, který Spring načte z prostředí. Kam ho tedy reálně uložit záleží na tom, jestli aplikaci spouštíš ve vývoji, nebo na serveru.

## Vývoj na tvém stroji

Nejčistší je samostatný soubor s omezenými právy, ne přímo do `.bashrc` (kde by ho viděl každý shell):

```bash
mkdir -p ~/.config/autoservis
echo 'ANTHROPIC_API_KEY=sk-ant-tvuj-klic' > ~/.config/autoservis/env
chmod 600 ~/.config/autoservis/env
```

Před spuštěním aplikace ho načteš:

```bash
set -a; source ~/.config/autoservis/env; set +a
./mvnw spring-boot:run
```

Když to nechceš psát pokaždé, přidej si načtení do `~/.bashrc`:

```bash
echo 'set -a; [ -f ~/.config/autoservis/env ] && source ~/.config/autoservis/env; set +a' >> ~/.bashrc
```

Pokud spouštíš z IntelliJ, jde to i přes Run/Debug Configuration → Environment variables → `ANTHROPIC_API_KEY=...`. Jen pozor, že se to uloží do `.idea/workspace.xml` — ověř, že máš `.idea/` v `.gitignore`, jinak klíč skončí v repu.

## Produkční server (systemd)

Tam patří do `EnvironmentFile`, který čte jen služba:

```bash
sudo install -d -m 750 /etc/autoservis
echo 'ANTHROPIC_API_KEY=sk-ant-tvuj-klic' | sudo tee /etc/autoservis/autoservis.env
sudo chown root:autoservis /etc/autoservis/autoservis.env
sudo chmod 640 /etc/autoservis/autoservis.env
```

A v unit souboru:

```ini
# /etc/systemd/system/autoservis.service
[Service]
User=autoservis
EnvironmentFile=/etc/autoservis/autoservis.env
ExecStart=/usr/bin/java -jar /opt/autoservis/autoservis.jar
```

V `EnvironmentFile` se píše `KLIC=hodnota` bez `export`. Po změně `sudo systemctl daemon-reload && sudo systemctl restart autoservis`.

## Tři pravidla, na kterých záleží

Klíč nikdy necommituj a nedávej do `application.yml` — když potřebuješ lokální přepisy, použij `application-local.yml` v `.gitignore`, ale pro tajemství je env proměnná stejně lepší.

Práva souboru drž na 600 (dev) nebo 640 root:služba (produkce). A počítej s tím, že proměnné prostředí běžícího procesu jsou čitelné přes `/proc/PID/environ` pro vlastníka procesu a roota — proto se na produkci aplikace spouští pod vyhrazeným systémovým uživatelem, ne pod tvým účtem.

Kdyby klíč někdy unikl (třeba omylem do gitu), hned ho zneplatni a vygeneruj nový v Anthropic Console — klíč v historii commitů je třeba považovat za kompromitovaný i po smazání.

Pro úplnost: Spring AI ve skutečnosti čte property `spring.ai.anthropic.api-key`, takže by fungovala i přímo proměnná `SPRING_AI_ANTHROPIC_API_KEY` (relaxed binding) bez placeholderu v yaml. Ale tvoje současná konfigurace s `${ANTHROPIC_API_KEY}` je v pořádku a čitelnější.