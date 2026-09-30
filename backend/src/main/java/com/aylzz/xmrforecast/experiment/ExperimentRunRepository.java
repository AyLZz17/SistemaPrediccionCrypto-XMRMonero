package com.aylzz.xmrforecast.experiment;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ExperimentRunRepository extends JpaRepository<ExperimentRun, Long> {

    Optional<ExperimentRun> findByExperimentIdAndRunKey(Long experimentId, String runKey);

    Page<ExperimentRun> findAllByExperimentIdOrderByCreatedAtDesc(Long experimentId, Pageable pageable);

    List<ExperimentRun> findAllByExperimentId(Long experimentId);

    /** Corridas que quedaron RUNNING tras un reinicio: candidatas a reintento. */
    List<ExperimentRun> findAllByStatus(ExperimentRun.RunStatus status);
}