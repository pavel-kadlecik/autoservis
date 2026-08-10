Zvládneme oboje – pojďme na to systematicky. Nejdřív síť pro zítřek, pak zálohy jako druhý ucelený blok.

---

# ČÁST A: Přesun serveru k zákazníkovi – kompletní postup

## Krok A1: Fyzické připojení – kabel, ne Wi-Fi

Jak jsme si od začátku říkali – u zákazníka **zapojte server kabelem** do routeru/switche. Tohle je ten moment, kdy se to konečně realizuje.

## Krok A2: Zjistit síťové údaje u zákazníka

Po zapojení kabelu na serveru:

```bash
ip a
```

Najděte kabelové rozhraní (bude jiné jméno než `wlp3s0`, typicky `enp1s0` nebo podobně) a zkontrolujte, že dostalo IP adresu automaticky přes DHCP zákazníkova routeru.

```bash
ip route | grep default
```

Zjistíte gateway (adresu routeru u zákazníka, např. `192.168.0.1` – čísla se budou lišit od vaší domácí sítě).

## Krok A3: Nastavit STATICKOU IP na serveru (teď už s kabelem, děláme to napevno)

```bash
sudo nano /etc/netplan/50-cloud-init.yaml
```

```yaml
network:
  version: 2
  ethernets:
    enp1s0:              # název podle toho, co ukázal "ip a"
      dhcp4: false
      addresses:
        - 192.168.0.50/24     # zvolte adresu MIMO DHCP rozsah zákazníkova routeru
      routes:
        - to: default
          via: 192.168.0.1     # gateway zákazníka, podle "ip route"
      nameservers:
        addresses: [1.1.1.1, 8.8.8.8]
```

**Důležité:** než zvolíte `192.168.0.50`, podívejte se do administrace routeru u zákazníka na rozsah DHCP (stejně jako jsme řešili dřív) a vyberte adresu mimo něj.

```bash
sudo netplan apply
```

Ověřte:

```bash
ip a
ping -c 4 192.168.0.1
```

## Krok A4: Přesměrování portu na ZÁKAZNÍKOVĚ routeru

Přihlaste se do administrace routeru u zákazníka (typicky `192.168.0.1` v prohlížeči) a vytvořte pravidlo:

| Nastavení | Hodnota |
|---|---|
| Protokol | UDP |
| Vnější port | 51820 |
| Vnitřní IP | `192.168.0.50` (ta, co jste právě nastavil) |
| Vnitřní port | 51820 |

**Kritické upozornění:** přesměrujte **jen** tenhle jeden port. **Nikdy nepřesměrovávejte port 22 (SSH) ani 80/443 (appka) přímo ven** – to je přesně to, čemu jsme se od začátku vyhýbali. Jediná cesta dovnitř zvenčí má být přes VPN tunel.

## Krok A5: DuckDNS – nemusíte dělat nic

Tohle je hezká vlastnost toho, jak jsme to postavili: cron skript na serveru běží dál stejně jako doma, jen teď nahlásí **novou veřejnou IP zákazníka** místo vaší domácí. `autoservis-dilna.duckdns.org` se **automaticky** do pár minut přepne na správnou adresu. Nic ručně upravovat nemusíte.

Pro jistotu po zapojení ověřte:

```bash
cat ~/duckdns/duck.log
```

## Krok A6: Vrátit klienta (vaše PC) zpátky na DuckDNS endpoint

Tohle je nejdůležitější krok, na který **nesmíte zapomenout** – když jsme řešili tu chybu s pořadím klíčů, dočasně jsme přepnuli endpoint na LAN adresu pro lokální test. Teď to musíte vrátit zpátky, jinak se z venku (od zákazníka pryč) nepřipojíte:

```bash
sudo nano /etc/wireguard/wg0.conf
```

```ini
[Peer]
PublicKey = ZiuLEJ6EXYa8wja1Ts3i2wui5B9cFn7Wz60lRv7Uk38=
Endpoint = autoservis-dilna.duckdns.org:51820
AllowedIPs = 10.8.0.1/32
PersistentKeepalive = 25
```

```bash
sudo wg-quick down wg0
sudo wg-quick up wg0
```

## Krok A7: Skutečný test zvenčí

Až budete od zákazníka odcházet (nebo z mobilu na datech, ne na jeho Wi-Fi), zkuste:

```bash
ping -c 4 10.8.0.1
ssh pka@10.8.0.1
```

Tohle bude **první opravdový test** toho, co jsme celou dobu budovali – přístup odjinud z internetu, ne jen v rámci jedné LAN.

## Krok A8 (doporučené zpřísnění, teď to dává smysl): Omezit SSH jen na VPN

Protože je teď server poprvé skutečně vystavený na internet (byť jen přes jeden VPN port), je čas dotáhnout bezpečnostní opatření, o kterém jsme mluvili na začátku:

```bash
sudo ufw status numbered
```

Podívejte se, jestli tam SSH (port 22) je povolené obecně. Pokud ano, omezte ho jen na LAN a VPN rozsah:

```bash
sudo ufw delete allow 22/tcp
sudo ufw allow from 192.168.0.0/24 to any port 22 proto tcp
sudo ufw allow from 10.8.0.0/24 to any port 22 proto tcp
```

(první rozsah nechá fungovat SSH z lokální sítě zákazníka, druhý z VPN – zvenčí přímo na SSH se nikdo nedostane, i kdyby náhodou uhodl IP)

---

# ČÁST B: Zálohování – konkrétní řešení

Tohle jsme v konverzaci zmiňovali principiálně (noční zálohy, offsite kopie, test obnovy, SMART monitoring), ale nikdy jsme to nezapsali do skutečného skriptu. Napravme to teď – tohle je obzvlášť naléhavé, protože nemáte RAID, takže zálohy jsou vaše **jediná** ochrana.

## B1: Zálohovací skript databáze

```bash
sudo mkdir -p /opt/backups/db
sudo nano /opt/backups/pg_backup.sh
```

```bash
#!/bin/bash
set -e

BACKUP_DIR="/opt/backups/db"
DATE=$(date +%Y-%m-%d_%H%M)
RETENTION_DAYS=14

# Vytvořit zálohu (formát "custom" - umožňuje selektivní obnovu, komprimovaný)
PGPASSWORD="$DB_PASSWORD" pg_dump -h localhost -U autoservis_app -d autoservis -Fc \
    -f "$BACKUP_DIR/autoservis_$DATE.dump"

# Smazat zálohy starší než RETENTION_DAYS
find "$BACKUP_DIR" -name "autoservis_*.dump" -mtime +$RETENTION_DAYS -delete

echo "$(date): Záloha dokončena - autoservis_$DATE.dump" >> /opt/backups/backup.log
```

```bash
sudo chmod +x /opt/backups/pg_backup.sh
```

**Proč formát `-Fc` (custom):** na rozdíl od obyčejného textového SQL dumpu umožňuje obnovit **jen konkrétní tabulku**, ne nutně celou databázi, a je automaticky komprimovaný.

**Kde je heslo:** skript potřebuje `$DB_PASSWORD` – uložte ho bezpečně mimo samotný skript:

```bash
sudo nano /etc/backup.env
```
```
DB_PASSWORD=vase_heslo_autoservis_app
```
```bash
sudo chmod 600 /etc/backup.env
```

A upravte první řádek skriptu, ať si ho načte:

```bash
#!/bin/bash
set -e
source /etc/backup.env
```

## B2: Naplánovat přes cron (každou noc)

```bash
sudo crontab -e
```

```
0 2 * * * /opt/backups/pg_backup.sh
```

(spustí se každou noc ve 2:00, kdy appku nikdo nepoužívá)

## B3: Offsite kopie – doporučuji `restic` + cloud úložiště

Lokální záloha na stejném disku vás nezachrání při krádeži/požáru/poruše disku. Doporučuji **restic** (moderní, šifrovaný, umí posílat přímo do cloudu) společně s **Backblaze B2** (levné cloudové úložiště, cca 6 Kč/GB/měsíc, běžně používané přesně pro tenhle účel):

```bash
sudo apt install restic -y
```

Inicializace repozitáře (jednorázově):

```bash
export RESTIC_REPOSITORY="b2:nazev-vaseho-bucketu:autoservis-backup"
export RESTIC_PASSWORD="silne-heslo-pro-sifrovani-zaloh"
export B2_ACCOUNT_ID="vas-b2-account-id"
export B2_ACCOUNT_KEY="vas-b2-account-key"

restic init
```

Přidejte do zálohovacího skriptu na konec (za `pg_dump` část):

```bash
source /etc/backup.env
restic backup "$BACKUP_DIR" /opt/autoservis --tag "autoservis-$DATE"
restic forget --keep-daily 14 --keep-weekly 8 --keep-monthly 6 --prune
```

**Co se zálohuje:** dumpy databáze + celý adresář appky (konfigurace, `application-prod.yaml`, atd.) – ne jen databáze samotná.

## B4: Test obnovy – **udělejte tohle nyní, ne až to budete potřebovat**

```bash
# Vytvořit testovací databázi
sudo -u postgres createdb autoservis_test

# Obnovit do ní z posledního dumpu
pg_restore -h localhost -U autoservis_app -d autoservis_test /opt/backups/db/autoservis_NEJNOVEJSI.dump

# Zkontrolovat, že tam data jsou
psql -h localhost -U autoservis_app -d autoservis_test -c "SELECT count(*) FROM customer.customers;"

# Uklidit testovací databázi
sudo -u postgres dropdb autoservis_test
```

Pokud tohle projde bez chyby a vidíte reálný počet zákazníků, víte jistě, že vaše zálohy **skutečně fungují**, ne jen že se skript "tváří" jako by proběhl.

## B5: SMART monitoring disku (kompenzace za chybějící RAID)

```bash
sudo apt install smartmontools -y
sudo systemctl enable smartd
sudo systemctl start smartd
```

Zkontrolovat aktuální zdraví disku:

```bash
sudo smartctl -a /dev/nvme0n1
```

Hledejte hlavně řádek `SMART overall-health self-assessment test result` – mělo by psát `PASSED`.

---

## Shrnutí – co udělat v jakém pořadí zítra

1. Kabel, static IP (A2-A3)
2. Port forwarding na routeru zákazníka (A4)
3. Ověřit DuckDNS (A5)
4. Vrátit klienta na DuckDNS endpoint (A6)
5. Otestovat zvenčí (A7)
6. Zpřísnit SSH firewall (A8)
7. Nastavit zálohovací skript + cron (B1-B2)
8. Nastavit offsite kopii (B3)
9. **Otestovat obnovu** (B4) – nepřeskakujte tenhle krok
10. SMART monitoring (B5)

Hodně štěstí zítra u zákazníka – napište, jak to dopadlo, nebo kdyby cokoliv nesedělo podle plánu.

---

# Záloha

Prošel jsem aktuální srovnání (ceny i podmínky se v cloud storage mění docela často), a pro váš konkrétní případ je jasný vítěz.

## Doporučení: Backblaze B2, konkrétně evropský region (Amsterdam)

## Proč zrovna B2, ne třeba Wasabi (druhá nejčastěji doporučovaná varianta)

Tady je ten rozhodující důvod, který se týká přímo způsobu, jak jsme si zálohování naplánovali: náš skript pravidelně **maže staré zálohy** (`restic forget --keep-daily 14 ...`) – to je s Wasabi problém, protože Wasabi vyžaduje minimální dobu uložení 90 dní – i smazané soubory se účtují po celou tuto dobu. Znamená to, že byste **platili i za zálohy, které jsme si nechali smazat**, jen proto, že ještě neuplynulo 90 dní od jejich nahrání.

Backblaze B2 naproti tomu nemá žádnou minimální dobu uložení – platíte přesně za to, co tam reálně leží, žádné skryté "penále" za rotaci záloh.

## Další důvody pro B2 konkrétně pro váš případ

1. **Verzování** – Backblaze B2 umožňuje obnovit přepsaný nebo smazaný soubor z předchozího bodu v čase – důležitá ochrana proti ransomwaru nebo omylem smazaným datům. Wasabi tohle nativně nenabízí.
2. **Bezplatný odchozí provoz do 3× objemu dat** – volný odchozí provoz až do trojnásobku uloženého objemu měsíčně. To je přesně scénář "test obnovy zálohy", co jsme si naplánovali dělat pravidelně – nebudete za tyhle testy nic platit navíc.
3. **Evropský region** – Backblaze B2 provozuje regiony v US West, US East a EU Central, takže data zůstanou v EU – relevantní kvůli GDPR, protože appka obsahuje osobní údaje zákazníků.
4. **Plná S3 kompatibilita s `restic`** – přesně nástroj, který jsme si vybrali, funguje s B2 bez jakékoliv úpravy.

## Kolik to bude reálně stát

Backblaze B2 stojí 6,95 $/TB/měsíc (aktuální sazba od května 2026). Vaše data (databázové dumpy malého autoservisu + konfigurace appky) budou v řádu **stovek MB až jednotek GB** i po letech provozu – takže reálný měsíční náklad bude v řádu **desítek korun, ne stovek**. Cena je na vaší škále prakticky zanedbatelná, rozhodovat by měly vlastnosti, ne cenovka.

## Praktický krok – založení účtu

1. Zaregistrujte se na [backblaze.com/b2](https://www.backblaze.com/b2/cloud-storage.html)
2. Při vytváření "Bucketu" zvolte region **EU (Amsterdam)**
3. Vygenerujte **Application Key** (najdete v sekci "App Keys") – to jsou vaše `B2_ACCOUNT_ID` a `B2_ACCOUNT_KEY`, které jsme už měli připravené v `restic` konfiguraci z minulého kroku

Zbytek (inicializace repozitáře, zálohovací skript) zůstává přesně tak, jak jsme si to popsali – jen `RESTIC_REPOSITORY` bude ukazovat na tenhle nově založený bucket.