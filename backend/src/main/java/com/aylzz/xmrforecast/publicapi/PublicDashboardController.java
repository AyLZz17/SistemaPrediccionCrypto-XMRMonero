package com.aylzz.xmrforecast.publicapi;

import com.aylzz.xmrforecast.market.MarketService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Superficie publica del dashboard: la primera pantalla, accesible sin sesion.
 *
 * <p><strong>Reglas de esta superficie (R-26, R-35):</strong>
 * <ul>
 *   <li>Metodos {@code GET} exclusivamente: aqui no se crea ni se modifica nada.</li>
 *   <li>Sin {@code @PreAuthorize}: la autorizacion es {@code permitAll} explicito
 *       en {@code SecurityConfig} para <em>esta</em> ruta y solo para esta.</li>
 *   <li>Parametros validados en el servidor (simbolo acotado, limit acotado a
 *       {@value PublicDashboardService#MAX_PUBLIC_SERIES} velas).</li>
 *   <li>Sin datos de usuario, de predicciones, de experimentos internos ni de
 *       configuracion: no hay identificador sobre el que hacer IDOR porque no
 *       se acepta ninguno.</li>
 *   <li>Rate limit con cubo propio ({@code /api/v1/public}, ver
 *       {@code RateLimitFilter}) y cache en Redis para las lecturas.</li>
 * </ul>
 *
 * <p>El resto de la API sigue exigiendo sesion: ver {@code SecurityConfig},
 * donde {@code /api/v1/public/**} es la unica ruta nueva que se abre.
 */
@Tag(name = "Dashboard publico", description = "Lectura anonima para la primera pantalla")
@RestController
@RequestMapping("/api/v1/public")
@Validated
public class PublicDashboardController {

    private final PublicDashboardService service;

    public PublicDashboardController(PublicDashboardService service) {
        this.service = service;
    }

    @Operation(summary = "Resumen publico de XMR",
            description = "Ultimo cierre, variacion y momento de mercado. 404 si "
                    + "aun no hay datos ingeridos.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Resumen actual"),
            @ApiResponse(responseCode = "404", description = "Sin datos para el simbolo"),
            @ApiResponse(responseCode = "429", description = "Limite de peticiones anonimas")
    })
    @GetMapping("/summary")
    public PublicDashboardService.PublicSummary summary(
            @RequestParam(defaultValue = "XMR-USD") @Size(max = 32) String symbol) {
        return service.summary(symbol);
    }

    @Operation(summary = "Serie historica publica",
            description = "Hasta " + PublicDashboardService.MAX_PUBLIC_SERIES
                    + " velas en orden cronologico, para las graficas de la pantalla publica.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Velas (posiblemente vacias)"),
            @ApiResponse(responseCode = "400", description = "Parametro fuera de rango")
    })
    @GetMapping("/series")
    public List<MarketService.CandleResponse> series(
            @RequestParam(defaultValue = "XMR-USD") @Size(max = 32) String symbol,
            @RequestParam(defaultValue = "90") @Min(1) @Max(PublicDashboardService.MAX_PUBLIC_SERIES)
                    int limit) {
        return service.series(symbol, limit);
    }

    @Operation(summary = "Estado general de los modelos",
            description = "Catalogo sin identificadores internos: familia, tarea y si "
                    + "tiene version campeona.")
    @ApiResponses(@ApiResponse(responseCode = "200", description = "Catalogo publico"))
    @GetMapping("/models")
    public List<PublicDashboardService.PublicModelStatus> models() {
        return service.models();
    }

    @Operation(summary = "Metricas publicas del ultimo experimento completado",
            description = "VALIDATION y TEST agregados de la ultima corrida terminada. "
                    + "Sin corridas, responde available=false en lugar de inventar cifras.")
    @ApiResponses(@ApiResponse(responseCode = "200", description = "Metricas o ausencia declarada"))
    @GetMapping("/metrics")
    public PublicDashboardService.PublicMetrics metrics() {
        return service.metrics();
    }

    @Operation(summary = "Comparacion general de modelos",
            description = "Una fila por familia sobre la misma corrida y la particion "
                    + "VALIDATION (R-05, R-24). Puede estar vacia.")
    @ApiResponses(@ApiResponse(responseCode = "200", description = "Filas de comparacion"))
    @GetMapping("/comparison")
    public List<PublicDashboardService.PublicComparisonRow> comparison() {
        return service.comparison();
    }

    @Operation(summary = "Frescura de los datos y estado del sistema",
            description = "Ultima ingesta, numero de velas, catalogo de modelos, ultimo "
                    + "experimento terminado y version de los documentos legales.")
    @ApiResponses(@ApiResponse(responseCode = "200", description = "Estado actual"))
    @GetMapping("/status")
    public PublicDashboardService.PublicStatus status() {
        return service.status();
    }
}
