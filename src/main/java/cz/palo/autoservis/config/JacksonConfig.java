package cz.palo.autoservis.config;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.type.LogicalType;

/**
 * Globální ladění JSON deserializace příchozích requestů.
 *
 * @see cz.palo.autoservis.model.converter konvertory aplikující stejné pravidlo na obyčejné stringy
 */
@Configuration
public class JacksonConfig {

    /**
     * Prázdný řetězec bere jako {@code null} pro každé enum pole.
     * <p>
     * HTML formuláře posílají nevyplněný {@code <select>} jako {@code ""}, nikdy jako JSON
     * {@code null}. Pro čísla a datumy Jackson převádí {@code ""} na {@code null} sám, ale
     * u enumů to standardně odmítá a celý request spadne už při deserializaci
     * s {@code HttpMessageNotReadableException} — dřív, než vůbec doběhne Bean Validation,
     * takže odebrání {@code @NotNull} z pole nepomůže.
     * <p>
     * Pravidlo je registrované globálně, ne po polích: stejný problém má každý nepovinný
     * enum v API (palivo vozidla, převodovka, …) a jedno místo je lepší než roztroušené
     * vlastní deserializéry po DTO. Povinných enumů se to nedotkne — jejich {@code @NotNull}
     * odmítne výsledný {@code null} řádnou 400 a čitelnou zprávou místo chyby parsování.
     */
    @Bean
    JsonMapperBuilderCustomizer emptyStringAsNullEnumCustomizer() {
        return builder -> builder.withCoercionConfig(
                LogicalType.Enum,
                config -> config.setCoercion(CoercionInputShape.EmptyString, CoercionAction.AsNull));
    }
}
