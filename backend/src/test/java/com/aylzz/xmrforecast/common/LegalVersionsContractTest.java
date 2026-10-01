package com.aylzz.xmrforecast.common;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Version de los documentos legales: una sola fuente de verdad en los dos lados
 * (R-43) verificada leyendo los dos archivos de origen (R-38).
 *
 * <p>El backend guarda en {@code consent_records} la version que dice haber
 * aceptado el usuario; el frontend la muestra en cada pagina y en el pie. Si
 * alguien cambia una de las dos sin la otra, la prueba de aceptacion apuntaria a
 * un texto que nadie vio: por eso se comparan los literales reales de
 * {@code LegalDocuments.java} y de {@code config/legal.ts}, no dos constantes
 * importadas del mismo sitio.
 */
class LegalVersionsContractTest {

    private static final Path BACKEND =
            Path.of("src", "main", "java", "com", "aylzz", "xmrforecast", "common", "LegalDocuments.java");
    private static final Path FRONTEND = Path.of("..", "frontend", "src", "config", "legal.ts");

    private static String backendVersion;
    private static String backendEffectiveDate;
    private static String backendContactEmail;
    private static String frontendVersion;
    private static String frontendEffectiveDate;
    private static String frontendContactEmail;

    @BeforeAll
    static void readBothSides() throws IOException {
        assertThat(Files.isRegularFile(BACKEND))
                .as("faltan las versiones legales del backend en %s", BACKEND.toAbsolutePath())
                .isTrue();
        assertThat(Files.isRegularFile(FRONTEND))
                .as("faltan las versiones legales del frontend en %s", FRONTEND.toAbsolutePath())
                .isTrue();

        String backend = Files.readString(BACKEND, StandardCharsets.UTF_8);
        String frontend = Files.readString(FRONTEND, StandardCharsets.UTF_8);

        backendVersion = literal(backend, "CURRENT_VERSION\\s*=\\s*\"([^\"]+)\"", BACKEND);
        backendEffectiveDate = literal(backend, "EFFECTIVE_DATE\\s*=\\s*\"([^\"]+)\"", BACKEND);
        backendContactEmail = literal(backend, "DEFAULT_CONTACT_EMAIL\\s*=\\s*\"([^\"]+)\"", BACKEND);

        frontendVersion = literal(frontend, "LEGAL_VERSION\\s*=\\s*'([^']+)'", FRONTEND);
        frontendEffectiveDate = literal(frontend, "LEGAL_EFFECTIVE_DATE\\s*=\\s*'([^']+)'", FRONTEND);
        frontendContactEmail = literal(frontend, "LEGAL_CONTACT_EMAIL\\s*=\\s*'([^']+)'", FRONTEND);
    }

    @Test
    @DisplayName("las versiones coinciden: el texto que se ve es el que se registra")
    void versionsMatch() {
        assertThat(frontendVersion)
                .as("version mostrada != version registrada en consent_records")
                .isEqualTo(backendVersion);
        assertThat(frontendEffectiveDate).isEqualTo(backendEffectiveDate);
    }

    @Test
    @DisplayName("el canal de contacto coincide en ambos lados")
    void contactEmailMatches() {
        assertThat(frontendContactEmail).isEqualTo(backendContactEmail);
    }

    @Test
    @DisplayName("la version publicada tiene forma de fecha: no es un texto libre olvidable")
    void versionLooksLikeADate() {
        assertThat(backendVersion).matches("\\d{4}-\\d{2}-\\d{2}");
        assertThat(frontendVersion).matches("\\d{4}-\\d{2}-\\d{2}");
    }

    @Test
    @DisplayName("el backend publica exactamente los documentos que el frontend enlaza")
    void publishedPathsMatch() throws IOException {
        String backend = Files.readString(BACKEND, StandardCharsets.UTF_8);
        String frontend = Files.readString(FRONTEND, StandardCharsets.UTF_8);

        assertThat(pathLiterals(backend, "\"(/[a-z-]+)\""))
                .as("rutas publicadas en LegalDocuments.published()")
                .containsExactlyInAnyOrderElementsOf(pathLiterals(frontend, "path: '(/[^']+)'"));
    }

    private static String literal(String source, String regex, Path file) {
        Matcher matcher = Pattern.compile(regex).matcher(source);
        assertThat(matcher.find())
                .as("no se encontro %s en %s", regex, file.toAbsolutePath())
                .isTrue();
        return matcher.group(1);
    }

    private static java.util.List<String> pathLiterals(String source, String regex) {
        Matcher matcher = Pattern.compile(regex).matcher(source);
        java.util.List<String> paths = new java.util.ArrayList<>();
        while (matcher.find()) {
            paths.add(matcher.group(1));
        }
        return paths;
    }
}
