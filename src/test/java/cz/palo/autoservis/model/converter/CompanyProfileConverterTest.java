package cz.palo.autoservis.model.converter;

import cz.palo.autoservis.model.domain.billing.CompanyProfile;
import cz.palo.autoservis.model.dto.billing.CompanyProfileDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Konvertor profilu vlastní firmy — čistý unit test bez Spring kontextu.
 *
 * <p>Profil je jediný řádek v DB a putuje na <strong>každou vystavenou fakturu</strong> jako
 * dodavatel. Špatně přenesený IBAN znamená QR platbu na cizí účet, proto se tvrdí každé pole.
 */
class CompanyProfileConverterTest {

    private final CompanyProfileConverter converter = new CompanyProfileConverter();

    @Test
    @DisplayName("toResponse přenese všechna pole včetně bankovního spojení")
    void toResponse_mapsAllFields() {
        CompanyProfile profile = profile();
        profile.setId(1);

        CompanyProfileDto.Response response = converter.toResponse(profile);

        assertThat(response.getId()).isEqualTo(1);
        assertThat(response.getName()).isEqualTo("Autoservis s.r.o.");
        assertThat(response.getIco()).isEqualTo("12345678");
        assertThat(response.getDic()).isEqualTo("CZ12345678");
        assertThat(response.getStreet()).isEqualTo("Dílenská");
        assertThat(response.getStreetNumber()).isEqualTo("12");
        assertThat(response.getCity()).isEqualTo("Praha");
        assertThat(response.getPostalCode()).isEqualTo("110 00");
        assertThat(response.getCountryCode()).isEqualTo("CZ");
        assertThat(response.getBankAccount()).isEqualTo("123456789/0800");
        assertThat(response.getIban()).isEqualTo("CZ6508000000192000145399");
        assertThat(response.getSwift()).isEqualTo("GIBACZPX");
        assertThat(response.getInvoiceNumberAuto()).isTrue();
        assertThat(response.getInvoiceNumberMask()).isEqualTo("{RRRR}{MM}{NNN}");
    }

    @Test
    @DisplayName("toResponse(null) → null")
    void toResponse_null_returnsNull() {
        assertThat(converter.toResponse(null)).isNull();
    }

    @Test
    @DisplayName("applyUpdate přepíše všechna editovatelná pole, id nechá být")
    void applyUpdate_overwritesEditableFields() {
        CompanyProfile existing = profile();
        existing.setId(1);

        CompanyProfileDto.UpdateRequest request = new CompanyProfileDto.UpdateRequest();
        request.setName("Autoservis a.s.");
        request.setIco("87654321");
        request.setDic("CZ87654321");
        request.setStreet("Nová");
        request.setStreetNumber("3a");
        request.setCity("Brno");
        request.setPostalCode("602 00");
        request.setCountryCode("SK");
        request.setBankAccount("987654321/0300");
        request.setIban("CZ0203000000000123456789");
        request.setSwift("CEKOCZPP");
        request.setInvoiceNumberAuto(false);
        request.setInvoiceNumberMask("{N}/{RR}");

        CompanyProfile result = converter.applyUpdate(existing, request);

        assertThat(result).as("mutace probíhá na místě, vrací se tentýž objekt").isSameAs(existing);
        assertThat(existing.getName()).isEqualTo("Autoservis a.s.");
        assertThat(existing.getIco()).isEqualTo("87654321");
        assertThat(existing.getDic()).isEqualTo("CZ87654321");
        assertThat(existing.getStreet()).isEqualTo("Nová");
        assertThat(existing.getStreetNumber()).isEqualTo("3a");
        assertThat(existing.getCity()).isEqualTo("Brno");
        assertThat(existing.getPostalCode()).isEqualTo("602 00");
        assertThat(existing.getCountryCode()).isEqualTo("SK");
        assertThat(existing.getBankAccount()).isEqualTo("987654321/0300");
        assertThat(existing.getIban()).isEqualTo("CZ0203000000000123456789");
        assertThat(existing.getSwift()).isEqualTo("CEKOCZPP");
        assertThat(existing.getInvoiceNumberAuto()).isFalse();
        assertThat(existing.getInvoiceNumberMask()).isEqualTo("{N}/{RR}");

        assertThat(existing.getId()).isEqualTo(1);
    }

    @Test
    @DisplayName("applyUpdate: vynulované bankovní spojení se propíše (QR platba pak nevznikne)")
    void applyUpdate_clearedBankDetails_areApplied() {
        CompanyProfile existing = profile();

        CompanyProfileDto.UpdateRequest request = new CompanyProfileDto.UpdateRequest();
        request.setName("Autoservis s.r.o.");
        request.setIban(null);
        request.setBankAccount(null);

        converter.applyUpdate(existing, request);

        assertThat(existing.getIban()).isNull();
        assertThat(existing.getBankAccount()).isNull();
    }

    @Test
    @DisplayName("applyUpdate vrací null, chybí-li kterýkoli z argumentů")
    void applyUpdate_nullArguments_returnNull() {
        assertThat(converter.applyUpdate(null, new CompanyProfileDto.UpdateRequest())).isNull();
        assertThat(converter.applyUpdate(profile(), null)).isNull();
    }

    private static CompanyProfile profile() {
        CompanyProfile profile = new CompanyProfile();
        profile.setName("Autoservis s.r.o.");
        profile.setIco("12345678");
        profile.setDic("CZ12345678");
        profile.setStreet("Dílenská");
        profile.setStreetNumber("12");
        profile.setCity("Praha");
        profile.setPostalCode("110 00");
        profile.setCountryCode("CZ");
        profile.setBankAccount("123456789/0800");
        profile.setIban("CZ6508000000192000145399");
        profile.setSwift("GIBACZPX");
        profile.setInvoiceNumberAuto(true);
        profile.setInvoiceNumberMask("{RRRR}{MM}{NNN}");
        return profile;
    }
}
