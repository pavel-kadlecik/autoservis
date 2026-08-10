package cz.palo.autoservis.service.impl;

import cz.palo.autoservis.model.dto.customer.AddressDto;
import cz.palo.autoservis.model.dto.customer.CustomerDto;
import cz.palo.autoservis.model.dto.order.OrderDto;
import cz.palo.autoservis.model.dto.vehicle.VehicleDto;
import cz.palo.autoservis.model.enums.AddressType;
import cz.palo.autoservis.service.CompanyProfileService;
import cz.palo.autoservis.service.CustomerService;
import cz.palo.autoservis.service.OrderDocumentService;
import cz.palo.autoservis.service.OrderService;
import cz.palo.autoservis.service.VehicleService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.util.Base64;
import java.util.List;

/**
 * Renderuje PDF zakázkového listu: Thymeleaf šablona {@code pdf/order-protocol} + sdílený
 * {@link PdfRenderer} (vzor {@code CashReceiptDocumentServiceImpl}).
 *
 * <p>Data se skládají ze <strong>živých</strong> zdrojů — zakázka, profil firmy, zákazník
 * a vozidlo. U daňových dokladů se tiskne ze snapshotů, tady ne schválně: zakázkový list se
 * tiskne při příjmu vozu a není evidenčním dokladem, takže nemá co zmrazovat. Jediná hodnota,
 * která se změnit nesmí, je stav tachometru při příjmu — a ta je uložená na zakázce (V70).
 */
@Service
@RequiredArgsConstructor
public class OrderDocumentServiceImpl implements OrderDocumentService {

    private static final String TEMPLATE = "pdf/order-protocol";

    private final OrderService orderService;
    private final CustomerService customerService;
    private final VehicleService vehicleService;
    private final CompanyProfileService companyProfileService;
    private final TemplateEngine templateEngine;
    private final PdfRenderer pdfRenderer;

    @Override
    public byte[] renderPdf(Long orderId) {
        OrderDto.DetailResponse order = orderService.getById(orderId);
        CustomerDto.DetailResponse customer = customerService.getById(order.getCustomerId());
        VehicleDto.DetailResponse vehicle = vehicleService.getById(order.getVehicleId());

        Context context = new Context();
        context.setVariable("order", order);
        context.setVariable("customer", customer);
        context.setVariable("address", preferredAddress(customer.getAddresses()));
        context.setVariable("vehicle", vehicle);
        context.setVariable("company", companyProfileService.get());
        context.setVariable("logoDataUri", loadImage("/templates/images/logo.png", "logo"));

        String html = templateEngine.process(TEMPLATE, context);
        return pdfRenderer.htmlToPdf(html, "zakázkový list id=" + orderId);
    }

    /**
     * Adresa na doklad: fakturační, jinak výchozí, jinak první — stejné pořadí, jaké nabízí
     * frontend při vystavení faktury, ať na dvou dokladech téhož zákazníka nestojí jiná adresa.
     *
     * @return adresa, nebo {@code null}, když zákazník žádnou nemá (šablona řádek vynechá)
     */
    private AddressDto.Response preferredAddress(List<AddressDto.Response> addresses) {
        if (addresses == null || addresses.isEmpty()) {
            return null;
        }
        return addresses.stream()
                .filter(a -> a.getAddressType() == AddressType.BILLING)
                .findFirst()
                .or(() -> addresses.stream().filter(AddressDto.Response::isDefault).findFirst())
                .orElse(addresses.getFirst());
    }

    /** Načte obrázek z classpath jako base64 data-URI (logo se vkládá přímo do HTML). */
    private String loadImage(String resource, String label) {
        try (var in = getClass().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Zdroj nenalezen: " + resource);
            }
            return "data:image/png;base64," + Base64.getEncoder().encodeToString(in.readAllBytes());
        } catch (Exception e) {
            throw new IllegalStateException("Nepodařilo se načíst " + label, e);
        }
    }
}
