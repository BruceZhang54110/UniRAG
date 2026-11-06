package com.unirag.ingestion;

import com.unirag.ingestion.support.BinaryTestDocuments;
import org.apache.opendal.Operator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 022 US2 验收（spec.md US2，FR-002/FR-004a，SC-002；clarify Q1）：确定性解析失败
 * 直接终态——不占重试预算、零数据残留。租户码全局唯一：{@code pdfFl}。
 */
class PdfDocxFailureIT extends AbstractIngestionIT {

    private static final String TENANT = "pdfFl";
    private static final String DOC = "pdf-failure-doc";
    private static final String TABLE = "embeddings_pdffl";

    @Autowired JdbcTemplate jdbc;
    @Autowired Operator documentStorageOperator;
    @Autowired DocumentIngestionWorker worker;

    private long tenantId;
    private long documentId;

    @BeforeEach
    void setUp() {
        cleanupTenant(TENANT, TABLE);
        tenantId = insertTenant(TENANT);
        insertStoreConfig(tenantId, "default", TABLE, 1024);
        insertPipeline(tenantId, "main", 500, 50, "default", "default");
        registrar.loadAndRegister();
        stubRouter(TENANT, Map.of("default", new DeterministicEmbeddingModel(1024)),
                Map.of("default", storeBean(TENANT, "default")));
        documentId = jdbc.queryForObject("""
                INSERT INTO document (tenant_id, doc_name, title, content_fingerprint, status, chunk_count, version, deleted)
                VALUES (?, ?, ?, ?, 'INGESTING', 0, 1, FALSE) RETURNING id""",
                Long.class, tenantId, DOC, DOC, fingerprint("failure doc"));
    }

    @Test
    void xlsxRenamedToDocxFailsTerminallyWithoutRetry() throws Exception {
        byte[] xlsx = BinaryTestDocuments.xlsxBytes();
        long versionId = enqueueVersion(1, "表格.docx", xlsx);

        assertThat(worker.runBatch()).isEqualTo(1);

        Map<String, Object> job = jobRow(versionId);
        assertThat(job.get("status")).isEqualTo("FAILED");
        assertThat(job.get("last_error_code")).isEqualTo("PARSE_FAILED");
        // clarify Q1：attempt 直接置 maxAttempts 终态（status=FAILED 即不再被认领重试），不占重试预算
        assertThat(job.get("extracted_chars")).isNull();
        // SC-002：切片与向量零残留
        assertThat(count("SELECT count(*) FROM document_chunk WHERE document_version_id=?", versionId)).isZero();
        assertThat(count("SELECT count(*) FROM " + TABLE + " WHERE metadata->>'document_version_id'=?::text",
                String.valueOf(versionId))).isZero();
    }

    @Test
    void scannedPdfFailsWithEmptyExtractedText() throws Exception {
        byte[] scanned = BinaryTestDocuments.emptyPdf();
        long versionId = enqueueVersion(1, "扫描件.pdf", scanned);

        assertThat(worker.runBatch()).isEqualTo(1);

        Map<String, Object> job = jobRow(versionId);
        assertThat(job.get("status")).isEqualTo("FAILED");
        assertThat(job.get("last_error_code")).isEqualTo("EMPTY_EXTRACTED_TEXT");
        assertThat(count("SELECT count(*) FROM document_chunk WHERE document_version_id=?", versionId)).isZero();
    }

    private Map<String, Object> jobRow(long versionId) {
        return jdbc.queryForMap(
                "SELECT status, last_error_code, next_attempt_at, extracted_chars "
                        + "FROM document_ingestion_job WHERE document_version_id = ?", versionId);
    }

    private long enqueueVersion(int version, String fileName, byte[] blob) {
        String fp = fingerprint(blob);
        String path = TENANT + "/" + DOC + "/v" + version;
        long versionId = jdbc.queryForObject("""
                INSERT INTO document_ingestion_version
                    (tenant_id, document_id, version, content_fingerprint, storage_path, file_name, mime_type, size_bytes, active)
                VALUES (?, ?, ?, ?, ?, ?, 'application/octet-stream', ?, FALSE) RETURNING id""",
                Long.class, tenantId, documentId, version, fp, path, fileName, blob.length);
        writeWithRetry(path, blob);
        jdbc.update("""
                INSERT INTO document_ingestion_job
                    (tenant_id, document_id, document_version_id, document_version, content_fingerprint,
                     status, stage, attempt_count, max_attempts, next_attempt_at)
                VALUES (?, ?, ?, ?, ?, 'QUEUED', 'UPLOADED', 0, 3, CURRENT_TIMESTAMP)""",
                tenantId, documentId, versionId, version, fp);
        return versionId;
    }

    private void writeWithRetry(String path, byte[] bytes) {
        for (int attempt = 1; ; attempt++) {
            try {
                documentStorageOperator.write(path, bytes);
                return;
            } catch (RuntimeException e) {
                if (attempt >= 6) {
                    throw e;
                }
                try {
                    Thread.sleep(100);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    private long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    private String fingerprint(String content) {
        return com.unirag.ingestion.support.DocumentFingerprint.sha256(content);
    }

    private String fingerprint(byte[] content) {
        return com.unirag.ingestion.support.DocumentFingerprint.sha256(content);
    }
}
