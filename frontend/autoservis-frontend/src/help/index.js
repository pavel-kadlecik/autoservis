import dashboard from './dashboard.md?raw';
import zakaznici from './zakaznici.md?raw';
import vozidla from './vozidla.md?raw';
import planovani from './planovani.md?raw';
import zakazky from './zakazky.md?raw';
import faktury from './faktury.md?raw';
import dobropis from './dobropis.md?raw';
import stkRegistr from './stk-registr.md?raw';
import skladPrehled from './sklad-prehled.md?raw';
import prijemZbozi from './prijem-zbozi.md?raw';
import skladPohyby from './sklad-pohyby.md?raw';
import inventura from './inventura.md?raw';
import dodavatele from './dodavatele.md?raw';
import prijmovyPokladniDoklad from './prijmovy-pokladni-doklad.md?raw';
import nastaveniFirmy from './nastaveni-firmy.md?raw';
import zamestnanci from './zamestnanci.md?raw';
import spravaUzivatelu from './sprava-uzivatelu.md?raw';

/**
 * Registr článků nápovědy zobrazovaných na /help.
 *
 * Přidání článku = nový .md soubor v tomto adresáři + řádek sem.
 * Konvence: slug = název souboru bez přípony; obsah česky, jazykem
 * obsluhy servisu (ne vývojáře). Markdown včetně **GFM** (rozhodnutí R-4,
 * U7.2): tabulky, přeškrtnutý text a automatické odkazy fungují. HTML
 * v článcích zůstává zakázané — `rehype-raw` se vědomě nepoužívá.
 *
 * Párový dokument pro vývojáře patří do docs/funkce/ (viz CLAUDE.md).
 */
export const HELP_ARTICLES = [
    {slug: 'dashboard', title: 'Přehled (úvodní stránka)', content: dashboard},
    {slug: 'zakaznici', title: 'Zákazníci', content: zakaznici},
    {slug: 'vozidla', title: 'Vozidla', content: vozidla},
    {slug: 'planovani', title: 'Plánování', content: planovani},
    {slug: 'zakazky', title: 'Zakázky', content: zakazky},
    {slug: 'faktury', title: 'Faktury', content: faktury},
    {slug: 'dobropis', title: 'Opravný daňový doklad (dobropis)', content: dobropis},
    {slug: 'stk-registr', title: 'STK a registr vozidel', content: stkRegistr},
    {slug: 'sklad-prehled', title: 'Přehled skladu a karta dílu', content: skladPrehled},
    {slug: 'prijem-zbozi', title: 'Příjem zboží na sklad', content: prijemZbozi},
    {slug: 'sklad-pohyby', title: 'Opravy stavu skladu', content: skladPohyby},
    {slug: 'inventura', title: 'Inventura', content: inventura},
    {slug: 'dodavatele', title: 'Dodavatelé', content: dodavatele},
    {slug: 'prijmovy-pokladni-doklad', title: 'Příjmový pokladní doklad', content: prijmovyPokladniDoklad},
    {slug: 'nastaveni-firmy', title: 'Fakturační údaje', content: nastaveniFirmy},
    {slug: 'zamestnanci', title: 'Zaměstnanci a náklad práce', content: zamestnanci},
    {slug: 'sprava-uzivatelu', title: 'Správa uživatelů a hesel', content: spravaUzivatelu},
];
