package com.aylzz.xmrforecast.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/** Configuracion compartida de clientes HTTP salientes. */
@Configuration
public class RestClientConfig {

    /**
     * Cliente base del servicio ML. No se comparte estado entre llamadas, de modo
     * que un fallo de un servicio no contamina las peticiones de otros.
     */
    @Bean
    public RestClient.Builder restClientBuilder() {
        return RestClient.builder()
                .requestFactory(requestFactory());
    }

    private static org.springframework.http.client.ClientHttpRequestFactory requestFactory() {
        var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofSeconds(60));
        return factory;
    }
}