package com.aylzz.xmrforecast.prediction;

import com.aylzz.xmrforecast.common.PageResponse;
import com.aylzz.xmrforecast.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * API de predicciones.
 *
 * <p>Seguridad: cada lectura y creacion esta acotada al usuario autenticado.
 * ADMIN puede leer las de cualquier usuario; el resto no tiene forma de hacerlo.
 */
@Tag(name = "Predicciones")
@RestController
@RequestMapping("/api/v1/predictions")
@Validated
@SecurityRequirement(name = "bearerAuth")
public class PredictionController {

    private final PredictionService service;

    public PredictionController(PredictionService service) {
        this.service = service;
    }

    @Operation(summary = "Solicita una prediccion",
            description = "Idempotente: repetir la misma combinacion de modelo, simbolo y "
                    + "fecha devuelve la prediccion existente en vez de crear otra.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Prediccion creada"),
            @ApiResponse(responseCode = "404", description = "La version de modelo no existe"),
            @ApiResponse(responseCode = "422", description = "El modelo no supero el gate de integridad"),
            @ApiResponse(responseCode = "503", description = "El servicio de ML no responde")
    })
    @PreAuthorize("hasAnyRole('ANALYST','ADMIN')")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PredictionService.PredictionResponse create(
            @RequestBody PredictionService.CreatePredictionRequest request,
            AuthenticatedUser user) {
        return service.create(request, user.id());
    }

    @Operation(summary = "Lista las predicciones del usuario autenticado")
    @GetMapping
    @PreAuthorize("hasAnyRole('VIEWER','ANALYST','ADMIN')")
    public PageResponse<PredictionService.PredictionResponse> list(
            @RequestParam(required = false) String symbol,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            AuthenticatedUser user) {
        return service.listForUser(user.id(), symbol, page, size);
    }

    @Operation(summary = "Detalle de una prediccion",
            description = "Devuelve 404 si la prediccion pertenece a otro usuario.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Prediccion"),
            @ApiResponse(responseCode = "404", description = "No existe o no le pertenece")
    })
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('VIEWER','ANALYST','ADMIN')")
    public PredictionService.PredictionResponse get(@PathVariable Long id,
                                                    AuthenticatedUser user) {
        return service.getOne(id, user.id(), user.isAdmin());
    }
}