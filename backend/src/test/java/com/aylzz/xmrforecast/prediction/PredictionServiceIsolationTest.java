package com.aylzz.xmrforecast.prediction;

import com.aylzz.xmrforecast.common.ApiException;
import com.aylzz.xmrforecast.ml.MlServiceClient;
import com.aylzz.xmrforecast.mlmodel.MlModel;
import com.aylzz.xmrforecast.mlmodel.MlModelRepository;
import com.aylzz.xmrforecast.mlmodel.ModelVersion;
import com.aylzz.xmrforecast.mlmodel.ModelVersionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Aislamiento entre usuarios en el servicio de predicciones.
 *
 * <p>Existia una fuga horizontal: el listado con filtro por simbolo consultaba
 * {@code findAllBySymbolOrderByTargetDateDesc}, que no lleva el propietario. Con
 * solo anadir el parametro {@code symbol} bastaba para listar las predicciones
 * de todos los usuarios. Estos tests fijan que ambos caminos consultan siempre
 * un metodo acotado por {@code requestedBy}.
 */
class PredictionServiceIsolationTest {

    private PredictionRepository predictionRepository;
    private ModelVersionRepository modelVersionRepository;
    private MlModelRepository modelRepository;
    private MlServiceClient mlClient;
    private PredictionService service;

    @BeforeEach
    void setUp() {
        predictionRepository = mock(PredictionRepository.class);
        modelVersionRepository = mock(ModelVersionRepository.class);
        modelRepository = mock(MlModelRepository.class);
        mlClient = mock(MlServiceClient.class);
        service = new PredictionService(predictionRepository, modelVersionRepository,
                modelRepository, mlClient);
    }

    private static Prediction prediction(Long id, Long requestedBy) {
        Prediction prediction = new Prediction();
        prediction.setId(id);
        prediction.setSymbol("XMR-USD");
        prediction.setTargetDate(LocalDate.of(2026, 6, 1));
        prediction.setPredictedClose(new BigDecimal("170.5"));
        prediction.setPredictedDirection(Prediction.Direction.UP);
        prediction.setModelVersionId(1L);
        prediction.setDatasetVersionId(1L);
        prediction.setArtifactSha256("a".repeat(64));
        prediction.setConfigSha256("b".repeat(64));
        prediction.setRequestedBy(requestedBy);
        prediction.setStatus(Prediction.Status.READY);
        return prediction;
    }

    @Nested
    @DisplayName("Listado")
    class Listing {

        @Test
        @DisplayName("sin filtro de simbolo consulta acotado por propietario")
        void listsOnlyTheCallersPredictions() {
            Pageable pageable = PageRequest.of(0, 20);
            when(predictionRepository.findAllByRequestedByOrderByCreatedAtDesc(7L, pageable))
                    .thenReturn(new PageImpl<>(java.util.List.of(prediction(1L, 7L))));

            var page = service.listForUser(7L, null, 0, 20);

            assertThat(page.items()).hasSize(1);
            verify(predictionRepository).findAllByRequestedByOrderByCreatedAtDesc(7L, pageable);
            verify(predictionRepository, never()).findAllByOrderByCreatedAtDesc(any());
        }

        @Test
        @DisplayName("con filtro de simbolo sigue acotado por propietario")
        void symbolFilterDoesNotDropTheOwnerConstraint() {
            Pageable pageable = PageRequest.of(0, 20);
            when(predictionRepository
                    .findAllByRequestedByAndSymbolOrderByTargetDateDesc(7L, "XMR-USD", pageable))
                    .thenReturn(new PageImpl<>(java.util.List.of()));

            service.listForUser(7L, "xmr-usd", 0, 20);

            // La fuga original era precisamente la ausencia de `requestedBy` aqui.
            verify(predictionRepository)
                    .findAllByRequestedByAndSymbolOrderByTargetDateDesc(7L, "XMR-USD", pageable);
            verify(predictionRepository, never()).findAllBySymbolOrderByTargetDateDesc(anyString(), any());
        }
    }

    @Nested
    @DisplayName("Lectura individual")
    class SingleRead {

        @Test
        void hidesAnotherUsersPredictionBehindANotFound() {
            when(predictionRepository.findById(99L)).thenReturn(Optional.of(prediction(99L, 3L)));

            // 404 y no 403: un 403 confirmaria que el recurso existe.
            assertThatThrownBy(() -> service.getOne(99L, 7L, false))
                    .isInstanceOf(ApiException.class)
                    .satisfies(ex -> {
                        assertThat(((ApiException) ex).getStatus()).isEqualTo(404);
                        assertThat(((ApiException) ex).getCode()).isEqualTo("PREDICTION_NOT_FOUND");
                    });
        }

        @Test
        void anAdminCanReadAnyPrediction() {
            when(predictionRepository.findById(99L)).thenReturn(Optional.of(prediction(99L, 3L)));
            when(modelVersionRepository.findById(1L)).thenReturn(Optional.of(modelVersion()));

            assertThat(service.getOne(99L, 7L, true).id()).isEqualTo("99");
        }
    }

    private static ModelVersion modelVersion() {
        ModelVersion version = new ModelVersion();
        version.setId(1L);
        version.setModelId(5L);
        version.setVersion("1");
        version.setArtifactSha256("a".repeat(64));
        version.setConfigSha256("b".repeat(64));
        version.setDatasetVersionId(1L);
        version.setIntegrityVerified(true);
        version.setCreatedAt(Instant.now());
        return version;
    }

    @Nested
    @DisplayName("Resolucion del modelo")
    class ModelResolution {

        @Test
        @DisplayName("un modelo sin campeon se rechaza: R-24 exige eleccion por validacion")
        void aModelWithoutAChampionIsRejected() {
            when(modelRepository.findById(5L)).thenReturn(Optional.of(model()));
            when(modelVersionRepository.findByModelIdAndChampionTrue(5L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.create(
                    new PredictionService.CreatePredictionRequest(5L, null, "XMR-USD", null, null), 7L))
                    .isInstanceOf(ApiException.class)
                    .satisfies(ex -> assertThat(((ApiException) ex).getCode())
                            .isEqualTo("NO_CHAMPION_VERSION"));
        }

        @Test
        @DisplayName("un modelo sin integridad verificada no se usa (R-28)")
        void anUnverifiedVersionIsRejected() {
            ModelVersion version = modelVersion();
            version.setIntegrityVerified(false);
            when(modelVersionRepository.findById(1L)).thenReturn(Optional.of(version));

            assertThatThrownBy(() -> service.create(
                    new PredictionService.CreatePredictionRequest(null, 1L, "XMR-USD", null, null), 7L))
                    .isInstanceOf(ApiException.class)
                    .satisfies(ex -> assertThat(((ApiException) ex).getCode())
                            .isEqualTo("MODEL_NOT_VERIFIED"));
        }

        @Test
        @DisplayName("sin modelo ni version la peticion se rechaza con un codigo estable")
        void requiresAModelOrAVersion() {
            assertThatThrownBy(() -> new PredictionService.CreatePredictionRequest(
                    null, null, "XMR-USD", null, null))
                    .isInstanceOf(ApiException.class)
                    .satisfies(ex -> assertThat(((ApiException) ex).getCode())
                            .isEqualTo("MISSING_MODEL"));
        }

        @Test
        @DisplayName("la idempotencia evita recalcular y duplicar")
        void returnsTheExistingPredictionInsteadOfRecalculating() {
            when(modelVersionRepository.findById(1L)).thenReturn(Optional.of(modelVersion()));
            when(predictionRepository.findByModelVersionIdAndSymbolAndTargetDate(
                    anyLong(), anyString(), any())).thenReturn(Optional.of(prediction(11L, 7L)));

            var response = service.create(new PredictionService.CreatePredictionRequest(
                    null, 1L, "xmr-usd", LocalDate.of(2026, 6, 1), null), 7L);

            assertThat(response.id()).isEqualTo("11");
            verify(mlClient, never()).predict(any());
        }
    }

    @Test
    @DisplayName("la respuesta publica ids opacos, nombre del modelo y aviso legal")
    void responseCarriesThePublicContract() {
        when(modelVersionRepository.findById(1L)).thenReturn(Optional.of(modelVersion()));
        when(modelRepository.findById(5L)).thenReturn(Optional.of(model()));
        when(predictionRepository.findAllByRequestedByOrderByCreatedAtDesc(
                anyLong(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(java.util.List.of(prediction(1L, 7L))));

        var response = service.listForUser(7L, null, 0, 20).items().get(0);

        // El id se publica como cadena opaca, no como numero.
        assertThat(response.id()).isEqualTo("1");
        assertThat(response.modelId()).isEqualTo("5");
        assertThat(response.modelName()).isEqualTo("lstm_base");
        assertThat(response.status()).isEqualTo(Prediction.Status.READY);
        // R-11: el aviso legal viaja tambien en la documentacion de la API.
        assertThat(response.disclaimer().toLowerCase())
                .contains("no es asesoria financiera")
                .contains("no promete rentabilidad");
    }

    private static MlModel model() {
        MlModel model = new MlModel();
        model.setId(5L);
        model.setModelKey("lstm_base");
        model.setFamily(MlModel.Family.LSTM);
        model.setTaskType(MlModel.TaskType.REGRESSION);
        model.setCreatedAt(Instant.now());
        return model;
    }

    @Test
    @DisplayName("el aviso legal no promete rentabilidad ni simula trading")
    void theDisclaimerIsExplicit() {
        assertThat(PredictionService.DISCLAIMER).contains("asesoria financiera");
        assertThat(PredictionService.DISCLAIMER.toLowerCase())
                .contains("no promete rentabilidad")
                .contains("no simula operaciones");
    }
}
