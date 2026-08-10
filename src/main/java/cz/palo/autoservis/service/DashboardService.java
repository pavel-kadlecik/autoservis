package cz.palo.autoservis.service;

import cz.palo.autoservis.model.dto.dashboard.DashboardDto;

/**
 * Souhrn pro úvodní stránku (dashboard) — jedno agregované volání.
 *
 * <p>Čte napříč moduly (zakázky, faktury, sklad, vozidla) a skládá počty,
 * součty a krátká preview. Nic nemění.
 */
public interface DashboardService {

    /**
     * Sestaví kompletní souhrn dashboardu z lehkých agregací nad existujícími daty.
     *
     * @return počty, součty a preview pro všechny dlaždice
     */
    DashboardDto.Summary getSummary();

    /**
     * Měsíční statistika (tržby, marže, počty zakázek a faktur) za zvolený rok
     * — počítá se živě, nic se neukládá.
     *
     * @param year rok; {@code null} = aktuální rok
     * @return rok, dostupné roky a řádky měsíců, ve kterých něco je
     */
    DashboardDto.Statistics getStatistics(Integer year);
}
