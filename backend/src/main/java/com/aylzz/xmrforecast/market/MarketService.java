package com.aylzz.xmrforecast.market;

import com.aylzz.xmrforecast.common.ApiException;
import com.aylzz.xmrforecast.common.PageResponse;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Lectura de datos de mercado. Solo lectura: la ingesta es responsabilidad del pipeline ML. */
@Service
public class MarketService {

    private static final String CACHE_LATEST = "market:latest:";
    private static final String CACHE_RANGE = "market:range:";

    private final MarketDataRepository repository;

    public MarketService(MarketDataRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    @Cacheable(value = CACHE_LATEST, key = "#symbol")
    public MarketDataResponse latest(String symbol) {
        return repository.findFirstBySymbolOrderByOpenedAtDesc(normalize(symbol))
                .map(MarketDataResponse::from)
                .orElseThrow(() -> ApiException.notFound("MARKET_DATA_NOT_FOUND",
                        "No hay datos de mercado para el simbolo " + symbol + "."));
    }

    @Transactional(readOnly = true)
    public PageResponse<MarketDataResponse> range(String symbol, Instant from, Instant to, int page, int size) {
        String normalized = normalize(symbol);
        // Un rango invertido se corrige en lugar de devolver una pagina vacia silenciosa.
        Instant start = from;
        Instant end = to;
        if (start != null && end != null && start.isAfter(end)) {
            Instant tmp = start;
            start = end;
            end = tmp;
        }
        Pageable pageable = PageRequest.of(Math.max(page, 0), clampSize(size));
        Page<MarketData> result = repository.findRange(normalized, start, end, pageable);
        return PageResponse.of(result, MarketDataResponse::from);
    }

    /** Serie completa en orden cronologico ascendente, para graficos y verificacion. */
    @Transactional(readOnly = true)
    public List<MarketDataResponse> series(String symbol, Instant from, Instant to, int limit) {
        Pageable pageable = PageRequest.of(0, Math.min(Math.max(limit, 1), 2000));
        return repository.findRange(normalize(symbol), from, to, pageable)
                .getContent().stream().map(MarketDataResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public long countFor(String symbol) {
        return repository.countBySymbol(normalize(symbol));
    }

    private static String normalize(String symbol) {
        return symbol == null ? "XMR-USD" : symbol.trim().toUpperCase(java.util.Locale.ROOT);
    }

    private static int clampSize(int size) {
        return Math.min(Math.max(size, 1), 1000);
    }

    /** Vista publica de una vela OHLCV. */
    public record MarketDataResponse(
            Long id,
            String symbol,
            String source,
            Instant openedAt,
            BigDecimal open,
            BigDecimal high,
            BigDecimal low,
            BigDecimal close,
            BigDecimal volume
    ) {
        static MarketDataResponse from(MarketData data) {
            return new MarketDataResponse(data.getId(), data.getSymbol(), data.getSource(),
                    data.getOpenedAt(), data.getOpen(), data.getHigh(), data.getLow(),
                    data.getClose(), data.getVolume());
        }
    }
}