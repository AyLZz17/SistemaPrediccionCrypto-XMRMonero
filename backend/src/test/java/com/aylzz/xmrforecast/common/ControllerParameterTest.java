package com.aylzz.xmrforecast.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Que todo controlador de la capa HTTP use la API de Spring correctamente.
 *
 * <p><strong>Por que hace falta.</strong> Spring resuelve los parametros de un
 * metodo de controlador por nombre. Un parametro {@code AuthenticatedUser} sin
 * {@code @AuthenticationPrincipal} no se inyecta: Spring intenta construirlo
 * como atributo de modelo y falla en la primera peticion con
 *
 * <pre>
 * java.lang.IllegalStateException: Current user principal is not of type
 * [AuthenticatedUser]: UsernamePasswordAuthenticationToken [Principal=AuthenticatedUser[...]]
 * </pre>
 *
 * El error aparece en el log, no en la compilacion, y solo para el endpoint
 * afectado. Asi estuvo `GET /api/v1/predictions` y `POST /api/v1/predictions`
 * durante toda la vida del proyecto: el controlador compila, los tests de
 * unidades pasan, y la pagina de predicciones devuelve 500.
 *
 * <p>Este test recorre los controladores por reflexion y no puede volver a
 * dejar pasar el mismo error.
 */
class ControllerParameterTest {

    private static final Path CONTROLLER_ROOT = Path.of("src", "main", "java", "com",
            "aylzz", "xmrforecast");

    private record ControllerMethod(String controller, String method, String route, String issue) {
    }

    private static List<ControllerMethod> inspectAllControllers() throws Exception {
        List<ControllerMethod> problems = new ArrayList<>();
        List<Class<?>> controllers = new ArrayList<>();

        try (Stream<Path> walk = Files.walk(CONTROLLER_ROOT)) {
            for (Path file : walk.filter(p -> p.toString().endsWith("Controller.java")).toList()) {
                String className = "com.aylzz.xmrforecast."
                        + CONTROLLER_ROOT.relativize(file).toString()
                        .replace('\\', '.')
                        .replace(".java", "");
                Class<?> type = Class.forName(className);
                if (type.isAnnotationPresent(RestController.class)
                        || type.isAnnotationPresent(RequestMapping.class)) {
                    controllers.add(type);
                }
            }
        }

        for (Class<?> controller : controllers) {
            String base = baseRoute(controller);
            for (Method method : controller.getDeclaredMethods()) {
                if (!AnnotatedElementUtils.hasAnnotation(method, RequestMapping.class)) {
                    continue;
                }
                String route = base + route(method);
                for (Parameter parameter : method.getParameters()) {
                    Class<?> type = parameter.getType();
                    boolean isPrincipal = type.getName().startsWith("com.aylzz.xmrforecast.security.")
                            || java.security.Principal.class.isAssignableFrom(type);
                    if (!isPrincipal) {
                        continue;
                    }
                    boolean annotated = parameter.isAnnotationPresent(AuthenticationPrincipal.class);
                    if (!annotated) {
                        problems.add(new ControllerMethod(
                                controller.getSimpleName(), method.getName(), route,
                                "el parametro '" + parameter.getName() + "' (" + type.getSimpleName()
                                        + ") necesita @AuthenticationPrincipal; sin el, Spring responde 500"));
                    }
                }
            }
        }
        return problems;
    }

    @Test
    @DisplayName("ningun controlador inyecta el principal sin @AuthenticationPrincipal")
    void everyPrincipalParameterIsAnnotated() throws Exception {
        List<ControllerMethod> problems = inspectAllControllers();

        assertThat(problems)
                .withFailMessage("""
                        Parametros de principal sin @AuthenticationPrincipal. Cada uno de estos
                        endpoints devuelve 500 en la PRIMERA peticion, aunque compile y sus
                        tests de unidades pasan:
                        %s
                        """, problems)
                .isEmpty();
    }

    @Test
    @DisplayName("la inspeccion encuentra controladores de verdad (el test no pasa por vacuidad)")
    void theInspectionActuallyFindsControllers() throws Exception {
        // Un reflexion que dejara de encontrar clases haria que el test anterior
        // pasara siempre. Este test falla si el recorrido se rompe.
        List<Class<?>> controllers = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(CONTROLLER_ROOT)) {
            for (Path file : walk.filter(p -> p.toString().endsWith("Controller.java")).toList()) {
                String className = "com.aylzz.xmrforecast."
                        + CONTROLLER_ROOT.relativize(file).toString()
                        .replace('\\', '.').replace(".java", "");
                controllers.add(Class.forName(className));
            }
        }
        assertThat(controllers)
                .as("no se encontro ningun controlador; revisa CONTROLLER_ROOT")
                .hasSizeGreaterThanOrEqualTo(10);
    }

    private static String baseRoute(Class<?> controller) {
        RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(
                controller, RequestMapping.class);
        return mapping == null || mapping.value().length == 0 ? "" : mapping.value()[0];
    }

    private static String route(Method method) {
        RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(
                method, RequestMapping.class);
        if (mapping == null) {
            return "";
        }
        if (mapping.value().length > 0) {
            return mapping.value()[0];
        }
        // @GetMapping y afines se compilan como @RequestMapping(method = ...).
        return mapping.path().length > 0 ? mapping.path()[0] : "";
    }
}
