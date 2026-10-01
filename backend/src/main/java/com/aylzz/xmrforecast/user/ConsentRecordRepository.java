package com.aylzz.xmrforecast.user;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ConsentRecordRepository extends JpaRepository<ConsentRecord, Long> {

    /** Ultima aceptacion vigente de un documento por un usuario. */
    List<ConsentRecord> findTop50ByUserIdAndConsentTypeOrderByAcceptedAtDesc(
            Long userId, ConsentType consentType);
}
