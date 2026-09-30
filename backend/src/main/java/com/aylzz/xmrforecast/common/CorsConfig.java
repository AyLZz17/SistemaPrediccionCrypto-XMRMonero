package com.aylzz.xmrforecast.common;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * CORS se configura en {@code SecurityConfig} (single source of truth) mediante
 * un {@code CorsConfigurationSource}, no mediante {@code addCorsMappings}.
 *
 * <p>Se declara el bean vacio a proposito. Si el origen de la configuracion fuera
 * el dispatcher servlet del MVC y este filtro se quedara sin nada, Spring Boot
 * registraria su propio {@code /mappings} con comodines en los metodos y en las
 * cabeceras; ese bean es lo que impide que eso ocurra.
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        // Deliberadamente vacio: cualquier regla aqui duplicaria y podria divergir
        // de la configuracion de seguridad, que es la unica fuente de verdad.
    }
}