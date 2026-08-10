# STK a registr vozidel

Aplikace umí načítat údaje o vozidlech přímo z oficiálního **Registru silničních vozidel** (dataovozidlech.cz, Ministerstvo dopravy) — včetně platnosti STK. Nemusíte nic opisovat z techničáku a datum STK máte vždy podle skutečnosti.

## Založení vozidla s načtením z registru

1. Otevřete **Vozidla → Nové vozidlo**.
2. V sekci *Registrace a identifikace* najdete blok **„Načíst z registru vozidel"**.
3. Vyberte, podle čeho hledat:
   - **VIN** — 17znakový kód vozidla,
   - **Číslo ORV** — osvědčení o registraci vozidla („malý techničák"), *nejpohodlnější volba: máte ho v ruce a vyplní se i VIN*,
   - **Číslo TP** — technický průkaz.
4. Zadejte číslo a klikněte **Načíst z registru**.

Formulář se předvyplní údaji z registru: značka, model, barva, objem a výkon motoru, datum první registrace, palivo — a **VIN**, pokud jste hledali podle ORV/TP. Co registr nezná, zůstane beze změny (nic se nemaže). Rok výroby registr neposkytuje, ten doplňte ručně.

Po uložení vozidla se **automaticky načte i stav STK** — bez dalšího kliknutí.

## Kde vidím stav STK

- **Detail vozidla** — karta **„STK a registr vozidel"**: platnost STK, stav vozidla v registru (např. PROVOZOVANÉ), datum evidenční prohlídky a kdy proběhlo poslední načtení.
- **Seznam vozidel** — sloupec **STK** s barevným štítkem.
- **Filtr „Končící STK"** v seznamu vozidel zobrazí jen vozidla, kterým STK končí do 30 dnů nebo už propadla — praktické pro obvolání zákazníků.

Barvy štítku:

- 🟢 **zelená** — STK platí déle než 30 dní,
- 🟠 **oranžová** — STK končí do 30 dnů,
- 🔴 **červená** — STK propadlá,
- ⚪ **šedá „—"** — údaje z registru zatím nejsou načtené.

## Proč nejde datum STK přepsat ručně?

Záměrně. Platnost STK je **autoritativní údaj státního registru** — ruční přepis by znamenal, že v aplikaci může být jiné datum, než jaké platí ve skutečnosti. Když se vám údaj zdá zastaralý (vozidlo bylo mezitím na prohlídce), klikněte na detailu vozidla na **„Aktualizovat z registru"** — registr se dotáže znovu a datum se přepíše podle skutečnosti.

Každé načtení se ukládá do historie, takže je vždy dohledatelné, kdy a s jakým výsledkem se registr dotazoval.

## Co znamenají chybové hlášky

- **„Vozidlo … nebylo v registru nalezeno."** — zadané číslo v českém registru není (překlep, zahraniční vozidlo, vozidlo dosud neregistrované). Zkontrolujte zadání; údaje lze vyplnit ručně, jen bez STK.
- **„Registr vozidel omezil počet dotazů…"** — registr povoluje 27 dotazů za minutu; chvíli počkejte a zkuste znovu.
- **„Registr vozidel neodpovídá."** — výpadek na straně registru; zkuste to později, na práci s vozidlem to nemá vliv.
- **„Registr vozidel odmítl API klíč."** — problém konfigurace; kontaktujte správce aplikace.
