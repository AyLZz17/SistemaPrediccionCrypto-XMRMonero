package com.aylzz.xmrforecast.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/** Documentacion OpenAPI de la API v1. */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI xmrForecastOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("XMR-Forecast API")
                        .version("1.0.0")
                        .description("""
                                Analisis predictivo de series de tiempo para Monero (XMR).

                                **Aviso legal.** Este servicio expone capacidad predictiva evaluada.
                                No es asesoria financiera, no promete rentabilidad y no simula
                                operaciones de trading ni backtesting.

                                Autenticacion: JWT propio emitido tras login tradicional o OAuth 2.0
                                con Google. Roles: VIEWER, ANALYST, ADMIN.
                                """)
                        .contact(new Contact().name("AyLZz Software Solutions"))
                        .license(new License().name("Propietario")))
                .servers(List.of(
                        new Server().url("https://localhost:8443").description("Desarrollo local (TLS)"),
                        new Server().url("https://api.xmr-forecast.local").description("Produccion")))
                .components(new Components().addSecuritySchemes("bearerAuth",
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("Access token emitido por /api/v1/auth/login")));
    }
}