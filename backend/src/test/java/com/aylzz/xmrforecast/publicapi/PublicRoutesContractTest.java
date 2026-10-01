package com.aylzz.xmrforecast.publicapi;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contrato de seguridad de la superficie publica (R-26, R-41).
 *
 * <p><strong>Por que se verifica sobre el fuente.</strong> Abrir
 * {@code /api/v1/public/**} a anonimos es exactamente el tipo de cambio que no
 * falla al compilar: si manana aparece un {@code POST} en este controlador, o si
 * alguien reordena los {@code requestMatchers} de {@code SecurityConfig} y la
 * ruta publica cae despues de {@code /api/v1/**").authenticated()}, el sistema
 * sigue compilando y la suite de unidades sigue en verde. El E2E
 * ({@code tools/verify-stack.ps1}) lo comprueba contra el sistema levantado;
 * aqui se fija la forma del codigo para que la revision lo vea en el PR.
 */
class PublicRoutesContractTest {

    private static final Path MAIN_ROOT = Path.of("src", "main", "java", "com",
            "aylzz", "xmrforecast");
    private static final Path SECURITY_CONFIG = MAIN_ROOT.resolve("config")
            .resolve("SecurityConfig.java");
    private static final Path CONTROLLER = MAIN_ROOT.resolve("publicapi")
            .resolve("PublicDashboardController.java");
    private static final Path SERVICE = MAIN_ROOT.resolve("publicapi")
            .resolve("PublicDashboardService.java");
    private static final Path RATE_LIMIT = MAIN_ROOT.resolve("security")
            .resolve("RateLimitFilter.java");

    private static String securityConfig;
    private static String controller;
    private static String service;
    private static String rateLimit;

    @BeforeAll
    static void readSources() throws IOException {
        securityConfig = Files.readString(SECURITY_CONFIG, StandardCharsets.UTF_8);
        controller = Files.readString(CONTROLLER, StandardCharsets.UTF_8);
        service = Files.readString(SERVICE, StandardCharsets.UTF_8);
        rateLimit = Files.readString(RATE_LIMIT, StandardCharsets.UTF_8);
    }

    /**
     * Rutas declaradas dentro de un {@code requestMatchers(...).permitAll()}.
     *
     * <p>Se eliminan primero los comentarios de linea completa: el bloque mas
     * largo lleva comentarios con parentesis — {@code (el usuario aun no tiene
     * sesion)} — que cortarian cualquier agrupacion basada en {@code [^)]*}.
     * Los comentarios de linea completa no se tocan cuando van detras de codigo,
     * porque una URL {@code https://...} dentro de un literal se partiria.
     */
    private static List<String> permitAllRoutes(String source) {
        String sinComentarios = source.replaceAll("(?m)^\\s*//.*$", "");
        List<String> routes = new ArrayList<>();
        Matcher matchers = Pattern
                .compile("\\.requestMatchers\\(([^)]*)\\)\\s*\\.permitAll\\(\\)")
                .matcher(sinComentarios);
        while (matchers.find()) {
            Matcher literals = Pattern.compile("\"([^\"]+)\"").matcher(matchers.group(1));
            while (literals.find()) {
                routes.add(literals.group(1));
            }
        }
        return routes;
    }

    @Test
    @DisplayName("la superficie publica esta declarada explicitamente como permitAll")
    void laRutaPublicaEstaEnElBlancoDePermitAll() {
        assertThat(permitAllRoutes(securityConfig))
                .as("Sin este matcher, /api/v1/public/** caeria en "
                        + "/api/v1/**).authenticated() y la primera pantalla pediria sesion.")
                .contains("/api/v1/public/**");
    }

    @Test
    @DisplayName("el matcher publico va ANTES del que exige sesion (el orden importa)")
    void elMatcherPublicoVaAntesDelAutenticado() {
        int publicMatcher = securityConfig.indexOf("\"/api/v1/public/**\"");
        int authenticated = securityConfig.indexOf("\"/api/v1/**\").authenticated()");

        assertThat(publicMatcher).as("el matcher publico no esta declarado").isGreaterThan(-1);
        assertThat(authenticated).as("el matcher autenticado no esta declarado").isGreaterThan(-1);
        assertThat(publicMatcher)
                .as("Spring evalua los matchers en orden: si el publico va despues, "
                        + "nunca se alcanza.")
                .isLessThan(authenticated);
    }

    @Test
    @DisplayName("el permitAll no abre ninguna otra ruta privada de la API")
    void elPermitAllNoAbreRutasPrivadas() {
        List<String> unexpected = permitAllRoutes(securityConfig).stream()
                .filter(route -> route.startsWith("/api/v1/"))
                .filter(route -> !(route.startsWith("/api/v1/public/")
                        || route.startsWith("/api/v1/auth/")
                        || route.startsWith("/api/v1/meta/")))
                .toList();

        assertThat(unexpected)
                .as("Todo lo que se abre a anonimos ademas de lo ya conocido es un cambio "
                        + "de superficie que debe discutirse, no aparecer de relleno.")
                .isEmpty();
    }

    @Test
    @DisplayName("el controlador publico solo declara GET: nada se crea ni se modifica")
    void elControladorSoloTieneGet() {
        assertThat(Pattern.compile("@(Post|Put|Patch|Delete)Mapping").matcher(controller).find())
                .as("La superficie anonima es de solo lectura: cualquier metodo de escritura "
                        + "aqui es un cambio de contrato.")
                .isFalse();
        assertThat(Pattern.compile("@GetMapping").matcher(controller).results().count())
                .isEqualTo(6);
    }

    @Test
    @DisplayName("las seis rutas publicas estan bajo /api/v1/public y el controlador lo declara")
    void lasRutasEstanBajoElPrefijoPublico() {
        Matcher classMapping = Pattern.compile("@RequestMapping\\(\"([^\"]+)\"\\)")
                .matcher(controller);
        assertThat(classMapping.find()).isTrue();
        assertThat(classMapping.group(1)).isEqualTo("/api/v1/public");

        Matcher getMapping = Pattern.compile("@GetMapping\\(\"([^\"]+)\"\\)").matcher(controller);
        List<String> suffixes = new ArrayList<>();
        while (getMapping.find()) {
            suffixes.add(getMapping.group(1));
        }
        assertThat(suffixes).containsExactlyInAnyOrder(
                "/summary", "/series", "/models", "/metrics", "/comparison", "/status");
    }

    @Test
    @DisplayName("los parametros anonimos se validan en el servidor")
    void losParametrosSeValidanEnElServidor() {
        assertThat(controller)
                .as("Sin @Size sobre symbol, un valor enorme viajaria a la consulta; "
                        + "sin @Min/@Max sobre limit, un cliente podria pedir 100000 velas.")
                .contains("@Size(max = 32)")
                .contains("@Min(1)")
                .contains("@Max(PublicDashboardService.MAX_PUBLIC_SERIES)")
                .contains("@Validated");
    }

    @Test
    @DisplayName("todas las respuestas publicas pasan por cache")
    void lasRespuestasPublicasEstanEnCache() {
        assertThat(Pattern.compile("@Cacheable").matcher(service).results().count())
                .as("La superficie anonima es la mas visitada: sin cache cada visita "
                        + "barreria market_data y metrics.")
                .isEqualTo(6);
    }

    @Test
    @DisplayName("el rate limit tiene un cubo propio para /api/v1/public")
    void elRateLimitTieneCuboPropio() {
        assertThat(rateLimit)
                .contains("isPublicPath")
                .contains("\"/api/v1/public/\"")
                .contains("publicRequestsPerMinute")
                .contains("\"ratelimit:\"");
    }

    @Test
    @DisplayName("el test no pasa por vacuidad: los extractores encuentran lo esperado")
    void losExtractoresEncuentranLoEsperado() {
        assertThat(permitAllRoutes(securityConfig)).hasSizeGreaterThanOrEqualTo(10);
        assertThat(controller).contains("publicapi");
        assertThat(service).contains("@Cacheable");
    }
}
