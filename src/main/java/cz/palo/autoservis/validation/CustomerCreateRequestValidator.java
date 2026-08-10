package cz.palo.autoservis.validation;

import cz.palo.autoservis.model.dto.customer.CustomerDto;
import cz.palo.autoservis.model.enums.CustomerType;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * Vyhodnocuje {@link ValidCustomerRequest} nad {@link CustomerDto.CreateRequest}.
 *
 * <p>Pravidla (zrcadlí DB CHECK {@code chk_company_required}):
 * <ul>
 *     <li>{@code customerType == INDIVIDUAL} — {@code firstName} i {@code lastName}
 *         musí být neprázdné; každé prázdné pole dostane vlastní violation
 *         (kód {@code CUSTOMER_NAME_REQUIRED}), aby ho klient mohl zvýraznit.</li>
 *     <li>{@code customerType == COMPANY} — {@code companyName} musí být neprázdné
 *         (kód {@code CUSTOMER_COMPANY_REQUIRED}).</li>
 * </ul>
 *
 * <p>{@code customerType == null} nechává na {@code @NotNull} přímo na poli —
 * validátor v tom případě vrací {@code true}, aby nepřidával druhou,
 * nadbytečnou chybu.
 */
public class CustomerCreateRequestValidator
        implements ConstraintValidator<ValidCustomerRequest, CustomerDto.CreateRequest> {

    static final String CUSTOMER_NAME_REQUIRED = "CUSTOMER_NAME_REQUIRED";
    static final String CUSTOMER_COMPANY_REQUIRED = "CUSTOMER_COMPANY_REQUIRED";

    @Override
    public boolean isValid(CustomerDto.CreateRequest request, ConstraintValidatorContext context) {
        if (request == null || request.getCustomerType() == null) {
            return true;
        }

        return switch (request.getCustomerType()) {
            case INDIVIDUAL -> validateIndividual(request, context);
            case COMPANY -> validateCompany(request, context);
        };
    }

    private boolean validateIndividual(CustomerDto.CreateRequest request, ConstraintValidatorContext context) {
        boolean firstNameBlank = isBlank(request.getFirstName());
        boolean lastNameBlank = isBlank(request.getLastName());
        if (!firstNameBlank && !lastNameBlank) {
            return true;
        }

        context.disableDefaultConstraintViolation();
        if (firstNameBlank) {
            addViolation(context, CUSTOMER_NAME_REQUIRED, "firstName");
        }
        if (lastNameBlank) {
            addViolation(context, CUSTOMER_NAME_REQUIRED, "lastName");
        }
        return false;
    }

    private boolean validateCompany(CustomerDto.CreateRequest request, ConstraintValidatorContext context) {
        if (!isBlank(request.getCompanyName())) {
            return true;
        }

        context.disableDefaultConstraintViolation();
        addViolation(context, CUSTOMER_COMPANY_REQUIRED, "companyName");
        return false;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private void addViolation(ConstraintValidatorContext context, String code, String property) {
        context.buildConstraintViolationWithTemplate(code)
                .addPropertyNode(property)
                .addConstraintViolation();
    }
}
