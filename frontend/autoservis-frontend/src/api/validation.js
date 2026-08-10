/**
 * Validační vzory a délky **zrcadlící serverová DTO** (audit KN-14 / 11-F-4).
 *
 * Proč to existuje: formulář zákazníka slibuje inline validaci („Pole označená * jsou povinná"
 * + `needs-validation`), ale u IČO, DIČ, jmen a délek nekontroloval nic — `checkValidity()`
 * propustil cokoli a uživatel dostal až generickou 400 ze serveru. Chybný formát IČO je přitom
 * běžná chyba přepisu z dokladu.
 *
 * **Při změně DTO změň i tohle.** Je to vědomá duplicita pravidla na dvou místech (server zůstává
 * autoritativní), stejně jako `ALLOWED_UNITS` v `api/units.js` zrcadlí `warehouse.import.allowed-units`.
 * Zdroj pravdy pro každý řádek je uvedený v komentáři.
 */

/** `CustomerDto` `@Pattern("^$|^\\d{8}$")` — 8 číslic, nebo prázdno. */
export const ICO_PATTERN = "\\d{8}";
export const ICO_MAX = 8;

/** `CustomerDto` `@Pattern("^$|^CZ\\d{8,10}$")` — DIČ vždy s předponou CZ. */
export const DIC_PATTERN = "CZ\\d{8,10}";
export const DIC_MAX = 12;

/**
 * `CustomerDto` `@Pattern("^$|^\\+?[\\d\\s\\-()]{7,20}$")` — číslice, mezery, pomlčky, závorky.
 * Formulář dřív povoloval 30 znaků, tedy **víc než server** (audit): 21 znaků prošlo formulářem
 * a spadlo až na 400.
 *
 * ⚠️ **Závorky musí být escapované, i když v Javě být nemusí.** HTML atribut `pattern` prohlížeč
 * kompiluje s příznakem `v`, ve kterém je neescapované `(` nebo `)` uvnitř třídy znaků
 * syntaktická chyba — a **vadný vzor se tiše ignoruje**, takže pole nevaliduje vůbec nic.
 * Odhaleno prokliknutím: se zápisem `[\d\s\-()]` prošlo formulářem „+420 123 456 789 klapka 22".
 */
export const PHONE_PATTERN = "\\+?[\\d\\s\\-\\(\\)]{7,20}";
export const PHONE_MAX = 21;

/** `AddressDto` `@Pattern("^\\d{3}\\s?\\d{2}$")` — PSČ s volitelnou mezerou. */
export const POSTAL_CODE_PATTERN = "\\d{3}\\s?\\d{2}";

/** Délky z `@Size` v `CustomerDto` — pole, kde formulář žádný limit neměl. */
export const CUSTOMER_MAX = {
    firstName: 100,
    lastName: 100,
    companyName: 255,
    legalForm: 100,
    primaryEmail: 255,
    internalNote: 2000,
};
