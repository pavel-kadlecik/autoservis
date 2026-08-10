package cz.palo.autoservis.prod;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Garantuje kontrakt produkčního nasazení: čerstvá DB migrovaná v produkčním režimu
 * ({@code spring.flyway.locations = db/migration,db/prod}) je PRÁZDNÁ až na jednoho
 * admin uživatele + role. Demo data (db/demo) se sem vědomě nedostanou.
 *
 * <p>Nedědí z {@link cz.palo.autoservis.AbstractIntegrationTest} schválně — ten sdílí
 * singleton kontejner už namigrovaný se základními (dev) locations, tj. plný demo dat.
 * Tady potřebujeme VLASTNÍ čerstvý kontejner a produkční locations.
 */
@SpringBootTest(properties = {
        // Produkční režim migrací: schéma + produkční seed, BEZ db/demo. Inline vlastnosti
        // mají vyšší prioritu než application.yaml (list-override přes @DynamicPropertySource
        // se u spring.flyway.locations neprosadí spolehlivě).
        "spring.flyway.locations=classpath:db/migration,classpath:db/prod",
        // Libovolný platný BCrypt hash — jen se uloží; obsah pro tento test nerozhoduje.
        "spring.flyway.placeholders.admin_password_hash=$2a$12$RfJPRJHqbKmHRfJwQqJyVeCGtsiVTZG5b3uEYofjI0dtKI3Mc51ky"
})
@ActiveProfiles("test")   // dodá dummy jwt.secret + AI klíč (start kontextu bez env)
class ProdSeedIntegrationTest {

    static {
        // Docker-java API negotiation fix (viz AbstractIntegrationTest).
        System.setProperty("api.version", "1.47");
    }

    // Vlastní čerstvý kontejner (ne sdílený singleton) — migruje se produkčně.
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("autoservis")
                    .withUsername("test")
                    .withPassword("test");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",      POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private JdbcTemplate jdbc;

    private long count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }

    @Test
    void productionDatabaseIsEmptyExceptSingleAdmin() {
        // Právě jeden uživatel — admin.
        assertThat(count("security.users")).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT username FROM security.users", String.class))
                .isEqualTo("admin");

        // Pět rolí (parita s demo seedem) a admin má ROLE_ADMIN.
        assertThat(count("security.roles")).isEqualTo(5);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM security.user_roles ur "
                        + "JOIN security.users u ON u.id = ur.user_id "
                        + "JOIN security.roles r ON r.id = ur.role_id "
                        + "WHERE u.username = 'admin' AND r.name = 'ROLE_ADMIN'", Long.class))
                .isEqualTo(1);

        // Žádná demo data.
        assertThat(count("employee.employees")).isZero();
        assertThat(count("customer.customers")).isZero();
        assertThat(count("vehicle.vehicles")).isZero();
        assertThat(count("\"order\".orders")).isZero();
        assertThat(count("billing.invoices")).isZero();

        // Číslování zákazníků startuje od 1 → první zákazník bude ZNK-{rok}-0001.
        assertThat(jdbc.queryForObject(
                "SELECT nextval('customer.customer_number_seq')", Long.class))
                .isEqualTo(1);
    }
}
