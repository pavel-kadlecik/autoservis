package cz.palo.autoservis.service;

import cz.palo.autoservis.model.dto.vehicle.MileageDto;

import java.util.List;

/**
 * Service pro historii odečtů tachometru vozidla ({@code vehicle.mileage_history}).
 *
 * <p>Odečty tvoří editovatelný deník. Cache {@code current_mileage_km} vozidla
 * udržuje DB trigger, takže tahle služba do ní nikdy nezapisuje přímo — stačí
 * odečet vložit, upravit nebo smazat a cache se sama srovná.
 */
public interface MileageService {

    /**
     * Vrací všechny odečty vozidla, nejnovější první.
     *
     * @param vehicleId ID vozidla
     * @return seznam odečtů (může být prázdný)
     */
    List<MileageDto.Response> findByVehicleId(Long vehicleId);

    /**
     * Zaznamená nový odečet vozidla.
     *
     * @param vehicleId ID vozidla
     * @param request   zvalidovaná data odečtu
     * @param userId    právě přihlášený uživatel (auditní pole {@code created_by})
     * @return založený odečet
     */
    MileageDto.Response addReading(Long vehicleId, MileageDto.CreateRequest request, Long userId);

    /**
     * Zapíše odečet vzniklý <strong>příjmem vozu na zakázku</strong> a sváže ho s ní (V84).
     *
     * <p>Vazba se drží kvůli mazání: zakázku založenou omylem — typicky na špatném voze —
     * jde smazat a odečet musí zmizet s ní, protože v historii cizího auta je to nesmyslný
     * údaj. Do V84 spojoval obojí jen text poznámky, tedy vazba přes řetězec.
     *
     * <p>Vazbu dosazuje <strong>server</strong>, ne klient — v {@code CreateRequest} proto
     * není (týž princip jako u auditních polí). Ručně zadaný odečet z karty vozidla
     * zakládá {@link #addReading} a žádnou zakázku nemá.
     *
     * @param vehicleId ID vozidla
     * @param request   zvalidovaná data odečtu
     * @param orderId   zakázka, při jejímž příjmu odečet vznikl; {@code null} = bez vazby
     * @param userId    právě přihlášený uživatel (auditní pole {@code created_by})
     * @return založený odečet
     */
    MileageDto.Response addIntakeReading(Long vehicleId, MileageDto.CreateRequest request,
                                         Long orderId, Long userId);

    /**
     * Opraví existující odečet vozidla.
     *
     * @param vehicleId ID vozidla (odečet mu musí patřit)
     * @param readingId ID odečtu
     * @param request   zvalidovaná nová data
     * @param userId    právě přihlášený uživatel
     * @return upravený odečet
     */
    MileageDto.Response updateReading(Long vehicleId, Long readingId, MileageDto.UpdateRequest request, Long userId);

    /**
     * Smaže odečet vozidla.
     *
     * @param vehicleId ID vozidla (odečet mu musí patřit)
     * @param readingId ID odečtu
     */
    void deleteReading(Long vehicleId, Long readingId);
}
