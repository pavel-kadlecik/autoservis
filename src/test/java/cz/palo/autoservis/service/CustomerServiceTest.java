package cz.palo.autoservis.service;

import cz.palo.autoservis.AbstractIntegrationTest;
import cz.palo.autoservis.exception.ResourceNotFoundException;
import cz.palo.autoservis.model.dto.customer.CustomerDto;
import cz.palo.autoservis.model.dto.customer.CustomerSearchParams;
import cz.palo.autoservis.model.dto.pagination.PagedResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// ① AbstractIntegrationTest = @SpringBootTest + sdílený Testcontainers PostgreSQL
//    (singleton container pattern — jeden kontejner a jeden Spring context pro
//    celý běh testů, viz javadoc bázové třídy)
//
// ② @Transactional = každý test běží v transakci,
//    která se NA KONCI AUTOMATICKY ODROLUJE.
//    DB zůstává pro další test čistá — data není třeba ručně mazat.
@Transactional
class CustomerServiceTest extends AbstractIntegrationTest {

    // ③ Service se autowiruje úplně normálně — Spring context je plně funkční.
    @Autowired
    private CustomerService customerService;


    // -----------------------------------------------------------------------
    //  Testy metody getById()
    //  @Nested třídy seskupují testy podle scénáře.
    //  Výsledek pak v IDE vypadá jako stromová struktura.
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("getById()")
    class GetById {

        // Seed data z V3__seed_initial_data.sql obsahují zákazníky s ID 1, 2, 3.
        // Flyway je při startu kontejneru aplikuje automaticky → můžeme je použít.

        @Test
        @DisplayName("existing ID → returns customer with correct data")
        void existingId_returnsCustomerWithCorrectData() {
            // ── GIVEN ──────────────────────────────────────────────────────
            // ID 1 existuje díky seed datům (migrace V3).
            // Víme přesně, jaká data tam jsou → můžeme assertovat konkrétní hodnoty.
            Long existingId = 1L;

            // ── WHEN ───────────────────────────────────────────────────────
            CustomerDto.DetailResponse result = customerService.getById(existingId);

            // ── THEN ───────────────────────────────────────────────────────
            // AssertJ: fluent asserty — čitelnější než JUnit assertEquals
            assertThat(result).isNotNull();
            assertThat(result.getId()).isEqualTo(existingId);

            // Ověření konkrétních business dat ze seed souboru:
             assertThat(result.getFirstName()).isEqualTo("Jan");
             assertThat(result.getLastName()).isEqualTo("Novák");
        }

        @Test
        @DisplayName("non-existent ID → throws ResourceNotFoundException")
        void nonExistentId_throwsResourceNotFoundException() {
            // ── GIVEN ──────────────────────────────────────────────────────
            Long nonExistentId = 999_999L;

            // ── WHEN & THEN ────────────────────────────────────────────────
            // assertThatThrownBy: ověří, že blok kódu vyhodí danou výjimku.
            // Alternativa: assertThatExceptionOfType(...).isThrownBy(...)
            assertThatThrownBy(() -> customerService.getById(nonExistentId))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining(String.valueOf(nonExistentId));
        }

        @Test
        @DisplayName("ID = null → throws IllegalArgumentException")
        void nullId_throwsIllegalArgumentException() {
            assertThatThrownBy(() -> customerService.getById(null))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // -----------------------------------------------------------------------
    //  Testy víceslovného hledání v getPage() (TD-18) — zákazník 1 ze seed dat
    //  V3 je "Jan Novák" (first_name/last_name).
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("getPage() — víceslovné hledání (TD-18)")
    class GetPageSearch {

        private PagedResponse<CustomerDto.ListResponse> search(String query) {
            CustomerSearchParams params = new CustomerSearchParams();
            params.setSearch(query);
            params.setPageSize(50);
            return customerService.getPage(params);
        }

        @Test
        @DisplayName("\"Jan Novák\" → najde zákazníka Jan Novák (jméno + příjmení napříč sloupci)")
        void twoWordQuery_findsCustomerAcrossColumns() {
            PagedResponse<CustomerDto.ListResponse> result = search("Jan Novák");

            assertThat(result.getContent())
                    .extracting(CustomerDto.ListResponse::getId)
                    .contains(1L);
        }

        @Test
        @DisplayName("\"novak jan\" (přehozené pořadí, bez diakritiky) → také najde Jan Novák")
        void reversedWordOrderWithoutDiacritics_findsCustomer() {
            PagedResponse<CustomerDto.ListResponse> result = search("novak jan");

            assertThat(result.getContent())
                    .extracting(CustomerDto.ListResponse::getId)
                    .contains(1L);
        }

        @Test
        @DisplayName("\"Novák Neexistujici\" → nenajde nic (druhé slovo nemá shodu v žádném sloupci)")
        void secondWordWithoutMatch_findsNothing() {
            PagedResponse<CustomerDto.ListResponse> result = search("Novák Neexistujici");

            assertThat(result.getContent()).isEmpty();
        }

        @Test
        @DisplayName("jednoslovné \"Novák\" → funguje jako dřív (najde zákazníka i firmu s tímto slovem)")
        void singleWordQuery_worksAsBefore() {
            PagedResponse<CustomerDto.ListResponse> result = search("Novák");

            assertThat(result.getContent())
                    .extracting(CustomerDto.ListResponse::getId)
                    .contains(1L);
        }
    }
}
