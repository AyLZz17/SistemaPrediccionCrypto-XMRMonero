package com.aylzz.xmrforecast.metrics;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MetricRepository extends JpaRepository<Metric, Long> {

    List<Metric> findAllByRunIdOrderBySplitAsc(Long runId);

    Optional<Metric> findByRunIdAndSplitAndModelVersionId(Long runId, Metric.Split split, Long modelVersionId);

    List<Metric> findAllByModelVersionId(Long modelVersionId);

    /** Comparacion: todas las metricas de una particion de una corrida, por modelo. */
    List<Metric> findAllByRunIdAndSplit(Long runId, Metric.Split split);

    /**
     * Igual que {@link #findAllByRunIdAndSplit} pero para varias corridas en una
     * sola consulta.
     *
     * <p>Existe para evitar N+1: al buscar la corrida mas reciente con metricas
     * habia que preguntar por cada corrida del experimento. Con 20 corridas, eran
     * 20 consultas por particion, y {@code experimentMetrics} la hacia dos veces.
     */
    List<Metric> findAllByRunIdInAndSplit(java.util.Collection<Long> runIds, Metric.Split split);
}