package cz.palo.autoservis.service;

import org.springframework.stereotype.Component;

@Component
public class SupplierNormalizer {

    public String normalizeRegistrationNumber(String raw){
        if (raw == null) {
            return null;
        }
        String normalized = raw.replaceAll("[\\s\\u00A0]", "");
        return normalized.isEmpty() ? null : normalized;
    }
}
