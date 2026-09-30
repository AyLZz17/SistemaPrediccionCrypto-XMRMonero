package com.aylzz.xmrforecast.job;

import com.aylzz.xmrforecast.common.PageResponse;
import com.aylzz.xmrforecast.common.QueryParams;
import com.aylzz.xmrforecast.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
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

import java.util.Map;

/**
 * API de la cola de trabajos.
 *
 * <p>Seguridad por ruta (R-35): VIEWER+ ve sus propios trabajos; ADMIN ve la cola
 * completa y puede cancelar cualquiera. El listado de un VIEWER esta acotado a su
 * {@code created_by} en la propia consulta, no en un filtro posterior.
 */
@Tag(name = "Trabajos")
@RestController
@RequestMapping("/api/v1/jobs")
@Validated
@SecurityRequirement(name = "bearerAuth")
public class JobController {

    private final JobService service;

    public JobController(JobService service) {
        this.service = service;
    }

    @Operation(summary = "Lista los trabajos",
            description = "ADMIN ve la cola completa; el resto, solo los que encolo.")
    @GetMapping
    @PreAuthorize("hasAnyRole('VIEWER','ANALYST','ADMIN')")
    public PageResponse<JobService.JobResponse> list(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(200) int size,
            @AuthenticationPrincipal AuthenticatedUser user) {
        return service.list(user.id(), user.isAdmin(), page, size);
    }

    @Operation(summary = "Resumen de la cola por estado")
    @GetMapping("/summary")
    @PreAuthorize("hasAnyRole('VIEWER','ANALYST','ADMIN')")
    public JobService.QueueSummary summary(@AuthenticationPrincipal AuthenticatedUser user) {
        return service.summary(user.isAdmin(), user.id());
    }

    @Operation(summary = "Detalle de un trabajo",
            description = "Devuelve 404 si no le pertenece, para no revelar su existencia.")
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('VIEWER','ANALYST','ADMIN')")
    public JobService.JobResponse get(@PathVariable String id,
                                      @AuthenticationPrincipal AuthenticatedUser user) {
        return service.get(QueryParams.id(id), user.id(), user.isAdmin());
    }

    @Operation(summary = "Encola un trabajo",
            description = "Idempotente por `idempotencyKey`: repetirla devuelve el trabajo "
                    + "existente en lugar de encolar un segundo trabajo identico.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Trabajo encolado o ya existente"),
            @ApiResponse(responseCode = "400", description = "Tipo de trabajo desconocido")
    })
    @PostMapping
    @PreAuthorize("hasAnyRole('ANALYST','ADMIN')")
    public JobService.JobResponse enqueue(@Valid @RequestBody EnqueueRequest request,
                                          @AuthenticationPrincipal AuthenticatedUser user) {
        return service.enqueue(request.type(), request.idempotencyKey(), request.payload(),
                user.id(), user.role().name());
    }

    @Operation(summary = "Cancela un trabajo",
            description = "Solo se puede cancelar lo que aun no ha terminado.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Trabajo cancelado"),
            @ApiResponse(responseCode = "404", description = "No existe o no le pertenece"),
            @ApiResponse(responseCode = "409", description = "El trabajo ya habia terminado")
    })
    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAnyRole('VIEWER','ANALYST','ADMIN')")
    public JobService.JobResponse cancel(@PathVariable String id,
                                         @AuthenticationPrincipal AuthenticatedUser user) {
        return service.cancel(QueryParams.id(id), user.id(), user.isAdmin(), user.role().name());
    }

    /** Cuerpo de encolado. */
    public record EnqueueRequest(
            @NotBlank(message = "Debe indicar el tipo de trabajo.")
            @Size(max = 48) String type,
            @Size(max = 128) String idempotencyKey,
            Map<String, Object> payload
    ) {
    }
}
