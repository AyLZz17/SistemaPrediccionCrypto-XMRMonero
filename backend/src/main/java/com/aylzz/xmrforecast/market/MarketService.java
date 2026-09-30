package com.aylzz.xmrforecast.market;

import com.aylzz.xmrforecast.common.ApiException;
import com.aylzz.xmrforecast.common.Ids;
import com.aylzz.xmrforecast.common.PageResponse;
import com.aylzz.xmrforecast.common.QueryParams;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;

/** Lectura de datos de mercado. Solo lectura: la ingesta es responsabilidad del pipeline ML. */
@Service
public class MarketService {

    private static final String CACHE_LATEST = "market:latest:";
    private static final int MAX_CANDLES = 1000;
    private static final int MAX_SERIES = 2000;
    private static final int PREVIOUS_LOOKUP = 2;

    private final MarketDataRepository repository;

    public MarketService(MarketDataRepository repository) {
        this.repository = repository;
    }

    /**
     * Ultima cotizacion del simbolo.
     *
     * <p>La variacion se calcula contra el <em>cierre de la vela anterior</em>, que
     * es la definicion de "variacion diaria" que espera cualquier persona. Sin ese
     * segundo dato, el cliente solo podria pintar una cifra sin contexto.
     */
    @Transactional(readOnly = true)
    // La clave usa el simbolo NORMALIZADO, no el parametro crudo. Con `#symbol`,
    // `?symbol=xmr-usd` y `?symbol=XMR-USD` producian dos entradas de Redis con
    // contenido identico; el limite se pagaba el doble de lo necesario y una
    // correccion del dato no alcanzaba a la otra mitad.
    @Cacheable(value = CACHE_LATEST, key = "T(com.aylzz.xmrforecast.common.QueryParams).symbol(#symbol)")
    public QuoteResponse latest(String symbol) {
        String normalized = QueryParams.symbol(symbol);
        List<MarketData> last = repository.findLatest(normalized, PageRequest.of(0, PREVIOUS_LOOKUP));
        if (last.isEmpty()) {
            throw ApiException.notFound("MARKET_DATA_NOT_FOUND",
                    "No hay datos de mercado para el simbolo " + normalized + ".");
        }
        MarketData current = last.get(0);
        MarketData previous = last.size() > 1 ? last.get(1) : null;
        return QuoteResponse.of(current, previous);
    }

    /** Velas en un rango, paginadas. Cada vela lleva su variacion intradia. */
    @Transactional(readOnly = true)
    public PageResponse<CandleResponse> range(String symbol, Instant from, Instant to, int page, int size) {
        String normalized = QueryParams.symbol(symbol);
        // Un rango invertido se corrige en lugar de devolver una pagina vacia silenciosa.
        Instant start = from == null ? MarketDataRepository.RANGE_START : from;
        Instant end = to == null ? MarketDataRepository.RANGE_END : to;
        if (start.isAfter(end)) {
            Instant tmp = start;
            start = end;
            end = tmp;
        }
        Pageable pageable = PageRequest.of(QueryParams.page(page), QueryParams.size(size, MAX_CANDLES));
        Page<MarketData> result = repository.findRange(normalized, start, end, pageable);
        return PageResponse.of(result, CandleResponse::of);
    }

    /** Serie completa en orden cronologico ascendente, para graficos y verificacion. */
    @Transactional(readOnly = true)
    public List<CandleResponse> series(String symbol, Instant from, Instant to, int limit) {
        Pageable pageable = PageRequest.of(0, Math.min(Math.max(limit, 1), MAX_SERIES));
        return repository.findRange(QueryParams.symbol(symbol),
                        from == null ? MarketDataRepository.RANGE_START : from,
                        to == null ? MarketDataRepository.RANGE_END : to,
                        pageable)
                .getContent().stream().map(CandleResponse::of).toList();
    }

    @Transactional(readOnly = true)
    public long countFor(String symbol) {
        return repository.countBySymbol(QueryParams.symbol(symbol));
    }

    /** Variacion porcentual segura frente a division por cero. */
    static BigDecimal percentChange(BigDecimal from, BigDecimal to) {
        if (from == null || to == null || from.signum() == 0) {
            return null;
        }
        return to.subtract(from)
                .divide(from, 8, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .setScale(4, RoundingMode.HALF_UP);
    }

    /**
     * Cotizacion actual. Es la forma que consume el encabezado y la primera
     * tarjeta del panel: precio, variacion contra el cierre anterior y momento
     * de mercado, que son los tres datos que un usuario mira de un vistazo.
     */
    public record QuoteResponse(
            String symbol,
            BigDecimal price,
            BigDecimal open,
            BigDecimal high,
            BigDecimal low,
            BigDecimal previousClose,
            BigDecimal change,
            BigDecimal changePercent,
            BigDecimal volume,
            Instant marketTime,
            Instant updatedAt,
            String source
    ) {
        static QuoteResponse of(MarketData current, MarketData previous) {
            BigDecimal previousClose = previous == null ? null : previous.getClose();
            BigDecimal change = previousClose == null ? null : current.getClose().subtract(previousClose);
            return new QuoteResponse(
                    current.getSymbol(),
                    current.getClose(),
                    current.getOpen(),
                    current.getHigh(),
                    current.getLow(),
                    previousClose,
                    change,
                    percentChange(previousClose, current.getClose()),
                    current.getVolume(),
                    current.getOpenedAt(),
                    current.getIngestedAt(),
                    current.getSource());
        }
    }

    /**
     * Vela OHLCV. La variacion es <strong>intradia</strong> (cierre frente a
     * apertura de la misma vela): es la unica que puede calcularse con los datos de
     * una fila aislada, y por eso no depende de que la vela vecina este cargada.
     */
    public record CandleResponse(
            String id,
            String date,
            BigDecimal open,
            BigDecimal high,
            BigDecimal low,
            BigDecimal close,
            BigDecimal volume,
            BigDecimal change,
            BigDecimal changePercent
    ) {
        static CandleResponse of(MarketData data) {
            return new CandleResponse(
                    Ids.of(data.getId()),
                    data.getOpenedAt().toString(),
                    data.getOpen(),
                    data.getHigh(),
                    data.getLow(),
                    data.getClose(),
                    data.getVolume(),
                    data.getClose().subtract(data.getOpen()),
                    percentChange(data.getOpen(), data.getClose()));
        }
    }
}
