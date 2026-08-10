package cz.palo.autoservis.model.draft;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Hodnota pole draftu spolu s jejím stavem (původem/důvěryhodností).
 * Serializuje se do JSONB jako {@code {"value": ..., "state": "..."}}.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TrackedField<T> {

    private T value;
    private FieldState state;

    public static <T> TrackedField<T> of(T value, FieldState state) {
        return new TrackedField<>(value, state);
    }

    /** null hodnota ⇒ ABSENT, jinak zadaný stav. */
    public static <T> TrackedField<T> ofNullable(T value, FieldState stateWhenPresent) {
        return value == null ? absent() : of(value, stateWhenPresent);
    }

    public static <T> TrackedField<T> absent() {
        return new TrackedField<>(null, FieldState.ABSENT);
    }

    public static <T> TrackedField<T> defaulted(T defaultValue) {
        return new TrackedField<>(defaultValue, FieldState.DEFAULTED);
    }

    /** Povýší na VERIFIED, jen pokud pole nese přečtenou/dopočtenou hodnotu. */
    public void verify() {
        if (state == FieldState.VERBATIM || state == FieldState.DERIVED) {
            state = FieldState.VERIFIED;
        }
    }
}
