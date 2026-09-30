package com.aylzz.xmrforecast.mlmodel;

import com.aylzz.xmrforecast.common.PageResponse;
import com.aylzz.xmrforecast.common.QueryParams;
import com.aylzz.xmrforecast.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * API del catalogo de modelos y promocion de campeon.
 *
 * <p>Seguridad por ruta (R-35):
 * <ul>
 *   <li>Lectura del catalogo: cualquier sesion autenticada. No hay datos de
 *       usuario, asi que no aplica filtro por propietario.</li>
 *   <li>Promocion: solo ADMIN. El backend revalida los gates de R-28 con
 *       independencia de lo que la interfaz muestre u oculte el boton.</li>
 * </ul>
 */
@Tag(name = "Modelos")
@RestController
@RequestMapping("/api/v1/models")
@Validated
@SecurityRequirement(name = "bearerAuth")
public class ModelController {

    private final ModelService service;

    public ModelController(ModelService service) {
        this.service = service;
    }

    @Operation(summary = "Lista el catalogo de modelos")
    @GetMapping
    @PreAuthorize("hasAnyRole('VIEWER','ANALYST','ADMIN')")
    public PageResponse<ModelService.ModelResponse> list(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(200) int size) {
        return service.listModels(page, size);
    }

    @Operation(summary = "Lista las versiones de un modelo",
            description = "Ordenadas de mas reciente a mas antigua, con el digest del artefacto.")
    @ApiResponses(@ApiResponse(responseCode = "404", description = "El modelo no existe"))
    @GetMapping("/{modelId}/versions")
    @PreAuthorize("hasAnyRole('VIEWER','ANALYST','ADMIN')")
    public List<ModelService.ModelVersionResponse> versions(@PathVariable String modelId) {
        return service.listVersions(QueryParams.id(modelId));
    }

    @Operation(summary = "Promueve una version a campeon",
            description = "Solo ADMIN. Revalida integridad del artefacto, procedencia y que la "
                    + "seleccion no se haya hecho sobre el conjunto de prueba.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Version promovida"),
            @ApiResponse(responseCode = "403", description = "El rol no es ADMIN"),
            @ApiResponse(responseCode = "404", description = "El modelo o la version no existen"),
            @ApiResponse(responseCode = "422", description = "Fallo un gate de integridad o de procedencia")
    })
    @PostMapping("/{modelId}/promote")
    @PreAuthorize("hasRole('ADMIN')")
    public ModelService.ModelVersionResponse promote(@PathVariable String modelId,
                                                     @Valid @RequestBody PromoteRequest request,
                                                     @AuthenticationPrincipal AuthenticatedUser user) {
        return service.promote(QueryParams.id(modelId), QueryParams.id(request.versionId()),
                user.id(), user.role());
    }

    /** Cuerpo de promocion. */
    public record PromoteRequest(
            @NotNull(message = "Debe indicar la version a promover.") String versionId
    ) {
    }
}
