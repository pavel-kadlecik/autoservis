package cz.palo.autoservis.controller;

import cz.palo.autoservis.model.dto.order.OrderDto;
import cz.palo.autoservis.model.dto.schedule.AppointmentDto;
import cz.palo.autoservis.model.enums.AppointmentStatus;
import cz.palo.autoservis.model.enums.AppointmentType;
import cz.palo.autoservis.security.model.domain.AppUserDetails;
import cz.palo.autoservis.service.AppointmentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * REST rozhraní plánovacího kalendáře.
 *
 * <p>Čtení a práce s objednávkami spadá pod výchozí oprávnění {@code /api/**}
 * (ADMIN/MANAGER/MECHANIC). Zakládání a rušení <strong>blokací dílny</strong> je navíc omezeno
 * na vedení — zavřít dílnu není rozhodnutí mechanika (konvence §19).
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/{version}/appointments")
public class AppointmentController {

    private final AppointmentService appointmentService;

    /**
     * Položky kalendáře v časovém okně — hlavní dotaz kalendáře.
     * Bez stránkování: okno je přirozený limit.
     */
    @GetMapping
    public ResponseEntity<List<AppointmentDto.ListResponse>> getInRange(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @RequestParam(required = false) AppointmentType entryType,
            @RequestParam(required = false) AppointmentStatus status) {
        return ResponseEntity.ok(appointmentService.getInRange(from, to, entryType, status));
    }

    /** Kontrola překryvu před uložením — podklad pro varování, nikoli zákaz. */
    @GetMapping("/overlaps")
    public ResponseEntity<AppointmentDto.OverlapResponse> checkOverlaps(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime startsAt,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime endsAt,
            @RequestParam(required = false) Long excludeId) {
        return ResponseEntity.ok(appointmentService.checkOverlaps(startsAt, endsAt, excludeId));
    }

    @GetMapping("/{id}")
    public ResponseEntity<AppointmentDto.DetailResponse> getById(@PathVariable Long id) {
        return ResponseEntity.ok(appointmentService.getById(id));
    }

    /**
     * Objednávka, ze které vznikla daná zakázka — zpětný odkaz na detailu zakázky.
     * Vrací 404, když zakázka vznikla přímo; to je běžný stav, ne chyba.
     */
    @GetMapping("/by-order/{orderId}")
    public ResponseEntity<AppointmentDto.DetailResponse> getByOrderId(@PathVariable Long orderId) {
        return appointmentService.findByOrderId(orderId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<AppointmentDto.DetailResponse> create(
            @Valid @RequestBody AppointmentDto.CreateRequest createRequest,
            @AuthenticationPrincipal AppUserDetails currentUser) {
        requireManagerForClosure(createRequest.getEntryType(), currentUser);

        AppointmentDto.DetailResponse created =
                appointmentService.create(createRequest, currentUser.getUserId());
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(created.getId())
                .toUri();
        return ResponseEntity.created(location).body(created);
    }

    @PutMapping("/{id}")
    public ResponseEntity<AppointmentDto.DetailResponse> update(
            @PathVariable Long id,
            @Valid @RequestBody AppointmentDto.UpdateRequest updateRequest) {
        return ResponseEntity.ok(appointmentService.update(id, updateRequest));
    }

    /**
     * Posun termínu — drag-and-drop a změna délky v kalendáři.
     *
     * <p>{@code POST}, ne {@code PATCH}: projekt žádné PATCH sloveso nepoužívá a CORS ho nepouští
     * (`SecurityConfig.corsConfigurationSource`). Akce na resource se tu dělají přes POST — stejně
     * jako sousední {@code /{id}/status}.
     */
    @PostMapping("/{id}/time")
    public ResponseEntity<AppointmentDto.DetailResponse> updateTime(
            @PathVariable Long id,
            @Valid @RequestBody AppointmentDto.TimeRequest timeRequest) {
        return ResponseEntity.ok(appointmentService.updateTime(id, timeRequest));
    }

    /** Potvrzení termínu, nedostavení se, zrušení. */
    @PostMapping("/{id}/status")
    public ResponseEntity<AppointmentDto.DetailResponse> changeStatus(
            @PathVariable Long id,
            @Valid @RequestBody AppointmentDto.StatusRequest statusRequest) {
        return ResponseEntity.ok(appointmentService.changeStatus(id, statusRequest));
    }

    /**
     * Převod objednávky na zakázku — jedna transakce, vrací vzniklou zakázku (201).
     * Tělo je běžný vstup pro zakázku, uživatel ho v UI před odesláním doplní.
     */
    @PostMapping("/{id}/convert")
    public ResponseEntity<OrderDto.DetailResponse> convert(
            @PathVariable Long id,
            @Valid @RequestBody OrderDto.CreateRequest orderRequest,
            @AuthenticationPrincipal AppUserDetails currentUser) {
        OrderDto.DetailResponse created =
                appointmentService.convert(id, orderRequest, currentUser.getUserId());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    /**
     * Trvalé smazání položky založené omylem (V76) — vrací 204 bez těla, protože po smazání
     * není co vracet. Zrušení skutečné objednávky je změna stavu na {@code CANCELLED}, ne tohle.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @PathVariable Long id,
            @AuthenticationPrincipal AppUserDetails currentUser) {
        requireManagerForClosure(appointmentService.getById(id).getEntryType(), currentUser);
        appointmentService.delete(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Blokace dílny je provozní rozhodnutí vedení, ne mechanika. Řešeno tady a ne přes
     * {@code @PreAuthorize}, protože oprávnění závisí na <em>hodnotě</em> {@code entryType},
     * ne na volané metodě — anotace na to nedosáhne.
     */
    private void requireManagerForClosure(AppointmentType entryType, AppUserDetails currentUser) {
        if (entryType != AppointmentType.CLOSURE) {
            return;
        }
        boolean isManagement = currentUser.getAuthorities().stream()
                .map(authority -> authority.getAuthority())
                .anyMatch(role -> "ROLE_ADMIN".equals(role) || "ROLE_MANAGER".equals(role));
        if (!isManagement) {
            throw new AccessDeniedException(
                    "Blokaci dílny může zakládat a rušit jen vedení.");
        }
    }
}
