package cz.palo.autoservis.security.filter;

import cz.palo.autoservis.exception.dto.ErrorDetail;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.util.List;

/**
 * Zapisuje 401 odpovědi ve formátu RFC 9457 {@link ProblemDetail} přímo ze servlet filter chainu.
 *
 * <p>Authentication entry point v {@link cz.palo.autoservis.config.security.SecurityConfig}
 * i {@link JwtAuthenticationFilter} odmítají requesty <em>před</em> spuštěním
 * {@code DispatcherServlet} Spring MVC, takže se ani jeden nemůže opřít
 * o {@link cz.palo.autoservis.exception.GlobalExceptionHandler} ({@code @RestControllerAdvice}
 * vidí jen výjimky vyhozené uvnitř controllerů). Tato třída staví identický tvar odpovědi
 * ručně — stejná konstrukce {@link ProblemDetail}, stejné položky {@link ErrorDetail} —
 * takže 401 z filter chainu je k nerozeznání od jakékoli jiné chybové odpovědi API
 * (V5, analyza-2026-07).
 *
 * <p>Sídlí vedle {@link JwtAuthenticationFilter} v balíčku {@code security.filter},
 * ne v {@code exception} — obě volající místa ({@code SecurityConfig},
 * {@code JwtAuthenticationFilter}) jsou kód security filter chainu, ne MVC obsluha výjimek;
 * zachovává se tak směr závislostí: security kód závisí na DTO z {@code exception.dto},
 * ne naopak.
 */
@Component
@RequiredArgsConstructor
public class SecurityProblemWriter {

    private final ObjectMapper objectMapper;

    /**
     * Zapíše a odešle 401 Unauthorized odpověď ve formátu {@link ProblemDetail}.
     *
     * @param request  příchozí HTTP požadavek — pro {@code instance} URI
     * @param response HTTP odpověď (nesmí být ještě odeslaná)
     * @param code     strojový chybový kód (např. {@code TOKEN_EXPIRED})
     * @param detail   lidsky čitelná zpráva (česky)
     */
    public void writeUnauthorized(HttpServletRequest request, HttpServletResponse response,
                                   String code, String detail) throws IOException {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, detail);
        problem.setTitle(HttpStatus.UNAUTHORIZED.getReasonPhrase());
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("errors", List.of(ErrorDetail.ofGlobal(code, detail)));

        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE + ";charset=UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(problem));
    }
}
