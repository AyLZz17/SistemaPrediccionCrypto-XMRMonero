package com.aylzz.xmrforecast.audit;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface AuditEventRepository extends JpaRepository<AuditEvent, Long> {

    Page<AuditEvent> findAllByOrderByCreatedAtDesc(Pageable pageable);

    List<AuditEvent> findTop100ByActorUserIdOrderByCreatedAtDesc(Long actorUserId);

    @Query("""
            select a.action, count(a) from AuditEvent a
            where a.createdAt >= :since group by a.action order by count(a) desc
            """)
    List<Object[]> countByActionSince(@Param("since") Instant since);
}