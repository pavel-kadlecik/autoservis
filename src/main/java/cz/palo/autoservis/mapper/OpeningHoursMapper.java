package cz.palo.autoservis.mapper;

import cz.palo.autoservis.model.domain.schedule.OpeningHours;
import cz.palo.autoservis.model.domain.schedule.ScheduleSettings;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** Přístup k otevírací době dílny a k přepínačům kalendáře. SQL je v {@code OpeningHoursMapper.xml}. */
@Mapper
public interface OpeningHoursMapper {

    /** Celý týdenní rozvrh seřazený od pondělí. */
    List<OpeningHours> findAll();

    /** Rozvrh jednoho dne; {@code dayOfWeek} je 1 = pondělí … 7 = neděle. */
    OpeningHours findByDayOfWeek(@Param("dayOfWeek") int dayOfWeek);

    /**
     * Přepíše jeden den rozvrhu.
     *
     * @return počet dotčených řádků — nula znamená, že den v tabulce chybí (nemělo by nastat,
     *         sedm řádků zakládá migrace V79)
     */
    int updateDay(OpeningHours openingHours);

    /** Přepínače kalendáře — vždy jeden řádek (singleton {@code id = 1}). */
    ScheduleSettings findSettings();

    /** @return počet dotčených řádků */
    int updateSettings(@Param("openingHoursEnabled") boolean openingHoursEnabled);
}
