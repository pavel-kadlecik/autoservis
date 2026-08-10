package cz.palo.autoservis.model.dto.registry;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Podmnožina objektu {@code Data} z dataovozidlech.cz, kterou aplikace mapuje.
 *
 * <p>API vrací ~70 polí v PascalCase; potřeba jsou jen tato — pro kartu STK
 * a předvyplnění formuláře vozidla. Kompletní payload se uchovává
 * v {@code vehicle.registry_snapshots.raw_response} (JSONB), takže pozdější
 * rozšíření tohoto recordu nevyžaduje nová volání API.
 *
 * <p>{@code @JsonProperty} je explicitní u každé komponenty — registr míchá
 * PascalCase s nepravidelnými zkratkami ({@code VIN}, {@code CisloTp}), takže
 * naming strategy by stejně potřebovala výjimky. Neznámá pole výchozí Jackson
 * konfigurace Spring Bootu ignoruje.
 *
 * <p>Datumová pole chodí jako ISO datetime řetězce ({@code "1997-03-21T00:00:00"})
 * a tady zůstávají jako řetězce; parsování na {@code LocalDate} dělá
 * {@code RegistryConverter}.
 */
public record RegistryVehicleData(
        @JsonProperty("VIN")                            String vin,
        @JsonProperty("TovarniZnacka")                  String tovarniZnacka,
        @JsonProperty("ObchodniOznaceni")               String obchodniOznaceni,
        @JsonProperty("VozidloKaroserieBarva")          String vozidloKaroserieBarva,
        @JsonProperty("Palivo")                         String palivo,
        @JsonProperty("MotorZdvihObjem")                Integer motorZdvihObjem,
        @JsonProperty("MotorMaxVykon")                  String motorMaxVykon,
        @JsonProperty("MotorTyp")                       String motorTyp,
        @JsonProperty("VozidloElektricke")              String vozidloElektricke,
        @JsonProperty("VozidloHybridni")                String vozidloHybridni,
        @JsonProperty("DatumPrvniRegistrace")           String datumPrvniRegistrace,
        @JsonProperty("PravidelnaTechnickaProhlidkaDo") String pravidelnaTechnickaProhlidkaDo,
        @JsonProperty("EvidencniProhlidkaDne")          String evidencniProhlidkaDne,
        @JsonProperty("StatusNazev")                    String statusNazev,
        @JsonProperty("CisloTp")                        String cisloTp,
        @JsonProperty("CisloOrv")                       String cisloOrv,
        @JsonProperty("OrvZadrzeno")                    Boolean orvZadrzeno,
        @JsonProperty("RzZadrzena")                     Boolean rzZadrzena,
        @JsonProperty("PocetVlastniku")                 Integer pocetVlastniku,
        @JsonProperty("PocetProvozovatelu")             Integer pocetProvozovatelu
) {
}
