package cz.palo.autoservis.service;

import cz.palo.autoservis.AbstractIntegrationTest;
import cz.palo.autoservis.exception.ResourceNotFoundException;
import cz.palo.autoservis.model.dto.billing.CompanyProfileDto;
import cz.palo.autoservis.model.enums.CashReceiptNumberSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Profil vlastní firmy ({@code CompanyProfileServiceImpl}) — jediný řádek v DB, který putuje
 * na každou vystavenou fakturu jako dodavatel.
 *
 * <p>Ověřuje se hlavně to, že {@code update} skutečně <strong>zapisuje do DB</strong> a vrací
 * znovu načtený stav (R-03 verify-and-fetch), ne jen odražený vstup — jinak by se změna
 * v odpovědi tvářila jako uložená, ale faktura by nesla stará data.
 */
@Transactional
class CompanyProfileServiceTest extends AbstractIntegrationTest {

    @Autowired
    private CompanyProfileService companyProfileService;

    /**
     * Čtení mimo MyBatis. Uvnitř jedné transakce vrací {@code companyProfileMapper.find()}
     * díky lokální cache SqlSession <strong>tentýž objekt</strong>, který service právě
     * zmutovala — kontrola „načti znovu přes service" by proto prošla, i kdyby se do DB
     * vůbec nezapsalo. Mutační test to odhalil (přeživší mutant „removed call to
     * CompanyProfileMapper::update"), proto se ověřuje přímým dotazem.
     */
    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("get vrátí existující profil firmy ze seedu")
    void get_returnsSeededProfile() {
        CompanyProfileDto.Response profile = companyProfileService.get();

        assertThat(profile).isNotNull();
        assertThat(profile.getId()).isNotNull();
        assertThat(profile.getName()).isNotBlank();
    }

    @Test
    @DisplayName("update uloží změny a vrátí je znovu načtené z DB")
    void update_persistsChangesAndReturnsFreshState() {
        CompanyProfileDto.UpdateRequest request = fullRequest();

        CompanyProfileDto.Response updated = companyProfileService.update(request);

        assertThat(updated.getName()).isEqualTo("Autoservis Testovací s.r.o.");
        assertThat(updated.getIco()).isEqualTo("12345678");
        assertThat(updated.getDic()).isEqualTo("CZ12345678");
        assertThat(updated.getStreet()).isEqualTo("Dílenská");
        assertThat(updated.getStreetNumber()).isEqualTo("12");
        assertThat(updated.getCity()).isEqualTo("Praha");
        assertThat(updated.getPostalCode()).isEqualTo("110 00");
        assertThat(updated.getCountryCode()).isEqualTo("CZ");
        assertThat(updated.getBankAccount()).isEqualTo("123456789/0800");
        assertThat(updated.getIban()).isEqualTo("CZ6508000000192000145399");
        assertThat(updated.getSwift()).isEqualTo("GIBACZPX");

        // Skutečně nezávislé čtení přímo z tabulky — dokazuje zápis do DB.
        assertThat(readColumnFromDb("name")).isEqualTo("Autoservis Testovací s.r.o.");
        assertThat(readColumnFromDb("iban")).isEqualTo("CZ6508000000192000145399");
        assertThat(readColumnFromDb("city")).isEqualTo("Praha");
    }

    @Test
    @DisplayName("update zachová id profilu — jde o singleton řádek, ne o vkládání nového")
    void update_keepsSingletonId() {
        Integer idBefore = companyProfileService.get().getId();

        CompanyProfileDto.Response updated = companyProfileService.update(fullRequest());

        assertThat(updated.getId()).isEqualTo(idBefore);
    }

    @Test
    @DisplayName("update umí vymazat bankovní spojení (pak se na fakturu negeneruje QR platba)")
    void update_canClearBankDetails() {
        companyProfileService.update(fullRequest());

        CompanyProfileDto.UpdateRequest withoutBank = fullRequest();
        withoutBank.setIban(null);
        withoutBank.setBankAccount(null);
        withoutBank.setSwift(null);

        CompanyProfileDto.Response updated = companyProfileService.update(withoutBank);

        assertThat(updated.getIban()).isNull();
        assertThat(updated.getBankAccount()).isNull();
        assertThat(updated.getSwift()).isNull();
        assertThat(readColumnFromDb("iban")).isNull();
        assertThat(readColumnFromDb("bank_account")).isNull();
    }

    @Test
    @DisplayName("dva updaty po sobě se neruší — platí poslední zapsaná hodnota")
    void update_twiceInARow_lastWriteWins() {
        CompanyProfileDto.UpdateRequest first = fullRequest();
        first.setCity("Brno");
        companyProfileService.update(first);

        CompanyProfileDto.UpdateRequest second = fullRequest();
        second.setCity("Ostrava");
        companyProfileService.update(second);

        assertThat(readColumnFromDb("city")).isEqualTo("Ostrava");
    }

    @Test
    @DisplayName("update uloží nastavení číslování — přepínač i masku (V71)")
    void update_persistsInvoiceNumberingSettings() {
        CompanyProfileDto.UpdateRequest request = fullRequest();
        request.setInvoiceNumberAuto(false);
        request.setInvoiceNumberMask("{N}/{RR}");

        CompanyProfileDto.Response updated = companyProfileService.update(request);

        assertThat(updated.getInvoiceNumberAuto()).isFalse();
        assertThat(updated.getInvoiceNumberMask()).isEqualTo("{N}/{RR}");
        assertThat(readColumnFromDb("invoice_number_mask")).isEqualTo("{N}/{RR}");
    }

    @Test
    @DisplayName("nevalidní maska se odmítne s kódem INVALID_INVOICE_NUMBER_MASK (V71)")
    void update_rejectsInvalidMask() {
        CompanyProfileDto.UpdateRequest request = fullRequest();
        request.setInvoiceNumberMask("{RRRR}{MM}"); // chybí sekvenční token

        assertThatThrownBy(() -> companyProfileService.update(request))
                .isInstanceOf(cz.palo.autoservis.exception.BusinessRuleException.class)
                .satisfies(ex -> assertThat(
                        ((cz.palo.autoservis.exception.BusinessRuleException) ex).getRuleCode())
                        .isEqualTo("INVALID_INVOICE_NUMBER_MASK"));
    }

    @Test
    @DisplayName("chybí-li řádek profilu, get i update hlásí 404 místo NullPointerException")
    void missingProfileRow_yieldsResourceNotFound() {
        // Řádek garantuje migrace, takže tenhle stav v provozu nenastane — ale chybová
        // cesta se má chovat definovaně. Smazání běží uvnitř testovací transakce,
        // která se na konci odroluje, takže se seed nepoškodí.
        jdbcTemplate.update("DELETE FROM billing.company_profile");

        assertThatThrownBy(() -> companyProfileService.get())
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> companyProfileService.update(fullRequest()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    /** Přímý dotaz do tabulky, mimo MyBatis cache — viz komentář u {@code jdbcTemplate}. */
    private String readColumnFromDb(String column) {
        return jdbcTemplate.queryForObject(
                "SELECT " + column + " FROM billing.company_profile LIMIT 1", String.class);
    }

    private static CompanyProfileDto.UpdateRequest fullRequest() {
        CompanyProfileDto.UpdateRequest request = new CompanyProfileDto.UpdateRequest();
        request.setName("Autoservis Testovací s.r.o.");
        request.setIco("12345678");
        request.setDic("CZ12345678");
        request.setStreet("Dílenská");
        request.setStreetNumber("12");
        request.setCity("Praha");
        request.setPostalCode("110 00");
        request.setCountryCode("CZ");
        request.setBankAccount("123456789/0800");
        request.setIban("CZ6508000000192000145399");
        request.setSwift("GIBACZPX");
        request.setInvoiceNumberAuto(true);
        request.setInvoiceNumberMask("{RRRR}{MM}{NNN}");
        request.setCashReceiptNumberSource(CashReceiptNumberSource.MASK);
        request.setCashReceiptNumberMask("PPD{RRRR}{MM}{NNN}");
        return request;
    }

    @Test
    @DisplayName("update uloží nastavení číslování pokladních dokladů (V92)")
    void update_persistsCashReceiptNumberingSettings() {
        CompanyProfileDto.UpdateRequest request = fullRequest();
        request.setCashReceiptNumberSource(CashReceiptNumberSource.MANUAL);
        request.setCashReceiptNumberMask("P{N}/{RR}");
        request.setCashReceiptGapCheckEnabled(true);
        request.setCashReceiptGapCheckFrom("P5/26");

        CompanyProfileDto.Response updated = companyProfileService.update(request);

        assertThat(updated.getCashReceiptNumberSource()).isEqualTo(CashReceiptNumberSource.MANUAL);
        assertThat(updated.getCashReceiptNumberMask()).isEqualTo("P{N}/{RR}");
        assertThat(updated.getCashReceiptGapCheckEnabled()).isTrue();
        assertThat(updated.getCashReceiptGapCheckFrom()).isEqualTo("P5/26");
        assertThat(readColumnFromDb("cash_receipt_number_mask")).isEqualTo("P{N}/{RR}");
    }

    @Test
    @DisplayName("nevalidní maska řady PPD se odmítne s kódem INVALID_CASH_RECEIPT_NUMBER_MASK (V92)")
    void update_rejectsInvalidCashReceiptMask() {
        CompanyProfileDto.UpdateRequest request = fullRequest();
        request.setCashReceiptNumberMask("PPD{RRRR}{MM}"); // chybí sekvenční token

        assertThatThrownBy(() -> companyProfileService.update(request))
                .isInstanceOf(cz.palo.autoservis.exception.BusinessRuleException.class)
                .satisfies(ex -> assertThat(
                        ((cz.palo.autoservis.exception.BusinessRuleException) ex).getRuleCode())
                        .isEqualTo("INVALID_CASH_RECEIPT_NUMBER_MASK"));
    }
}
