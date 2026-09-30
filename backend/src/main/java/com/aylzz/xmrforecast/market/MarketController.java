package com.aylzz.xmrforecast.market;

import com.aylzz.xmrforecast.common.PageResponse;
import com.aylzz.xmrforecast.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/**
 * API de mercado. Solo lectura, requiere sesion con rol VIEWER o superior
 * (permiso {@code market:read}, el mas basico del sistema).
 */
@Tag(name = "Mercado")
@RestController
@RequestMapping("/api/v1/market")
@Validated
@SecurityRequirement(name = "bearerAuth")
public class MarketController {

    private final MarketService service;

    public MarketController(MarketService service) {
        this.service = service;
    }

    @Operation(summary = "Ultima vela de un simbolo")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Vela mas reciente"),
            @ApiResponse(responseCode = "404", description = "Sin datos para el simbolo")
    })
    @PreAuthorize("hasAnyRole('VIEWER','ANALYST','ADMIN')")
    @GetMapping("/latest")
    public MarketService.MarketDataResponse latest(
            @Parameter(description = "Simbolo, por defecto XMR-USD")
            @RequestParam(defaultValue = "XMR-USD") String symbol) {
        return service.latest(symbol);
    }

    @Operation(summary = "Velas en un rango temporal, paginadas")
    @PreAuthorize("hasAnyRole('VIEWER','ANALYST','ADMIN')")
    @GetMapping("/candles")
    public PageResponse<MarketService.MarketDataResponse> candles(
            @RequestParam(defaultValue = "XMR-USD") String symbol,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(1000) int size) {
        return service.range(symbol, from, to, page, size);
    }

    @Operation(summary = "Serie completa en orden cronologico ascendente")
    @PreAuthorize("hasAnyRole('VIEWER','ANALYST','ADMIN')")
    @GetMapping("/series")
    public List<MarketService.MarketDataResponse> series(
            @RequestParam(defaultValue = "XMR-USD") String symbol,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "365") @Min(1) @Max(2000) int limit) {
        return service.series(symbol, from, to, limit);
    }
}