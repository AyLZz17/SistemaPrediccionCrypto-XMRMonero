package com.aylzz.xmrforecast.market;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface MarketDataRepository extends JpaRepository<MarketData, Long> {

    Optional<MarketData> findFirstBySymbolOrderByOpenedAtDesc(String symbol);

    Page<MarketData> findBySymbolOrderByOpenedAtDesc(String symbol, Pageable pageable);

    /**
     * Rango temporal ascendente para el modelado. Se pagina en orden ASC para que
     * las ventanas conserven el orden cronologico exigido por R-01.
     */
    @Query("""
            select m from MarketData m
            where m.symbol = :symbol and m.openedAt >= :from and m.openedAt <= :to
            order by m.openedAt asc
            """)
    Page<MarketData> findRange(@Param("symbol") String symbol,
                               @Param("from") Instant from,
                               @Param("to") Instant to,
                               Pageable pageable);

    long countBySymbol(String symbol);
}