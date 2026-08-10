package cz.palo.autoservis.model.dto.pagination;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code PagedResponse.of} — příznaky {@code first}/{@code last} při 1-based stránkování (TD-50).
 *
 * <p>Čistý unit test bez Springu. {@code page} je 1-based (první stránka = 1), takže {@code first}
 * musí být true právě na stránce 1 a {@code last} právě na poslední. Testuje se celá matice
 * čtyřstránkového výsledku plus hraniční případy (jediná stránka, prázdný výsledek).
 */
class PagedResponseTest {

    @Test
    @DisplayName("první stránka ze čtyř: first=true, last=false")
    void firstOfFourPages() {
        PagedResponse<String> page = PagedResponse.of(List.of("a", "b", "c", "d", "e"), 1, 5, 20);

        assertThat(page.getTotalPages()).isEqualTo(4);
        assertThat(page.isFirst()).isTrue();
        assertThat(page.isLast()).isFalse();
    }

    @Test
    @DisplayName("poslední stránka ze čtyř: first=false, last=true")
    void lastOfFourPages() {
        PagedResponse<String> page = PagedResponse.of(List.of("a", "b", "c", "d", "e"), 4, 5, 20);

        assertThat(page.isFirst()).isFalse();
        assertThat(page.isLast()).isTrue();
    }

    @ParameterizedTest(name = "stránka {0} ze 4 → first={1}, last={2}")
    @CsvSource({
            "1, true,  false",
            "2, false, false",
            "3, false, false",   // předposlední NENÍ poslední (jádro TD-50)
            "4, false, true"
    })
    @DisplayName("matice příznaků napříč čtyřmi stránkami")
    void flagsAcrossPages(int page, boolean expectedFirst, boolean expectedLast) {
        PagedResponse<String> result = PagedResponse.of(List.of("x"), page, 5, 20);

        assertThat(result.isFirst()).as("first na stránce %d", page).isEqualTo(expectedFirst);
        assertThat(result.isLast()).as("last na stránce %d", page).isEqualTo(expectedLast);
    }

    @Test
    @DisplayName("jediná stránka je zároveň první i poslední")
    void singlePage_isFirstAndLast() {
        PagedResponse<String> page = PagedResponse.of(List.of("a", "b"), 1, 5, 2);

        assertThat(page.getTotalPages()).isEqualTo(1);
        assertThat(page.isFirst()).isTrue();
        assertThat(page.isLast()).isTrue();
    }

    @Test
    @DisplayName("prázdný výsledek: stránka 1 je první i poslední, totalPages 0")
    void emptyResult_firstAndLast() {
        PagedResponse<String> page = PagedResponse.of(List.of(), 1, 5, 0);

        assertThat(page.getTotalPages()).isZero();
        assertThat(page.isFirst()).isTrue();
        assertThat(page.isLast()).isTrue();
        assertThat(page.getContent()).isEmpty();
    }

    @Test
    @DisplayName("neúplná poslední stránka (23 prvků, 5 na stránku → 5 stránek)")
    void partialLastPage() {
        assertThat(PagedResponse.of(List.of("x"), 5, 5, 23).isLast()).isTrue();
        assertThat(PagedResponse.of(List.of("x"), 4, 5, 23).isLast()).isFalse();
        assertThat(PagedResponse.of(List.of("x"), 4, 5, 23).getTotalPages()).isEqualTo(5);
    }

    @Test
    @DisplayName("obálka nese předaný obsah i metadata beze změny")
    void carriesContentAndMetadata() {
        PagedResponse<String> page = PagedResponse.of(List.of("a", "b"), 2, 10, 42);

        assertThat(page.getContent()).containsExactly("a", "b");
        assertThat(page.getPage()).isEqualTo(2);
        assertThat(page.getPageSize()).isEqualTo(10);
        assertThat(page.getTotalElements()).isEqualTo(42);
        assertThat(page.getTotalPages()).isEqualTo(5);
    }
}
