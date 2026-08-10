package cz.palo.autoservis.service;

import cz.palo.autoservis.exception.BusinessRuleException;
import cz.palo.autoservis.model.domain.warehouse.DocumentType;
import cz.palo.autoservis.model.domain.warehouse.ReceiptSource;
import cz.palo.autoservis.model.draft.DraftLine;
import cz.palo.autoservis.model.draft.DraftSupplier;
import cz.palo.autoservis.model.draft.FieldState;
import cz.palo.autoservis.model.draft.ReceiptDraft;
import cz.palo.autoservis.model.draft.TrackedField;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Adaptér českého e-fakturačního standardu ISDOC → kanonický {@link ReceiptDraft}.
 *
 * <p>Payoff kanonického draftu: nový vstupní kanál je jen nový adaptér — kontroly,
 * párování, completeness gate i potvrzení zůstávají beze změny. Na rozdíl od AI
 * extrakce jsou tu data <b>strojová a jistá</b>, takže všechna přečtená pole
 * dostávají stav {@code VERBATIM}; co v dokladu není, zůstává {@code ABSENT} —
 * nic se nedomýšlí (dopočty dělá až {@code DraftAssembler.fillDerivedValues}).
 *
 * <p>Struktura odpovídá oficiálnímu XSD ISDOC 6.0.2 (isdoc.cz), namespace
 * {@code http://isdoc.cz/namespace/2013}. Starší dokumenty 5.x mají namespace jiný,
 * proto se elementy hledají podle lokálních jmen, ne podle namespace.
 */
@Component
public class IsdocParser {

    /** ISDOC {@code DocumentType} 1 = faktura (jediný podporovaný typ dokladu). */
    private static final String DOCUMENT_TYPE_INVOICE = "1";

    /**
     * Kódy měrných jednotek dle UN/ECE Rec. 20 → náš číselník
     * ({@code warehouse.import.allowed-units}). Neznámý kód se <b>nepřekládá</b>:
     * projde tak, jak je, a kontrolor ho v review uvidí jako „mimo číselník".
     */
    private static final Map<String, String> UNIT_CODES = Map.of(
            "C62", "ks",    // one/piece
            "H87", "ks",    // piece
            "XPP", "ks",    // kus (balení)
            "LTR", "l",
            "KGM", "kg",
            "MTR", "m",
            "SET", "sada",
            "PR", "pár",
            "XBX", "bal"
    );

    /**
     * Naparsuje ISDOC dokument do kanonického draftu.
     *
     * @param xml obsah souboru .isdoc / .xml
     * @return draft se všemi přečtenými poli VERBATIM
     * @throws IllegalArgumentException pokud soubor není čitelné XML (→ 400)
     * @throws BusinessRuleException    pokud nejde o podporovaný typ dokladu (→ 422)
     */
    public ReceiptDraft parse(byte[] xml) {
        Element root = parseRoot(xml);
        requireSupportedDocumentType(root);

        ReceiptDraft draft = ReceiptDraft.builder()
                .schemaVersion(ReceiptDraft.CURRENT_SCHEMA_VERSION)
                .documentType(DocumentType.INVOICE)
                .sourceChannel(ReceiptSource.ISDOC)
                .header(parseHeader(root))
                .supplier(parseSupplier(root))
                .vatRecap(parseVatRecap(root))
                .deliveryNoteRefs(new ArrayList<>())
                .lines(parseLines(root))
                .checks(new ArrayList<>())
                .build();
        return draft;
    }

    // ------------------------------------------------------------------ XML

    private Element parseRoot(byte[] xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            // XXE: doklad přichází zvenčí, externí entity ani DOCTYPE nechceme
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            factory.setNamespaceAware(true);

            Document document = factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
            return document.getDocumentElement();
        } catch (Exception e) {
            throw new IllegalArgumentException("Soubor není platný ISDOC (XML nelze přečíst).", e);
        }
    }

    /**
     * ISDOC {@code DocumentType}: 1 = faktura. Dobropis (5) a vrubopis (6) by se
     * naskladnily místo odepsání — dokud není hotová fáze E5b, odmítáme je.
     */
    private void requireSupportedDocumentType(Element root) {
        String type = text(root, "DocumentType");
        if (type != null && !DOCUMENT_TYPE_INVOICE.equals(type.trim())) {
            throw new BusinessRuleException("ISDOC_UNSUPPORTED_DOCUMENT_TYPE", null,
                    "Tenhle ISDOC není faktura (DocumentType " + type.trim()
                            + "). Dobropisy a vrubopisy zatím naskladnit nelze.",
                    Map.of("documentType", type.trim()));
        }
    }

    // ------------------------------------------------------------------ hlavička

    private ReceiptDraft.Header parseHeader(Element root) {
        Element totals = child(root, "LegalMonetaryTotal");
        Element taxTotal = child(root, "TaxTotal");

        return ReceiptDraft.Header.builder()
                .documentNumber(verbatim(text(root, "ID")))
                .orderNumber(TrackedField.absent())
                .originalOrderNumber(TrackedField.absent())
                .issueDate(verbatimDate(text(root, "IssueDate")))
                .dueDate(verbatimDate(text(root, "DueDate")))
                .taxableSupplyDate(verbatimDate(text(root, "TaxPointDate")))
                .currency(verbatim(text(root, "CurrencyCode")))
                .subtotal(verbatimNumber(text(totals, "TaxExclusiveAmount")))
                // pozor: TaxAmount je i uvnitř TaxSubTotal — text() bere jen přímé potomky
                .vatAmount(verbatimNumber(text(taxTotal, "TaxAmount")))
                .totalAmount(verbatimNumber(text(totals, "TaxInclusiveAmount")))
                .build();
    }

    private DraftSupplier parseSupplier(Element root) {
        Element party = child(child(root, "AccountingSupplierParty"), "Party");
        if (party == null) {
            return DraftSupplier.builder().matchState(DraftSupplier.MatchState.NONE).build();
        }
        Element address = child(party, "PostalAddress");
        String street = joinStreet(text(address, "StreetName"), text(address, "BuildingNumber"));

        return DraftSupplier.builder()
                .extracted(DraftSupplier.Extracted.builder()
                        .name(text(child(party, "PartyName"), "Name"))
                        .registrationNumber(text(child(party, "PartyIdentification"), "ID"))
                        .vatId(text(child(party, "PartyTaxScheme"), "CompanyID"))
                        .street(street)
                        .city(text(address, "CityName"))
                        .postalCode(text(address, "PostalZone"))
                        .build())
                .matchState(DraftSupplier.MatchState.NONE)
                .build();
    }

    private String joinStreet(String streetName, String buildingNumber) {
        if (streetName == null) return buildingNumber;
        return buildingNumber == null ? streetName : streetName + " " + buildingNumber;
    }

    // ------------------------------------------------------------------ rekapitulace DPH

    private List<ReceiptDraft.VatRecapRow> parseVatRecap(Element root) {
        List<ReceiptDraft.VatRecapRow> recap = new ArrayList<>();
        Element taxTotal = child(root, "TaxTotal");
        if (taxTotal == null) return recap;

        for (Element subTotal : children(taxTotal, "TaxSubTotal")) {
            Integer percent = toInteger(text(child(subTotal, "TaxCategory"), "Percent"));
            recap.add(ReceiptDraft.VatRecapRow.builder()
                    .code(percent == null ? null : String.valueOf(percent))
                    .ratePercent(percent)
                    .base(toDecimal(text(subTotal, "TaxableAmount")))
                    .vat(toDecimal(text(subTotal, "TaxAmount")))
                    .build());
        }
        return recap;
    }

    // ------------------------------------------------------------------ řádky

    private List<DraftLine> parseLines(Element root) {
        List<DraftLine> lines = new ArrayList<>();
        Element container = child(root, "InvoiceLines");
        if (container == null) return lines;

        int position = 1;
        for (Element line : children(container, "InvoiceLine")) {
            Element item = child(line, "Item");
            Element quantity = child(line, "InvoicedQuantity");

            lines.add(DraftLine.builder()
                    .lineKind(DraftLine.LineKind.ITEM)
                    .position(position++)
                    // katalogové číslo dodavatele — identita pro párovací kaskádu
                    .catalogNumber(verbatim(catalogNumberOf(item)))
                    .name(verbatim(text(item, "Description")))
                    .unit(verbatim(unitOf(quantity)))
                    .quantity(verbatimNumber(quantity == null ? null : quantity.getTextContent()))
                    .unitPriceExclVat(verbatimNumber(text(line, "UnitPrice")))
                    .vatRate(verbatimInteger(text(child(line, "ClassifiedTaxCategory"), "Percent")))
                    .totalExclVat(verbatimNumber(text(line, "LineExtensionAmount")))
                    .totalInclVat(verbatimNumber(text(line, "LineExtensionAmountTaxInclusive")))
                    .build());
        }
        return lines;
    }

    /** Katalogové číslo: přednost má kód prodávajícího, jinak katalogový. */
    private String catalogNumberOf(Element item) {
        String sellers = text(child(item, "SellersItemIdentification"), "ID");
        return sellers != null ? sellers : text(child(item, "CatalogueItemIdentification"), "ID");
    }

    /** Převod UN/ECE kódu na náš číselník; neznámý kód projde beze změny. */
    private String unitOf(Element quantity) {
        if (quantity == null) return null;
        String code = quantity.getAttribute("unitCode");
        if (code == null || code.isBlank()) return null;
        return UNIT_CODES.getOrDefault(code.trim().toUpperCase(), code.trim());
    }

    // ------------------------------------------------------------------ pomocné

    private Element child(Element parent, String localName) {
        if (parent == null) return null;
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node instanceof Element element && localName.equals(element.getLocalName())) {
                return element;
            }
        }
        return null;
    }

    private List<Element> children(Element parent, String localName) {
        List<Element> found = new ArrayList<>();
        if (parent == null) return found;
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node instanceof Element element && localName.equals(element.getLocalName())) {
                found.add(element);
            }
        }
        return found;
    }

    /** Text přímého potomka; {@code null} když chybí nebo je prázdný. */
    private String text(Element parent, String localName) {
        Element element = child(parent, localName);
        if (element == null) return null;
        String value = element.getTextContent();
        return value == null || value.isBlank() ? null : value.trim();
    }

    private TrackedField<String> verbatim(String value) {
        return TrackedField.ofNullable(value, FieldState.VERBATIM);
    }

    private TrackedField<LocalDate> verbatimDate(String value) {
        LocalDate date = toDate(value);
        return TrackedField.ofNullable(date, FieldState.VERBATIM);
    }

    private TrackedField<BigDecimal> verbatimNumber(String value) {
        return TrackedField.ofNullable(toDecimal(value), FieldState.VERBATIM);
    }

    private TrackedField<Integer> verbatimInteger(String value) {
        return TrackedField.ofNullable(toInteger(value), FieldState.VERBATIM);
    }

    private LocalDate toDate(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException e) {
            return null;   // nečitelné datum → ABSENT, doplní člověk
        }
    }

    private BigDecimal toDecimal(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return new BigDecimal(value.trim().replace(",", "."));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Integer toInteger(String value) {
        BigDecimal decimal = toDecimal(value);
        return decimal == null ? null : decimal.intValue();
    }
}
