package com.aylzz.xmrforecast.common;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * CORS se configura en {@code SecurityConfig} (single source of truth).
 * Esta clase existe solo para dejar constancia de que el MVC no debe relajar
 * los origenes permitidos mas alla de lo declarado alli.
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        // Deliberadamente vacio: cualquier regla aqui duplicaria y podria divergir
        // de la configuracion de seguridad, que es la unica fuente de verdad.
    }
}