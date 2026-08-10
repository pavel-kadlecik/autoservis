package cz.palo.autoservis.config.registry;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;

/**
 * HTTP klient pro API ARES (ares.gov.cz). Stejné stavební kameny jako
 * {@link VehicleRegistryClientConfig} — kvalifikovaný název beanu, aby oba
 * externí klienti koexistovali — ale bez API klíče: ARES je veřejný
 * a neautentizovaný.
 */
@Configuration
@EnableConfigurationProperties(AresProperties.class)
public class AresClientConfig {

    @Bean
    RestClient aresRestClient(AresProperties props) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(props.connectTimeout()).build());
        factory.setReadTimeout(props.readTimeout());
        return RestClient.builder()
                .baseUrl(props.baseUrl())
                .requestFactory(factory)
                .build();
    }

}
