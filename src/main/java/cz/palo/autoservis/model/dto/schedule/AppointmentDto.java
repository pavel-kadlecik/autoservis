package cz.palo.autoservis.model.dto.schedule;

import cz.palo.autoservis.model.enums.AppointmentStatus;
import cz.palo.autoservis.model.enums.AppointmentType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * DTO namespace pro položky plánovacího kalendáře ({@code schedule.appointments}).
 *
 * <p>Zásady, které tady platí:
 * <ul>
 *   <li>{@code DetailResponse} je nadmnožinou {@code ListResponse} — detail nesmí ukázat míň než seznam.</li>
 *   <li>{@code ListResponse} nese jen to, co se vykreslí v kalendáři bez kliknutí.</li>
 *   <li>Vstupní DTO neobsahují {@code id}, {@code status}, {@code orderId}, {@code createdBy} ani časy —
 *       ty určuje databáze nebo service, ne klient.</li>
 * </ul>
 */
public class AppointmentDto {

    /**
     * Vstup pro založení nové položky kalendáře.
     *
     * <p>{@code status} se nezadává — nová položka vzniká vždy jako {@code PLANNED} (default v DB).
     * {@code orderId} se nezadává — vazbu na zakázku vytvoří až převod objednávky.
     */
    @Data
    public static class CreateRequest {

        @NotNull(message = "Typ položky je povinný.")
        private AppointmentType entryType;

        @NotBlank(message = "Název je povinný.")
        @Size(max = 200, message = "Název může mít nejvýš 200 znaků.")
        private String title;

        private String note;

        @NotNull(message = "Datum a čas začátku je povinný.")
        private OffsetDateTime startsAt;

        /**
         * Konec termínu. {@code null} = <em>„zákazník nechá auto, konec neznámý"</em> — délku opravy
         * nelze před diagnostikou odhadnout (V74). U blokace dílny je povinný, to hlídá service.
         */
        private OffsetDateTime endsAt;

        /**
         * Nepovinné (V85) — termín se domlouvá dřív, než je zákazník v evidenci. Blokace dílny
         * (CLOSURE) ani událost (EVENT) zákazníka nemají. Kombinaci hlídá service a CHECK v DB.
         */
        private Long customerId;

        /** Nepovinné (V85) — po telefonu se často neví, s čím zákazník dorazí. */
        private Long vehicleId;

        /**
         * Kontakt na zákazníka mimo evidenci (jméno, telefon). Jen pro objednávku — bez něj
         * by nešlo termín s nikým přeložit. Kombinaci hlídá service a CHECK v DB.
         */
        @Size(max = 200, message = "Kontakt může mít nejvýš 200 znaků.")
        private String contactNote;

        /** Jen pro událost (EVENT) — dovolená apod. Volitelné; kombinaci hlídá service a CHECK v DB. */
        private Long employeeId;
    }

    /**
     * Vstup pro úpravu existující položky.
     *
     * <p>{@code entryType} tu záměrně není — typ se určí při založení a dál se nemění. Překlopení
     * objednávky na blokaci by porušilo {@code chk_appointments_closure_empty} (zůstal by zákazník).
     * {@code status} se mění vlastní akcí, ne úpravou položky.
     */
    @Data
    public static class UpdateRequest {

        @NotBlank(message = "Název je povinný.")
        @Size(max = 200, message = "Název může mít nejvýš 200 znaků.")
        private String title;

        private String note;

        @NotNull(message = "Datum a čas začátku je povinný.")
        private OffsetDateTime startsAt;

        /** {@code null} = konec neznámý. Vyprázdněním se otevřená objednávka vrátí do nejasného stavu. */
        private OffsetDateTime endsAt;

        private Long customerId;

        private Long vehicleId;

        /** Kontakt na zákazníka mimo evidenci. Vyprázdněním se maže — např. když se zákazník doeviduje. */
        @Size(max = 200, message = "Kontakt může mít nejvýš 200 znaků.")
        private String contactNote;

        /** Jen pro událost (EVENT). Volitelné; kombinaci hlídá service a CHECK v DB. */
        private Long employeeId;
    }

    /** Úplná odpověď pro detail položky. Nadmnožina {@link ListResponse}. */
    @Data
    public static class DetailResponse {

        private Long id;
        private AppointmentType entryType;
        private String title;
        private String note;
        private OffsetDateTime startsAt;
        private OffsetDateTime endsAt;

        private Long customerId;
        private String customerDisplayName;

        /** Vyplněný jen u objednávky bez {@code customerId} — zákazník, který v evidenci není. */
        private String contactNote;

        private Long vehicleId;
        private String vehicleLicensePlate;
        private String vehicleBrand;
        private String vehicleModel;
        /** V detailu ano — mechanik ho potřebuje k objednání dílů. V seznamu ne, nikdo ho z kalendáře nečte. */
        private String vehicleVin;

        private Long orderId;
        private String orderNumber;

        /** Jen pro událost (EVENT) — dovolená apod. */
        private Long employeeId;
        private String employeeDisplayName;

        private AppointmentStatus status;
        private OffsetDateTime createdAt;
        private OffsetDateTime updatedAt;
        // createdBy tu záměrně není: je to holé user_id, které frontend neumí zobrazit (potřeboval by
        // jméno, tedy další JOIN). Devět ostatních DTO ho nese a FE ho nepoužívá ani jednou.
        // Až bude potřeba audit v UI, přidat rovnou createdByName, ne samotné id.
    }

    /**
     * Zúžená odpověď pro vykreslení kalendáře.
     *
     * <p>Obsahuje jen to, co kalendář nakreslí bez kliknutí: kam událost umístit ({@code startsAt},
     * {@code endsAt}), co do ní napsat, jakou má mít barvu ({@code entryType}, {@code status})
     * a kam vede klik ({@code id}). Načítá se po desítkách najednou, takže každé pole navíc
     * je desetinásobek přenesených dat.
     */
    @Data
    public static class ListResponse {

        private Long id;
        private AppointmentType entryType;
        private String title;
        private OffsetDateTime startsAt;
        private OffsetDateTime endsAt;
        private AppointmentStatus status;

        private String customerDisplayName;

        /** Zákazník mimo evidenci — kalendář ho na kartě vypíše místo {@code customerDisplayName}. */
        private String contactNote;

        private String vehicleLicensePlate;
        private String vehicleBrand;
        private String vehicleModel;

        /** Jen pro událost (EVENT) — kalendář jméno vypíše místo zákazníka. */
        private String employeeDisplayName;

        /** Vyplněné = objednávka už má zakázku. Kalendář takové události kreslí odlišně. */
        private Long orderId;
    }

    /**
     * Posun termínu — drag-and-drop a změna délky v kalendáři.
     *
     * <p>Vlastní DTO místo {@link UpdateRequest} proto, že přetažení myší nesmí vyžadovat
     * název ani zákazníka; klient je při té akci nemá po ruce a poslat je zpátky nezměněné
     * je jen příležitost je omylem přepsat.
     */
    @Data
    public static class TimeRequest {

        @NotNull(message = "Datum a čas začátku je povinný.")
        private OffsetDateTime startsAt;

        /**
         * {@code null} = konec zůstává neznámý. Přetažení otevřené objednávky posune jen příjezd;
         * konec doplní teprve protažení za spodní okraj, kterým mechanik délku určí.
         */
        private OffsetDateTime endsAt;
    }

    /**
     * Změna stavu objednávky (potvrzeno / nedorazil / zrušeno).
     *
     * <p>{@code CONVERTED} tudy nastavit nelze — vzniká jen převodem na zakázku, spolu s {@code order_id}.
     */
    @Data
    public static class StatusRequest {

        @NotNull(message = "Stav je povinný.")
        private AppointmentStatus status;
    }

    /**
     * Odpověď kontroly překryvu — podklad pro <em>varování</em>, ne pro zákaz.
     *
     * <p>Překryv objednávek servis běžně chce (dvě auta naráz), takže rozhodnutí zůstává na
     * uživateli. Jediné tvrdé pravidlo je {@code blockedByClosure}: do zavřené dílny se objednat nedá.
     */
    @Data
    public static class OverlapResponse {

        /** Počet aktivních objednávek, které se s navrženým termínem překrývají. */
        private int overlappingCount;

        /** Překrývající se objednávky pro zobrazení ve varování. */
        private List<ListResponse> overlapping;

        /** {@code true} = termín zasahuje do blokace dílny; uložení skončí chybou 422. */
        private boolean blockedByClosure;

        /**
         * {@code true} = příjezd padá mimo otevírací dobu dílny. Na rozdíl od blokace
         * <strong>nebrání uložení</strong> (rozhodnutí uživatele 2026-08-04) — je to upozornění,
         * ne zákaz. Při vypnutém hlídání je vždy {@code false}.
         */
        private boolean startOutsideOpeningHours;

        /** Totéž pro vyzvednutí. Prázdný konec („konec neznámý") je vždy {@code false}. */
        private boolean endOutsideOpeningHours;
    }
}
