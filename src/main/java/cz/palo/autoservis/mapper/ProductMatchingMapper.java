package cz.palo.autoservis.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * MyBatis mapper kaskády párování produktů (převodník dodavatele,
 * normalizovaná čísla dílů, trigramová podobnost názvu). SQL
 * v {@code ProductMatchingMapper.xml}.
 */
@Mapper
public interface ProductMatchingMapper {

    /** Kandidát párování pro kontrolní obrazovku. */
    record Candidate(Long productId, String sku, String name, Double score) {}

    /** Krok 1: přesná shoda v převodníku (supplier_id + supplier_sku). */
    Optional<Long> findProductIdBySupplierSku(@Param("supplierId") Long supplierId,
                                              @Param("supplierSku") String supplierSku);

    /**
     * Krok 2: shoda normalizovaného čísla dílu proti
     * products.part_number_normalized nebo normalizovanému sku.
     */
    List<Candidate> findByNormalizedNumbers(@Param("numbers") List<String> numbers);

    /** Krok 3: pg_trgm podobnost názvu (similarity > threshold), top N. */
    List<Candidate> findByNameSimilarity(@Param("name") String name,
                                         @Param("threshold") double threshold,
                                         @Param("limit") int limit);

    /** Samoučení: potvrzené párování → upsert převodníku. */
    void upsertSupplierProduct(@Param("supplierId") Long supplierId,
                               @Param("supplierSku") String supplierSku,
                               @Param("productId") Long productId,
                               @Param("nameSnapshot") String nameSnapshot,
                               @Param("lastUnitPriceExclVat") BigDecimal lastUnitPriceExclVat);
}
