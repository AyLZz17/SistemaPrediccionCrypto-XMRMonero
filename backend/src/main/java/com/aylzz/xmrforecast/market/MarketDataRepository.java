package com.aylzz.xmrforecast.market;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface MarketDataRepository extends JpaRepository<MarketData, Long> {

    Optional<MarketData> findFirstBySymbolOrderByOpenedAtDesc(String symbol);

    Page<MarketData> findBySymbolOrderByOpenedAtDesc(String symbol, Pageable pageable);

    /**
     * Rango temporal ascendente para el modelado. Se pagina en orden ASC para que
     * las ventanas conserven el orden cronologico exigido por R-01.
     *
     * <p><strong>Las cotas NO son opcionales.</strong> La version anterior las
     * aceptaba como nulas y escribia {@code (:from is null or m.openedAt >=
     * :from)}. Cuando el cliente omite el rango —que es justo el caso por
     * defecto, porque la interfaz solo pagina— PostgreSQL rechaza la consulta con
     * {@code could not determine data type of parameter $2}: un parametro usado
     * en {@code IS NULL} llega sin tipo y no hay nada de donde deducirlo. El
     * servicio traduce "sin cota" a los extremos de {@link #RANGE_START} y
     * {@link #RANGE_END}, de modo que la consulta siempre lleva dos
     * {@code timestamptz} tipados.
     */
    @Query("""
            select m from MarketData m
            where m.symbol = :symbol
              and m.openedAt >= :from
              and m.openedAt <= :to
            order by m.openedAt asc
            """)
    Page<MarketData> findRange(@Param("symbol") String symbol,
                               @Param("from") Instant from,
                               @Param("to") Instant to,
                               Pageable pageable);

    /**
     * Extremos usados cuando el cliente no acota el rango. 2000 es anterior a
     * los datos de alright y 2100 es posterior a cualquier dato posible, y ambos
     * caben de sobra en el rango de {@code timestamptp} de PostgreSQL
     * (4713 a. C. - 294276 d. C.). No se usan {@code Instant.MIN}/{@code MAX}
     * porque estan fuera de ese rango y fallarian igual.
     */
    Instant RANGE_START = Instant.parse("2000-01-01T00:00:00Z");
    Instant RANGE_END = Instant.parse("2100-01-01T00:00:00Z");

    /**
     * Ultimas velas en orden descendente. Se usa para la cotizacion actual, que
     * necesita la vela previa para calcular la variacion diaria.
     */
    @Query("""
            select m from MarketData m
            where m.symbol = :symbol
            order by m.openedAt desc
            """)
    List<MarketData> findLatest(@Param("symbol") String symbol, Pageable pageable);

    long countBySymbol(String symbol);

    /** Momento en que el ultimo dato fue ingerido: "ultima actualizacion" del dashboard. */
    Optional<MarketData> findTopByOrderByIngestedAtDesc();
}
