package cz.palo.autoservis.exception;

import cz.palo.autoservis.exception.dto.ErrorDetail;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ProblemDetail;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Čistý unit test (bez Spring kontextu) pro kontrakt odpovědí
 * {@link GlobalExceptionHandler}. Frontend parsuje surové tělo ProblemDetail a zobrazuje
 * {@code detail} uživateli (viz api.js + WarehousePage.handleImportSubmit),
 * takže {@code status}, {@code detail} a {@code errors[].code} jsou API kontrakt.
 */
class GlobalExceptionHandlerTest {

    // MessageSource potřebuje jen handler Bean Validation — null tady stačí.
    private final GlobalExceptionHandler handler = new GlobalExceptionHandler(null);

    @Test
    @DisplayName("ConflictException → 409, code from the exception, message in detail")
    void conflictException_mapsTo409WithCodeAndDetail() {
        // ── GIVEN ── přesně ta výjimka, kterou vyhazuje WarehouseImportServiceImpl
        //             při duplicitním importu faktury (kontrola idempotence)
        var ex = new ConflictException("DUPLICATE_IMPORT",
                "Faktura 2026-001 od tohoto dodavatele už je naimportovaná.");
        var request = new MockHttpServletRequest("POST", "/api/v1/warehouse/receipts/import");

        // ── WHEN ──
        ProblemDetail problem = handler.handleConflict(ex, request);

        // ── THEN ── status + detail (frontend zobrazuje detail v modálu)
        assertThat(problem.getStatus()).isEqualTo(409);
        assertThat(problem.getDetail())
                .isEqualTo("Faktura 2026-001 od tohoto dodavatele už je naimportovaná.");
        assertThat(problem.getInstance()).hasPath("/api/v1/warehouse/receipts/import");

        // errors[] nese strojově čitelný kód jako globální (ne-polní) chybu
        @SuppressWarnings("unchecked")
        var errors = (List<ErrorDetail>) problem.getProperties().get("errors");
        assertThat(errors).hasSize(1);
        assertThat(errors.getFirst().code()).isEqualTo("DUPLICATE_IMPORT");
        assertThat(errors.getFirst().field()).isNull();
    }

    @Test
    @DisplayName("IllegalArgumentException → 400, code INVALID_ARGUMENT, detail from the exception message (TD-20)")
    void illegalArgumentException_mapsTo400WithCodeAndDetail() {
        // ── GIVEN ── přesně ta výjimka, kterou vyhazují D2 null guardy (např. CustomerServiceImpl.getById)
        var ex = new IllegalArgumentException("id nesmí být null");
        var request = new MockHttpServletRequest("GET", "/api/v1/vehicles/null");

        // ── WHEN ──
        ProblemDetail problem = handler.handleIllegalArgument(ex, request);

        // ── THEN ──
        assertThat(problem.getStatus()).isEqualTo(400);
        assertThat(problem.getDetail()).isEqualTo("id nesmí být null");
        assertThat(problem.getInstance()).hasPath("/api/v1/vehicles/null");

        @SuppressWarnings("unchecked")
        var errors = (List<ErrorDetail>) problem.getProperties().get("errors");
        assertThat(errors).hasSize(1);
        assertThat(errors.getFirst().code()).isEqualTo("INVALID_ARGUMENT");
        assertThat(errors.getFirst().message()).isEqualTo("id nesmí být null");
        assertThat(errors.getFirst().field()).isNull();
    }
}
