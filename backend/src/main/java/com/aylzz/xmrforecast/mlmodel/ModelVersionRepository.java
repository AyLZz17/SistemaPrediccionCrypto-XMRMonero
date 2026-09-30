package com.aylzz.xmrforecast.mlmodel;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ModelVersionRepository extends JpaRepository<ModelVersion, Long> {

    Optional<ModelVersion> findByModelIdAndVersion(Long modelId, String version);

    Optional<ModelVersion> findByModelIdAndChampionTrue(Long modelId);

    Page<ModelVersion> findAllByModelIdOrderByCreatedAtDesc(Long modelId, Pageable pageable);

    List<ModelVersion> findAllByRunId(Long runId);

    Optional<ModelVersion> findFirstByArtifactSha256(String artifactSha256);

    /**
     * Deja un unico campeon por modelo. El indice unico parcial de la tabla es la
     * garantia final; este UPDATE deja el estado coherente antes de insertar.
     */
    @Modifying
    @Query("""
            update ModelVersion v set v.champion = false
            where v.modelId = :modelId and v.champion = true
            """)
    int demoteChampions(@Param("modelId") Long modelId);
}