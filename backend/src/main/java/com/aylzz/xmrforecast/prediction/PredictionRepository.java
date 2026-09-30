package com.aylzz.xmrforecast.prediction;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface PredictionRepository extends JpaRepository<Prediction, Long> {

    Page<Prediction> findAllByOrderByCreatedAtDesc(Pageable pageable);

    Page<Prediction> findAllBySymbolOrderByTargetDateDesc(String symbol, Pageable pageable);

    /**
     * Busqueda acotada al propietario. Es la base de la proteccion IDOR: la
     * prediccion de otro usuario simplemente no aparece en la consulta.
     */
    Page<Prediction> findAllByRequestedByOrderByCreatedAtDesc(Long requestedBy, Pageable pageable);

    /**
     * Filtro por simbolo <strong>siempre combinado con el propietario</strong>.
     *
     * <p>Existe como metodo derivado separado, y no como un filtro opcional
     * aplicado en memoria, para que ningun camino de codigo pueda consultar por
     * simbolo sin acotar por usuario (OWASP API1 / IDOR).
     */
    Page<Prediction> findAllByRequestedByAndSymbolOrderByTargetDateDesc(
            Long requestedBy, String symbol, Pageable pageable);

    Optional<Prediction> findByModelVersionIdAndSymbolAndTargetDate(
            Long modelVersionId, String symbol, LocalDate targetDate);

    List<Prediction> findAllByTargetDateBetweenAndSymbolOrderByTargetDateAsc(
            LocalDate from, LocalDate to, String symbol);
}