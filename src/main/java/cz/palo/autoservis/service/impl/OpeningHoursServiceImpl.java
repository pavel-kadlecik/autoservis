package cz.palo.autoservis.service.impl;

import cz.palo.autoservis.exception.BusinessRuleException;
import cz.palo.autoservis.mapper.OpeningHoursMapper;
import cz.palo.autoservis.model.converter.OpeningHoursConverter;
import cz.palo.autoservis.model.domain.schedule.OpeningHours;
import cz.palo.autoservis.model.dto.schedule.OpeningHoursDto;
import cz.palo.autoservis.service.OpeningHoursService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Otevírací doba dílny — čtení, uložení celého týdne a dotaz „padne tenhle okamžik mimo?". */
@Service
@RequiredArgsConstructor
public class OpeningHoursServiceImpl implements OpeningHoursService {

    /** Dnů v týdnu je sedm; rozvrh musí přijít celý, jinak by evidence zůstala v půlce. */
    private static final Set<Integer> ALL_DAYS = Set.of(1, 2, 3, 4, 5, 6, 7);

    private final OpeningHoursMapper mapper;
    private final OpeningHoursConverter converter;

    @Override
    @Transactional(readOnly = true)
    public OpeningHoursDto.Response get() {
        return converter.toResponse(mapper.findAll(), isOpeningHoursEnabled());
    }

    @Override
    @Transactional
    public OpeningHoursDto.Response update(OpeningHoursDto.UpdateRequest request) {
        requireCompleteWeek(request.getDays());
        request.getDays().forEach(OpeningHoursServiceImpl::requireConsistentDay);

        request.getDays().stream()
                .map(converter::toDomain)
                .forEach(mapper::updateDay);
        mapper.updateSettings(request.getOpeningHoursEnabled());

        // verify-and-fetch (R-03): vrací se stav přečtený z databáze, ne poskládaný z requestu.
        return get();
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isOpeningHoursEnabled() {
        return mapper.findSettings().isOpeningHoursEnabled();
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isOutsideOpeningHours(OffsetDateTime moment) {
        if (moment == null || !isOpeningHoursEnabled()) {
            return false;
        }
        /*
         * Rozvrh se čte v místním čase serveru — otevírací doba je „sedm ráno u nás v dílně",
         * ne posun od UTC. Kdyby se porovnával UTC čas, otevřelo by se v zimě o hodinu jinak.
         */
        OpeningHours day = mapper.findByDayOfWeek(moment.atZoneSameInstant(
                java.time.ZoneId.systemDefault()).getDayOfWeek().getValue());
        if (day == null || day.isClosed()) {
            return true;
        }
        LocalTime time = moment.atZoneSameInstant(java.time.ZoneId.systemDefault()).toLocalTime();
        return time.isBefore(day.getOpensAt()) || time.isAfter(day.getClosesAt());
    }

    /** Chybějící nebo zdvojený den by nechal rozvrh v nejednoznačném stavu. */
    private static void requireCompleteWeek(List<OpeningHoursDto.Day> days) {
        Set<Integer> given = days.stream()
                .map(OpeningHoursDto.Day::getDayOfWeek)
                .collect(Collectors.toSet());
        if (given.size() != days.size() || !given.equals(ALL_DAYS)) {
            throw new BusinessRuleException(
                    "INCOMPLETE_WEEK", "days",
                    "Rozvrh musí obsahovat každý den v týdnu právě jednou.",
                    Map.of("given", given));
        }
    }

    /**
     * Zrcadlí {@code chk_opening_hours_pair} a {@code chk_opening_hours_range}. Bez toho by
     * uživatel místo české hlášky dostal 500 z porušeného CHECKu.
     */
    private static void requireConsistentDay(OpeningHoursDto.Day day) {
        boolean opensSet = day.getOpensAt() != null;
        boolean closesSet = day.getClosesAt() != null;

        if (opensSet != closesSet) {
            throw new BusinessRuleException(
                    "INCOMPLETE_OPENING_HOURS", "days",
                    "Vyplňte oba časy, nebo den nechte zavřený.",
                    Map.of("dayOfWeek", day.getDayOfWeek()));
        }
        if (opensSet && !day.getClosesAt().isAfter(day.getOpensAt())) {
            throw new BusinessRuleException(
                    "INVALID_OPENING_HOURS", "days",
                    "Zavírací čas musí být pozdější než otevírací.",
                    Map.of("dayOfWeek", day.getDayOfWeek()));
        }
    }
}
