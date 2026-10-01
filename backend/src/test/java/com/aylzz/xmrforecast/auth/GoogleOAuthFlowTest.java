package com.aylzz.xmrforecast.auth;

import com.aylzz.xmrforecast.auth.dto.TokenResponse;
import com.aylzz.xmrforecast.common.ApiException;
import com.aylzz.xmrforecast.common.GlobalExceptionHandler;
import com.aylzz.xmrforecast.config.AppProperties;
import com.aylzz.xmrforecast.security.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Flujo de Google OAuth por HTTP: los dos endpoints y sus errores.
 *
 * <p><strong>El defecto que este test fija.</strong>
 * {@code GoogleOAuthService.buildAuthorizationUri()} construia la URL de Google con
 * {@code UriComponentsBuilder...build(true)}. El booleano de {@code build} no es
 * "codifica o no": es "las partes que le he dado ya estan codificadas". Con
 * {@code true}, Spring valida cada valor y el espacio de
 * {@code scope=openid email profile} es un caracter ilegal en un parametro de
 * consulta:
 *
 * <pre>
 * java.lang.IllegalArgumentException: Invalid character ' ' for QUERY_PARAM in
 * "openid email profile"
 * </pre>
 *
 * <p>La excepcion salia del controlador, nadie la capturaba y el manejador global
 * la traducia a {@code 500 INTERNAL_ERROR}. En otras palabras: <strong>el login con
 * Google estaba roto en todos los entornos y con cualquier valor de las variables
 * de entorno</strong>, incluido un despliegue correctamente configurado. No
 * dependia de {@code GOOGLE_CLIENT_ID}, ni del redirect URI, ni de nada que se
 * pudiera arreglar desde el panel de Render.
 *
 * <p>Las pruebas anteriores solo ejercitaban {@code verifyIdToken()} por reflexion.
 * {@code buildAuthorizationUri()} no se invocaba en ninguna, de modo que el endpoint
 * que la usa podia devolver 500 con la suite entera en verde. Compilar no es
 * arrancar, y arrancar no es responder: este test invoca el endpoint.
 */
class GoogleOAuthFlowTest {

    private static final String REDIRECT_URI =
            "https://localhost:8443/api/v1/auth/google/callback";
    private static final String FRONTEND_CALLBACK =
            "https://frontend.example/auth/callback";

    // --------------------------------------------------------------- authorize

    @Test
    @DisplayName("Con Google configurado, /authorize responde 307 y redirige a accounts.google.com")
    void authorizeRedirectsToGoogle() throws Exception {
        MockMvc mvc = mvc(google());

        String location = locationOf(mvc, get("/api/v1/auth/google/authorize"));

        URI uri = URI.create(location);
        assertThat(uri.getScheme()).isEqualTo("https");
        assertThat(uri.getHost()).isEqualTo("accounts.google.com");
        assertThat(uri.getPath()).isEqualTo("/o/oauth2/v2/auth");

        assertThat(queryOf(location))
                .containsEntry("client_id", "client-id-de-prueba")
                .containsEntry("redirect_uri", REDIRECT_URI)
                .containsEntry("response_type", "code")
                .containsEntry("scope", "openid email profile")
                .containsEntry("prompt", "select_account")
                .containsEntry("access_type", "offline");
        assertThat(queryOf(location).get("state")).isNotBlank();
        assertThat(queryOf(location).get("nonce")).isNotBlank();
        assertThat(queryOf(location).get("state"))
                .isNotEqualTo(queryOf(location).get("nonce"));
    }

    @Test
    @DisplayName("El scope viaja codificado y la URL no lleva espacios crudos")
    void scopeIsEncodedInTheLocation() throws Exception {
        // Con build(true) este era el punto exacto del fallo: el espacio de
        // `openid email profile` es ilegal en un parametro de consulta.
        MockMvc mvc = mvc(google());

        String location = locationOf(mvc, get("/api/v1/auth/google/authorize"));

        assertThat(location).doesNotContain(" ");
        assertThat(location).contains("scope=openid%20email%20profile");
        // Y el valor sigue siendo el correcto una vez decodificado por el cliente.
        assertThat(queryOf(location)).containsEntry("scope", "openid email profile");
    }

    @Test
    @DisplayName("El redirect URI se envia tal cual: debe coincidir con el de Google Cloud")
    void redirectUriIsSentVerbatim() throws Exception {
        // `:` y `/` no se codifican, de modo que la URI de la peticion es
        // identica a la registrada en Google Cloud. Codificarla haria que Google
        // respondiera redirect_uri_mismatch.
        String deployed = "https://xmr-backend-bcml.onrender.com/api/v1/auth/google/callback";
        MockMvc mvc = mvc(google("client-id-de-prueba", "client-secret", deployed));

        String location = locationOf(mvc, get("/api/v1/auth/google/authorize"));

        assertThat(location).contains("redirect_uri=" + deployed);
        assertThat(queryOf(location)).containsEntry("redirect_uri", deployed);
    }

    @Test
    @DisplayName("Cada peticion usa un state y un nonce distintos (CSRF y replay)")
    void everyRequestUsesAFreshStateAndNonce() throws Exception {
        MockMvc mvc = mvc(google());

        String first = locationOf(mvc, get("/api/v1/auth/google/authorize"));
        String second = locationOf(mvc, get("/api/v1/auth/google/authorize"));

        assertThat(queryOf(first).get("state")).isNotEqualTo(queryOf(second).get("state"));
        assertThat(queryOf(first).get("nonce")).isNotEqualTo(queryOf(second).get("nonce"));
    }

    @Test
    @DisplayName("Un state es de un solo uso: la segunda reutilizacion se rechaza")
    void stateCannotBeReplayed() {
        GoogleOAuthService service = service(google());
        String state = queryOf(service.buildAuthorizationUri().toString()).get("state");

        Object first = ReflectionTestUtils.invokeMethod(service, "consumeState", state);
        Object second = ReflectionTestUtils.invokeMethod(service, "consumeState", state);

        assertThat(first).isNotNull();
        assertThat(second)
                .as("el state se elimina al consumirse: reutilizarlo es un ataque CSRF")
                .isNull();
    }

    @Test
    @DisplayName("Sin GOOGLE_CLIENT_ID / SECRET, /authorize responde 503 controlado, no 500")
    void authorizeWithoutCredentialsIsAControlledError() throws Exception {
        MockMvc mvc = mvc(google("", ""));

        mvc.perform(get("/api/v1/auth/google/authorize"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("OAUTH_NOT_CONFIGURED"))
                .andExpect(jsonPath("$.status").value(503));
    }

    @Test
    @DisplayName("Sin GOOGLE_REDIRECT_URI, /authorize responde 503 controlado, no 500")
    void authorizeWithoutRedirectUriIsAControlledError() throws Exception {
        MockMvc mvc = mvc(google("client-id-de-prueba", "client-secret", "   "));

        mvc.perform(get("/api/v1/auth/google/authorize"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("OAUTH_REDIRECT_URI_NOT_CONFIGURED"));
    }

    // ---------------------------------------------------------------- callback

    @Test
    @DisplayName("Callback correcto: 302 al frontend con la sesion en el fragmento y cookie")
    void successfulCallbackRedirectsWithSessionInTheFragment() throws Exception {
        // El canje con Google necesita red, asi que aqui se dobla el servicio: lo
        // que se comprueba es el contrato HTTP del controlador, que es lo que se
        // rompio. El canje y la validacion del ID Token tienen su propia prueba en
        // GoogleOAuthServiceTest, sin llamadas de red (R-18).
        GoogleOAuthService oauth = mock(GoogleOAuthService.class);
        when(oauth.handleCallback(any(), any(), any(), any(), any()))
                .thenReturn(new TokenResponse("access-de-prueba", "refresh-de-prueba",
                        "Bearer", 900, Set.of(Role.VIEWER), null));
        MockMvc mvc = mvc(oauth, google());

        MvcResult result = mvc.perform(get("/api/v1/auth/google/callback")
                        .param("code", "codigo").param("state", "state"))
                .andExpect(status().isFound())
                .andReturn();

        String location = result.getResponse().getHeader(HttpHeaders.LOCATION);
        assertThat(location).startsWith(FRONTEND_CALLBACK + "#");
        // El token viaja en el fragmento, nunca en la query: la query si llega a
        // los logs de acceso del servidor y al historial del navegador.
        String query = location.substring(0, location.indexOf('#'));
        String fragment = location.substring(location.indexOf('#') + 1);
        // La query si llega a los logs de acceso del servidor: vacia de tokens.
        assertThat(query).isEqualTo(FRONTEND_CALLBACK);
        assertThat(query).doesNotContain("access-de-prueba").doesNotContain("refresh-de-prueba");
        assertThat(fragment)
                .contains("access_token=access-de-prueba")
                .contains("refresh_token=refresh-de-prueba")
                .contains("role=VIEWER");
        // Ademas, cookie HttpOnly para poder renovar sin exponer el refresh a JS.
        assertThat(result.getResponse().getHeader(HttpHeaders.SET_COOKIE))
                .contains("xmr_refresh")
                .contains("HttpOnly");
    }

    @Test
    @DisplayName("Callback con error de Google: se distingue de un fallo de credenciales")
    void callbackWithGoogleErrorIsNotACredentialFailure() throws Exception {
        AuthService authService = mock(AuthService.class);
        MockMvc mvc = mvc(service(google(), authService));

        String location = locationOf(mvc, get("/api/v1/auth/google/callback")
                .param("error", "access_denied")
                .param("error_description", "El usuario denego el acceso"));

        assertThat(location).startsWith(FRONTEND_CALLBACK + "#");
        assertThat(location).contains("error_code=OAUTH_DENIED");
        verify(authService, never()).loginWithGoogle(any(), any(), anyString());
    }

    @Test
    @DisplayName("Callback sin codigo: error controlado, no 500")
    void callbackWithoutCodeIsAControlledError() throws Exception {
        MockMvc mvc = mvc(google());

        String location = locationOf(mvc, get("/api/v1/auth/google/callback"));

        assertThat(location).contains("error_code=OAUTH_MISSING_CODE");
    }

    @Test
    @DisplayName("Callback con state desconocido: se rechaza como CSRF y no se canjea nada")
    void callbackWithUnknownStateIsRejected() throws Exception {
        AuthService authService = mock(AuthService.class);
        MockMvc mvc = mvc(service(google(), authService));

        String location = locationOf(mvc, get("/api/v1/auth/google/callback")
                .param("code", "codigo-inventado").param("state", "state-que-no-existe"));

        assertThat(location).contains("error_code=OAUTH_INVALID_STATE");
        // El canje con Google no llega a intentarse: el state se valida primero.
        verify(authService, never()).loginWithGoogle(any(), any(), anyString());
    }

    @Test
    @DisplayName("Callback con un state ya usado: el replay se rechaza")
    void replayedStateIsRejectedOnTheSecondUse() throws Exception {
        AuthService authService = mock(AuthService.class);
        GoogleOAuthService service = service(google(), authService);
        MockMvc mvc = mvc(service);
        String state = queryOf(service.buildAuthorizationUri().toString()).get("state");

        // El primer canje fallaria sin Google de pruebas, pero el state ya se
        // consumio: el segundo uso tiene que rechazarse.
        ReflectionTestUtils.invokeMethod(service, "consumeState", state);
        String location = locationOf(mvc, get("/api/v1/auth/google/callback")
                .param("code", "codigo").param("state", state));

        assertThat(location).contains("error_code=OAUTH_INVALID_STATE");
        verify(authService, never()).loginWithGoogle(any(), any(), anyString());
    }

    @Test
    @DisplayName("El callback redirige al FRONTEND_OAUTH_CALLBACK configurado, con la query vacia")
    void callbackRedirectsToTheConfiguredFrontend() throws Exception {
        GoogleOAuthService oauth = mock(GoogleOAuthService.class);
        when(oauth.handleCallback(any(), any(), any(), any(), any()))
                .thenThrow(ApiException.unauthorized("OAUTH_DENIED", "denegado"));
        MockMvc mvc = mvc(oauth, google());

        String location = locationOf(mvc, get("/api/v1/auth/google/callback")
                .param("error", "access_denied"));

        assertThat(location).startsWith(FRONTEND_CALLBACK + "#error_code=OAUTH_DENIED");
        // Solo el fragmento: nada viaja en la query.
        assertThat(location).doesNotContain("?");
    }

    @Test
    @DisplayName("La URL de autorizacion lleva nonce propio, que se compara con el del ID Token")
    void authorizationUriCarriesItsOwnNonce() throws Exception {
        // El nonce viaja en la URL de autorizacion y `verifyIdToken` lo compara
        // con el del ID Token: sin el, un token capturado de otro inicio de sesion
        // seria valido aqui. La comparacion en si esta en GoogleOAuthServiceTest
        // ("Un nonce distinto se rechaza"); esto fija que se emite.
        MockMvc mvc = mvc(google());

        String location = locationOf(mvc, get("/api/v1/auth/google/authorize"));

        assertThat(queryOf(location).get("nonce")).isNotBlank();
    }

    @Test
    @DisplayName("El decodificador de la prueba detecta una URL mal codificada")
    void queryHelperDecodesEncodedValues() {
        assertThat(queryOf("https://x.test/auth?scope=openid%20email%20profile&state=a%2Bb"))
                .containsEntry("scope", "openid email profile")
                .containsEntry("state", "a+b");
    }

    @Test
    @DisplayName("El state se guarda en Redis, no en memoria: sobrevive a un reinicio")
    void stateIsStoredInRedisSoItSurvivesARestart() {
        // Defecto medido: con el state solo en memoria, un login fallaba si la
        // instancia se reiniciaba entre /authorize y /callback. En el plan
        // gratuito de Render eso pasa de forma rutinaria. Este test fija que el
        // estado se escribe en Redis con TTL y que alli se consume, de modo que
        // el reinicio de la instancia deje de importar.
        org.springframework.data.redis.core.StringRedisTemplate redis =
                mock(org.springframework.data.redis.core.StringRedisTemplate.class);
        org.springframework.data.redis.core.ValueOperations<String, String> ops =
                mock(org.springframework.data.redis.core.ValueOperations.class);
        org.mockito.Mockito.when(redis.opsForValue()).thenReturn(ops);

        GoogleOAuthService service = new GoogleOAuthService(properties(google()),
                RestClient.builder(), mock(AuthService.class),
                mock(com.aylzz.xmrforecast.audit.AuditService.class), redis);

        String state = queryOf(service.buildAuthorizationUri().toString()).get("state");

        verify(ops).set(org.mockito.ArgumentMatchers.eq("oauth:state:" + state),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq(java.time.Duration.ofMinutes(10)));

        // Sin valor en Redis (porque la instancia se reinicio) el state se
        // rechaza: es exactamente el caso que antes fallaba por el reinicio.
        org.mockito.Mockito.when(ops.getAndDelete("oauth:state:" + state)).thenReturn(null);
        Object trasReinicio = ReflectionTestUtils.invokeMethod(service, "consumeState", state);
        assertThat(trasReinicio).isNull();
    }

    @Test
    @DisplayName("El state se consume una sola vez tambien en Redis (GETDEL atomico)")
    void stateIsConsumedAtomicallyInRedis() {
        org.springframework.data.redis.core.StringRedisTemplate redis =
                mock(org.springframework.data.redis.core.StringRedisTemplate.class);
        org.springframework.data.redis.core.ValueOperations<String, String> ops =
                mock(org.springframework.data.redis.core.ValueOperations.class);
        org.mockito.Mockito.when(redis.opsForValue()).thenReturn(ops);

        GoogleOAuthService service = new GoogleOAuthService(properties(google()),
                RestClient.builder(), mock(AuthService.class),
                mock(com.aylzz.xmrforecast.audit.AuditService.class), redis);
        String state = queryOf(service.buildAuthorizationUri().toString()).get("state");

        String nonce = "nonce-de-prueba";
        String stored = nonce + "|" + System.currentTimeMillis();
        // GETDEL borra: la segunda llamada ya no devuelve nada.
        org.mockito.Mockito.when(ops.getAndDelete("oauth:state:" + state))
                .thenReturn(stored).thenReturn(null);

        Object first = ReflectionTestUtils.invokeMethod(service, "consumeState", state);
        Object second = ReflectionTestUtils.invokeMethod(service, "consumeState", state);

        assertThat(first).isNotNull();
        assertThat(second).isNull();
    }

    @Test
    @DisplayName("Si Redis falla, el login sigue funcionando en memoria")
    void redisFailureDegradesToMemoryInsteadOfBreakingLogin() {
        // Un fallo de Redis no puede convertir el login en un error: se degrada,
        // igual que hace RateLimitFilter, y el usuario si puede entrar.
        org.springframework.data.redis.core.StringRedisTemplate redis =
                mock(org.springframework.data.redis.core.StringRedisTemplate.class);
        org.mockito.Mockito.when(redis.opsForValue())
                .thenThrow(new org.springframework.data.redis.RedisConnectionFailureException("caido"));

        GoogleOAuthService service = new GoogleOAuthService(properties(google()),
                RestClient.builder(), mock(AuthService.class),
                mock(com.aylzz.xmrforecast.audit.AuditService.class), redis);

        String state = queryOf(service.buildAuthorizationUri().toString()).get("state");

        Object recuperado = ReflectionTestUtils.invokeMethod(service, "consumeState", state);
        assertThat(recuperado).isNotNull();
    }

    // ------------------------------------------------------------------ helpers

    private static AppProperties.OAuth2.Google google() {
        return google("client-id-de-prueba", "client-secret-de-prueba", REDIRECT_URI);
    }

    private static AppProperties.OAuth2.Google google(String clientId, String clientSecret) {
        return google(clientId, clientSecret, REDIRECT_URI);
    }

    private static AppProperties.OAuth2.Google google(String clientId, String clientSecret,
                                                    String redirectUri) {
        return new AppProperties.OAuth2.Google(clientId, clientSecret, redirectUri,
                FRONTEND_CALLBACK, List.of("https://accounts.google.com"), clientId);
    }

    private static AppProperties properties(AppProperties.OAuth2.Google google) {
        return new AppProperties(
                new AppProperties.Jwt("k".repeat(64), "https://localhost:8443",
                        "xmr-forecast-api", 900, 2592000, 30),
                new AppProperties.OAuth2(google),
                new AppProperties.Ml("https://ml-service:8443", 3000, 60000, 2, ""),
                new AppProperties.Security(true, 31536000L,
                        List.of("https://frontend.example"),
                        new AppProperties.Security.RateLimit(true, 5, 300),
                        new AppProperties.Security.Account(5, 15)),
                new AppProperties.Jobs(3, 900));
    }

    private static GoogleOAuthService service(AppProperties.OAuth2.Google google) {
        return service(google, mock(AuthService.class));
    }

    private static GoogleOAuthService service(AppProperties.OAuth2.Google google,
                                              AuthService authService) {
        return new GoogleOAuthService(properties(google), RestClient.builder(),
                authService, mock(com.aylzz.xmrforecast.audit.AuditService.class), null);
    }

    private static MockMvc mvc(AppProperties.OAuth2.Google google) {
        return mvc(service(google), properties(google));
    }

    private static MockMvc mvc(GoogleOAuthService service, AppProperties.OAuth2.Google google) {
        return mvc(service, properties(google));
    }

    private static MockMvc mvc(GoogleOAuthService service, AppProperties properties) {
        return MockMvcBuilders
                .standaloneSetup(new GoogleOAuthController(service, properties))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private static MockMvc mvc(GoogleOAuthService service) {
        return mvc(service, properties(google()));
    }

    private static String locationOf(MockMvc mvc, RequestBuilder request) throws Exception {
        return mvc.perform(request).andReturn().getResponse().getHeader(HttpHeaders.LOCATION);
    }

    /** Parametros de consulta de la URL de autorizacion, ya decodificados. */
    private static Map<String, String> queryOf(String location) {
        Map<String, String> values = new LinkedHashMap<>();
        String query = UriComponentsBuilder.fromUriString(location).build().getQuery();
        if (query == null) {
            return values;
        }
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            String name = eq < 0 ? pair : pair.substring(0, eq);
            String value = eq < 0 ? "" : pair.substring(eq + 1);
            values.put(URLDecoder.decode(name, StandardCharsets.UTF_8),
                    URLDecoder.decode(value, StandardCharsets.UTF_8));
        }
        return values;
    }
}
