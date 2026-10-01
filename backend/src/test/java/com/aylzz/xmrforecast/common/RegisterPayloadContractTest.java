package com.aylzz.xmrforecast.common;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contrato del cuerpo de {@code POST /api/v1/auth/register} entre el backend y
 * el frontend (R-38, y R-43 para la version del documento).
 *
 * <p><strong>Por que existe.</strong> El registro paso a exigir tres campos de
 * consentimiento que antes no existian. Si el cliente dejara de enviarlos, el
 * servidor responderia 400 con {@code CONSENT_REQUIRED} y el alta se romperia
 * sin que compilara ni fallara ninguna prueba de un solo lado. Si el servidor
 * perdiera un campo, el cliente enviaria datos que nadie mira y la aceptacion
 * no quedaria registrada.
 *
 * <p><strong>Que compara.</strong> Los componentes reales del record Java contra
 * las claves reales del objeto que el formulario envia, leidos de los dos
 * arboles de origen. No compara una constante con otra: compara lo que se
 * envia con lo que se acepta.
 */
class RegisterPayloadContractTest {

    private static final Path BACKEND_REQUESTS =
            Path.of("src", "main", "java", "com", "aylzz", "xmrforecast", "auth", "dto", "AuthRequests.java");
    private static final Path FRONTEND_REGISTER_PAGE =
            Path.of("..", "frontend", "src", "pages", "RegisterPage.tsx");
    private static final Path FRONTEND_TYPES = Path.of("..", "frontend", "src", "types", "session.ts");

    private static Set<String> backendComponents;
    private static Set<String> frontendPayloadKeys;
    private static Set<String> frontendTypeFields;

    @BeforeAll
    static void readBothSides() throws IOException {
        assertThat(Files.isRegularFile(BACKEND_REQUESTS))
                .as("falta el contrato del backend en %s", BACKEND_REQUESTS.toAbsolutePath())
                .isTrue();
        assertThat(Files.isRegularFile(FRONTEND_REGISTER_PAGE))
                .as("falta el formulario del cliente en %s", FRONTEND_REGISTER_PAGE.toAbsolutePath())
                .isTrue();

        backendComponents = extractRecordComponents(Files.readString(BACKEND_REQUESTS, StandardCharsets.UTF_8));
        frontendPayloadKeys = extractPayloadKeys(Files.readString(FRONTEND_REGISTER_PAGE, StandardCharsets.UTF_8));
        frontendTypeFields = extractInterfaceFields(Files.readString(FRONTEND_TYPES, StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("la extraccion no esta vacia: si lo estara, el test no estaria comprobando nada")
    void extractionIsNotEmpty() {
        assertThat(backendComponents)
                .contains("email", "password", "fullName", "acceptTerms", "acceptDataPolicy", "acceptMarketing");
        assertThat(frontendPayloadKeys)
                .contains("email", "password", "fullName", "acceptTerms", "acceptDataPolicy", "acceptMarketing");
        assertThat(frontendTypeFields)
                .contains("email", "password", "fullName", "acceptTerms", "acceptDataPolicy", "acceptMarketing");
    }

    @Test
    @DisplayName("el formulario envia exactamente los campos que el backend acepta")
    void payloadMatchesAcceptedFields() {
        assertThat(frontendPayloadKeys)
                .as("el cliente envia campos que el servidor ignora, o deja de enviar campos "
                        + "que el servidor exige")
                .isEqualTo(backendComponents);
    }

    @Test
    @DisplayName("la interfaz TypeScript del request coincide con los componentes del record")
    void interfaceMatchesRecord() {
        assertThat(frontendTypeFields).isEqualTo(backendComponents);
    }

    // ------------------------------------------------------------ extraccion

    private static Set<String> extractRecordComponents(String source) {
        int start = source.indexOf("record RegisterRequest(");
        assertThat(start).as("no se encontro RegisterRequest en AuthRequests.java").isGreaterThanOrEqualTo(0);
        int end = source.indexOf(") {}", start);
        assertThat(end).as("no se encontro el cierre de RegisterRequest").isGreaterThan(start);

        String block = strip(source.substring(start + "record RegisterRequest(".length(), end));
        // Tras quitar anotaciones y comentarios quedan pares "Tipo nombre".
        block = block.replaceAll("@\\w+\\s*\\([^)]*\\)", " ");
        block = block.replaceAll("@\\w+", " ");

        Matcher matcher = Pattern.compile("\\b[A-Z][A-Za-z0-9_]*\\s+([a-z][A-Za-z0-9_]*)\\s*[,\\s]").matcher(block);
        LinkedHashSet<String> fields = new LinkedHashSet<>();
        while (matcher.find()) {
            fields.add(matcher.group(1));
        }
        return fields;
    }

    private static Set<String> extractPayloadKeys(String source) {
        Matcher matcher = Pattern.compile("await register\\(\\{([\\s\\S]*?)\\}\\)").matcher(source);
        assertThat(matcher.find()).as("no se encontro la llamada register({...}) en RegisterPage.tsx").isTrue();
        String body = strip(matcher.group(1));

        LinkedHashSet<String> keys = new LinkedHashSet<>();
        // Acepta tanto `clave: valor` como el shorthand `clave,` (que es como
        // el formulario envia password y los tres consentimientos).
        Matcher keyMatcher =
                Pattern.compile("(?m)^\\s*([a-z][A-Za-z0-9_]*)(?:\\s*:|,|\\s*$)").matcher(body);
        while (keyMatcher.find()) {
            keys.add(keyMatcher.group(1));
        }
        return keys;
    }

    private static Set<String> extractInterfaceFields(String source) {
        Matcher matcher = Pattern.compile("interface RegisterRequest \\{([\\s\\S]*?)\\}").matcher(source);
        assertThat(matcher.find()).as("no se encontro RegisterRequest en types/session.ts").isTrue();
        String body = strip(matcher.group(1));

        LinkedHashSet<String> fields = new LinkedHashSet<>();
        Matcher fieldMatcher = Pattern.compile("(?m)^\\s*([a-z][A-Za-z0-9_]*)\\??:").matcher(body);
        while (fieldMatcher.find()) {
            fields.add(fieldMatcher.group(1));
        }
        return fields;
    }

    private static String strip(String text) {
        return text.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("//[^\n]*", " ");
    }
}
