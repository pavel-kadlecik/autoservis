package cz.palo.autoservis.model.converter;

import cz.palo.autoservis.model.domain.billing.CompanyProfile;
import cz.palo.autoservis.model.dto.billing.CompanyProfileDto;
import org.springframework.stereotype.Component;

/**
 * Konvertor mezi doménovými objekty {@link CompanyProfile} a DTO {@link CompanyProfileDto}.
 */
@Component
public class CompanyProfileConverter {

    /**
     * Převede {@link CompanyProfile} na response DTO.
     *
     * @param profile doménový objekt
     * @return response DTO, nebo {@code null} při {@code null} vstupu
     */
    public CompanyProfileDto.Response toResponse(CompanyProfile profile) {
        if (profile == null) {
            return null;
        }

        CompanyProfileDto.Response response = new CompanyProfileDto.Response();
        response.setId(profile.getId());
        response.setName(profile.getName());
        response.setIco(profile.getIco());
        response.setDic(profile.getDic());
        response.setStreet(profile.getStreet());
        response.setStreetNumber(profile.getStreetNumber());
        response.setCity(profile.getCity());
        response.setPostalCode(profile.getPostalCode());
        response.setCountryCode(profile.getCountryCode());
        response.setBankAccount(profile.getBankAccount());
        response.setIban(profile.getIban());
        response.setSwift(profile.getSwift());
        response.setInvoiceNumberAuto(profile.getInvoiceNumberAuto());
        response.setInvoiceNumberMask(profile.getInvoiceNumberMask());
        response.setInvoiceGapCheckEnabled(profile.getInvoiceGapCheckEnabled());
        response.setInvoiceGapCheckFrom(profile.getInvoiceGapCheckFrom());
        response.setCashReceiptNumberSource(profile.getCashReceiptNumberSource());
        response.setCashReceiptNumberMask(profile.getCashReceiptNumberMask());
        response.setCashReceiptGapCheckEnabled(profile.getCashReceiptGapCheckEnabled());
        response.setCashReceiptGapCheckFrom(profile.getCashReceiptGapCheckFrom());
        return response;
    }

    /**
     * Aplikuje pole z {@link CompanyProfileDto.UpdateRequest} na existující
     * {@link CompanyProfile}. Existující objekt se mění na místě a vrací.
     *
     * @param existing      profil načtený z databáze
     * @param updateRequest zvalidované update request DTO
     * @return upravený doménový objekt, nebo {@code null}, je-li kterýkoli argument {@code null}
     */
    public CompanyProfile applyUpdate(CompanyProfile existing, CompanyProfileDto.UpdateRequest updateRequest) {
        if (existing == null || updateRequest == null) {
            return null;
        }

        existing.setName(updateRequest.getName());
        existing.setIco(updateRequest.getIco());
        existing.setDic(updateRequest.getDic());
        existing.setStreet(updateRequest.getStreet());
        existing.setStreetNumber(updateRequest.getStreetNumber());
        existing.setCity(updateRequest.getCity());
        existing.setPostalCode(updateRequest.getPostalCode());
        existing.setCountryCode(updateRequest.getCountryCode());
        existing.setBankAccount(updateRequest.getBankAccount());
        existing.setIban(updateRequest.getIban());
        existing.setSwift(updateRequest.getSwift());
        existing.setInvoiceNumberAuto(updateRequest.getInvoiceNumberAuto());
        existing.setInvoiceNumberMask(updateRequest.getInvoiceNumberMask());
        existing.setInvoiceGapCheckEnabled(Boolean.TRUE.equals(updateRequest.getInvoiceGapCheckEnabled()));
        existing.setInvoiceGapCheckFrom(updateRequest.getInvoiceGapCheckFrom());
        existing.setCashReceiptNumberSource(updateRequest.getCashReceiptNumberSource());
        existing.setCashReceiptNumberMask(updateRequest.getCashReceiptNumberMask());
        existing.setCashReceiptGapCheckEnabled(Boolean.TRUE.equals(updateRequest.getCashReceiptGapCheckEnabled()));
        existing.setCashReceiptGapCheckFrom(updateRequest.getCashReceiptGapCheckFrom());
        return existing;
    }
}
