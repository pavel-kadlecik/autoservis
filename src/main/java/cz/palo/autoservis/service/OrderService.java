package cz.palo.autoservis.service;

import cz.palo.autoservis.model.dto.order.OrderDto;
import cz.palo.autoservis.model.dto.order.OrderSearchParams;
import cz.palo.autoservis.model.dto.pagination.PagedResponse;

import java.util.List;

/**
 * Service rozhraní pro správu servisních zakázek.
 *
 * <p>Poskytuje CRUD operace a stránkované hledání nad tabulkou {@code order.orders}.
 */
public interface OrderService {

    /**
     * Vrátí stránkovaný seznam zakázek odpovídajících zadaným parametrům hledání.
     *
     * @param params parametry hledání (filtry, stránka, velikost stránky)
     * @return stránkovaná odpověď
     */
    PagedResponse<OrderDto.ListResponse> getPage(OrderSearchParams params);

    /**
     * Vrátí plný detail zakázky podle ID.
     *
     * @param id ID zakázky
     * @return detail zakázky
     */
    OrderDto.DetailResponse getById(Long id);

    /**
     * Spočítá otevřené (neterminální) aktivní zakázky zákazníka.
     * Používá modul zákazníků při rozhodování, zda lze zákazníka deaktivovat.
     *
     * @param customerId ID zákazníka
     * @return počet otevřených zakázek (0 = žádná)
     */
    int countOpenByCustomerId(Long customerId);

    /**
     * Spočítá otevřené (neterminální) aktivní zakázky vozidla.
     * Používá modul vozidel při rozhodování, zda lze vozidlo deaktivovat.
     *
     * @param vehicleId ID vozidla
     * @return počet otevřených zakázek (0 = žádná)
     */
    int countOpenByVehicleId(Long vehicleId);

    /**
     * Založí novou servisní zakázku.
     *
     * @param createRequest zvalidovaný request s daty zakázky
     * @param userId        ID přihlášeného uživatele (auditní pole {@code created_by})
     * @return detail vytvořené zakázky
     */
    OrderDto.DetailResponse create(OrderDto.CreateRequest createRequest, Long userId);

    /**
     * Aktualizuje existující servisní zakázku.
     *
     * @param id            ID zakázky
     * @param updateRequest zvalidovaný request s novými hodnotami
     * @param userId        ID přihlášeného uživatele
     * @return detail zakázky po aktualizaci
     */
    OrderDto.DetailResponse update(Long id, OrderDto.UpdateRequest updateRequest, Long userId);

    /**
     * Tvrdě smaže zakázku, kterou obsluha založila omylem (rozhodnutí uživatele 2026-08-06).
     *
     * <p><strong>Smazat ≠ zrušit.</strong> Mazání je pro záznam, který nikdy neměl vzniknout
     * (překlep, špatné auto); zakázka, u které k práci nedošlo, se ruší stavem
     * {@code CANCELLED} a v evidenci zůstává jako obchodní fakt.
     *
     * <p>Projde jen tehdy, když po zakázce <strong>nic nezůstalo</strong>: žádná faktura
     * (ani stornovaná či dobropisovaná) a žádný skladový pohyb. Pouhá rezervace materiálu
     * nevadí — díl regál neopustil. Položky zakázky a odečet tachometru z příjmu odejdou
     * kaskádou; objednávka v kalendáři se vrátí na „Naplánováno“.
     *
     * @param id ID zakázky
     */
    void delete(Long id, Long userId);

    /**
     * Zruší zakázku a <strong>vrátí veškerý vydaný materiál</strong> na sklad, obojí v jedné
     * transakci (rozhodnutí uživatele 2026-08-06).
     *
     * <p>Ze zrušené zakázky nemá co zbýt: díly, které zůstaly na voze, zákazník zaplatí —
     * patří tedy na <em>novou</em> zakázku, kterou obsluha založí, ne na tuhle. Zrušená
     * zakázka se tím vyčistí celá a fakturuje se jen to, co se opravdu stalo.
     *
     * <p>Dosud šlo zrušení jen ručně: smazat položky po jedné a teprve pak přepnout stav —
     * u osmi dílů devět potvrzovacích dialogů.
     *
     * <p>Aktivní faktura zrušení <strong>dál blokuje</strong> (`ORDER_HAS_ACTIVE_INVOICE`);
     * nejdřív se musí stornovat koncept, resp. vystavit dobropis.
     *
     * @param id     ID zakázky
     * @param userId ID přihlášeného uživatele pro audit vratek
     * @return zakázka po zrušení
     */
    OrderDto.DetailResponse cancel(Long id, Long userId);

    /**
     * Změní stav zakázky vyhrazenou cestou (bez full-replace celého záznamu).
     *
     * <p>Stav se mění nejčastěji ze všech polí, ale dosud k tomu vedl jen
     * {@code PUT /orders/{id}}, který nese celý záznam — obsluha musela otevřít editační
     * formulář a přepsat devět dalších sloupců. Objednávka v kalendáři přitom vyhrazený
     * endpoint má od začátku.
     *
     * <p>Prochází <strong>toutéž brankou</strong> jako {@code update}, takže automat ani
     * podmínky zrušení a znovuotevření neobchází.
     *
     * @param id            ID zakázky
     * @param statusRequest cílový stav
     * @param userId        ID přihlášeného uživatele (výdej či vrácení materiálu)
     * @return zakázka po změně
     */
    OrderDto.DetailResponse changeStatus(Long id, OrderDto.StatusRequest statusRequest, Long userId);


}
