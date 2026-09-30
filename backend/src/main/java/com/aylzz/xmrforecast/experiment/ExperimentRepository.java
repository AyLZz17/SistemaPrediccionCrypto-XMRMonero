package com.aylzz.xmrforecast.experiment;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ExperimentRepository extends JpaRepository<Experiment, Long> {

    Optional<Experiment> findByCode(String code);

    boolean existsByCode(String code);

    Page<Experiment> findAllByOrderByCreatedAtDesc(Pageable pageable);

    Page<Experiment> findAllByStatusOrderByCreatedAtDesc(Experiment.ExperimentStatus status, Pageable pageable);
}