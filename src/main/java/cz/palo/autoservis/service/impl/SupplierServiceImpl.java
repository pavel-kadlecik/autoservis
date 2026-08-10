package cz.palo.autoservis.service.impl;

import cz.palo.autoservis.exception.BusinessRuleException;
import cz.palo.autoservis.exception.ResourceNotFoundException;
import cz.palo.autoservis.mapper.SupplierMapper;
import cz.palo.autoservis.model.converter.SupplierConverter;
import cz.palo.autoservis.model.domain.warehouse.Supplier;
import cz.palo.autoservis.model.dto.pagination.PagedResponse;
import cz.palo.autoservis.model.dto.warehouse.SupplierDto;
import cz.palo.autoservis.model.dto.warehouse.SupplierSearchParams;
import cz.palo.autoservis.service.SupplierNormalizer;
import cz.palo.autoservis.service.SupplierService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class SupplierServiceImpl implements SupplierService {

    private final SupplierMapper supplierMapper;
    private final SupplierConverter supplierConverter;
    private final SupplierNormalizer supplierNormalizer;

    @Override
    public PagedResponse<SupplierDto.ListResponse> getPage(SupplierSearchParams params) {
        List<Supplier> suppliers = supplierMapper.search(params);
        List<SupplierDto.ListResponse> listResponses = supplierConverter.toListResponse(suppliers);
        long total = supplierMapper.countSearch(params);

        return  PagedResponse.of(listResponses, params.getPage(), params.getPageSize(), total);

    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException  když je {@code id} null
     * @throws ResourceNotFoundException když dodavatel s daným ID neexistuje
     */
    @Override
    public SupplierDto.DetailResponse getById(Long id) {
        if (id == null) {
            throw new IllegalArgumentException("id nesmí být null");
        }
        return supplierMapper.findById(id)
                .map(supplierConverter::toDetailResponse)
                .orElseThrow(() -> new ResourceNotFoundException("Dodavatel", id));
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException  když je {@code id} null
     * @throws ResourceNotFoundException když dodavatel s daným ID neexistuje
     * @throws BusinessRuleException     když nové IČO už používá jiný dodavatel
     */
    @Override
    @Transactional
    public SupplierDto.DetailResponse update(Long id, SupplierDto.UpdateRequest updateRequest) {
        if (id == null) {
            throw new IllegalArgumentException("id nesmí být null");
        }

        Supplier existingSupplier = supplierMapper.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Dodavatel", id));

        // normalizace IČO
        String normalizedRn = supplierNormalizer.normalizeRegistrationNumber(updateRequest.getRegistrationNumber());
        updateRequest.setRegistrationNumber(normalizedRn);


        if(updateRequest.getRegistrationNumber() != null && supplierMapper.existsByRegistrationNumber(id, updateRequest.getRegistrationNumber())) {
            throw new BusinessRuleException(
                    "DUPLICATE_REGISTRATION_NUMBER",
                    "registrationNumber",
                    "Dodavatel s IČO " + updateRequest.getRegistrationNumber() + " už existuje.",
                    Map.of("registrationNumber", updateRequest.getRegistrationNumber()));
        }

        Supplier supplier = supplierConverter.applyUpdate(existingSupplier, updateRequest);

        int affectedRows = supplierMapper.update(supplier);
        if (affectedRows == 0) {
            throw new IllegalStateException("Dodavatel " + id + " zmizel během aktualizace");
        }

        return supplierMapper.findById(id)
                .map(supplierConverter::toDetailResponse)
                .orElseThrow(() -> new IllegalStateException("Dodavatel " + id + " zmizel mezi UPDATE a SELECT"));

    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException  když je {@code id} null
     * @throws ResourceNotFoundException když dodavatel s daným ID neexistuje
     */
    @Override
    @Transactional
    public SupplierDto.DetailResponse deactivate(Long id) {
        if (id == null) {
            throw new IllegalArgumentException("id nesmí být null");
        }
        if (supplierMapper.deactivate(id) == 0) {
            throw new ResourceNotFoundException("Dodavatel", id);
        }
        return getById(id);
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException  když je {@code id} null
     * @throws ResourceNotFoundException když dodavatel s daným ID neexistuje
     */
    @Override
    @Transactional
    public SupplierDto.DetailResponse activate(Long id) {
        if (id == null) {
            throw new IllegalArgumentException("id nesmí být null");
        }
        if (supplierMapper.activate(id) == 0) {
            throw new ResourceNotFoundException("Dodavatel", id);
        }
        return getById(id);
    }
}
