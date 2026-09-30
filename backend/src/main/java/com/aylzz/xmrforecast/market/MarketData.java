package com.aylzz.xmrforecast.market;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Velas OHLCV de XMR-USD. La unicidad (symbol, source, opened_at) en la base de
 * datos impide duplicar una vela aunque la ingesta se re-ejecute (seccion 9).
 */
@Entity
@Table(name = "market_data")
@Getter
@Setter
public class MarketData {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 16)
    private String symbol;

    @Column(nullable = false, length = 32)
    private String source;

    @Column(name = "opened_at", nullable = false)
    private Instant openedAt;

    @Column(nullable = false, precision = 20, scale = 8)
    private BigDecimal open;

    @Column(nullable = false, precision = 20, scale = 8)
    private BigDecimal high;

    @Column(nullable = false, precision = 20, scale = 8)
    private BigDecimal low;

    @Column(nullable = false, precision = 20, scale = 8)
    private BigDecimal close;

    @Column(precision = 28, scale = 8)
    private BigDecimal volume;

    @Column(name = "ingested_at", nullable = false)
    private Instant ingestedAt = Instant.now();
}