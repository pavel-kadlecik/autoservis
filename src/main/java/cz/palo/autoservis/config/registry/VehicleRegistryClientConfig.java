package cz.palo.autoservis.config.registry;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;

/**
 * HTTP klient pro API dataovozidlech.cz (Datová kostka RSV).
 *
 * <p>První externí REST klient v projektu (Spring AI má vlastní).
 * Bean má kvalifikovaný název ({@code vehicleRegistryRestClient}), aby s ním
 * budoucí klienti dalších služeb mohli koexistovat bez nejednoznačnosti.
 *
 * <p>API klíč cestuje v hlavičce {@code API_KEY} každého requestu; timeouty
 * jsou záměrně krátké — registr se volá synchronně z uživatelských requestů
 * a pomalý registr je nesmí blokovat dlouho.
 */
@Configuration
@EnableConfigurationProperties(VehicleRegistryProperties.class)
public class VehicleRegistryClientConfig {

    @Bean
    RestClient vehicleRegistryRestClient(VehicleRegistryProperties props) {
        // Holé spring-web stavební kameny nad JDK HttpClient — Boot 4 rozděluje
        // auto-konfiguraci RestClient.Builder a ClientHttpRequestFactorySettings
        // do samostatných modulů, které nejsou na classpath, a tenhle klient
        // nepotřebuje ani jedno: statický builder s explicitní factory stačí na vše.
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(props.connectTimeout()).build());
        factory.setReadTimeout(props.readTimeout());
        return RestClient.builder()
                .baseUrl(props.baseUrl())
                .defaultHeader("API_KEY", props.apiKey())
                .requestFactory(factory)
                .build();
    }

}
