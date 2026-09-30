package com.aylzz.xmrforecast.common;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Metadatos publicos del servicio.
 *
 * <p>{@code /api/v1/meta/disclaimer} estaba declarado como publico en la cadena de
 * seguridad desde el principio pero no existia como endpoint: una regla
 * {@code permitAll} que no apunta a nada es documentacion que miente. Este
 * controlador la convierte en una ruta real, de modo que el aviso legal de R-11
 * sea accesible sin sesion y la regla de seguridad tenga a que proteger algo.
 */
@Tag(name = "Metadatos")
@RestController
@RequestMapping("/api/v1/meta")
public class MetaController {

    private final String appName;
    private final String version;

    public MetaController(@Value("${info.app.name:XMR-Forecast}") String appName,
                          @Value("${info.app.version:1.0.0}") String version) {
        this.appName = appName;
        this.version = version;
    }

    @Operation(summary = "Aviso legal obligatorio",
            description = "Publica. R-11 exige que el aviso sea visible sin necesidad de sesion.")
    @ApiResponses(@ApiResponse(responseCode = "200", description = "Aviso legal"))
    @GetMapping("/disclaimer")
    public DisclaimerResponse disclaimer() {
        return new DisclaimerResponse(appName, version, DISCLAIMER);
    }

    @Operation(summary = "Estado del servicio y su version")
    @GetMapping("/info")
    public DisclaimerResponse info() {
        return new DisclaimerResponse(appName, version, DISCLAIMER);
    }

    /**
     * Texto del aviso. Se devuelve en la API porque R-11 lo exige tambien en la
     * documentacion de la API, no solo en la interfaz.
     */
    static final String DISCLAIMER =
            "XMR-Forecast analiza y estima el precio de cierre y la direccion de Monero (XMR) "
                    + "usando modelos evaluados de forma rigorosa. No ofrece asesoria financiera, "
                    + "no promete rentabilidad y no simula operaciones ni backtesting de trading. "
                    + "Toda cifra publicada es capacidad predictiva evaluada sobre datos historicos, "
                    + "no una recomendacion de compra o venta.";

    /** Aviso legal y metadatos del servicio. */
    public record DisclaimerResponse(String application, String version, String disclaimer) {
    }
}
