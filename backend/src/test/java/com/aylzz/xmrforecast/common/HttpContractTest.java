package com.aylzz.xmrforecast.common;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verificacion del contrato HTTP compartido entre el frontend y el backend (R-38).
 *
 * <p>Por que existe: durante la construccion del sistema, 21 de los 34 endpoints
 * que el cliente podia invocar no existian, y siete mas tenian un contrato
 * distinto (el backend publicaba {@code roles[]} donde el cliente leia
 * {@code role}, y {@code close} donde leia {@code price}). Nada de eso falla al
 * compilar: son dos relaciones correctas por separado que no encajan. La regla
 * del proyecto es que los contratos se <strong>verifican</strong>, no se
 * suponen, y este test es la verificacion.
 *
 * <p>Extrae las rutas de ambos lados del codigo fuente y exige que cada ruta que
 * el cliente puede emitir exista en el backend con el mismo metodo HTTP.
 */
class HttpContractTest {

    private static final Path CONTROLLER_ROOT = Path.of("src", "main", "java", "com",
            "aylzz", "xmrforecast");
    private static final Path FRONTEND_API_ROOT = Path.of("..", "frontend", "src", "api");

    private static Set<String> backendRoutes;
    private static Map<String, String> frontendCalls;

    @BeforeAll
    static void extractBothSides() throws IOException {
        backendRoutes = readBackendRoutes();
        frontendCalls = readFrontendCalls();
    }

    @Test
    @DisplayName("el repositorio contiene las dos caras del contrato")
    void bothSidesExist() {
        assertThat(Files.isDirectory(CONTROLLER_ROOT))
                .as("No se encontro el arbol de controladores en %s", CONTROLLER_ROOT.toAbsolutePath())
                .isTrue();
        assertThat(Files.isDirectory(FRONTEND_API_ROOT))
                .as("No se encontro el cliente en %s. El contrato no se puede verificar "
                        + "contra un cliente ausente.", FRONTEND_API_ROOT.toAbsolutePath())
                .isTrue();
    }

    @Test
    @DisplayName("toda ruta que el cliente emite existe en el backend")
    void everyFrontendCallHasABackendRoute() {
        Set<String> missing = new TreeSet<>();
        frontendCalls.forEach((route, origin) -> {
            if (!backendRoutes.contains(route)) {
                missing.add(route + "   (" + origin + ")");
            }
        });
        assertThat(missing)
                .as("""
                        Rutas que el frontend puede emitir y el backend no implementa.
                        Sin este test, estas 422/404 solo aparecen en produccion.
                        """)
                .isEmpty();
    }

    @Test
    @DisplayName("la extraccion encuentra rutas en ambos lados (el test no pasa por vacuidad)")
    void theExtractorActuallyFindsRoutes() {
        // Un extractor roto haria que el test anterior pasara siempre. Este test
        // falla si el Patron deja de encontrar rutas.
        assertThat(backendRoutes).hasSizeGreaterThanOrEqualTo(30);
        assertThat(backendRoutes).contains("GET /api/v1/market/latest");
        assertThat(frontendCalls).hasSizeGreaterThanOrEqualTo(25);
        assertThat(frontendCalls.keySet()).contains("GET /api/v1/predictions");
    }

    @Test
    @DisplayName("el backend no expone ninguna ruta bajo /api/v1 que el controlador no declare")
    void allApiRoutesAreUnderTheVersionedPrefix() {
        // Una ruta sin prefijo /api/v1 seeria incompatible con la regla de
        // versionado y no la encontraria el cliente.
        assertThat(backendRoutes)
                .allSatisfy(route -> assertThat(route).contains(" /api/v1/"));
    }

    // ------------------------------------------------------------ extraccion

    private static final Pattern CLASS_MAPPING =
            Pattern.compile("@RequestMapping\\(\\s*(?:value\\s*=\\s*)?\"([^\"]+)\"");
    private static final Pattern REST_CONTROLLER = Pattern.compile("@RestController");
    /**
     * Mapeo de metodo. La ruta es opcional a proposito: `@GetMapping` sin
     * argumentos es igual de valido que `@GetMapping("/latest")`, y exigir las
     * comillas hacia que el extractor ignorara en silencio media API.
     */
    private static final Pattern API_DOC_MAPPING = Pattern.compile(
            "@(Get|Post|Put|Patch|Delete)Mapping\\b\\s*(?:\\(\\s*(?:value\\s*=\\s*)?\"([^\"]*)\"[^)]*\\))?");

    private static Set<String> readBackendRoutes() throws IOException {
        Set<String> routes = new LinkedHashSet<>();
        List<Path> files;
        try (Stream<Path> walk = Files.walk(CONTROLLER_ROOT)) {
            files = walk.filter(path -> path.toString().endsWith("Controller.java")).toList();
        }
        for (Path file : files) {
            String source = Files.readString(file, StandardCharsets.UTF_8);
            if (!REST_CONTROLLER.matcher(source).find()) {
                continue;
            }
            Matcher classMatcher = CLASS_MAPPING.matcher(source);
            String base = classMatcher.find() ? classMatcher.group(1) : "";
            Matcher methodMatcher = API_DOC_MAPPING.matcher(source);
            while (methodMatcher.find()) {
                String method = methodMatcher.group(1).toUpperCase();
                String suffix = methodMatcher.group(2) == null ? "" : methodMatcher.group(2);
                routes.add(method + " " + join(base, suffix));
            }        }
        return routes;
    }

    private static final Pattern CONST_ASSIGNMENT =
            Pattern.compile("const\\s+([A-Z_0-9]+)\\s*=\\s*'([^']*)'");
    /** Punto de entrada de una peticion: el cliente HTTP y la navegacion del navegador. */
    private static final Pattern TRANSPORT_CALL = Pattern.compile(
            "\\b(apiRequest|rawRequest|absoluteUrl)(?:<[^<>]*>)?\\s*\\(");
    private static final Pattern STRING_LITERAL = Pattern.compile("'([^']*)'|`([^`]*)`");
    private static final Pattern METHOD_OPTION = Pattern.compile("method\\s*:\\s*'([A-Z]+)'");
    private static final Pattern TEMPLATE_PARAM = Pattern.compile("\\$\\{([^}]+)}");

    private static Map<String, String> readFrontendCalls() throws IOException {
        Map<String, String> calls = new LinkedHashMap<>();
        List<Path> files;
        try (Stream<Path> walk = Files.walk(FRONTEND_API_ROOT)) {
            files = walk.filter(path -> path.toString().endsWith(".ts")).toList();
        }
        for (Path file : files) {
            String source = Files.readString(file, StandardCharsets.UTF_8);
            Map<String, String> constants = readConstants(source);
            String origin = file.getFileName().toString();

            Matcher call = TRANSPORT_CALL.matcher(source);
            while (call.find()) {
                int open = call.end() - 1;
                String arguments = balancedArguments(source, open);
                if (arguments == null) {
                    continue;
                }
                String path = firstPathArgument(arguments, constants);
                if (path == null || !path.startsWith("/api/v1")) {
                    continue;
                }
                Matcher method = METHOD_OPTION.matcher(arguments);
                // Sin `method` explícito el transporte usa GET, que es el default
                // de fetch y del wrapper del cliente.
                String verb = method.find() ? method.group(1) : "GET";
                calls.putIfAbsent(verb + " " + path, origin);
            }
        }
        return calls;
    }

    private static Map<String, String> readConstants(String source) {
        Map<String, String> constants = new LinkedHashMap<>();
        Matcher matcher = CONST_ASSIGNMENT.matcher(source);
        while (matcher.find()) {
            constants.put(matcher.group(1), matcher.group(2));
        }
        return constants;
    }

    /**
     * Devuelve el texto de los argumentos de una llamada, desde el parentesis que
     * abre hasta el que la cierra. Sin esto, las opciones de una llamada se
     * mezclarian con las de la siguiente y el metodo HTTP se leeria del sitio
     * equivocado.
     */
    private static String balancedArguments(String source, int openIndex) {
        int depth = 0;
        for (int index = openIndex; index < source.length(); index++) {
            char current = source.charAt(index);
            if (current == '(' || current == '{' || current == '[') {
                depth++;
            } else if (current == ')' || current == '}' || current == ']') {
                depth--;
                if (depth == 0) {
                    return source.substring(openIndex + 1, index);
                }
            }
        }
        return null;
    }

    /** Primer literal de cadena de los argumentos que parezca una ruta de la API. */
    private static String firstPathArgument(String arguments, Map<String, String> constants) {
        Matcher literal = STRING_LITERAL.matcher(arguments);
        while (literal.find()) {
            String raw = literal.group(1) != null ? literal.group(1) : literal.group(2);
            String resolved = resolve(raw, constants);
            if (resolved.startsWith("/api/v1")) {
                return stripQuery(resolved);
            }
        }
        return null;
    }

    /**
     * Sustituye las constantes de prefijo (`${BASE}/predictions` -> `/api/v1/predictions`)
     * y normaliza los parametros de ruta a `{}` (`${id}` -> `{}`), de modo que la
     * comparacion con el backend no dependa de nombres de variable.
     */
    private static String resolve(String template, Map<String, String> constants) {
        String result = template;
        for (int guard = 0; guard < 5 && result.contains("${"); guard++) {
            StringBuilder out = new StringBuilder();
            Matcher matcher = TEMPLATE_PARAM.matcher(result);
            int last = 0;
            boolean replaced = false;
            while (matcher.find()) {
                out.append(result, last, matcher.start());
                String constant = constants.get(matcher.group(1));
                out.append(constant == null ? "{}" : constant);
                last = matcher.end();
                replaced = true;
            }
            out.append(result.substring(last));
            String next = out.toString();
            if (!replaced || next.equals(result)) {
                result = next;
                break;
            }
            result = next;
        }
        return result;
    }

    private static String stripQuery(String path) {
        int query = path.indexOf('?');
        String value = query >= 0 ? path.substring(0, query) : path;
        return value.replaceAll("/+", "/");
    }
    /**
     * Une el prefijo de clase con el sufijo de metodo y normaliza los parametros
     * de ruta a {@code {}}. Los dos lados nombran sus variables distinto
     * ({@code @PathVariable Long id} frente a {@code ${id}}), pero un parametro es
     * un parametro: comparar por posicion es lo que verifica el contrato.
     */
    private static String join(String base, String suffix) {
        String prefix = base == null ? "" : base.trim();
        String tail = suffix == null ? "" : suffix.trim();
        String joined;
        if (tail.isEmpty()) {
            joined = prefix;
        } else if (prefix.isEmpty()) {
            joined = tail;
        } else {
            joined = prefix.endsWith("/") && tail.startsWith("/")
                    ? prefix.substring(0, prefix.length() - 1) + tail
                    : prefix + (tail.startsWith("/") ? "" : "/") + tail;
        }
        return joined.replaceAll("/+", "/").replaceAll("\\{[^}/]+}", "{}");
    }
}
