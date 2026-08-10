package cz.palo.autoservis.web;

import cz.palo.autoservis.AbstractIntegrationTest;
import cz.palo.autoservis.model.domain.user.Role;
import cz.palo.autoservis.model.domain.user.User;
import cz.palo.autoservis.security.model.domain.AppUserDetails;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Kontrakt chybových odpovědí (RFC 9457 {@code ProblemDetail} + rozšíření {@code errors[]})
 * napříč moduly — přesně tak, jak ho popisuje {@code docs/api.md}.
 *
 * <p><strong>Proč přes MockMvc a ne přes service:</strong> frontend na tomhle tvaru staví
 * (parsuje {@code detail} a {@code errors[].code}). Test na úrovni service by ověřil, že se
 * vyhodí správná výjimka, ale ne že se z ní stane správný HTTP status, {@code Content-Type}
 * {@code application/problem+json} a správná struktura JSONu. Rozbít se přitom může kterákoli
 * z těch vrstev zvlášť.
 *
 * <p>U každé odpovědi se proto tvrdí <strong>status, Content-Type, title, detail i kód
 * v {@code errors[]}</strong> — ne jen „vrátilo to chybu".
 */
@AutoConfigureMockMvc
@Transactional
class ProblemDetailContractTest extends AbstractIntegrationTest {

    private static final String PROBLEM_JSON = "application/problem+json";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private cz.palo.autoservis.security.mapper.UserMapper userMapper;

    /** Externí registr se mockuje — test ověřuje překlad chyby na 503, ne dostupnost služby. */
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private cz.palo.autoservis.client.VehicleRegistryClient vehicleRegistryClient;

    private static AppUserDetails admin() {
        return principalWithRole("ROLE_ADMIN");
    }

    private static AppUserDetails mechanic() {
        return principalWithRole("ROLE_MECHANIC");
    }

    /**
     * Principal se předává jako skutečný {@link AppUserDetails} přes {@code user(...)} —
     * {@code @WithMockUser} by u {@code @AuthenticationPrincipal AppUserDetails} dal null.
     */
    private static AppUserDetails principalWithRole(String roleName) {
        return new AppUserDetails(User.builder()
                .id(1L).username("admin").passwordHash("n/a")
                .enabled(true).accountNonExpired(true).accountNonLocked(true)
                .credentialsNonExpired(true)
                .roles(List.of(Role.builder().name(roleName).build()))
                .build());
    }

    // =========================================================================
    // 404 — ResourceNotFoundException
    // =========================================================================

    @Nested
    @DisplayName("404 RESOURCE_NOT_FOUND")
    class NotFound {

        @Test
        @DisplayName("neznámé id vozidla → 404 s kódem, názvem zdroje i jeho id v params")
        void unknownVehicle_returnsResourceNotFound() throws Exception {
            mockMvc.perform(get("/api/v1/vehicles/999999").with(user(admin())))
                    .andExpect(status().isNotFound())
                    .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                    .andExpect(jsonPath("$.status").value(404))
                    .andExpect(jsonPath("$.title").value("Not Found"))
                    .andExpect(jsonPath("$.instance").value("/api/v1/vehicles/999999"))
                    .andExpect(jsonPath("$.errors[0].code").value("RESOURCE_NOT_FOUND"))
                    .andExpect(jsonPath("$.errors[0].params.resourceName").value("Vozidlo"))
                    .andExpect(jsonPath("$.errors[0].params.resourceId").value("999999"));
        }

        @Test
        @DisplayName("neznámé id zákazníka → 404 se jménem zdroje Zákazník")
        void unknownCustomer_returnsResourceNotFound() throws Exception {
            mockMvc.perform(get("/api/v1/customers/999999").with(user(admin())))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.errors[0].code").value("RESOURCE_NOT_FOUND"))
                    .andExpect(jsonPath("$.errors[0].params.resourceName").value("Zákazník"));
        }

        @Test
        @DisplayName("neznámé id faktury → 404 se jménem zdroje Faktura")
        void unknownInvoice_returnsResourceNotFound() throws Exception {
            mockMvc.perform(get("/api/v1/invoices/999999").with(user(admin())))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.errors[0].code").value("RESOURCE_NOT_FOUND"))
                    .andExpect(jsonPath("$.errors[0].params.resourceName").value("Faktura"));
        }

        @Test
        @DisplayName("neznámá cesta (žádný handler) → 404 NOT_FOUND, ne 500 z catch-all (TD-61/S-5)")
        void unknownPath_returnsNotFound() throws Exception {
            mockMvc.perform(get("/api/v1/tahle-cesta-neexistuje").with(user(admin())))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.errors[0].code").value("NOT_FOUND"));
        }
    }

    // =========================================================================
    // 400 — Bean Validation
    // =========================================================================

    @Nested
    @DisplayName("400 validace @Valid")
    class Validation {

        @Test
        @DisplayName("chybějící povinné pole → 400 REQUIRED s názvem pole")
        void missingRequiredField_returnsRequired() throws Exception {
            // VIN je od V90 nepovinný (stroje bez VIN) — kontrakt REQUIRED se ověřuje
            // na značce, která povinná zůstala.
            String body = """
                    {"customerId": 1, "model": "Octavia"}
                    """;

            mockMvc.perform(post("/api/v1/vehicles").with(user(admin()))
                            .contentType(APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.title").value("Bad Request"))
                    .andExpect(jsonPath("$.detail").value("Ověření zadaných údajů selhalo"))
                    .andExpect(jsonPath("$.errors[?(@.field=='brand')].code").value("REQUIRED"));
        }

        @Test
        @DisplayName("neplatný formát VIN → 400 INVALID_PATTERN na poli vin")
        void invalidVinPattern_returnsInvalidPattern() throws Exception {
            String body = """
                    {"customerId": 1, "vin": "KRATKY-VIN", "brand": "Škoda",
                     "model": "Octavia", "fuelType": "PETROL"}
                    """;

            mockMvc.perform(post("/api/v1/vehicles").with(user(admin()))
                            .contentType(APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[?(@.field=='vin')].code").value("INVALID_PATTERN"));
        }

        @Test
        @DisplayName("krátké heslo → 400 SIZE_EXCEEDED (kód se odvozuje z anotace @Size)")
        void tooShortPassword_returnsSizeExceeded() throws Exception {
            String body = """
                    {"username": "novy", "email": "novy@autoservis.cz",
                     "password": "krat", "roleIds": [1]}
                    """;

            mockMvc.perform(post("/api/v1/users").with(user(admin()))
                            .contentType(APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[?(@.field=='password')].code").value("SIZE_EXCEEDED"));
        }

        @Test
        @DisplayName("neplatný e-mail → 400 INVALID_EMAIL")
        void invalidEmail_returnsInvalidEmail() throws Exception {
            String body = """
                    {"username": "novy", "email": "tohle-neni-email",
                     "password": "Password1!", "roleIds": [1]}
                    """;

            mockMvc.perform(post("/api/v1/users").with(user(admin()))
                            .contentType(APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[?(@.field=='email')].code").value("INVALID_EMAIL"));
        }

        @Test
        @DisplayName("vlastní class-level validátor nese kód přímo (CUSTOMER_NAME_REQUIRED)")
        void customValidator_carriesItsOwnCode() throws Exception {
            String body = """
                    {"customerType": "INDIVIDUAL", "firstName": "Jan", "gdprConsent": true,
                     "addresses": [{"addressType": "BILLING", "street": "Hlavní",
                                    "streetNumber": "1", "city": "Praha", "postalCode": "11000"}]}
                    """;

            mockMvc.perform(post("/api/v1/customers").with(user(admin()))
                            .contentType(APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].code").value("CUSTOMER_NAME_REQUIRED"));
        }

        @Test
        @DisplayName("více porušených pravidel → všechna jsou v errors[], ne jen první")
        void multipleViolations_areAllReported() throws Exception {
            String body = """
                    {"username": "x", "email": "spatny", "password": "krat", "roleIds": []}
                    """;

            mockMvc.perform(post("/api/v1/users").with(user(admin()))
                            .contentType(APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors.length()").value(org.hamcrest.Matchers.greaterThanOrEqualTo(3)))
                    .andExpect(jsonPath("$.errors[?(@.field=='email')].code").value("INVALID_EMAIL"))
                    .andExpect(jsonPath("$.errors[?(@.field=='password')].code").value("SIZE_EXCEEDED"))
                    .andExpect(jsonPath("$.errors[?(@.field=='roleIds')].code").value("REQUIRED"));
        }
    }

    // =========================================================================
    // 422 — BusinessRuleException
    // =========================================================================

    @Nested
    @DisplayName("422 business pravidla")
    class BusinessRules {

        @Test
        @DisplayName("duplicitní VIN → 422 DUPLICATE_VIN s polem vin")
        void duplicateVin_returnsBusinessRuleCode() throws Exception {
            String body = """
                    {"customerId": 1, "vin": "WBA3A5C50DF595551", "brand": "Škoda",
                     "model": "Octavia", "fuelType": "PETROL"}
                    """;

            mockMvc.perform(post("/api/v1/vehicles").with(user(admin()))
                            .contentType(APPLICATION_JSON).content(body))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                    .andExpect(jsonPath("$.status").value(422))
                    .andExpect(jsonPath("$.title").value("Unprocessable Entity"))
                    .andExpect(jsonPath("$.errors[0].code").value("DUPLICATE_VIN"))
                    .andExpect(jsonPath("$.errors[0].field").value("vin"));
        }

        @Test
        @DisplayName("deaktivace vozidla s otevřenou zakázkou → 422 VEHICLE_HAS_OPEN_ORDERS s params")
        void deactivateVehicleWithOpenOrder_returnsBusinessRuleCode() throws Exception {
            mockMvc.perform(delete("/api/v1/vehicles/7").with(user(admin())))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.errors[0].code").value("VEHICLE_HAS_OPEN_ORDERS"))
                    .andExpect(jsonPath("$.errors[0].params.openOrders").exists());
        }

        @Test
        @DisplayName("deaktivace zákazníka s otevřenou zakázkou → 422 CUSTOMER_HAS_OPEN_ORDERS")
        void deactivateCustomerWithOpenOrder_returnsBusinessRuleCode() throws Exception {
            mockMvc.perform(delete("/api/v1/customers/3").with(user(admin())))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.errors[0].code").value("CUSTOMER_HAS_OPEN_ORDERS"));
        }

        @Test
        @DisplayName("deaktivace vlastního účtu → 422 CANNOT_DEACTIVATE_SELF (globální chyba bez pole)")
        void deactivateOwnAccount_returnsGlobalErrorWithoutField() throws Exception {
            mockMvc.perform(delete("/api/v1/users/1").with(user(admin())))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.errors[0].code").value("CANNOT_DEACTIVATE_SELF"))
                    .andExpect(jsonPath("$.errors[0].field").doesNotExist());
        }
    }

    // =========================================================================
    // 400 — IllegalArgumentException (TD-20)
    // =========================================================================

    @Nested
    @DisplayName("400 INVALID_ARGUMENT")
    class InvalidArgument {

        // Test „null identifikátor ze service → 400 INVALID_ARGUMENT" tady BÝVAL, ale byl planý
        // (audit 2026-07-30, KN-21 / 09-T-3): volal `GET /vehicles/1/mileage` a asertoval
        // `status().isOk()` — tedy ověřoval úspěch na endpointu, který 400 nikdy nevrátí. Odstraněn
        // místo „opravy" proto, že tuhle cestu přes HTTP nasimulovat nelze: identifikátory jsou
        // path variables, takže `null` se do service nedostane a její guard je nedosažitelný.
        // Mapování `IllegalArgumentException` → 400 `INVALID_ARGUMENT` (TD-20) proto ověřuje
        // jednotkový `GlobalExceptionHandlerTest` přímo nad handlerem; přes HTTP je ve stejném
        // kódu doložený testem níže (chybný typ v cestě).

        @Test
        @DisplayName("nečíselné id v cestě → 400 INVALID_ARGUMENT (TD-52)")
        void nonNumericPathId_returnsBadRequest() throws Exception {
            // MethodArgumentTypeMismatchException (překlep v URL) má vlastní handler → 400,
            // ne pád do catch-all na 500. Klient poslal nesmysl, ne selhání serveru.
            mockMvc.perform(get("/api/v1/vehicles/neni-cislo").with(user(admin())))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.errors[0].code").value("INVALID_ARGUMENT"));
        }
    }

    // =========================================================================
    // 401 / 403 — bezpečnostní kontrakty
    // =========================================================================

    @Nested
    @DisplayName("401 a 403")
    class Security {

        @Test
        @DisplayName("bez přihlášení → 401 UNAUTHORIZED ve stejném tvaru ProblemDetail")
        void withoutAuthentication_returnsUnauthorizedProblemDetail() throws Exception {
            mockMvc.perform(get("/api/v1/vehicles/1"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                    .andExpect(jsonPath("$.status").value(401))
                    .andExpect(jsonPath("$.errors[0].code").value("UNAUTHORIZED"));
        }

        @Test
        @DisplayName("správa uživatelů bez role ADMIN → 403 ACCESS_DENIED")
        void userAdministrationWithoutAdminRole_returnsForbidden() throws Exception {
            mockMvc.perform(get("/api/v1/users").with(user(mechanic())))
                    .andExpect(status().isForbidden())
                    .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                    .andExpect(jsonPath("$.status").value(403))
                    .andExpect(jsonPath("$.title").value("Forbidden"))
                    .andExpect(jsonPath("$.errors[0].code").value("ACCESS_DENIED"));
        }

        @Test
        @DisplayName("správa uživatelů s rolí ADMIN projde (druhá větev autorizace)")
        void userAdministrationWithAdminRole_isAllowed() throws Exception {
            mockMvc.perform(get("/api/v1/users").with(user(admin())))
                    .andExpect(status().isOk());
        }
    }

    // =========================================================================
    // Chybové kontrakty přihlášení a registru (kódy, na které reaguje frontend)
    // =========================================================================

    @Nested
    @DisplayName("401 přihlášení, 409 konflikt, 503 registr")
    class OtherErrorContracts {

        @Test
        @DisplayName("špatné heslo → 401 BAD_CREDENTIALS (stejná hláška pro jméno i heslo)")
        void wrongPassword_returnsBadCredentials() throws Exception {
            String body = """
                    {"username": "admin", "password": "SpatneHeslo1!"}
                    """;

            mockMvc.perform(post("/api/v1/auth/login").contentType(APPLICATION_JSON).content(body))
                    .andExpect(status().isUnauthorized())
                    .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                    .andExpect(jsonPath("$.status").value(401))
                    .andExpect(jsonPath("$.errors[0].code").value("BAD_CREDENTIALS"));
        }

        @Test
        @DisplayName("neexistující uživatel → taky BAD_CREDENTIALS (prevence enumerace účtů)")
        void unknownUsername_returnsSameCodeAsWrongPassword() throws Exception {
            String body = """
                    {"username": "neexistujici-ucet", "password": "Password1!"}
                    """;

            mockMvc.perform(post("/api/v1/auth/login").contentType(APPLICATION_JSON).content(body))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.errors[0].code")
                            .value("BAD_CREDENTIALS"));
        }

        @Test
        @DisplayName("zamčený účet → 401 ACCOUNT_LOCKED (jiný kód než špatné heslo)")
        void lockedAccount_returnsAccountLocked() throws Exception {
            userMapper.lockAccount(3L); // mechanic

            String body = """
                    {"username": "mechanic", "password": "Password1!"}
                    """;

            mockMvc.perform(post("/api/v1/auth/login").contentType(APPLICATION_JSON).content(body))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.errors[0].code").value("ACCOUNT_LOCKED"));
        }

        @Test
        @DisplayName("obsazené uživatelské jméno → 409 USER_ALREADY_EXISTS")
        void duplicateUsername_returnsConflict() throws Exception {
            String body = """
                    {"username": "admin", "email": "jiny@autoservis.cz",
                     "password": "Password1!", "roleIds": [1]}
                    """;

            mockMvc.perform(post("/api/v1/users").with(user(admin()))
                            .contentType(APPLICATION_JSON).content(body))
                    .andExpect(status().isConflict())
                    .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                    .andExpect(jsonPath("$.status").value(409))
                    .andExpect(jsonPath("$.title").value("Conflict"))
                    .andExpect(jsonPath("$.errors[0].code").value("USER_ALREADY_EXISTS"));
        }

        @Test
        @DisplayName("nedostupný registr vozidel → 503 s konkrétním kódem důvodu")
        void registryUnavailable_returnsServiceUnavailableWithReasonCode() throws Exception {
            org.mockito.BDDMockito.given(vehicleRegistryClient.fetch(org.mockito.ArgumentMatchers.any()))
                    .willThrow(new cz.palo.autoservis.exception.RegistryUnavailableException(
                            "REGISTRY_RATE_LIMITED", "Registr je přetížen, zkuste to za chvíli."));

            mockMvc.perform(get("/api/v1/vehicles/registry-lookup?vin=WBA3A5C50DF595551")
                            .with(user(admin())))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                    .andExpect(jsonPath("$.status").value(503))
                    .andExpect(jsonPath("$.errors[0].code").value("REGISTRY_RATE_LIMITED"));
        }
    }

    // =========================================================================
    // Kontrakt úspěšných odpovědí, na kterých závisí frontend
    // =========================================================================

    @Nested
    @DisplayName("úspěšné odpovědi")
    class SuccessContracts {

        @Test
        @DisplayName("POST vrací 201 s hlavičkou Location na nový zdroj (TD-12)")
        void createReturnsLocationHeader() throws Exception {
            String body = """
                    {"customerId": 1, "vin": "VF1RJB00X66123456", "brand": "Renault",
                     "model": "Mégane", "fuelType": "PETROL"}
                    """;

            mockMvc.perform(post("/api/v1/vehicles").with(user(admin()))
                            .contentType(APPLICATION_JSON).content(body))
                    .andExpect(status().isCreated())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                            .header().string("Location", org.hamcrest.Matchers.matchesPattern(".*/api/v1/vehicles/\\d+")))
                    .andExpect(jsonPath("$.id").exists())
                    .andExpect(jsonPath("$.vin").value("VF1RJB00X66123456"));
        }

        @Test
        @DisplayName("prázdný řetězec u volitelného ENUMu se čte jako NULL, ne jako chyba parsování (V86)")
        void emptyStringEnumIsReadAsNull() throws Exception {
            // Formulář posílá nevyplněný <select> jako "", nikdy jako JSON null. Bez pravidla
            // v JacksonConfig by request spadl na HttpMessageNotReadableException už při
            // deserializaci — tedy dřív, než se ke slovu dostane validace (viz docs/funkce/palivo-nepovinne.md).
            String body = """
                    {"customerId": 1, "vin": "TMBJJ7NE0G0123456", "brand": "Agados",
                     "model": "Handy 20", "fuelType": "", "transmission": ""}
                    """;

            mockMvc.perform(post("/api/v1/vehicles").with(user(admin()))
                            .contentType(APPLICATION_JSON).content(body))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.fuelType").value(org.hamcrest.Matchers.nullValue()))
                    .andExpect(jsonPath("$.transmission").value(org.hamcrest.Matchers.nullValue()));
        }

        @Test
        @DisplayName("DELETE = soft delete → 200 s deaktivovaným objektem, ne 204")
        void deleteReturnsDeactivatedBody() throws Exception {
            mockMvc.perform(delete("/api/v1/vehicles/1").with(user(admin())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(1))
                    .andExpect(jsonPath("$.active").value(false));
        }

        @Test
        @DisplayName("id se bere z cesty, ne z těla — tělo s cizím id ho nepřepíše (R-14)")
        void idInBodyIsIgnored() throws Exception {
            String body = """
                    {"id": 999999, "customerId": 1, "vin": "WBA3A5C50DF595551",
                     "licensePlate": "9XY 8765", "brand": "BMW", "model": "3 Series",
                     "fuelType": "PETROL"}
                    """;

            mockMvc.perform(put("/api/v1/vehicles/1").with(user(admin()))
                            .contentType(APPLICATION_JSON).content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(1))
                    .andExpect(jsonPath("$.licensePlate").value("9XY 8765"));
        }

        @Test
        @DisplayName("stránkovaný seznam má všechna pole PagedResponse; první stránka má first=true")
        void pagedListHasCompleteEnvelope() throws Exception {
            mockMvc.perform(get("/api/v1/vehicles?page=1&pageSize=5").with(user(admin())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content").isArray())
                    .andExpect(jsonPath("$.content.length()").value(5))
                    .andExpect(jsonPath("$.page").value(1))
                    .andExpect(jsonPath("$.pageSize").value(5))
                    .andExpect(jsonPath("$.totalElements").exists())
                    .andExpect(jsonPath("$.totalPages").exists())
                    .andExpect(jsonPath("$.first").value(true))
                    .andExpect(jsonPath("$.last").value(false));
        }

        @Test
        @DisplayName("příznak first je true na první stránce (1-based, TD-50)")
        void pagedResponseFirstFlag_isTrueOnFirstPage() throws Exception {
            mockMvc.perform(get("/api/v1/vehicles?page=1&pageSize=5").with(user(admin())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.page").value(1))
                    .andExpect(jsonPath("$.first").value(true));
        }

        @Test
        @DisplayName("předposlední stránka NENÍ poslední — last se rozsvítí až na skutečné poslední (TD-50)")
        void pagedResponseLastFlag_notSetOnSecondToLastPage() throws Exception {
            // Při 20 vozidlech a pageSize=5 jsou 4 stránky. Na stránce 3 musí být last=false
            // (stránka 4 teprve následuje) a na stránce 4 last=true.
            mockMvc.perform(get("/api/v1/vehicles?page=3&pageSize=5").with(user(admin())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.page").value(3))
                    .andExpect(jsonPath("$.totalPages").value(4))
                    .andExpect(jsonPath("$.first").value(false))
                    .andExpect(jsonPath("$.last").value(false))
                    .andExpect(jsonPath("$.content.length()").value(5));

            mockMvc.perform(get("/api/v1/vehicles?page=4&pageSize=5").with(user(admin())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.page").value(4))
                    .andExpect(jsonPath("$.last").value(true));
        }

        @Test
        @DisplayName("page=0 se ořízne na 1 → 200, ne 500 (E6.2/S-6)")
        void pageZero_isClampedNotServerError() throws Exception {
            mockMvc.perform(get("/api/v1/vehicles?page=0&pageSize=5").with(user(admin())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.page").value(1));
        }
    }

    @Test
    @DisplayName("neparsovatelné tělo requestu → 400 MALFORMED_REQUEST (E6.1/S-5)")
    void malformedJsonBody_returnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/v1/orders").with(user(admin()))
                        .contentType(APPLICATION_JSON).content("{ tohle neni validni json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("MALFORMED_REQUEST"));
    }
}
