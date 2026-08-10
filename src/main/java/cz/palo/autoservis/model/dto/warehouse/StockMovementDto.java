package cz.palo.autoservis.model.dto.warehouse;

import cz.palo.autoservis.model.domain.warehouse.MovementType;
import cz.palo.autoservis.model.domain.warehouse.ReturnReason;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * DTO namespace pro ruční skladové pohyby (E2.1, P-1).
 *
 * <p>Ruční pohyb je vždy <b>záporný</b>: korekce dolů (manko, rozbití), odpis,
 * vratka dodavateli nebo interní spotřeba mimo zakázku. Kladný přebytek se řeší ruční
 * příjemkou (rozhodnutí R-E), ne tímto endpointem — proto jsou povoleny jen
 * {@code ADJUSTMENT}, {@code WRITE_OFF}, {@code RETURN} a {@code ISSUE} (bez zakázky).
 */
public class StockMovementDto {

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class CreateRequest {

        /**
         * Jen ADJUSTMENT (korekce), WRITE_OFF (odpis), RETURN (vratka) nebo ISSUE
         * (interní spotřeba bez zakázky) — ostatní odmítne validace (400).
         */
        @NotNull(message = "Typ pohybu je povinný")
        private MovementType movementType;

        /** Důvod vratky — povinný právě a jen u RETURN (zrcadlí DB CHECK chk_return_reason). */
        private ReturnReason returnReason;

        /** Číslo přijatého dobropisu — volitelné, jen u vratky. */
        @Size(max = 50, message = "Číslo dobropisu může mít nejvýše 50 znaků")
        private String creditNoteNumber;

        /** Pohyb jde vždy proti konkrétní šarži, ať trigger sníží i zůstatek šarže. */
        @NotNull(message = "Šarže je povinná")
        private Long batchId;

        /** Kladné množství; service ho znegatuje (pohyb je vždy záporný). */
        @NotNull(message = "Množství je povinné")
        @Positive(message = "Množství musí být kladné")
        private BigDecimal quantity;

        /** Ruční pohyb musí být zdůvodněn — poznámka je povinná. */
        @NotBlank(message = "Poznámka je povinná")
        @Size(min = 3, max = 500, message = "Poznámka musí mít 3–500 znaků")
        private String note;

        @AssertTrue(message = "Povolený typ ručního pohybu je jen korekce (ADJUSTMENT), "
                + "odpis (WRITE_OFF), vratka dodavateli (RETURN) nebo spotřeba (ISSUE)")
        public boolean isManualMovementType() {
            // null řeší @NotNull výše — tady ho pustíme, ať nevzniknou dvě chyby
            return movementType == null
                    || movementType == MovementType.ADJUSTMENT
                    || movementType == MovementType.WRITE_OFF
                    || movementType == MovementType.RETURN
                    || movementType == MovementType.ISSUE;
        }

        /**
         * Zrcadlí DB CHECK {@code chk_return_reason}: důvod patří právě a jen k vratce.
         * Chybějící důvod u vratky i důvod u korekce/odpisu je chyba vstupu (400).
         */
        @AssertTrue(message = "Důvod vratky vyplňte právě u vratky dodavateli — jinde nemá smysl")
        public boolean isReturnReasonConsistent() {
            if (movementType == MovementType.RETURN) {
                return returnReason != null;
            }
            return returnReason == null;
        }

        /** Číslo dobropisu se váže k vratce; u korekce ani odpisu žádný dobropis není. */
        @AssertTrue(message = "Číslo dobropisu lze uvést jen u vratky dodavateli")
        public boolean isCreditNoteOnlyForReturn() {
            return movementType == MovementType.RETURN
                    || creditNoteNumber == null || creditNoteNumber.isBlank();
        }
    }
}
