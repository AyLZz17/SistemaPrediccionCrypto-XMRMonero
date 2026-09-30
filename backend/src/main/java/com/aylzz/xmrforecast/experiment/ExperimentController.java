package com.aylzz.xmrforecast.experiment;

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
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
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

import java.util.List;

/**
 * API de experimentos y corridas.
 *
 * <p>Seguridad por ruta (R-35): lectura para VIEWER+, escritura y arranque de
 * corridas para ANALYST+. Un experimento no pertenece a un usuario concreto —
es un recurso del proyecto— asi que no aplica aislamiento horizontal por
propietario; quien puede crear puede ver todos.
 */
@Tag(name = "Experimentos")
@RestController
@RequestMapping("/api/v1/experiments")
@Validated
@SecurityRequirement(name = "bearerAuth")
public class ExperimentController {

    private final ExperimentService service;

    public ExperimentController(ExperimentService service) {
        this.service = service;
    }

    @Operation(summary = "Lista los experimentos")
    @GetMapping
    @PreAuthorize("hasAnyRole('VIEWER','ANALYST','ADMIN')")
    public PageResponse<ExperimentService.ExperimentResponse> list(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(200) int size) {
        return service.list(page, size);
    }

    @Operation(summary = "Detalle de un experimento")
    @ApiResponses(@ApiResponse(responseCode = "404", description = "No existe"))
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('VIEWER','ANALYST','ADMIN')")
    public ExperimentService.ExperimentResponse get(@PathVariable String id) {
        return service.get(QueryParams.id(id));
    }

    @Operation(summary = "Crea un experimento",
            description = "El codigo y la configuracion los genera el servidor: la particion "
                    + "cronologica 70/15/15 y las semillas son las del proyecto, no las del cliente.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Experimento creado"),
            @ApiResponse(responseCode = "400", description = "Nombre vacio"),
            @ApiResponse(responseCode = "422", description = "No hay ninguna version de dataset registrada")
    })
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ANALYST','ADMIN')")
    public ExperimentService.ExperimentResponse create(
            @Valid @RequestBody CreateExperimentBody body,
            @AuthenticationPrincipal AuthenticatedUser user) {
        return service.create(new ExperimentService.CreateExperimentRequest(
                body.name(), body.description(), body.hypothesis(),
                body.task() == null ? null : Experiment.TaskType.valueOf(body.task()),
                body.datasetId()), user.id(), user.role().name());
    }

    @Operation(summary = "Arranca una corrida del experimento",
            description = "Registra la corrida y encola el trabajo de entrenamiento. Es "
                    + "idempotente por (experimento, runKey).")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Corrida encolada"),
            @ApiResponse(responseCode = "400", description = "Menos de 5 semillas (R-08)"),
            @ApiResponse(responseCode = "409", description = "La corrida ya existe o el experimento esta cancelado")
    })
    @PostMapping("/{id}/runs")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ANALYST','ADMIN')")
    public ExperimentService.RunStartResponse startRun(@PathVariable String id,
                                                       @Valid @RequestBody(required = false) StartRunBody body,
                                                       @AuthenticationPrincipal AuthenticatedUser user) {
        return service.startRun(QueryParams.id(id),
                new ExperimentService.StartRunRequest(
                        body == null ? null : body.runKey(),
                        body == null ? null : body.seeds()),
                user.id(), user.role().name());
    }

    @Operation(summary = "Lista las corridas de un experimento")
    @GetMapping("/{id}/runs")
    @PreAuthorize("hasAnyRole('VIEWER','ANALYST','ADMIN')")
    public PageResponse<ExperimentService.RunResponse> listRuns(
            @PathVariable String id,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(200) int size) {
        return service.listRuns(QueryParams.id(id), page, size);
    }

    @Operation(summary = "Detalle de una corrida")
    @ApiResponses(@ApiResponse(responseCode = "404", description = "No existe o no es de este experimento"))
    @GetMapping("/{id}/runs/{runId}")
    @PreAuthorize("hasAnyRole('VIEWER','ANALYST','ADMIN')")
    public ExperimentService.RunResponse getRun(@PathVariable String id, @PathVariable String runId) {
        return service.getRun(QueryParams.id(id), QueryParams.id(runId));
    }

    /** Cuerpo de alta de experimento, ya validado. */
    public record CreateExperimentBody(
            @NotBlank(message = "El nombre del experimento es obligatorio.")
            @Size(max = 160, message = "El nombre no puede superar 160 caracteres.")
            String name,
            @Size(max = 2000) String description,
            @Size(max = 2000) String hypothesis,
            // Se restringe con un patron, no con el enum, para que un valor mal
            // escrito responda 400 VALIDATION_FAILED. Con `TaskType.valueOf` un
            // "regresion" en minusculas lanzaba IllegalArgumentException, que el
            // manejador global no traduce, y el cliente recibia 500 INTERNAL_ERROR
            // por un error de escritura suyo.
            @Pattern(regexp = "REGRESSION|DIRECTION",
                    message = "La tarea debe ser REGRESSION o DIRECTION.")
            String task,
            String datasetId
    ) {
    }

    /**
     * Cuerpo de arranque de corrida.
     *
     * <p>Las restricciones existian pero no se aplicaban: el controlador declaraba
     * {@code @RequestBody(required = false)} <strong>sin {@code @Valid}</strong>,
     * de modo que un {@code runKey} de 200 caracteres pasaba la validacion y
     * chocaba contra {@code experiment_runs.run_key VARCHAR(64)} con un 500.
     *
     * <p>Los elementos de {@code seeds} no llevan anotacion de tipo. La restriccion
     * que habia, {@code @Size} sobre un {@code Integer}, no es valida para ese tipo
     * ({@code @Size} solo admite {@code CharSequence}, {@code Collection}, {@code Map}
     * y arrays): Hibernate Validator lanza
     * {@code UnexpectedTypeException: HV000030: No validator could be found for
     * constraint 'Size' validating type 'Integer'} en la PRIMERA peticion que llega
     * con semillas. Nunca se habia visto porque la falta de {@code @Valid} hacia que
     * el validador no se ejecutara nunca. El tamano lo acota el servicio
     * ({@code MIN_SEEDS} / {@code MAX_SEEDS}, R-08) y cada elemento ya es un
     * {@code Integer}, asi que no admite nada fuera de rango.
     */
    public record StartRunBody(
            @Size(max = 64, message = "El runKey no puede superar 64 caracteres.")
            @Pattern(regexp = "[A-Za-z0-9._-]*",
                    message = "el runKey solo admite letras, digitos, punto, guion y guion bajo")
            String runKey,
            @Size(max = 32, message = "Se admiten como maximo 32 semillas.")
            List<@NotNull Integer> seeds
    ) {
    }
}
