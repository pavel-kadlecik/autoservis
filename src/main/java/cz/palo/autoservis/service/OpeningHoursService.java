package cz.palo.autoservis.service;

import cz.palo.autoservis.model.dto.schedule.OpeningHoursDto;

import java.time.OffsetDateTime;

/** Otevírací doba dílny a přepínače plánovacího kalendáře. */
public interface OpeningHoursService {

    /** Celý týdenní rozvrh i s přepínačem hlídání. */
    OpeningHoursDto.Response get();

    /** Uloží celý týden naráz; vrací uložený stav. */
    OpeningHoursDto.Response update(OpeningHoursDto.UpdateRequest request);

    /** @return {@code true}, má-li se na otevírací dobu brát ohled */
    boolean isOpeningHoursEnabled();

    /**
     * Padne okamžik mimo otevírací dobu svého dne?
     *
     * <p>Odpovídá <strong>jen na jeden okamžik</strong> — příjezd nebo vyzvednutí. Doba mezi nimi
     * se nekontroluje schválně: auto přes noc v zavřené dílně stojí běžně a vícedenní opravy (V74)
     * na tom stojí. Otevírací doba se týká chvil, kdy u toho musí někdo být.
     *
     * @return {@code false}, je-li hlídání vypnuté — vypnutý přepínač znamená „neřeš"
     */
    boolean isOutsideOpeningHours(OffsetDateTime moment);
}
