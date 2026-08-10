package cz.palo.autoservis.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Třídní constraint na {@link cz.palo.autoservis.model.dto.customer.CustomerDto.CreateRequest}
 * vynucující vyplnění identifikačních polí podle typu zákazníka:
 * {@code INDIVIDUAL} vyžaduje {@code firstName}/{@code lastName}, {@code COMPANY}
 * vyžaduje {@code companyName}.
 *
 * <p>Stejný problém dnes chytá i DB CHECK constraint {@code chk_company_required},
 * ale až jako 422 {@code DATA_INTEGRITY_VIOLATION} — pro klienta pozdě a nekonkrétně.
 * Tato anotace přesouvá kontrolu do Bean Validation (400).
 *
 * <p><strong>Šablona zprávy = chybový kód, ne text.</strong> {@link
 * cz.palo.autoservis.exception.GlobalExceptionHandler#CUSTOM_VALIDATOR_ANNOTATIONS}
 * bere {@code FieldError.getDefaultMessage()} jako strojový kód a lidský text
 * dohledává přes {@code MessageSource} ({@code messages.properties}).
 *
 * @see CustomerCreateRequestValidator
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Constraint(validatedBy = CustomerCreateRequestValidator.class)
public @interface ValidCustomerRequest {

    /** Výchozí zpráva — nikdy se reálně nezobrazí; violations si vždy přidávají vlastní šablonu na úrovni pole. */
    String message() default "Invalid customer request";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
