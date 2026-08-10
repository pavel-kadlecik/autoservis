package cz.palo.autoservis.service.impl;

import cz.palo.autoservis.exception.BusinessRuleException;
import cz.palo.autoservis.exception.ResourceNotFoundException;
import cz.palo.autoservis.mapper.CompanyProfileMapper;
import cz.palo.autoservis.model.converter.CompanyProfileConverter;
import cz.palo.autoservis.model.domain.billing.CompanyProfile;
import cz.palo.autoservis.model.dto.billing.CompanyProfileDto;
import cz.palo.autoservis.service.CompanyProfileService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implementace {@link CompanyProfileService}.
 */
@Service
@RequiredArgsConstructor
public class CompanyProfileServiceImpl implements CompanyProfileService {

    private final CompanyProfileMapper companyProfileMapper;
    private final CompanyProfileConverter companyProfileConverter;

    /** {@inheritDoc} */
    @Override
    public CompanyProfileDto.Response get() {
        return companyProfileConverter.toResponse(load());
    }

    /**
     * {@inheritDoc}
     *
     * @throws ResourceNotFoundException if the profile row is missing
     */
    @Override
    @Transactional
    public CompanyProfileDto.Response update(CompanyProfileDto.UpdateRequest request) {
        requireValidMask(request.getInvoiceNumberMask(),
                "INVALID_INVOICE_NUMBER_MASK", "invoiceNumberMask");
        requireValidMask(request.getCashReceiptNumberMask(),
                "INVALID_CASH_RECEIPT_NUMBER_MASK", "cashReceiptNumberMask");
        CompanyProfile updated = companyProfileConverter.applyUpdate(load(), request);
        companyProfileMapper.update(updated);
        return companyProfileConverter.toResponse(load());
    }

    private CompanyProfile load() {
        return companyProfileMapper.find()
                .orElseThrow(() -> new ResourceNotFoundException("Profil firmy", 1L));
    }

    /**
     * Obsahová validace masky číselné řady (tokeny, právě jedna sekvence, délka) —
     * společná pro faktury (V71) i pokladní doklady (V92). Validuje se i při vypnutém
     * automatickém číslování — v DB je maska NOT NULL a po pozdějším zapnutí přepínače
     * musí být okamžitě použitelná.
     */
    private void requireValidMask(String mask, String errorCode, String field) {
        try {
            DocumentNumberMask.parse(mask);
        } catch (IllegalArgumentException e) {
            throw new BusinessRuleException(
                    errorCode, field,
                    e.getMessage(),
                    java.util.Map.of("mask", String.valueOf(mask)));
        }
    }
}
