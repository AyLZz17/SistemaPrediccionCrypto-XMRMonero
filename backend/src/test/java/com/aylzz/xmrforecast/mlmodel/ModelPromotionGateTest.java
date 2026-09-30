package com.aylzz.xmrforecast.mlmodel;

import com.aylzz.xmrforecast.audit.AuditEvent;
import com.aylzz.xmrforecast.audit.AuditEventRepository;
import com.aylzz.xmrforecast.audit.AuditService;
import com.aylzz.xmrforecast.common.ApiException;
import com.aylzz.xmrforecast.security.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Gates de promocion de campeon (R-28 y R-24).
 *
 * <p>Promover un campeon decide que modelo usa la plataforma. Aceptar una
 * version sin artefacto verificado, sin procedencia o seleccionada sobre el
 * conjunto de prueba haria que la cifra publicada no se correspondiera con el
 * criterio de seleccion, que es justo lo que el proyecto existe para impedir.
 */
class ModelPromotionGateTest {

    private MlModelRepository modelRepository;
    private ModelVersionRepository versionRepository;
    private AuditService auditService;
    private ModelService service;

    @BeforeEach
    void setUp() {
        modelRepository = mock(MlModelRepository.class);
        versionRepository = mock(ModelVersionRepository.class);
        // AuditService real contra un repositorio simulado: asi se verifica que
        // la promocion deja rastro sin abrir una transaccion de base de datos.
        auditService = new AuditService(mock(AuditEventRepository.class), null);
        service = new ModelService(modelRepository, versionRepository, auditService);
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

    private static ModelVersion version(ModelVersion.SelectedOn selectedOn, boolean verified) {
        ModelVersion version = new ModelVersion();
        version.setId(9L);
        version.setModelId(5L);
        version.setVersion("2");
        version.setRunId(3L);
        version.setDatasetVersionId(1L);
        version.setArtifactSha256("a".repeat(64));
        version.setConfigSha256("b".repeat(64));
        version.setSelectedOn(selectedOn);
        version.setIntegrityVerified(verified);
        version.setCreatedAt(Instant.now());
        return version;
    }

    @Test
    @DisplayName("una version verificada, con procedencia y elegida por validacion, se promueve")
    void promotesAValidVersion() {
        when(modelRepository.findById(5L)).thenReturn(Optional.of(model()));
        when(versionRepository.findById(9L)).thenReturn(Optional.of(
                version(ModelVersion.SelectedOn.VALIDATION, true)));
        when(versionRepository.findByModelIdAndChampionTrue(5L)).thenReturn(Optional.empty());

        var response = service.promote(5L, 9L, 1L, Role.ADMIN);

        assertThat(response.isChampion()).isTrue();
        // Se registra como MANUAL porque un ADMIN lo decidio, no el pipeline.
        assertThat(response.selectedOn()).isEqualTo("MANUAL");
    }

    @Test
    @DisplayName("sin integridad verificada se rechaza con 422 y no se promueve")
    void rejectsAnUnverifiedArtifact() {
        when(modelRepository.findById(5L)).thenReturn(Optional.of(model()));
        when(versionRepository.findById(9L)).thenReturn(Optional.of(
                version(ModelVersion.SelectedOn.VALIDATION, false)));

        assertThatThrownBy(() -> service.promote(5L, 9L, 1L, Role.ADMIN))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    assertThat(((ApiException) ex).getStatus()).isEqualTo(422);
                    assertThat(((ApiException) ex).getCode()).isEqualTo("INTEGRITY_NOT_VERIFIED");
                });
        verify(versionRepository, never()).save(any());
    }

    @Test
    @DisplayName("sin corrida asociada se rechaza: no hay procedencia (R-28)")
    void rejectsAVersionWithoutProvenance() {
        ModelVersion orphan = version(ModelVersion.SelectedOn.VALIDATION, true);
        orphan.setRunId(null);
        when(modelRepository.findById(5L)).thenReturn(Optional.of(model()));
        when(versionRepository.findById(9L)).thenReturn(Optional.of(orphan));

        assertThatThrownBy(() -> service.promote(5L, 9L, 1L, Role.ADMIN))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode()).isEqualTo("NO_PROVENANCE"));
    }

    @Test
    @DisplayName("seleccionada sobre el conjunto de prueba se rechaza (R-24)")
    void rejectsAChampionChosenOnTheTestSet() {
        when(modelRepository.findById(5L)).thenReturn(Optional.of(model()));
        when(versionRepository.findById(9L)).thenReturn(Optional.of(
                version(ModelVersion.SelectedOn.TEST, true)));

        assertThatThrownBy(() -> service.promote(5L, 9L, 1L, Role.ADMIN))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode())
                        .isEqualTo("SELECTED_ON_TEST"));
    }

    @Test
    @DisplayName("una version de otro modelo no se confirma ni se niega: 404")
    void doesNotLeakTheExistenceOfAnotherModelsVersion() {
        when(modelRepository.findById(5L)).thenReturn(Optional.of(model()));
        ModelVersion foreign = version(ModelVersion.SelectedOn.VALIDATION, true);
        foreign.setModelId(77L);
        when(versionRepository.findById(9L)).thenReturn(Optional.of(foreign));

        // Un 403 confirmaria que la version existe. Un 404 no dice nada (OWASP API1).
        assertThatThrownBy(() -> service.promote(5L, 9L, 1L, Role.ADMIN))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    assertThat(((ApiException) ex).getStatus()).isEqualTo(404);
                    assertThat(((ApiException) ex).getCode()).isEqualTo("MODEL_VERSION_NOT_FOUND");
                });
    }

    @Test
    @DisplayName("la promocion libera el campeon anterior: solo puede haber uno")
    void demotesThePreviousChampion() {
        when(modelRepository.findById(5L)).thenReturn(Optional.of(model()));
        when(versionRepository.findById(9L)).thenReturn(Optional.of(
                version(ModelVersion.SelectedOn.VALIDATION, true)));
        ModelVersion previous = version(ModelVersion.SelectedOn.MANUAL, true);
        previous.setId(8L);
        previous.setChampion(true);
        when(versionRepository.findByModelIdAndChampionTrue(5L)).thenReturn(Optional.of(previous));

        service.promote(5L, 9L, 1L, Role.ADMIN);

        assertThat(previous.isChampion()).isFalse();
        ArgumentCaptor<ModelVersion> saved = ArgumentCaptor.forClass(ModelVersion.class);
        verify(versionRepository, org.mockito.Mockito.atLeast(2)).save(saved.capture());
        assertThat(saved.getAllValues().stream().filter(ModelVersion::isChampion).count()).isEqualTo(1);
    }

    @Test
    @DisplayName("la promocion queda auditada con el actor y el digest del artefacto")
    void recordsThePromotionInTheAuditTrail() {
        AuditEventRepository auditRepository = mock(AuditEventRepository.class);
        ModelService audited = new ModelService(modelRepository, versionRepository,
                new AuditService(auditRepository, null));
        when(modelRepository.findById(5L)).thenReturn(Optional.of(model()));
        when(versionRepository.findById(9L)).thenReturn(Optional.of(
                version(ModelVersion.SelectedOn.VALIDATION, true)));
        when(versionRepository.findByModelIdAndChampionTrue(5L)).thenReturn(Optional.empty());

        audited.promote(5L, 9L, 1L, Role.ADMIN);

        ArgumentCaptor<AuditEvent> event = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditRepository).save(event.capture());
        assertThat(event.getValue().getAction()).isEqualTo("MODEL_PROMOTED");
        assertThat(event.getValue().getOutcome()).isEqualTo(AuditEvent.Outcome.SUCCESS);
        assertThat(event.getValue().getActorUserId()).isEqualTo(1L);
        assertThat(event.getValue().getDetails()).containsEntry("modelKey", "lstm_base");
    }

    @Test
    @DisplayName("el catalogo publica el identificador de la version campeon")
    void theCatalogExposesTheChampionVersionId() {
        when(modelRepository.findAllByOrderByModelKeyAsc(any()))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(
                        java.util.List.of(model())));
        // El catalogo resuelve los campeones de la pagina en una sola consulta
        // (findAllByModelIdInAndChampionTrue), no con un findByModelId por fila.
        when(versionRepository.findAllByModelIdInAndChampionTrue(any()))
                .thenReturn(java.util.List.of(version(ModelVersion.SelectedOn.VALIDATION, true)));

        var catalog = service.listModels(0, 20);

        assertThat(catalog.items()).hasSize(1);
        assertThat(catalog.items().get(0).championVersionId()).isEqualTo("9");
        assertThat(catalog.items().get(0).name()).isEqualTo("lstm_base");
    }

    @Test
    @DisplayName("un modelo sin campeon lo publica como null, no como un id inventado")
    void aModelWithoutAChampionPublishesNull() {
        when(modelRepository.findAllByOrderByModelKeyAsc(any()))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(
                        java.util.List.of(model())));
        when(versionRepository.findAllByModelIdInAndChampionTrue(any()))
                .thenReturn(java.util.List.of());

        assertThat(service.listModels(0, 20).items().get(0).championVersionId()).isNull();
    }
}
