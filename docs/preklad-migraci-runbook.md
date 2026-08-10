# Runbook: nasazení přeložených migrací (etapa E10 překladu komentářů)

**Datum vzniku:** 2026-08-10 · **Větev:** `preklad-komentaru` · **Kontext:** [preklad-komentaru.md](preklad-komentaru.md)

## Co se stalo a proč je tento runbook potřeba

Komentáře ve všech 95 migračních souborech (`db/migration`, `db/demo`, `db/prod`)
byly přeloženy do češtiny — jednorázová odsouhlasená výjimka z pravidla R-09.
Změnily se **výhradně**:

1. SQL komentáře (`--` a `/* */`),
2. texty v `COMMENT ON ... IS '...'` (metadata, ne struktura),

což strojově ověřuje skript porovnáním normalizovaného SQL před/po
(všech 95 souborů: **OK**). Navíc přibyla nová migrace
`V95__ceske_komentare_db.sql`, která české `COMMENT ON` texty znovu aplikuje
na **existující** databáze (nové je dostanou už z přeložených V1–V94).

**Problém:** Flyway ukládá do `flyway_schema_history` checksum celého souboru
včetně komentářů. Na každé databázi, kde už migrace V1–V94 běžely (lokální dev,
**produkce**), validace při startu aplikace spadne na neshodě checksumů.
Řešení je `flyway repair` — přepíše uložené checksumy podle souborů na disku.
Data ani struktura DB se nijak nemění.

**Čerstvé databáze** (Testcontainers testy, nová instalace) problém nemají —
migrace se aplikují od nuly s novými checksumy. Plný testovací běh
(`./mvnw test`) to ověřuje.

## Postup nasazení — POŘADÍ JE ZÁVAZNÉ

### A) Lokální vývojová DB (před prvním lokálním startem nové verze)

> ✅ **Provedeno 2026-08-10** — repair + migrate (V95, V96) aplikováno,
> validace 95 migrací OK. Krok níže je pro případné další lokální DB.

```bash
cd /home/pka/JAVA/autoservis
./mvnw org.flywaydb:flyway-maven-plugin:11.14.1:repair \
  -Dflyway.url=jdbc:postgresql://localhost:5432/autoservis \
  -Dflyway.user=postgres \
  -Dflyway.password="$DB_PASSWORD" \
  -Dflyway.locations=filesystem:src/main/resources/db/migration,filesystem:src/main/resources/db/demo \
  -Dflyway.defaultSchema=public
# poté normálně: ./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

(Verze pluginu 11.14.1 odpovídá `flyway-core` v projektu — ověřeno
`./mvnw dependency:list`; při povýšení Flyway srovnat.)

### B) Produkce (ssh pka@10.8.0.1)

**Zásada: repair proběhne PO nahrání nových souborů a PŘED startem aplikace.**

1. Zastavit aplikaci (systemd službu).
2. Nasadit nový build (JAR obsahuje přeložené migrace i V95).
3. Spustit repair proti produkční DB — buď z rozbaleného JARu, nebo z checkoutu
   repa se stejnou verzí souborů:
   ```bash
   ./mvnw org.flywaydb:flyway-maven-plugin:11.14.1:repair \
     -Dflyway.url=jdbc:postgresql://localhost:5432/autoservis \
     -Dflyway.user=<produkční uživatel> \
     -Dflyway.password=<z prod .env> \
     -Dflyway.locations=filesystem:src/main/resources/db/migration,filesystem:src/main/resources/db/prod \
     -Dflyway.defaultSchema=public
   ```
   ⚠️ Produkce používá `db/migration` + `db/prod` (bez `db/demo`) — locations
   musí odpovídat `application-prod.yaml`, jinak repair přepočítá checksumy
   podle špatné sady.
4. Spustit aplikaci. Flyway zvaliduje opravené checksumy a aplikuje **V95**
   (jen `COMMENT ON` — bez zásahu do dat).
5. Kontrola: log obsahuje `Successfully applied 1 migration` (V95) a žádnou
   `Validate failed`.

### C) Když se něco pokazí

- `Validate failed: Migration checksum mismatch` po startu → repair neproběhl,
  nebo proběhl proti jiné sadě souborů/locations. Aplikaci zastavit, zopakovat
  krok 3 se správnými locations.
- Repair je vratný v tom smyslu, že nic nemaže — jen přepisuje checksumy
  v `flyway_schema_history`. Nouzový návrat: nasadit předchozí build a spustit
  repair znovu proti starým souborům.

## Co repair NEDĚLÁ

- Nemění žádnou aplikační tabulku, data ani strukturu.
- Nespouští žádné migrace (V95 aplikuje až běžný start aplikace).
