Dobrý přístup – většina nastavení (server, klíče, DuckDNS) je nezávislá na tom, kde server fyzicky je, takže to připravíme teď kompletně, a jen jednu konkrétní věc (přesměrování portu na routeru) budete muset zopakovat, až server přestěhujete k zákazníkovi. Označím vám jasně, co je "univerzální" a co bude potřeba předělat.

## Krok 1: Instalace WireGuard

```bash
sudo apt install wireguard -y
```

## Krok 2: Vygenerovat klíče serveru

WireGuard funguje na stejném principu jako SSH klíče, co jsme řešili dřív – pár veřejný/soukromý klíč, jen tady slouží k šifrování celého síťového tunelu, ne jen k přihlášení.

```bash
wg genkey | sudo tee /etc/wireguard/server_private.key | wg pubkey | sudo tee /etc/wireguard/server_public.key
sudo chmod 600 /etc/wireguard/server_private.key
```

Zobrazte si veřejný klíč (budete ho za chvíli potřebovat):

```bash
sudo cat /etc/wireguard/server_public.key
```

## Krok 3: Konfigurace serveru

```bash
sudo nano /etc/wireguard/wg0.conf
```

Obsah (nahraďte `PRIVATNI_KLIC_SERVERU` obsahem souboru `server_private.key`):

```ini
[Interface]
Address = 10.8.0.1/24
ListenPort = 51820
PrivateKey = PRIVATNI_KLIC_SERVERU
```

**Co to znamená:** VPN vytvoří vlastní virtuální síť `10.8.0.0/24` – server v ní bude mít adresu `10.8.0.1`, každé připojené zařízení (váš PC/mobil) dostane další adresu z tohohle rozsahu (`10.8.0.2`, `10.8.0.3`, atd.). Přes tuhle virtuální síť pak budete dosahovat na server stejně, jako byste byl fyzicky v servisu.

## Krok 4: Povolit ve firewallu

```bash
sudo ufw allow 51820/udp
```

## Krok 5: Spustit a nastavit automatický start

```bash
sudo systemctl enable wg-quick@wg0
sudo systemctl start wg-quick@wg0
```

Ověřit, že běží:

```bash
sudo wg show
```

## Krok 6: DuckDNS – tohle nastavíme rovnou univerzálně, i kdyby zákazník měl statickou IP

I kdyby výsledná síť u zákazníka měla statickou veřejnou IP, DuckDNS **neuškodí a ušetří vám starost** – budete se vždycky připojovat na stejnou "adresu" (např. `autoservis-dilna.duckdns.org`), bez ohledu na to, jestli se pod tím skrývá statická nebo dynamická IP.

1. Zaregistrujte se zdarma na [duckdns.org](https://www.duckdns.org) (přes Google/GitHub účet)
2. Vytvořte si subdoménu, např. `autoservis-dilna` → dostanete `autoservis-dilna.duckdns.org`
3. Zkopírujte si svůj **token** z DuckDNS stránky

Na serveru nastavte automatickou aktualizaci (aby DuckDNS vždycky věděl aktuální veřejnou IP):

```bash
mkdir ~/duckdns
nano ~/duckdns/duck.sh
```

```bash
echo url="https://www.duckdns.org/update?domains=autoservis-dilna&token=VAS_TOKEN&ip=" | curl -k -o ~/duckdns/duck.log -K -
```

```bash
chmod 700 ~/duckdns/duck.sh
crontab -e
```

Přidejte řádek (aktualizace každých 5 minut):

```
*/5 * * * * ~/duckdns/duck.sh >/dev/null 2>&1
```

---

**Tohle vše (kroky 1-6) je univerzální** a klidně to dokončete teď doma. Zbylé dva kroky (přesměrování portu na routeru + vytvoření klienta) uděláme příště – u portu na routeru vám dám postup obecně, ale konkrétní kliknutí se bude lišit podle značky routeru, ať už doma, nebo pak u zákazníka.

Zkuste kroky 1-6 a napište, až budete mít `sudo wg show` s běžícím rozhraním a DuckDNS doménu vytvořenou – pak přejdeme na klienta a přesměrování portu.


---

# Palova blbá otazka na IP.

Ne, nic tam doplňovat nemusíte – prázdné `ip=` je **záměrné a správné**, ne chyba nebo neúplný příkaz.

## Proč se nechává prázdné

Když DuckDNS dostane požadavek s prázdným `ip=`, automaticky použije **veřejnou IP adresu, ze které požadavek přišel** – tedy tu, kterou má právě vaše připojení k internetu v tu chvíli. Je to schválně navržené takhle, protože celý smysl skriptu je **automaticky nahlásit aktuální IP**, ať už je jakákoliv – nemusíte ji nikde ručně zjišťovat a vypisovat.

Je to podobné, jako byste na obálku napsal jen adresu příjemce a nechal pošťáka, ať sám pozná, odkud dopis posíláte, podle razítka na obálce – nemusíte to sám vypisovat.

## Kdy byste tam něco doplnil

Jen v neobvyklé situaci, kdy byste chtěl **ručně vynutit konkrétní IP** (např. testování, nebo speciální síťová konfigurace) – pak byste napsal `ip=1.2.3.4`. Pro váš běžný případ (automatická aktualizace přes cron každých 5 minut) je prázdné pole přesně to, co chcete.

---

Takže klidně nechte příkaz přesně tak, jak byl, a pokračujte dál – zkuste ho spustit ručně jednou napřed, ať uvidíte, že funguje, než se spolehnete na cron:

```bash
~/duckdns/duck.sh
cat ~/duckdns/duck.log
```

Měli byste v logu vidět `OK` (ne `KO`) jako potvrzení, že se aktualizace povedla.

---

## Krok 7 nastaveni wireguard na klientovi

Výborně, DuckDNS funguje – od teď máte stálou adresu (`autoservis-dilna.duckdns.org`), co vždy ukáže na vaši aktuální veřejnou IP, ať se mění, jak chce. Pojďme zkontrolovat i zbytek z minula, než půjdeme na klienta a router.

## Rychlá kontrola před dalším krokem

```bash
sudo wg show
```

Mělo by ukázat rozhraní `wg0` s vaším veřejným klíčem a portem `51820` – potvrďte mi, že tam něco vidíte (i kdyby zatím bez připojených peerů, to je v pořádku, žádného klienta jsme ještě nepřidali).

## Krok 7: Vytvořit klienta (váš PC nebo mobil)

Na **vašem PC** (ne na serveru) vygenerujte vlastní pár klíčů pro tenhle konkrétní počítač:

```bash
wg genkey | tee client_private.key | wg pubkey > client_public.key
```

(pokud nemáte WireGuard nástroje na svém PC, nainstalujte: `sudo apt install wireguard` – stejný balíček funguje jako pro server, tak pro klienta)

Zobrazte si oba klíče:

```bash
cat client_private.key
cat client_public.key
```

## Krok 8: Přidat klienta jako "peer" na server

Zpátky na serveru:

```bash
sudo nano /etc/wireguard/wg0.conf
```

Přidejte na konec souboru (za `[Interface]` sekci) nový blok – nahraďte `VEREJNY_KLIC_KLIENTA` obsahem `client_public.key` z vašeho PC:

```ini
[Peer]
PublicKey = VEREJNY_KLIC_KLIENTA
AllowedIPs = 10.8.0.2/32
```

**Co to znamená:** říkáme serveru "tohle konkrétní zařízení (identifikované jeho veřejným klíčem) smí být připojené a bude mít adresu `10.8.0.2`".

Načtěte novou konfiguraci bez nutnosti restartu celé služby:

```bash
sudo wg-quick down wg0
sudo wg-quick up wg0
```

## Krok 9: Konfigurace na straně klienta (vaše PC)

Na svém PC vytvořte soubor (nahraďte `PRIVATNI_KLIC_KLIENTA` a `VEREJNY_KLIC_SERVERU` skutečnými hodnotami):

```ini
[Interface]
PrivateKey = PRIVATNI_KLIC_KLIENTA
Address = 10.8.0.2/32

[Peer]
PublicKey = VEREJNY_KLIC_SERVERU
Endpoint = autoservis-dilna.duckdns.org:51820
AllowedIPs = 10.8.0.1/32
PersistentKeepalive = 25
```

**Vysvětlení `AllowedIPs = 10.8.0.1/32`:** tohle říká klientovi "přes tenhle tunel posílej jen provoz směřující na server samotný" – ne celý internet. Je to takzvaný "split tunnel" (na rozdíl od "full tunnel", kde by šel přes VPN úplně všechen internetový provoz). Pro naši potřebu (dostat se na server) je tohle přesně správné nastavení.

**`PersistentKeepalive = 25`:** posílá "jsem tu pořád" signál každých 25 vteřin – důležité, protože router/NAT by jinak mohl spojení po chvíli nečinnosti "zapomenout".

---

Pošlete mi výstup z `sudo wg show`, ať potvrdíme, že server běží, jak má – pak dokončíme klienta a přejdeme na poslední kus: přesměrování portu na routeru.

```
pka@dilna-server:~$ sudo wg show
interface: wg0
public key: ZiuLE...............7Uk38=
private key: (hidden)
listening port: 51820

peer: vFL2A0V01.................GeH4=
allowed ips: 10.8.0.2/32
```

---


Perfektní, server je správně nastavený a čeká na přesně toho jednoho klienta (vidím jeho veřejný klíč a přidělenou adresu `10.8.0.2/32`). Pojďme klienta připojit a otestovat tunel, než půjdeme na router.

## Krok 10: Spustit WireGuard na klientovi (vašem PC)

Uložte konfigurační soubor, co jsme připravili v kroku 9, jako:

```bash
sudo nano /etc/wireguard/wg0.conf
```

(stejný obsah, co jsem psal výše – s vašimi skutečnými klíči a `Endpoint = autoservis-dilna.duckdns.org:51820`)

Spusťte tunel:

```bash
sudo wg-quick up wg0
```

## Krok 11: Otestovat tunel – zatím jen uvnitř domácí sítě

Protože jste zatím doma (server i klient na stejné síti), tenhle test ověří, že **samotná WireGuard komunikace funguje**, ještě předtím, než zapojíme router:

```bash
ping -c 4 10.8.0.1
```

Pokud dostanete odpovědi, tunel funguje – gratuluji, právě jste vytvořil svoji první VPN.

## Krok 12: Zkusit SSH přes VPN adresu

```bash
ssh pka@10.8.0.1
```

Všimněte si – používáte teď **VPN adresu** (`10.8.0.1`), ne běžnou LAN adresu serveru. Pokud se připojíte, máte funkční důkaz, že celý koncept funguje.

---

## Krok 13: Přesměrování portu na routeru – teď to dává smysl otestovat i naostro

I doma to má smysl vyzkoušet, ať víte, jak se to dělá, až budete nastavovat u zákazníka:

1. Přihlaste se do administrace routeru (obvykle `192.168.1.1` v prohlížeči)
2. Najděte sekci **"Port Forwarding"** / "Přesměrování portů" / "Virtuální servery" (název se liší podle výrobce)
3. Vytvořte pravidlo:
    - **Protokol:** UDP
    - **Vnější port:** 51820
    - **Vnitřní IP:** LAN adresa serveru (aktuálně `192.168.1.220`, nebo výsledek DHCP rezervace, pokud jste ji nastavil)
    - **Vnitřní port:** 51820

## Krok 14: Pravý test – z mobilu na datech (ne na Wi-Fi doma!)

Tohle je klíčové – abyste otestoval **skutečné** připojení zvenčí, musíte to zkusit z něčeho, co **není** připojené k vaší domácí Wi-Fi (jinak byste jen zjišťoval, že vnitřní síť funguje, což už víme).

Na mobilu (s vypnutým Wi-Fi, na mobilních datech):
1. Nainstalujte aplikaci **WireGuard** (App Store / Google Play)
2. Vytvořte nového klienta stejným postupem (nový pár klíčů, nový peer na serveru s jinou adresou, např. `10.8.0.3/32`)
3. Zkuste se připojit

---

Zkuste kroky 10-12 (tunel uvnitř domácí sítě) a napište výsledek `ping` a `ssh` – to je základní ověření, než se pustíme do testu zvenčí přes mobil.

---
# DUCK DNS

Dobrá otázka na shrnutí – DuckDNS má v celé naší architektuře **přesně jednu úlohu**, ne víc: řeší problém s **měnící se veřejnou IP adresou** pro připojení k VPN.

## Konkrétní problém, který řeší

Vzpomeňte si na `Endpoint` v konfiguraci klienta:

```ini
[Peer]
Endpoint = autoservis-dilna.duckdns.org:51820
```

Tohle říká vašemu PC/mobilu **"kam se má připojit, aby vůbec navázal VPN tunel"**. Bez DuckDNS byste tam musel mít napsanou **konkrétní veřejnou IP adresu** – a to je problém, protože ta se u běžného domácího/firemního internetu (bez placeného "statická IP" tarifu) **občas mění** (poskytovatel ji čas od času přiděl jinou).

## Co by se stalo bez DuckDNS

```
Dnes: veřejná IP = 89.24.156.12  → funguje
Za týden: poskytovatel změní na 89.24.201.87 → VPN klient má v configu starou adresu → nedá se připojit
→ musel byste ručně zjišťovat novou IP a přepisovat konfiguraci klienta pokaždé
```

## Co dělá DuckDNS

Ten skript, co jsme nastavili přes cron (spouští se každých 5 minut), hlásí DuckDNS "moje aktuální IP je teď tahle" – DuckDNS si to poznamená a `autoservis-dilna.duckdns.org` **vždycky ukazuje na aktuální IP**, ať se mění, jak chce. Vy se tedy v klientovi připojujete pořád na **stejný název**, a překlad na aktuální IP se řeší automaticky na pozadí.

## Shrnutí – kde přesně v řetězci DuckDNS "žije"

```
VPN klient (vaše PC/mobil)
    │
    │ "Kam se mám připojit?" → dotaz na autoservis-dilna.duckdns.org
    ▼
DuckDNS → odpoví aktuální veřejnou IP
    │
    ▼
Router (přesměruje port 51820 na server)
    │
    ▼
WireGuard tunel se naváže
    │
    ▼
TEPRVE TEĎ jste "uvnitř" a můžete jít na 10.8.0.1 (appka, SSH, Cockpit, cokoliv)
```

DuckDNS tedy **nemá nic společného s appkou samotnou** ani s tím, jak appku otevíráte – jeho jediná práce končí ve chvíli, kdy se VPN tunel úspěšně sestaví. Všechno, co děláte potom (web, SSH, deploy skript), už jde přes VPN adresu `10.8.0.1`, se kterou DuckDNS vůbec nesouvisí.
