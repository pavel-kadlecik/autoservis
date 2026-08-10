package cz.palo.autoservis.model.converter;

import cz.palo.autoservis.model.domain.warehouse.Supplier;
import cz.palo.autoservis.model.dto.warehouse.SupplierDto;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Component
public class SupplierConverter {

    public SupplierDto.DetailResponse toDetailResponse(Supplier supplier) {
        if(supplier == null){
            return null;
        }

        return SupplierDto.DetailResponse.builder()
                .id(supplier.getId())
                .name(supplier.getName())
                .registrationNumber(supplier.getRegistrationNumber())
                .vatId(supplier.getVatId())
                .street(supplier.getStreet())
                .city(supplier.getCity())
                .postalCode(supplier.getPostalCode())
                .countryCode(supplier.getCountryCode())
                .bankAccount(supplier.getBankAccount())
                .iban(supplier.getIban())
                .swift(supplier.getSwift())
                .email(supplier.getEmail())
                .phone(supplier.getPhone())
                .active(supplier.isActive())
                .createdAt(supplier.getCreatedAt())
                .updatedAt(supplier.getUpdatedAt())
                .build();
    }

    public Supplier applyUpdate(Supplier existingSupplier, SupplierDto.UpdateRequest updateRequest) {

        if (updateRequest == null || existingSupplier == null) {
            return null;
        }

        existingSupplier.setName(updateRequest.getName());
        existingSupplier.setRegistrationNumber(updateRequest.getRegistrationNumber());
        existingSupplier.setVatId(updateRequest.getVatId());
        existingSupplier.setStreet(updateRequest.getStreet());
        existingSupplier.setCity(updateRequest.getCity());
        existingSupplier.setPostalCode(updateRequest.getPostalCode());
        existingSupplier.setCountryCode(updateRequest.getCountryCode());
        existingSupplier.setBankAccount(updateRequest.getBankAccount());
        existingSupplier.setIban(updateRequest.getIban());
        existingSupplier.setSwift(updateRequest.getSwift());
        existingSupplier.setEmail(updateRequest.getEmail());
        existingSupplier.setPhone(updateRequest.getPhone());

        return existingSupplier;
    }

    public List<SupplierDto.ListResponse> toListResponse(List<Supplier> suppliers) {
        return suppliers.stream().map(this::toListResponse).collect(Collectors.toList());
    }

    private SupplierDto.ListResponse toListResponse(Supplier supplier) {
        if(supplier == null){
            return null;
        }

        return SupplierDto.ListResponse.builder()
                .id(supplier.getId())
                .name(supplier.getName())
                .registrationNumber(supplier.getRegistrationNumber())
                .vatId(supplier.getVatId())
                .street(supplier.getStreet())
                .city(supplier.getCity())
                .email(supplier.getEmail())
                .phone(supplier.getPhone())
                .active(supplier.isActive())
                .build();
    }

}
