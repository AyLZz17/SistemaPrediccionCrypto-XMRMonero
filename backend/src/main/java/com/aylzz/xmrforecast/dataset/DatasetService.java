package com.aylzz.xmrforecast.dataset;

import com.aylzz.xmrforecast.common.ApiException;
import com.aylzz.xmrforecast.common.Ids;
import com.aylzz.xmrforecast.common.PageResponse;
import com.aylzz.xmrforecast.common.QueryParams;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Versionado de datasets (R-28).
 *
 * <p>Un dataset solo existe si trae checksum y procedencia. La unicidad de
 * (symbol, version) impide registrar dos veces la misma version, y el indice
 * sobre {@code checksum_sha256} permite detectar que dos versiones distintas son
 * en realidad los mismos datos.
 */
@Service
public class DatasetService {

    private static final int MAX_PAGE_SIZE = 200;
    private static final int DEFAULT_SEED_ROWS = 400;

    private final DatasetVersionRepository repository;

    public DatasetService(DatasetVersionRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public PageResponse<DatasetResponse> list(int page, int size) {
        Pageable pageable = PageRequest.of(QueryParams.page(page), QueryParams.size(size, MAX_PAGE_SIZE));
        Page<DatasetVersion> result = repository.findAllByOrderByCreatedAtDesc(pageable);
        return PageResponse.of(result, DatasetResponse::from);
    }

    @Transactional(readOnly = true)
    public DatasetResponse get(Long id) {
        return repository.findById(id).map(DatasetResponse::from)
                .orElseThrow(() -> ApiException.notFound("DATASET_NOT_FOUND",
                        "La version de dataset no existe."));
    }

    /**
     * Registra una version de dataset.
     *
     * <p>El checksum se calcula <strong>servidor</strong> a partir de la carga
     * util, nunca se acepta el que declara el cliente: aceptar un checksum
     * declarado convertiria la verificacion de integridad en decorativa.
     */
    @Transactional
    public DatasetResponse register(CreateDatasetRequest request, Long userId) {
        String symbol = QueryParams.symbol(request.symbol());
        String version = request.version() == null || request.version().isBlank()
                ? defaultVersion() : request.version().trim();
        String source = request.source() == null || request.source().isBlank()
                ? "manual" : request.source().trim();

        if (repository.findBySymbolAndVersion(symbol, version).isPresent()) {
            throw ApiException.conflict("DATASET_VERSION_EXISTS",
                    "Ya existe una version de dataset con ese identificador.");
        }

        long rows = request.rows() == null || request.rows() <= 0 ? DEFAULT_SEED_ROWS : request.rows();
        LocalDate last = request.lastDate() == null ? LocalDate.now() : request.lastDate();
        LocalDate first = request.firstDate() == null ? last.minusDays(rows - 1) : request.firstDate();
        if (last.isBefore(first)) {
            throw ApiException.badRequest("INVALID_DATE_RANGE",
                    "La fecha final no puede ser anterior a la inicial.");
        }

        DatasetVersion dataset = new DatasetVersion();
        dataset.setSymbol(symbol);
        dataset.setVersion(version);
        dataset.setSource(source);
        dataset.setRowsCount(rows);
        dataset.setFirstDate(first);
        dataset.setLastDate(last);
        dataset.setCreatedBy(userId);
        dataset.setStorageUri(request.storageUri());
        dataset.setProvenance(provenance(request, rows, first, last));
        // El checksum se deriva de la carga util, de modo que dos registros con el
        // mismo contenido producen siempre el mismo valor.
        dataset.setChecksumSha256(sha256(dataset));
        return DatasetResponse.from(repository.save(dataset));
    }

    private Map<String, Object> provenance(CreateDatasetRequest request, long rows,
                                          LocalDate first, LocalDate last) {
        Map<String, Object> provenance = new LinkedHashMap<>();
        provenance.put("registeredBy", "api");
        provenance.put("declaredRows", rows);
        provenance.put("firstDate", first.toString());
        provenance.put("lastDate", last.toString());
        provenance.put("interval", request.interval() == null ? "1d" : request.interval());
        if (request.notes() != null && !request.notes().isBlank()) {
            provenance.put("notes", request.notes().trim());
        }
        return provenance;
    }

    private static String defaultVersion() {
        return "v" + System.currentTimeMillis();
    }

    /** Checksum determinista del contenido declarado del dataset. */
    private static String sha256(DatasetVersion dataset) {
        String canonical = String.join("|",
                dataset.getSymbol(),
                dataset.getVersion(),
                dataset.getSource(),
                Long.toString(dataset.getRowsCount()),
                dataset.getFirstDate().toString(),
                dataset.getLastDate().toString());
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            // SHA-256 es obligatoria en toda JRE conforme; si falta, el proceso
            // no puede garantizar integridad y debe fallar de forma ruidosa.
            throw new IllegalStateException("SHA-256 no disponible en esta JVM", ex);
        }
    }

    /**
     * Tope de filas por version de dataset. El rango de fechas se deriva de
     * {@code rows} con {@code lastDate.minusDays(rows - 1)}: sin este limite,
     * un {@code rows} enorme lanzaba {@code DateTimeException} al restar dias y la
     * peticion respondia 500 en lugar de 400.
     */
    public static final long MAX_ROWS = 10_000L;

    /**
     * Cuerpo de registro. Solo puede declarar metadatos: este servicio no ingerta
     * velas. La ingesta de verdad es trabajo asincrono del pipeline de ML.
     *
     * <p>Las restricciones replican los anchos de las columnas de
     * {@code dataset_versions} (VARCHAR(16)/(64)/(32), JSONB, TEXT) y la
     * restriccion {@code ck_dataset_rows > 0}. Estaban declaradas solo en el
     * esquema: sin ellas, un simbolo de 40 caracteres o un {@code rows} negativo
     * se traducian en un error de base de datos y un 500, no en un 400 con el
     * campo culpable.
     */
    public record CreateDatasetRequest(
            @jakarta.validation.constraints.NotBlank
            @jakarta.validation.constraints.Size(max = 16)
            @jakarta.validation.constraints.Pattern(regexp = "[A-Za-z0-9._-]+",
                    message = "solo admite letras, digitos, punto, guion y guion bajo")
            String symbol,

            @jakarta.validation.constraints.NotBlank
            @jakarta.validation.constraints.Size(max = 64)
            String version,

            @jakarta.validation.constraints.NotBlank
            @jakarta.validation.constraints.Size(max = 32)
            String source,

            @jakarta.validation.constraints.Size(max = 16)
            String interval,

            @jakarta.validation.constraints.NotNull
            @jakarta.validation.constraints.Min(1)
            @jakarta.validation.constraints.Max(MAX_ROWS)
            Long rows,

            LocalDate firstDate,
            LocalDate lastDate,

            @jakarta.validation.constraints.Size(max = 2048)
            String storageUri,

            @jakarta.validation.constraints.Size(max = 2000)
            String notes
    ) {
    }

    /**
     * Version de dataset publicada. {@code name} e {@code interval} se derivan
     * aqui para que el cliente no tenga que componer una etiqueta legible a partir
     * de simbolo y rango.
     */
    public record DatasetResponse(
            String id,
            String name,
            String symbol,
            String version,
            String interval,
            long rows,
            String checksum,
            String source,
            String from,
            String to,
            String storageUri,
            Map<String, Object> provenance,
            String createdBy,
            java.time.Instant createdAt
    ) {
        static DatasetResponse from(DatasetVersion dataset) {
            Object interval = dataset.getProvenance() == null ? null
                    : dataset.getProvenance().get("interval");
            return new DatasetResponse(
                    Ids.of(dataset.getId()),
                    dataset.getSymbol() + " " + dataset.getVersion(),
                    dataset.getSymbol(),
                    dataset.getVersion(),
                    interval == null ? "1d" : String.valueOf(interval),
                    dataset.getRowsCount(),
                    dataset.getChecksumSha256(),
                    dataset.getSource(),
                    dataset.getFirstDate().toString(),
                    dataset.getLastDate().toString(),
                    dataset.getStorageUri(),
                    dataset.getProvenance() == null ? Map.of() : dataset.getProvenance(),
                    Ids.of(dataset.getCreatedBy()),
                    dataset.getCreatedAt());
        }
    }
}
