-- =============================================================================
-- V84__add_order_id_to_mileage_history.sql
-- Schéma: vehicle
--
-- Vazba odečtu tachometru na zakázku, při jejímž příjmu vznikl.
-- Rozhodnutí uživatele 2026-08-06 (Etapa 2 — tvrdé smazání omylem založené zakázky).
--
-- PROČ:
--   Založení zakázky zapisuje stav tachometru z příjmu i do historie vozidla
--   (OrderServiceImpl.recordIntakeMileage, zdroj SERVICE, V70). Dosud to spojoval jen
--   TEXT poznámky „Příjem vozu — zakázka ZAK-…“ — vazba přes řetězec, kterou utrhne
--   každý, kdo poznámku přepíše.
--   Zakázku má nově jít smazat, když ji obsluha založila omylem (typicky na špatném
--   voze). Pak musí zmizet i ten odečet: na cizím autě je to nesmyslný údaj a přesně
--   ten případ, kvůli kterému se mazání zavádí. Dohledávat ho podle textu poznámky by
--   znamenalo postavit mazání dat na porovnávání řetězců.
--
-- ON DELETE CASCADE: odečet z příjmu je vlastnictvím té zakázky — vznikl jejím
--   založením a bez ní nemá smysl. Mazání tak obstará databáze a service nemusí nic
--   dopočítávat. Ručně zadané odečty z karty vozidla mají order_id NULL a změna se
--   jich netýká.
--   (Zrušení zakázky odečet NEMAŽE — zrušená zakázka existovala a vůz jí opravdu projel;
--   maže se jen při tvrdém smazání, tedy když záznam neměl vzniknout vůbec.)
--
-- NULLABLE: většina odečtů se zakázkou nesouvisí — zadávají se ručně na kartě vozidla.
-- =============================================================================

ALTER TABLE vehicle.mileage_history
    ADD COLUMN order_id BIGINT,
    ADD CONSTRAINT fk_mileage_history_order
        FOREIGN KEY (order_id) REFERENCES "order".orders(id)
        ON UPDATE CASCADE ON DELETE CASCADE;

CREATE INDEX idx_mileage_history_order ON vehicle.mileage_history (order_id);

COMMENT ON COLUMN vehicle.mileage_history.order_id IS
    'Zakázka, při jejímž příjmu odečet vznikl (V70/V84). NULL u ručně zadaných odečtů '
    'z karty vozidla. ON DELETE CASCADE — smazání zakázky odečet odstraní, protože '
    'u omylem založené zakázky (typicky na špatném voze) jde o nesmyslný údaj.';

-- -----------------------------------------------------------------------------
-- Backfill starých odečtů
--
-- Do V84 byla jediná stopa v poznámce, kterou skládá recordIntakeMileage jako
-- „Příjem vozu — zakázka {číslo}“. Čísla zakázek jsou unikátní, takže shoda na konci
-- poznámky je jednoznačná; navíc se vyžaduje shoda vozidla a zdroj SERVICE, aby ručně
-- psaná poznámka nemohla omylem chytit cizí zakázku.
-- -----------------------------------------------------------------------------

UPDATE vehicle.mileage_history mh
   SET order_id = o.id
  FROM "order".orders o
 WHERE mh.order_id IS NULL
   AND mh.source = 'SERVICE'
   AND mh.vehicle_id = o.vehicle_id
   AND mh.note LIKE '%' || o.order_number;

-- -----------------------------------------------------------------------------
-- Kontrola po nasazení (nespouští se, jen návod):
--
--   -- Odečty z příjmu, které se nepodařilo spárovat. Očekávaný výsledek: 0 řádků.
--   -- Nenulový výsledek není chyba dat — jen odečet, jehož poznámku někdo přepsal;
--   -- zůstane bez vazby a smazání zakázky ho nechá být.
--   SELECT mh.id, mh.vehicle_id, mh.note
--     FROM vehicle.mileage_history mh
--    WHERE mh.order_id IS NULL
--      AND mh.source = 'SERVICE'
--      AND mh.note LIKE 'Příjem vozu%';
-- -----------------------------------------------------------------------------
