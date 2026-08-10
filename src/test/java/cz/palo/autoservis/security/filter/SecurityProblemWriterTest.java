package cz.palo.autoservis.security.filter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.json.ProblemDetailJacksonMixin;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Čistý unit test (bez Spring kontextu) — zrcadlí
 * {@link cz.palo.autoservis.exception.GlobalExceptionHandlerTest}.
 *
 * <p>Registruje stejný {@link ProblemDetailJacksonMixin}, který Spring Boot přes
 * {@code JacksonAutoConfiguration} aplikuje na skutečný {@code ObjectMapper} bean aplikace
 * ({@code JacksonAutoConfiguration.JsonProblemDetailsConfiguration}), takže tvar JSON
 * ověřovaný zde odpovídá tomu, co injektovaný bean skutečně produkuje za běhu — a tedy
 * odpovídá {@code GlobalExceptionHandler.buildProblemDetail} (V5, analyza-2026-07).
 */
class SecurityProblemWriterTest {

    private final JsonMapper mapper = JsonMapper.builder()
            .addMixIn(ProblemDetail.class, ProblemDetailJacksonMixin.class)
            .build();

    private final SecurityProblemWriter writer = new SecurityProblemWriter(mapper);

    @Test
    @DisplayName("writeUnauthorized produces the same ProblemDetail shape as GlobalExceptionHandler")
    void writeUnauthorized_producesProblemDetailShape() throws Exception {
        var request = new MockHttpServletRequest("GET", "/api/v1/customers");
        var response = new MockHttpServletResponse();

        writer.writeUnauthorized(request, response, "TOKEN_EXPIRED", "Platnost tokenu vypršela.");

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentType()).isEqualTo("application/problem+json;charset=UTF-8");

        Map<?, ?> body = mapper.readValue(response.getContentAsString(), Map.class);
        assertThat(body.get("title")).isEqualTo("Unauthorized");
        assertThat(body.get("status")).isEqualTo(401);
        assertThat(body.get("detail")).isEqualTo("Platnost tokenu vypršela.");
        assertThat(body.get("instance")).isEqualTo("/api/v1/customers");

        @SuppressWarnings("unchecked")
        var errors = (List<Map<String, Object>>) body.get("errors");
        assertThat(errors).hasSize(1);
        assertThat(errors.getFirst().get("code")).isEqualTo("TOKEN_EXPIRED");
        assertThat(errors.getFirst().get("message")).isEqualTo("Platnost tokenu vypršela.");
        assertThat(errors.getFirst()).containsEntry("field", null);
        assertThat(errors.getFirst()).containsEntry("params", null);
    }
}
