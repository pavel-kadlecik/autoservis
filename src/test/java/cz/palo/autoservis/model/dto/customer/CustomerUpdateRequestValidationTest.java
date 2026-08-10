package cz.palo.autoservis.model.dto.customer;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bean Validation na {@code CustomerDto.UpdateRequest.addresses} (audit KN-15).
 *
 * <p>Plain unit test bez Spring kontextu — ověřuje <strong>samotnou anotaci</strong>, ne službu.
 * Service testy (`CustomerCrudServiceTest`) volají službu přímo, takže {@code @Valid} vůbec
 * nespustí; rozbitá anotace by jim proto prošla. Tenhle test je to jediné místo, kde se
 * kontrakt DTO skutečně vyhodnotí.
 *
 * <p><strong>Proč zrovna {@code @Size(min = 1)} a ne {@code @NotEmpty}:</strong> pole má tři
 * stavy a každý znamená něco jiného —
 * {@code null} = „adresy neměnit" (TD-42/TD-23), neprázdný seznam = full-replace celé sady,
 * prázdný seznam = chyba. {@code @NotEmpty} odmítá i {@code null}, takže by z adres udělal
 * povinné pole při každé editaci a první stav by zmizel. {@code @Size} {@code null} ignoruje.
 * Přesně tenhle rozdíl testy níže drží.
 */
class CustomerUpdateRequestValidationTest {

    private static final ValidatorFactory FACTORY = Validation.buildDefaultValidatorFactory();
    private static final Validator VALIDATOR = FACTORY.getValidator();

    /** Porušení vázaná přímo na pole {@code addresses} (ne na vnořené adresy jako addresses[0].street). */
    private List<String> addressViolations(CustomerDto.UpdateRequest request) {
        return VALIDATOR.validate(request).stream()
                .filter(v -> "addresses".equals(v.getPropertyPath().toString()))
                .map(jakarta.validation.ConstraintViolation::getMessage)
                .toList();
    }

    @Test
    @DisplayName("addresses = null projde — znamená „adresy neměnit\", ne chybu")
    void nullAddresses_isValid() {
        CustomerDto.UpdateRequest request = new CustomerDto.UpdateRequest();
        request.setAddresses(null);

        assertThat(addressViolations(request))
                .as("null nesmí být validační chyba, jinak by adresy byly povinné při každé editaci")
                .isEmpty();
    }

    @Test
    @DisplayName("addresses = prázdný seznam je odmítnut — jinak by full-replace smazal celou sadu (KN-15)")
    void emptyAddresses_isRejected() {
        CustomerDto.UpdateRequest request = new CustomerDto.UpdateRequest();
        request.setAddresses(List.of());

        assertThat(addressViolations(request)).hasSize(1);
    }

    @Test
    @DisplayName("addresses se třemi záznamy je odmítnut (max 2)")
    void tooManyAddresses_isRejected() {
        CustomerDto.UpdateRequest request = new CustomerDto.UpdateRequest();
        request.setAddresses(List.of(
                new AddressDto.CreateRequest(),
                new AddressDto.CreateRequest(),
                new AddressDto.CreateRequest()));

        assertThat(addressViolations(request)).hasSize(1);
    }
}
