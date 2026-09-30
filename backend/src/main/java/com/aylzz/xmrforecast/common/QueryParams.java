package com.aylzz.xmrforecast.common;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Locale;

/**
 * Normalizacion de parametros de consulta.
 *
 * <p>Se concentra aqui el parseo tolerante de fechas para que ningun endpoint
 * tenga que reinventarlo y para que el comportamiento sea uniforme: un cliente
 * puede enviar {@code 2026-06-01} o {@code 2026-06-01T00:00:00Z} y ambos
 * funcionan. Devolver 400 solo por un formato de fecha distinto al esperado es una
 * falta de usabilidad gratuita, no una medida de seguridad.
 */
public final class QueryParams {

    /** Tope por defecto de una pagina de listado. */
    public static final int DEFAULT_PAGE_SIZE = 20;

    /** Tope absoluto de elementos por pagina: evita que un cliente pida 1 000 000. */
    public static final int MAX_PAGE_SIZE = 200;

    private QueryParams() {
    }

    /**
     * Parsea una fecha de inicio. Acepta {@code YYYY-MM-DD} (medianoche UTC),
     * ISO-8601 con zona y epoch en milisegundos.
     */
    public static Instant start(String raw) {
        return instant(raw);
    }

    /**
     * Parsea una fecha de fin. Cuando el cliente envia solo {@code YYYY-MM-DD} se
     * toma el final de ese dia, de modo que {@code ?from=2026-06-01&to=2026-06-01}
     * devuelva la jornada completa en lugar de un unico instante.
     */
    public static Instant end(String raw) {
        Instant parsed = instant(raw);
        if (parsed == null) {
            return null;
        }
        return isPlainDate(raw) ? parsed.plusSeconds(86_399L) : parsed;
    }

    /** Parseo generico: devuelve {@code null} si la entrada es nula o vacia. */
    public static Instant instant(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        if (value.isEmpty()) {
            return null;
        }
        if (isPlainDate(value)) {
            // `2026-13-45` tiene forma de fecha pero no es una fecha. El parseo va
            // dentro del try: fuera de el, DateTimeParseException escaparia como
            // 500 en lugar del 400 que corresponde a una entrada invalida.
            try {
                return LocalDate.parse(value, java.time.format.DateTimeFormatter.ISO_LOCAL_DATE)
                        .atStartOfDay(ZoneOffset.UTC).toInstant();
            } catch (DateTimeParseException ignored) {
                // Se cae al mensaje de error comun de abajo.
            }
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException ignored) {
            // Se intenta el resto de formatos ISO antes de rendirse.
        }
        try {
            return java.time.OffsetDateTime.parse(value,
                    java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant();
        } catch (DateTimeParseException ignored) {
            // Continua: puede ser una fecha local sin zona.
        }
        try {
            return java.time.LocalDateTime.parse(value,
                    java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME).toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException ignored) {
            // Continua: puede ser epoch en milisegundos.
        }
        try {
            long epochMillis = Long.parseLong(value);
            // Se descarta cualquier valor que no sea una fecha plausible: evita
            // que un entero arbitrario se interprete como un instante.
            if (epochMillis > 0 && epochMillis < 4_102_444_800_000L) {
                return Instant.ofEpochMilli(epochMillis);
            }
        } catch (NumberFormatException ignored) {
            // No es un entero: se devuelve el error de abajo.
        }
        throw ApiException.badRequest("INVALID_PARAMETER",
                "El valor '" + value + "' no es una fecha valida. Use YYYY-MM-DD o una marca ISO-8601.");
    }

    /** Un identificador de ruta publicado como cadena se acepta tambien como numero. */
    public static Long id(String raw) {
        if (raw == null || raw.isBlank()) {
            throw ApiException.badRequest("INVALID_PARAMETER", "Falta el identificador del recurso.");
        }
        try {
            long value = Long.parseLong(raw.trim());
            if (value <= 0) {
                throw new NumberFormatException("id no positivo");
            }
            return value;
        } catch (NumberFormatException ex) {
            throw ApiException.badRequest("INVALID_ID",
                    "El identificador '" + raw + "' no tiene un formato valido.");
        }
    }

    /** Normaliza el numero de pagina: nunca negativo. */
    public static int page(Integer page) {
        return page == null || page < 0 ? 0 : page;
    }

    /**
     * Acota el tamano de pagina. Sin este tope, {@code ?size=1000000} permitiria
     * que un solo cliente saturase la base de datos (OWASP API4).
     */
    public static int size(Integer size, int max) {
        int requested = size == null || size <= 0 ? DEFAULT_PAGE_SIZE : size;
        return Math.min(requested, max);
    }

    /** Normaliza un simbolo: mayusculas, sin espacios. */
    public static String symbol(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            return "XMR-USD";
        }
        return symbol.trim().toUpperCase(Locale.ROOT);
    }

    private static boolean isPlainDate(String raw) {
        if (raw == null) {
            return false;
        }
        String value = raw.trim();
        return value.length() == 10 && value.charAt(4) == '-' && value.charAt(7) == '-';
    }
}
