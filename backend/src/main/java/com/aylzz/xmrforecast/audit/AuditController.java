package com.aylzz.xmrforecast.audit;

import com.aylzz.xmrforecast.common.Ids;
import com.aylzz.xmrforecast.common.PageResponse;
import com.aylzz.xmrforecast.common.QueryParams;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * API de consulta de auditoria.
 *
 * <p>Seguridad por ruta (R-35): solo ADMIN. El registro de auditoria revela que
 * cuentas existen, que recursos se han tocado y desde que direccion IP, asi que
 * no es un recurso que pueda leer cualquier sesion.
 *
 * <p>El detalle de cada evento pasa por el mismo saneado que usa la escritura, de
 * modo que un valor que se colara en la base de datos no sale por la API.
 */
@Tag(name = "Auditoria")
@RestController
@RequestMapping("/api/v1/audit")
@Validated
@SecurityRequirement(name = "bearerAuth")
@PreAuthorize("hasRole('ADMIN')")
public class AuditController {

    private static final int MAX_PAGE_SIZE = 200;

    private final AuditEventRepository repository;

    public AuditController(AuditEventRepository repository) {
        this.repository = repository;
    }

    @Operation(summary = "Lista los eventos de auditoria",
            description = "Append-only, de mas reciente a mas antigua. Solo ADMIN.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Pagina de eventos"),
            @ApiResponse(responseCode = "403", description = "El rol no es ADMIN")
    })
    @GetMapping
    public PageResponse<AuditEntryResponse> list(
            @Parameter(description = "Filtra por un actor concreto (id de usuario)")
            @RequestParam(required = false) String actorId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size) {
        var pageable = PageRequest.of(QueryParams.page(page), QueryParams.size(size, MAX_PAGE_SIZE));
        var result = (actorId == null || actorId.isBlank())
                ? repository.findAllByOrderByCreatedAtDesc(pageable)
                : repository.findAllByActorUserIdOrderByCreatedAtDesc(
                        QueryParams.id(actorId), pageable);
        return PageResponse.of(result, AuditEntryResponse::from);
    }

    /** Evento de auditoria publicado, con el detalle ya saneado. */
    public record AuditEntryResponse(
            String id,
            Instant timestamp,
            String actorId,
            String actorRole,
            String action,
            String resourceType,
            String resourceId,
            String outcome,
            String ipAddress,
            String requestId,
            String traceId,
            String detail
    ) {
        static AuditEntryResponse from(AuditEvent event) {
            return new AuditEntryResponse(
                    Ids.of(event.getId()),
                    event.getCreatedAt(),
                    Ids.of(event.getActorUserId()),
                    event.getActorRole(),
                    event.getAction(),
                    event.getResourceType(),
                    event.getResourceId(),
                    event.getOutcome().name(),
                    event.getIpAddress(),
                    event.getRequestId(),
                    event.getTraceId(),
                    renderDetail(event.getDetails()));
        }

        /**
         * El detalle se aplana a texto antes de publicarse. Es una medida de
         * defensa en profundidad: la API no expone estructura JSON libre, que es
         * la via habitual por la que un dato sensible acaba en una respuesta.
         */
        private static String renderDetail(Map<String, Object> details) {
            Map<String, Object> safe = AuditService.sanitize(details);
            if (safe == null || safe.isEmpty()) {
                return null;
            }
            StringBuilder text = new StringBuilder();
            safe.forEach((key, value) -> {
                if (text.length() > 0) {
                    text.append("; ");
                }
                text.append(key).append('=').append(value);
            });
            String rendered = text.toString();
            return rendered.length() <= 500 ? rendered : rendered.substring(0, 500) + "...";
        }
    }
}
