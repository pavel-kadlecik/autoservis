package cz.palo.autoservis.service.impl;

import cz.palo.autoservis.exception.ResourceNotFoundException;
import cz.palo.autoservis.mapper.GoodsReceiptMapper;
import cz.palo.autoservis.model.converter.GoodsReceiptItemConverter;
import cz.palo.autoservis.model.domain.warehouse.GoodsReceiptItem;
import cz.palo.autoservis.model.dto.autocomplete.AutocompleteItem;
import cz.palo.autoservis.model.dto.autocomplete.AutocompleteResponse;
import cz.palo.autoservis.model.dto.warehouse.GoodReceiptAutocompleteParams;
import cz.palo.autoservis.model.dto.warehouse.GoodsReceiptItemDto;
import cz.palo.autoservis.service.GoodsReceiptService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class GoodsReceiptServiceImpl implements GoodsReceiptService {

    private final GoodsReceiptItemConverter goodsReceiptItemConverter;
    private final GoodsReceiptMapper goodsReceiptMapper;

    @Override
    public AutocompleteResponse autocomplete(GoodReceiptAutocompleteParams params) {

        List<AutocompleteItem> items = goodsReceiptMapper.autocomplete(params);
        int effectiveLimit = params.effectiveLimit();
        boolean hasMore = items.size() > effectiveLimit;

        AutocompleteResponse response = new AutocompleteResponse();
        response.setData(items.subList(0, Math.min(items.isEmpty() ? 0 : hasMore ? items.size() - 1 : items.size(), effectiveLimit)));
        response.setHasMore(hasMore);
        return response;
    }


    @Override
    public List<GoodsReceiptItemDto.Response> getImportableItems(Long receiptId) {

        if (receiptId == null) {
            throw new IllegalArgumentException("ID příjemky nesmí být null");
        }

        if (!goodsReceiptMapper.existsConfirmed(receiptId)) {
            throw new ResourceNotFoundException("Příjemka", receiptId);
        }

        List<GoodsReceiptItem> importableItems = goodsReceiptMapper.findImportableItems(receiptId);
        return importableItems.stream().map(goodsReceiptItemConverter::toDto).toList();
    }
}
