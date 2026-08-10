package cz.palo.autoservis.model.domain.warehouse;

import lombok.*;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Převodník kódů dodavatelů (warehouse.supplier_products):
 * (dodavatel, jeho katalogové číslo) → skladová karta. Samoučící se —
 * potvrzené párování upsertuje review workflow.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SupplierProduct {
    private Long id;
    private Long supplierId;
    private String supplierSku;
    private Long productId;
    private String nameSnapshot;
    private BigDecimal lastUnitPriceExclVat;
    private OffsetDateTime lastSeenAt;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
