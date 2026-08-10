package cz.palo.autoservis.model.draft;

/**
 * Stav (důvěryhodnost) jednoho pole draftu příjemky. Barevné odlišení v UI.
 *
 * <p>Model AI vrací jen VERBATIM / DERIVED / ABSENT; na VERIFIED povyšuje
 * výhradně deterministický kód (DraftVerificationService), DEFAULTED dosazuje
 * DraftAssembler z konfigurace a EDITED vzniká úpravou v kontrolní obrazovce.
 */
public enum FieldState {
    /** Opsáno doslova z dokladu (zatím bez nezávislého ověření). */
    VERBATIM,
    /** Dopočteno (modelem či kódem) z jiných hodnot dokladu. */
    DERIVED,
    /** V dokladu chybí — doplněno výchozí hodnotou z konfigurace. */
    DEFAULTED,
    /** Přečteno/dopočteno A ověřeno deterministickou kontrolou kódu. */
    VERIFIED,
    /** V dokladu chybí a nemá default — vyžaduje doplnění člověkem. */
    ABSENT,
    /** Změněno uživatelem při kontrole. */
    EDITED
}
