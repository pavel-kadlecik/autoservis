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
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Granulární rolová autorizace (E7 / audit R-6): matice role × operace.
 *
 * <p>Baseline {@code /api/**} smí všechny pracovní role; nad rámec toho jsou účetní a správní
 * úkony vyhrazeny vedení (ADMIN/MANAGER) přes {@code @PreAuthorize} na controllerech. Test tvrdí
 * dvě věci: <strong>MECHANIC dostane 403</strong> na vyhrazených operacích, ale prochází na
 * baseline (není plošně zablokovaný); a <strong>vedení autorizací projde</strong> (na neexistující
 * id dostane 404, ne 403 — tj. bránu prošlo a spadlo až na business vrstvě).
 *
 * <p>Většina případů jsou bez-tělové endpointy: {@code @PreAuthorize} se vyhodnocuje až po
 * deserializaci {@code @RequestBody}, takže u vadného těla by 400 předběhla 403. Endpoint s tělem
 * (PUT profilu firmy) se proto testuje s **platným** tělem — dřív se u něj spoléhalo na to, že
 * „nese stejnou anotaci", takže nejcitlivější správní operace (IBAN na fakturách) test neměla
 * (audit KN-21, bod 6.2).
 */
@AutoConfigureMockMvc
@Transactional
class RoleAuthorizationTest extends AbstractIntegrationTest {

    private static final long ABSENT_ID = 999_999L;

    /**
     * Vystavení nese tělo (číslo dokladu a datum vystavení) — bez něj by 400 z {@code @Valid}
     * předběhla 403/404, viz javadoc třídy. Datum je pevné a v minulosti: tenhle test na jeho
     * hodnotě nestojí, jen musí projít validací.
     */
    private static final String ISSUE_BODY =
            "{\"invoiceNumber\":\"202699999\",\"issueDate\":\"2026-01-15\"}";

    @Autowired
    private MockMvc mockMvc;

    private static AppUserDetails userWithRole(String username, String role) {
        return new AppUserDetails(User.builder()
                .id(1L).username(username).passwordHash("n/a")
                .enabled(true).accountNonExpired(true).accountNonLocked(true)
                .credentialsNonExpired(true)
                .roles(List.of(Role.builder().name(role).build()))
                .build());
    }

    private static AppUserDetails mechanic() {
        return userWithRole("mechanic", "ROLE_MECHANIC");
    }

    private static AppUserDetails manager() {
        return userWithRole("manager", "ROLE_MANAGER");
    }

    private static AppUserDetails admin() {
        return userWithRole("admin", "ROLE_ADMIN");
    }

    @Nested
    @DisplayName("MECHANIC — vyhrazené operace → 403")
    class MechanicForbidden {

        @Test
        @DisplayName("vystavení faktury → 403")
        void issueInvoice() throws Exception {
            mockMvc.perform(post("/api/v1/invoices/{id}/issue", ABSENT_ID).with(user(mechanic()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(ISSUE_BODY))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("evidence úhrady → 403")
        void payInvoice() throws Exception {
            mockMvc.perform(post("/api/v1/invoices/{id}/pay", ABSENT_ID).with(user(mechanic())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("smazání konceptu faktury → 403")
        void deleteInvoice() throws Exception {
            mockMvc.perform(delete("/api/v1/invoices/{id}", ABSENT_ID).with(user(mechanic())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("dobropis (celý controller) → 403")
        void creditNote() throws Exception {
            mockMvc.perform(get("/api/v1/credit-notes/{id}", ABSENT_ID).with(user(mechanic())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("deaktivace zákazníka → 403")
        void deactivateCustomer() throws Exception {
            mockMvc.perform(delete("/api/v1/customers/{id}", ABSENT_ID).with(user(mechanic())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("deaktivace vozidla → 403")
        void deactivateVehicle() throws Exception {
            mockMvc.perform(delete("/api/v1/vehicles/{id}", ABSENT_ID).with(user(mechanic())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("uzavření inventury → 403")
        void closeStockTake() throws Exception {
            mockMvc.perform(post("/api/v1/warehouse/stock-takes/{id}/close", ABSENT_ID).with(user(mechanic())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("deaktivace zaměstnance → 403 (mechanik nesmí měnit personál ani hodinové sazby)")
        void deactivateEmployee() throws Exception {
            mockMvc.perform(delete("/api/v1/employees/{id}", ABSENT_ID).with(user(mechanic())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("reaktivace zaměstnance → 403")
        void activateEmployee() throws Exception {
            mockMvc.perform(post("/api/v1/employees/{id}/activate", ABSENT_ID).with(user(mechanic())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("reaktivace zákazníka → 403")
        void activateCustomer() throws Exception {
            mockMvc.perform(post("/api/v1/customers/{id}/activate", ABSENT_ID).with(user(mechanic())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("reaktivace vozidla → 403")
        void activateVehicle() throws Exception {
            mockMvc.perform(post("/api/v1/vehicles/{id}/activate", ABSENT_ID).with(user(mechanic())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("pokladní doklady faktury (celý controller) → 403")
        void listCashReceipts() throws Exception {
            mockMvc.perform(get("/api/v1/cash-receipts").param("invoiceId", String.valueOf(ABSENT_ID))
                            .with(user(mechanic())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("detail pokladního dokladu → 403")
        void getCashReceipt() throws Exception {
            mockMvc.perform(get("/api/v1/cash-receipts/{id}", ABSENT_ID).with(user(mechanic())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("PDF pokladního dokladu → 403")
        void cashReceiptPdf() throws Exception {
            mockMvc.perform(get("/api/v1/cash-receipts/{id}/pdf", ABSENT_ID).with(user(mechanic())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("vystavení dobropisu → 403")
        void issueCreditNote() throws Exception {
            mockMvc.perform(post("/api/v1/credit-notes/{id}/issue", ABSENT_ID).with(user(mechanic())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("PDF dobropisu → 403")
        void creditNotePdf() throws Exception {
            mockMvc.perform(get("/api/v1/credit-notes/{id}/pdf", ABSENT_ID).with(user(mechanic())))
                    .andExpect(status().isForbidden());
        }

        /**
         * Profil firmy je fakturační identita servisu — jméno, DIČ a **IBAN na fakturách**.
         * Dosud se spoléhalo na to, že „nese stejnou anotaci" (komentář v této třídě), takže
         * jediné místo, kde by mechanik mohl přesměrovat platby zákazníků, nemělo test
         * (audit KN-21 / bod 6.2). Tělo je **platné** schválně: kdyby bylo vadné, předběhla by
         * 403 validační 400 (metodová autorizace běží po deserializaci těla).
         */
        @Test
        @DisplayName("změna profilu firmy (IBAN na fakturách!) → 403")
        void updateCompanyProfile() throws Exception {
            mockMvc.perform(put("/api/v1/invoices/company-profile")
                            .with(user(mechanic()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"Podvržená firma s.r.o.\",\"countryCode\":\"CZ\","
                                    + "\"iban\":\"CZ0000000000000000000000\","
                                    + "\"invoiceNumberAuto\":true,\"invoiceNumberMask\":\"{RRRR}{MM}{NNN}\","
                                    + "\"cashReceiptNumberSource\":\"MASK\",\"cashReceiptNumberMask\":\"PPD{RRRR}{MM}{NNN}\"}"))
                    .andExpect(status().isForbidden());
        }
    }

    @Nested
    @DisplayName("MECHANIC — baseline provoz → prochází")
    class MechanicAllowed {

        @Test
        @DisplayName("výpis zákazníků → 200 (mechanik není plošně blokovaný)")
        void listCustomers() throws Exception {
            mockMvc.perform(get("/api/v1/customers").with(user(mechanic())))
                    .andExpect(status().isOk());
        }
    }

    @Nested
    @DisplayName("Vedení — autorizací projde (404 na neexistující, ne 403)")
    class ManagementPasses {

        @Test
        @DisplayName("MANAGER vystaví fakturu — brána prošla, 404 z business vrstvy")
        void managerIssueInvoice() throws Exception {
            mockMvc.perform(post("/api/v1/invoices/{id}/issue", ABSENT_ID).with(user(manager()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(ISSUE_BODY))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("ADMIN otevře dobropis — brána prošla, 404 z business vrstvy")
        void adminGetCreditNote() throws Exception {
            mockMvc.perform(get("/api/v1/credit-notes/{id}", ABSENT_ID).with(user(admin())))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("MANAGER změní profil firmy — brána prošla (200), zápis se rollbackne")
        void managerUpdateCompanyProfile() throws Exception {
            mockMvc.perform(put("/api/v1/invoices/company-profile")
                            .with(user(manager()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"Servis vedení s.r.o.\",\"countryCode\":\"CZ\","
                                    + "\"invoiceNumberAuto\":true,\"invoiceNumberMask\":\"{RRRR}{MM}{NNN}\","
                                    + "\"cashReceiptNumberSource\":\"MASK\",\"cashReceiptNumberMask\":\"PPD{RRRR}{MM}{NNN}\"}"))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("MANAGER otevře pokladní doklady faktury — brána prošla (200, prázdný seznam)")
        void managerListCashReceipts() throws Exception {
            mockMvc.perform(get("/api/v1/cash-receipts").param("invoiceId", String.valueOf(ABSENT_ID))
                            .with(user(manager())))
                    .andExpect(status().isOk());
        }
    }
}
