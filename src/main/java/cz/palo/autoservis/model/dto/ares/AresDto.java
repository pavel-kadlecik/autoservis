package cz.palo.autoservis.model.dto.ares;

/**
 * Namespace pro API DTO integrace ARES (ares.gov.cz).
 * Jen response tvary — request nese obyčejný query parametr.
 */
public final class AresDto {

    private AresDto() {
    }

    /**
     * Odpověď {@code GET /customers/ares-lookup} — data pro předvyplnění
     * formuláře zákazníka (typ COMPANY). Adresní pole popisují registrované
     * sídlo ({@code sidlo}); formulář je předvyplní do fakturační adresy.
     *
     * <p>{@code dic} se plní, jen když odpovídá aplikačnímu formátu
     * {@code CZ\d{8,10}} — DIČ v zahraničním formátu by neprošlo validací
     * formuláře, proto zůstává {@code null} (pole se nedotkne).
     * Jakákoli {@code null} hodnota znamená „ARES neví" a pole formuláře
     * se prostě nepředvyplní.
     */
    public record LookupResponse(
            String ico,
            String companyName,
            String dic,
            String street,
            String streetNumber,
            String city,
            String postalCode,
            String countryCode
    ) {
    }
}
