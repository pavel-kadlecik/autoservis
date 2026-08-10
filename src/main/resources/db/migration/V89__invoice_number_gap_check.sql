-- ============================================================================
-- V89 — hlídání mezer v číselné řadě faktur
-- ============================================================================
-- Od V88 lze smazat nepředanou vystavenou fakturu. U poslední v řadě se číslo
-- uvolní a mezera nevznikne, u starší po ní zůstane díra — a `MAX+1` ji sám
-- nezavře. Zavřít se dá ručně: číslo je při vystavení editovatelné, takže se
-- příští faktura vystaví s tím chybějícím.
--
-- Aby se na to nepřišlo až u kontroly, dostává obsluha varování nad seznamem
-- faktur (rozhodnutí uživatele 2026-08-08). Hlídání je VOLITELNÉ a má vlastní
-- začátek, protože historická data přenesená odjinud řadu typicky nedodržují
-- a bez startovního čísla by hláška křičela od prvního dne.
--
-- Hlídá se jen **aktuální období** podle masky (měsíc u {MM}, jinak rok) —
-- rozhodnutí uživatele: díra se nejsnáz zavře tentýž měsíc a hláška tím zůstane
-- akceschopná. Zároveň to ohraničuje otravnost: přetečením do dalšího období
-- upozornění zmizí samo, i když se díra nezaplnila.
--
-- Detekce je v aplikaci, ne v SQL: masku umí rozebrat jen `InvoiceNumberMask`
-- (proto je i generátor čísel jako jediná řada v projektu mimo DB trigger).
-- Kontrola skládá očekávaná čísla TOUTÉŽ metodou, která je přiděluje, a porovná
-- je se skutečností — nemůže se tedy s generátorem rozejít.
-- ============================================================================

ALTER TABLE billing.company_profile
    ADD COLUMN invoice_gap_check_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN invoice_gap_check_from    VARCHAR(20);

COMMENT ON COLUMN billing.company_profile.invoice_gap_check_enabled IS
    'Hlídat mezery v číselné řadě faktur a varovat nad seznamem (V89).';
COMMENT ON COLUMN billing.company_profile.invoice_gap_check_from IS
    'Číslo faktury, od kterého se hlídá; starší se ignorují (typicky data přenesená '
    'z jiného systému). NULL = hlídat celé aktuální období od pořadí 1.';
