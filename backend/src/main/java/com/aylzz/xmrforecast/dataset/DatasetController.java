package com.aylzz.xmrforecast.dataset;

import com.aylzz.xmrforecast.common.PageResponse;
import com.aylzz.xmrforecast.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
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
 * API de versiones de dataset.
 *
 * <p>Seguridad por ruta (R-35): lectura para cualquier sesion (VIEWER+), escritura
 * solo para ANALYST. No hay datos de usuario en el recurso: un dataset es del
 * proyecto, no de una persona, de modo que no aplica filtro por propietario.
 */
@Tag(name = "Datasets")
@RestController
@RequestMapping("/api/v1/datasets")
@Validated
@SecurityRequirement(name = "bearerAuth")
public class DatasetController {

    private final DatasetService service;

    public DatasetController(DatasetService service) {
        this.service = service;
    }

    @Operation(summary = "Lista las versiones de dataset registradas",
            description = "Ordenadas de mas reciente a mas antigua.")
    @GetMapping
    @PreAuthorize("hasAnyRole('VIEWER','ANALYST','ADMIN')")
    public PageResponse<DatasetService.DatasetResponse> list(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(200) int size) {
        return service.list(page, size);
    }

    @Operation(summary = "Detalle de una version de dataset")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Version de dataset"),
            @ApiResponse(responseCode = "404", description = "No existe")
    })
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('VIEWER','ANALYST','ADMIN')")
    public DatasetService.DatasetResponse get(@PathVariable String id) {
        return service.get(com.aylzz.xmrforecast.common.QueryParams.id(id));
    }

    @Operation(summary = "Registra una version de dataset",
            description = "El checksum lo calcula el servidor a partir de los metadatos "
                    + "declarados; nunca se acepta el que envia el cliente.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Version registrada"),
            @ApiResponse(responseCode = "400", description = "Rango de fechas incoherente"),
            @ApiResponse(responseCode = "409", description = "La version ya existe")
    })
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ANALYST','ADMIN')")
    public DatasetService.DatasetResponse create(
            @Valid @RequestBody DatasetService.CreateDatasetRequest request,
            @AuthenticationPrincipal AuthenticatedUser user) {
        return service.register(request, user.id());
    }
}
