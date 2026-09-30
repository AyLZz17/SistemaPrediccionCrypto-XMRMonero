package com.aylzz.xmrforecast.metrics;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MetricRepository extends JpaRepository<Metric, Long> {

    List<Metric> findAllByRunIdOrderBySplitAsc(Long runId);

    Optional<Metric> findByRunIdAndSplitAndModelVersionId(Long runId, Metric.Split split, Long modelVersionId);

    List<Metric> findAllByModelVersionId(Long modelVersionId);

    /** Comparacion: todas las metricas de TEST de una corrida, por modelo. */
    List<Metric> findAllByRunIdAndSplit(Long runId, Metric.Split split);
}