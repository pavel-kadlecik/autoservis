# Správa uživatelů a hesel

Administrátor může v aplikaci spravovat přihlašovací účty ostatních zaměstnanců — založit nový účet, upravit roli, deaktivovat účet při odchodu zaměstnance nebo mu resetovat zapomenuté heslo. Každý přihlášený uživatel si navíc může kdykoliv sám změnit své heslo.

## Kde najdu správu uživatelů

V levém menu se položka **Uživatelé** zobrazuje jen administrátorům. Pokud ji nevidíte, nemáte na správu účtů oprávnění — obraťte se na administrátora.

## Založení nového účtu

1. Otevřete **Uživatelé → Nový uživatel**.
2. Vyplňte uživatelské jméno, email a počáteční heslo (alespoň 8 znaků).
3. Zaškrtněte alespoň jednu roli — podle toho, co bude uživatel v aplikaci dělat: **administrátor**, **vedoucí servisu**, nebo **mechanik**.
4. Uložte. Nový uživatel se může ihned přihlásit se zadaným heslem.

Uživatelské jméno ani email nesmí být v systému už použité — pokud ano, aplikace na to upozorní.

## Úprava účtu

Na detailu uživatele (Editovat) můžete změnit email a role. **Uživatelské jméno po založení změnit nejde** — je to trvalá identita účtu.

## Deaktivace a opětovná aktivace

V nabídce akcí u řádku uživatele zvolte **Deaktivovat** — uživatel se pak nemůže přihlásit, ale jeho historie v aplikaci (kdo co vytvořil, zpracoval apod.) zůstává zachovaná. **Aktivovat** účet znovu zapne.

Dvě věci si aplikace hlídá sama a nedovolí je:

- **nemůžete deaktivovat svůj vlastní účet** — abyste si omylem nezablokovali přístup,
- **nelze deaktivovat posledního administrátora** — aby aplikace nezůstala bez nikoho, kdo ji může spravovat.

## Uzamčení účtu po neúspěšných přihlášeních

Po **deseti** neúspěšných pokusech o přihlášení se účet **zamkne** a další pokus skončí hláškou
„Účet je zamčený" — i se správným heslem. Je to obrana proti hádání hesla.

Zámek **sám vyprší za 15 minut**; kdo si jen spletl heslo, se pak přihlásí bez cizí pomoci.
Nechce-li obsluha čekat, admin účet odemkne okamžitě **resetem hesla** (viz níže) — ten zároveň
nuluje počítadlo neúspěchů. Úspěšné přihlášení počítadlo nuluje taky.

## Reset hesla uživateli

Pokud si zaměstnanec nepamatuje heslo, administrátor mu ho může rovnou nastavit nové: v nabídce akcí u řádku uživatele zvolte **Resetovat heslo** a zadejte nové heslo (alespoň 8 znaků). Uživatele o tom informujte mimo aplikaci (osobně, telefonicky) — systém mu nové heslo sám neposílá.

## Změna vlastního hesla

Tohle může udělat kterýkoliv přihlášený uživatel, nejen administrátor. Vlevo dole pod svým jménem klikněte na **Změnit heslo**, zadejte současné heslo a nové heslo (alespoň 8 znaků). Na rozdíl od resetu administrátorem je tu potřeba znát to současné — je to ochrana proti tomu, aby heslo změnil někdo jiný než majitel účtu.

## Účet zamčený po opakovaném špatném heslu

Po **10 neúspěšných pokusech** o přihlášení se účet sám zamkne. Je to ochrana proti hádání hesla:
i kdyby někdo zadal správné heslo, přihlášení v té chvíli neprojde.

Zámek **není trvalý** — po **15 minutách** vyprší a stačí se přihlásit znovu. Kdo nechce čekat,
může požádat administrátora o **reset hesla**, který účet odemkne okamžitě.

Když se to stane opakovaně a vy si přitom heslo pamatujete, dejte vědět administrátorovi — může
to znamenat, že se někdo zkouší přihlásit na váš účet.

## Časté dotazy

**Zapomněl jsem heslo, co teď?** Přihlásit se bez hesla nejde — požádejte administrátora o reset (viz výše).

**Přihlašuju se správně, ale aplikace mě nepustí dál.** Pravděpodobně je účet zamčený po opakovaném
špatném hesle — počkejte 15 minut, nebo požádejte administrátora o reset hesla (viz výše).

**Proč nejde přejmenovat uživatelské jméno?** Je to trvalý identifikátor účtu napříč celou historií zakázek a faktur — změna by rozbila dohledatelnost, kdo co v aplikaci udělal.

**Vidím u sebe jen „Změnit heslo", ne „Uživatelé" — je to chyba?** Ne, správa uživatelů je dostupná jen administrátorům. Ke změně vlastního hesla oprávnění nepotřebujete.
