package com.aylzz.xmrforecast.mlmodel;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface MlModelRepository extends JpaRepository<MlModel, Long> {

    Optional<MlModel> findByModelKey(String modelKey);

    Page<MlModel> findAllByOrderByModelKeyAsc(Pageable pageable);
}