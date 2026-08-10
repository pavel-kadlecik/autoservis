package cz.palo.autoservis.service;

import cz.palo.autoservis.AbstractIntegrationTest;
import cz.palo.autoservis.exception.BusinessRuleException;
import cz.palo.autoservis.model.domain.user.Role;
import cz.palo.autoservis.model.domain.user.User;
import cz.palo.autoservis.model.dto.customer.CustomerDto;
import cz.palo.autoservis.security.model.domain.AppUserDetails;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * TD-10 — podmíněně povinná pole (INDIVIDUAL → firstName+lastName, COMPANY → companyName).
 *
 * <p>Testují se dvě vrstvy:
 * <ul>
 *     <li>{@code CreateRequest}: celý HTTP řetězec přes {@link MockMvc} — ověřuje, že celá
 *         pipeline request→{@code @Valid}→{@code @ValidCustomerRequest}→{@code GlobalExceptionHandler}
 *         opravdu vrací 400 s očekávaným kódem (ne staré 422
 *         {@code DATA_INTEGRITY_VIOLATION} z DB CHECK constraintu).</li>
 *     <li>{@code UpdateRequest}: {@link CustomerService} volaný přímo — Bean Validation
 *         běží v controlleru (na {@code @RequestBody @Valid}), ne v service, a
 *         {@code UpdateRequest} beztak nemá {@code customerType}, proti kterému by validoval;
 *         pravidlo žije v {@code CustomerServiceImpl.requireNameOrCompanyPresent} a vyhazuje
 *         {@link BusinessRuleException} (422) přímo — k jeho pozorování není HTTP vrstva potřeba.</li>
 * </ul>
 *
 * Seed data (migrace V3): zákazník id 1 = Jan Novák, INDIVIDUAL.
 */
@AutoConfigureMockMvc
@Transactional
class CustomerValidationTest extends AbstractIntegrationTest {

    private static final String CUSTOMERS_URL = "/api/v1/customers";
    private static final long SEED_INDIVIDUAL_ID = 1L;

    @Autowired private MockMvc mockMvc;
    @Autowired private CustomerService customerService;

    private AppUserDetails admin() {
        return new AppUserDetails(User.builder()
                .id(1L).username("admin").passwordHash("n/a")
                .enabled(true).accountNonExpired(true).accountNonLocked(true)
                .credentialsNonExpired(true)
                .roles(List.of(Role.builder().name("ROLE_ADMIN").build()))
                .build());
    }

    private String validAddressJson() {
        return """
                "addresses": [
                    {"addressType": "BILLING", "street": "Hlavní", "streetNumber": "1",
                     "city": "Praha", "postalCode": "11000"}
                ]
                """;
    }

    @Nested
    @DisplayName("POST /customers — @ValidCustomerRequest")
    class Create {

        @Test
        @DisplayName("COMPANY bez companyName → 400 CUSTOMER_COMPANY_REQUIRED (ne 422 z DB)")
        void companyWithoutCompanyName_returns400() throws Exception {
            String body = """
                    {"customerType": "COMPANY", "gdprConsent": true, %s}
                    """.formatted(validAddressJson());

            mockMvc.perform(post(CUSTOMERS_URL)
                            .contentType(APPLICATION_JSON).content(body)
                            .with(user(admin())))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[?(@.field=='companyName')].code")
                            .value("CUSTOMER_COMPANY_REQUIRED"))
                    .andExpect(jsonPath("$.errors[?(@.field=='companyName')].message")
                            .value("Název firmy je u firmy povinný"));
        }

        @Test
        @DisplayName("INDIVIDUAL bez lastName → 400 CUSTOMER_NAME_REQUIRED na poli lastName")
        void individualWithoutLastName_returns400() throws Exception {
            String body = """
                    {"customerType": "INDIVIDUAL", "firstName": "Petr", "gdprConsent": true, %s}
                    """.formatted(validAddressJson());

            mockMvc.perform(post(CUSTOMERS_URL)
                            .contentType(APPLICATION_JSON).content(body)
                            .with(user(admin())))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[?(@.field=='lastName')].code")
                            .value("CUSTOMER_NAME_REQUIRED"));
        }

        @Test
        @DisplayName("validní INDIVIDUAL → 201")
        void validIndividual_succeeds() throws Exception {
            String body = """
                    {"customerType": "INDIVIDUAL", "firstName": "Petr", "lastName": "Testovací",
                     "gdprConsent": true, %s}
                    """.formatted(validAddressJson());

            mockMvc.perform(post(CUSTOMERS_URL)
                            .contentType(APPLICATION_JSON).content(body)
                            .with(user(admin())))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.firstName").value("Petr"))
                    .andExpect(jsonPath("$.lastName").value("Testovací"));
        }

        @Test
        @DisplayName("validní COMPANY → 201")
        void validCompany_succeeds() throws Exception {
            String body = """
                    {"customerType": "COMPANY", "companyName": "Testovací s.r.o.",
                     "gdprConsent": true, %s}
                    """.formatted(validAddressJson());

            mockMvc.perform(post(CUSTOMERS_URL)
                            .contentType(APPLICATION_JSON).content(body)
                            .with(user(admin())))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.companyName").value("Testovací s.r.o."));
        }
    }

    @Nested
    @DisplayName("PUT /customers/{id} — service-level rule (immutable type, no customerType in body)")
    class Update {

        @Test
        @DisplayName("INDIVIDUAL zákazník + update s prázdným lastName → BusinessRuleException CUSTOMER_NAME_REQUIRED")
        void individualWithoutLastName_throwsBusinessRuleException() {
            CustomerDto.UpdateRequest update = new CustomerDto.UpdateRequest();
            update.setFirstName("Jan");
            update.setLastName("");   // full-replace sémantika — prázdný řetězec přepíše existující hodnotu
            update.setGdprConsent(true);

            assertThatThrownBy(() -> customerService.update(SEED_INDIVIDUAL_ID, update, 1L))
                    .isInstanceOf(BusinessRuleException.class)
                    .satisfies(ex -> {
                        BusinessRuleException bre = (BusinessRuleException) ex;
                        assertThat(bre.getRuleCode()).isEqualTo("CUSTOMER_NAME_REQUIRED");
                        assertThat(bre.getField()).isEqualTo("lastName");
                    });
        }

        @Test
        @DisplayName("validní update INDIVIDUAL zákazníka projde beze změny chování")
        void individualWithBothNames_succeeds() {
            CustomerDto.UpdateRequest update = new CustomerDto.UpdateRequest();
            update.setFirstName("Jan");
            update.setLastName("Novák-aktualizováno");
            update.setGdprConsent(true);

            CustomerDto.DetailResponse result = customerService.update(SEED_INDIVIDUAL_ID, update, 1L);

            assertThat(result.getLastName()).isEqualTo("Novák-aktualizováno");
        }

        @Test
        @DisplayName("400 přes celý HTTP řetězec pro update (MockMvc) — 422, protože jde o BusinessRuleException")
        void individualWithoutLastName_viaHttp_returns422() throws Exception {
            String body = """
                    {"firstName": "Jan", "lastName": "", "gdprConsent": true}
                    """;

            mockMvc.perform(put(CUSTOMERS_URL + "/" + SEED_INDIVIDUAL_ID)
                            .contentType(APPLICATION_JSON).content(body)
                            .with(user(admin())))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.errors[0].code").value("CUSTOMER_NAME_REQUIRED"));
        }
    }

    /**
     * TD-23 — {@code gdprConsent}/{@code marketingConsent} jsou na {@code UpdateRequest}
     * typu {@code Boolean} (ne {@code boolean}), takže request body, které je vynechá,
     * nechá uloženou hodnotu nedotčenou — místo aby Jackson chybějící pole doplnil na
     * {@code false} a {@code CustomerConverter.applyUpdate} tiše přepsal dříve udělený
     * souhlas.
     *
     * <p>Seed zákazník 1 (migrace V3) má {@code gdpr_consent = TRUE} a
     * {@code marketing_consent = TRUE}.
     */
    @Nested
    @DisplayName("PUT /customers/{id} — TD-23 gdprConsent/marketingConsent PATCH-tolerant")
    class UpdateConsentFields {

        @Test
        @DisplayName("update bez gdprConsent/marketingConsent v JSON → hodnoty v DB se nezmění")
        void updateWithoutConsentFields_keepsStoredValues() throws Exception {
            String body = """
                    {"firstName": "Jan", "lastName": "Novák"}
                    """;

            mockMvc.perform(put(CUSTOMERS_URL + "/" + SEED_INDIVIDUAL_ID)
                            .contentType(APPLICATION_JSON).content(body)
                            .with(user(admin())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.gdprConsent").value(true))
                    .andExpect(jsonPath("$.marketingConsent").value(true));
        }

        @Test
        @DisplayName("service-level: UpdateRequest s null gdprConsent/marketingConsent → hodnoty se nepřepíší")
        void applyUpdate_withNullConsentFields_keepsExistingValues() {
            CustomerDto.UpdateRequest update = new CustomerDto.UpdateRequest();
            update.setFirstName("Jan");
            update.setLastName("Novák");
            // gdprConsent / marketingConsent úmyslně nenastaveno → zůstávají null

            CustomerDto.DetailResponse result = customerService.update(SEED_INDIVIDUAL_ID, update, 1L);

            assertThat(result.isGdprConsent()).isTrue();
            assertThat(result.isMarketingConsent()).isTrue();
        }
    }
}
