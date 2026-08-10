-- =============================================================================
-- V76__appointment_hard_delete.sql
-- Schéma: schedule
--
-- Objednávka založená omylem se maže NATVRDO. Sloupec is_active proto končí.
--
-- Proč výjimka z R-06 (soft-delete přes is_active):
--   Objednávka termínu není doklad. Nikdo se na ni zpětně neodvolává, nemá číselnou řadu
--   a neodkazuje na ni žádná jiná tabulka (ověřeno: nula cizích klíčů mířících sem).
--   Deaktivovaný záznam by tu jen ležel a nikdo by ho nikdy nezobrazil.
--   Týž princip má projekt u konceptu faktury a dobropisu — doklad, který nikdy nevznikl,
--   se maže, ne stornuje (viz konvence §18).
--
-- Objednávka má nově dva konce a nic mezi tím:
--   status = CANCELLED  → zákazník nepřijede; ZŮSTÁVÁ v historii, počítá se do statistik
--   DELETE              → záznam vznikl omylem; mizí úplně
--   Sloučit obojí do jednoho příznaku znamenalo přijít o jedno z toho.
--
-- Převedenou objednávku (status CONVERTED) smazat nelze — hlídá to service, ne DB:
-- je to pravidlo o významu záznamu, ne o integritě, a uživatel má dostat českou hlášku (422).
--
-- Index idx_appointments_range měl v predikátu is_active, takže se musí přestavět.
-- =============================================================================

-- Dřív deaktivované záznamy musí zmizet DŘÍV, než sloupec padne — jinak by se zahozením
-- příznaku tiše vrátily do kalendáře. (Odhaleno při ověřování: šest položek smazaných ještě
-- soft-deletem se po migraci objevilo zpátky.) V čerstvé databázi nemá tenhle příkaz co mazat,
-- ale bez něj je migrace nedokončená a v jakémkoli běžícím prostředí by křísila smazané objednávky.
DELETE FROM schedule.appointments WHERE is_active = FALSE;

DROP INDEX IF EXISTS schedule.idx_appointments_range;

CREATE INDEX idx_appointments_range
    ON schedule.appointments (starts_at, ends_at);

ALTER TABLE schedule.appointments
    DROP COLUMN is_active;

COMMENT ON TABLE schedule.appointments IS
    'Objednávky termínů (BOOKING) a blokace dílny (CLOSURE). Objednávka vzniká před zakázkou; '
    'po převodu ukazuje order_id na vzniklou zakázku a status je CONVERTED. '
    'Bez soft-delete (V76): zrušení = status CANCELLED (zůstává), omyl = DELETE (mizí).';
