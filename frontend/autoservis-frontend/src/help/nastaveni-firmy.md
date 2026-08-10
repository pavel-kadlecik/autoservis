# Fakturační údaje

Údaje vaší firmy, které se tisknou na **faktury** — název, IČ/DIČ, adresa, **bankovní spojení** a **nastavení číslování faktur a pokladních dokladů**. Upravovat je smí jen **vedení** (role Administrátor / Manažer). Najdete je v menu **Nastavení → Fakturační údaje**.

## Co vyplnit

- **Název, IČ, DIČ** a **adresu** (ulice a číslo, město, PSČ, země),
- **Bankovní spojení**: číslo účtu, **IBAN**, SWIFT/BIC.

## IBAN = QR platba na faktuře

Aby na faktuře vznikla **QR platba**, musí být vyplněný **IBAN**. Bez něj se QR nevygeneruje. (QR platbu čtou české a slovenské banky; SWIFT/BIC se do ní nedává — je to údaj pro zahraniční platby.)

## Číslování faktur

Číslo dostává faktura **při vystavení** (koncept ho ještě nemá — díky tomu po zrušeném konceptu nezůstane v řadě mezera). Přepínač **„Generovat číslo faktury podle masky"** určuje, co uvidíte v dialogu vystavení:

- **Zapnuto** — číslo se **předvyplní** podle masky a navazuje na číselnou řadu.
- **Vypnuto** — pole zůstane prázdné.

V obou režimech můžete před vystavením zapsat **libovolné** číslo; maska je jen předpis, podle kterého se skládá návrh. Hlídá se unikátnost a délka do 20 znaků. Číslo v jiném tvaru, než má maska, řadu neovlivní: příští faktura naváže na nejvyšší číslo, které masce odpovídá (v řadě po něm ovšem zůstane mezera, tak s ním šetřete).

### Maska číselné řady

Maska je šablona čísla. Tokeny ve složených závorkách se nahradí, vše ostatní (lomítka, pomlčky, písmena) zůstává:

| Token | Význam | Příklad |
|---|---|---|
| `{RRRR}` | rok, 4 číslice | 2026 |
| `{RR}` | rok, poslední 2 číslice | 26 |
| `{MM}` | měsíc, 2 číslice | 08 |
| `{N}`, `{NN}`, `{NNN}`… | pořadové číslo — počet N určuje šířku doplněnou nulami | 1 / 01 / 001 |

Příklady: maska `{N}/{RR}` dává čísla `17/26`; výchozí maska `{RRRR}{MM}{NNN}` dává `202608001`.

**Reset řady plyne z masky sám:** obsahuje-li `{MM}`, čísluje se od 1 každý **měsíc**; jinak s rokem každý **rok**; bez roku řada pokračuje bez konce. Rok a měsíc se berou z **data vystavení** faktury.

### Mezery v řadě

Koncept faktury číslo **nemá** — dostane ho až vystavením, takže zrušený koncept řadu nenaruší. Mezera může vzniknout smazáním vystavené faktury, kterou zákazník nedostal (u jiné než poslední v řadě), nebo ručním zápisem čísla mimo řadu. Zapnete-li **Hlídání mezer**, aplikace na chybějící čísla aktuálního období upozorní; mezeru zavřete tím, že příští doklad vystavíte s chybějícím číslem místo navrženého. Volitelné pole **Hlídat od čísla** se hodí po přechodu z jiného systému — starší čísla se ignorují.

## Číslování pokladních dokladů

Pokladní doklad nemá koncept: číslo dostává **hned při vystavení** a v dialogu ho vždy můžete přepsat. **Zdroj čísla** — tedy co se do dialogu předvyplní — si vyberete:

- **Podle masky (vlastní řada)** — funguje stejně jako u faktur, jen s vlastní maskou (výchozí `PPD{RRRR}{MM}{NNN}` dává `PPD202608001`). K dispozici je i hlídání mezer; díra v řadě vzniká smazáním dokladu — jak ji zavřít, popisuje nápověda *Příjmový pokladní doklad*.
- **Podle čísla faktury** — doklad dostane číslo hrazené faktury. Účetní pak páruje platbu s fakturou na první pohled a vlastní řada pokladních dokladů se nevede. Hlídání mezer je v tomto režimu vypnuté: hotově se platí jen některé faktury, takže „díry" mezi čísly dokladů jsou faktury zaplacené převodem, ne chyba — souvislost řady hlídá kontrola mezer u číslování faktur.
- **Ručně** — pole zůstane prázdné a číslo píšete celé sami.

## Změny se promítnou jen do nových faktur

Údaje firmy se na fakturu **zmrazí při jejím vystavení**. Když je tady později změníte, projeví se to jen na **nově** vystavených fakturách — dříve vystavené doklady zůstanou beze změny (jsou to neměnné doklady). Změna masky ovlivní jen nově zakládané faktury.
