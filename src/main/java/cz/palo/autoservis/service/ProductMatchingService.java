package cz.palo.autoservis.service;

import cz.palo.autoservis.mapper.ProductMatchingMapper;
import cz.palo.autoservis.model.draft.DraftLine;
import cz.palo.autoservis.model.draft.ReceiptDraft;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Párovací kaskáda řádku dokladu na skladovou kartu:
 *
 * <ol>
 *   <li>přesná shoda v převodníku {@code supplier_products} → AUTO,</li>
 *   <li>shoda normalizovaného čísla dílu (vč. heuristiky odříznutí brand
 *       prefixu, např. "EL 871.180" → "871180") → SUGGESTED — potvrzuje člověk,</li>
 *   <li>pg_trgm podobnost názvu → SUGGESTED,</li>
 *   <li>nic → NONE (potvrzení založí nový produkt).</li>
 * </ol>
 *
 * <p>Prefix-parsing nikdy nevede na AUTO — špatně odhadnutý prefix smí stát
 * nejvýš jeden klik člověka navíc. Samoučení (upsert převodníku po potvrzení)
 * dělá review workflow, ne tahle služba.
 */
@Component
@RequiredArgsConstructor
public class ProductMatchingService {

    private static final double NAME_SIMILARITY_THRESHOLD = 0.45;
    private static final int NAME_SUGGESTION_LIMIT = 3;

    private final ProductMatchingMapper mapper;

    /** Doplní {@code productMatch} všem ITEM řádkům draftu (vyžaduje napárovaného dodavatele pro krok 1). */
    public void matchLines(ReceiptDraft draft) {
        Long supplierId = draft.getSupplier() == null
                ? null : draft.getSupplier().getMatchedSupplierId();
        for (DraftLine line : draft.getLines()) {
            if (line.getLineKind() != DraftLine.LineKind.ITEM) continue;
            // uživatelem potvrzenou volbu nikdy nepřepisovat
            if (line.getProductMatch() != null
                    && line.getProductMatch().getState() == DraftLine.ProductMatch.State.CONFIRMED) {
                continue;
            }
            line.setProductMatch(matchLine(supplierId, line));
        }
    }

    private DraftLine.ProductMatch matchLine(Long supplierId, DraftLine line) {
        String catalogNumber = line.getCatalogNumber() == null
                ? null : line.getCatalogNumber().getValue();
        String name = line.getName() == null ? null : line.getName().getValue();

        // 1) převodník dodavatele — jediný krok, kterému věříme automaticky
        if (supplierId != null && catalogNumber != null) {
            Optional<Long> exact = mapper.findProductIdBySupplierSku(supplierId, catalogNumber);
            if (exact.isPresent()) {
                return DraftLine.ProductMatch.builder()
                        .state(DraftLine.ProductMatch.State.AUTO)
                        .productId(exact.get())
                        .build();
            }
        }

        List<DraftLine.ProductMatch.Candidate> candidates = new ArrayList<>();

        // 2) normalizovaná čísla dílu (plné i bez brand prefixu)
        if (catalogNumber != null) {
            List<String> numbers = normalizedVariants(catalogNumber);
            // Guard: katalogové číslo složené jen ze separátorů (např. "-") dá prázdný seznam →
            // dotaz by skončil na `IN ()` (SQL syntax error). Přeskočíme na párování dle názvu (E6.6/№11).
            if (!numbers.isEmpty()) {
                for (ProductMatchingMapper.Candidate c : mapper.findByNormalizedNumbers(numbers)) {
                    candidates.add(candidate(c, "PART_NUMBER"));
                }
            }
        }

        // 3) podobnost názvu — jen doplněk, nikdy sama o sobě AUTO
        if (candidates.isEmpty() && name != null && !name.isBlank()) {
            for (ProductMatchingMapper.Candidate c : mapper.findByNameSimilarity(
                    name, NAME_SIMILARITY_THRESHOLD, NAME_SUGGESTION_LIMIT)) {
                candidates.add(candidate(c, "NAME_SIMILARITY"));
            }
        }

        if (candidates.isEmpty()) {
            return DraftLine.ProductMatch.builder()
                    .state(DraftLine.ProductMatch.State.NONE)
                    .build();
        }
        return DraftLine.ProductMatch.builder()
                .state(DraftLine.ProductMatch.State.SUGGESTED)
                .candidates(candidates)
                .build();
    }

    private DraftLine.ProductMatch.Candidate candidate(ProductMatchingMapper.Candidate c,
                                                       String reason) {
        return DraftLine.ProductMatch.Candidate.builder()
                .productId(c.productId())
                .reason(reason)
                .score(c.score())
                .label((c.sku() != null ? c.sku() + " · " : "") + c.name())
                .build();
    }

    /**
     * Normalizované varianty katalogového čísla: celé číslo a číslo bez
     * úvodního brand prefixu (2–4 velká písmena oddělená mezerou).
     */
    List<String> normalizedVariants(String catalogNumber) {
        Set<String> variants = new LinkedHashSet<>();
        variants.add(normalize(catalogNumber));
        String[] tokens = catalogNumber.trim().split("\\s+", 2);
        if (tokens.length == 2 && tokens[0].matches("[A-Za-z]{2,4}")) {
            variants.add(normalize(tokens[1]));
        }
        variants.remove("");
        return new ArrayList<>(variants);
    }

    private String normalize(String raw) {
        return raw == null ? "" : raw.toUpperCase().replaceAll("[ .\\-]", "");
    }
}
