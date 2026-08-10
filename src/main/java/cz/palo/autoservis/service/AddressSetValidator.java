package cz.palo.autoservis.service;

import cz.palo.autoservis.exception.BusinessRuleException;
import cz.palo.autoservis.model.dto.customer.AddressDto;
import cz.palo.autoservis.model.enums.AddressType;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class AddressSetValidator {

    /**
     * Validuje sadu adres zákazníka.
     * <ul>
     *     <li>{@code null} znamená „stávající sadu nechat být" (UpdateRequest, TD-42) — povoleno.</li>
     *     <li><strong>Prázdný</strong> seznam se odmítá — viz níže.</li>
     *     <li>Fakturační adresa musí být právě jedna.</li>
     *     <li>Kontaktní adresa smí být nejvýše jedna.</li>
     * </ul>
     *
     * <p><strong>Prázdný seznam (audit KN-15).</strong> Dřív propadal stejným early
     * returnem jako {@code null} s komentářem „Handled by @NotEmpty" — pravda pro
     * {@code CreateRequest}, ale {@code UpdateRequest} takovou anotaci nemá, takže prázdný
     * seznam došel do {@code CustomerServiceImpl.update}, prošel validací a přes
     * {@code deleteByCustomerId} smazal celou sadu adres, aniž by se cokoli vložilo zpět.
     * Zákazníka pak vůbec nešlo fakturovat. {@code UpdateRequest} teď nese
     * {@code @Size(min = 1)} a tahle pojistka je druhá linie těsně před mazáním.
     *
     * @param addresses seznam adres k validaci; {@code null} = změna se nepožaduje
     * @throws BusinessRuleException když je sada prázdná nebo porušuje pravidla typů adres
     */
    public void validate(List<AddressDto.CreateRequest> addresses) {
        if (addresses == null) {
            return;   // „neměnit stávající sadu" — volající adresy vůbec nepřepisuje
        }

        if (addresses.isEmpty()) {
            throw new BusinessRuleException(
                    "EMPTY_ADDRESS_SET", "addresses",
                    "Zákazníkovi nelze smazat všechny adresy — musí mu zůstat právě jedna fakturační.",
                    Map.of());
        }

        long billingCount = addresses.stream().filter(a -> a.getAddressType() == AddressType.BILLING).count();
        if (billingCount != 1) {
            throw new BusinessRuleException(
                    "INVALID_BILLING_ADDRESS_COUNT", "addresses",
                    "Zákazník musí mít právě jednu fakturační adresu.",
                    Map.of("billingCount", billingCount));
        }

        long contactCount = addresses.stream().filter(a -> a.getAddressType() == AddressType.CONTACT).count();
        if (contactCount > 1) {
            throw new BusinessRuleException(
                    "INVALID_CONTACT_ADDRESS_COUNT", "addresses",
                    "Zákazník smí mít nejvýše jednu kontaktní adresu.",
                    Map.of("contactCount", contactCount));
        }
    }


}
