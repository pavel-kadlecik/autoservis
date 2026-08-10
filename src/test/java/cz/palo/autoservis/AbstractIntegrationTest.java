package cz.palo.autoservis;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Sdílená bázová třída všech integračních testů.
 *
 * <p>Používá Testcontainers <em>singleton container pattern</em>: PostgreSQL
 * kontejner se nastartuje jednou ve statickém initializeru a sdílí ho každá
 * testovací třída, která z báze dědí. Ryuk (reaper Testcontainers) kontejner
 * odstraní při ukončení JVM — explicitní stop() není potřeba.
 *
 * <p>Proč ne {@code @Testcontainers} + {@code @Container}? Ty anotace statický
 * kontejner zastaví po KAŽDÉ testovací třídě, takže druhá třída v běhu by
 * dostala mrtvý kontejner. Singleton vzor drží jeden kontejner (a díky
 * cachování Spring kontextu jeden aplikační kontext) po celý testovací běh.
 *
 * <p>Každý potomek proto potřebuje JEN Docker — žádnou lokální dev DB na :5433.
 */
@SpringBootTest
@ActiveProfiles("test")   // application-test.yaml: testovací jwt.secret + dummy AI klíč (E4.1)
public abstract class AbstractIntegrationTest {

    // Oprava vyjednávání verze API docker-java na tomto stroji — musí proběhnout
    // dřív, než se poprvé sáhne na kontejner (a tím na Docker klienta).
    static {
        System.setProperty("api.version", "1.47");
    }

    // Startuje jednou pro celý testovací běh (singleton vzor, viz Javadoc třídy).
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")  // stejná verze jako produkce
                    .withDatabaseName("autoservis")
                    .withUsername("test")
                    .withPassword("test");

    static {
        POSTGRES.start();
    }

    // Spring Boot o našem Docker kontejneru neví. Tento statický callback se volá
    // PŘED startem kontextu a dynamicky přepisuje datasource properties — Docker
    // přiděluje port náhodně. Flyway migrace pak proti kontejneru běží automaticky.
    @DynamicPropertySource
    static void overrideDatasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",      POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
}
