package com.aylzz.xmrforecast.metrics;

import com.aylzz.xmrforecast.common.ApiException;
import com.aylzz.xmrforecast.common.QueryParams;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;

/**
 * API de metricas de evaluacion.
 *
 * <p>Seguridad por ruta (R-35): solo lectura, VIEWER o superior. Son resultados
 * de un experimento del proyecto, no datos de un usuario, asi que no hay filtro
 * por propietario que aplicar.
 *
 * <p>El endpoint de comparacion acepta elegir particion, pero el valor por
 * defecto es {@code VALIDATION}: es la unica particion que R-24 permite usar para
 * decidir, y ofrecer {@code TEST} como opcion principal invitaria a usarlo mal.
 */
@Tag(name = "Metricas")
@RestController
@RequestMapping("/api/v1/metrics")
@SecurityRequirement(name = "bearerAuth")
public class MetricsController {

    private final MetricsService service;

    public MetricsController(MetricsService service) {
        this.service = service;
    }

    @Operation(summary = "Compara los modelos de un experimento",
            description = "Una fila por modelo con MAE, RMSE, MAPE y acierto de direccion, "
                    + "todas calculadas sobre la misma corrida y la misma particion (R-05).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Filas de comparacion; puede estar vacia"),
            @ApiResponse(responseCode = "404", description = "El experimento no existe"),
            @ApiResponse(responseCode = "400", description = "Particion desconocida")
    })
    @GetMapping("/compare")
    @PreAuthorize("hasAnyRole('VIEWER','ANALYST','ADMIN')")
    public List<MetricsService.ComparisonRow> compare(
            @Parameter(description = "Experimento a comparar")
            @RequestParam String experimentId,
            @Parameter(description = "VALIDATION (por defecto) o TEST")
            @RequestParam(defaultValue = "VALIDATION") String split) {
        return service.compare(QueryParams.id(experimentId), parseSplit(split));
    }

    @Operation(summary = "Metricas agregadas de un experimento",
            description = "Validacion y prueba se devuelven separadas y nunca se mezclan (R-04).")
    @ApiResponses(@ApiResponse(responseCode = "404", description = "El experimento no existe"))
    @GetMapping("/experiments/{experimentId}")
    @PreAuthorize("hasAnyRole('VIEWER','ANALYST','ADMIN')")
    public MetricsService.ExperimentMetrics experimentMetrics(@PathVariable String experimentId) {
        return service.experimentMetrics(QueryParams.id(experimentId));
    }

    private static Metric.Split parseSplit(String raw) {
        try {
            return Metric.Split.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException ex) {
            throw ApiException.badRequest("INVALID_SPLIT",
                    "Particion desconocida: use TRAIN, VALIDATION o TEST.");
        }
    }
}
