package com.aylzz.xmrforecast.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Establece la correlacion de la peticion, la propaga en la respuesta y la deja
 * en el MDC para que logback emita los logs JSON con {@code request_id} y {@code trace_id}.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    private static final String MDC_REQUEST_ID = "request_id";
    private static final String MDC_TRACE_ID = "trace_id";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String requestId = RequestContext.sanitize(request.getHeader(RequestContext.HEADER));
        String traceId = RequestContext.sanitize(
                request.getHeader(RequestContext.TRACE_HEADER) != null
                        ? request.getHeader(RequestContext.TRACE_HEADER)
                        : UUID.randomUUID().toString());

        RequestContext.set(requestId, traceId);
        response.setHeader(RequestContext.HEADER, requestId);
        response.setHeader(RequestContext.TRACE_HEADER, traceId);
        MDC.put(MDC_REQUEST_ID, requestId);
        MDC.put(MDC_TRACE_ID, traceId);

        try {
            chain.doFilter(request, response);
        } finally {
            // Importante en pool de hilos: sin esto el request_id se filtra entre peticiones.
            MDC.remove(MDC_REQUEST_ID);
            MDC.remove(MDC_TRACE_ID);
            RequestContext.clear();
        }
    }
}