package cz.palo.autoservis.model.domain.vehicle;

import cz.palo.autoservis.model.domain.customer.Customer;
import cz.palo.autoservis.model.enums.FuelType;
import cz.palo.autoservis.model.enums.TransmissionType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * Doménový objekt vozidla — mapuje se na {@code vehicle.vehicles}.
 *
 * <p>Čisté POJO bez JPA anotací a závislostí na Springu.
 * Databázové sloupce na pole mapuje MyBatis přes {@code ResultMap}
 * v {@code VehicleMapper.xml}.
 *
 * <p>Fáze 1: {@code brand} a {@code model} jsou volný text.
 * Fáze 2 je nahradí cizími klíči do číselníku značek/modelů.
 *
 * @see FuelType
 * @see TransmissionType
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Vehicle {

    /** Primární klíč — generuje {@code vehicle.vehicles_id_seq}. */
    private Long id;

    /** ID vlastníka. NOT NULL — každé vozidlo musí mít právě jednoho zákazníka. */
    private Long customerId;

    /** Detail vlastníka. Načítá se přes MyBatis association v detailových dotazech. */
    private Customer customer;

    /**
     * Vehicle Identification Number — globálně unikátní 17znakový kód.
     * Od V90 {@code null} u strojů bez VIN (zahradní traktory, sekačky).
     */
    private String vin;

    /**
     * Výrobní číslo stroje bez VIN (V90). Volný text, není unikátní —
     * různí výrobci mohou mít shodná číslování.
     */
    private String machineSerialNumber;

    /** Registrační značka (SPZ). {@code null} u neregistrovaných vozidel. Není unikátní — značky se mění. */
    private String licensePlate;

    /** Značka — fáze 1: volný text. Fáze 2: FK do {@code vehicle.brands}. */
    private String brand;

    /** Model — fáze 1: volný text. Fáze 2: FK do {@code vehicle.models}. */
    private String model;

    /** Rok výroby. */
    private Short yearOfManufacture;

    /** Datum první registrace (z technického průkazu). */
    private LocalDate firstRegistrationDate;

    /** Palivo / pohon. NOT NULL. */
    private FuelType fuelType;

    /** Typ převodovky. */
    private TransmissionType transmission;

    /** Zdvihový objem motoru v cm³. {@code null} u elektromobilů. */
    private Integer engineDisplacementCcm;

    /** Kód motoru od výrobce, např. „642.980", „CAXA". Nullable — často neznámý. */
    private String engineCode;

    /** Výkon motoru v kW. */
    private Short enginePowerKw;

    /** Barva vozidla (volný text). */
    private String color;

    /**
     * Aktuální stav tachometru v km.
     * Denormalizovaná hodnota — plná historie je v {@code vehicle.mileage_history}.
     */
    private Integer currentMileageKm;

    /**
     * Platnost STK (technické kontroly) do.
     * Denormalizováno z nejnovějšího řádku {@code vehicle.registry_snapshots}
     * triggerem {@code trg_registry_snapshots_sync_stk} — aplikační kód sem nikdy nezapisuje.
     */
    private LocalDate stkValidUntil;

    /**
     * Pneu/ráfky po nápravách z registru ({@code NapravyPneuRafky}), jen ke čtení.
     * Denormalizováno z nejnovějšího snapshotu týmž sync triggerem (V62) —
     * aplikační kód nezapisuje, ručně se needituje.
     */
    private String wheels;

    /** Interní servisní poznámka. Vidí jen personál, zákazníkovi se nikdy neukazuje. */
    private String internalNote;

    /** Příznak soft delete. {@code false} = vozidlo vyřazeno (sešrotováno či prodáno). */
    private boolean active;

    /** Čas vytvoření záznamu. Nastavuje databázový default. */
    private OffsetDateTime createdAt;

    /** Čas poslední změny. Udržuje trigger {@code trg_vehicles_updated_at}. */
    private OffsetDateTime updatedAt;

    /** Mezischémová FK na {@code security.users.id}. Auditní stopa — kdo vozidlo zaevidoval. */
    private Long createdBy;
}
