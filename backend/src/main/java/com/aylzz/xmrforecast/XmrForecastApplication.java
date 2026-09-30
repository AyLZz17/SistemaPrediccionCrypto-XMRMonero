package com.aylzz.xmrforecast;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * XMR-Forecast backend: monolito modular Spring Boot 3.2 / Java 21 (R-31, R-36).
 *
 * <p>Aviso legal (R-11): analisis predictivo de series de tiempo para Monero (XMR).
 * No es asesoria financiera, no promete rentabilidad y no simula operaciones de trading.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableCaching
@EnableAsync
@EnableScheduling
public class XmrForecastApplication {

    public static void main(String[] args) {
        SpringApplication.run(XmrForecastApplication.class, args);
    }
}