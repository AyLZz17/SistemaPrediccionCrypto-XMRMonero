package com.aylzz.xmrforecast.dataset;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface DatasetVersionRepository extends JpaRepository<DatasetVersion, Long> {

    Optional<DatasetVersion> findBySymbolAndVersion(String symbol, String version);

    Page<DatasetVersion> findAllByOrderByCreatedAtDesc(Pageable pageable);

    Optional<DatasetVersion> findFirstByChecksumSha256(String checksumSha256);
}