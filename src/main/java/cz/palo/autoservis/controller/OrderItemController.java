package cz.palo.autoservis.controller;

import cz.palo.autoservis.model.dto.order.OrderItemDto;
import cz.palo.autoservis.model.dto.order.OrderItemSummaryDto;
import cz.palo.autoservis.model.dto.warehouse.GoodsReceiptItemDto;
import cz.palo.autoservis.security.model.domain.AppUserDetails;
import cz.palo.autoservis.service.OrderItemService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * REST controller správy položek servisní zakázky.
 *
 * <p>Base path: {@code /api/{version}/orders/{orderId}} — položky pod {@code /items},
 * výdej materiálu pod {@code /issue-stock}.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/{version}/orders/{orderId}")
@Validated
public class OrderItemController {

    private final OrderItemService orderItemService;

    /**
     * Vrací úplný detail položky zakázky podle ID.
     *
     * @param id ID položky zakázky
     * @return 200 OK s položkou zakázky
     */
    @GetMapping("/items/{id}")
    public ResponseEntity<OrderItemDto.Response> getById(@PathVariable Long id) {
        return ResponseEntity.ok(orderItemService.getById(id));
    }

    /**
     * Vrací všechny položky dané zakázky seřazené podle pozice.
     *
     * @param orderId ID zakázky (z cesty)
     * @return 200 OK se seznamem položek zakázky
     */
    @GetMapping("/items")
    public ResponseEntity<List<OrderItemDto.Response>> getByOrderId(@PathVariable Long orderId) {
        return ResponseEntity.ok(orderItemService.getByOrderId(orderId));
    }


    @GetMapping("/items/summary")
    public ResponseEntity<OrderItemSummaryDto.Response> getSummaryByOrderId(@PathVariable Long orderId) {
        return ResponseEntity.ok(orderItemService.getSummaryByOrderId(orderId));
    }

    /**
     * Založí novou položku v dané zakázce.
     *
     * @param orderId       ID zakázky (z cesty)
     * @param createRequest validované tělo requestu
     * @param currentUser   právě přihlášený uživatel
     * @return 201 Created s {@code Location} hlavičkou a založenou položkou zakázky
     */
    @PostMapping("/items")
    public ResponseEntity<OrderItemDto.Response> create(
            @PathVariable Long orderId,
            @Valid @RequestBody OrderItemDto.CreateRequest createRequest,
            @AuthenticationPrincipal AppUserDetails currentUser) {
        OrderItemDto.Response created = orderItemService.create(orderId, createRequest, currentUser.getUserId());
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(created.getId())
                .toUri();
        return ResponseEntity.created(location).body(created);
    }

    /**
     * Importuje položky příjemky do zakázky a založí odpovídající položky zakázky.
     *
     * @param orderId        ID zakázky (z cesty)
     * @param importRequest  validovaný seznam položek příjemky k importu
     * @param currentUser    právě přihlášený uživatel
     * @return 201 Created se seznamem založených položek zakázky
     */
    @PostMapping("/items/import-from-receipt")
    public ResponseEntity<List<OrderItemDto.Response>> createFromReceipt(
            @PathVariable Long orderId,
            @Valid @RequestBody List<GoodsReceiptItemDto.ImportRequest> importRequest,
            @AuthenticationPrincipal AppUserDetails currentUser
    ){

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(orderItemService.importFromReceipt(orderId, importRequest, currentUser.getUserId()));
    }

    /**
     * Aktualizuje existující položku zakázky.
     *
     * @param id            ID položky zakázky
     * @param updateRequest validované tělo requestu
     * @return 200 OK s aktualizovanou položkou zakázky
     */
    @PutMapping("/items/{id}")
    public ResponseEntity<OrderItemDto.Response> update(
            @PathVariable Long id,
            @Valid @RequestBody OrderItemDto.UpdateRequest updateRequest,
            @AuthenticationPrincipal AppUserDetails currentUser) {
        return ResponseEntity.ok(orderItemService.update(id, updateRequest, currentUser.getUserId()));
    }

    /**
     * Aktualizuje zobrazovanou pozici více položek v rámci dané zakázky.
     *
     * @param orderId ID zakázky (z cesty)
     * @param items   validovaný seznam položek s novými pozicemi
     * @return 204 No Content
     */
    @PutMapping("/items/reorder")
    public ResponseEntity<Void> reorder(
            @PathVariable Long orderId,
            @Valid @RequestBody List<OrderItemDto.ReorderRequest> items) {
        orderItemService.reorder(orderId, items);
        return ResponseEntity.noContent().build();
    }

    /**
     * Trvale smaže položku zakázky podle ID.
     *
     * @param id ID položky zakázky
     * @return 204 No Content
     */
    @DeleteMapping("/items/{id}")
    public ResponseEntity<Void> delete(
            @PathVariable Long id,
            @AuthenticationPrincipal AppUserDetails currentUser) {
        orderItemService.delete(id, currentUser.getUserId());
        return ResponseEntity.noContent().build();
    }

    /**
     * Vydá ze skladu materiál rezervovaný na zakázce (V83).
     *
     * <p>Přidání dílu na zakázku je jen rezervace — díl leží dál v regálu. Tímto voláním
     * fyzicky odejde: vzniknou skladové pohyby a klesne stav. Vydává se <strong>celá
     * zakázka najednou</strong> (rozhodnutí uživatele 2026-08-05), tedy vše, co ještě
     * vydáno nebylo. Opakované volání nic nezdvojí.
     *
     * @param orderId ID zakázky
     * @return 200 s počtem vydaných položek; 422 {@code STOCK_MISSING_FOR_ISSUE},
     *         když rezervovaný díl mezitím ze skladu zmizel
     */
    @PostMapping("/issue-stock")
    public ResponseEntity<Map<String, Integer>> issueStock(
            @PathVariable Long orderId,
            @AuthenticationPrincipal AppUserDetails currentUser) {
        int issued = orderItemService.issueStock(orderId, currentUser.getUserId());
        return ResponseEntity.ok(Map.of("issuedItems", issued));
    }
}
