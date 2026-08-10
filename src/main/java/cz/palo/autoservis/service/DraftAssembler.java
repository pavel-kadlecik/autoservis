package cz.palo.autoservis.service;

import cz.palo.autoservis.config.WarehouseImportProperties;
import cz.palo.autoservis.model.domain.warehouse.DocumentType;
import cz.palo.autoservis.model.domain.warehouse.ReceiptSource;
import cz.palo.autoservis.model.draft.*;
import cz.palo.autoservis.model.dto.warehouse.DocumentExtractionResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Složí kanonický {@link ReceiptDraft} z výsledku AI extrakce.
 *
 * <p>Tady se dělají dopočty, které nesmí dělat model („AI čte, kód počítá"):
 * mapování písmenných kódů sazeb přes extrahovanou rekapitulaci, dopočet
 * řádkových a hlavičkových součtů, dosazení defaultů (DEFAULTED). Je to šev
 * pro budoucí adaptéry (ISDOC, ruční formulář) — všechny kanály končí
 * stejným draftem.
 */
@Component
@RequiredArgsConstructor
public class DraftAssembler {

    private final WarehouseImportProperties props;

    public ReceiptDraft assemble(DocumentExtractionResult ex, DocumentType documentType,
                                 String extractionModel) {
        List<ReceiptDraft.VatRecapRow> recap = mapRecap(ex.vatRecap());
        List<DraftLine> lines = mapLines(ex.lines(), recap, documentType);
        List<DeliveryNoteRef> dnRefs = collectDeliveryNoteRefs(lines);

        return ReceiptDraft.builder()
                .schemaVersion(ReceiptDraft.CURRENT_SCHEMA_VERSION)
                .documentType(documentType)
                .sourceChannel(ReceiptSource.AI_PDF)
                .extraction(ReceiptDraft.Extraction.builder()
                        .model(extractionModel)
                        .extractedAt(OffsetDateTime.now())
                        .build())
                .header(mapHeader(ex, lines, documentType))
                .supplier(mapSupplier(ex.supplier()))
                .vatRecap(recap)
                .deliveryNoteRefs(dnRefs)
                .lines(lines)
                .checks(new ArrayList<>())
                .build();
    }

    /**
     * Doplní do draftu chybějící (ABSENT) dopočitatelné hodnoty: řádkové
     * a hlavičkové součty, u dodacího listu default sazby. Nikdy nepřepisuje
     * vyplněné hodnoty — slouží ruční příjemce a editaci v review
     * (uživatel doplní množství a ceny, kód dopočte zbytek).
     */
    public void fillDerivedValues(ReceiptDraft draft) {
        for (DraftLine line : draft.getLines()) {
            if (line.getLineKind() != DraftLine.LineKind.ITEM) continue;

            if (isAbsent(line.getUnit())) {
                line.setUnit(TrackedField.defaulted(props.getDefaults().getUnit()));
            }
            if (isAbsent(line.getVatRate()) && draft.getDocumentType() == DocumentType.DELIVERY_NOTE) {
                line.setVatRate(TrackedField.defaulted(props.getDefaults().getVatRate()));
            }
            deriveLineAmounts(line);
        }

        var h = draft.getHeader();
        if (isAbsent(h.getCurrency())) {
            h.setCurrency(TrackedField.defaulted(props.getDefaults().getCurrency()));
        }
        if (isAbsent(h.getSubtotal())) {
            BigDecimal sum = sumLines(draft.getLines(), DraftLine::getTotalExclVat);
            if (sum != null) h.setSubtotal(TrackedField.of(sum, FieldState.DERIVED));
        }
        if (isAbsent(h.getTotalAmount())) {
            BigDecimal sum = sumLines(draft.getLines(), DraftLine::getTotalInclVat);
            if (sum != null) h.setTotalAmount(TrackedField.of(sum, FieldState.DERIVED));
        }
        if (isAbsent(h.getVatAmount())
                && h.getSubtotal().getValue() != null && h.getTotalAmount().getValue() != null) {
            h.setVatAmount(TrackedField.of(
                    h.getTotalAmount().getValue().subtract(h.getSubtotal().getValue()),
                    FieldState.DERIVED));
        }
    }

    private boolean isAbsent(TrackedField<?> field) {
        return field == null || field.getValue() == null;
    }

    /**
     * Dopočet řádkových částek — sdílený mezi {@link #mapLine} (AI import)
     * a {@link #fillDerivedValues} (ISDOC, ruční příjemka, přepočet v review).
     * Doplňuje jen ABSENT hodnoty, nikdy nepřepisuje. Kromě dopředného směru
     * (množství × jednotková cena → základ → s DPH) umí i <b>zpětný</b>
     * (cena s DPH → základ → jednotková cena) pro doklady, které uvádějí jen
     * cenu s DPH — typicky ručně psané dodací listy. Vše se stavem DERIVED.
     *
     * <p>Pořadí zachovává přednost vytištěných/dopředných hodnot: má-li řádek
     * jednotkovou cenu, základ se počítá z ní (krok 1) a zpětný krok 2 se
     * neuplatní.
     */
    private void deriveLineAmounts(DraftLine line) {
        BigDecimal qty = valueOf(line.getQuantity());
        Integer rate = line.getVatRate() == null ? null : line.getVatRate().getValue();

        // 1) dopředu: základ = množství × jednotková cena
        if (isAbsent(line.getTotalExclVat()) && qty != null && valueOf(line.getUnitPriceExclVat()) != null) {
            line.setTotalExclVat(derived(qty.multiply(valueOf(line.getUnitPriceExclVat()))));
        }
        // 2) zpětně: základ = cena s DPH / (1 + sazba)
        if (isAbsent(line.getTotalExclVat()) && valueOf(line.getTotalInclVat()) != null && rate != null) {
            line.setTotalExclVat(derived(
                    valueOf(line.getTotalInclVat()).divide(vatFactor(rate), 2, RoundingMode.HALF_UP)));
        }
        // 3) zpětně: jednotková cena = základ / množství
        if (isAbsent(line.getUnitPriceExclVat()) && valueOf(line.getTotalExclVat()) != null
                && qty != null && qty.signum() > 0) {
            line.setUnitPriceExclVat(derived(
                    valueOf(line.getTotalExclVat()).divide(qty, 2, RoundingMode.HALF_UP)));
        }
        // 4) dopředu: cena s DPH = základ × (1 + sazba)
        if (isAbsent(line.getTotalInclVat()) && valueOf(line.getTotalExclVat()) != null && rate != null) {
            line.setTotalInclVat(derived(valueOf(line.getTotalExclVat()).multiply(vatFactor(rate))));
        }
    }

    private static BigDecimal valueOf(TrackedField<BigDecimal> field) {
        return field == null ? null : field.getValue();
    }

    private static TrackedField<BigDecimal> derived(BigDecimal value) {
        return TrackedField.of(value.setScale(2, RoundingMode.HALF_UP), FieldState.DERIVED);
    }

    private static BigDecimal vatFactor(int ratePercent) {
        return BigDecimal.ONE.add(BigDecimal.valueOf(ratePercent).movePointLeft(2));
    }

    // ------------------------------------------------------------------ header

    private ReceiptDraft.Header mapHeader(DocumentExtractionResult ex, List<DraftLine> lines,
                                          DocumentType documentType) {
        var h = ex.header();
        var sum = ex.summary();

        TrackedField<BigDecimal> subtotal = tracked(sum == null ? null : sum.subtotal());
        TrackedField<BigDecimal> vatAmount = tracked(sum == null ? null : sum.vatAmount());
        TrackedField<BigDecimal> totalAmount = tracked(sum == null ? null : sum.totalAmount());

        // Dodací list nemá rozpis DPH — hlavičkové součty dopočte kód z řádků.
        if (subtotal.getState() == FieldState.ABSENT) {
            BigDecimal fromLines = sumLines(lines, DraftLine::getTotalExclVat);
            if (fromLines != null) subtotal = TrackedField.of(fromLines, FieldState.DERIVED);
        }
        if (vatAmount.getState() == FieldState.ABSENT) {
            BigDecimal excl = sumLines(lines, DraftLine::getTotalExclVat);
            BigDecimal incl = sumLines(lines, DraftLine::getTotalInclVat);
            if (excl != null && incl != null) {
                vatAmount = TrackedField.of(incl.subtract(excl), FieldState.DERIVED);
            }
        }
        if (totalAmount.getState() == FieldState.ABSENT) {
            BigDecimal incl = sumLines(lines, DraftLine::getTotalInclVat);
            if (incl != null) totalAmount = TrackedField.of(incl, FieldState.DERIVED);
        }

        TrackedField<String> currency = tracked(h == null ? null : h.currency());
        if (currency.getState() == FieldState.ABSENT) {
            currency = TrackedField.defaulted(props.getDefaults().getCurrency());
        }

        return ReceiptDraft.Header.builder()
                .documentNumber(tracked(h == null ? null : h.documentNumber()))
                .orderNumber(tracked(h == null ? null : h.orderNumber()))
                .originalOrderNumber(tracked(h == null ? null : h.originalOrderNumber()))
                .issueDate(tracked(h == null ? null : h.issueDate()))
                .dueDate(tracked(h == null ? null : h.dueDate()))
                .taxableSupplyDate(tracked(h == null ? null : h.taxableSupplyDate()))
                .currency(currency)
                .subtotal(subtotal)
                .vatAmount(vatAmount)
                .totalAmount(totalAmount)
                .build();
    }

    // ------------------------------------------------------------------ lines

    private List<DraftLine> mapLines(List<DocumentExtractionResult.Line> lines,
                                     List<ReceiptDraft.VatRecapRow> recap,
                                     DocumentType documentType) {
        if (lines == null) return new ArrayList<>();
        List<DraftLine> result = new ArrayList<>();
        for (DocumentExtractionResult.Line line : lines) {
            result.add(mapLine(line, recap, documentType));
        }
        return result;
    }

    private DraftLine mapLine(DocumentExtractionResult.Line line,
                              List<ReceiptDraft.VatRecapRow> recap,
                              DocumentType documentType) {
        DraftLine.LineKind kind = line.kind() == null
                ? DraftLine.LineKind.ITEM
                : DraftLine.LineKind.valueOf(line.kind().name());

        TrackedField<String> unit = tracked(line.unit());
        if (kind == DraftLine.LineKind.ITEM && unit.getState() == FieldState.ABSENT) {
            unit = TrackedField.defaulted(props.getDefaults().getUnit());
        }

        TrackedField<Integer> vatRate = resolveVatRate(line.vatRateOrCode(), recap, kind, documentType);

        DraftLine draftLine = DraftLine.builder()
                .lineKind(kind)
                .position(line.position())
                .catalogNumber(tracked(line.catalogNumber()))
                .name(tracked(line.name()))
                .unit(unit)
                .quantity(tracked(line.quantity()))
                .unitPriceExclVat(tracked(line.unitPriceExclVat()))
                .vatRate(vatRate)
                .totalExclVat(tracked(line.totalExclVat()))
                .totalInclVat(tracked(line.totalInclVat()))
                .deliveryNoteNumber(line.deliveryNoteNumber())
                .productMatch(DraftLine.ProductMatch.builder()
                        .state(DraftLine.ProductMatch.State.NONE)
                        .build())
                .build();

        // Dopočty řádkových částek (dopředu i zpětně) — kód, ne model.
        if (kind == DraftLine.LineKind.ITEM) {
            deriveLineAmounts(draftLine);
        }
        return draftLine;
    }

    /**
     * Sazba řádku: procento opsané z dokladu (VERBATIM), písmenný kód přeložený
     * přes rekapitulaci (DERIVED), u dodacího listu default (DEFAULTED),
     * u faktury bez sazby ABSENT — flag k ruční revizi.
     */
    private TrackedField<Integer> resolveVatRate(DocumentExtractionResult.F rateOrCode,
                                                 List<ReceiptDraft.VatRecapRow> recap,
                                                 DraftLine.LineKind kind,
                                                 DocumentType documentType) {
        if (kind != DraftLine.LineKind.ITEM) {
            return TrackedField.absent();
        }
        if (rateOrCode != null && rateOrCode.value() != null
                && rateOrCode.state() != DocumentExtractionResult.SourceState.ABSENT) {
            String raw = rateOrCode.value().trim();
            Integer percent = parsePercent(raw);
            if (percent != null) {
                FieldState state = rateOrCode.state() == DocumentExtractionResult.SourceState.DERIVED
                        ? FieldState.DERIVED : FieldState.VERBATIM;
                return TrackedField.of(percent, state);
            }
            Integer fromRecap = lookupRecapRate(raw, recap);
            if (fromRecap != null) {
                return TrackedField.of(fromRecap, FieldState.DERIVED);
            }
        }
        if (documentType == DocumentType.DELIVERY_NOTE) {
            return TrackedField.defaulted(props.getDefaults().getVatRate());
        }
        return TrackedField.absent();
    }

    private Integer parsePercent(String raw) {
        String cleaned = raw.replace("%", "").trim();
        try {
            return Integer.valueOf(cleaned);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Integer lookupRecapRate(String code, List<ReceiptDraft.VatRecapRow> recap) {
        if (recap == null) return null;
        return recap.stream()
                .filter(r -> code.equalsIgnoreCase(r.getCode()))
                .map(ReceiptDraft.VatRecapRow::getRatePercent)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    // ------------------------------------------------------------------ misc

    private DraftSupplier mapSupplier(DocumentExtractionResult.Supplier s) {
        if (s == null) {
            return DraftSupplier.builder().matchState(DraftSupplier.MatchState.NONE).build();
        }
        return DraftSupplier.builder()
                .extracted(DraftSupplier.Extracted.builder()
                        .name(s.name())
                        .registrationNumber(s.registrationNumber())
                        .vatId(s.vatId())
                        .street(s.street())
                        .city(s.city())
                        .postalCode(s.postalCode())
                        .bankAccount(s.bankAccount())
                        .iban(s.iban())
                        .swift(s.swift())
                        .build())
                .matchState(DraftSupplier.MatchState.NONE)
                .build();
    }

    private List<ReceiptDraft.VatRecapRow> mapRecap(List<DocumentExtractionResult.VatRecapRow> recap) {
        if (recap == null) return new ArrayList<>();
        return recap.stream()
                .map(r -> ReceiptDraft.VatRecapRow.builder()
                        .code(r.code())
                        .ratePercent(r.ratePercent())
                        .base(r.base())
                        .vat(r.vat())
                        .build())
                .collect(ArrayList::new, ArrayList::add, ArrayList::addAll);
    }

    private List<DeliveryNoteRef> collectDeliveryNoteRefs(List<DraftLine> lines) {
        List<DeliveryNoteRef> refs = new ArrayList<>();
        for (DraftLine line : lines) {
            if (line.getLineKind() == DraftLine.LineKind.DELIVERY_NOTE_GROUP
                    && line.getDeliveryNoteNumber() != null) {
                refs.add(DeliveryNoteRef.builder()
                        .number(line.getDeliveryNoteNumber())
                        .totalInclVat(line.getTotalInclVat() == null
                                ? null : line.getTotalInclVat().getValue())
                        .build());
            }
        }
        return refs;
    }

    private BigDecimal sumLines(List<DraftLine> lines,
                                java.util.function.Function<DraftLine, TrackedField<BigDecimal>> field) {
        BigDecimal sum = BigDecimal.ZERO;
        boolean any = false;
        for (DraftLine line : lines) {
            if (line.getLineKind() != DraftLine.LineKind.ITEM) continue;
            TrackedField<BigDecimal> f = field.apply(line);
            if (f == null || f.getValue() == null) return null;   // neúplné řádky → nedopočítávat
            sum = sum.add(f.getValue());
            any = true;
        }
        return any ? sum : null;
    }

    // Extrakční tracked pole → draft TrackedField (VERBATIM/DERIVED/ABSENT).
    private TrackedField<String> tracked(DocumentExtractionResult.F f) {
        if (f == null || f.value() == null) return TrackedField.absent();
        return TrackedField.of(f.value(), toFieldState(f.state()));
    }

    private TrackedField<BigDecimal> tracked(DocumentExtractionResult.FDec f) {
        if (f == null || f.value() == null) return TrackedField.absent();
        return TrackedField.of(f.value(), toFieldState(f.state()));
    }

    private TrackedField<java.time.LocalDate> tracked(DocumentExtractionResult.FDate f) {
        if (f == null || f.value() == null) return TrackedField.absent();
        return TrackedField.of(f.value(), toFieldState(f.state()));
    }

    private FieldState toFieldState(DocumentExtractionResult.SourceState s) {
        return s == DocumentExtractionResult.SourceState.DERIVED
                ? FieldState.DERIVED : FieldState.VERBATIM;
    }
}
