package com.aylzz.xmrforecast.common;

import com.aylzz.xmrforecast.auth.dto.AuthRequests;
import com.aylzz.xmrforecast.auth.dto.PasswordPolicy;
import com.aylzz.xmrforecast.dataset.DatasetController;
import com.aylzz.xmrforecast.dataset.DatasetService;
import com.aylzz.xmrforecast.experiment.ExperimentController;
import com.aylzz.xmrforecast.job.JobController;
import com.aylzz.xmrforecast.mlmodel.ModelController;
import com.aylzz.xmrforecast.security.Role;
import com.aylzz.xmrforecast.user.AdminUserController;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Comprueba que las restricciones de bean de los cuerpos de peticion sean
 * <em>ejecutables</em>, no solo correctas en apariencia.
 *
 * <p><strong>El defecto que este test fija.</strong>
 * {@code StartRunBody.seeds} declaraba {@code List<@Size(...) Integer>}.
 * {@code @Size} solo es valida para {@code CharSequence}, {@code Collection},
 * {@code Map} y arrays: sobre un {@code Integer} no existe validador, y Hibernate
 * Validator lanza
 * {@code UnexpectedTypeException: HV000030: No validator could be found for
 * constraint 'Size' validating type 'Integer'} en la primera peticion que llega.
 *
 * <p>El defecto llevaba tiempo vivo porque el controlador declaraba
 * {@code @RequestBody(required = false)} <strong>sin {@code @Valid}</strong>: el
 * validador no se ejecutaba nunca y la anotacion imposible nunca llego a fallar.
 * Compilaba, las pruebas de unidad pasaban, y el endpoint devolvia 500 en la
 * primera llamada real. Un health check en verde no demuestra que un endpoint
 * funcione: hay que invocarlo con una instancia valida.
 *
 * <p>Por eso este test <strong>valida instancias reales</strong> de cada cuerpo, en
 * lugar de inspeccionar anotaciones por reflexion. Es exactamente lo que hace
 * Spring al procesar un {@code @Valid @RequestBody}, y por eso detecta la clase de
 * fallo que un analisis estatico del codigo pasa por alto.
 *
 * <p>Referencia: Jakarta Bean Validation, seccion 2.3 — la metacrestriccion
 * {@code ValidatedValue} solo admite tipos para los que exista un
 * {@code ConstraintValidator} registrado para esa anotacion.
 */
class BeanValidationConstraintTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    /**
     * Un cuerpo de peticion VALIDO no puede producir un error de validacion.
     *
     * <p>Es la asercion central: si una anotacion es inaplicable a su tipo, el
     * fallo aparece aqui como {@code UnexpectedTypeException}, con el mensaje de
     * Hibernate Validator, en lugar de descubrirse en produccion.
     */
    @Test
    @DisplayName("Validar un cuerpo de peticion valido no lanza HV000030")
    void everyValidBodyValidatesCleanly() {
        List<Object> validBodies = List.of(
                // El caso que reventaba: cinco semillas.
                new ExperimentController.StartRunBody("run-1", List.of(1, 2, 3, 4, 5)),
                new ExperimentController.StartRunBody(null, null),
                new ExperimentController.CreateExperimentBody("Verificacion",
                        "descripcion", "hipotesis", "REGRESSION", null),
                new AdminUserController.ChangeRoleRequest(Role.ANALYST,
                        new LinkedHashSet<>(List.of(Role.VIEWER, Role.ANALYST))),
                new DatasetService.CreateDatasetRequest("XMR-USD", "v1", "sintetico",
                        "1d", 400L, null, null, "s3://xmr/v1.csv", "notas"),
                new JobController.EnqueueRequest("PREDICT", "clave-1", null),
                new ModelController.PromoteRequest("1"),
                new AuthRequests.RegisterRequest("user@example.com", "Clave#Fuerte2026",
                        "Usuario", true, true, false),
                new AuthRequests.LoginRequest("user@example.com", "Clave#Fuerte2026"),
                new AuthRequests.ForgotPasswordRequest("user@example.com"),
                new AuthRequests.ResendVerificationRequest("user@example.com"));

        for (Object body : validBodies) {
            assertThatCode(() -> validator.validate(body))
                    .as("cuerpo invalido por anotacion: %s (%s)", body,
                            body.getClass().getSimpleName())
                    .doesNotThrowAnyException();
            assertThat(validator.validate(body))
                    .as("un cuerpo valido no debe tener violaciones: %s",
                            body.getClass().getSimpleName())
                    .isEmpty();
        }
    }

    @Test
    @DisplayName("Las semillas admiten valores validos y rechazan los nulos")
    void seedsValidateWithoutTypeError() {
        assertThat(validator.validate(
                new ExperimentController.StartRunBody("run-1", List.of(1, 2, 3, 4, 5))))
                .isEmpty();

        assertThat(validator.validate(
                new ExperimentController.StartRunBody("run-1", Arrays.asList(1, null, 3, 4, 5))))
                .as("una semilla nula se rechaza")
                .isNotEmpty();
    }

    @Test
    @DisplayName("Un runKey demasiado largo se rechaza en la validacion, no en la base de datos")
    void runKeyLengthIsEnforced() {
        // Sin @Valid, un runKey de 200 caracteres pasaba el controlador y chocaba
        // contra experiment_runs.run_key VARCHAR(64): 500 en lugar de 400.
        assertThat(validator.validate(
                new ExperimentController.StartRunBody("r".repeat(200), List.of(1, 2, 3, 4, 5))))
                .isNotEmpty();
    }

    @Test
    @DisplayName("Un runKey con caracteres no permitidos se rechaza")
    void runKeyPatternIsEnforced() {
        assertThat(validator.validate(
                new ExperimentController.StartRunBody("run 1; DROP TABLE", null)))
                .as("el patron restringe el runKey a un formato seguro")
                .isNotEmpty();
    }

    @Test
    @DisplayName("Una tarea de experimento invalida responde 400, no 500")
    void experimentTaskPatternIsEnforced() {
        // Con `TaskType.valueOf("regresion")` en minusculas se lanzaba
        // IllegalArgumentException, que el manejador global no traduce: 500 por un
        // error de escritura del cliente.
        assertThat(validator.validate(new ExperimentController.CreateExperimentBody(
                "Verificacion", null, null, "regresion", null)))
                .isNotEmpty();
        assertThat(validator.validate(new ExperimentController.CreateExperimentBody(
                "Verificacion", null, null, "REGRESSION", null)))
                .isEmpty();
    }

    @Test
    @DisplayName("El cuerpo de dataset acota los campos que tienen columna acotada")
    void datasetRequestLengthsAreEnforced() {
        assertThat(validator.validate(new DatasetService.CreateDatasetRequest(
                "XMR-USD", "v1", "sintetico", "1d", 400L, null, null, null, null)))
                .isEmpty();

        assertThat(validator.validate(new DatasetService.CreateDatasetRequest(
                "XMR-USD-LARGO-DEMASIADO", "v1", "sintetico", "1d", 400L,
                null, null, null, null)))
                .as("symbol es VARCHAR(16): un simbolo largo terminaba en 500")
                .isNotEmpty();

        assertThat(validator.validate(new DatasetService.CreateDatasetRequest(
                "XMR-USD", "v1", "sintetico", "1d", 0L, null, null, null, null)))
                .as("ck_dataset_rows exige rows > 0")
                .isNotEmpty();

        assertThat(validator.validate(new DatasetService.CreateDatasetRequest(
                "XMR-USD", "v1", "sintetico", "1d", 1_000_000_000L, null, null, null, null)))
                .as("un rows enorme hacia que last.minusDays(...) lanzara DateTimeException")
                .isNotEmpty();
    }

    @Test
    @DisplayName("El cuerpo de password reset exige los dos campos")
    void passwordResetRequestIsValidated() {
        assertThat(validator.validate(new AuthRequests.ResetPasswordRequest(
                null, "NuevaClave#2026"))).isNotEmpty();
        assertThat(validator.validate(new AuthRequests.ResetPasswordRequest(
                "token-opaco", null))).isNotEmpty();
        assertThat(validator.validate(new AuthRequests.ResetPasswordRequest(
                "token-opaco", "NuevaClave#2026"))).isEmpty();
    }

    @Test
    @DisplayName("La politica de contrasenas acepta una clave fuerte y rechaza las debiles")
    void passwordPolicyStillHolds() {
        // La politica se expresa con una expresion regular (OWASP ASVS V2: 12
        // caracteres, mayuscula, minuscula, digito y simbolo, sin espacios).
        assertThat("Clave#Fuerte2026").matches(PasswordPolicy.REGEX);
        assertThat("corta1#A").as("demasiado corta").doesNotMatch(PasswordPolicy.REGEX);
        assertThat("sinmayusculas#2026").as("sin mayuscula").doesNotMatch(PasswordPolicy.REGEX);
        assertThat("SinDigitos#Letras").as("sin digito").doesNotMatch(PasswordPolicy.REGEX);
        assertThat("Sin Simbolos 2026").as("con espacios y sin simbolo")
                .doesNotMatch(PasswordPolicy.REGEX);
    }

    @Test
    @DisplayName("El registro exige los dos aceptes, y los acepta solo cuando son true")
    void registerConsentIsExecutableNotDecorative() {
        // R-46: @AssertTrue sobre un componente de record debe PROBARSE sobre una
        // instancia real. Si no llegara a ejecutarse, un cliente podria registrar
        // cuentas sin aceptar nada y el servidor lo permitiria en silencio.
        AuthRequests.RegisterRequest completo = new AuthRequests.RegisterRequest(
                "user@example.com", "Clave#Fuerte2026", "Usuario", true, true, true);
        assertThat(validator.validate(completo)).isEmpty();

        AuthRequests.RegisterRequest sinTerminos = new AuthRequests.RegisterRequest(
                "user@example.com", "Clave#Fuerte2026", "Usuario", false, true, false);
        assertThat(validator.validate(sinTerminos))
                .extracting(violation -> violation.getPropertyPath().toString())
                .contains("acceptTerms");

        AuthRequests.RegisterRequest sinPolitica = new AuthRequests.RegisterRequest(
                "user@example.com", "Clave#Fuerte2026", "Usuario", true, false, false);
        assertThat(validator.validate(sinPolitica))
                .extracting(violation -> violation.getPropertyPath().toString())
                .contains("acceptDataPolicy");

        // Campo ausente en el JSON => null => rechazo. Es lo que le pasa a un
        // cliente que no envia los campos nuevos (contrato R-38).
        AuthRequests.RegisterRequest nulos = new AuthRequests.RegisterRequest(
                "user@example.com", "Clave#Fuerte2026", "Usuario", null, null, null);
        assertThat(validator.validate(nulos))
                .extracting(violation -> violation.getPropertyPath().toString())
                .contains("acceptTerms", "acceptDataPolicy");

        // Marketing opcional: null no es violacion.
        AuthRequests.RegisterRequest sinMarketing = new AuthRequests.RegisterRequest(
                "user@example.com", "Clave#Fuerte2026", "Usuario", true, true, null);
        assertThat(validator.validate(sinMarketing)).isEmpty();
    }

    @Test
    @DisplayName("El reenvio de verificacion exige un correo valido")
    void resendVerificationRequestIsValidated() {
        assertThat(validator.validate(
                new AuthRequests.ResendVerificationRequest(null))).isNotEmpty();
        assertThat(validator.validate(
                new AuthRequests.ResendVerificationRequest("no-es-correo"))).isNotEmpty();
        assertThat(validator.validate(
                new AuthRequests.ResendVerificationRequest("user@example.com"))).isEmpty();
    }

    @Test
    @DisplayName("Los roles se declaran como enum, no como texto libre")
    void rolesUseTheEnumType() {
        // Jackson deserializa el enum y rechaza lo que no existe con un 400; con
        // texto libre habria que traducir un IllegalArgumentException a mano.
        // getType() devuelve la clase borrada (Set); el tipo parametrizado, que es
        // donde vive el enum, solo aparece en getGenericType().
        assertThat(AdminUserController.ChangeRoleRequest.class
                        .getRecordComponents()[1].getGenericType().getTypeName())
                .contains("com.aylzz.xmrforecast.security.Role");
    }
}
