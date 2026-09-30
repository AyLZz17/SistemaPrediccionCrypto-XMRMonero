package com.aylzz.xmrforecast.config;

import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/** Configuracion compartida de clientes HTTP salientes. */
@Configuration
public class RestClientConfig {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(60);

    /**
     * Cliente base del servicio ML y de Google OAuth.
     *
     * <p><strong>Ambito prototype, no singleton.</strong> Spring Boot declara su
     * propio bean {@code RestClient.Builder} con
     * {@code @Scope(SCOPE_PROTOTYPE)} y su documentacion dice que es
     * "strongly advised to inject it in your components" precisamente para que
     * cada punto de inyeccion reciba un builder nuevo
     * (<a href="https://docs.spring.io/spring-boot/reference/io/rest-client.html">
     * Spring Boot Reference, "RestClient"</a>). El builder es <em>mutable</em>:
     * {@code baseUrl(...)} y {@code requestFactory(...)} modifican el mismo
     * objeto.
     *
     * <p>Como singleton, los dos clientes compartian estado. Si el cliente del
     * servicio ML se construia primero, su {@code baseUrl} (ml-service:8443) y sus
     * timeouts se filtraban al cliente de Google. Hoy el efecto es invisible
     * porque Google se llama con una URI absoluta, que tiene prioridad sobre la
     * base; pero es una dependencia de una sutileza de Spring, no una garantia,
     * y el proximo cliente que se anada hereda el defecto.
     */
    @Bean
    @Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
    public RestClient.Builder restClientBuilder() {
        return RestClient.builder()
                .requestFactory(requestFactory());
    }

    private static ClientHttpRequestFactory requestFactory() {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT);
        factory.setReadTimeout(READ_TIMEOUT);
        return factory;
    }
}
