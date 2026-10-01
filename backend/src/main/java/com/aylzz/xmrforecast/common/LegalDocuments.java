package com.aylzz.xmrforecast.common;

import java.util.List;

/**
 * Documentos legales publicados, sus versiones vigentes y el canal de contacto.
 *
 * <p><strong>Una sola fuente de verdad (R-43).</strong> La version que se guarda
 * en {@code consent_records}, la que sirve {@code GET /api/v1/meta/legal} y la
 * que el frontend muestra en cada pagina salen todas de aqui. Si la frontend
 * tuviera su propia copia escrita a mano, un cambio de politica podria quedar
 * aceptado bajo una version que el backend no conoce, y la prueba de
 * aceptacion dejaria de ser util.
 *
 * <p><strong>Quien debe revisar esto.</strong> Estos textos se redactaron para
 * el servicio real que existe hoy (web SaaS de analisis predictivo de XMR,
 * responsable AyLZz Software Solutions, usuarios en Colombia y en el resto del
 * mundo) y siguen el marco colombiano (Ley 1581 de 2012, Decreto 1074 de 2015)
 * mas las normas que puedan aplicar al usuario. No son asesoria juridica: la
 * politica del propio documento lo advierte y debe ser revisada por un abogado
 * colombiano antes de operar con usuarios reales.
 */
public final class LegalDocuments {

    /** Version compartida de todos los documentos publicados hoy. */
    public static final String CURRENT_VERSION = "2026-10-01";

    /** Fecha de entrada en vigor, igual a la version por convencion. */
    public static final String EFFECTIVE_DATE = "2026-10-01";

    /**
     * Canal de atencion a titulares. Es el buzon operativo del servicio; se
     * puede sustituir con {@code LEGAL_CONTACT_EMAIL} sin tocar codigo.
     */
    public static final String DEFAULT_CONTACT_EMAIL = "aylzz.software.solutions@gmail.com";

    private LegalDocuments() {
    }

    /** Un documento publicado y su ruta publica. */
    public record LegalDocument(String key, String title, String path,
                                String version, String effectiveDate) {
    }

    /** Respuesta de {@code GET /api/v1/meta/legal}. */
    public record LegalResponse(List<LegalDocument> documents, String contactEmail) {
    }

    /** Los documentos en el orden en que los enlaza el pie de pagina. */
    public static List<LegalDocument> published() {
        return List.of(
                new LegalDocument("terms", "Terminos y condiciones", "/terms",
                        CURRENT_VERSION, EFFECTIVE_DATE),
                new LegalDocument("privacy", "Politica de privacidad", "/privacy",
                        CURRENT_VERSION, EFFECTIVE_DATE),
                new LegalDocument("dataPolicy",
                        "Politica de tratamiento de datos personales", "/data-policy",
                        CURRENT_VERSION, EFFECTIVE_DATE),
                new LegalDocument("cookies", "Politica de cookies", "/cookies",
                        CURRENT_VERSION, EFFECTIVE_DATE),
                new LegalDocument("legalNotice", "Aviso legal de analisis predictivo",
                        "/legal-notice", CURRENT_VERSION, EFFECTIVE_DATE));
    }
}
